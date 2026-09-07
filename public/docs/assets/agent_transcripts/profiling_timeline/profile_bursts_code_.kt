// Sparse, longer CPU bars. Try 800000 iterations, or shorten the quiet interval.
val iterations = 350000
val quietMillis = 240
var checksum = 0.0
frames {
    while (frame()) {
        repeat(iterations) { checksum += kotlin.math.cos(it.toDouble()) }
        delay(quietMillis)
    }
}
