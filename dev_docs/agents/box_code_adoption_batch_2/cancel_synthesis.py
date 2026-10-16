"""Observe and cancel the native synthesis stage of the actual edited-partials example."""
import asyncio
import json
import re
import shutil
import struct
import subprocess
import time
from pathlib import Path
import yaml
from rehearse import ROOT, OUT, SOURCES, batch, macro

async def main():
    cache = OUT / ('synthesis-cancel-' + str(time.time_ns()))
    cache.mkdir()
    # The oldest existing entry is the source analysis captured by the preceding pilot.
    analysis = min((ROOT / 'build/field-loris/cache').glob('*.bin'), key=lambda p: p.stat().st_mtime)
    shutil.copy2(analysis, cache / analysis.name)
    source = yaml.safe_load((SOURCES / 'loris_partial_explorer.yaml').read_text())
    create = next(op for op in source['operations'] if op.get('create', {}).get('box') == 'loris_edit_partials')
    create['create']['properties']['code'] = create['create']['properties']['code'].replace(
        'LorisClient()', f'LorisClient(cacheDirectory = java.nio.file.Path.of("{cache}"))')
    # Stretch only this private cancellation fixture so the real worker stays observable.
    create['create']['properties']['code'] = create['create']['properties']['code'].replace(
        'frequencyScale = frequencyScale,', 'timeScale = 12.0, frequencyScale = frequencyScale,')
    await macro('cancel_synthesis_create', [create], True)
    box = 'loris_edit_partials'
    launched = asyncio.create_task(batch('cancel_synthesis_launch', [f'launch {box}']))
    observed = None
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        listing = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,command='], text=True)
        for line in listing.splitlines():
            if 'field_loris' not in line or str(cache) not in line: continue
            match = re.search(re.escape(str(cache)) + r'/request-[^ ]+/input.bin', line)
            if not match: continue
            path = Path(match.group())
            try: header = path.read_bytes()[:12]
            except FileNotFoundError: continue
            if len(header) == 12 and header[:8] == b'FLORIS01' and struct.unpack('>i', header[8:])[0] == 1:
                observed = {'pid': int(line.split()[0]), 'request': str(path.parent), 'operation': 'synthesize'}
                break
        if observed: break
        if launched.done(): await launched
        await asyncio.sleep(0.02)
    await launched
    assert observed, 'a native synthesize child was not observed'
    await batch('cancel_synthesis_stop', [f'stop {box}', f'lifecycle-census {box} --expect-stopped'])
    for _ in range(50):
        pids = {int(p) for p in subprocess.check_output(['ps', '-axo', 'pid='], text=True).split()}
        if observed['pid'] not in pids and not Path(observed['request']).exists(): break
        await asyncio.sleep(0.1)
    assert observed['pid'] not in pids
    assert not Path(observed['request']).exists()
    response = await batch('cancel_synthesis_output', [f'output {box} --channel "changed partials and sound" --kind display'])
    assert response['results'][0]['data']['count'] == 0, response
    observed.update(exited=True, requestRemoved=True, staleResultCount=0)
    (OUT / 'synthesis-cancellation.json').write_text(json.dumps(observed, indent=2))
    print('Actual composed synthesis cancellation passed', flush=True)

if __name__ == '__main__': asyncio.run(main())
