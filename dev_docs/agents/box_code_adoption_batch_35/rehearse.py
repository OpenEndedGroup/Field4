"""Verify actual native painting/build ownership, including deliberately failing work."""
import asyncio,importlib.util,json,re,sys,yaml
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig35',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9703;m.OUT=R/'build/box-code-revision/adoption-35/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'));from format_docs import sections
ROWS={r['path']:r for r in json.loads((H/'changes.json').read_text())}
def clean(s):return re.sub(r'^\s*:::[^\n]*\n','',s,flags=re.M)
def states(page,label='after'):
 text=ROWS['public/docs/'+page][label] if label=='before' else (R/'public/docs'/page).read_text()
 return [clean(text[a:b]) for a,b in sections(text)]
async def sweeps():
 answers={}
 for label in ['before','after']:
  ss=states('paint_sweeps.md',label)
  columns=[s[s.index('fun column('):s.index('// the same columns')] for s in ss]
  assert len(columns)==26 and len(set(columns))==1
  code=ss[0];code=code[:code.index('// the same columns')]
  code='val allocated = mutableListOf<PaintCanvas>()\nval released = mutableListOf<PaintCanvas>()\n'+code
  code=code.replace('sizeMm = 500.0, seed = 7)','sizeMm = 500.0, seed = 7).also { allocated += it }')
  code=code.replace('c.close()','c.close(); released += c')
  # Persist the actual native image before close; do not read freed native memory.
  marker='return label to imgFromBytes(png)'
  code=code.replace(marker,'java.nio.file.Files.write(java.nio.file.Path.of('+json.dumps(str(m.OUT/(label+'-column.png')))+'), png)\n'+marker)
  answers[label]=await m.program('column-'+label,code+'\nval rendered = column("baseline", Settings())\ndisplay("main",rendered.second)', 'check(allocated.size==1 && released==allocated);proof("all allocated canvases closed")',capture=True)
  if label=='after':
   failed=code.replace('val config = BrushConfig(', 'if (label == "fail") throw IllegalStateException("controlled painting failure")\nval config = BrushConfig(')
   await m.program('column-failure',failed+'\nval failure = runCatching { column("fail",Settings()) }.exceptionOrNull()', 'check(failure?.message=="controlled painting failure");check(allocated.size==1 && released==allocated);proof("native canvas closes after injected failure")')
 from PIL import Image
 a=Image.open(m.OUT/'before-column.png').convert('RGBA');b=Image.open(m.OUT/'after-column.png').convert('RGBA')
 assert a.size==b.size and a.tobytes()==b.tobytes()
 (m.OUT/'pixels.json').write_text(json.dumps({'equalPixels':True,'size':a.size,'sharedColumnCopiesChecked':26},indent=2))
async def gestures():
 text=(R/'dev_docs/agents/transcripts/gesture_paint_replay.yaml').read_text()
 bodies=[o['create']['properties']['code'] for o in yaml.safe_load(text)['operations'] if 'create' in o]
 code=next(c for c in bodies if 'val canvases =' in c)
 imports='\n'.join(x for x in code.splitlines() if x.startswith('import '));code='\n'.join(x for x in code.splitlines() if not x.startswith('import '))
 code=code.replace('canvases += c','canvases += c\nallocated += c').replace('it.close()','it.close(); released += it')
 prefix=imports+'\nval allocated=mutableListOf<PaintCanvas>()\nval released=mutableListOf<PaintCanvas>()\n'
 await m.program('gesture-dimensions',prefix+code,'check(allocated.size==4 && released==allocated);proof("four genuine native canvases closed")',capture=True)
 failed=code.replace('allocated += c','allocated += c\nthrow IllegalStateException("controlled stroke failure")')
 await m.program('gesture-failure',prefix+'val failure=runCatching {\n'+failed+'\n}.exceptionOrNull()', 'check(failure?.message=="controlled stroke failure");check(allocated.size==1 && released==allocated);proof("partially allocated native comparison closes on failure")')
async def solids():
 ss=states('solid_notation.md');vocab=next(s for s in ss if 'fun mesh(e:' in s)
 vocab=vocab.replace('e.buildSolid(q).use','e.buildSolid(q).also { owned += it.solid }.use')
 imports='\n'.join(x for x in vocab.splitlines() if x.startswith('import '));body='\n'.join(x for x in vocab.splitlines() if not x.startswith('import '))
 await m.program('solid-vocabulary',imports+'\nval owned=mutableListOf<afield.geometry.solid.manifold.Solid>()\n'+body,'check(owned.size==6 && owned.all { it.isClosed });proof("six native builds close after copied meshes")',channel='vocab',capture=True)
 helper=body[:body.index('threeGrid(')].replace('build.solid.toTriMesh(normals = true)','throw IllegalStateException("controlled conversion failure")')
 # A throwing helper needs an explicit return type when its inferred result would be Nothing.
 helper=helper.replace('fun mesh(e: SolidExpr) =','fun mesh(e: SolidExpr): TriMesh =')
 await m.program('solid-conversion-failure',imports+'\nval owned=mutableListOf<afield.geometry.solid.manifold.Solid>()\n'+helper+'\nval failure=runCatching { mesh(sphere(1.0)) }.exceptionOrNull()', 'check(failure?.message=="controlled conversion failure");check(owned.size==1 && owned.single().isClosed);proof("native build closes on failed conversion")')
 hybrid=next(s for s in ss if 'part.buildSolid(q).use' in s).replace('part.buildSolid(q).use','part.buildSolid(q).also { owned += it.solid }.use')
 imports='\n'.join(x for x in hybrid.splitlines() if x.startswith('import '));body='\n'.join(x for x in hybrid.splitlines() if not x.startswith('import '))
 await m.program('solid-hybrid',imports+'\nval owned=mutableListOf<afield.geometry.solid.manifold.Solid>()\n'+body,'check(owned.size==1 && owned.single().isClosed);proof("hybrid native build closes after real mesh comparisons")',channel='hybrid',capture=True)
async def main():
 await m.program('native-paint-config','System.setProperty("afield.paint.library", "/Users/marc/.codex/worktrees/box-code-revision-pilot/field4/build/field-paint/release/libfield_paint_jvm.dylib")\ncheck(PaintCanvas.isAvailable)')
 await sweeps();await gestures();await solids()
asyncio.run(main())
