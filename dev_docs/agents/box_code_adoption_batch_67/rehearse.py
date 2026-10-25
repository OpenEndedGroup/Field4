"""Run real raster animations, measure update cadence and retain the completed WFC picture."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-67'
s=importlib.util.spec_from_file_location('rig67',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9721;m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(label,box,code):return await m.macro(label,[{'execute':{'box':box,'type':'fragment','code':'run {\n'+code+'\n}'}}])
header='''prop<java.util.concurrent.ConcurrentLinkedQueue<Double>>(proofTimes)
proofTimes=java.util.concurrent.ConcurrentLinkedQueue<Double>()
'''
async def main():
 page=(R/'public/docs/wfc_examples.md').read_text();sections=n.m.sections(page)
 wfc=next(m.extract_static_code(lines=page[a:b].splitlines()) for a,b in sections if 'val solver = WfcSolver(44, 30, rules, seed = 3L)' in page[a:b])
 wfc=header+wfc.replace('        display(\n','        proofTimes.add(AnimationClock.nowSeconds())\n        display(\n',1)
 assert 'proofTimes.add' in wfc
 await m.macro('g4-wfc-launch',[{'create':{'box':'g4-wfc','frame':{'x':40.,'y':40.,'w':1200.,'h':100.},'properties':{'code':wfc}}},{'launch':{'box':'g4-wfc'}}])
 for attempt in range(40):
  tag='WFC_SOLVED:'+str(attempt)+'='
  r=await fragment('g4-wfc-status-'+str(attempt),'g4-wfc','println('+json.dumps(tag)+'+(solver.status==WfcStatus.Solved))')
  if any(o.get('html')==tag+'true' for x in r['results'] for o in x.get('data',{}).get('output',[])):break
  await m.macro('g4-wfc-wait-'+str(attempt),[{'wait':{'frames':30}}])
 else:raise AssertionError('actual WFC animation did not solve')
 await fragment('g4-wfc-check','g4-wfc','''check(solver.resolvedFraction()==1.0)
val times=proofTimes.toList()
check(times.size>1)
check(times.zipWithNext().all {(a,b)->b-a>=.049})
val finishedWave=tiles.waveToImg(solver)
val solved=tiles.toImg(requireNotNull(solver.resolvedTileGridOrNull()),44,30)
check(finishedWave.data.zip(solved.data).all {(a,b)->kotlin.math.abs(a-b)<1e-6f})
java.io.File(PATH).writeText(times.joinToString(","))
prop<Int>(finalDisplayCount)
finalDisplayCount=times.size
displayOpen("main")
println("real WFC solution matches its last wave; display cadence bounded")'''.replace('PATH',json.dumps(str(m.OUT/'wfc-times.csv'))))
 await m.macro('g4-wfc-idle',[{'wait':{'frames':60}}])
 await fragment('g4-wfc-final-stable','g4-wfc','check(proofTimes.size==finalDisplayCount);println("solved output no longer rebuilt")')
 await m.batch('g4-wfc-capture',[f'screenshot-box g4-wfc {m.OUT}/wfc-solved.png'])
 doc=yaml.safe_load((R/'dev_docs/agents/transcripts/typography_worked_example.yaml').read_text());box=next(o['create'] for o in doc['operations'] if o.get('create',{}).get('box')=='typo_kinetic');box['box']='g4-kinetic';source=box['properties']['code'];source=header+source.replace('    showImgGrid(\n','    proofTimes.add(elapsed)\n    showImgGrid(\n',1);assert 'proofTimes.add' in source;box['properties']['code']=source
 await m.macro('g4-kinetic-launch',[{'create':box},{'launch':{'box':'g4-kinetic'}},{'wait':{'frames':120}}])
 await fragment('g4-kinetic-check','g4-kinetic','''val times=proofTimes.toList()
check(times.size>=5) {"only ${times.size} real raster updates"}
check(times.zipWithNext().all {(a,b)->b-a>=.049999}) {times.toString()}
java.io.File(PATH).writeText(times.joinToString(","))
displayOpen("main")
println("real kinetic text raster updates are capped while elapsed-time motion continues")'''.replace('PATH',json.dumps(str(m.OUT/'kinetic-times.csv'))))
 await m.batch('g4-kinetic-capture',[f'screenshot-box g4-kinetic {m.OUT}/kinetic.png','stop g4-kinetic'])
 await fragment('g4-kinetic-stopped','g4-kinetic','prop<Int>(stoppedCount);stoppedCount=proofTimes.size')
 await m.macro('g4-kinetic-idle',[{'wait':{'frames':30}}])
 await fragment('g4-kinetic-no-late-frames','g4-kinetic','check(proofTimes.size==stoppedCount);println("Stop halts actual text updates")')
 counts={name:len((m.OUT/(name+'-times.csv')).read_text().split(',')) for name in ['wfc','kinetic']}
 (H/'behavior.json').write_text(json.dumps({'actualRasterUpdates':counts,'maxDisplayFps':20,'wfcSolvedAndFinalWaveMatchesResolvedGrid':True,'noDisplayAfterSolverCompletion':True,'kineticElapsedTimeMotionAndStop':True,'limits':'Complete authored WFC and kinetic bodies; fixture only records producer timestamps and requests private browser captures. No clock or solver/raster output is substituted; public media unchanged.'},indent=2)+'\n')
asyncio.run(main())
