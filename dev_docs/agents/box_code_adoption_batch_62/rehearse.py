"""Refresh real generated RectSL material shaders, preserving fragment expansion order."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
from PIL import Image,ImageChops
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig62',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9719;m.OUT=R/'build/box-code-revision/adoption-62/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,box,code):return await m.macro(name,[{'execute':{'box':box,'type':'fragment','code':'run {\n'+code+'\n}'}}])
async def capture(name):
 p=m.OUT/(name+'.png');await m.batch(name,['wait-native-renderer renderer --frames 12 --timeout 60',f'native-screenshot {p} --uid renderer --wait-frames 4 --timeout 60']);return p
async def update(label,box,code):
 p=m.OUT/(label+'.kt');p.write_text(code);await m.batch(label,[f'set {box} code --file {p}',f'launch {box}'])
def frozen(code):
 return code.replace('var t = 0.0','var t = 1.5').replace('var playing = true','var playing = false').replace('t += 1.0 / 60.0','t += 0.0')
async def main():
 records=[]
 await m.batch('rectsl-stop-first-probe',['stop recstl_playground'])
 await fragment('rectsl-clear-private-graph-v2','aperture_composite','renderer.renderList.modules.clear();renderer.renderList.preTargets.clear()')
 for row in json.loads((H/'changes.json').read_text()):
  name=Path(row['path']).stem
  # Figure is consistently the second authored box; preserve all properties and fragment markers.
  old=[o['create'] for o in yaml.safe_load(row['before'])['operations'] if 'create' in o][1]
  new=[o['create'] for o in yaml.safe_load((R/row['path']).read_text())['operations'] if 'create' in o][1]
  box='b62_'+old['box'];old['box']=box;old['properties']['code']=frozen(old['properties']['code'])
  await m.macro(name+'-original-v2',[{'create':old},{'connect':{'parent':'renderer','child':box}},{'launch':{'box':box}}])
  await fragment(name+'-original-clock',box,'t=1.5;m.push["time"]=1.5f;program.writeFrameToMesh(1.5,m);java.nio.file.Files.writeString(java.nio.file.Path.of('+json.dumps(str(m.OUT/(name+'-original.frag')))+'),requireNotNull(fragment))')
  before=await capture(name+'-before-v2')
  source=frozen(new['properties']['code']);await update(name+'-refresh-v2',box,source)
  await fragment(name+'-shader-property-v2',box,'check(fragment==shader.exampleFragmentShader);println("installed generated material shader matches current RectSL source")')
  await fragment(name+'-revised-clock',box,'t=1.5;m.push["time"]=1.5f;program.writeFrameToMesh(1.5,m);java.nio.file.Files.writeString(java.nio.file.Path.of('+json.dumps(str(m.OUT/(name+'-revised.frag')))+'),requireNotNull(fragment))')
  assert (m.OUT/(name+'-original.frag')).read_text()==(m.OUT/(name+'-revised.frag')).read_text(),'same-source generated shader differs'
  after=await capture(name+'-after-v2')
  assert ImageChops.difference(Image.open(before).convert('RGB'),Image.open(after).convert('RGB')).getbbox() is None,name
  changed=False
  if name in ['recstl_playground','rectsl_dataviz']:
   if name=='recstl_playground':edited=source.replace('uv 3.0 * time 0.5 * + fbm palette','uv 9.0 * time 0.5 * + fbm palette')
   else:edited=source.replace('#rectsl\n','#rectsl\n          material repaint \'( uv 3.0 * time 0.5 * + fbm palette )\n',1).replace('colormap solid ->fill','colormap solid ->fill repaint ->material')
   assert edited!=source
   await update(name+'-material-edit',box,edited)
   await fragment(name+'-edited-shader-property',box,'check(fragment==shader.exampleFragmentShader);println("edited material shader installed on actual box")')
   material=await capture(name+'-material')
   changed=ImageChops.difference(Image.open(after).convert('RGB'),Image.open(material).convert('RGB')).getbbox() is not None
   assert changed,name+' material did not change visible output'
  await m.batch(name+'-stop',[f'stop {box}'])
  await fragment(name+'-detach',box,'renderer.renderList.modules.clear()')
  records.append({'example':name,'beforeAfterPixelsEqual':True,'installedShaderMatchesGenerated':True,'materialEditChangesNativePixels':changed if name in ['recstl_playground','rectsl_dataviz'] else 'not separately edited'})
 (H/'behavior.json').write_text(json.dumps({'examples':records,'limits':'Actual authored fragment boundaries, RectSL compiler and Vulkan rendering. Fixture freezes the authored clock at 1.5 seconds for deterministic comparison and reuses the existing private renderer. No public media replaced.'},indent=2)+'\n')
asyncio.run(main())
