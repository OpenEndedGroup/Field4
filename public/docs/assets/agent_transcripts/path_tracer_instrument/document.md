# Field Document: path_tracer_instrument

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="pt_renderer" index="0" -->

### Narrative

From `code`

The window and the Vulkan renderer every other box in this document inherits.
`once.renderer` means re-running this box does not build a second window.

```edn [field-box id="pt_renderer" role="properties"]
{"id" "pt_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["pt_tracer"] "properties" {"children" #{#afield/box "pt_tracer"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_renderer_code_.kt" "lines" 11 "characters" 387 "sha256" "21db7a14df2e5196cca0e276155dca6635ce1b1afa37b747c8591205089e7602"}]}
```

```kotlin [field-property box="pt_renderer" name="code" path="pt_renderer_code_.kt"]
// The window and the Vulkan renderer every other box in this document inherits.
// `once.renderer` means re-running this box does not build a second window.
import afield.graphics.vulkan.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Path tracer instrument")
    Renderer(window)
}
start(renderer::run)
```

## 2. The tracer, with a log

<!-- field-box id="pt_tracer" index="1" -->

### Narrative

From `code`

The shipped materials scene at 640x360, plus the one new line: a convergence log.

`recordConvergence()` attaches a log the tracer appends to from inside its own `render`, so
it costs no frames. This replaces the shape four scratch boxes were written in last night --
`frames { repeat(180) { frame() } }` with a `println` inside -- which spends 180 real frames
to learn what the log already knows.

```edn [field-box id="pt_tracer" role="properties"]
{"id" "pt_tracer" "index" 1 "name" "The tracer, with a log" "parents" ["root" "pt_renderer"] "children" ["pt_instrument" "pt_plot" "pt_swap" "pt_restore"] "properties" {"children" #{#afield/box "pt_instrument" #afield/box "pt_plot" #afield/box "pt_swap" #afield/box "pt_restore"} "parents" #{#afield/box "root" #afield/box "pt_renderer"} "name" "The tracer, with a log" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_tracer_code_.kt" "lines" 22 "characters" 922 "sha256" "dd13d79a679b42ea921fa98f3f5b702be41f074855f559828ea8df26a8287c79"}]}
```

```kotlin [field-property box="pt_tracer" name="code" path="pt_tracer_code_.kt"]
// The shipped materials scene at 640x360, plus the one new line: a convergence log.
//
// `recordConvergence()` attaches a log the tracer appends to from inside its own `render`, so
// it costs no frames. This replaces the shape four scratch boxes were written in last night --
// `frames { repeat(180) { frame() } }` with a `println` inside -- which spends 180 real frames
// to learn what the log already knows.
import afield.graphics.scene.fabricated.*
import java.io.File

prop<PathTracingRenderer>(tracer)
prop<PathTraceConvergence>(log)

tracer = PathTraceDemo.install(renderer,
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)
tracer.samplesPerFrame = 1
tracer.maxBounces = 5
tracer.indirectClamp = 10f
tracer.exposure = 1.5f
tracer.blitToScreen()

log = tracer.recordConvergence()
println("${tracer.backend}: ${tracer.triangleCount} triangles, ${tracer.width} x ${tracer.height}")
```

## 3. Swap the integrator, live

<!-- field-box id="pt_swap" index="2" -->

### Narrative

From `code`

Three calls, three different answers, and that is the whole point of the type.

```edn [field-box id="pt_swap" role="properties"]
{"id" "pt_swap" "index" 2 "name" "Swap the integrator, live" "parents" ["root" "pt_tracer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pt_tracer"} "name" "Swap the integrator, live" "_tombstone" false "frame" #afield/rect [60.0 4040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_swap_code_.kt" "lines" 36 "characters" 2208 "sha256" "902e0e0cee75c1d31f129bad6eaa52a808bf0ae107d1f284a8afff18bd95dc9b"}]}
```

```kotlin [field-property box="pt_swap" name="code" path="pt_swap_code_.kt"]
// Three calls, three different answers, and that is the whole point of the type.
import afield.graphics.scene.fabricated.*

prop<PathTracingRenderer>(tracer)

// 1. No argument offers the packaged integrator -- which is exactly what is already
//    running. UNCHANGED. `ComputeModulePointer.reload` answers a bare `false` here, the
//    same `false` it answers for a compile failure, so a `check(...)` around it throws on
//    a no-op. That is why this returns three outcomes instead of a boolean.
println(tracer.reloadIntegrator())

// 2. A source that does not compile. FAILED, with the compiler's own messages, and the
//    last working pipeline keeps rendering -- watch the window: the image does not blink.
println(tracer.reloadIntegrator("#version 460\nvoid main() { this is not glsl }"))

// 3. A real swap, and a deliberately unphysical one: multiply the direct and indirect
//    terms where the integrator adds them, scale, and curve the result. One line changes,
//    but the whole ~30 KB integrator is recompiled and hot-swapped under a running camera.
//    `integratorSource()` hands you the packaged source with this device's HARDWARE_QUERY
//    define already applied -- the step that used to exist only at two private call sites,
//    in no document and no API.
//
//    This is the line to play with. `radiance` is what reached the camera directly and
//    `indirect` is what arrived by a bounce; both are vec3 and both are in scope at the end
//    of the integrator, so anything you can write over the two of them compiles and is in
//    the window about a second later.
val remix = tracer.integratorSource()
    .replace("return vec4(radiance+indirect,1);",
        "return vec4(pow(radiance*indirect*2, vec3(0.76)),1);")
println(tracer.reloadIntegrator(remix))

// Anything the camera sees with no bounce behind it multiplies to zero, so the sunset
// environment drops to black and the three spheres become the only lit things in the frame,
// each keeping its own colour over a soft pool on the floor; the gold one holds just its sun
// glint. The camera has not moved by a single bit.
println("reset: '" + tracer.lastResetReason + "' generation=" + tracer.resetGeneration)
```

## 4. The convergence curve

<!-- field-box id="pt_plot" index="3" -->

### Narrative

From `code`

Samples against wall seconds, with a labelled dashed rule wherever accumulation
restarted and why. Run box 3 or 4 first and re-run this to see a mark appear.

This is one call to `showSignals`, the house terse-plot helper. There is no new plotting
code anywhere in this feature: `showSignals` already takes named series, a shared x, and
`marks` drawn as full-height dashed rules carrying their label into the legend.

```edn [field-box id="pt_plot" role="properties"]
{"id" "pt_plot" "index" 3 "name" "The convergence curve" "parents" ["root" "pt_tracer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pt_tracer"} "name" "The convergence curve" "_tombstone" false "frame" #afield/rect [60.0 3040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_plot_code_.kt" "lines" 14 "characters" 624 "sha256" "291a8d5cbe0aabfba57fe77eba0062315bb85ba5b5df908f1bc51924c0c0b8f0"}]}
```

```kotlin [field-property box="pt_plot" name="code" path="pt_plot_code_.kt"]
// Samples against wall seconds, with a labelled dashed rule wherever accumulation
// restarted and why. Run box 3 or 4 first and re-run this to see a mark appear.
//
// This is one call to `showSignals`, the house terse-plot helper. There is no new plotting
// code anywhere in this feature: `showSignals` already takes named series, a shared x, and
// `marks` drawn as full-height dashed rules carrying their label into the legend.
import afield.graphics.scene.fabricated.*

prop<PathTracingRenderer>(tracer)
prop<PathTraceConvergence>(log)

println(log)
println("resets so far: " + log.resetMarks())
showConvergence(log)
```

## 5. Put the integrator back

<!-- field-box id="pt_restore" index="4" -->

### Narrative

From `code`

The same no-argument call that answered UNCHANGED in box 3 now RELOADS, because the live
pipeline is the patched one. Nothing about the tracer had to be rebuilt to get here: same
scene, same environment, same accumulation buffers, same camera.

```edn [field-box id="pt_restore" role="properties"]
{"id" "pt_restore" "index" 4 "name" "Put the integrator back" "parents" ["root" "pt_tracer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pt_tracer"} "name" "Put the integrator back" "_tombstone" false "frame" #afield/rect [60.0 5040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_restore_code_.kt" "lines" 13 "characters" 664 "sha256" "9b7b35dcd02ab56b8405ff1ef87166e38a31877f096ec50e17e254d4c0135c8f"}]}
```

```kotlin [field-property box="pt_restore" name="code" path="pt_restore_code_.kt"]
// The same no-argument call that answered UNCHANGED in box 3 now RELOADS, because the live
// pipeline is the patched one. Nothing about the tracer had to be rebuilt to get here: same
// scene, same environment, same accumulation buffers, same camera.
import afield.graphics.scene.fabricated.*

prop<PathTracingRenderer>(tracer)

println(tracer.reloadIntegrator())
println("reset: '" + tracer.lastResetReason + "' generation=" + tracer.resetGeneration)

// And the instrument again, so you can read the generation count going up and the reason
// changing. Re-run box 2 to see the reset land on the convergence curve as a labelled rule.
display("output", tracer)
```

## 6. The instrument, in one line

<!-- field-box id="pt_instrument" index="5" -->

### Narrative

From `code`

This is the whole instrument. One line.

Until tonight it produced
PathTracingRenderer afield.graphics.scene.fabricated.PathTracingRenderer@19266ca3
and nothing else -- 376 bytes, ~330 of them the wrapper div. `PathTracingRenderer` is not a
data class and had no value printer registered, so `ValueVisualizer` fell all the way through
to `formatFallbackObject`, which is a class name and a `toString()`. Every one of the 26
`afield.four` types the tracer is built out of -- `Renderer`, `Camera`, `ComputeModulePointer`,
`RenderToTexture`, `VulkanBuffer` -- has had a printer since it was written.

Re-run this box at any time; it reads the live tracer.

The row to read is `up`. This scene's camera declares an up of (0, 1, 0), and the tracer
builds its own as `right x forward`, which on this camera is 11.94 degrees away -- so the
declared vector is not the one the image is made with. Two of last night's four scratch boxes
were printing `camera.up` and its three components as `toRawBits()`, hunting exactly this, and
no property tree could have shown it to them: the value is a local inside a private method.
The row only appears when the two disagree.

```edn [field-box id="pt_instrument" role="properties"]
{"id" "pt_instrument" "index" 5 "name" "The instrument, in one line" "parents" ["root" "pt_tracer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pt_tracer"} "name" "The instrument, in one line" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_instrument_code_.kt" "lines" 22 "characters" 1332 "sha256" "31fc58d0342007b91192e213e485e338d688230db1b66654bdb47ae6287529a0"}]}
```

```kotlin [field-property box="pt_instrument" name="code" path="pt_instrument_code_.kt"]
// This is the whole instrument. One line.
//
// Until tonight it produced
//   PathTracingRenderer afield.graphics.scene.fabricated.PathTracingRenderer@19266ca3
// and nothing else -- 376 bytes, ~330 of them the wrapper div. `PathTracingRenderer` is not a
// data class and had no value printer registered, so `ValueVisualizer` fell all the way through
// to `formatFallbackObject`, which is a class name and a `toString()`. Every one of the 26
// `afield.four` types the tracer is built out of -- `Renderer`, `Camera`, `ComputeModulePointer`,
// `RenderToTexture`, `VulkanBuffer` -- has had a printer since it was written.
//
// Re-run this box at any time; it reads the live tracer.
//
// The row to read is `up`. This scene's camera declares an up of (0, 1, 0), and the tracer
// builds its own as `right x forward`, which on this camera is 11.94 degrees away -- so the
// declared vector is not the one the image is made with. Two of last night's four scratch boxes
// were printing `camera.up` and its three components as `toRawBits()`, hunting exactly this, and
// no property tree could have shown it to them: the value is a local inside a private method.
// The row only appears when the two disagree.
import afield.graphics.scene.fabricated.PathTracingRenderer

prop<PathTracingRenderer>(tracer)
display("output", tracer)
```

