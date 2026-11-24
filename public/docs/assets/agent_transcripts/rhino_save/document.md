# Field Document: rhino_save

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - ribbons saved to a .3dm

<!-- field-box id="rhino_ribbons" index="0" -->

```edn [field-box id="rhino_ribbons" role="properties"]
{"id" "rhino_ribbons" "index" 0 "name" "1 - ribbons saved to a .3dm" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - ribbons saved to a .3dm" "_tombstone" false "frame" #afield/rect [80.0 80.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rhino_ribbons_code_.kt" "lines" 68 "characters" 2711 "sha256" "b8ff3916f71af78700f7777670b5f42771197d4eed8ea4730cb0633945680d28"}]}
```

```kotlin [field-property box="rhino_ribbons" name="code" path="rhino_ribbons_code_.kt"]
import afield.geometry.mesh.TriMesh
import afield.geometry.rhino.*
import kotlin.math.*

val dir = java.nio.file.Files.createTempDirectory("rhino-save").toFile()
val ribbons = 14
val steps = 40

// One ribbon: a point on its centre line at t in 0..1, and how far it has twisted there.
fun centre(a: Double, t: Double) = vec((30 + 25 * t) * cos(a + t), (30 + 25 * t) * sin(a + t), 40 * sin(PI * t))
fun twist(a: Double, t: Double) = a * 2 + 3 * PI * t

val file = Rhino3dm.save(java.io.File(dir, "ribbons.3dm"), units = "millimeters") {
    layer("Ribbons", vec(0.85, 0.4, 0.15, 1.0))
    layer("Ribbons::Spines", vec(0.95, 0.9, 0.8, 1.0))
    layer("Notes", vec(0.8, 0.8, 0.8, 1.0))

    for (i in 0 until ribbons) {
        val a = 2 * PI * i / ribbons

        // The spine, as cubic Bezier spans through the centre line: they arrive in Rhino as
        // degree-3 NURBS with exactly these control points.
        val spine = FLine().moveTo(centre(a, 0.0))
        for (k in 1..4) {
            val t0 = (k - 1) / 4.0
            val t1 = k / 4.0
            spine.cubicTo(centre(a, t0 + (t1 - t0) / 3), centre(a, t0 + 2 * (t1 - t0) / 3), centre(a, t1))
        }
        curve(spine, layer = "Ribbons::Spines", name = "spine $i")

        // The strip: two edges either side of the centre line, turning as it rises.
        val positions = FloatArray((steps + 1) * 6)
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val c = centre(a, t)
            val w = 4.0
            val dx = w * cos(twist(a, t)) * cos(a + t)
            val dy = w * cos(twist(a, t)) * sin(a + t)
            val dz = w * sin(twist(a, t))
            floatArrayOf(
                c.x + dx.toFloat(),
                c.y + dy.toFloat(),
                c.z + dz.toFloat(),
                c.x - dx.toFloat(),
                c.y - dy.toFloat(),
                c.z - dz.toFloat(),
            ).copyInto(positions, s * 6)
        }
        val triangles = IntArray(steps * 6)
        for (s in 0 until steps) intArrayOf(2 * s, 2 * s + 1, 2 * s + 2, 2 * s + 1, 2 * s + 3, 2 * s + 2)
            .copyInto(triangles, s * 6)
        val hue = i.toDouble() / ribbons
        mesh(
            TriMesh(positions, triangles),
            layer = "Ribbons",
            name = "ribbon $i",
            color = vec(0.9, 0.3 + 0.5 * hue, 0.2, 1.0),
        )
    }
    text("$ribbons ribbons", vec(-30.0, -70.0, 0.0), height = 6.0, layer = "Notes")
}

// What Rhino will open, read back with the same openNURBS.
val model = Rhino3dm.load(file)
println(model)
println("${file.length() / 1024} kB, layers " + model.layers.joinToString { it.path })
three(height = 460) { rhino(model) }
dir.deleteRecursively()
```

## 2. 2 - read, change, write

<!-- field-box id="rhino_roundtrip" index="1" -->

```edn [field-box id="rhino_roundtrip" role="properties"]
{"id" "rhino_roundtrip" "index" 1 "name" "2 - read, change, write" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - read, change, write" "_tombstone" false "frame" #afield/rect [80.0 220.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rhino_roundtrip_code_.kt" "lines" 30 "characters" 1069 "sha256" "2e13bcc3b693f91dfee98dda7305da77f95b09ee97b1f64e708955d3d203b84f"}]}
```

```kotlin [field-property box="rhino_roundtrip" name="code" path="rhino_roundtrip_code_.kt"]
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
```

