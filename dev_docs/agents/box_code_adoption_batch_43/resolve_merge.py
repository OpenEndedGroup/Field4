"""Integrate verified ownership with main's newer real galleries and capture presentation."""
from pathlib import Path
import subprocess,json,re,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
paths=['public/docs/audio_ai_analysis.md','dev_docs/agents/transcripts/audio_ai_analysis.yaml','public/docs/audio_ai_corpus_microscope.md','public/docs/audio_ai_spectral_embeddings.md']
def git(ref,path):return subprocess.check_output(['git','show',ref+':'+path],cwd=R,text=True)
ours={p:git('b910f54d5',p) for p in paths};theirs={p:git('main',p) for p in paths}
p=paths[0];s=ours[p];header=next(l for l in theirs[p].splitlines() if l.startswith('```diffstatic_runner'))
s=s.replace('```diffstatic {data-lang=kotlin}',header).replace('The persistent monitor','The monitor')
s=s.replace('import java.util.concurrent.CancellationException',':::fold imports and client\nimport java.util.concurrent.CancellationException',1).replace('val client = cached { FieldAudioClient() }','val client = cached { FieldAudioClient() }\n:::endfold',1)
s=s.replace('        display(\n            "analysis",','        displayClear("all jobs")\n        for (label in ["beats", "monophonic pitch", "polyphonic pitch"]) displayClear("$label job")\n        displayOpen("source")\n        display(\n            "analysis",',1)
s=s.replace('                results,\n                panelHeight','                results,\n                width = 1040,\n                panelHeight',1)
s=s.replace('    },\n    onError =','        displayOpen("analysis")\n    },\n    onError =',1)
s=s.replace('                    initialStartSeconds = start,','                    width = 1040,\n                    waveformHeight = 110,\n                    initialStartSeconds = start,',1)
s=s.replace('        val requests = [','        :::fold submit and track the three analysis jobs\n        val requests = [',1).replace('CompletableFuture.allOf(*analyses.toTypedArray()).thenApply { analyses.map { it.join() } }','CompletableFuture.allOf(*analyses.toTypedArray()).thenApply { analyses.map { it.join() } }\n        :::endfold',1)
resolved={p:s}
# Preserve the new real cached gallery and all incoming operation metadata. The live body
# matches the page, without its documentation-only fold directives.
p=paths[1];s=theirs[p];node=yaml.compose(s)
def live(n):
 if isinstance(n,yaml.MappingNode):
  d={k.value:v for k,v in n.value}
  if d.get('box') and d['box'].value=='analyse_nancy':return next(v for k,v in d['properties'].value if k.value=='code')
  for _,v in n.value:
   f=live(v)
   if f:return f
 elif isinstance(n,yaml.SequenceNode):
  for v in n.value:
   f=live(v)
   if f:return f
v=live(node);body=re.search(r'```diffstatic_runner[^\n]*\n([\s\S]*?)\n```',resolved[paths[0]])[1]
body='\n'.join(l for l in body.splitlines() if not l.strip().startswith(':::'))+'\n'
s=s[:v.start_mark.index]+'|\n'+''.join('          '+l+'\n' if l else '\n' for l in body.rstrip().splitlines())+s[v.end_mark.index:];resolved[p]=s
p=paths[2];s=ours[p].replace('fun tracked(run:', ':::fold durable job tracking\nfun tracked(run:',1).replace('\nrunAsync<AudioAsset>(', '\n:::endfold\n\nrunAsync<AudioAsset>(',1);resolved[p]=s
p=paths[3];s=ours[p];a=s.index('```diffstatic {data-lang=kotlin}');front=s[:a];s=s[a:]
s=s.replace('import java.nio.file.Files',':::fold imports\nimport java.nio.file.Files',1).replace('import java.util.concurrent.CompletableFuture','import java.util.concurrent.CompletableFuture\n:::endfold',1)
s=s.replace('    val tracked:', '    :::fold durable job tracking\n    val tracked:',1).replace('    val lookup =', '    :::endfold\n    val lookup =',1)
s=s.replace('            when {','            :::fold find or import the recording\n            when {',1).replace('\n        }\n        .thenCompose { source ->','\n            :::endfold\n        }\n        .thenCompose { source ->',1)
resolved[p]=front+s
rows=[]
for p in paths:
 (R/p).write_text(resolved[p]);rows.append({'path':p,'before':theirs[p],'after':resolved[p]})
(H/'merge-resolution.json').write_text(json.dumps(rows,indent=2)+'\n')
