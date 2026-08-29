package afield.stacksl

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first tests StackSL has ever had.
 *
 * Until this file, `StackSL.kt` was 3320 lines of compiler with no unit test of any kind — the
 * three `jvmTest` files that mention the name are `StackSLString` keyframer-serialisation tests
 * and one comment. That is worth saying plainly, because it is the reason the two defects this
 * branch is about (the 2D `fbm`'s 0.96875 ceiling, and `noise`/`fbm` silently collapsing a 3D
 * coordinate into a sheared plane) survived long enough for someone to hit them in live work.
 *
 * Everything here runs on the CPU in milliseconds. `StackSLCompiler.compile` is a pure
 * `String -> GLSL` function, and [VolumetricNoiseReference] is a transliteration of the emitted
 * shader, so the whole family's semantics are reachable without a device.
 *
 * Where a bound below looks oddly specific, it is a MEASURED number with headroom, not a guess.
 * The measurements are printed by [reportMeasurements] so the next person can see what the
 * margins actually are rather than trusting the constants.
 */
class VolumetricNoiseTest {

    private val K = VolumetricNoiseConstants
    private val ref = VolumetricNoiseReference

    // ---------------------------------------------------------------------------------------
    // Sampling helpers.
    // ---------------------------------------------------------------------------------------

    /** A deterministic spread of points well away from the origin and from the integer lattice. */
    private fun samples(n: Int, spread: Float = 40f, seed: Int = 12345): List<NoiseVec3> {
        val rng = Random(seed)
        return List(n) {
            NoiseVec3(
                (rng.nextFloat() - 0.5f) * 2f * spread,
                (rng.nextFloat() - 0.5f) * 2f * spread,
                (rng.nextFloat() - 0.5f) * 2f * spread
            )
        }
    }

    private fun rms(values: List<Float>): Float =
        sqrt(values.sumOf { (it * it).toDouble() } / values.size).toFloat()

    // ---------------------------------------------------------------------------------------
    // The hashes.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `hashes are in range, and the origin is not a fixed point`() {
        // The Hoskins hash has a zero fixed point: fract(0 * s) is 0, the dot product of zero
        // with anything is 0, and fract(0 * 0) is 0 -- so an unseeded hash31(0,0,0) is exactly
        // 0.0 and puts a black speck on the origin of every field. HASH_SEED exists to break it.
        val atOrigin = ref.hash31(0f, 0f, 0f)
        assertTrue(atOrigin > 1e-4f, "hash31 at the origin degenerated to $atOrigin; HASH_SEED is not working")

        val h33 = ref.hash33(0f, 0f, 0f)
        assertTrue(h33.x > 1e-4f || h33.y > 1e-4f || h33.z > 1e-4f, "hash33 collapsed at the origin: $h33")
        assertTrue(ref.hash41(0f, 0f, 0f, 0f) > 1e-4f, "hash41 collapsed at the origin")

        // And across the lattice the range must hold.
        for (x in -8..8) for (y in -8..8) for (z in -8..8) {
            val h = ref.hash31(x.toFloat(), y.toFloat(), z.toFloat())
            assertTrue(h >= 0f && h < 1f, "hash31($x,$y,$z) = $h is outside [0,1)")
            val v = ref.hash33(x.toFloat(), y.toFloat(), z.toFloat())
            assertTrue(v.x in 0f..1f && v.y in 0f..1f && v.z in 0f..1f, "hash33($x,$y,$z) = $v out of range")
        }
    }

    @Test
    fun `hashes are roughly uniform over the lattice`() {
        // A hash that clumps makes value noise that clumps. Ten buckets over the integer lattice;
        // a perfect hash puts 10% in each. This is a smoke test for gross bias, not a PRNG audit.
        val buckets = IntArray(10)
        var count = 0
        for (x in -12..12) for (y in -12..12) for (z in -12..12) {
            val h = ref.hash31(x.toFloat(), y.toFloat(), z.toFloat())
            buckets[(h * 10f).toInt().coerceIn(0, 9)]++
            count++
        }
        val expected = count / 10.0
        val worst = buckets.maxOf { abs(it - expected) / expected }
        println("hash31 lattice uniformity: buckets=${buckets.toList()} worstDeviation=${"%.3f".format(worst)}")
        assertTrue(worst < 0.35, "hash31 is badly non-uniform over the lattice: $worst relative deviation")
    }

    // ---------------------------------------------------------------------------------------
    // noise3 / noise4 ranges and smoothness.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `noise3 is exactly in the unit interval`() {
        // Value noise is a convex blend of eight hashes each in [0,1), so this is not an
        // empirical bound -- it is structural, and if it ever fails the interpolation is wrong.
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        samples(20000).forEach {
            val v = ref.noise3(it)
            assertTrue(v >= 0f && v < 1f, "noise3($it) = $v escaped [0,1)")
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        println("noise3 observed range over 20000 samples: $lo .. $hi")
        // And it must actually use the range, not hover near the mean.
        assertTrue(lo < 0.10f && hi > 0.90f, "noise3 only covered $lo..$hi; it is not exercising its range")
    }

    @Test
    fun `noise4 is exactly in the unit interval`() {
        val rng = Random(777)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        repeat(20000) {
            val p = NoiseVec4(
                (rng.nextFloat() - 0.5f) * 60f, (rng.nextFloat() - 0.5f) * 60f,
                (rng.nextFloat() - 0.5f) * 60f, (rng.nextFloat() - 0.5f) * 60f
            )
            val v = ref.noise4(p)
            assertTrue(v >= 0f && v < 1f, "noise4($p) = $v escaped [0,1)")
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        println("noise4 observed range: $lo .. $hi")
        assertTrue(lo < 0.10f && hi > 0.90f, "noise4 only covered $lo..$hi")
    }

    @Test
    fun `noise3 is continuous across cell boundaries`() {
        // Value noise is only C0 at the lattice, but it must not JUMP: a discontinuity is the
        // signature of a corner-indexing bug, and it shows up as a visible grid of hard edges.
        var worst = 0f
        val step = 1e-3f
        for (i in 0 until 400) {
            val t = i * 0.25f
            // Walk straight through an integer plane in each axis.
            val a = NoiseVec3(3f - step, 0.3f + t, 1.7f - t * 0.5f)
            val b = NoiseVec3(3f + step, 0.3f + t, 1.7f - t * 0.5f)
            worst = maxOf(worst, abs(ref.noise3(b) - ref.noise3(a)))
        }
        println("noise3 worst jump across an integer plane at step ${step * 2}: $worst")
        assertTrue(worst < 0.02f, "noise3 is discontinuous at the lattice: jump of $worst")
    }

    // ---------------------------------------------------------------------------------------
    // THE DEFECT THIS BRANCH IS ABOUT: 2D noise applied to a 3D coordinate.
    // ---------------------------------------------------------------------------------------

    /**
     * A CPU copy of StackSL's *existing* 2D helpers, purely as a baseline to measure the shear
     * against. Transliterated from `helperSource`'s `_hash21`/`_noise`/`_fbm` at StackSL.kt:2783.
     * This is comparison scaffolding, not a reference: the `sin()` makes it unportable, which is
     * exactly why the new family does not use it.
     */
    private fun legacyHash21(px: Float, py: Float): Float {
        var x = px * 123.34f
        var y = py * 456.21f
        x -= floor(x)
        y -= floor(y)
        val d = x * (x + 45.32f) + y * (y + 45.32f)
        x += d
        y += d
        val r = x * y
        return r - floor(r)
    }

    private fun legacyNoise2(px: Float, py: Float): Float {
        val ix = floor(px)
        val iy = floor(py)
        val fx = px - ix
        val fy = py - iy
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val a = legacyHash21(ix, iy)
        val b = legacyHash21(ix + 1f, iy)
        val c = legacyHash21(ix, iy + 1f)
        val d = legacyHash21(ix + 1f, iy + 1f)
        return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy
    }

    /** The 2D `fbm`, including its 0.96875 ceiling. Five octaves, `mat2(1.6,1.2,-1.2,1.6)`. */
    private fun legacyFbm2(px: Float, py: Float): Float {
        var x = px
        var y = py
        var amp = 0.5f
        var value = 0f
        repeat(5) {
            value += amp * legacyNoise2(x, y)
            val nx = 1.6f * x + -1.2f * y + 17.17f
            val ny = 1.2f * x + 1.6f * y + 17.17f
            x = nx
            y = ny
            amp *= 0.5f
        }
        return value
    }

    @Test
    fun `the 2D fbm really does top out at 0-96875 - dated characterization`() {
        // FENCE DATED 2026-08-29. This pins CURRENT behaviour of the pre-existing 2D `fbm`; it is
        // NOT an endorsement. The five octave amplitudes are 0.5 0.25 0.125 0.0625 0.03125, which
        // sum to 0.96875, so `fbm 0.5 -` is biased by about -0.031 rather than centred. A user hit
        // this on 2026-08-29 and hand-patched it with a `1.032258` multiply
        // (= 1/0.96875) in dust_two's noiseForce.
        //
        // This branch deliberately does NOT change the 2D word -- that would move every rendered
        // golden and doc capture. It fixes the class of bug in the new family instead, by
        // accumulating the amplitude sum at runtime. If you ever DO renormalise the 2D fbm, this
        // test is the tripwire that tells you goldens are about to move.
        val amplitudes = generateSequence(0.5) { it * 0.5 }.take(5).toList()
        assertEquals(0.96875, amplitudes.sum(), 1e-12, "the 2D fbm's octave amplitudes")

        var hi = -Float.MAX_VALUE
        val rng = Random(31337)
        repeat(200000) {
            val v = legacyFbm2((rng.nextFloat() - 0.5f) * 200f, (rng.nextFloat() - 0.5f) * 200f)
            if (v > hi) hi = v
        }
        println("legacy 2D fbm observed maximum over 200000 samples: $hi (ceiling 0.96875)")
        assertTrue(hi <= 0.96875f, "the 2D fbm exceeded its structural ceiling: $hi")
        assertTrue(hi > 0.87f, "the 2D fbm sampling did not get near its ceiling ($hi); the probe is too weak to be evidence")
    }

    @Test
    fun `the 2D word shears z into x, and the 3D word does not`() {
        // THE HEADLINE DEFECT, measured rather than asserted from the source text.
        //
        // `noise`/`fbm` lower to `_fbm(input.xy + input.zw * 0.07)`. So moving along z moves the
        // sample point 1/14 as far as moving along x does, and a volume seen down z carries one
        // pattern smeared through every depth. Here that is a number: the RMS change in the field
        // for a unit step along z, divided by the same for a unit step along x.
        val pts = samples(3000, spread = 20f, seed = 99)
        val delta = 1.0f

        val legacyX = pts.map { legacyFbm2(it.x + delta, it.y) - legacyFbm2(it.x, it.y) }
        // z enters the 2D word through the `.zw * 0.07` term, i.e. added to the x coordinate.
        val legacyZ = pts.map { legacyFbm2(it.x + delta * 0.07f, it.y) - legacyFbm2(it.x, it.y) }
        val legacyRatio = rms(legacyZ) / rms(legacyX)

        val volX = pts.map { ref.fbm3(NoiseVec3(it.x + delta, it.y, it.z)) - ref.fbm3(it) }
        val volZ = pts.map { ref.fbm3(NoiseVec3(it.x, it.y, it.z + delta)) - ref.fbm3(it) }
        val volRatio = rms(volZ) / rms(volX)

        println(
            "z-sensitivity / x-sensitivity  --  legacy 2D fbm: ${"%.4f".format(legacyRatio)}, " +
                "volumetric fbm3: ${"%.4f".format(volRatio)}"
        )

        assertTrue(
            legacyRatio < 0.35f,
            "the 2D fbm was expected to be far less sensitive to z than to x, but the ratio was $legacyRatio"
        )
        assertTrue(
            volRatio > 0.7f && volRatio < 1.4f,
            "fbm3 should respond to z about as much as to x, but the ratio was $volRatio"
        )
        assertTrue(
            volRatio > legacyRatio * 3f,
            "fbm3 ($volRatio) is not decisively more z-sensitive than the 2D word ($legacyRatio)"
        )
    }

    // ---------------------------------------------------------------------------------------
    // fbm3 / fbm4 normalisation -- the structural fix for 0.96875.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `fbm3 covers the unit interval for every octave count, lacunarity and gain`() {
        val pts = samples(4000, spread = 60f, seed = 4242)
        for (octaves in 1..K.MAX_OCTAVES) {
            for (gain in listOf(0.3f, 0.5f, 0.75f, 1.0f)) {
                for (lacunarity in listOf(1.7f, 2.0f, 2.6f)) {
                    var lo = Float.MAX_VALUE
                    var hi = -Float.MAX_VALUE
                    pts.forEach {
                        val v = ref.fbm3(it, octaves.toFloat(), lacunarity, gain)
                        assertTrue(
                            v >= 0f && v < 1f,
                            "fbm3 octaves=$octaves gain=$gain lac=$lacunarity produced $v, outside [0,1)"
                        )
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                    // One octave is raw value noise and must span nearly the whole interval; the
                    // more octaves are averaged the more the extremes concentrate, which is
                    // correct fractal behaviour, not a normalisation failure.
                    if (octaves == 1) {
                        assertTrue(lo < 0.05f && hi > 0.95f, "fbm3 with 1 octave only covered $lo..$hi")
                    }
                }
            }
        }
    }

    @Test
    fun `fbm3 is centred on one half, unlike the 2D fbm`() {
        // The whole point of dividing by the accumulated amplitude: `fbm3 0.5 -` is an unbiased
        // signed field, so a force built from it averages out instead of integrating into a drift.
        val pts = samples(40000, spread = 80f, seed = 5150)
        val mean = pts.sumOf { ref.fbm3(it).toDouble() } / pts.size
        val legacyMean = pts.sumOf { legacyFbm2(it.x, it.y).toDouble() } / pts.size
        println("mean of fbm3: ${"%.5f".format(mean)}   mean of legacy 2D fbm: ${"%.5f".format(legacyMean)}")

        assertTrue(abs(mean - 0.5) < 0.02, "fbm3's mean is $mean, not centred on 0.5")
        // And the legacy one demonstrably is not -- this is the bias the user compensated by hand.
        assertTrue(
            legacyMean < 0.49,
            "the legacy 2D fbm was expected to sit below 0.5 because of its 0.96875 ceiling, but its mean was $legacyMean"
        )
    }

    @Test
    fun `fbm4 stays in the unit interval and moves in w`() {
        val rng = Random(2024)
        val pts = List(3000) {
            NoiseVec4(
                (rng.nextFloat() - 0.5f) * 40f, (rng.nextFloat() - 0.5f) * 40f,
                (rng.nextFloat() - 0.5f) * 40f, (rng.nextFloat() - 0.5f) * 40f
            )
        }
        pts.forEach {
            val v = ref.fbm4(it)
            assertTrue(v >= 0f && v < 1f, "fbm4($it) = $v escaped [0,1)")
        }
        // w must be a real fourth dimension: a step in w has to change the field about as much as
        // a step in x. If w were being folded in weakly, this is where it would show.
        val dx = pts.map { ref.fbm4(NoiseVec4(it.x + 1f, it.y, it.z, it.w)) - ref.fbm4(it) }
        val dw = pts.map { ref.fbm4(NoiseVec4(it.x, it.y, it.z, it.w + 1f)) - ref.fbm4(it) }
        val ratio = rms(dw) / rms(dx)
        println("fbm4 w-sensitivity / x-sensitivity: ${"%.4f".format(ratio)}")
        assertTrue(ratio > 0.5f && ratio < 2.0f, "fbm4's w axis is not carrying its weight: ratio $ratio")
    }

    @Test
    fun `octave count clamps rather than erroring`() {
        assertEquals(1, ref.octaveCount(0f))
        assertEquals(1, ref.octaveCount(-5f))
        assertEquals(1, ref.octaveCount(1.9f), "GLSL's int() truncates; the reference must too")
        assertEquals(5, ref.octaveCount(5f))
        assertEquals(K.MAX_OCTAVES, ref.octaveCount(999f))
    }

    @Test
    fun `the per-octave rotation is orthonormal`() {
        // If it is not, it rescales as well as rotates, and the effective lacunarity stops being
        // the lacunarity the caller asked for.
        val c = K.OCTAVE_ROTATION_COLUMNS
        for (i in 0..2) {
            val len = c[i].length
            assertEquals(1.0, len.toDouble(), 1e-6, "octave rotation column $i is not unit length")
        }
        fun dot(a: NoiseVec3, b: NoiseVec3) = a.x * b.x + a.y * b.y + a.z * b.z
        assertEquals(0.0, dot(c[0], c[1]).toDouble(), 1e-6, "columns 0 and 1 are not orthogonal")
        assertEquals(0.0, dot(c[0], c[2]).toDouble(), 1e-6, "columns 0 and 2 are not orthogonal")
        assertEquals(0.0, dot(c[1], c[2]).toDouble(), 1e-6, "columns 1 and 2 are not orthogonal")
    }

    // ---------------------------------------------------------------------------------------
    // curl3 -- the payoff word.
    // ---------------------------------------------------------------------------------------

    /** Discrete divergence of an arbitrary vector field, central differences at step [e]. */
    private fun divergence(e: Float, p: NoiseVec3, field: (NoiseVec3) -> NoiseVec3): Float {
        val inv = 1.0f / (2.0f * e)
        val dx = (field(p + NoiseVec3(e, 0f, 0f)) - field(p - NoiseVec3(e, 0f, 0f))) * inv
        val dy = (field(p + NoiseVec3(0f, e, 0f)) - field(p - NoiseVec3(0f, e, 0f))) * inv
        val dz = (field(p + NoiseVec3(0f, 0f, e)) - field(p - NoiseVec3(0f, 0f, e))) * inv
        return dx.x + dy.y + dz.z
    }

    @Test
    fun `curl3 is a live field, not a degenerate zero one`() {
        // Guarding the guard. A field that is identically zero has zero divergence, so the
        // divergence test below would pass gloriously on a completely broken word. This test
        // exists so that cannot happen: the field has to be BIG before being solenoidal means
        // anything.
        val pts = samples(2000, spread = 30f, seed = 606)
        val mags = pts.map { ref.curl3(it).length }
        val meanMag = mags.average().toFloat()
        val maxMag = mags.max()
        println("curl3 magnitude over 2000 samples: mean=${"%.4f".format(meanMag)} max=${"%.4f".format(maxMag)}")
        assertTrue(meanMag > 0.5f, "curl3's mean magnitude is only $meanMag; the field is not alive")
        assertTrue(maxMag > 1.0f, "curl3's peak magnitude is only $maxMag")
        // And it must not be a constant field either -- a constant is also divergence-free.
        val spreadOfX = rms(pts.map { ref.curl3(it).x - mags.average().toFloat() })
        assertTrue(spreadOfX > 0.1f, "curl3's x component barely varies ($spreadOfX); it may be constant")
    }

    @Test
    fun `curl3 is divergence-free to floating point, and a generic field is not`() {
        // curl3 takes central differences of a vector potential at ONE shared epsilon. The same
        // shift operator applied twice commutes, so the six terms of div(curl) cancel exactly in
        // pairs -- this is not an O(e^2) approximation, it is an identity of the discrete
        // operator, and only rounding stops it being 0.0 on the nose.
        //
        // The control is the vector potential itself, an ordinary fbm3 vector field of the same
        // magnitude and roughness. Without the ratio the absolute number means nothing.
        val e = K.DEFAULT_CURL_EPSILON
        val pts = samples(600, spread = 25f, seed = 8080)

        val curlDiv = pts.map { abs(divergence(e, it) { q -> ref.curl3(q, e) }) }
        val controlDiv = pts.map {
            abs(divergence(e, it) { q -> ref.potential3(q, K.DEFAULT_OCTAVES, K.DEFAULT_LACUNARITY, K.DEFAULT_GAIN) })
        }

        val curlRms = rms(curlDiv)
        val controlRms = rms(controlDiv)
        val ratio = controlRms / curlRms
        val curlMag = rms(pts.map { ref.curl3(it, e).length })
        val relative = curlRms / curlMag

        println(
            "divergence RMS -- curl3: ${"%.6f".format(curlRms)},  generic fbm3 field: ${"%.6f".format(controlRms)},  " +
                "ratio: ${"%.1f".format(ratio)}x,  curl3 |div|/|field|: ${"%.6f".format(relative)}"
        )

        // MEASURED: ratio about 2,377,880x and |div|/|field| about 3.4e-7 -- that is the commuting
        // -shift identity holding to float rounding, not a numerical coincidence. The bounds keep
        // two orders of headroom, which is still tight enough to catch the obvious regression:
        // taking the two differences at different epsilons drops the ratio to single digits.
        assertTrue(
            ratio > 10000f,
            "curl3's divergence ($curlRms) should be orders of magnitude below a generic field's " +
                "($controlRms), but the ratio was only ${ratio}x"
        )
        assertTrue(
            relative < 1e-4f,
            "curl3's divergence is $relative of its own magnitude; that is not divergence-free"
        )
    }

    @Test
    fun `curl3 responds to its epsilon and octave knobs`() {
        val p = NoiseVec3(3.7f, -8.2f, 5.1f)
        val a = ref.curl3(p, 0.05f, 5f)
        val b = ref.curl3(p, 0.20f, 5f)
        val c = ref.curl3(p, 0.05f, 2f)
        assertTrue((a - b).length > 1e-3f, "curl3 ignored its epsilon: $a vs $b")
        assertTrue((a - c).length > 1e-3f, "curl3 ignored its octave count: $a vs $c")
    }

    // ---------------------------------------------------------------------------------------
    // worley3.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `worley3 packs F1 F2 and a cell id, with F1 never above F2`() {
        val pts = samples(20000, spread = 50f, seed = 1618)
        var maxF1 = 0f
        var minGap = Float.MAX_VALUE
        pts.forEach {
            val w = ref.worley3(it)
            assertTrue(w.x >= 0f, "worley3 F1 is negative: ${w.x}")
            assertTrue(w.y >= w.x, "worley3 F2 (${w.y}) is below F1 (${w.x}) at $it")
            assertEquals(w.y - w.x, w.z, 1e-5f, "worley3 .z must be exactly F2 - F1")
            assertTrue(w.z >= 0f, "the crack channel went negative: ${w.z}")
            assertTrue(w.w >= 0f && w.w < 1f, "worley3 cell id ${w.w} is outside [0,1)")
            if (w.x > maxF1) maxF1 = w.x
            if (w.z < minGap) minGap = w.z
        }
        println("worley3 over 20000 samples: max F1 = ${"%.4f".format(maxF1)}, min (F2-F1) = ${"%.6f".format(minGap)}")
        // A 3x3x3 search is exact while F1 stays below 1; if F1 routinely exceeded that the
        // neighbourhood would be too small and cells would visibly pop.
        assertTrue(maxF1 < 1.2f, "worley3's F1 reached $maxF1; the 3x3x3 neighbourhood is too small")
        assertTrue(maxF1 > 0.5f, "worley3's F1 never got above $maxF1; the feature points are not spread out")
        // The crack channel must actually approach zero somewhere, or it is not a crack field.
        assertTrue(minGap < 0.01f, "worley3's F2-F1 never approached zero (min $minGap); no cell walls")
    }

    @Test
    fun `worley3 cells are flat - the cell id is constant within a cell`() {
        // .w is meant for flat-shading a cell. Two nearby points in the same cell must agree.
        val base = NoiseVec3(10.5f, 4.5f, -3.5f)
        val a = ref.worley3(base)
        val b = ref.worley3(base + NoiseVec3(0.01f, 0.01f, 0.01f))
        assertEquals(a.w, b.w, 1e-6f, "the worley3 cell id is not constant across a cell interior")
    }

    // ---------------------------------------------------------------------------------------
    // fbmd3 and warp3.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `fbmd3 returns fbm3 and its actual gradient`() {
        val pts = samples(400, spread = 20f, seed = 246)
        var worstValue = 0f
        var worstCos = 1f
        pts.forEach { p ->
            val d = ref.fbmd3(p)
            worstValue = maxOf(worstValue, abs(d.x - ref.fbm3(p)))
            // Compare the packed gradient against an independently taken one at a different step.
            val e = 0.01f
            val gx = (ref.fbm3(p + NoiseVec3(e, 0f, 0f)) - ref.fbm3(p - NoiseVec3(e, 0f, 0f))) / (2 * e)
            val gy = (ref.fbm3(p + NoiseVec3(0f, e, 0f)) - ref.fbm3(p - NoiseVec3(0f, e, 0f))) / (2 * e)
            val gz = (ref.fbm3(p + NoiseVec3(0f, 0f, e)) - ref.fbm3(p - NoiseVec3(0f, 0f, e))) / (2 * e)
            val a = NoiseVec3(d.y, d.z, d.w)
            val b = NoiseVec3(gx, gy, gz)
            if (a.length > 0.2f && b.length > 0.2f) {
                val cos = (a.x * b.x + a.y * b.y + a.z * b.z) / (a.length * b.length)
                if (cos < worstCos) worstCos = cos
            }
        }
        println("fbmd3: worst |value - fbm3| = $worstValue, worst gradient cosine vs an independent stencil = ${"%.4f".format(worstCos)}")
        assertEquals(0f, worstValue, 1e-6f, "fbmd3's .x is not exactly fbm3")
        assertTrue(worstCos > 0.7f, "fbmd3's gradient points the wrong way somewhere (worst cosine $worstCos)")
    }

    @Test
    fun `warp3 stays normalised but is not the same field as fbm3`() {
        val pts = samples(5000, spread = 30f, seed = 1357)
        var differences = 0
        val deltas = mutableListOf<Float>()
        pts.forEach {
            val w = ref.warp3(it)
            assertTrue(w >= 0f && w < 1f, "warp3($it) = $w escaped [0,1)")
            val d = w - ref.fbm3(it)
            deltas += d
            if (abs(d) > 0.05f) differences++
        }
        // Measured: 1876/5000 (37.5%) differ by more than 0.05, RMS difference about 0.09 --
        // which is what a displacement of up to +-0.6 should do to a field whose RMS slope is
        // about 0.145 per unit. The bounds are that measurement with headroom on both sides.
        println("warp3 vs fbm3: $differences / ${pts.size} differ by >0.05, rms difference ${"%.4f".format(rms(deltas))}")
        assertTrue(differences > pts.size / 4, "warp3 barely differs from fbm3; the domain warp is not being applied")
        assertTrue(rms(deltas) > 0.05f, "warp3's RMS departure from fbm3 is only ${rms(deltas)}")
    }

    // ---------------------------------------------------------------------------------------
    // The Kotlin reference and the emitted GLSL must not drift apart.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every shared constant appears in the emitted GLSL`() {
        // The reference and the shader are written twice, in two languages. This is the seam
        // where they can silently disagree, so every number the Kotlin side uses has to be
        // findable in the text the GPU is given.
        val glsl = volumetricNoiseHelperSource("t", volumetricNoiseHelperDependencies.keys)
        // The epsilons and the warp strength are not baked into the helpers -- they are argument
        // DEFAULTS that the compiler injects at the call site, so they are checked against real
        // compiled programs further down instead.
        val callSiteDefaults = setOf("DEFAULT_CURL_EPSILON", "DEFAULT_GRADIENT_EPSILON", "DEFAULT_WARP_STRENGTH")
        val missing = K.allScalars().filterKeys { it !in callSiteDefaults }.filter { (_, v) ->
            val formatted = String.format(java.util.Locale.US, "%.7f", v).trimEnd('0').trimEnd('.')
            formatted !in glsl
        }
        assertTrue(missing.isEmpty(), "these constants never reached the shader: ${missing.keys}")

        listOf(K.OCTAVE_OFFSET, K.FIELD_OFFSET_B, K.FIELD_OFFSET_C).forEach { v ->
            listOf(v.x, v.y, v.z).forEach { c ->
                if (c != 0f) {
                    val formatted = String.format(java.util.Locale.US, "%.7f", c).trimEnd('0').trimEnd('.')
                    assertTrue(formatted in glsl, "vector constant component $formatted never reached the shader")
                }
            }
        }
        K.OCTAVE_ROTATION_COLUMNS.forEach { col ->
            listOf(col.x, col.y, col.z).filter { it != 0f }.forEach { c ->
                val formatted = String.format(java.util.Locale.US, "%.7f", c).trimEnd('0').trimEnd('.')
                assertTrue(formatted in glsl, "rotation component $formatted never reached the shader")
            }
        }
    }

    @Test
    fun `the call-site defaults reach the compiled program`() {
        // A default that never arrives is the quietest possible bug: the word still compiles, and
        // the shader silently uses whatever GLSL's zero-initialisation gives it.
        val curl = compileOk("input curl3").glsl
        assertTrue(", ${K.DEFAULT_CURL_EPSILON}" in curl || "vec4(${K.DEFAULT_CURL_EPSILON})" in curl,
            "curl3's default epsilon ${K.DEFAULT_CURL_EPSILON} is not in the emitted call")
        val fbmd = compileOk("input fbmd3").glsl
        assertTrue("vec4(${K.DEFAULT_GRADIENT_EPSILON})" in fbmd || ", ${K.DEFAULT_GRADIENT_EPSILON}" in fbmd,
            "fbmd3's default epsilon ${K.DEFAULT_GRADIENT_EPSILON} is not in the emitted call")
        val warp = compileOk("input warp3").glsl
        assertTrue("vec4(${K.DEFAULT_WARP_STRENGTH})" in warp || ", ${K.DEFAULT_WARP_STRENGTH}" in warp,
            "warp3's default strength ${K.DEFAULT_WARP_STRENGTH} is not in the emitted call")
        val fbm3 = compileOk("input fbm3").glsl
        assertTrue("vec4(${K.DEFAULT_OCTAVES})" in fbm3, "fbm3's default octave count is not in the emitted call")
        assertTrue("vec4(${K.DEFAULT_LACUNARITY})" in fbm3, "fbm3's default lacunarity is not in the emitted call")
        assertTrue("vec4(${K.DEFAULT_GAIN})" in fbm3, "fbm3's default gain is not in the emitted call")
    }

    @Test
    fun `helpers are emitted only when reached, with their dependencies`() {
        // curl3 alone drags in potential3, fbm3, noise3 and hash31 -- eighteen fbm3 calls of
        // shader text. A program that only says `noise3` must not pay for any of it.
        val small = volumetricNoiseHelperSource("t", setOf("noise3"))
        assertTrue("t_hash31" in small, "noise3 did not pull in its hash")
        assertTrue("t_noise3" in small)
        assertTrue("t_fbm3" !in small, "noise3 alone should not emit fbm3")
        assertTrue("t_curl3" !in small)
        assertTrue("t_worley3" !in small)

        val curl = volumetricNoiseHelperSource("t", setOf("curl3"))
        listOf("t_hash31", "t_noise3", "t_fbm3", "t_potential3", "t_curl3").forEach {
            assertTrue(it in curl, "curl3's transitive dependency $it was not emitted")
        }
        // Declaration order, so a function is always defined before it is called.
        val order = listOf("float t_hash31", "float t_noise3", "float t_fbm3", "vec3 t_potential3", "vec3 t_curl3")
        var at = -1
        order.forEach {
            val i = curl.indexOf(it)
            assertTrue(i > at, "$it is emitted out of dependency order")
            at = i
        }

        assertEquals("", volumetricNoiseHelperSource("t", emptySet()), "an empty helper set must emit nothing")
        assertEquals("", volumetricNoiseHelperSource("t", setOf("noSuchHelper")))
    }

    // ---------------------------------------------------------------------------------------
    // The compiler: the words must actually lower, and lower to 3D.
    // ---------------------------------------------------------------------------------------

    private fun compileOk(source: String): StackSLCompileResult {
        val r = compileStackSL(source)
        assertTrue(
            r.ok,
            "`$source` did not compile: " + r.diagnostics.filter { it.severity == StackSLSeverity.ERROR }
        )
        return r
    }

    @Test
    fun `each new word lowers to its helper and consumes exactly one value by default`() {
        val expectations = mapOf(
            "noise3" to "stacksl_noise3(",
            "noise4" to "stacksl_noise4(",
            "fbm3" to "stacksl_fbm3(",
            "fbm4" to "stacksl_fbm4(",
            "curl3" to "stacksl_curl3(",
            "fbmd3" to "stacksl_fbmd3(",
            "warp3" to "stacksl_warp3(",
            "worley3" to "stacksl_worley3(",
            "voronoi3" to "stacksl_worley3("
        )
        // The baseline is the EXISTING unary noise word, not a magic number: a StackSL root
        // carries one implicit value, so `input fbm` finishes at depth 2 rather than 1. Comparing
        // against `fbm` states the actual invariant -- "these behave like the word they replace" --
        // and cannot rot if that baseline ever changes.
        val baseline = compileOk("input fbm").analysis.functions.single { it.name == "<root>" }
        assertEquals(0, baseline.prunedValues, "the `fbm` baseline itself prunes; this test's premise is stale")

        expectations.forEach { (word, helper) ->
            val r = compileOk("input $word")
            assertTrue(helper in r.glsl, "`input $word` did not emit $helper")
            val root = r.analysis.functions.single { it.name == "<root>" }
            assertEquals(
                baseline.finalStackDepth, root.finalStackDepth,
                "`input $word` left the stack at ${root.finalStackDepth}, but `input fbm` leaves it at " +
                    "${baseline.finalStackDepth}; the word is not unary"
            )
            assertEquals(
                0, root.prunedValues,
                "`input $word` pruned ${root.prunedValues} value(s) -- it is consuming the wrong arity, " +
                    "which is exactly how a raw coordinate ends up where a noise sample should be"
            )
        }
    }

    @Test
    fun `the volumetric words do not carry the 2D shear`() {
        // The regression test for the actual reported defect, at the level of emitted text.
        val legacy = compileOk("input fbm").glsl
        assertTrue(".zw * 0.07" in legacy, "the 2D fbm's lowering changed; this test's premise is stale")

        listOf("noise3", "fbm3", "curl3", "fbmd3", "warp3", "worley3").forEach { word ->
            val glsl = compileOk("input $word").glsl
            assertTrue(
                "0.07" !in glsl,
                "`$word` emitted the 2D shear constant 0.07; it is not reading a real 3D coordinate"
            )
            assertTrue("stacksl_in.xyz" in glsl, "`$word` should read input.xyz, but did not")
        }
        // The 4D words take the whole vector, so they must NOT swizzle it down.
        val glsl4 = compileOk("input fbm4").glsl
        assertTrue("stacksl_fbm4(stacksl_in," in glsl4, "fbm4 should take the whole vec4")
    }

    @Test
    fun `arity variants consume what they say they consume`() {
        // The trap the base commit is about: a word that quietly eats the wrong number of stack
        // values leaves a raw coordinate where a noise sample should be. `fbm3.3` must take three.
        // `pulse` is the existing three-argument word, so it is the honest baseline for what a
        // balanced arity-3 program looks like.
        val pulseBaseline = compileOk("input 0.5 0.1 pulse.3").analysis.functions.single { it.name == "<root>" }
        val r = compileOk("input 4 2.1 fbm3.3")
        val root = r.analysis.functions.single { it.name == "<root>" }
        assertEquals(
            pulseBaseline.finalStackDepth, root.finalStackDepth,
            "fbm3.3 finished at depth ${root.finalStackDepth} where the equivalent `pulse.3` finishes at " +
                "${pulseBaseline.finalStackDepth}"
        )
        assertEquals(0, root.prunedValues, "fbm3.3 pruned values, so it did not consume its three arguments")

        // Bare `fbm3` takes ONE, leaving the two extra values behind rather than swallowing them.
        // Silent swallowing is the failure mode that cost a debugging session on 2026-08-29.
        val bare = compileOk("input 4 2.1 fbm3").analysis.functions.single { it.name == "<root>" }
        assertTrue(
            bare.finalStackDepth > root.finalStackDepth,
            "bare fbm3 should be unary and leave the other two values on the stack, but it finished at " +
                "${bare.finalStackDepth}, the same as the arity-3 form"
        )

        // An arity the word does not offer must be a diagnostic, not silence.
        val bad = compileStackSL("input fbm3.9")
        assertTrue(
            bad.diagnostics.any { it.severity == StackSLSeverity.ERROR && "arity" in it.message.lowercase() },
            "fbm3.9 should have been rejected, but the diagnostics were ${bad.diagnostics}"
        )
    }

    @Test
    fun `the new words are reserved, so a definition cannot shadow them`() {
        val r = compileStackSL(": curl3 input ; input curl3")
        assertTrue(
            r.diagnostics.any { it.severity == StackSLSeverity.ERROR && "shadows" in it.message },
            "defining `curl3` should collide with the built-in, but did not: ${r.diagnostics}"
        )
    }

    @Test
    fun `a program can mix the family with the rest of the language`() {
        // The shape the dust_two workspace actually wants: a three-channel divergence-free force.
        val r = compileOk("input 2.2 * curl3 xyz 0.4 *")
        assertTrue("stacksl_curl3(" in r.glsl)
        // And the packed words swizzle like anything else.
        val w = compileOk("input 6 * worley3 z 1- 8 pow")
        assertTrue("stacksl_worley3(" in w.glsl)
    }

    @Test
    fun reportMeasurements() {
        // Not an assertion -- a printed census, so the numbers behind the bounds above are visible
        // in the test log rather than only in a commit message.
        val pts = samples(2000, spread = 30f, seed = 4321)
        val e = K.DEFAULT_CURL_EPSILON
        println("---- volumetric noise census ----")
        println("  fbm3 mean            ${"%.5f".format(pts.map { ref.fbm3(it) }.average())}")
        println("  fbm3 rms slope/unit  ${"%.5f".format(rms(pts.map { ref.fbm3(NoiseVec3(it.x + 1f, it.y, it.z)) - ref.fbm3(it) }))}")
        println("  curl3 mean |v|       ${"%.5f".format(pts.map { ref.curl3(it, e).length }.average())}")
        println("  curl3 rms div        ${"%.8f".format(rms(pts.map { divergence(e, it) { q -> ref.curl3(q, e) } }))}")
        println("  worley3 mean F1      ${"%.5f".format(pts.map { ref.worley3(it).x }.average())}")
        println("  worley3 mean F2-F1   ${"%.5f".format(pts.map { ref.worley3(it).z }.average())}")
        println("  emitted GLSL, all helpers: ${volumetricNoiseHelperSource("stacksl", volumetricNoiseHelperDependencies.keys).lines().size} lines")
    }
}
