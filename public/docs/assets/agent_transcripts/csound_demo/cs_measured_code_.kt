// Offline: a Field 1 kHz sine through Csound's butterlp at 500 Hz, and back.
val cs =
    afield.audio.csound.CsoundInstance(
        """
instr Filter
  ain   chnget    "in"
  alow  butterlp  ain, 500
  chnset    alow, "low"
endin
""",
        "i \"Filter\" 0 z",
    )
val node = cs.node(audioIn = mapOf("in" to SineOsc(Param(1000f))))
val (dry, low) = renderSignalsOffline(0.5, SineOsc(Param(1000f)), node.audioOut("low"))
cs.close()

fun rms(x: FloatArray) = sqrt(x.drop(4800).sumOf { it.toDouble() * it } / (x.size - 4800))
val expected = 1.0 / sqrt(1.0 + 16.0) // a 2nd-order Butterworth at twice its cutoff
println(
    "in rms ${"%.4f".format(rms(dry))}, out rms ${"%.4f".format(rms(low))}: gain ${"%.4f".format(rms(low) / rms(dry))}, Butterworth says ${"%.4f".format(expected)}",
)
val window = 4800 until 5040
showSignals(
    "Field sine" to window.map { dry[it] },
    "after Csound's butterlp" to window.map { low[it] },
    x = window.map { it / 48.0 },
    channel = "signals",
    title = "1 kHz in, butterlp 500 Hz, back to Field",
    xTitle = "ms",
)
