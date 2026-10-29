"""Refresh template fallbacks through Field's codec and assert graph/non-source preservation."""
from pathlib import Path
import asyncio,importlib.util,json
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('saved45',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9716;m.OUT=R/'build/box-code-revision/adoption-45/saved';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 for name in ['first-graphics','first-steps']:
  directory=R/'templates'/name
  code='''val directory=DIRECTORY
val saving=afield.document.persistence.SavingContext()
val before=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
val sourceNames=saving.readInlineFileSnapshots(directory).groupBy { it.boxId }.mapValues { it.value.map { s -> s.property }.toSet() }
saving.refreshInlineFilesFromDisk(directory)
val after=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
check(before.map { it.id } == after.map { it.id })
check(before.map { it.properties-sourceNames[it.id].orEmpty() } == after.map { it.properties-sourceNames[it.id].orEmpty() })
check(saving.readInlineFileSnapshots(directory).all { it.inline == java.io.File(directory,it.path).readText() })
println("template sources refreshed; graph and all non-source properties preserved")'''.replace('DIRECTORY',json.dumps(str(directory)))
  await m.program(name+'-fallbacks',code)
asyncio.run(main())
