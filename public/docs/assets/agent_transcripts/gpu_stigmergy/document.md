# Field Document: gpu_stigmergy

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="stigmergy_renderer" index="0" -->

```edn [field-box id="stigmergy_renderer" role="properties"]
{"id" "stigmergy_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["stigmergy_slime"] "properties" {"children" #{#afield/box "stigmergy_slime"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stigmergy_renderer_code_.kt" "lines" 14 "characters" 462 "sha256" "109e47dfd2511c12c72dcfa7c2d78a803114257ce4aee265c2cd7f8049b81b31"}]}
```

```kotlin [field-property box="stigmergy_renderer" name="code" path="stigmergy_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Stigmergy", initialDimensions = Rect(80.0, 80.0, 768.0, 768.0))
    Renderer(window)
}
println("renderer ready")
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. three species

<!-- field-box id="stigmergy_slime" index="1" -->

### Narrative

From `code`

Two species on one 512 x 512 trail, one channel each. Row s of `attraction` is how
species s weighs the four channels when it senses: +1 for its own, -1 for the other's.
Each follows its own roads and turns away from the other's, so the field divides into
amber and cyan territories that keep re-negotiating their borders.

```edn [field-box id="stigmergy_slime" role="properties"]
{"id" "stigmergy_slime" "index" 1 "name" "three species" "parents" ["root" "stigmergy_renderer"] "children" ["stigmergy_followers" "stigmergy_chase" "stigmergy_reset"] "properties" {"children" #{#afield/box "stigmergy_followers" #afield/box "stigmergy_chase" #afield/box "stigmergy_reset"} "parents" #{#afield/box "root" #afield/box "stigmergy_renderer"} "name" "three species" "_tombstone" false "frame" #afield/rect [340.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stigmergy_slime_code_.kt" "lines" 21 "characters" 769 "sha256" "05283f3965f562546cabbcc9bcc852992ca94bd83475c20962db4e7674af5eee"}]}
```

```kotlin [field-property box="stigmergy_slime" name="code" path="stigmergy_slime_code_.kt"]
/*
Two species on one 512 x 512 trail, one channel each. Row s of `attraction` is how
species s weighs the four channels when it senses: +1 for its own, -1 for the other's.
Each follows its own roads and turns away from the other's, so the field divides into
amber and cyan territories that keep re-negotiating their borders.
*/
prop<ShaderPhysarumSystem>(physarum) = ShaderPhysarumSystem() {
    width = 512
    height = 512
    agentCount = 250_000
    speciesCount = 2
    exposure = 0.06f
    attraction = listOf(
        vec(1f, -1f, 0f, 0f),
        vec(-1f, 1f, 0f, 0f),
    )
}

// steps once a frame and draws the trail, one colour per species
physarum.installInto()
println("${physarum.settings.agentCount} agents, ${physarum.settings.speciesCount} species")
```

## 3. followers

<!-- field-box id="stigmergy_followers" index="2" -->

### Narrative

From `code`

A second particle system that only reads the field. Each particle climbs the gradient
of dot(trail, weights): up species 0's channel (amber) and down species 1's (cyan), so
the magenta dots gather on amber roads and slide off cyan ones.

```edn [field-box id="stigmergy_followers" role="properties"]
{"id" "stigmergy_followers" "index" 2 "name" "followers" "parents" ["root" "stigmergy_slime"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stigmergy_slime"} "name" "followers" "_tombstone" false "frame" #afield/rect [680.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stigmergy_followers_code_.kt" "lines" 14 "characters" 540 "sha256" "d67064798994229ccc87378d8eaf5c1e9715d1914e6dd527d6837a95999d1eb5"}]}
```

```kotlin [field-property box="stigmergy_followers" name="code" path="stigmergy_followers_code_.kt"]
/*
A second particle system that only reads the field. Each particle climbs the gradient
of dot(trail, weights): up species 0's channel (amber) and down species 1's (cyan), so
the magenta dots gather on amber roads and slide off cyan ones.
*/
var followers = TrailFollowers(physarum.trail) {
    count = 20_000
    weights = vec(1f, -1f, 0f, 0f)
    steering = 0.4f
    pointSize = 3f
}
// draws after the trail's preview, so the points sit on top of it
followers.installInto()
println("${followers.settings.count} followers on the trail")
```

## 4. reset

<!-- field-box id="stigmergy_reset" index="3" -->

### Narrative

From `code`

empty the trail, restore the territorial matrices, and scatter the agents again

```edn [field-box id="stigmergy_reset" role="properties"]
{"id" "stigmergy_reset" "index" 3 "name" "reset" "parents" ["root" "stigmergy_slime"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stigmergy_slime"} "name" "reset" "_tombstone" false "frame" #afield/rect [340.0 400.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stigmergy_reset_code_.kt" "lines" 9 "characters" 293 "sha256" "a13b7133fc33e4c30b665967c693ebf868ac7452bc5d3cb2d9c511ba372ec01a"}]}
```

```kotlin [field-property box="stigmergy_reset" name="code" path="stigmergy_reset_code_.kt"]
// empty the trail, restore the territorial matrices, and scatter the agents again
physarum.settings.depositInto = null
physarum.settings.attraction = listOf(
    vec(1f, -1f, 0f, 0f),
    vec(-1f, 1f, 0f, 0f),
)
physarum.trail.clear()
physarum.resetAgents(System.nanoTime())
println("reset")
```

## 5. chase

<!-- field-box id="stigmergy_chase" index="4" -->

### Narrative

From `code`

Species may write into each other's channels. Here species 1 (cyan) also lays half a
mark of species 0's scent wherever it goes, and species 0 (amber) is repelled twice as
hard by cyan as before. Re-run with other numbers: both matrices are read every frame.

```edn [field-box id="stigmergy_chase" role="properties"]
{"id" "stigmergy_chase" "index" 4 "name" "chase" "parents" ["root" "stigmergy_slime"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stigmergy_slime"} "name" "chase" "_tombstone" false "frame" #afield/rect [340.0 160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stigmergy_chase_code_.kt" "lines" 14 "characters" 519 "sha256" "ffaaa46157bf368fa7808771ee282165c4ff107a44b8d3d6a1d2362957a9cdce"}]}
```

```kotlin [field-property box="stigmergy_chase" name="code" path="stigmergy_chase_code_.kt"]
/*
Species may write into each other's channels. Here species 1 (cyan) also lays half a
mark of species 0's scent wherever it goes, and species 0 (amber) is repelled twice as
hard by cyan as before. Re-run with other numbers: both matrices are read every frame.
*/
physarum.settings.depositInto = listOf(
    vec(1f, 0f, 0f, 0f),
    vec(0.5f, 1f, 0f, 0f),
)
physarum.settings.attraction = listOf(
    vec(1f, -2f, 0f, 0f),
    vec(1f, 1f, 0f, 0f),
)
println("chase: species 1 follows species 0 and marks its channel")
```

