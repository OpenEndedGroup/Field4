"""Refresh the saved polytope through Field's codec; preserve graph/non-source data."""
from pathlib import Path
import importlib.util,json,asyncio,os
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('refresh32',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(os.environ.get('FIELD_TEST_PORT','9702'));m.OUT=R/'build/box-code-revision/adoption-32/refresh';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 directory=R/'dev_docs/agents/demos/stackauto_conversation'
 code='''val directory=DIRECTORY
val saving=afield.document.persistence.SavingContext()
val before=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
val names=saving.readInlineFileSnapshots(directory).groupBy { it.boxId }.mapValues { it.value.map { s->s.property }.toSet() }
saving.refreshInlineFilesFromDisk(directory)
val after=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
check(before.map { it.id }==after.map { it.id })
check(before.map { it.properties-names[it.id].orEmpty() }==after.map { it.properties-names[it.id].orEmpty() })
check(saving.readInlineFileSnapshots(directory).all { it.inline==java.io.File(directory,it.path).readText() })
println("saved sources refreshed; graph and non-source properties preserved")'''.replace('DIRECTORY',json.dumps(str(directory)))
 await m.program('metadata',code)
asyncio.run(main())
