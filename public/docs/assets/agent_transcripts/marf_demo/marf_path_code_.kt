// Two readers of one bank as two coordinates. The path stays coherent without the
// coordinates being synchronised, because both read the same material on their own clocks.
displayClearAll()
displayOpen("lines")   // an unopened channel measures 0x0

val b = marfBank(
    rampTo(0.1, 1.0), rampTo(0.9, 0.5), holdAt(0.4, 2.0),
    oscTo(0.7, 1.0, cycles = 3.0), curveTo(0.2, 0.25, amount = 4.0)
)
val x = b.read(pat("<0 1 2 3 4>").nums(), fields = MarfFieldClock.STAGES, name = "x")
val y = b.read(pat("<0 2 4 1 3>").nums().slow(3), fields = MarfFieldClock.STAGES, name = "y")

val path = fline {
    for (i in 0..2400) {
        val t = i / 100.0
        val px = 40.0 + x.value(t) * 460.0
        val py = 20.0 + y.value(t) * 260.0
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    stroked = true
    thickness = 1.5
    color = vec(0.5, 0.85, 1.0, 0.8)
}
lines(listOf(path), w = 560.0, h = 300.0, fitToSize = true)
