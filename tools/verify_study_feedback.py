"""Capture real build progress and unresolved native solid intersections in Field.

Uses an owned temporary document and the standard build card, runner and AI4 tools.
The explicit publication barrier exists only to make a running-state capture deterministic.
"""
import argparse,asyncio,json,sys,uuid
from pathlib import Path
from field_backdoor import FieldBackdoor,SessionLogger
ROOT=Path(__file__).resolve().parents[1]
SOURCE=r'''
import afield.geometry.solid.manifold.*
val owner=Box.context.get()!!
val builds=studyBuilds("feedback",listOf(
    StudyBuildStage("overlap","Check solid intersections","proof-v1",resultNotes={ job ->
        val volume=(job.facts["overlapMm3"] as Number).toDouble()
        if(volume>0)listOf(StudyBuildNotice("Solids overlap by $volume mm³. Separate them and repeat the check.",StudyBuildNoticeKind.ERROR)) else emptyList()
    }) { c ->
        val a=Solid.cube(100.0,100.0,100.0)
        val b=Solid.cube(100.0,100.0,100.0).translated(90.0,0.0,0.0)
        val overlap=Solid.intersection(listOf(a,b)).volume
        c.file("report.txt").writeText("overlapMm3=$overlap")
        c.progress(.75,"Measured the intersection; retaining its report")
        // Verification barrier only: capture the real running card before publication.
        while(owner.getPropertyRaw("verificationHold","true").toString()=="true") { c.checkCancelled(); Thread.sleep(20) }
        StudyBuildProduct(listOf("report.txt"),mapOf("overlapMm3" to overlap))
    }
)){emptyMap()}
showStudyBuilds("feedback")
'''
async def verify(port,output):
 OUT=output.resolve();OUT.mkdir(parents=True,exist_ok=True)
 log=SessionLogger(OUT/'logs',sys.argv)
 c=FieldBackdoor(port=port,workspace=None,default_timeout=180,logger=log)
 async def tool(tool_name,**kw):
  r=await c.ai_tool_call(name=tool_name,arguments=kw,timeout=180)
  (OUT/(tool_name+'.json')).write_text(json.dumps(r,indent=2));print(tool_name,str(r.get('result'))[:500],flush=True)
  assert r.get('ok') and r.get('result',{}).get('success',True),r
  return r['result']
 try:
  await c.ensure_server();await c.connect()
  await c.open_document('feedback-proof-'+uuid.uuid4().hex[:8],scratch=False,timeout=120,keep_running=True)
  uid=(await tool('NewBoxRequest',name='Build feedback proof'))['uid']
  await c.open_playwright(timeout=90)
  await c.set_property(uid=uid,property_name='code',value=SOURCE,timeout=60)
  await tool('LaunchBoxRequest',box=uid,timeoutSeconds=120)
  s=(await tool('InspectStudyBuildsRequest',boxId=uid,name='feedback'))['details']['stages'][0]
  j=(await tool('StartStudyBuildRequest',boxId=uid,name='feedback',stageId='overlap',expectedFingerprint=s['fingerprint']))['details']['job']['id']
  for _ in range(60):
   r=(await c.ai_tool_call(name='InspectStudyBuildsRequest',arguments=dict(boxId=uid,name='feedback',jobId=j),timeout=30))['result']['details']['job']
   if r['progress']==.75:break
   assert r['status']!='FAILED',r
   await asyncio.sleep(.1)
  assert r['progress']==.75,r
  await asyncio.sleep(1)
  shot=await c.screenshot_box(uid=uid,path=str(OUT/'running.png'),padding=12,pan_into_view=True,open_outputs=True,timeout=60)
  (OUT/'running-capture.json').write_text(json.dumps(shot,indent=2))
  await c.set_property(uid=uid,property_name='verificationHold',value='false',timeout=60)
  r=await tool('WaitStudyBuildRequest',boxId=uid,name='feedback',jobId=j,waitSeconds=60)
  assert r['details']['job']['status']=='SUCCEEDED' and r['details']['resultNotes'][0]['kind']=='ERROR',r
  await asyncio.sleep(1)
  shot=await c.screenshot_box(uid=uid,path=str(OUT/'needs-correction.png'),padding=12,pan_into_view=True,open_outputs=True,timeout=60)
  (OUT/'result-capture.json').write_text(json.dumps(shot,indent=2))
  print('PASSED',flush=True)
 finally:await c.close();log.close()
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--port',type=int,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/study-feedback')
 args=parser.parse_args()
 asyncio.run(verify(args.port,args.output))
