# Field Document: penrose_spectra

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - counting the E = 0 states

<!-- field-box id="ps_count" index="0" -->

### Narrative

From `code`

Exact counts of E = 0 states on Penrose patches, beside a periodic square lattice and a
jittered 5-fold tiling with the same number of vertices.

```edn [field-box id="ps_count" role="properties"]
{"id" "ps_count" "index" 0 "name" "1 - counting the E = 0 states" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - counting the E = 0 states" "_tombstone" false "frame" #afield/rect [60.0 60.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_count_code_.kt" "lines" 22 "characters" 1255 "sha256" "d8aa59cb2d343409f998e62510bc7ec745a783fc986d8438fe52a90ed0b7dca1"}]}
```

```kotlin [field-property box="ps_count" name="code" path="ps_count_code_.kt"]
// Exact counts of E = 0 states on Penrose patches, beside a periodic square lattice and a
// jittered 5-fold tiling with the same number of vertices.
val radii = [6.0, 14.0, 20.0, 28.0, 36.0]
val penrose = radii.map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0).vertexGraph() }
val square = penrose.map { TilingGraph.squareLattice(sqrt(it.vertexCount / PI)) }
val jittered = radii.map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0, disorder = 0.1).vertexGraph() }
val counts = table(
    "vertices" to penrose.map { it.vertexCount },
    "E = 0 states" to penrose.map { it.zeroModeCount() },
    "Penrose" to penrose.map { it.zeroModeFraction() },
    "square lattice" to square.map { it.zeroModeFraction() },
    "jittered 0.1" to jittered.map { it.zeroModeFraction() },
).apply { title = "fraction of states at E = 0" }
["Penrose", "square lattice", "jittered 0.1"].forEach { col -> counts.format(col) { (it as Double).f4 } }
display("counts", counts)
val fit = ZeroModeFit.of(penrose.takeLast(3))
val tau = (1 + sqrt(5.0)) / 2
markdown(
    "fit",
    "bulk fit over the three largest: **%.4f** · square lattice: **%.4f** · 81 − 50τ = **%.4f**"
        .format(fit.bulk, ZeroModeFit.of(square.takeLast(3)).bulk, 81 - 50 * tau),
)
```

## 2. 5 - strike three vertices

<!-- field-box id="ps_strike" index="1" -->

```edn [field-box id="ps_strike" role="properties"]
{"id" "ps_strike" "index" 1 "name" "5 - strike three vertices" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - strike three vertices" "_tombstone" false "frame" #afield/rect [60.0 1460.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_strike_code_.kt" "lines" 56 "characters" 2145 "sha256" "b12898ae4503fd41592e5703a4666ff577e46a61d7d59183ea9bd5d340f0b930"}]}
```

```kotlin [field-property box="ps_strike" name="code" path="ps_strike_code_.kt"]
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// A Penrose patch and a square lattice as spring networks with equal stiffness, so E = 0 rings
// at 220 Hz in both. Each row, top to bottom: strike energy against pitch for one struck vertex.
val penrose = PenroseSpectraDocs.penrose()
val square = PenroseSpectraDocs.square()
val pm = penrose.springModes(PenroseSpectraDocs.STIFFNESS)
val sm = square.springModes(PenroseSpectraDocs.STIFFNESS)
val strikes = [
    Triple("Penrose, most confined weight", pm, PenroseSpectraDocs.strikeVertex(penrose, pm, true)),
    Triple("Penrose, seven-edged vertex", pm, PenroseSpectraDocs.strikeVertex(penrose, pm, false)),
    Triple("square lattice, centre", sm, square.nearest(square.centre.first, square.centre.second)),
]
val out = ArrayList<FLine>()
val lo = 120.0
val hi = 340.0
strikes.forEachIndexed { row, (name, modes, v) ->
    val a = modes.strikeVertex(v)
    val f = modes.frequencies(220.0)
    val bins = DoubleArray(110)
    for (k in a.indices) bins[((f[k] - lo) / (hi - lo) * bins.size).toInt().coerceIn(0, bins.size - 1)] += a[k]
    val y0 = row * 1.3
    bins.forEachIndexed { b, e ->
        if (e > 0)
            out +=
                fline {
                    moveTo(b * 0.05, y0)
                    lineTo(b * 0.05, y0 - sqrt(e))
                }
                    .apply {
                        color = vec(1.0, 0.55, 0.15, 1.0)
                        thickness = 3.0
                    }
    }
    out +=
        fline {
            moveTo((220.0 - lo) / (hi - lo) * 5.5, y0 + 0.05)
            lineTo((220.0 - lo) / (hi - lo) * 5.5, y0 + 0.2)
        }
            .apply {
                color = vec(1.0, 1.0, 1.0, 1.0)
                thickness = 2.0
            }
    display(
        name,
        WavFloatArrayWriter.encodeMono16(
            modes.render(a, zeroHz = 220.0, seconds = 3.0, alpha = 1.0, beta = 0.2),
            44100,
        ),
    )
}
lines(out, w = 600.0, h = 300.0, fitToSize = true)
strikes.joinToString("\n") { (name, modes, v) ->
    "$name: %.1f%% of the strike at 220 Hz".format(100 * modes.zeroShare(v))
}
```

## 3. 6 - drag symmetry and disorder

<!-- field-box id="ps_slider" index="2" -->

### Narrative

From `code`

Drag the sliders: the tiling is rebuilt, its E = 0 states counted exactly and their weight drawn
on the vertices. 5-fold with no disorder is the Penrose tiling, at about 0.098.

```edn [field-box id="ps_slider" role="properties"]
{"id" "ps_slider" "index" 2 "name" "6 - drag symmetry and disorder" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 - drag symmetry and disorder" "_tombstone" false "frame" #afield/rect [700.0 1460.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_slider_code_.kt" "lines" 33 "characters" 1256 "sha256" "2a8b614900ae352809b47665b6b6bffe3edb53ba83a095928d0bde82ee434ece"}]}
```

```kotlin [field-property box="ps_slider" name="code" path="ps_slider_code_.kt"]
// Drag the sliders: the tiling is rebuilt, its E = 0 states counted exactly and their weight drawn
// on the vertices. 5-fold with no disorder is the Penrose tiling, at about 0.098.
var symmetry = 5.0
var disorder = 0.0

val draw = {
    val tiling = deBruijnTiling(symmetry = symmetry.toInt(), radius = 25.0 / symmetry, scale = 1.0, disorder = disorder)
    val graph = tiling.vertexGraph()
    val weight = graph.spectrum().zeroWeights()
    val grey = [vec(0.30, 0.31, 0.36, 1.0), vec(0.22, 0.23, 0.28, 1.0)]
    lines(tiling.lines(palette = grey) + graph.vertexDots(weight), w = 480.0, h = 480.0, fitToSize = true)
    markdown(
        "measure",
        "%d-fold, disorder %.2f: **%d** E = 0 states of %d vertices, fraction **%.4f** (Penrose: 81 − 50τ = 0.0983)"
            .format(
                symmetry.toInt(),
                disorder,
                graph.zeroModeCount(),
                graph.vertexCount,
                graph.zeroModeFraction(),
            ),
    )
}

slider("symmetry", min = 3.0, max = 12.0, step = 1.0, initialValue = 5.0, channel = "symmetry") {
    symmetry = it
    draw()
}
slider("disorder", min = 0.0, max = 0.8, step = 0.01, initialValue = 0.0, channel = "disorder") {
    disorder = it
    draw()
}
draw()
```

## 4. 3 - integrated density of states

<!-- field-box id="ps_idos" index="3" -->

### Narrative

From `code`

N(E) for a radius-8 Penrose patch: every state (grey), and states weighted by their share in
the patch's inner 60% (orange). Left, the whole spectrum; right, E from -0.5 to 0.5.

```edn [field-box id="ps_idos" role="properties"]
{"id" "ps_idos" "index" 3 "name" "3 - integrated density of states" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - integrated density of states" "_tombstone" false "frame" #afield/rect [60.0 760.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_idos_code_.kt" "lines" 44 "characters" 1858 "sha256" "e1d823ef3e6a14fc782214942a2f6cbda8cff611ef890aedfc8882241258371f"}]}
```

```kotlin [field-property box="ps_idos" name="code" path="ps_idos_code_.kt"]
// N(E) for a radius-8 Penrose patch: every state (grey), and states weighted by their share in
// the patch's inner 60% (orange). Left, the whole spectrum; right, E from -0.5 to 0.5.
val graph = deBruijnTiling(symmetry = 5, radius = 8.0, scale = 1.0).vertexGraph()
val spectrum = graph.spectrum()
val inner = graph.interior(0.6 * graph.extent)
val bulk = spectrum.weightOn(inner)
val grey = vec(0.62, 0.62, 0.68, 1.0)
val orange = vec(1.0, 0.55, 0.15, 1.0)
// One panel: both curves over [from, to], with a white tick at E = 0, shifted right by dx.
fun panel(from: Double, to: Double, dx: Double): List<FLine> {
    val all = spectrum.idosLine(300.0, 300.0, from = from, to = to).apply {
        color = grey
        thickness = 3.0
    }
    val weighted = spectrum.idosLine(300.0, 300.0, weights = bulk, from = from, to = to).apply {
        color = orange
        thickness = 1.5
    }
    val zeroX = -from / (to - from) * 300.0
    val axis = fline {
        moveTo(0.0, 0.0)
        lineTo(300.0, 0.0)
        moveTo(zeroX, 0.0)
        lineTo(zeroX, 10.0)
    }
        .apply { color = vec(1.0, 1.0, 1.0, 0.7) }
    return [all, weighted, axis].map { it.transform { v -> Vec3(v.x + dx.toFloat(), v.y, 0f) } }
}
lines(
    panel(spectrum.energies.first(), spectrum.energies.last(), 0.0) + panel(-0.5, 0.5, 360.0),
    w = 660.0,
    h = 330.0,
    fitToSize = true,
)
val gap = spectrum.gaps(2, weights = bulk, minWeight = 0.3 * inner.size / graph.vertexCount)
"E from %.3f to %.3f; jump at 0 of %.4f; nearest bulk states at %.3f and %.3f; nearest of all at ±%.3f"
    .format(
        spectrum.energies.first(),
        spectrum.energies.last(),
        spectrum.zeroCount().toDouble() / graph.vertexCount,
        gap.minOf { it.first },
        gap.maxOf { it.second },
        spectrum.energies.filter { abs(it) > 1e-8 }.minOf { abs(it) },
    )
```

## 5. 4 - symmetry, disorder, offset sum

<!-- field-box id="ps_sweep" index="4" -->

### Narrative

From `code`

Bulk E = 0 fraction (fit over four patch sizes) at every symmetry, then the Penrose tiling
with jittered grid lines, then with its offsets moved off a zero sum.

```edn [field-box id="ps_sweep" role="properties"]
{"id" "ps_sweep" "index" 4 "name" "4 - symmetry, disorder, offset sum" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - symmetry, disorder, offset sum" "_tombstone" false "frame" #afield/rect [700.0 760.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_sweep_code_.kt" "lines" 40 "characters" 1724 "sha256" "e72e0214fd78061b41c5acbb270b2b3f382e441b5c8e67804e85a7f966f0d118"}]}
```

```kotlin [field-property box="ps_sweep" name="code" path="ps_sweep_code_.kt"]
// Bulk E = 0 fraction (fit over four patch sizes) at every symmetry, then the Penrose tiling
// with jittered grid lines, then with its offsets moved off a zero sum.
fun fit(graphs: List<TilingGraph>) = ZeroModeFit.of(graphs)
val symmetries = [3, 4, 5, 6, 7, 8, 9, 10, 12]
val bySymmetry = symmetries.map { n ->
    val base = 40.0 / n
    fit([1.0, 1.5, 2.2, 3.0].map { deBruijnTiling(symmetry = n, radius = base * it, scale = 1.0).vertexGraph() })
}
val sizes = [10.0, 16.0, 24.0]
val disorders = [0.0, 0.02, 0.05, 0.1, 0.2, 0.4, 0.8]
val byDisorder = disorders.map { d ->
    fit(sizes.map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0, disorder = d).vertexGraph() })
}
val sums = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5]
val bySum = sums.map { s ->
    val offsets = defaultDeBruijnOffsets(5).map { it + s / 5 }
    fit(sizes.map { deBruijnTiling(symmetry = 5, offsets = offsets, radius = it, scale = 1.0).vertexGraph() })
}
fun fmt(t: Table, vararg cols: String) = t.apply { cols.forEach { c -> format(c) { (it as Double).f4 } } }
display(
    "by symmetry",
    fmt(
        table(
            "symmetry" to symmetries,
            "largest patch" to bySymmetry.map { it.counts.last().first },
            "raw fraction" to bySymmetry.map { it.counts.last().second.toDouble() / it.counts.last().first },
            "bulk fit" to bySymmetry.map { it.bulk },
        ),
        "raw fraction",
        "bulk fit",
    ).heat("bulk fit"),
)
display(
    "by disorder",
    fmt(table("disorder" to disorders, "bulk fit" to byDisorder.map { it.bulk }), "bulk fit").heat("bulk fit"),
)
display(
    "by offset sum",
    fmt(table("offset sum" to sums, "bulk fit" to bySum.map { it.bulk }), "bulk fit").heat("bulk fit"),
)
```

## 6. 2 - where the confined states live

<!-- field-box id="ps_states" index="5" -->

### Narrative

From `code`

Left: how much of the E = 0 level sits on each vertex. Right, enlarged: the smallest strictly
localised state found near the middle of the patch.

```edn [field-box id="ps_states" role="properties"]
{"id" "ps_states" "index" 5 "name" "2 - where the confined states live" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - where the confined states live" "_tombstone" false "frame" #afield/rect [700.0 60.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_states_code_.kt" "lines" 38 "characters" 2156 "sha256" "16129473944ff25df5ab78afbb4e55afa915c950c78b5edb64dc481775a997e4"}]}
```

```kotlin [field-property box="ps_states" name="code" path="ps_states_code_.kt"]
// Left: how much of the E = 0 level sits on each vertex. Right, enlarged: the smallest strictly
// localised state found near the middle of the patch.
val tiling = deBruijnTiling(symmetry = 5, radius = 6.0, scale = 1.0)
val graph = tiling.vertexGraph()
val spectrum = graph.spectrum()
val weight = spectrum.zeroWeights()
val grey = [vec(0.30, 0.31, 0.36, 1.0), vec(0.22, 0.23, 0.28, 1.0)]
val (cx, cy) = graph.centre
// Grow a confined state at each of the twelve confined vertices nearest the centre; keep the smallest.
val tried =
    graph
        .interior(0.4 * graph.extent)
        .filter { weight[it] > 1e-6 }
        .sortedBy { hypot(graph.x[it] - cx, graph.y[it] - cy) }
        .take(12)
val psi = tried.mapNotNull { graph.confinedState(it) }.minBy { s -> s.count { abs(it) > 1e-9 } }
val support = (0 until graph.vertexCount).filter { abs(psi[it]) > 1e-9 }
val residual = (0 until graph.vertexCount).maxOf { v -> abs(graph.neighbours[v].sumOf { psi[it] }) }
// The enlarged view: the tiles within 3.5 edges of the state, 2.5 times larger, to the right.
val sx = support.map { graph.x[it] }.average()
val sy = support.map { graph.y[it] }.average()
val zoom = 2.5
val dx = 1.1 * graph.extent + 3.6 * zoom
val enlarge = { f: FLine ->
    f.transform { v -> Vec3(((v.x - sx) * zoom + dx).toFloat(), ((v.y - sy) * zoom).toFloat(), 0f) }
}
val near = tiling.tiles.filter { hypot(it.center.x - sx, it.center.y - sy) < 3.5 }
val nearTiles = DeBruijnTiling(tiling.symmetry, tiling.offsets, tiling.directions, near).lines(palette = grey)
val right = (nearTiles + graph.vertexDots(psi, maxRadius = 0.32)).map(enlarge)
lines(tiling.lines(palette = grey) + graph.vertexDots(weight) + right, w = 700.0, h = 330.0, fitToSize = true)
val byDegree =
    (0 until graph.vertexCount)
        .groupBy { graph.degree(it) }
        .toSortedMap()
        .entries
        .joinToString(", ") { (d, vs) -> "%d-edge %.3f".format(d, vs.sumOf { weight[it] } / vs.size) }
"${spectrum.zeroCount()} zero states on ${graph.vertexCount} vertices; mean weight by vertex: $byDegree\nthe state on the right: ${support.size} vertices, max |A·ψ| = %.1e"
    .format(residual)
```

## 7. 7 - a live tiling (launch it)

<!-- field-box id="ps_live" index="6" -->

```edn [field-box id="ps_live" role="properties"]
{"id" "ps_live" "index" 6 "name" "7 - a live tiling (launch it)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 - a live tiling (launch it)" "_tombstone" false "frame" #afield/rect [60.0 2160.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_live_code_.kt" "lines" 18 "characters" 628 "sha256" "42ba4477b6b8324a67f479d138b6e786744597f305d0948712fba074b6b67440"}]}
```

```kotlin [field-property box="ps_live" name="code" path="ps_live_code_.kt"]
import afield.audio.modal.*

// The radius-6 Penrose patch as a live voice. It strikes itself every 1.5 s at the vertex with
// the most confined weight; call drum.strike(x, y) to hit it somewhere else. Launch this box.
val graph = deBruijnTiling(symmetry = 5, radius = 6.0, scale = 1.0).vertexGraph()
val modes = graph.springModes(8.0)
val weight = modes.spectrum.zeroWeights()
val v = graph.interior(0.5 * graph.extent).maxBy { weight[it] }
val drum = ModalDrum(
    modes,
    fundamental = modes.fundamentalFor(220.0),
    alpha = 1.0,
    beta = 0.2,
    every = 1.5,
    autoX = graph.x[v],
    autoY = graph.y[v],
)
drum
```

