"""Make the projected camera explicit and carry it into world-space lighting."""
from pathlib import Path
import re,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
project='''// Orbit in world space; share the current eye with fragment lighting.
vec3 cameraPosition(float time) {
    float angle = pc.settings.y == 1 ? time * .18 : 0;
    return vec3(5 * sin(angle) + 5 * cos(angle), 3.7, 5 * cos(angle) - 5 * sin(angle));
}

// Positive view depth maps the near plane to Z=0 and the far plane to Z=W.
vec4 project(vec3 worldPosition, float time) {
    vec3 eye = cameraPosition(time);
    vec3 forward = normalize(vec3(0, .8, 0) - eye);
    vec3 right = normalize(cross(forward, vec3(0, 1, 0)));
    vec3 up = cross(right, forward);
    vec3 viewOffset = worldPosition - eye;
    float depth = dot(viewOffset, forward);
    float near = .1;
    float far = 100;
    return vec4(
        dot(viewOffset, right) * 1.9 * pc.sizeJitter.y / pc.sizeJitter.x,
        dot(viewOffset, up) * 1.9,
        far / (far - near) * depth - far * near / (far - near),
        depth
    );
}'''
def shader(s,stage):
 if stage=='vertex':
  a=s.index('vec4 project(');b=s.index('\nvoid main()',a);s=s[:a]+project+s[b:]
  s=s.replace('layout(location=4) flat out int objectId;','layout(location=4) flat out int objectId;\nlayout(location=5) flat out vec3 cameraEye;')
  s=s.replace('void main() {','void main() {\n    cameraEye = cameraPosition(pc.times.x);\n    // First triangle fills the background; remaining triangles form the objects.',1)
  s=s.replace('    objectId=(gl_VertexIndex-3)/36;','    // Recover object/face IDs, then transform the triangle into world space.\n    objectId=(gl_VertexIndex-3)/36;')
  s=s.replace('    vec3 a=objectPoint(objectId,corner(indices[tri]),t),b=objectPoint(objectId,corner(indices[tri+1]),t),c=objectPoint(objectId,corner(indices[tri+2]),t);','    vec3 a = objectPoint(objectId, corner(indices[tri]), t);\n    vec3 b = objectPoint(objectId, corner(indices[tri + 1]), t);\n    vec3 c = objectPoint(objectId, corner(indices[tri + 2]), t);')
 else:
  s=s.replace('layout(location=4) flat in int objectId;','layout(location=4) flat in int objectId;\nlayout(location=5) flat in vec3 cameraEye;')
  s=s.replace('    float specular=pow(max(0,abs(dot(n,normalize(light+normalize(vec3(5,3.7,5)-world))))),96);','    // Lighting and the view vector use the same world-space eye as projection.\n    vec3 viewDirection = normalize(cameraEye - world);\n    vec3 halfDirection = normalize(light + viewDirection);\n    float specular = pow(max(0, abs(dot(n, halfDirection))), 96);')
 # Keep the public push ABI, with explicit producer units and flags.
 for member,comment in [('sizeJitter','xy = input pixels; zw = raster jitter in pixels (Y down).'),('times','xy = current/previous scene seconds; zw = horizontal shifts in pixels.'),('settings','x = appearance stress flag; y = camera orbit flag; zw unused.')]:
  s=re.sub(r'(?m)(\s*)vec4 '+member+r';',lambda m:'\n    // '+comment+'\n    vec4 '+member+';',s,count=1)
 return s
rows=[]
def record(p,b):
 a=p.read_text();assert a!=b,p;p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
for root in ['public/docs/assets/opendlssnr','src/jvmMain/resources/afield/four/neuralrendering']:
 for ext,stage in [('vert','vertex'),('frag','fragment')]:
  p=R/root/('cubes.'+ext);record(p,shader(p.read_text(),stage))
for rel in ['dev_docs/agents/transcripts/open_dlss_nr_cubes.yaml','public/docs/assets/opendlssnr/open_dlss_nr_cubes.yaml']:
 p=R/rel;a=p.read_text();edits=[]
 def visit(n):
  if isinstance(n,yaml.MappingNode):
   for k,v in n.value:
    if k.value in ['vertex','fragment'] and isinstance(v,yaml.ScalarNode) and 'objectId' in v.value:
     code=shader(v.value,k.value);indent=k.start_mark.column+2;edits.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*indent+x+'\n' if x else '\n' for x in code.splitlines())))
    else:visit(v)
  elif isinstance(n,yaml.SequenceNode):
   for v in n.value:visit(v)
 visit(yaml.compose(a));b=a
 for x,y,v in sorted(edits,reverse=True):b=b[:x]+v+b[y:]
 record(p,b)
p=R/'public/docs/open_dlss_nr.md';a=p.read_text();blocks=list(re.finditer(r'(```diffstatic \{data-lang=glsl\}\n)([\s\S]*?)(\n```)',a));b=a
for m in reversed(blocks):
 if 'objectId' in m[2]:b=b[:m.start(2)]+shader(m[2], 'vertex' if 'vec4 project(' in m[2] else 'fragment')+b[m.end(2):]
record(p,b)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
