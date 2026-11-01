# Field Document: shader_boids_separation

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="sb_renderer" index="0" -->

```edn [field-box id="sb_renderer" role="properties"]
{"id" "sb_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["sb_even" "sb_groups"] "properties" {"children" #{#afield/box "sb_even" #afield/box "sb_groups"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sb_renderer_code_.kt" "lines" 12 "characters" 264 "sha256" "0997692c41670d3e4832bd6fe0c1c69107a546029873caf6c8264ec486db5dcd"}]}
```

```kotlin [field-property box="sb_renderer" name="code" path="sb_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Shader boids")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. separation reaches a quarter

<!-- field-box id="sb_groups" index="1" -->

```edn [field-box id="sb_groups" role="properties"]
{"id" "sb_groups" "index" 1 "name" "separation reaches a quarter" "parents" ["root" "sb_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sb_renderer"} "name" "separation reaches a quarter" "_tombstone" false "frame" #afield/rect [720.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sb_groups_code_.kt" "lines" 39 "characters" 1315 "sha256" "a4699a733f2a63b1193544eead5092231cc8e7dcbb348b188809f684d2332fba"}]}
```

```kotlin [field-property box="sb_groups" name="code" path="sb_groups_code_.kt"]
import afield.simulation.physics.ShaderBoids

prop<afield.simulation.physics.ShaderBoids>(groups)

once.groups {
    val grid = GpuNeighbourGrid(
        capacity = 12000,
        cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f),
        domainSize = 2f,
        renderer = renderer,
        name = "groupsGrid",
    )
    ShaderBoids(grid, 12000, renderer) {
            name = "groups"
            radius = 2f / 64f
        }
        .also {
            it.reset(Vector2f(-0.05f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
            it.install(renderer.renderList.others)
            it.installDrawInto()
        }
}

// The default weights; only separation's reach differs between the two boxes.
groups.settings.separation = 0.02f
groups.settings.separationRadiusFraction = 0.25f
groups.settings.alignment = 3.0f
groups.settings.cohesion = 200f
groups.settings.maxSpeed = 0.25f
groups.settings.containment = 1.5f
groups.settings.containmentRadius = 0.44f
groups.settings.containmentCenter = Vector2f(0.45f, 0f)
groups.settings.dt = 1f / 60f
groups.settings.pointSize = 3f
groups.settings.intensity = 1.5f
// No driver: left alone, the flock settles into the shape its rule makes.
groups.settings.driverStrength = 0f
println("groups: separationRadiusFraction = ${groups.settings.separationRadiusFraction}")
```

## 3. separation reaches the whole radius

<!-- field-box id="sb_even" index="2" -->

```edn [field-box id="sb_even" role="properties"]
{"id" "sb_even" "index" 2 "name" "separation reaches the whole radius" "parents" ["root" "sb_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sb_renderer"} "name" "separation reaches the whole radius" "_tombstone" false "frame" #afield/rect [380.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sb_even_code_.kt" "lines" 39 "characters" 1279 "sha256" "7915f63763786f2729df0227fc894561e5217450de789e5bb76fb2f734d1bab2"}]}
```

```kotlin [field-property box="sb_even" name="code" path="sb_even_code_.kt"]
import afield.simulation.physics.ShaderBoids

prop<afield.simulation.physics.ShaderBoids>(even)

once.even {
    val grid = GpuNeighbourGrid(
        capacity = 12000,
        cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f),
        domainSize = 2f,
        renderer = renderer,
        name = "evenGrid",
    )
    ShaderBoids(grid, 12000, renderer) {
            name = "even"
            radius = 2f / 64f
        }
        .also {
            it.reset(Vector2f(-0.95f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
            it.install(renderer.renderList.others)
            it.installDrawInto()
        }
}

// The default weights; only separation's reach differs between the two boxes.
even.settings.separation = 0.02f
even.settings.separationRadiusFraction = 1.0f
even.settings.alignment = 3.0f
even.settings.cohesion = 200f
even.settings.maxSpeed = 0.25f
even.settings.containment = 1.5f
even.settings.containmentRadius = 0.44f
even.settings.containmentCenter = Vector2f(-0.45f, 0f)
even.settings.dt = 1f / 60f
even.settings.pointSize = 3f
even.settings.intensity = 1.5f
// No driver: left alone, the flock settles into the shape its rule makes.
even.settings.driverStrength = 0f
println("even: separationRadiusFraction = ${even.settings.separationRadiusFraction}")
```

