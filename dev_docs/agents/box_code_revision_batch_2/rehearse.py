import asyncio
import json
import shlex
import shutil
import re
import subprocess
import sys
from pathlib import Path
import yaml

ROOT = Path.cwd()
sys.path.insert(0, str(ROOT / 'tools'))
from field_session import send_request, socket_path_for
OUT = ROOT / 'build/box-code-revision/batch-2'
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'originals').mkdir(exist_ok=True)
for name in ('frames_lifecycle', 'dataframe_typed_columns'):
    content = subprocess.run(['git', 'show', 'c51633025:dev_docs/agents/transcripts/' + name + '.yaml'],
                             cwd=ROOT, check=True, capture_output=True).stdout
    (OUT / 'originals' / (name + '.yaml')).write_bytes(content)

async def batch(name, commands, reset=False):
    print(name, flush=True)
    response = await send_request(socket_path_for(9486), {
        'commands': commands, 'resetDoc': reset, 'scratch': True,
        'withPlaywright': True, 'timeout': 120,
    }, timeout=180)
    (OUT / (name + '.json')).write_text(json.dumps(response, indent=2))
    if not response.get('ok'):
        print(json.dumps(response, indent=2)[-9000:], flush=True)
        raise RuntimeError(name)
    return response

async def fragment(name, box, code):
    path = OUT / (name + '.yaml')
    path.write_text(yaml.safe_dump({'operations': [{'execute': {
        'box': box, 'property': 'code', 'type': 'fragment', 'code': code,
    }}]}, sort_keys=False))
    return await batch(name, ['macro write ' + name + ' --file ' + shlex.quote(str(path)), 'macro run ' + name])

async def replay(name, source):
    return await batch(name, ['macro write example --file ' + shlex.quote(str(source)), 'macro run example'], True)

async def frames(label, source):
    await replay(label + '_frames', source)
    for box in ('frames_lifecycle_forever', 'frames_lifecycle_rerun'):
        await batch(label + '_' + box, [
            f'wait-running {box} --state running --settle 0.2',
            f'lifecycle-census {box} --expect-exactly jobs=1',
            f'launch {box}',
            f'wait-running {box} --state running --settle 0.2',
            f'lifecycle-census {box} --expect-exactly jobs=1',
            f'stop {box}', f'lifecycle-census {box} --expect-stopped',
        ])
    await batch(label + '_self_stop', [
        'wait-running frames_lifecycle_stops --state stopped --settle 0.2',
        'lifecycle-census frames_lifecycle_stops --expect-stopped',
    ])
    await fragment(label + '_static_trail', 'frames_lifecycle_forever', 'draw(1.5)')
    await fragment(label + '_static_progress', 'frames_lifecycle_stops', 'draw(3.0, true)')
    await batch(label + '_captures', [
        f'screenshot-box frames_lifecycle_forever {OUT}/{label}_trail.png --padding 12',
        f'screenshot-box frames_lifecycle_stops {OUT}/{label}_progress.png --padding 12',
    ])

async def main():
    probe = OUT / 'dataframe_formatter_probe.yaml'
    shutil.copyfile(ROOT / 'dev_docs/agents/transcripts/dataframe_typed_columns.yaml', probe)
    frames_probe = OUT / 'frames_formatter_probe.yaml'
    shutil.copyfile(ROOT / 'dev_docs/agents/transcripts/frames_lifecycle.yaml', frames_probe)
    await batch('format_fixed', ['run-jvm-main afield.examples.BoxCodeFormat --arg=--imports '
        '--arg=' + str(frames_probe) + ' --arg=' + str(probe) + ' --timeout 120'])
    assert len(re.findall(r'^\s*//%-\s*$', probe.read_text(), re.M)) == 1
    await replay('dataframe_formatter_probe', probe)
    await frames('original', OUT / 'originals/frames_lifecycle.yaml')
    await frames('revised', ROOT / 'dev_docs/agents/transcripts/frames_lifecycle.yaml')
    box = 'frames_lifecycle_cleanup'
    await batch('cleanup_cancel', [f'launch {box}', f'wait-running {box} --state running --settle 0.3',
        f'lifecycle-census {box} --expect-exactly jobs=1', f'stop {box}', f'lifecycle-census {box} --expect-stopped'])
    await fragment('cleanup_cancel_assert', box,
        'check(notes.size == 2) { notes.toString() }\n'
        'check(notes[0].startsWith(".end"))\ncheck(notes[1].startsWith("finally"))\n'
        'println("stop: end then finally")')
    await batch('cleanup_return', [f'launch {box}', f'wait-running {box} --state running --settle 0.3',
        f'wait-running {box} --state stopped --settle 0.2', f'lifecycle-census {box} --expect-stopped'])
    await fragment('cleanup_return_assert', box,
        'check(notes.size == 2) { notes.toString() }\n'
        'check(notes[0].startsWith("returned"))\ncheck(notes[1].startsWith("finally"))\n'
        'println("return: finally without end")')
    await replay('dataframe', ROOT / 'dev_docs/agents/transcripts/dataframe_typed_columns.yaml')
    for index in (1, 2):
        await fragment('dataframe_query_' + str(index), 'dataframe_live',
            'check(p.age.toList() == listOf(15, 30, 45))\n'
            'check(p.filter { age >= 18 }.name.toList() == listOf("Bob", "Cara"))\n'
            'println("Peach columns remain typed across repeated fragments")')
    await batch('dataframe_rerun', ['run dataframe_live'])
    print('Batch 2 runtime checks passed', flush=True)

if __name__ == '__main__':
    asyncio.run(main())
