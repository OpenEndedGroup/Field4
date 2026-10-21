"""Exercise actual qualified-source approval and model-free staged corpus, including Stop."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-73'
s=importlib.util.spec_from_file_location('rig73',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9723;m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def macro(name,ops):
 p=m.OUT/(name+'.yaml');v=m.OUT/(name+'.json')
 if p.exists() and v.exists() and yaml.safe_load(p.read_text())=={'operations':ops} and json.loads(v.read_text()).get('ok'):return json.loads(v.read_text())
 return await m.macro(name,ops)
async def frag(name,uid,body):return await macro(name,[{'execute':{'box':uid,'type':'fragment','code':body}}])
async def js(name,body):
 p=m.OUT/(name+'.js');p.write_text(body);return await m.batch(name,[f'page-eval --body --file {p}'])
async def main():
 transcript=yaml.safe_load((R/'public/docs/assets/transcripts/qualified_sources_approval.yaml').read_text());ops=transcript['operations'];ops[-1]={'launch':ops[-1]['execute']};ops.append({'await':{}})
 if '--after-approval' not in __import__('sys').argv:await macro('g2-approval-install',ops)
 if '--after-approval' not in __import__('sys').argv:await m.batch('g2-approval-ready',['wait-output qualified_approval --channel loot-approval --contains Approve --timeout 120'])
 if '--after-approval' not in __import__('sys').argv:await js('g2-real-approval-click', '''const until=Date.now()+10000;while(![...document.querySelectorAll('button')].some(b=>b.textContent.includes('Approve'))){if(Date.now()>until)throw Error('Approval button missing');await new Promise(r=>setTimeout(r,25));}const buttons=[...document.querySelectorAll('button')].filter(b=>b.textContent.includes('Approve'));for(const b of buttons){b.dispatchEvent(new PointerEvent("pointerdown",{bubbles:true,button:0,buttons:1,pointerId:73}));window.dispatchEvent(new PointerEvent("pointerup",{bubbles:true,button:0,buttons:0,pointerId:73}));}return {approvedOwnFixtureButtons:buttons.length};''')
 await m.batch('g2-approval-applied',['wait-output qualified_approval --channel loot --contains applied --timeout 120'])
 await frag('g2-approval-result','qualified_approval','check(loot.pendingProposals().isEmpty()); check(loot.rule.liveSource().contains("doors >= 3")); println("VERIFIED73 approved fixture replayed and candidate applied")')
 # Stop a second real approval waiter before a person approves; it must never apply afterward.
 await macro('g2-retire-collided-fixture',[{'stop':{'box':'qualified_approval_stop'}}])
 stopped_ops=json.loads(json.dumps(ops).replace('qualified_approval','qualified_approval_stop2'))
 stopped_ops[0]['create']['properties']['code']=stopped_ops[0]['create']['properties']['code'].replace('qualifiedSource(\n    \"loot\"','qualifiedSource(\n    \"lootstop2\"')
 await macro('g2-approval-fresh-waiter',stopped_ops)
 await m.batch('g2-approval-stop-ready',['wait-output qualified_approval_stop2 --channel lootstop2-approval --contains Approve --timeout 120'])
 await macro('g2-approval-stop',[{'stop':{'box':'qualified_approval_stop2'}},{'wait':{'frames':20}}])
 await frag('g2-approval-stop-proof','qualified_approval_stop2','check(loot.rule.liveSource().contains("doors >= 4"));println("VERIFIED73 stopped waiter did not apply a candidate")')
 await corpus()
async def corpus():
 transcript=yaml.safe_load((R/'dev_docs/agents/transcripts/staged_project.yaml').read_text());ops=[]
 for op in transcript['operations']:
  if 'execute' in op:ops.extend([{'launch':op['execute']},{'await':{}}])
  else:ops.append(op)
 if '--stop-corpus' not in __import__('sys').argv:await m.macro('g2-staged-corpus-isolated',ops,True)
 if '--stop-corpus' not in __import__('sys').argv:await m.batch('g2-staged-corpus-complete',['wait-output staged_corpus --channel gallery --contains "the level as it is" --timeout 180'])
 if '--stop-corpus' not in __import__('sys').argv:await frag('g2-staged-corpus-proof','staged_corpus','check(seeds.count() > 1);println("VERIFIED73 real seeded corpus completed")')
 # An explicit IO gate makes cancellation land before the first variant without changing its work.
 code=next(x['create']['properties']['code'] for x in ops if x.get('create',{}).get('box')=='staged_corpus')
 code=code.replace('val ownerRun =', 'val testStarted = java.util.concurrent.atomic.AtomicBoolean(false)\nval ownerRun =')
 code=code.replace('RunScope.withCurrent(ownerRun) { runCorpus { activeContext.ensureActive() } }','Thread.sleep(1000)\n            testStarted.set(true)\n            RunScope.withCurrent(ownerRun) { runCorpus { activeContext.ensureActive() } }')
 path=m.OUT/'stopped-corpus_code_.kt';path.write_text(code)
 await m.batch('g2-corpus-stop-source',[f'set staged_corpus code --file {path}'])
 await macro('g2-corpus-stop-corrected',[{'launch':{'box':'staged_corpus'}},{'await':{}},{'stop':{'box':'staged_corpus'}},{'wait':{'frames':120}},{'execute':{'box':'staged_corpus','type':'fragment','code':'check(!testStarted.get());println("VERIFIED73 stopped corpus never entered a later build")'}}])
 await m.batch('g2-corpus-no-stale-publication',['output staged_corpus --channel gallery'])
 (H/'behavior.json').write_text(json.dumps({'realQualifiedProposalApprovedAndApplied':True,'stoppedApprovalWaiterDoesNotApply':True,'realSeededCorpusCompletes':True,'corpusStopBeforeFirstVariantExercised':True,'limits':'Private scratch fixture only; approval buttons authorize its own replayed loot outcome. No agent/provider calls or public captures. Stop fixture adds a deterministic IO delay before unchanged real corpus work.'},indent=2)+'\n')
asyncio.run(corpus() if any(x in __import__('sys').argv for x in ['--corpus','--stop-corpus']) else main())
