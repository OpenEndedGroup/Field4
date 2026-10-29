"""Verify inherited graph properties and live pass edits against the native renderer."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
from PIL import Image,ImageChops
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig60',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9719;m.OUT=R/'build/box-code-revision/adoption-60/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,code):return await m.macro(name,[{'execute':{'box':'pc-proof','type':'fragment','code':'run {\n'+code+'\n}'}}])
async def capture(name):
 path=m.OUT/(name+'.png')
 await m.batch(name,['wait-native-renderer renderer --frames 16 --timeout 60',f'native-screenshot {path} --uid renderer --wait-frames 4 --timeout 60'])
 return path
async def main():
 row=json.loads((H/'changes.json').read_text())[0]
 original=yaml.safe_load(row['before']);revised=yaml.safe_load((R/row['path']).read_text())
 boxes=[o['create'] for o in original['operations'] if 'create' in o][1:]
 if '--resume' not in __import__('sys').argv:await m.macro('pass-chain-baseline',[*({'create':b} for b in boxes),{'connect':{'parent':'renderer','child':'pc_scene'}},{'connect':{'parent':'pc_scene','child':'pc_chain'}},{'execute':{'box':'pc_scene'}},{'execute':{'box':'pc_chain'}}])
 await m.macro('pass-chain-identity-probe-five',[{'create':{'box':'pc-proof','properties':{'code':'''import afield.graphics.scene.fabricated.*
prop<FabricateBuiltChain>(originalChain)
prop<StandardRenderer.Bundle>(originalBundle)
originalChain=chain
originalBundle=bundle
check(chain.effects.size==5)
println("saved actual scene and five-pass chain identities")'''}}},{'connect':{'parent':'pc_chain','child':'pc-proof'}},{'execute':{'box':'pc-proof'}}])
 before=await capture('baseline')
 for box in [o['create'] for o in revised['operations'] if 'create' in o][1:]:
  path=m.OUT/(box['box']+'.kt');path.write_text(box['properties']['code'])
  await m.batch('inherited-'+box['box'],[f"set {box['box']} code --file {path}",f"run {box['box']}"])
 await fragment('pass-chain-identities-preserved','check(chain===originalChain);check(bundle===originalBundle);check(chain.effects.zip(originalChain.effects).all {(a,b)->a.target===b.target});println("same chain, bundle and four shared GPU targets after rerun")')
 after=await capture('inherited')
 assert ImageChops.difference(Image.open(before).convert('RGB'),Image.open(after).convert('RGB')).getbbox() is None,'native pixels changed'
 await fragment('pass-chain-live-knobs','check(chain.setPush("aperture-near","radius",10f));check(chain.setPush("aperture-far","radius",28f));check(chain.setPush("composite","blend",0.4f));check(chain===originalChain);println("three live push edits preserve chain identity")')
 edited=await capture('edited')
 assert ImageChops.difference(Image.open(after).convert('RGB'),Image.open(edited).convert('RGB')).getbbox() is not None,'live knobs did not affect pixels'
 await fragment('pass-chain-restore-knobs','check(chain.setPush("aperture-near","radius",4f));check(chain.setPush("aperture-far","radius",14f));check(chain.setPush("composite","blend",0.92f))')
 restored=await capture('restored')
 assert ImageChops.difference(Image.open(after).convert('RGB'),Image.open(restored).convert('RGB')).getbbox() is None,'restored pixels differ'
 (H/'behavior.json').write_text(json.dumps({'actualNativeGraph':True,'fivePassChainFourTargets':True,'rerunKeepsBundleChainAndTargets':True,'beforeAfterPixelsEqual':True,'liveKnobsChangePixelsAndRestoreExactly':True,'limits':'Uses existing private renderer instead of starting another window; authored scene and chain at original 1024 resolution. No public capture replaced.'},indent=2)+'\n')
asyncio.run(main())
