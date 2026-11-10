# Field Document: eigendrum

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - sixteen modes of a square

<!-- field-box id="ed_modes" index="0" -->

```edn [field-box id="ed_modes" role="properties"]
{"id" "ed_modes" "index" 0 "name" "1 - sixteen modes of a square" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - sixteen modes of a square" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ed_modes_code_.kt" "lines" 15 "characters" 551 "sha256" "a6aabb3aeeb9d0995143e72572458b81e9a9182a7e27a94994a036f5cb1b9892"}]}
```

```kotlin [field-property box="ed_modes" name="code" path="ed_modes_code_.kt"]
import afield.audio.modal.*

// A unit-square drum. Its lowest sixteen modes, each a row you can play on its own;
// the analytic spectrum is π²(m² + n²), so λ₁ ≈ 19.74.
val square = drumMesh(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), 0.04)
val modes = square.modes(16)
display("modes", modes.modeTable(fundamental = 110.0))
lines(
    (0 until 8).flatMap { k ->
        modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add((k % 4) * 1.1f, (k / 4) * 1.1f, 0f) } } }
    },
    w = 520.0,
    h = 270.0,
    fitToSize = true,
)
```

## 2. 5 - a drum from your drawing

<!-- field-box id="ed_draw" index="1" -->

```edn [field-box id="ed_draw" role="properties"]
{"id" "ed_draw" "index" 1 "name" "5 - a drum from your drawing" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - a drum from your drawing" "_tombstone" false "frame" #afield/rect [740.0 1200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ed_draw_code_.kt" "lines" 26 "characters" 1211 "sha256" "300b83274cd85e94f6e162fdd59ef7f930f01bc27dcaafeff3759865c8c4ac9f"}]}
```

```kotlin [field-property box="ed_draw" name="code" path="ed_draw_code_.kt"]
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// Draw a closed shape on the drawing layer (Alt+D, freedraw), then run this box.
// With nothing drawn yet it uses a heart-ish polar curve.
val stroke = notation.strokes.lastOrNull()?.toFLine()
val outline = stroke ?: polygonFLine(polarPolygon(200) { t -> 1 + 0.35 * sin(t) * abs(cos(t)) + 0.15 * cos(3 * t) })
val mesh = drumMeshFitted(outline, 32)
val modes = mesh.modes(12)
val pictures =
    (0 until 4).flatMap { k ->
        modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add(k * 1.15f, 0f, 0f) } } } +
            modes.nodalLines(k).copy().apply {
                transform { v -> v.add(k * 1.15f, 0f, 0f) }
                color = vec(1.0, 1.0, 1.0, 1.0)
            }
    }
lines(pictures, w = 520.0, h = 150.0, fitToSize = true)
val c = mesh.x.indices.filter { !mesh.boundary[it] }.minBy { abs(mesh.x[it] - 0.4) + abs(mesh.y[it] - 0.35) }
display(
    "strike",
    WavFloatArrayWriter.encodeMono16(modes.render(modes.strike(mesh.x[c], mesh.y[c]), fundamental = 130.0), 44100),
)
println(
    if (stroke == null) "no stroke yet: using the polar curve" else "drum from your stroke: ${mesh.nodeCount} nodes",
)
```

## 3. 4 - striking a nodal line

<!-- field-box id="ed_strike" index="2" -->

```edn [field-box id="ed_strike" role="properties"]
{"id" "ed_strike" "index" 2 "name" "4 - striking a nodal line" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - striking a nodal line" "_tombstone" false "frame" #afield/rect [60.0 1200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ed_strike_code_.kt" "lines" 39 "characters" 1488 "sha256" "1d954ed10ff0310744579d6ea753e61a676cfcd4b1d2dd24bc7c5aa2621dd77d"}]}
```

```kotlin [field-property box="ed_strike" name="code" path="ed_strike_code_.kt"]
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// A square drum struck on the centre, which lies on a nodal line of every mode with m or n even,
// and off-centre. The bars are how strongly each of the 16 modes rings.
val modes = drumMesh(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), 0.05).modes(16)
val spots = [0.5 to 0.5, 0.31 to 0.22]
val out = ArrayList<FLine>()
spots.forEachIndexed { s, (px, py) ->
    val dx = s * 2.4f
    out += modes.modeMesh(1).map { it.copy().apply { transform { v -> v.add(dx, 0f, 0f) } } }
    out +=
        modes.nodalLines(1).copy().apply {
            transform { v -> v.add(dx, 0f, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 1.5
        }
    out +=
        fline { circle(px + dx, py, 0.04) }
            .apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                filled = true
            }
    val amps = modes.strike(px, py)
    val peak = amps.maxOf { abs(it) }
    amps.forEachIndexed { k, a ->
        out +=
            fline {
                moveTo(dx + k * 0.06, -0.15)
                lineTo(dx + k * 0.06, -0.15 - 0.6 * abs(a) / peak)
            }
                .apply {
                    color = vec(1.0, 0.85, 0.3, 1.0)
                    thickness = 3.0
                }
    }
    display("strike ${s + 1}", WavFloatArrayWriter.encodeMono16(modes.render(amps, fundamental = 120.0), 44100))
}
lines(out, w = 520.0, h = 300.0, fitToSize = true)
```

## 4. 3 - two shapes, one spectrum

<!-- field-box id="ed_kac" index="3" -->

```edn [field-box id="ed_kac" role="properties"]
{"id" "ed_kac" "index" 3 "name" "3 - two shapes, one spectrum" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - two shapes, one spectrum" "_tombstone" false "frame" #afield/rect [1420.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ed_kac_code_.kt" "lines" 34 "characters" 1563 "sha256" "6c35f861a0f41ce01e7d2f1b19c86ab913aa88b53ff5e5a1a302057a3f5ade4f"}]}
```

```kotlin [field-property box="ed_kac" name="code" path="ed_kac_code_.kt"]
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// Gordon, Webb and Wolpert's isospectral pair: you cannot hear the shape of a drum.
val a = drumMesh(KacPair.drumA, 0.06).modes(10)
val b = drumMesh(KacPair.drumB, 0.06).modes(10)
// λ A and λ B share one colour range, so matching rows come out the same colour.
val spectra = table(
    "mode" to (1..10).toList(),
    "λ A" to a.lambda.take(10),
    "λ B" to b.lambda.take(10),
    "diff %" to (0 until 10).map { 100 * abs(a.lambda[it] - b.lambda[it]) / a.lambda[it] },
).format("λ A") { (it as Double).f3 }
    .format("λ B") { (it as Double).f3 }
    .format("diff %") { (it as Double).f3 }
    .heat("λ A", "λ B", by = HeatScope.TABLE)
    .heat("diff %", colormap = "magma")
display("spectra", spectra)
val outlineA = polygonFLine(KacPair.drumA).apply {
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val outlineB = polygonFLine(KacPair.drumB).apply {
    transform { v -> v.add(4f, 0f, 0f) }
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val shapeA = a.modeMesh(2)
val shapeB = b.modeMesh(2).map { it.copy().apply { transform { v -> v.add(4f, 0f, 0f) } } }
lines(shapeA + shapeB + listOf(outlineA, outlineB), w = 520.0, h = 220.0, fitToSize = true)
val strikeA = a.render(a.strike(1.5, 1.5, 0.05), fundamental = 140.0, seconds = 2.0)
val strikeB = b.render(b.strike(1.5, 1.5, 0.05), fundamental = 140.0, seconds = 2.0)
display("drum A", WavFloatArrayWriter.encodeMono16(strikeA, 44100))
display("drum B", WavFloatArrayWriter.encodeMono16(strikeB, 44100))
```

## 5. 6 - a live drum

<!-- field-box id="ed_live" index="4" -->

```edn [field-box id="ed_live" role="properties"]
{"id" "ed_live" "index" 4 "name" "6 - a live drum" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 - a live drum" "_tombstone" false "frame" #afield/rect [1420.0 1200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ed_live_code_.kt" "lines" 7 "characters" 359 "sha256" "ed4a33cc1079d3efb0bbcc90db48ed073d624f8732b8ae5aeb13ed0a49c8df8e"}]}
```

```kotlin [field-property box="ed_live" name="code" path="ed_live_code_.kt"]
import afield.audio.modal.*

// The flower drum as a live voice in the audio engine: it strikes itself every 1.5 s.
// Call drum.strike(x, y) from any box or callback to hit it somewhere else.
val modes = polarDrumMesh(0.06) { t -> 1 + 0.3 * cos(5 * t) }.modes(16)
val drum = ModalDrum(modes, fundamental = 110.0, every = 1.5, autoX = 0.35, autoY = 0.1)
drum
```

## 6. 2 - a polar drum and its mesh

<!-- field-box id="ed_polar" index="5" -->

```edn [field-box id="ed_polar" role="properties"]
{"id" "ed_polar" "index" 5 "name" "2 - a polar drum and its mesh" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - a polar drum and its mesh" "_tombstone" false "frame" #afield/rect [740.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ed_polar_code_.kt" "lines" 18 "characters" 786 "sha256" "0fc6071cf3d7439e4dd42f61c80ce21d1d98f4ed2ad887f3eddfd353dc7f4f43"}]}
```

```kotlin [field-property box="ed_polar" name="code" path="ed_polar_code_.kt"]
import afield.audio.modal.*

// r(θ) = 1 + 0.3 cos 5θ: a five-lobed drum. Left, the triangle mesh;
// right, its sixth mode coloured by displacement, nodal lines in white.
val mesh = polarDrumMesh(0.06) { t -> 1 + 0.3 * cos(5 * t) }
val modes = mesh.modes(8)
val wire = mesh.wireframe().apply {
    color = vec(0.8, 0.8, 0.8, 0.6)
    thickness = 0.5
}
val shape = modes.modeMesh(5).map { it.copy().apply { transform { v -> v.add(3f, 0f, 0f) } } }
val nodes = modes.nodalLines(5).copy().apply {
    transform { v -> v.add(3f, 0f, 0f) }
    color = vec(1.0, 1.0, 1.0, 1.0)
    thickness = 1.5
}
lines(listOf(wire) + shape + listOf(nodes), w = 520.0, h = 260.0, fitToSize = true)
println("${mesh.nodeCount} nodes, ${mesh.triangleCount} triangles, ratios ${modes.ratios().map { it.f3 }}")
```

## 7. 7 - a drum that changes shape

<!-- field-box id="ed_morph" index="6" -->

```edn [field-box id="ed_morph" role="properties"]
{"id" "ed_morph" "index" 6 "name" "7 - a drum that changes shape" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 - a drum that changes shape" "_tombstone" false "frame" #afield/rect [1420.0 1500.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ed_morph_code_.kt" "lines" 21 "characters" 1220 "sha256" "46953554085bc6d49fb245edf7b831c45b6ac82f9cf03bb877ddb890d1f06d13"}]}
```

```kotlin [field-property box="ed_morph" name="code" path="ed_morph_code_.kt"]
import afield.audio.modal.*

// Nine drums from a circle to a five-lobed flower. The voice glides through them and back
// every 4 s, striking itself as it goes, so every mode's pitch bends with the outline.
val shapes = (0..8).map { i -> polarDrumMesh(0.07) { t -> 1 + 0.05 * i * cos(5 * t) }.modes(12) }
val outlines = shapes.flatMapIndexed { i, m ->
    m.modeMesh(0).map { it.copy().apply { transform { v -> v.add(i * 2.8f, 0f, 0f) } } }
}
lines(outlines, w = 620.0, h = 100.0, fitToSize = true)
// One row per shape; each mode's column is heated on its own, so you can watch every
// overtone fall as the lobes deepen. Mode 1 is the reference and stays plain.
val modeNames = ["f₁", "f₂", "f₃", "f₄", "f₅", "f₆"]
val ratios = table(
    "a" to shapes.indices.map { 0.05 * it },
    *modeNames.mapIndexed { k, name -> name to shapes.map { it.ratios()[k] } }.toTypedArray(),
).apply { title = "f / f₁ of modes 1–6" }
    .format("a") { (it as Double).f2 }
modeNames.forEach { name -> ratios.format(name) { (it as Double).f3 } }
ratios.heat(*modeNames.drop(1).toTypedArray())
display("ratios", ratios)
ModalDrum(shapes[0], fundamental = 110.0, every = 0.6, autoX = 0.3, autoY = 0.1, morph = shapes, morphPeriod = 4.0)
```

