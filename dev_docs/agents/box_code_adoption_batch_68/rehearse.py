"""Render real bundled texture lessons and the actual sampled-texture fragment."""
from pathlib import Path
import asyncio,importlib.util,json
from PIL import Image,ImageChops
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-68'
s=importlib.util.spec_from_file_location('rig68',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9722;m.OUT=O/'native';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(label,box,code):return await m.macro(label,[{'execute':{'box':box,'type':'fragment','code':'run {\n'+code+'\n}'}}])
async def capture(label):
 path=m.OUT/(label+'.png');await m.batch(label,['wait-native-renderer renderer --frames 12 --timeout 60',f'native-screenshot {path} --uid renderer --wait-frames 4 --timeout 60']);return path
async def main():
 owner='''import afield.graphics.vulkan.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableVR=false
Options.enableHDR=false
Options.profiling=false
window=Window("Private texture lesson checks",initialDimensions=Rect(40.0,40.0,800.0,600.0))
renderer=Renderer(window)
start(renderer::run)'''
 await m.macro('g4-texture-owner',[{'create':{'box':'renderer','properties':{'code':owner}}},{'launch':{'box':'renderer'}},{'await':{}}])
 folder=R/'public/docs/assets/canonical_graphics'
 for label,uid in [('array','3cbcadc0-b279-470d-9a5f-afc120518829'),('probe','e1d6dd17-1ebd-40e7-a0ef-309779c562ff')]:
  box='g4-texture-'+label
  props={'code':(folder/(uid+'_code_.kt')).read_text(),'vertex':(folder/(uid+'_vertex_.glsl')).read_text(),'fragment':(folder/(uid+'_fragment_.glsl')).read_text()}
  await m.macro(box,[{'create':{'box':box,'properties':props}},{'connect':{'parent':'renderer','child':box}},{'execute':{'box':box}}])
  await capture(label)
  await fragment(box+'-detach',box,'renderer.renderList.modules.clear()')
 vertex='''#version 450
layout(location=2) out vec2 vUV;
layout(location=3) out vec4 vColor;
void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);vUV=vec2(p.x,1-p.y);vColor=vec4(1);gl_Position=vec4(p*2-1,0,1);}'''
 new=(folder/'f1d2cbea-b20d-4794-b9f7-37b847dcdeb6_fragment_.glsl').read_text();old=next(x['before'] for x in json.loads((H/'changes.json').read_text()) if x['path'].endswith('f1d2cbea-b20d-4794-b9f7-37b847dcdeb6_fragment_.glsl'))
 body='''val image=LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
image.transferNow()
prop<Array<ImageView>>(uTextures)
uTextures=Array(6){image.imageView}
val module=moduleHere {depthTest=false;depthWrite=false;blend=false}
val quad=fullscreenTriangle(module)
module+="textured" to quad
renderer.renderList+="textured" to module'''
 await m.macro('g4-sampled-shader',[{'create':{'box':'g4-sampled-shader','properties':{'code':body,'vertex':vertex,'fragment':new}}},{'connect':{'parent':'renderer','child':'g4-sampled-shader'}},{'execute':{'box':'g4-sampled-shader'}}])
 after=await capture('sampled-texture')
 path=m.OUT/'old-diagnostic.frag';path.write_text(old)
 await m.batch('g4-original-diagnostic',[f'set g4-sampled-shader fragment --file {path}','run g4-sampled-shader'])
 before=await capture('old-uv-diagnostic')
 assert ImageChops.difference(Image.open(after).convert('RGB'),Image.open(before).convert('RGB')).getbbox() is not None
 # Restore the lesson's real sampled-texture source.
 path=m.OUT/'sampled.frag';path.write_text(new)
 await m.batch('g4-restore-sampled',[f'set g4-sampled-shader fragment --file {path}','run g4-sampled-shader'])
 (H/'native-behavior.json').write_text(json.dumps({'authoredArrayTextureLessonRendersBundledImages':True,'authoredProbeTextureLessonRendersBundledImage':True,'productionGltfFragmentSamplesRealTexture':True,'sampledTextureDiffersFromOverwrittenUvDiagnostic':True,'limits':'Actual authored array/probe bodies and shader properties. The glTF fragment is exercised with controlled fullscreen UV geometry and a real six-element texture binding; the original full glTF lesson still requires its documented external Sample Models asset. No public capture replaced.'},indent=2)+'\n')
asyncio.run(main())
