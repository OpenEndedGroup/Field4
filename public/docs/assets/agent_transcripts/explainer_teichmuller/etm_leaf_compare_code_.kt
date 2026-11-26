import afield.geometry.conformal.*
import afield.media.image.util.PngEncoder

/*
Two leaf outlines and the Teichmüller map from the first to the second. Six landmarks
match: tip, base, and the margin a third and two thirds of the way up on each side. The
outline between landmarks is free to slide. Colour is |μ| of the map: the distortion field.
*/
val names = ["elliptic", "lanceolate", "ovate", "heart-shaped", "round"]

// Each leaf as a distance from its middle in every direction; the tip points up.
fun oval(a: Double, b: Double, t: Double) = a * b / sqrt((b * cos(t)).pow(2) + (a * sin(t)).pow(2))
fun leaf(kind: Int): (Double) -> Double = { t ->
    when (kind) {
        0 -> oval(0.5, 1.0, t)
        1 -> oval(0.27, 1.0, t) * (1 + 0.15 * max(0.0, sin(t)).pow(6))
        2 -> oval(0.62 * (1 - 0.22 * sin(t)), 0.95, t)
        3 ->
            oval(0.75 * (1 - 0.15 * sin(t)), 0.9, t) *
                (1 + 0.2 * max(0.0, sin(t)).pow(8)) *
                (1 - 0.45 * exp(-(1 + sin(t)) / 0.024)) // the notch at the base, the same however t wraps
        else -> oval(0.85, 0.95, t)
    }
}

var first = 0
var second = 3

fun draw() {
    // The map is mirror-symmetric, so it is computed on the right halves and drawn twice.
    val c = MirrorShapeComparison(leaf(first), leaf(second))
    val mesh = c.half
    val picture = MeshPicture(640, 360, -2.2, -1.24, 2.2, 1.24)
    val colours = IntArray(mesh.triangleCount) { MeshPicture.distortionColor(c.map.moduli[it], 0.6) }
    for (side in [1.0, -1.0]) {
        picture.mesh(mesh, DoubleArray(mesh.vertexCount) { side * mesh.x[it] - 1.1 }, mesh.y, colours, edgeAlpha = 0.25)
        picture.mesh(
            mesh,
            DoubleArray(mesh.vertexCount) { side * c.map.u[it] + 1.1 },
            c.map.v,
            colours,
            edgeAlpha = 0.25,
        )
        for (k in 0 until 4) {
            picture.dot(side * mesh.x[c.landmarks[k]] - 1.1, mesh.y[c.landmarks[k]], 4.0, 0xFFFF9933.toInt())
            picture.dot(side * c.targetLandmarks[k][0] + 1.1, c.targetLandmarks[k][1], 4.0, 0xFFFF9933.toInt())
        }
    }
    display("leaves", PngEncoder.encode(picture.pixels, 640, 360))
    markdown(
        "readout",
        "**${names[first]} → ${names[second]}**: Teichmüller distance **${c.distance.f2}** (K = ${exp(2 * c.distance).f2}); " +
            "|μ| is ${c.map.medianModulus.f2} over half the leaf, ${c.map.minModulus.f2} to ${c.map.maxModulus.f2} at the extremes",
    )
}

draw()
controlGroup {
    slider("first leaf", 0.0, 4.0, step = 1.0, initialValue = 0.0) {
        first = it.toInt()
        draw()
    }
    slider("second leaf", 0.0, 4.0, step = 1.0, initialValue = 3.0) {
        second = it.toInt()
        draw()
    }
}
