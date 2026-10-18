"""Replace historical teaching headers and explain existing shader contracts."""
from pathlib import Path
import json,re,yaml,subprocess
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
STR=re.compile(r':code\s+("(?:\\.|[^"\\])*")')
def record(path,fn):
 p=R/path;a=subprocess.check_output(["git","show","df883e149:"+path],cwd=R,text=True);b=fn(a);assert a!=b,path;p.write_text(b);rows.append({'path':path,'before':a,'after':b})
def inline(text,fn):
 edits=[]
 for i,m in enumerate(STR.finditer(text)):
  a=json.loads(m[1]);b=fn(a,i)
  if a!=b:edits.append((m.start(1),m.end(1),json.dumps(b,ensure_ascii=False)))
 for a,b,c in reversed(edits):text=text[:a]+c+text[b:]
 return text
petri='dev_docs/agents/artifacts/petri_state_graph_bound/state_graph_bound_demo/metadata.edn'
def reduction(c,i):
 if i!=3:return c
 a=c.index('// 4 --');b=c.index('// A transition consumes',a)
 c=c[:a]+'''// STUBBORN reduction preserves deadlock answers, not the full reachability graph.
// Its transition set must be closed under mutual disabling, including read and inhibitor arcs.
// Compare full and reduced searches on the two minimal nets below.

'''+c[b:]
 c=c.replace('// there and does not consume it, so it was not among the consumers of `r`.','// there without consuming it: draining r can still disable peek.')
 c=c.replace("// filling `x`, and `mayDisable` reads a transition's inputs, so it could not see that.","// filling `x`: dependency closure must include produced tokens as well as consumed ones.")
 return c
record(petri,lambda s:inline(s,reduction))
def tess(c,i):
 a=c.index('// One FLine.');b=c.index('val cornerOfACube',a)
 c=c[:a]+'''// One FLine contains three perpendicular faces, each with its own square hole.
// Coplanarity.DEFAULT groups subpaths per plane, so each hole belongs to its face.
// Coplanarity.SINGLE_PLANE projects all rings onto one plane; the two edge-on faces cannot fill.
// Compare the measured polygon/triangle counts below, then orbit the threeLines view.

'''+c[b:]
 c=c.replace('// For comparison, force all subpaths onto ONE plane, as the old single-axis code did.','// For comparison, project all subpaths onto one plane with SINGLE_PLANE.')
 return c
record('dev_docs/agents/artifacts/tessellate_facing/multi_plane_fill_demo/metadata.edn',lambda s:inline(s,tess))
def scaffold(c,i):
 if i!=0:return c
 a=c.index('prop<Window>');return '''// This Kotlin scaffold runs the suspend Renderer.run loop on the JVM.
// Its FieldML child builds the scene through moduleHere and the shared renderer property.
'''+c[a:]
record('public/docs/assets/fieldml_macro_module_demo/metadata.edn',lambda s:inline(s,scaffold))
for name in ['c82c404d-a15d-4f51-a06f-66dfc2a00364_vertex_.glsl','gpu_skin_vertex_.glsl']:
 def skin(c):
  assert c.count('viewPos')==3
  c=re.sub(r'\bviewPos\b','clipPosition',c)
  return c.replace('    vViewPos = clipPosition.xyz;','    // Compatibility varying: clip-space xyz, currently unused by the paired fragment.\n    vViewPos = clipPosition.xyz;')
 record('dev_docs/agents/demos/skinned_characters/'+name,skin)
def yaml_props(s,fn):
 edits=[]
 def visit(n):
  if isinstance(n,yaml.MappingNode):
   for k,v in n.value:
    if k.value in ['vertex','fragment','compute'] and isinstance(v,yaml.ScalarNode):
     c=fn(k.value,v.value)
     if c!=v.value:
      ind=k.start_mark.column+2;value='|\n'+''.join(' '*ind+l+'\n' if l else '\n' for l in c.rstrip().splitlines());edits.append((v.start_mark.index,v.end_mark.index,value))
    else:visit(v)
  elif isinstance(n,yaml.SequenceNode):
   for v in n.value:visit(v)
 visit(yaml.compose(s))
 for a,b,c in sorted(edits,reverse=True):s=s[:a]+c+s[b:]
 return s

def cluster(k,c):
 if k!='compute':return c
 c=c.replace('struct Cluster {uvec4 range;uvec4 identity;};','// range: vertex offset, index offset, vertex count, triangle count.\n// identity: cluster ID, geometry ID, then two reserved zero components.\nstruct Cluster {uvec4 range;uvec4 identity;};')
 c=c.replace('    pc.descriptions.data[id]=Cluster','    // A 9x9 vertex grid contains 8x8 quads: 81 vertices, 128 triangles, 384 indices.\n    pc.descriptions.data[id]=Cluster');return c
record('dev_docs/agents/transcripts/computed_ray_geometry.yaml',lambda s:yaml_props(s,cluster))
def study(k,c):
 if k=='vertex':return c.replace('uv=vec2(corner.x,1-corner.y);','            // Image UVs run down from the top; clip-space Y runs up.\n            uv=vec2(corner.x,1-corner.y);')
 if k=='fragment':
  c=c.replace('layout(push_constant) uniform Study { vec4 frame; } pc;','        // Host frame: x = time in seconds, yz = source size in pixels, w = unused.\n        layout(push_constant) uniform Study { vec4 frame; } pc;')
  c=c.replace('color=vec4(linearize(mix(paper,pigment,ink)),1);','            // Decode display-space pigment/paper colours to the linear LDR input expected by NR.\n            color=vec4(linearize(mix(paper,pigment,ink)),1);')
 return c
record('public/docs/assets/opendlssnr/open_dlss_nr.yaml',lambda s:yaml_props(s,study))
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n');print(len(rows),'sources')
