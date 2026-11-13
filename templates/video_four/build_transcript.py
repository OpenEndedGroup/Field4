#!/usr/bin/env python3
"""Create video_four: video_three's room with portable Linux/macOS WiVRn startup."""
import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('/tmp/video-four-transcripts'))
    parser.add_argument('--video', default='')
    parser.add_argument('--no-browser', action='store_true')
    parser.add_argument('--shader-document', type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    base = args.output / 'video-three-base'
    command = [sys.executable, str(here.parent / 'video_three/build_transcript.py'),
               '--output', str(base), '--headset']
    if args.no_browser:
        command += ['--no-browser']
    if args.shader_document:
        command += ['--shader-document', str(args.shader_document)]
    subprocess.run(command, check=True)
    for name in ('create', 'launch', 'stop', 'verify'):
        data = json.loads((base / f'{name}.json').read_text())
        # video_three supplies the ring controls and geometry; retain video_four's existing IDs.
        for op in data['operations']:
            for payload in op.values():
                if isinstance(payload, dict):
                    for key in ('box', 'parent', 'child'):
                        if payload.get(key) == 'video3_geometry':
                            payload[key] = 'video4_geometry'
        if name == 'create':
            for op in data['operations']:
                box = op.get('create', {})
                uid = box.get('box', '')
                if uid == 'video3_looks':
                    box['properties']['code'] = (here / 'looks.kt').read_text()
                if uid == 'video4_geometry':
                    box['properties']['code'] = (here / 'geometry.kt').read_text()
                if box.get('box') == 'video1_context':
                    code = (here / 'context.kt').read_text()
                    if args.no_browser:
                        code = code.replace('val withFieldBrowser = true', 'val withFieldBrowser = false')
                    box['properties']['code'] = code
                if box.get('box') == 'video1_playback':
                    code = box['properties']['code']
                    start = code.index('var selectedFile =')
                    end = code.index('\nvar scrubber:', start)
                    # Resolve bundled media through the document, independent of host and workspace location.
                    fallback = json.dumps(args.video).replace('$', '\\$') if args.video else '"%document/thisLand_3D_SBS.mp4".path.toString()'
                    box['properties']['code'] = code[:start] + 'var selectedFile = System.getenv("FIELD_STEREO_VIDEO") ?: ' + fallback + code[end:]
        path = args.output / f'{name}.json'
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        print(path)


if __name__ == '__main__':
    main()
