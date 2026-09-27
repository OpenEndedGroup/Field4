# Field Document: letter_interpolation_panels

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="li_renderer" index="0" -->

```edn [field-box id="li_renderer" role="properties"]
{"id" "li_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["li_fields"] "properties" {"children" #{#afield/box "li_fields"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "li_renderer_code_.kt" "lines" 11 "characters" 260 "sha256" "d28cd606359d20ae167a81a4d2b9e2472caf7092f4cfa7a90be93f4ab25e6dfb"}]}
```

```kotlin [field-property box="li_renderer" name="code" path="li_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Letter interpolation")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. train both letters

<!-- field-box id="li_fields" index="1" -->

```edn [field-box id="li_fields" role="properties"]
{"id" "li_fields" "index" 1 "name" "train both letters" "parents" ["root" "li_renderer"] "children" ["li_panels"] "properties" {"children" #{#afield/box "li_panels"} "parents" #{#afield/box "root" #afield/box "li_renderer"} "name" "train both letters" "_tombstone" false "frame" #afield/rect [380.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "li_fields_code_.kt" "lines" 65 "characters" 3021 "sha256" "245b178fa4d7a7fa9efe3344c7d19c52862298403533be43bd0c4a3083f7214c"}]}
```

```kotlin [field-property box="li_fields" name="code" path="li_fields_code_.kt"]
import afield.stdlib.FlowCoupling
import afield.stdlib.FlowMatchOptions
import afield.stdlib.contourComponents
import afield.stdlib.flowMatch

prop<afield.stdlib.FlowMatchExport>(fieldA)
prop<afield.stdlib.FlowMatchExport>(fieldB)

val LETTERS = "C" to "S"
val N = 512

// Both fields must start from the *same* cloud. That is what gives a particle one start
// point and two destinations, and it is also what makes the two normalisation frames
// agree -- `blendWith` refuses to mix two coordinate systems, so sharing the cloud is a
// requirement and not a tidiness.
val source = (0 until N).map {
    val a = 2 * Math.PI * it / N
    vec(Math.cos(a), Math.sin(a), 0.0)
}

// A letter as N points in arc-length order, centred and scaled to unit RMS radius so
// that both letters live in the same frame as the circle.
fun glyph(ch: String): List<Vec3> {
    val parts = contourComponents(layoutText(ch, typeface("Helvetica", 300.0)).toFLines(), total = N, seed = 3)
    if (parts.size != 1) error("'$ch' has ${parts.size} closed contours; this scene pairs one circle with one contour, so pick a letter like C, S, E, F, L, T, V or Z")
    val p = parts[0]
    val cx = p.map { it.x.toDouble() }.average()
    val cy = p.map { it.y.toDouble() }.average()
    val rms = Math.sqrt(p.sumOf { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) } / p.size)
    return p.map { vec((it.x - cx) / rms, (it.y - cy) / rms, 0.0) }
}

// Rotate and if necessary reverse the contour so index i starts near circle index i.
// Without this each letter carries an arbitrary rotation relative to the circle, and --
// worse for this scene -- a *different* one, so the two fields would disagree about
// which part of the circle becomes which part of the glyph and the interpolation would
// be comparing nothing.
fun align(contour: List<Vec3>): List<Vec3> {
    var best = contour
    var bestCost = Double.MAX_VALUE
    for (reversed in listOf(false, true)) {
        val base = if (reversed) contour.reversed() else contour
        for (shift in 0 until N) {
            var cost = 0.0
            for (i in 0 until N) {
                val q = base[(i + shift) % N]; val s = source[i]
                cost += (q.x - s.x) * (q.x - s.x) + (q.y - s.y) * (q.y - s.y)
                if (cost > bestCost) break
            }
            if (cost < bestCost) { bestCost = cost; best = List(N) { base[(it + shift) % N] } }
        }
    }
    return best
}

// The same seed in both calls. Smile's Xavier initialisation is drawn from a generator
// that `flowMatch` reseeds, so this really is the same starting point twice.
fun train(target: List<Vec3>) = flowMatch(
    target, source,
    FlowMatchOptions(steps = 9000, coupling = FlowCoupling.PAIRED, seed = 7, hidden = intArrayOf(64, 64)),
).export()

fieldA = train(align(glyph(LETTERS.first)))
fieldB = train(align(glyph(LETTERS.second)))
println("${LETTERS.first} and ${LETTERS.second}: ${fieldA.network.parameterCount} parameters each, one source of ${fieldA.sourceCount} points")
```

## 3. three pinned panels

<!-- field-box id="li_panels" index="2" -->

```edn [field-box id="li_panels" role="properties"]
{"id" "li_panels" "index" 2 "name" "three pinned panels" "parents" ["root" "li_fields"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "li_fields"} "name" "three pinned panels" "_tombstone" false "frame" #afield/rect [740.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "li_panels_code_.kt" "lines" 38 "characters" 1637 "sha256" "a3dddba634315dc65a9e5414fc03ea7c7422a34a36f019e0969ee8c6f26542e1"}]}
```

```kotlin [field-property box="li_panels" name="code" path="li_panels_code_.kt"]
import afield.stdlib.ShaderNeuralParticles
import afield.stdlib.blendWith

prop<List<afield.stdlib.ShaderNeuralParticles>>(panels)

// 0.5 is the ring. The other two are where the form is buckling -- a ring with the S's
// spine pressing into it -- and they are the reason this figure is not just "a circle".
// 0.78 rather than 0.75 because the buckle is deepest a little past three quarters.
val STOPS = listOf(0.25f, 0.5f, 0.78f)

// `name` keeps each system's buffers and modules apart; without it the second one would
// bind over the first. `viewCenter` is subtracted before the divide, so a negative x
// moves that panel to the right of the screen.
once.panels {
    STOPS.mapIndexed { i, _ ->
        ShaderNeuralParticles(fieldA, renderer) {
            name = "panel$i"
            particleCount = 150_000
            stepsPerCycle = 150
            // Higher than the sweeping scene's: a still wants the arrived distribution
            // crisp rather than the stream between shapes.
            dwellCycles = 3f
            viewScale = 4.6f
            viewCenter = Vector2f((1 - i) * 5.2f, 0f)
        }.also { it.installInto() }
    }
}

// Set once, not per frame. Each panel is a different field from the same trained pair.
STOPS.forEachIndexed { i, t ->
    panels[i].updateWeights(fieldA.blendWith(fieldB, t).network)
    panels[i].settings.intensity = 1.1f
    panels[i].settings.arrivalBias = 0.6f
    panels[i].settings.pointSize = 1.5f
    panels[i].settings.sourceColor = vec(0.15, 0.45, 1.0, 1.0)
    panels[i].settings.targetColor = vec(1.0, 0.62, 0.22, 1.0)
}
println("panels at ${STOPS.joinToString(", ")}")
```

