"""Real service failure/Stop paths and genuine GPU source/output JPEG completion."""
from pathlib import Path
import asyncio,importlib.util,json,yaml,urllib.request,shlex
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-72'
s=importlib.util.spec_from_file_location('rig72',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(__import__("os").environ.get("FIELD_TEST_PORT","9723"));m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('docs72',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
async def cached(name,ops):
 p=m.OUT/(name+'.yaml');v=m.OUT/(name+'.json')
 if p.exists() and v.exists() and yaml.safe_load(p.read_text())=={'operations':ops} and json.loads(v.read_text()).get('ok'):return json.loads(v.read_text())
 return await m.macro(name,ops)
async def box(name,body,parent=False):
 ops=[{'create':{'box':name,'frame':{'x':40.,'y':40.,'w':1000.,'h':100.},'properties':{'code':body}}}]
 if parent:ops.append({'connect':{'parent':'renderer','child':name}})
 return await cached(name,ops+[{'launch':{'box':name}},{'await':{}}])
async def main():
 health=json.load(urllib.request.urlopen('http://127.0.0.1:18765/health'));assert '/adoption-43/' in health['project_root'];(m.OUT/'service-identity.json').write_text(json.dumps(health,indent=2)+'\n')
 text=(R/'public/docs/field_audio_ai.md').read_text();bodies=[text[a:b] for a,b in d.sections(text)];assert len(bodies)==2
 transcript=yaml.safe_load((R/'dev_docs/agents/transcripts/audio_ai_gesture_board.yaml').read_text());body=next(x['create']['properties']['code'] for x in transcript['operations'] if x.get('create',{}).get('box')=='generate_and_rank');bodies.insert(0,body)
 for label,body in zip(['gesture','style','underfit'],bodies):
  body=body.replace('FieldAudioClient()','FieldAudioClient("http://127.0.0.1:18765")').replace('"*Nancy*"','"no-such-source-readability-72.wav"')
  if label=='underfit':
   # The real LoRA checkpoint/model environment is absent; compile the complete source without inventing one.
   body='if (false) {\n'+body.replace('import java.nio.file.Path\n','')+'\n}\nprintln("VERIFIED72 Underfit body compiles; checkpoint prerequisite unavailable")'
   body='import java.nio.file.Path\n'+body
  else:body=body.replace('runAsync(', 'val exercise = runAsync(',1)
  await box('g2-candidates-'+label,body)
  if label!='underfit':await m.batch('g2-real-lookup-error-'+label,[f'wait-output g2-candidates-{label} --channel "generation error" --contains "Generation failed" --timeout 30'])
 # Delay an actual failed service lookup to make Stop deterministic; no service result is substituted.
 body=bodies[0].replace('FieldAudioClient()','FieldAudioClient("http://127.0.0.1:18765")').replace('"*Nancy*"','"no-such-source-readability-72-stop.wav"').replace('runAsync(', 'val exercise = runAsync(',1)
 body=body.replace('val lookup = client.assetByFilename("no-such-source-readability-72-stop.wav")','''val lookup = java.util.concurrent.CompletableFuture.supplyAsync {
    Thread.sleep(1500)
    client.assetByFilename("no-such-source-readability-72-stop.wav").join()
}''')
 await cached('g2-candidate-stop',[{'create':{'box':'g2-candidate-stop','properties':{'code':body}}},{'launch':{'box':'g2-candidate-stop'}},{'await':{}},{'stop':{'box':'g2-candidate-stop'}},{'wait':{'frames':160}},{'execute':{'box':'g2-candidate-stop','type':'fragment','code':'check(exercise.future.isCancelled); println("VERIFIED72 Stop cancelled operation")'}}])
 probe='''renderer.renderList.preTargets["readability-capture-72"] = source
val captures = mutableListOf<java.util.concurrent.CompletableFuture<String>>()
val first = java.io.File(OUT,"source.jpg")
val second = java.io.File(OUT,"output.jpg")
captures += source.saveJpgAsync(first.absolutePath, quality = 95)
captures += source.saveJpgAsync(second.absolutePath, quality = 95)
frames {
    while(!captures.all { it.isDone }) frame()
    captures.forEach { it.join() }
    for(file in listOf(first,second)) {
        val image=checkNotNull(javax.imageio.ImageIO.read(file))
        check(image.width==96 && image.height==64)
    }
    val failure=source.saveJpgAsync(java.io.File(OUT).absolutePath, quality = 95)
    while(!failure.isDone) frame()
    check(runCatching { failure.join() }.isFailure)
    display("capture-proof", "VERIFIED72 both real JPEGs complete; real write failure rejected")
}.end { captures.forEach { it.cancel(false) }; renderer.renderList.preTargets.remove("readability-capture-72", source) }
'''.replace('OUT',json.dumps(str(m.OUT)))
 await cached('g2-real-capture-pairs',[{'execute':{'box':'g5-gpu_shader_learning-after-callback','type':'fragment','code':probe}}])
 await m.batch('g2-real-capture-proof',['wait-output g5-gpu_shader_learning-after-callback --channel capture-proof --contains VERIFIED72 --timeout 60'])
 await box('g2-diffusion-availability',(R/'optional/streamdiffusion/examples/capture-motion.kt').read_text(),True)
 (H/'behavior.json').write_text(json.dumps({'privateServiceIdentityVerified':True,'gestureAndStyleRealLookupFailureReported':True,'completeUnderfitSourceTypechecked':True,'stopCancelsDelayedActualLookupOperation':True,'twoRealGpuJpegsDecodedAt96x64':True,'realFilesystemWriteFailureRejectsCompletion':True,'completeDiffusionExampleActualAvailabilityBranchRuns':True,'limits':'No MusicGen/Underfit generation or TensorRT streaming diffusion is claimed. Underfit requires an unavailable real LoRA checkpoint; its complete body was typechecked in an unentered branch. Candidate failure tests use the real private service with an absent filename; Stop adds only a deterministic delay before a real lookup. JPEG evidence uses the real existing authored teacher render target.'},indent=2)+'\n')
async def finish_cpu():
 from PIL import Image
 # Retain completed real service verdicts and native files across the host interruption.
 previous=O/'runtime'
 for label in ['gesture','style']:
  assert json.loads((previous/('g2-real-lookup-error-'+label+'.json')).read_text())['ok']
 assert 'VERIFIED72 Stop cancelled operation' in (previous/'g2-candidate-stop.json').read_text()
 for name in ['source.jpg','output.jpg']:assert Image.open(previous/name).size==(96,64)
 m.OUT=O/'runtime-cpu';m.OUT.mkdir(parents=True,exist_ok=True)
 code=(R/'optional/streamdiffusion/examples/capture-motion.kt').read_text().replace('import afield.media.video.diffusion.*','')
 result=await box('g2-diffusion-typecheck','import afield.media.video.diffusion.*\nprop<Renderer>(renderer)\nif(false) {\n'+code+'\n}\nprintln("VERIFIED72 complete diffusion body typechecked")')
 assert 'VERIFIED72 complete diffusion body typechecked' in str(result)
 (H/'behavior.json').write_text(json.dumps({'privateServiceIdentityVerified':True,'gestureAndStyleRealLookupFailureReported':True,'completeUnderfitSourceTypechecked':True,'stopCancelsDelayedActualLookupOperation':True,'twoRealGpuJpegsDecodedAt96x64':True,'completeDiffusionBodyTypechecked':True,'limits':'No MusicGen/Underfit generation or TensorRT streaming diffusion is claimed. Underfit requires an unavailable real LoRA checkpoint; its complete body was typechecked in an unentered branch. Candidate failure tests use the real private service with an absent filename; Stop adds only a deterministic delay before a real lookup. JPEG evidence uses the real existing authored teacher render target. The private native host later blocked in vkAcquireNextImageKHR; its thread trace is retained and it was stopped. The subsequent GPU write-failure case did not finish and is unverified. A fresh CPU host typechecked the complete diffusion example without entering its hardware branch.'},indent=2)+'\n')
asyncio.run(finish_cpu() if '--finish-cpu' in __import__('sys').argv else main())
