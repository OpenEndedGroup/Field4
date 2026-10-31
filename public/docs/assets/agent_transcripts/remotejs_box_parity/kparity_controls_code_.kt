// {{ remoteAR }}
var freq = 3.0
var square = false

fun redraw() {
    display("main", "<b>${if (square) "square" else "sine"}</b> at ${freq.asDynamic().toFixed(1)} Hz")
}

slider("frequency", min = 1.0, max = 10.0, step = 0.1, initialValue = 3.0) {
    freq = it
    redraw()
}
checkbox("square wave") {
    square = it
    redraw()
}
redraw()
