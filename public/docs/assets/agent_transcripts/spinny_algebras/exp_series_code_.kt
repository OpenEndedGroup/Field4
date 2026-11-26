// exp(tε) = 1 + tε + (tε)²/2! + ..., summed with nothing but the algebra's own multiplication.
fun times(p: Vec2, q: Vec2, eps2: Double) = vec(p.x * q.x + eps2 * p.y * q.y, p.x * q.y + p.y * q.x)

fun expSeries(t: Double, eps2: Double, terms: Int): Vec2 {
    var sum = vec(0.0, 0.0)
    var term = vec(1.0, 0.0)
    for (n in 1..terms) {
        sum = sum + term
        term = times(term, vec(0.0, t / n), eps2) // term · tε / n
    }
    return sum
}

val s = 60.0
fun curve(eps2: Double, terms: Int, col: Vec4, ox: Double, width: Double = 1.5) = fline {
    (0..200).forEach {
        val t = Math.PI * (-1.0 + 2.0 * it / 200)
        val p = expSeries(t, eps2, terms)
        if (p.length() < 4.0) {
            if (nodes.isEmpty()) moveTo(ox + s * p.x, -s * p.y) else lineTo(ox + s * p.x, -s * p.y)
        }
    }
    color = col
    thickness = width
}

val axes =
    [0.0, 480.0].flatMap { ox ->
        [
            fline {
                moveTo(ox - 3 * s, 0)
                lineTo(ox + 4 * s, 0)
                color = vec(1, 1, 1, 0.25)
            },
            fline {
                moveTo(ox, -3 * s)
                lineTo(ox, 3 * s)
                color = vec(1, 1, 1, 0.25)
            },
        ]
    }

// Left: the three named algebras, with the series cut off after 3, 5 and 30 terms.
val named =
    [-1.0 to vec(0.35, 0.8, 1.0), 0.0 to vec(0.95, 0.85, 0.3), 1.0 to vec(1.0, 0.45, 0.55)].flatMap { (k, c) ->
        [3 to 0.25, 5 to 0.5, 30 to 1.0].map { (n, a) ->
            curve(k, n, vec(c.x, c.y, c.z, a), 0.0, if (n == 30) 2.0 else 1.0)
        }
    }

// Right: ε² swept continuously from -1 to +1; the circle opens through a line into a hyperbola.
val sweep = (0..20).map {
    val k = -1.0 + 2.0 * it / 20
    val f = it / 20.0
    curve(k, 30, vec(0.35 + 0.65 * f, 0.8 - 0.35 * f, 1.0 - 0.45 * f, 0.9), 480.0)
}

lines(*(axes + named + sweep).toTypedArray(), w = 760.0, h = 340.0, fitToSize = true)
