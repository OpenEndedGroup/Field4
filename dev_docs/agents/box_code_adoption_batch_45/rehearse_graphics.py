"""Render the real first-graphics template before/after, rerun it and edit a vertex."""
from pathlib import Path
import asyncio,importlib.util,json,subprocess,yaml,sys,os
from PIL import Image,ImageChops
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-45/graphics';OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('rig45',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9716;m.OUT=OUT
baseMacro=m.macro
async def trialMacro(name,*args,**kwargs):return await baseMacro(name+"-"+os.environ.get("FIELD_TRIAL","initial"),*args,**kwargs)
m.macro=trialMacro
async def fragment(name,code):return await m.macro(name,[{'execute':{'box':'first_graphics_triangle','property':'code','type':'fragment','code':code}}])
async def capture(name):
 p=OUT/(name+'.png')
 await m.batch(name,['wait-native-renderer first_graphics_renderer --frames 15 --timeout 60',f'native-screenshot {p} --uid first_graphics_renderer --wait-frames 4 --timeout 60'])
 return Image.open(p).convert('RGB')
async def main():
 data=yaml.safe_load((R/'dev_docs/agents/transcripts/docs_capture/first_graphics_native.yaml').read_text())
 operations=[o for o in data['operations'] if 'create'in o or 'connect'in o]
 old=subprocess.check_output(['git','show','8074d4abd:templates/first-graphics/first_graphics_triangle_code_.kt'],cwd=R,text=True)
 box=next(o['create'] for o in operations if 'create'in o and o['create']['box']=='first_graphics_triangle');box['properties']['code']=old
 if '--resume-adoption' in sys.argv:
  before=Image.open(OUT/'before.png').convert('RGB')
  previous=yaml.safe_load((OUT/'adopt.yaml').read_text())['operations'][0]['edit']['to']
  await fragment('remove-test-only-new-slot','renderer.renderList.modules.remove("first-graphics"); println("removed test-only new slot")')
  old=previous
 else:
  await m.macro('baseline-create',operations,True)
  await m.macro('baseline-launch',[{'launch':{'box':'first_graphics_renderer'}},{'await':{}},{'launch':{'box':'first_graphics_triangle'}},{'await':{}}])
  before=await capture('before')
 new=(R/'templates/first-graphics/first_graphics_triangle_code_.kt').read_text()
 await m.macro('adopt',[{'edit':{'box':'first_graphics_triangle','property':'code','from':old,'to':new}},{'launch':{'box':'first_graphics_triangle'}},{'await':{}}])
 after=await capture('after')
 assert before.size==after.size
 assert ImageChops.difference(before,after).getbbox() is None,'The unchanged triangle changed pixels'
 proof='''check(renderer.renderList.modules.size == 1)
val drawn = renderer.renderList.modules.values.single().current
check(drawn.meshes.keys == setOf("triangle"))
check(drawn.meshes.values.single().numVert == 3)
check(drawn.meshes.values.single().numTriangle == 3)
println("one module, one mesh, three vertices and indices")'''
 await fragment('one-drawing',proof)
 for i in range(2):
  await m.batch('rerun-'+str(i),['launch first_graphics_triangle'])
  await fragment('rerun-proof-'+str(i),proof+'\nprintln("rerun '+str(i)+'")')
 rerun=await capture('rerun');assert ImageChops.difference(after,rerun).getbbox() is None
 edited=new.replace('0.5, 0.5, 0)','0.8, 0.7, 0)');assert edited!=new
 await m.macro('edit-corner',[{'edit':{'box':'first_graphics_triangle','property':'code','from':new,'to':edited}},{'launch':{'box':'first_graphics_triangle'}},{'await':{}}])
 changed=await capture('edited-corner');assert ImageChops.difference(after,changed).getbbox() is not None
 await fragment('edited-proof',proof+'\nprintln("edited corner is rendered")')
 (OUT/'comparison.json').write_text(json.dumps({'samePixelsBeforeAfter':True,'samePixelsAfterTwoReruns':True,'singleModuleAndMesh':True,'vertexEditChangesActualNativePixels':True,'size':before.size},indent=2)+'\n')
 await m.batch('stop-owned-renderer',['stop first_graphics_triangle','stop first_graphics_renderer'])
asyncio.run(main())
