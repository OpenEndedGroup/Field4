import afield.geometry.conformal.*

// One small circle under the map z ↦ z + μ·z̄, the simplest map with a given μ.
// The size of μ sets how long the ellipse is; its angle sets which way it points.
var size = 0.4
var degrees = 30.0

fun draw() {
    val angle = degrees * PI / 180
    val mr = size * cos(angle)
    val mi = size * sin(angle)
    // z + μ z̄ for z = x + iy.
    fun map(x: Double, y: Double) = doubleArrayOf(x + mr * x + mi * y, y + mi * x - mr * y)
    fun sx(x: Double) = 320 + 110 * x
    fun sy(y: Double) = 180 - 110 * y
    fun curve(thick: Double, isGuide: Boolean, at: (Double) -> DoubleArray) = fline {
        for (s in 0..96) {
            val p = at(2 * PI * s / 96)
            if (s == 0) moveTo(sx(p[0]), sy(p[1])) else lineTo(sx(p[0]), sy(p[1]))
        }
        thickness = thick
        color = if (isGuide) vec(1.0, 1.0, 1.0, 0.35) else vec(0.4, 0.9, 1.0, 1.0)
    }
    val drawn = mutableListOf(
        curve(1.5, true) { doubleArrayOf(cos(it), sin(it)) },
        curve(3.0, false) { map(cos(it), sin(it)) },
    )
    // The direction stretched most, and the one squeezed most: half the angle of μ, and a right angle from it.
    val long = map(cos(angle / 2), sin(angle / 2))
    val short = map(-sin(angle / 2), cos(angle / 2))
    drawn += fline {
        moveTo(sx(-long[0]), sy(-long[1]))
        lineTo(sx(long[0]), sy(long[1]))
        thickness = 2.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    drawn += fline {
        moveTo(sx(-short[0]), sy(-short[1]))
        lineTo(sx(short[0]), sy(short[1]))
        thickness = 1.0
        color = vec(1.0, 0.6, 0.2, 0.6)
    }
    lines(drawn, channel = "circle", w = 640.0, h = 360.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 360.0))
    markdown(
        "readout",
        "μ = ${size.f2} at ${degrees.f0}°: stretched by **${(1 + size).f2}**, squeezed to **${(1 - size).f2}**, ratio K = **${dilatation(size).f2}**",
    )
}

draw()
controlGroup {
    slider("size", 0.0, 0.9, initialValue = size) {
        size = it
        draw()
    }
    slider("angle", 0.0, 360.0, step = 1.0, initialValue = degrees) {
        degrees = it
        draw()
    }
}
