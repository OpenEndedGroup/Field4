"""Keep beginner sources, fixtures and editable controls in sync."""
from pathlib import Path
import json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def record(path,after):
 p=R/path;before=p.read_text();assert before!=after;rows.append(dict(path=path,before=before,after=after));p.write_text(after)
triangle='''// A module connects this box's vertex and fragment shaders to its meshes.
// Re-launching replaces the module under this box's name.
renderer += moduleHere {
    primitiveType = triangle_strip
    +Mesh("triangle") {
        // Three clip-space positions (-1..1), three RGBA colours and one triangle.
        set(3, 3) {
            pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)
            3 {
                col.add(0, it / 3.0, 1 - it / 3.0, 1)
            }
            tri.add(0, 1, 2)
        }
    }
}

/*
Switch to the shaders with F1 or Ctrl+P:
[[property vertex]]
[[property fragment]]
*/
'''
p='templates/first-graphics/first_graphics_triangle_code_.kt';record(p,triangle)
p='templates/first-steps/first_steps_drawing_code_.kt';drawing=(R/p).read_text().replace('var phase = 0.0','// Phase advances once per Runloop iteration; this is a frame-based speed.\nvar phaseStep = 0.045\nphaseStep = 0.045 // Change this value and Alt+Enter this line to change the running speed.\nvar phase = 0.0').replace('phase += 0.045','phase += phaseStep');record(p,drawing)
def replace_scalar(text,box,code):
 def visit(n):
  if isinstance(n,yaml.MappingNode):
   d={k.value:v for k,v in n.value}
   if d.get('box') and d['box'].value==box and 'properties'in d:return next(v for k,v in d['properties'].value if k.value=='code')
   for _,v in n.value:
    found=visit(v)
    if found:return found
  elif isinstance(n,yaml.SequenceNode):
   for v in n.value:
    found=visit(v)
    if found:return found
 n=visit(yaml.compose(text));assert n
 # Existing first-graphics fixtures use escaped quoted strings. Keep that input form here
 # so the actual formatter must exercise the newly repaired quoted-scalar path.
 return text[:n.start_mark.index]+json.dumps(code,ensure_ascii=False)+text[n.end_mark.index:]
for name,box,code in [('first_graphics','first_graphics_triangle',triangle),('first_graphics_native','first_graphics_triangle',triangle),('first_steps','first_steps_drawing',drawing)]:
 p=f'dev_docs/agents/transcripts/docs_capture/{name}.yaml';record(p,replace_scalar((R/p).read_text(),box,code))
p='dev_docs/agents/transcripts/docs_capture/bookmarks_panel.yaml';s=(R/p).read_text();assert s.count('background:linear-gradient(135deg,#1e3a5f,#7a3e1d)')==1;record(p,s.replace('background:linear-gradient(135deg,#1e3a5f,#7a3e1d)','background:#161616'))
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
