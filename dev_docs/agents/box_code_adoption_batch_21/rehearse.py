"""Run authored corpus readers, invalid controls, live histories and browser button callbacks."""
from pathlib import Path
import importlib.util,asyncio,json,yaml,subprocess,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig21',H.parent/'box_code_adoption_batch_20/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.OUT=R/'build/box-code-revision/adoption-21/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 records=[]
 pid=subprocess.check_output(['lsof','-nP','-iTCP:9700','-sTCP:LISTEN','-t'],text=True).strip()
 for name in ['corpus','classify']:
  path=R/f'dev_docs/agents/demos/completion_rank_at_scale/{name}_code_.kt';code=path.read_text();hashes=[]
  for i in range(3):
   hashes.append(await m.program(name+str(i),code,'proof(report)'))
   opened=subprocess.run(['lsof','-a','-p',pid,str(R/'src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz')],capture_output=True,text=True)
   (m.OUT/f'{name}{i}-handles.txt').write_text(opened.stdout+opened.stderr)
   assert opened.returncode==1 and not opened.stdout,opened.stdout
  assert len(set(hashes))==1
  records.append({'reader':name,'actualRuns':3,'reportHash':hashes[0],'corpusDescriptorsRemaining':0})
 path=R/'dev_docs/agents/demos/pattern_condense/condense_invents_nothing_code_.kt';code=path.read_text()
 await m.program('condense_default',code,'check(wrong==0);proof(listOf(named,wrong,median,structured))',capture=True)
 for i,(density,trials,message) in enumerate([(0,300,'density'),(-1,300,'density'),(3,0,'trials'),(3,-1,'trials')]):
  altered=code.replace('val density = 3',f'val density = {density}').replace('val trials = 300',f'val trials = {trials}').replace('import kotlin.random.Random','')
  wrapper='import kotlin.random.Random\nvar rejected=false\ntry {\n'+altered+'\n} catch(t:IllegalArgumentException) { check(t.message?.startsWith("'+message+' must be positive")==true);rejected=true }\ncheck(rejected);println("invalid control rejected before search")'
  await m.program('invalid_'+str(i),wrapper)
 records.append({'condenseDefaultWrong':0,'invalidControlsRejected':['zero density','negative density','zero trials','negative trials']})
 ops=yaml.safe_load((R/'dev_docs/agents/transcripts/inertial_movement.yaml').read_text())['operations']
 bodies=[o['create']['properties']['code'] for o in ops if o.get('create',{}).get('box') in ['inertial_simple','inertial_vs_restarting']]
 # Select by actual bounded-history declaration, independent of the authored box aliases.
 bodies=[o['create']['properties']['code'] for o in ops if 'val target = mutableListOf<Double>()' in o.get('create',{}).get('properties',{}).get('code','')]
 assert len(bodies)==2
 for i,body in enumerate(bodies):
  limit=240 if i==0 else 300;names=['target','seen'] if i==0 else ['target','reSolved','fromRest'];checks=' && '.join(f'{x}.size == minOf(frameNumber.toInt()+1,{limit})' for x in names)
  injected='''check(CHECKS)
    if(frameNumber>=360)display("proof","bounded histories passed beyond capacity")
    showSignals('''.replace('CHECKS',checks)
  code=body.replace('showSignals(',injected)
  await m.macro('histories_'+str(i),[{'create':{'box':'subject','properties':{'code':code}}},{'launch':{'box':'subject'}}],True)
  await m.batch('histories_'+str(i)+'_assert',['wait-output subject --channel proof --contains passed --timeout 30','stop subject','lifecycle-census subject --expect-stopped'])
  records.append({'liveHistory':i,'bound':limit,'ranBeyondFrame':360})
 ops=yaml.safe_load((R/'dev_docs/agents/transcripts/control_surface_laws.yaml').read_text())['operations'];body=next(o['create']['properties']['code'] for o in ops if 'val log = mutableListOf<String>()' in o.get('create',{}).get('properties',{}).get('code',''))
 await m.program('button',body)
 script=m.OUT/'button-events.js';script.write_text('''const b=document.querySelector('.field-button');if(!b)throw Error('missing gate');for(let i=0;i<10;i++){b.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0}));window.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0}));}return 'sent ten real press/release pairs';''')
 await m.batch('button_events',[f'page-eval --body --file {script} --wait-ms 1000'])
 await m.macro('button_assert',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'check(log==listOf("gate down","gate up","gate down","gate up","gate down","gate up")) { log.toString() };println("last six real gate events retained")'}}])
 records.append({'buttonPressReleasePairs':10,'retainedEvents':6})
 (m.OUT/'verification.json').write_text(json.dumps(records,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
