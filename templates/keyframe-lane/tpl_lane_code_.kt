// Launch this box to play the curve: the playhead sweeps it every four seconds. Edit the
// curve while it plays; your edits are saved with the box. Boxes under this one read `lane`.
prop<Double>(lane) = 0.0
val curve = keyframes(name = "lane", defaults = [vec(0.0, 0.1), vec(1.0, 0.9), vec(2.5, 0.4), vec(4.0, 0.1)])

lateinit var gauge: MeterHandle
controlGroup("meter") { gauge = meter("lane") }
displayOpen("meter")

val start = System.nanoTime()
loop {
    val t = ((System.nanoTime() - start) / 1e9) % 4.0
    curve.setTime(t)
    lane = curve.evaluateValueAt(t)
    gauge.set(lane)
}
