"""Run the exact authored VR panel, then edit controls and recreate the keyed texture."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys

import yaml

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/teaching-code-open-implementations'
SOURCE = ROOT / 'dev_docs/agents/transcripts/imgui_vr_panel.yaml'


def main():
    macro = yaml.safe_load(SOURCE.read_text())
    props = next(op['create']['properties'] for op in macro['operations']
                 if op.get('create', {}).get('box') == 'imgui_vr_panel')
    source = props['code']
    checks = '''
prop<ImGuiRenderToTexture>(proofTexture)
frames {
    repeat(10) { frame() }
    val hit = checkNotNull(panel.raycast(Vector3f(), Vector3f(0f, 0f, 1f)))
    check(hit.onPanel && abs(hit.u - 0.5f) < 1e-5f && abs(hit.v - 0.5f) < 1e-5f)
    check(gain.get() == EXPECTED_GAIN)
    TEXTURE_CHECK
    proofTexture = imgui
    println("VERIFICATION_TOKEN")
}
'''
    cases = [
        ('default', source, '0.5f', ''),
        ('controls', source.replace('ImFloat(0.5f)', 'ImFloat(0.75f)').replace(
            'mapped onto a quad, shown in both eyes.', 'Rerun: gain starts at 0.75.'),
         '0.75f', 'check(proofTexture === imgui) { "Unchanged native target was recreated" }'),
        ('font', source.replace('val fontSize = 96f', 'val fontSize = 80f'),
         '0.5f', 'check(proofTexture !== imgui) { "Changed font key did not recreate the target" }'),
    ]
    commands = []
    for i, (name, code, expected, texture_check) in enumerate(cases):
        token = f'TEACHING_VR_{name.upper()}_PASSED'
        code += '\n' + checks.replace('EXPECTED_GAIN', expected).replace('TEXTURE_CHECK', texture_check).replace('VERIFICATION_TOKEN', token)
        if i == 0:
            props['code'] = code
            macro_path = OUT / 'probe-vr-panel.yaml'
            macro_path.write_text(yaml.safe_dump(macro, sort_keys=False))
            commands.extend([f'macro write panel_probe --file {shlex.quote(str(macro_path))}', 'macro run panel_probe'])
        else:
            path = OUT / f'probe-vr-{name}.kt'
            path.write_text(code)
            commands.append(f'launch imgui_vr_panel --file {shlex.quote(str(path))} --timeout 180')
        commands.extend([
            f'wait-output imgui_vr_panel --kind all --contains {token} --timeout 90',
            f'native-screenshot {OUT}/vr-{name}.png --uid imgui_vr_renderer --wait-frames 5 --timeout 90',
        ])
    commands.append('stop imgui_vr_renderer')
    cmd = [sys.executable, 'tools/field_backdoor.py', '--json', '--port', '9478',
           '--with-playwright', '--document', 'teaching_vr_open']
    for command in commands:
        cmd.extend(['--command', command])
    log = OUT / 'vr-verification.log'
    with log.open('w') as output:
        result = subprocess.run(cmd, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit(f'Verification failed; inspect {log}')
    receipt = dict(source=str(SOURCE.relative_to(ROOT)), sha256=hashlib.sha256(source.encode()).hexdigest(),
                   centerRayUv=[0.5, 0.5], defaultGain=0.5, editedGain=0.75,
                   sameTextureOnControlRerun=True, replacedTextureOnFontRerun=True,
                   defaultFontPixels=96, editedFontPixels=80,
                   nativeImages=[f'vr-{name}.png' for name, *_ in cases],
                   log=str(log.relative_to(ROOT)))
    (OUT / 'vr-verification.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print('VR: real stereo render, center ray, control edit and keyed texture replacement passed.')


if __name__ == '__main__':
    main()
