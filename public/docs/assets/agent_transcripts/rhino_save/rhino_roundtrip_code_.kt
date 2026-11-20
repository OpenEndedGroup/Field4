import afield.geometry.rhino.*

// Read a model, add to it, write it back, and read the result: the round trip a Rhino user's file takes.
val dir = java.nio.file.Files.createTempDirectory("rhino-roundtrip").toFile()
val original = Rhino3dm.load(Rhino3dm.writeSample(java.io.File(dir, "sample.3dm")))

val frame =
    FLine()
        .moveTo(vec(-10.0, -20.0, 0.0))
        .lineTo(vec(140.0, -20.0, 0.0))
        .lineTo(vec(140.0, 75.0, 0.0))
        .lineTo(vec(-10.0, 75.0, 0.0))
frame.close()

val edited = Rhino3dm.save(original, java.io.File(dir, "edited.3dm")) {
    layer("Field", vec(0.2, 0.7, 0.9, 1.0))
    curve(frame, layer = "Field", name = "frame")
    text("edited in Field", vec(0.0, -30.0, 0.0), height = 4.0, layer = "Field")
}
val back = Rhino3dm.load(edited)

println(original)
println(back)
println(
    "objects ${original.objects.size} -> ${back.objects.size}, layers ${original.layers.size} -> ${back.layers.size}",
)
println("openNURBS log on the way back: \"${back.log.trim()}\"")

three(height = 420) { rhino(back) }
dir.deleteRecursively()
