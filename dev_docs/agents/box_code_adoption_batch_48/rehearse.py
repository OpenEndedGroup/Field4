"""Verify actual build cancellation and Phased loop yielding with controlled input."""
from pathlib import Path
import importlib.util,asyncio,json,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig48',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9718;m.OUT=R/'build/box-code-revision/adoption-48/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
from format_docs import sections
from verify_tokens import TOKEN
async def main():
 page=(R/'public/docs/ai4_long_builds.md').read_text();code=next(page[a:b] for a,b in sections(page));start=code.index('StudyBuildStage(');brace=code.index('{ context ->',start);depth=0
 for token in TOKEN.finditer(code,brace):
  if token[0]=='{':depth+=1
  elif token[0]=='}':
   depth-=1
   if not depth:end=token.end();break
 stage=code[start:end]
 instrumented=stage.replace('{ context ->','{ context ->\n began.countDown()\n try {',1)
 instrumented=instrumented[:-1]+'} finally { ended.countDown() }\n}'
 small=stage.replace('val batches = 60','val batches = 2').replace('val perBatch = 1_000_000_000','val perBatch = 100_000')
 old=small.replace('if (sample % 65_536 == 0) context.checkCancelled()','')
 program='''import afield.study.build.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
val began=CountDownLatch(1)
val ended=CountDownLatch(1)
val temporary=java.nio.file.Files.createTempDirectory("box48-builds-").toFile()
val longRunner=StudyBuilds(java.io.File(temporary,"cancel"),listOf('''+instrumented+''')) {emptyMap()}
try {
 val job=longRunner.start("pi",longRunner.stages().single().fingerprint!!)
 check(began.await(2,TimeUnit.SECONDS))
 Thread.sleep(50)
 val started=System.nanoTime()
 longRunner.cancel(job.id)
 check(ended.await(2,TimeUnit.SECONDS)) {"Worker did not leave its inner batch"}
 val elapsed=(System.nanoTime()-started)/1_000_000
 check(longRunner.job(job.id).status==StudyBuildStatus.CANCELLED)
 println("actual worker exited after cancellation in $elapsed ms")
} finally {longRunner.close()}
fun finish(runner:StudyBuilds):StudyBuildRecord {
 val accepted=runner.start("pi",runner.stages().single().fingerprint!!)
 val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
 var result=runner.job(accepted.id)
 while(result.status in setOf(StudyBuildStatus.QUEUED,StudyBuildStatus.RUNNING) && System.nanoTime()<deadline) {Thread.sleep(5);result=runner.job(accepted.id)}
 check(result.status==StudyBuildStatus.SUCCEEDED) {result.toString()}
 return result
}
val beforeRunner=StudyBuilds(java.io.File(temporary,"before"),listOf('''+old+''')) {emptyMap()}
val afterRunner=StudyBuilds(java.io.File(temporary,"after"),listOf('''+small+''')) {emptyMap()}
try {
 val before=finish(beforeRunner);val after=finish(afterRunner)
 check(before.facts==after.facts) {"Sampling changed: ${before.facts} vs ${after.facts}"}
 println("identical seeded 200000-sample completion: ${after.facts}")
} finally {beforeRunner.close();afterRunner.close()}
'''
 program=program.replace(')) {emptyMap()}', '),readInputs={emptyMap()})')
 if '--resume-loops' not in sys.argv:await m.macro('cooperative-build',[{'create':{'box':'build-check','properties':{'code':program}}},{'launch':{'box':'build-check'}},{'await':{}}],True)
 page=(R/'public/docs/diff-player.md').read_text();bodies=[page[a:b] for a,b in sections(page)];afters=[c for c in bodies if 'f.lineTo(at + vec(' in c];assert len(afters)==2
 for i,body in enumerate(afters):
  body=body.replace('lines["ourLine"] = f','emitted++\n        lines["ourLine"] = f')
  prefix='''val lines=mutableMapOf<String,FLine>()
val startedAt=afield.runtime.lifecycle.Runloop.runLoop.frame
var emitted=0
class ControlledController {
 fun controllerState()=this
 val triggerValue get()=if(afield.runtime.lifecycle.Runloop.runLoop.frame-startedAt<10)1.0 else 0.0
 val position get()=vec(0,0,0)
}
val stereo=ControlledController()
'''
  alias='drawing-'+str(i)
  await m.macro('held-trigger-'+str(i),[{'create':{'box':alias,'properties':{'code':prefix+body}}},{'launch':{'box':alias}},{'wait':{'frames':35}},{'execute':{'box':alias,'property':'code','type':'fragment','code':'check(emitted in 1..15) {"unexpected emissions $emitted"};check(afield.runtime.lifecycle.Runloop.runLoop.frame-startedAt>=20);println("held trigger yielded: $emitted points, input released")'}},{'stop':{'box':alias}}])
 (H/'behavior.json').write_text(json.dumps({'actualStudyBuildWorkerCancellation':True,'workerExitBoundSeconds':2,'seededCompletionSamples':200000,'seededCompletionFactsEqual':True,'actualPhasedAfterStates':2,'controlledInput':'Held for ten Runloop frames then released. No VR hardware or capture claimed.','fullGuards':'delegated to lander'},indent=2)+'\n')
asyncio.run(main())
