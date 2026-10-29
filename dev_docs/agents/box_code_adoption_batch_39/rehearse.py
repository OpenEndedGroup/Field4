"""Verify real reruns, persistent input/targets, live reset edits and a full camera cycle."""
import asyncio,importlib.util,json,yaml,os,math,shutil,time,sys
from pathlib import Path
from PIL import Image,ImageStat
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig39',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(os.environ.get('FIELD_TEST_PORT','9706'))
REOPEN='--reopened' in sys.argv;m.OUT=R/('build/box-code-revision/adoption-39/runtime-reopened' if REOPEN else 'build/box-code-revision/adoption-39/runtime-build');m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,box,code):return await m.macro(name,[{'execute':{'box':box,'property':'code','type':'fragment','code':code}}])
async def capture(name):
 p=m.OUT/(name+'.png');await m.batch(name,['wait-native-renderer rp_renderer --frames 35 --timeout 60',f'native-screenshot {p} --uid rp_renderer --wait-frames 4 --timeout 60']);return p
async def main():
 folder=R/'dev_docs/agents/demos/frame_history_reprojection';doc=yaml.safe_load((folder/'build.yaml').read_text());source=yaml.safe_load((R/'dev_docs/agents/transcripts/frame_history_reprojection.yaml').read_text())
 for data in [doc,source]:
  for o in data['operations']:
   if 'create' in o:
    c=o['create'];assert c['properties']['code']==(folder/(c['box']+'_code_.kt')).read_text()
 if REOPEN:
  name='box-code-reprojection-39-'+str(time.time_ns());destination=R/'build/field-automation-workspace'/name;shutil.copytree(folder,destination)
  sys.path.insert(0,str(R/'tools'));import field_kit
  async with field_kit.LiveHost(m.PORT,name) as host:
   for box in ['rp_renderer','rp_bundle','rp_chain','rp_modes']:
    assert (await host.get(box,'code')).rstrip()==(folder/(box+'_code_.kt')).read_text().rstrip()
  await m.batch('open-saved',['open '+name])
 elif '--resume-chain' not in sys.argv:
  await m.macro('build-graph',[o for o in doc['operations'] if 'create' in o or 'connect' in o],True)
 if '--resume-chain' in sys.argv:
  old=yaml.safe_load((m.OUT/'build-graph.yaml').read_text())['operations']
  oldCode=next(o['create']['properties']['code'] for o in old if o.get('create',{}).get('box')=='rp_chain')
  await m.macro('fix-chain-math',[{'edit':{'box':'rp_chain','property':'code','from':oldCode,'to':(folder/'rp_chain_code_.kt').read_text()}},{'launch':{'box':'rp_chain'}},{'await':{}}])
 else:
  await m.macro('launch-graph',[{'launch':{'box':'rp_renderer'}},{'await':{}},{'launch':{'box':'rp_bundle'}},{'await':{}},{'launch':{'box':'rp_chain'}},{'await':{}}])
 await fragment('retain-input','rp_chain','''prop<afield.graphics.vulkan.targets.RenderToTexture>(originalInput)=chain.input
prop<afield.graphics.vulkan.targets.RenderToTexture>(originalState)=chain.pass("taa")!!.target
prop<List<String>>(originalOrder)=bundle.postStack.names()
prop<Any>(previousEpoch)=bundle.frameHistoryEpoch
check(chain.input !== chain.output)
check(balls.size==25)
''')
 if not REOPEN:
  stats=[]
  for mode in range(4):
   await fragment('mode-'+str(mode),'rp_chain',f'chain.setPush("display","mode",{mode})')
   p=await capture('mode-'+str(mode));im=Image.open(p).convert('RGB');std=ImageStat.Stat(im).stddev;assert max(std)>0.1,(mode,std);stats.append({'mode':mode,'size':im.size,'rgbDeviation':std})
  (m.OUT/'modes.json').write_text(json.dumps(stats,indent=2)+'\n')
  # A real assignment edits the existing binding, rather than redeclaring the variable.
  await fragment('zero-gain','rp_chain','chain.setPush("display","mode",1);motionGain=0f')
  p=await capture('zero-gain');im=Image.open(p).convert('RGB');w,h=im.size;std=ImageStat.Stat(im.crop((w//10,h//10,w*9//10,h*9//10))).stddev;assert max(std)<1.1,std
  (m.OUT/'live-gain.json').write_text(json.dumps({'zeroGainRgbDeviation':std},indent=2)+'\n')
  await fragment('restore-gain','rp_chain','motionGain=20f;dollyAmplitude=0.0')
  await m.batch('settle-fixed-eye',['wait-native-renderer rp_renderer --frames 35 --timeout 60'])
  await fragment('fixed-eye','rp_chain','check(kotlin.math.abs(camera.at.z+4.2f)<1e-5);dollyAmplitude=1.7;println("reset assignment controls the existing motion loop")')
 target=m.OUT/'poses.csv';count=900 if not REOPEN else 120
 collector='''val samples=mutableListOf<DoubleArray>()
frames {
    repeat(COUNT) {
        val c=bundle.scene.camera
        val d=afield.math.linalg.Vector3f(c.target).sub(c.at)
        samples += doubleArrayOf(c.at.x.toDouble(),c.at.y.toDouble(),c.at.z.toDouble(),d.x.toDouble(),d.y.toDouble(),d.z.toDouble())
        frame()
    }
    java.io.File(PATH).writeText(samples.joinToString("\\n") { it.joinToString(",") })
    display("poses", "${samples.size} camera poses")
}
'''.replace('COUNT',str(count)).replace('PATH',json.dumps(str(target)))
 await m.macro('collect-poses',[{'create':{'box':'rp_samples','properties':{'code':collector}}},{'connect':{'parent':'rp_bundle','child':'rp_samples'}},{'launch':{'box':'rp_samples'}},{'await':{}}])
 await m.batch('pose-complete',[f'wait-output rp_samples --channel poses --contains "{count} camera poses" --timeout 90'])
 rows=[[float(v) for v in line.split(',')] for line in target.read_text().splitlines()];assert len(rows)==count
 lengths=[math.sqrt(sum(v*v for v in r[3:])) for r in rows];yaw=[math.atan2(r[3],r[5]) for r in rows];eye=[r[2] for r in rows]
 expected=math.sqrt(1.4**2+4.2**2);assert max(abs(v-expected) for v in lengths)<1e-5,(min(lengths),max(lengths));assert max(abs(v) for v in yaw)<=0.10001
 if not REOPEN:assert min(eye)<-5.89 and max(eye)>-2.51,(min(eye),max(eye))
 (m.OUT/'pose-bounds.json').write_text(json.dumps({'samples':count,'viewDirectionLength':[min(lengths),max(lengths)],'azimuthRadians':[min(yaw),max(yaw)],'eyeZ':[min(eye),max(eye)]},indent=2)+'\n')
 for i in range(2):
  await m.batch('rerun-'+str(i),['launch rp_chain','wait-native-renderer rp_renderer --frames 35 --timeout 60'])
  await fragment('rerun-identities-'+str(i),'rp_chain','''check(chain.input === originalInput)
check(chain.input !== chain.output)
check(chain.pass("taa")!!.target === originalState)
check(bundle.postStack.names() == originalOrder)
check(bundle.frameHistoryEpoch !== previousEpoch)
previousEpoch=bundle.frameHistoryEpoch
val directionAfterRerun=afield.math.linalg.Vector3f(camera.target).sub(camera.at)
check(kotlin.math.abs(directionAfterRerun.length().toDouble()-kotlin.math.sqrt(19.6))<1e-5)
check(kotlin.math.abs(kotlin.math.atan2(directionAfterRerun.x.toDouble(),directionAfterRerun.z.toDouble()))<=0.10001)
println("upstream input, persistent state, pass order and bounded reset pose verified")''')
 if not REOPEN:
  await m.batch('mode-cycle',['launch rp_modes','wait-output rp_modes --contains "mode 3" --timeout 90','stop rp_modes'])
  await capture('after-reruns')
 else:await capture('reopened-beauty')
 await m.batch('stop',['stop rp_samples','stop rp_modes','stop rp_chain','stop rp_bundle','stop rp_renderer'])
asyncio.run(main())
