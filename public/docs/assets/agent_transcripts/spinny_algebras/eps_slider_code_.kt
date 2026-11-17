// Drag ε² from -1 (complex) through 0 (dual) to +1 (split-complex).
// Left: a grid multiplied by w = 0.9 + 0.5ε. Right: exp(tε) for t from -π to π.
fun times(p: Vec2, q: Vec2, eps2: Double) = vec(p.x * q.x + eps2 * p.y * q.y, p.x * q.y + p.y * q.x)

fun expSeries(t: Double, eps2: Double): Vec2 {
    var sum = vec(0.0, 0.0)
    var term = vec(1.0, 0.0)
    for (n in 1..30) {
        sum = sum + term
        term = times(term, vec(0.0, t / n), eps2)
    }
    return sum
}

fun draw(eps2: Double) {
    val s = 45.0
    val w = vec(0.9, 0.5)
    val grid =
        (-3..3).flatMap { k ->
            listOf((0..60).map { vec(k / 3.0, -1 + it / 30.0) }, (0..60).map { vec(-1 + it / 30.0, k / 3.0) })
        }
    val mapped = grid.map { pts ->
        fline {
            pts.forEachIndexed { i, p ->
                val q = times(w, p, eps2)
                if (i == 0) moveTo(s * q.x, -s * q.y) else lineTo(s * q.x, -s * q.y)
            }
            color = vec(0.4, 0.8, 1.0, 0.8)
        }
    }
    val curve = fline {
        (0..200).forEach {
            val p = expSeries(Math.PI * (-1.0 + 2.0 * it / 200), eps2)
            if (p.length() < 4.0) {
                if (nodes.isEmpty()) moveTo(260 + s * p.x, -s * p.y) else lineTo(260 + s * p.x, -s * p.y)
            }
        }
        color = vec(1.0, 0.8, 0.3, 1.0)
        thickness = 2.5
    }
    val axes = listOf(
        fline {
            moveTo(260 - 3 * s, 0)
            lineTo(260 + 4 * s, 0)
            color = vec(1, 1, 1, 0.25)
        },
        fline {
            moveTo(260, -3 * s)
            lineTo(260, 3 * s)
            color = vec(1, 1, 1, 0.25)
        },
    )
    lines(*(mapped + axes + curve).toTypedArray(), w = 620.0, h = 300.0, fitToSize = true)
}

slider("epsilon²", min = -1.0, max = 1.0, step = 0.01, initialValue = -1.0) { draw(it) }
draw(-1.0)
