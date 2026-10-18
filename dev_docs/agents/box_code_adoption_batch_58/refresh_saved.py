"""Refresh the saved crowd fallback through Field, then its matching document mirror."""
from pathlib import Path
import asyncio,importlib.util,json,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('saved58',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9719;m.OUT=R/'build/box-code-revision/adoption-58/saved';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 rows=json.loads((H/'changes.json').read_text());directory=R/'dev_docs/agents/demos/skinned_characters';metadata=directory/'metadata.edn';before=metadata.read_text()
 code='''val directory=DIRECTORY
val saving=afield.document.persistence.SavingContext()
val before=saving.readEdnTransfers(directory)
val snapshots=saving.readInlineFileSnapshots(directory)
check(snapshots.filter {it.path!="gpu_crowd_code_.kt"}.all {it.inline==java.io.File(directory,it.path).readText()}) {"Unrelated fallback already differs"}
val sourceNames=snapshots.groupBy {it.boxId}.mapValues {it.value.map {s->s.property}.toSet()}
saving.refreshInlineFilesFromDisk(directory)
val after=saving.readEdnTransfers(directory)
check(before.map {it.id}==after.map {it.id})
check(before.map {it.properties-sourceNames[it.id].orEmpty()}==after.map {it.properties-sourceNames[it.id].orEmpty()})
check(saving.readInlineFileSnapshots(directory).all {it.inline==java.io.File(directory,it.path).readText()})
println("crowd fallback refreshed; graph and non-source values preserved")'''.replace('DIRECTORY',json.dumps(str(directory)))
 await m.macro('crowd-fallback-codec',[{'create':{'box':'crowd-codec','properties':{'code':code}}},{'execute':{'box':'crowd-codec'}}])
 rows.append({'path':str(metadata.relative_to(R)),'before':before,'after':metadata.read_text()})
 sys.path.insert(0,str(R));from tools.check_box_idioms import MIRROR_FENCE,refresh_mirror_digests
 mirror=directory/'document.md';before=mirror.read_text();row=next(x for x in rows if x['path'].endswith('gpu_crowd_code_.kt'));match=next(x for x in MIRROR_FENCE.finditer(before) if x['path']=='gpu_crowd_code_.kt');assert match['body'].rstrip('\n')==row['before'].rstrip('\n')
 mirror.write_text(before[:match.start('body')]+(directory/'gpu_crowd_code_.kt').read_text().rstrip()+'\n'+before[match.end('body'):]);refresh_mirror_digests(mirror);rows.append({'path':str(mirror.relative_to(R)),'before':before,'after':mirror.read_text()});(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
asyncio.run(main())
