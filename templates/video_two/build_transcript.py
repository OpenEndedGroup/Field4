#!/usr/bin/env python3
"""Build video_two's shared-source carousel through Field operation transcripts."""
import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('/tmp/video-two-transcripts'))
    parser.add_argument('--video', default='')
    parser.add_argument('--headset', action='store_true')
    parser.add_argument('--no-browser', action='store_true')
    parser.add_argument('--look-file', type=Path)
    parser.add_argument('--shader-document', type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    repo = here.parents[1]
    args.output.mkdir(parents=True, exist_ok=True)
    base = args.output / 'base'
    subprocess.run([sys.executable, str(here.parent / 'video_one/build_transcript.py'), '--output', str(base)], check=True)
    operations = json.loads((base / 'create.json').read_text())['operations']
    create = {op['create']['box']: op['create'] for op in operations if 'create' in op}
    context = (here / 'context.kt').read_text()
    if args.headset:
        context = context.replace('val headsetMode = false', 'val headsetMode = true')
    if args.no_browser:
        context = context.replace('val withFieldBrowser = true', 'val withFieldBrowser = false')
    create['video1_context']['properties']['code'] = context
    create['video1_context']['frame'].update(x=0, y=0)
    ring = (here / 'ring.kt').read_text()
    playback = (here / 'playback.kt').read_text()
    if args.video:
        start = playback.index('var selectedFile =')
        end = playback.index('\nvar scrubber:', start)
        playback = playback[:start] + 'var selectedFile = ' + json.dumps(args.video).replace('$', '\\$') + playback[end:]
    if args.look_file:
        look = args.look_file.read_text().splitlines()
        values = {'view.accumulationMix = 1f': f'view.accumulationMix = {look[4]}f',
                  'view.accumulationHalfLife = 4.0': f'view.accumulationHalfLife = {look[5]}',
                  'view.accumulationInputWeight = 1.0': f'view.accumulationInputWeight = {look[6]}',
                  'view.exposureCadenceEnabled = true': f'view.exposureCadenceEnabled = {look[12]}',
                  'view.exposurePairsPerSecond = 12.0': f'view.exposurePairsPerSecond = {look[13]}',
                  'view.exposureBlurSigma = 1.3f': f'view.exposureBlurSigma = {look[14]}f',
                  'view.exposureBlurMix = .95f': f'view.exposureBlurMix = {look[15]}f',
                  'view.exposureDarkening = .04f': f'view.exposureDarkening = {look[16]}f',
                  'view.swapEyes = false': f'view.swapEyes = {look[8]}\n    view.flipVertically = {look[9]}',
                  'carousel.source.speed = 8.0': f'carousel.source.speed = {look[3]}\ncarousel.source.materialFramesPerSecond = {look[7]}\ncarousel.source.selectiveDecode = {look[10]}\ncarousel.source.skipNonReferenceFrames = {look[11]}'}
        for old, new in values.items():
            ring = ring.replace(old, new)
        playback = playback.replace('if (carousel.load(selectedFile)) showTransport()',
            'if (carousel.load(selectedFile)) {\n        source.seek(' + look[1] + ')\n        ' + ('source.play()' if look[2] == 'true' else 'source.pause()') + '\n        showTransport()\n    }')
    create['video1_screen'].update(name='2 · Carousel layout', frame=dict(x=650, y=0, w=480, h=100))
    create['video1_screen']['properties']['code'] = ring
    create['video1_playback']['properties']['code'] = playback
    create['video1_playback']['frame'].update(x=0, y=650)
    stages = {kind: {stage: create[uid]['properties'][stage] for stage in ['vertex', 'fragment']}
              for kind, uid in [('material', 'video1_shader'), ('accumulation', 'video1_accumulation')]}
    if args.shader_document:
        for kind, uid in [('material', 'video1_shader'), ('accumulation', 'video1_accumulation')]:
            for stage in ['vertex', 'fragment']:
                stages[kind][stage] = (args.shader_document / f'{uid}_{stage}_.glsl').read_text()
    operations.append({'create': dict(box='video2_looks', name='0 · Shared screen looks',
        frame=dict(x=-700, y=0, w=500, h=100), properties=dict(code=(here / 'looks.kt').read_text()))})
    operations.append({'connect': dict(parent='video1_screen', child='video2_looks')})
    operations.append({'create': dict(box='video2_motion', name='Ring motion',
        frame=dict(x=-700, y=2200, w=500, h=100), properties=dict(code=(here / 'motion.kt').read_text()))})
    operations.append({'connect': dict(parent='video1_screen', child='video2_motion')})
    launch = ['video1_context', 'video1_screen', 'video2_looks', 'video2_motion']
    for index in range(8):
        x, y = 1300 + index % 4 * 650, index // 4 * 1800
        for kind, uid, name, offset in [
            ('material', 'video1_shader' if index == 0 else f'video2_material_{index}', f'{index + 1} · Screen material / exposure', 0),
            ('accumulation', 'video1_accumulation' if index == 0 else f'video2_accumulation_{index}', f'{index + 1} · Accumulation shader', 1250),
        ]:
            payload = dict(box=uid, name=name, frame=dict(x=x, y=y+offset, w=480, h=100),
                           properties=dict(code=(here / f'{kind}.kt').read_text().replace('SCREEN_INDEX', str(index)), **stages[kind]))
            if uid in create:
                create[uid].update(payload)
            else:
                operations.append({'create': payload})
                operations.append({'connect': dict(parent='video1_screen', child=uid)})
            if kind == 'material':
                operations.append({'connect': dict(parent='video2_looks', child=uid)})
            launch.append(uid)
    launch.append('video1_playback')
    for name, ops in [('create', operations), ('launch', [{'launch': {'box': uid}} for uid in launch] + [{'await': {'wait': 'pending'}}]),
                      ('stop', [{'stop': {'box': uid}} for uid in reversed(launch)])]:
        path = args.output / (name + '.json')
        path.write_text(json.dumps({'operations': ops}, ensure_ascii=False, indent=2) + '\n')
        print(path)


if __name__ == '__main__':
    main()
