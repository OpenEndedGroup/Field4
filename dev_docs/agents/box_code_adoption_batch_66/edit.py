"""Keep worker results tied to submitted settings, and cancellation off the Runloop."""
from pathlib import Path
import json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def change(code,name):
 if name=='kratos_parameter_study':
  code=code.replace('if (cancelling.get()) error("cancelled")','if (cancelling.get()) throw java.util.concurrent.CancellationException("cancelled")')
  code=code.replace('val optimisation: StudyOptimisation<KratosStudyDemo.Design>?,','val optimisation: StudyOptimisation<KratosStudyDemo.Design>?,\n    val winningField: SimulationResult?,')
  old='''if (command == "grid") StudyOutcome(active.grid(n, n), null)
                    else StudyOutcome(null, active.optimise((n * n / 2).coerceAtLeast(4), seed = 7L))'''
  new='''val landscape = if (command == "grid") active.grid(n, n) else null
                    val optimisation =
                        if (command == "search") active.optimise((n * n / 2).coerceAtLeast(4), seed = 7L) else null
                    if (cancelling.get()) throw java.util.concurrent.CancellationException("cancelled")
                    val winner = landscape?.best ?: optimisation?.best
                    // Re-solving the winner also belongs on this worker, never on the Runloop.
                    val field = winner?.let { active.resultAt(it.coordinates) }
                    if (cancelling.get()) throw java.util.concurrent.CancellationException("cancelled")
                    StudyOutcome(landscape, optimisation, field)'''
  assert old in code;code=code.replace(old,new)
  code=code.replace('val field = study.resultAt(winner.coordinates)','val field = requireNotNull(outcome.winningField)')
  old='                }\n                cancelling.set(false)\n            }\n\n            val text'
  new='                } finally {\n                    progress?.finish()\n                    progress = null\n                }\n                cancelling.set(false)\n            }\n\n            val text'
  assert old in code;code=code.replace(old,new)
  code=code.replace('''    } finally {
        progress?.finish()''','''    } finally {
        cancelling.set(true)
        progress?.finish()''')
 elif name=='kratos_flow_playground':
  code=code.replace('var pending: java.util.concurrent.CompletableFuture<SimulationResult?>? = null','var pending: java.util.concurrent.CompletableFuture<SimulationResult?>? = null\n        var submitted: List<Double>? = null')
  marker='val parameters = FlowParameters('
  assert marker in code
  code=code.replace(marker,'val requested = values.toList()\n                submitted = requested\n                '+marker)
  a=code.index('val requested = values.toList()');b=code.index('val ready = pending',a)
  block=code[a:b].replace('velocity = values[1]','velocity = requested[1]').replace('viscosity = values[2]','viscosity = requested[2]').replace('steps = values[3].toInt()','steps = requested[3].toInt()').replace('body(values[0])','body(requested[0])')
  code=code[:a]+block+code[b:]
  needle='''if (ready != null && ready.isDone) {
                pending = null''';assert needle in code
  code=code.replace(needle,needle+'\n                val solvedSettings = requireNotNull(submitted)\n                submitted = null')
  a=code.index('val ready = pending');b=code.index('\n        val text',a) if '\n        val text' in code[a:] else code.index('\n            frame()',a)
  block=code[a:b].replace('shown[0]','solvedSettings[0]').replace('reynoldsOf(shown)','reynoldsOf(solvedSettings)');code=code[:a]+block+code[b:]
 # Stop is control flow; it must not be displayed as a worker installation failure.
 marker='} catch (failure: Exception) {';at=code.rfind(marker);assert at>=0
 code=code[:at]+code[at:].replace(marker,'} catch (cancelled: java.util.concurrent.CancellationException) {\n        throw cancelled\n    '+marker,1)
 return code
for name in ['kratos_parameter_study','kratos_flow_playground','kratos_thermal_playground']:
 p=R/'dev_docs/agents/transcripts'/(name+'.yaml');a=p.read_text();edits=[]
 def visit(n):
  if isinstance(n,yaml.MappingNode):
   for k,v in n.value:
    if k.value=='code' and isinstance(v,yaml.ScalarNode):
     code=change(v.value,name);indent=k.start_mark.column+2;edits.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*indent+x+'\n' if x else '\n' for x in code.splitlines())))
    else:visit(v)
  elif isinstance(n,yaml.SequenceNode):
   for v in n.value:visit(v)
 visit(yaml.compose(a));b=a
 for x,y,c in sorted(edits,reverse=True):b=b[:x]+c+b[y:]
 assert b!=a;rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
for row in rows:(R/row['path']).write_text(row['after'])
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
