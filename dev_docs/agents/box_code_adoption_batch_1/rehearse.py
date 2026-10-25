"""Replay the helper-adoption pilot against a private warm Field session on port 9697."""
import asyncio
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
OUT = ROOT / 'build/box-code-revision/adoption-1'
OUT.mkdir(parents=True, exist_ok=True)
TRANSCRIPTS = ROOT / 'dev_docs/agents/transcripts'
BASE = '32744aa1d'

async def batch(name, commands, reset=False, expect=True):
    print(name, flush=True)
    response = await send_request(socket_path_for(9697), {
        'commands': commands, 'resetDoc': reset, 'scratch': True,
        'withPlaywright': True, 'timeout': 120,
    }, timeout=180)
    (OUT / (name + '.json')).write_text(json.dumps(response, indent=2))
    assert response.get('ok') is expect, (name, str(response)[-5000:])
    return response

async def macro(name, operations, reset=False, expect=True):
    p = OUT / (name + '.yaml')
    p.write_text(yaml.safe_dump({'operations': operations}, sort_keys=False))
    return await batch(name, [f'macro write {name} --file {shlex.quote(str(p))}', f'macro run {name}'], reset, expect)

async def fragment(name, box, code):
    return await macro(name, [{'execute': {'box': box, 'property': 'code', 'type': 'fragment', 'code': code}}])

async def replay(name, source):
    return await batch(name, [f'macro write {name} --file {shlex.quote(str(source))}', f'macro run {name}'], True)

async def mesh(label, source):
    await replay(label + '_mesh', source)
    await fragment(label + '_mesh_checks', 'mesh_bridges', '''
check(path.nodes.size == 121)
check(path.nodes.first() is FLine.MoveTo)
check(path.nodes.drop(1).all { it is FLine.LineTo })
check(coil.isWatertight())
check(kotlin.math.abs(total - boxVolume) / boxVolume < 1e-5)
println("mesh proof: nodes=${path.nodes.size}, closed=$closed, volume=$total, box=$boxVolume, tube=${coil.signedVolume()}")
''')
    await macro(label + '_mesh_layout', [
        {'execute': {'box': box, 'property': 'code', 'type': 'fragment',
                     'code': f'thisBox.frame = Rect({index * 4000}.0, 0.0, 1000.0, 100.0)'}}
        for index, box in enumerate(['mesh_primitives', 'mesh_marching', 'mesh_modelling',
                                    'mesh_process', 'mesh_measure', 'mesh_bridges', 'mesh_inline_view'])
    ])
    await batch(label + '_mesh_captures', [
        f'screenshot-box mesh_measure {OUT}/{label}_measure.png',
        f'screenshot-box mesh_bridges {OUT}/{label}_bridges.png',
        f'screenshot-box mesh_process {OUT}/{label}_process.png',
    ])

async def spin(size, model=0):
    source = yaml.safe_load((TRANSCRIPTS / 'spin_glass.yaml').read_text())
    for op in source['operations']:
        create = op.get('create', {})
        if create.get('box') == 'sg_update':
            create['properties']['code'] = create['properties']['code'].replace('val latticeSide = 128', f'val latticeSide = {size}').replace('prop<Int>(sgModel) = 0', f'prop<Int>(sgModel) = {model}')
    p = OUT / f'spin_{size}.yaml'; p.write_text(yaml.safe_dump(source, sort_keys=False))
    await replay(f'spin_{size}', p)
    await batch(f'spin_{size}_capture', [
        'wait-native-renderer sg_draw --frames 40 --timeout 60',
        'wait-output sg_measure --channel stats --contains "mean" --timeout 40',
        f'native-screenshot {OUT}/spin_{size}.png --uid sg_draw --wait-frames 10 --timeout 40',
    ])
    await fragment(f'spin_{size}_probe', 'sg_measure', f'''
val snapshot = requireNotNull(_fieldProbe?.readLatest())
check(snapshot.invocationCount == {size * size}L) {{ snapshot.toString() }}
check(snapshot.finiteCount == {size * size}L)
check(snapshot.sampleCount == {size * size})
check(sgModel == {model})
println("spin proof: size=$sgL, invocations=${{snapshot.invocationCount}}, finite=${{snapshot.finiteCount}}")
''')
    await fragment(f'spin_{size}_edit_controls', 'sg_update', 'sgAnneal = false\nsgTemperature = 1.25\nsgField = 0.2\nsgMode = 1')
    await batch(f'spin_{size}_live', [f'native-screenshot {OUT}/spin_{size}_live.png --uid sg_draw --wait-frames 20 --timeout 40'])
    await fragment(f'spin_{size}_live_check', 'sg_update', 'check(!sgAnneal && sgTemperature == 1.25 && sgField == 0.2 && sgMode == 1)')
    if model == 1:
        await fragment('gaussian_bonds', 'sg_update', 'sgJRight.mapAndRead { check((0..63).any { kotlin.math.abs(get(it)) != 1f }) }\nprintln("Gaussian bonds contain non-unit magnitudes")')
    await batch(f'spin_{size}_stop', ['stop sg_renderer'])

async def frames():
    await replay('frames', TRANSCRIPTS / 'frames_lifecycle.yaml')
    box = 'frames_lifecycle_cleanup'
    await batch('cleanup_stop', [f'launch {box}', f'wait-running {box} --state running --settle 0.3',
        f'lifecycle-census {box} --expect-exactly jobs=1', f'stop {box}', f'lifecycle-census {box} --expect-stopped',
        f'wait-output {box} --channel cleanup --contains "finally inside" --timeout 20',
        f'screenshot-box {box} {OUT}/cleanup_stop.png'])
    await batch('cleanup_return', [f'launch {box}', f'wait-running {box} --state running --settle 0.3',
        f'wait-running {box} --state stopped --settle 0.2 --timeout 20', f'lifecycle-census {box} --expect-stopped',
        f'output {box} --channel cleanup --kind display', f'screenshot-box {box} {OUT}/cleanup_return.png'])
    await batch('cleanup_rerun', [f'launch {box}', f'wait-running {box} --state running --settle 0.3',
        f'launch {box}', f'wait-running {box} --state running --settle 0.3',
        f'lifecycle-census {box} --expect-exactly jobs=1', f'stop {box}', f'lifecycle-census {box} --expect-stopped',
        f'wait-output {box} --channel cleanup --contains "finally inside" --timeout 20',
        f'screenshot-box {box} {OUT}/cleanup_rerun.png', 'stop frames_lifecycle_forever', 'stop frames_lifecycle_rerun'])

async def loris():
    source = yaml.safe_load((TRANSCRIPTS / 'loris_partial_explorer.yaml').read_text())
    create = source['operations'][0]
    await macro('loris_create', [create], True)
    box = create['create']['box']
    await batch('loris_complete', [f'launch {box}',
        f'wait-output {box} --channel provenance --timeout 120',
        f'screenshot-box {box} {OUT}/loris.png'])
    await batch('loris_rerun', [f'launch {box}', f'wait-output {box} --channel provenance --timeout 120',
        f'lifecycle-census {box} --expect-stopped'])

async def main():
    originals = OUT / 'originals'; originals.mkdir(exist_ok=True)
    p = originals / 'mesh_geometry_examples.yaml'
    p.write_bytes(subprocess.check_output(['git', 'show', BASE + ':dev_docs/agents/transcripts/' + p.name]))
    await mesh('original', p)
    await mesh('revised', TRANSCRIPTS / p.name)
    await spin(128)
    await spin(130, model=1)
    await frames()
    await loris()
    print('Adoption pilot runtime checks passed', flush=True)

if __name__ == '__main__':
    asyncio.run(main())
