# Field Document: harmonic_drum

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4 - shape alone cannot reach the octave

<!-- field-box id="hd_shapes" index="0" -->

```edn [field-box id="hd_shapes" role="properties"]
{"id" "hd_shapes" "index" 0 "name" "4 - shape alone cannot reach the octave" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - shape alone cannot reach the octave" "_tombstone" false "frame" #afield/rect [60.0 780.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "hd_shapes_code_.kt" "lines" 50 "characters" 1830 "sha256" "f6007bc24cbe8cb4d5c44f9a37e46afc1a62611d38b65c3395e1cbd0a4cbcf39"}]}
```

```kotlin [field-property box="hd_shapes" name="code" path="hd_shapes_code_.kt"]
import afield.audio.modal.*

// Forty random uniform drums: f₂/f₁ across (1.0–2.1), distance from 1:2:3:4:5 up (0–400 cents).
// The white line is f₂/f₁ = 1.593, the most any uniform drum can reach; yellow is the syahi.
val disk = polarDrumMesh(0.08) { 1.0 }
val rnd = java.util.Random(3)
val shapes = (0 until 40).map {
    PolarShape.circle(5).apply {
        for (k in 2..5) {
            cosine[k] = 0.5 * (rnd.nextDouble() - 0.5) / k
            sine[k] = 0.5 * (rnd.nextDouble() - 0.5) / k
        }
    }
}
val scores =
    shapes
        .parallelStream()
        .map { s ->
            val m = disk.polarMorphed(s).modes(15)
            m.ratios()[1] to m.ramanHarmonicity().rms
        }
        .toList()
val syahi = loadedDisk(Raman.compositeRadius, Raman.compositeMassRatio, h = 0.07).modes(15)
fun dot(f: Double, cents: Double) = fline { circle((f - 1.0) * 400, 400 - cents, 6.0) }.apply { filled = true }
val out = ArrayList<FLine>()
out +=
    fline {
        moveTo(0.0, 0.0)
        lineTo(440.0, 0.0)
        lineTo(440.0, 400.0)
        lineTo(0.0, 400.0)
        lineTo(0.0, 0.0)
    }
        .apply { color = vec(0.4, 0.4, 0.4, 1.0) }
out +=
    fline {
        moveTo(0.593 * 400, 0.0)
        lineTo(0.593 * 400, 400.0)
    }
        .apply {
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 1.5
        }
scores.forEach { (f, c) -> out += dot(f, c).apply { color = vec(0.35, 0.6, 1.0, 1.0) } }
out += dot(syahi.ratios()[1], syahi.ramanHarmonicity().rms).apply { color = vec(1.0, 0.85, 0.3, 1.0) }
lines(out, w = 440.0, h = 400.0, fitToSize = true)
println(
    "highest f₂/f₁ of 40 shapes: ${scores.maxOf { it.first }.f3}; closest to 1:2:3:4:5: ${scores.minOf { it.second }.f1} cents",
)
println("loaded disk: f₂/f₁ ${syahi.ratios()[1].f3}, ${syahi.ramanHarmonicity().rms.f1} cents")
```

## 2. 6 - a drum that plays a triad (draw first)

<!-- field-box id="hd_triad" index="1" -->

```edn [field-box id="hd_triad" role="properties"]
{"id" "hd_triad" "index" 1 "name" "6 - a drum that plays a triad (draw first)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 - a drum that plays a triad (draw first)" "_tombstone" false "frame" #afield/rect [1420.0 780.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "hd_triad_code_.kt" "lines" 35 "characters" 1368 "sha256" "e481ea1eafea6791fb2255ce66e559065f8db946e2c5b83fbd22b9202350d6ab"}]}
```

```kotlin [field-property box="hd_triad" name="code" path="hd_triad_code_.kt"]
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// Bend your last drawn stroke (or the heart) into a uniform drum whose three lowest
// modes sound a major triad, 4:5:6.
val stroke = notation.strokes.lastOrNull()?.toFLine()
val start = if (stroke != null) PolarShape.fromLine(stroke, 3) else PolarShape.fromPolygon(EigendrumDocs.heart, 3)
val design = designShape([4.0, 5.0, 6.0], start = start, h = 0.08)
val modes = polarDrumMesh(0.05) { 1.0 }.polarMorphed(design.shape).modes(12)
val out = ArrayList<FLine>()
out +=
    design.start.outline().apply {
        color = vec(0.6, 0.6, 0.6, 1.0)
        thickness = 2.0
    }
out +=
    design.shape.outline().apply {
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 2.0
    }
for (k in 0 until 3) {
    val dx = (k + 1) * 2.8f
    out += modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add(dx, 0f, 0f) } } }
    out +=
        modes.nodalLines(k).copy().apply {
            transform { v -> v.add(dx, 0f, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
        }
}
lines(out, w = 520.0, h = 140.0, fitToSize = true)
println("start ${design.startFit}, fitted ${design.fit}; ratios ${modes.ratios().take(3).map { (4 * it).f3 }}")
display(
    "strike",
    WavFloatArrayWriter.encodeMono16(modes.render(modes.strike(0.3, 0.25, 0.04), fundamental = 220.0), 44100),
)
```

## 3. 1 - Raman's nine modes on a loaded drum

<!-- field-box id="hd_families" index="2" -->

```edn [field-box id="hd_families" role="properties"]
{"id" "hd_families" "index" 2 "name" "1 - Raman's nine modes on a loaded drum" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Raman's nine modes on a loaded drum" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "hd_families_code_.kt" "lines" 41 "characters" 1712 "sha256" "22550327cba4e1cc4776afed245bb2844e9bff5707dc8efeb8d1e20df28418ab"}]}
```

```kotlin [field-property box="hd_families" name="code" path="hd_families_code_.kt"]
import afield.audio.modal.*

// A unit drum loaded like Ramakrishna & Sondhi's tabla: density ×9.77 inside radius 0.4.
// Its nine lowest mode families, drawn in Raman's five columns, one per harmonic.
val bare = polarDrumMesh(0.05) { 1.0 }
val bareModes = bare.modes(15)
val modes = bare.withDensity(syahi(0.4, 3.125 * 3.125)).modes(15)
val labels = modes.circularLabels()
val pictures = ArrayList<FLine>()
val perTone = IntArray(6)
Raman.families.forEach { (n, m, tone) ->
    val k = labels.indexOfFirst { it.diameters == n && it.circles == m }
    val dx = (tone - 1) * 2.3f
    val dy = perTone[tone]++ * 2.3f
    pictures += modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add(dx, dy, 0f) } } }
    pictures +=
        modes.nodalLines(k).copy().apply {
            transform { v -> v.add(dx, dy, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 1.5
        }
    pictures +=
        fline { circle(dx.toDouble(), dy.toDouble(), 0.4) }
            .apply {
                color = vec(0.1, 0.1, 0.1, 1.0)
                thickness = 1.0
            }
}
val families = table(
    "mode" to Raman.families.map { (n, m, _) -> "($n,$m)" },
    "tone" to Raman.families.map { it.third },
    "bare" to bareModes.ramanFamilies().toList(),
    "loaded" to modes.ramanFamilies().toList(),
    "exact" to Raman.compositeExact.toList(),
    "measured" to Raman.measured.toList(),
)
listOf("bare", "loaded", "exact", "measured").forEach { c -> families.format(c) { (it as Double).f3 } }
display("families", families)
lines(pictures, w = 520.0, h = 330.0, fitToSize = true)
println("bare drum: ${bareModes.ramanHarmonicity()}")
println("loaded drum: ${modes.ramanHarmonicity()}")
```

## 4. 2 - two modes at one pitch: sand figures

<!-- field-box id="hd_sand" index="3" -->

```edn [field-box id="hd_sand" role="properties"]
{"id" "hd_sand" "index" 3 "name" "2 - two modes at one pitch: sand figures" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - two modes at one pitch: sand figures" "_tombstone" false "frame" #afield/rect [740.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "hd_sand_code_.kt" "lines" 26 "characters" 987 "sha256" "b6fe4770f7f75bc7af1a6a614a8588f2dcee633d42913125bb52ef78a87d0504"}]}
```

```kotlin [field-property box="hd_sand" name="code" path="hd_sand_code_.kt"]
import afield.audio.modal.*

// Mixes of the loaded drum's (0,2) and (2,1) modes, which share one pitch: the nodal lines
// Raman saw in sand, from a circle to two parallel lines to two diameters.
val modes = polarDrumMesh(0.04) { 1.0 }.withDensity(syahi(0.4, 3.125 * 3.125)).modes(10)
val labels = modes.circularLabels()
val ring = labels.indexOf(CircularLabel(0, 2))
val cross = labels.indexOf(CircularLabel(2, 1))
val mixes = [0.0, 0.6, 1.0, 1.25, 2.5, 1000.0]
val out = ArrayList<FLine>()
mixes.forEachIndexed { i, w ->
    val dx = i * 2.3f
    out +=
        fline { circle(dx.toDouble(), 0.0, 1.0) }
            .apply {
                color = vec(0.5, 0.5, 0.5, 1.0)
                thickness = 1.0
            }
    out +=
        modes.mesh.zeroLines(modes.superpose(ring, cross, w)).apply {
            transform { v -> v.add(dx, 0f, 0f) }
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 2.0
        }
}
lines(out, w = 520.0, h = 110.0, fitToSize = true)
```

## 5. 5 - fitting a layered syahi

<!-- field-box id="hd_syahi" index="4" -->

```edn [field-box id="hd_syahi" role="properties"]
{"id" "hd_syahi" "index" 4 "name" "5 - fitting a layered syahi" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - fitting a layered syahi" "_tombstone" false "frame" #afield/rect [740.0 780.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "hd_syahi_code_.kt" "lines" 26 "characters" 1186 "sha256" "da83bbfa7a94717d9e344c49407db83e8f8c0f96eae7f8ce0dfce6d626dbbe10"}]}
```

```kotlin [field-property box="hd_syahi" name="code" path="hd_syahi_code_.kt"]
import afield.audio.modal.*

// Fit a syahi of one, two and three stacked layers to 1:2:3:4:5, each starting from
// Ramakrishna & Sondhi's disc. Below, each design's density from the centre (left) to the rim.
val designs = (1..3).toList().parallelStream().map { designSyahi(it, h = 0.08) }.toList()
display(
    "designs",
    table(
        "layers" to designs.map { it.layers },
        "radii" to designs.map { d -> d.radii.sorted().joinToString(" ") { it.f2 } },
        "centre density" to designs.map { it.peak.f1 },
        "start (cents)" to designs.map { it.startFit.rms.f1 },
        "fitted (cents)" to designs.map { it.fit.rms.f1 },
        "worst (cents)" to designs.map { it.fit.worst.f1 },
    ),
)
val colours = [vec(0.35, 0.6, 1.0, 1.0), vec(1.0, 0.85, 0.3, 1.0), vec(1.0, 0.45, 0.3, 1.0)]
val profiles = designs.mapIndexed { i, d -> d.profile(w = 3.0, h = 1.0, top = 16.0).apply { color = colours[i] } }
val composite = fline {
    moveTo(0.0, 1 - 9.77 / 16)
    lineTo(1.2, 1 - 9.77 / 16)
    lineTo(1.2, 1 - 1.0 / 16)
    lineTo(3.0, 1 - 1.0 / 16)
}
    .apply { color = vec(0.6, 0.6, 0.6, 1.0) }
lines(profiles + composite, w = 520.0, h = 180.0, fitToSize = true)
```

## 6. 7 - morphing to the harmonic drum (live voice)

<!-- field-box id="hd_morph" index="5" -->

```edn [field-box id="hd_morph" role="properties"]
{"id" "hd_morph" "index" 5 "name" "7 - morphing to the harmonic drum (live voice)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 - morphing to the harmonic drum (live voice)" "_tombstone" false "frame" #afield/rect [60.0 1500.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "hd_morph_code_.kt" "lines" 16 "characters" 807 "sha256" "75fe40b61dc625d7be45e5e723164a0060772c031e13f85efe4c4b5adeaa396c"}]}
```

```kotlin [field-property box="hd_morph" name="code" path="hd_morph_code_.kt"]
import afield.audio.modal.*

// From the bare drum to Raman's harmonic drum and back, every 4 s, as a live voice.
val bare = polarDrumMesh(0.07) { 1.0 }.modes(15)
val composite = loadedDisk(Raman.compositeRadius, Raman.compositeMassRatio, h = 0.07).modes(15)
val fitted = designSyahi(1, h = 0.08).modes
val drums = [bare, composite, fitted]
val names = ["f₂", "f₄", "f₆", "f₇", "f₉", "f₁₁", "f₁₅"]
val picks = [1, 3, 5, 6, 8, 10, 14]
val spectra = table(
    "drum" to ["bare", "composite", "one layer"],
    *names.mapIndexed { c, name -> name to drums.map { it.ratios()[picks[c]] } }.toTypedArray(),
)
names.forEach { n -> spectra.format(n) { (it as Double).f3 } }
display("spectra", spectra)
ModalDrum(bare, fundamental = 220.0, every = 0.7, autoX = 0.62, autoY = 0.15, morph = drums, morphPeriod = 4.0)
```

## 7. 3 - harmonicity over radius and mass

<!-- field-box id="hd_map" index="6" -->

```edn [field-box id="hd_map" role="properties"]
{"id" "hd_map" "index" 6 "name" "3 - harmonicity over radius and mass" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - harmonicity over radius and mass" "_tombstone" false "frame" #afield/rect [1420.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "hd_map_code_.kt" "lines" 10 "characters" 637 "sha256" "130d3352744de604743bd0aa26c5734c60ea8fd94aca55075e60db30768f65de"}]}
```

```kotlin [field-property box="hd_map" name="code" path="hd_map_code_.kt"]
import afield.audio.modal.*

// Loading radius across (0.15–0.7 of the rim), density ratio up (1–30, log spaced); each
// cell is one loaded drum's rms distance from 1:2:3:4:5, bright where it is close.
val map = harmonicityMap(linSpace(0.15, 0.7, 12), logSpace(1.0, 30.0, 12), h = 0.07)
val tabla = [Raman.compositeRadius to Raman.compositeMassRatio]
lines(map.cells(w = 12.0, h = 12.0, marks = tabla), w = 400.0, h = 400.0, fitToSize = true)
val (i, j) = map.best
println("most harmonic cell: radius ${map.radii[j].f2}, density × ${map.massRatios[i].f2}, ${map.cents[i][j].f1} cents")
println("bare skin: ${map.cents[0][0].f1} cents")
```

