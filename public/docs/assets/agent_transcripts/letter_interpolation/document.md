# Field Document: letter_interpolation

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
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
{"id" "li_fields" "index" 1 "name" "train both letters" "parents" ["root" "li_renderer"] "children" ["li_sweep"] "properties" {"children" #{#afield/box "li_sweep"} "parents" #{#afield/box "root" #afield/box "li_renderer"} "name" "train both letters" "_tombstone" false "frame" #afield/rect [380.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "li_fields_code_.kt" "lines" 65 "characters" 3021 "sha256" "245b178fa4d7a7fa9efe3344c7d19c52862298403533be43bd0c4a3083f7214c"}]}
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

## 3. the middle

<!-- field-box id="li_sweep" index="2" -->

```edn [field-box id="li_sweep" role="properties"]
{"id" "li_sweep" "index" 2 "name" "the middle" "parents" ["root" "li_fields"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "li_fields"} "name" "the middle" "_tombstone" false "frame" #afield/rect [740.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "li_sweep_code_.kt" "lines" 42 "characters" 1924 "sha256" "48b418a995fe48f3c8ac25f5c21f9e8c482c992e8e95e363d1217c58926feda9"}]}
```

```kotlin [field-property box="li_sweep" name="code" path="li_sweep_code_.kt"]
import afield.stdlib.ShaderNeuralParticles
import afield.stdlib.blendWith

prop<afield.stdlib.ShaderNeuralParticles>(particles)

once.particles {
    ShaderNeuralParticles(fieldA, renderer) {
        particleCount = 200_000
        stepsPerCycle = 150
        // Low, on purpose. A high dwell shows the *distribution* a field settles on,
        // which is the right choice for reading a word and the wrong one here: the
        // interesting thing is a shape that is continuously becoming another, so the
        // stream between the shapes has to stay visible.
        dwellCycles = 1.2f
    }.also { it.installInto() }
}

particles.settings.intensity = 1.1f
particles.settings.arrivalBias = 0.55f
particles.settings.pointSize = 1.5f
particles.settings.viewScale = 3.4f
particles.settings.sourceColor = vec(0.15, 0.45, 1.0, 1.0)
particles.settings.targetColor = vec(1.0, 0.62, 0.22, 1.0)

val PERIOD = 2400.0    // frames for a full there-and-back: forty seconds at sixty

frames {
    while (true) {
        // A plain triangle wave -- constant speed, every value of t given the same
        // time. An earlier version cubed this about the centre to linger at t = 0.5,
        // on the assumption that the middle was the interesting part. Captured frame
        // by frame it is not: t = 0.5 is a clean closed ring, the tidiest and least
        // ambiguous form in the whole sweep, and the shapes worth watching are the
        // buckling ones either side of it at roughly 0.2-0.3 and 0.7-0.9. Easing
        // towards the centre parks on the dullest frame. Slow everywhere beats clever.
        val cycle = (renderer.frameCounter % PERIOD.toLong()) / PERIOD
        val t = (if (cycle < 0.5) cycle * 2 else 2 - cycle * 2).toFloat()
        // The whole idea, once a frame: a field that was never trained on anything.
        particles.updateWeights(fieldA.blendWith(fieldB, t).network)
        frame()
    }
}
```

