from pathlib import Path
import json,yaml,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def save(p,b):
 a=p.read_text()
 if a!=b:p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
def operation(body,result):
 # Every HTTP/polling leg stays cancellable even though CompletableFuture composition is not transitive.
 return '''runAsync(
    onResult = { result: CandidateSet ->
RESULT
    },
    onError = { failure -> markdown("generation error", "Generation failed: ${(failure.message ?: failure.toString()).md}") },
) {
    val lookup = client.assetByFilename("*Nancy*")
    onCancel { lookup.cancel(false) }
    lookup.thenCompose { source ->
        if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
BODY
    }
}
'''.replace('RESULT','\n'.join('        '+x for x in result.splitlines())).replace('BODY','\n'.join('        '+x for x in body.splitlines()))
def pending(expr):return '''val submitted = '''+expr+'''
onCancel { submitted.cancel(false) }
submitted.thenCompose { accepted ->
    if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
    client.awaitCandidates(accepted).also { polling -> onCancel { polling.cancel(false) } }
}'''
p=R/'dev_docs/agents/transcripts/audio_ai_gesture_board.yaml';a=p.read_text();ed=[]
def walk(n):
 if isinstance(n,yaml.MappingNode):
  for k,v in n.value:
   if k.value=='code' and isinstance(v,yaml.ScalarNode) and 'client.submit(' in v.value:
    s=v.value.replace('val client = FieldAudioClient()','val client = cached { FieldAudioClient() }');s=s.replace('val source = client.assetByFilename("*Nancy*").get()\n','');s=s.replace('val region = AssetRegion(source.id, startSeconds = 18.0, durationSeconds = 4.5)\n','');s=s[:s.index('client.submit(')]+operation('val region = AssetRegion(source.id, startSeconds = 18.0, durationSeconds = 4.5)\n'+pending('client.submit(gestureCandidates(region, sketch, seeds = 0L until 8L))'),'display("ranked gesture", gestureBoardHtml(sketch, candidates = result))\ndisplay("audition", candidateBoardHtml(result))')
    ed.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*(k.start_mark.column+2)+x+'\n' if x else '\n' for x in s.splitlines())))
   else:walk(v)
 elif isinstance(n,yaml.SequenceNode):
  for x in n.value:walk(x)
walk(yaml.compose(a));b=a
for x,y,s in reversed(ed):b=b[:x]+s+b[y:]
save(p,b)
p=R/'public/docs/field_audio_ai.md';a=p.read_text();ed=[]
for match in re.finditer(r'```diffstatic \{data-lang=kotlin\}\n([\s\S]*?)\n```',a):
 s=match[1].replace('val client = FieldAudioClient()','val client = cached { FieldAudioClient() }');s=s.replace('val source = client.assetByFilename("*Nancy*").get()\n','')
 if 'val request =' in s:
  start=s.index('val region =');end=s.index('client.submit(request)');body=s[start:end]+pending('client.submit(request)');s=s[:start]+operation(body,'display("candidates", candidateBoardHtml(result))')
 else:
  start=s.index('val region =');region=s[start:s.index('\n',start)];s=s[:start]+s[s.index('\n',start)+1:];start=s.index('client\n');expr=s[start:s.index('    .thenCompose(client::awaitCandidates)',start)].strip();s=s[:start]+operation(region+'\n'+pending(expr),'display("underfit", candidateBoardHtml(result))')
 ed.append((match.start(1),match.end(1),s.rstrip()))
b=a
for x,y,s in reversed(ed):b=b[:x]+s+b[y:]
b=b.replace('The source window, text, seed family,', 'Stop or rerun cancels this box’s lookup and local polling, and suppresses obsolete output.\nThe durable service job and cached shared client remain available.\n\nThe source window, text, seed family,',1);save(p,b)
p=R/'optional/streamdiffusion/examples/capture-motion.kt';s=p.read_text().replace('    frames {','''    val captures = mutableListOf<java.util.concurrent.CompletableFuture<String>>()
    frames {''',1).replace('        var lastSave: java.util.concurrent.CompletableFuture<String>? = null\n','').replace('                    demo.source.saveJpgAsync','                    captures += demo.source.saveJpgAsync').replace('                    lastSave = demo.view.output.saveJpgAsync','                    captures += demo.view.output.saveJpgAsync').replace('lastSave?.isDone == true','captures.all { it.isDone }').replace('                    proofDone = true','''                    // isDone also covers failure; join every source/output pair before claiming success.
                    captures.forEach { it.join() }
                    proofDone = true''').replace('.end { demo.close() }','''.end {
            captures.forEach { it.cancel(false) }
            demo.close()
        }''');save(p,s)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
