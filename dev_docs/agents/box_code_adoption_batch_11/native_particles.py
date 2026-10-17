"""Render the actual linked particle boxes and inspect the corrected ring at two scales."""
from pathlib import Path
import asyncio,importlib.util,json
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('r',HERE/'rehearse.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r);m=r.m;ROOT=r.ROOT
async def main():
 asset=ROOT/'public/docs/assets/canonical_graphics'
 ids={'renderer':'611072da-1af6-4d2d-85bf-07a9ba191aca','compute':'ad857eaa-362e-45ea-bdc8-472af40f1ed2','draw':'262e80f5-d991-453d-9af8-8d181ff0eb0b'}
 ops=[]
 for box,id in ids.items():
  code=(asset/(id+'_code_.kt')).read_text()
  if box=='renderer':code=code.replace('Options.enableHDR = true','Options.enableHDR = false').replace('Window("Field")','Window("Private linked particles verification")')
  props={'code':code}
  for stage in ['compute','vertex','fragment']:
   p=asset/(id+'_'+stage+'_.glsl')
   if p.exists():props[stage]=p.read_text()
  ops.append({'create':{'box':box,'name':box,'frame':{'x':400.*len(ops),'y':0.,'w':350.,'h':100.},'properties':props}})
 ops += [{'connect':{'parent':'renderer','child':'compute'}},{'connect':{'parent':'compute','child':'draw'}},{'launch':{'box':'renderer'}},{'await':{}},{'launch':{'box':'compute'}},{'await':{}},{'launch':{'box':'draw'}},{'await':{}}]
 await m.macro('linked_particles_setup',ops,True)
 await m.batch('linked_particles_capture',['wait-native-renderer draw --frames 30 --timeout 60',f'native-screenshot {m.OUT}/particles_1.png --uid draw --wait-frames 6 --timeout 60'])
 await r.fragment('linked_count','draw','check(N==particleCount && N==100000); println("linked draw count: "+N)')
 vertex=(asset/(ids['draw']+'_vertex_.glsl')).read_text();assert '1.0 * pc.inData' in vertex
 for label,scale in [('small','0.4'),('large','2.0')]:
  await m.macro('particles_'+label+'_edit',[{'edit':{'box':'draw','properties':{'vertex':vertex.replace('1.0 * pc.inData',scale+' * pc.inData')}}},{'launch':{'box':'draw'}},{'await':{}}])
  await m.batch('particles_'+label+'_capture',['wait-native-renderer draw --frames 20 --timeout 60',f'native-screenshot {m.OUT}/particles_{label}.png --uid draw --wait-frames 6 --timeout 60'])
 await r.fragment('linked_draw_cleanup','draw','renderer.renderList.modules.remove("boo2", module)\nmodule.current.cleanup()')
 await r.fragment('linked_compute_cleanup','compute','renderer.computeList.modules.remove("module", module)\nmodule.current.cleanup()\ninData.buffers.forEach { it.cleanup() }\noutData.buffers.forEach { it.cleanup() }')
 await m.batch('linked_particles_stop',['stop draw','stop compute','stop renderer','lifecycle-census renderer --expect-stopped'])
 from PIL import Image,ImageStat
 rows=[]
 for label in ['1','small','large']:
  p=m.OUT/f'particles_{label}.png';image=Image.open(p).convert('RGB');stats=ImageStat.Stat(image);assert max(stats.stddev)>5,(label,stats.mean,stats.stddev)
  rows.append({'scale':label,'size':image.size,'mean':stats.mean,'stddev':stats.stddev})
 (m.OUT/'particle-captures.json').write_text(json.dumps({'particleCount':100000,'actualSavedSources':True,'captures':rows},indent=2)+'\n')
 print('Actual linked 100000-particle scene and two ring-scale variants rendered',flush=True)
if __name__=='__main__':asyncio.run(main())
