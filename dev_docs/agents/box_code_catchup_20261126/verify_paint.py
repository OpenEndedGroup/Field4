"""Exercise the real connected paint graph before/after, with two paint seeds."""
import json
import re
import shlex
import subprocess
import sys

import yaml

from run import S


def main():
    name = 'dev_docs/agents/transcripts/addressable_paint_strokes.yaml'
    before = subprocess.check_output(['git', 'show', 'HEAD:'+name], cwd=S.ROOT, text=True)
    commands = []
    cases = []
    for seed in [3, 4]:
        for label, source in [('before', before), ('after', (S.ROOT/name).read_text())]:
            index = len(cases)
            source = source.replace('paint_strokes_', f'paint{index}_')
            source = source.replace('seed = 3', f'seed = {seed}')
            transcript = yaml.safe_load(source)
            token = f'CATCHUP_NOV26_PAINT_{index}'
            # This additional child observes the sheet actually exported by the authored parent.
            # The original edit, note/tool registration and four-frame sway children run unchanged.
            proof = ('check(PaintCanvas.isAvailable) { PaintLibrary.describe() }\n'
                     'val inherited = fromContext<PaintStrokes>()\n'
                     f'println("{token}|" + inherited.png().contentHashCode() + ":" + inherited.describe())\n')
            uid = f'paint{index}_proof'
            transcript['operations'] += [
                {'create': {'box': uid, 'name': token, 'properties': {'code': proof}}},
                {'connect': {'parent': f'paint{index}_creature', 'child': uid}},
                {'execute': {'box': uid, 'property': 'code'}},
            ]
            target = S.OUT/f'paint-proof-{index}.yaml'
            target.write_text(yaml.safe_dump(transcript, sort_keys=False))
            commands += [f'macro write paint_{index} --file '+shlex.quote(str(target)), f'macro run paint_{index} --timeout 240']
            cases.append(dict(seed=seed, label=label, token=token))
    log = S.OUT/'paint-behavior.log'
    texts = []
    for index in range(len(cases)):
        # Named AI tools belong to one box per document: independent oracles get independent hosts.
        args = [sys.executable, 'tools/field_backdoor.py', '--json', '--port', '9786', '--document', f'catchup_nov26_paint_{index}']
        for command in commands[index*2:index*2+2]:
            args += ['--command', command]
        case_log = S.OUT/f'paint-behavior-{index}.log'
        with case_log.open('w') as stream:
            result = subprocess.run(args, cwd=S.ROOT, stdout=stream, stderr=subprocess.STDOUT)
        texts.append(case_log.read_text())
        log.write_text('\n'.join(texts))
        if result.returncode:
            raise SystemExit('Connected paint verification failed: '+str(case_log))
    decoder = json.JSONDecoder()
    outputs = []
    def collect(value):
        if isinstance(value, dict):
            if isinstance(value.get('html'), str):
                outputs.append(value['html'])
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)
    for text in texts:
        pos = 0
        while pos < len(text):
            if text[pos] != '{':
                pos += 1
                continue
            try:
                value, size = decoder.raw_decode(text[pos:])
            except ValueError:
                pos += 1
                continue
            pos += size
            collect(value)
    values = dict(re.findall(r'(CATCHUP_NOV26_PAINT_\d+)\|(-?\d+)', '\n'.join(outputs)))
    for a, b in zip(cases[::2], cases[1::2]):
        assert values.get(a['token']) and values[a['token']] == values.get(b['token']), (a, b, values)
    (S.HERE/'paint-verification.json').write_text(json.dumps(dict(cases=cases, pngHashes=values,
        method='All four authored connected boxes execute, including copies/edits, tools registration and sway; compare inherited parent sheet PNG bytes before/after for seeds 3 and 4, using the matching native paint library.'), indent=2)+'\n')
    print('Connected paint graphs passed; two before/after native PNG equivalence pairs')


if __name__ == '__main__':
    main()
