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

// the continuation as a figure, stepped at 30 Hz; the floor square keeps the framing still
val player = Improviser(memory, seed = 2, reuseWeight = 3.0, reuseMemory = 60.0)
val floor = fline { moveTo(-12.0, 0.0, -12.0); lineTo(12.0, 0.0, -12.0); lineTo(12.0, 0.0, 12.0); lineTo(-12.0, 0.0, 12.0); lineTo(-12.0, 0.0, -12.0); color = vec(1, 1, 1, 0.25) }
frames {
    val period = (1e9 / 30).toLong()
    var next = System.nanoTime()
    while (frame()) {
        if (System.nanoTime() < next) continue
        next += period
        val pose = player.step()
        val take = dance.episodeOf(max(0, player.position))
        three(name = "dancer", width = 640, height = 480) {
            lines(takes[0].skeleton(pose, dance.channels).onEach { it.color = colours[take]; it.thickness = 3.0 } + floor)
            helpers(false)
            camera(yaw = 30.0, pitch = 12.0)
        }
    }
}
