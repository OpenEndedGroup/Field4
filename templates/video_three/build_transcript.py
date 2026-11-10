#!/usr/bin/env python3
"""Build video_three: video_two's renderer with source-backed whole-ring keyframes."""
import argparse
import json
import re
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('/tmp/video-three-transcripts'))
    parser.add_argument('--video', default='')
    parser.add_argument('--headset', action='store_true')
    parser.add_argument('--no-browser', action='store_true')
    parser.add_argument('--shader-document', type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    base = args.output / 'video-two-base'
    command = [sys.executable, str(here.parent / 'video_two/build_transcript.py'), '--output', str(base)]
    for flag, value in [('--video', args.video), ('--shader-document', args.shader_document)]:
        if value:
            command += [flag, str(value)]
    if args.headset:
        command += ['--headset']
    if args.no_browser:
        command += ['--no-browser']
    subprocess.run(command, check=True)
    create = json.loads((base / 'create.json').read_text())['operations']
    # Keep stable shader-box IDs so existing stage tabs and reloads retain their normal behaviour.
    for operation in create:
        box = operation.get('create')
        if box:
            uid = box['box']
            if uid == 'video1_context':
                box['properties']['code'] = box['properties']['code'].replace('video_two', 'video_three')
            elif uid == 'video2_looks':
                box['name'] = 'Whole-ring rules / ordered keyframes'
                box['properties']['code'] = (here / 'looks.kt').read_text()
            elif uid == 'video2_motion':
                code = box['properties']['code']
                start = code.index('// Native screen-look lanes:')
                end = code.index('// Insert before all surface prepare targets', start)
                box['properties']['code'] = code[:start] + code[end:]
            elif uid == 'video1_shader' or uid.startswith('video2_material_'):
                code = box['properties']['code']
                start = code.index('controlGroup("shared looks")')
                end = code.index('// Whole-refresh shutter settings', start)
                code = code[:start] + code[end:]
                code = code.replace('screenLooks.watchControls(screenIndex) { controlMirrors.forEach { it() } }',
                    'var lastMirror = 0L\nloop { val now = System.nanoTime(); if (now - lastMirror >= 250_000_000L) { lastMirror = now; controlMirrors.forEach { it() } }; frame() }')
                code = re.sub(r'controlGroup\("([^"]+)"\) \{', r'controlGroup("\1", restoreValues = false) {', code)
                box['properties']['code'] = code.replace('displayOpen("shared looks")', '')
        connection = operation.get('connect')
        if connection == {'parent': 'video2_looks', 'child': 'video2_motion'}:
            connection.update(parent='video1_screen', child='video2_motion')
    create.append({'connect': {'parent': 'video2_motion', 'child': 'video2_looks'}})
    create.append({'connect': {'parent': 'video2_looks', 'child': 'video1_playback'}})
    # Motion now provides the clock to the ordered look pool, rather than registering per-screen keys.
    launch = json.loads((base / 'launch.json').read_text())['operations']
    looks = next(i for i, op in enumerate(launch) if op.get('launch', {}).get('box') == 'video2_looks')
    motion = next(i for i, op in enumerate(launch) if op.get('launch', {}).get('box') == 'video2_motion')
    launch[looks], launch[motion] = launch[motion], launch[looks]
    for op in create + launch:
        for payload in op.values():
            if isinstance(payload, dict):
                for key in ('box', 'parent', 'child'):
                    if payload.get(key) == 'video2_looks':
                        payload[key] = 'video3_looks'
    verify = [{'execute': {'box': 'video3_looks', 'code': (here / 'verify.kt').read_text()}}, {'await': {'wait': 'pending'}}]
    for name, operations in [('verify', verify), ('create', create), ('launch', launch),
                             ('stop', list(reversed([{'stop': op['launch']} for op in launch if 'launch' in op])))]:
        path = args.output / f'{name}.json'
        path.write_text(json.dumps({'operations': operations}, ensure_ascii=False, indent=2) + '\n')
        print(path)


if __name__ == '__main__':
    main()
