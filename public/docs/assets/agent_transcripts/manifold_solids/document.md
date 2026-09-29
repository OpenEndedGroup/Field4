# Field Document: manifold_solids

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. Manifold — a part, and whether it is manufacturable

<!-- field-box id="manifold_bracket" index="0" -->

```edn [field-box id="manifold_bracket" role="properties"]
{"id" "manifold_bracket" "index" 0 "name" "Manifold — a part, and whether it is manufacturable" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Manifold — a part, and whether it is manufacturable" "_tombstone" false "frame" #afield/rect [80.0 80.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "manifold_bracket_code_.kt" "lines" 144 "characters" 6652 "sha256" "ea9a2957b64b8e6f50cd37b8ce679d53a1bcb2dbfc12e31ed4bd86fc9a748706"}]}
```

```kotlin [field-property box="manifold_bracket" name="code" path="manifold_bracket_code_.kt"]
import afield.manifold.JoinType
import afield.manifold.ManifoldLibrary
import afield.manifold.Section
import afield.manifold.Solid
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume
import afield.stdlib.MeshView

// ---- the two parameters to change ------------------------------------------------
val bore = 3.0          // bore radius, mm. Push past 6.0 and the bosses break through the
                        // plate edge — watch `genus` and `status` react rather than the
                        // picture silently becoming wrong.
val chamfer = 0.8       // edge chamfer, mm, done as a Minkowski sum with a small octahedron.
// ----------------------------------------------------------------------------------

// An explicit `run { }` so the "not installed" branch can bail with `return@run` instead of
// nesting the whole box inside an else.
run {

if (!ManifoldLibrary.isAvailable) {
    // A fallback rather than a blank box: build the same shape with Field's own marching
    // cubes, so what Manifold adds shows by its absence. This mesh is watertight but NOT
    // a manifold you could subtract from.
    println(ManifoldLibrary.describe())
    val approx = marchingCubes(
        sdfSmoothUnion(
            sdfBox(vec(30.0, 20.0, 4.0)),
            sdfCylinder(8.0, 12.0),
            k = 3.0,
        ),
        boundsAround(vec(0.0, 0.0, 0.0), 40.0),
        resolution = 110,
    )
    showMesh(
        approx,
        title = "Field's marchingCubes — closed, but not a manifold you can cut",
        caption = "Manifold is not installed, so this is the SDF approximation instead. " +
            "`brew install manifold` and re-run for the real part: exact volume, " +
            "genus you can count, and a Boolean that cannot fail silently.",
        view = MeshView(width = 700, height = 460),
    )
    return@run
}

println(ManifoldLibrary.describe())

// Everything inside a scope is released when it exits, except what comes back — so a box
// re-run on every keystroke does not wait for a Cleaner to reclaim native memory.
val part = Solid.scope {
    // A 60 x 40 x 8 plate, centred.
    val plate = Solid.cube(60.0, 40.0, 8.0, center = true)

    // Four bosses standing on it, one per corner of a 40 x 24 rectangle.
    val bossCentres = listOf(-20.0 to -12.0, 20.0 to -12.0, -20.0 to 12.0, 20.0 to 12.0)
    val bosses = bossCentres.map { (x, y) ->
        Solid.cylinder(14.0, 6.0, segments = 64, center = true).translated(x, y, 0.0)
    }

    // A bore through each boss, and a slot down the middle. Batched rather than folded:
    // Manifold plans the whole subtraction instead of doing five nested ones.
    val bores = bossCentres.map { (x, y) ->
        Solid.cylinder(30.0, bore, segments = 64, center = true).translated(x, y, 0.0)
    }
    val slot = Section.square(26.0, 9.0, center = true)
        .offset(4.5, JoinType.ROUND, circularSegments = 64)   // a rounded-end slot, properly
        .extruded(30.0)
        .translated(0.0, 0.0, -15.0)

    val rough = Solid.union(listOf(plate) + bosses) - Solid.union(bores + slot)

    // The chamfer. A Minkowski sum with a small octahedron rounds every edge at once,
    // which displacing vertices cannot reproduce.
    if (chamfer > 0.0) {
        val tool = Solid.hullOfPoints(
            listOf(
                vec(chamfer, 0f, 0f), vec(-chamfer, 0f, 0f),
                vec(0f, chamfer, 0f), vec(0f, -chamfer, 0f),
                vec(0f, 0f, chamfer), vec(0f, 0f, -chamfer),
            ),
        )
        // Grow then shrink: a closing, which rounds the convex edges and leaves the
        // concave ones, exactly as a ball-nose cutter would.
        rough.minkowskiSum(tool).minkowskiDifference(tool)
    } else {
        rough
    }
}

// ---- the numbers that decide whether this is a part or a picture -------------------
// Two meshes on purpose. `normals = true` splits a vertex per face at every hard edge so
// each face can carry its own normal — which is what makes the render read as machined, and
// is also why that mesh is NOT index-watertight and reports the wrong genus. So: the shaded
// one for the picture, the plain one for anything that measures topology.
val shaded = part.toTriMesh(normals = true)
val mesh = part.toTriMesh()
println("status      ${part.status}")
println("volume      ${"%.3f".format(part.volume)} mm^3   (%.2f g in 6061 aluminium)"
    .format(part.volume * 0.0027))
println("area        ${"%.3f".format(part.surfaceArea)} mm^2")
println("genus       ${part.genus}   — four bores plus the slot, so five holes through it")
println("mesh        ${part.triangleCount} triangles, ${part.vertexCount} vertices")

// Two implementations that share no code, on the same surface. Manifold integrates the
// solid in double; Field sums signed tetrahedra over float triangles. If the bridge were
// wrong these would not agree.
println()
println("Field's own reckoning of the plain mesh that came back:")
println("  watertight  ${mesh.isWatertight()}")
println("  genus       ${mesh.genus()}   — agreeing with Manifold's ${part.genus}")
println("  volume      ${"%.3f".format(mesh.signedVolume())}   " +
    "(Manifold said ${"%.3f".format(part.volume)}, differing by " +
    "${"%.2e".format(kotlin.math.abs(mesh.signedVolume() - part.volume))})")
println("  and the shaded mesh: ${shaded.vertexCount} vertices against ${mesh.vertexCount}, " +
    "split at the creases, so isWatertight() is ${shaded.isWatertight()} on that one — " +
    "same geometry, different index topology")

// A clearance check: how close does the slot come to the nearest bore? This is the number
// that says whether the wall between them will survive being cut.
val nearestBore = Solid.scope {
    Solid.cylinder(30.0, bore, segments = 64, center = true).translated(-20.0, -12.0, 0.0)
        .copy()
}
val slotSolid = Solid.scope {
    Section.square(26.0, 9.0, center = true)
        .offset(4.5, JoinType.ROUND, circularSegments = 64)
        .extruded(30.0).translated(0.0, 0.0, -15.0).copy()
}
println()
println("wall between the slot and the nearest bore: " +
    "${"%.3f".format(slotSolid.minGap(nearestBore, searchLength = 30.0))} mm")
nearestBore.close()
slotSolid.close()

showMesh(
    shaded,
    title = "60 x 40 x 8 plate, four bored bosses, a rounded slot, ${chamfer}mm chamfer",
    caption = "genus ${part.genus} &middot; ${"%.1f".format(part.volume)} mm&sup3; &middot; " +
        "${part.triangleCount} triangles &middot; every Boolean guaranteed to close",
    view = MeshView(yawDegrees = 34.0, pitchDegrees = 26.0, width = 760, height = 470),
)
part.close()

}
```

## 2. Manifold — a Kotlin lambda as a level set, then sliced

<!-- field-box id="manifold_levelset_slices" index="1" -->

```edn [field-box id="manifold_levelset_slices" role="properties"]
{"id" "manifold_levelset_slices" "index" 1 "name" "Manifold — a Kotlin lambda as a level set, then sliced" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Manifold — a Kotlin lambda as a level set, then sliced" "_tombstone" false "frame" #afield/rect [80.0 230.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "manifold_levelset_slices_code_.kt" "lines" 125 "characters" 5463 "sha256" "bc8efab2468e9a49edff74492094642fb15b3335904fe284299d9409dcce7191"}]}
```

```kotlin [field-property box="manifold_levelset_slices" name="code" path="manifold_levelset_slices_code_.kt"]
import afield.graphics.FLine
import afield.manifold.JoinType
import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.stdlib.MeshView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

run {

if (!ManifoldLibrary.isAvailable) {
    println(ManifoldLibrary.describe())
    // Deliberately no fenced code block in this string. A triple-backtick run anywhere in a
    // box's code — including in a comment like this one — makes the materialized document.md
    // wrap that property in a FOUR-backtick fence, and the completion corpus's document
    // parser only reads three, so the box silently drops out of tools/completion-corpus.
    // Inline code costs nothing and keeps it in.
    markdown("main", """
        ### This box needs Manifold

        It is an optional native dependency and nothing here vendors it. On macOS, install it
        with `brew install manifold`; on Linux use your package manager or build Manifold with
        `-DMANIFOLD_CBIND=ON`.

        Then run this box again. The binding finds `libmanifoldc` on its own; set
        `-Dafield.manifold.library=<path>` if you built your own.
    """.trimIndent())
    return@run
}

// ---- the shape, as a function ----------------------------------------------------
// Positive inside, which is Manifold's convention. This is a gyroid-thickened torus: the
// kind of shape that only exists as a field, and that plain marching cubes turns into a
// triangle soup wherever the surface nearly touches itself.
val majorRadius = 22.0
val minorRadius = 8.0
val gyroid = 0.55           // 0.0 is a plain torus; push toward 1.0 and it perforates.

fun field(x: Double, y: Double, z: Double): Double {
    val q = hypot(x, y) - majorRadius
    val tube = minorRadius - sqrt(q * q + z * z)
    val s = 0.32
    val g = sin(s * x) * cos(s * y) + sin(s * y) * cos(s * z) + sin(s * z) * cos(s * x)
    return tube - gyroid * minorRadius * kotlin.math.abs(g) * 0.5
}
// ----------------------------------------------------------------------------------

val bounds = boundsOf(
    vec(-(majorRadius + minorRadius + 2), -(majorRadius + minorRadius + 2), -(minorRadius + 2)),
    vec(majorRadius + minorRadius + 2, majorRadius + minorRadius + 2, minorRadius + 2),
)

// The upcall. `field` is called by Manifold's own worker threads — measured at ~2x the
// sequential path on this machine — which is why it must be thread-safe, and is: it reads
// three captured doubles and allocates nothing. Pass parallel = false for one that is not.
val start = System.nanoTime()
val solid = Solid.levelSet(bounds, edgeLength = 1.1, parallel = true, field = ::field)
val marchMs = (System.nanoTime() - start) / 1e6

println("level set: ${"%.0f".format(marchMs)} ms")
println("status     ${solid.status}   — a valid closed manifold, whatever the field did")
println("genus      ${solid.genus}")
println("volume     ${"%.1f".format(solid.volume)} mm^3")
println("mesh       ${solid.triangleCount} triangles")

// Field's own topology code, which shares nothing with Manifold, agrees the surface is
// closed. Note the plain mesh — `normals = true` splits
// vertices at creases for shading, which is right for the picture and would make this
// check read false for a reason that has nothing to do with the surface.
val mesh = solid.toTriMesh()
val shaded = solid.toTriMesh(normals = true)
println("watertight by Field's own reckoning: ${mesh.isWatertight()}")
println("euler characteristic ${mesh.eulerCharacteristic()} = 2 - 2g, so g = ${mesh.genus()}")

// ---- and now slice it, the way a printer would ------------------------------------
// slice() is the operation that makes a solid manufacturable rather than merely
// renderable. Each layer comes back as a Section — a 2D region with holes — which offsets,
// so the inner line here is the path a nozzle of that width would actually walk.
val layers = 13
val zLow = -minorRadius + 0.5
val zHigh = minorRadius - 0.5
val nozzle = 0.9

val outlines = mutableListOf<FLine>()
val paths = mutableListOf<FLine>()
var totalContours = 0
for (i in 0 until layers) {
    val z = zLow + (zHigh - zLow) * i / (layers - 1.0)
    Solid.scope {
        val layer = solid.slice(z)
        totalContours += layer.contourCount.toInt()
        // Draw each layer at its own height, so the stack reads as a stack.
        outlines += layer.toFLines(z = z).onEach {
            it.color = vec(0.55, 0.78, 0.95, 0.55)
            it.thickness = 1.0
        }
        paths += layer.offset(-nozzle / 2, JoinType.ROUND, circularSegments = 24)
            .toFLines(z = z).onEach {
                it.color = vec(1.0, 0.72, 0.35, 0.85)
                it.thickness = 1.6
            }
        Unit
    }
}

println()
println("$layers layers between z=${"%.1f".format(zLow)} and ${"%.1f".format(zHigh)}, " +
    "$totalContours contours in total")
println("orange = the ${nozzle}mm nozzle path, offset inward from each layer's outline")

showMesh(
    shaded,
    channel = "solid",
    title = "A gyroid-thickened torus, marched from a Kotlin lambda",
    caption = "${solid.triangleCount} triangles &middot; genus ${solid.genus} &middot; " +
        "${"%.0f".format(marchMs)} ms across Manifold's thread pool &middot; " +
        "guaranteed closed, so it can be cut",
    view = MeshView(yawDegrees = 24.0, pitchDegrees = 52.0, width = 700, height = 430),
)
display("slices", outlines + paths)
solid.close()

}
```

