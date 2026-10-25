// A keyframe curve with its playhead running, and a meter it drives.
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
