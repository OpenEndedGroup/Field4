"""Verify ordinary Field tool reuse and durable diagnostics in two fresh documents.

Uses the existing AI4 registry; no provider credentials or fabricated tool results.
"""
import argparse,asyncio,json,sys,hashlib,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[1];sys.path.insert(0,str(root/'tools'))
from field_backdoor import FieldBackdoor,SessionLogger,select_automation_port,DEFAULT_AUTOMATION_PORT
async def verify(port,output,attach_existing):
 output.mkdir(parents=True,exist_ok=True);suffix=uuid.uuid4().hex[:8]
 logger=SessionLogger(output/'logs',sys.argv);c=FieldBackdoor(port=port,workspace=None,default_timeout=120,logger=logger,attach_existing=attach_existing)
 evidence=[]
 async def call(tool_name,**args):
  r=await c.ai_tool_call(name=tool_name,arguments=args,timeout=180);evidence.append({'tool':tool_name,'response':r});(output/'evidence.json').write_text(json.dumps(evidence,indent=2));assert r.get('ok'),r;return r['result']
 async def provider(name,code):
  r=await c.create_box(name=name,x=50,y=50,w=300,h=80,timeout=90);uid=r['box']['uid'];result=await call('RunBoxRequest',box=uid,code=code,timeoutSeconds=120);assert result['success'],result;return uid
 source=(root/'dev_docs/agents/tool-pool/bounds.kt').read_text();digest=hashlib.sha256(source.encode()).hexdigest()
 # Exercise the ordinary provider against an isolated shared pool. Verification
 # notes and curated fixture versions must not accumulate in the user's library.
 pool_path=json.dumps(str(output/('pool-'+suffix))).replace('$','\\$')
 pool_source=(root/'dev_docs/agents/tool-pool/tools.kt').read_text().replace('val pool=ToolPool()',f'val pool=ToolPool(java.io.File({pool_path}))')
 try:
  await c.ensure_server();await c.connect();await c.open_document('tool-pool-source-'+suffix,scratch=False,timeout=120,keep_running=True)
  await provider('Tool pool',pool_source);uid=await provider('Bounds provider',source)
  untested=await call('pool.inspect',name='geometry.bounds3');assert untested[0]['checks'] is None
  verdict=await call('pool.test',name='geometry.bounds3',sourceHash=digest);assert verdict['passed'],verdict
  assert len(verdict['checks'])==3 and sum('Rejected as expected:' in check['message'] for check in verdict['checks'])==2,verdict
  entry=await call('pool.publish',boxId=uid,key='geometry-bounds-example',description='Executable example: axis-aligned bounds, no external dependencies or project state. Test in each destination.',sourceHash=digest)
  assert entry['checks']['geometry.bounds3']['tests'][0]['passed']
  await c.open_document('tool-pool-reuse-'+suffix,scratch=False,timeout=120,keep_running=True)
  await provider('Tool pool',pool_source)
  imported=await call('pool.read',key=entry['key'],revision=entry['revision']);assert imported['source']==source
  prior_checks=imported['checks']
  note=await call('pool.note',key=entry['key'],revision=entry['revision'],sourceHash=digest,text='Verification: destination checks must be rerun; source evidence is historical.',actor='Tool pool verification',evidence=[str(output/'evidence.json')])
  rejected=await c.ai_tool_call(name='pool.note',arguments=dict(key=entry['key'],revision=entry['revision'],sourceHash='0'*64,text='This stale write must fail',actor='Verification',evidence=[]),timeout=90)
  evidence.append({'expectedStaleNoteFailure':rejected});assert not rejected.get('ok'),rejected
  reread=await call('pool.read',key=entry['key'],revision=entry['revision']);assert reread['source']==source and reread['checks']==prior_checks
  assert any(n['id']==note['id'] for n in reread['notes'])
  notes=await call('pool.notes',key=entry['key'],revision=entry['revision'],before='',limit=1);assert notes['notes'][0]['id']==note['id']
  assert notes['nextBefore'] is None
  later=await call('pool.note',key=entry['key'],revision=entry['revision'],sourceHash=digest,text='Additional observation: retain the earlier note when adding a correction.',actor='Tool pool verification',evidence=[])
  latest=await call('pool.notes',key=entry['key'],revision=entry['revision'],before='',limit=1)
  assert latest['total']==2 and latest['notes'][0]['id']==later['id'] and latest['nextBefore']==later['id']
  earlier=await call('pool.notes',key=entry['key'],revision=entry['revision'],before=latest['nextBefore'],limit=1)
  assert earlier['notes'][0]['id']==note['id'] and earlier['nextBefore'] is None
  dest=await provider('Imported bounds',imported['source']);checks=await call('pool.inspect',name='geometry.bounds3');assert checks[0]['checks'] is None,'Original checks must not certify destination'
  verdict=await call('pool.test',name='geometry.bounds3',sourceHash=digest);assert verdict['passed'],verdict
  assert len(verdict['checks'])==3 and sum('Rejected as expected:' in check['message'] for check in verdict['checks'])==2,verdict
  result=await call('geometry.bounds3',points=[[2,8,1],[-4,2,3]]);assert result['min']==[-4.0,2.0,1.0]
  failed=await c.ai_tool_call(name='geometry.bounds3',arguments={'points':[]},timeout=90);evidence.append({'expectedFailure':failed})
  diagnostics=await call('pool.inspect',name='geometry.bounds3');assert any(not x['success'] and 'finite xyz' in x['result'] for x in diagnostics[0]['calls'])
  # Re-register from persisted source: the new tool instance reads the same durable evidence.
  r=await call('RunBoxRequest',box=dest,code=source,timeoutSeconds=120);assert r['success']
  diagnostics=await call('pool.inspect',name='geometry.bounds3');assert diagnostics[0]['checks']['tests'][0]['passed'];assert len(diagnostics[0]['calls'])>=3
  edited=source.replace('BOUNDS_TOOL_READY','BOUNDS_TOOL_CHANGED')
  await call('RunBoxRequest',box=dest,code=edited,timeoutSeconds=120)
  after=await call('pool.inspect',name='geometry.bounds3');assert after[0]['checks'] is None and not after[0]['calls']
  print(json.dumps({'passed':True,'poolRevision':entry['revision'],'sourceHash':digest,'checks':['real binding','cross-project import','destination starts untested','failure evidence','reload evidence','edited source loses green','versioned notes preserve source and checks','stale note rejected','paginated notes']}))
 finally:
  (output/'evidence.json').write_text(json.dumps(evidence,indent=2))
  await c.close();logger.close()

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--port',type=int)
 parser.add_argument('--output',type=Path,default=root/'build/tool-pool-verification')
 parser.add_argument('--attach-existing',action='store_true',help='Use an explicitly selected live server; never stops it')
 args=parser.parse_args()
 if args.attach_existing and args.port is None:parser.error('--attach-existing requires --port')
 port=args.port if args.port is not None else select_automation_port(DEFAULT_AUTOMATION_PORT,explicit=False)
 asyncio.run(verify(port,args.output.resolve(),args.attach_existing))
