// Flip reuse to true, relaunch, and record again. Both paths update the same vectors.
// vec is Field's linalg constructor; set writes into an existing vector.
val reuse = false
val count = 8192
var positions = Array(count) { vec(0f, 0f, 0f) }
var phase = 0f
loop {
    phase += 0.01f
    if (reuse) {
        for (i in positions.indices) positions[i].set(i.toFloat(), phase, 0f)
    } else {
        positions = Array(count) { vec(it.toFloat(), phase, 0f) }
    }
    delay(16)
}
