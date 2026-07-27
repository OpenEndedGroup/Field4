import afield.graphics.VisionGeometryPreview
import afield.graphics.previewVisionGeometry

// A ring of quads whose corners are given in the other order. Every normal still points
// outward; only the winding changed. The head culls back faces, so on the device this is a
// ring you can only see from the inside — and from any normal viewing position, nothing.
val inverted = """
    // {{ visionmetal }}
    import kotlin.math.cos
    import kotlin.math.sin

    onStart {
        geometry {
            color(0.9, 0.35, 0.35)
            for (i in 0 until 24) {
                val a = i * 6.2831853 / 24.0
                val nx = sin(a)
                val nz = cos(a)
                val tx = cos(a) * 0.024
                val tz = -sin(a) * 0.024
                normal(nx, 0.0, nz)
                val b0 = vertex(0.2 * nx - tx, -0.08, -1.2 + 0.2 * nz - tz)
                val b1 = vertex(0.2 * nx + tx, -0.08, -1.2 + 0.2 * nz + tz)
                val b2 = vertex(0.2 * nx + tx, 0.08, -1.2 + 0.2 * nz + tz)
                val b3 = vertex(0.2 * nx - tx, 0.08, -1.2 + 0.2 * nz - tz)
                quad(b3, b2, b1, b0)   // <- the same four corners, reversed
            }
        }
    }
""".trimIndent()

val capture = try {
    previewVisionGeometry(source = inverted)
} catch (missing: VisionGeometryPreview.NodeUnavailable) {
    println("geometry preview needs node on PATH: " + missing.message)
    null
}

capture?.mesh()?.let { mesh ->
    val agreement = mesh.windingAgreement()
    println("winding agreement " + "%.0f".format(agreement * 100) + "% — " +
        (if (agreement < 0.5) "wound inside-out; the head would cull this away"
         else "consistent"))
}
