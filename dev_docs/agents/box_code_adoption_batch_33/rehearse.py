"""Exercise saved/reopened studies, authored docs bodies, and a real kit migration."""
import asyncio,importlib.util,json,sys,yaml,shutil,time
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig33',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9702;m.OUT=R/'build/box-code-revision/adoption-33/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(R/'tools'));import field_kit as fk
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'));from format_docs import sections
WORKSPACE=R/'build/field-automation-workspace';STAMP=str(time.time_ns())
async def evaluate(host,box,expression):
 r=await host.call('EvaluateKotlinExpressionRequest',target=box,expression=expression,renderHtml=False)
 (m.OUT/('eval-'+str(len(list(m.OUT.glob('eval-*.json'))))+'.json')).write_text(json.dumps(r,indent=2))
 assert r['success'],r
 assert r.get('primitiveValue') is True,r
async def studies_document():
 doc=yaml.safe_load((R/'dev_docs/agents/transcripts/studies_rings.yaml').read_text())
 graph=[o for o in doc['operations'] if 'create' in o or 'connect' in o]
 name='box-code-rings-33-'+STAMP;folder=WORKSPACE/name
 await m.macro('rings-save-graph',graph+[{'save':{'path':str(folder)}}],True)
 async with fk.LiveHost(m.PORT,name) as host:
  for box in ['rings_study','rings_change','rings_try']:
   ok,message=await host.launch(box);assert ok,message
  await evaluate(host,'rings_study','StudyKnobs.plainNumbers(rings).map { it.value } == listOf(7.0,0.05,0.2)')
  ok,message=await host.launch('rings_study');assert ok,message
  await evaluate(host,'rings_study','StudyKnobs.plainNumbers(rings).map { it.value } == listOf(7.0,0.05,0.2)')
  await host.save()
 reopened=name+'-reopened';shutil.copytree(folder,WORKSPACE/reopened)
 async with fk.LiveHost(m.PORT,reopened) as host:
  ok,message=await host.launch('rings_study');assert ok,message
  await evaluate(host,'rings_study','StudyKnobs.plainNumbers(rings).map { it.value } == listOf(7.0,0.05,0.2)')
  ok,message=await host.launch('rings_change');assert ok,message
  await evaluate(host,'rings_study','StudyKnobs.plainNumbers(rings).map { it.value } == listOf(7.0,0.05,0.8)')
async def real_kit():
 text=(R/'public/docs/kits.md').read_text();code=next(text[a:b] for a,b in sections(text) if 'prop<String>(roomRules,' in text[a:b])
 name='box-code-kit-33-'+STAMP;folder=WORKSPACE/name;initial='minRoom=3\nmaxRoom=9'
 await m.macro('kit-save-graph',[{'create':{'box':'rules','name':'Rules','properties':{'code':code,'roomRules':initial}}},{'save':{'path':str(folder)}}],True)
 async with fk.LiveHost(m.PORT,name) as host:
  # Ordinary custom sources are installed through the same property tool used by kit authors.
  await host.set('rules','roomRules',initial)
  await host.set('rules','rulesBaseline',initial)
  ok,message=await host.launch('rules');assert ok,message
  await evaluate(host,'rules','rules == mapOf("minRoom" to 3,"maxRoom" to 9)')
  kit=m.OUT/('kit-'+STAMP)
  assert await fk.make(host,kit,'readability-kit',['rules=Rules'],['rules.roomRules'],['rules.rulesBaseline=rules.roomRules'],[])==0
  source=kit/'roles/rules/roomRules.txt';source.write_text('minRoom=6\nmaxRoom=12')
  assert fk.rehash(kit,False)==0
  assert await fk.migrate(host,kit)==0
  await evaluate(host,'rules','rules == mapOf("minRoom" to 6,"maxRoom" to 12)')
  assert await host.get('rules','rulesBaseline')=='minRoom=6\nmaxRoom=12'
  await host.set('rules','roomRules','minRoom=4\nmaxRoom=10')
  ok,message=await host.launch('rules');assert ok,message
  await evaluate(host,'rules','rules == mapOf("minRoom" to 4,"maxRoom" to 10)')
  assert await host.get('rules','rulesBaseline')=='minRoom=6\nmaxRoom=12'
  assert await fk.migrate(host,kit)==2
  # Missing source retains the explicitly documented empty default in a separate box.
  empty=await host.new_box('No source');await host.set(empty,'code',code)
  ok,message=await host.launch(empty);assert ok,message
  await evaluate(host,empty,'rules.isEmpty()')
async def docs_programs():
 text=(R/'public/docs/studies.md').read_text();done=[]
 for i,(a,b) in enumerate(sections(text)):
  code=text[a:b]
  # 4 requires an existing design study; 5 is a constructor argument; 6 deliberately throws.
  # Their unchanged tokens are checked separately; do not invent standalone context.
  if i in [4,5,6]: continue
  await m.program('study-doc-'+str(i),code)
  done.append(i)
 (m.OUT/'docs-coverage.json').write_text(json.dumps({'executedStates':done},indent=2)+'\n')
async def main():
 if "--docs-only" not in sys.argv and "--skip-studies" not in sys.argv: await studies_document()
 if "--docs-only" not in sys.argv: await real_kit()
 await docs_programs()
asyncio.run(main())
