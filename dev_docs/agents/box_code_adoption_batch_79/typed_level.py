"""Replace the remaining tutorial initializer backdoor with its declared typed property."""
from pathlib import Path
import asyncio,importlib.util,json,yaml,subprocess
H=Path(__file__).resolve().parent;R=H.parents[2];p=R/'dev_docs/agents/transcripts/staged_project.yaml';before=p.read_text()
old='if (getPropertyRaw("level") == null) setPropertyRaw("level", json.writeValueAsString(json.readTree(first)))'
new='prop<String?>(level)\n          if (level == null) level = json.writeValueAsString(json.readTree(first))'
if old not in before:before=subprocess.check_output(["git","show","HEAD:"+str(p.relative_to(R))],cwd=R,text=True)
assert old in before
text=before.replace(old,new,1).replace('{ getPropertyRaw("level", "").toString() }','{ level.orEmpty() }',1);p.write_text(text)
rows=json.loads((H/'changes.json').read_text());row=next((x for x in rows if x['path']==str(p.relative_to(R))),None)
if row is None:rows.append({'path':str(p.relative_to(R)),'before':before,'after':text})
else:
 assert row['after']==before;row['after']=text
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
(H/'typed-level-edit.json').write_text(json.dumps({'path':str(p.relative_to(R)),'old':old,'new':new,'getter':'level.orEmpty()'},indent=2)+'\n')
s=importlib.util.spec_from_file_location('typed79',H.parent/'box_code_adoption_batch_78/rehearse.py');r=importlib.util.module_from_spec(s);exec((H.parent/'box_code_adoption_batch_78/rehearse.py').read_text().rsplit('asyncio.run(main())',1)[0],r.__dict__);r.m.OUT=R/'build/box-code-revision/adoption-79/typed-level';r.m.OUT.mkdir(exist_ok=True)
async def main():
 ops=yaml.safe_load(text)['operations'];body=next(x['create']['properties']['code'] for x in ops if 'create' in x and 'prop<String?>(level)' in x['create']['properties'].get('code',''))
 body+='\ncheck(!level.isNullOrBlank());check(json.readTree(level).has("rooms"));println("TYPED_LEVEL_INITIALIZED79")'
 await r.box('typed-level79',body)
 await r.fragment('typed-level-rerun-edit79','typed-level79','level = json.writeValueAsString(json.readTree(level).also { (it as com.fasterxml.jackson.databind.node.ObjectNode).put("typedPropertyRerun",true) })')
 # Execute the initializer as a fragment: the persisted source must survive re-execution unchanged.
 await r.fragment('typed-level-preserves-existing79','typed-level79','if(level==null)level=json.writeValueAsString(json.readTree(first));check(json.readTree(level).get("typedPropertyRerun").asBoolean());println("TYPED_LEVEL_PRESERVED79")')
 (H/'typed-level-behavior.json').write_text(json.dumps({'completeAuthoredLevelBodyRan':True,'typedPropertyInitialized':True,'existingSourceSurvivedRepeatedInitializer':True},indent=2)+'\n')
asyncio.run(main())
