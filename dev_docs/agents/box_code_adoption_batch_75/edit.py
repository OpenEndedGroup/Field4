from pathlib import Path
import json,yaml,re,textwrap,importlib.util
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
s=importlib.util.spec_from_file_location('docs75',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
def save(p,b):
 a=p.read_text()
 if a!=b:p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
def yaml_edit(p,change):
 a=p.read_text();ed=[]
 def walk(n):
  if isinstance(n,yaml.MappingNode):
   for k,v in n.value:
    if k.value=='code' and isinstance(v,yaml.ScalarNode):
     code=change(v.value)
     if code!=v.value:ed.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*(k.start_mark.column+2)+x+'\n' if x else '\n' for x in code.splitlines())))
    else:walk(v)
  elif isinstance(n,yaml.SequenceNode):
   for x in n.value:walk(x)
 walk(yaml.compose(a));b=a
 for x,y,c in reversed(ed):b=b[:x]+c+b[y:]
 save(p,b)
def go(work,error):return '''go {
    try {
'''+textwrap.indent(work.strip(),'        ')+'''
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("'''+error+'''", "Operation failed: ${(failure.message ?: failure.toString()).md}")
    }
}
'''
IMPORT='import kotlinx.coroutines.*\nimport kotlinx.coroutines.future.await\n'
def track(request,channel):return '''client.trackAnalysis(
    REQUEST,
    onAccepted = { display("CHANNEL", fieldAudioJobStatusHtml(it)) },
    onProgress = { job, event -> display("CHANNEL", fieldAudioJobStatusHtml(job, event)) },
    onCompleted = { display("CHANNEL", fieldAudioJobStatusHtml(it)) },
    onError = {}, // Report failure once through the surrounding coroutine.
).await()'''.replace('REQUEST',request).replace('CHANNEL',channel)
def muscriptor(code,live):
 marker='client\n    .assetByFilename(sourcePattern)';prefix=code[:code.index(marker)].replace('val client = FieldAudioClient()','val client = cached { FieldAudioClient() }')
 # Keep request controls and the source player's dimensions/caption exactly as authored.
 begin=code.index('    .thenCompose { source ->')+len('    .thenCompose { source ->');end=code.index('        client\n            .submit(',begin);source=textwrap.dedent(code[begin:end]).strip()
 request_start=code.index('musicTranscriptionAnalysis(',end);request_end=code.index('\n            )\n            .thenCompose',request_start);request=textwrap.dedent(code[request_start:request_end].rstrip().rstrip(',')).strip()
 # Request itself ends at the inner function's closing parenthesis.
 depth=0
 for i,c in enumerate(request):
  if c=='(':depth+=1
  elif c==')':
   depth-=1
   if depth==0:request=request[:i+1];break
 result='''display("score", analysisVisualizationHtml(result, width = 1100, height = 420, syncGroup = sync))'''
 if live:
  result+='''
val transcription = client.musicTranscription(result).await()
val byInstrument = transcription.notes.groupingBy { it.instrument }.eachCount()
display("typed notes", "${transcription.notes.size} notes · $byInstrument")
display("interpretation", "Instrument labels are predictions. Compare pitches and onsets with the source; a completed job is not an accuracy guarantee.")
val downloads = mutableListOf<Path>()
downloads.add(client.download(result.musicTranscriptionMidi(), midiDirectory.resolve("muscriptor-performance.mid")).await())
result.musicTranscriptionQuantizedMidiOrNull()?.let { midi ->
    downloads.add(client.download(midi, midiDirectory.resolve("muscriptor-quantized.mid")).await())
}
display("MIDI", downloads.joinToString("<br>") { "saved $it" })'''
 else:result+='''
transcriptionResult = result
val performanceMidi = result.musicTranscriptionMidi()
val quantizedMidi = result.musicTranscriptionQuantizedMidiOrNull()
display("artifacts", "performance MIDI: ${performanceMidi.id}; quantized: ${quantizedMidi?.id ?: "not produced"}")'''
 return IMPORT+prefix+('prop<AnalysisResult>(transcriptionResult)\n' if not live else '')+go('val source = client.assetByFilename(sourcePattern).await()\n'+source+'\nval result = '+track(request,'transcription job' if live else 'job')+'\n'+result,'MuScriptor error')
yaml_edit(R/'dev_docs/agents/transcripts/audio_ai_muscriptor.yaml',lambda c:muscriptor(c,True) if '.assetByFilename(sourcePattern)' in c else c)
p=R/'public/docs/audio_ai_muscriptor.md';before=p.read_text();ed=[]
for a,b in d.sections(before):
 code=before[a:b]
 if '.assetByFilename(sourcePattern)' in code:
  # Remove documentation-only folds before rewriting this complete executable body.
  lines=code.splitlines();code='\n'.join(x for x in lines if not x.lstrip().startswith(':::'));ed.append((a,b,muscriptor(code,False)))
 elif 'client.musicTranscription(result).thenAccept' in code:
  ed.append((a,b,IMPORT+go('val transcription = client.musicTranscription(transcriptionResult).await()\nval cello = transcription.notes.filter { it.instrument == "cello" }\ndisplay("cello notes", cello.joinToString("<br>"))','MuScriptor error')))
after=before
for a,b,c in reversed(ed):after=after[:a]+c+after[b:]
after=after.replace('`client.musicTranscription(result)` downloads','After the transcription finishes, run the next fragment in the same box. The typed\n`transcriptionResult` property retains its durable result; `client.musicTranscription(transcriptionResult)` downloads').replace('Compose futures, update\nthe status card from worker events, and await the durable result:', 'Use a box-owned coroutine and `trackAnalysis` for progress and the durable result.\nStop or rerun cancels this run’s subscriptions and pending local futures; the durable service job remains:');save(p,after)

(H/"changes.json").write_text(json.dumps(rows,indent=2)+"\n")
