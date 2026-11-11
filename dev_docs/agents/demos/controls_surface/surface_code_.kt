var cutoff = 800.0
var orbit = Vector2f(0.3f, 0.2f)
var armed = true
var title = "orbit"
var tint = Vector4f(0.05f, 0.78f, 0.93f, 1f)
fun draw() {
    val radius = 10 + 40 * kotlin.math.log10(cutoff / 20.0) / 3.0
    val x = 150 + orbit.x * 120
    val y = 150 - orbit.y * 120
    val arm = fline {
        moveTo(150, 150)
        lineTo(x, y)
        color = vec(0.075, 0.38, 0.47, 1.0)
    }
    val disc = fline {
        circle(x, y, radius)
        filled = true
        stroked = false
        color = Vector4f(tint)
    }
    val figure: List<FLine> = if (armed) [arm, disc] else [arm]
    lines(
        figure,
        channel = "figure",
        w = 300.0,
        h = 300.0,
        framing = LinesFraming.fixed(0.0, 0.0, 300.0, 300.0),
    )
    markdown("caption", "**$title** · cutoff `${cutoff.toInt()} Hz`")
}
controlGroup("controls") {
    slider("cutoff", min = 20.0, max = 20000.0, initialValue = 800.0, law = ControlLaw.LOGARITHMIC) {
        cutoff = it
        draw()
    }
    vec2Control("orbit", initialValue = Vector2f(0.3f, 0.2f), step = 0.01) {
        orbit = it
        draw()
    }
    checkbox("armed", initialValue = true) {
        armed = it
        draw()
    }
    stringInput("title", initialValue = "orbit") {
        title = it
        draw()
    }
    colorPicker("tint", initialValue = Vector4f(0.05f, 0.78f, 0.93f, 1f)) {
        tint = it
        draw()
    }
}
displayOpen("controls")
draw()
displayOpen("figure")
