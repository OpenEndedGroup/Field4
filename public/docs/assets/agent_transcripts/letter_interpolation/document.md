# Field Document: letter_interpolation

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="li_renderer" index="0" -->

```edn [field-box id="li_renderer" role="properties"]
{"id" "li_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["li_fields"] "properties" {"children" #{#afield/box "li_fields"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "li_renderer_code_.kt" "lines" 12 "characters" 272 "sha256" "00106986b913e0fdc66ecd33fec4c3dcfa7b1fcd1a748b48572ff52830fe3d32"}]}
```

```kotlin [field-property box="li_renderer" name="code" path="li_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

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
{"id" "li_fields" "index" 1 "name" "train both letters" "parents" ["root" "li_renderer"] "children" ["li_sweep"] "properties" {"children" #{#afield/box "li_sweep"} "parents" #{#afield/box "root" #afield/box "li_renderer"} "name" "train both letters" "_tombstone" false "frame" #afield/rect [380.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "li_fields_code_.kt" "lines" 74 "characters" 2985 "sha256" "f52a99b415d3ba1030a2555512153eb11d9eaa8320fe59621d1dd7b03b4fcc24"}]}
```

```kotlin [field-property box="li_fields" name="code" path="li_fields_code_.kt"]
prop<afield.simulation.particles.FlowMatchExport>(fieldA)
prop<afield.simulation.particles.FlowMatchExport>(fieldB)

val LETTERS = "C" to "S"
val N = 512

/*
Both fields must start from the *same* cloud. That is what gives a particle one start
point and two destinations, and it is also what makes the two normalisation frames
agree -- `blendWith` refuses to mix two coordinate systems, so sharing the cloud is a
requirement and not a tidiness.
*/
val source = (0 until N).map {
    val a = 2 * Math.PI * it / N
    vec(Math.cos(a), Math.sin(a), 0.0)
}

// A letter as N points in arc-length order, centred and scaled to unit RMS radius so
// that both letters live in the same frame as the circle.
fun glyph(ch: String): List<Vec3> {
    val parts = contourComponents(layoutText(ch, typeface("Helvetica", 300.0)).toFLines(), total = N, seed = 3)
    if (parts.size != 1)
        error(
            "'$ch' has ${parts.size} closed contours; this scene pairs one circle with one contour, so pick a letter like C, S, E, F, L, T, V or Z",
        )
    val p = parts[0]
    val cx = p.map { it.x.toDouble() }.average()
    val cy = p.map { it.y.toDouble() }.average()
    val rms = Math.sqrt(p.sumOf { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) } / p.size)
    return p.map { vec((it.x - cx) / rms, (it.y - cy) / rms, 0.0) }
}

/*
Rotate and if necessary reverse the contour so index i starts near circle index i.
Without this each letter carries an arbitrary rotation relative to the circle, and --
worse for this scene -- a *different* one, so the two fields would disagree about
which part of the circle becomes which part of the glyph and the interpolation would
be comparing nothing.
*/
fun align(contour: List<Vec3>): List<Vec3> {
    var best = contour
    var bestCost = Double.MAX_VALUE
    for (reversed in [false, true]) {
        val base = if (reversed) contour.reversed() else contour
        for (shift in 0 until N) {
            var cost = 0.0
            for (i in 0 until N) {
                val q = base[(i + shift) % N]
                val s = source[i]
                cost += (q.x - s.x) * (q.x - s.x) + (q.y - s.y) * (q.y - s.y)
                if (cost > bestCost) break
            }
            if (cost < bestCost) {
                bestCost = cost
                best = List(N) { base[(it + shift) % N] }
            }
        }
    }
    return best
}

// The same seed in both calls. Smile's Xavier initialisation is drawn from a generator
// that `flowMatch` reseeds, so this really is the same starting point twice.
fun train(target: List<Vec3>) = flowMatch(
    target,
    source,
    FlowMatchOptions(steps = 9000, coupling = FlowCoupling.PAIRED, seed = 7, hidden = intArrayOf(64, 64)),
).export()

fieldA = train(align(glyph(LETTERS.first)))
fieldB = train(align(glyph(LETTERS.second)))
println(
    "${LETTERS.first} and ${LETTERS.second}: ${fieldA.network.parameterCount} parameters each, one source of ${fieldA.sourceCount} points",
)
```

## 3. the middle

<!-- field-box id="li_sweep" index="2" -->

```edn [field-box id="li_sweep" role="properties"]
{"id" "li_sweep" "index" 2 "name" "the middle" "parents" ["root" "li_fields"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "li_fields"} "name" "the middle" "_tombstone" false "frame" #afield/rect [740.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "li_sweep_code_.kt" "lines" 37 "characters" 1492 "sha256" "913ab051aca9946f46c156e2de2895648dd66d7d213147a8f86829e9588cd4db"}]}
```

```kotlin [field-property box="li_sweep" name="code" path="li_sweep_code_.kt"]
prop<afield.simulation.particles.ShaderNeuralParticles>(particles)

once.particles {
    ShaderNeuralParticles(fieldA, renderer) {
            particleCount = 200_000
            stepsPerCycle = 150
            /*
            Low, on purpose. A high dwell shows the *distribution* a field settles on,
            which is the right choice for reading a word and the wrong one here: the
            interesting thing is a shape that is continuously becoming another, so the
            stream between the shapes has to stay visible.
            */
            dwellCycles = 1.2f
        }
        .also { it.installInto() }
}

particles.settings.intensity = 1.1f
particles.settings.arrivalBias = 0.55f
particles.settings.pointSize = 1.5f
particles.settings.viewScale = 3.4f
particles.settings.sourceColor = vec(0.15, 0.45, 1.0, 1.0)
particles.settings.targetColor = vec(1.0, 0.62, 0.22, 1.0)

val PERIOD = 2400.0 // frames for a full there-and-back: forty seconds at sixty

frames {
    while (true) {
        // A constant-speed triangle wave gives every interpolation value equal time,
        // including the buckling shapes on either side of the clean middle ring.
        val cycle = (renderer.frameCounter % PERIOD.toLong()) / PERIOD
        val t = (if (cycle < 0.5) cycle * 2 else 2 - cycle * 2).toFloat()
        // The whole idea, once a frame: a field that was never trained on anything.
        particles.updateWeights(fieldA.blendWith(fieldB, t).network)
        frame()
    }
}
```

