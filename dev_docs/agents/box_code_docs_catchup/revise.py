"""Apply the reviewed catch-up to explicit current docs and authored starting points."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import yaml

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
OUT = ROOT/'build/box-code-revision/docs-catchup'
sys.path.insert(0,str(ROOT))
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
DOCS=module('catchup_doc_sections',HERE.parent/'box_code_adoption_batch_7/format_docs.py')
LITERALS=module('catchup_literals',HERE.parent/'box_code_adoption_batch_6/literals.py')
PAGES=['contour_trees','editing_from_outside','evolving_creatures','geometry_chains','lp_curves','mapper','matrix_profile','morse_smale','optimal_transport','polyvector','principal_curves','topology_tracking','vector_field_topology','temporal_memory','filming_the_canvas']
TRANSCRIPTS=['mapper_explorer','principal_curves_explorer','contour_trees_explorer','polyvector_explorer','morse_smale_explorer','matrix_profile','lp_curves','lp_curves_3d']
PATHS=[f'public/docs/{name}.md' for name in PAGES]+[f'dev_docs/agents/transcripts/{name}.yaml' for name in TRANSCRIPTS]

# Scientific notation and deliberately padded headings retain printf formatting.
REPLACEMENTS={
 '"end\\nmoved %.2f".format(run.netCentroidMotion)': '"end\\nmoved ${run.netCentroidMotion.f2}"',
 '"moved %.2f over %d cycles".format(bug.netCentroidMotion, config.simulation.measuredCycles)': '"moved ${bug.netCentroidMotion.f2} over ${config.simulation.measuredCycles} cycles"',
 '"end\\nmoved %.2f along the floor".format(crawler.displacement.x)': '"end\\nmoved ${crawler.displacement.x.f2} along the floor"',
 '"24 cycles: %.1f along the floor".format(crawler.displacement.x)': '"24 cycles: ${crawler.displacement.x.f1} along the floor"',
 '"tau = sigma = $step\\n$outside of 701 iterates outside; ends %.3f from the vertex".format(end.position.distance(answer))': '"tau = sigma = $step\\n$outside of 701 iterates outside; ends ${end.position.distance(answer).f3} from the vertex"',
 '"the first 24 ellipses\\narea shrinks by %.3f per step".format(shrink)': '"the first 24 ellipses\\narea shrinks by ${shrink.f3} per step"',
 '"c = (1, 0.5, 0.3): four routes to the vertex at (%.2f, %.2f, %.2f)".format(answer.x, answer.y, answer.z)': '"c = (1, 0.5, 0.3): four routes to the vertex at (${answer.x.f2}, ${answer.y.f2}, ${answer.z.f2})"',
 '"ellipsoids 0, 8, 16 and 24; each step keeps %.3f of the volume".format(shrink)': '"ellipsoids 0, 8, 16 and 24; each step keeps ${shrink.f3} of the volume"',
 '"orange: the ${trace.trajectory.samples.size} centres; best feasible centre (%.3f, %.3f, %.3f), optimum (1, 1, 1)".format(best.x, best.y, best.z)': '"orange: the ${trace.trajectory.samples.size} centres; best feasible centre (${best.x.f3}, ${best.y.f3}, ${best.z.f3}), optimum (1, 1, 1)"',
 '"centroid cuts toward (1, 1, 1): volume %.1f → %.4f in ${volumes.size - 1} cuts".format(volumes.first(), volumes.last())': '"centroid cuts toward (1, 1, 1): volume ${volumes.first().f1} → ${volumes.last().f4} in ${volumes.size - 1} cuts"',
 '"ellipsoid\\n${ellipsoid.ellipses.size} ellipses, each %.2f of the last\'s area".format(kotlin.math.sqrt(ellipsoid.ellipses[1].determinant / ellipsoid.ellipses[0].determinant))': '"ellipsoid\\n${ellipsoid.ellipses.size} ellipses, each ${(kotlin.math.sqrt(ellipsoid.ellipses[1].determinant / ellipsoid.ellipses[0].determinant)).f2} of the last\'s area"',
 '"every route ends at %s".format(polygon.optimalVertex(c))': '"every route ends at ${polygon.optimalVertex(c)}"',
 '"every route ends at %s".format(polytope.optimalVertex(c))': '"every route ends at ${polytope.optimalVertex(c)}"',
 '"| %.3f | ${c.vertexCount} |".format(c.attributes["persistence"])': '"| ${c.attributes.getValue("persistence").f3} | ${c.vertexCount} |"',
 '"| ${c.id} | ${end.type.name.lowercase()} ${end.id} | %.3f | ${c.vertexCount} |".format(c.attributes["persistence"])': '"| ${c.id} | ${end.type.name.lowercase()} ${end.id} | ${c.attributes.getValue("persistence").f3} | ${c.vertexCount} |"',
 '"| ${it.dimension} | ${it.birth} | ${it.death} | %.3f |".format(it.persistence)': '"| ${it.dimension} | ${it.birth} | ${it.death} | ${it.persistence.f3} |"',
 '"| y ≈ %.0f | %.0f | %.0f | %.2f | %.3f |".format(c.vertex(c.vertexCount / 2).y, stats.support[i], stats.nEff[i], stats.width[i], stats.standardError[i])': '"| y ≈ ${c.vertex(c.vertexCount / 2).y.f0} | ${stats.support[i].f0} | ${stats.nEff[i].f0} | ${stats.width[i].f2} | ${stats.standardError[i].f3} |"',
}

def fixed_formats(code):
    # Find only the exact fixed-decimal idiom; balanced argument tokens retain nested calls/strings.
    pattern=re.compile(r'"%\.(\d)f"\.format\(')
    edits=[]
    for match in pattern.finditer(code):
        depth=1;end=None
        for token in LITERALS.TOKEN.finditer(code,match.end()):
            if token[0].startswith(('//','/*','"',"'",'`')):continue
            if token[0]=='(':depth+=1
            elif token[0]==')':
                depth-=1
                if depth==0:end=token.start();break
        assert end is not None
        expression=code[match.end():end]
        simple=re.fullmatch(r'[\w.]+(?:\([^()]*\))?',expression)
        replacement=(expression if simple else f'({expression})')+'.f'+match[1]
        edits.append((match.start(),end+1,replacement))
    for start,end,value in reversed(edits):code=code[:start]+value+code[end:]
    return code

def revise(code):
    for old,new in REPLACEMENTS.items():code=code.replace(old,new)
    code=fixed_formats(code)
    code,_=LITERALS.convert(code)
    code=re.sub(r'\b(it|f|line)\.properties\["(pointed|pointSize|color|thickness)"\]\s*=',r'\1.\2 =',code)
    code=code.replace('Launch this one (Alt-R).','Launch this one (Alt+Up).')
    # The table now uses the same numeric helpers as its rows; preserve the raw text layout.
    code=code.replace('**Saddle ${saddle.id}** at (%.1f, %.1f), value %.3f:', '**Saddle ${saddle.id}** at (${saddle.x.f1}, ${saddle.y.f1}), value ${saddle.value.f3}:')
    code=code.replace('""".format(saddle.x, saddle.y, saddle.value))','""")')
    return code

def visit_yaml(node,edits):
    if isinstance(node,yaml.MappingNode):
        for key,value in node.value:
            if key.value=='code' and isinstance(value,yaml.ScalarNode):
                new=revise(value.value)
                if new!=value.value:
                    assert value.style=='|'
                    indent=' '*(key.start_mark.column+2)
                    text=('|\n' if new.endswith('\n') else '|-\n')+''.join(indent+line+'\n' if line else '\n' for line in new.rstrip('\n').splitlines())
                    edits.append((value.start_mark.index,value.end_mark.index,text))
            else:visit_yaml(value,edits)
    elif isinstance(node,yaml.SequenceNode):
        for child in node.value:visit_yaml(child,edits)

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    assert not (OUT/'source-changes.json').exists(),'Already applied; use the recorded scope and formatter receipt to resume'
    records=[]
    for name in PATHS:
        p=ROOT/name;before=p.read_text();edits=[]
        if p.suffix=='.md':
            for start,end in DOCS.sections(before):
                new=revise(before[start:end])
                if new!=before[start:end]:edits.append((start,end,new))
        else:visit_yaml(yaml.compose(before),edits)
        after=before
        for start,end,new in sorted(edits,reverse=True):after=after[:start]+new+after[end:]
        if name.endswith('evolving_creatures.md'):
            after=after.replace('Evolution takes seconds, so run it on a thread and have the loop draw whatever the latest champion is.\nAn `EvolutionController` lets the box stop the search when you stop the box.', 'Evolution takes seconds, so `go` runs the search in a cancellable worker coroutine. An atomic\nreference publishes the latest completed champion to the drawing loop. Stopping or relaunching the\nbox cancels its search; worker failures appear in the editor.')
            old='''val controller = EvolutionController()
var champion: CreatureEvaluation? = null
kotlin.concurrent.thread(isDaemon = true) {
    CreatureEvolution.evolve(config, controller) { champion = it.champion }
}'''
            new='''// The worker publishes complete evaluations; the drawing loop reads one snapshot per frame.
val champion = java.util.concurrent.atomic.AtomicReference<CreatureEvaluation?>(null)
go {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        CreatureEvolution.evolveSuspend(config) { champion.set(it.champion) }
    }
}'''
            assert old in after
            after=after.replace(old,new).replace('val bug = champion ?: return@loop','val bug = champion.get() ?: return@loop').replace('}.end { controller.requestStop() }','}')
            # Keep concurrency details in imports, rather than crowding the live controls.
            marker='// The worker publishes complete evaluations;'
            start=after.rfind('import afield.simulation.creatures.*',0,after.index(marker))
            after=after[:start]+after[start:].replace('import afield.simulation.creatures.*','import afield.simulation.creatures.*\nimport java.util.concurrent.atomic.AtomicReference\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext',1)
            after=after.replace('java.util.concurrent.atomic.AtomicReference<CreatureEvaluation?>','AtomicReference<CreatureEvaluation?>').replace('kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default)','withContext(Dispatchers.Default)')
        if name.endswith('temporal_memory.md'):
            after=after.replace('and keeps a minute of history to a few hundred samples.', 'giving 600–1,800 samples per minute at those rates.')
        if name.endswith('topology_tracking.md'):
            after=after.replace('(a blob of scale σ is about 2σ across)', '(the displayed diameter is four times σ)')
        if p.suffix=='.yaml':yaml.safe_load(after)
        if before!=after:p.write_text(after);records.append(dict(path=name,before=before,after=after))
    (OUT/'source-changes.json').write_text(json.dumps(records,indent=2)+'\n')
    (HERE/'scope.json').write_text(json.dumps(dict(baseline=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),paths=PATHS,sourceChanges=[dict(path=r['path'],beforeSha256=hashlib.sha256(r['before'].encode()).hexdigest()) for r in records]),indent=2)+'\n')
    print('Reviewed source changes:',len(records),'of',len(PATHS),'scoped paths')

if __name__=='__main__':main()
