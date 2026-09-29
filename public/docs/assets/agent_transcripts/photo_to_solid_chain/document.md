# Field Document: photo_to_solid_chain

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. the same solid, marched — and is parallel = true reproducible?

<!-- field-box id="photo_levelset" index="0" -->

```edn [field-box id="photo_levelset" role="properties"]
{"id" "photo_levelset" "index" 0 "name" "the same solid, marched — and is parallel = true reproducible?" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the same solid, marched — and is parallel = true reproducible?" "_tombstone" false "frame" #afield/rect [60.0 3100.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "photo_levelset_code_.kt" "lines" 44 "characters" 2354 "sha256" "a65313cc59eec340b2edcd66a9543b2462d2fe8a0b545912574215a02a66de79"}]}
```

```kotlin [field-property box="photo_levelset" name="code" path="photo_levelset_code_.kt"]
import afield.examples.chain.PhotoToSolidChain
import afield.manifold.Solid
import afield.manifold.toSolid

// Two independent constructions of the same object: the grid triangulation, and a marched
// level set of the same depth field. Compared against EACH OTHER, not against a constant.
val mount = PhotoToSolidChain.mount()
val edge = 2.0 / 128
Solid.scope {
    // plaqueVolume, not volume: the field describes the plaque, and `volume` is the plaque
    // UNION the frame. Comparing against the wrong one of those reads as a 7% disagreement.
    val marched = PhotoToSolidChain.marchedPlaque(mount.depth, mount.footprintZ, edge, parallel = false)
    val apart = kotlin.math.abs(marched.volume - mount.plaqueVolume) / mount.plaqueVolume
    println("grid %.9f, marched %.9f — %.3f%% apart at edgeLength %.4f"
        .format(mount.plaqueVolume, marched.volume, apart * 100, edge))
    println("two constructions that share no code, agreeing to about one marching cell")
}

// Solid.levelSet's KDoc says parallel = true is not reproducible. Eight runs each way, in
// three regimes. `floor` erodes the plaque into islands, which is the only thing that
// changes between them — the field, the bounds and the edge length are identical.
val regimes = listOf(
    "the plaque itself" to -0.12,
    "eroded a little" to 0.10,
    "eroded to islands" to 0.14,
)
val rows = regimes.map { (label, floor) ->
    label to PhotoToSolidChain.reproducibilityCensus(mount.depth, mount.footprintZ, 2.0 / 64, floor)
}

// A table, not a paragraph: the whole point is that one column is constant and the other
// is not. An answer counts as the same only if triangles, components AND the volume to
// twelve places all agree, so this cannot report reproducibility that is not there.
markdown(buildString {
    appendLine("| regime | components | `parallel = false` | `parallel = true` |")
    appendLine("| --- | --- | --- | --- |")
    rows.forEach { (label, c) ->
        appendLine("| $label (floor %+.2f) | %d | **%s** distinct | **%s** distinct |"
            .format(c.floor, c.parts, c.sequentialRatio(), c.parallelRatio()))
    }
    appendLine()
    appendLine("8 runs each way per regime, edgeLength ${"%.4f".format(2.0 / 64)}, one depth field.")
    appendLine("Distinct answers are counted on (triangles, components, volume to 1e-12).")
}, width = 760)
```

## 2. the depth map as a closed relief plaque

<!-- field-box id="photo_plaque" index="1" -->

```edn [field-box id="photo_plaque" role="properties"]
{"id" "photo_plaque" "index" 1 "name" "the depth map as a closed relief plaque" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the depth map as a closed relief plaque" "_tombstone" false "frame" #afield/rect [60.0 1100.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "photo_plaque_code_.kt" "lines" 24 "characters" 1228 "sha256" "33225ce200e7ad186fcc503dfbde792333023587233a771ca16d518ba96c9935"}]}
```

```kotlin [field-property box="photo_plaque" name="code" path="photo_plaque_code_.kt"]
import afield.examples.chain.PhotoToSolidChain

// toReliefMesh is the hop that did not exist until this page: toHeightfieldMesh returns a
// *disc* — one boundary loop, chi = 1 — and Manifold refuses an open sheet.
val mount = PhotoToSolidChain.mount()
val relief = mount.relief
val topo = relief.topology()
println("relief ${relief.vertexCount} vertices, ${relief.triangleCount} triangles")
println("watertight=${topo.isWatertight} manifold=${topo.isManifold} chi=${topo.eulerCharacteristic}")
println("boundary loops=${topo.boundaryLoops.size} — a plaque has none, a heightfield has one")
println("volume ${"%.6f".format(relief.volume())}, and Manifold agrees to the last digit")

// The CPU rasteriser is AWT-free (PngEncoder), so it is safe inside a live box on macOS.
// Three angles, because a relief read from one is a picture and read from three is a shape.
fun view(yaw: Double, pitch: Double) =
    imgFromBytes(relief.renderPng(MeshView(yawDegrees = yaw, pitchDegrees = pitch, width = 760, height = 560)))

showImgGrid(
    "from above — the room, inside out" to view(0.0, 72.0),
    "three-quarter" to view(34.0, 30.0),
    "along the rim — the base slab" to view(88.0, 8.0),
    columns = 3,
    cellSide = 460,
)
```

## 3. mounted in a frame, and what the Boolean owes

<!-- field-box id="photo_boolean" index="2" -->

```edn [field-box id="photo_boolean" role="properties"]
{"id" "photo_boolean" "index" 2 "name" "mounted in a frame, and what the Boolean owes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "mounted in a frame, and what the Boolean owes" "_tombstone" false "frame" #afield/rect [60.0 2100.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "photo_boolean_code_.kt" "lines" 33 "characters" 1652 "sha256" "0301ac42623cd277a75ba0582e005b8f763b27e64e6d6b403c60019435504e88"}]}
```

```kotlin [field-property box="photo_boolean" name="code" path="photo_boolean_code_.kt"]
import afield.examples.chain.PhotoToSolidChain
import afield.manifold.Solid
import afield.manifold.toSolid

val mount = PhotoToSolidChain.mount()
println(mount.summary())

// Booleans, with the identities any two solids owe each other checked rather than trusted.
Solid.scope {
    val plaque = mount.relief.toSolid() + mount.frame.toSolid()
    val frame = PhotoToSolidChain.frameAround(2.0, mount.footprintZ)
    val union = plaque + frame
    val meet = plaque and frame
    println("frame genus ${frame.genus} — a slab with a window is a torus")
    println("mounted genus ${union.genus} in ${union.decompose().size} part — the plaque plugs it")
    println("union %.9f + meet %.9f = %.9f".format(union.volume, meet.volume, union.volume + meet.volume))
    println("plaque %.9f + frame %.9f = %.9f".format(plaque.volume, frame.volume, plaque.volume + frame.volume))
    println("inclusion-exclusion holds to ${"%.2e".format(kotlin.math.abs((union.volume + meet.volume) - (plaque.volume + frame.volume)))}")
}

// mount()'s meshes carry NO normals on purpose — splitting a vertex at a crease is what a
// renderer wants and is exactly what stops toSolid() accepting the mesh back. renderPng
// adds them itself, so nothing here has to care.
fun draw(m: TriMesh, yaw: Double = 30.0, pitch: Double = 34.0) =
    imgFromBytes(m.renderPng(MeshView(yawDegrees = yaw, pitchDegrees = pitch, width = 760, height = 560)))

showImgGrid(
    "relief = plaque - frame" to draw(mount.relief),
    "the frame, genus 1" to draw(mount.frame),
    "assembled, genus 0" to draw(triMesh { add(mount.relief); add(mount.frame) }),
    columns = 3,
    cellSide = 460,
)
```

## 4. a photograph, and how far away everything in it is

<!-- field-box id="photo_depth" index="3" -->

```edn [field-box id="photo_depth" role="properties"]
{"id" "photo_depth" "index" 3 "name" "a photograph, and how far away everything in it is" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a photograph, and how far away everything in it is" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "photo_depth_code_.kt" "lines" 18 "characters" 905 "sha256" "952d1b198e3d8a428619be9b62a28883192ceb0c3fd24aa24ee048201382a7d3"}]}
```

```kotlin [field-property box="photo_depth" name="code" path="photo_depth_code_.kt"]
import afield.examples.chain.PhotoToSolidChain

// No depth camera anywhere. One still photograph in, one single-channel map out.
val photo = imgFromFile(PhotoToSolidChain.PHOTO, 3).scaleBy(0.45)
val (depth, model) = PhotoToSolidChain.depthMapOf(photo)

// The output is inverse *relative* depth in arbitrary units, so it has to be normalised
// against a window; depthMapOf does that against this frame's own range and prints it.
// Larger means NEARER, which is why the near floor is the bright band at the bottom.
println("photo ${photo.width}x${photo.height} -> depth ${depth.width}x${depth.height}")
println("${model.lastInferenceMs}ms on ${model.activeProvider}, input forced to a multiple of 14")

showImgGrid(
    "photograph ${photo.width}x${photo.height}" to photo,
    "depth ${depth.width}x${depth.height} — bright is near" to depth.colormap("inferno"),
    columns = 2,
    cellSide = 520,
)
```

