from pathlib import Path
import json,re,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def change(s):
 s=re.sub(r'val mesh = Mesh\(renderer, teacher\)\n\s*mesh.resize\(3, 0, teacher\)\n\s*mesh.push.setMembers\(teacher.current.fi.pushConstants.single\(\).members\)', 'val mesh = fullscreenTriangle(teacher)',s)
 return s.replace('mesh.push["state"] =','mesh.state =')
for rel in ['dev_docs/agents/demos/learned_from_module/example.kt','dev_docs/agents/demos/gpu_shader_learning/example.kt','dev_docs/agents/transcripts/learned_from_module.yaml','dev_docs/agents/transcripts/gpu_shader_learning.yaml']:
 p=R/rel;a=p.read_text();b=a
 if p.suffix=='.kt':b=change(a)
 else:
  edits=[]
  def visit(n):
   if isinstance(n,yaml.MappingNode):
    for k,v in n.value:
     if k.value=='code' and isinstance(v,yaml.ScalarNode) and 'mesh.push.setMembers(teacher.' in v.value:
      code=change(v.value);indent=k.start_mark.column+2;edits.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*indent+x+'\n' if x else '\n' for x in code.splitlines())))
     else:visit(v)
   elif isinstance(n,yaml.SequenceNode):
    for v in n.value:visit(v)
  visit(yaml.compose(a))
  for x,y,v in sorted(edits,reverse=True):b=b[:x]+v+b[y:]
 assert a!=b;p.write_text(b);rows.append({'path':rel,'before':a,'after':b})
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
