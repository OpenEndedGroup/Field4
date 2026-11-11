/*
The map: a frame named "network" on the drawing layer (Alt+D) and a first set of food
sources as dots inside it. The frame is the whole trail field, so a dot's place in the
frame is its place in the field. Draw more dots (any ellipse) inside the frame, drag
them, delete them: the slime box follows. Re-running this box puts back the dots it
drew and leaves yours alone.
*/
val left = 400.0
val top = 200.0
val side = 400.0
val dots = [
    0.22 to 0.30,
    0.38 to 0.18,
    0.62 to 0.22,
    0.80 to 0.35,
    0.30 to 0.52,
    0.52 to 0.45,
    0.72 to 0.58,
    0.20 to 0.75,
    0.45 to 0.70,
    0.62 to 0.82,
    0.84 to 0.78,
    0.50 to 0.30,
]
notation {
    frame("network", left, top, side, side) {
        for ((u, v) in dots) ellipse(left + u * side - 6, top + v * side - 6, 12, 12, fill = "#ffd8a8")
    }
}
println("${dots.size} food sources drawn in the frame \"network\"")
