"""Verify actual study replacement, source-mode controls and record-state restoration."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
from PIL import Image,ImageChops
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig59',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9719;m.OUT=R/'build/box-code-revision/adoption-59/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,box,code):return await m.macro(name,[{'execute':{'box':box,'type':'fragment','code':'run {\n'+code+'\n}'}}])
async def save(name,box,study):
 path=m.OUT/(name+'.png')
 await fragment(name+'-request',box,'savedFrame='+study+'.saveFrame(java.nio.file.Path.of('+json.dumps(str(path))+'))')
 await m.batch(name+'-render',['wait-native-renderer renderer --frames 12 --timeout 60'])
 # Readback and PNG encoding finish asynchronously, independently of native frame count.
 for attempt in range(30):
  verdict=await fragment(name+'-ready-'+str(attempt),box,'println('+json.dumps('READBACK_READY:'+name+':'+str(attempt)+'=')+'+savedFrame.isDone)')
  ready=any(o.get('html')=='READBACK_READY:'+name+':'+str(attempt)+'=true' for result in verdict['results'] for o in result.get('data',{}).get('output',[]))
  if ready:break
  await m.macro(name+'-wait-'+str(attempt),[{'wait':{'frames':30}}])
 else:raise AssertionError('GPU readback did not complete: '+name)
 await fragment(name+'-saved',box,'savedFrame.get();println("actual GPU PNG saved")')
 return path
async def dom(alias,label,expected):
 path=m.OUT/(label+'-controls.json');js='''const box=[...document.querySelectorAll('.box')].find(b=>b.uid===ALIAS);
if(!box)throw new Error('missing study box');
return JSON.stringify({reload:[...box.querySelectorAll('.field-button')].some(e=>e.textContent.includes('Reload edited shaders')),animate:[...box.querySelectorAll('.field-checkbox')].some(e=>e.textContent.includes('Animate source'))});'''.replace('ALIAS',json.dumps(alias))
 script='java.io.File('+json.dumps(str(path))+').writeText(ui.eval('+json.dumps(js)+').toString())'
 await m.macro(label+'-dom',[{'wait':{'frames':10}},{'drive':{'box':alias,'script':script}}]);value=json.loads(path.read_text());value=json.loads(value) if isinstance(value,str) else value;assert value=={'reload':expected,'animate':expected},value
 return value
async def main():
 if '--resume-after-replacement' not in __import__('sys').argv:
  await m.batch('retire-initial-proof',['stop replacement-proof'])
  await m.batch('crowd-stop',['stop crowd'])
  await fragment('crowd-hide','crowd','renderer.renderList.modules.remove("gpu skinned crowd");println("retired private crowd drawing")')
  setup='''import afield.graphics.render.neural.*
 prop<OpenDlssNrStudy>(oldStudy)
 prop<OpenDlssNrStudy>(replacementStudy)
 prop<java.util.concurrent.CompletableFuture<java.nio.file.Path>>(savedFrame)
 oldStudy=OpenDlssNrStudy(renderer,size=64,name="nr-replacement")
 replacementStudy=OpenDlssNrStudy(renderer,size=64,name="nr-replacement")
 check(renderer.renderList.preTargets["nr-replacement"]===replacementStudy)
 println("real replacement installed")'''
  await m.macro('replacement-scoped-create',[{'create':{'box':'replacement-scoped-proof','properties':{'code':setup}}},{'connect':{'parent':'renderer','child':'replacement-scoped-proof'}},{'execute':{'box':'replacement-scoped-proof'}}])
  before=await save('scoped-replacement-before-close','replacement-scoped-proof','replacementStudy')
  await fragment('scoped-old-close-preserves-new','replacement-scoped-proof','val currentScreen=renderer.renderList.modules.getValue("nr-replacement");oldStudy.close();check(renderer.renderList.preTargets["nr-replacement"]===replacementStudy);check(renderer.renderList.modules["nr-replacement"]===currentScreen);println("old close leaves the new target and exact screen pointer installed")')
  after=await save('scoped-replacement-after-close','replacement-scoped-proof','replacementStudy')
  assert ImageChops.difference(Image.open(before).convert('RGBA'),Image.open(after).convert('RGBA')).getbbox() is None
  asset=(R/'public/docs/assets/opendlssnr/OpenDlssNrStudy.kt').read_text();assert 'screen?.let { renderer.renderList.modules.remove(name, it) }' in asset
 else:
  before=m.OUT/'scoped-replacement-before-close.png';after=m.OUT/'scoped-replacement-after-close.png'
  assert ImageChops.difference(Image.open(before).convert('RGBA'),Image.open(after).convert('RGBA')).getbbox() is None
 await fragment('replacement-close','replacement-scoped-proof','replacementStudy.close()')
 doc=yaml.safe_load((R/'dev_docs/agents/demos/opendlssnr/image-study.yaml').read_text());box=next(o['create'] for o in doc['operations'] if o.get('create',{}).get('box')=='open_dlss_nr');box['box']='nr-example';source=box['properties']['code'].replace('size = 768','size = 96');box['properties']['code']=source
 await m.macro('image-mode-create',[{'create':box},{'connect':{'parent':'renderer','child':'nr-example'}},{'launch':{'box':'nr-example'}}])
 imageDom=await dom('nr-example','image-mode',False)
 await fragment('image-reload-contract','nr-example','check(imagePath!=null);check(lab.reload().isFailure);println("still-image source does not advertise procedural reload")')
 procedural=source.replace('val imagePath: String? = "public/docs/assets/opendlssnr/ink-input.png"','val imagePath: String? = null');assert procedural!=source
 path=m.OUT/'procedural.kt';path.write_text(procedural)
 await m.batch('procedural-mode',[f'set nr-example code --file {path}','launch nr-example','wait-native-renderer renderer --frames 12 --timeout 60'])
 proceduralDom=await dom('nr-example','procedural-mode',True)
 await fragment('procedural-reload-contract','nr-example','check(imagePath==null);check(lab.reload(vertex=requireNotNull(vertex),fragment=requireNotNull(fragment)).isSuccess);println("procedural source reloads the actual authored shaders")')
 await m.batch('example-stop',['stop nr-example'])
 # Independent live study: controller cannot overwrite animate while recording is tested.
 recordOwner='''import afield.graphics.render.neural.*
prop<OpenDlssNrStudy>(lab)
prop<java.util.concurrent.ConcurrentLinkedQueue<String>>(recordEvents)
recordEvents=java.util.concurrent.ConcurrentLinkedQueue<String>()
lab=OpenDlssNrStudy(renderer,size=64,name="record-proof")
lab.animate=true
println("actual source-mode GPU record target ready")'''
 await m.macro('record-owner',[{'create':{'box':'record-owner','properties':{'code':recordOwner}}},{'connect':{'parent':'renderer','child':'record-owner'}},{'execute':{'box':'record-owner'}}])
 original=(R/'public/docs/assets/opendlssnr/record.kt').read_text()
 base=original.replace('check(study.availability.ready) { study.availability.reason }','// Fixture: source-mode GPU frames exercise cleanup without the optional NR pass.').replace('"build/opendlssnr-study/clip"',json.dumps(str(m.OUT/'clip'))).replace('0 until 180','0 until 3').replace('study.recording = false','study.recording = false\n        check(study.time==previousTime && study.animate==previousAnimate)\n        recordEvents.add("restored:"+previousAnimate)')
 await m.macro('record-three-frames',[{'create':{'box':'record-success','properties':{'code':base}}},{'connect':{'parent':'record-owner','child':'record-success'}},{'launch':{'box':'record-success'}}])
 await m.batch('record-completes',['wait-native-renderer renderer --frames 40 --timeout 60'])
 await fragment('record-success-state','record-owner','check(recordEvents.toList()==listOf("restored:true"));check(!lab.recording && lab.animate);println("record success restores animated source state")')
 cancelled=base.replace('try {\n','try {\n        delay(10_000)\n',1)
 await m.macro('record-held',[{'create':{'box':'record-held','properties':{'code':cancelled}}},{'connect':{'parent':'record-owner','child':'record-held'}},{'launch':{'box':'record-held'}}])
 await fragment('record-held-state','record-owner','check(lab.recording && !lab.animate);println("recording suspended before first capture")')
 await m.batch('record-stop',['stop record-held'])
 await fragment('record-stop-state','record-owner','check(recordEvents.toList()==listOf("restored:true","restored:true"));check(!lab.recording && lab.animate);println("Stop restores animated source state");lab.close()')
 assert len(list((m.OUT/'clip').glob('frame-*.png')))==3
 (H/'behavior.json').write_text(json.dumps({'lateOldStudyClosePreservesNewTargetAndScreen':True,'actualGpuPixelsUnchangedByOldClose':True,'downloadedStudyAlreadyUsesSameIdentityGuard':True,'actualImageModeControls':imageDom,'actualProceduralModeControls':proceduralDom,'actualProceduralShaderReload':True,'recordedRealGpuFrames':3,'recordSuccessAndStopRestoreTimeAndAnimate':True,'limits':'Source-mode Vulkan rendering only; no optional NVIDIA NR inference. Demo size reduced to 96 and record target to 64. Recording fixture removes only the NR readiness guard and uses three frames; Stop fixture delays before capture. No public media replaced.'},indent=2)+'\n')
asyncio.run(main())
