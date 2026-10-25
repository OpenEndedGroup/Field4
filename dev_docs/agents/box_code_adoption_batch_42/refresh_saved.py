"""Refresh canonical MIDI sources through the codec and rename actions through a real saved document."""
from pathlib import Path
import asyncio,importlib.util,json,sys,time,shutil,yaml,os
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig42save',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(os.environ.get('FIELD_TEST_PORT','9712'));m.OUT=R/'build/box-code-revision/adoption-42/saved';m.OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(R/'tools'));import field_kit
async def main():
 folder=R/'public/docs/assets/rtp_midi_demo'
 transcript=yaml.safe_load((R/'dev_docs/agents/transcripts/rtp_midi_demo.yaml').read_text());creates=[o['create'] for o in transcript['operations'] if 'create' in o]
 for c in creates:assert c['properties']['code']==(folder/(c['box']+'_code_.kt')).read_text(),c['box']
 code='''val directory=DIRECTORY
val saving=afield.document.persistence.SavingContext()
val before=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
val names=saving.readInlineFileSnapshots(directory).groupBy { it.boxId }.mapValues { it.value.map { s->s.property }.toSet() }
saving.refreshInlineFilesFromDisk(directory)
val after=saving.readEdnTransfersFromString(java.io.File(directory,"metadata.edn").readText())
check(before.map { it.id }==after.map { it.id })
check(before.map { it.properties-names[it.id].orEmpty() }==after.map { it.properties-names[it.id].orEmpty() })
check(saving.readInlineFileSnapshots(directory).all { it.inline==java.io.File(directory,it.path).readText() })
println("canonical sources refreshed; graph preserved")'''.replace('DIRECTORY',json.dumps(str(folder)))
 await m.program('refresh-fallbacks',code)
 name='box-code-midi-42-'+str(time.time_ns());copy=R/'build/field-automation-workspace'/name;shutil.copytree(folder,copy)
 async with field_kit.LiveHost(m.PORT,name) as host:
  for c in creates:
   assert (await host.get(c['box'],'code')).rstrip()==c['properties']['code'].rstrip()
   if c['box'] in ['rtpm-peer','rtpm-play']:await host.set(c['box'],'name',c['name'])
  await host.save()
 code='''val original=java.io.File(ORIGINAL,"metadata.edn")
val saved=java.io.File(COPY,"metadata.edn")
val codec=afield.document.persistence.SavingContext()
val a=codec.readEdnTransfersFromString(original.readText()).associateBy { it.id }
val b=codec.readEdnTransfersFromString(saved.readText()).associateBy { it.id }
check(a.keys==b.keys)
a.forEach { (id,box) -> check(box.properties-"name"==b.getValue(id).properties-"name") { "Unexpected non-name change in $id" } }
check(b.getValue("rtpm-peer").properties["name"]=="2. A peer joins (launch)")
check(b.getValue("rtpm-play").properties["name"]=="3. Play into it (launch)")
java.nio.file.Files.copy(saved.toPath(),original.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
println("real Field save renamed only the two action labels")'''.replace('ORIGINAL',json.dumps(str(folder))).replace('COPY',json.dumps(str(copy)))
 await m.program('verify-saved-names',code)
 (m.OUT/'document.json').write_text(json.dumps({'freshCopy':name,'sourcesMatch':True,'nameChangesThroughField':True,'nonNamePropertiesPreserved':True},indent=2)+'\n')
asyncio.run(main())
