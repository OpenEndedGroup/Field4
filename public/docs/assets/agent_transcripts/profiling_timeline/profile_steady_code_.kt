// A small amount of CPU work at roughly 60 Hz. Increase iterations to widen the bars.
val iterations = 18000
var checksum = 0.0
loop {
    repeat(iterations) { checksum += kotlin.math.sin(it.toDouble()) }
    delay(16) // suspension should be a gap, not charged CPU time
}
