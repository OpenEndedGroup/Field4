"""Render the actual crowd with 1, 2 and 64 instances in one fresh native Renderer."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig58',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9719;m.OUT=R/'build/box-code-revision/adoption-58/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,box,code):return await m.macro(name,[{'execute':{'box':box,'property':'code','type':'fragment','code':code}}])
async def main():
 data=yaml.safe_load((R/'dev_docs/agents/transcripts/skinned_characters.yaml').read_text());crowd=next(o['create'] for o in data['operations'] if o.get('create',{}).get('box')=='gpu_crowd');new=crowd['properties']['code']
 owner=yaml.safe_load((R/'dev_docs/agents/transcripts/docs_capture/first_graphics_native.yaml').read_text())['operations'][0]['create'];owner['box']='renderer';owner['properties']['code']='import afield.graphics.vulkan.util.Options\nOptions.enableVR=false\nOptions.enableHDR=false\n'+owner['properties']['code'];crowd['box']='crowd';crowd['properties']['code']=new.replace('val count = 64','val count = 1')
 await m.macro('crowd-create',[{'create':owner},{'create':crowd},{'connect':{'parent':'renderer','child':'crowd'}},{'launch':{'box':'renderer'}},{'await':{}},{'launch':{'box':'crowd'}},{'await':{}}],True)
 for count in [1,2,64]:
  if count!=1:
   path=m.OUT/f'count-{count}.kt';path.write_text(new.replace('val count = 64',f'val count = {count}'))
   await m.batch('count-'+str(count),[f'set crowd code --file {path}','launch crowd'])
  await m.batch('native-'+str(count),['wait-native-renderer renderer --frames 15 --timeout 60',f'native-screenshot {m.OUT}/count-{count}.png --uid renderer --wait-frames 4 --timeout 60'])
  proof='''check(renderer.renderList.modules.keys==setOf("gpu skinned crowd"))
check(module.current.meshes.size==1 && module.current.meshes.values.single()===m)
check(m.instanceCount==COUNT)
check(skinner.instances==COUNT)
for(i in 0 until COUNT) {
    val palette=skinner.blendedPalette(place(i),"bend",0.3f,"curl",0.7f,mix=if(COUNT==1)0f else i/(COUNT-1f))
    check(palette.size==skinner.boneCount)
    check(palette.all {it.get(FloatArray(16)).all {value->value.isFinite()}}) {"non-finite palette"}
}
println("COUNT instances, finite palettes, one module/mesh/draw")'''.replace('COUNT',str(count))
  await fragment('finite-palettes-'+str(count),'crowd',proof)
 # Exercise the actual early guard without constructing a second model or touching the drawing.
 prefix=new[:new.index('val model =')].replace('val count = 64','val count = 0')
 await fragment('reject-zero-before-allocation','crowd','val rejected=runCatching {\n'+prefix+'\n}.exceptionOrNull()\ncheck(rejected is IllegalArgumentException && rejected.message!!.contains("count must be positive"));check(m.instanceCount==64);println("zero fails before model allocation; existing crowd remains")')
 (H/'behavior.json').write_text(json.dumps({'actualNativeInstanceCounts':[1,2,64],'finitePalettes':True,'oneModuleMeshAndDraw':True,'zeroGuardBeforeAllocation':True,'rendererPort':9719,'limits':'Real fixture, shaders and native screenshots. Zero exercises the authored prefix before model allocation. Renderer remains running for subsequent GPU checks.'},indent=2)+'\n')
asyncio.run(main())
