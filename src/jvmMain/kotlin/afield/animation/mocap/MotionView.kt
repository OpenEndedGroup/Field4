@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import afield.document.graph.Box
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.graphics.three.three
import afield.math.linalg.Vec4

/** Existing Field 3D viewer/camera, streamed FLine geometry; repeated draws preserve the user's orbit. */
fun Box.showMotion(frame: MotionFrame, skeleton: MotionSkeleton, channel: String = "motion",
    reference: Boolean = true, contacts: Boolean = true, overlays: List<FLine> = emptyList(), height: Int = 420) {
    val actual = frame.executed.bones(skeleton).apply { color = Vec4(.9f, .94f, 1f, 1f); thickness = 3.0 }
    val intended = if (reference) frame.intended?.bones(skeleton)?.apply { color = Vec4(.15f, .7f, .85f, .65f); thickness = 1.5 } else null
    val points = FLine().apply {
        repeat(skeleton.size) { i ->
            val p = frame.executed.position(i)
            moveTo(p.x - .012f, p.y, p.z).lineTo(p.x + .012f, p.y, p.z)
            moveTo(p.x, p.y - .012f, p.z).lineTo(p.x, p.y + .012f, p.z)
        }
        color = Vec4(.9f, .94f, 1f, 1f); thickness = 2.0
    }
    val contactMarks = FLine().apply {
        // CLoSD exports a contact probability for each runtime body/joint, in skeleton order.
        // Other contact layouts need their explicit runtime mapping before display.
        if (contacts && frame.contacts.size == skeleton.size && frame.contactProvenance == "simulator.measured") {
            frame.contacts.forEachIndexed { i, value -> if (value > .5) {
                val p = frame.executed.position(i)
                moveTo(p.x - .035f, p.y, p.z).lineTo(p.x + .035f, p.y, p.z)
                moveTo(p.x, p.y, p.z - .035f).lineTo(p.x, p.y, p.z + .035f)
            } }
        }
        color = Vec4(.3f, 1f, .5f, 1f); thickness = 4.0
    }
    three(channel = channel, name = "motion", height = height, width = 700) {
        lines(actual, points, contactMarks)
        intended?.let { lines(it) }
        lines(overlays)
        camera(yaw = 35.0, pitch = 12.0, distance = 2.6)
        title("${skeleton.id} · ${"%.2f".format(frame.time)} s · episode ${frame.episode} · revision ${frame.revision}")
        helpers(true)
    }
}
