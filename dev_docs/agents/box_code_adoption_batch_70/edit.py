from pathlib import Path
import json,yaml,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def save(p,b):
 a=p.read_text()
 if a!=b:p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
def reduction(s):
 s=s.replace('mutableListOf<Triple<String, List<FLine>, String>>()','mutableListOf<InteractionStage>()').replace('Triple(', 'stage(').replace('{ it.first }','{ it.title }')
 a=s.index('val panelWidth =');b=s.index('\n/*',a)
 return s[:a]+'''showInteractionStages(
    *stages.toTypedArray(),
    channel = "reduction",
    graphicTitle = "$source, one panel per stage",
    stageWidth = 196.0,
    stageHeight = 250.0,
    gap = 10.0,
    padding = 22.0,
)
'''+s[b:]
def patch_yaml(p,change):
 a=p.read_text();ed=[]
 def walk(n):
  if isinstance(n,yaml.MappingNode):
   for k,v in n.value:
    if k.value=='code' and isinstance(v,yaml.ScalarNode):
     code=change(v.value)
     if code!=v.value:ed.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*(k.start_mark.column+2)+s+'\n' if s else '\n' for s in code.splitlines())))
    else:walk(v)
  elif isinstance(n,yaml.SequenceNode):
   for v in n.value:walk(v)
 walk(yaml.compose(a));b=a
 for x,y,c in sorted(ed,reverse=True):b=b[:x]+c+b[y:]
 save(p,b)
patch_yaml(R/'dev_docs/agents/transcripts/interaction_nets_lambda.yaml',lambda s:reduction(s) if 'val panelWidth =' in s else s)
p=R/'dev_docs/agents/demos/interaction_nets_lambda/lambda-reduction_code_.kt';save(p,reduction(p.read_text()))
def trace(s):
 if 'prop<PathTracingRenderer>' not in s:return s
 s=s.replace('prop<PathTracingRenderer>(tracer)','''prop<PathTracingRenderer>(tracer)

// Start here when changing render cost, lighting or convergence.
val samplesPerFrame = 2
val maxBounces = 8
val exposure = 1.35f
val environmentRotation = 2.1f
val indirectClamp = 10f // Optional biased suppression of indirect fireflies.
val targetSamples = 900''')
 for key,value in [('samplesPerFrame','2'),('maxBounces','8'),('exposure','1.35f'),('environmentRotation','2.1f')]:s=s.replace(f'tracer.{key} = {value}',f'tracer.{key} = {key}')
 return s.replace('tracer.indirectClamp = 10f // Optional biased suppression of indirect fireflies.','tracer.indirectClamp = indirectClamp').replace('tracer.samples < 900','tracer.samples < targetSamples')
patch_yaml(R/'dev_docs/agents/transcripts/polytope_path_trace_lineage.yaml',trace)
p=R/'public/docs/assets/opendlssnr/example.kt';save(p,p.read_text().replace('val owner = fromContext<Renderer>()\n','').replace('availability(owner)','availability(renderer)').replace('OpenDlssNrImage(owner,','OpenDlssNrImage(renderer,'))
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
