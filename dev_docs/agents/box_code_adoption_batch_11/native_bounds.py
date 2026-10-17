"""Dispatch the actual particle shader, checking valid output and padded canaries on GPU."""
from pathlib import Path
import asyncio,importlib.util,subprocess,json
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('r',HERE/'rehearse.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r);m=r.m;ROOT=r.ROOT
BASE='c2103ed97';PREFIX='public/docs/assets/canonical_graphics/ad857eaa-362e-45ea-bdc8-472af40f1ed2'
def source(path,original):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT,text=True) if original else (ROOT/path).read_text()
async def main():
 renderer=(ROOT/'public/docs/assets/canonical_graphics/611072da-1af6-4d2d-85bf-07a9ba191aca_code_.kt').read_text().replace('Options.enableHDR = true','Options.enableHDR = false').replace('Window("Field")','Window("Private compute bounds verification")')
 await m.macro('bounds_renderer',[{'create':{'box':'renderer','name':'Private bounds renderer','frame':{'x':0.,'y':0.,'w':400.,'h':100.},'properties':{'code':renderer}}},{'launch':{'box':'renderer'}},{'await':{}}],True)
 results=[]
 for original,n in [(True,65),(False,0),(False,1),(False,64),(False,65),(False,100000)]:
  label=('baseline' if original else 'revised')+'_'+str(n)
  code=source(PREFIX+'_code_.kt',original).replace('val N = 100000',f'val N = {n}').replace('N.vec4s','(N + 64).vec4s').replace('x += 0.01','x += 0.0')
  code=code.replace('renderer += "module" to module','')
  code=code[:code.index('inData.write {')]+'''
// The fixture pads both allocations so a missing tail guard can be detected safely.
inData.buffers.forEach { buffer -> buffer.write {
    repeat(N + 64) { i -> put(vec(i.toFloat(), -i.toFloat(), i * 0.5f, 1f)) }
} }
outData.buffers.forEach { buffer -> buffer.write {
    repeat(N + 64) { put(vec(-777f, -777f, -777f, -777f)) }
} }
renderer += "module" to module
'''
  shader=source(PREFIX+'_compute_.glsl',original)
  await m.macro(label+'_setup',[{'create':{'box':'kernel','name':label,'frame':{'x':500.,'y':0.,'w':400.,'h':100.},'properties':{'code':code,'compute':shader}}},{'connect':{'parent':'renderer','child':'kernel'}},{'launch':{'box':'kernel'}},{'await':{}}])
  await m.batch(label+'_frames',['wait-native-renderer kernel --frames 24 --timeout 60'])
  expected=(n//64)*64 if original else n
  await r.fragment(label+'_values','kernel',f'''
var checked = 0
outData.read {{
 for(i in 0 until N + 64) {{
  val desired = if(i < {expected}) floatArrayOf(i.toFloat(), -i.toFloat(), i * 0.5f, 1f) else floatArrayOf(-777f,-777f,-777f,-777f)
  for(j in 0..3) {{
   val got=get(i*4+j)
   check(got==desired[j]) {{ "{label} particle $i channel $j: $got != ${{desired[j]}}" }}
   checked++
  }}
 }}
}}
println("GPU bounds proof {label}: checked $checked floats; {expected} particles written; remaining padding untouched")
''')
  results.append({'case':label,'count':n,'written':expected,'paddingUntouched':True,'floatChecks':(n+64)*4})
  (m.OUT/'gpu-boundaries.json').write_text(json.dumps(results,indent=2)+'\n')
  await r.fragment(label+'_cleanup','kernel','''
renderer.computeList.modules.remove("module", module)
module.current.cleanup()
inData.buffers.forEach { it.cleanup() }
outData.buffers.forEach { it.cleanup() }
println("exact compute instance removed and buffers retired")
''')
  await m.batch(label+'_stop',['stop kernel','lifecycle-census kernel --expect-stopped','delete-box kernel'])
 await m.batch('bounds_renderer_stop',['stop renderer','lifecycle-census renderer --expect-stopped'])
 print('Native GPU workgroup-boundary checks passed',flush=True)
if __name__=='__main__':asyncio.run(main())
