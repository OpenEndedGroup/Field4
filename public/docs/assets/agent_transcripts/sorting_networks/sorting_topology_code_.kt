val bubble = SortingNetwork.bubble(10)
val merge = SortingNetwork.oddEvenMerge(10)

fun shift(lines: List<FLine>, dx: Double, dy: Double) = lines.map { it + vec(dx, dy, 0) }

fun panel(x: Double, y: Double, w: Double, h: Double, color: Vec4) = fline {
    rect(x, y, w, h)
    filled = true
    stroked = true
    thickness = 1.2
    this.color = color
}

val frames = [
    panel(30.0, 40.0, 1030.0, 390.0, vec(0.08, 0.14, 0.20, 0.55)),
    panel(30.0, 500.0, 1030.0, 390.0, vec(0.18, 0.11, 0.07, 0.52)),
]

lines(
    frames +
        shift(bubble.toFLines(wireSpacing = 34.0, layerSpacing = 46.0, margin = 42.0), 70.0, 95.0) +
        shift(merge.toFLines(wireSpacing = 34.0, layerSpacing = 46.0, margin = 42.0), 70.0, 555.0),
    channel = "sorting-topology",
    w = 1120.0,
    h = 940.0,
    fitToSize = true,
)
