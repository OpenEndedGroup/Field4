import afield.math.oracle.*
import kotlin.math.*

// Three short recordings made of the same four gestures in different orders. Replace these
// with your own: any Trajectory.of(rate, "name" to values, ...) per take, joined as episodes.
val rate = 20.0
val r = java.util.Random(11)
fun rise() = 30 { it / 29.0 }
fun hesitate() = 25 { 1.0 + 0.03 * r.nextGaussian() }
fun burst() = 30 { 1.0 + 0.7 * sin(it * 8 * PI / 29) }
fun pause() = (20 + r.nextInt(20)) { 0.0 }
val demo = Trajectory.join([
    rise() + hesitate() + burst() + pause() + rise() + burst() + pause(),
    rise() + burst() + pause() + rise() + hesitate() + hesitate() + burst() + pause(),
    hesitate() + burst() + pause() + rise() + hesitate() + burst() + pause() + rise(),
].map { Trajectory.of(rate, "v" to it) })

val memory = VariableMarkovOracle.build(demo)
print("$demo\n$memory")
showSignals(*demo.series(), x = List(demo.length) { it / rate }, xTitle = "seconds",
    marks = demo.episodeStarts.drop(1).map { "take" to it / rate },
    channel = "main", width = 1000, height = 260)
