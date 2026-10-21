"""Retain readable projection continuation indentation after the editor's GLSL formatter."""
from pathlib import Path
import re,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
rows=json.loads((H/'changes.json').read_text())
def tidy(s):
 s=s.replace('// World position to Vulkan clip space: positive view depth, near Z = 0 and far Z = W.\n','')
 s=re.sub(r'(uniform Scene \{)\n\s*\n',r'\1\n',s)
 s=re.sub(r'(vec4 (?:sizeJitter|times);)\n\s*\n',r'\1\n',s)
 a=s.find('    return vec4(\n')
 if a>=0:
  b=s.index('\n    );',a);lines=s[a:b].splitlines();s=s[:a]+'\n'.join([lines[0]]+['        '+x.strip() for x in lines[1:]])+s[b:]
 return s
for row in rows:
 p=R/row['path'];a=p.read_text();b=a
 if p.suffix in ['.vert','.frag']:b=tidy(a)
 elif p.suffix=='.yaml':
  edits=[]
  def visit(n):
   if isinstance(n,yaml.MappingNode):
    for k,v in n.value:
     if k.value in ['vertex','fragment'] and isinstance(v,yaml.ScalarNode) and 'cameraEye' in v.value:
      code=tidy(v.value);indent=k.start_mark.column+2;edits.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*indent+x+'\n' if x else '\n' for x in code.splitlines())))
     else:visit(v)
   elif isinstance(n,yaml.SequenceNode):
    for v in n.value:visit(v)
  visit(yaml.compose(a))
  for x,y,v in sorted(edits,reverse=True):b=b[:x]+v+b[y:]
 else:
  blocks=list(re.finditer(r'(```diffstatic \{data-lang=glsl\}\n)([\s\S]*?)(\n```)',a))
  for m in reversed(blocks):
   if 'cameraEye' in m[2]:b=b[:m.start(2)]+tidy(m[2])+b[m.end(2):]
 p.write_text(b);row['after']=b
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
