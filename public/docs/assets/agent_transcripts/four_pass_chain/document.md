# Field Document: four_pass_chain

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="pc_renderer" index="0" -->

```edn [field-box id="pc_renderer" role="properties"]
{"id" "pc_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["pc_scene"] "properties" {"children" #{#afield/box "pc_scene"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pc_renderer_code_.kt" "lines" 11 "characters" 250 "sha256" "3136c727cd3fd3d333acf6cd802906e64dbef95f446c869224d5fd0b491ca3e2"}]}
```

```kotlin [field-property box="pc_renderer" name="code" path="pc_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Pass chain")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. scene

<!-- field-box id="pc_scene" index="1" -->

### Narrative

From `code`

`afield.four.fabricated` is not among a box's automatic imports, unlike `afield.four`.

```edn [field-box id="pc_scene" role="properties"]
{"id" "pc_scene" "index" 1 "name" "scene" "parents" ["root" "pc_renderer"] "children" ["pc_chain"] "properties" {"children" #{#afield/box "pc_chain"} "parents" #{#afield/box "root" #afield/box "pc_renderer"} "name" "scene" "_tombstone" false "frame" #afield/rect [340.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pc_scene_code_.kt" "lines" 38 "characters" 1198 "sha256" "65b80104bfaa9f7c9b54413599f8f62f1e27f5b539535ed1a00525f2f8c4bff7"}]}
```

```kotlin [field-property box="pc_scene" name="code" path="pc_scene_code_.kt"]
// `afield.four.fabricated` is not among a box's automatic imports, unlike `afield.four`.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f

prop<StandardRenderer.Bundle>(bundle)

val r: Renderer = fromContext()

once.bundle {
    val b = StandardRenderer.build(
        r,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.09f, 0.10f, 0.13f)
        )
    )
    b.scene.camera.at.set(0f, 1.5f, -4.2f)
    b.scene.camera.target.set(0f, 0f, 0f)
    b.scene.lightDirection.set(Vector3f(-0.5f, -1f, -0.3f).normalize())
    b.scene.lightIntensity = 3.4f
    b.scene.shadowStrength = 1f
    b.scene.aoStrength = 0.8f

    for (ix in -2..2) {
        for (iz in -2..2) {
            b.registerSphere(name = "ball-$ix-$iz", radius = 0.3f, slices = 24, stacks = 24, renderer = r)
                .setModel(Matrix4f().translate(ix * 0.85f, 0f, iz * 0.85f))
        }
    }
    b.applyScenePlan()
    b.present(renderer = r)
    b.attachTo(r)
    b
}
println("scene ready: ${bundle.postStack.names()}")
```

## 3. pass chain

<!-- field-box id="pc_chain" index="2" -->

```edn [field-box id="pc_chain" role="properties"]
{"id" "pc_chain" "index" 2 "name" "pass chain" "parents" ["root" "pc_scene"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pc_scene"} "name" "pass chain" "_tombstone" false "frame" #afield/rect [640.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pc_chain_code_.kt" "lines" 38 "characters" 1371 "sha256" "310a470e88ed8057770a79cdeefd65c979211239ad2b060e74e1d8f2d34b3bc7"}]}
```

```kotlin [field-property box="pc_chain" name="code" path="pc_chain_code_.kt"]
import afield.four.fabricated.FabricateBuiltChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.addPassChain

prop<FabricateBuiltChain>(chain)

val r: Renderer = fromContext()
val b: StandardRenderer.Bundle = fromContext()

// Declared once. Six passes, each with its own resolution: reduce to a half, reduce to a
// quarter, two aperture discs on the cheap quarter-resolution image, and a composite that
// rejoins at full resolution while sampling the small blur. `printReport` prints the plan
// — sizes, sampler wiring, shared targets, and the memory cost against one full-resolution
// target per pass. Read it before believing the picture: a chain planned at the wrong
// resolution renders something that looks perfectly plausible.
once.chain {
    val c = b.addPassChain(
        FabricatePassLibrary.apertureChain(
            nearRadius = 4f,
            farRadius = 12f,
            taps = 40,
            blend = 0.92f
        ),
        renderer = r,
        printReport = true
    )
    b.attachTo(r)
    b.present(renderer = r)
    c
}

// ── live knobs. Edit a number, hit run. No rebuild, no reallocation. ──
chain.setPush("aperture-near", "radius", 4f)
chain.setPush("aperture-far", "radius", 14f)
chain.setPush("composite", "blend", 0.92f)

println(chain.plan.report())
```

