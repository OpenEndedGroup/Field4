"""Run real geometry tools and downloaded FLine lessons, and compare circle sampling."""
from pathlib import Path
import importlib.util,json,re,asyncio,sys,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig52',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9718;m.OUT=R/'build/box-code-revision/adoption-52/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
PLAIN=re.compile(r':code\s+("(?:\\.|[^"\\])*")');INLINE=re.compile(r'"code"\s+#afield/file-inline\s*\{"path"\s+("(?:\\.|[^"\\])*")\s+"inline"\s+("(?:\\.|[^"\\])*")')
async def main():
 tool=(R/'public/docs/assets/ai_tool_demo/metadata.edn').read_text();code=next(json.loads(x[1]) for x in PLAIN.finditer(tool) if 'GeometryAgentTools.addSearchRoot' in json.loads(x[1]))
 imports='\n'.join(re.findall(r'(?m)^import [^\n]+',code));body=re.sub(r'(?m)^import [^\n]+\n','',code)
 body=body.replace('File(System.getProperty("java.io.tmpdir"), "field-ai-tool-demo")','File('+json.dumps(str(m.OUT))+', "geometry-tool-files")')
 prefix='''val sentinel=java.nio.file.Files.createTempDirectory("box52-existing-root-").toFile()
GeometryAgentTools.addSearchRoot(sentinel)
val previousRoots=GeometryAgentTools.searchRoots()
'''
 if '--resume-curves' not in sys.argv:
  await m.program('preserve-search-roots',imports+'\n'+prefix+body,'''
check(GeometryAgentTools.searchRoots().containsAll(previousRoots))
check(sentinel.canonicalFile in GeometryAgentTools.searchRoots())
val once=GeometryAgentTools.searchRoots();GeometryAgentTools.addSearchRoot(root)
check(GeometryAgentTools.searchRoots()==once)
check(report.vertexCount>0 && report.triangleCount>0)
println("pre-existing roots retained, duplicate root deduplicated, real mesh inspected and image produced")
proof(once.map { it.path })
''')
 text=(R/'public/docs/assets/fline_examples/metadata.edn').read_text();bodies=[json.loads(x[2]) for x in INLINE.finditer(text)];assert len(bodies)==38
 # Reuse matching successful verdicts when recovering from a later lesson failure.
 for i,code in enumerate(bodies):
  name=f'fline-{i:02}'
  path=m.OUT/(name+'.json')
  if '--resume-curves' in sys.argv and path.exists() and json.loads(path.read_text()).get('ok'):
   prior=yaml.safe_load((m.OUT/(name+'.yaml')).read_text())['operations'][0]['create']['properties']['code']
   if prior.rstrip()==code.rstrip():continue
  await m.program(name,code)
 circle=next(c for c in bodies if 'val circle = fline' in c and 'byProjectingOnto' in c);circle=circle[:circle.index('\nlines(')]
 alternative,n=re.subn(r'    for \(i in 0\.\.140\) \{[\s\S]*?\n    }\n    close\(\)', '    this.circle(center.x, center.y, radius, segments = 140)',circle);assert n==1
 compare='''val manual=run {
'''+circle+'''
listOf(circle,messy,projected)
}
val helper=run {
'''+alternative+'''
listOf(circle,messy,projected)
}
val manualPoints=manual[0].nodes.mapNotNull { it.toOrNull }
val helperPoints=helper[0].nodes.mapNotNull { it.toOrNull }
check(manualPoints.size==helperPoints.size+1)
check(manualPoints.zip(helperPoints).all { (a,b)->(a-b).length()<1e-8 })
val noiseDifference=manual[1].nodes.mapNotNull { it.toOrNull }.zip(helper[1].nodes.mapNotNull { it.toOrNull }).maxOf { (a,b)->(a-b).length() }
check(noiseDifference>0.01) {"Expected node-index sampling change: $noiseDifference"}
println("circle helper changes node-index noise by up to $noiseDifference; explicit repeated endpoint retained")
'''
 await m.program('circle-sampling-contract',compare,'proof(noiseDifference)')
 (H/'behavior.json').write_text(json.dumps({'actualGeometryToolsAndCpuImage':True,'existingRootsPreserved':True,'duplicateRootDeduplicated':True,'actualFLineLessons':38,'absoluteScreenshotWrites':0,'circleManualRetained':'Actual helper comparison demonstrates a changed downstream node-index noise result; construction is retained deliberately, not mechanically replaced.','noPublicCapturesReplaced':True},indent=2)+'\n')
asyncio.run(main())
