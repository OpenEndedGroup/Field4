"""Compare actual authored path bodies and helper geometry; retain deliberate dot tolerances."""
from pathlib import Path
import importlib.util,json,asyncio,re,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig23',H.parent/'box_code_adoption_batch_17/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.OUT=R/'build/box-code-revision/adoption-23/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('docs23',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
rows=json.loads((H/'changes.json').read_text());sources={r['path']:[r['before'],(R/r['path']).read_text()] for r in rows}
def bodies(path,i):return [re.sub(r'(?m)^\s*:::(?:fold[^\n]*|endfold)\s*$','',sources[path][i][a:b]) for a,b in d.sections(sources[path][i])]
def function(code,name):
 start=code.index('fun '+name+'(');end=code.index('\n\n',start);return code[start:end].strip()
async def main():
 p='public/docs/fline_examples.md';records=[]
 for label,needle,probe in [
 ('rings','val rings =','rings'),('ribbons','val ribbons =','ribbons'),('callbacks','val stack =','stack'),
 ('square','val square = fline {','listOf(a,b,c)'),('curve','// A smooth target curve (a circle-ish polyline)','listOf(circle)'),('helix','val drawn = fline {','listOf(drawn)')]:
  hashes=[]
  for i in [0,1]:
   code=next(c for c in bodies(p,i) if needle in c)
   if i==0:code=code.replace('lines([a, b, c],','lines(a, b, c,').replace('lines([circle, messy, projected],','lines(circle, messy, projected,')
   hashes.append(await m.program(label+str(i),code,'proof(geometry('+probe+'))'))
  assert hashes[0]==hashes[1],label;records.append({'body':label,'exactNodeGeometry':True})
 for case,needle,fn,call,tolerance in [
 ('circle','fun circle(cx:','circle','F(0.0,0.0,45.0)',.0001),
 ('boolean_dots','fun circle(cx:','dot','F(0f,0f,vec(1,1,1,1))',.03),
 ('offset_dots','fun star(cx:','dot','F(0f,0f,vec(1,1,1,1))',.03),
 ('star','fun star(cx:','star','F(110.0,100.0)',.0001)]:
  functions=[function(next(c for c in bodies(p,i) if needle in c),fn).replace('fun '+fn+'(','fun '+name+'(',1) for i,name in enumerate(['original','revised'])]
  code='\n'.join(functions)+'\nval a='+call.replace('F(','original(')+'\nval b='+call.replace('F(','revised(')+f'\ncheck(a.sampleByNumber(100).zip(b.sampleByNumber(100)).all {{ (x,y)->(x-y).length() < {tolerance} }})\ncheck(a.filled==b.filled && a.stroked==b.stroked && a.color==b.color)'
  await m.program(case,code);records.append({'helper':case,'sampledCurveTolerance':tolerance,'styleEqual':True})
 p='public/docs/animation_time_model.md'
 for fn,call in [('border','F(vec(1,1,1,1))'),('disc','F(330.0,105.0,15.0,vec(1,1,1,1))')]:
  functions=[function(next(c for c in bodies(p,i) if 'fun border(' in c),fn).replace('fun '+fn+'(','fun '+name+'(',1) for i,name in enumerate(['original','revised'])]
  test='same('+call.replace('F(','original(')+','+call.replace('F(','revised(')+')'
  if fn=='disc':test='val a='+call.replace('F(','original(')+'\nval b='+call.replace('F(','revised(')+'\ncheck(a.sampleByNumber(100).zip(b.sampleByNumber(100)).all { (x,y)->(x-y).length()<0.0001 });check(a.color==b.color && a.filled==b.filled && a.stroked==b.stroked)'
  await m.program('animation_'+fn,'val W=980.0\nval H=290.0\n'+'\n'.join(functions)+'\n'+n.old.COMPARE+'\n'+test)
  records.append({'helper':fn,'sampledCurveAndStyleEqual':True,'duplicateClosingPointRemoved':fn=='disc'})
 (m.OUT/'verification.json').write_text(json.dumps(records,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
