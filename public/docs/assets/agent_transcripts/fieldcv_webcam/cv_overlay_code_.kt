import afield.python.NumpyShared

// every polyline channel box 1 publishes, and the colour it is drawn in
val channels = listOf(
    "tracks" to vec(0.2, 1.0, 0.3, 0.8),
    "skeleton" to vec(1.0, 0.3, 0.8, 1.0),
    "hands" to vec(1.0, 0.9, 0.2, 1.0),
    "face" to vec(0.3, 0.9, 1.0, 0.9),
    "contours" to vec(1.0, 0.55, 0.1, 1.0),
    "strokes" to vec(1.0, 1.0, 1.0, 1.0),
).map { (name, colour) -> NumpyShared.lines(name) to colour }

var module = moduleHere { }
var m = Mesh()
module += m named "overlay"
renderer += module

val width = 0.003   // half the line's width, in NDC
val strokeWidth = 0.008

frames {
    while (frame()) {
        // one segment per pair of consecutive nodes, in normalized image coordinates
        val segs = channels.flatMap { (shared, colour) ->
            val w = if (shared.name == "strokes") strokeWidth else width
            shared.lines().flatMap { f -> f.nodes.zipWithNext().map { Triple(it, colour, w) } }
        }
        if (segs.isEmpty()) continue
        // each segment is a thin quad, two triangles, since GPU lines are a single pixel wide
        m.set(4 * segs.size, 2 * segs.size) {
            for ((i, s) in segs.withIndex()) {
                val (ab, c, w) = s
                // image (u, v), v down, to Field's y-up NDC (2u - 1, 1 - 2v)
                val ax = 2 * ab.first.to.x - 1
                val ay = 1 - 2 * ab.first.to.y
                val bx = 2 * ab.second.to.x - 1
                val by = 1 - 2 * ab.second.to.y
                val len = maxOf(Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()), 1e-6)
                val nx = -(by - ay) / len * w
                val ny = (bx - ax) / len * w
                pos.add(ax + nx, ay + ny, 0.1, ax - nx, ay - ny, 0.1, bx - nx, by - ny, 0.1, bx + nx, by + ny, 0.1)
                4 { col.add(c.x, c.y, c.z, c.w) }
                tri.add(4 * i, 4 * i + 1, 4 * i + 2, 4 * i, 4 * i + 2, 4 * i + 3)
            }
        }
    }
}
