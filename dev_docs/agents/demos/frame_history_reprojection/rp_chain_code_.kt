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
        // actual radial structure is visible.
        motionScale = 6f
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
