"""Compare authored helpers in the real Field runtime, including explicit closure differences."""
from pathlib import Path
import importlib.util,sys,asyncio,json,re,yaml
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent/'box_code_adoption_batch_3'))
spec=importlib.util.spec_from_file_location('runtime14',HERE.parent/'box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.ROOT=ROOT;m.BASE='befabee7a';m.PORT=9700;m.OUT=ROOT/'build/box-code-revision/adoption-14/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
rows=json.loads((HERE/'changes.json').read_text());before={r['path']:r['before'] for r in rows}
def source(path,label):return before[path] if label=='original' else (ROOT/path).read_text()
def box(path,label,id):
 return next(op['create']['properties']['code'] for op in yaml.safe_load(source(path,label))['operations'] if 'create' in op and op['create']['box']==id)
def function(code,name):
 start=code.index('fun '+name+'(');brace=code.index('{',start);depth=1;i=brace+1
 while depth:
  if code[i]=='{':depth+=1
  elif code[i]=='}':depth-=1
  i+=1
 return code[start:i]
COMPARE='''
fun points(f:FLine): List<Vec3> {
    var start=vec(0,0,0)
    return f.nodes.map { node ->
        if (node is FLine.MoveTo) start=node.toOrNull!!
        if (node is FLine.Close) start else node.toOrNull!!
    }
}
fun same(a:FLine,b:FLine) {
    val aa=points(a);val bb=points(b)
    check(aa.size==bb.size) { "node count ${aa.size} != ${bb.size}" }
    check(aa.zip(bb).all { (x,y) -> (x-y).length()<0.0001 }) { "outline differs" }
    check(a.color==b.color && a.thickness==b.thickness && a.filled==b.filled && a.stroked==b.stroked)
}
'''
async def main():
 cases=[
 ('sorting_panel','public/docs/sorting_networks_examples.md','panel','panel(30.0,40.0,1030.0,390.0,vec(.08,.14,.2,.55))',''),
 ('boolean_panel','public/docs/boolean_networks_examples.md','panel','panel(30.0,40.0,430.0,430.0,vec(.08,.13,.18,.56))',''),
 ('boolean_curve','public/docs/boolean_networks_examples.md','curve','curve([0.0,2.0,1.0],vec(1,1,1,1),3.0)','val maxY=3.0\n'),
 ('boolean_markers','public/docs/boolean_networks_examples.md','markers','markers([0.0,2.0,1.0],vec(1,1,1,1),5.0)','val maxY=3.0\n'),
 ('color_ring','public/docs/colors_examples.md','ring','ring(100.0,.42,vec(.5,.8,1,1))',''),
 ('morph_dot','public/docs/chain_drawn_morph.md','dot','dot(vec(540,290),100.0,30.0,vec(.1,.45,.85,1))',''),
 ('reticle','dev_docs/agents/transcripts/autodiff_worked_example.yaml','marker','marker(vec(330,150),26.0)',''),
 ]
 results=[]
 for name,path,fn,call,setup in cases:
  funcs=[]
  for label in ['original','revised']:
   code=source(path,label)
   if path.endswith('.yaml'):code=box(path,label,'autodiff_ik')
   funcs.append(function(code,fn).replace('fun '+fn+'(','fun '+label+'(' ,1))
  code=setup+'\n'.join(funcs)+'\n'+COMPARE+'\nval a='+call.replace(fn+'(','original(',1)+'\nval b='+call.replace(fn+'(','revised(',1)+'\nsame(a,b)\nlines(b)'
  await m.program(name,code,channel='lines',capture=True)
  results.append({'helper':name,'coordinateTolerance':0.0001,'styleEqual':True,'actualRuntime':True})
 for path,id,channel,probe in [
  ('dev_docs/agents/transcripts/fline_measure.yaml','fline-measure-helix','lines','proof(geometry([drawn]+frames))'),
 ]:
  values={}
  for label in ['original','revised']:
   values[label]=await m.program(label+'_'+id,box(path,label,id),probe,channel=channel,capture=label=='revised')
  assert values['original']==values['revised'],values;results.append({'box':id,'exactNodeFingerprintEqual':True})
 # Full authored palette bodies and spiral, comparing their exact node fingerprints.
 for name,path,index,probe,channel in [
  ('swatches','public/docs/colors_examples.md',0,'proof(geometry(swatchRow(120.0,hslSet,120.0)+swatchRow(300.0,okhslSet,120.0)))','colors-hues'),
  ('spiral','public/docs/visualizers.md',11,'proof(geometry([spiral]))','lines'),
 ]:
  values={}
  for label in ['original','revised']:
   text=source(path,label)
   # Use the complete parser; visualizers has many states, so locate by its actual declaration.
   spec=importlib.util.spec_from_file_location('states14',HERE.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
   bodies=[text[a:b] for a,b in d.sections(text)]
   code=next(x for x in bodies if 'val spiral = FLine()' in x) if name=='spiral' else bodies[index]
   if name=='spiral':code+='\nlines(spiral)'
   values[label]=await m.program(label+'_'+name,code,probe,channel=channel,capture=label=='revised')
  assert values['original']==values['revised'],values;results.append({'box':name,'exactNodeFingerprintEqual':True})
 # Replay the actual morph transcript and capture its output, with real notation and matching.
 transcript=yaml.safe_load((ROOT/'dev_docs/agents/transcripts/drawn_morph.yaml').read_text())
 await m.macro('actual_morph',transcript['operations'],True)
 await m.batch('actual_morph_capture',['wait-output dm_morph --channel lines --timeout 60',f'screenshot-box dm_morph {m.OUT}/actual_morph.png'])
 (m.OUT/'equivalence.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
