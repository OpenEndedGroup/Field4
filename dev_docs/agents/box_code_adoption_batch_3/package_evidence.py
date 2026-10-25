"""Bind the batch's final sources to genuine execution, capture and formatter evidence."""
import hashlib
import io
import json
import re
import subprocess
import tarfile
from pathlib import Path
import yaml
from format_docs import sections

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
OUT = ROOT / 'build/box-code-revision/adoption-3'
BASE = '72cdc6dcc'
SOURCES = [
    'public/docs/interpolants_examples.md', 'public/docs/interaction_nets_examples.md',
    'dev_docs/agents/transcripts/imaging_vector_kernels.yaml',
    'dev_docs/agents/transcripts/spatial_geometry_examples.yaml',
]
FINDINGS = ['0abf058ae10d-01', '0abf058ae10d-02', '0abf058ae10d-03', '0abf058ae10d-04',
            'bb71d7b005b0-01', '293b7bec8185-01', 'bc7141d7966c-01', 'bc7141d7966c-02']

def sha(data):
    return hashlib.sha256(data).hexdigest()

def mask_yaml(value):
    if isinstance(value, dict):
        return {k: '<code>' if k == 'code' else mask_yaml(v) for k, v in value.items()}
    if isinstance(value, list): return [mask_yaml(v) for v in value]
    return value

def mask_markdown(text):
    for start, end in reversed(list(sections(text))):
        text = text[:start] + '<code>\n' + text[end:]
    return text

def compact(value):
    if isinstance(value, str) and len(value) > 8192:
        data = value.encode()
        return {'stringBytes': len(data), 'sha256': sha(data)}
    if isinstance(value, dict): return {k: compact(v) for k, v in value.items()}
    if isinstance(value, list): return [compact(v) for v in value]
    return value

def main():
    entries = {}
    source_hashes = {}
    for source in SOURCES:
        before = subprocess.check_output(['git', 'show', BASE + ':' + source], cwd=ROOT)
        after = (ROOT / source).read_bytes()
        source_hashes[source] = {'original': sha(before), 'revised': sha(after)}
        entries['sources/original/' + source] = before
        entries['sources/revised/' + source] = after
        if source.endswith('.yaml'):
            assert mask_yaml(yaml.safe_load(before)) == mask_yaml(yaml.safe_load(after)), source
        else:
            assert mask_markdown(before.decode()) == mask_markdown(after.decode()), source
        assert re.findall(rb'//%[-+]', before) == re.findall(rb'//%[-+]', after), source
    verdicts = []
    failures = []
    for path in sorted(OUT.glob('*.json')):
        result = json.loads(path.read_text())
        if not isinstance(result, dict) or 'results' not in result: continue
        if path.name.endswith('.failure.json'):
            failures.append(path.name)
            entries['diagnostics/' + path.name] = path.read_bytes()
            continue
        assert result['ok'], path
        assert all(not x.get('timedOut') for x in result['results']), path
        for item in result['results']:
            assert not item.get('data', {}).get('captureWarnings'), path
        verdicts.append({'name': path.stem, 'commands': len(result['results']), 'ok': True})
        entries['verdicts/' + path.name] = json.dumps(compact(result), indent=2).encode()
    for pattern in ['*.png', '*.yaml', '*.log']:
        for path in sorted(OUT.glob(pattern)):
            entries['rehearsal/' + path.name] = path.read_bytes()
    equivalence = json.loads((OUT / 'equivalence.json').read_text())
    assert equivalence['identical']
    entries['equivalence.json'] = (OUT / 'equivalence.json').read_bytes()
    captures = {p.name: sha(p.read_bytes()) for p in sorted(OUT.glob('*.png'))}
    record = {
        'baseline': BASE, 'branch': 'codex/box-code-helper-adoption-3',
        'sources': source_hashes, 'addressedFindings': FINDINGS,
        'nonCodeStructurePreserved': True, 'fragmentMarkersPreserved': True,
        'runtimeVerdicts': verdicts,
        'successfulBatches': len(verdicts), 'successfulCommands': sum(v['commands'] for v in verdicts),
        'captures': captures, 'retainedDiagnostics': failures,
        'largeVerdictStrings': 'Length and SHA-256; exact programs and genuine PNG files retained separately.',
        'equivalence': equivalence,
        'limits': [
            'Original interpolant states 4 and 5 needed the two recorded collection-overload compilation repairs.',
            'Random correlation examples compare dimensions/finite results, not exact random samples.',
            'Animated sweeps compare live output and lifecycle, not wall-clock-identical frames.',
            'Timing figures are observations from this machine, not performance thresholds.',
            'Screenshots are genuine separate headless-browser captures, not public docs replacements.',
            'No GLSL properties occur in these four source groups.',
        ],
    }
    (HERE / 'verification.json').write_text(json.dumps(record, indent=2) + '\n')
    entries['verification.json'] = (HERE / 'verification.json').read_bytes()
    with tarfile.open(HERE / 'evidence.tar.gz', 'w:gz') as archive:
        for name, data in entries.items():
            item = tarfile.TarInfo(name); item.size = len(data)
            archive.addfile(item, io.BytesIO(data))
    diff = subprocess.check_output(['git', 'diff', '--unified=0', BASE, '--', *SOURCES], cwd=ROOT)
    (HERE / 'sources.diff').write_bytes(diff)
    print(f'{len(verdicts)} successful batches, {record["successfulCommands"]} commands, {len(captures)} genuine captures; {len(failures)} diagnostic failures retained')
    print(f'archive {(HERE / "evidence.tar.gz").stat().st_size} bytes')

if __name__ == '__main__': main()
