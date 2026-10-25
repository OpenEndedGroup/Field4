from pathlib import Path
import json,yaml,re,textwrap,importlib.util
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=json.loads((H/'changes.json').read_text())
s=importlib.util.spec_from_file_location('docs75s',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
def save(p,b):
 a=p.read_text()
 if a!=b:p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
def speech(code,live):
 prefix=code[:code.index('fun Box.tracked')].replace('val client = FieldAudioClient()','val client = cached { FieldAudioClient() }')
 prefix=prefix.replace('val projectionPoolMillis =','val projectionPoolMillis: Double? =').replace('val projectionHopMillis =','val projectionHopMillis: Double? =')
 start='projectionStartSeconds' if live else 'projectionStart';duration='projectionDurationSeconds' if live else 'projectionDuration';local='localPath' if live else 'local';sync='timeSync' if live else 'sync'
 x=code.index('.thenCompose { source ->')+len('.thenCompose { source ->');y=code.index('    val '+('results' if live else 'jobs')+' =',x);source=textwrap.dedent(code[x:y]).strip()
 x=code.index('client.speechEmbeddingSmileTsnePreview(');depth=0
 for i in range(code.index('(',x),len(code)):
  if code[i]=='(':depth+=1
  elif code[i]==')':
   depth-=1
   if depth==0:y=i+1;break
 preview=textwrap.dedent(code[x:y]).strip()
 x=code.index('.thenAccept { previews ->')+len('.thenAccept { previews ->');y=code.index('\n    }',x);publish=textwrap.dedent(code[x:y]).strip()
 publish=publish.replace('Field Smile t-SNE complete · 500 ms pool · 40 ms hop · ${previews.size} spaces','Field Smile t-SNE complete · $projectionDescription · ${previews.size} spaces')
 req='''"emotion2vec" to emotion2VecEmbeddingAnalysis(region),
"WavLM Base+" to wavlmBasePlusEmbeddingAnalysis(region, layer = 6),'''
 if live:req=req.replace('emotion2VecEmbeddingAnalysis(region)','emotion2VecEmbeddingAnalysis(region, requestedBy = "field:speech-embeddings/near-drowning")').replace('layer = 6)','layer = 6, requestedBy = "field:speech-embeddings/near-drowning")')
 work='''val matches = client.assetsByFilename("*near_drowning*").await()
val source = when {
    matches.size == 1 -> matches.single()
    matches.isEmpty() && Files.isRegularFile(LOCAL) -> client.importWav(LOCAL).await()
    matches.isEmpty() -> throw IllegalStateException("near_drowning.wav is neither imported nor present at $LOCAL")
    else -> throw IllegalStateException("near_drowning filename matched ${matches.size} assets")
}
SOURCE
val requests = [
REQUESTS
]
val results = coroutineScope {
    requests.map { (label, request) ->
        async {
            client.trackAnalysis(
                request,
                onAccepted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                onProgress = { job, event -> display("$label job", fieldAudioJobStatusHtml(job, event)) },
                onCompleted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                onError = {}, // The surrounding coroutine reports the combined failure.
            ).await()
        }
    }.awaitAll()
}
val projectionDescription = "start ${START}s · duration ${DURATION}s · pool ${projectionPoolMillis?.let { "${it} ms" } ?: "raw"} · hop ${projectionHopMillis?.let { "${it} ms" } ?: "native"}"
display("projection", "Fitting Field Smile t-SNE · $projectionDescription…")
val previews = coroutineScope {
    results.map { result ->
        async { PREVIEW.await() }
    }.awaitAll()
}
PUBLISH'''.replace('LOCAL',local).replace('SOURCE',source).replace('REQUESTS',req).replace('START',start).replace('DURATION',duration).replace('PREVIEW',preview).replace('PUBLISH',publish)
 return 'import kotlinx.coroutines.*\nimport kotlinx.coroutines.future.await\n'+prefix+'''go {
    try {
'''+textwrap.indent(work,'        ')+'''
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("speech analysis error", "Speech analysis failed: ${(failure.message ?: failure.toString()).md}")
    }
}
'''
p=R/'dev_docs/agents/transcripts/audio_ai_speech_embeddings.yaml';a=p.read_text();ed=[]
def walk(n):
 if isinstance(n,yaml.MappingNode):
  for k,v in n.value:
   if k.value=='code' and isinstance(v,yaml.ScalarNode) and 'fun Box.trackedSpeech' in v.value:
    c=speech(v.value,True);ed.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*(k.start_mark.column+2)+x+'\n' if x else '\n' for x in c.splitlines())))
   else:walk(v)
 elif isinstance(n,yaml.SequenceNode):
  for x in n.value:walk(x)
walk(yaml.compose(a));b=a
for x,y,c in reversed(ed):b=b[:x]+c+b[y:]
save(p,b)
p=R/'public/docs/audio_ai_speech_embeddings.md';a=p.read_text();ed=[]
for x,y in d.sections(a):
 if 'fun Box.tracked(' in a[x:y]:ed.append((x,y,speech(a[x:y],False)))
b=a
for x,y,c in reversed(ed):b=b[:x]+c+b[y:]
b=b.replace('Re-running the unchanged box returns', 'Stop or rerun cancels both local subscriptions and pending projection futures through the\nbox-owned coroutine. Status captions are built from the current range, pool and hop controls.\n\nRe-running the unchanged box returns',1);save(p,b)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
