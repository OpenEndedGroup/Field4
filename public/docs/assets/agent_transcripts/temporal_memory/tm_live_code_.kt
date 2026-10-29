import afield.math.oracle.*

/*
Two coupled oscillators as the memory. Launch this box, tick "take over x" and drag the
slider: x follows your hand, y keeps playing from memory and realigns to what you do.
Untick to give x back. The panel draws the last ten seconds of (x, y) as a trail.
*/
val rate = 20.0
val n = (45 * rate).toInt()
val demo = Trajectory.of(
    rate,
    "x" to
        n {
            val t = it / rate
            sin(2 * PI * 0.5 * t) * (1 + 0.3 * sin(2 * PI * 0.05 * t))
        },
    "y" to
        n {
            val t = it / rate
            0.7 * sin(2 * PI * 0.75 * t + 0.6)
        },
)
val player = Improviser(VariableMarkovOracle.build(demo), seed = 3)

var hold = false
var value = 0.0
checkbox("take over x", channel = "take over") { hold = it }
slider("x", min = -1.5, max = 1.5, initialValue = 0.0) { value = it }

val trail = ArrayDeque<DoubleArray>()
frames {
    // frames ticks far faster than the memory's 20 Hz, so step on a wall clock
    val period = (1e9 / rate).toLong()
    var next = System.nanoTime()
    var lastDraw = 0L
    while (frame()) {
        val now = System.nanoTime()
        while (now >= next) {
            if (hold) player.perform("x", value) else player.release("x")
            trail.addLast(player.step())
            if (trail.size > 200) trail.removeFirst()
            next += period
        }
        if (now - lastDraw > 100_000_000 && trail.size > 2) {
            lastDraw = now
            val path =
                FLine()
                    .apply {
                        breakNext()
                        trail.forEach { f -> lineTo(f[0] * 150, -f[1] * 150) }
                    }
                    .apply {
                        color = vec(0.48, 0.85, 1.0, 0.9)
                        thickness = 2.0
                    }
            val head =
                FLine()
                    .apply { moveTo(trail.last()[0] * 150, -trail.last()[1] * 150) }
                    .apply {
                        stroked = false
                        pointed = true
                        pointSize = 6.0
                        color = if (hold) vec(1, 0.5, 0.2, 1) else vec(1, 1, 1, 1)
                    }
            lines(path, head, channel = "trail", w = 420.0, h = 320.0)
        }
    }
}
