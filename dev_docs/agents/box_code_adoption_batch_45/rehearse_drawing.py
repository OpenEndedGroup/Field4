"""Measure actual loop increments and old-loop retirement while the real ribbon is drawn."""
from pathlib import Path
import asyncio,importlib.util,json
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-45/drawing';OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('d45',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9716;m.OUT=OUT
async def fragment(name,code):return await m.macro(name,[{'execute':{'box':'drawing','property':'code','type':'fragment','code':code}}])
async def main():
 source=(R/'templates/first-steps/first_steps_drawing_code_.kt').read_text()
 code='val ticks = java.util.concurrent.CopyOnWriteArrayList<Double>()\nvar changeAt = -1\n'+source
 code=code.replace('phase += phaseStep','''phase += phaseStep
    ticks.add(phase)
    if (ticks.size == 8) display("sampled", "first eight iterations")
    if (changeAt >= 0 && ticks.size == changeAt + 8) display("changed", "eight changed iterations")''')
 await m.macro('create',[{'create':{'box':'drawing','name':'live phase step','frame':{'x':0.0,'y':0.0,'w':420.0,'h':100.0},'properties':{'code':code}}}],True)
 await m.batch('launch',['launch drawing','wait-output drawing --channel sampled --contains "first eight" --timeout 30'])
 await fragment('default-step','''check(ticks.take(8).zipWithNext().all { (a,b) -> kotlin.math.abs(b-a-0.045) < 1e-9 })
changeAt = ticks.size
phaseStep = 0.09
println("assignment changes the existing phaseStep binding")''')
 await m.batch('changed-output',['wait-output drawing --channel changed --contains "eight changed" --timeout 30'])
 await fragment('changed-step','''check(ticks.drop(changeAt).take(8).zipWithNext().all { (a,b) -> kotlin.math.abs(b-a-0.09) < 1e-9 })
val previousTicks = ticks
println("measured doubled phase increments")''')
 replacement=code.replace('phaseStep = 0.045 //','phaseStep = 0.09 //')
 await m.macro('persist-edit',[{'edit':{'box':'drawing','property':'code','from':code,'to':replacement}}])
 await m.batch('relaunch',['launch drawing','wait-output drawing --channel sampled --contains "first eight" --timeout 30'])
 await fragment('relaunch-proof','''check(ticks !== previousTicks)
check(ticks.take(8).zipWithNext().all { (a,b) -> kotlin.math.abs(b-a-0.09) < 1e-9 })
val retiredCount = previousTicks.size
println("edited speed retained after relaunch")''')
 # New loop advances while the superseded loop's own history stays fixed.
 await m.batch('visible-ribbon',['wait-output drawing --channel lines --timeout 30',f'screenshot-box drawing {OUT}/ribbon.png'])
 await fragment('retired-loop-proof','''check(previousTicks.size == retiredCount)
println("old loop retired; current ribbon advances")''')
 await m.batch('stop',['stop drawing','wait-running drawing --state stopped --timeout 5'])
 await fragment('stopped-count','val stoppedCount = ticks.size\nprintln("stopped loop sampled")')
 await m.batch('stopped-state',['lifecycle-census drawing --expect-stopped'])
 await fragment('stopped-proof','check(ticks.size == stoppedCount)\nprintln("stop preserves the last drawing without advancing phase")')
asyncio.run(main())
