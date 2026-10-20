from pathlib import Path
import json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
p=R/'public/docs/wfc_examples.md';a=p.read_text();start=a.index('val solver = WfcSolver(44, 30, rules, seed = 3L)');end=a.index('\n```',start);old=a[start:end]
new=old.replace('println("wfc collapse animation ready")','''val displayFps = 20
val stepsPerDisplay = 12
println("wfc collapse animation ready")''')
new=new.replace('A few step()s per displayed frame, then push the wave to the box\'s output channel.','Take stepsPerDisplay solver steps per output update, at no more than displayFps updates per second.')
new=new.replace('One display() per frame,','One display() per update,')
new=new.replace('''loop {
    if (solver.status == WfcStatus.Running) solver.step() // one WFC iteration per displayed frame''','''frames {
    while (true) {
        repeat(stepsPerDisplay) {
            if (solver.status == WfcStatus.Running) solver.step()
        }''')
new=new.replace('''    frame()
}''','''        // Leave the final solved (or contradicted) wave visible without rebuilding it.
        if (solver.status != WfcStatus.Running) break
        delay(1000 / displayFps)
    }
}''')
b=a[:start]+new+a[end:];assert a!=b;p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
p=R/'dev_docs/agents/transcripts/typography_worked_example.yaml';a=p.read_text();edits=[]
def visit(n):
 if isinstance(n,yaml.MappingNode):
  for k,v in n.value:
   if k.value=='code' and isinstance(v,yaml.ScalarNode) and 'fun waveAt(' in v.value:
    code=v.value.replace('val started = AnimationClock.nowSeconds()','''val started = AnimationClock.nowSeconds()
val displayFps = 20.0
var nextDisplay = 0.0''').replace('val elapsed = AnimationClock.nowSeconds() - started','''val elapsed = AnimationClock.nowSeconds() - started
    // Keep motion tied to elapsed time; cap the expensive raster and output work.
    if (elapsed < nextDisplay) return@animate
    nextDisplay = elapsed + 1.0 / displayFps''')
    indent=k.start_mark.column+2;edits.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*indent+x+'\n' if x else '\n' for x in code.splitlines())))
   else:visit(v)
 elif isinstance(n,yaml.SequenceNode):
  for v in n.value:visit(v)
visit(yaml.compose(a));assert len(edits)==1;b=a
for x,y,c in sorted(edits,reverse=True):b=b[:x]+c+b[y:]
p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b});(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
