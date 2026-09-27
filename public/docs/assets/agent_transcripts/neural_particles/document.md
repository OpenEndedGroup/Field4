# Field Document: neural_particles

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="np_renderer" index="0" -->

```edn [field-box id="np_renderer" role="properties"]
{"id" "np_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["np_field"] "properties" {"children" #{#afield/box "np_field"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "np_renderer_code_.kt" "lines" 11 "characters" 256 "sha256" "6e2b0ae459c7a165a0f6c40296dc07df3dddc7a428073309c233343a1a13743f"}]}
```

```kotlin [field-property box="np_renderer" name="code" path="np_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Neural particles")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. train the field

<!-- field-box id="np_field" index="1" -->

```edn [field-box id="np_field" role="properties"]
{"id" "np_field" "index" 1 "name" "train the field" "parents" ["root" "np_renderer"] "children" ["np_particles"] "properties" {"children" #{#afield/box "np_particles"} "parents" #{#afield/box "root" #afield/box "np_renderer"} "name" "train the field" "_tombstone" false "frame" #afield/rect [380.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "np_field_code_.kt" "lines" 39 "characters" 1958 "sha256" "a559bafb9581cbb5e751a0f5b78ef92d941205e59d130f44546c71e9389c52a0"}]}
```

```kotlin [field-property box="np_field" name="code" path="np_field_code_.kt"]
import afield.stdlib.FlowCoupling
import afield.stdlib.FlowMatchOptions
import afield.stdlib.flowMatch
import afield.stdlib.contourComponents
import afield.stdlib.mixtureSource

// Written out in full because `prop<>` rewrites the box's code and the rewrite does not
// carry the snippet's own imports — the same trap the `four_simulation_chain` bundle box
// documents.
prop<afield.stdlib.FlowMatchExport>(neuralExport)

// A word, not a letter — and only because the source has the word's topology. A
// standard Gaussian cannot become five disjoint outlines: it measures a chamfer of
// 13.1 against a floor of 0.8, and no amount of training moves it, because a flow is a
// continuous invertible map and one blob cannot tear into five.
//
// Three things get it to 5.2, and they were each worth a measurement:
//   one ellipse per *closed subpath* (a "D" is two — outer and counter, and treating
//   the glyph as one component costs a factor of two in landing error);
//   each ellipse rotated into phase with the contour it becomes (without that the
//   source is worse than a blob);
//   and no jitter on the source, which sounds like a detail and is a factor of four —
//   thickening the ellipse into an annulus destroys the correspondence that is doing
//   all the work.
//
// `contourComponents` does the first, `mixtureSource` the second, and the third is now
// just the default. Points come back in arc-length order and the pairing is by index,
// which is what makes PAIRED mean anything here.
val contours = layoutText("FIELD", typeface("Helvetica", 260.0)).toFLines()
val pairing = mixtureSource(contourComponents(contours, total = 12000), seed = 5)

val field = flowMatch(
    pairing.to,
    pairing.from,
    FlowMatchOptions(steps = 20000, coupling = FlowCoupling.PAIRED, seed = 4)
)
neuralExport = field.export()

println("trained on ${pairing.size} pairs: loss ${"%.4g".format(field.loss(200))}, ${field.parameterCount} parameters")
```

## 3. neural particles

<!-- field-box id="np_particles" index="2" -->

```edn [field-box id="np_particles" role="properties"]
{"id" "np_particles" "index" 2 "name" "neural particles" "parents" ["root" "np_field"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "np_field"} "name" "neural particles" "_tombstone" false "frame" #afield/rect [740.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "np_particles_code_.kt" "lines" 32 "characters" 1426 "sha256" "0e0527bf836cafcbb1059153f880a7477c68dc11787b17c55b4bbf2a9d1a159d"}]}
```

```kotlin [field-property box="np_particles" name="code" path="np_particles_code_.kt"]
import afield.stdlib.ShaderNeuralParticles

prop<afield.stdlib.ShaderNeuralParticles>(particles)

// `renderer` and `neuralExport` are the parent boxes' props, visible through the
// preamble. The `once` is real rather than habit: the shader has the network's shape
// compiled into it, so rebuilding on every run would leak a module per keystroke.
once.particles {
    ShaderNeuralParticles(neuralExport, renderer) {
        particleCount = 300_000
        stepsPerCycle = 150
        dwellCycles = 3f
    }.also { it.installInto() }
}

// Everything below is live. The push buffer re-reads these lambdas every frame, so
// editing a number here and re-running changes the picture without touching the GPU
// allocation or recompiling a shader.
particles.settings.intensity = 1.1f
particles.settings.arrivalBias = 0.5f
// Seeded slightly off the trained source so 300,000 particles are not 12,000 threads.
particles.settings.sourceJitter = 0.02f
particles.settings.pointSize = 1.5f
particles.settings.viewScale = 2.6f
particles.settings.stepsPerCycle = 150
particles.settings.sourceColor = vec(0.15, 0.45, 1.0, 1.0)
particles.settings.targetColor = vec(1.0, 0.62, 0.22, 1.0)

// `particleCount` and `phaseSpread` are the two that are not live: they decide the
// buffer's size and its contents. Change one and call `particles.reset()`.

println("${particles.settings.particleCount} particles on ${particles.export.network}")
```

