#!/usr/bin/env python3
"""Launch Field with SteamVR OpenXR and desktop (or paired headset) input; leave the default runtime untouched."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys


def steamvr_manifest(home: Path, explicit: Path | None = None) -> Path:
    if explicit is not None:
        candidates = [explicit.expanduser() / 'steamxr_linux64.json']
    else:
        candidates = []
        registry = home / '.config/openvr/openvrpaths.vrpath'
        if registry.is_file():
            candidates.extend(Path(p) / 'steamxr_linux64.json'
                              for p in json.loads(registry.read_text()).get('runtime', []))
        candidates.extend(root / 'steamapps/common/SteamVR/steamxr_linux64.json' for root in (
            home / '.local/share/Steam', home / '.steam/steam'))
    for manifest in candidates:
        if not manifest.is_file():
            continue
        runtime = json.loads(manifest.read_text()).get('runtime', {})
        library = Path(runtime.get('library_path', ''))
        if not library.is_absolute():
            library = manifest.parent / library
        if runtime.get('VALVE_runtime_is_steamvr') is True and library.is_file():
            return manifest.resolve()
    raise ValueError('No usable SteamVR OpenXR runtime found. Install SteamVR or pass --steamvr DIRECTORY.')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--steamvr', type=Path, help='SteamVR installation directory')
    parser.add_argument('--check', action='store_true', help='Print the launch plan without starting anything')
    parser.add_argument('--paired-input', action='store_true',
                        help='Take keyboard/mouse from the paired visionOS ALVR client (FIELD_VR_INPUT=paired) '
                             'instead of the desktop companion; experimental, not yet verified on a headset')
    parser.add_argument('field_args', nargs=argparse.REMAINDER, help='Arguments after -- go to field.sh')
    args = parser.parse_args()
    try:
        manifest = steamvr_manifest(Path.home(), args.steamvr)
    except (OSError, ValueError, TypeError) as error:
        parser.error(str(error))
    launcher = Path(__file__).resolve().parent.parent / 'field.sh'
    field_args = args.field_args
    if field_args[:1] == ['--']:
        field_args = field_args[1:]
    overrides = {'XR_RUNTIME_JSON': str(manifest), 'FIELD_VR_INPUT': 'paired' if args.paired_input else 'desktop',
                 'AFIELD_FIELD_SH_NO_TERMINAL': '1'}
    if args.check:
        print(json.dumps({'environment': overrides, 'command': [str(launcher), *field_args],
                          'status': 'Launch plan only; ALVR connection and headset input not verified.'}, indent=2))
        return 0
    print('Field → SteamVR → ALVR. Start and connect ALVR first. ' + (
          'Keyboard/mouse come from the paired headset app; the pairing code is in the VR toolbar.'
          if args.paired_input else 'Keyboard/mouse use the focused desktop companion.'), file=sys.stderr, flush=True)
    os.execve(launcher, [str(launcher), *field_args], {**os.environ, **overrides})
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
