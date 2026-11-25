val seeds = ["#3b82f6", "#ef4444", "#22c55e", "#eab308"].map { colorHex(it) }
val white = vec(1.0, 1.0, 1.0, 1.0)
val nearBlack = colorHex("#0a0a0a")

fun css(c: Vec4) = "rgb(${(c.x * 255).roundToInt()},${(c.y * 255).roundToInt()},${(c.z * 255).roundToInt()})"

fun panel(background: Vec4): String {
    val rows =
        seeds.joinToString("") { seed ->
            val scale = contrastScale(seed, background)
            val cells =
                scale.colors.indices.joinToString("") { i ->
                    val step = contrastScaleSteps[i]
                    val ring =
                        if (step == scale.seedStep) "outline:2px solid ${css(scale.textOn[i])};outline-offset:-4px;"
                        else ""
                    "<div style='width:52px;height:52px;background:${css(scale.colors[i])};color:${css(scale.textOn[i])};$ring" +
                        "font:600 11px system-ui;padding:5px;box-sizing:border-box'>$step<br>${scale.swatches[i].achieved.f1}</div>"
                }
            "<div style='display:flex;gap:3px;margin:5px 0'>$cells</div>"
        }
    return "<div style='background:${css(background)};padding:12px'>$rows</div>"
}

display("contrast-scale", "<div style='display:flex;gap:12px'>${panel(white)}${panel(nearBlack)}</div>")
