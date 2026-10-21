"""Verify a fresh real analysis through trackAnalysis before adopting it in more examples."""
from pathlib import Path
import asyncio,importlib.util,json,sys,wave,numpy as np
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-43';OUT.mkdir(parents=True,exist_ok=True)
p=OUT/'readability43-test.wav'
if not p.exists():
 sr=48000;t=np.arange(sr*6,dtype=np.float64)/sr
 signal=0.2*np.sin(2*np.pi*220*t)+0.12*np.sin(2*np.pi*330*t)+0.08*np.sin(2*np.pi*440*t)
 signal+=0.22*np.exp(-((t*2)%1)*70)*np.sin(2*np.pi*1000*t)
 with wave.open(str(p),'wb') as f:f.setnchannels(1);f.setsampwidth(2);f.setframerate(sr);f.writeframes((signal*32767).astype('<i2').tobytes())
s=importlib.util.spec_from_file_location('rig43',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9713;m.OUT=OUT/'bootstrap';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 code='''import java.nio.file.Path
import java.util.concurrent.CancellationException
val client=FieldAudioClient("http://127.0.0.1:18765")
val accepted=java.util.concurrent.CopyOnWriteArrayList<JobAccepted>()
val completed=java.util.concurrent.CopyOnWriteArrayList<JobRecord>()
val progress=java.util.concurrent.CopyOnWriteArrayList<String>()
val operation=runAsync(
    onResult={ value:Pair<AnalysisResult,AnalysisPreview> ->
        display("analysis",analysisPreviewDashboardHtml([value.second],width=1000,panelHeight=260))
        display("done","completed real spectral analysis: ${value.first.id}")
    },
    onError={ failure -> display("error",failure.message ?: failure.toString()) },
) {
    val run=RunScope.current()
    val upload=client.importWav(Path.of(INPUT))
    onCancel { upload.cancel(true) }
    upload.thenCompose { source ->
        if(future.isDone) throw CancellationException("run ended")
        RunScope.withCurrent(run) {
            client.trackAnalysis(
                spectralEmbeddingAnalysis(AssetRegion(source.id,0.0,6.0),requestedBy="field:readability43/bootstrap"),
                onAccepted={ accepted.add(it) },
                onProgress={ _,event -> progress.add(event.toString()) },
                onCompleted={ completed.add(it) },
                onError={},
            )
        }.thenCompose { result ->
            client.download(result.data,Path.of(OUTPUT)).thenCombine(client.analysisPreview(result.id,1800)) { _,preview -> result to preview }
        }
    }
}
'''.replace('INPUT',json.dumps(str(p))).replace('OUTPUT',json.dumps(str(OUT/'fresh-spectral.npz')))
 await m.program('fresh-analysis',code)
 await m.batch('fresh-complete',['wait-output subject --channel done --contains "completed real spectral analysis" --timeout 180'])
 proof='''check(operation.future.isDone && !operation.future.isCompletedExceptionally)
check(accepted.size==1 && completed.size==1)
check(completed.single().state==JobState.COMPLETE)
check(completed.single().requestHash==operation.future.join().first.requestHash)
check(progress.isNotEmpty())
check(operation.future.join().second.durationSeconds>0)
println("fresh job="+completed.single().id+"; state="+completed.single().state+"; progress events="+progress.size)
println("result="+operation.future.join().first.id)
'''
 await m.macro('fresh-proof',[{'execute':{'box':'subject','property':'code','type':'fragment','code':proof}}])
 with np.load(OUT/'fresh-spectral.npz') as data:
  arrays={k:{'shape':list(data[k].shape),'dtype':str(data[k].dtype)} for k in data.files}
 assert arrays
 (OUT/'fresh-spectral-arrays.json').write_text(json.dumps(arrays,indent=2)+'\n')
asyncio.run(main())
