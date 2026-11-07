// Drag the slider: every grid line of family 0 moves by `offset`, and the tiling re-forms.
// Tiles flip in small groups ("phason flips") while the rest stay put.
var offset = 0.0
var symmetry = 5.0

val draw = {
    val n = symmetry.toInt()
    val offsets = defaultDeBruijnOffsets(n).toMutableList()
    offsets[0] += offset
    val t = deBruijnTiling(symmetry = n, offsets = offsets, radius = 5.0, scale = 12.0)
    lines(t.lines(), w = 480.0, h = 480.0, fitToSize = true)
}

slider("family 0 offset", min = -1.0, max = 1.0, step = 0.01, initialValue = 0.0, channel = "offset") {
    offset = it
    draw()
}
slider("symmetry", min = 4.0, max = 12.0, step = 1.0, initialValue = 5.0, channel = "symmetry") {
    symmetry = it
    draw()
}
draw()
