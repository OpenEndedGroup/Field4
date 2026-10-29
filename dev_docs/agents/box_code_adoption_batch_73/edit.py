from pathlib import Path
import json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def save(p,b):
 a=p.read_text()
 if a!=b:p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
def approval(s):
 s='import kotlinx.coroutines.*\n\n'+s
 s=s.replace('kotlin.concurrent.thread {','go {').replace('    loot.replays.await(job.id)','    runInterruptible(Dispatchers.IO) { loot.replays.await(job.id) }\n    ensureActive()').replace('while (loot.pendingProposals().isNotEmpty()) Thread.sleep(200)','while (loot.pendingProposals().isNotEmpty()) delay(200)').replace('    loot.replays.await(loot.test(third.id).id)','    val thirdJob = loot.test(third.id)\n    runInterruptible(Dispatchers.IO) { loot.replays.await(thirdJob.id) }\n    ensureActive()')
 return s
p=R/'public/docs/qualified_sources.md';a=p.read_text();a=a.replace('val second = loot.candidate(', 'val second = loot.candidate(',1)
start=a.rfind('```diffstatic',0,a.index('kotlin.concurrent.thread {'));start=a.index('\n',start)+1;end=a.index('\n```',start);body=a[start:end];body=approval(body);save(p,a[:start]+body+a[end:])
def patch_yaml(p,change):
 a=p.read_text();ed=[]
 def walk(n):
  if isinstance(n,yaml.MappingNode):
   for k,v in n.value:
    if k.value=='code' and isinstance(v,yaml.ScalarNode):
     s=change(v.value)
     if s!=v.value:ed.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*(k.start_mark.column+2)+x+'\n' if x else '\n' for x in s.splitlines())))
    else:walk(v)
  elif isinstance(n,yaml.SequenceNode):
   for x in n.value:walk(x)
 walk(yaml.compose(a));b=a
 for x,y,s in reversed(ed):b=b[:x]+s+b[y:]
 save(p,b)
patch_yaml(R/'public/docs/assets/transcripts/qualified_sources_approval.yaml',approval)
p=R/'public/docs/assets/transcripts/qualified_sources_approval.yaml';save(p,p.read_text().replace('A thread then waits','A run-owned coroutine then waits'))
def corpus(s):
 if 'fun runCorpus()' not in s:return s
 s='import kotlinx.coroutines.*\nimport afield.runtime.lifecycle.RunScope\n\n'+s
 s=s.replace('fun runCorpus() {','fun runCorpus(checkActive: () -> Unit) {').replace('val tiles = seeds.map { seed ->','val tiles = seeds.map { seed ->\n        checkActive()').replace('fun build(stage: String): StudyBuildRecord? {','fun build(stage: String): StudyBuildRecord? {\n            checkActive()')
 s=s.replace('    BoxStudies.show(\n        thisBox,\n        "gallery",\n        "<div style=', '    checkActive()\n    BoxStudies.show(\n        thisBox,\n        "gallery",\n        "<div style=')
 s=s.replace('// Running this box builds every variant; the stages run on their own threads, so Field stays responsive.\nkotlin.concurrent.thread {','''// Stop interrupts the current wait and prevents later variants from being installed.
val ownerRun = RunScope.current()
go {
    val activeContext = currentCoroutineContext()''').replace('        runCorpus()','        runInterruptible(Dispatchers.IO) {\n            RunScope.withCurrent(ownerRun) { runCorpus { activeContext.ensureActive() } }\n        }').replace('    } catch (e: Exception) {','    } catch (cancelled: CancellationException) {\n        throw cancelled\n    } catch (e: Exception) {\n        ensureActive()')
 return s
patch_yaml(R/'dev_docs/agents/transcripts/staged_project.yaml',corpus)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
