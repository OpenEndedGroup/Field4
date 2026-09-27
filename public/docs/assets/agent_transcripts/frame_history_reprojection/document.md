# Field Document: frame_history_reprojection

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. frame history renderer

<!-- field-box id="rp_renderer" index="0" -->

```edn [field-box id="rp_renderer" role="properties"]
{"id" "rp_renderer" "index" 0 "name" "frame history renderer" "parents" ["root"] "children" ["rp_bundle"] "properties" {"children" #{#afield/box "rp_bundle"} "parents" #{#afield/box "root"} "name" "frame history renderer" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_renderer_code_.kt" "lines" 10 "characters" 248 "sha256" "aa209cdda55609d779460ce6d29a6a33fab2d5f30684d071ad313d1f560d4d92"}]}
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

## 2. lit scene

<!-- field-box id="rp_bundle" index="1" -->

### Narrative

From `code`

`afield.four.fabricated` is not among a box's automatic imports, and the `prop<>` type
argument is written out in full on purpose: `prop<>` rewrites the box's code and the
rewrite does not carry the snippet's own imports, so `prop<StandardRenderer.Bundle>`
under an `import ...StandardRenderer` fails against the *import* line.

```edn [field-box id="rp_bundle" role="properties"]
{"id" "rp_bundle" "index" 1 "name" "lit scene" "parents" ["root" "rp_renderer"] "children" ["rp_chain"] "properties" {"children" #{#afield/box "rp_chain"} "parents" #{#afield/box "root" #afield/box "rp_renderer"} "name" "lit scene" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_bundle_code_.kt" "lines" 52 "characters" 2556 "sha256" "541d91a412f681ce7ea116f37557c6fdc0533b0dc3d759191da3e980ab4df06f"}]}
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

// The registration handles, kept so the next box can move each ball independently.
// `registerSphere` returns the `PullingMeshPair` it registered and there is no by-name
// lookup on a bundle, so the handle is the only way back to an object once it is in the
// scene — hold it at registration or you cannot animate it at all.
prop<MutableList<afield.four.fabricated.StandardRenderer.Bundle.PullingMeshPair>>(balls)

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
    // The handles go into `balls` in row-major order, ix outer and iz inner, which is the
    // order the animation box relies on to recover (ix, iz) from an index.
    val handles = mutableListOf<afield.four.fabricated.StandardRenderer.Bundle.PullingMeshPair>()
    for (ix in -2..2) for (iz in -2..2) {
        handles.add(
            b.registerSphere(name = "ball-" + ix + "-" + iz, radius = 0.3f, slices = 24, stacks = 24)
                .setModel(Matrix4f().translate(ix * 0.85f, 0f, iz * 0.85f))
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
{"id" "rp_chain" "index" 2 "name" "reprojected accumulation" "parents" ["root" "rp_bundle"] "children" ["rp_modes"] "properties" {"children" #{#afield/box "rp_modes"} "parents" #{#afield/box "root" #afield/box "rp_bundle"} "name" "reprojected accumulation" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_chain_code_.kt" "lines" 108 "characters" 6330 "sha256" "62d4d78b861eaabd8fea47f1ff518de8b7f791c99b99422eae2bb89de9f10321"}]}
```

```kotlin [field-property box="rp_chain" name="code" path="rp_chain_code_.kt"]
import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.addPassChain
import afield.stdlib.linalg.Matrix4f
import kotlin.math.sin

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
        // `motionScale` IS SCENE-TUNED AND IT SATURATES SILENTLY. It multiplies
        // `previousUv - currentUv` before the view maps it to red/green about grey, and
        // nothing in the picture tells you it clipped. Measured both ways on this scene:
        // at 8 a slow yaw is a shift of ~0.03 in linear light, under two 8-bit codes and
        // indistinguishable from flat grey; at 60 the dolly below pins every channel to
        // the rail and the view collapses to four flat quadrants whose only remaining
        // information is the SIGN of each component - which reads like a result, not like
        // a clipped instrument. 6 keeps this scene's dolly inside range so the field's
        // actual structure is visible. 20 suits per-pixel motion, whose per-object part is
        // smaller than the dolly's global field and washes out at 6.
        motionScale = 20f
    ),
    printReport = true
)
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// PER-OBJECT motion, not just camera motion, and the distinction is the whole reason this
// box moves the balls rather than only turning the camera.
//
// A pure camera yaw produces a GLOBAL motion field: every pixel moves the same way, so
// mode 1 is a near-uniform tint and mode 2 is a clean corner-to-corner ramp — and both
// look almost exactly the same whether per-object correspondence works or is absent
// entirely. It is the easiest case a reprojection can be handed. Moving each ball on its
// own path instead means the motion field has structure: mode 1 shows the balls against a
// still background, mode 2's UV map breaks at their silhouettes, and an accumulation built
// on a wrong correspondence ghosts visibly on the movers while the background stays sharp.
//
// This also exercises the half of the pipeline the camera can never reach.
// `standardRenderer` binds a per-object `prevModel` from the object's own previous
// transform; a camera-only scene leaves every `prevModel` equal to its `model` forever, so
// that path is never tested. Writing a new model matrix each frame is what advances it.
// The camera SWEEPS, it does not accumulate, and that is a fix rather than a flourish.
// `yaw(d, aroundTarget = false)` rotates about the camera's own position by `d` every frame
// and nothing bounds the total, so a constant `d` turns the camera away from the scene for
// good: at the 0.004 this box used to pass, the ball grid is gone in under seven seconds.
// Driving the yaw from a sine and applying the DELTA keeps every frame's motion a pure
// rotation about the camera position — so `reprojection` stays P*R*inverse(P), which maps a
// ray to a ray whatever its depth, and the assumed NDC depth still costs nothing — while
// the net excursion stays inside +/- YAW_AMPLITUDE and the scene never leaves the frame.
val YAW_AMPLITUDE = 0.10   // radians, about 6 degrees either side — a garnish now, not the point
val DOLLY_AMPLITUDE = 1.7  // world units along the view axis: 4.2 out to 2.5 and back
var yawApplied = 0.0
var t = 0.0
frames {
    while (true) {
        t += 1.0 / 60.0
        for (i in balls.indices) {
            val ix = i / 5 - 2
            val iz = i % 5 - 2
            // Vertical bob, phase-offset per column and row so neighbours separate rather
            // than moving as one slab — a slab would be a global field again.
            val bob = sin(t * 1.7 + ix * 0.9 + iz * 0.6) * 0.45
            // A gentle orbit in x as well, so the motion is not purely axis-aligned: a
            // sign error in one axis of the reprojection hides in axis-aligned motion.
            val drift = sin(t * 0.8 + iz * 1.3) * 0.18
            balls[i].setModel(
                Matrix4f().translate(ix * 0.85f + drift.toFloat(), bob.toFloat(), iz * 0.85f)
            )
        }
        // A DOLLY, not just a yaw, and this is the motion that makes the debug views mean
        // something.
        //
        // A yaw about the camera's own position produces a motion field that is IDENTICAL at
        // every depth — that is exactly why `reprojection` can be P*R*inverse(P) and why the
        // pass's single assumed `ndcDepth` costs nothing under it. The cost of that freedom
        // is that the field has no structure: every pixel moves the same way, so mode 1 is a
        // flat tint and nothing in the picture can disagree with anything else.
        //
        // Translating the camera along its view axis makes the field depth-DEPENDENT: a near
        // ball sweeps further across the screen than a far one for the same metre of dolly.
        // So the motion field acquires structure, the overlapping silhouettes of near and far
        // balls show a genuine discontinuity across the edge, and the assumed-depth
        // approximation is finally being asked a question it can get wrong. If mode 1 stays
        // flat under a dolly, the reprojection really is stuck at the identity.
        val dolly = sin(t * 0.6) * DOLLY_AMPLITUDE
        bundle.scene.camera.at.set(0f, 1.4f, (-4.2 + dolly).toFloat())
        // Keep a little yaw as well, bounded, so mode 3 still has a trailing edge to show.
        val yawWanted = sin(t * 0.45) * YAW_AMPLITUDE
        bundle.scene.camera.yaw(yawWanted - yawApplied, aroundTarget = false)
        yawApplied = yawWanted
        frame()
    }
}
```

## 4. the four debug modes

<!-- field-box id="rp_modes" index="3" -->

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
{"id" "rp_modes" "index" 3 "name" "the four debug modes" "parents" ["root" "rp_chain"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rp_chain"} "name" "the four debug modes" "_tombstone" false "frame" #afield/rect [60.0 3040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rp_modes_code_.kt" "lines" 21 "characters" 1149 "sha256" "7ccddec39799eeb79be3ea98a23c57d2a880421b704145c05c939a4560080850"}]}
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

