// The opening at each full f-stop for one lens, drawn to scale. Each is √2 narrower than the
// last, so it has half the area and lets in half the light.
val stops = listOf("1.4", "2", "2.8", "4", "5.6", "8", "11", "16")
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
var chosen = 2

fun draw() {
    val parts =
        stops.indices.map { k ->
            val r = 38.0 / sqrt(2.0).pow(k)
            fline {
                for (s in 0..48) {
                    val t = 2 * PI * s / 48
                    if (s == 0) moveTo(40 + 80 * k + r, 60 + 0.0) else lineTo(40 + 80 * k + r * cos(t), 60 + r * sin(t))
                }
                filled = true
                color = if (k == chosen) orange else cyan
            }
        }
    showPlane(
        parts,
        channel = "openings",
        width = 600,
        height = 112,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 120.0),
        interactive = false,
    )
    val labels = stops.mapIndexed { k, s ->
        "<span style='display:inline-block;width:75px;text-align:center;${if (k == chosen) "color:#ff9933" else ""}'>f/$s</span>"
    }
    val share = "1/${1 shl chosen}"
    markdown(
        "readout",
        "<div style='width:600px'>${labels.joinToString("")}</div>\n\nf/${stops[chosen]} lets in $share of the light of f/1.4",
    )
}

draw()
controlGroup {
    slider("stop", 0.0, 7.0, step = 1.0, initialValue = 2.0) {
        chosen = it.toInt()
        draw()
    }
}
