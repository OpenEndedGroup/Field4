"""Run real labels, full native meshing/chart output and two isolated keyframe browsers."""
from pathlib import Path
import asyncio,importlib.util,json,sys,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig41',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9707;m.OUT=R/'build/box-code-revision/adoption-41/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'));from format_docs import sections
from docs_hooks import extract_static_code

def body(page,needle):
 t=(R/'public/docs'/page).read_text();found=[t[a:b] for a,b in sections(t) if needle in t[a:b]];assert len(found)==1,(page,len(found));return extract_static_code(lines=found[0].splitlines())

async def main():
 graph=body('datalog.md','fun label(i: Int)')
 await m.program('numbered-reachability',graph,'''check(n==7 && links.size==8 && implied.size==34)
check((0 until n).all { label(it).isNotEmpty() })
check(labels.isNotEmpty())
proof("all seven node numbers; 8 stated and 34 implied edges")''',channel='lines',capture=True)
 failed=graph.replace('val layout = layoutText', 'check(i != 3) { "controlled label generation failure" }\n    val layout = layoutText',1);assert failed!=graph
 saved=m.OUT/'label-failure.yaml'
 retained=('--resume' in sys.argv and (m.OUT/'expected-failure.json').exists() and saved.exists() and yaml.safe_load(saved.read_text())['operations'][0]['create']['properties']['code']==failed+'\n')
 if not retained:
  try:
   await m.program('label-failure',failed)
  except AssertionError:
   d=json.loads((m.OUT/'label-failure.json').read_text());assert d['ok'] is False and 'controlled label generation failure' in str(d)
   (m.OUT/'expected-failure.json').write_text(json.dumps({'expectedLabelFailureReachedCaller':True,'message':'controlled label generation failure'},indent=2)+'\n')
  else:raise AssertionError('label-generation failure was swallowed')
 await m.program('native-corner-chart',body('tetgen.md','fun closedCoordinates('),'''check(corner.clip)
check(corner.marks.size==corners.size+1)
check(corner.marks.all { it.x.size>1 && it.x.first()==it.x.last() && it.y.first()==it.y.last() })
val series=corner.marks.map { it.label }.toSet()
val actual=corner.toFLines().filter { it.label in series }
check(actual.size==corner.marks.size)
val plot=corner.plotRect()
check(actual.flatMap { it.nodes }.mapNotNull { it.toOrNull }.all { p ->
    p.x>=plot.x-1e-6 && p.x<=plot.x+plot.w+1e-6 && p.y>=plot.y-1e-6 && p.y<=plot.y+plot.h+1e-6
})
check(tet.cellCount>0 && kotlin.math.abs(tet.volume()-24.0)<1e-8)
proof("complete closed contours; native Chart marks clipped to axes; TetGen volume=24")''',channel='output',capture=True)
 browser=body('keyframer_examples.md','Files.createTempDirectory("keyframer-docs-")')
 original=None;snapshot=None
 for i in range(2):
  record=m.OUT/f'directory-{i}.txt'
  probe='''val names=listOf("soft","hot","mid","wide")
check(names.all { name -> scope.loadKey(dir.resolve(name)).values.size==3 && scope.keyVersions(dir.resolve(name)).size==1 })
java.nio.file.Files.writeString(java.nio.file.Path.of(RECORD),dir.toString())
proof("four independent keys with one version and three slots")'''.replace('RECORD',json.dumps(str(record)))
  await m.program('keyframe-private-'+str(i),browser,probe,channel='keyframes',capture=True)
  directory=Path(record.read_text())
  if original is None:
   original=directory;snapshot={p.relative_to(directory):p.read_bytes() for p in directory.rglob('*') if p.is_file()}
  else:
   assert original.parent!=directory.parent
   assert snapshot=={p.relative_to(original):p.read_bytes() for p in original.rglob('*') if p.is_file()}
   (m.OUT/'private-directories.json').write_text(json.dumps({'differentParents':True,'firstKeysAndVersionsUnchanged':True,'firstFiles':len(snapshot)},indent=2)+'\n')
asyncio.run(main())
