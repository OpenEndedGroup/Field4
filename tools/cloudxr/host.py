#!/usr/bin/env python3
"""Experimental Linux host for Apple's Foveated Streaming session protocol.

Uses NVIDIA's public C service API; never changes the global OpenXR runtime.
Protocol: https://developer.apple.com/documentation/foveatedstreaming/establishing-foveated-streaming-sessions-with-apple-vision-pro
"""
import argparse
import asyncio
import base64
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import ssl
import struct
import subprocess
import threading
import time
import uuid

from field_lifecycle import stop_managed_field

MAX_MESSAGE = 65536


def event(name, **fields):
    print(json.dumps({"event": name, **fields}), flush=True)


async def receive(reader):
    size = struct.unpack('<I', await reader.readexactly(4))[0]
    if not 0 < size <= MAX_MESSAGE:
        raise ValueError('Session message exceeds size limit')
    value = json.loads(await reader.readexactly(size))
    if not isinstance(value, dict):
        raise ValueError('Expected a JSON object')
    return value


async def send(writer, name, session, **fields):
    payload = json.dumps(dict(Event=name, SessionID=session, **fields)).encode()
    writer.write(struct.pack('<I', len(payload)) + payload)
    await writer.drain()


class Identity:
    """Stable certificate/server identity and deterministic per-device bearer tokens."""
    def __init__(self, directory):
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(directory, 0o700)
        self.directory = directory
        identity = directory / 'identity.json'
        if not identity.exists():
            fd = os.open(identity, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as f:
                json.dump(dict(server=uuid.uuid4().hex, secret=secrets.token_hex(32)), f)
        data = json.loads(identity.read_text())
        self.server = data['server']
        self.secret = bytes.fromhex(data['secret'])
        cert, key = directory / 'certificate.pem', directory / 'private-key.pem'
        # The visionOS system provider rejects lifetimes >47 days, even with a matching pin.
        # Refresh on startup when expired/near expiry; keep the endpoint ID and bearer secret.
        if not key.exists() or not self.certificate_current(cert):
            pending = directory / 'certificate.next.pem'
            command = ['openssl', 'req', '-x509', '-out', str(pending), '-days', '30',
                       '-subj', '/CN=Field CloudXR',
                       '-addext', 'basicConstraints=critical,CA:FALSE',
                       '-addext', 'keyUsage=critical,digitalSignature,keyEncipherment',
                       '-addext', 'extendedKeyUsage=serverAuth']
            if key.exists():
                command += ['-key', str(key)]
            else:
                command += ['-newkey', 'rsa:2048', '-nodes', '-keyout', str(key)]
            subprocess.run(command, check=True, capture_output=True)
            pending.replace(cert)
        os.chmod(key, 0o600)
        self.cert, self.key = cert.read_text(), key.read_text()
        self.digest = hashlib.sha256(ssl.PEM_cert_to_DER_cert(self.cert)).hexdigest().upper()

    @staticmethod
    def certificate_current(cert):
        if not cert.is_file():
            return False
        result = subprocess.run(['openssl', 'x509', '-in', str(cert), '-noout', '-dates'],
                                capture_output=True, text=True)
        if result.returncode:
            return False
        try:
            dates = dict(line.split('=', 1) for line in result.stdout.splitlines())
            start = ssl.cert_time_to_seconds(dates['notBefore'])
            end = ssl.cert_time_to_seconds(dates['notAfter'])
        except (KeyError, ValueError):
            return False
        now = time.time()
        return start <= now and end - start <= 47 * 86400 and end - now > 3 * 86400

    def token(self, client):
        return base64.b64encode(hmac.digest(self.secret, client.encode(), 'sha256')).decode()


class CloudXRFailure(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__(f'CloudXR service API returned {code}')


class Runtime:
    """Run the SDK in a native child so its private OpenSSL never enters Python's address space."""
    def __init__(self, directory, identity, logs):
        logs.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env['NV_CXR_OUTPUT_DIR'] = str(logs)
        env['LD_LIBRARY_PATH'] = str(directory) + ':' + env.get('LD_LIBRARY_PATH', '')
        self.started = False
        self.lock = threading.RLock()
        binary = Path.home()/'.cache/field-cloudxr/runtime-helper'
        if not binary.is_file():
            raise RuntimeError('Build runtime-helper first; see tools/cloudxr/README.md')
        read_fd, write_fd = os.pipe()
        self.log = (logs/'native-runtime.log').open('ab')
        self.process = subprocess.Popen([str(binary), str(directory), str(write_fd)],
            stdin=subprocess.PIPE, stdout=self.log, stderr=subprocess.STDOUT,
            pass_fds=(write_fd,), env=env, text=True)
        os.close(write_fd)
        self.replies = os.fdopen(read_fd)
        try:
            self.request('init', certificate=str(identity.directory/'certificate.pem'),
                key=str(identity.directory/'private-key.pem'), token=secrets.token_urlsafe(32))
        except Exception:
            self.close()
            raise

    def request(self, command, **fields):
        with self.lock:
            self.process.stdin.write(json.dumps(dict(command=command, **fields)) + '\n')
            self.process.stdin.flush()
            line = self.replies.readline()
            if not line:
                raise RuntimeError('Native CloudXR helper exited; inspect native-runtime.log')
            result = json.loads(line)
            if result['code'] != 0:
                raise CloudXRFailure(result['code'])
            return result

    def token(self, token):
        self.request('token', token=token)

    def start(self):
        with self.lock:
            if not self.started:
                self.request('start')
                self.started = True
                event('runtime-started')

    def stop(self):
        with self.lock:
            if self.started:
                self.request('stop')
                self.started = False
                event('runtime-stopped')

    def poll(self):
        with self.lock:
            return self.request('event')['event'] if self.started else 0

    def close(self):
        try:
            if self.process.poll() is None:
                self.request('close')
        finally:
            self.process.stdin.close()
            self.process.wait(timeout=15)
            self.replies.close()
            self.log.close()
            self.started = False


class Host:
    def __init__(self, runtime, identity, args):
        self.runtime, self.identity, self.args = runtime, identity, args
        self.client = self.session = None
        self.connection_lock = asyncio.Lock()
        self.qr = None
        self.status = 'Waiting for a Vision Pro'
        self.launch_process = None

    async def connection(self, reader, writer):
        try:
            await asyncio.wait_for(self.connection_lock.acquire(), timeout=5)
        except TimeoutError:
            writer.close()
            await writer.wait_closed()
            return
        try:
            try:
                while True:
                    # Established sessions can be quiet indefinitely; doffing is not a timeout.
                    timeout = None if self.status in {'CONNECTED', 'PAUSED'} else 120
                    message = await asyncio.wait_for(receive(reader), timeout=timeout)
                    kind = message.get('Event')
                    session = message.get('SessionID', '')
                    if not isinstance(session, str) or not re.fullmatch(r'[\w-]{1,128}', session):
                        raise ValueError('Invalid session ID')
                    if kind == 'RequestConnection':
                        client = message.get('ClientID', '')
                        if (message.get('ProtocolVersion') != '1' or
                            message.get('StreamingProvider') != 'com.nvidia.CloudXR' or
                            str(message.get('StreamingProviderVersion', '')).split('.')[0] != '6' or
                            not isinstance(client, str) or not re.fullmatch(r'[\w-]{1,128}', client)):
                            raise ValueError('Unsupported streaming protocol or client ID')
                        if self.client and client != self.client:
                            raise ValueError('One headset per host; disconnect the active session first')
                        self.client, self.session = client, session
                        self.runtime.token(self.identity.token(client))
                        await send(writer, 'AcknowledgeConnection', session,
                                   ServerID=self.identity.server, CertificateFingerprint=self.identity.digest)
                    elif session != self.session:
                        raise ValueError('Session ID does not match the current connection')
                    elif kind == 'RequestBarcodePresentation':
                        payload = json.dumps(dict(token=self.identity.token(self.client), digest=self.identity.digest))
                        # Pass the secret on stdin; it never appears in process arguments or logs.
                        self.qr = subprocess.run(['qrencode', '-t', 'PNG', '-o', '-', '-s', '8'],
                                                 input=payload.encode(), capture_output=True, check=True).stdout
                        self.status = 'Scan the pairing QR code with Vision Pro'
                        await send(writer, 'AcknowledgeBarcodePresentation', session)
                        event('pairing-code-ready', url=f'http://127.0.0.1:{self.args.qr_port}/')
                    elif kind == 'SessionStatusDidChange':
                        status = message.get('Status')
                        if status not in {'WAITING', 'CONNECTING', 'CONNECTED', 'PAUSED', 'DISCONNECTED'}:
                            raise ValueError('Unknown session status')
                        self.qr = None
                        self.status = status
                        event('session-status', status=status)
                        if status == 'WAITING':
                            await asyncio.to_thread(self.runtime.start)
                            await send(writer, 'MediaStreamIsReady', session)
                        elif status == 'DISCONNECTED':
                            await self.stop_field()
                            await asyncio.to_thread(self.runtime.stop)
                            self.client = self.session = None
                            break
                        # PAUSED deliberately keeps the runtime and Field alive across headset doffing.
                    else:
                        raise ValueError('Unsupported session message')
            except (asyncio.IncompleteReadError, ConnectionError, TimeoutError):
                event('management-connection-closed', runtime_retained=self.runtime.started)
            except Exception as error:
                # Log types, not untrusted packet contents, credentials or native exception details.
                event('management-error', error=type(error).__name__, code=getattr(error, 'code', None))
                if self.session:
                    try:
                        await send(writer, 'RequestSessionDisconnect', self.session)
                    except ConnectionError:
                        pass
            finally:
                self.qr = None
                if not self.runtime.started:
                    self.client = self.session = None
                writer.close()
                await writer.wait_closed()
        finally:
            self.connection_lock.release()

    async def stop_field(self):
        unit = getattr(self.args, 'field_unit', None)
        if unit:
            await asyncio.to_thread(stop_managed_field, unit, self.args.field_port)
            event('managed-field-stopped', unit=unit)

    async def runtime_events(self):
        while True:
            kind = await asyncio.to_thread(self.runtime.poll)
            if kind:
                event('runtime-event', kind=kind)
                if kind == 1 and self.args.on_client_connected:
                    if self.launch_process is None or self.launch_process.poll() is not None:
                        # This is a local administrator-supplied argv, never peer-supplied shell input.
                        self.launch_process = subprocess.Popen(self.args.on_client_connected)
            await asyncio.sleep(.1)

    def http_handler(self):
        host = self
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == '/status':
                    body = json.dumps(dict(status=host.status, pairing=host.qr is not None)).encode()
                    content_type = 'application/json'
                elif self.path == '/pairing.png':
                    body, content_type = host.qr, 'image/png'
                    if body is None:
                        self.send_error(404)
                        return
                elif self.path == '/':
                    body = ('''<!doctype html><meta charset="utf-8"><title>Field CloudXR pairing</title>
<style>body{font:20px system-ui;background:#171717;color:white;text-align:center}img{max-width:80vw}</style>
<h1>Field CloudXR</h1><p>Scan this code in the Vision Pro pairing dialog.</p>
<img id="qr" hidden><p id="status"></p><script>
setInterval(async()=>{
const s=await(await fetch('/status')).json();
document.querySelector('#status').textContent=s.status;
const image=document.querySelector('#qr');image.hidden=!s.pairing;
if(s.pairing)image.src='/pairing.png?t='+Date.now();
else image.removeAttribute('src');
},1000);
</script>''').encode()
                    content_type = 'text/html; charset=utf-8'
                elif self.path.startswith('/pairing.png?'):
                    body, content_type = host.qr, 'image/png'
                    if body is None:
                        self.send_error(404)
                        return
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header('Content-Type', content_type)
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass
        return Handler


async def serve(args):
    identity = Identity(args.state)
    runtime = Runtime(args.runtime, identity, args.state / 'logs')
    host = Host(runtime, identity, args)
    http = ThreadingHTTPServer(('127.0.0.1', args.qr_port), host.http_handler())
    threading.Thread(target=http.serve_forever, daemon=True).start()
    publisher = None
    try:
        server = await asyncio.start_server(host.connection, args.bind, args.port)
        publisher = subprocess.Popen(['avahi-publish-service', 'Field CloudXR',
            '_apple-foveated-streaming._tcp', str(args.port), 'Application-Identifier=' + args.bundle_id],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        done = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, done.set)
        poller = asyncio.create_task(host.runtime_events())
        event('host-ready', port=args.port, bundle_id=args.bundle_id,
              pairing_page=f'http://127.0.0.1:{args.qr_port}/')
        async with server:
            waiter = asyncio.create_task(done.wait())
            completed, _ = await asyncio.wait([waiter, poller], return_when=asyncio.FIRST_COMPLETED)
            if poller in completed:
                poller.result()
            poller.cancel()
            waiter.cancel()
    finally:
        if publisher:
            publisher.terminate()
            publisher.wait(timeout=5)
        http.shutdown()
        http.server_close()
        # Runtime teardown invalidates every application instance: retire Field first.
        await host.stop_field()
        await asyncio.to_thread(runtime.close)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--runtime', type=Path, default=Path.home()/'.local/opt/cloudxr-6.2.3')
    p.add_argument('--state', type=Path, default=Path.home()/'.local/state/field-cloudxr')
    p.add_argument('--bundle-id', default='org.field4.CloudXRClient')
    p.add_argument('--bind', default='0.0.0.0')
    p.add_argument('--port', type=int, default=55000)
    p.add_argument('--qr-port', type=int, default=55001)
    p.add_argument('--field-unit', help='Optional owned Field unit to stop before runtime teardown')
    p.add_argument('--field-port', type=int, default=9492)
    p.add_argument('--on-client-connected', nargs=argparse.REMAINDER,
                   help='Optional local argv to launch once the real CloudXR client connects')
    args = p.parse_args()
    asyncio.run(serve(args))


if __name__ == '__main__':
    main()
