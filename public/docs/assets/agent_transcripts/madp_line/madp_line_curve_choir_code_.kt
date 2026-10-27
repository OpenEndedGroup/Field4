/*
Curve Choir: one stroke blooms into four related curves. A noise wind warps the stroke;
add() and subtract() push the stroke by the wind's shape and against it, scale() blows up a
halo, and project() squeezes one curve into the other's directions.
*/
eachStroke(trail = 0.1) {
    fun paint(curve: List<Vec2>, hue: Double, alpha: Double, overshoot: Double): FLine {
        val line = smooth(curve)
        line.color = cssHsla(hue + (curve.size - 1) * 2, 0.92, 0.68, alpha)
        if (overshoot > 0) line.overshoot = overshoot
        return line
    }

    draw { t ->
        val base = resample(points, 64)
        val wind =
            warp(base) { p ->
                vec(
                    p.x + driftNoise(p.y * 0.031, t, 0.65) * 20,
                    p.y + driftNoise(p.x * 0.027 + 30, t, 0.52) * 18 + triWave(t * 0.28 + p.x * 0.002) * 8,
                )
            }
        val halo = scale(base, 1.16 + tri01(t * 0.24 + heldNoise(1.7, t, 4.0) * 0.6) * 0.16)
        val rise = add(base, wind)
        val fall = subtract(base, wind)
        val needle = project(rise, halo)
        [
            paint(halo, 12 + t * 18, 0.3, 0.0),
            paint(rise, 200 + t * 14, 0.74, 7.0),
            paint(needle, 82 + t * 10, 0.58, 0.0),
            paint(fall, 318 + t * 16, 0.74, 7.0),
        ]
    }
}
