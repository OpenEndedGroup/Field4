// prolate / oblate against the radius of the patch, beside the golden ratio.
val phi = (1 + sqrt(5.0)) / 2
val ratios =
    [2, 4, 8, 12].joinToString(" · ") { r ->
        val counts = deBruijnTiling3D(radius = r.toDouble()).shapeCounts()
        "radius $r: %.4f".format(counts.getValue(1).toDouble() / counts.getValue(2))
    }

val penrose3 = deBruijnTiling3D(radius = 2.0, scale = 10.0)
// The cells whose crossing is nearest the middle of the grid.
val middle = penrose3.cells.filter { it.crossing.length() < 1.3 }
showMesh(
    penrose3.toTriMesh(only = middle, shrink = 0.88),
    title = "${middle.size} cells of the 3D Penrose tiling, each shrunk to 88%",
    caption = "yellow prolate, blue oblate · prolate/oblate $ratios · φ = %.4f".format(phi),
    view = MeshView(yawDegrees = 25.0, pitchDegrees = 22.0, width = 720, height = 520),
)
