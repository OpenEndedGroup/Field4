# Field Document: particle_lenia_gpu

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="plg_renderer" index="0" -->

```edn [field-box id="plg_renderer" role="properties"]
{"id" "plg_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["plg_lenia"] "properties" {"children" #{#afield/box "plg_lenia"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "plg_renderer_code_.kt" "lines" 12 "characters" 266 "sha256" "020c6c6af4d6090b60cd76d060cd0ed5971d41725c5b8517143bc72156a94f21"}]}
```

```kotlin [field-property box="plg_renderer" name="code" path="plg_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Particle Lenia")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. 6400 Particle Lenia particles

<!-- field-box id="plg_lenia" index="1" -->

```edn [field-box id="plg_lenia" role="properties"]
{"id" "plg_lenia" "index" 1 "name" "6400 Particle Lenia particles" "parents" ["root" "plg_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "plg_renderer"} "name" "6400 Particle Lenia particles" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "plg_lenia_code_.kt" "lines" 25 "characters" 873 "sha256" "6c233bac592f0f86739b4b90ab012c32990c89de38498a0592ba64f5c7fdb4ff"}]}
```

```kotlin [field-property box="plg_lenia" name="code" path="plg_lenia_code_.kt"]
import afield.simulation.physics.ShaderParticleLenia

prop<ShaderParticleLenia>(lenia)

// 64 clusters of 100 particles under the rotator rule: 6400 particles, one creature per cluster.
once.lenia {
    val grid = GpuNeighbourGrid(
        capacity = 6400,
        cellsPerSide = 40,
        domainMin = Vector2f(-120f, -120f),
        domainSize = 240f,
        renderer = renderer,
        name = "leniaGrid",
    )
    ShaderParticleLenia(grid, 6400, renderer, ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3), name = "lenia").also {
        it.reset(creatures = 64, perCreature = 100, side = 8.5f, spacing = 26f, seed = 1)
        it.install(renderer.renderList.others)
        it.installDrawInto()
    }
}

lenia.substeps = 20
lenia.pointSize = 3f
lenia.viewScale = 110f
println("particles: " + lenia.count + ", cutoff " + lenia.cutoff + ", cell " + lenia.grid.cellSize)
```

