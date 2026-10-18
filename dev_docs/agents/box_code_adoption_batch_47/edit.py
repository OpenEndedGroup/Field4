"""Clear empty overlays and use mesh-builder attachment in the published CV lesson."""
from pathlib import Path
import json,re,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def record(path,fn):
 p=R/path;a=p.read_text();b=fn(a);assert a!=b;rows.append({'path':path,'before':a,'after':b});p.write_text(b)
def clear(code):
 assert code.count('if (segs.isEmpty()) continue')==1
 return code.replace('if (segs.isEmpty()) continue','''if (segs.isEmpty()) {
            // Publish an empty draw when detections disappear; retain no old overlay.
            m.resize(0, 0)
            continue
        }''')
def page(s):
 s=clear(s)
 a=s.index('var module = moduleHere { }',s.index('name="python-cv-native-shared-memory"'));b=s.index('\n```',a)
 old=s[a:b];body=old[old.index('// the image fills'):];new='renderer += moduleHere {\n    +Mesh("camera quad") {\n'+''.join('        '+line+'\n' if line else '\n' for line in body.splitlines())+'    }\n}'
 new=new.replace('        m.set(4, 6)', '        set(4, 6)');s=s[:a]+new+s[b:]
 old='var module = moduleHere { }\nvar m = Mesh()\nmodule += m named "overlay"\nrenderer += module';assert old in s;s=s.replace(old,'val module = moduleHere { +Mesh("overlay") }\nval m = module.current.meshes.getValue("overlay")\nrenderer += module')
 s=s.replace("about two pixels across a 1000-pixel window", "about four pixels across a 1000-pixel window")
 return s
record('public/docs/python_cv.md',page)
def transcript(s):
 edits=[]
 def visit(n):
  if isinstance(n,yaml.MappingNode):
   for k,v in n.value:
    if k.value=='code' and isinstance(v,yaml.ScalarNode) and 'if (segs.isEmpty()) continue' in v.value:
     code=clear(v.value);ind=k.start_mark.column+2;value='|\n'+''.join(' '*ind+x+'\n' if x else '\n' for x in code.rstrip().splitlines());edits.append((v.start_mark.index,v.end_mark.index,value))
    else:visit(v)
  elif isinstance(n,yaml.SequenceNode):
   for v in n.value:visit(v)
 visit(yaml.compose(s));assert len(edits)==1
 for a,b,v in reversed(edits):s=s[:a]+v+s[b:]
 return s
record('dev_docs/agents/transcripts/fieldcv_webcam.yaml',transcript)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
