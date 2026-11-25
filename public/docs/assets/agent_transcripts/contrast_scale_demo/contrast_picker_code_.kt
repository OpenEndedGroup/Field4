var seed = colorHex("#3b82f6")
var dark = false

fun css(c: Vec4) = "rgb(${(c.x * 255).roundToInt()},${(c.y * 255).roundToInt()},${(c.z * 255).roundToInt()})"

fun redraw() {
    val background = if (dark) colorHex("#0a0a0a") else vec(1.0, 1.0, 1.0, 1.0)
    val scale = contrastScale(seed, background)
    val cells =
        scale.colors.indices.joinToString("") { i ->
            val step = contrastScaleSteps[i]
            val ring =
                if (step == scale.seedStep) "outline:2px solid ${css(scale.textOn[i])};outline-offset:-4px;" else ""
            "<div style='width:60px;height:60px;background:${css(scale.colors[i])};color:${css(scale.textOn[i])};$ring" +
                "font:600 11px system-ui;padding:6px;box-sizing:border-box'>$step<br>${scale.swatches[i].achieved.f2}</div>"
        }
    display("scale", "<div style='background:${css(background)};padding:12px;display:flex;gap:3px'>$cells</div>")
}

colorPicker("seed", Vector4f(seed.x, seed.y, seed.z, 1f)) { c ->
    seed = vec(c.x, c.y, c.z, 1.0)
    redraw()
}
checkbox("dark background", dark, channel = "background") { on ->
    dark = on
    redraw()
}
redraw()
