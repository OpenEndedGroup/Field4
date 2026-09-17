# Field Document: frame_history_reprojection

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isDatalogRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. frame history renderer

<!-- field-box id="rp_renderer" index="0" -->

```edn [field-box id="rp_renderer" role="properties"]
{"id" "rp_renderer" "index" 0 "name" "frame history renderer" "parents" ["root"] "children" ["rp_bundle"] "properties" {"children" #{#afield/box "rp_bundle"} "parents" #{#afield/box "root"} "name" "frame history renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 100.0] "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_renderer_code_.kt" "lines" 10 "characters" 248 "sha256" "aa209cdda55609d779460ce6d29a6a33fab2d5f30684d071ad313d1f560d4d92"} {"name" "preamble" "language" "text" "lines" 6 "characters" 240 "sha256" "8c95217c5eb296f0b777d1cd1c5a4aac3bd08cee26c0845c51bfbd99a3c29f34"}]}
```

```kotlin [field-property box="rp_renderer" name="code" path="rp_renderer_code_.kt"]
import afield.four.util.Options
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

```text [field-property box="rp_renderer" name="preamble"]
import afield.four.Renderer
import afield.four.util.Window
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
```

## 2. lit scene

<!-- field-box id="rp_bundle" index="1" -->

### Narrative

From `code`

`afield.four.fabricated` is not among a box's automatic imports, and the `prop<>` type
argument is written out in full on purpose: `prop<>` rewrites the box's code and the
rewrite does not carry the snippet's own imports, so `prop<StandardRenderer.Bundle>`
under an `import ...StandardRenderer` fails against the *import* line.

```edn [field-box id="rp_bundle" role="properties"]
{"id" "rp_bundle" "index" 1 "name" "lit scene" "parents" ["root" "rp_renderer"] "children" ["rp_chain"] "properties" {"children" #{#afield/box "rp_chain"} "parents" #{#afield/box "root" #afield/box "rp_renderer"} "name" "lit scene" "_tombstone" false "frame" #afield/rect [400.0 40.0 320.0 100.0] "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_bundle_code_.kt" "lines" 40 "characters" 1782 "sha256" "67b028330c979231243a921b14a726b0ebbcb2b1bf0a5a9ea1d9a4c816df2759"} {"name" "preamble" "language" "text" "lines" 8 "characters" 407 "sha256" "7ae4d91094799cd551bb5acef3c326b45a83f3affe6bec76a9848e32c871619d"}]}
```

```kotlin [field-property box="rp_bundle" name="code" path="rp_bundle_code_.kt"]
// `afield.four.fabricated` is not among a box's automatic imports, and the `prop<>` type
// argument is written out in full on purpose: `prop<>` rewrites the box's code and the
// rewrite does not carry the snippet's own imports, so `prop<StandardRenderer.Bundle>`
// under an `import ...StandardRenderer` fails against the *import* line.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f

prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)

// A `once`, unlike the chain box: `StandardRenderer.build` has no replace-in-place path,
// so re-running it would strand the bundle the chain is appended to.
once.bundle {
    val b = StandardRenderer.build(
        renderer,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.05f, 0.06f, 0.08f)
        )
    )
    b.scene.camera.at.set(0f, 1.4f, -4.2f)
    b.scene.camera.target.set(0f, 0f, 0f)
    b.scene.lightIntensity = 3.4f
    b.scene.aoStrength = 0.8f
    // The standard stack's own motion blur off: it is a second, different temporal effect
    // and it would sit upstream of everything the chain below is showing you.
    b.scene.motionBlurStrength = 0f
    // Silhouette edges, so there is something for a reprojection to be right or wrong about.
    for (ix in -2..2) for (iz in -2..2) {
        b.registerSphere(name = "ball-" + ix + "-" + iz, radius = 0.3f, slices = 24, stacks = 24)
            .setModel(Matrix4f().translate(ix * 0.85f, 0f, iz * 0.85f))
    }
    b.applyScenePlan()
    b.present(renderer = renderer)
    b.attachTo(renderer)
    b
}
println("bundle ready: " + bundle.postStack.names())
```

```text [field-property box="rp_bundle" name="preamble"]
import afield.four.util.Window
import afield.four.Renderer
var Box.bundle by Inheritance<afield.four.fabricated.StandardRenderer.Bundle>() /**/
val Once.bundle by Onceing<afield.four.fabricated.StandardRenderer.Bundle>() /**/
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
```

## 3. reprojected accumulation

<!-- field-box id="rp_chain" index="2" -->

```edn [field-box id="rp_chain" role="properties"]
{"id" "rp_chain" "index" 2 "name" "reprojected accumulation" "parents" ["root" "rp_bundle"] "children" ["rp_modes"] "properties" {"children" #{#afield/box "rp_modes"} "parents" #{#afield/box "root" #afield/box "rp_bundle"} "name" "reprojected accumulation" "_tombstone" false "frame" #afield/rect [760.0 40.0 320.0 100.0] "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_chain_code_.kt" "lines" 42 "characters" 1735 "sha256" "f196bbb99f95afd059cad5e85bbe85076aed1d94d069c108d4e2831ecb8eaa9b"} {"name" "preamble" "language" "text" "lines" 10 "characters" 564 "sha256" "aefb4afd09c0c218970462cf6a0312ee1c30d0422a9f1cc2c65751dd5bb7fe22"}]}
```

```kotlin [field-property box="rp_chain" name="code" path="rp_chain_code_.kt"]
import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.addPassChain

prop<afield.four.fabricated.FabricateBuiltChain>(chain)

// `persistent` says WHAT last frame's pixels were. `frameHistory` says WHERE they were.
// `temporalAccumulate` declares both, and that composition is the whole point: without the
// second it accumulates correctly while the camera is still and smears the moment it moves.
//
// `resolution` must equal the chain input's size, or the reprojected UV addresses the right
// fraction of the wrong picture.
chain = bundle.addPassChain(
    FabricatePassLibrary.temporalAccumulate(
        name = "taa",
        source = FabricatePassChain.CHAIN_INPUT,
        blend = 0.92f,
        resolution = 1024 to 1024
    ) + FabricatePassLibrary.temporalDebug(
        name = "display",
        source = "taa",
        mode = 0,
        // Turn mode 1's gain WAY up. The default 8 is tuned for a fast camera; this yaw
        // moves the image about five texels a frame, which at 8 is a shift of 0.03 in
        // linear light - under two 8-bit codes, and indistinguishable from flat grey even
        // though the number underneath is right. At 60 you can see the pan.
        motionScale = 60f
    ),
    printReport = true
)
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// The camera turns, so reprojection has something to do. A pure yaw about the camera's own
// position: `reprojection` is then P*R*inverse(P), which maps a ray to a ray whatever its
// depth, so the assumed NDC depth costs nothing here.
frames {
    while (true) {
        bundle.scene.camera.yaw(0.004, aroundTarget = false)
        frame()
    }
}
```

```text [field-property box="rp_chain" name="preamble"]
import afield.four.util.Window
import afield.four.Renderer
var Box.chain by Inheritance<afield.four.fabricated.FabricateBuiltChain>() /**/
val Once.chain by Onceing<afield.four.fabricated.FabricateBuiltChain>() /**/
var Box.bundle by Inheritance<afield.four.fabricated.StandardRenderer.Bundle>() /**/
val Once.bundle by Onceing<afield.four.fabricated.StandardRenderer.Bundle>() /**/
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
```

## 4. renderer

<!-- field-box id="agent_graphics_context" index="3" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 3 "name" "renderer" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "renderer" "frame" #afield/rect [0.0 0.0 120.0 80.0] "nested" #{} "preamble" ""} "blockProperties" []}
```

## 5. the four debug modes

<!-- field-box id="rp_modes" index="4" -->

### Narrative

From `code`

The debug view is not garnish. A reprojection with a sign error in y renders an image a
person will call fine, and a temporal pass built on it looks BETTER than the correct one
on a still frame. These four modes are how you see which you have.

0 beauty          the accumulated image. The baseline to flip against.
1 backward motion where each pixel came FROM. Flat grey is zero motion.
2 previous UV     the reprojected coordinate itself, as a corner-to-corner ramp. The
only mode that shows a CONSTANT offset - inside a difference an
offset is invisible, inside an absolute coordinate it is a colour shift.
3 availability    green where history is usable, red where it is not. Watch the red
band on the trailing edge: those pixels were off screen last frame.

```edn [field-box id="rp_modes" role="properties"]
{"id" "rp_modes" "index" 4 "name" "the four debug modes" "parents" ["root" "rp_chain"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rp_chain"} "name" "the four debug modes" "_tombstone" false "frame" #afield/rect [1120.0 40.0 320.0 100.0] "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_modes_code_.kt" "lines" 21 "characters" 1149 "sha256" "7ccddec39799eeb79be3ea98a23c57d2a880421b704145c05c939a4560080850"} {"name" "preamble" "language" "text" "lines" 10 "characters" 564 "sha256" "aefb4afd09c0c218970462cf6a0312ee1c30d0422a9f1cc2c65751dd5bb7fe22"}]}
```

```kotlin [field-property box="rp_modes" name="code" path="rp_modes_code_.kt"]
// The debug view is not garnish. A reprojection with a sign error in y renders an image a
// person will call fine, and a temporal pass built on it looks BETTER than the correct one
// on a still frame. These four modes are how you see which you have.
//
//   0 beauty          the accumulated image. The baseline to flip against.
//   1 backward motion where each pixel came FROM. Flat grey is zero motion.
//   2 previous UV     the reprojected coordinate itself, as a corner-to-corner ramp. The
//                     only mode that shows a CONSTANT offset - inside a difference an
//                     offset is invisible, inside an absolute coordinate it is a colour shift.
//   3 availability    green where history is usable, red where it is not. Watch the red
//                     band on the trailing edge: those pixels were off screen last frame.
val names = listOf("beauty", "backward motion", "previous UV", "availability")
frames {
    while (true) {
        for (m in 0..3) {
            chain.setPush("display", "mode", m)
            println("mode " + m + " - " + names[m])
            repeat(150) { frame() }
        }
    }
}
```

```text [field-property box="rp_modes" name="preamble"]
import afield.four.util.Window
import afield.four.Renderer
var Box.chain by Inheritance<afield.four.fabricated.FabricateBuiltChain>() /**/
val Once.chain by Onceing<afield.four.fabricated.FabricateBuiltChain>() /**/
var Box.bundle by Inheritance<afield.four.fabricated.StandardRenderer.Bundle>() /**/
val Once.bundle by Onceing<afield.four.fabricated.StandardRenderer.Bundle>() /**/
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
```

