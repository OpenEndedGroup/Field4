// Boxes connected under this one read `level` by name. Run this box, then drag the slider.
prop<Double>(level) = 0.5

controlGroup("parameters") {
    slider("level", min = 0.0, max = 1.0, initialValue = level) { level = it }
}
