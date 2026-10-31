# Field Document: streaming_diffusion

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 · renderer

<!-- field-box id="diffusion_renderer" index="0" -->

```edn [field-box id="diffusion_renderer" role="properties"]
{"id" "diffusion_renderer" "index" 0 "name" "2 · renderer" "parents" ["root"] "children" ["diffusion_curves"] "properties" {"children" #{#afield/box "diffusion_curves"} "parents" #{#afield/box "root"} "name" "2 · renderer" "_tombstone" false "frame" #afield/rect [40.0 160.0 360.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "diffusion_renderer_code_.kt" "lines" 15 "characters" 275 "sha256" "e90393b044932ad7e022f5505bec768466f0d819aaa553c877bfe061d981c6d2"}]}
```

```kotlin [field-property box="diffusion_renderer" name="code" path="diffusion_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field")
    Renderer(window)
}

println("agent graphics renderer ready")
start(renderer::run)
```

## 2. 3 · spinning curves

<!-- field-box id="diffusion_curves" index="1" -->

```edn [field-box id="diffusion_curves" role="properties"]
{"id" "diffusion_curves" "index" 1 "name" "3 · spinning curves" "parents" ["root" "diffusion_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "diffusion_renderer"} "name" "3 · spinning curves" "_tombstone" false "frame" #afield/rect [440.0 160.0 360.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "diffusion_curves_code_.kt" "lines" 108 "characters" 4734 "sha256" "ea557ba7b1037b4b3251144beb84571f1c6b4d1cbf6e305c87ac6c5dc7520072"}]}
```

```kotlin [field-property box="diffusion_curves" name="code" path="diffusion_curves_code_.kt"]
import afield.media.video.diffusion.*

// Connect this box beneath a running renderer box before launching it.
val owner: Renderer = fromContext()
prop<SpinningDiffusionDemo>(diffusionDemo)
fromContextWithNameOrNull<SpinningDiffusionDemo>("diffusionDemo")?.close()

// Edit these before launching. identity/invert also use the GPU bridge in both directions.
val backend = "diffusion"
val whiteOnBlack = false
val root = StreamDiffusionSupport.configuredRoot()
val config = root?.let {
    StreamDiffusionConfig(it, backend = backend, displayReprojection = true, historyBlend = .35, noiseAdvection = .8)
}
val support = StreamDiffusionSupport.probe(owner, config)
if (!support.available) {
    print("Streaming diffusion unavailable: ${support.reason}")
    markdown(
        """
        ## Streaming diffusion is unavailable here

        ${support.reason}

        See [Streaming diffusion](https://openendedgroup.com/Field4/streaming_diffusion/).
        The model environment is optional; you can keep editing this document.
    """
            .trimIndent(),
    )
} else {
    var material = 1 // 0 ink, 1 charcoal, 2 barbed wire
    var promptOverride = "" // Any text prompt; blank selects the material preset.
    var strength = .85
    var noiseAdvection = .8
    var adaptPrompt = whiteOnBlack
    var pendingReset = false
    var animate = true
    fun selectedPrompt(): String = promptOverride.trim().ifEmpty {
        when {
            material == 2 && adaptPrompt -> StreamDiffusionPresets.WHITE_WIRE
            material == 2 -> StreamDiffusionPresets.WIRE
            material == 1 && adaptPrompt -> StreamDiffusionPresets.WHITE_CHARCOAL
            material == 1 -> StreamDiffusionPresets.CHARCOAL
            adaptPrompt -> StreamDiffusionPresets.WHITE_INK
            else -> StreamDiffusionPresets.INK
        }
    }
    diffusionDemo = SpinningDiffusionDemo(
        owner,
        checkNotNull(config).copy(prompt = selectedPrompt(), strength = strength),
        whiteOnBlack = whiteOnBlack,
    )
    val demo = diffusionDemo
    controlGroup("diffusion") {
        slider("0: ink / 1: charcoal / 2: wire", min = 0.0, max = 2.0, step = 1.0, initialValue = material.toDouble()) {
            material = it.toInt()
            pendingReset = true
        }
        text("Prompt override (blank uses preset)", initialValue = promptOverride) {
            promptOverride = it
            pendingReset = true
        }
        slider("Strength", min = .2, max = .9, step = .05, initialValue = strength) {
            strength = it
            pendingReset = true
        }
        checkbox("Adapt prompt for white marks on black", initialValue = adaptPrompt) {
            adaptPrompt = it
            pendingReset = true
        }
        checkbox("Reproject output to current geometry", initialValue = demo.view.displayReprojection) {
            demo.view.displayReprojection = it
        }
        slider("History blend", min = 0.0, max = .9, step = .05, initialValue = demo.view.historyBlend) {
            demo.view.historyBlend = it
        }
        slider("Noise advection (experimental)", min = 0.0, max = 1.0, step = .05, initialValue = noiseAdvection) {
            noiseAdvection = it
            pendingReset = true
        }
        checkbox("Animate", initialValue = true) { animate = it }
        button("Reset causal state / seed") { if (it) pendingReset = true }
    }
    displayOpen("diffusion")
    print("Left: current source. Right: delayed diffusion output. GPU pixel transport. ${demo.view.session.logFile}")
    frames {
        var count = 0
        var angle = 0.0
        var previous = System.nanoTime()
        while (!owner.isClosed) {
            val now = System.nanoTime()
            if (animate) angle += (now - previous) * 1e-9 * Math.PI * 2 * .12
            previous = now
            demo.angle = angle.toFloat()
            if (pendingReset && demo.view.session.ready) {
                demo.view.reset(selectedPrompt(), strength, 0, noiseAdvection)
                pendingReset = false
            }
            if (count++ % 120 == 0)
                print(
                    "${demo.view.session.status}; captured=${demo.view.capturedFrames}, shown=${demo.view.presentedFrames}, skipped=${demo.view.skippedCaptures}, displayWarps=${demo.view.displayReprojectionRequests}, historyWarps=${demo.view.historyBlendRequests}, noiseMaps=${demo.view.noiseMotionFrames}; ${demo.view.session.capabilities}",
                )
            if (count % 120 == 1 && demo.view.session.capabilities.optBoolean("tensorrt_active"))
                print("Active TAEHV TensorRT; shown=${demo.view.presentedFrames}")
            frame()
        }
    }
        .end { demo.close() }
}
```

## 3. 1 · streaming diffusion

<!-- field-box id="diffusion_guide" index="2" -->

```edn [field-box id="diffusion_guide" role="properties"]
{"id" "diffusion_guide" "index" 2 "name" "1 · streaming diffusion" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · streaming diffusion" "_tombstone" false "frame" #afield/rect [40.0 40.0 360.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "diffusion_guide_code_.kt" "lines" 18 "characters" 738 "sha256" "6aa0eef134434b5745dbfcf31135427e1bd6b55996b1fd322ccd28e032c446d6"}]}
```

```kotlin [field-property box="diffusion_guide" name="code" path="diffusion_guide_code_.kt"]
markdown(
    """
    # Streaming diffusion

    Run **2 · renderer**, then **3 · spinning curves**.
    Source is on the left; the returned stylized image is on the right.
    The example starts with charcoal. Enter any text in the prompt override.
    Open the diffusion controls to change material, strength, reprojection,
    history blending and experimental noise advection.

    The local model worker is optional and requires Linux/NVIDIA.
    On other platforms the curves box explains availability without starting it.
    Set `FIELD_STREAMDIFFUSION_ROOT` before starting Field after completing the setup.

    [Setup and RTT integration](https://openendedgroup.com/Field4/streaming_diffusion/)
    """
        .trimIndent(),
)
```

