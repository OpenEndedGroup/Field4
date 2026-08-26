@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.four.targets.OpenXRHmdTarget.ControllerState
import afield.four.targets.OpenXRHmdTarget.HandJoint
import afield.four.targets.OpenXRHmdTarget.HandTrackingState
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.intersectRayPlane
import kotlin.math.sqrt

/**
 * A person pointing — mouse, XR controller or pinched hand — behind one interface, and the
 * hover / press / drag / release machine that turns a stream of samples into an interaction.
 *
 * # A pointer is three things
 *
 * A ray, a select value in `0..1`, and whether it exists at all ([PointerState]). That is enough to
 * unify every pointing device Field can see, which is the whole reason the interface is this thin:
 *
 *  - **A mouse** has a ray only because a camera can be inverted ([mousePointer]), a select value
 *    that is `0` or `1`, and is always active while the window has a cursor.
 *  - **An XR controller** has a pose and a trigger, and reports `active` when the runtime is tracking
 *    it ([controllerPointer]).
 *  - **A pinched hand** has 26 joints and a geometric pinch strength, and is the interesting case,
 *    because its select value is genuinely continuous and genuinely noisy ([pinchPointer]).
 *
 * The `handState` that landed in [afield.four.targets.OpenXRHmdTarget] had **no consumer** — every
 * mention of it in the tree was inside the file that writes it. [pinchPointer] is the first.
 *
 * # Why the machine has hysteresis, in two places
 *
 * Feed raw nearest-hit results to a UI and it flickers. Two specific, different flickers, and
 * [PointerInteraction] treats them differently because they *are* different:
 *
 *  1. **The part flickers** when the ray lands near an edge two parts share, or grazes a silhouette.
 *    A pixel's worth of hand tremor alternates the answer every frame, and a label that alternates
 *    every frame is unreadable. The fix is a **dwell** guarding every transition *away* from a held
 *    hover — see [PointerInteraction.switchSamples] and the rule stated on `resolveHover`. Acquiring
 *    a hover is never delayed, because a lag between pointing at something and it lighting up reads
 *    as the pointer being broken.
 *  2. **The click flickers** when a continuous select value sits on the threshold — which a
 *    geometric `pinchStrength` derived from a 40 mm range of finger separation does constantly. The
 *    fix is a **Schmitt trigger**: press at [PointerInteraction.pressThreshold], release at the lower
 *    [PointerInteraction.releaseThreshold]. A value wobbling between them holds its state.
 *
 * And once pressed, **the drag belongs to what it grabbed**: hover is frozen, and
 * [PointerInteraction.dragOffset] reports where the ray now crosses the plane through the original
 * contact point, so letting the pointer wander off the object does not drop the drag.
 *
 * # Typical use
 *
 * ```kotlin
 * val scene = pickScene { add("model", mesh, parts = builder.groups()) }
 * val pointer = mousePointer(camera = { view.rayCamera(mesh) }, cursor = { window.cursor },
 *                            selecting = { window.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT) })
 * val interaction = PointerInteraction(scene)
 * forever {
 *     interaction.update(pointer.sample())
 *     if (interaction.justPressed) println("grabbed ${interaction.grabbed?.partName}")
 * }
 * ```
 */

// ==============================================================================================
// The interface
// ==============================================================================================

/**
 * One reading of a pointing device.
 *
 * [ray] is `null` exactly when there is nothing to point with — no cursor, an untracked controller, a
 * hand out of view. That is a different condition from `select == 0.0`, which means "present, not
 * selecting", and [PointerInteraction] handles them differently: a released select is a click, a
 * vanished pointer is a cancellation.
 */
class PointerState(
    /** Where the pointer is aiming, or `null` when it is not there. */
    val ray: Ray?,
    /**
     * How hard the person is selecting, `0..1`. A mouse button is `0` or `1`; a trigger and a pinch
     * are continuous, which is why [PointerInteraction] needs two thresholds rather than one.
     */
    val select: Double = 0.0,
) {
    /** True when there is a ray to use. */
    val active: Boolean get() = ray != null

    override fun toString(): String =
        if (ray == null) "PointerState(inactive)" else "PointerState($ray, select=%.3f)".format(select)

    companion object {
        /** Nothing there. */
        val inactive = PointerState(null, 0.0)
    }
}

/** Something a person points with. Sampled, not subscribed to — see [PointerInteraction]. */
interface Pointer {
    /** For logs and for telling two hands apart. */
    val name: String

    /** Read the device now. */
    fun sample(): PointerState
}

/** A [Pointer] from a lambda. What every factory below builds. */
class LambdaPointer(override val name: String, private val read: () -> PointerState) : Pointer {
    override fun sample(): PointerState = read()
    override fun toString(): String = "Pointer('$name')"
}

// ==============================================================================================
// The three sources
// ==============================================================================================

/**
 * A mouse, made into a spatial pointer by inverting a camera.
 *
 * Deliberately built from three lambdas rather than from a `Renderer`: it is then usable from a test,
 * from a headless capture and from a live window without three separate implementations, and the
 * camera can be rebuilt per frame (which it must be, if the window resizes).
 *
 * @param camera the camera to unproject through, re-read on every sample. Return `null` to make the
 *   pointer inactive — which is what a scene with no camera yet should do.
 * @param cursor the cursor position in pixels from the frame's **top-left**, which is what
 *   `Window.cursor` reports.
 * @param selecting whether the select button is down.
 * @param clampToFrame when true (the default) a cursor outside the frame makes the pointer inactive
 *   rather than producing a ray through a pixel that does not exist. Off is occasionally what you
 *   want — a drag that leaves the window and comes back.
 */
fun mousePointer(
    name: String = "mouse",
    camera: () -> RayCamera?,
    cursor: () -> Vec2,
    selecting: () -> Boolean,
    clampToFrame: Boolean = true,
): Pointer = LambdaPointer(name) {
    val c = camera() ?: return@LambdaPointer PointerState.inactive
    val at = cursor()
    val x = at.x.toDouble()
    val y = at.y.toDouble()
    if (clampToFrame && (x < 0 || y < 0 || x >= c.pixelWidth || y >= c.pixelHeight)) {
        PointerState.inactive
    } else {
        PointerState(c.rayThrough(x, y), if (selecting()) 1.0 else 0.0)
    }
}

/**
 * An XR controller's aim, and its trigger.
 *
 * The forward convention is **+Z rotated by the controller's quaternion**, matching
 * [afield.four.imgui.ImGuiPanel.controllerRay] — the only place in the tree that had already made
 * this decision, for Field's mock controller and for OpenXR alike. Deliberately the same call rather
 * than a second opinion: two conventions for "which way is a controller pointing" is exactly the bug
 * that takes a day to find.
 *
 * @param state read on every sample; `null` or `!active || !poseValid` makes the pointer inactive.
 * @param select what counts as selecting. Defaults to the trigger's analogue value, falling back to
 *   the digital `selectPressed` when the runtime only binds that.
 */
fun controllerPointer(
    name: String = "controller",
    state: () -> ControllerState?,
    localForward: Vector3f = Vector3f(0f, 0f, 1f),
    select: (ControllerState) -> Double = { s ->
        maxOf(s.triggerValue.toDouble(), if (s.selectPressed || s.triggerPressed) 1.0 else 0.0)
    },
): Pointer = LambdaPointer(name) {
    val s = state() ?: return@LambdaPointer PointerState.inactive
    if (!s.active || !s.poseValid) return@LambdaPointer PointerState.inactive
    val dir = Vector3f(localForward)
    s.rotation.transform(dir)
    if (dir.lengthSquared() < 1e-12f) return@LambdaPointer PointerState.inactive
    PointerState(
        Ray(
            s.position.x.toDouble(), s.position.y.toDouble(), s.position.z.toDouble(),
            dir.x.toDouble(), dir.y.toDouble(), dir.z.toDouble(),
        ),
        select(s).coerceIn(0.0, 1.0),
    )
}

/**
 * A bare hand: aim by moving it, select by pinching. The first consumer of
 * [afield.four.targets.OpenXRHmdTarget.handState].
 *
 * ## How the ray is derived, and why it is derived this way
 *
 * From **joint positions only** — the ray runs from the wrist out through the pinch point (the
 * midpoint of the thumb tip and the index tip), and starts at the pinch point.
 *
 * Two alternatives were available and are worse:
 *
 *  - **Rotate a local forward by a joint's orientation quaternion**, the way [controllerPointer]
 *    does. That needs to know which axis of an `XR_EXT_hand_tracking` joint points down the finger,
 *    and getting it wrong produces a ray that is confidently perpendicular to where the person is
 *    pointing. Positions have no such convention to get wrong: a difference of two positions is a
 *    direction in the same space, and that is all this needs.
 *  - **Point the index finger** — `INDEX_TIP - INDEX_PROXIMAL`. Reads beautifully and is unusable,
 *    because the finger curls toward the thumb *in order to* pinch, so the aim swings away exactly
 *    as the person commits to the click. Wrist-through-pinch barely moves when the pinch closes,
 *    which is what makes "aim, then click without re-aiming" possible at all.
 *
 * The version a headset's own UI uses — a ray from the **eye** through the pinch point — is better
 * still, and is not available here: it needs a head pose, and taking one would make this function
 * require a live XR session. [mousePointer] and [controllerPointer] both stay poll-a-lambda; so does
 * this. Compose it yourself if you have the head pose:
 * `Ray.through(headPosition, pinchPoint)`.
 *
 * @param state read on every sample; `null` or `!isActive` makes the pointer inactive, as does a
 *   frame in which the wrist or either fingertip lost tracking.
 */
fun pinchPointer(
    name: String = "pinch",
    state: () -> HandTrackingState?,
): Pointer = LambdaPointer(name) {
    val s = state() ?: return@LambdaPointer PointerState.inactive
    if (!s.isActive) return@LambdaPointer PointerState.inactive
    val wrist = s[HandJoint.WRIST]
    val thumb = s[HandJoint.THUMB_TIP]
    val index = s[HandJoint.INDEX_TIP]
    if (!wrist.positionValid || !thumb.positionValid || !index.positionValid) {
        return@LambdaPointer PointerState.inactive
    }
    val px = (thumb.position.x + index.position.x) * 0.5
    val py = (thumb.position.y + index.position.y) * 0.5
    val pz = (thumb.position.z + index.position.z) * 0.5
    val dx = px - wrist.position.x
    val dy = py - wrist.position.y
    val dz = pz - wrist.position.z
    if (sqrt(dx * dx + dy * dy + dz * dz) < 1e-4) {
        // The pinch point sitting on the wrist is not a hand; it is a tracking glitch.
        return@LambdaPointer PointerState.inactive
    }
    PointerState(
        Ray(px, py, pz, dx, dy, dz),
        s.pinchStrength.toDouble().coerceIn(0.0, 1.0),
    )
}

/** A pointer that always reports [fixed] — for tests, and for driving a capture from a script. */
fun fixedPointer(name: String = "fixed", fixed: () -> PointerState): Pointer = LambdaPointer(name, fixed)

// ==============================================================================================
// Hover / press / drag / release
// ==============================================================================================

/** What a [PointerInteraction] is doing. */
enum class PointerPhase {
    /** Nothing under the pointer, or no pointer. */
    IDLE,

    /** Something under the pointer, not selected. */
    HOVER,

    /** Selected, and the pointer has not moved off the contact point yet. */
    PRESSED,

    /** Selected and moving. [PointerInteraction.dragOffset] is the displacement. */
    DRAGGING,
}

/**
 * A stream of [PointerState]s turned into an interaction: what is hovered, what is grabbed, and what
 * a drag has moved by.
 *
 * Stateful and single-threaded — one per pointer. Call [update] once per frame with that pointer's
 * sample; everything else is a read.
 *
 * See this file's documentation for why there are two independent kinds of hysteresis and what each
 * one is for.
 */
class PointerInteraction(
    /** What to pick against. Swap it freely between calls; the machine holds hits, not indices. */
    var scene: PickScene,
    /**
     * Select value at which a press begins. Above [releaseThreshold] by construction.
     *
     * `0.6` rather than `0.5` because a geometric pinch strength spends real time near the middle of
     * its range while the fingers are still closing, and a press that fires there fires early.
     */
    val pressThreshold: Double = 0.6,
    /**
     * Select value at which a press ends. The lower half of the Schmitt trigger: a value wobbling
     * between the two thresholds holds whichever state it is in.
     */
    val releaseThreshold: Double = 0.35,
    /**
     * How many consecutive samples a *different* part must be the nearest hit before the hover moves
     * to it.
     *
     * `3` at 90 Hz is 33 ms — below the threshold at which a person notices a label lagging, and far
     * above the single-frame alternation that a ray sitting on a shared edge produces. `1` disables
     * the dwell.
     */
    val switchSamples: Int = 3,
    /** Ignore anything further away than this. */
    val maxDistance: Double = Double.MAX_VALUE,
    /** Skip triangles facing away, as a renderer would. */
    val cullBackFaces: Boolean = false,
) {
    init {
        require(pressThreshold > releaseThreshold) {
            "the press threshold ($pressThreshold) must be above the release threshold " +
                "($releaseThreshold), or the trigger has no hysteresis and a select value sitting on " +
                "the line will chatter"
        }
        require(switchSamples >= 1) { "switchSamples must be at least 1, got $switchSamples" }
    }

    /** What the machine is doing. */
    var phase: PointerPhase = PointerPhase.IDLE
        private set

    /**
     * What the pointer is over. Frozen to [grabbed] while [PointerPhase.PRESSED] or
     * [PointerPhase.DRAGGING] — a drag belongs to what it grabbed.
     */
    var hover: PickHit? = null
        private set

    /** What the press landed on, or `null` when nothing is pressed. */
    var grabbed: PickHit? = null
        private set

    /** The last sample's ray, for whoever wants to draw it. */
    var ray: Ray? = null
        private set

    /** True for exactly the one [update] in which the press began. */
    var justPressed: Boolean = false
        private set

    /**
     * True for exactly the one [update] in which the press ended *with the pointer still there* —
     * i.e. a click. A pointer that vanished mid-press sets [justCancelled] instead.
     */
    var justReleased: Boolean = false
        private set

    /** True for exactly the one [update] in which a press was abandoned because the pointer went away. */
    var justCancelled: Boolean = false
        private set

    /** How many consecutive samples the current candidate has been winning. */
    private var candidateRuns: Int = 0
    private var candidateKey: Pair<String, Int>? = null

    /**
     * How far the drag has moved the grabbed point, in world units, or `null` when nothing is
     * grabbed.
     *
     * Measured on the **plane through the original contact point perpendicular to the grab-time ray**
     * — the usual and the only stable choice: it is defined for every subsequent ray direction, it is
     * zero at the moment of the grab, and it does not depend on the pointer still being over the
     * object, which is the whole point of a drag.
     */
    var dragOffset: Vec3? = null
        private set

    private var grabPlanePoint: Vec3? = null
    private var grabPlaneNormal: Vec3? = null

    /** Straight-line length of [dragOffset], or 0. */
    val dragDistance: Double
        get() = dragOffset?.let { sqrt((it.x * it.x + it.y * it.y + it.z * it.z).toDouble()) } ?: 0.0

    /**
     * Feed one sample. Returns the new [phase].
     *
     * @param moveEpsilon how far a drag must travel before [PointerPhase.PRESSED] becomes
     *   [PointerPhase.DRAGGING], in world units. Below it a press is a click and not a drag, which is
     *   what makes a click on a draggable thing possible.
     */
    fun update(state: PointerState, moveEpsilon: Double = 1e-4): PointerPhase {
        justPressed = false
        justReleased = false
        justCancelled = false
        ray = state.ray

        val holding = phase == PointerPhase.PRESSED || phase == PointerPhase.DRAGGING

        val currentRay = state.ray
        if (currentRay == null) {
            if (holding) justCancelled = true
            phase = PointerPhase.IDLE
            hover = null
            grabbed = null
            dragOffset = null
            grabPlanePoint = null
            grabPlaneNormal = null
            candidateRuns = 0
            candidateKey = null
            return phase
        }

        if (holding) {
            // Frozen: the drag belongs to what it grabbed. Only the select value and the drag offset
            // move, and the hover stays where the press put it.
            dragOffset = offsetOnGrabPlane(currentRay)
            if (state.select <= releaseThreshold) {
                justReleased = true
                grabbed = null
                dragOffset = null
                grabPlanePoint = null
                grabPlaneNormal = null
                // Re-resolve immediately, so a release does not spend a frame in IDLE while the
                // pointer is plainly still over something.
                hover = resolveHover(currentRay)
                phase = if (hover != null) PointerPhase.HOVER else PointerPhase.IDLE
            } else {
                phase = if (dragDistance > moveEpsilon) PointerPhase.DRAGGING else PointerPhase.PRESSED
            }
            return phase
        }

        hover = resolveHover(currentRay)
        val target = hover
        if (target != null && state.select >= pressThreshold) {
            grabbed = target
            grabPlanePoint = Vec3(target.point)
            grabPlaneNormal = Vec3(
                (-currentRay.dirX).toFloat(), (-currentRay.dirY).toFloat(), (-currentRay.dirZ).toFloat(),
            )
            dragOffset = Vec3(0f, 0f, 0f)
            justPressed = true
            phase = PointerPhase.PRESSED
        } else {
            phase = if (target != null) PointerPhase.HOVER else PointerPhase.IDLE
        }
        return phase
    }

    /**
     * The nearest hit, subject to the dwell.
     *
     * **The rule, in one sentence: the dwell guards every transition *away* from a held hover, and
     * never guards acquiring one.** Both halves are deliberate, and the asymmetry is the whole design:
     *
     *  - **Acquiring** (nothing → a part) is immediate, because a lag between pointing at a thing and
     *    the thing lighting up is felt as the pointer being broken. There is also nothing to arbitrate
     *    — the dwell exists to settle a *competition* between two claims, and an empty hover is not a
     *    claim.
     *  - **Switching** (part A → part B) waits, which is what stops a ray sitting on an edge two parts
     *    share from alternating: alternation never accumulates a run, so it never wins, and the hover
     *    settles on whichever part it saw first.
     *  - **Releasing** (a part → nothing) waits too, which is the case it would be easy to miss:
     *    a ray grazing a silhouette alternates between hitting and missing, and a hover that dropped
     *    instantly would flicker exactly as badly there as it would on a shared edge.
     *
     * The acknowledged cost: a single stray hit lights a label for [switchSamples] samples — 33 ms at
     * 90 Hz — before releasing. That is the price of zero acquisition lag and it is the right way
     * round.
     */
    private fun resolveHover(currentRay: Ray): PickHit? {
        val candidate = scene.pick(currentRay, maxDistance, cullBackFaces)
        val held = hover
        val candidateId = candidate?.let { it.objectName to it.part }
        val heldId = held?.let { it.objectName to it.part }

        if (heldId == null) {
            // Nothing held: acquire at once. Also the switchSamples == 1 path for everything.
            candidateRuns = 0
            candidateKey = null
            return candidate
        }

        if (candidateId == heldId) {
            // Same part: refresh the geometry of the hit — the contact point moved even though the
            // part did not — and reset the run so a later change starts counting from zero.
            candidateRuns = 0
            candidateKey = null
            return candidate
        }

        if (candidateKey == candidateId) candidateRuns++ else { candidateKey = candidateId; candidateRuns = 1 }
        if (candidateRuns >= switchSamples) {
            candidateRuns = 0
            candidateKey = null
            return candidate
        }
        return held
    }

    /** Where [currentRay] crosses the grab plane, minus where the grab started. */
    private fun offsetOnGrabPlane(currentRay: Ray): Vec3? {
        val point = grabPlanePoint ?: return null
        val normal = grabPlaneNormal ?: return null
        val at = Vec3()
        val ok = intersectRayPlane(currentRay.origin, currentRay.direction, point, normal, at)
        if (!ok) return dragOffset // ray now parallel to the plane; hold the last offset rather than jump
        return Vec3(at.x - point.x, at.y - point.y, at.z - point.z)
    }

    override fun toString(): String =
        "PointerInteraction($phase" +
            (hover?.let { ", over ${it.objectName}/${it.partName}" } ?: "") +
            (if (phase == PointerPhase.DRAGGING) ", dragged %.4f".format(dragDistance) else "") + ")"
}
