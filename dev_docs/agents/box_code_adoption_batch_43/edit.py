"""Adopt tracked analyses while preserving each surface's source and dashboard options."""
from pathlib import Path
import json,re,yaml,subprocess
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
rows=[]
def record(path,edit):
 p=R/path;before=subprocess.check_output(["git","show","5e02bad8c:"+path],cwd=R,text=True);after=edit(before);assert before!=after;rows.append(dict(path=path,before=before,after=after));p.write_text(after)
def adopt(code):
 code=code.replace('val client = FieldAudioClient()', 'val client = cached { FieldAudioClient() }')
 code=code.replace('import java.util.concurrent.CompletableFuture','import afield.runtime.lifecycle.RunScope\nimport java.util.concurrent.CancellationException\nimport java.util.concurrent.CompletableFuture')
 a=code.index('client.assetByFilename(');b=code.index('.thenCompose { source ->',a)
 lookup=code[a:b]
 body=code[b+len('.thenCompose { source ->'):code.index('    val requests =',b)]
 body=body.replace('display("source",','showSource(')
 req=code[code.index('    val requests ='):code.index('    val futures =') if '    val futures =' in code else code.index('    val results =')]
 result=code[code.index('}.thenAccept { results ->')+len('}.thenAccept { results ->'):code.index('}.exceptionally')]
 return code[:a]+'''runAsync(
    onResult = { results: List<AnalysisResult> ->'''+result+'''    },
    onError = { failure ->
        markdown("analysis error", "Analysis failed: ${(failure.message ?: failure.toString()).md}")
    },
) {
    val showSource = progress<String> { display("source", it) }
    // HTTP continuations run elsewhere; every analysis still belongs to this box run.
    val run = RunScope.current()
    val lookup = '''+lookup+'''
    onCancel { lookup.cancel(true) }
    lookup.thenCompose { source ->
        if (future.isDone) throw CancellationException("run ended")
'''+body+req+'''        val analyses = requests.map { (label, request) ->
            RunScope.withCurrent(run) {
                client.trackAnalysis(
                    request,
                    onAccepted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                    onProgress = { job, event -> display("$label job", fieldAudioJobStatusHtml(job, event)) },
                    onCompleted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                    onError = {}, // The outer operation reports failure once.
                )
            }.also { pending -> onCancel { pending.cancel(true) } }
        }
        CompletableFuture.allOf(*analyses.toTypedArray()).thenApply { analyses.map { it.join() } }
    }
}
'''
def doc(text):
 a=text.index('import java.util.concurrent.CompletableFuture');b=text.index('\n```',a)
 text=text[:a]+adopt(text[a:b])+text[b:]
 return text.replace('An unchanged request still goes through `submit` and `awaitAnalysis`, but the service resolves it to', 'An unchanged request still goes through `trackAnalysis`, but the service resolves it to').replace('new operation. `AnalysisResult.data`','new operation. Stop or rerun closes this run’s progress subscriptions and suppresses stale output;\nthe durable service jobs and their cached results remain available. `AnalysisResult.data`')
record('public/docs/audio_ai_analysis.md',doc)
def transcript(text):
 node=yaml.compose(text)
 for op in node.value:
  pass
 # Replace just the live box scalar, retaining all operation metadata and other code bodies.
 def visit(n):
  if isinstance(n,yaml.MappingNode):
   d={k.value:v for k,v in n.value}
   if d.get('box') and d['box'].value=='analyse_nancy':
    props=d['properties'];v=next(v for k,v in props.value if k.value=='code');return v
   for k,v in n.value:
    found=visit(v)
    if found:return found
  elif isinstance(n,yaml.SequenceNode):
   for v in n.value:
    found=visit(v)
    if found:return found
 v=visit(node);assert v;new=adopt(v.value)
 return text[:v.start_mark.index]+'|\n'+''.join('          '+line+'\n' if line else '\n' for line in new.rstrip().splitlines())+text[v.end_mark.index:]
record('dev_docs/agents/transcripts/audio_ai_analysis.yaml',transcript)
# The existing starting-point implementation already owns its HTTP continuations and fit publication.
# Use that same reviewed body on the page instead of maintaining another unowned watch helper.
spectral=yaml.safe_load((R/'dev_docs/agents/transcripts/audio_ai_spectral_embeddings.yaml').read_text())
codes=[o['create']['properties']['code'] for o in spectral['operations'] if 'create' in o]
live=next(c for c in codes if 'val localPath' in c)
def spectraldoc(text):
 fences=list(re.finditer(r'```diffstatic \{data-lang=kotlin\}\n([\s\S]*?)\n```',text));assert len(fences)==1
 f=fences[0];return text[:f.start(1)]+live.rstrip()+text[f.end(1):]
record('public/docs/audio_ai_spectral_embeddings.md',spectraldoc)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
