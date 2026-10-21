"""Run the saved fluid resource/advection sources in their real parent graph."""
import asyncio,importlib.util,json,sys,yaml
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig29',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m
m.OUT=R/'build/box-code-revision/adoption-29/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(name,box,code):
 return await m.macro(name,[{'execute':{'box':box,'property':'code','type':'fragment','code':code}}])
async def main():
 doc=yaml.safe_load((R/'dev_docs/agents/transcripts/flow_grain.yaml').read_text())
 selected={'flow_renderer','flow_resources','flow_helpers','flow_inputs','flow_advect'}
 ops=[]
 for o in doc['operations']:
  if o.get('create',{}).get('box') in selected:
   c=o['create'];alias=c['box']
   if alias in ['flow_resources','flow_advect']:c['properties']['code']=(R/'dev_docs/agents/demos/flow_grain'/('resources.kt' if alias=='flow_resources' else 'advect.kt')).read_text()
   if alias=='flow_advect':c['properties']['compute']=(R/'dev_docs/agents/demos/flow_grain/advect.comp').read_text()
   ops.append(o)
  elif 'connect' in o and o['connect']['parent'] in selected and o['connect']['child'] in selected:ops.append(o)
 # Explicit launches keep the resource/input graph settled before installing the stage.
 for alias in ['flow_renderer','flow_resources','flow_inputs','flow_advect']:
  ops.extend([{'launch':{'box':alias}},{'await':{}},{'wait':{'frames':3}}])
 await m.macro('saved-fluid-graph',ops,True)
 await m.batch('fluid-frames',['wait-native-renderer flow_renderer --frames 30 --timeout 60'])
 await fragment('advect-owned','flow_advect','''check(simulation === flowSimulation);check(simulation.modules["advect"] === module);println("advection is attached to the inherited simulation")''')
 await m.batch('advect-stop',['stop flow_advect'])
 await fragment('advect-detached','flow_resources','''check(simulation.modules["advect"] == null);check(targets["flow-simulation"] === simulation);check(targets["flow-surface"] === surface);println("stopping stage detaches it and keeps parent targets")''')
 await m.batch('advect-rerun',['launch flow_advect','wait-native-renderer flow_renderer --frames 30 --timeout 60'])
 await fragment('advect-rerun-owned','flow_advect','''check(simulation.modules["advect"] === module);println("rerun installs the new advection stage")''')
 await m.batch('children-stop',['stop flow_advect','stop flow_inputs'])
 await fragment('retain-old-resources','flow_resources','thisBox.properties["proof.old-simulation"] = simulation;thisBox.properties["proof.old-surface"] = surface')
 await m.batch('resources-rerun',['launch flow_resources','wait-native-renderer flow_renderer --frames 10 --timeout 60'])
 await fragment('replacement-resources-owned','flow_resources','''check(simulation !== thisBox.properties["proof.old-simulation"]);check(surface !== thisBox.properties["proof.old-surface"]);check(targets["flow-simulation"] === simulation);check(targets["flow-surface"] === surface);println("old cleanup preserves newly installed targets")''')
 await m.batch('fluid-stop',['stop flow_resources'])
 await fragment('resources-detached','flow_renderer','''check(renderer.renderList.preTargets["flow-simulation"] == null);check(renderer.renderList.preTargets["flow-surface"] == null);println("Stop detaches both owned parent targets")''')
 await m.batch('renderer-stop',['stop flow_renderer','lifecycle-census flow_renderer --expect-stopped'])
asyncio.run(main())
