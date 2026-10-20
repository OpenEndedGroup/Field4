"""Include the newly landed capture fixture and both live creature starting points."""
from pathlib import Path
import importlib.util
import json
import re
import yaml
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/docs-catchup'
spec=importlib.util.spec_from_file_location('catchup_revisions',HERE/'revise.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
paths=['dev_docs/agents/transcripts/docs_capture/canvas_flight.yaml','dev_docs/agents/transcripts/evolving_water_bugs.yaml'];records=[]
for name in paths:
    path=ROOT/name;before=path.read_text();edits=[]
    def visit(node):
        if isinstance(node,yaml.MappingNode):
            for k,v in node.value:
                if k.value=='code' and isinstance(v,yaml.ScalarNode):
                    code=m.revise(v.value)
                    if name.endswith('evolving_water_bugs.yaml'):
                        code=code.replace('Launch it (Alt-R).','Launch it (Alt+Up).')
                        code=code.replace('import afield.simulation.creatures.*','import afield.simulation.creatures.*\nimport java.util.concurrent.atomic.AtomicReference\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext')
                        pattern=r'val controller = EvolutionController\(\)\nvar champion: CreatureEvaluation\? = null\nvar status = "generation 0"\nkotlin.concurrent.thread\([^\n]*\) \{\n    CreatureEvolution.evolve\(config, controller\) \{ update ->\n        champion = update.champion\n        status = [^\n]*\n    }\n}'
                        replacement='''// Publish one complete update, so the caption and drawing use the same generation.
val latest = AtomicReference<EvolutionUpdate?>(null)
go {
    withContext(Dispatchers.Default) {
        CreatureEvolution.evolveSuspend(config) { latest.set(it) }
    }
}'''
                        code,count=re.subn(pattern,replacement,code);assert count==1
                        for creature in ['bug','crawler']:
                            code=code.replace(f'val {creature} = champion ?: return@loop',f'val update = latest.get() ?: return@loop\n    val {creature} = update.champion\n    val status = "generation ${{update.generation.generation}}: best ${{{creature}.fitness.f2}}"')
                        code=code.replace('}.end { controller.requestStop() }','}')
                        code=code.replace('// Evolution takes seconds per generation, so it runs on its own thread and hands its\n// champion over; the loop below only draws.','// Evolution runs in a cancellable worker; the loop below only draws its latest snapshot.')
                    if name.endswith('canvas_flight.yaml'):
                        code=code.replace('drawing.toMutableList().apply { add(spiral) }','drawing + spiral')
                    if code!=v.value:
                        indent=' '*(k.start_mark.column+2)
                        replacement='|\n'+''.join(indent+l+'\n' if l else '\n' for l in code.rstrip('\n').splitlines())
                        edits.append((v.start_mark.index,v.end_mark.index,replacement))
                else:visit(v)
        elif isinstance(node,yaml.SequenceNode):
            for child in node.value:visit(child)
    visit(yaml.compose(before));after=before
    for start,end,value in reversed(edits):after=after[:start]+value+after[end:]
    after=after.replace('on its own thread; the loop draws the current champion swimming and its foot score.','in a run-owned worker coroutine; the loop draws the latest champion swimming and its foot score.')
    after=after.replace('Pure CPU, no Window and no Renderer. Both boxes are `loop { }`s with an `.end { }` that stops\n  their evolution thread, so they must be launched.', 'Pure CPU, no Window and no Renderer. Both boxes launch a `go { }` search and a `loop { }` drawing.\n  Stopping the box cancels both, so they must be launched.')
    yaml.safe_load(after);assert before!=after
    path.write_text(after);records.append(dict(path=name,before=before,after=after))
(OUT/'related-changes.json').write_text(json.dumps(records,indent=2)+'\n')
scope=json.loads((HERE/'scope.json').read_text());scope['paths']+=paths;(HERE/'scope.json').write_text(json.dumps(scope,indent=2)+'\n')
print('Included',len(records),'related operational transcripts')
