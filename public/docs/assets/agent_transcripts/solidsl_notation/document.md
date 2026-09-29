# Field Document: solidsl_notation

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. SolidSL — the same value, lowered to GLSL

<!-- field-box id="solidsl_shader" index="0" -->

```edn [field-box id="solidsl_shader" role="properties"]
{"id" "solidsl_shader" "index" 0 "name" "SolidSL — the same value, lowered to GLSL" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "SolidSL — the same value, lowered to GLSL" "_tombstone" false "frame" #afield/rect [80.0 700.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "solidsl_shader_code_.kt" "lines" 61 "characters" 3032 "sha256" "eff37fb7f07e8cc030861efb0668894f24210191786482a5a61d8d1035ab1cb9"}]}
```

```kotlin [field-property box="solidsl_shader" name="code" path="solidsl_shader_code_.kt"]
import afield.solidsl.*

// ---- change the twist and re-run: the shader changes with it ----------------------
val twist = 140.0       // Degrees over the column's height.
val blend = 0.18
// -----------------------------------------------------------------------------------

// This box needs no Manifold, no GPU and no window. Lowering to GLSL is a tree walk, so
// it is the cheapest possible check that the emitter still tracks the language.
val column = Profile.star(6, 0.85, 0.42)
    .extruded(2.4, twistDegrees = twist)
    .at(0.0, 0.0, -1.2)
val plate = cuboid(3.6, 3.6, 0.5).at(0.0, 0.0, -1.35)
val scene = smoothUnion(column, plate, k = blend) - cylinder(4.0, 0.28, segments = 64)

val emitted = scene.toGlsl()

println("---- the expression, as a distance function ----")
println("function         ${emitted.functionName}")
println("statements       ${emitted.statementCount}")
println("step scale       ${emitted.stepScale}")
if (emitted.notes.isEmpty()) {
    println("notes            (none — a true distance field, so sphere tracing is exact)")
} else {
    println("notes:")
    emitted.notes.forEach { println("  - $it") }
}
println()

// The emitted code is scalar single-assignment on purpose: no vec3 locals, no swizzles,
// no loops. Any driver re-vectorises it in the first pass, and the small grammar is what
// lets the test suite parse the shader BACK and run it against the Kotlin field.
println("---- the first 20 lines of ${emitted.glsl.lines().size} ----")
println(emitted.glsl.lines().take(20).joinToString("\n"))
println("    ...")
println()

// ---- the simplifier, which only exists because the shape is a value ---------------
val wasteful = differenceOf(
    listOf(scene) + (0 until 40).map {
        cylinder(3.0, 0.12, segments = 32).at(-8.0 + it * 0.42, 0.0, 0.0)
    },
)
println("---- 40 extra bores, most of them nowhere near the part ----")
println(wasteful.simplificationReport())
println("Disjointness is decided from symbolic bounds. Nothing was sampled and nothing built.")
println()

// The rule that is genuinely strange, and worth trying in the box above too: a blend of
// radius zero IS a union, and a union has an exact CSG form where a smooth blend never
// will. So simplifying can move an expression off the level-set fallback onto the exact
// path — a rewrite that changes which BACKEND can take it.
val noBlend = smoothUnion(column, plate, k = 0.0)
println("---- the same scene with k = 0.0 ----")
println(noBlend.simplificationReport())
// Read those two lines together, because apart they look like a contradiction. The report
// ends `path level set -> exact`: that is the path of the SIMPLIFIED tree. The original
// tree still has a SmoothUnion node in it and still refuses. The rewrite is what removes
// the refusal, so you have to ask the simplified expression to see it gone.
println("  before simplifying: ${noBlend.exactRefusal()?.why ?: "(none)"}")
println("  after simplifying:  ${noBlend.simplified().exactRefusal()?.why ?: "(none — it lowers exactly)"}")
```

## 2. SolidSL — a part, and what the notation knows before it meshes

<!-- field-box id="solidsl_part" index="1" -->

```edn [field-box id="solidsl_part" role="properties"]
{"id" "solidsl_part" "index" 1 "name" "SolidSL — a part, and what the notation knows before it meshes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "SolidSL — a part, and what the notation knows before it meshes" "_tombstone" false "frame" #afield/rect [80.0 80.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "solidsl_part_code_.kt" "lines" 95 "characters" 4035 "sha256" "863ba45558be4fc26a70c6c8cab4f026bebba32d44291a7fc621de42b756cdfc"}]}
```

```kotlin [field-property box="solidsl_part" name="code" path="solidsl_part_code_.kt"]
import afield.solidsl.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshView
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume

// ---- the three parameters to change ----------------------------------------------
val boreRadius = 0.30   // Push toward 0.55 and the four bores break into each other and
                        // into the plate edge — watch `genus` change rather than the
                        // picture quietly becoming wrong.
val blend = 0.22        // The fillet where the ball meets the post. Set it to 0.0 and
                        // watch the refusal DISAPPEAR: a blend of radius zero is a union,
                        // and a union has an exact CSG form.
val quality = 96        // Segments on the round primitives.
// -----------------------------------------------------------------------------------

val plate = cuboid(3.2, 2.2, 0.42)
val bores = unionOf(
    listOf(-1.1 to -0.7, 1.1 to -0.7, -1.1 to 0.7, 1.1 to 0.7).map { (x, y) ->
        cylinder(2.0, boreRadius, segments = quality).at(x, y, 0.0)
    },
)
val boss = smoothUnion(
    sphere(0.42, segments = quality).at(0.0, 0.0, 0.62),
    cylinder(1.1, 0.26, segments = quality).at(0.0, 0.0, 0.30),
    k = blend,
)
val part = (plate - bores) + boss

run {

// ---- what the expression knows about itself, before anything is meshed ------------
// None of this samples the shape. It is all structural, which is the thing a closure
// cannot offer: `(Vec3) -> Float` can be asked for a value at a point and nothing else.
println("---- the expression, before it is a mesh ----")
println("nodes            ${part.nodeCount()}")
println("bounds           ${part.bounds()}")
println("simplifier       ${part.simplificationReport()}")
println()

println("---- what has no exact CSG form, and why ----")
val refusals = part.allRefusals()
if (refusals.isEmpty()) {
    println("  (nothing — every node lowers exactly; try blend = 0.22 again)")
} else {
    refusals.forEach {
        println("  ${it.node}")
        println("    ${it.why}")
        println("    closable: ${it.closable}")
    }
}
println()

val q = SolidQuality(segments = quality, edgeLengthFraction = 1.0 / 80.0)

if (!ManifoldLibrary.isAvailable) {
    // The fallback when Manifold is missing: the field lowering alone. Everything above
    // still ran, because none of it needed Manifold — the structural answers are free.
    println(ManifoldLibrary.describe())
    println("Showing the field lowering instead: one level set for the WHOLE part,")
    println("including the plate, whose six faces did not need approximating.")
    showMesh(
        part.marchedMesh(q),
        title = "the field lowering — everything marched, including the exact parts",
        caption = "Manifold is not installed. `brew install manifold` and re-run to get " +
            "the hybrid: exact CSG for the plate and its bores, marching only for the blend.",
        view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 720, height = 470),
    )
    return@run
}

// ---- the hybrid build: march the SMALLEST subtree that needs it -------------------
val build = part.buildSolid(q)
println("---- the hybrid build ----")
println(build.report())
println()

// Field's own topology, which shares no code with Manifold's.
val mesh = build.solid.toTriMesh()
println("watertight       ${mesh.isWatertight()}")
println("volume           ${"%.6f".format(mesh.signedVolume())}")
println("genus            ${mesh.genus()}")
println("status           ${build.solid.status} — closed however the field behaved")

showMesh(
    build.solid.toTriMesh(normals = true),
    title = "exact where it can be, marched where it cannot",
    caption = "${build.solid.triangleCount} triangles &middot; the plate and its four bores are " +
        "exact CSG &middot; only the blended boss was marched &middot; still one closed solid",
    view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 720, height = 470),
)
build.close()

}
```

