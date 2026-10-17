"""Refresh the saved feedback box through Field's real persistence codec."""
from pathlib import Path
import importlib.util,sys,asyncio,json
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent/'box_code_adoption_batch_3'))
spec=importlib.util.spec_from_file_location('prior_rehearsal',HERE.parent/'box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.ROOT=ROOT;m.OUT=ROOT/'build/box-code-revision/adoption-12/runtime';m.OUT.mkdir(parents=True,exist_ok=True);m.PORT=9700
async def main():
 await m.program('refresh_feedback',f'''
val directory={json.dumps(str(ROOT/'public/docs/assets/stacksl_cpu_demo'))}
val saving=afield.document.persistence.SavingContext()
val before=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
val snapshots=saving.readInlineFileSnapshots(directory)
val excluded=snapshots.groupBy {{ it.boxId }}.mapValues {{ (_,v) -> v.map {{ it.property }}.toSet() }}
println("refreshing "+snapshots.filter {{ it.inline!=java.io.File(directory,it.path).readText() }}.map {{ it.path }})
saving.refreshInlineFilesFromDisk(directory)
val after=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
check(before.map {{ it.id }}==after.map {{ it.id }})
check(before.map {{ it.properties-(excluded[it.id]?:emptySet<String>()) }}==after.map {{ it.properties-(excluded[it.id]?:emptySet<String>()) }})
check(saving.readInlineFileSnapshots(directory).all {{ it.inline==java.io.File(directory,it.path).readText() }})
println("saved feedback fallback synchronized; graph and other properties preserved")
''')
if __name__=='__main__':asyncio.run(main())
