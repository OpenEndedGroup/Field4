#!/usr/bin/env python3
"""Emit real Field operation transcripts; create documents through Field, never by editing EDN."""
import argparse
import json
import re
import textwrap
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transcript', type=Path, help='Write the full public starting-point transcript with readable YAML source blocks')
    parser.add_argument('--output', type=Path, default=Path('/tmp/text-one-transcripts'))
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    repo = here.parents[1]
    kotlin = (repo / 'src/jvmMain/kotlin/afield/graphics/vulkan/targets/MultiviewStereoTarget.kt').read_text()
    match = re.search(r'val VIEWS_GLSL\s*=\s*"""(.*?)"""', kotlin, re.S)
    if not match:
        raise SystemExit('Cannot find the current StereoViews ABI')
    views = textwrap.dedent(match.group(1)).strip()

    def shader(name):
        return (repo / f'src/jvmMain/resources/afield/reading/{name}.glsl').read_text().replace('// VIEWS', views)

    boxes = [
        ('text1_context', '1 · Reading space', 0, 150, {'code': (here/'context.kt').read_text()}),
        ('text1_poem', '2 · Marked breath', 650, 0, {'code': (here/'example_one.kt').read_text()}),
        ('text1_example2', '3 · Constituency canopy', 650, 110, {'code': (here/'example_two.kt').read_text()}),
        ('text1_example3', '4 · Dependency undertow', 650, 220, {'code': (here/'example_three.kt').read_text()}),
        ('text1_example4', '5 · Sentence furniture', 650, 330, {'code': (here/'example_four.kt').read_text()}),
        ('text1_typography', '13 · Typography playground', 650, 440, {'code': (here/'typography_playground.kt').read_text()}),
        ('text1_word_blur', '14 · Words becoming weather', 650, 550, {'code': (here/'word_blur.kt').read_text()}),
        ('text1_score', '6 · Scores / example selector', 1200, 0, {'code': (here/'score.kt').read_text()}),
        ('text1_stroke', '7 · FLine strokes', 1800, 0, {'code': 'prop<Box>(strokeSource) = thisBox\n', 'vertex': shader('stroke_vertex'), 'fragment': shader('stroke_fragment')}),
        ('text1_point', '8 · SDF markers', 1800, 110, {'code': 'prop<Box>(pointSource) = thisBox\n', 'vertex': shader('point_vertex'), 'fragment': shader('point_fragment')}),
        ('text1_text', '9 · Slug words', 1800, 220, {'code': 'prop<Box>(textSource) = thisBox\n', 'vertex': shader('slug_vertex'), 'fragment': shader('slug_fragment')}),
        ('text1_blur', '10 · Blur cascade', 1800, 330, {'code': 'prop<Box>(blurSource) = thisBox\n', 'vertex': shader('fullscreen_vertex'), 'fragment': shader('blur_fragment')}),
        ('text1_composite', '11 · Green / paint compositor', 1800, 440, {'code': 'prop<Box>(compositeSource) = thisBox\n', 'vertex': shader('fullscreen_vertex'), 'fragment': shader('composite_fragment')}),
        ('text1_drawing', '12 · Drawing / score playback', 2350, 0, {'code': (here/'drawing.kt').read_text()}),
    ]
    create = [
        {'create': {'box': uid, 'name': name, 'frame': {'x': x, 'y': y, 'w': 480, 'h': 100}, 'properties': props}}
        for uid, name, x, y, props in boxes
    ]
    for child in ['text1_poem', 'text1_example2', 'text1_example3', 'text1_example4', 'text1_typography', 'text1_word_blur', 'text1_stroke', 'text1_point', 'text1_text', 'text1_blur', 'text1_composite']:
        create.append({'connect': {'parent': 'text1_context', 'child': child}})
    for parent in ['text1_poem', 'text1_example2', 'text1_example3', 'text1_example4', 'text1_typography', 'text1_word_blur']:
        create.append({'connect': {'parent': parent, 'child': 'text1_score'}})
    for parent in ['text1_poem', 'text1_score', 'text1_stroke', 'text1_point', 'text1_text', 'text1_blur', 'text1_composite']:
        create.append({'connect': {'parent': parent, 'child': 'text1_drawing'}})
    launch = [{'launch': {'box': uid}} for uid, *_ in boxes] + [{'await': {'wait': 'pending'}}]
    stop = [{'stop': {'box': 'text1_drawing'}}, {'stop': {'box': 'text1_context'}}]
    if args.transcript:
        import yaml

        class SourceDumper(yaml.SafeDumper):
            pass

        def source_string(dumper, value):
            return dumper.represent_scalar('tag:yaml.org,2002:str', value, style='|' if '\n' in value else None)

        SourceDumper.add_representer(str, source_string)
        args.transcript.parent.mkdir(parents=True, exist_ok=True)
        args.transcript.write_text(yaml.dump({
            'name': 'text_one_vr',
            'description': 'Six shallow-3D reading spaces: marked breath, constituency canopy, dependency undertow, Reed-Kellogg sentence furniture, a mixed-font typography playground and slow word-local Gaussian blur. CPU SIMDVerlet, per-eye FLine thickening and Slug text, with independent scores. Starts in desktop preview; OpenXR is an explicit fresh-process mode. Generated from templates/text_one by build_transcript.py.',
            'docs': [{'page': 'sentence_diagrams.md', 'why': 'Parser setup, four diagrams, mixed-font typography and the reading-space DSL.'}],
            'operations': create + launch,
        }, Dumper=SourceDumper, allow_unicode=True, sort_keys=False, width=100))
        print(args.transcript)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, operations in [('create', create), ('launch', launch), ('stop', stop)]:
        path = args.output / f'{name}.json'
        path.write_text(json.dumps({'operations': operations}, ensure_ascii=False, indent=2)+'\n')
        print(path)


if __name__ == '__main__':
    main()
