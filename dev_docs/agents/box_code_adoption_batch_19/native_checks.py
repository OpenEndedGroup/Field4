"""Reuse the real GPU-boundary rig for the authored probe kernel and complete stats producer."""
from pathlib import Path
import importlib.util,asyncio,json,yaml,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('native11',H.parent/'box_code_adoption_batch_11/native_bounds.py');old=importlib.util.module_from_spec(s);s.loader.exec_module(old);m=old.m;m.OUT=R/'build/box-code-revision/adoption-19/native';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('states19',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
async def main():
 renderer=(R/'public/docs/assets/canonical_graphics/611072da-1af6-4d2d-85bf-07a9ba191aca_code_.kt').read_text().replace('Options.enableHDR = true','Options.enableHDR = false').replace('Window("Field")','Window("Private field probe verification")')
 await m.macro('probe_renderer',[{'create':{'box':'renderer','name':'private renderer','properties':{'code':renderer}}},{'launch':{'box':'renderer'}},{'await':{}}],True)
 props=next(o['create']['properties'] for o in yaml.safe_load((R/'dev_docs/agents/transcripts/threebox.yaml').read_text())['operations'] if o.get('create',{}).get('box')=='tb_compute')
 results=[]
 try:
  for n in [0,1,64,65,100000]:
   code=props['code'].replace('val N = 100000',f'val N = {n}').replace('N.vec4s','(N + 64).vec4s').replace('renderer += "compute" to module','')
   code=re.sub(r'm.something = \{[^\n]+\}', 'm.something = { 0f }',code)
   code=code[:code.index('val seeded =')]+'''
inData.buffers.forEach { b -> b.write { repeat(N+64) { i -> put(vec(i.toFloat(),-i.toFloat(),i*0.5f,1f)) } } }
outData.buffers.forEach { b -> b.write { repeat(N+64) { put(vec(-777f,-777f,-777f,-777f)) } } }
renderer += "compute" to module
'''
   label='probe_'+str(n)
   await m.macro(label,[{'create':{'box':'kernel','name':label,'properties':{'code':code,'compute':props['compute']}}},{'connect':{'parent':'renderer','child':'kernel'}},{'launch':{'box':'kernel'}},{'await':{}}])
   await m.batch(label+'_frames',['wait-native-renderer kernel --frames 30 --timeout 60'])
   probe='''val snap=requireNotNull(_fieldProbe?.readLatest()); check(snap.invocationCount==N.toLong() && snap.finiteCount==N.toLong()) { snap.toString() }''' if n else ''
   check='''outData.read { for(i in 0 until N+64) {
 val expected=if(i<N) floatArrayOf(i.toFloat(),-i.toFloat(),i*0.5f,1f) else floatArrayOf(-777f,-777f,-777f,-777f)
 for(j in 0..3) check(get(i*4+j)==expected[j]) { "particle $i channel $j" }
} }
'''+probe+'\nprintln("GPU writes, bounds and probe counts passed")'
   await old.r.fragment(label+'_assert','kernel',check)
   results.append({'particleCount':n,'padding':64,'exactOutput':True,'probeCountVerified':n>0})
   await old.r.fragment(label+'_cleanup','kernel','''_fieldProbe?.deregister()
renderer.computeList.modules.remove("compute",module)
module.current.cleanup()
inData.buffers.forEach { it.cleanup() }
outData.buffers.forEach { it.cleanup() }''')
   await m.batch(label+'_stop',['stop kernel','delete-box kernel'])
  # The exact published stats producer and its full Kotlin body, with deterministic seeds.
  text=(R/'public/docs/field_probes.md').read_text();code=next(text[a:b] for a,b in d.sections(text) if 'val positions' in text[a:b]);shader=next(text[a:b] for a,b in d.sections(text,'glsl') if 'buffer Positions' in text[a:b])
  code=re.sub(r'(?m)^\s*:::(?:fold[^\n]*|endfold)\s*$','',code)
  await m.macro('stats_producer',[{'create':{'box':'stats_kernel','name':'actual stats producer','properties':{'code':code,'compute':shader}}},{'connect':{'parent':'renderer','child':'stats_kernel'}},{'launch':{'box':'stats_kernel'}},{'await':{}}])
  await m.batch('stats_frames',['wait-native-renderer stats_kernel --frames 40 --timeout 60','wait-output stats_kernel --channel stats --timeout 30'])
  await old.r.fragment('stats_result','stats_kernel','''val s=stats.read(0)
check(s.calls==N.toLong() && s.finiteCount==N.toLong()) { s.toString() }
check(s.mean.isFinite() && s.mean>0 && s.min>=0 && s.max.isFinite()) { s.toString() }
println("actual stats producer: count=${s.calls}, mean=${s.mean}")''')
  results.append({'statsParticles':16384,'matchingProducerCompiledAndRan':True,'exactCountAndFiniteValues':True})
  await old.r.fragment('stats_cleanup','stats_kernel','''renderer.computeList.modules.remove("compute",compute)
compute.current.cleanup()
positions.buffers.forEach { it.cleanup() }
velocities.buffers.forEach { it.cleanup() }''')
  await m.batch('stats_stop',['stop stats_kernel'])
 finally:
  await m.batch('renderer_stop',['stop renderer','lifecycle-census renderer --expect-stopped'])
 (m.OUT/'verification.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
