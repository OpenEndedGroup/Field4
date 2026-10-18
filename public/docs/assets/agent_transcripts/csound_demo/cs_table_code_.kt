// A wavetable computed in Kotlin, copied into ftable 1, played by poscil, and read back.
val cs =
    afield.audio.csound.CsoundInstance(
        """
giWave  ftgen  1, 0, 4096, 10, 1
instr Play
  asig  poscil  0.3, 220, giWave
  outs    asig, asig
endin
""",
        "i \"Play\" 0 0.1",
    )
val partials = listOf(1.0, 0.0, 0.33, 0.0, 0.2, 0.0, 0.14) // odd harmonics: a square-ish wave
val wave =
    DoubleArray(cs.tableLength(1)) { i ->
        val ph = 2 * PI * i / 4096
        partials.withIndex().sumOf { (k, a) -> a * sin((k + 1) * ph) }
    }
cs.writeTable(1, wave)
val back = cs.table(1)!!
val (left, _) = cs.renderOffline()
cs.close()
println(
    "wrote ${wave.size} values; read back equal: ${wave.contentEquals(back)}; rendered ${left.size} frames, peak ${"%.3f".format(left.maxOf { abs(it) })}",
)
showSignals(
    "ftable 1, read back" to (0 until 4096 step 8).map { back[it] },
    channel = "table",
    title = "ftable 1, written from Kotlin",
)
showSignals("poscil 220 Hz" to (0 until 480).map { left[it] }, channel = "played", title = "what poscil plays from it")
