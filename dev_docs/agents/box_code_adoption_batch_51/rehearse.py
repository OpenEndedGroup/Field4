"""Exercise real controls and authored band callbacks without claiming GPU/model coverage."""
from pathlib import Path
import importlib.util,asyncio,json,re,yaml,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig51',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9718;m.OUT=R/'build/box-code-revision/adoption-51/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
from format_docs import sections
from verify_tokens import TOKEN

def callback(code,label):
 a=code.index('step.'+label+' =');brace=code.index('{',a);depth=0
 for t in TOKEN.finditer(code,brace):
  if t[0]=='{':depth+=1
  elif t[0]=='}':
   depth-=1
   if depth==0:return code[a:t.end()]
 raise AssertionError(label)
async def measure(alias,code,expected):
 path=m.OUT/(alias+'-controls.json')
 js='''const box=[...document.querySelectorAll('.box')].find(b=>b.uid===ALIAS);
 if(!box)throw new Error('control fixture box missing');
 const values={};
 for(const e of box.querySelectorAll('.field-slider')) values[e.querySelector('.field-label').textContent]=Number(e.dataset.default);
 for(const e of box.querySelectorAll('.field-checkbox')) values[e.querySelector('.field-label').textContent]=e.querySelector('input').checked;
 return JSON.stringify(values);'''.replace('ALIAS',json.dumps(alias))
 script='val result=ui.eval('+json.dumps(js)+');java.io.File('+json.dumps(str(path))+').writeText(result.toString())'
 await m.macro(alias,[{'create':{'box':alias,'name':alias,'properties':{'code':'import afield.ui.output.controlGroup\n'+code}}},{'launch':{'box':alias}},{'wait':{'frames':4}},{'drive':{'box':alias,'script':script}}])
 measured=json.loads(path.read_text());measured=json.loads(measured) if isinstance(measured,str) else measured
 for label,value in expected.items():
  assert label in measured,(alias,label,measured)
  if isinstance(value,bool):assert measured[label] is value,(alias,label,measured[label])
  else:assert abs(measured[label]-value)<1e-6,(alias,label,measured[label],value)
 return {'fixture':alias,'measuredDefaults':measured,'expected':expected}
async def main():
 records=json.loads((H/"behavior.json").read_text())["actualControlGroupAndBrowserDom"] if "--resume-bands" in sys.argv else []
 page=(R/'public/docs/open_dlss_nr.md').read_text();codes=[page[a:b] for a,b in sections(page) if 'var style =' in page[a:b]];assert len(codes)==2
 for i,c in enumerate(codes):
  if "--resume-bands" in sys.argv:break
  a=c.index('var style =');b=c.index(':::endfold',a);ui=c[a:b].replace('if (lab.availability.ready)', 'if (true)')
  for edited in [False,True]:
   current=ui
   changes={'style':'2','tone':'3f','structure':'4f','intensity':'0.6f','animate':'true','red':'0.5f','green':'0.6f','blue':'0.7f'}
   if edited:
    for name,value in changes.items():current,n=re.subn(r'(?m)^var '+name+r' = [^\n]+','var '+name+' = '+value,current);assert n==1,name
   values={'Network style':2 if edited else 1,'Tone':3 if edited else 0,'Structure':4 if edited else 10,'NR intensity (0 = source)':.6 if edited else 1,'Animate source':edited,'Grade red':.5 if edited else .65,'Grade green':.6 if edited else 1.1,'Grade blue':.7 if edited else 1.4}
   records.append(await measure(f'nr-{i}-{int(edited)}',current,values))
 direct=yaml.safe_load((R/'dev_docs/agents/transcripts/direct_lighting.yaml').read_text());code=next(o['create']['properties']['code'] for o in direct['operations'] if o.get('create',{}).get('box')=='direct_light_lab');ui='val sdkReady=true\n'+code[code.index('var useRtxdi ='):code.index('displayOpen("lighting")')].replace('lab.visibility.available','true')
 for edited in [False,True]:
  if "--resume-bands" in sys.argv:break
  current=ui
  if edited:
   for name,value in {'neighbors':'3','lightCount':'6','weight':'0.4f','power':'25f','phase':'1.5','diagnostic':'2'}.items():current,n=re.subn(r'(?m)^var '+name+r' = [^\n]+','var '+name+' = '+value,current);assert n==1,name
  records.append(await measure('light-'+str(int(edited)),current,{'Spatial neighbors':3 if edited else 8,'Area lights':6 if edited else 16,'Effect weight':.4 if edited else 1,'Radiance':25 if edited else 60,'Light phase':1.5 if edited else 0,'View (0: lit, 9: raw)':2 if edited else 0}))
 transcript=yaml.safe_load((R/'dev_docs/agents/transcripts/pattern_particles.yaml').read_text());full=next(o['create']['properties']['code'] for o in transcript['operations'] if o.get('create',{}).get('box')=='pp_compute')
 doc=(R/'public/docs/chain_pattern_particles.md').read_text();doccode=next(doc[a:b] for a,b in sections(doc) if 'var gain = ' in doc[a:b])
 for i,source in enumerate([full,doccode]):
  assignments='\n'.join(callback(source,'band'+str(j)) for j in range(4))
  code='''class BandBindings {
 var band0:()->Float={0f};var band1:()->Float={0f};var band2:()->Float={0f};var band3:()->Float={0f}
}
val step=BandBindings()
val bandEnergy=doubleArrayOf(.1,.2,.3,.4)
var gain=2.4
gain=2.4
'''+assignments
  probe='check(listOf(step.band0(),step.band1(),step.band2(),step.band3()).mapIndexed { j,v->kotlin.math.abs(v-(bandEnergy[j]*gain).toFloat()) }.all {it<1e-6});println("all four authored callbacks read gain=$gain")'
  alias='bands-'+str(i)+('-reset-only' if '--resume-bands' in sys.argv else '')
  await m.macro(alias,[{'create':{'box':alias,'properties':{'code':code}}},{'launch':{'box':alias}},{'execute':{'box':alias,'type':'fragment','code':probe}},{'execute':{'box':alias,'type':'fragment','code':'gain=4.8\n'+probe}}])
  edited=code.replace('\ngain=2.4\n','\ngain=4.8\n')
  await m.macro(alias+'-rerun',[{'edit':{'box':alias,'property':'code','from':code,'to':edited}},{'launch':{'box':alias}},{'execute':{'box':alias,'type':'fragment','code':'check(gain==4.8)\n'+probe}}])
 (H/'behavior.json').write_text(json.dumps({'actualControlGroupAndBrowserDom':records,'authoredCallbackVariants':2,'actualAssignmentFragmentsAndFullRerun':True,'limits':'Controls are isolated from hardware setup; availability branches enabled in the fixture. No NR/RTXDI inference, audio engine or GPU rendering claimed. Band callbacks execute with controlled energy values.'},indent=2)+'\n')
asyncio.run(main())
