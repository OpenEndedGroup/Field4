"""Compile and execute the authored disc shader on the real native GPU, then both force fields."""
import asyncio,importlib.util,json,re,yaml,sys
from pathlib import Path
from PIL import Image
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig37',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9703;m.OUT=R/'build/box-code-revision/adoption-37/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,box,code):return await m.macro(name,[{'execute':{'box':box,'property':'code','type':'fragment','code':code}}])
async def capture(name):
 p=m.OUT/(name+'.png')
 await m.batch(name,['wait-native-renderer gs_renderer --frames 35 --timeout 60',f'native-screenshot {p} --uid gs_renderer --wait-frames 4 --timeout 60'])
 return p
async def main():
 main=yaml.safe_load((R/'dev_docs/agents/transcripts/gpu_state.yaml').read_text());flat=yaml.safe_load((R/'dev_docs/agents/transcripts/docs_capture/gpu_state_flat_fbm.yaml').read_text())
 def props(d,box):return next(o['create']['properties'] for o in d['operations'] if o.get('create',{}).get('box')==box)
 shader=props(main,'gs_draw')['fragment'];assert shader==props(flat,'gs_draw')['fragment']
 graph=[o for o in main['operations'] if 'create' in o or 'connect' in o]
 vertex='''#version 450
layout(location=0) out vec2 tc;
layout(location=1) out float warmth;
void main() {
    vec2 corner=vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    tc=corner;
    warmth=0.0;
    gl_Position=vec4(corner*2.0-1.0,0.0,1.0);
}
'''
 fixture='''val quadModule=moduleHere { primitiveType=triangles }
quadModule += "disc" to fullscreenTriangle(quadModule)
renderer += "disc-alpha-test" to quadModule
'''
 if "--finish-only" not in sys.argv:
  await m.macro('gpu-graph',graph+[{'create':{'box':'alpha_fixture','properties':{'code':fixture,'vertex':vertex,'fragment':shader}}},{'connect':{'parent':'gs_renderer','child':'alpha_fixture'}},{'launch':{'box':'gs_renderer'}},{'await':{}},{'launch':{'box':'alpha_fixture'}},{'await':{}}],True)
  p=await capture('disc-alpha');im=Image.open(p).convert('RGB');w,h=im.size
  samples=[im.getpixel((round((0.5+r)*w),h//2)) for r in [0.0,0.1,0.25,0.4,0.49]]
  assert samples[0][2]>samples[2][2]>samples[3][2]>samples[4][2],samples
  assert max(samples[-1])<5,samples
  (m.OUT/'alpha-samples.json').write_text(json.dumps({'size':[w,h],'radii':[0.0,0.1,0.25,0.4,0.49],'rgb':samples,'identicalAuthoredFragment':True},indent=2)+'\n')
  await fragment('fixture-release','alpha_fixture','renderer.renderList.modules.entries.removeIf { it.value === quadModule };quadModule.current.cleanup();println("disc fixture released")')
  await m.macro('main-simulation',[{'launch':{'box':'gs_sim'}},{'await':{}},{'launch':{'box':'gs_draw'}},{'await':{}}])
  await capture('fbm3-particles')
  await fragment('particle-count','gs_draw','check(quads.numVert==240000);println("forty thousand shader-generated quads")')
 code=props(flat,'gs_sim')['code'];force=next(line.strip() for line in code.splitlines() if line.startswith('simModule.force'))
 await fragment('flat-force','gs_sim',force)
 await capture('flat-fbm-particles')
 for name in ['fbm3-particles','flat-fbm-particles']:
  image=Image.open(m.OUT/(name+'.png')).convert('RGB')
  visible=sum(1 for r,g,b in image.getdata() if max(r,g,b)>20)
  assert visible>1000,(name,visible)
  (m.OUT/(name+'-pixels.json')).write_text(json.dumps({'visiblePixels':visible,'size':image.size},indent=2)+'\n')
 await m.batch('native-stop',['stop gs_draw','stop gs_sim','stop gs_renderer'])
asyncio.run(main())
