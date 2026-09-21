// One bank, three readers. The bank has no position of its own; the readers do.
displayClearAll()          // drop anything a previous run left on another channel
displayOpen("lines")
displayOpen("legend")

val b = marfBank(
    rampTo(0.1, 1.0), rampTo(0.9, 0.5), holdAt(0.4, 2.0),
    oscTo(0.7, 1.0, cycles = 3.0), curveTo(0.2, 0.25, amount = 4.0)
)
val a = b.read(pat("<0 1 2 1 3 4>").nums(), fields = MarfFieldClock.STAGES, name = "A")
val c = b.read(pat("<0 2 4 1 3>").nums().slow(3), fields = MarfFieldClock.STAGES, name = "B")
val g = b.read(saw().slow(8).range(0.0, 4.0), duration = constant(0.25), name = "glide")

// Three lanes at one shared time scale, so reading down a column compares the same instant.
// The dashed verticals are stage boundaries — the structure the pattern algebra laid out.
val cycles = 8L
val W = 1060.0
val laneH = 96.0
val palette = listOf(
    vec(0.45, 0.82, 1.0, 0.95),
    vec(1.0, 0.60, 0.26, 0.95),
    vec(0.52, 0.90, 0.52, 0.95)
)

val out = mutableListOf<FLine>()
listOf(a, c, g).forEachIndexed { lane, reader ->
    val top = 14.0 + lane * (laneH + 20.0)
    reader.stagesIn(PatArc(PatQ.ZERO, PatQ.of(cycles))).forEach { s ->
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
        for (i in 0..800) {
            val t = i / 800.0 * cycles
            val px = 20.0 + (t / cycles) * W
            val py = top + laneH - reader.value(t) * (laneH - 6.0)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        stroked = true; thickness = 1.8; color = palette[lane]
    }
}
lines(out, w = 1100.0, h = 360.0, fitToSize = true)

markdown(
    "legend",
    """
    **One bank, three readers** — eight cycles, top to bottom:

    - <span style="color:#73d1ff">blue</span> — `read "<0 1 2 1 3 4>"`
    - <span style="color:#ff9942">orange</span> — `read slow 3 "<0 2 4 1 3>"`, each address held three stages
    - <span style="color:#85e585">green</span> — `read saw slow 8`, a fractional address blending two slots

    Same five stages in all three. Only the trajectory differs.
    """.trimIndent()
)
