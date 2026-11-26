import afield.geometry.conformal.*
import afield.media.image.util.PngEncoder

// Every pair of five leaves, compared by Teichmüller distance with the same six landmarks.
// A shape scaled up is the same shape: scaling is conformal, so it costs nothing.
val names = ["elliptic", "lanceolate", "ovate", "heart-shaped", "round"]
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

val d = Array(5) { DoubleArray(5) }
for (a in 0 until 5) for (b in a + 1 until 5) {
    d[a][b] = MirrorShapeComparison(leaf(a), leaf(b)).distance
    d[b][a] = d[a][b]
}

// The five leaves in a row, then the table of distances.
val picture = MeshPicture(640, 150, 0.0, -0.7, 6.0, 0.7)
for (k in 0 until 5) {
    val n = 200
    val r = leaf(k)
    val mesh = PlanarMesh.outline(
        DoubleArray(n) { 0.6 * r(2 * PI * it / n) * cos(2 * PI * it / n) + 0.6 + 1.2 * k },
        DoubleArray(n) { 0.6 * r(2 * PI * it / n) * sin(2 * PI * it / n) },
        0.04,
    )
    picture.mesh(mesh, colors = IntArray(mesh.triangleCount) { 0xFF2E8BBF.toInt() }, edgeAlpha = 0.0)
}
display("row", PngEncoder.encode(picture.pixels, 640, 150))
val table = StringBuilder("| | " + names.joinToString(" | ") + " |\n|---" + "|---".repeat(5) + "|\n")
for (a in 0 until 5) table.append(
    "| **${names[a]}** | " + (0 until 5).joinToString(" | ") { if (it == a) "·" else d[a][it].f2 } + " |\n",
)
markdown("readout", table.toString())
