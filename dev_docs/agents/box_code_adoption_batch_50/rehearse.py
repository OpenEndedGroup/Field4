"""Replay the corrected transcript lessons and verify their measured interpretation."""
from pathlib import Path
import importlib.util,asyncio,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig50',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9718;m.OUT=R/'build/box-code-revision/adoption-50/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 doc=yaml.safe_load((R/'dev_docs/agents/transcripts/statistics_examples.yaml').read_text());codes={o['create']['box']:o['create']['properties']['code'] for o in doc['operations'] if 'create' in o}
 await m.program('three-cycle-dependence',codes['stats_dependence'],'''
val sineCase=cases.single { it.first.startsWith("sine") }
check(sineCase.first.contains("3 cycles"))
check(sineCase.second.contentEquals(DoubleArray(n) { sin(3.0*PI*x[it]) }))
val cycles=(3.0*PI*x.last()-3.0*PI*x.first())/(2.0*PI)
check(abs(cycles-3.0)<1e-12)
println("measured x range ${x.first()}..${x.last()}: $cycles sine cycles; table and plots produced")
proof(cycles)
''')
 await m.program('calibrated-rolling-transcript',codes['stats_rolling'],'''
check(agreement<1e-10)
check(dSeries.take(w-1).all { it.isNaN() } && xiSeries.take(w-1).all { it.isNaN() })
check(threshold>0.0 && xiThreshold>0.0 && threshold!=xiThreshold)
check(abs(dSeries.subList(w,change).average()-0.215)<0.001)
check(abs(dSeries.subList(change+w,total).average()-0.574)<0.001)
println("independent statistic references: dCor=$threshold, xi=$xiThreshold; seeded crossings $dCrossing / $xiCrossing; agreement=$agreement")
proof(listOf(threshold,xiThreshold,agreement))
''')
 (H/'behavior.json').write_text(json.dumps({'actualDependenceTranscript':True,'sineCyclesAcrossShownDomain':3,'actualRollingTranscript':True,'separateStatisticNullLevels':True,'fullWindowWarmupRetained':True,'batchIncrementalTolerance':1e-10,'reusedIndependentNullCalibration':'batch25 already exercised the identical page algorithm against 200 held-out windows; this run replays the newly synchronized transcript.'},indent=2)+'\n')
asyncio.run(main())
