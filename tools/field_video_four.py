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
    parser.add_argument('--kosmickrisp', action='store_true',
                        help='macOS: render on KosmicKrisp through the patched WiVRn runtime instead of MoltenVK')
    parser.add_argument('--prepare', action='store_true',
                        help='macOS: prepare WiVRn once (with --kosmickrisp, the patched runtime too), then exit')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    wivrn_macos = [sys.executable, str(repo / 'tools/field_wivrn_macos.py')]
    if platform.system() != 'Darwin' and (args.kosmickrisp or args.prepare):
        parser.error('--kosmickrisp and --prepare are for macOS; on Linux, WiVRn needs no preparation from Field')
    if args.prepare:
        return subprocess.call(wivrn_macos + ['--prepare'] + (['--kosmickrisp'] if args.kosmickrisp else []))
    if args.kosmickrisp and args.desktop:
        parser.error('--kosmickrisp selects the headset runtime; --desktop runs without OpenXR')
    workspace = args.workspace.expanduser().resolve()
    if not (workspace / 'video_four/metadata.edn').is_file():
        parser.error(f'Create video_four first: {workspace / "video_four"}')
    env = os.environ.copy()
    env['FIELD_VIDEO_DESKTOP'] = '1' if args.desktop else '0'
    env['AFIELD_FIELD_SH_NO_TERMINAL'] = '1'
    field_args = [str(workspace), '-d', 'video_four']
    if platform.system() == 'Darwin' and not args.desktop:
        command = list(wivrn_macos)
        if args.check:
            command += ['--check']
        if args.kosmickrisp:
            command += ['--kosmickrisp']
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
