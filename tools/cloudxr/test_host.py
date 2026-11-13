"""Protocol unit checks; --integration additionally exercises a running REAL SDK host.

The integration peer speaks Apple's management protocol. It is not a Vision Pro,
so a passing result does not establish video, tracking, foveation, or device pairing.
"""
import asyncio
import hashlib
import http.client
import json
import socket
import ssl
import struct
import subprocess
import tempfile
from pathlib import Path
import sys
import unittest
import uuid
from host import Host, Identity, receive
from field_lifecycle import stop_managed_field
from types import SimpleNamespace
from unittest.mock import patch


class Framing(unittest.IsolatedAsyncioTestCase):
    async def test_fragmented_utf8_frame(self):
        value = {'Event': 'RequestConnection', 'name': 'Field · light'}
        payload = json.dumps(value, ensure_ascii=False).encode()
        reader = asyncio.StreamReader()
        for byte in struct.pack('<I', len(payload)) + payload:
            reader.feed_data(bytes([byte]))
        self.assertEqual(await receive(reader), value)

    async def test_oversized_frame(self):
        reader = asyncio.StreamReader()
        reader.feed_data(struct.pack('<I', 65537))
        with self.assertRaises(ValueError):
            await receive(reader)

    async def test_truncated_payload(self):
        reader = asyncio.StreamReader()
        reader.feed_data(struct.pack('<I', 10) + b'{')
        reader.feed_eof()
        with self.assertRaises(asyncio.IncompleteReadError):
            await receive(reader)


class Lifecycle(unittest.IsolatedAsyncioTestCase):
    async def exercise(self, status):
        calls = []

        class Runtime:
            started = False
            def token(self, value): pass
            def start(self):
                self.started = True
                calls.append('runtime-start')
            def stop(self):
                calls.append('runtime-stop')
                self.started = False

        class Writer:
            def write(self, value): pass
            async def drain(self): pass
            def close(self): pass
            async def wait_closed(self): pass

        reader = asyncio.StreamReader()
        messages = [dict(Event='RequestConnection', ProtocolVersion='1',
                         StreamingProvider='com.nvidia.CloudXR', StreamingProviderVersion='6',
                         ClientID='test-device', SessionID='test-session'),
                    dict(Event='SessionStatusDidChange', SessionID='test-session', Status='WAITING'),
                    dict(Event='SessionStatusDidChange', SessionID='test-session', Status=status)]
        for message in messages:
            payload = json.dumps(message).encode()
            reader.feed_data(struct.pack('<I', len(payload)) + payload)
        reader.feed_eof()
        identity = SimpleNamespace(server='test-server', digest='0' * 64, token=lambda client: 'test-token')
        args = SimpleNamespace(field_unit='test-field.service', field_port=9492)
        host = Host(Runtime(), identity, args)
        with patch('host.stop_managed_field', side_effect=lambda unit, port: calls.append('field-stop')):
            await host.connection(reader, Writer())
        return calls, host

    async def test_disconnect_retires_field_before_invalidating_its_runtime(self):
        calls, host = await self.exercise('DISCONNECTED')
        self.assertEqual(calls, ['runtime-start', 'field-stop', 'runtime-stop'])
        self.assertIsNone(host.session)

    async def test_headset_pause_preserves_field_and_runtime(self):
        calls, host = await self.exercise('PAUSED')
        self.assertEqual(calls, ['runtime-start'])
        self.assertTrue(host.runtime.started)
        self.assertEqual(host.session, 'test-session')


class ManagedFieldOwnership(unittest.TestCase):
    def test_foreign_port_owner_is_never_signalled(self):
        replies = [SimpleNamespace(returncode=0, stdout='/user.slice/field.service'),
                   SimpleNamespace(returncode=0, stdout='12345\n')]
        with patch('field_lifecycle.subprocess.run', side_effect=replies), \
             patch('field_lifecycle.Path.read_text', return_value='0::/user.slice/other.service\n'), \
             patch('field_lifecycle.os.kill') as signal_process:
            with self.assertRaisesRegex(RuntimeError, 'outside field.service'):
                stop_managed_field('field.service', 9492)
            signal_process.assert_not_called()

    def test_unconfigured_unit_has_no_process_side_effects(self):
        with patch('field_lifecycle.subprocess.run') as run:
            stop_managed_field(None, 9492)
            run.assert_not_called()


class Certificates(unittest.TestCase):
    def test_short_lived_certificate_and_stable_pairing_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            first = Identity(root)
            self.assertTrue(Identity.certificate_current(root / 'certificate.pem'))
            second = Identity(root)
            self.assertEqual(first.digest, second.digest)
            self.assertEqual(first.server, second.server)
            self.assertEqual(first.token('device'), second.token('device'))

    def test_legacy_long_lived_certificate_is_replaced(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            first = Identity(root)
            key = (root / 'private-key.pem').read_bytes()
            subprocess.run(['openssl', 'req', '-x509', '-key', str(root / 'private-key.pem'),
                            '-out', str(root / 'certificate.pem'), '-days', '3650',
                            '-subj', '/CN=Field CloudXR'], check=True, capture_output=True)
            self.assertFalse(Identity.certificate_current(root / 'certificate.pem'))
            renewed = Identity(root)
            self.assertTrue(Identity.certificate_current(root / 'certificate.pem'))
            self.assertEqual(first.server, renewed.server)
            self.assertEqual(key, (root / 'private-key.pem').read_bytes())
            self.assertEqual(first.token('device'), renewed.token('device'))


def integration():
    def exact(sock, n):
        out = b''
        while len(out) < n:
            data = sock.recv(n - len(out))
            if not data:
                raise EOFError('peer closed')
            out += data
        return out

    def send(sock, data):
        payload = json.dumps(data).encode()
        for part in [struct.pack('<I', len(payload))[:2], struct.pack('<I', len(payload))[2:], payload]:
            sock.sendall(part)

    def recv(sock):
        return json.loads(exact(sock, struct.unpack('<I', exact(sock, 4))[0]))

    client, session = uuid.uuid4().hex, uuid.uuid4().hex
    with socket.create_connection(('127.0.0.1', 55000), timeout=15) as sock:
        send(sock, dict(Event='RequestConnection', ProtocolVersion='1',
                        StreamingProvider='com.nvidia.CloudXR', StreamingProviderVersion='6',
                        SessionID=session, ClientID=client))
        ack = recv(sock)
        assert ack['Event'] == 'AcknowledgeConnection' and ack['SessionID'] == session
        assert len(ack['CertificateFingerprint']) == 64
        send(sock, dict(Event='RequestBarcodePresentation', SessionID=session))
        assert recv(sock)['Event'] == 'AcknowledgeBarcodePresentation'
        page = http.client.HTTPConnection('127.0.0.1', 55001)
        page.request('GET', '/pairing.png')
        response = page.getresponse()
        assert response.status == 200 and response.read().startswith(b'\x89PNG')
        page.close()
        send(sock, dict(Event='SessionStatusDidChange', SessionID=session, Status='WAITING'))
        assert recv(sock)['Event'] == 'MediaStreamIsReady'
        # Only this test disables system CA validation; explicitly compare the live cert to its pin.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        with socket.create_connection(('127.0.0.1', 48322), timeout=10) as tcp:
            with context.wrap_socket(tcp, server_hostname='Field CloudXR') as tls:
                digest = hashlib.sha256(tls.getpeercert(binary_form=True)).hexdigest()
                assert digest == ack['CertificateFingerprint'].lower()
        send(sock, dict(Event='SessionStatusDidChange', SessionID=session, Status='PAUSED'))
    # A doffed device reconnects on a new TCP connection without rebuilding the native service.
    with socket.create_connection(('127.0.0.1', 55000), timeout=15) as sock:
        send(sock, dict(Event='SessionStatusDidChange', SessionID=session, Status='WAITING'))
        assert recv(sock)['Event'] == 'MediaStreamIsReady'
        send(sock, dict(Event='SessionStatusDidChange', SessionID=session, Status='DISCONNECTED'))
        assert sock.recv(1) == b''
    print('Real SDK: management handshake, QR image, TLS certificate pin, pause/reconnect and shutdown passed.')
    print('Vision Pro pairing/video/tracking remain untested.')


if __name__ == '__main__':
    if '--integration' in sys.argv:
        integration()
    else:
        unittest.main()
