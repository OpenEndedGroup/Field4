import afield.io.mocap.*
import afield.math.oracle.*
import kotlin.math.*

// six takes of modern dance (CMU subject 05): every joint, in the dancer's own frame, at 30 Hz,
// without the standing still before and after each take
val dir = "public/docs/assets/temporal_memory/cmu_05"
val takes = ["05_02", "05_07", "05_09", "05_12", "05_14", "05_18"].map { Bvh.read("$dir/$it.bvh.gz") }
val dance = Trajectory.join(takes.map { it.toTrajectory(rate = 30.0).trimStill() })
val memory = VariableMarkovOracle.build(dance)
val colours = [vec(0.48, 0.85, 1.0, 1.0), vec(1.0, 0.62, 0.29, 1.0), vec(0.56, 1.0, 0.6, 1.0),
               vec(1.0, 0.88, 0.4, 1.0), vec(0.77, 0.55, 1.0, 1.0), vec(1.0, 0.48, 0.62, 1.0)]

// one minute of new dance from 61 seconds of memory
val played = Improviser(memory, seed = 2, reuseWeight = 3.0, reuseMemory = 60.0).take(60.0)

// a pose every half second for the first sixteen seconds, two rows, coloured by take
val poses = (0 until 32).flatMap { k ->
    val t = k * 15
    val take = dance.episodeOf(max(0, played.source[t]))
    val at = doubleArrayOf((k % 16) * 14.0, if (k < 16) 0.0 else -42.0, 0.0)
    takes[0].skeleton(played.output.frame(t), dance.channels, at).onEach { it.color = colours[take]; it.thickness = 2.0 }
}
print("${played.segments.size - 1} jumps; every take used: coverage ${"%.2f".format(played.coverage(dance.length))}")
// an invisible point above the rows leaves room for the viewer's toolbar
val headroom = fline { moveTo(0.0, 70.0, 0.0); color = vec(0, 0, 0, 0) }
three(name = "dance-strip", width = 1000, height = 420) {
    lines(poses + headroom)
    helpers(false)
    camera(yaw = 0.0, pitch = 4.0)
    title("one minute from six takes, first sixteen seconds, a pose every half second")
    caption("colour = which take the pose was played from")
}
