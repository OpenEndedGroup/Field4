# Field Document: geodesic_carving

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Counterflow double spiral: underfloor-heating pipe

<!-- field-box id="double-spiral" index="0" -->

### Narrative

From `code`

A 4 m by 2.8 m room, at 1:2; pipe every 0.08 units (16 cm).

```edn [field-box id="double-spiral" role="properties"]
{"id" "double-spiral" "index" 0 "name" "Counterflow double spiral: underfloor-heating pipe" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Counterflow double spiral: underfloor-heating pipe" "_tombstone" false "frame" #afield/rect [1500.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "double-spiral_code_.kt" "lines" 19 "characters" 915 "sha256" "737ca840e117f92b386f46c87b6dcf59b680ea35025e3a0d338f618df6d811c2"}]}
```

```kotlin [field-property box="double-spiral" name="code" path="double-spiral_code_.kt"]
// A 4 m by 2.8 m room, at 1:2; pipe every 0.08 units (16 cm).
val floor = heightfieldMesh(100, 70, 2.0, 1.4) { _, _ -> 0f }
val fromWalls = floor.geodesicDistanceFromBoundary()
val rings = floor.evenIsolines(fromWalls, 0.08).map { level -> level.maxBy { it.size } }
// Supply runs in to the bend in the middle, return runs back out between it.
val (supply, back) = doubleSpiralArms(rings)
var length = 0.0
for (arm in [supply, back]) for (i in 1 until arm.size) length += arm[i].distance(arm[i - 1])
println("${rings.size} rings, ${supply.size + back.size} points, ${(length * 2).f1} m of pipe")

// Seen from above: floor x to the right, floor z down the page.
fun plan(path: List<Vec3>) = path.map { vec(it.x * 300f, it.z * 300f, 0f) }
lines(
    plan(supply).toFLine3(vec(0.9, 0.25, 0.15, 1.0), 2.0),
    plan(back).toFLine3(vec(0.15, 0.4, 0.9, 1.0), 2.0),
    w = 640.0,
    h = 460.0,
    fitToSize = true,
)
```

## 2. Geodesic vs straight-line distance on a folded sheet

<!-- field-box id="geodesic-vs-euclidean" index="1" -->

```edn [field-box id="geodesic-vs-euclidean" role="properties"]
{"id" "geodesic-vs-euclidean" "index" 1 "name" "Geodesic vs straight-line distance on a folded sheet" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Geodesic vs straight-line distance on a folded sheet" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "geodesic-vs-euclidean_code_.kt" "lines" 35 "characters" 1471 "sha256" "15609ef8db7e2407d596a7ad464420cb08832dbd5e70f3952c07a0db155869ed"}]}
```

```kotlin [field-property box="geodesic-vs-euclidean" name="code" path="geodesic-vs-euclidean_code_.kt"]
import afield.graphics.three.ThreeCameraSpec

// A sheet with a deep fold down the middle: the far wall is close through the air and far along the stone.
val shell = heightfieldMesh(90, 60, 3.0, 2.0) { x, _ -> -1.1f * kotlin.math.exp(-(x * x) / 0.12f) }
val source = shell.nearestVertex(-0.9, 0, 0)

val straight = shell.euclideanDistance(source)
val alongSurface = shell.geodesicDistance(source)
println(
    "vertex across the fold: straight %.3f, along the surface %.3f"
        .format(
            straight[shell.nearestVertex(0.9, 0, 0)],
            alongSurface[shell.nearestVertex(0.9, 0, 0)],
        ),
)

// Same colour scale and the same 0.15 ring spacing on both, so the bands compare directly.
// The rings are lifted a hair off the sheet so the surface does not hide them.
val top = alongSurface.max()
val view = ThreeCameraSpec(yawDegrees = 20.0, pitchDegrees = 40.0)
three(height = 360) {
    columns(2)
    for ((i, panelled) in
        listOf("straight-line distance" to straight, "geodesic distance" to alongSurface).withIndex()) {
        val (title, d) = panelled
        panel(title = title, camera = view)
        mesh(shell.colouredBy(d, 0.0, top), wireframe = false, cullBackFaces = false)
        val rings = shell.evenIsolines(d, 0.15).flatten()
        lines(
            rings.map { r -> r.map { vec(it.x, it.y + 0.01f, it.z) }.toFLine3(vec(1.0, 1.0, 1.0, 0.95), 1.4) },
            id = "rings$i",
            panel = i,
        )
    }
}
```

## 3. Isoplanar roughing passes coloured by depth

<!-- field-box id="roughing-passes" index="2" -->

```edn [field-box id="roughing-passes" role="properties"]
{"id" "roughing-passes" "index" 2 "name" "Isoplanar roughing passes coloured by depth" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Isoplanar roughing passes coloured by depth" "_tombstone" false "frame" #afield/rect [1020.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "roughing-passes_code_.kt" "lines" 26 "characters" 1010 "sha256" "e392b4634090c8a0196ffbc6988e71f007d3a68c23a2ab425800fcc9ec8eb197"}]}
```

```kotlin [field-property box="roughing-passes" name="code" path="roughing-passes_code_.kt"]
import afield.graphics.three.ThreeCameraSpec

// A part modelled as a field: a ball fused to a bar, with a fillet.
val part = marchingCubes(
    sdfSmoothUnion(sdfSphere(0.7), sdfBox(vec(2.0, 0.35, 0.6)), k = 0.25),
    boundsAround(radius = 1.4),
    72,
)
val passes = part.roughingPasses(stepDown = 0.08)
println("${passes.size} passes, ${passes.sumOf { it.second.size }} contours")

// Colour each pass by its depth below the top, shallow pale to deep red.
val top = passes.first().first
val bottom = passes.last().first
val contours = passes.flatMap { (h, rings) ->
    val s = (top - h) / (top - bottom)
    rings.map { it.toFLine3(vec(1.0, 0.85 - 0.6 * s, 0.6 - 0.5 * s, 1.0), 1.4) }
}
val view = ThreeCameraSpec(yawDegrees = 30.0, pitchDegrees = 30.0)
three(height = 360) {
    columns(2)
    panel(title = "the part", camera = view)
    mesh(part, wireframe = false)
    panel(title = "${passes.size} roughing passes, coloured by depth", camera = view)
    lines(contours, id = "passes", panel = 1)
}
```

## 4. Double spiral in an L-shaped room with a bay window

<!-- field-box id="double-spiral-room" index="3" -->

### Narrative

From `code`

An L-shaped living room with a bay window in the left wall, at 1:2.

```edn [field-box id="double-spiral-room" role="properties"]
{"id" "double-spiral-room" "index" 3 "name" "Double spiral in an L-shaped room with a bay window" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Double spiral in an L-shaped room with a bay window" "_tombstone" false "frame" #afield/rect [1980.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "double-spiral-room_code_.kt" "lines" 23 "characters" 1290 "sha256" "7c9dd29d4075083fa246958ed2595be2f7b5cf62cb3fe561e1763c936b2862a9"}]}
```

```kotlin [field-property box="double-spiral-room" name="code" path="double-spiral-room_code_.kt"]
// An L-shaped living room with a bay window in the left wall, at 1:2.
val bay =
    (0..16).map { i ->
        val a = kotlin.math.PI * i / 16
        vec(-1.0 - 0.3 * kotlin.math.sin(a), 0.25 + 0.25 * kotlin.math.cos(a))
    }
val plan = [vec(-1.0, -0.7), vec(1.0, -0.7), vec(1.0, 0.1), vec(0.1, 0.1), vec(0.1, 0.7), vec(-1.0, 0.7)] + bay
val floor = outlineMesh(plan, 0.0125)
val angle = floor.windingAngle(floor.geodesicDistanceFromBoundary())
val (supply, back) = angle.fieldDoubleSpiral(0.07)
var length = 0.0
for (arm in [supply, back]) for (i in 1 until arm.size) length += arm[i].distance(arm[i - 1])
println("${supply.size + back.size} points, ${(length * 2).f1} m of pipe")

// The winding angle's spokes, faintly, under the pipe and the walls.
fun drawn(path: List<Vec3>) = path.map { vec(it.x * 300f, it.z * 300f, 0f) }
val shown = ArrayList<FLine>()
for (k in 1..11) for (spoke in angle.mesh.isolines(angle.angle, k / 12.0)) shown +=
    drawn(spoke).toFLine3(vec(0.3, 0.3, 0.3, 1.0), 1.0)
shown += drawn((plan + plan.first()).map { vec(it.x, 0f, it.y) }).toFLine3(vec(0.45, 0.45, 0.45, 1.0), 3.0)
shown += drawn(supply).toFLine3(vec(0.9, 0.25, 0.15, 1.0), 2.0)
shown += drawn(back).toFLine3(vec(0.15, 0.4, 0.9, 1.0), 2.0)
lines(shown, w = 640.0, h = 460.0, fitToSize = true)
```

## 5. The L-shaped room's double spiral, relaxed

<!-- field-box id="relaxed-spiral" index="4" -->

### Narrative

From `code`

The L-shaped room again: the field spiral, then the same pipe relaxed.

```edn [field-box id="relaxed-spiral" role="properties"]
{"id" "relaxed-spiral" "index" 4 "name" "The L-shaped room's double spiral, relaxed" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The L-shaped room's double spiral, relaxed" "_tombstone" false "frame" #afield/rect [2460.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "relaxed-spiral_code_.kt" "lines" 24 "characters" 1385 "sha256" "383ffb00cf9edc3341a75f1383585594bc29eacd6dd57758ee52622baa9cc0cb"}]}
```

```kotlin [field-property box="relaxed-spiral" name="code" path="relaxed-spiral_code_.kt"]
// The L-shaped room again: the field spiral, then the same pipe relaxed.
val bay =
    (0..16).map { i ->
        val a = kotlin.math.PI * i / 16
        vec(-1.0 - 0.3 * kotlin.math.sin(a), 0.25 + 0.25 * kotlin.math.cos(a))
    }
val plan = [vec(-1.0, -0.7), vec(1.0, -0.7), vec(1.0, 0.1), vec(0.1, 0.1), vec(0.1, 0.7), vec(-1.0, 0.7)] + bay
val floor = outlineMesh(plan, 0.0125)
val spacing = 0.07
val (supply, back) = floor.windingAngle(floor.geodesicDistanceFromBoundary()).fieldDoubleSpiral(spacing)
val wall = outlineWallDistance(plan)
val relaxed = relaxPath(supply, back, spacing, wall)
println("closest lap ${relaxed.closest.f3}, tightest bend ${relaxed.tightestBend.f3}, crossings ${relaxed.crossings}")

// Field spiral on the left, relaxed on the right.
val walls = (plan + plan.first()).map { vec(it.x, 0f, it.y) }
fun drawn(path: List<Vec3>, dx: Float) = path.map { vec(it.x * 300f + dx, it.z * 300f, 0f) }
val shown = ArrayList<FLine>()
for (dx in [0f, 780f]) shown += drawn(walls, dx).toFLine3(vec(0.45, 0.45, 0.45, 1.0), 3.0)
shown += drawn(supply, 0f).toFLine3(vec(0.9, 0.25, 0.15, 1.0), 2.0)
shown += drawn(back, 0f).toFLine3(vec(0.15, 0.4, 0.9, 1.0), 2.0)
shown += drawn(relaxed.first, 780f).toFLine3(vec(0.9, 0.25, 0.15, 1.0), 2.0)
shown += drawn(relaxed.second, 780f).toFLine3(vec(0.15, 0.4, 0.9, 1.0), 2.0)
lines(shown, w = 1000.0, h = 400.0, fitToSize = true)
```

## 6. Iso-geodesic rings joined into a spiral toolpath

<!-- field-box id="geodesic-spiral" index="5" -->

```edn [field-box id="geodesic-spiral" role="properties"]
{"id" "geodesic-spiral" "index" 5 "name" "Iso-geodesic rings joined into a spiral toolpath" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Iso-geodesic rings joined into a spiral toolpath" "_tombstone" false "frame" #afield/rect [540.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "geodesic-spiral_code_.kt" "lines" 25 "characters" 1228 "sha256" "3758671033cc2477e7e5622737cc14dcd535cd83ff3ee25e3e7db2785646ade1"}]}
```

```kotlin [field-property box="geodesic-spiral" name="code" path="geodesic-spiral_code_.kt"]
import afield.graphics.three.ThreeCameraSpec

// A carved boss: a dome with a ripple, on a square tablet.
val relief =
    heightfieldMesh(80, 80, 2.0, 2.0) { x, z ->
        val r = kotlin.math.sqrt(x * x + z * z)
        0.5f * kotlin.math.exp(-r * r * 2.2f) + 0.05f * kotlin.math.cos(r * 14f) * kotlin.math.exp(-r * 1.5f)
    }
val fromEdge = relief.geodesicDistanceFromBoundary()

// One ring a level, nearest the edge first; join them into one path.
val rings = relief.evenIsolines(fromEdge, 0.06).map { level -> level.maxBy { it.size } }
val spiral = spiralFromRings(rings)
println("${rings.size} rings, spiral of ${spiral.size} points")

fun lifted(path: List<Vec3>) = path.map { vec(it.x, it.y + 0.01f, it.z) }
val view = ThreeCameraSpec(yawDegrees = 25.0, pitchDegrees = 50.0)
three(height = 360) {
    columns(2)
    panel(title = "iso-geodesic rings from the border", camera = view)
    mesh(relief.colouredBy(fromEdge), wireframe = false)
    lines(rings.map { lifted(it).toFLine3(vec(1.0, 1.0, 1.0, 0.95), 1.4) }, id = "rings", panel = 0)
    panel(title = "one spiral of ${spiral.size} points, never lifting", camera = view)
    lines([spiral.toFLine3(vec(0.95, 0.35, 0.2, 1.0), 1.6)], id = "spiral", panel = 1)
}
```

