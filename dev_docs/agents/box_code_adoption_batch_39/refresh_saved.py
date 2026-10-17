"""Refresh the saved reprojection document through Field's codec; preserve graph/non-source data."""
from pathlib import Path
import importlib.util,json,asyncio,os
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('refresh39',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(os.environ.get('FIELD_TEST_PORT','9706'));m.OUT=R/'build/box-code-revision/adoption-39/refresh';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 directory=R/'dev_docs/agents/demos/frame_history_reprojection'
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
# Owned Markdown code mirrors follow the canonical, formatted sidecars.
import sys
spec=importlib.util.spec_from_file_location('idioms39',R/'tools/check_box_idioms.py');idioms=importlib.util.module_from_spec(spec);sys.modules[spec.name]=idioms;spec.loader.exec_module(idioms)
directory=R/'dev_docs/agents/demos/frame_history_reprojection';p=directory/'document.md';text=p.read_text();edits=[]
for hit in idioms.MIRROR_FENCE.finditer(text):
 source=directory/hit['path']
 if source.name in {'rp_renderer_code_.kt','rp_bundle_code_.kt','rp_chain_code_.kt','rp_modes_code_.kt'}:edits.append((hit.start('body'),hit.end('body'),source.read_text().rstrip('\n')+'\n'))
assert len(edits)==4
for a,b,value in reversed(edits):text=text[:a]+value+text[b:]
p.write_text(text);idioms.refresh_mirror_digests(p)

