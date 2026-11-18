"""Compare authored offline FM1 results and compile the actual GLSL without a GPU."""
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import run as R

S = R.S

def main():
    frozen = json.loads((S.OUT/'bodies.json').read_text())
    commands = []
    cases = []
    for index in [21, 23]:
        old = frozen[index]
        now = next(r for r in S.spans(old['path'],(S.ROOT/old['path']).read_text())
                   if r.get('property')=='code' and ('val bars =' in r['code'] if index==21 else 'screenPng(scale = 3)' in r['code']))
        for variant in [False, True]:
            for label, source in [('before',old['code']),('after',now['code'])]:
                code='val proof = mutableListOf<String>()\n'+source
                if index==21:
                    anchor='println("project $slot  playing=${sim.isPlaying}  $trace")'
                    assert anchor in code
                    code=code.replace(anchor,'proof += "$slot:${l.contentHashCode()}:${r.contentHashCode()}:$trace"\n'+anchor)
                    if variant: code=code.replace('0 until 30','0 until 10')
                else:
                    assert 'display("screen", fm1.screenPng(scale = 3))' in code
                    code=code.replace('display("screen", fm1.screenPng(scale = 3))', 'proof += "${l.contentHashCode()}:${r.contentHashCode()}:${fm1.screenPng(scale = 3).contentHashCode()}"')
                    if variant:code=code.replace('fm1.preset(4)','fm1.preset(2)')
                token=f'CATCHUP_PROOF_{len(cases)}'
                code+='\nprintln("'+token+'|"+proof.joinToString(";"))\n'
                path=S.OUT/f'fm1-proof-{len(cases)}.kt';path.write_text(code)
                # A new box for each independent oracle, then the next default/variant.
                commands+=['new-box --name '+token, 'run @last --file '+shlex.quote(str(path))+' --timeout 180']
                cases.append(dict(source=old['path'],case='levels' if index==21 else 'screen',variant=variant,label=label,token=token))
    shaders=[]
    for name in ['bonded_organisms','cuda_particles']:
        path=f'dev_docs/agents/transcripts/{name}.yaml'
        for row in S.spans(path,(S.ROOT/path).read_text()):
            if row['language']!='glsl':continue
            kind={'vertex':'VERTEX_SHADER','fragment':'FRAGMENT_SHADER','compute':'COMPUTE_SHADER'}[row['property']]
            shaders.append(dict(path=path,property=row['property'],sha256=S.digest(row['code'])))
            target=S.OUT/f'shader-{len(shaders)}.kt'
            target.write_text('import afield.graphics.shader.ShaderSPIRVUtils\nimport afield.graphics.shader.ShaderKind\n'+
                'val shader = ShaderSPIRVUtils.compileShader("catchup-'+name+'", '+json.dumps(row['code'])+', ShaderKind.'+kind+')\ncheck(shader.bytecode()!!.remaining() > 0)\nshader.free()\nprintln("CATCHUP_SHADER_'+str(len(shaders))+'_PASSED")\n')
            commands+=['new-box --name shader-'+str(len(shaders)), 'run @last --file '+shlex.quote(str(target))+' --timeout 180']
    args=[sys.executable,'tools/field_backdoor.py','--json','--port','9784','--document','catchup_nov17_cpu']
    for c in commands:args+=['--command',c]
    log=S.OUT/'behavior.log'
    with log.open('w') as output:result=subprocess.run(args,cwd=S.ROOT,stdout=output,stderr=subprocess.STDOUT)
    if result.returncode:raise SystemExit('Actual-code verification failed; inspect '+str(log))
    text=log.read_text();values={}
    # Parse output-event payloads, not echoed source text or command arguments.
    decoder=json.JSONDecoder();pos=0
    while pos<len(text):
        if text[pos]!='{':pos+=1;continue
        try:r,n=decoder.raw_decode(text[pos:])
        except ValueError:pos+=1;continue
        pos+=n
        for event in r.get('events',[]):
            if event.get('name')=='output':
                html=event.get('payload',{}).get('html','')
                for token,value in re.findall(r'(CATCHUP_PROOF_\d+)\|([^<\n]+)',html):values[token]=value.replace('\\n','')
    for a,b in zip(cases[::2],cases[1::2]):
        assert values.get(a['token']) and values.get(a['token'])==values.get(b['token']),(a,b,values)
    for i in range(1,len(shaders)+1):assert f'CATCHUP_SHADER_{i}_PASSED' in text
    receipt=dict(fm1=cases,results=values,equivalencePairs=4,shaders=shaders,shaderCount=len(shaders),
                 method='Actual before/after authored private simulators: channel sample hashes plus level traces or screen PNG hash; defaults and changed render duration/project. Actual shaderc SPIR-V compilation of every touched shader.',
                 hardwareLimit='CUDA execution and committed linux-nvidia figures retained; no NVIDIA device on this Mac.')
    (S.HERE/'behavior-verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print('4 FM1 equivalence pairs and',len(shaders),'real GLSL compilations passed')

if __name__=='__main__':main()
