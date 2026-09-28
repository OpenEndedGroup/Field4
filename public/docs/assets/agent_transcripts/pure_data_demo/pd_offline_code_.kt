// No audio device: render one second, measure it, read back what Pd recorded.
val text = """
#N canvas 0 0 400 260 12;
#X obj 20 20 osc~ 440;
#X obj 20 60 *~ 0.25;
#X obj 20 100 dac~;
#X obj 160 100 tabwrite~ scope;
#X obj 160 20 loadbang;
#X obj 260 60 table scope 240;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 1 0 2 1;
#X connect 1 0 3 0;
#X connect 4 0 3 0;
"""
val pd = afield.pd.PdInstance(sampleRate = 48000)
pd.openPatchText(text)
// Not `val (left, _) = …`: a top-level `_` in a destructuring never completes in a box.
val (left, right) = pd.renderOffline(1.0)
val scope = pd.readArray("scope")
pd.close()

val ups = (1 until left.size).count { left[it - 1] < 0f && left[it] >= 0f }
val peak = left.maxOf { abs(it) }
println("$ups upward zero crossings in 1 s, peak ${"%.4f".format(peak)}, channels equal: ${left.contentEquals(right)}")
showSignals("tabwrite~ scope" to scope.toList(), channel = "scope", title = "[table scope], recorded by Pd")
lines(afield.pd.PdPatchGraph.parse(text).toFLines(), channel = "patch", w = 420.0, h = 200.0, fitToSize = true)
