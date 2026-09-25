#!/usr/bin/env python3
"""Exercise the three editable Starting Points, their controls, and ordinary Stop cleanup."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--port', type=int, required=True)
p.add_argument('--server-log', type=Path, required=True)
a = p.parse_args()
work = ROOT / 'build/neural-tools'
work.mkdir(parents=True, exist_ok=True)
(work / 'glfw-platform.kt').write_text('println(if(org.lwjgl.glfw.GLFW.glfwGetPlatform()==org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND) "GLFW_PLATFORM_WAYLAND" else "GLFW_PLATFORM_OTHER")')
log_start = a.server_log.stat().st_size
results = {}
for page, label in [('ray_reconstruction', 'Move light'), ('optical_flow', 'Consecutive-frame wiggle'),
                    ('learned_shader', 'Cooperative vectors')]:
    toggle = work / f'toggle-{page}.js'
    toggle.write_text('''(() => {
        const label = Array.from(document.querySelectorAll('.field-checkbox label'))
            .find(e => e.textContent.trim() === LABEL);
        if (!label) throw new Error('Missing control');
        const input = document.getElementById(label.htmlFor);
        input.click();
        return {label: label.textContent, checked: input.checked};
    })()'''.replace('LABEL', json.dumps(label)))
    commands = [f'macro write review --file dev_docs/agents/transcripts/{page}.yaml',
                'macro run review', 'sleep 2', f'page-eval --file {toggle}', 'sleep 2',
                f'page-eval --file {toggle}', 'sleep 2',
                'page-eval --file tools/lighting/capture_background.js',
                f'screenshot-box {page} {ROOT}/public/docs/assets/{page}/controls.png --padding 16',
                f'output {page}', 'new-box --name Platform',
                f'run @last --file {work}/glfw-platform.kt',
                f'stop {page}', 'stop agent_graphics_context']
    cmd = ['uv', 'run', '--project', '.', '--group', 'backdoor', 'python', 'tools/field_backdoor.py',
           '--json', '--attach-existing', '--port', str(a.port), '--with-playwright']
    for command in commands:
        cmd += ['--command', command]
    run = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=240)
    (work / f'{page}-starting-point.json').write_text(run.stdout)
    (work / f'{page}-starting-point.stderr').write_text(run.stderr)
    assert run.returncode == 0, f'{page}: inspect its starting-point log'
    decoder = json.JSONDecoder()
    rest = run.stdout
    records = []
    while rest.strip():
        record, end = decoder.raw_decode(rest.lstrip())
        rest = rest.lstrip()[end:]
        assert record['ok'] and not record.get('timedOut'), record
        records.append(record)
    toggles = [r['data']['result']['checked'] for r in records if r['command'].startswith('page-eval --file ') and str(toggle) in r['command']]
    assert toggles == [False, True], toggles
    platform = next(e['payload']['html'].strip() for r in records if 'glfw-platform.kt' in r['command']
                    for e in r['events'] if e.get('name') == 'output' and 'GLFW_PLATFORM_' in e.get('payload', {}).get('html', ''))
    results[page] = {'commandsPassed': len(records), 'glfwPlatform': platform, 'controlToggle': toggles,
                     'capture': f'{page}/controls.png', 'stoppedSceneAndRenderer': True}
    print(f'PASS {page}', flush=True)
new_log = a.server_log.read_bytes()[log_start:].decode(errors='replace')
assert 'VUID-' not in new_log, 'Vulkan validation during Starting Point lifecycle; inspect server log'
assert '[error,execution]' not in new_log, 'Field execution error; inspect server log'
results['vulkanValidationErrors'] = 0
out = ROOT / 'dev_docs/lighting/results/neural-tools/starting-points.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(results, indent=2) + '\n')
print('NEURAL STARTING POINTS CHECK DONE')
