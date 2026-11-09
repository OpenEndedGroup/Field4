#!/usr/bin/env python3
"""Create video_one through Field's operation transcript API; never edit document EDN."""
import argparse
import json
import os
import re
import textwrap
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('/tmp/video-one-transcripts'))
    parser.add_argument('--transcript', type=Path)
    parser.add_argument('--video', default='')
    parser.add_argument('--headset', action='store_true')
    parser.add_argument('--no-browser', action='store_true')
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    repo = here.parents[1]
    context = (here / 'context.kt').read_text()
    if args.headset:
        context = context.replace('val headsetMode = false', 'val headsetMode = true')
    if args.no_browser:
        context = context.replace('val withFieldBrowser = true', 'val withFieldBrowser = false')
    playback = (here / 'playback.kt').read_text()
    if args.video:
        playback = re.sub(r'var selectedFile = System.getenv\("FIELD_STEREO_VIDEO"\)\s*\?: java.io.File\("[^"\n]+"\).absolutePath', lambda _: 'var selectedFile = ' + json.dumps(args.video), playback)
    boxes = [
        ('video1_context', '1 · Headset / preview / Field pane', 0, context),
        ('video1_screen', '2 · Stereo screen placement', 600, (here / 'screen.kt').read_text()),
        ('video1_shader', '4 · Video shader', 1800, (here / 'shader.kt').read_text()),
        ('video1_accumulation', '5 · Temporal accumulation shader', 2400, (here / 'accumulation.kt').read_text()),
        ('video1_playback', '3 · Movie / transport', 1200, playback),
    ]
    create = [{'create': {'box': uid, 'name': name, 'frame': {'x': x, 'y': 0, 'w': 480, 'h': 100}, 'properties': {'code': code}}} for uid, name, x, code in boxes]
    shader_box = next(op['create'] for op in create if op['create']['box'] == 'video1_shader')
    for stage in ['vertex', 'fragment']:
        shader_source = (repo / f'src/jvmMain/resources/afield/stereovideo/{stage}.glsl').read_text()
        if stage == 'vertex':
            abi = (repo / 'src/jvmMain/kotlin/afield/graphics/vulkan/targets/MultiviewStereoTarget.kt').read_text()
            views = re.search(r'val VIEWS_GLSL\s*=\s*"""(.*?)"""', abi, re.S)
            if views is None:
                raise SystemExit('Cannot find StereoViews ABI')
            shader_source = shader_source.replace('// VIEWS', textwrap.dedent(views.group(1)).strip())
        shader_box['properties'][stage] = shader_source
    accumulation_box = next(op['create'] for op in create if op['create']['box'] == 'video1_accumulation')
    for stage in ['vertex', 'fragment']:
        accumulation_box['properties'][stage] = (repo / f'src/jvmMain/resources/afield/stereovideo/accumulation_{stage}.glsl').read_text()
    create += [{'connect': {'parent': 'video1_screen', 'child': 'video1_shader'}}, {'connect': {'parent': 'video1_shader', 'child': 'video1_playback'}}]
    create += [{'connect': {'parent': 'video1_context', 'child': 'video1_screen'}}, {'connect': {'parent': 'video1_screen', 'child': 'video1_playback'}}]
    create += [{'connect': {'parent': 'video1_screen', 'child': 'video1_accumulation'}}]
    launch = [{'launch': {'box': uid}} for uid, *_ in boxes] + [{'await': {'wait': 'pending'}}]
    stop = [{'stop': {'box': uid}} for uid in ['video1_playback', 'video1_accumulation', 'video1_shader', 'video1_screen', 'video1_context']]
    if args.transcript:
        import yaml
        class Dumper(yaml.SafeDumper):
            pass
        Dumper.add_representer(str, lambda d, s: d.represent_scalar('tag:yaml.org,2002:str', s, style='|' if '\n' in s else None))
        args.transcript.parent.mkdir(parents=True, exist_ok=True)
        args.transcript.write_text(yaml.dump({'name': 'video_one_vr', 'description': 'A full-resolution side-by-side stereo movie on a movable cylindrical screen. Background P010 decoding, current OpenXR poses, a mirror of the actual eye image and an optional Field browser. Variable speed and optional float16 video-local temporal accumulation. No audio or scene compositor.', 'docs': [{'page': 'stereo_video.md', 'why': 'Full-resolution stereo playback, curved screen placement and precision.'}], 'operations': create + launch}, Dumper=Dumper, sort_keys=False, allow_unicode=True, width=100))
        subprocess.run([str(repo / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), 'formatBoxCode', f'--args="{args.transcript.resolve().as_posix()}"'], cwd=repo, check=True)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, operations in [('create', create), ('launch', launch), ('stop', stop)]:
        path = args.output / f'{name}.json'
        path.write_text(json.dumps({'operations': operations}, ensure_ascii=False, indent=2) + '\n')
        print(path)


if __name__ == '__main__':
    main()
