// Launch "pluck" first. Every line here talks to the Csound it is playing.
val pluck = csoundOf("pluck").csound

pluck.setChannel("cutoff", 900) // chnget "cutoff" reads it next k-cycle
pluck.setStringChannel("voice", "from Kotlin") // an S channel; Reverb outvalues it back
println("level now: ${"%.4f".format(pluck.channel("level"))}")

// Every channel the orchestra allocated, with the range chn_k declared for it.
pluck.channels().forEach { c ->
    println("${c.name}: ${c.hints?.let { "${it.default} in ${it.min}..${it.max}" } ?: "no hints"}")
}

// Score events with p-fields from Kotlin values: a rising arpeggio, half a second apart.
[57, 60, 64, 69].forEachIndexed { k, note ->
    pluck.event("i \"Pluck\" ${k * 0.5} 0.5 $note -12")
}

// Stop or rerun removes these listeners from the independently playing orchestra.
val run = requireNotNull(RunScope.current())
run.own(pluck.onOutvalue("said") { println("Csound said: $it") })
var n = 0
run.own(
    pluck.onOutvalue("level") { v ->
        if (n++ % 40 == 0) display("level", "<div style='padding:8px'>Reverb rms ${"%.4f".format(v as Double)}</div>")
    },
)
