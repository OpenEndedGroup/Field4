import afield.geometry.rhino.*

val dir = java.nio.file.Files.createTempDirectory("rhino-save").toFile()
val ribbons = 14
val steps = 40

// One ribbon: a point on its centre line at t in 0..1, and how far it has twisted there.
fun centre(a: Double, t: Double) = vec((30 + 25 * t) * cos(a + t), (30 + 25 * t) * sin(a + t), 40 * sin(PI * t))
fun twist(a: Double, t: Double) = a * 2 + 3 * PI * t

val file = Rhino3dm.save(java.io.File(dir, "ribbons.3dm"), units = "millimeters") {
    layer("Ribbons", vec(0.85, 0.4, 0.15, 1.0))
    layer("Ribbons::Spines", vec(0.95, 0.9, 0.8, 1.0))
    layer("Notes", vec(0.8, 0.8, 0.8, 1.0))

    for (i in 0 until ribbons) {
        val a = 2 * PI * i / ribbons

        // The spine, as cubic Bezier spans through the centre line: they arrive in Rhino as
        // degree-3 NURBS with exactly these control points.
        val spine = FLine().moveTo(centre(a, 0.0))
        for (k in 1..4) {
            val t0 = (k - 1) / 4.0
            val t1 = k / 4.0
            spine.cubicTo(centre(a, t0 + (t1 - t0) / 3), centre(a, t0 + 2 * (t1 - t0) / 3), centre(a, t1))
        }
        curve(spine, layer = "Ribbons::Spines", name = "spine $i")

        // The strip: two edges either side of the centre line, turning as it rises.
        val positions = FloatArray((steps + 1) * 6)
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val c = centre(a, t)
            val w = 4.0
            val dx = w * cos(twist(a, t)) * cos(a + t)
            val dy = w * cos(twist(a, t)) * sin(a + t)
            val dz = w * sin(twist(a, t))
            floatArrayOf(
                c.x + dx.toFloat(),
                c.y + dy.toFloat(),
                c.z + dz.toFloat(),
                c.x - dx.toFloat(),
                c.y - dy.toFloat(),
                c.z - dz.toFloat(),
            ).copyInto(positions, s * 6)
        }
        val triangles = IntArray(steps * 6)
        for (s in 0 until steps) intArrayOf(2 * s, 2 * s + 1, 2 * s + 2, 2 * s + 1, 2 * s + 3, 2 * s + 2)
            .copyInto(triangles, s * 6)
        val hue = i.toDouble() / ribbons
        mesh(
            TriMesh(positions, triangles),
            layer = "Ribbons",
            name = "ribbon $i",
            color = vec(0.9, 0.3 + 0.5 * hue, 0.2, 1.0),
        )
    }
    text("$ribbons ribbons", vec(-30.0, -70.0, 0.0), height = 6.0, layer = "Notes")
}

// What Rhino will open, read back with the same openNURBS.
val model = Rhino3dm.load(file)
println(model)
println("${file.length() / 1024} kB, layers " + model.layers.joinToString { it.path })
three(height = 460) { rhino(model) }
dir.deleteRecursively()
