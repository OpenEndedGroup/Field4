@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import afield.math.linalg.Quat
import afield.math.linalg.Vec3
import org.json.JSONObject
import java.nio.file.Path
import kotlin.math.PI

/** A diagnostic two-joint clip, explicitly unrelated to learned motion or simulated humanoids. */
object MotionExamples {
    fun contractClip(): MotionClip {
        val skeleton = MotionSkeleton("field.motion.coordinate-probe", "1", listOf("origin", "one-metre-link"), listOf(-1, 0),
            listOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0), listOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        val frames = List(81) { i ->
            val episode = if (i < 40) 0L else 1L
            val angle = if (episode == 0L) PI / 2 * i / 39 else -PI / 2 * (i - 40) / 40
            val root = Vec3(if (episode == 0L) -.5f else .5f, 0f, 0f)
            val q = Quat().setAngleAxis(angle.toFloat(), 0f, 0f, 1f)
            val tip = q.transform(Vec3(0f, 1f, 0f)).add(root)
            val pose = MotionPose(listOf(root.x.toDouble(), 0.0, 0.0),
                listOf(q.x.toDouble(), q.y.toDouble(), q.z.toDouble(), q.w.toDouble(), 0.0, 0.0, 0.0, 1.0),
                listOf(root.x.toDouble(), root.y.toDouble(), root.z.toDouble(), tip.x.toDouble(), tip.y.toDouble(), tip.z.toDouble()))
            MotionFrame(i / 20.0, i.toLong(), episode, 0, pose)
        }
        return MotionClip(skeleton, frames)
    }

    fun recordContractClip(directory: Path) {
        val clip = contractClip()
        MotionRecorder(directory, clip.skeleton, JSONObject().put("backend", "coordinate-probe")
            .put("learnedMotion", false).put("simulation", false)).use { recorder ->
            recorder.event(JSONObject().put("type", "reset").put("time", 2.0).put("episode", 1).put("reason", "coordinate-probe"))
            clip.frames.forEach(recorder::append)
        }
    }
}
