"""Read-only native reproduction of chain feedback and camera pose drift before the repair."""
import asyncio,importlib.util,json,yaml
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig39base',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9705;m.OUT=R/'build/box-code-revision/adoption-39/baseline';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,box,code):return await m.macro(name,[{'execute':{'box':box,'property':'code','type':'fragment','code':code}}])
async def main():
 runtime=(R/"src/jvmMain/kotlin/afield/graphics/scene/fabricated/FabricatePassChainBuilder.kt").read_text()
 if "val chainInput = postStack.output()" not in runtime: raise SystemExit("Baseline already recorded before the runtime repair; reuse the retained baseline evidence.")
 folder=R/'dev_docs/agents/demos/frame_history_reprojection';doc=yaml.safe_load((folder/'build.yaml').read_text());graph=[o for o in doc['operations'] if 'create' in o or 'connect' in o]
 for o in graph:
  if 'create' in o:
   b=o['create'];b['properties']['code']=(folder/(b['box']+'_code_.kt')).read_text()
 await m.macro('graph',graph+[{'launch':{'box':'rp_renderer'}},{'await':{}},{'launch':{'box':'rp_bundle'}},{'await':{}},{'launch':{'box':'rp_chain'}},{'await':{}}],True)
 for mode in range(4):
  await fragment('mode-'+str(mode),'rp_chain',f'chain.setPush("display","mode",{mode})')
  await m.batch('capture-'+str(mode),['wait-native-renderer rp_renderer --frames 35 --timeout 60',f'native-screenshot {m.OUT}/mode-{mode}.png --uid rp_renderer --wait-frames 4 --timeout 60'])
 target=m.OUT/'poses.csv'
 collector='''val samples=mutableListOf<DoubleArray>()
frames {
    repeat(900) {
        val c=bundle.scene.camera
        val direction=afield.math.linalg.Vector3f(c.target).sub(c.at)
        samples += doubleArrayOf(c.at.x.toDouble(),c.at.y.toDouble(),c.at.z.toDouble(),direction.x.toDouble(),direction.y.toDouble(),direction.z.toDouble())
        frame()
    }
    java.io.File(PATH).writeText(samples.joinToString("\\n") { it.joinToString(",") })
    display("poses", "${samples.size} camera poses")
}
'''.replace('PATH',json.dumps(str(target)))
 await m.macro('collect-poses',[{'create':{'box':'rp_samples','properties':{'code':collector}}},{'connect':{'parent':'rp_bundle','child':'rp_samples'}},{'launch':{'box':'rp_samples'}},{'await':{}}])
 await m.batch('pose-complete',['wait-output rp_samples --channel poses --contains "900 camera poses" --timeout 90'])
 import math
 rows=[[float(x) for x in line.split(',')] for line in target.read_text().splitlines()];assert len(rows)==900
 lengths=[math.sqrt(sum(x*x for x in r[3:])) for r in rows];yaw=[math.atan2(r[3],r[5]) for r in rows]
 assert max(lengths)-min(lengths)>0.1,(min(lengths),max(lengths))
 (m.OUT/'pose-drift.json').write_text(json.dumps({'samples':900,'viewDirectionLength':[min(lengths),max(lengths)],'azimuthRadians':[min(yaw),max(yaw)]},indent=2)+'\n')
 # Freeze the renderer before building the known feedback chain: inspect the real target identities without submitting that broken graph to the GPU.
 await m.batch('freeze',['stop rp_samples','stop rp_chain','stop rp_renderer'])
 await fragment('retain-input','rp_chain','prop<afield.graphics.vulkan.targets.RenderToTexture>(baselineInput)=chain.input;prop<afield.graphics.vulkan.targets.RenderToTexture>(baselineTaa)=chain.pass("taa")!!.target')
 await fragment('feedback-selection','rp_chain','val nextInput = bundle.postStack.output();check(nextInput !== baselineInput);check(nextInput === chain.output);check(chain.pass("taa")!!.target === baselineTaa);println("old API input selection resolves to the existing display target")')
 await m.batch('stop',['stop rp_chain'])
asyncio.run(main())
