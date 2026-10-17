"""Refresh saved source fallbacks with the real codec; update matching Markdown source mirrors."""
from pathlib import Path
import importlib.util,json,asyncio,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig21',H/'rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m
async def main():
 for i,name in enumerate(['completion_rank_at_scale','pattern_condense']):
  directory=R/'dev_docs/agents/demos'/name
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
  await m.program('metadata_'+str(i),code)
  p=directory/'document.md'
  if p.exists():
   s=importlib.util.spec_from_file_location('idioms21',R/'tools/check_box_idioms.py');t=importlib.util.module_from_spec(s);sys.modules[s.name]=t;s.loader.exec_module(t)
   changed={Path(r['path']).name for r in json.loads((H/'changes.json').read_text()) if Path(r['path']).parent==directory.relative_to(R)}
   text=p.read_text();edits=[]
   for hit in t.MIRROR_FENCE.finditer(text):
    if hit['path'] in changed:edits.append((hit.start('body'),hit.end('body'),(directory/hit['path']).read_text().rstrip('\n')+'\n'))
   assert len(edits)==len(changed),(changed,len(edits))
   for a,b,v in reversed(edits):text=text[:a]+v+text[b:]
   p.write_text(text);t.refresh_mirror_digests(p)
if __name__=='__main__':asyncio.run(main())
