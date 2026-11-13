#!/usr/bin/env python3
"""Create video_four: video_three's room with portable Linux/macOS WiVRn startup."""
import argparse
import json
import re
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
        if name == 'launch':
            index = next(i for i, op in enumerate(data['operations']) if op.get('launch', {}).get('box') == 'video2_motion')
            data['operations'].insert(index + 1, {'launch': {'box': 'video4_geometry'}})
        if name == 'stop':
            data['operations'].insert(0, {'stop': {'box': 'video4_geometry'}})
        if name == 'create':
            data['operations'] += [
                {'create': {'box': 'video4_geometry', 'name': 'Ring arrangements',
                    'frame': {'x': -1450, 'y': 2143, 'w': 500, 'h': 100},
                    'properties': {'code': (here / 'geometry.kt').read_text()}}},
                {'connect': {'parent': 'video1_screen', 'child': 'video4_geometry'}}]

            for op in data['operations']:
                box = op.get('create', {})
                uid = box.get('box', '')
                if uid == 'video3_looks':
                    box['properties']['code'] = (here / 'looks.kt').read_text()
                if uid == 'video1_screen':
                    code = box['properties']['code']
                    defaults = {'accumulationMix': '0f', 'accumulationHalfLife': '0.0',
                        'accumulationInputWeight': '1.0', 'exposureCadenceEnabled': 'false',
                        'exposureBlurSigma': '0f', 'exposureBlurMix': '0f',
                        'exposureDarkening': '0f', 'swapEyes': 'true'}
                    for key, value in defaults.items():
                        code = re.sub(r'view\.' + key + r' = [^\n]+', 'view.' + key + ' = ' + value, code)
                    code = re.sub(r'carousel.source.speed = [^\n]+',
                        'carousel.source.speed = 2.0\ncarousel.source.materialFramesPerSecond = 24\n'
                        'carousel.source.selectiveDecode = false\ncarousel.source.skipNonReferenceFrames = false', code)
                    box['properties']['code'] = code.replace('spacingFrames = 16', 'spacingFrames = 8').replace('carousel.fill = .9f', 'carousel.fill = .97f')
                if uid == 'video1_shader' or uid.startswith('video2_material_'):
                    code = box['properties']['code']
                    code = code.replace('val screenModule = moduleHere { +view }', '''val screenModule = moduleHere {
    +view
    // Add emitted light, with opacity controlling energy rather than occlusion.
    blend = true
    srcColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA
    dstColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    srcAlpha = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    dstAlpha = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    depthTest = false
    depthWrite = false
}''')
                    box['properties']['code'] = code
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
                    fallback = json.dumps(args.video).replace('$', '\\$') if args.video else '"%document/thisLand_3D_SBS.mp4".file.absolutePath'
                    box['properties']['code'] = code[:start] + 'var selectedFile = System.getenv("FIELD_STEREO_VIDEO") ?: ' + fallback + code[end:]
        path = args.output / f'{name}.json'
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        print(path)


if __name__ == '__main__':
    main()
