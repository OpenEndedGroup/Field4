# Field Document: gpu_physarum

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="physarum_renderer" index="0" -->

```edn [field-box id="physarum_renderer" role="properties"]
{"id" "physarum_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["physarum_slime"] "properties" {"children" #{#afield/box "physarum_slime"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "physarum_renderer_code_.kt" "lines" 14 "characters" 461 "sha256" "1b69b7a77e28606eec378520eb7c5f26889d41235268171eb1d97d2855f3cac7"}]}
```

```kotlin [field-property box="physarum_renderer" name="code" path="physarum_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Physarum", initialDimensions = Rect(80.0, 80.0, 768.0, 768.0))
    Renderer(window)
}
println("renderer ready")
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. slime

<!-- field-box id="physarum_slime" index="1" -->

### Narrative

From `code`

A million agents on a 1024 x 1024 trail. Each one looks at the trail three ways ahead,
turns toward the strongest, steps a pixel and leaves a mark; the trail blurs and fades
by itself. The defaults are the CPU PhysarumParams defaults, so this is the same model
as the page's 6000-agent swarm at about 170 times the population.

```edn [field-box id="physarum_slime" role="properties"]
{"id" "physarum_slime" "index" 1 "name" "slime" "parents" ["root" "physarum_renderer"] "children" ["physarum_weather" "physarum_reset"] "properties" {"children" #{#afield/box "physarum_weather" #afield/box "physarum_reset"} "parents" #{#afield/box "root" #afield/box "physarum_renderer"} "name" "slime" "_tombstone" false "frame" #afield/rect [340.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "physarum_slime_code_.kt" "lines" 16 "characters" 665 "sha256" "f458b2f9634dff3ad6cdd18a9ab2103b65b6773da272d3a0173c2e81c7c9d884"}]}
```

```kotlin [field-property box="physarum_slime" name="code" path="physarum_slime_code_.kt"]
/*
A million agents on a 1024 x 1024 trail. Each one looks at the trail three ways ahead,
turns toward the strongest, steps a pixel and leaves a mark; the trail blurs and fades
by itself. The defaults are the CPU PhysarumParams defaults, so this is the same model
as the page's 6000-agent swarm at about 170 times the population.
*/
prop<ShaderPhysarumSystem>(physarum) = ShaderPhysarumSystem() {
    width = 1024
    height = 1024
    agentCount = 1_000_000
    speciesCount = 2
}

// steps once a frame and draws the trail, one colour per species
physarum.installInto()
println("${physarum.settings.agentCount} agents, ${physarum.settings.speciesCount} species")
```

## 3. weather

<!-- field-box id="physarum_weather" index="2" -->

### Narrative

From `code`

Everything here is read every frame, so re-run this box with other numbers and the
running network responds: wind drags the whole trail (pixels per frame), a longer
half-life makes thicker, slower roads, and jitter makes agents wander off them.

```edn [field-box id="physarum_weather" role="properties"]
{"id" "physarum_weather" "index" 2 "name" "weather" "parents" ["root" "physarum_slime"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "physarum_slime"} "name" "weather" "_tombstone" false "frame" #afield/rect [340.0 160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "physarum_weather_code_.kt" "lines" 9 "characters" 502 "sha256" "cfef7c47e8d0c586f634bb99c04d5a117273cf4c21b61f91d65e94319b4f7944"}]}
```

```kotlin [field-property box="physarum_weather" name="code" path="physarum_weather_code_.kt"]
/*
Everything here is read every frame, so re-run this box with other numbers and the
running network responds: wind drags the whole trail (pixels per frame), a longer
half-life makes thicker, slower roads, and jitter makes agents wander off them.
*/
physarum.trail.settings.windPx = Vector2f(0.25f, 0f)
physarum.trail.settings.halfLifeFrames = vec(12f, 12f, 12f, 12f)
physarum.settings.jitter = 0.05f
println("wind ${physarum.trail.settings.windPx}, decay per frame ${physarum.trail.decayFactors()}")
```

## 4. reset

<!-- field-box id="physarum_reset" index="3" -->

### Narrative

From `code`

empty the trail and scatter the agents again, with a new seed each time

```edn [field-box id="physarum_reset" role="properties"]
{"id" "physarum_reset" "index" 3 "name" "reset" "parents" ["root" "physarum_slime"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "physarum_slime"} "name" "reset" "_tombstone" false "frame" #afield/rect [340.0 280.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "physarum_reset_code_.kt" "lines" 4 "characters" 155 "sha256" "0433616bd59a2f1fb93c37df3b901e41805d23fa2bfdad47106826b293f4b3ef"}]}
```

```kotlin [field-property box="physarum_reset" name="code" path="physarum_reset_code_.kt"]
// empty the trail and scatter the agents again, with a new seed each time
physarum.trail.clear()
physarum.resetAgents(System.nanoTime())
println("reset")
```

