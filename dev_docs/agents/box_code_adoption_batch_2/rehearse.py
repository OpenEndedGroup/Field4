"""Run the async teaching-code batch in a private session on 9698."""
import asyncio
import base64
import hashlib
import json
import re
import shlex
import subprocess
import sys
from pathlib import Path
import yaml

ROOT = Path.cwd()
sys.path.insert(0, str(ROOT / 'tools'))
from field_session import send_request, socket_path_for
OUT = ROOT / 'build/box-code-revision/adoption-2'
SOURCES = ROOT / 'dev_docs/agents/transcripts'
BASE = 'e97a8c4a9'
CHANNELS = {
    'loris_edit_partials': ['changed partials and sound'],
    'loris_two_resolutions': ['fine time', 'fine frequency'],
    'loris_spectral_whirlpool': ['edited tracks and sound'],
    'loris_label_align_morph': ['morph'],
}

async def batch(name, commands, reset=False):
    print(name, flush=True)
    result = await send_request(socket_path_for(9698), {
        'commands': commands, 'resetDoc': reset, 'scratch': True,
        'withPlaywright': True, 'timeout': 180,
    }, timeout=240)
    (OUT / (name + '.json')).write_text(json.dumps(result, indent=2))
    assert result['ok'], (name, str(result)[-3500:])
    return result

async def macro(name, operations, reset=False):
    path = OUT / (name + '.yaml')
    path.write_text(yaml.safe_dump({'operations': operations}, sort_keys=False))
    return await batch(name, [f'macro write {name} --file {shlex.quote(str(path))}', f'macro run {name}'], reset)

async def loris(label, source):
    creates = []
    for op in source['operations']:
        box = op.get('create', {}).get('box')
        if box in CHANNELS:
            op['create']['frame'] = {'x': len(creates) * 4000.0, 'y': 0.0, 'w': 1200.0, 'h': 100.0}
            creates.append(op)
    await macro(label + '_create', creates, True)
    hashes = {}
    for box, channels in CHANNELS.items():
        commands = [f'launch {box}']
        commands += [f'wait-output {box} --channel {shlex.quote(channel)} --timeout 180' for channel in channels]
        commands += [f'lifecycle-census {box} --expect-stopped']
        await batch(label + '_' + box, commands)
        result = await batch(label + '_' + box + '_output', [
            f'output {box} --channel {shlex.quote(channel)} --kind display' for channel in channels])
        encoded = re.findall(r'data:audio/wav;base64,([A-Za-z0-9+/=]+)', json.dumps(result))
        assert encoded, box + ': actual WAV not found'
        hashes[box] = sorted(set(hashlib.sha256(base64.b64decode(value)).hexdigest() for value in encoded))
        await batch(label + '_' + box + '_capture', [f'screenshot-box {box} {OUT}/{label}_{box}.png'])
    return hashes

async def audio_examples():
    for name, box, channel in [
        ('audio_ai_spectral_embeddings', 'analyse_near_drowning_spectral', 'spectral error'),
        ('audio_ai_corpus_microscope', 'index_and_explore_corpus', 'corpus error'),
    ]:
        source = yaml.safe_load((SOURCES / (name + '.yaml')).read_text())
        create = next(op for op in source['operations'] if op.get('create', {}).get('box') == box)
        await macro(name + '_create', [create], True)
        await batch(name + '_real_transport_error', [f'launch {box}',
            f'wait-output {box} --channel {shlex.quote(channel)} --contains "Analysis failed" --timeout 30',
            f'lifecycle-census {box} --expect-stopped', f'screenshot-box {box} {OUT}/{name}_error.png'])
    # The gallery uses committed real analysis artifacts, rather than a substituted service result.
    source = yaml.safe_load((SOURCES / 'audio_ai_spectral_embeddings.yaml').read_text())
    box = 'spectral_embedding_visualizer_gallery'
    create = next(op for op in source['operations'] if op.get('create', {}).get('box') == box)
    await macro('spectral_gallery_create', [create], True)
    await batch('spectral_gallery_real_artifacts', [f'launch {box}',
        f'wait-output {box} --channel "spectral spaces" --timeout 180',
        f'screenshot-box {box} {OUT}/spectral_gallery.png'])

async def main():
    original = yaml.safe_load(subprocess.check_output(['git', 'show', BASE + ':dev_docs/agents/transcripts/loris_partial_explorer.yaml']))
    before = await loris('original', original)
    after = await loris('revised', yaml.safe_load((SOURCES / 'loris_partial_explorer.yaml').read_text()))
    assert before == after, (before, after)
    (OUT / 'wav-equivalence.json').write_text(json.dumps({'original': before, 'revised': after, 'identical': True}, indent=2))
    await audio_examples()
    print('Async adoption rehearsal passed', flush=True)

if __name__ == '__main__':
    asyncio.run(main())
