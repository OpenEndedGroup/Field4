"""Exercise the authored scene, compare its packed data, and render a parameter rerun.

Run through `uv run --group docs python <this file> material|glass`.
Only resolution/convergence and assertions change in the private verification copy.
Committed linux-nvidia figures remain owned by their actual capture profile.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys

import yaml

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/teaching-code-open-implementations'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('scene', choices=['material', 'glass'])
    parser.add_argument('--port', type=int, default=9478)
    args = parser.parse_args()
    scene = args.scene
    transcript = ROOT / f'dev_docs/agents/transcripts/path_tracing_{"materials" if scene == "material" else "glass"}.yaml'
    macro = yaml.safe_load(transcript.read_text())
    box = macro['operations'][1]['create']['box']
    source = macro['operations'][1]['create']['properties']['code']
    imports = '\n'.join(line for line in source.splitlines() if line.startswith('import '))
    body = '\n'.join(line for line in source.splitlines() if not line.startswith('import '))
    for old, new in [('width = 1280', 'width = 320'), ('height = 720', 'height = 180')]:
        assert body.count(old) == 1, old
        body = body.replace(old, new)
    helper = 'PathTraceDemo' if scene == 'material' else 'PathTraceGlassDemo'
    comparison = '''
fun packedSignature(value: PathTracingRenderer): String {
    val packed = value.javaClass.getDeclaredField("packed").also { it.isAccessible = true }.get(value)
    return ["triangles", "materials", "nodes"].joinToString("\\n") { name ->
        packed.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(packed).toString()
    } + "\\n${value.camera.at}|${value.camera.target}|${value.camera.p.fov}"
}
'''+f'''val reference = {helper}.install(renderer,
    "public/docs/assets/fabricate_demo/venice_sunset_1k.exr", width = 320, height = 180)
val expected = packedSignature(reference)
val expectedTriangles = reference.triangleCount
'''
    body = body.replace('tracer.samplesPerFrame = 1', '''check(packedSignature(tracer) == expected) { "Scene, materials, BVH or camera changed" }
check(tracer.triangleCount == expectedTriangles)
tracer.samplesPerFrame = 1''')
    budget = '1024' if scene == 'material' else '2048'
    assert f'tracer.samples < {budget}' in body
    body = body.replace(f'tracer.samples < {budget}', 'tracer.samples < 32')
    message = 'materials' if scene == 'material' else 'glass'
    body = body.replace(f'println("{message} study converged: ${{tracer.samples}} samples")',
                        'tracer.paused = true\n    println("TEACHING_SCENE_PASSED ${tracer.samples}")')
    source_to_run = imports + f'\nimport afield.graphics.scene.fabricated.{helper}\n' + comparison + body + '\n'
    macro['name'] = f'teaching_{scene}_verification'
    macro['operations'][1]['create']['properties']['code'] = source_to_run
    OUT.mkdir(parents=True, exist_ok=True)
    macro_path = OUT / f'probe-{scene}-scene.yaml'
    macro_path.write_text(yaml.safe_dump(macro, sort_keys=False))
    # Same box, new authored parameters, same topology. The default oracle must differ.
    variant = source_to_run.replace('packedSignature(tracer) == expected', 'packedSignature(tracer) != expected')
    old, new = ('0.8f, red,', '0.65f, red,') if scene == 'material' else ('roughness = 0.32f', 'roughness = 0.5f')
    assert old in variant, old
    variant = variant.replace(old, new).replace('TEACHING_SCENE_PASSED', 'TEACHING_SCENE_VARIANT_PASSED')
    variant_path = OUT / f'probe-{scene}-variant.kt'
    variant_path.write_text(variant)
    commands = [
        f'macro write scene_probe --file {shlex.quote(str(macro_path))}',
        'macro run scene_probe',
        f'wait-output {box} --kind all --contains TEACHING_SCENE_PASSED --timeout 180',
        f'native-screenshot {OUT}/{scene}-default.png --uid path_trace_renderer --wait-frames 3 --timeout 90',
        f'launch {box} --file {shlex.quote(str(variant_path))} --timeout 180',
        f'wait-output {box} --kind all --contains TEACHING_SCENE_VARIANT_PASSED --timeout 180',
        f'native-screenshot {OUT}/{scene}-variant.png --uid path_trace_renderer --wait-frames 3 --timeout 90',
        'stop path_trace_renderer',
    ]
    cmd = [sys.executable, 'tools/field_backdoor.py', '--json', '--port', str(args.port),
           '--with-playwright', '--document', f'teaching_{scene}_verification']
    for command in commands:
        cmd.extend(['--command', command])
    log = OUT / f'{scene}-verification.log'
    with log.open('w') as output:
        result = subprocess.run(cmd, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit(f'Verification failed; inspect {log}')
    receipt = dict(source=str(transcript.relative_to(ROOT)), sha256=hashlib.sha256(source.encode()).hexdigest(),
                   defaultPackedGeometryMaterialsBvhCameraEqual=True, defaultTriangleCountEqual=True,
                   parameterRerun=True, resolution=[320, 180], convergenceSamples=32,
                   comparison='Exact packed triangles/materials/BVH and camera against the original installer; real Vulkan render, then changed material/geometry in the same box.',
                   nativeImages=[f'{scene}-default.png', f'{scene}-variant.png'], log=str(log.relative_to(ROOT)))
    (OUT / f'{scene}-verification.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print(f'{scene}: default packed data match; parameter rerun and two real native renders passed.')


if __name__ == '__main__':
    main()
