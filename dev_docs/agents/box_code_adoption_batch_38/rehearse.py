"""Compare real sampled paths and inspect the newly exposed conversion/sampling data."""
import asyncio,importlib.util,json,sys,re
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig38',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9703;m.OUT=R/'build/box-code-revision/adoption-38/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'));from format_docs import sections
row=json.loads((H/'changes.json').read_text())[0]
def states(label):
 text=row['before'] if label=='before' else (R/row['path']).read_text()
 return [re.sub(r'^\s*:::[^\n]*\n','',text[a:b],flags=re.M) for a,b in sections(text)]
async def run(name,code,checks):
 at=code.rfind('\n}');assert at!=-1
 await m.program(name,m.PROOF+code[:at]+'\n'+checks+'\n'+code[at:])
 return m.proof_of(json.loads((m.OUT/(name+'.json')).read_text()))
async def main():
 values={}
 for label in ['before','after']:
  ss=states(label);values[label]={}
  if label=='before':
   # Adapt only an existing presentation-call ambiguity, retaining all computed coordinates.
   ss[6]=ss[6].replace('lines([frame, ringGuide, line],','lines(frame, ringGuide, line,')
  for index,value in [(5,'curve'),(6,'line')]:
   values[label][str(index)]=await run('curve-'+str(index)+'-'+label,ss[index],f'proof(geometry(listOf({value})))')
 assert values['before']==values['after'];(m.OUT/'curve-comparison.json').write_text(json.dumps(values,indent=2)+'\n')
 ss=states('after')
 await run('samples-visible',ss[116],'check(pts.size==32 && markers.size==32);check(pts.all { it.y==f.eval(it.x) });proof(pts)')
 await run('converted-visible',ss[124],'check(line.nodes.size==1400);proof(geometry(listOf(line)))')
 await run('peaks-visible',ss[136],'check(peaks.isNotEmpty() && peaks.size<=20 && markers.size==peaks.size);check(peaks.all { it.y==source.eval(it.x) });proof(peaks)')
asyncio.run(main())
