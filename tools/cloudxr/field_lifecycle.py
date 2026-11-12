"""Close the explicitly managed Field before retiring the OpenXR runtime it uses."""
import os
from pathlib import Path
import signal
import subprocess
import time


def stop_managed_field(unit: str | None, port: int) -> None:
    """Only signal the port owner when systemd proves it belongs to the configured unit."""
    if not unit:
        return
    result = subprocess.run(['systemctl', '--user', 'show', unit, '--property=ControlGroup', '--value'],
                            capture_output=True, text=True, check=False)
    group = result.stdout.strip()
    if result.returncode or not group:
        return
    listeners = subprocess.run(['lsof', '-nP', f'-iTCP:{port}', '-sTCP:LISTEN', '-t'],
                               capture_output=True, text=True, check=False)
    pids = {int(pid) for pid in listeners.stdout.split()}
    for pid in pids:
        membership = Path(f'/proc/{pid}/cgroup').read_text().splitlines()
        if not any(line.split(':', 2)[-1] == group for line in membership):
            raise RuntimeError(f'Port {port} belongs to a process outside {unit}; refusing to stop it')
    for pid in pids:
        # Field's JVM shutdown hook flushes live edits and retires its OpenXR session.
        os.kill(pid, signal.SIGINT)
    deadline = time.monotonic() + 10
    while any(Path(f'/proc/{pid}').exists() for pid in pids):
        if time.monotonic() >= deadline:
            raise TimeoutError(f'Field did not finish shutting down; leaving its runtime alive')
        time.sleep(.1)
    # Also cancel a launcher still building/starting, before a listener has appeared.
    subprocess.run(['systemctl', '--user', 'stop', unit], check=True, timeout=15,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
