"""Compile the remaining standalone GLSL with real shaderc inside the private Field host."""
import asyncio,hashlib,json,re,subprocess
import session as S
import rehearse as H
async def main():
 rows=json.loads((S.OUT/'bodies.json').read_text());sources=[];seen=set();retained=[]
 for p in sorted({r['path'] for r in rows if r['language']=='glsl'}):
  original=subprocess.check_output(['git','show',f"{json.loads((S.HERE/'scope.json').read_text())['head']}:{p}"],cwd=S.ROOT,text=True);old=S.spans(p,original);now=S.spans(p,(S.ROOT/p).read_text());assert len(old)==len(now)
  wanted={r['sha256'] for r in rows if r['path']==p and r['language']=='glsl'}
  for a,b in zip(old,now):
   if a['sha256'] not in wanted:continue
   code=b['code']
   if code in seen:continue
   seen.add(code)
   if '#include' in code or '{{' in code:
    retained.append(dict(path=p,reason='Domain tests compile authored presets with actual Field includes/preambles'));continue
   if '{%' in code or '#version' not in code:
    retained.append(dict(path=p,reason='Probe macro/partial documentation fragment; no claim of standalone compilation'));continue
   stage='VERTEX_SHADER' if 'gl_Position' in code else 'COMPUTE_SHADER' if 'local_size_' in code else 'FRAGMENT_SHADER'
   target=S.OUT/'runtime'/f'shader-{len(sources)}.glsl';target.write_text(code);sources.append(dict(path=p,fixture=str(target),kind=stage,sha256=hashlib.sha256(code.encode()).hexdigest()))
 lines=['import afield.graphics.shader.ShaderSPIRVUtils','import afield.graphics.shader.ShaderKind']
 for i,s in enumerate(sources):
  lines+=['run {',f'val source = java.io.File({json.dumps(s["fixture"])}).readText()',f'val spirv = ShaderSPIRVUtils.compileShader("catchup-{i}.glsl", source, ShaderKind.{s["kind"]})','try { check((spirv.bytecode()?.remaining() ?: 0) > 0) } finally { spirv.free() }','}']
 lines.append('println("CATCHUP_PROOF=compiled-'+str(len(sources))+'")')
 await H.run('standalone_shaders','\n'.join(lines))
 (S.HERE/'shader-verification.json').write_text(json.dumps(dict(status='passed',standalone=sources,domainTests=['ShadertoyChannelsTest','BoidSketchTest','IteratedSystemShaderTest','ParticleSketchShaderTest','SplatShaderTest'],domainTestLog=str(S.OUT/'shader-tests.log'),notes=retained,visualLimit='No native GPU render was produced on this macOS host; compilation and numeric mask intent are verified separately from GPU appearance.'),indent=2)+'\n');print(len(sources),'actual shaders compiled')
if __name__=='__main__':asyncio.run(main())
