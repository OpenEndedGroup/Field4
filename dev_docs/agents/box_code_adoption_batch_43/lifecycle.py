"""Observe stop and active rerun with actual durable pYIN jobs, not a protocol stand-in."""
from pathlib import Path
import asyncio,importlib.util,json,sys,wave
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-43'
longInput=OUT/'readability43-long.wav'
if not longInput.exists():
 with wave.open(str(OUT/'readability43-test.wav'),'rb') as source:
  params=source.getparams(); pcm=source.readframes(source.getnframes())
 with wave.open(str(longInput),'wb') as target:target.setparams(params);target.writeframes(pcm*10)
s=importlib.util.spec_from_file_location('rig43',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9713;m.OUT=OUT/'lifecycle';m.OUT.mkdir(parents=True,exist_ok=True)
code='''import java.util.concurrent.CompletableFuture
val client = cached { FieldAudioClient("http://127.0.0.1:18765") }
val ledger = cached { java.util.concurrent.CopyOnWriteArrayList<String>() }
val label = "OLD"
ledger.add(label + ":start")
val pending = runAsync<AnalysisResult>(
    onResult = { result -> ledger.add(label + ":result"); display("done", label + ":complete") },
    onError = { failure -> ledger.add(label + ":error"); display("done", label + ":failed " + failure.message) },
) {
    val run = RunScope.current()
    client.importWav(java.nio.file.Path.of(INPUT)).thenCompose { source ->
        RunScope.withCurrent(run) {
            client.trackAnalysis(
                monophonicPitchAnalysis(AssetRegion(source.id,0.0,DURATION),requestedBy="field:readability43/lifecycle"),
                onAccepted={ ledger.add(label + ":accepted"); display("accepted",label) },
                onProgress={ _,event -> ledger.add(label + ":event") },
                onError={},
            )
        }
    }
}
'''.replace('INPUT',json.dumps(str(longInput)))
async def main():
 for kind,duration in [('stop','35.61'),('rerun','35.63')]:
  old=code.replace('DURATION',duration).replace('OLD',kind+'-old')
  await m.macro(kind+'-create',[{'create':{'box':'subject','name':kind+' lifecycle','frame':{'x':0.0,'y':0.0,'w':1200.0,'h':100.0},'properties':{'code':old}}}],True)
  await m.batch(kind+'-launch',['launch subject'])
  await m.batch(kind+'-accepted',['wait-output subject --channel accepted --contains '+kind+'-old --timeout 30'])
  await m.macro(kind+'-retain-old',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'val oldPending = pending\ncheck(!oldPending.future.isDone)'}}])
  if kind=='stop':await m.batch('stop-request',['stop subject','wait-running subject --state stopped --timeout 5'])
  new=old.replace(kind+'-old',kind+'-new').replace(duration, str(float(duration)-0.01))
  await m.macro(kind+'-replace',[{'edit':{'box':'subject','property':'code','from':old,'to':new}}])
  await m.batch(kind+'-replacement-launch',['launch subject'])
  await m.batch(kind+'-new-complete',['wait-output subject --channel done --contains '+kind+'-new --timeout 180'])
  proof='''check(oldPending.future.isCancelled)
check(pending.future.join().id.isNotBlank())
val newStart = ledger.indexOf("KIND-new:start")
check(newStart >= 0)
check(ledger.drop(newStart).none { it.startsWith("KIND-old:") })
check(ledger.count { it == "KIND-new:result" } == 1)
println("REAL_LIFECYCLE_KIND " + ledger.toList())
'''.replace('KIND',kind)
  await m.macro(kind+'-proof',[{'execute':{'box':'subject','property':'code','type':'fragment','code':proof}}])
asyncio.run(main())
