#!/usr/bin/env python3
"""Repeated GPU/lifecycle checks of the NVIDIA ping-pong Starting point. No SDK preparation."""
import argparse
from array import array
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import sys
import time

from flow_pingpong_metrics import BORDER, COST_LIMIT, OUTLIER_PIXELS, measure

ROOT = Path(__file__).resolve().parents[2]


def records_from(text):
    decoder = json.JSONDecoder()
    while text.strip():
        record, end = decoder.raw_decode(text.lstrip())
        yield record
        text = text.lstrip()[end:]


def run_check(args, directory, index):
    work = directory / f'run-{index:02d}'
    work.mkdir()
    print(f'RUN {index}/{args.repeats}: {work}', flush=True)
    template = (ROOT / 'tools/lighting/capture_flow_pingpong.kt').read_text()
    (work / 'capture.kt').write_text(template.replace('__OUTPUT__', json.dumps(str(work))))
    (work / 'paused.kt').write_text('capturePair.accept("paused")\n')
    batches = {}
    for stage in ('initial', 'resume', 'relaunch'):
        marker = work / f'{stage}.done'
        script = work / f'{stage}.kt'
        # One compilation per stage, not per readback: REPL compiler history is retained.
        script.write_text(f'''frames {{
    repeat({args.samples_per_stage}) {{
        repeat(15) {{ frame() }}
        capturePair.accept("moving")
    }}
    java.io.File({json.dumps(str(marker))}).writeText("done")
}}
''')
        batches[stage] = [f'launch @last --file {script}',
                          f'wait-file {marker} --fail-on-error-uid @last --timeout 90']
    (work / 'size.kt').write_text('org.lwjgl.glfw.GLFW.glfwSetWindowSize(window.windowHandle,1920,1080)')
    (work / 'toggle.js').write_text('''(async() => {
for(let i=0;i<100;i++) {
 const label=Array.from(document.querySelectorAll('.field-checkbox label')).find(e=>e.textContent.trim()==='Animate source');
 if(label){const input=document.getElementById(label.htmlFor);input.click();return input.checked;}
 await new Promise(r=>setTimeout(r,100));
} throw new Error('Missing animate control');
})()''')
    (work / 'reset.js').write_text('''(() => {const button=Array.from(document.querySelectorAll('.field-button')).find(e=>e.textContent.includes('Reset the pair'))?.querySelector('button');if(!button)throw new Error('Reset absent');button.click();return true;})()''')
    # Exercise these controls below. An unused cropped controls PNG used to make the GPU
    # check fail when Field's box-output layout did not settle; only the native image is published.
    (work / 'controls.js').write_text('''(() => {
const animate=Array.from(document.querySelectorAll('.field-checkbox label')).find(e=>e.textContent.trim()==='Animate source');
const reset=Array.from(document.querySelectorAll('.field-button')).find(e=>e.textContent.includes('Reset the pair'))?.querySelector('button');
if(!animate || !reset)throw new Error('Missing ping-pong controls');
return {animate:document.getElementById(animate.htmlFor).checked, reset:!reset.disabled};
})()''')
    commands = [
        'macro write flow --file dev_docs/agents/transcripts/optical_flow_pingpong.yaml',
        f'page-eval --file {work}/ready.js', 'macro run flow', 'sleep 2',
        'new-box --name FlowChecks', 'connect optical_flow_pingpong @last', f'run @last --file {work}/size.kt',
        f'run @last --file {work}/capture.kt',
        *batches['initial'], f'native-screenshot {work}/pingpong.png --uid agent_graphics_context --wait-frames 12',
        f'page-eval --file {work}/controls.js',
        f'page-eval --file {work}/toggle.js', 'sleep 1', f'run @last --file {work}/paused.kt',
        f'page-eval --file {work}/reset.js', 'sleep 1', f'run @last --file {work}/paused.kt',
        f'page-eval --file {work}/toggle.js', 'sleep 1', *batches['resume'],
        'launch optical_flow_pingpong', 'launch flow_texture', 'sleep 1', *batches['relaunch'],
        'stop flow_texture', 'stop optical_flow_pingpong', 'sleep 1',
        'launch optical_flow_pingpong', 'launch flow_texture', 'sleep 1',
        'stop agent_graphics_context', 'sleep 1', 'stop flow_texture', 'stop optical_flow_pingpong',
    ]
    start = args.server_log.stat().st_size
    document = f'flow-pingpong-{time.time_ns()}'
    (work / 'ready.js').write_text('''(async() => {
for(let i=0;i<200;i++) {
 if(window.fieldClientOpen===true && window.fieldBackdoorUiReady===true &&
    window.currentDocumentFilename===DOCUMENT_NAME && window.fieldBackdoorDisconnectedOverlayActive!==true)
   return {ready:true,document:window.currentDocumentFilename};
 await new Promise(r=>setTimeout(r,100));
} throw new Error('Field browser is not ready for this document');
})()'''.replace('DOCUMENT_NAME', json.dumps(document)))
    base = ['uv', 'run', '--project', '.', '--group', 'backdoor', 'python', 'tools/field_backdoor.py',
            '--json', '--attach-existing', '--timeout', '45', '--port', str(args.port), '--document', document]
    cmd = base + ['--with-playwright']
    for command in commands:
        cmd += ['--command', command]
    with (work / 'run.json').open('w') as out, (work / 'stderr.txt').open('w') as err:
        process = subprocess.Popen(cmd, cwd=ROOT, stdout=out, stderr=err, start_new_session=True)
        try:
            code = process.wait(timeout=300)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            code = -1
    if code:
        # Attempt cleanup even when a compile/runtime error interrupted the command sequence.
        try:
            subprocess.run(base + ['--no-fail-on-error', '--command', 'stop FlowChecks', '--command', 'stop flow_texture',
                                   '--command', 'stop optical_flow_pingpong', '--command', 'stop agent_graphics_context'],
                           cwd=ROOT, capture_output=True, timeout=60)
        except subprocess.TimeoutExpired:
            print('Cleanup timed out; inspect the Field process and retained logs.', flush=True)
    log = args.server_log.read_bytes()[start:].decode(errors='replace')
    (work / 'server.log').write_text(log)
    failures = []
    try:
        records = list(records_from((work / 'run.json').read_text()))
    except (ValueError, KeyError) as e:
        records = []
        failures.append(f'Invalid backdoor output: {e}')
    if code or len(records) != len(commands) or not all(r.get('ok') for r in records):
        failures.append('Backdoor command sequence failed or incomplete; inspect run.json and stderr.txt')
    if 'VUID-' in log or '[error,execution]' in log:
        failures.append('Vulkan validation or Field execution error; inspect server.log')
    samples = []
    for pair in sorted(work.glob('pair-*')):
        metadata = json.loads((pair / 'metadata.json').read_text())
        pixels = array('f')
        pixels.frombytes((pair / 'flow.f32').read_bytes())
        if sys.byteorder != 'little':
            pixels.byteswap()
        metrics = measure(pixels, metadata['width'], metadata['height'], metadata['expected'],
                          metadata['kind'] == 'paused', metadata['sourceMaxDifference'])
        if not metadata['samePairDuringReadback']:
            metrics['failures'].append('Readback straddled evaluations')
            metrics['passed'] = False
        sample = dict(metadata, **metrics, artifact=str(pair.relative_to(directory)))
        (pair / 'metrics.json').write_text(json.dumps(sample, indent=2) + '\n')
        samples.append(sample)
        if not metrics['passed']:
            failures.append(f'{pair.name}: {metrics["failures"]}')
    if sum(s['kind'] == 'moving' for s in samples) != 3 * args.samples_per_stage or sum(s['kind'] == 'paused' for s in samples) != 2:
        failures.append('Missing moving/paused captures')
    proof = {'passed': not failures, 'failures': failures, 'document': document,
             'commandsExpected': len(commands), 'commandsPassed': sum(bool(r.get('ok')) for r in records),
             'vulkanValidationErrors': log.count('VUID-'), 'samples': samples}
    (work / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(f'{"PASS" if proof["passed"] else "FAIL"} run {index}: {len(samples)} pairs; {failures}', flush=True)
    return proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--server-log', type=Path, required=True)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--samples-per-stage', type=int, default=8,
                        help='Moving readbacks at initial launch, resume, and relaunch (default: 8 each)')
    parser.add_argument('--output', type=Path, help='New artifact directory; must not already exist')
    parser.add_argument('--publish', action='store_true', help='Publish proof and capture only after every repeat passes')
    args = parser.parse_args()
    if args.repeats < 1 or args.samples_per_stage < 1:
        parser.error('repeats and samples-per-stage must be positive')
    args.server_log = args.server_log.resolve()
    directory = (args.output or ROOT / 'build/neural-tools/pingpong' / str(time.time_ns())).resolve()
    directory.mkdir(parents=True, exist_ok=False)
    report = {
        'schema': 2, 'utc': datetime.now(timezone.utc).isoformat(), 'host': platform.node(),
        'os': platform.platform(), 'desktopEnvironment': {k: os.getenv(k) for k in ('XDG_SESSION_TYPE', 'XDG_CURRENT_DESKTOP', 'WAYLAND_DISPLAY', 'DISPLAY')},
        'gitHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'gitDirty': bool(subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=ROOT, text=True).strip()),
        'sourceShaderSha256': hashlib.sha256((ROOT / 'dev_docs/agents/demos/optical_flow_pingpong/fragment.glsl').read_bytes()).hexdigest(),
        'acceptance': {'borderFlowTexels': BORDER, 'rawMedianEpeLessThan': .5, 'rawP95EpeLessThan': 1,
                       'diagnosticOutlierPixels': OUTLIER_PIXELS, 'diagnosticCostLimit': COST_LIMIT,
                       'costAffectsAcceptance': False},
        'sampling': 'Unmodified Starting point; independent documents and resource recreation. Each stage samples in one Field job, with 15 Field frames between readbacks. Animation phases follow actual Field frames, not fixed wall-clock phases.',
        'requestedRepeats': args.repeats, 'samplesPerMovingStage': args.samples_per_stage, 'runs': [],
    }
    for index in range(1, args.repeats + 1):
        report['runs'].append(run_check(args, directory, index))
        report['passed'] = all(r['passed'] for r in report['runs'])
        report['complete'] = len(report['runs']) == args.repeats
        (directory / 'proof.json').write_text(json.dumps(report, indent=2) + '\n')
        if not report['passed']:
            raise SystemExit(f'FAILED; full diagnostics retained in {directory}')
    # All raw fields, source images, rejected samples and command logs remain in the run directory.
    if args.publish:
        (ROOT / 'dev_docs/lighting/results/neural-tools/pingpong.json').write_text(json.dumps(report, indent=2) + '\n')
        shutil.copyfile(directory / 'run-01/pingpong.png', ROOT / 'public/docs/assets/optical_flow/pingpong.png')
    print(f'PINGPONG TEXTURE CHECK DONE: {args.repeats} repeated runs; {directory}')


if __name__ == '__main__':
    main()
