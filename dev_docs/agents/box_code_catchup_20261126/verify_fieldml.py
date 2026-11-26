"""Exercise the actual mixed-language graph and its FLine bars at two input values."""
import json
import shlex
import subprocess
import sys
import yaml
from run import S

source = (S.ROOT/'dev_docs/agents/transcripts/fieldml_reactive_bridge.yaml').read_text()
commands = []
for index, level in enumerate([0.5, 0.8]):
    transcript = yaml.safe_load(source.replace('exposure_', f'fml{index}_').replace('0.5, min', f'{level}, min'))
    for operation in transcript['operations']:
        box = operation.get('create', {})
        if box.get('box') != f'fml{index}_meters':
            continue
        box['properties']['code'] += f'''
check(abs(measured - {level}) < 1e-6)
check(abs(mapped - (0.1 + 0.9 * {level} * {level})) < 1e-6)
check(abs(bars[1].nodes.mapNotNull {{ it.toOrNull }}.maxOf {{ it.x }} - track * measured) < 1e-3)
check(abs(bars[3].nodes.mapNotNull {{ it.toOrNull }}.maxOf {{ it.x }} - track * mapped) < 1e-3)
println("CATCHUP_NOV26_FIELDML_{index}_PASSED")
'''
    path = S.OUT/f'fieldml-proof-{index}.yaml'
    path.write_text(yaml.safe_dump(transcript, sort_keys=False))
    commands += [f'macro write fml_{index} --file '+shlex.quote(str(path)), f'macro run fml_{index} --timeout 180']
args = [sys.executable, 'tools/field_backdoor.py', '--json', '--port', '9786', '--document', 'catchup_nov26_fieldml']
for command in commands:
    args += ['--command', command]
log = S.OUT/'fieldml-behavior.log'
with log.open('w') as stream:
    result = subprocess.run(args, cwd=S.ROOT, stdout=stream, stderr=subprocess.STDOUT)
if result.returncode:
    raise SystemExit('Actual FieldML graph failed: '+str(log))
# The markers are emitted only after actual property and geometry assertions; macro success rejects execution errors.
text = log.read_text()
for index in range(2):
    assert f'"html": "CATCHUP_NOV26_FIELDML_{index}_PASSED' in text, index
(S.HERE/'fieldml-verification.json').write_text(json.dumps(dict(inputs=[0.5, 0.8], passed=True,
    method='Actual Kotlin -> FieldML -> Kotlin graph; assert inherited input, computed opacity and both FLine bar widths for default and edited input.'), indent=2)+'\n')
print('FieldML bridge defaults and edited input passed, including FLine geometry')
