// `level` is not declared here: it is inherited from the box above. Launch this box and
// drag that box's slider.
loop {
    val bar = fline {
        rect(0.0, 0.0, 300.0 * level, 24.0)
        filled = true
        color = vec(0.3, 0.7, 1.0, 1.0)
    }
    lines([bar], w = 300.0, h = 24.0)
}
