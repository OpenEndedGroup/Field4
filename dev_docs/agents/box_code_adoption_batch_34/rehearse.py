"""Run the edited examples through the real Field compiler, SVG output and search evaluator."""
import asyncio, importlib.util, json, re, sys
from pathlib import Path
R=Path(__file__).resolve().parents[3]; H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig34',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n)
m=n.m;m.PORT=9702;m.OUT=R/'build/box-code-revision/adoption-34/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'));from format_docs import sections
def body(page,needle):
 text=(R/'public/docs'/page).read_text()
 found=[text[a:b] for a,b in sections(text) if needle in text[a:b]]
 assert len(found)==1,(page,needle,len(found))
 return re.sub(r'^\s*:::[^\n]*\n','',found[0],flags=re.M)
async def main():
 await m.program('dataframe-chart',body('chart.md','val people ='), 'check(people.rowsCount() == 3);proof("three rows plotted")',channel="output",capture=True)
 code=body('geometry_from_disk.md','GeometryAgentTools.addSearchRoot')
 root=m.OUT/'home';(root/'scans').mkdir(parents=True,exist_ok=True)
 code='val previousHome = System.getProperty("user.home")\nSystem.setProperty("user.home",'+json.dumps(str(root))+')\ntry {\n'+code+code+'\n} finally { System.setProperty("user.home", previousHome) }'
 await m.program('geometry-search-root',code,'check(GeometryAgentTools.searchRoots().count { it == java.io.File('+json.dumps(str(root/'scans'))+').canonicalFile } == 1);proof("canonical root registered once after repeated snippet")')
 await m.program('parser-table',body('polytope_examples.md','fun row(src:'), 'check(cases.size == 13);proof(cases.map { row(it) })',channel='parser',capture=True)
 await m.program('holes-table',body('polytope_examples.md','val refusals ='), 'check(refusals.size == 4);check(refusals.none { it.second.startsWith("no exception") });check(rimTriangles == 8);proof(refusals)',channel='window',capture=True)
 # A pipe and newline exercise escaping through the actual Markdown renderer.
 await m.program('literal-message', '''val message = "bad | face\\nnext **cell**"
markdown("main", "| call | message |\\n|---|---|\\n| invalid | ${message.md} |")''','check(message.md.count { it.code == 92 } == 5);check(message.md.lines().size == 1);proof(message.md)',capture=True)
 text=(R/'public/docs/morpho_search.md').read_text()
 choice=re.search(r'val correct = runs.mapNotNull[^\n]+\nval best =[^\n]+',text)[0]
 code='''import afield.lang.morpho.*
val problem = MorphoProblems.orReduce()
val trials = (1..8).map { MorphoSearch.random(problem.spec, problem.generator, 64, seed = it) }
val runs = trials.filter { it.best != null && !it.best!!.fitness.correct }.sortedWith { a,b -> MorphoIndividual.BY_QUALITY.compare(b.best!!,a.best!!) }
check(runs.size >= 2)
println(trials.map { it.best?.fitness.toString() })
check(MorphoIndividual.BY_QUALITY.compare(runs.first().best!!, runs.last().best!!) > 0)
'''+choice
 await m.program('unsolved-search-choice',code,'''check(correct.isEmpty())
check(best !== runs.first().best)
check(runs.all { MorphoIndividual.BY_QUALITY.compare(best,it.best!!) <= 0 })
println(runs.map { it.best!!.fitness.error })
println("selected error="+best.fitness.error)
proof("actual unsolved candidates use the shared comparator")''')
asyncio.run(main())
