"""Ask Field's real document codec to replace only selected teaching source properties."""
from pathlib import Path
from collections import defaultdict
import asyncio,importlib.util,json,sys,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-79'
spec=importlib.util.spec_from_file_location('metadata_format_rig',H.parent/'box_code_adoption_batch_26/rehearse.py');rig=importlib.util.module_from_spec(spec);spec.loader.exec_module(rig);m=rig.m;m.PORT=9726;m.OUT=OUT/'codec';m.OUT.mkdir(parents=True,exist_ok=True)
receipt=OUT/'metadata-changes.json'
if receipt.exists():
 records=json.loads(receipt.read_text())
 assert all((R/x['path']).read_text()==x['after'] for x in records), 'Metadata changed after the recorded codec pass'
 print('Reusing completed codec receipt for',len(records),'documents');sys.exit(0)
bodies={x['language']+'-'+x['sha256']:x for x in map(json.loads,(OUT/'bodies.jsonl').read_text().splitlines())};requests=defaultdict(lambda:{'sidecars':[],'updates':[]})
changes=json.loads((OUT/'changes.json').read_text())
for x in changes:
 p=R/x['path'];meta=p.parent/'metadata.edn'
 if meta.is_file() and p.suffix not in {'.md','.yaml','.yml'}:requests[str(p.parent)]['sidecars'].append(p.name)
for row in map(json.loads,(OUT/'mechanical/reconciled-results.jsonl').read_text().splitlines()):
 if row['status']!='candidate':continue
 body=bodies[row['key']];candidate=OUT/'mechanical/inputs'/(row['key']+('_code_.kt' if row['language']=='kotlin' else '.yaml'));code=candidate.read_text() if row['language']=='kotlin' else yaml.safe_load(candidate.read_text())['fragment']
 for o in body['occurrences']:
  if not o['path'].endswith('.edn'):continue
  box,prop=o['locator'].rsplit('/',1);assert not o['folds'] and o['state']==0
  requests[str((R/o['path']).parent)]['updates'].append({'box':box,'property':prop,'code':code})
req=[{'directory':d,**v} for d,v in sorted(requests.items())];(OUT/'metadata-request.json').write_text(json.dumps(req,indent=2)+'\n')
before={str(Path(x['directory'])/'metadata.edn'): (Path(x['directory'])/'metadata.edn').read_text() for x in req};(OUT/'metadata-before.json').write_text(json.dumps(before)+'\n')
CODE='''import afield.document.persistence.*
import kotlinx.serialization.json.*
val request=Json.parseToJsonElement(java.io.File(REQUEST).readText()).jsonArray
val saving=SavingContext()
// Admin tooling uses the existing codec's registered tag types; no tutorial escape hatch.
val codecField=SavingContext::class.java.getDeclaredField("ednCustom").also {it.isAccessible=true}
val codec=codecField.get(null) as Edn.CustomTypes
fun namedKey(map: Map<*,*>, name:String):Any? = map.keys.first {it.toString().removePrefix(":")==name}
fun replaceSource(old:Any?, code:String):Any? {
    if(old is String)return code
    check(old!=null && old.javaClass.simpleName=="FileInline") {"Unsupported source value ${old?.javaClass}"}
    val pathField=old.javaClass.getDeclaredField("path").also {it.isAccessible=true}
    val copy=old.javaClass.getDeclaredMethod("copy",String::class.java,String::class.java).also {it.isAccessible=true}
    return copy.invoke(old,pathField.get(old) as String,code)
}
var documents=0
var properties=0
for(entry in request) {
    val data=entry.jsonObject
    val directory=data.getValue("directory").jsonPrimitive.content
    val file=java.io.File(directory,"metadata.edn")
    val beforeText=file.readText()
    val before=saving.readEdnTransfersFromString(beforeText)
    val updates=linkedMapOf<Pair<String,String>,String>()
    for(update in data.getValue("updates").jsonArray) {
        val u=update.jsonObject
        updates[u.getValue("box").jsonPrimitive.content to u.getValue("property").jsonPrimitive.content]=u.getValue("code").jsonPrimitive.content
    }
    val sidecars=data.getValue("sidecars").jsonArray.map {it.jsonPrimitive.content}.toSet()
    for(snapshot in saving.readInlineFileSnapshots(directory)) {
        if(snapshot.path in sidecars)updates[snapshot.boxId to snapshot.property]=java.io.File(directory,snapshot.path).readText()
    }
    if(updates.isEmpty())continue
    val root=Edn.read(beforeText,codec)
    val boxes=if(root is Map<*,*>)root[namedKey(root,"boxes")] as List<*> else root as List<*>
    val seen=mutableSetOf<Pair<String,String>>()
    val rewritten=boxes.map { item ->
        val box=item as Map<*,*>
        val id=box[namedKey(box,"id")].toString()
        val propsKey=namedKey(box,"properties")
        val props=box[propsKey] as Map<*,*>
        val newProps=props.mapValues { (key,value) ->
            val pair=id to key.toString().removePrefix(":")
            val code=updates[pair]
            if(code==null)value else {seen+=pair;replaceSource(value,code)}
        }
        box.mapValues { (key,value) -> if(key==propsKey)newProps else value }
    }
    check(seen==updates.keys) {"Missing source properties in $directory: ${updates.keys-seen}"}
    val newRoot=if(root is Map<*,*>) {val boxesKey=namedKey(root,"boxes");root.mapValues { (key,value) -> if(key==boxesKey)rewritten else value }} else rewritten
    val afterText=Edn.write(newRoot,codec)
    val after=saving.readEdnTransfersFromString(afterText)
    check(before.map {it.id}==after.map {it.id}) {"Graph IDs changed: $directory"}
    before.zip(after).forEach { (a,b) ->
        val changed=updates.keys.filter {it.first==a.id}.map {it.second}.toSet()
        check(a.properties-changed==b.properties-changed) {"Unselected property changed: $directory ${a.id}"}
        for(prop in changed)check(saving.resolveStoredValue(b.properties[prop],directory,preferInlineFiles=true)==updates[a.id to prop]) {"Source mismatch: $directory ${a.id}/$prop"}
    }
    file.writeText(afterText)
    documents++;properties+=updates.size
}
println("CODEC FORMAT: $documents documents, $properties selected properties; graph IDs and all unselected properties preserved")
'''
async def main():
 code=CODE.replace('REQUEST',json.dumps(str(OUT/'metadata-request.json')))
 await m.program('selected-source-codec',code)
 records=[]
 for name,old in before.items():
  p=Path(name);new=p.read_text()
  if old!=new:records.append({'path':str(p.relative_to(R)),'before':old,'after':new})
 (OUT/'metadata-changes.json').write_text(json.dumps(records,indent=2)+'\n');print('metadata files updated',len(records))
asyncio.run(main())
