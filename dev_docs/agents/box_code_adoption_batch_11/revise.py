"""Repair published dispatch bounds and streamer setup on explicit source copies."""
from pathlib import Path
import re,json
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-11';OUT.mkdir(parents=True,exist_ok=True)
changes=[];pending={}
def edit(path,old,new,why):
 s=pending.setdefault(path,(ROOT/path).read_text());assert old in s,(path,old);pending[path]=s.replace(old,new);changes.append(dict(path=path,old=old,new=new,why=why))
doc='public/docs/afield_four_graphics.md';compute='public/docs/assets/canonical_graphics/ad857eaa-362e-45ea-bdc8-472af40f1ed2_code_.kt';shader='public/docs/assets/canonical_graphics/ad857eaa-362e-45ea-bdc8-472af40f1ed2_compute_.glsl';draw='public/docs/assets/canonical_graphics/262e80f5-56f9-4aaa-b015-84a79bf8884e_code_.kt'
# Locate the saved draw sidecar by its invariant prefix if a UUID suffix differs.
found=list((ROOT/'public/docs/assets/canonical_graphics').glob('262e80f5*_code_.kt'));assert len(found)==1;draw=str(found[0].relative_to(ROOT))
edit(doc,'m.x = numVec4 / localSize','// Include a partial final group; the shader guards both buffer lengths.\nm.x = (numVec4 + localSize - 1) / localSize','ceil descriptor workgroups')
edit(doc,'''    if (idx < 256)
        outData[idx] = inData[idx] * vec4(1,1,1,1) +
            vec4(3 + idx * 1, -(idx * 21.0), idx * 3, idx * 4);''','''    // Array lengths reflect the actual SSBO allocation, including edited element counts.
    if (idx >= uint(min(inData.length(), outData.length()))) return;
    outData[idx] = inData[idx] * vec4(1,1,1,1) +
        vec4(3 + idx * 1, -(idx * 21.0), idx * 3, idx * 4);''','bound the descriptor buffers')
for path in [doc,compute]:
 edit(path,'prop<FrameBufferGroup>(outputPositions) = outData','prop<FrameBufferGroup>(outputPositions) = outData\nprop<Int>(particleCount) = N\n\n// uint push members accept Int lambdas; the shader checks the tail group.\nm.count = { N }','publish count and bind uint push member')
 edit(path,'m.x = N / 64','m.x = (N + 63) / 64','ceil buffer-reference workgroups')
for path in [doc,shader]:
 edit(path,'    float something;\n} pc;','    float something;\n    uint count;\n} pc;','declare element bound')
 edit(path,'    uint idx = gl_GlobalInvocationID.x;\n    pc.outData.a[idx].x', '    uint idx = gl_GlobalInvocationID.x;\n    // Ceiling dispatch includes extra lanes; none may access the particle buffers.\n    if (idx >= pc.count) return;\n    pc.outData.a[idx].x','bound every reference access')
for path in [doc,draw]:
 edit(path,'m.inData = { outputPositions }\n\nval N = 100000','m.inData = { outputPositions }\n\n// Inherit the count from the same parent that owns the buffers.\nval N = particleCount','match draw count to the producer')
for path in [doc,'public/docs/assets/canonical_graphics/8323570b-87db-4623-95a2-bd61c7d0f6f7_code_.kt']:
 edit(path,'import afield.four.stream.FieldRTTStreamer','import afield.media.stream.FieldRTTStreamer','current streamer package')
 s=pending[path];s,n=re.subn(r'(?m)^(\s*)= FieldRTTStreamer\(',r'\1FieldRTTStreamer(',s);assert n==1,path;pending[path]=s
 changes.append(dict(path=path,why='return the streamer from once initializer',pattern='^\\s*= FieldRTTStreamer('))
 edit(path,'@exported\nval target = streamer.start().first()','prop<RenderToTexture>(target) = streamer.start().first()','publish typed target')
 if not path.endswith('.md'):edit(path,'    true\n}', '}','loop uses Unit')
for path,text in pending.items():(ROOT/path).write_text(text)
(HERE/'changes.json').write_text(json.dumps(changes,indent=2)+'\n');(OUT/'paths.json').write_text(json.dumps(list(pending),indent=2)+'\n');print(len(pending),'explicit sources revised')
