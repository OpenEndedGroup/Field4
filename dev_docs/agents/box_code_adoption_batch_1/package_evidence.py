"""Persist the pilot's source-bound verdicts and genuine captures after both drivers pass."""
import hashlib
import html
import json
import re
import subprocess
import tarfile
from pathlib import Path
import yaml

ROOT = Path.cwd()
OUT = ROOT / 'build/box-code-revision/adoption-1'
DOC = ROOT / 'dev_docs/agents/box_code_adoption_batch_1'
NAMES = ['spin_glass', 'frames_lifecycle', 'mesh_geometry_examples', 'loris_partial_explorer']
records = []
for label in ['original', 'revised']:
    records += [label + '_mesh' + suffix for suffix in ['', '_checks', '_layout', '_captures']]
for size in [128, 130]:
    records += [f'spin_{size}' + suffix for suffix in ['', '_capture', '_probe', '_edit_controls', '_live', '_live_check', '_stop']]
records += ['gaussian_bonds', 'frames', 'cleanup_stop', 'cleanup_return', 'cleanup_rerun',
            'loris_create', 'loris_complete', 'loris_rerun', 'loris_cold_create', 'loris_cold_launch', 'loris_cold_stop', 'loris_after_cancel']
for size in [0, 129]:
    records += [f'invalid_size_{size}' + suffix for suffix in ['', '_no_buffers', '_stop']]
verdicts = {}
commands = 0
for name in records:
    result = json.loads((OUT / (name + '.json')).read_text())
    expected_rejection = name in ['invalid_size_0', 'invalid_size_129']
    assert result['ok'] is not expected_rejection, name
    commands += len(result['results'])
    verdicts[name] = {'ok': result['ok'], 'expectedRejection': expected_rejection,
                     'commands': len(result['results'])}

def last_cleanup(name):
    result = json.loads((OUT / (name + '.json')).read_text())
    events = [event for command in result['results'] for event in command.get('events', [])]
    for event in reversed(events):
        payload = event.get('payload', {})
        if payload.get('channel') != 'cleanup': continue
        text = payload.get('value', payload.get('html', payload.get('output', '')))
        if isinstance(text, str) and 'finally inside' in text:
            return html.unescape(re.sub('<[^>]*>', '\n', text))
    raise AssertionError(name)

cleanup = {}
for name, expected in [('cleanup_stop', ['.end', 'finally']),
                       ('cleanup_return', ['.end', 'finally', 'returned', 'finally']),
                       ('cleanup_rerun', ['.end', 'finally', 'returned', 'finally', '.end', 'finally', '.end', 'finally'])]:
    text = last_cleanup(name)
    actual = re.findall(r'(?m)^\s*(\.end|finally|returned)(?=[\s,])', text)
    assert actual == expected, (name, actual)
    cleanup[name] = actual

preservation = {}
for name in NAMES:
    path = 'dev_docs/agents/transcripts/' + name + '.yaml'
    before = yaml.safe_load(subprocess.check_output(['git', 'show', '32744aa1d:' + path]))
    after = yaml.safe_load((ROOT / path).read_text())
    changed = []
    for a, b in zip(before['operations'], after['operations']):
        if 'create' not in a: continue
        for prop in list(a['create']['properties']):
            if prop not in ['code', 'compute', 'vertex', 'fragment']: continue
            old = a['create']['properties'][prop]; new = b['create']['properties'][prop]
            if old != new: changed.append(a['create']['box'] + ':' + prop)
            assert re.findall(r'(?m)^\s*//\s*%-.*$', old) == re.findall(r'(?m)^\s*//\s*%-.*$', new)
            a['create']['properties'][prop] = b['create']['properties'][prop] = '<code>'
    assert before == after, name
    preservation[path] = {'sha256': hashlib.sha256((ROOT / path).read_bytes()).hexdigest(),
                          'nonCodeStructureUnchanged': True, 'fragmentMarkersUnchanged': True,
                          'changedBodies': changed}

record = {'baseline': '32744aa1d', 'scope': 'representative edit pilot, four transcripts; not corpus completion',
          'source': preservation, 'batches': verdicts, 'commandsIncludingExpectedRejections': commands,
          'cleanupOrder': cleanup, 'nativeCancellation': json.loads((OUT / 'native-cancellation.json').read_text()),
          'deferred': ['Loris async bodies 2-5', 'Loris whirlpool creative controls', 'all other corpus groups'],
          'descriptionsHandoff': '14a871f3', 'fullGuard': 'landing queue'}
(DOC / 'verification.json').write_text(json.dumps(record, indent=2) + '\n')
with tarfile.open(DOC / 'evidence.tar.gz', 'w:gz') as archive:
    for name in records:
        archive.add(OUT / (name + '.json'), arcname='verdicts/' + name + '.json')
    for path in OUT.glob('*.png'):
        archive.add(path, arcname='captures/' + path.name)
    for path in OUT.glob('*.yaml'):
        archive.add(path, arcname='fixtures/' + path.name)
    for name in NAMES:
        blob = subprocess.check_output(['git', 'show', '32744aa1d:dev_docs/agents/transcripts/' + name + '.yaml'])
        original = OUT / 'originals' / (name + '.yaml'); original.write_bytes(blob)
        archive.add(original, arcname='originals/' + original.name)
    for path in OUT.glob('*.log'):
        if path.name != 'server.log': archive.add(path, arcname='logs/' + path.name)
    archive.add(OUT / 'native-cancellation.json', arcname='native-cancellation.json')
print(f'Packaged {len(records)} batches, {commands} commands including two intentional rejections')
