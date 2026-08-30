package drowning

import afield.four.Renderer
import afield.four.buffers.BufferWriteable
import afield.four.buffers.FrameBufferGroup
import afield.four.targets.StereoCamera
import afield.four.util.Camera
import afield.four.util.Options
import afield.stdlib.Vec4
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.vec

/**
 * The host side of `dust`: the buffer writes, the knobs, the stereo decision, and the history cursor.
 *
 * Nothing here creates a Vulkan object. That is the same split [PsubGpu] makes and for the same reason:
 * a box's `defaultBuffer { }` and `RenderToTexture { }` calls are where the access pattern and the
 * resolution belong, because only the box knows what it is doing with them. This file knows the
 * *layout* and writes it, and knows the *maths* and computes it.
 *
 * ```kotlin
 * // in the field box: allocate, then fill
 * var stateA = defaultBuffer { size = DustLayout.stateVec4s(n).vec4s }.buffer
 * var stateB = defaultBuffer { size = DustLayout.stateVec4s(n).vec4s }.buffer
 * var links  = defaultBuffer { size = DustLayout.linksVec4s(n).vec4s; writeOnce() }.buffer
 * field.writeStateInto(stateA); field.writeStateInto(stateB)
 * field.writeLinksInto(links, onn)
 * ```
 *
 * State is host-visible rather than `writeOnce()` on purpose — it is written once here but the compute
 * shader writes it every frame, so staging it would be a lie about the access pattern. Links *are*
 * `writeOnce()`: written here, read by every invocation of every frame, never touched again.
 */

// ---- the writes --------------------------------------------------------------------------------

/**
 * Fill a state buffer with the field at rest.
 *
 * Call it on **both** halves of the ping-pong. The force shader reads one and writes the other from the
 * first frame, so an unwritten second buffer means the first frame integrates from garbage — which on a
 * host-visible allocation is usually zeroes and so looks like every particle collapsing to the origin
 * for one frame, then recovering. A confusing thing to debug for what it is.
 */
fun DustField.writeStateInto(into: BufferWriteable) {
    into.mapAndWrite_bytes { writeState(this) }
    into.clean(false, null)
}

/** Fill the links buffer: rest positions and the network. Once, at startup. */
fun DustField.writeLinksInto(into: BufferWriteable, links: DustLinks, flags: IntArray? = null) {
    into.mapAndWrite_bytes { writeLinks(this, links, flags) }
    // Flush the staged upload here, on the thread that wrote it. See the long note on
    // `PsubGpu.flushUpload`: leaving a `writeOnce()` buffer's pending upload for the renderer to
    // discover means two modules race to flush it and one gets a torn HashSet.
    into.clean(false, null)
}

// ---- the four buffers, and which of the two is current -----------------------------------------

/**
 * The buffers a running dust field needs, and the rule that decides which half of the ping-pong is
 * which this frame.
 *
 * The box allocates; this holds them and answers one question. That question is the whole reason the
 * class exists, because the obvious way to answer it is wrong: a `swap()` called from some box's `loop`
 * makes the answer depend on **which box's loop ran first**, and the loops of eleven boxes have no
 * defined order relative to each other or to the frame. A field that is correct until you re-run a box
 * in a different order is worse than one that is broken.
 *
 * So the parity comes from [frame] — `renderer.frameCounter`, which is incremented once per completed
 * frame and is therefore *constant* for the whole of one frame's recording. Every binding lambda in
 * every box evaluates it independently and they cannot disagree.
 *
 * ```kotlin
 * forces.prevState = { _dust.read }      // last frame's positions: what the force shader reads
 * forces.state     = { _dust.write }     // this frame's: what it writes
 * points.state     = { _dust.write }     // the draws want the fresh one
 * ```
 */
class DustBuffers(
    /** Particles. */
    val count: Int,
    /** Frames of history per particle. */
    val historyCapacity: Int,
    /** One half of the state ping-pong. */
    val stateA: FrameBufferGroup,
    /** The other half. Both must be written at startup — see [writeStateInto]. */
    val stateB: FrameBufferGroup,
    /** Rest positions and the network. Never written after startup. */
    val links: FrameBufferGroup,
    /** Header then trail samples. */
    val history: FrameBufferGroup,
    /** `renderer.frameCounter`, or anything else that changes exactly once per frame. */
    val frame: () -> Long
) {
    /** The buffer holding the positions this frame starts from — the force shader's `prevState`. */
    val read: FrameBufferGroup get() = if (frame() % 2L == 0L) stateA else stateB

    /** The buffer this frame's positions go into — the force shader's `state`, and what the draws read. */
    val write: FrameBufferGroup get() = if (frame() % 2L == 0L) stateB else stateA

    /** Where this frame's history goes. Derived from [frame], so nothing has to advance it. */
    val cursor = DustHistoryCursor(historyCapacity, frame)

    private val startNanos = System.nanoTime()

    /**
     * Seconds since this field was built — one clock, shared, so `pc.time` means the same thing in the
     * force programs and in the draws.
     *
     * Wall clock rather than `frame * dt`: the force programs' oscillators should keep musical time even
     * when the frame rate does not, while the *integrator* uses the fixed [DustLook.dt] because a spring
     * network's stability depends on the step being constant. Those are different clocks on purpose.
     */
    val seconds: Float get() = ((System.nanoTime() - startNanos) / 1e9).toFloat()

    /** Workgroups of 64 to cover every particle — `forces.x = dust.workgroups`. */
    val workgroups: Int get() = (count + 63) / 64

    /** One line for a box's output channel. */
    fun describe(): String =
        "dust buffers: $count particles, $historyCapacity frames of history, " +
            "%.1f MB on the device".format(DustLayout.deviceBytes(count, historyCapacity) / 1e6)
}

// ---- the history cursor ------------------------------------------------------------------------

/**
 * Where this frame's history goes: a cursor derived from the frame counter, not advanced by anyone.
 *
 * The host owns the cursor rather than the GPU, which is what makes the history shader race-free —
 * every invocation has to agree on the slot, and a slot read out of a header that one invocation is
 * also advancing does not give that. See [DustShaders.HISTORY_COMPUTE].
 *
 * But it is *derived* rather than stepped, for the same reason [DustBuffers] derives its ping-pong
 * parity: an `advance()` called from some box's `loop` would make the answer depend on which box's loop
 * ran first, and the history shader and the trail draw both read this within one frame. `frameCounter`
 * is constant for the whole of one frame's recording, so both read the same cursor whatever order they
 * are evaluated in — and there is nothing to forget to call.
 *
 * ```kotlin
 * history.slot    = { _dust.cursor.slot.toUInt() }
 * history.written = { _dust.cursor.written.toUInt() }
 * trails.head     = { _dust.cursor.head.toUInt() }
 * ```
 */
class DustHistoryCursor(
    val capacity: Int,
    /** `renderer.frameCounter`, or anything that changes exactly once per frame. */
    val frame: () -> Long
) {
    init { require(capacity > 0) { "history capacity must be positive" } }

    /**
     * The frame the history started on. Everything below counts from here.
     *
     * It has to exist because `renderer.frameCounter` is the *renderer's* clock and the renderer has
     * usually been running for a while by the time the history box launches. Counting from zero made
     * [written] saturate at [capacity] on the first frame, so the trail draw believed a ring full of
     * samples that had never been written — and drew a ring of lines through the origin. Which reads,
     * on screen, like the simulation exploding.
     */
    var origin: Long = frame()
        private set

    /** Frames since [origin], never negative. */
    val elapsed: Long get() = (frame() - origin).coerceAtLeast(0L)

    /** Start counting again from now: the trails vanish and grow back in over [capacity] frames. */
    fun rebase() { origin = frame() }

    /** The slot this frame writes. */
    val slot: Int get() = slotFor(elapsed, capacity)

    /**
     * Frames written so far, **including this one**, saturating at [capacity].
     *
     * Including this one because the history dispatch runs in the compute pass and the trail draw in
     * the graphics pass of the same frame, so by the time anything reads the buffer this frame's sample
     * is in it. Below [capacity] the ring is only partly filled and the trail draw clips the rest.
     */
    val written: Int get() = writtenFor(elapsed, capacity)

    /** The slot *after* [slot] — what the trail draw wants, and what the shader records in the header. */
    val head: Int get() = (slot + 1) % capacity

    /** What the header should say if the shader has been keeping up — for a readback comparison. */
    fun expectedHeader(particles: Int) = DustHistoryHeader(
        capacity = capacity, head = head, count = written, particles = particles,
        frame = frame().toInt(), stride = 1,
        magic = DustLayout.HISTORY_MAGIC, version = DustLayout.HISTORY_VERSION
    )

    override fun toString(): String =
        "history cursor: frame ${frame()} (+${elapsed} since $origin), slot $slot, head $head, " +
            "$written/$capacity written"

    companion object {
        /** The slot frame [frame] writes. Pure arithmetic, so it can be checked without a renderer. */
        fun slotFor(frame: Long, capacity: Int): Int =
            ((frame % capacity) + capacity).toInt() % capacity

        /** Frames written by the end of frame [frame], including it, saturating at [capacity]. */
        fun writtenFor(frame: Long, capacity: Int): Int =
            minOf(frame + 1, capacity.toLong()).coerceAtLeast(0L).toInt()
    }
}

// ---- stereo ------------------------------------------------------------------------------------

/**
 * How the two eyes are drawn. Chosen once, by [choose], and then invisible to every shader — see the
 * `{{ dust.stereo }}` note in [DustShaders].
 */
enum class DustStereoMode {
    /**
     * `VK_KHR_multiview`: one two-layer `VK_IMAGE_VIEW_TYPE_2D_ARRAY` colour target, `viewMask = 0b11`,
     * one draw call covering both eyes, and `gl_ViewIndex` selecting the per-eye matrix.
     *
     * The reason to want this rather than two passes is not only the halved draw count: the eyes are
     * separate array *layers* rather than two halves of one image, so a full-screen blur cannot bleed
     * across the seam. There is no seam.
     */
    MULTIVIEW,

    /**
     * One layer, one eye, `gl_ViewIndex` replaced by a literal 0.
     *
     * The colour target is still an array view (`forceColorArrayView`), so the shaders are unchanged
     * and what reaches the screen is the left eye of the same pipeline.
     */
    MONO;

    /** Colour layers the main target needs. */
    val layers: Int get() = if (this == MULTIVIEW) 2 else 1

    /** `RTTParameters.multiviewViewMask`. Zero disables multiview. */
    val viewMask: Int get() = if (this == MULTIVIEW) 0b11 else 0

    /** The GLSL for the `{{ dust.stereo }}` template slot. */
    val shaderSlot: String
        get() = if (this == MULTIVIEW) DustShaders.STEREO_MULTIVIEW else DustShaders.STEREO_MONO

    companion object {
        /**
         * [MULTIVIEW] when the device advertises `VK_KHR_multiview`, [MONO] otherwise.
         *
         * Worth knowing where this can bite: `MultiviewSideBySideStereoTarget` in `afield.four.targets`
         * refuses to construct on macOS with "not reliable for Field's current path", but that guard is
         * on *that class*, not on `RenderToTexture`, which only asks `device.supportsMultiview`. On this
         * machine MoltenVK does advertise the extension (`maxMultiviewViewCount = 32`), so the multiview
         * path is what a Mac takes here. If it misbehaves, pass [MONO] explicitly rather than editing
         * shaders — that is what the fallback is for.
         */
        fun choose(renderer: Renderer, prefer: DustStereoMode = MULTIVIEW): DustStereoMode =
            if (prefer == MULTIVIEW && renderer.device.supportsMultiview) MULTIVIEW else MONO

        /** One line on what was chosen and why, for a box's output channel. */
        fun explain(renderer: Renderer, chosen: DustStereoMode): String =
            "stereo: $chosen (device ${if (renderer.device.supportsMultiview) "has" else "lacks"} " +
                "VK_KHR_multiview${if (Options.isMac) ", on macOS" else ""})"
    }
}

/**
 * The two eyes' view-projection matrices, which is the only thing the draws need to know about stereo.
 *
 * Wraps `afield.four.targets.StereoCamera` — Field's own eye construction, so the interocular geometry
 * here is the same one `SideBySideStereoTarget` uses and a scene set up in one looks right in the other.
 * The matrices are pre-multiplied on the host because a multiview draw needs both of them in the push
 * block at once and `mat4 p; mat4 m;` per eye would be 256 bytes of push constant on its own.
 *
 * In [DustStereoMode.MONO] both eyes return the same matrix, so nothing downstream branches.
 */
class DustEyes(
    /** The head. Point a `KeyCamera` or `OrbitControls` at this and both eyes follow. */
    val camera: Camera,
    val mode: DustStereoMode = DustStereoMode.MULTIVIEW,
    /** The eye buffer's width in pixels — what the projection's aspect must come from. */
    val eyeWidth: Int = 1024,
    /** The eye buffer's height in pixels. */
    val eyeHeight: Int = 1024
) {
    /** Field's stereo camera. Set `ipd`, `convergenceDistance` and `asymmetricFrustumOffset` on it. */
    val stereo = StereoCamera(camera)

    init {
        // Point the projection at the EYE, not at the window.
        //
        // `Camera`'s default `SuperProjection(renderer)` installs an `auto` lambda that runs inside
        // *every* `evaluateUniform()` and calls `resize(window.width, window.height)`. So
        // `camera.setAspect(...)` is silently overwritten on the next frame, and a square eye buffer
        // gets the window's 16:9 aspect: a cube of particles comes out compressed in x by 1/1.78 and
        // reads as a tall slab. Nothing errors, and the number is plausible enough to stare past.
        //
        // Replacing `auto` rather than calling `resize` once is the fix, because anything that calls
        // `resize` once is overwritten again on the next evaluation.
        val projection = camera.p
        val matchDistance = projection.autoMatchDistance
        projection.auto = {
            projection.matchDepth = matchDistance()
            projection.resize(eyeWidth, eyeHeight)
        }
        projection.resize(eyeWidth, eyeHeight)
    }

    private val cachedView = listOf(Matrix4f(), Matrix4f())
    private val out = listOf(Matrix4f(), Matrix4f())

    /**
     * `projection * view` for [eye], recomputed on the call.
     *
     * Recomputed and not cached per frame: these go into a `mesh.vpL = { ... }` binding, which Field
     * evaluates once per draw, and a camera that moved between the force dispatch and the draw should
     * be seen by the draw.
     */
    fun viewProjection(eye: Int): Matrix4f {
        val e = if (mode == DustStereoMode.MONO) 0 else eye.coerceIn(0, 1)
        val views = stereo.eyeViewMatrices()
        val projections = stereo.projectionMatrices()
        cachedView[e].set(views[e])
        return out[e].set(projections[e]).mul(cachedView[e])
    }

    /** The left eye's view-projection — `mesh.vpL = { eyes.left() }`. */
    fun left(): Matrix4f = viewProjection(0)

    /** The right eye's — the same matrix as [left] in [DustStereoMode.MONO]. */
    fun right(): Matrix4f = viewProjection(1)

    /**
     * The **projection's own** `(p00, p11)` for both eyes, as `(leftX, leftY, rightX, rightY)`.
     *
     * This exists because `vp[0][0]` is not it, and reaching for `vp[0][0]` is the mistake that shipped
     * in `dust_one`. `vp` is `projection * view`, so its top-left entry is the projection's x scale
     * *mixed with the view rotation* — very nearly right while the camera sits still, and visibly wrong
     * the moment it turns. The shader needs the projection alone for its pixel floor, so it comes
     * across separately.
     *
     * The two eyes share fov and aspect and differ only in `ox` (the asymmetric frustum offset), which
     * does not touch `m00`/`m11`, so both halves are equal today. Both are sent anyway: the day an eye
     * gets its own fov, nothing here has to change.
     */
    fun projScale(): Vec4 {
        val projections = stereo.projectionMatrices()
        val l = projections[0]
        val r = projections[if (mode == DustStereoMode.MONO) 0 else 1]
        return vec(l.m00, l.m11, r.m00, r.m11)
    }

    /**
     * The camera's right in world space, `w` unused — one half of the billboard basis.
     *
     * Shared between the eyes rather than per-eye, and that is correct rather than a shortcut: the two
     * eye cameras are built by translating along `right` and looking at a common focus, so their
     * *orientations* differ only by the tiny toe-in, and a sprite facing the base camera faces both.
     * Using a per-eye basis would make a dot very slightly different shapes in the two eyes, which is
     * exactly the kind of interocular mismatch that reads as eye strain rather than as depth.
     */
    fun camRight(): Vec4 = camera.right.let { vec(it.x, it.y, it.z, 0f) }

    /** The camera's up in world space — the other half. See [camRight]. */
    fun camUp(): Vec4 = camera.up.let { vec(it.x, it.y, it.z, 0f) }

    /** Interocular distance in world units. 0 collapses the stereo without changing any code path. */
    var ipd: Float
        get() = stereo.ipd
        set(v) { stereo.ipd = v }

    /** Where the eyes converge, as a multiple of the camera's distance to its target. */
    var convergence: Float
        get() = stereo.convergenceDistance
        set(v) { stereo.convergenceDistance = v }
}

// ---- the knobs ---------------------------------------------------------------------------------

/**
 * Every push constant the `dust_one` shaders read, in one object.
 *
 * **This is the shape `dust_two` moved away from, and it is kept only so `dust_one` still runs.** New
 * work should use the named-argument `Vec4` factories in `DustParams.kt` instead. The two reasons, both
 * of which only show up once you are actually working:
 *
 * 1. **Adding a knob needed a full teardown.** This class lives in the personal jar, so a new field
 *    meant a Gradle build and a restart of the running sketch — the opposite of live coding, at exactly
 *    the moment you want a new knob.
 * 2. **It hid the parameters from the keyframer.** `KeyframeScope` binds *named values in named
 *    shaders*: `target("dot", points.push)` reads and writes a push member, and `KValue` persists
 *    `vec4` natively. A knob that only exists as a field on a Kotlin class the shader never sees cannot
 *    be snapshotted, interpolated or saved at all.
 *
 * Typesafe, and the wrong trade. Left standing because deleting it would break a working document, and
 * because the comparison is worth having in the source.
 *
 * The defaults are deliberately *calm* — a field that settles, so that the first thing you see is the
 * network rather than an explosion. [wild] is the same knobs turned up.
 */
data class DustLook(

    // ---- forces ----

    /** Scales every `edgeForce` result. Negative pushes edges apart instead of pulling them together. */
    var edgeGain: Float = 1f,

    /** Scales `noiseForce`. This is the one to turn up first; the rest tether will pull it back. */
    var noiseGain: Float = 0.6f,

    /** Scales `restForce`. Zero lets the field float free — see [DustOrthantSettings.mutualOnly]. */
    var restGain: Float = 0.8f,

    /**
     * Velocity retained per step, `0..1`. Verlet's damping term, so 1 is frictionless and 0.9 is thick
     * air. Above 1 the integrator gains energy every step and the field leaves the screen; the
     * integrator does not stop you, [maxStep] does.
     */
    var damping: Float = 0.94f,

    /** Seconds per step. Fixed rather than measured, because a spring network's stability depends on it. */
    var dt: Float = 1f / 60f,

    /**
     * Clamp on how far a particle may move in one step, in world units. Zero disables it.
     *
     * The safety net that makes the force programs *safe to live-edit*: a program with a sign error or a
     * divide by something small produces a huge force, and without this the field is off the screen
     * before you have finished typing. With it, the worst case is a field that looks like static.
     */
    var maxStep: Float = 0.05f,

    /** Clamp on the accumulated force before `1/mass`. Zero disables it. Same reason as [maxStep]. */
    var maxForce: Float = 200f,

    /** Honour only the first N of the six neighbour slots. Lower is a slacker, stringier network. */
    var edgeSlots: Int = DustLayout.EDGES,

    /** Freeze the integrator. The programs still run, so a force can still be watched through `aux`. */
    var paused: Boolean = false,

    /** Constant acceleration. */
    var gravityX: Float = 0f,
    var gravityY: Float = 0f,
    var gravityZ: Float = 0f,

    /** Half-extent of the containing box. `boundsElasticity` 0 turns it off entirely. */
    var boundsX: Float = 1.6f,
    var boundsY: Float = 1.6f,
    var boundsZ: Float = 1.6f,

    /**
     * How much of the incoming speed a wall returns, `0..1`. Zero *is* off — the shader tests the whole
     * field, so there is no separate enable to forget.
     */
    var boundsElasticity: Float = 0.35f,

    /** Centre of that box. */
    var centreX: Float = 0f,
    var centreY: Float = 0f,
    var centreZ: Float = 0f,

    /** Three free `vec4`s for the force programs, reachable as `?pc.knobA` and so on. */
    var knobA: Vec4 = vec(1, 1, 1, 1),
    var knobB: Vec4 = vec(0, 0, 0, 0),
    var knobC: Vec4 = vec(0, 0, 0, 0),

    // ---- the debug draws ----

    /** Dot radius in world units. */
    var dotSize: Float = 0.006f,

    /** Floor on the on-screen dot radius, in pixels, so distant particles stay visible. */
    var dotMinPixels: Float = 0.8f,

    /** `1 / the speed that saturates the colour ramp`. A field's mean step is a good scale. */
    var speedGain: Float = 60f,

    /** `1 / the displacement-from-rest that saturates the diverging ramp`. */
    var strainGain: Float = 4f,

    /** Exponential distance fade toward black, per world unit of clip-space w. Zero disables it. */
    var fade: Float = 0.04f,

    /** 0 flat, 1 by speed, 2 by displacement from rest, 3 by degree, 4 by the per-particle hash. */
    var colorMode: Int = 1,

    /** Colour and alpha of the point draw. */
    var pointTint: Vec4 = vec(0.86, 0.84, 0.79, 0.85),

    /** Colour and alpha of the edge draw. Low alpha on purpose: there are six of these per particle. */
    var edgeTint: Vec4 = vec(0.40, 0.52, 0.70, 0.16),

    /** Colour and alpha of the trail draw. */
    var trailTint: Vec4 = vec(0.95, 0.62, 0.30, 0.30),

    /** Draw one particle in N, per layer. Points and edges can afford 1; trails usually cannot. */
    var pointEveryNth: Int = 1,
    var edgeEveryNth: Int = 1,
    var trailEveryNth: Int = 24,

    /** How much the oldest trail sample fades relative to the newest, `0..1`. */
    var trailFade: Float = 0.85f,

    // ---- the blur cascade ----

    /**
     * Kernel width multiplier for each cascade level.
     *
     * The second level runs at half the first's resolution *and* reaches further, which is what makes
     * the two levels look like different scales of light rather than like the same blur twice.
     */
    var blurTapsNear: Float = 1f,
    var blurTapsFar: Float = 2f,

    /** Per-level output gain. */
    var blurGainNear: Float = 1f,
    var blurGainFar: Float = 1f,

    /**
     * Input floor subtracted before blurring, per level. Above zero only the bright parts bloom, which
     * is the difference between a glow and a fog.
     */
    var blurThreshold: Float = 0.25f,

    // ---- the grade ----

    /** `pow(max((c + lift) * gain, 0), gamma)` — the colourist's triple, applied after the bloom. */
    var lift: Vec4 = vec(0.0, 0.004, 0.012, 0.0),
    var gain: Vec4 = vec(1.05, 1.00, 0.96, 1.0),
    var gamma: Vec4 = vec(0.95, 1.00, 1.05, 1.0),

    /** How much of each blur level is added back. */
    var bloomNear: Float = 0.35f,
    var bloomFar: Float = 0.55f,

    /** Saturation, 1 neutral. */
    var saturation: Float = 1.1f,

    /** Reinhard tonemap blend, `0..1`. Worth having above zero once the bloom is up. */
    var tonemap: Float = 0.6f,

    /** Corner darkening. */
    var vignette: Float = 0.35f,

    /** Radial chromatic aberration, in thousandths of the frame. Small numbers only. */
    var chroma: Float = 1.2f,

    /** Film grain. Seeded per eye — identical grain in both eyes fuses into a plane of dirt. */
    var grain: Float = 0.012f
) {
    /** `gravity` as the shader's `vec4` — `forces.gravity = { look.gravity() }`. */
    fun gravity(): Vec4 = vec(gravityX, gravityY, gravityZ, 0f)

    /** `bounds` as the shader's `vec4`: half extent in xyz, elasticity in w. */
    fun bounds(): Vec4 = vec(boundsX, boundsY, boundsZ, boundsElasticity)

    /** `centre` as the shader's `vec4`. */
    fun centre(): Vec4 = vec(centreX, centreY, centreZ, 0f)

    /** `bloom` as the shader's `vec4`: near, far, saturation, tonemap. */
    fun bloom(): Vec4 = vec(bloomNear, bloomFar, saturation, tonemap)

    /** Scale the knobs to fit a cloud of a given extent, so the defaults survive a resize. */
    fun forExtent(extent: Float): DustLook = apply {
        boundsX = extent * 1.6f; boundsY = extent * 1.6f; boundsZ = extent * 1.6f
        dotSize = extent * 0.006f
        maxStep = extent * 0.05f
    }

    /** Scale the knobs to a cloud's mean edge length — the scale the network actually has. */
    fun forMeanEdge(meanEdge: Float): DustLook = apply {
        dotSize = meanEdge * 0.18f
        speedGain = 1f / (meanEdge * 0.05f).coerceAtLeast(1e-5f)
        strainGain = 1f / (meanEdge * 0.5f).coerceAtLeast(1e-5f)
    }

    /** The same knobs, turned up. Not tasteful; a good place to start pushing from. */
    fun wild(): DustLook = apply {
        noiseGain = 2.4f
        restGain = 0.25f
        damping = 0.985f
        colorMode = 1
        bloomNear = 0.9f
        bloomFar = 1.4f
        chroma = 3.5f
        grain = 0.03f
        trailFade = 0.55f
        trailEveryNth = 8
    }

    override fun toString(): String =
        "dust look: edge %.2f noise %.2f rest %.2f damp %.3f | dot %.4f mode %d | bloom %.2f/%.2f tone %.2f"
            .format(edgeGain, noiseGain, restGain, damping, dotSize, colorMode, bloomNear, bloomFar, tonemap)
}
