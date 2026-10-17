"""Reuse the path comparison harness; cover empty inputs, rotated marks and circle tolerances."""
from pathlib import Path
import importlib.util,json,asyncio,yaml,re,sys,os
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('reuse14',H.parent/'box_code_adoption_batch_14/rehearse.py');old=importlib.util.module_from_spec(s);s.loader.exec_module(old);m=old.m;m.OUT=R/'build/box-code-revision/adoption-17/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
rows=json.loads((H/'changes.json').read_text());sources={r['path']:(r['before'],(R/r['path']).read_text()) for r in rows}
def codes(path,label):
 text=sources[path][label]
 if path.endswith('.yaml'):return [o['create']['properties']['code'] for o in yaml.safe_load(text)['operations'] if 'code' in o.get('create',{}).get('properties',{})]
 s=importlib.util.spec_from_file_location('states17',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d);return [text[a:b] for a,b in d.sections(text)]
def fn(path,label,name):return old.function(next(c for c in codes(path,label) if 'fun '+name+'(' in c),name)
async def main():
 if library:=os.environ.get('AFIELD_PAINT_LIBRARY'):
  await m.program('configure_paint', 'System.setProperty("afield.paint.library", '+json.dumps(str(Path(library).resolve()))+')')
 results=[]
 for name,path,function,call,setup in [
  ('oval','public/docs/paint_gestures.md','oval','oval(300.0,250.0,120.0,170.0)',''),
  ('star','dev_docs/agents/transcripts/fline_exact_booleans.yaml','star','star(110.0,100.0)',''),
  ('reticle','public/docs/autodiff_examples.md','marker','marker(vec(330,150),26.0)',''),
  ('frame','public/docs/autodiff_examples.md','frame','frame()','val boxWidth=340.0\nval boxHeight=250.0\n'),
  ('flow_open','public/docs/flow_matching.md','asClosed','asClosed(points,vec(1,1,1,1),2.0)',''),
  ('flow_closed','public/docs/flow_matching.md','asLine','asLine(points,vec(1,1,1,1),2.0)',''),
 ]:
  funcs=[fn(path,i,function).replace('fun '+function+'(','fun '+v+'(',1) for i,v in enumerate(['original','revised'])]
  loop='''for (points in listOf(emptyList<Vec3>(),listOf(vec(1,2,3)),listOf(vec(1,2,3),vec(4,5,6),vec(-2,3,4)))) { same(CALL_A,CALL_B) }''' if name.startswith('flow_') else 'same(CALL_A,CALL_B)'
  loop=loop.replace('CALL_A',call.replace(function+'(','original(',1)).replace('CALL_B',call.replace(function+'(','revised(',1))
  await m.program(name,setup+'\n'.join(funcs)+'\n'+old.COMPARE+'\n'+loop)
  results.append({'helper':name,'pointTolerance':0.0001,'styleEqual':True})
 # Caught helper approximations are compared on the actual curves, including cubic controls.
 p='dev_docs/agents/transcripts/fline_exact_booleans.yaml'
 for case,radius,tolerance in [('circle',45.0,0.0001),('dots',2.5,0.03)]:
  funcs=[]
  for i,label in enumerate(['original','revised']):
   if case=='circle':
    text=next(c for c in codes(p,i) if 'fun circle(' in c);a=text.index('fun circle(');b=text.index('\n\n',a);f=text[a:b].strip();f=f.replace('fun circle(','fun '+label+'(',1)
   else:
    text=next(c for c in codes(p,i) if 'fun dots(' in c);a=text.index('fun dots(');b=text.index('\n\n',a);f=text[a:b].strip().replace('fun dots(','fun '+label+'(',1)
   funcs.append(f)
  call='LABEL(0.0,0.0,45.0)' if case=='circle' else 'LABEL(FLine().moveTo(0,0),vec(1,1,1,1)).first()'
  code='\n'.join(funcs)+'\nval a='+call.replace('LABEL','original')+'\nval b='+call.replace('LABEL','revised')+f'\ncheck(a.sampleByNumber(100).zip(b.sampleByNumber(100)).all {{ (x,y)->(x-y).length() < {tolerance} }})\ncheck(a.filled==b.filled && a.stroked==b.stroked && a.color==b.color)'
  await m.program(case,code);results.append({'helper':case,'sampledCurveTolerance':tolerance})
 # Mark's world geometry includes its rotation; the former manual translation discarded it.
 path='dev_docs/agents/transcripts/drawn_paint.yaml';revised=re.search(r'fun lower[^\n]+',next(c for c in codes(path,1) if 'fun lower(' in c))[0]
 code='''import afield.ui.notation.Mark
notation { stroke([vec(0,0),vec(20,0)],name="rotated") }
val mark=notation.named("rotated")!!
val rotated=notation.edit(mark) { angle=PI/2 }!!
'''+revised+'''
val points=lower(rotated,40.0).nodes.mapNotNull { it.toOrNull }
check(points.size==2)
check(kotlin.math.abs(points[0].x-10)<0.0001 && kotlin.math.abs(points[0].y-30)<0.0001)
check(kotlin.math.abs(points[1].x-10)<0.0001 && kotlin.math.abs(points[1].y-50)<0.0001)
lines(lower(rotated,40.0))
'''
 await m.program('rotated_mark',code,channel='lines',capture=True);results.append({'helper':'mark_translation','rotatedWorldEndpointsVerified':True})
 # Run the actual paint areas body: the ordinary global rect builds a Rect, so use FLine.rect explicitly.
 code=next(c for c in codes('public/docs/paint_canvas.md',1) if 'canvas.work(FLine().rect' in c)
 await m.program('paint_areas',code,channel='main',capture=True)
 results.append({'box':'paint_areas','nativeRender':True,'capturedRealOutput':True})
 # The contour lesson must retain its exact nodes and hole styles.
 values=[]
 for i,label in enumerate(['original','revised']):
  code=next(c for c in codes('public/docs/typography_examples.md',i) if 'val lines = rings.map' in c)
  code=re.sub(r'(?m)^\s*:::(?:fold[^\n]*|endfold)\s*$','',code)
  values.append(await m.program('typography_'+label,code,'proof(geometry(lines.map { it.first }))'))
 assert values[0]==values[1];results.append({'helper':'typography_contours','exactGeometryEqual':True})
 (m.OUT/'comparisons.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
