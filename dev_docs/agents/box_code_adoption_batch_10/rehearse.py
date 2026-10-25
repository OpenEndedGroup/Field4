"""Run actual CPU teaching programs and refresh metadata through Field's own codec."""
from pathlib import Path
import sys,importlib.util,asyncio,json
ROOT=Path(__file__).resolve().parents[3];sys.path.insert(0,str(ROOT/'dev_docs/agents/box_code_adoption_batch_3'))
spec=importlib.util.spec_from_file_location('prior',ROOT/'dev_docs/agents/box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);m.ROOT=ROOT;m.OUT=ROOT/'build/box-code-revision/adoption-10/runtime';m.OUT.mkdir(parents=True,exist_ok=True);m.PORT=9700
async def main():
 paths=[
 'public/docs/assets/stacksl_fit_demo/stacksl-roundtrip_code_.kt',
 'public/docs/assets/stacksl_surrogate_demo/surrogate-cost_code_.kt',
 'public/docs/assets/stacksl_surrogate_demo/surrogate-wall_code_.kt',
 'templates/h2-explorer-examples/9a3aaad6-0c2f-4851-9ef7-ae0fc6af4d00_code_.kt',
 'templates/timeSlider/3c6a8254-9160-4021-803c-57c4ff93f8f1_code_.kt',
 ]
 for i,path in enumerate(paths):await m.program('source_'+str(i),(ROOT/path).read_text())
 for i,directory in enumerate(['public/docs/assets/stacksl_fit_demo','public/docs/assets/stacksl_surrogate_demo','templates/h2-explorer-examples','templates/first-graphics']):
  code=f'''
val directory={json.dumps(str(ROOT/directory))}
val saving=afield.document.persistence.SavingContext()
val before=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
val inlineNames=saving.readInlineFileSnapshots(directory).map {{ it.property }}.toSet()
val refreshed=saving.refreshInlineFilesFromDisk(directory)
val after=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
check(before.map {{ it.id }}==after.map {{ it.id }})
check(before.map {{ it.properties-inlineNames }}==after.map {{ it.properties-inlineNames }})
check(saving.readInlineFileSnapshots(directory).all {{ it.inline==java.io.File(directory,it.path).readText() }})
println("refreshed ${{refreshed.size}} files; graph and non-sidecar properties preserved")
'''
  await m.program('metadata_'+str(i),code)
if __name__=='__main__':asyncio.run(main())
