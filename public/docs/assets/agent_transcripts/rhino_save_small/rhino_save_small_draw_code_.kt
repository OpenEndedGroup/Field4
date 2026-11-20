import afield.geometry.rhino.*

// A "Save Small" .3dm: NURBS Breps and SubDs with no render meshes, drawn as Rhino's saved
// meshes alone would give them, then tessellated from their exact surfaces
if (!Rhino3dm.isAvailable) {
    println(Rhino3dm.describe())
} else {
    val dir = java.nio.file.Files.createTempDirectory("rhino-nurbs").toFile()
    val file = Rhino3dm.writeNurbsSample(java.io.File(dir, "save_small.3dm"))
    val saved = Rhino3dm.load(file, meshing = RhinoBrepMeshing.SAVED)
    val evaluated = Rhino3dm.load(file)
    println(evaluated)
    three(channel = "saved", name = "saved", height = 360) { rhino(saved) }
    three(channel = "evaluated", name = "evaluated", height = 360) { rhino(evaluated) }
    dir.deleteRecursively()
}
