// The other half. MarfFieldClock.STEPS subdivides each cycle of STAGES: a field
// contributes one stage per onset, and at least one per cycle. So "<0 3> 1" is the
// three-entry sequence it looks like rather than an error, and the bottom lane shows
// the property that makes STEPS a clock rather than a re-spelling of the address —
// the address and the duration walk their OWN grids, one entry each per stage.
displayClearAll()
displayOpen("lines")
displayOpen("key")

val bank = marfBank(
    rampTo(0.1, 1.0), rampTo(0.9, 1.0), holdAt(0.45, 1.0),
    oscTo(0.75, 1.0, cycles = 3.0), curveTo(0.2, 1.0, amount = 4.0)
)

val readers = listOf(
    "<0 3>  STAGES" to bank.read(pat("<0 3>").nums(), fields = MarfFieldClock.STAGES, name = "a"),
    "<0 3> 1  STEPS" to bank.read(pat("<0 3> 1").nums(), fields = MarfFieldClock.STEPS, name = "b"),
    "<0 3> 1 2  STEPS" to bank.read(pat("<0 3> 1 2").nums(), fields = MarfFieldClock.STEPS, name = "c"),
    "<0 3> 1 + dur <1 2>" to bank.read(
        pat("<0 3> 1").nums(),
        duration = pat("<1 2>").nums(),
        fields = MarfFieldClock.STEPS,
        name = "d"
    )
)

val cycles = 12L
val W = 1000.0
val laneH = 74.0
val palette = listOf(
    vec(0.45, 0.82, 1.0, 0.95),
    vec(1.0, 0.60, 0.26, 0.95),
    vec(0.52, 0.90, 0.52, 0.95),
    vec(0.85, 0.62, 1.0, 0.95)
)

val out = mutableListOf<FLine>()
readers.forEachIndexed { lane, entry ->
    val reader = entry.second
    val top = 16.0 + lane * (laneH + 18.0)
    // Dashed verticals are the stage boundaries the step grid laid out.
    reader.stagesIn(PatArc(PatQ.ZERO, PatQ.of(cycles))).forEach { s ->
        val bx = 60.0 + (s.start.toDouble() / cycles) * W
        out += fline {
            moveTo(bx, top); lineTo(bx, top + laneH)
            stroked = true; thickness = 1.0; color = vec(0.40, 0.44, 0.54, 0.85)
        }
    }
    out += fline {
        moveTo(60.0, top + laneH); lineTo(60.0 + W, top + laneH)
        stroked = true; thickness = 1.0; color = vec(0.32, 0.35, 0.43, 0.9)
    }
    out += fline {
        for (i in 0..1200) {
            val t = i / 1200.0 * cycles
            val px = 60.0 + (t / cycles) * W
            val py = top + laneH - reader.value(t) * (laneH - 6.0)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        stroked = true; thickness = 1.8; color = palette[lane]
    }
}
lines(out, w = 1080.0, h = 380.0, fitToSize = true)

val key = StringBuilder()
key.append("**Twelve cycles, top to bottom.** The address each lane actually walked:\n\n")
readers.forEachIndexed { lane, entry ->
    val seq = entry.second.stagesIn(PatArc(PatQ.ZERO, PatQ.of(cycles)))
        .take(8).joinToString(" ") { s -> s.address.toInt().toString() }
    key.append("- `${entry.first}` — `$seq`\n")
}
key.append("\nThe top lane is the one spelling that was always read correctly. Lanes two\n")
key.append("and three are spellings that used to collapse onto it. The bottom lane is the\n")
key.append("same address as lane two against `duration = \"<1 2>\"` — two grids of different\n")
key.append("lengths, each advancing one entry per stage, which is why its boundaries fall\n")
key.append("in different places while its addresses match lane two's exactly.\n")
markdown("key", key.toString())
