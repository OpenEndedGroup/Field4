"""Replay aperture producer/consumer graph and compare three actual native display modes."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
from PIL import Image,ImageChops
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig61',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9719;m.OUT=R/'build/box-code-revision/adoption-61/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,code,box='aperture_composite'):return await m.macro(name,[{'execute':{'box':box,'type':'fragment','code':'run {\n'+code+'\n}'}}])
async def capture(label):
 path=m.OUT/(label+'.png');await m.batch(label,['wait-native-renderer renderer --frames 12 --timeout 60',f'native-screenshot {path} --uid renderer --wait-frames 4 --timeout 60']);return path
async def main():
 row=json.loads((H/'changes.json').read_text())[0];before=yaml.safe_load(row['before']);after=yaml.safe_load((R/row['path']).read_text())
 await m.macro('aperture-clear-private-scene',[{'execute':{'box':'pc-proof','type':'fragment','code':'renderer.renderList.modules.clear();renderer.renderList.preTargets.clear();println("private pass-chain graph detached for aperture proof")'}}])
 boxes=[o['create'] for o in before['operations'] if 'create' in o][1:]
 connections=[o for o in before['operations'] if 'connect' in o]
 for op in connections:
  if op['connect']['parent']=='agent_graphics_context':op['connect']['parent']='renderer'
 order=['aperture_source','aperture_half','aperture_quarter','aperture_medium','aperture_broad','aperture_composite','aperture_sprites']
 await m.macro('aperture-baseline-graph',[*({'create':b} for b in boxes),*connections,*({'execute':{'box':b}} for b in order)])
 original={}
 for mode in range(3):
  await fragment('aperture-original-mode-'+str(mode),f'apertureControl.set(1f,{mode}f,0f,0f)')
  original[mode]=await capture('original-'+str(mode))
 for box in [o['create'] for o in after['operations'] if 'create' in o][1:]:
  path=m.OUT/(box['box']+'.kt');path.write_text(box['properties']['code']);await m.batch('aperture-edit-'+box['box'],[f"set {box['box']} code --file {path}"])
 await m.macro('aperture-inherited-graph',[{'execute':{'box':b}} for b in order])
 await fragment('aperture-source-layout','check(inside.boxes.getValue("aperture_half").nestedIn===thisBox);check(inside.boxes.getValue("aperture_quarter").nestedIn===thisBox);check(frame==Rect(360.0,70.0,260.0,270.0));println("source and two reduction boxes retain authored nesting/layout")','aperture_source')
 for mode in range(3):
  await fragment('aperture-inherited-mode-'+str(mode),f'apertureControl.set(1f,{mode}f,0f,0f)')
  revised=await capture('inherited-'+str(mode))
  assert ImageChops.difference(Image.open(original[mode]).convert('RGB'),Image.open(revised).convert('RGB')).getbbox() is None,mode
 assert ImageChops.difference(Image.open(original[0]).convert('RGB'),Image.open(original[1]).convert('RGB')).getbbox() is not None
 assert ImageChops.difference(Image.open(original[1]).convert('RGB'),Image.open(original[2]).convert('RGB')).getbbox() is not None
 await fragment('aperture-control-restore','apertureControl.set(1f,0f,0f,0f)')
 (H/'behavior.json').write_text(json.dumps({'actualNativeFullDependentGraph':True,'sourceRerunThenDownstream':True,'sourceAndReductionNestingPreserved':True,'pixelIdenticalModes':['aperture','sharp','radius guide'],'modesProduceDifferentPixels':True,'limits':'Uses existing private renderer/window; all other authored producer, filter, composite and analytic sprite bodies/shaders are replayed. No public media replaced.'},indent=2)+'\n')
asyncio.run(main())
