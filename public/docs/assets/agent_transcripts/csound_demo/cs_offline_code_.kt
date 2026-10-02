// One second of a 440 Hz sine, rendered without an audio device, then measured.
val (left, right) = csoundOffline("""
instr 1
  aenv linen 0.25, 0.01, p3, 0.05
  a1 oscili aenv, 440
  outs a1, a1
endin
""", "i1 0 1", wav = java.io.File(System.getProperty("java.io.tmpdir"), "csound_sine.wav"))
val ups = (1 until 48000).count { left[it - 1] < 0f && left[it] >= 0f }
val peak = left.maxOf { abs(it) }
println("$ups upward zero crossings in the first second, peak ${"%.3f".format(peak)}, channels equal: ${left.contentEquals(right)}")
