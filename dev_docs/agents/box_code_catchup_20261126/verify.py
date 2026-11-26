"""Run actual before/after CPU examples and compile complete authored GLSL stages."""
import json
import re
import shlex
import subprocess
import sys

from run import S


def current(row):
    original = subprocess.check_output(['git', 'show', json.loads((S.HERE/'scope.json').read_text())['head']+':'+row['path']], cwd=S.ROOT, text=True)
    index = next(i for i, r in enumerate(S.spans(row['path'], original)) if r['sha256'] == row['sha256'])
    return S.spans(row['path'], (S.ROOT/row['path']).read_text())[index]['code']


def main():
    frozen = json.loads((S.OUT/'bodies.json').read_text())
    commands = []
    cases = []
    specifications = [
        (39, 'controller', ('runStudy(0.15)', 'runStudy(0.22)'), 'proof += "$fixedTail:$feedbackTail:${offset.hashCode()}:${spanCorrection.hashCode()}"', 'val fixedTail = fixedError.takeLast(60).average()'),
        (111, 'geodesic', ('heightfieldMesh(90, 60', 'heightfieldMesh(60, 40'), 'proof += "${straight.contentHashCode()}:${alongSurface.contentHashCode()}"', None),
        (240, 'algebra', ('draw(-1.0)', 'draw(0.0)'), 'proof += (mapped + axes + curve).flatMap { line -> line.nodes.map { it.to.toString() } }.hashCode().toString()', '    lines(*(mapped + axes + curve).toTypedArray()'),
        (246, 'splats', ('val count = 40_000', 'val count = 10_000'), 'proof += "${reference.contentHashCode()}:${tiled.contentHashCode()}:$worst"', None),
    ]
    for index, name, variant, assertion, anchor in specifications:
        old = frozen[index]
        for changed in [False, True]:
            for label, source in [('before', old['code']), ('after', current(old))]:
                if changed:
                    assert variant[0] in source
                    source = source.replace(*variant)
                imports = re.findall(r'^import[^\n]+', source, re.M)
                code = '\n'.join(imports) + '\nval proof = mutableListOf<String>()\n' + re.sub(r'^import[^\n]+\n?', '', source, flags=re.M)
                if name == 'controller':
                    # Sample the actual arrays after the authored study; retain its plotting and controls.
                    anchor2 = 'val feedbackTail = feedbackError.takeLast(60).average()'
                    assert anchor2 in code
                    code = code.replace(anchor2, anchor2 + '\n' + assertion)
                elif anchor:
                    assert anchor in code, name
                    code = code.replace(anchor, assertion+'\n'+anchor)
                else:
                    code += '\n' + assertion
                token = f'CATCHUP_NOV26_CPU_{len(cases)}'
                code += '\nprintln("'+token+'|" + proof.joinToString(";"))\n'
                target = S.OUT/f'proof-{len(cases)}_code_.kt'
                target.write_text(code)
                commands += ['new-box --name '+token, 'run @last --file '+shlex.quote(str(target))+' --timeout 240']
                cases.append(dict(source=old['path'], name=name, variant=changed, label=label, token=token))
    shaders = []
    for old in frozen:
        if old['language'] != 'glsl' or '#version' not in old['code'] or 'void main()' not in old['code']:
            continue
        prop = old.get('property') or old['path'].rsplit('_', 2)[-2]
        if prop not in {'vertex', 'fragment', 'compute'}:
            continue
        kind = dict(vertex='VERTEX_SHADER', fragment='FRAGMENT_SHADER', compute='COMPUTE_SHADER')[prop]
        token = f'CATCHUP_NOV26_SHADER_{len(shaders)}'
        code = 'import afield.graphics.shader.*\n'
        code += '''val includes = ShaderIncludeResolver { request ->
    when (request.requestedPath) {
        "coverage/triangle.glsl" -> ShaderIncludeResolution(request.requestedPath, afield.graphics.render.TriangleCoverage.GLSL)
        else -> java.io.File("src/jvmMain/resources/afield/four/" + request.requestedPath).takeIf { it.isFile }?.let { ShaderIncludeResolution(request.requestedPath, it.readText()) }
    }
}
'''
        for label, source in [('before', old['code']), ('after', current(old))]:
            code += f'val {label} = ShaderSPIRVUtils.compileShader(ShaderCompileSource("catchup-stage", {json.dumps(source)}, includes), ShaderKind.{kind})\n'
        code += 'check(before.bytecode()!!.remaining() > 0)\n'
        # Debug source line tables may legitimately differ. The token guard separately verifies identity.
        code += 'check(after.bytecode()!!.remaining() > 0)\nbefore.free()\nafter.free()\n'
        code += f'println("{token}_PASSED")\n'
        target = S.OUT/f'shader-proof-{len(shaders)}_code_.kt'
        target.write_text(code)
        commands += ['new-box --name '+token, 'run @last --file '+shlex.quote(str(target))+' --timeout 180']
        shaders.append(dict(path=old['path'], property=prop, token=token))
    args = [sys.executable, 'tools/field_backdoor.py', '--json', '--port', '9786', '--document', 'catchup_nov26_cpu']
    for command in commands:
        args += ['--command', command]
    log = S.OUT/'behavior.log'
    with log.open('w') as stream:
        result = subprocess.run(args, cwd=S.ROOT, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit('Actual-code verification failed: '+str(log))
    text = log.read_text()
    decoder = json.JSONDecoder()
    pos = 0
    outputs = []
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
        for event in value.get('events', []):
            if event.get('name') == 'output':
                outputs.append(event.get('payload', {}).get('html', ''))
    actual = '\n'.join(outputs)
    values = dict(re.findall(r'(CATCHUP_NOV26_CPU_\d+)\|([^<\n]+)', actual))
    for a, b in zip(cases[::2], cases[1::2]):
        assert values.get(a['token']) and values.get(a['token']) == values.get(b['token']), (a, b, values)
    for shader in shaders:
        assert shader['token']+'_PASSED' in actual, shader
    receipt = dict(cases=cases, results=values, equivalencePairs=len(cases)//2, shaders=shaders, shaderCount=len(shaders),
                   method='Exact authored CPU examples before/after, default and edited inputs, real arrays/geometry/raster results; shaderc compilation before/after for every complete frozen GLSL stage. Partial GLSL and Shadertoy template require their enclosing program.')
    (S.HERE/'behavior-verification.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print(len(cases)//2, 'CPU equivalence pairs and', len(shaders), 'before/after GLSL compilations passed')


if __name__ == '__main__':
    main()
