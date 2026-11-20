import afield.geometry.rhino.*

// How close the tessellations are to the shapes the file describes: area against the analytic
// value, at the default tolerance and at a ten times finer one
if (!Rhino3dm.isAvailable) {
    println(Rhino3dm.describe())
} else {
    val dir = java.nio.file.Files.createTempDirectory("rhino-nurbs").toFile()
    val file = Rhino3dm.writeNurbsSample(java.io.File(dir, "save_small.3dm"))
    val exact = mapOf(
        "sphere" to 4 * PI * 100,
        "capped cylinder" to 2 * PI * 5 * 20 + 2 * PI * 25,
        "plate with hole" to 400 - PI * 25,
        "creased SubD" to 600.0,
    )
    for (tolerance in [null, 0.003]) {
        val model = Rhino3dm.load(file, tolerance = tolerance)
        println("tolerance ${tolerance ?: "default"}")
        for ((name, area) in exact) {
            val mesh = model.meshes.single { it.name == name }.mesh
            val error = (mesh.surfaceArea() - area) / area * 100
            println(
                "  ${name.padEnd(16)} ${mesh.triangleCount.toString().padStart(6)} triangles, area off by ${error.f3}%",
            )
        }
    }
    dir.deleteRecursively()
}
