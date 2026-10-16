"""Exercise this batch's real programs in a private Field/browser session on 9699."""
import asyncio
import json
import re
import shlex
import subprocess
import sys
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'public')]
from field_session import send_request, socket_path_for
from docs_hooks import extract_static_code
from format_docs import sections

BASE = '72cdc6dcc'
OUT = ROOT / 'build/box-code-revision/adoption-3'
PORT = 9699
RECORD = []
PROOF = '''
fun proof(value: Any?) {
    val bytes = value.toString().toByteArray()
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    println("PROOF " + digest.joinToString("") { "%02x".format(it.toInt() and 255) })
}
fun geometry(lines: List<FLine>) = lines.map { f ->
    f.nodes.map { it.javaClass.simpleName + ":" + it.toOrNull.toString() }
}
'''

async def batch(name, commands, reset=False):
    print(name, flush=True)
    result = await send_request(socket_path_for(PORT), {
        'commands': commands, 'resetDoc': reset, 'scratch': True,
        'withPlaywright': True, 'timeout': 180,
    }, timeout=240)
    previous = OUT / (name + '.json')
    if previous.exists() and not json.loads(previous.read_text()).get('ok'):
        previous.rename(OUT / (name + '.failure.json'))
    (OUT / (name + '.json')).write_text(json.dumps(result, indent=2))
    RECORD.append({'name': name, 'ok': result.get('ok'), 'commands': len(commands)})
    (OUT / 'batches.json').write_text(json.dumps(RECORD, indent=2))
    assert result.get('ok'), (name, str(result)[-5000:])
    return result

async def macro(name, ops, reset=False):
    p = OUT / (name + '.yaml')
    p.write_text(yaml.safe_dump({'operations': ops}, sort_keys=False))
    return await batch(name, [f'macro write {name} --file {shlex.quote(str(p))}', f'macro run {name}'], reset)

def proof_of(result):
    values = []
    for entry in result['results']:
        for out in entry.get('data', {}).get('output', []):
            if out.get('html', '').startswith('PROOF '):
                values.append(out['html'].removeprefix('PROOF '))
    assert len(values) == 1, values
    return values[0]

def source(path, label):
    return subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=ROOT, text=True) if label == 'original' else (ROOT / path).read_text()

async def program(name, code, probe='', channel='main', capture=False):
    full = code + '\n' + (PROOF + probe if probe else '')
    saved = OUT / (name + '.yaml')
    verdict = OUT / (name + '.json')
    capture_verdict = OUT / (name + '_capture.json')
    if '--resume' in sys.argv and saved.exists() and verdict.exists() and 'animate {' not in code:
        same = yaml.safe_load(saved.read_text())['operations'][0]['create']['properties']['code'] == full
        good = json.loads(verdict.read_text()).get('ok')
        captured = not capture or (capture_verdict.exists() and json.loads(capture_verdict.read_text()).get('ok'))
        if same and good and captured:
            print(name + ' (retained matching successful run)', flush=True)
            return proof_of(json.loads(verdict.read_text())) if probe else None
    ops = [
        {'create': {'box': 'subject', 'name': name, 'frame': {'x': 0., 'y': 0., 'w': 1200., 'h': 100.}, 'properties': {'code': full}}},
    ]
    animated = 'animate {' in code
    if not animated:
        ops.append({'execute': {'box': 'subject', 'property': 'code'}})
    result = await macro(name, ops, True)
    if animated:
        await batch(name + '_launch', ['launch subject', 'wait-output subject --channel main --timeout 30'])
    if capture:
        await batch(name + '_capture', [f'wait-output subject --channel {shlex.quote(channel)} --timeout 60', f'screenshot-box subject {OUT}/{name}.png'])
    return proof_of(result) if probe else None

async def docs(label):
    answers = {}
    page = 'public/docs/interpolants_examples.md'
    text = source(page, label)
    probes = [
        'proof([a.toList(), b.toList(), c.toList(), tgt.toList()])',
        'check(f.nodes.size == 180); check(f.nodes.first() is FLine.MoveTo); proof(geometry([f]))',
        'check(timeLine.nodes.size == 220); proof(geometry([stepLine, timeLine]))',
        'proof([a.toList(), b.toList(), c.toList(), tgt.toList()])',
        'check(family.all { it.nodes.size == 601 }); proof(geometry(family + jolt + past))',
        'check([gain, path, code].all { it.nodes.size == 221 }); proof(geometry([gain, path, code]))',
        'check(dcor.all { it.isFinite() } && xi.all { it.isFinite() }); proof([dcor.size, xi.size])',
        'check(metrics.values.all { it.isFinite() }); proof(metrics.keys)',
    ]
    channels = ['main', 'interpolants-diff', 'interpolants-diff', 'value', 'family', 'keyframe-morph', 'output', 'source']
    for i, (start, end) in enumerate(sections(text)):
        code = extract_static_code(lines=text[start:end].splitlines())
        if i == 4 and label == 'original':
            # The baseline's literal RHS is ambiguous under today's collection overloads.
            # Apply only the same explicit element concatenation used in the teaching fix.
            a = code.index('fun guides(')
            b = code.index('val family =', a)
            guides = code[a:b].replace('} + [\n    fline {', '} + fline {')
            guides = guides.replace('    },\n    fline {', '    } + fline {').replace('    }\n]\n', '    }\n')
            code = code[:a] + guides + code[b:]
        if i == 5 and label == 'original':
            # The list literal also makes lines(List)/lines(vararg) ambiguous.
            code = code.replace('lines([path, gain, code],', 'lines(path, gain, code,')
        # showSignals' default is checked from the source, not guessed for captures.
        channel = channels[i]
        answers['interpolants_' + str(i)] = await program(f'{label}_interpolants_{i}', code, probes[i], channel, label == 'revised')
    page = 'public/docs/interaction_nets_examples.md'
    text = source(page, label)
    for i, (start, end) in enumerate(sections(text)):
        code = extract_static_code(lines=text[start:end].splitlines())
        lines = ('beforeLines, middleLines, afterLines' if i == 4 else 'beforeLines, afterLines') if label == 'original' else ('before.lines, middle.lines, after.lines' if i == 4 else 'before.lines, after.lines')
        subject = 'subject' if i == 4 else 'net'
        probe = f'proof([geometry([{lines}].flatten()), {subject}.nodes.size, {subject}.edges.size, {subject}.totalEnergy()])'
        answers['interaction_' + str(i)] = await program(f'{label}_interaction_{i}', code, probe, 'overview', label == 'revised')
    return answers

TRANSCRIPT_PROBES = {
    'imaging_perf_pyrdown': 'check(worst < 1e-5f); proof([fused.data.toList(), naive.data.toList(), worst])',
    'imaging_perf_boxblur': 'check(worst < 1e-4f); proof([running.data.toList(), kernelled.data.toList(), worst])',
    'imaging_perf_bilateral': 'check(edgeOf(bil) > edgeOf(gauss)); proof([bil.data.toList(), gauss.data.toList()])',
    'spatial_delaunay': 'check(triangles.size == 2*n-2-h); check(delaunay.edges.size == 3*n-3-h); proof([triangles.toString(), delaunay.edges.toString(), hull.hullIndices.toList()])',
    'spatial_apollonius': 'check(worstTie < 1e-5); proof([arcs(equal), straights(equal), arcs(mixed), fullCircles(swallowed), enclosed(swallowed), worstTie])',
    'spatial_contours': 'check(atZero.isEmpty()); proof(byLevel.map { it.first to it.second.map { c -> c.map { p -> p.toString() } } })',
    'spatial_marching': '''
check(planeContours.size == 1 && aligned.size == 1)
val grids = [gridLines(-1.0, 1.0, -1.0, 1.0, 4, 4), gridLines(-2.0, 2.0, -2.0, 2.0, 16, 16), gridLines(-1.05, 1.03, -1.02, 1.07, 8, 8)]
check(grids.map { it.nodes.size } == [20, 68, 36])
check(grids.all { g -> g.nodes.withIndex().all { (i, n) -> if (i % 2 == 0) n is FLine.MoveTo else n is FLine.LineTo } })
proof([planeContours.toString(), aligned.toString(), branches.toString(), geometry(grids)])
''',
    'spatial_clustering': 'check(nested && areSamples); proof([cut3.labels.toList(), cut6.labels.toList(), medoids.distortion, medians.objective])',
}

async def transcripts(label):
    answers = {}
    for name in ['imaging_vector_kernels', 'spatial_geometry_examples']:
        doc = yaml.safe_load(source('dev_docs/agents/transcripts/' + name + '.yaml', label))
        for op in doc['operations']:
            create = op.get('create')
            if not create: continue
            box = create['box']
            code = create['properties']['code']
            probe = TRANSCRIPT_PROBES.get(box, '')
            answers[box] = await program(label + '_' + box, code, probe, 'main', label == 'revised')
            if box.endswith('_sweep'):
                await batch(label + '_' + box + '_stop', ['wait-output subject --channel main --timeout 60', 'stop subject', 'lifecycle-census subject --expect-stopped'])
    return answers

async def variants():
    text = source('public/docs/interpolants_examples.md', 'revised')
    start, end = list(sections(text))[2]
    code = extract_static_code(lines=text[start:end].splitlines()).replace('val duration = 1.2', 'val duration = 0.8')
    await program('duration_08', code, '''
check(duration == 0.8)
val arrival = samples.first { it.byTime >= 1f - 1e-6f }.elapsed
check(arrival >= duration && arrival < duration + 0.0181) { "$arrival" }
check(abs(timeLine.nodes.last().to.x - x(samples.last().elapsed)) < 1e-3)
println("duration proof: requested=$duration, first arrival=$arrival, guide=${x(duration)}")
proof(arrival)
''', 'interpolants-diff', True)
    doc = yaml.safe_load(source('dev_docs/agents/transcripts/imaging_vector_kernels.yaml', 'revised'))
    code = next(op['create']['properties']['code'] for op in doc['operations'] if op.get('create', {}).get('box') == 'imaging_perf_boxblur').replace('val r = 2', 'val r = 24')
    await program('radius_24', code, '''
check(r == 24 && worst < 1e-4f)
check(faster == if (tRunning < tKernel) "running sum" else "separable kernel")
println("timing proof: radius=$r, running=$tRunning, kernel=$tKernel, reported=$faster")
proof(faster)
''', 'main', True)
    doc = yaml.safe_load(source('dev_docs/agents/transcripts/spatial_geometry_examples.yaml', 'revised'))
    code = next(op['create']['properties']['code'] for op in doc['operations'] if op.get('create', {}).get('box') == 'spatial_apollonius_sweep')
    await program('sweep_rerun', code)
    await batch('sweep_rerun_cleanup', [
        'wait-running subject --state running --settle 0.2',
        'launch subject',
        'wait-running subject --state running --settle 0.2',
        'stop subject', 'lifecycle-census subject --expect-stopped',
    ])

async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    before = await docs('original')
    before.update(await transcripts('original'))
    after = await docs('revised')
    after.update(await transcripts('revised'))
    result = {'original': before, 'revised': after, 'identical': before == after}
    (OUT / 'equivalence.json').write_text(json.dumps(result, indent=2))
    assert before == after, [(k, before[k], after[k]) for k in before if before[k] != after[k]]
    await variants()
    print('Helper adoption batch 3 rehearsal passed', flush=True)

if __name__ == '__main__':
    asyncio.run(main())
