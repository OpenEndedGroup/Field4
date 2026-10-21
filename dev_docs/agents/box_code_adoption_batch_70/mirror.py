from pathlib import Path
import json,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;sys.path.insert(0,str(R))
from tools.check_box_idioms import MIRROR_FENCE,refresh_mirror_digests
rows=json.loads((H/'changes.json').read_text());row=next(x for x in rows if x['path'].endswith('lambda-reduction_code_.kt'));p=(R/row['path']).parent/'document.md';before=p.read_text();ed=[]
for m in MIRROR_FENCE.finditer(before):
 if m['path']!='lambda-reduction_code_.kt':continue
 assert m['body'].rstrip('\n')==row['before'].rstrip('\n'),'pre-existing reduction mirror drift'
 ed.append((m.start('body'),m.end('body'),(R/row['path']).read_text().rstrip()+'\n'))
assert len(ed)==1
text=before
for a,b,c in reversed(ed):text=text[:a]+c+text[b:]
p.write_text(text);refresh_mirror_digests(p)
rows.append({'path':str(p.relative_to(R)),'before':before,'after':p.read_text()});(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
