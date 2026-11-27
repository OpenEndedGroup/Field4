# Field Document: one_cotangent_laplacian

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Heat geodesics and mean curvature from the same L

<!-- field-box id="heat-and-curvature" index="0" -->

### Narrative

From `code`

HeatGeodesics factors massScale·M + t·L built by cotangentLaplacianMatrix; meanCurvature applies L to positions.

```edn [field-box id="heat-and-curvature" role="properties"]
{"id" "heat-and-curvature" "index" 0 "name" "Heat geodesics and mean curvature from the same L" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Heat geodesics and mean curvature from the same L" "_tombstone" false "frame" #afield/rect [1260.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "heat-and-curvature_code_.kt" "lines" 30 "characters" 1209 "sha256" "0b15b4b5d109fbbf6234e84fcbab89113b8c58c74d24f7bfde64bf727d02a074"}]}
```

```kotlin [field-property box="heat-and-curvature" name="code" path="heat-and-curvature_code_.kt"]
// HeatGeodesics factors massScale·M + t·L built by cotangentLaplacianMatrix; meanCurvature applies L to positions.
val sphere = icosphereMesh(4, 1.0)
val lumpy = sphere.positions.copyOf()
for (v in 0 until sphere.vertexCount) {
    val bump = 1f + 0.08f * kotlin.math.sin(9f * sphere.x(v)) * kotlin.math.cos(7f * sphere.y(v))
    for (d in 0..2) lumpy[3 * v + d] *= bump
}
val ball = TriMesh(lumpy, sphere.indices.copyOf()).withVertexNormals()
val source = ball.nearestVertex(0, 1, 0)
val distance = ball.geodesicDistance(source)
val mean = ball.meanCurvature()
println(
    "farthest point %.3f along the surface; mean curvature %.2f to %.2f".format(distance.max(), mean.min(), mean.max()),
)

three(height = 340) {
    columns(2)
    panel(title = "geodesic distance from the north pole")
    mesh(ball.colouredBy(distance), wireframe = false)
    val rings = ball.evenIsolines(distance, 0.25).flatten()
    lines(
        rings.map { r ->
            r.map { vec(it.x * 1.01f, it.y * 1.01f, it.z * 1.01f) }.toFLine3(vec(1.0, 1.0, 1.0, 0.95), 1.4)
        },
        id = "rings",
        panel = 0,
    )
    panel(title = "mean curvature")
    mesh(ball.colouredBy(mean, -3.0, 4.0), wireframe = false)
}
```

## 2. A flat TriMesh through the Beltrami solver: square onto disc

<!-- field-box id="flat-harmonic-map" index="1" -->

```edn [field-box id="flat-harmonic-map" role="properties"]
{"id" "flat-harmonic-map" "index" 1 "name" "A flat TriMesh through the Beltrami solver: square onto disc" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A flat TriMesh through the Beltrami solver: square onto disc" "_tombstone" false "frame" #afield/rect [2460.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flat-harmonic-map_code_.kt" "lines" 31 "characters" 1400 "sha256" "2de10e2d71ff11e1712a505537f78c869ac38ce0ad66e40de091d49e25d205ba"}]}
```

```kotlin [field-property box="flat-harmonic-map" name="code" path="flat-harmonic-map_code_.kt"]
import afield.geometry.conformal.harmonicMap
import afield.geometry.conformal.toPlanarMesh

// planeMesh is a TriMesh; toPlanarMesh hands it to the conformal solvers, whose μ = 0 case is the same cotangent L.
val square = planeMesh(24, 24, 2.0, 2.0)
val planar = square.toPlanarMesh()
val disc =
    planar.boundary.associateWith { i ->
        val r = kotlin.math.hypot(planar.x[i], planar.y[i])
        doubleArrayOf(planar.x[i] / r, planar.y[i] / r)
    }
val map = harmonicMap(planar, disc)
println("${planar.vertexCount} vertices; mean |μ| of the harmonic map %.3f".format(map.meanModulus))

val positions = FloatArray(square.vertexCount * 3)
for (i in 0 until square.vertexCount) {
    positions[3 * i] = map.u[i].toFloat()
    positions[3 * i + 1] = map.v[i].toFloat()
}
val mapped = TriMesh(positions, square.indices.copyOf())
// A checkerboard on the square's own coordinates shows where each cell went.
val checker =
    DoubleArray(square.vertexCount) { i -> kotlin.math.sin(6.0 * square.x(i)) * kotlin.math.sin(6.0 * square.y(i)) }
val top = ThreeCameraSpec(yawDegrees = 0.0, pitchDegrees = 0.0)
three(height = 340) {
    columns(2)
    panel(title = "planeMesh(24, 24)", camera = top)
    mesh(square.colouredBy(checker, bands = 2), wireframe = false)
    panel(title = "harmonicMap onto the unit disc", camera = top)
    mesh(mapped.colouredBy(checker, bands = 2), wireframe = false)
}
```

## 3. The operator itself: weights, mass, and L on a lumpy sphere

<!-- field-box id="cotangent-operator" index="2" -->

### Narrative

From `code`

A lumpy icosphere, then the three pieces every cotangent caller now shares.

```edn [field-box id="cotangent-operator" role="properties"]
{"id" "cotangent-operator" "index" 2 "name" "The operator itself: weights, mass, and L on a lumpy sphere" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The operator itself: weights, mass, and L on a lumpy sphere" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cotangent-operator_code_.kt" "lines" 35 "characters" 1754 "sha256" "1391b0e5cf9cb4310002e5017bf4bd62b840816db92c6755207a5c9b7e3e2a84"}]}
```

```kotlin [field-property box="cotangent-operator" name="code" path="cotangent-operator_code_.kt"]
// A lumpy icosphere, then the three pieces every cotangent caller now shares.
val sphere = icosphereMesh(4, 1.0)
val lumpy = sphere.positions.copyOf()
for (v in 0 until sphere.vertexCount) {
    val bump = 1f + 0.08f * kotlin.math.sin(9f * sphere.x(v)) * kotlin.math.cos(7f * sphere.y(v))
    for (d in 0..2) lumpy[3 * v + d] *= bump
}
val ball = TriMesh(lumpy, sphere.indices.copyOf()).withVertexNormals()
// Fine noise on top of the lumps, for the smoother to take away.
val rough = ball.positions.copyOf()
for (i in rough.indices) rough[i] += 0.025f * kotlin.math.sin(37f * i)
val noisy = TriMesh(rough, ball.indices.copyOf()).withVertexNormals()
val topo = ball.topology()
val weights = topo.cotangentWeights()
val mass = ball.barycentricVertexAreas()
val laplacian = topo.cotangentLaplacianMatrix(weights)

// L annihilates constants, and the lumped mass adds up to the surface area.
var rowSumMax = 0.0
val rowSums = DoubleArray(ball.vertexCount)
for (col in 0 until laplacian.numCols) for (k in laplacian.col_idx[col] until laplacian.col_idx[col + 1]) rowSums[
    laplacian.nz_rows[k]] += laplacian.nz_values[k]
for (s in rowSums) rowSumMax = maxOf(rowSumMax, kotlin.math.abs(s))
println("${topo.edgeCount} edge weights, ${weights.count { it < 0 }} negative (obtuse pairs)")
println("sum of mass %.5f, surface area %.5f, max |L·1| %.2e".format(mass.sum(), ball.surfaceArea(), rowSumMax))

// The same weights drive the smoother: cotangent Taubin against the lumpy input.
val smoothed = noisy.taubinSmooth(20, cotangent = true)
three(height = 340) {
    columns(2)
    panel(title = "lumpy sphere with fine noise")
    mesh(noisy, wireframe = false)
    panel(title = "taubinSmooth(20, cotangent = true)")
    mesh(smoothed, wireframe = false)
}
```

