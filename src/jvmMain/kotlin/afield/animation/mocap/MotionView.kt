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
    reference: Boolean = true, contacts: Boolean = true, overlays: List<FLine> = emptyList(), height: Int = 420,
    follow: Boolean = false, followHalfLife: Double = .4, followVertical: Boolean = false, followEpoch: String = frame.episode.toString()) {
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
        if (follow) followCamera(frame.executed.root.let { afield.math.linalg.Vec3(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) },
            halfLifeSeconds=followHalfLife, epoch="${skeleton.hash}:$followEpoch", horizontalOnly=!followVertical)
        title("${skeleton.id} · ${"%.2f".format(frame.time)} s · episode ${frame.episode} · revision ${frame.revision}")
        helpers(true)
    }
}

/** Red spring/anchor and an amber submitted-force vector, scaled in metres per Newton for drawing. */
fun TetherObservation.overlays(forceScale: Double = .00015): List<FLine> {
    require(forceScale.isFinite() && forceScale >= 0)
    if (!active || point.size != 3 || anchor.size != 3 || force.size != 3) return emptyList()
    val spring = FLine().apply {
        moveTo(point[0], point[1], point[2]); lineTo(anchor[0], anchor[1], anchor[2])
        val r = .06
        moveTo(anchor[0]-r, anchor[1], anchor[2]); lineTo(anchor[0]+r, anchor[1], anchor[2])
        moveTo(anchor[0], anchor[1]-r, anchor[2]); lineTo(anchor[0], anchor[1]+r, anchor[2])
        moveTo(anchor[0], anchor[1], anchor[2]-r); lineTo(anchor[0], anchor[1], anchor[2]+r)
        color = Vec4(1f, .3f, .4f, 1f); thickness = 3.0
    }
    val forceLine = FLine().apply {
        val scale = minOf(forceScale, .6 / forceN.coerceAtLeast(1e-9))
        moveTo(point[0], point[1], point[2])
        lineTo(point[0]+force[0]*scale, point[1]+force[1]*scale, point[2]+force[2]*scale)
        color = Vec4(1f, .75f, .2f, 1f); thickness = 4.0
    }
    return listOf(spring, forceLine)
}
