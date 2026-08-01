// KeyframeScope.camera stores a CameraPose — a rigid transform as a dual quaternion, plus
// the focus distance — rather than a position and a target point. Interpolating that is a
// SCREW MOTION, and between two cameras looking at the same subject it is a very different
// move from lerping the two positions. Draw both and the argument makes itself.
import afield.four.util.CameraPose
import afield.stdlib.KeyframeScope
import afield.stdlib.linalg.Vector3f

val scope = KeyframeScope()
val a = scope.Key(mutableMapOf("camera" to CameraPose.lookAt(Vector3f(8f, 2f, 0f), Vector3f(0f, 0f, 0f))))
val b = scope.Key(mutableMapOf("camera" to CameraPose.lookAt(Vector3f(0f, 2f, 8f), Vector3f(0f, 0f, 0f))))
val pa = (a["camera"] as CameraPose).position
val pb = (b["camera"] as CameraPose).position

val n = 60
fun sclerpAt(t: Double) = (scope.interpolate(a, b, t)["camera"] as CameraPose)
fun lerpAt(t: Double) = Vector3f(pa).lerp(pb, t.toFloat())

// ---- panel 1: plan view, looking down y. World units scaled up to figure units.
val S = 40.0
val screw = fline {
    for (i in 0..n) {
        val p = sclerpAt(i.toDouble() / n).position
        if (i == 0) moveTo(p.x * S, p.z * S) else lineTo(p.x * S, p.z * S)
    }
    color = vec(0.36, 0.84, 1.0, 1.0); thickness = 3.0
}
val chord = fline {
    moveTo(pa.x * S, pa.z * S); lineTo(pb.x * S, pb.z * S)
    color = vec(1.0, 0.62, 0.26, 1.0); thickness = 3.0
}
// Sight lines from the sclerp path to the subject: every one is exactly the same length.
val sight = fline {
    for (i in 0..10) {
        val p = sclerpAt(i / 10.0).position
        moveTo(p.x * S, p.z * S); lineTo(0.0, 0.0)
    }
    color = vec(0.36, 0.84, 1.0, 0.22); thickness = 1.0
}
// …and from the lerped positions, which are visibly shorter through the middle.
val sightLerp = fline {
    for (i in 0..10) {
        val p = lerpAt(i / 10.0)
        moveTo(p.x * S, p.z * S); lineTo(0.0, 0.0)
    }
    color = vec(1.0, 0.62, 0.26, 0.22); thickness = 1.0
}
val subject = fline {
    val r = 14.0
    for (i in 0..48) {
        val ang = i / 48.0 * Math.PI * 2.0
        if (i == 0) moveTo(r * kotlin.math.cos(ang), r * kotlin.math.sin(ang))
        else lineTo(r * kotlin.math.cos(ang), r * kotlin.math.sin(ang))
    }
    color = vec(0.95, 0.95, 0.95, 0.9); thickness = 2.0
}

// ---- panel 2: distance from the camera to the subject, against t.
fun plotY(d: Double) = 330.0 - (d - 5.5) * 120.0
val dScrew = fline {
    for (i in 0..n) {
        val t = i.toDouble() / n
        if (i == 0) moveTo(0.0, plotY(sclerpAt(t).position.length().toDouble()))
        else lineTo(t * 400.0, plotY(sclerpAt(t).position.length().toDouble()))
    }
    color = vec(0.36, 0.84, 1.0, 1.0); thickness = 3.0
}
val dChord = fline {
    for (i in 0..n) {
        val t = i.toDouble() / n
        if (i == 0) moveTo(0.0, plotY(lerpAt(t).length().toDouble()))
        else lineTo(t * 400.0, plotY(lerpAt(t).length().toDouble()))
    }
    color = vec(1.0, 0.62, 0.26, 1.0); thickness = 3.0
}

// Every number in the captions is measured here, never asserted from memory.
val rMin = (0..n).minOf { sclerpAt(it.toDouble() / n).position.length() }
val rMax = (0..n).maxOf { sclerpAt(it.toDouble() / n).position.length() }
val worstAim = (0..n).maxOf { sclerpAt(it.toDouble() / n).target.length() }
val lerpMid = lerpAt(0.5).length()
val closer = (1.0 - lerpMid / rMax) * 100.0
println("sclerp distance to subject: %.4f .. %.4f".format(rMin, rMax))
println("straight-line midpoint:     %.4f  (%.1f%% nearer)".format(lerpMid, closer))
println("worst-case aiming error:    %.6f world units".format(worstAim))

showPlaneGrid(
    ("plan view, looking down y\n" +
        "blue: sclerp, a quarter circle. orange: the straight line between the same two positions") to
        listOf(sight, sightLerp, subject, chord, screw),
    ("distance to the subject, over the move\n" +
        "sclerp holds %.4f..%.4f; the straight line dips to %.4f — %.1f%% nearer".format(
            rMin, rMax, lerpMid, closer)) to
        listOf(dScrew, dChord),
    columns = 2, cellWidth = 440, cellHeight = 380
)
display(
    "aim",
    "<div style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;padding:8px 0'>" +
        "worst-case aiming error along the sclerp path: <b>%.6f</b> world units &mdash; ".format(worstAim) +
        "the subject never leaves the centre of frame." +
    "</div>"
)
