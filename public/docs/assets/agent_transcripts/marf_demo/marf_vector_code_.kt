// A stage's value need not be a number. One Vector3f reader, drawn as its three components:
// one stage structure carrying three values, so the osc:2 stage bumps all of them at once.
displayClearAll()          // drop anything a previous run left on another channel
displayOpen("lines")

val path = marfBankIn(
    MarfVector3f,
    stageTo(Vector3f(0f, 0f, 0f), 1.0, label = "origin"),
    stageTo(Vector3f(1f, 0f, 0f), 0.5, MarfCurve.Smooth, label = "x"),
    stageTo(Vector3f(1f, 1f, 0f), 1.5, MarfCurve.Exp(3.0), label = "xy"),
    stageTo(Vector3f(0f, 0.25f, 1f), 1.0, MarfCurve.Osc(2.0), label = "z")
).read(pat("<0 1 2 3 2 1>").nums(), fields = MarfFieldClock.STAGES, name = "vec")

val cycles = 6L
val W = 1060.0
val laneH = 96.0
val palette = listOf(
    vec(0.45, 0.82, 1.0, 0.95),
    vec(1.0, 0.60, 0.26, 0.95),
    vec(0.52, 0.90, 0.52, 0.95)
)
val component: List<(Vector3f) -> Double> = listOf({ v -> v.x.toDouble() }, { v -> v.y.toDouble() }, { v -> v.z.toDouble() })

val out = mutableListOf<FLine>()
component.forEachIndexed { lane, pick ->
    val top = 14.0 + lane * (laneH + 20.0)
    // The boundaries are the SAME in all three lanes: one reader, one stage structure.
    path.stagesIn(PatArc(PatQ.ZERO, PatQ.of(cycles))).forEach { s ->
        val bx = 20.0 + (s.start.toDouble() / cycles) * W
        out += fline {
            moveTo(bx, top); lineTo(bx, top + laneH)
            stroked = true; thickness = 1.0; color = vec(0.40, 0.44, 0.54, 0.85)
        }
    }
    out += fline {
        moveTo(20.0, top + laneH); lineTo(20.0 + W, top + laneH)
        stroked = true; thickness = 1.0; color = vec(0.32, 0.35, 0.43, 0.9)
    }
    out += fline {
        for (i in 0..600) {
            val t = i / 600.0 * cycles
            val px = 20.0 + (t / cycles) * W
            val py = top + laneH - pick(path.value(t)) * (laneH - 6.0)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        stroked = true; thickness = 1.8; color = palette[lane]
    }
}
lines(out, w = 1100.0, h = 360.0, fitToSize = true)

markdown(
    "legend",
    """
    **One `Vector3f` reader, three components** — <span style="color:#73d1ff">x</span>,
    <span style="color:#ff9942">y</span>, <span style="color:#85e585">z</span>.

    The stage boundaries fall in the same places in all three lanes, because there is one stage
    structure carrying three values. `signal { it.x.toDouble() }` is how a vector reader reaches
    the rest of the language, which is scalar nearly everywhere.
    """.trimIndent()
)
