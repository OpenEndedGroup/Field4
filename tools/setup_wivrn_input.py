#!/usr/bin/env python3
"""Install per-user access to only WiVRn's two virtual input devices. Default: show the plan."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import pwd
import shlex
import subprocess
import sys
import tempfile

RULE = Path('/etc/udev/rules.d/72-field4-wivrn-input.rules')
DEVICES = {'WiVRn Keyboard': '0840', 'WiVRn Mouse': '0839'}


def rules(uid: int) -> str:
    if uid <= 0:
        raise ValueError('Choose an ordinary user, not root')
    lines = ['# Field4 WiVRn input: access only for the named user; excluded from the desktop seat.']
    for name, product in DEVICES.items():
        lines.append(f'SUBSYSTEM=="input", KERNEL=="event*", ATTRS{{name}}=="{name}", '
                     f'ATTRS{{id/bustype}}=="0003", ATTRS{{id/vendor}}=="4711", '
                     f'ATTRS{{id/product}}=="{product}", OWNER:="{uid}", MODE:="0600", '
                     'ENV{LIBINPUT_IGNORE_DEVICE}="1"')
    return '\n'.join(lines) + '\n'


def matching_nodes(sys_root: Path = Path('/sys/class/input')) -> list[Path]:
    result = []
    for node in sys_root.glob('event[0-9]*'):
        try:
            device = node / 'device'
            name = (device / 'name').read_text().strip()
            if (name in DEVICES and (device / 'id/vendor').read_text().strip() == '4711'
                    and (device / 'id/product').read_text().strip() == DEVICES[name]
                    and (device / 'id/bustype').read_text().strip() == '0003'):
                result.append(node)
        except OSError:
            continue
    return result


def enable_forwarding() -> None:
    uid = os.getuid()
    if not RULE.is_file() or RULE.read_text() != rules(uid):
        raise RuntimeError('Install the matching WiVRn device rule before enabling HID forwarding')
    config_file = Path(os.environ.get('XDG_CONFIG_HOME', str(Path(pwd.getpwuid(uid).pw_dir) / '.config'))) / 'wivrn/config.json'
    config = json.loads(config_file.read_text()) if config_file.exists() else {}
    config = {} if config is None else config
    if not isinstance(config, dict):
        raise ValueError(f'{config_file} must contain a JSON object')
    # A running dashboard/server can have configuration not yet reflected in this file.
    bus = ['busctl', '--user']
    destination = ['io.github.wivrn.Server', '/io/github/wivrn/Server', 'io.github.wivrn.Server', 'JsonConfiguration']
    live = subprocess.run([*bus, 'get-property', *destination], text=True, capture_output=True)
    if live.returncode == 0:
        value = json.loads(json.loads(live.stdout.split(' ', 1)[1]))
        if value is not None:
            if not isinstance(value, dict):
                raise ValueError('WiVRn JsonConfiguration must be a JSON object')
            config = value
    config['hid-forwarding'] = True
    encoded = json.dumps(config, indent=2) + '\n'
    config_file.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='w', dir=config_file.parent, delete=False) as handle:
        handle.write(encoded)
        temporary = Path(handle.name)
    os.chmod(temporary, 0o600)
    os.replace(temporary, config_file)
    if live.returncode == 0:
        subprocess.run([*bus, 'set-property', *destination, 's', json.dumps(config)], check=True)
    print('WiVRn HID forwarding enabled. Connect/reconnect the headset to create its input devices.')


def configure_private_serve(port: int, field_port: int) -> None:
    if not 1024 <= port <= 65535 or not 1 <= field_port <= 65535:
        raise ValueError('Serve port must be 1024..65535 and Field port must be 1..65535')
    before = json.loads(subprocess.check_output(['tailscale', 'serve', 'status', '--json'], text=True))
    if str(port) in before.get('TCP', {}):
        raise RuntimeError(f'Tailscale port {port} already has a listener; choose an unused --serve-port')
    host = json.loads(subprocess.check_output(['tailscale', 'status', '--json'], text=True))['Self']['DNSName'].rstrip('.')
    if before.get('AllowFunnel', {}).get(f'{host}:{port}'):
        raise RuntimeError(f'Tailscale port {port} is marked public; choose a private --serve-port')
    subprocess.run(['tailscale', 'serve', '--bg', f'--https={port}', '--yes',
                    f'https+insecure://localhost:{field_port}'], check=True)
    after = json.loads(subprocess.check_output(['tailscale', 'serve', 'status', '--json'], text=True))
    if after.get('AllowFunnel', {}).get(f'{host}:{port}'):
        raise RuntimeError('The new listener unexpectedly permits Funnel; disable it before using Field')
    for section in ['TCP', 'Web', 'AllowFunnel']:
        if any(after.get(section, {}).get(key) != value for key, value in before.get(section, {}).items()):
            raise RuntimeError(f'Tailscale changed an existing {section} entry unexpectedly')
    print(f'Private Field URL: https://{host}:{port}/index.html?file=Field-VR-IDE')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install', action='store_true', help='Install the rule, reload it, and enable WiVRn HID forwarding')
    parser.add_argument('--user', default=os.environ.get('SUDO_USER') or pwd.getpwuid(os.getuid()).pw_name)
    parser.add_argument('--enable-forwarding', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--serve-port', type=int, help='Also add a new private Tailscale HTTPS listener on this unused port')
    parser.add_argument('--field-port', type=int, default=9092)
    args = parser.parse_args()
    if args.enable_forwarding:
        enable_forwarding()
        return 0
    account = pwd.getpwnam(args.user)
    content = rules(account.pw_uid)
    if args.serve_port is not None and not 1024 <= args.serve_port <= 65535:
        parser.error('--serve-port must be 1024..65535')
    if not 1 <= args.field_port <= 65535:
        parser.error('--field-port must be 1..65535')
    print(f'{RULE}:\n{content}')
    if not args.install:
        command = ['sudo', 'python3', str(Path(__file__).resolve()), '--install', '--user', account.pw_name]
        if args.serve_port is not None:
            command += ['--serve-port', str(args.serve_port), '--field-port', str(args.field_port)]
        print('Apply: ' + shlex.join(command))
        return 0
    if os.geteuid() != 0:
        parser.error('--install needs administrator privileges; use the printed sudo command')
    if RULE.exists() and not RULE.read_text().startswith('# Field4 WiVRn input:'):
        raise RuntimeError(f'Refusing to replace an unrecognized rule at {RULE}')
    RULE.write_text(content)
    RULE.chmod(0o644)
    subprocess.run(['udevadm', 'control', '--reload-rules'], check=True)
    for node in matching_nodes():
        subprocess.run(['udevadm', 'trigger', '--action=change', str(node)], check=True)
    subprocess.run(['udevadm', 'settle'], check=True)
    # Configuration changes run as the destination user, not as root in their home directory.
    env = dict(os.environ, XDG_RUNTIME_DIR=f'/run/user/{account.pw_uid}',
               DBUS_SESSION_BUS_ADDRESS=f'unix:path=/run/user/{account.pw_uid}/bus',
               XDG_CONFIG_HOME=str(Path(account.pw_dir) / '.config'))
    subprocess.run(['runuser', '--user', account.pw_name, '--', sys.executable,
                    str(Path(__file__).resolve()), '--enable-forwarding'], env=env, check=True)
    if args.serve_port is not None:
        configure_private_serve(args.serve_port, args.field_port)
    print(f'Installed for {account.pw_name}. No physical input devices or input-group membership changed.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
