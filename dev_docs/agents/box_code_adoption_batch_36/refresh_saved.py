"""Synchronize the authored mesh-repair document through Field's codec and owned mirrors."""
from pathlib import Path
import importlib.util,json,asyncio,sys,subprocess,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('refresh36',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9703;m.OUT=R/'build/box-code-revision/adoption-36/refresh';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 directory=R/'public/docs/assets/mesh_repair_demo';rows=json.loads((H/'changes.json').read_text());known={r['path'] for r in rows};changed=set()
 for o in yaml.safe_load((R/'dev_docs/agents/transcripts/mesh_repair_demo.yaml').read_text())['operations']:
  box=o.get('create',{});code=box.get('properties',{}).get('code')
  if code is None:continue
  p=directory/(box['box']+'_code_.kt');before=subprocess.check_output(['git','show','62444457e:'+str(p.relative_to(R))],text=True,cwd=R);p.write_text(code);changed.add(p.name)
  if str(p.relative_to(R)) not in known:rows.append(dict(path=str(p.relative_to(R)),findings=[],before=before,after=code))
 (H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
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
 s=importlib.util.spec_from_file_location('idioms36',R/'tools/check_box_idioms.py');t=importlib.util.module_from_spec(s);sys.modules[s.name]=t;s.loader.exec_module(t)
 p=directory/'document.md';text=p.read_text();edits=[]
 for hit in t.MIRROR_FENCE.finditer(text):
  if hit['path'] in changed:edits.append((hit.start('body'),hit.end('body'),(directory/hit['path']).read_text().rstrip('\n')+'\n'))
 assert len(edits)==len(changed)
 for a,b,v in reversed(edits):text=text[:a]+v+text[b:]
 p.write_text(text);t.refresh_mirror_digests(p)
asyncio.run(main())
