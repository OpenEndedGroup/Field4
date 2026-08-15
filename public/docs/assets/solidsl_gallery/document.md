# Field Document: solidsl_gallery

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["cross_check" "glsl_backend" "refusals"] "properties" {"children" #{#afield/box "cross_check" #afield/box "glsl_backend" #afield/box "refusals"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. Solid notation — refusals, and the hybrid build

<!-- field-box id="refusals" index="1" -->

```edn [field-box id="refusals" role="properties"]
{"id" "refusals" "index" 1 "name" "Solid notation — refusals, and the hybrid build" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Solid notation — refusals, and the hybrid build" "_tombstone" false "frame" #afield/rect [80.0 280.0 380.0 50.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "refusals_code_.kt" "lines" 96 "characters" 4243 "sha256" "737aa9864a0cf563a05c8b29944b65f0c3ee69fc5589e012731d9f0cdcd2770c"}]}
```

```kotlin [field-property box="refusals" name="code" path="refusals_code_.kt"]
import afield.solidsl.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshView
import afield.stdlib.isWatertight

// ---- a part that is half CSG and half field -------------------------------------------
// A plate you could machine, unioned with a blob you could not: the fillet where the ball
// meets the post lies on NEITHER of them, so no sequence of unions, intersections and
// differences can produce it. The exact lowering says so instead of guessing.
val fillet = 0.28

val machinable = cuboid(3.2, 3.2, 0.5) - unionOf(
    listOf(-1.1 to -1.1, 1.1 to -1.1, -1.1 to 1.1, 1.1 to 1.1).map { (x, y) ->
        cylinder(2.0, 0.22, segments = 128).at(x, y, 0.0)
    },
)
val organic = smoothUnion(
    sphere(0.62, segments = 128).at(0.0, 0.0, 0.95),
    cylinder(1.6, 0.28, segments = 128).at(0.0, 0.0, 0.45),
    k = fillet,
)
val part = machinable + organic
// ---------------------------------------------------------------------------------------

run {

println("---- what this expression refuses, and why ----")
part.allRefusals().forEach {
    println("  ${it.node}")
    println("    ${it.why}")
    println("    closable: ${it.closable}${if (it.missing.isNotBlank()) " — needs ${it.missing}" else ""}")
}
if (part.allRefusals().isEmpty()) println("  (nothing — every node has an exact CSG form)")
println()

// There are two flavours here, and flattening them would lose the interesting one.
println("The catalogue, in full — try each of these:")
listOf<Pair<String, SolidExpr>>(
    "smoothUnion(a, b, k)" to smoothUnion(sphere(1.0), cuboid(1.0, 1.0, 1.0), k = 0.2),
    "gyroid()" to gyroid(),
    "sphere(1).rounded(0.1)" to sphere(1.0).rounded(0.1),
    "sphere(1).shelled(0.05)" to sphere(1.0).shelled(0.05),
).forEach { (label, e) ->
    println("  %-24s %s".format(label, e.exactRefusal()!!.why.take(96) + "…"))
}
println()
println("The last two are the interesting ones. They are NOT 'we did not get to it': an")
println("exact-looking answer is available — Minkowski sum with a sphere — and it is the")
println("WRONG answer, systematically, by up to that sphere's sagitta, in a direction that")
println("does not shrink as the march refines. Filing that beside a smooth blend would")
println("flatten a real distinction, so the reason says which kind it is.")
println()

if (!ManifoldLibrary.isAvailable) {
    println(ManifoldLibrary.describe())
    return@run
}

// ---- the hybrid: the level set applied at the SMALLEST subtree that needs it -----------
// A naive fallback asks "does the whole tree lower exactly?" and marches everything if the
// answer is no — so one smooth blend anywhere costs a hundred-thousand-triangle
// approximation of a plate that was perfectly exact. This walks down instead.
val q = SolidQuality(segments = 128, edgeLengthFraction = 1.0 / 90.0)
val build = part.buildSolid(q)
println("---- the hybrid build ----")
println(build.report())
println()

// What the naive version would have cost, measured rather than asserted.
val raw = part.bounds()!!
val edge = maxOf(raw.size.x, raw.size.y, raw.size.z) / 90.0
val naive = afield.manifold.Solid.levelSet(raw.expandedBy(2.0 * edge), edge, parallel = true) { x, y, z ->
    part.toLevelSetField().at(x, y, z)
}
println("hybrid  ${build.solid.triangleCount} triangles")
println("naive   ${naive.triangleCount} triangles for the same shape at the same edge length")
println("        — ${"%.2f".format(naive.triangleCount.toDouble() / build.solid.triangleCount)}x, and " +
    "the plate's six faces stay six faces")
println()

val mesh = build.solid.toTriMesh()
println("Field's own topology, sharing nothing with Manifold: watertight ${mesh.isWatertight()}")
println("status ${build.solid.status} — guaranteed closed however the field behaved")

showMesh(
    build.solid.toTriMesh(normals = true),
    channel = "hybrid",
    title = "exact where it can be, marched where it cannot",
    caption = "${build.solid.triangleCount} triangles &middot; the plate and its four bores are exact CSG; " +
        "only the blended post was marched &middot; the whole thing is still one guaranteed-closed solid",
    view = MeshView(yawDegrees = 36.0, pitchDegrees = 22.0, width = 720, height = 470),
)
naive.close()
build.close()

}
```

## 3. Solid notation — one shape, two evaluations

<!-- field-box id="cross_check" index="2" -->

```edn [field-box id="cross_check" role="properties"]
{"id" "cross_check" "index" 2 "name" "Solid notation — one shape, two evaluations" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Solid notation — one shape, two evaluations" "_tombstone" false "frame" #afield/rect [80.0 80.0 380.0 50.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cross_check_code_.kt" "lines" 103 "characters" 5167 "sha256" "68e06a3674f42378fd4012333bb20d9d2c0288c191500c2502e3fcbd3d10c780"}]}
```

```kotlin [field-property box="cross_check" name="code" path="cross_check_code_.kt"]
import afield.solidsl.*
import afield.manifold.ManifoldLibrary
import afield.stdlib.MeshView
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume

// ---- the shape, written once -------------------------------------------------------
// Everything below reads THIS. Change a number and both evaluations follow, because the
// shape is a value rather than a closure — which is the whole of what afield.solidsl is.
val boreRadius = 0.30      // the two side bores. Past ~0.55 they break into the slot;
                           // watch `genus` and the agreement react rather than the
                           // picture silently becoming wrong.
val slotWidth = 0.85
val quality = 192          // circular segments. This is the knob that sets the floor the
                           // two evaluations agree to — see the note further down.

val part =
    (cuboid(4.0, 2.6, 0.7) + cylinder(1.4, 0.8, segments = quality)) -
        cylinder(3.0, 0.42, segments = quality) -
        unionOf(
            listOf(-1.45, 1.45).map { cylinder(3.0, boreRadius, segments = quality).at(it, 0.0, 0.0) },
        ) -
        Profile.rect(2.9, slotWidth)
            .offset(0.22, segments = quality)
            .extruded(3.0)
            .at(0.0, 1.05, -1.5)
// -------------------------------------------------------------------------------------

run {

// A closure cannot do any of the next four lines. `sdfUnion(sdfSphere(1.0), …)` prints as
// "Function1<Vector3f, Float>", compares by identity, and cannot be asked how big it is.
println(part)
println()
println("nodes           ${part.nodeCount()}")
println("bounds          ${part.bounds()}")
println("GLSL            ${part.toGlsl().statementCount} statements, step scale ${part.toGlsl().stepScale}")
println("exact CSG path  ${part.exactRefusal()?.why ?: "available for every node"}")
println("field notes     ${part.fieldNotes().size} place(s) where the distance is only sign-correct")
part.fieldNotes().forEach { println("                - $it") }
println()

if (!ManifoldLibrary.isAvailable) {
    // Half the demonstration still works: the field lowering needs nothing installed.
    println(ManifoldLibrary.describe())
    println("Only one of the two evaluations is available, so there is nothing to cross-check.")
    showMesh(
        part.marchedMesh(SolidQuality(edgeLengthFraction = 1.0 / 110.0)),
        title = "afield.solidsl through marching cubes",
        caption = "Install Manifold (brew install manifold) and re-run to get the exact CSG " +
            "evaluation of the same expression beside it, and the agreement between them.",
        view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 720, height = 460),
    )
    return@run
}

// ---- and now the same expression, twice -----------------------------------------------
// Side A: the distance field, sampled on a grid by Field's own marching cubes, in Kotlin.
// Side B: Manifold's exact boolean kernel, in C++, through Panama/FFM.
val q = SolidQuality(segments = quality, edgeLengthFraction = 1.0 / 110.0)
val agreement = part.crossCheck(q)

// First: is side B actually independent of side A? Claiming "they share no code" is not
// worth much on its own — if this shape had refused the exact path, side B would be a level
// set over the SAME field side A samples, and the two would agree even if the field were
// wrong. So it is demonstrated rather than asserted, by a property the two paths cannot
// share: the exact lowering has no edgeLength input at all. Refine the march and a genuinely
// exact solid CANNOT move.
println("---- is the comparison independent? ----")
val coarse = part.buildSolid(SolidQuality(segments = quality, edgeLengthFraction = 1.0 / 40.0))
val fine = part.buildSolid(SolidQuality(segments = quality, edgeLengthFraction = 1.0 / 110.0))
val unmoved = coarse.solid.volume == fine.solid.volume &&
    coarse.solid.triangleCount == fine.solid.triangleCount
println("side B at h/40:  volume ${"%.9f".format(coarse.solid.volume)}, ${coarse.solid.triangleCount} triangles")
println("side B at h/110: volume ${"%.9f".format(fine.solid.volume)}, ${fine.solid.triangleCount} triangles")
println(
    if (unmoved) "bit-identical under a 2.75x refinement, so side B is exact CSG and owes side A nothing"
    else "MOVED with the march — this shape refused the exact path, so both sides are marching one " +
        "field and their agreement is self-consistency, not cross-validation",
)
coarse.close(); fine.close()
println()

println("---- the measurement ----")
println(agreement.report())

// The floor is not zero, and pretending otherwise would be the interesting lie. Manifold's
// circle is an inscribed n-gon; the field's is a circle. The deficit between them is
// computable from the segment count alone, and for a cylinder it is exact to every digit.
println("faceting floor at $quality segments: ${"%.3e".format(facetingDeficit(quality))}")
println("  (a cylinder is a right prism over that n-gon, so its own volume deficit IS this")
println("   number. Drop `quality` to 24 and watch the agreement above degrade to match.)")
println()

// A third opinion, on the marched surface, from code that shares nothing with either side.
val marched = part.marchedMesh(q)
println("Field's own topology on the marched surface: watertight ${marched.isWatertight()}, " +
    "genus ${marched.genus()}, signed volume ${"%.5f".format(marched.signedVolume())}")

val exact = part.exactSolid(q).orThrow()
showMesh(
    exact.toTriMesh(normals = true),
    channel = "exact",
    title = "B — Manifold's exact CSG",
    caption = "${exact.triangleCount} triangles &middot; volume ${"%.5f".format(exact.volume)} &middot; " +
        "genus ${exact.genus} &middot; every boolean guaranteed to close",
    view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 700, height = 440),
)
showMesh(
    marched,
    channel = "marched",
    title = "A — the same expression, marched from its distance field",
    caption = "${marched.triangleCount} triangles &middot; volume " +
        "${"%.5f".format(marched.signedVolume())} &middot; the two disagree by " +
        "${"%.3e".format(agreement.relativeVolumeError)} relative, and their surfaces are never " +
        "more than ${"%.4f".format(agreement.deviation)} apart",
    view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 700, height = 440),
)
exact.close()

}
```

## 4. Solid notation — the same source as a GLSL raymarcher

<!-- field-box id="glsl_backend" index="3" -->

```edn [field-box id="glsl_backend" role="properties"]
{"id" "glsl_backend" "index" 3 "name" "Solid notation — the same source as a GLSL raymarcher" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Solid notation — the same source as a GLSL raymarcher" "_tombstone" false "frame" #afield/rect [80.0 180.0 380.0 50.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "glsl_backend_code_.kt" "lines" 54 "characters" 2713 "sha256" "c1735ec8159ea4de4d1a827867a1fcd5ab2da3bfd6e6524e3e84b514063b8744"}]}
```

```kotlin [field-property box="glsl_backend" name="code" path="glsl_backend_code_.kt"]
import afield.solidsl.*

// ---- a shape that is worth ray-marching ----------------------------------------------
// A twisted star column standing in a bored plate, blended where they meet. Change the
// twist and re-run: the printed shader changes with it, because the shader is emitted
// from the expression rather than written beside it.
val twist = 140.0
val blend = 0.18

val column = Profile.star(6, 0.85, 0.42).extruded(2.4, twistDegrees = twist).at(0.0, 0.0, -1.2)
val plate = cuboid(3.6, 3.6, 0.5).at(0.0, 0.0, -1.35)
val scene = smoothUnion(column, plate, k = blend) - cylinder(4.0, 0.28, segments = 64)
// ---------------------------------------------------------------------------------------

val emitted = scene.toGlsl()

println("$emitted")
println()
println("A `(Vec3) -> Float` runs on the JVM and nowhere else. An expression tree walks")
println("into whatever language you like, and this is the third place this one goes:")
println()
println(emitted.glsl.lines().take(26).joinToString("\n"))
println("    ...  (${emitted.glsl.lines().size} lines in total)")
println()

// The emitted code is scalar single-assignment on purpose — no vec3 locals, no swizzles,
// no loops. Any driver re-vectorises it in the first pass, and what it buys is a grammar
// small enough that `SolidSLGlslTest` can parse the shader BACK and run it against the
// Kotlin field. 29 shapes, 58,000 sampled points, worst disagreement 6.3e-15. That is a
// measurement of the backend's fidelity, not a hope that two tree walks agree.
println("step scale ${emitted.stepScale} — ${if (emitted.notes.isEmpty()) "a true distance field, so sphere tracing is exact" else "the field over-estimates distance somewhere"}")
emitted.notes.forEach { println("  - $it") }
println()

// The simplifier, which only exists because the shape is a value.
println("---- what the simplifier can do ----")
val wasteful = differenceOf(
    listOf(scene) + (0 until 40).map { cylinder(3.0, 0.12, segments = 32).at(-8.0 + it * 0.42, 0.0, 0.0) },
)
println("40 extra bores, most of them nowhere near the part:")
println("  ${wasteful.simplificationReport()}")
println("  Disjointness is decided from symbolic bounds — nothing sampled, nothing built.")
println()

// And the rule that is genuinely strange: a blend of radius zero IS a union, and a union
// has an exact CSG form where a smooth blend never will. So simplifying can move an
// expression off the level-set fallback and onto the exact path.
val noBlend = smoothUnion(column, plate, k = 0.0)
println("a blend with k = 0:")
println("  ${noBlend.simplificationReport()}")
println()

println("---- the whole raymarch fragment, ready to paste ----")
println(scene.toRaymarchFragment())
```

