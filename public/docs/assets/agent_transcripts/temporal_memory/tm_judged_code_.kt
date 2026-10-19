import afield.io.mocap.*
import afield.math.oracle.*
import afield.math.temporal.*
import kotlin.math.*

// six takes of modern dance (CMU subject 05): every joint, in the dancer's own frame, at 30 Hz,
// without the standing still before and after each take
val dir = "public/docs/assets/temporal_memory/cmu_05"
val takes = ["05_02", "05_07", "05_09", "05_12", "05_14", "05_18"].map { Bvh.read("$dir/$it.bvh.gz") }
val dance = Trajectory.join(takes.map { it.toTrajectory(rate = 30.0).trimStill() })
val memory = VariableMarkovOracle.build(dance)
val colours = [vec(0.48, 0.85, 1.0, 1.0), vec(1.0, 0.62, 0.29, 1.0), vec(0.56, 1.0, 0.6, 1.0),
               vec(1.0, 0.88, 0.4, 1.0), vec(0.77, 0.55, 1.0, 1.0), vec(1.0, 0.48, 0.62, 1.0)]

// two minutes, five ways, same seed; the judged ones forecast all 114 channels with Timestral-28M
val seconds = 120.0
val key = ["LeftHand", "RightHand", "Head", "LeftToeBase", "RightToeBase"].flatMap { j -> ["x", "y", "z"].map { dance.channels.indexOf("$j.$it") } }
val runs = TimestralSession().use { session ->
    fun judged(mode: JudgeMode) = ForecastJudge(session, memory, lockstep = true).use { judge ->
        Improviser(memory, seed = 1, jumpProbability = 0.1, judge = judge, judgeMode = mode).take(seconds)
    }
    linkedMapOf(
        "loop the first take" to Improviser(memory, policy = Policy.Loop).take(seconds),
        "nearest history" to Improviser(memory, seed = 1, policy = Policy.NearestHistory, jumpProbability = 0.1).take(seconds),
        "oracle" to Improviser(memory, seed = 1, jumpProbability = 0.1).take(seconds),
        "oracle, Timestral vetoes" to judged(JudgeMode.Veto),
        "oracle, Timestral prefers" to judged(JudgeMode.Prefer),
    )
}
markdown("main", "| two minutes from 61 s of dance | coverage | repeats itself | join roughness | jumps |\n|---|---|---|---|---|\n" +
    runs.entries.joinToString("\n") { (name, p) ->
        "| $name | %.2f | %.2f | %.2f | %d |".format(p.coverage(dance.length), p.outputRepetition(channels = key), p.joinRoughness(dance), p.segments.size - 1)
    })
