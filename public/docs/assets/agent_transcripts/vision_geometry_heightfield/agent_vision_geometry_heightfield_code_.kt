// {{ visionmetal uniforms=spin,light }}
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

onStart {
    geometry {
        val n = 48                 // cells per side; (n+1)^2 vertices
        val size = 0.52            // metres across
        val centreZ = -1.2

        // The surface, as a plain function. Sampling it three times per vertex is what
        // gives us a normal without any calculus.
        fun height(x: Double, z: Double): Double {
            val r = sqrt(x * x + z * z)
            return 0.055 * cos(r * 38.0) * exp(-r * 5.5)
        }

        val step = size / n
        val eps = step * 0.5
        val base = vertexCount

        for (row in 0..n) {
            for (col in 0..n) {
                val x = (col.toDouble() / n - 0.5) * size
                val z = (row.toDouble() / n - 0.5) * size
                val h = height(x, z)

                // Central differences along x and z give the two tangents; their cross
                // product is the normal. Negating the slopes puts it on the +y side.
                val dx = (height(x + eps, z) - height(x - eps, z)) / (2.0 * eps)
                val dz = (height(x, z + eps) - height(x, z - eps)) / (2.0 * eps)
                val len = sqrt(dx * dx + dz * dz + 1.0)
                normal(-dx / len, 1.0 / len, -dz / len)

                // Colour by height, so the shader needs no extra uniforms to show relief.
                val t = (h / 0.055 + 1.0) * 0.5
                color(0.15 + 0.75 * t, 0.35 + 0.35 * t, 0.95 - 0.35 * t)

                uv(col.toDouble() / n, row.toDouble() / n)
                vertex(x, h, centreZ + z)
            }
        }

        // vertex() handed back an index for each of those; stitch them into quads. The
        // lattice is (n+1) wide, so a cell's four corners are a, a+stride, a+stride+1, a+1.
        //
        // The order matters as much as the membership. This sheet lies in XZ with its
        // normal on +y, and only this diagonal winds counter-clockwise seen from above.
        // Take the corners the other way round and the head — which culls back faces —
        // shows you the underside of your landscape, which is to say nothing at all.
        val stride = n + 1
        for (row in 0 until n) {
            for (col in 0 until n) {
                val a = base + row * stride + col
                quad(a, a + stride, a + stride + 1, a + 1)
            }
        }

        log("height field: $vertexCount vertices, ${indexCount / 3} triangles")
    }

    uniform("light", 0.35, 0.9, 0.35)
    onFrame { frame -> uniform("spin", frame.time * 0.25) }

    onStop { println("height field stopped") }
    println("height field running")
}
