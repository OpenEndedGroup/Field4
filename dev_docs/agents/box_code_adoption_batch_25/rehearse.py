"""Run actual spectral/statistics lessons with independent numerical acceptance checks."""
from pathlib import Path
import importlib.util,sys,asyncio,json,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;sys.path.insert(0,str(H.parent/'box_code_adoption_batch_3'))
s=importlib.util.spec_from_file_location('rig25',H.parent/'box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.ROOT=R;m.PORT=9700;m.OUT=R/'build/box-code-revision/adoption-25/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('states25',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
def body(path,needle):
 text=(R/path).read_text();return re.sub(r'(?m)^\s*:::(?:fold[^\n]*|endfold)\s*$','',next(text[a:b] for a,b in d.sections(text) if needle in text[a:b]))
async def main():
 code=body('public/docs/spectral_examples.md','val chirpRate =')
 probe='''val peaks = mutableListOf<Double>()
for(start in intArrayOf(0,6976)) {
 val signal=DoubleArray(1024) { j->
  val i=start+j;val t=i/sampleRate.toDouble()
  stream[i].toDouble()-0.25*sin(2.0*PI*900.0*t)-(if(i==n/3 || i==2*n/3)2.0 else 0.0)
 }
 val peak=fft(signal,WindowType.HANN,sampleRate.toDouble()).peakFrequency()
 val expected=startHz+chirpRate*(start+512)/sampleRate
 check(kotlin.math.abs(peak-expected)<35.0) { "FFT peak $peak, expected $expected" }
 peaks+=peak
}
println("early/late chirp FFT peaks: $peaks")
proof(peaks)'''
 await m.program('streaming_features',code,probe)
 code=body('public/docs/statistics_examples.md','val xiNull =')
 probe='''check(agreement<1e-10)
check(dSeries.take(w-1).all { it.isNaN() } && xiSeries.take(w-1).all { it.isNaN() } && absR.take(w-1).all { it.isNaN() })
check(dSeries.drop(w-1).all { it.isFinite() } && xiSeries.drop(w-1).all { it.isFinite() })
check(kotlin.math.abs(dSeries.subList(w,change).average()-0.215)<0.001)
check(kotlin.math.abs(dSeries.subList(change+w,total).average()-0.574)<0.001)
val heldout=java.util.Random(4321)
var falseD=0;var falseXi=0
repeat(200) {
 val nx=DoubleArray(w) { heldout.nextGaussian() };val ny=DoubleArray(w) { heldout.nextGaussian() }
 if(StatsDependence.distanceCorrelation(nx,ny)>threshold)falseD++
 if(StatsDependence.chatterjeeXi(nx,ny)>xiThreshold)falseXi++
}
check(falseD in 2..30 && falseXi in 2..30) { "held-out null exceedances: dCor=$falseD, xi=$falseXi" }
println("CALIBRATION dCor=$threshold xi=$xiThreshold; held-out null exceedances $falseD/200 and $falseXi/200; crossings $dCrossing and $xiCrossing")
proof(listOf(threshold,xiThreshold,falseD,falseXi,agreement))'''
 await m.program('rolling_statistics',code,probe)
 (m.OUT/'verification.json').write_text(json.dumps({'actualStreamingFeatureLesson':True,'chirpEarlyLateFftToleranceHz':35,'actualRollingStatisticsLesson':True,'independentHeldoutNullWindows':200,'perStatisticExceedanceAccepted':[2,30],'batchRollingAgreementTolerance':1e-10},indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
