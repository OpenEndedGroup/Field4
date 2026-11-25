// Two clickable, keyed rectangles. `lines-real-click.yaml` clicks one with the real (Playwright) mouse, then drags
// across the other to pan: the click must reach its line, the drag must pan and must not click.
val clicked = mutableListOf<String>()
fun drawClickable() {
    val cells = ["left", "right"].mapIndexed { i, name ->
        fline {
            moveTo(20.0 + i * 220.0, 20.0)
            lineTo(220.0 + i * 220.0, 20.0)
            lineTo(220.0 + i * 220.0, 220.0)
            lineTo(20.0 + i * 220.0, 220.0)
            close()
            filled = true
            color = if (name in clicked) vec(0.9, 0.7, 0.2, 1.0) else vec(0.3, 0.5, 0.8, 1.0)
            key = name
            onClick = { _ ->
                clicked += name
                drawClickable()
            }
        }
    }
    lines(cells, channel = "clickable", w = 480.0, h = 240.0, transition = 0.2)
    display("state", "<pre id='lines-real-click-state'>${clicked.joinToString(",")}</pre>")
}
drawClickable()
