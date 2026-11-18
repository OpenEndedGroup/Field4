"""Apply narrowly reviewed source edits to the frozen delta, without visiting preambles."""
from pathlib import Path
import json
import re
import run as R

S = R.S
if (S.HERE / "revision-verification.json").exists():
    raise SystemExit("Reviewed edits already applied; resume formatting or verification instead.")
rows = json.loads((S.OUT / 'bodies.json').read_text())
plan = []
for path in json.loads((S.HERE / 'scope.json').read_text())['paths']:
    source = (S.ROOT / path).read_text()
    edits = []
    original = __import__('subprocess').check_output(['git', 'show', f"{json.loads((S.HERE/'scope.json').read_text())['head']}:{path}"], cwd=S.ROOT, text=True)
    hashes = {r['sha256'] for r in rows if r['path'] == path}
    selected = {i for i, r in enumerate(S.spans(path, original)) if r['sha256'] in hashes}
    for i, row in enumerate(S.spans(path, source)):
        if i not in selected or row['language'] not in {'kotlin', 'glsl'} or row.get('property') == 'preamble':
            continue
        code = before = row['code']
        # Explicit collection literals in authored Kotlin, with no change to ordering or values.
        if row['language'] == 'kotlin':
            for old, new in [
                ('val starts = listOf(', 'val starts = ['),
                ('for (loop in listOf(muse.leftLoop!!, muse.rightLoop!!))', 'for (loop in [muse.leftLoop!!, muse.rightLoop!!])'),
                ('degrees = listOf(7, 9, 11)', 'degrees = [7, 9, 11]'),
                ('degrees = emptyList()', 'degrees = []'),
            ]:
                if old == 'val starts = listOf(' and old in code:
                    code = code.replace(old, new).replace('Triple(16f, -2f, PI.toFloat()),\n        )', 'Triple(16f, -2f, PI.toFloat()),\n        ]')
                elif old != 'val starts = listOf(': code = code.replace(old, new)
            if path == 'public/docs/linear_algebra.md' and 'listOf(' in code:
                code = code.replace('listOf(\n', '[\n', 1)
                code = re.sub(r'(// a horizontal one\n[ \t]*)\)', r'\1]', code, count=1)
            # Private offline simulators must close even if rendering or display fails.
            for variable in ['sim', 'fm1']:
                opening = f'val {variable} = FM1Sim.create()\n'
                closing = f'{variable}.close()'
                if opening in code and code.rstrip().endswith(closing):
                    a = code.index(opening)
                    b = code.rindex(closing)
                    # For the per-project loop, the close is followed by the loop's closing brace.
                elif opening in code and re.search(rf'\n    {variable}\.close\(\)\n}}\s*$', code):
                    code = code.replace(f'    val {variable} = FM1Sim.create()\n', f'    FM1Sim.create().use {{ {variable} ->\n', 1)
                    code = code.replace(f'    {variable}.close()\n}}', '    }\n}', 1)
                    continue
                else: continue
                code = code[:a]+f'FM1Sim.create().use {{ {variable} ->\n'+code[a+len(opening):b]+'}\n'
            if path.endswith('cerf_explorer.yaml'):
                code = code.replace("grows, splits in two and pushes its\ndaughters apart", "grows, splits in two and pushes its daughters apart")
        elif path.endswith('bonded_organisms.yaml'):
            code = code.replace('vec4 pv;','vec4 pv; // world position xy, velocity zw').replace('vec4 e;', 'vec4 e; // energy, cell kind, organism id, padding')
            code = code.replace('CellBuf cells;', 'CellBuf cells; // two capacity-sized halves; step parity selects the source')
            code = code.replace('BondBuf bonds;', 'BondBuf bonds; // four neighbour slots per cell; -1 marks an empty slot')
            code = code.replace('uint latest;', 'uint latest; // most recently written cell half, 0 or 1')
            code = code.replace('uint capacity;', 'uint capacity; // stride between the two cell halves')
            code = code.replace('float viewHalfHeight;', 'float viewHalfHeight; // visible world units above and below the view centre')
            code = code.replace('float dt;', 'float dt; // simulated seconds per step')
        elif path.endswith('cuda_particles.yaml'):
            code = code.replace('Particles particles;', 'Particles particles; // shared CUDA/Vulkan float4 buffer').replace('float aspectRatio;', 'float aspectRatio; // window width / height')
        if code != before:
            replacement = code
            if row['kind'] == 'yaml':
                replacement = '|\n' + ''.join(' '*row['indent']+line+'\n' if line else '\n' for line in code.rstrip('\n').splitlines())
            edits.append((row['start'], row['end'], replacement))
            plan.append(dict(path=path, span=i, language=row['language'], beforeSha256=S.digest(before), afterSha256=S.digest(code)))
    for start, end, code in sorted(edits, reverse=True):
        source = source[:start] + code + source[end:]
    if edits: (S.ROOT/path).write_text(source)

# The walkthrough said two kernels, but omitted seed and its function handle.
p = S.ROOT / 'public/docs/cuda.md'
s = p.read_text()
canonical = S.ROOT / 'dev_docs/agents/transcripts/cuda_particles.yaml'
import yaml
macro = yaml.safe_load(canonical.read_text())
compute = next(op['create']['properties']['code'] for op in macro['operations'] if op.get('create', {}).get('box') == 'cuda_flow')
seed = re.search(r'    extern "C" __global__ void seed\(.*?\n    }\n', compute, re.S)[0]
anchor = '    // Advect every particle through a flow that turns slowly with t.'
assert s.count(anchor) == 1
s = s.replace(anchor, seed+'\n'+anchor).replace('val flow = program.function("flow")', 'val seed = program.function("seed")\nval flow = program.function("flow")', 1)
s = s.replace('so rerunning the box keeps the particles where they are; a new size makes a new buffer.', 'so requesting the buffer again preserves its contents; a new size makes a new buffer.\nThe explicit `seed.launch` below resets those contents when this example is rerun.')
p.write_text(s)
(S.HERE/'revision-verification.json').write_text(json.dumps({'edits':plan,'cuda':'Added the exact seed kernel from the canonical transcript and its missing function handle; corrected the buffer-reuse/rerun explanation.'}, indent=2)+'\n')
print(len(plan), 'bodies revised, plus the CUDA walkthrough kernel/handle repair')
