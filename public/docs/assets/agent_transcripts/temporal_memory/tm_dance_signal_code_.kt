import afield.io.mocap.*
import afield.math.oracle.*
import kotlin.math.*

// six takes of modern dance (CMU subject 05): every joint, in the dancer's own frame, at 30 Hz,
// without the standing still before and after each take
val dir = "public/docs/assets/temporal_memory/cmu_05"
val takes = ["05_02", "05_07", "05_09", "05_12", "05_14", "05_18"].map { Bvh.read("$dir/$it.bvh.gz") }
val dance = Trajectory.join(takes.map { it.toTrajectory(rate = 30.0).trimStill() })
val memory = VariableMarkovOracle.build(dance)
val colours = [
    vec(0.48, 0.85, 1.0, 1.0),
    vec(1.0, 0.62, 0.29, 1.0),
    vec(0.56, 1.0, 0.6, 1.0),
    vec(1.0, 0.88, 0.4, 1.0),
    vec(0.77, 0.55, 1.0, 1.0),
    vec(1.0, 0.48, 0.62, 1.0),
]

val played = Improviser(memory, seed = 2, reuseWeight = 3.0, reuseMemory = 60.0).take(60.0)
val seconds = List(played.output.length) { it / 30.0 }
fun height(joint: String) = played.output.channel("$joint.y").toList()

// shade each stretch of output by the take it was played from
val hex = ["#7ad9ff", "#ff9e4a", "#8fff9a", "#ffe066", "#c58cff", "#ff7a9e"]
val bands = (played.segments + Segment(played.output.length, -1, -1, Reason.Continue, 0.0, 0, 0.0)).zipWithNext().mapNotNull {
    (a, b) ->
    if (a.episode < 0) null
    else SignalRegion("${a.episode + 1}", a.outputStart / 30.0, b.outputStart / 30.0, hex[a.episode])
}
showSignals(
    "right hand" to height("RightHand"),
    "left hand" to height("LeftHand"),
    "head" to height("Head"),
    x = seconds,
    xTitle = "seconds",
    yTitle = "height",
    regions = bands,
    title = "heights of hands and head; bands numbered by take",
    channel = "main",
    width = 1000,
    height = 380,
)
