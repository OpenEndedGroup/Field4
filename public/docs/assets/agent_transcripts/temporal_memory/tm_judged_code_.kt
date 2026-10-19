import afield.math.oracle.*
import afield.math.temporal.*
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
val seconds = 180.0

// Four ways to play the same memory from the same seed: the oracle alone, the oracle told to
// avoid recent passages harder, and Timestral judging the oracle's proposals two ways.
val runs = TimestralSession().use { session ->
    fun judged(mode: JudgeMode, reuseWeight: Double) = ForecastJudge(session, memory, lockstep = true).use { judge ->
        Improviser(memory, seed = 1, jumpProbability = 0.15, judge = judge, judgeMode = mode, reuseWeight = reuseWeight).take(seconds)
    }
    linkedMapOf(
        "oracle alone" to Improviser(memory, seed = 1, jumpProbability = 0.15).take(seconds),
        "oracle, reuseWeight 6" to Improviser(memory, seed = 1, jumpProbability = 0.15, reuseWeight = 6.0).take(seconds),
        "Timestral vetoes" to judged(JudgeMode.Veto, 1.0),
        "Timestral prefers, reuseWeight 6" to judged(JudgeMode.Prefer, 6.0),
    )
}

markdown("table", "| three minutes of the phrases | reuse | coverage | join roughness | jumps |\n|---|---|---|---|---|\n" +
    runs.entries.joinToString("\n") { (name, p) ->
        "| $name | %.2f | %.2f | %.2f | %d |".format(p.reuse(), p.coverage(demo.length), p.joinRoughness(demo), p.segments.size - 1)
    })

// what the plain oracle and the judged one actually played, the first half minute of each
val shown = (30 * rate).toInt()
val compared = ["oracle alone", "Timestral prefers, reuseWeight 6"]
for (name in compared) {
    showSignals(name to runs.getValue(name).output.values[0].take(shown), x = List(shown) { it / rate },
        channel = name, width = 1000, height = 180)
}

// and where in memory each was playing from over the same half minute
showSignals(*compared.map { name -> name to runs.getValue(name).sourceSeconds().take(shown) }.toTypedArray(),
    x = List(shown) { it / rate }, xTitle = "seconds", yTitle = "memory position (s)",
    channel = "main", width = 1000, height = 300)
