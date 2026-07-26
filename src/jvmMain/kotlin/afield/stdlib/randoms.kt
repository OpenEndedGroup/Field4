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

fun random(): Double {
    return currentStdlibRandom().nextDouble()
}

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

fun noisePhase(): Float = currentStdlibPhase()

fun noiseForward(delta: Float): Float {
    val next = currentStdlibPhase() + delta
    return setStdlibPhase(next)
}

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

// Repeatable 1D Perlin-style noise driven by the current stdlib seed and a phase you can advance.
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

fun Vector2f.perlinNoise(amplitude: Float, phaseAdvance: Float = 0f, frequency: Float = 1f, seedOffset: Long = 0L, extraHash: Long = 0L): Vector2f {
    val n = sampleNoiseChannels(2, amplitude, phaseAdvance, frequency, seedOffset, extraHash)
    this.x += n[0]
    this.y += n[1]
    return this
}

fun Vector3f.perlinNoise(amplitude: Float, phaseAdvance: Float = 0f, frequency: Float = 1f, seedOffset: Long = 0L, extraHash: Long = 0L): Vector3f {
    val n = sampleNoiseChannels(3, amplitude, phaseAdvance, frequency, seedOffset, extraHash)
    this.x += n[0]
    this.y += n[1]
    this.z += n[2]
    return this
}

fun Vector4f.perlinNoise(amplitude: Float, phaseAdvance: Float = 0f, frequency: Float = 1f, seedOffset: Long = 0L, extraHash: Long = 0L): Vector4f {
    val n = sampleNoiseChannels(4, amplitude, phaseAdvance, frequency, seedOffset, extraHash)
    this.x += n[0]
    this.y += n[1]
    this.z += n[2]
    this.w += n[3]
    return this
}

fun Vector2f.noise(d: Number) : Vector2f {
    val rng = currentStdlibRandom()
    val scale = d.toFloat()
    this.x += (rng.nextFloat() - 0.5f) * scale
    this.y += (rng.nextFloat() - 0.5f) * scale
    return this
}

fun Vector3f.noise(d: Number) : Vector3f {
    val rng = currentStdlibRandom()
    val scale = d.toFloat()
    this.x += (rng.nextFloat() - 0.5f) * scale
    this.y += (rng.nextFloat() - 0.5f) * scale
    this.z += (rng.nextFloat() - 0.5f) * scale
    return this
}

fun Vector4f.noise(d: Number) : Vector4f {
    val rng = currentStdlibRandom()
    val scale = d.toFloat()
    this.x += (rng.nextFloat() - 0.5f) * scale
    this.y += (rng.nextFloat() - 0.5f) * scale
    this.z += (rng.nextFloat() - 0.5f) * scale
    this.w += (rng.nextFloat() - 0.5f) * scale
    return this
}
