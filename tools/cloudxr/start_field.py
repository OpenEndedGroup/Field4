#!/usr/bin/env python3
"""Start this checkout's Field with process-local CloudXR selection, then launch its VR document."""
import argparse
import json
import os
import signal
from pathlib import Path
import socket
import subprocess
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--runtime', type=Path, default=Path.home()/'.local/opt/cloudxr-6.2.3')
    p.add_argument('--workspace', type=Path, default=Path.home()/'.local/state/field-cloudxr/workspace')
    p.add_argument('--document', default='cloudxr_one')
    p.add_argument('--port', type=int, default=9492, help='Dedicated Field server port')
    p.add_argument('--launch-macro', help='Defaults to <document>_launch')
    p.add_argument('--desktop', action='store_true', help='Test stereo rendering without a headset')
    p.add_argument('--ide', action=argparse.BooleanOptionalAction, default=False,
                   help='Optionally include the Vulkan IDE panel; the default is scene-only')
    p.add_argument('--lan-editor', action='store_true', help='Expose HTTPS Field on the LAN for the native visionOS editor')
    args = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    if not 1024 <= args.port <= 45535:
        p.error('--port must leave room for the HTTP mirror and Electron CDP ports')
    # A JVM's OpenXR/Vulkan selection is fixed at its first instance. Never attach to another run.
    with socket.socket() as sock:
        if sock.connect_ex(('127.0.0.1', args.port)) == 0:
            raise SystemExit(f'Field is already running on {args.port}; stop it before selecting CloudXR.')
    if not (args.workspace/args.document/'metadata.edn').is_file():
        raise SystemExit('Requested document does not exist')
    env = os.environ.copy()
    env['PORT'] = str(args.port)
    if args.lan_editor:
        env['AFIELD_BIND_HOST'] = '0.0.0.0'
    env['FIELD_CLOUDXR_DESKTOP'] = '1' if args.desktop else '0'
    env['FIELD_CLOUDXR_IDE'] = '1' if args.ide else '0'
    env['FIELD_VR_INPUT'] = 'desktop'
    env['XR_RUNTIME_JSON'] = str(args.runtime/'openxr_cloudxr.json')
    env['LD_LIBRARY_PATH'] = str(args.runtime) + ':' + env.get('LD_LIBRARY_PATH', '')
    env['AFIELD_OPENXR_VALIDATE'] = 'false'
    env['AFIELD_FIELD_SH_NO_TERMINAL'] = '1'
    folder = Path.home()/'.local/state/field-cloudxr/logs'
    folder.mkdir(parents=True, exist_ok=True)
    with (folder/'field.log').open('ab') as log:
        process = subprocess.Popen([str(root/'field.sh'), '--offline', '--workspace', str(args.workspace),
                                    '--document', args.document], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(180):
        if process.poll() is not None:
            raise SystemExit('Field launcher exited; inspect field.log')
        with socket.socket() as sock:
            ready = sock.connect_ex(('127.0.0.1', args.port)) == 0
        if ready:
            break
        time.sleep(1)
    else:
        raise SystemExit('Field did not start in 180 seconds; inspect field.log')
    # A listening server can still be hydrating the requested document. Launch only after
    # a read-only backdoor status proves the workspace/document and nonempty box graph.
    backdoor = ['uv', 'run', '--project', str(root), '--group', 'backdoor', 'python',
                str(root/'tools/field_backdoor.py'), '--json', '--port', str(args.port),
                '--attach-existing', '--document', args.document]
    deadline = time.monotonic() + 90
    while True:
        status = subprocess.run(backdoor + ['--command', 'status'], cwd=root, env=env,
                                capture_output=True, text=True, timeout=45)
        if status.returncode == 0:
            data = json.loads(status.stdout)['data']
            if (data.get('serverWorkspace') != str(args.workspace.resolve()) or
                    data.get('documentName') != args.document):
                raise SystemExit('Field workspace/document identity does not match the launch request')
            if data.get('boxCount', 0) > 0:
                break
        if process.poll() is not None or time.monotonic() >= deadline:
            raise SystemExit('Document did not become ready; inspect Field startup before launching')
        time.sleep(1)

    # The repo backdoor checks the actual workspace and waits for the macro's compilation verdict.
    with (folder/'scene-launch.jsonl').open('ab') as log:
        result = subprocess.run(['uv', 'run', '--project', str(root), '--group', 'backdoor', 'python',
            str(root/'tools/field_backdoor.py'), '--json', '--port', str(args.port), '--attach-existing',
            '--document', args.document, '--command', 'macro run ' + (args.launch_macro or args.document + '_launch')],
            cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        # Keep the editor available to inspect/fix a live shader or box compile error.
        print('Scene launch failed; Field stays open for repair. Inspect scene-launch.jsonl and field.log.', flush=True)
    else:
        print(f'Field {args.document} launched in {"desktop" if args.desktop else "CloudXR"} mode.', flush=True)
    # Keep this separately managed unit alive. A stop gives the launcher/JVM time to save normally.
    def stop_field(signum, frame):
        process.send_signal(signal.SIGINT)
    signal.signal(signal.SIGINT, stop_field)
    signal.signal(signal.SIGTERM, stop_field)
    process.wait()


if __name__ == '__main__':
    main()
