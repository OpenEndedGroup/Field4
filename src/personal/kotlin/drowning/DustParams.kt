package drowning

import afield.stdlib.Vec4
import afield.stdlib.vec

/**
 * Every `dust` parameter, as functions with named arguments that return a `Vec4`.
 *
 * ## Why not a class
 *
 * `dust_one` put the whole knob surface on a `DustLook` data class. It was typesafe and it was the
 * wrong shape, for two reasons that only show up once you are actually working:
 *
 * 1. **Adding a knob needed a full teardown.** `DustLook` lives in the personal jar, so a new field
 *    meant a Gradle build and a restart of the running sketch — which is the opposite of live coding,
 *    and it is exactly the moment you want a new knob.
 * 2. **It hid the parameters from the keyframer.** `KeyframeScope` binds *named values in named
 *    shaders*: `target("points@dot", points.push)` reads and writes the `dot` push member. A knob
 *    that only exists as a Kotlin field on a class the shader never sees cannot be snapshotted,
 *    interpolated or saved. `KValue` persists `vec4` natively, so a `vec4`-shaped parameter is the one
 *    that survives a keyframe round trip.
 *
 * So the knobs live in the **shader**, grouped into named `vec4`s, and this file is only the readable
 * way to *write* one. Nothing here holds state, nothing here needs reloading, and adding a knob is a
 * new lane in a `vec4` plus a `#define` — one shader edit, hot-reloaded.
 *
 * ```kotlin
 * points.dot  = { dustDot(size = 0.012f, minPixels = 0.9f, viewportHeight = h) }
 * points.ramp = { dustRamp(speed = 60f, mode = DUST_COLOR_SPEED) }
 * forces.gains = { dustGains(edge = 1f, noise = 2.4f, rest = 0.2f, damping = 0.985f) }
 * ```
 *
 * versus the same thing keyframed, where the scope drives the same members directly:
 *
 * ```kotlin
 * var keys = keyframes {
 *     target("gains", forces.push)
 *     target("ramp", points.push)
 *     stackAuto(forceModule, "noiseForce")
 *     camera(camera)
 * }
 * var calm = keys.capture()
 * ```
 *
 * ## The lane names are in the GLSL too
 *
 * Every group below has a matching block of `#define`s in [DustShaders.DUST_LANES], so the shader reads
 * `DUST_NOISE_GAIN` rather than `pc.gains.y`. The two lists are checked against each other by
 * `DustWorkout.everyParameterLaneIsNamedOnBothSides` — a lane that gains a meaning on one side and not
 * the other is the failure mode this shape invites, and it is the one thing worth a test.
 *
 * ## Integers in a float lane
 *
 * A few genuinely integral things ride in a `vec4` lane: `edgeSlots`, `colorMode`, the history cursor.
 * `float` holds every integer below 2^24 exactly, so a 40000-particle count and a 64-frame ring are
 * both lossless, and the shader reads them with `uint(x + 0.5)`. The reason to do it rather than keep
 * them as `uint` members is that a `vec4` is what the keyframer interpolates — a colour mode that can
 * be morphed is worth more than a colour mode that is typed.
 */

// ---- colour modes ------------------------------------------------------------------------------
//
// Ints rather than an enum, because they travel in a float lane and an enum would need a class.

/** Flat — the tint alone. */
const val DUST_COLOR_FLAT = 0

/** By speed, through the palette. */
const val DUST_COLOR_SPEED = 1

/** By displacement from the rest position, through the diverging ramp. */
const val DUST_COLOR_STRAIN = 2

/** By degree in the trimmed orthant network, 0..6. */
const val DUST_COLOR_DEGREE = 3

/** By the stable per-particle hash — a fixed random colour each. */
const val DUST_COLOR_HASH = 4

// ---- what the force shader's probe watches -----------------------------------------------------

/** Probe the per-particle speed, `|pos - prev|`. The default, and the one to start from. */
const val DUST_PROBE_SPEED = 0

/** Probe the magnitude of the accumulated force, before `1/mass` and before the clamps. */
const val DUST_PROBE_FORCE = 1

/** Probe the mean relative edge strain, `(len - rest) / rest`, over the particle's filled slots. */
const val DUST_PROBE_STRAIN = 2

/** Probe the distance from the rest position. */
const val DUST_PROBE_DISPLACEMENT = 3

// ---- the force shader --------------------------------------------------------------------------

/**
 * `gains` — how hard each of the three force terms pulls.
 *
 * @param edge scales every `edgeForce` result. Negative inverts the network into a repulsion.
 * @param noise scales `noiseForce`. The one to turn up first; the tether pulls it back.
 * @param rest scales `restForce`. Zero lets the field float free.
 * @param damping velocity retained per step, `0..1`. Verlet's damping term, so 1 is frictionless.
 *   Above 1 the integrator gains energy every step and only `maxStep` stops it leaving the screen.
 */
fun dustGains(
    edge: Float = 1f,
    noise: Float = 0.6f,
    rest: Float = 0.8f,
    damping: Float = 0.94f
): Vec4 = vec(edge, noise, rest, damping)

/**
 * `limits` — the two clamps, how much of the network to honour, and the freeze.
 *
 * The clamps are what make the force programs safe to live-edit: a program with a sign error produces
 * an enormous force, and without them the field is off the screen before you have finished typing.
 * With them the worst case looks like static.
 *
 * @param maxStep clamp on per-step displacement, in world units. Zero disables it.
 * @param maxForce clamp on the accumulated force, before `1/mass`. Zero disables it.
 * @param edgeSlots honour only the first N of the six neighbour slots. Lower is slacker and stringier.
 * @param paused freeze the integrator. The programs still run, so a force can still be watched.
 */
fun dustLimits(
    maxStep: Float = 0.05f,
    maxForce: Float = 200f,
    edgeSlots: Int = DustLayout.EDGES,
    paused: Boolean = false
): Vec4 = vec(maxStep, maxForce, edgeSlots.toFloat(), if (paused) 1f else 0f)

/** `gravity` — a constant acceleration. */
fun dustGravity(x: Float = 0f, y: Float = 0f, z: Float = 0f): Vec4 = vec(x, y, z, 0f)

/**
 * `bounds` — the containing box's half extent and how much speed a wall returns.
 *
 * @param elasticity `0..1`. **Zero is off**: the shader tests the whole field, so there is no separate
 *   enable to forget.
 */
fun dustBounds(
    x: Float = 1.6f,
    y: Float = 1.6f,
    z: Float = 1.6f,
    elasticity: Float = 0.35f
): Vec4 = vec(x, y, z, elasticity)

/** `bounds`, cubic — the common case. */
fun dustBounds(half: Float, elasticity: Float = 0.35f): Vec4 = vec(half, half, half, elasticity)

/** `centre` — where that box sits. */
fun dustCentre(x: Float = 0f, y: Float = 0f, z: Float = 0f): Vec4 = vec(x, y, z, 0f)

/**
 * `probe` — what the force shader's field probe records, and over how much of the field.
 *
 * The probe is free statistics: a histogram, min/max, NaN and infinity counts, and a mean, all
 * reduced on the GPU and shown in the box's output. It is a much better answer than reading the state
 * buffer back to the CPU, which is what `dust_one` did.
 *
 * @param what one of the `DUST_PROBE_*` constants
 * @param everyNth probe one particle in N. 1 probes all of them; the reservoir sampling in the probe
 *   itself means this is about *cost*, not about sample quality.
 */
fun dustProbe(what: Int = DUST_PROBE_SPEED, everyNth: Int = 1): Vec4 =
    vec(what.toFloat(), everyNth.coerceAtLeast(1).toFloat(), 0f, 0f)

// ---- the debug draws ---------------------------------------------------------------------------

/**
 * `dot` — the point sprite's size.
 *
 * Sized in **world** units with a floor in pixels, so the cloud thins with distance the way a real one
 * does rather than becoming a wall of same-sized specks.
 *
 * @param size radius in world units
 * @param minPixels floor on the on-screen radius, so distant particles stay visible
 * @param viewportHeight the render target's height in pixels — what turns [minPixels] into world units
 */
fun dustDot(
    size: Float = 0.01f,
    minPixels: Float = 0.8f,
    viewportHeight: Float = 1024f
): Vec4 = vec(size, minPixels, viewportHeight, 0f)

/**
 * `ramp` — how a draw turns a quantity into a colour, and how it fades with distance.
 *
 * @param speed `1 / the speed that saturates the palette`. A field's mean step is a good scale.
 * @param strain `1 / the displacement-from-rest that saturates the diverging ramp`.
 * @param fade exponential distance fade toward black, per world unit of clip-space w. Zero disables it.
 * @param mode one of the `DUST_COLOR_*` constants
 */
fun dustRamp(
    speed: Float = 60f,
    strain: Float = 4f,
    fade: Float = 0.04f,
    mode: Int = DUST_COLOR_SPEED
): Vec4 = vec(speed, strain, fade, mode.toFloat())

/**
 * `trail` — where the history ring's newest sample is, and how much of the field to trace.
 *
 * The first three come from `DustBuffers.cursor` and are not really knobs; [everyNth] is. At 40k
 * particles and 64 frames the untrimmed trail draw is 5M vertices for a debug view, which is why it
 * defaults to drawing one particle in 24.
 */
fun dustTrail(
    capacity: Int,
    head: Int,
    written: Int,
    everyNth: Int = 24
): Vec4 = vec(
    capacity.toFloat(), head.toFloat(), written.toFloat(), everyNth.coerceAtLeast(1).toFloat()
)

/** `trail` straight off a cursor — the form a box actually writes. */
fun dustTrail(cursor: DustHistoryCursor, everyNth: Int = 24): Vec4 =
    dustTrail(cursor.capacity, cursor.head, cursor.written, everyNth)

/**
 * `stroke` — the per-draw look that is not a colour ramp: alpha falloff along a trail, and the
 * every-Nth thinning that applies to points and edges too.
 *
 * @param trailFade how much the oldest trail sample fades relative to the newest, `0..1`
 * @param everyNth draw one particle in N. Independent of [dustTrail]'s, because the points and the
 *   edges are thinned separately from the trails.
 */
fun dustStroke(trailFade: Float = 0.85f, everyNth: Int = 1): Vec4 =
    vec(trailFade, everyNth.coerceAtLeast(1).toFloat(), 0f, 0f)

/** `tint` — a draw's base colour and alpha. */
fun dustTint(r: Float, g: Float, b: Float, a: Float = 1f): Vec4 = vec(r, g, b, a)

// ---- the blur cascade and the grade -------------------------------------------------------------

/**
 * `blur` — one pass of the 7-tap separable cascade.
 *
 * @param horizontal the smear direction. The box runs the same module twice with this flipped.
 * @param taps kernel width multiplier. The far level uses a larger value so the two levels look like
 *   different scales of light rather than the same blur twice.
 * @param gain output gain
 * @param threshold input floor subtracted before blurring. Above zero only the bright parts bloom,
 *   which is the difference between a glow and a fog. Apply it on the **first** pass only.
 */
fun dustBlur(
    horizontal: Boolean,
    taps: Float = 1f,
    gain: Float = 1f,
    threshold: Float = 0f
): Vec4 = vec(if (horizontal) 1f else 0f, taps, gain, threshold)

/** `blurSize` — the source's size in pixels, which is what turns [dustBlur]'s taps into texels. */
fun dustBlurSize(width: Int, height: Int): Vec4 = vec(width.toFloat(), height.toFloat(), 0f, 0f)

/** `lift` / `gain` / `gamma` — `pow(max((c + lift) * gain, 0), gamma)`, the colourist's triple. */
fun dustLift(r: Float = 0f, g: Float = 0.004f, b: Float = 0.012f): Vec4 = vec(r, g, b, 0f)

/** See [dustLift]. */
fun dustGain(r: Float = 1.05f, g: Float = 1.0f, b: Float = 0.96f): Vec4 = vec(r, g, b, 1f)

/** See [dustLift]. */
fun dustGamma(r: Float = 0.95f, g: Float = 1.0f, b: Float = 1.05f): Vec4 = vec(r, g, b, 1f)

/**
 * `bloom` — how much of each blur level comes back, and the two global shapers.
 *
 * @param near the half-resolution level
 * @param far the quarter-resolution level
 * @param saturation 1 is neutral
 * @param tonemap Reinhard blend, `0..1`. Worth having above zero once the bloom is up.
 */
fun dustBloom(
    near: Float = 0.35f,
    far: Float = 0.55f,
    saturation: Float = 1.1f,
    tonemap: Float = 0.6f
): Vec4 = vec(near, far, saturation, tonemap)

/**
 * `lens` — the three things that make a stereo pair read as a photograph rather than a render.
 *
 * @param vignette corner darkening
 * @param chroma radial chromatic aberration, in thousandths of the frame. Small numbers only.
 * @param grain film grain. Seeded per eye — identical grain in both eyes fuses into a plane of dirt
 *   hanging in front of the image, which is genuinely unpleasant in a headset.
 * @param time seconds, so the grain moves
 */
fun dustLens(
    vignette: Float = 0.35f,
    chroma: Float = 1.2f,
    grain: Float = 0.012f,
    time: Float = 0f
): Vec4 = vec(vignette, chroma, grain, time)
