package afield.stacksl

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// StackSL's volumetric noise family: noise3 fbm3 fbmd3 warp3 curl3 worley3 noise4 fbm4.
//
// WHY A SEPARATE FAMILY, AND NOT A THIRD ARGUMENT TO `noise`.
//
// StackSL's original `noise` and `fbm` are two-dimensional. The word lowers to
// `_noise(input.xy + input.zw * 0.07)`, so a vec4 carrying a position and a time is squashed into
// a plane: z enters at one fourteenth the weight of x, as a SHEAR rather than as a third
// dimension. On a volume seen down z that reads as one fixed pattern imprinted at every depth.
// The two-slice-sum workaround (f(x,y) + f(y,z) + f(z,x)) is what people write instead, and it
// costs three fbms and does not actually give a 3D field — it gives three sheared 2D ones.
//
// THREE THINGS THIS FAMILY DOES DIFFERENTLY, EACH OF WHICH IS A BUG IN THE 2D WORDS.
//
// 1. The range really is [0,1). `_fbm(vec2)` sums five octaves at amplitudes 0.5 0.25 0.125
//    0.0625 0.03125, which total 0.96875, not 1 — so the usual `0.5 -` recentering leaves a
//    constant bias of about -0.031. A constant force integrates into a drift instead of
//    averaging out. Here the amplitude sum is ACCUMULATED at runtime and divided out, so the
//    range is exactly [0,1) for any octave count, lacunarity and gain. The bug cannot recur
//    because nothing is hard-coded to be recomputed.
//
// 2. The hashes contain no `sin()`. `_hash11`/`_hash21` are `fract(sin(x) * 43758.5453)`, and
//    `sin` is not bit-portable: its precision is implementation-defined, it loses all meaning
//    for large arguments, and no CPU implementation can be trusted to agree with a GPU's. The
//    hashes below are multiply/add/fract only, which are IEEE-754 exact operations. That is what
//    makes [VolumetricNoiseReference] a real reference and not an approximation, and it is why
//    this family could not have been built on the existing `_hash21`.
//
// 3. `curl3` is divergence-free by construction, not approximately. It is the curl of a vector
//    potential taken with central differences at ONE shared epsilon. Discrete shift operators
//    commute, so the discrete divergence of the discrete curl cancels term for term and is zero
//    up to float rounding — see [VolumetricNoiseTest]. Particles advected through it swirl
//    instead of piling into sinks, which is the entire reason to reach for curl noise.
//
// THE CONSTANTS BELOW ARE THE SINGLE SOURCE OF TRUTH. The Kotlin reference reads them and the
// emitted GLSL interpolates them, so the two cannot drift apart in their numbers.
// `VolumetricNoiseTest.glsl constants are the Kotlin constants` fails if a literal is ever typed
// into the shader text by hand.
// ---------------------------------------------------------------------------------------------

/** A float triple. Deliberately `Float`, not `Double`: GLSL `float` is what is being mirrored. */
data class NoiseVec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: NoiseVec3) = NoiseVec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: NoiseVec3) = NoiseVec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = NoiseVec3(x * s, y * s, z * s)
    val length: Float get() = sqrt(x * x + y * y + z * z)
}

/** A float quadruple, for the 4D words and for the packed `worley3` result. */
data class NoiseVec4(val x: Float, val y: Float, val z: Float, val w: Float)

object VolumetricNoiseConstants {
    /** Loop bound in the emitted GLSL. An octave request above this is clamped, not an error. */
    const val MAX_OCTAVES = 12

    const val DEFAULT_OCTAVES = 5.0f
    const val DEFAULT_LACUNARITY = 2.0f
    const val DEFAULT_GAIN = 0.5f

    /**
     * `curl3`'s finite-difference step, in the same units as the input coordinate. Large enough
     * that `(f(p+e) - f(p-e)) / 2e` is not dominated by float cancellation, small enough that the
     * stencil still resolves the finest octave. At the default lacunarity the fifth octave has a
     * feature size around 1/16, so 0.05 straddles roughly a third of it.
     */
    const val DEFAULT_CURL_EPSILON = 0.05f

    /** `fbmd3`'s gradient step. Same trade-off, one field rather than three. */
    const val DEFAULT_GRADIENT_EPSILON = 0.02f

    /** `warp3`'s displacement strength, in input units. */
    const val DEFAULT_WARP_STRENGTH = 0.6f

    /**
     * Added before every hash so the origin is not a fixed point. Without it
     * `hash31(0,0,0)` is exactly 0 — `fract(0 * 0.1031)` is 0, the dot product of zero with
     * anything is 0, and `fract((0 + 0) * 0)` is 0 — which puts a black dot on the origin of
     * every field. The offset is non-integral, so no lattice corner can ever land on the new
     * fixed point at `-HASH_SEED`.
     */
    const val HASH_SEED = 0.1731f

    // Hoskins-style hash multipliers. Nearly-but-not-quite equal, and none a ratio of the others.
    const val HASH3_SCALE_X = 0.1031f
    const val HASH3_SCALE_Y = 0.1030f
    const val HASH3_SCALE_Z = 0.0973f
    const val HASH4_SCALE_W = 0.1099f
    const val HASH31_ADD = 31.32f
    const val HASH33_ADD = 33.33f
    const val HASH41_ADD = 33.33f

    /**
     * Per-octave rotation. Orthonormal (asserted in the tests), so it changes the direction of
     * the lattice without changing the scale — the lacunarity does all the scaling. Without a
     * rotation, every octave's lattice lines up with the last and the sum shows axis-aligned
     * grid streaks. Stored as COLUMNS, which is what GLSL's `mat3(...)` constructor takes.
     */
    val OCTAVE_ROTATION_COLUMNS: Array<NoiseVec3> = arrayOf(
        NoiseVec3(0.00f, -0.80f, -0.60f),
        NoiseVec3(0.80f, 0.36f, -0.48f),
        NoiseVec3(0.60f, -0.48f, 0.64f)
    )

    /** Translation applied after each octave's rotate-and-scale, to decorrelate the lattices. */
    val OCTAVE_OFFSET = NoiseVec3(17.17f, 9.53f, -23.71f)
    const val OCTAVE_OFFSET_W = 11.31f

    /**
     * The three offsets that turn one scalar fbm into a vector potential for `curl3`, and that
     * separate `warp3`'s three displacement fields. Far apart and non-integral, so the three
     * components are decorrelated samples of the same field rather than three views of one blob.
     */
    val FIELD_OFFSET_A = NoiseVec3(0.0f, 0.0f, 0.0f)
    val FIELD_OFFSET_B = NoiseVec3(31.416f, 17.234f, -9.871f)
    val FIELD_OFFSET_C = NoiseVec3(-63.219f, 45.128f, 71.653f)

    /** Every float literal that must appear in the emitted GLSL, for the anti-drift test. */
    fun allScalars(): Map<String, Float> = linkedMapOf(
        "HASH_SEED" to HASH_SEED,
        "HASH3_SCALE_X" to HASH3_SCALE_X,
        "HASH3_SCALE_Y" to HASH3_SCALE_Y,
        "HASH3_SCALE_Z" to HASH3_SCALE_Z,
        "HASH4_SCALE_W" to HASH4_SCALE_W,
        "HASH31_ADD" to HASH31_ADD,
        "HASH33_ADD" to HASH33_ADD,
        "OCTAVE_OFFSET_W" to OCTAVE_OFFSET_W,
        "DEFAULT_CURL_EPSILON" to DEFAULT_CURL_EPSILON,
        "DEFAULT_GRADIENT_EPSILON" to DEFAULT_GRADIENT_EPSILON,
        "DEFAULT_WARP_STRENGTH" to DEFAULT_WARP_STRENGTH
    )
}

/**
 * The CPU twin of the GLSL emitted by [volumetricNoiseHelperSource]. Every function here is a
 * statement-for-statement transliteration of its shader counterpart, in `Float`, using only
 * operations GLSL and the JVM both round identically. It exists so the family's semantics —
 * ranges, continuity, `worley3`'s F1 <= F2, `curl3`'s divergence — can be asserted without a
 * device, which is the only kind of test StackSL has ever been able to have.
 *
 * It is a SEMANTIC reference, not a bit-exact GPU emulator: a driver is free to contract a
 * multiply-add into an FMA and change the last bit or two. Nothing here depends on the last bit.
 */
object VolumetricNoiseReference {
    private val K = VolumetricNoiseConstants

    private fun fract(v: Float): Float = v - floor(v)
    private fun fract(v: NoiseVec3) = NoiseVec3(fract(v.x), fract(v.y), fract(v.z))
    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun smooth(f: Float): Float = f * f * (3.0f - 2.0f * f)

    /** vec3 -> [0,1). Multiply/add/fract only; see the header on why that matters. */
    fun hash31(px: Float, py: Float, pz: Float): Float {
        var x = fract((px + K.HASH_SEED) * K.HASH3_SCALE_X)
        var y = fract((py + K.HASH_SEED) * K.HASH3_SCALE_Y)
        var z = fract((pz + K.HASH_SEED) * K.HASH3_SCALE_Z)
        val d = x * (z + K.HASH31_ADD) + y * (y + K.HASH31_ADD) + z * (x + K.HASH31_ADD)
        x += d
        y += d
        z += d
        return fract((x + y) * z)
    }

    /** vec3 -> three independent values in [0,1), for `worley3`'s feature-point jitter. */
    fun hash33(px: Float, py: Float, pz: Float): NoiseVec3 {
        var x = fract((px + K.HASH_SEED) * K.HASH3_SCALE_X)
        var y = fract((py + K.HASH_SEED) * K.HASH3_SCALE_Y)
        var z = fract((pz + K.HASH_SEED) * K.HASH3_SCALE_Z)
        val d = x * (y + K.HASH33_ADD) + y * (x + K.HASH33_ADD) + z * (z + K.HASH33_ADD)
        x += d
        y += d
        z += d
        return NoiseVec3(fract((x + y) * z), fract((x + x) * y), fract((y + x) * x))
    }

    /** vec4 -> [0,1). */
    fun hash41(px: Float, py: Float, pz: Float, pw: Float): Float {
        var x = fract((px + K.HASH_SEED) * K.HASH3_SCALE_X)
        var y = fract((py + K.HASH_SEED) * K.HASH3_SCALE_Y)
        var z = fract((pz + K.HASH_SEED) * K.HASH3_SCALE_Z)
        var w = fract((pw + K.HASH_SEED) * K.HASH4_SCALE_W)
        val d = x * (w + K.HASH41_ADD) + y * (z + K.HASH41_ADD) +
            z * (x + K.HASH41_ADD) + w * (y + K.HASH41_ADD)
        x += d
        y += d
        z += d
        w += d
        return fract((x + y) * (z + w))
    }

    /**
     * Trilinearly interpolated value noise on the integer lattice, smoothstep-faded. The result
     * is a convex combination of eight hashes each in [0,1), so it is in [0,1) exactly — no
     * scaling, no bias, nothing to get wrong.
     */
    fun noise3(p: NoiseVec3): Float {
        val ix = floor(p.x)
        val iy = floor(p.y)
        val iz = floor(p.z)
        val ux = smooth(p.x - ix)
        val uy = smooth(p.y - iy)
        val uz = smooth(p.z - iz)
        val c000 = hash31(ix, iy, iz)
        val c100 = hash31(ix + 1f, iy, iz)
        val c010 = hash31(ix, iy + 1f, iz)
        val c110 = hash31(ix + 1f, iy + 1f, iz)
        val c001 = hash31(ix, iy, iz + 1f)
        val c101 = hash31(ix + 1f, iy, iz + 1f)
        val c011 = hash31(ix, iy + 1f, iz + 1f)
        val c111 = hash31(ix + 1f, iy + 1f, iz + 1f)
        val z0 = mix(mix(c000, c100, ux), mix(c010, c110, ux), uy)
        val z1 = mix(mix(c001, c101, ux), mix(c011, c111, ux), uy)
        return mix(z0, z1, uz)
    }

    /** The 4D lattice, 16 corners. `w` is the animation axis when you want a volume that moves. */
    fun noise4(p: NoiseVec4): Float {
        val ix = floor(p.x)
        val iy = floor(p.y)
        val iz = floor(p.z)
        val iw = floor(p.w)
        val ux = smooth(p.x - ix)
        val uy = smooth(p.y - iy)
        val uz = smooth(p.z - iz)
        val uw = smooth(p.w - iw)
        fun slice(dw: Float): Float {
            val c000 = hash41(ix, iy, iz, iw + dw)
            val c100 = hash41(ix + 1f, iy, iz, iw + dw)
            val c010 = hash41(ix, iy + 1f, iz, iw + dw)
            val c110 = hash41(ix + 1f, iy + 1f, iz, iw + dw)
            val c001 = hash41(ix, iy, iz + 1f, iw + dw)
            val c101 = hash41(ix + 1f, iy, iz + 1f, iw + dw)
            val c011 = hash41(ix, iy + 1f, iz + 1f, iw + dw)
            val c111 = hash41(ix + 1f, iy + 1f, iz + 1f, iw + dw)
            val z0 = mix(mix(c000, c100, ux), mix(c010, c110, ux), uy)
            val z1 = mix(mix(c001, c101, ux), mix(c011, c111, ux), uy)
            return mix(z0, z1, uz)
        }
        return mix(slice(0f), slice(1f), uw)
    }

    /** `OCTAVE_ROTATION_COLUMNS` applied as GLSL applies a mat3: sum of columns times components. */
    fun rotateOctave(p: NoiseVec3): NoiseVec3 {
        val c = K.OCTAVE_ROTATION_COLUMNS
        return NoiseVec3(
            c[0].x * p.x + c[1].x * p.y + c[2].x * p.z,
            c[0].y * p.x + c[1].y * p.y + c[2].y * p.z,
            c[0].z * p.x + c[1].z * p.y + c[2].z * p.z
        )
    }

    /**
     * Fractal sum. The amplitude sum is accumulated into `norm` and divided out at the end, so
     * the range is exactly [0,1) whatever the octave count, lacunarity or gain — this is the
     * structural fix for the 2D `fbm`'s 0.96875 ceiling.
     */
    fun fbm3(
        p: NoiseVec3,
        octaves: Float = K.DEFAULT_OCTAVES,
        lacunarity: Float = K.DEFAULT_LACUNARITY,
        gain: Float = K.DEFAULT_GAIN
    ): Float {
        val n = octaveCount(octaves)
        var q = p
        var amp = 1.0f
        var sum = 0.0f
        var norm = 0.0f
        for (i in 0 until n) {
            sum += amp * noise3(q)
            norm += amp
            q = rotateOctave(q) * lacunarity + K.OCTAVE_OFFSET
            amp *= gain
        }
        return if (norm > 0.0f) sum / norm else 0.0f
    }

    fun fbm4(
        p: NoiseVec4,
        octaves: Float = K.DEFAULT_OCTAVES,
        lacunarity: Float = K.DEFAULT_LACUNARITY,
        gain: Float = K.DEFAULT_GAIN
    ): Float {
        val n = octaveCount(octaves)
        var q = p
        var amp = 1.0f
        var sum = 0.0f
        var norm = 0.0f
        for (i in 0 until n) {
            sum += amp * noise4(q)
            norm += amp
            val r = rotateOctave(NoiseVec3(q.x, q.y, q.z)) * lacunarity
            q = NoiseVec4(
                r.x + K.OCTAVE_OFFSET.x,
                r.y + K.OCTAVE_OFFSET.y,
                r.z + K.OCTAVE_OFFSET.z,
                q.w * lacunarity + K.OCTAVE_OFFSET_W
            )
            amp *= gain
        }
        return if (norm > 0.0f) sum / norm else 0.0f
    }

    /** GLSL clamps then truncates; mirror that exactly rather than rounding. */
    fun octaveCount(octaves: Float): Int =
        octaves.coerceIn(1.0f, K.MAX_OCTAVES.toFloat()).toInt()

    /**
     * The vector potential `curl3` differentiates: one scalar fbm read at three widely separated
     * offsets. Using one field three times rather than three fields is deliberate — it is
     * cheaper, and the offsets are far enough apart that the components are uncorrelated.
     */
    fun potential3(p: NoiseVec3, octaves: Float, lacunarity: Float, gain: Float) = NoiseVec3(
        fbm3(p + K.FIELD_OFFSET_A, octaves, lacunarity, gain),
        fbm3(p + K.FIELD_OFFSET_B, octaves, lacunarity, gain),
        fbm3(p + K.FIELD_OFFSET_C, octaves, lacunarity, gain)
    )

    /**
     * Curl of [potential3], central differences at one shared epsilon.
     *
     * The shared epsilon is the whole point. Written this way the operator is
     * `(D_y P_z - D_z P_y, D_z P_x - D_x P_z, D_x P_y - D_y P_x)` where each `D_i` is the same
     * central-difference shift operator. Shift operators commute, so applying the same `D_i`
     * again to take a divergence produces six terms that cancel in pairs and the result is
     * identically zero — not small, zero, up to float rounding. Mixing epsilons, or taking the
     * divergence with a different stencil, throws that away and leaves an O(e^2) residue.
     */
    fun curl3(
        p: NoiseVec3,
        epsilon: Float = K.DEFAULT_CURL_EPSILON,
        octaves: Float = K.DEFAULT_OCTAVES,
        lacunarity: Float = K.DEFAULT_LACUNARITY,
        gain: Float = K.DEFAULT_GAIN
    ): NoiseVec3 {
        val e = maxOf(abs(epsilon), 1.0e-4f)
        val inv = 1.0f / (2.0f * e)
        val dx = (potential3(p + NoiseVec3(e, 0f, 0f), octaves, lacunarity, gain) -
            potential3(p - NoiseVec3(e, 0f, 0f), octaves, lacunarity, gain)) * inv
        val dy = (potential3(p + NoiseVec3(0f, e, 0f), octaves, lacunarity, gain) -
            potential3(p - NoiseVec3(0f, e, 0f), octaves, lacunarity, gain)) * inv
        val dz = (potential3(p + NoiseVec3(0f, 0f, e), octaves, lacunarity, gain) -
            potential3(p - NoiseVec3(0f, 0f, e), octaves, lacunarity, gain)) * inv
        return NoiseVec3(dy.z - dz.y, dz.x - dx.z, dx.y - dy.x)
    }

    /**
     * Cellular noise over a jittered lattice, packed so that the useful channels are all present
     * at once: `.x` is F1, the distance to the nearest feature point; `.y` is F2, the second
     * nearest; `.z` is `F2 - F1`, which is near zero exactly on the cell walls and is the crack
     * or Voronoi-edge field; `.w` is a per-cell random constant, for flat-shading the cells.
     *
     * A 3x3x3 search is exact whenever F1 is below 1.0, which it is for the overwhelming
     * majority of points; beyond that it can miss a nearer point two cells away.
     */
    fun worley3(p: NoiseVec3): NoiseVec4 {
        val ix = floor(p.x)
        val iy = floor(p.y)
        val iz = floor(p.z)
        val fx = p.x - ix
        val fy = p.y - iy
        val fz = p.z - iz
        var f1 = 1.0e9f
        var f2 = 1.0e9f
        var cell = 0.0f
        for (gz in -1..1) {
            for (gy in -1..1) {
                for (gx in -1..1) {
                    val cx = ix + gx
                    val cy = iy + gy
                    val cz = iz + gz
                    val o = hash33(cx, cy, cz)
                    val d = NoiseVec3(gx + o.x - fx, gy + o.y - fy, gz + o.z - fz).length
                    if (d < f1) {
                        f2 = f1
                        f1 = d
                        cell = hash31(cx, cy, cz)
                    } else if (d < f2) {
                        f2 = d
                    }
                }
            }
        }
        return NoiseVec4(f1, f2, f2 - f1, cell)
    }

    /**
     * `fbm3` and its gradient in one word: `.x` is the value, `.yzw` the gradient. The gradient
     * of a scalar field is a normal — of an isosurface, of a cloud edge — and getting it out of
     * StackSL previously meant six calls and a lot of stack juggling.
     */
    fun fbmd3(
        p: NoiseVec3,
        epsilon: Float = K.DEFAULT_GRADIENT_EPSILON,
        octaves: Float = K.DEFAULT_OCTAVES,
        lacunarity: Float = K.DEFAULT_LACUNARITY,
        gain: Float = K.DEFAULT_GAIN
    ): NoiseVec4 {
        val e = maxOf(abs(epsilon), 1.0e-4f)
        val inv = 1.0f / (2.0f * e)
        val v = fbm3(p, octaves, lacunarity, gain)
        val gx = (fbm3(p + NoiseVec3(e, 0f, 0f), octaves, lacunarity, gain) -
            fbm3(p - NoiseVec3(e, 0f, 0f), octaves, lacunarity, gain)) * inv
        val gy = (fbm3(p + NoiseVec3(0f, e, 0f), octaves, lacunarity, gain) -
            fbm3(p - NoiseVec3(0f, e, 0f), octaves, lacunarity, gain)) * inv
        val gz = (fbm3(p + NoiseVec3(0f, 0f, e), octaves, lacunarity, gain) -
            fbm3(p - NoiseVec3(0f, 0f, e), octaves, lacunarity, gain)) * inv
        return NoiseVec4(v, gx, gy, gz)
    }

    /**
     * Domain warping: `fbm3` evaluated at a position that has itself been displaced by an fbm3
     * vector field. One line of arithmetic that turns the characteristic cloudy fbm into
     * something with filaments and folds. Still in [0,1), because the outer call is still fbm3.
     */
    fun warp3(
        p: NoiseVec3,
        strength: Float = K.DEFAULT_WARP_STRENGTH,
        octaves: Float = K.DEFAULT_OCTAVES,
        lacunarity: Float = K.DEFAULT_LACUNARITY,
        gain: Float = K.DEFAULT_GAIN
    ): Float {
        val q = potential3(p, octaves, lacunarity, gain)
        val displaced = p + (q - NoiseVec3(0.5f, 0.5f, 0.5f)) * (2.0f * strength)
        return fbm3(displaced, octaves, lacunarity, gain)
    }
}

// ---------------------------------------------------------------------------------------------
// GLSL emission. Every numeric literal below is interpolated from VolumetricNoiseConstants; a
// bare float in this text is a bug, and the anti-drift test looks for exactly that.
// ---------------------------------------------------------------------------------------------

/** Helper keys, and what each one needs emitted before it. Order of emission is dependency order. */
internal val volumetricNoiseHelperDependencies: Map<String, List<String>> = linkedMapOf(
    "hash31" to emptyList(),
    "hash33" to emptyList(),
    "hash41" to emptyList(),
    "noise3" to listOf("hash31"),
    "noise4" to listOf("hash41"),
    "fbm3" to listOf("noise3"),
    "fbm4" to listOf("noise4"),
    "potential3" to listOf("fbm3"),
    "curl3" to listOf("potential3"),
    "fbmd3" to listOf("fbm3"),
    "warp3" to listOf("potential3"),
    "worley3" to listOf("hash31", "hash33")
)

/** Expand a requested helper set to include everything it transitively needs, in emission order. */
internal fun expandVolumetricHelpers(requested: Set<String>): Set<String> {
    val out = linkedSetOf<String>()
    fun visit(name: String) {
        if (name in out) return
        val deps = volumetricNoiseHelperDependencies[name] ?: return
        deps.forEach(::visit)
        out += name
    }
    // Iterate the declaration order, not the request order, so emission is deterministic.
    volumetricNoiseHelperDependencies.keys.forEach { if (it in requested) visit(it) }
    return out
}

private fun Float.g(): String {
    if (!this.isFinite()) return "0.0"
    val text = String.format(Locale.US, "%.7f", this).trimEnd('0').trimEnd('.')
    return if (text.contains('.')) text else "$text.0"
}

private fun NoiseVec3.g(): String = "vec3(${x.g()}, ${y.g()}, ${z.g()})"

/**
 * The shader half of this file. Emitted only for the helpers a program actually reaches, which
 * matters: `curl3` alone pulls in eighteen `fbm3` calls' worth of code, and a program that only
 * says `noise3` should not pay for it.
 */
internal fun volumetricNoiseHelperSource(prefix: String, helpers: Set<String>): String {
    val needed = expandVolumetricHelpers(helpers)
    if (needed.isEmpty()) return ""
    val K = VolumetricNoiseConstants
    val scale3 = "vec3(${K.HASH3_SCALE_X.g()}, ${K.HASH3_SCALE_Y.g()}, ${K.HASH3_SCALE_Z.g()})"
    val scale4 = "vec4(${K.HASH3_SCALE_X.g()}, ${K.HASH3_SCALE_Y.g()}, " +
        "${K.HASH3_SCALE_Z.g()}, ${K.HASH4_SCALE_W.g()})"
    val seed = K.HASH_SEED.g()
    val rot = "mat3(${K.OCTAVE_ROTATION_COLUMNS.joinToString(", ") { it.g() }})"
    val maxOct = K.MAX_OCTAVES

    return buildString {
        if ("hash31" in needed) {
            appendLine("float ${prefix}_hash31(vec3 p) {")
            appendLine("    p = fract((p + $seed) * $scale3);")
            appendLine("    p += dot(p, p.zyx + ${K.HASH31_ADD.g()});")
            appendLine("    return fract((p.x + p.y) * p.z);")
            appendLine("}")
            appendLine()
        }
        if ("hash33" in needed) {
            appendLine("vec3 ${prefix}_hash33(vec3 p) {")
            appendLine("    p = fract((p + $seed) * $scale3);")
            appendLine("    p += dot(p, p.yxz + ${K.HASH33_ADD.g()});")
            appendLine("    return fract((p.xxy + p.yxx) * p.zyx);")
            appendLine("}")
            appendLine()
        }
        if ("hash41" in needed) {
            appendLine("float ${prefix}_hash41(vec4 p) {")
            appendLine("    p = fract((p + $seed) * $scale4);")
            appendLine("    p += dot(p, p.wzxy + ${K.HASH41_ADD.g()});")
            appendLine("    return fract((p.x + p.y) * (p.z + p.w));")
            appendLine("}")
            appendLine()
        }
        if ("noise3" in needed) {
            appendLine("float ${prefix}_noise3(vec3 p) {")
            appendLine("    vec3 i = floor(p);")
            appendLine("    vec3 f = p - i;")
            appendLine("    vec3 u = f * f * (3.0 - 2.0 * f);")
            appendLine("    float c000 = ${prefix}_hash31(i + vec3(0.0, 0.0, 0.0));")
            appendLine("    float c100 = ${prefix}_hash31(i + vec3(1.0, 0.0, 0.0));")
            appendLine("    float c010 = ${prefix}_hash31(i + vec3(0.0, 1.0, 0.0));")
            appendLine("    float c110 = ${prefix}_hash31(i + vec3(1.0, 1.0, 0.0));")
            appendLine("    float c001 = ${prefix}_hash31(i + vec3(0.0, 0.0, 1.0));")
            appendLine("    float c101 = ${prefix}_hash31(i + vec3(1.0, 0.0, 1.0));")
            appendLine("    float c011 = ${prefix}_hash31(i + vec3(0.0, 1.0, 1.0));")
            appendLine("    float c111 = ${prefix}_hash31(i + vec3(1.0, 1.0, 1.0));")
            appendLine("    float z0 = mix(mix(c000, c100, u.x), mix(c010, c110, u.x), u.y);")
            appendLine("    float z1 = mix(mix(c001, c101, u.x), mix(c011, c111, u.x), u.y);")
            appendLine("    return mix(z0, z1, u.z);")
            appendLine("}")
            appendLine()
        }
        if ("noise4" in needed) {
            appendLine("float ${prefix}_noise4_slice(vec4 i, vec3 u) {")
            appendLine("    float c000 = ${prefix}_hash41(i + vec4(0.0, 0.0, 0.0, 0.0));")
            appendLine("    float c100 = ${prefix}_hash41(i + vec4(1.0, 0.0, 0.0, 0.0));")
            appendLine("    float c010 = ${prefix}_hash41(i + vec4(0.0, 1.0, 0.0, 0.0));")
            appendLine("    float c110 = ${prefix}_hash41(i + vec4(1.0, 1.0, 0.0, 0.0));")
            appendLine("    float c001 = ${prefix}_hash41(i + vec4(0.0, 0.0, 1.0, 0.0));")
            appendLine("    float c101 = ${prefix}_hash41(i + vec4(1.0, 0.0, 1.0, 0.0));")
            appendLine("    float c011 = ${prefix}_hash41(i + vec4(0.0, 1.0, 1.0, 0.0));")
            appendLine("    float c111 = ${prefix}_hash41(i + vec4(1.0, 1.0, 1.0, 0.0));")
            appendLine("    float z0 = mix(mix(c000, c100, u.x), mix(c010, c110, u.x), u.y);")
            appendLine("    float z1 = mix(mix(c001, c101, u.x), mix(c011, c111, u.x), u.y);")
            appendLine("    return mix(z0, z1, u.z);")
            appendLine("}")
            appendLine()
            appendLine("float ${prefix}_noise4(vec4 p) {")
            appendLine("    vec4 i = floor(p);")
            appendLine("    vec4 f = p - i;")
            appendLine("    vec4 u = f * f * (3.0 - 2.0 * f);")
            appendLine("    float s0 = ${prefix}_noise4_slice(i, u.xyz);")
            appendLine("    float s1 = ${prefix}_noise4_slice(i + vec4(0.0, 0.0, 0.0, 1.0), u.xyz);")
            appendLine("    return mix(s0, s1, u.w);")
            appendLine("}")
            appendLine()
        }
        if ("fbm3" in needed) {
            appendLine("float ${prefix}_fbm3(vec3 p, float octaves, float lacunarity, float gain) {")
            appendLine("    int n = int(clamp(octaves, 1.0, ${maxOct.toFloat().g()}));")
            appendLine("    float amp = 1.0;")
            appendLine("    float sum = 0.0;")
            appendLine("    float norm = 0.0;")
            appendLine("    for (int i = 0; i < $maxOct; i++) {")
            appendLine("        if (i >= n) break;")
            appendLine("        sum += amp * ${prefix}_noise3(p);")
            appendLine("        norm += amp;")
            appendLine("        p = $rot * p * lacunarity + ${K.OCTAVE_OFFSET.g()};")
            appendLine("        amp *= gain;")
            appendLine("    }")
            appendLine("    return norm > 0.0 ? sum / norm : 0.0;")
            appendLine("}")
            appendLine()
        }
        if ("fbm4" in needed) {
            appendLine("float ${prefix}_fbm4(vec4 p, float octaves, float lacunarity, float gain) {")
            appendLine("    int n = int(clamp(octaves, 1.0, ${maxOct.toFloat().g()}));")
            appendLine("    float amp = 1.0;")
            appendLine("    float sum = 0.0;")
            appendLine("    float norm = 0.0;")
            appendLine("    for (int i = 0; i < $maxOct; i++) {")
            appendLine("        if (i >= n) break;")
            appendLine("        sum += amp * ${prefix}_noise4(p);")
            appendLine("        norm += amp;")
            appendLine("        p = vec4($rot * p.xyz * lacunarity + ${K.OCTAVE_OFFSET.g()},")
            appendLine("                 p.w * lacunarity + ${K.OCTAVE_OFFSET_W.g()});")
            appendLine("        amp *= gain;")
            appendLine("    }")
            appendLine("    return norm > 0.0 ? sum / norm : 0.0;")
            appendLine("}")
            appendLine()
        }
        if ("potential3" in needed) {
            appendLine("vec3 ${prefix}_potential3(vec3 p, float octaves, float lacunarity, float gain) {")
            appendLine("    return vec3(")
            appendLine("        ${prefix}_fbm3(p + ${K.FIELD_OFFSET_A.g()}, octaves, lacunarity, gain),")
            appendLine("        ${prefix}_fbm3(p + ${K.FIELD_OFFSET_B.g()}, octaves, lacunarity, gain),")
            appendLine("        ${prefix}_fbm3(p + ${K.FIELD_OFFSET_C.g()}, octaves, lacunarity, gain));")
            appendLine("}")
            appendLine()
        }
        if ("curl3" in needed) {
            appendLine("// Central differences at ONE shared epsilon: the discrete shift operators then")
            appendLine("// commute, so the discrete divergence of this cancels term for term and is zero.")
            appendLine("vec3 ${prefix}_curl3(vec3 p, float epsilon, float octaves, float lacunarity, float gain) {")
            appendLine("    float e = max(abs(epsilon), 0.0001);")
            appendLine("    float inv = 1.0 / (2.0 * e);")
            appendLine("    vec3 dx = (${prefix}_potential3(p + vec3(e, 0.0, 0.0), octaves, lacunarity, gain)")
            appendLine("             - ${prefix}_potential3(p - vec3(e, 0.0, 0.0), octaves, lacunarity, gain)) * inv;")
            appendLine("    vec3 dy = (${prefix}_potential3(p + vec3(0.0, e, 0.0), octaves, lacunarity, gain)")
            appendLine("             - ${prefix}_potential3(p - vec3(0.0, e, 0.0), octaves, lacunarity, gain)) * inv;")
            appendLine("    vec3 dz = (${prefix}_potential3(p + vec3(0.0, 0.0, e), octaves, lacunarity, gain)")
            appendLine("             - ${prefix}_potential3(p - vec3(0.0, 0.0, e), octaves, lacunarity, gain)) * inv;")
            appendLine("    return vec3(dy.z - dz.y, dz.x - dx.z, dx.y - dy.x);")
            appendLine("}")
            appendLine()
        }
        if ("fbmd3" in needed) {
            appendLine("vec4 ${prefix}_fbmd3(vec3 p, float epsilon, float octaves, float lacunarity, float gain) {")
            appendLine("    float e = max(abs(epsilon), 0.0001);")
            appendLine("    float inv = 1.0 / (2.0 * e);")
            appendLine("    float v = ${prefix}_fbm3(p, octaves, lacunarity, gain);")
            appendLine("    float gx = (${prefix}_fbm3(p + vec3(e, 0.0, 0.0), octaves, lacunarity, gain)")
            appendLine("              - ${prefix}_fbm3(p - vec3(e, 0.0, 0.0), octaves, lacunarity, gain)) * inv;")
            appendLine("    float gy = (${prefix}_fbm3(p + vec3(0.0, e, 0.0), octaves, lacunarity, gain)")
            appendLine("              - ${prefix}_fbm3(p - vec3(0.0, e, 0.0), octaves, lacunarity, gain)) * inv;")
            appendLine("    float gz = (${prefix}_fbm3(p + vec3(0.0, 0.0, e), octaves, lacunarity, gain)")
            appendLine("              - ${prefix}_fbm3(p - vec3(0.0, 0.0, e), octaves, lacunarity, gain)) * inv;")
            appendLine("    return vec4(v, gx, gy, gz);")
            appendLine("}")
            appendLine()
        }
        if ("warp3" in needed) {
            appendLine("float ${prefix}_warp3(vec3 p, float strength, float octaves, float lacunarity, float gain) {")
            appendLine("    vec3 q = ${prefix}_potential3(p, octaves, lacunarity, gain);")
            appendLine("    return ${prefix}_fbm3(p + (q - vec3(0.5)) * (2.0 * strength), octaves, lacunarity, gain);")
            appendLine("}")
            appendLine()
        }
        if ("worley3" in needed) {
            appendLine("// .x = F1, .y = F2, .z = F2 - F1 (the cell-wall crack field), .w = a per-cell constant.")
            appendLine("vec4 ${prefix}_worley3(vec3 p) {")
            appendLine("    vec3 i = floor(p);")
            appendLine("    vec3 f = p - i;")
            appendLine("    float f1 = 1000000000.0;")
            appendLine("    float f2 = 1000000000.0;")
            appendLine("    float cell = 0.0;")
            appendLine("    for (int gz = -1; gz <= 1; gz++) {")
            appendLine("        for (int gy = -1; gy <= 1; gy++) {")
            appendLine("            for (int gx = -1; gx <= 1; gx++) {")
            appendLine("                vec3 g = vec3(float(gx), float(gy), float(gz));")
            appendLine("                vec3 c = i + g;")
            appendLine("                float d = length(g + ${prefix}_hash33(c) - f);")
            appendLine("                if (d < f1) {")
            appendLine("                    f2 = f1;")
            appendLine("                    f1 = d;")
            appendLine("                    cell = ${prefix}_hash31(c);")
            appendLine("                } else if (d < f2) {")
            appendLine("                    f2 = d;")
            appendLine("                }")
            appendLine("            }")
            appendLine("        }")
            appendLine("    }")
            appendLine("    return vec4(f1, f2, f2 - f1, cell);")
            appendLine("}")
            appendLine()
        }
    }
}
