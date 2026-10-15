#!/usr/bin/env python3
"""Run the real JVM export and Electron canvas probe on the caller's display.
Example: uv run --group backdoor python tools/electron_dmabuf_probe/run.py /tmp/probe-xorg --ozone-platform=x11
A fresh evidence directory is mandatory; results from different sessions cannot overwrite one another.
"""
from pathlib import Path
import os
import json
import shlex
import subprocess
import sys
import time

repo = Path(__file__).resolve().parents[2]
if len(sys.argv) < 2:
    raise SystemExit(__doc__)
directory = Path(sys.argv[1]).resolve()
directory.mkdir(parents=True, exist_ok=False)
env = dict(os.environ, FIELD_DMABUF_PROBE_DIR=str(directory))
electron = repo / 'electron/node_modules/electron/dist/electron'
command = shlex.join(['run-jvm-main', 'afield.media.stream.LinuxElectronImportProbe',
                      '--arg', str(directory), '--timeout', '90'])
with (directory / 'electron.log').open('w') as electron_log, (directory / 'jvm.log').open('w') as jvm_log:
    consumer = subprocess.Popen([str(electron), str(repo / 'tools/electron_dmabuf_probe/main.js'),
                                 *sys.argv[2:]], cwd=repo, env=env, stdout=electron_log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 15
        while not (directory / 'fds.sock').exists():
            if consumer.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError(f'Electron failed to listen; inspect {directory / "electron.log"}')
            time.sleep(0.05)
        producer = subprocess.run(['uv', 'run', '--project', str(repo), '--group', 'backdoor',
                                  'python', str(repo / 'tools/field_backdoor.py'), '--json',
                                  '--command', command], cwd=repo, env=env, stdout=jvm_log, stderr=subprocess.STDOUT)
        result = directory / 'result.json'
        if result.exists():
            print(result.read_text())
        else:
            raise RuntimeError(f'No canvas verdict; inspect {directory / "jvm.log"}')
        raise SystemExit(producer.returncode or (0 if json.loads(result.read_text()).get("ok") else 1))
    finally:
        if consumer.poll() is None:
            consumer.terminate()
            try:
                consumer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                consumer.kill()
                consumer.wait()
