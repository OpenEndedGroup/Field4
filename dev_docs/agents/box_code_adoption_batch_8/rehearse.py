"""Compare actual drawing helpers, then exercise the shipped first-steps launch lifecycle."""
from pathlib import Path
import importlib.util,sys,asyncio,json,re,subprocess
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'dev_docs/agents/box_code_adoption_batch_3'))
spec=importlib.util.spec_from_file_location('prior',ROOT/'dev_docs/agents/box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.ROOT=ROOT;m.BASE='989d5f72e';m.PORT=9700;m.OUT=ROOT/'build/box-code-revision/adoption-8/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
def function(code,name):
 start=code.index('fun '+name+'('); brace=code.index('{',start);depth=1;i=brace+1
 while depth:
  if code[i]=='{':depth+=1
  elif code[i]=='}':depth-=1
  i+=1
 return code[start:i]
async def main():
 results={}
 for label in ['original','revised']:
  for file,name,probe in [
   ('gp_tune','draw','draw(listOf(0.0 to 0.0, 1.0 to 2.0, -3.0 to 4.0), 37.0, vec(1,1,1,1), 2.0)'),
   ('gp_duel','rosette','rosette(mapOf("petals" to 5.0, "depth" to 0.6, "curl" to 0.1, "hue" to 250.0), 30.0)'),
   ('gp_judge','rosette','rosette(mapOf("petals" to 7.0, "depth" to 0.3, "curl" to 0.8, "hue" to 20.0), -30.0)'),
  ]:
   code=m.source('dev_docs/agents/demos/gp_instruments/'+file+'_code_.kt',label)
   code=function(code,name)+'\nval measured = '+probe+'\nlines(measured)'
   value=await m.program(label+'_'+file,code,'check(measured.nodes.first() is FLine.MoveTo); proof(geometry(listOf(measured)))',channel='lines',capture=label=='revised')
   results.setdefault(file,{})[label]=value
 for values in results.values():assert values['original']==values['revised'],values
 # Exercise the unchanged sampling calculations in the actual template, capturing one frame.
 code=(ROOT/'templates/first-steps/first_steps_drawing_code_.kt').read_text()
 code=code.replace('    lines(ribbon,','    check(ribbon.size == 24 && ribbon.all { it.nodes.size == 181 && it.nodes.first() is FLine.MoveTo })\n    lines(ribbon,')
 await m.macro('first_steps_setup',[{'create':{'box':'subject','name':'First steps','frame':{'x':0.0,'y':0.0,'w':400.0,'h':100.0},'properties':{'code':code}}}],True)
 await m.batch('first_steps_live',['launch subject','wait-output subject --channel lines --timeout 30','stop subject',f'screenshot-box subject {m.OUT}/first_steps.png','lifecycle-census subject --expect-stopped','launch subject','wait-output subject --channel lines --timeout 30','stop subject','lifecycle-census subject --expect-stopped'])
 (m.OUT/'equivalence.json').write_text(json.dumps(results,indent=2))
if __name__=='__main__':asyncio.run(main())
