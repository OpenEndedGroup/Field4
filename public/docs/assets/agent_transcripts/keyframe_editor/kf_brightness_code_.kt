// Launch this box to play the curve: the playhead sweeps it every four seconds and the
// meter shows its value. Edit the curve while it plays; your edits are saved with the box.
val brightness = keyframes(name = "brightness", defaults = [vec(0.0, 0.1), vec(1.0, 0.9), vec(2.5, 0.4), vec(4.0, 0.1)])

lateinit var level: MeterHandle
controlGroup("level") { level = meter("brightness") }
displayOpen("level")

val start = System.nanoTime()
loop {
    val t = ((System.nanoTime() - start) / 1e9) % 4.0
    brightness.setTime(t)
    level.set(brightness.evaluateValueAt(t))
}
