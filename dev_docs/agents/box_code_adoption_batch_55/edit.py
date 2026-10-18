"""Keep native solids within scope until their independent meshes have been copied."""
from pathlib import Path
import json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def update(name,transform):
 p=R/f'dev_docs/agents/transcripts/{name}.yaml';before=p.read_text();edits=[]
 def visit(node):
  if isinstance(node,yaml.MappingNode):
   for k,v in node.value:
    if k.value=='code' and isinstance(v,yaml.ScalarNode) and 'Solid.' in v.value:
     code=transform(v.value);assert code!=v.value
     ind=k.start_mark.column+2;replacement='|\n'+''.join(' '*ind+l+'\n' if l else '\n' for l in code.rstrip().splitlines());edits.append((v.start_mark.index,v.end_mark.index,replacement))
    else:visit(v)
  elif isinstance(node,yaml.SequenceNode):
   for v in node.value:visit(v)
 visit(yaml.compose(before));assert len(edits)==1
 text=before
 for a,b,s in reversed(edits):text=text[:a]+s+text[b:]
 p.write_text(text);rows.append({'path':str(p.relative_to(R)),'before':before,'after':text})
def physics(s):
 s=s.replace('val (net, netGenus) = Solid.scope {','// Copy both meshes before the scope releases the union and all intermediate rods.\nval (shaded, plain, netGenus) = Solid.scope {')
 a=s.index('    // A Pair\'s Solid');b=s.index('println("genus $netGenus',a)
 s=s[:a]+'''    Triple(
        welded.toTriMesh(normals = true, minSharpAngle = 30.0),
        welded.toTriMesh(),
        welded.genus,
    )
}
'''+s[b:];return s.replace('net.close()\n','')
def polytope(s):
 s=s.replace('val cut = Solid.scope {','// The native shell stays scoped; only its copied triangle mesh escapes.\nval shaded = Solid.scope {')
 a=s.index('    wall.trimByPlane(');b=s.index('// ---- stage three:',a)
 block=s[a:b];block=block.replace('    wall.trimByPlane','    val cut = wall.trimByPlane',1).replace('\n}\n// A 20','\n// A 20',1)
 block=block.replace('val shaded = cut.toTriMesh','val shaded = cut.toTriMesh',1)
 block+='    shaded\n}\n\n';s=s[:a]+block+s[b:];return s.replace('cut.close()\n','')
def life(s):
 a=s.index('val whole = Solid.levelSet');b=s.index('// The frame comes off',a);block=s[a:b]
 floor='val floor = scene.material(PathTraceMaterial.plastic(0.30f, 0.31f, 0.33f, 0.5f))\n';assert floor in block;block=block.replace(floor,'').replace('parts.forEach { it.close() }\nwhole.close()','Triple(scene, sceneLo, sceneHi)')
 return s[:a]+'// Census and mesh import share a native scope, including the decomposed parts.\nval (scene, sceneLo, sceneHi) = Solid.scope {\n'+block.rstrip()+'\n}\n'+floor+'\n'+s[b:]
update('physics_manifold_path_trace',physics);update('polytope_manifold_path_trace',polytope);update('life3d_manifold_path_trace',life)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
