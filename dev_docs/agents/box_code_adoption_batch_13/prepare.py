"""Prepare shader contracts and readable YAML scalars without touching tracked sources."""
from pathlib import Path
import yaml,json,re,hashlib,subprocess
OUT=Path('build/box-code-revision/adoption-13');rows=[]
rrBefore=subprocess.check_output(['git','show','94245dbf4:dev_docs/agents/demos/rr_plumbing/fragment.glsl'],text=True)
rrAfter=Path('dev_docs/agents/demos/rr_plumbing/fragment.glsl').read_text()
def lex(code):return re.findall(r'\w+|\S',re.sub(r'//[^\n]*|/\*[\s\S]*?\*/','',code))
SCENE='layout(push_constant) uniform Scene { vec4 sizeJitter; vec4 times; vec4 settings; } pc;'
LEGEND='''layout(push_constant) uniform Scene {
    // xy = input width/height in pixels; zw = raster jitter in pixels, with Y down.
    vec4 sizeJitter;
    // xy = current/previous scene times; zw = current/previous horizontal shifts in input pixels.
    vec4 times;
    // x = particle/appearance stress test; y = orbiting camera; zw unused.
    vec4 settings;
} pc;'''
def shader(code):
 if lex(code)==lex(rrBefore):return rrAfter
 if SCENE in code:
  code=code.replace(SCENE,LEGEND)
  code=code.replace('vec3 objectPoint(int id,vec3 p,float time){','// Object IDs: floor 0, boxes 1/2, thin bars 3..38, particles 39 onward.\nvec3 objectPoint(int id,vec3 p,float time){')
  code=code.replace('vec4 project(vec3 p,float t){','// World position to Vulkan clip space: positive view depth, near Z = 0 and far Z = W.\nvec4 project(vec3 p,float t){')
  code=code.replace('    nowClip=project(world,t);','    // Keep correspondence in unjittered clip space for both scene times.\n    nowClip=project(world,t);')
  code=code.replace('    gl_Position=nowClip;','    // Jitter moves only the raster position; it must not become motion.\n    gl_Position=nowClip;')
  code=code.replace('    vec2 uv=nowClip.xy','    // Flip clip Y into top-left UVs. Both positions exclude raster jitter.\n    vec2 uv=nowClip.xy')
  code=code.replace('    motion=(prev-uv)*pc.sizeJitter.xy;','    // Current-to-previous displacement, scaled from UVs to input pixels.\n    motion=(prev-uv)*pc.sizeJitter.xy;')
  code=code.replace('    depth=gl_FragCoord.z;','    // Standard Vulkan depth in [0, 1]; background is written as 1 above.\n    depth=gl_FragCoord.z;')
  code=code.replace('    if(objectId<3){vec2 st=world.xz*9;','    // Checker coordinates are world-space XZ; lighting is evaluated separately below.\n    if(objectId<3){vec2 st=world.xz*9;')
 return code
raw=['dev_docs/agents/demos/reconstruction_plumbing/vertex.glsl','dev_docs/agents/demos/reconstruction_plumbing/fragment.glsl','public/docs/assets/opendlssnr/cubes.vert','public/docs/assets/opendlssnr/cubes.frag','public/docs/assets/opendlssnr/compare.frag','public/docs/assets/opendlssnr/study.vert']
for path in raw:
 before=Path(path).read_text();after=shader(before)
 if path.endswith('compare.frag'):
  after=after.replace('layout(push_constant) uniform Compare { vec4 grade; vec4 dimensions; } pc;','''layout(push_constant) uniform Compare {
    vec4 grade;
    // xy = output width/height; z = heading height in pixels; w = neural pass available.
    vec4 dimensions;
} pc;''')
 if path.endswith('study.vert'):
  after=after.replace('    vec2 corner=','    // The 0/2 corners make an oversized fullscreen triangle from three vertices.\n    vec2 corner=').replace('    uv=','    // Flip Y when converting raster corners to source texture coordinates.\n    uv=')
 assert after!=before,path
 rows.append({'path':path,'beforeSha256':hashlib.sha256(before.encode()).hexdigest(),'after':after})
keys={'code','vertex','fragment','compute','task','mesh'}
def visit(node,edits):
 if isinstance(node,yaml.MappingNode):
  for key,value in node.value:
   if key.value in keys and isinstance(value,yaml.ScalarNode) and value.tag=='tag:yaml.org,2002:str':
    code=shader(value.value) if key.value!='code' else value.value
    indent=key.start_mark.column+2
    replacement=('|\n' if code.endswith('\n') else '|-\n')+''.join(' '*indent+line+'\n' if line else '\n' for line in code.rstrip().splitlines())
    # Keep the original separator newline outside the scalar.
    edits.append((value.start_mark.index,value.end_mark.index,replacement.rstrip('\n')+('\n' if value.style in ['|','>'] else '')))
   else:visit(value,edits)
 elif isinstance(node,yaml.SequenceNode):
  for child in node.value:visit(child,edits)
for path in ['dev_docs/agents/transcripts/rr_plumbing.yaml','dev_docs/agents/transcripts/reconstruction_plumbing.yaml','dev_docs/agents/transcripts/neural_reconstruction.yaml','public/docs/assets/opendlssnr/open_dlss_nr_cubes.yaml']:
 before=Path(path).read_text();edits=[];visit(yaml.compose(before),edits);after=before
 for a,b,value in sorted(edits,reverse=True):after=after[:a]+value+after[b:]
 # Only known shader bodies change; quoted Kotlin is just decoded to a literal scalar.
 a=yaml.safe_load(before);b=yaml.safe_load(after)
 def normalized(v):
  if isinstance(v,dict):return {k:(shader(x) if k in keys-{'code'} and isinstance(x,str) else normalized(x)) for k,x in v.items()}
  if isinstance(v,list):return [normalized(x) for x in v]
  return v
 assert normalized(a)==b,path
 rows.append({'path':path,'beforeSha256':hashlib.sha256(before.encode()).hexdigest(),'after':after})
(OUT/'drafts.json').write_text(json.dumps(rows,indent=2)+'\n');print(len(rows),'shader source/container drafts prepared')
