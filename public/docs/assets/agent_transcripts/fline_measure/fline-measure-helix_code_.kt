// a helix of radius 80 rising 30 units per turn, one cubic per quarter turn
val r = 80.0
val rise = 30.0
val k = 0.5522847 * r
val helix = fline {
    moveTo(r, 0, 0)
    for (q in 0 until 12) {
        val a0 = q * PI / 2
        val a1 = a0 + PI / 2
        val z0 = rise * q / 4
        val z1 = rise * (q + 1) / 4
        cubicTo(
            r * cos(a0) - k * sin(a0), r * sin(a0) + k * cos(a0), z0 + (z1 - z0) / 3,
            r * cos(a1) + k * sin(a1), r * sin(a1) - k * cos(a1), z0 + 2 * (z1 - z0) / 3,
            r * cos(a1), r * sin(a1), z1,
        )
    }
}

// look at it from the side and a little above: x across, z up, y into the page
fun view(p: Vec3) = vec(p.x + 0.35 * p.y, -p.z - 0.35 * p.y)

val drawn = fline {
    helix.sampleByNumber(400).forEachIndexed { i, p -> if (i == 0) moveTo(view(p)) else lineTo(view(p)) }
    thickness = 1.5
    color = vec(1.0, 1.0, 1.0, 1.0)
}

// the 3D frame at 16 evenly spaced places: tangent orange, normal blue, binormal green
fun tick(p: Vec3, d: Vec3?, c: Vec4) = fline {
    if (d != null) { moveTo(view(p)); lineTo(view(p + d * 18.0)) }
    thickness = 2.0
    color = c
}
val frames = (0..15).flatMap { i ->
    val loc = helix.locationAtFraction(i / 15f)!!
    listOf(
        tick(loc.point, loc.tangent, vec(1.0, 0.6, 0.2, 1.0)),
        tick(loc.point, loc.normal, vec(0.4, 0.8, 1.0, 1.0)),
        tick(loc.point, loc.binormal, vec(0.5, 1.0, 0.5, 1.0)),
    )
}

// a helix has constant curvature r / (r^2 + c^2), with c the rise per radian
val c = rise / (2 * PI)
val mid = helix.locationAtFraction(0.5f)!!
println("curvature %.5f, a true helix %.5f".format(mid.curvature, r / (r * r + c * c)))
println("normal points at the axis: %.3f".format(mid.normal!!.dot(vec(-mid.point.x, -mid.point.y, 0.0).normalize())))

lines(listOf(drawn) + frames, w = 420.0, h = 260.0, fitToSize = true)
