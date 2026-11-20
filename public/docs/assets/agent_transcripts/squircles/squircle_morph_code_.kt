val styles = [
    SquircleStyle.Superellipse,
    SquircleStyle.SuperellipseCubic,
    SquircleStyle.Chromium,
    SquircleStyle.Figma,
    SquircleStyle.Clothoid,
]
val colors = [
    vec(1.0, 1.0, 1.0, 1.0),
    vec(1.0, 0.55, 0.45, 1.0),
    vec(1.0, 0.8, 0.3, 1.0),
    vec(0.7, 0.6, 1.0, 1.0),
    vec(0.5, 0.8, 1.0, 1.0),
]

// every shape, and its curvature round one corner, at one gauge
fun draw(gauge: Double) {
    val drawn = ArrayList<FLine>()
    for ((i, style) in styles.withIndex()) {
        val left = 20.0 + i * 130
        drawn +=
            squircleFrom(vec(left + 55, 70), 50.0, gauge = gauge, style = style).apply {
                thickness = 3.0
                color = colors[i]
            }
        val profile = squircleCurvatureProfile(style, gauge)
        val length = profile.last().x
        drawn +=
            polylineFrom(profile.map { vec(left + it.x / length * 110, 260 - it.y * 15) }).apply {
                thickness = 2.0
                color = colors[i]
            }
        drawn += polylineFrom([vec(left, 260), vec(left + 110, 260)]).apply { color = vec(1.0, 1.0, 1.0, 0.3) }
    }
    lines(drawn, w = 660.0, h = 280.0, fitToSize = true)
    print("gauge ${gauge.f3} = superellipse n ${squircleExponent(gauge).f2}")
}

val start = squircleGauge(3.0)
slider("Gauge", min = 0.708, max = 0.95, initialValue = start) { draw(it) }
draw(start)
