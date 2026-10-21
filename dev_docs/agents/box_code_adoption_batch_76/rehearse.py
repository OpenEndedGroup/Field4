"""Real Vulkan replacement, Stop and edited-source persistence acceptance."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-76'
s=importlib.util.spec_from_file_location('rig76',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9724;m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def macro(name,ops):
 p=m.OUT/(name+'.yaml');v=m.OUT/(name+'.json')
 if p.exists() and v.exists() and yaml.safe_load(p.read_text())=={'operations':ops} and json.loads(v.read_text()).get('ok'):return json.loads(v.read_text())
 return await m.macro(name,ops)
async def frag(name,uid,code):return await macro(name,[{'execute':{'box':uid,'type':'fragment','code':code}}])
async def box(uid,code,properties=None):
 return await macro(uid,[{'create':{'box':uid,'frame':{'x':40.,'y':40.,'w':900.,'h':100.},'properties':{'code':code,**(properties or {})}}},{'connect':{'parent':'renderer','child':uid}},{'launch':{'box':uid}},{'await':{}}])
async def main():
 owner='''import afield.graphics.vulkan.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableVR=false
Options.enableHDR=false
Options.profiling=false
window=Window("Private resource ownership acceptance",initialDimensions=Rect(40.0,40.0,800.0,600.0))
renderer=Renderer(window)
start(renderer::run)'''
 await macro('g3-renderer',[{'create':{'box':'renderer','properties':{'code':owner}}},{'launch':{'box':'renderer'}},{'await':{}}])
 cases={
 'learning':('ShaderLearningView(renderer,source,name="ownership-learning",setSourceParameter={ _ -> })','ownership-learning','val source=RenderToTexture(renderer){width=96;height=64;useDepth=false;clearColor=vec(.2,.4,.7,1)}\n'),
 'reconstruction':('ReconstructionDemo(renderer,mode=null,outputSize=ReconstructionSize(96,64),name="ownership-reconstruction")','ownership-reconstruction',''),
 'flow':('OpticalFlowDemo(renderer,width=96,height=64,name="ownership-flow")','ownership-flow',''),
 }
 for kind,(constructor,name,prefix) in cases.items():
  for label in ['old','new']:
   ownership='' if kind=='reconstruction' else 'requireNotNull(RunScope.current()).own(view)\n'
   code='''import afield.graphics.shader.learning.*
import afield.media.reconstruction.*
import afield.media.vision.*
import afield.runtime.lifecycle.RunScope
'''+prefix+'val view='+constructor+'\n'+ownership+('view.update(0f,true)\n' if kind=='flow' else '')+f'val installed=renderer.renderList.modules.getValue("{name}")\nprintln("VERIFIED76 view installed")'
   await box('g3-'+kind+'-'+label,code)
  await macro('g3-'+kind+'-stop-old',[{'stop':{'box':'g3-'+kind+'-old'}},{'wait':{'frames':8}}])
  await frag('g3-'+kind+'-replacement-retained','g3-'+kind+'-new',f'check(renderer.renderList.modules["{name}"]===installed);'+('' if kind=='flow' else f'check(renderer.renderList.preTargets["{name}"]===view);')+'println("VERIFIED76 old Stop retained replacement")')
  await macro('g3-'+kind+'-stop-new',[{'stop':{'box':'g3-'+kind+'-new'}},{'wait':{'frames':8}}])
  await frag('g3-'+kind+'-detached','renderer',f'check(!renderer.renderList.modules.containsKey("{name}"));check(!renderer.renderList.preTargets.containsKey("{name}"));println("VERIFIED76 new Stop detached own view")')
 doc=yaml.safe_load((R/'dev_docs/agents/transcripts/neural_reconstruction.yaml').read_text());props=next(x['create']['properties'] for x in doc['operations'] if x.get('create',{}).get('box')=='neural_reconstruction')
 code=props['code'].replace('ReconstructionDemo(renderer, mode, attach = false)','ReconstructionDemo(renderer, mode, outputSize=ReconstructionSize(96,64), attach = false)')
 await box('g3-authored-reconstruction',code,{k:v for k,v in props.items() if k!='code'})
 await frag('g3-edit-reconstruction-source','g3-authored-reconstruction','fragment=requireNotNull(fragment)+"\\n// readability-owned-source-76\\n";reload=true')
 await m.batch('g3-source-reload-frames',['wait-native-renderer renderer --frames 12 --timeout 30'])
 await frag('g3-force-mode-recreation','g3-authored-reconstruction','val beforeMode=ownedLab;active=-1;requested=0')
 await m.batch('g3-source-mode-frames',['wait-native-renderer renderer --frames 12 --timeout 30'])
 await frag('g3-source-mode-persistence','g3-authored-reconstruction','''
check(ownedLab!==beforeMode)
val moduleField=ReconstructionDemo::class.java.getDeclaredField("module").also {it.isAccessible=true}
val activeModule=moduleField.get(ownedLab) as Module
check(requireNotNull(activeModule.parameters.fragmentIntrospection).source.contains("readability-owned-source-76"))
check(renderer.renderList.preTargets["neural-aa"]===ownedLab)
println("VERIFIED76 authored source survives real view recreation")
''')
 await m.batch('g3-resource-capture',[f'native-screenshot {m.OUT}/reconstruction.png --uid renderer --wait-frames 4 --timeout 30'])
 await macro('g3-reconstruction-stop',[{'stop':{'box':'g3-authored-reconstruction'}},{'wait':{'frames':8}}])
 await frag('g3-authored-detached','renderer','check(!renderer.renderList.modules.containsKey("neural-aa"));check(!renderer.renderList.preTargets.containsKey("neural-aa"));println("VERIFIED76 authored Stop detached view")')
 (H/'native-behavior.json').write_text(json.dumps({'actualOldStopRetainsReplacement':list(cases),'actualNewStopDetachesOwnView':list(cases),'reconstructionConstructorOwnsRunImmediately':True,'authoredReconstructionReloadPersistsAcrossViewRecreation':True,'actualNativeCapture':'runtime/reconstruction.png','limits':'Actual Vulkan via KosmicKrisp, source-only shader-learning view, portable optical flow and Source reconstruction at 96×64. The LearnedShader runtime also requires unavailable optional precompiled shaders (the actual constructor failure is retained). NVIDIA DLSS/NR/cluster/cooperative training paths are not available and are not claimed. Administrative reflection reads the actual active compiled fragment only in this verification rig.'},indent=2)+'\n')
async def transaction():
 doc=yaml.safe_load((R/'dev_docs/agents/transcripts/neural_reconstruction.yaml').read_text());props=next(x['create']['properties'] for x in doc['operations'] if x.get('create',{}).get('box')=='neural_reconstruction')
 code=props['code'].replace('ReconstructionDemo(renderer, mode, attach = false)','ReconstructionDemo(renderer, mode, outputSize=ReconstructionSize(96,64), attach = false)')
 uid='g3-authored-transaction'
 await box(uid,code,{k:v for k,v in props.items() if k!='code'})
 await frag('g3-transaction-invalid-source',uid,'val working=ownedLab;val installed=renderer.renderList.modules.getValue("neural-aa");val goodSource=fragment;fragment="invalid shader for actual rejection";active=-1;requested=0')
 await m.batch('g3-transaction-frames',['wait-native-renderer renderer --frames 12 --timeout 30'])
 await frag('g3-transaction-keeps-working',uid,'check(ownedLab===working);check(renderer.renderList.preTargets["neural-aa"]===working);check(renderer.renderList.modules["neural-aa"]===installed);fragment=goodSource;active=0;requested=0;println("VERIFIED76 rejected replacement kept the working view and module")')
 await macro('g3-transaction-stop',[{'stop':{'box':uid}}])
 p=H/'native-behavior.json';data=json.loads(p.read_text());data['actualRejectedReplacementRetainsWorkingViewAndModule']=True;p.write_text(json.dumps(data,indent=2)+'\n')
asyncio.run(transaction() if '--transaction' in __import__('sys').argv else main())
