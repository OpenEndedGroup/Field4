#!/usr/bin/env python3
"""Launch video_four through the host's WiVRn runtime, without changing global OpenXR settings."""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, default=Path.home() / 'Documents/AFieldWorkspace')
    parser.add_argument('--desktop', action='store_true', help='Inspect without a headset in a fresh process')
    parser.add_argument('--check', action='store_true', help='Show launch plan without starting Field')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    workspace = args.workspace.expanduser().resolve()
    if not (workspace / 'video_four/metadata.edn').is_file():
        parser.error(f'Create video_four first: {workspace / "video_four"}')
    env = os.environ.copy()
    env['FIELD_VIDEO_DESKTOP'] = '1' if args.desktop else '0'
    env['AFIELD_FIELD_SH_NO_TERMINAL'] = '1'
    field_args = [str(workspace), '-d', 'video_four']
    if platform.system() == 'Darwin' and not args.desktop:
        command = [sys.executable, str(repo / 'tools/field_wivrn_macos.py')]
        if args.check:
            command += ['--check']
        return subprocess.call(command + ['--'] + field_args, env=env)
    command = [str(repo / 'field.sh')] + field_args
    if not args.desktop:
        if platform.system() != 'Linux':
            parser.error('WiVRn startup is supported on Linux and macOS')
        manifest = Path('/usr/share/openxr/1/openxr_wivrn.json')
        if not manifest.is_file():
            parser.error(f'WiVRn runtime missing: {manifest}')
        env['XR_RUNTIME_JSON'] = str(manifest)
    if args.check:
        print(json.dumps({'command': command, 'environment': {k: env[k] for k in
            ('FIELD_VIDEO_DESKTOP', 'XR_RUNTIME_JSON') if k in env}}, indent=2))
        return 0
    return subprocess.call(command, env=env)


if __name__ == '__main__':
    raise SystemExit(main())
