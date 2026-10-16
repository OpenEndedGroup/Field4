"""Boundary checks following rehearse.py, using the same private warm session."""
import asyncio
import json
import subprocess
import time
from pathlib import Path
import yaml
from rehearse import ROOT, OUT, TRANSCRIPTS, batch, macro, fragment, replay

async def invalid_sizes():
    source = yaml.safe_load((TRANSCRIPTS / 'spin_glass.yaml').read_text())
    for size in (0, 129):
        ops = []
        for op in source['operations']:
            if 'create' in op and op['create']['box'] in ('sg_renderer', 'sg_update'):
                clone = yaml.safe_load(yaml.safe_dump(op))
                code = clone['create']['properties']['code']
                clone['create']['properties']['code'] = code.replace('val latticeSide = 128', f'val latticeSide = {size}')
                ops.append(clone)
        ops += [{'connect': {'parent': 'sg_renderer', 'child': 'sg_update'}},
                {'launch': {'box': 'sg_renderer', 'strand': 'renderer'}}, {'await': {}},
                {'launch': {'box': 'sg_update'}}, {'await': {}}]
        response = await macro(f'invalid_size_{size}', ops, reset=True, expect=False)
        assert 'L must be positive and even' in json.dumps(response)
        await fragment(f'invalid_size_{size}_no_buffers', 'sg_update', '''
check(fromContextWithNameOrNull<FrameBufferGroup>("sgSpins") == null)
println("invalid size rejected before spin allocation")
''')
        await batch(f'invalid_size_{size}_stop', ['stop sg_renderer'])

async def cancellation():
    source = yaml.safe_load((TRANSCRIPTS / 'loris_partial_explorer.yaml').read_text())
    create = source['operations'][0]
    cache = OUT / ('cold-cache-' + str(time.time_ns()))
    code = create['create']['properties']['code']
    code = code.replace('LorisClient()', f'LorisClient(cacheDirectory = java.nio.file.Path.of("{cache}"))')
    create['create']['properties']['code'] = code
    await macro('loris_cold_create', [create], reset=True)
    box = create['create']['box']
    await batch('loris_cold_launch', [f'launch {box}', f'wait-output {box} --channel status --contains "native worker" --timeout 30'])
    processes = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,command='], text=True)
    workers = [int(line.split()[0]) for line in processes.splitlines() if 'field_loris' in line and str(cache) in line]
    assert workers, 'cold native worker finished before it could be observed'
    await batch('loris_cold_stop', [f'stop {box}', f'lifecycle-census {box} --expect-stopped'])
    for _ in range(50):
        live = set(int(p) for p in subprocess.check_output(['ps', '-axo', 'pid='], text=True).split())
        temporary = list(cache.glob('request-*'))
        if not (set(workers) & live) and not temporary: break
        await asyncio.sleep(0.1)
    assert not (set(workers) & live), workers
    assert not temporary, temporary
    response = await batch('loris_after_cancel', [f'output {box} --channel provenance --kind display',
        f'screenshot-box {box} {OUT}/loris_cancelled.png'])
    # No completed result/provenance may be published by the stopped run.
    assert response['results'][0]['data']['count'] == 0, response['results'][0]
    (OUT / 'native-cancellation.json').write_text(json.dumps({'workers': workers, 'exited': True, 'temporaryDirectoriesRemoved': True}, indent=2))

async def main():
    await invalid_sizes()
    await cancellation()
    print('Boundary checks passed', flush=True)

if __name__ == '__main__':
    asyncio.run(main())
