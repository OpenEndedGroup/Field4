# Field Document: frame_history_reprojection

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. frame history renderer

<!-- field-box id="rp_renderer" index="0" -->

```edn [field-box id="rp_renderer" role="properties"]
{"id" "rp_renderer" "index" 0 "name" "frame history renderer" "parents" ["root"] "children" ["rp_bundle"] "properties" {"children" #{#afield/box "rp_bundle"} "parents" #{#afield/box "root"} "name" "frame history renderer" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_renderer_code_.kt" "lines" 11 "characters" 260 "sha256" "a2f87b84ec48ac6ac3781e33256d8f1db58cf7dadc51509ecae9b4bb479e1c0b"}]}
```

```kotlin [field-property box="rp_renderer" name="code" path="rp_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · frame history reprojection")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. lit scene

<!-- field-box id="rp_bundle" index="1" -->

```edn [field-box id="rp_bundle" role="properties"]
{"id" "rp_bundle" "index" 1 "name" "lit scene" "parents" ["root" "rp_renderer"] "children" ["rp_chain"] "properties" {"children" #{#afield/box "rp_chain"} "parents" #{#afield/box "root" #afield/box "rp_renderer"} "name" "lit scene" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_bundle_code_.kt" "lines" 50 "characters" 2128 "sha256" "144e5431f5f920c476bded0e120bdecfd43507eadd6c12f14d98fa2bdc22ddb1"}]}
```

```kotlin [field-property box="rp_bundle" name="code" path="rp_bundle_code_.kt"]
import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.scene.fabricated.applyScenePlan

prop<StandardRenderer.Bundle>(bundle)

/*
The registration handles, kept so the next box can move each ball independently.
`registerSphere` returns the `PullingMeshPair` it registered and there is no by-name
lookup on a bundle, so the handle is the only way back to an object once it is in the
scene — hold it at registration or you cannot animate it at all.
*/
prop<MutableList<StandardRenderer.Bundle.PullingMeshPair>>(balls)

// A `once`, unlike the chain box: `StandardRenderer.build` has no replace-in-place path,
// so re-running it would strand the bundle the chain is appended to.
once.bundle {
    val b = StandardRenderer.build(
        renderer,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.05f, 0.06f, 0.08f),
        ),
    )
    b.scene.camera.at.set(0f, 1.4f, -4.2f)
    b.scene.camera.target.set(0f, 0f, 0f)
    b.scene.lightIntensity = 3.4f
    b.scene.aoStrength = 0.8f
    // The standard stack's own motion blur off: it is a second, different temporal effect
    // and it would sit upstream of everything the chain below is showing you.
    b.scene.motionBlurStrength = 0f
    /*
    Silhouette edges, so there is something for a reprojection to be right or wrong about.
    The handles go into `balls` in row-major order, ix outer and iz inner, which is the
    order the animation box relies on to recover (ix, iz) from an index.
    */
    val handles = mutableListOf<afield.graphics.scene.fabricated.StandardRenderer.Bundle.PullingMeshPair>()
    for (ix in -2..2) for (iz in -2..2) {
        handles.add(
            b.registerSphere(name = "ball-" + ix + "-" + iz, radius = 0.3f, slices = 24, stacks = 24)
                .setModel(Matrix4f().translate(ix * 0.85f, 0f, iz * 0.85f)),
        )
    }
    balls = handles
    b.applyScenePlan()
    b.present(renderer = renderer)
    b.attachTo(renderer)
    b
}
println("bundle ready: " + bundle.postStack.names() + ", " + balls.size + " balls")
```

## 3. reprojected accumulation

<!-- field-box id="rp_chain" index="2" -->

```edn [field-box id="rp_chain" role="properties"]
{"id" "rp_chain" "index" 2 "name" "reprojected accumulation" "parents" ["root" "rp_bundle"] "children" ["rp_modes"] "properties" {"children" #{#afield/box "rp_modes"} "parents" #{#afield/box "root" #afield/box "rp_bundle"} "name" "reprojected accumulation" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_chain_code_.kt" "lines" 96 "characters" 3522 "sha256" "35224fab41471ffd17ea0273f9b8a49dd196e5c486ff798dd2b79b44b28b36a3"}]}
```

```kotlin [field-property box="rp_chain" name="code" path="rp_chain_code_.kt"]
import afield.graphics.scene.fabricated.FabricateBuiltChain
import afield.graphics.scene.fabricated.FabricatePassChain
import afield.graphics.scene.fabricated.FabricatePassLibrary
import afield.graphics.scene.fabricated.addPassChain

prop<FabricateBuiltChain>(chain)

// Change a reset assignment with Alt-Enter; declarations belong to a full launch.
var historyBlend = 0.92f
historyBlend = 0.92f // History weight, from 0 (current frame only) to just below 1.
var motionGain = 20f
motionGain = 20f // Debug display gain; large gains clip the red/green motion channels.

// Angles are radians, distances are world units, speeds are radians per simulation second.
var yawAmplitude = 0.10
yawAmplitude = 0.10
var yawSpeed = 0.45
yawSpeed = 0.45
var dollyAmplitude = 1.7
dollyAmplitude = 1.7
var dollySpeed = 0.6
dollySpeed = 0.6
var bobAmplitude = 0.45
bobAmplitude = 0.45
var bobSpeed = 1.7
bobSpeed = 1.7
var driftAmplitude = 0.18
driftAmplitude = 0.18
var driftSpeed = 0.8
driftSpeed = 0.8

// Persistent pixels plus frame history let accumulation follow the moving scene.
// Resolution must match the bundle's 1024x1024 input. Per-pixel motion includes the balls.
chain = bundle.addPassChain(
    FabricatePassLibrary.temporalAccumulate(
        name = "taa",
        source = FabricatePassChain.CHAIN_INPUT,
        blend = historyBlend,
        resolution = 1024 to 1024,
        perPixelMotion = true,
    ) +
        FabricatePassLibrary.temporalDebug(
            name = "display",
            source = "taa",
            mode = 0,
            motionScale = motionGain,
            perPixelMotion = true,
        ),
    printReport = true,
)
chain["blend"] = { historyBlend }
chain["motionScale"] = { motionGain }
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// Reset one stable pose on launch. Translate eye and target together, then apply bounded
// world-Y yaw to the original direction; no camera rotation accumulates between frames/runs.
val camera = bundle.scene.camera
val initialEye = Vector3f(0f, 1.4f, -4.2f)
val initialTarget = Vector3f(0f, 0f, 0f)
val initialDirection = Vector3f(initialTarget).sub(initialEye)
val direction = Vector3f()
camera.at.set(initialEye)
camera.target.set(initialTarget)
camera.up.set(0f, 1f, 0f)
var time = 0.0
time = 0.0
bundle.invalidateFrameHistory() // Relaunching is an intentional camera/object cut.

frames {
    while (true) {
        time += 1.0 / 60.0 // One fixed simulation step per Field frame.
        for (i in balls.indices) {
            val ix = i / 5 - 2
            val iz = i % 5 - 2
            // Different phases separate neighbouring balls, exposing local correspondence.
            val bob = sin(time * bobSpeed + ix * 0.9 + iz * 0.6) * bobAmplitude
            val drift = sin(time * driftSpeed + iz * 1.3) * driftAmplitude
            balls[i].setModel(
                Matrix4f().translate(ix * 0.85f + drift.toFloat(), bob.toFloat(), iz * 0.85f),
            )
        }
        val dolly = sin(time * dollySpeed) * dollyAmplitude
        val yaw = sin(time * yawSpeed) * yawAmplitude
        camera.at.set(initialEye).add(0f, 0f, dolly.toFloat())
        val cosYaw = kotlin.math.cos(yaw)
        val sinYaw = sin(yaw)
        direction.set(
            (initialDirection.x * cosYaw + initialDirection.z * sinYaw).toFloat(),
            initialDirection.y,
            (initialDirection.z * cosYaw - initialDirection.x * sinYaw).toFloat(),
        )
        camera.target.set(camera.at).add(direction)
        frame()
    }
}
```

## 4. the four debug modes

<!-- field-box id="rp_modes" index="3" -->

### Narrative

From `code`

Each mode tests a different part of the same per-pixel correspondence:
0 accumulated beauty; 1 backward motion, including each ball; 2 previous UV,
which breaks at moving silhouettes; 3 green valid history, red invalid/off-screen reprojection.

```edn [field-box id="rp_modes" role="properties"]
{"id" "rp_modes" "index" 3 "name" "the four debug modes" "parents" ["root" "rp_chain"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rp_chain"} "name" "the four debug modes" "_tombstone" false "frame" #afield/rect [60.0 3040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_modes_code_.kt" "lines" 15 "characters" 543 "sha256" "75d58df8878d8c88bb42dfbe5087115ea91b4edc9471380a72321f3f07d169f1"}]}
```

```kotlin [field-property box="rp_modes" name="code" path="rp_modes_code_.kt"]
/*
Each mode tests a different part of the same per-pixel correspondence:
0 accumulated beauty; 1 backward motion, including each ball; 2 previous UV,
which breaks at moving silhouettes; 3 green valid history, red invalid/off-screen reprojection.
*/
val names = ["beauty", "backward motion", "previous UV", "availability"]
frames {
    while (true) {
        for (mode in 0..3) {
            chain.setPush("display", "mode", mode)
            println("mode " + mode + " - " + names[mode])
            repeat(150) { frame() }
        }
    }
}
```

