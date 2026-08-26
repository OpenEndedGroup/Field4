@file:afield.CompletionCategory("Math")

package afield.stdlib

import afield.Box
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.util.*
import kotlin.random.Random as KotlinRandom

/**
 * Box-scoped randomness. [random], [randomGaussian] and [setRandomSeed] draw from a
 * `java.util.Random` held as a property on the current `Box`, so re-running a box replays the
 * same sequence; with no `Box` in context they fall back to one process-wide generator seeded
 * with [STDLIB_RANDOM_DEFAULT_SEED].
 *
 * Alongside the seed each box carries a noise phase you advance yourself ([noisePhase],
 * [noiseForward], [setNoisePhase]). That phase and seed drive [noise1D], a small Perlin-style
 * hashed 1D noise, and the `perlinNoise` extensions on `Vector2f`/`Vector3f`/`Vector4f`, which
 * take one independent noise channel per component.
 *
 * All the vector extensions — `perlinNoise` and the uniform-jitter `noise(d)` — mutate the
 * receiver in place and return it rather than allocating. [nextGaussian] is a Box-Muller
 * extension that fills the gap left by `kotlin.random.Random`, which has no Gaussian of its own.
 */

// Box-scoped RNG so stdlib random/noise are repeatable per execution.
private const val STDLIB_RANDOM_PROPERTY = "__stdlib_random"
private const val STDLIB_RANDOM_SEED_PROPERTY = "__stdlib_random_seed"
private const val STDLIB_NOISE_PHASE_PROPERTY = "__stdlib_noise_phase"
/**
 * The seed every box's generator starts from, and the seed [setRandomSeed] undoes.
 *
 * It is `1L` — a fixed value, so that a freshly re-run box produces the same "random"
 * sketch as it did last time. Pass a seed of your own to [setRandomSeed] when you want a different
 * draw, or `null` for an unpredictable one.
 */
const val STDLIB_RANDOM_DEFAULT_SEED: Long = 1L
private val fallbackStdlibRandom = Random(STDLIB_RANDOM_DEFAULT_SEED)
private var fallbackStdlibSeed: Long = STDLIB_RANDOM_DEFAULT_SEED
private var fallbackStdlibPhase: Float = 0f

internal fun Box.resetStdlibRandom(seed: Long = STDLIB_RANDOM_DEFAULT_SEED): Random {
    val rng = Random(seed)
    setPropertyRaw(STDLIB_RANDOM_PROPERTY, rng)
    setPropertyRaw(STDLIB_RANDOM_SEED_PROPERTY, seed)
    setPropertyRaw(STDLIB_NOISE_PHASE_PROPERTY, 0f)
    return rng
}

internal fun Box.stdlibRandom(): Random {
    val existing = getPropertyRaw(STDLIB_RANDOM_PROPERTY) as? Random
    if (existing != null) return existing
    return resetStdlibRandom()
}

private fun currentStdlibSeed(): Long {
    val box = Box.context.get()
    val seeded = box?.getPropertyRaw(STDLIB_RANDOM_SEED_PROPERTY) as? Long
    return seeded ?: fallbackStdlibSeed
}

private fun currentStdlibPhase(): Float {
    val box = Box.context.get()
    val phase = box?.getPropertyRaw(STDLIB_NOISE_PHASE_PROPERTY) as? Float
    return phase ?: fallbackStdlibPhase
}

private fun setStdlibPhase(phase: Float): Float {
    val box = Box.context.get()
    if (box != null) {
        box.setPropertyRaw(STDLIB_NOISE_PHASE_PROPERTY, phase)
    } else {
        fallbackStdlibPhase = phase
    }
    return phase
}

private fun currentStdlibRandom(): Random {
    val box = Box.context.get()
    return box?.stdlibRandom() ?: fallbackStdlibRandom
}

/**
 * A uniform sample from `[0, 1)` — zero possible, one not — drawn from the current box's generator.
 *
 * Repeatable: the box's generator is reseeded to [STDLIB_RANDOM_DEFAULT_SEED] the first time it is
 * touched, so the same box re-run gives the same sequence back. [setRandomSeed] is how you pick a
 * different one.
 */
fun random(): Double {
    return currentStdlibRandom().nextDouble()
}

/**
 * A standard-normal sample (mean 0, standard deviation 1) from the current box's generator.
 *
 * Unbounded, though about 99.7% of draws land in `-3..3`; scale and offset it yourself for another
 * mean and spread. Repeatable on the same terms as [random].
 */
fun randomGaussian(): Double {
    return currentStdlibRandom().nextGaussian()
}

/**
 * Standard-normal (Box-Muller) sample from any [kotlin.random.Random]. The Kotlin
 * stdlib `Random` has no `nextGaussian()` of its own (unlike `java.util.Random`),
 * so this fills the gap for code that carries its own seeded generator.
 */
fun KotlinRandom.nextGaussian(): Double {
    var u1 = nextDouble()
    while (u1 <= Double.MIN_VALUE) u1 = nextDouble()
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
        kotlin.math.cos(2.0 * kotlin.math.PI * u2)
}

/**
 * Reseeds the current box's generator and resets its noise phase to 0, returning the seed used.
 *
 * With no argument a seed is drawn unpredictably — the way to get a *different* sketch each run —
 * and the return value is how you find out which one you got, so you can pass it back to reproduce
 * the version you liked. Everything downstream restarts: [random], [randomGaussian], the `noise`
 * jitter and, through the seed, [noise1D] and the `perlinNoise` extensions.
 */
fun setRandomSeed(seed: Long? = null): Long {
    val chosenSeed = seed ?: KotlinRandom.Default.nextLong()
    val box = Box.context.get()
    if (box != null) {
        box.resetStdlibRandom(chosenSeed)
    } else {
        fallbackStdlibRandom.setSeed(chosenSeed)
        fallbackStdlibSeed = chosenSeed
        fallbackStdlibPhase = 0f
    }
    return chosenSeed
}

/**
 * Where along its noise curve this box currently is — the default `phase` [noise1D] samples at.
 *
 * Starts at 0 and only moves when you move it, with [noiseForward] or [setNoisePhase]; nothing
 * advances it for you, so a box that never advances the phase gets the same noise value every frame.
 */
fun noisePhase(): Float = currentStdlibPhase()

/**
 * Advances this box's noise phase by [delta] and returns the new phase.
 *
 * The usual once-per-frame call for animated noise. [delta] is in phase units, and [noise1D] *multiplies*
 * phase by its `frequency`, so one whole unit of delta is one lattice cell only at the default
 * `frequency = 1f` — at `frequency = 4f` it is four. At that default, around `0.01..0.05` per frame reads
 * as a slow drift and 1.0 as unrelated values frame to frame. Negative deltas run the noise backwards.
 */
fun noiseForward(delta: Float): Float {
    val next = currentStdlibPhase() + delta
    return setStdlibPhase(next)
}

/** Jumps this box's noise phase straight to [phase], returning it. Use to drive noise from a clock. */
fun setNoisePhase(phase: Float): Float = setStdlibPhase(phase)

private fun fade(t: Float): Float {
    // smootherstep
    return t * t * t * (t * (t * 6 - 15) + 10)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)

private fun grad1(hash: Int, x: Float): Float {
    return if ((hash and 1) == 0) x else -x
}

private fun hash1D(seed: Long, x: Int): Int {
    var h = x
    h = h xor (seed.toInt())
    h = h * 0x27d4eb2d
    h = h xor (h ushr 15)
    return h and 255
}

/**
 * Repeatable 1D Perlin-style noise: smooth, continuous, and bounded by **±0.5**.
 *
 * That half range is not a bug but it is a trap, so it is stated first: one-dimensional gradient noise
 * with ±1 gradients peaks at 0.5 (midway between two lattice points that lean opposite ways), and nothing
 * here re-normalises it. Double it if you want the full `-1..1`.
 *
 * Unlike [random] this is a *function* of its [phase] rather than a stream — sample the same phase
 * twice and you get the same number, and nearby phases give nearby numbers. One unit of phase is one
 * lattice cell, [frequency] multiplies the phase before sampling (so `frequency = 4` gives four times
 * the wiggle per unit), and the default phase is the box's own [noisePhase].
 *
 * The curve is fixed by the box's random seed, so [setRandomSeed] gives you a different one. [seedOffset]
 * picks a different curve off that seed, which is how you get one noise channel per thing you are animating
 * without them moving in lockstep; the `perlinNoise` extensions use it exactly that way, one channel per
 * vector component. [extraHash] is **summed into the same scalar**, so it is a second way to spell the same
 * offset rather than an independent axis — `seedOffset = 1` and `extraHash = 1` give the identical curve, and
 * a non-zero `extraHash` will collide with a neighbouring component's channel.
 */
fun noise1D(
    phase: Float = currentStdlibPhase(),
    seedOffset: Long = 0L,
    frequency: Float = 1f,
    extraHash: Long = 0L
): Float {
    val baseSeed = currentStdlibSeed() + seedOffset + extraHash
    val phaseBias = hash1D(baseSeed, 0x12345).toFloat() / 255f // shift so integer phases aren't at lattice points

    val x = (phase + phaseBias) * frequency
    val xi = kotlin.math.floor(x).toInt()
    val xf = x - xi

    val g0 = grad1(hash1D(baseSeed, xi), xf)
    val g1 = grad1(hash1D(baseSeed, xi + 1), xf - 1f)

    val u = fade(xf)
    return lerp(g0, g1, u)
}

private fun sampleNoiseChannels(
    count: Int,
    amplitude: Float,
    phaseAdvance: Float,
    frequency: Float,
    seedOffset: Long = 0L,
    extraHash: Long = 0L
): FloatArray {
    val basePhase = noisePhase()
    val out = FloatArray(count) { i ->
        noise1D(basePhase, seedOffset + i, frequency, extraHash) * amplitude
    }
    if (phaseAdvance != 0f) noiseForward(phaseAdvance)
    return out
}

/**
 * Adds smooth [noise1D] wander to this point **in place**, one independent channel per component,
 * and returns the same instance.
 *
 * Each component is offset by up to `±amplitude/2`, because [noise1D] is bounded by ±0.5 before scaling. The
 * receiver is mutated, so `p.perlinNoise(4f)` moves `p` — copy it first if you needed the original.
 *
 * [phaseAdvance] is applied to the box's shared [noisePhase] *after* sampling, so a chain of calls in
 * one frame all read the same phase; pass it on one call per frame (or use [noiseForward] yourself)
 * to animate. [seedOffset] shifts which noise channels are used, so two points that should wander
 * differently need different offsets — spaced by at least 2 here, since this call consumes channels
 * `seedOffset` and `seedOffset + 1`.
 */
fun Vector2f.perlinNoise(amplitude: Float, phaseAdvance: Float = 0f, frequency: Float = 1f, seedOffset: Long = 0L, extraHash: Long = 0L): Vector2f {
    val n = sampleNoiseChannels(2, amplitude, phaseAdvance, frequency, seedOffset, extraHash)
    this.x += n[0]
    this.y += n[1]
    return this
}

/**
 * Adds smooth [noise1D] wander to this point **in place** — three independent channels, one per
 * component — and returns the same instance. Conventions exactly as the `Vector2f` overload; this one
 * consumes noise channels `seedOffset` through `seedOffset + 2`.
 */
fun Vector3f.perlinNoise(amplitude: Float, phaseAdvance: Float = 0f, frequency: Float = 1f, seedOffset: Long = 0L, extraHash: Long = 0L): Vector3f {
    val n = sampleNoiseChannels(3, amplitude, phaseAdvance, frequency, seedOffset, extraHash)
    this.x += n[0]
    this.y += n[1]
    this.z += n[2]
    return this
}

/**
 * Adds smooth [noise1D] wander to all four components **in place**, one independent channel each, and
 * returns the same instance. Conventions as the `Vector2f` overload. Note that on a colour this
 * wanders alpha too, which is rarely what you want — jitter the `Vector3f` and rebuild the alpha.
 */
fun Vector4f.perlinNoise(amplitude: Float, phaseAdvance: Float = 0f, frequency: Float = 1f, seedOffset: Long = 0L, extraHash: Long = 0L): Vector4f {
    val n = sampleNoiseChannels(4, amplitude, phaseAdvance, frequency, seedOffset, extraHash)
    this.x += n[0]
    this.y += n[1]
    this.z += n[2]
    this.w += n[3]
    return this
}

/**
 * Jitters this point **in place** by an independent uniform draw per component and returns the same
 * instance.
 *
 * Each component moves by up to `±d/2` — [d] is the full width of the jitter box, not the radius —
 * so `p.noise(10)` scatters within 10 units across. Unlike [perlinNoise] this is white noise from
 * [random], with no continuity between calls: a point jittered every frame flickers rather than
 * drifts. The receiver is mutated.
 */
fun Vector2f.noise(d: Number) : Vector2f {
    val rng = currentStdlibRandom()
    val scale = d.toFloat()
    this.x += (rng.nextFloat() - 0.5f) * scale
    this.y += (rng.nextFloat() - 0.5f) * scale
    return this
}

/**
 * Jitters all three components **in place** by up to `±d/2` each, uniformly and independently, and
 * returns the same instance. As the `Vector2f` overload: white noise, not smooth wander.
 */
fun Vector3f.noise(d: Number) : Vector3f {
    val rng = currentStdlibRandom()
    val scale = d.toFloat()
    this.x += (rng.nextFloat() - 0.5f) * scale
    this.y += (rng.nextFloat() - 0.5f) * scale
    this.z += (rng.nextFloat() - 0.5f) * scale
    return this
}

/**
 * Jitters all four components **in place** by up to `±d/2` each, uniformly and independently, and
 * returns the same instance. As the `Vector2f` overload — including alpha, if this is a colour.
 */
fun Vector4f.noise(d: Number) : Vector4f {
    val rng = currentStdlibRandom()
    val scale = d.toFloat()
    this.x += (rng.nextFloat() - 0.5f) * scale
    this.y += (rng.nextFloat() - 0.5f) * scale
    this.z += (rng.nextFloat() - 0.5f) * scale
    this.w += (rng.nextFloat() - 0.5f) * scale
    return this
}
