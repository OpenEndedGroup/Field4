var cutoff = 800.0
var orbit = Vector2f(0.3f, 0.2f)
var armed = true
var title = "orbit"
var tint = Vector4f(0.05f, 0.78f, 0.93f, 1f)
fun draw() {
    val c = "rgba(${(tint.x * 255).toInt()},${(tint.y * 255).toInt()},${(tint.z * 255).toInt()},${tint.w})"
    val r = 10 + 40 * kotlin.math.log10(cutoff / 20.0) / 3.0
    val dot =
        if (armed) "<circle cx='${150 + orbit.x * 120}' cy='${150 - orbit.y * 120}' r='${r.f1}' fill='$c'/>" else ""
    display(
        "figure",
        """
        <svg width="300" height="300" style="background:#05080a;border:1px solid #136178">
          <line x1="150" y1="150" x2="${150 + orbit.x * 120}" y2="${150 - orbit.y * 120}" stroke="#136178"/>$dot
        </svg>
        <div style="font:18px Iosevka,monospace;color:#0cc8ed">$title  cutoff ${cutoff.toInt()} Hz</div>
    """
            .trimIndent(),
    )
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
