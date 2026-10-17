"""Apply reviewed drawing helper substitutions on explicit teaching sources."""
from pathlib import Path
import re,json
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;changes=[]
paths=['dev_docs/agents/transcripts/sorting_networks.yaml','public/docs/sorting_networks_examples.md','dev_docs/agents/transcripts/drawn_morph.yaml','public/docs/chain_drawn_morph.md','public/docs/colors_examples.md','public/docs/visualizers.md','dev_docs/agents/transcripts/autodiff_worked_example.yaml','dev_docs/agents/transcripts/fline_measure.yaml','public/docs/boolean_networks_examples.md']
def replace(s,old,new):
 assert old in s,old;return s.replace(old,new)
for path in paths:
 p=ROOT/path;before=p.read_text();s=before
 if path.endswith(('sorting_networks.yaml','sorting_networks_examples.md','boolean_networks_examples.md')):
  pattern=r'(?m)^([ \t]*)moveTo\(x, y\)\s*\n[ \t]*lineTo\(x \+ w, y\)\s*\n[ \t]*lineTo\(x \+ w, y \+ h\)\s*\n[ \t]*lineTo\(x, y \+ h\)\s*\n[ \t]*close\(\)'
  s,n=re.subn(pattern,lambda m:m[1]+'rect(x, y, w, h)',s);assert n==(3 if 'boolean_' in path else 1),(path,n)
 if path.endswith(('drawn_morph.yaml','chain_drawn_morph.md')):
  s=replace(s,'f.moveTo(x - 2, y - 2).lineTo(x + 2, y - 2).lineTo(x + 2, y + 2).lineTo(x - 2, y + 2).lineTo(x - 2, y - 2)','f.rect(x - 2, y - 2, 4, 4)')
 if path.endswith('colors_examples.md'):
  s=replace(s,'    val x1 = x0 + 78.0\n','')
  old='''        moveTo(x0, y0)
        lineTo(x1, y0)
        lineTo(x1, y0 + h)
        lineTo(x0, y0 + h)
        close()''';s=replace(s,old,'        rect(x0, y0, 78.0, h)')
  s=replace(s,'    for (i in 0..n) {','    breakNext()\n    for (i in 0..n) {')
  s=replace(s,'if (i == 0) moveTo(x, y) else lineTo(x, y)','lineTo(x, y)')
 if path.endswith('visualizers.md'):
  s=replace(s,'for (i in 0..steps) {','spiral.breakNext()\nfor (i in 0..steps) {')
  s=replace(s,'if (i == 0) spiral.moveTo(x, y) else spiral.lineTo(x, y)','spiral.lineTo(x, y)')
 if path.endswith('autodiff_worked_example.yaml'):
  a=s.index('              for (i in 0..40) {');b=s.index('              color =',a)
  s=s[:a]+'              circle(p.x, p.y, r, segments = 40)\n'+s[b:]
  s=replace(s,'              moveTo(0.0, 0.0); lineTo(boxWidth, 0.0); lineTo(boxWidth, boxHeight); lineTo(0.0, boxHeight)\n              close()','              rect(0.0, 0.0, boxWidth, boxHeight)')
 if path.endswith('fline_measure.yaml'):
  old='''          val drawn = fline {
              helix.sampleByNumber(400).forEachIndexed { i, p -> if (i == 0) moveTo(view(p)) else lineTo(view(p)) }'''
  new='''          val projected = helix.sampleByNumber(400).map(::view)
          val drawn = fline {
              data("m l+", projected)''';s=replace(s,old,new)
 if path.endswith('boolean_networks_examples.md'):
  s=replace(s,'fun curve(values: List<Double>, color: Vec4, thickness: Double): FLine = fline {\n','fun curve(values: List<Double>, color: Vec4, thickness: Double): FLine = fline {\n    breakNext()\n')
  s=replace(s,'if (i == 0) moveTo(x, y) else lineTo(x, y)','lineTo(x, y)')
  old='''        moveTo(x - size, y - size)
        lineTo(x + size, y - size)
        lineTo(x + size, y + size)
        lineTo(x - size, y + size)
        close()''';s=replace(s,old,'        rect(x - size, y - size, 2 * size, 2 * size)')
 assert s!=before,path
 changes.append({'path':path,'before':before,'after':s})
for r in changes:(ROOT/r['path']).write_text(r['after'])
(HERE/'changes.json').write_text(json.dumps(changes,indent=2)+'\n');print(len(changes),'explicit paths revised')
