import afield.math.oracle.*
import kotlin.math.*

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

// One minute from about 35 seconds of memory. Change the seed for another performance,
// jumpProbability for more or fewer recombinations, minPhrase for longer stretches.
val played = Improviser(memory, seed = 4, jumpProbability = 0.3, minPhrase = 1.0).take(60.0)

val seconds = List(played.output.length) { it / rate }
val byTake = demo.episodeStarts.indices.map { e ->
    "from take ${e + 1}" to played.source.map { if (it >= 0 && demo.episodeOf(it) == e) it / rate else Double.NaN }
}
print("${played.segments.size} segments, reuse ${"%.2f".format(played.reuse())}, coverage ${"%.2f".format(played.coverage(demo.length))}")
print(played.segments.take(8).joinToString("\n") { "  at ${it.outputStart / rate}s: ${it.reason} to take ${it.episode + 1} @ ${"%.1f".format(it.source / rate)}s, context ${it.context}" })
showSignals("performance" to played.output.values[0].toList(), x = seconds, channel = "main", width = 1000, height = 220)
showSignals(*byTake.toTypedArray(), x = seconds, xTitle = "seconds", yTitle = "memory position (s)",
    channel = "memory", width = 1000, height = 300, floatingLegend = true)
