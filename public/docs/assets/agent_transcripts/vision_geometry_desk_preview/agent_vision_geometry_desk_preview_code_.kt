import afield.graphics.VisionGeometryPreview
import afield.graphics.previewVisionGeometry

// Exactly what you would paste into a `// {{ visionmetal }}` box — a lantern built from
// the shape helpers and a hand-written ring of quads on top.
val program = """
    // {{ visionmetal uniforms=spin }}
    import kotlin.math.cos
    import kotlin.math.sin

    onStart {
        geometry {
            color(0.95, 0.6, 0.2)
            sphere(0.11, segments = 28, rings = 18, cy = 0.06, cz = -1.2)

            color(0.35, 0.7, 1.0)
            tube(
                (0..40).map { i ->
                    val t = i / 40.0
                    val a = t * 6.2831853
                    p(0.17 * sin(a), -0.1, -1.2 + 0.17 * cos(a))
                },
                radius = 0.012,
                sides = 8,
            )

            // The raw level: eight uprights, each a quad stitched from four indices that
            // vertex() handed back, wound counter-clockwise seen from outside.
            //
            // Each upright is a single flat quad, and a flat quad has one side — so in the
            // preview (as in the headset) you see the four facing you and not the four
            // facing away. Give each one a back face, or build it as a thin cube, if you
            // want it visible from everywhere.
            color(0.75, 0.75, 0.8)
            for (i in 0 until 8) {
                val a = i * 6.2831853 / 8.0
                val nx = sin(a)
                val nz = cos(a)
                val tx = cos(a) * 0.012
                val tz = -sin(a) * 0.012
                normal(nx, 0.0, nz)
                val b0 = vertex(0.17 * nx - tx, -0.1, -1.2 + 0.17 * nz - tz)
                val b1 = vertex(0.17 * nx + tx, -0.1, -1.2 + 0.17 * nz + tz)
                val b2 = vertex(0.17 * nx + tx, 0.06, -1.2 + 0.17 * nz + tz)
                val b3 = vertex(0.17 * nx - tx, 0.06, -1.2 + 0.17 * nz - tz)
                quad(b0, b1, b2, b3)
            }
        }
        onFrame { frame -> uniform("spin", frame.time * 0.5) }
    }
""".trimIndent()

// This really compiles the Kotlin above to JavaScript and really runs it; the mesh drawn
// below is the packed array Metal would have received. It takes a second or two.
val capture = try {
    previewVisionGeometry(source = program)
} catch (missing: VisionGeometryPreview.NodeUnavailable) {
    // The mesh comes from running the box's own compiled program, so without node there
    // is no mesh at all. Print why instead of a stand-in picture.
    println("geometry preview needs node on PATH: " + missing.message)
    null
}

capture?.mesh()?.let { mesh ->
    println("built " + mesh.vertexCount + " vertices, " + mesh.triangleCount + " triangles")
    val b = mesh.bounds()
    println("extent " + "%.3f".format(b[3] - b[0]) + " x " + "%.3f".format(b[4] - b[1]) +
        " x " + "%.3f".format(b[5] - b[2]) + " m")
    println("winding agreement " + "%.0f".format(mesh.windingAgreement() * 100) + "%")
}
