package afield.stacksl

/**
 * StackSL's phase noise family: `qnoise`, `phnoise`, `phrot` — noise whose *phase* is a control,
 * per frequency.
 *
 * #### What it is for
 *
 * Two fields made from the same random kernels, differing only in phase, are related in a precise
 * way that no amount of offsetting or reseeding gives you. Rotate every kernel's phase by `θ`
 * turns and the new field correlates with the old one by `cos(2πθ)`: `0` is the same field,
 * `0.25` an uncorrelated partner with the same spectrum, `0.5` its negative. Rotate only the
 * kernels above some frequency and the two fields share their coarse structure exactly while
 * their fine detail decorrelates — stereo pairs, RGB channels or particle species that belong
 * together, and noise that churns at small scales while its large forms hold still.
 *
 * Value noise (`noise`, `fbm`, `fbm3`) cannot do this. It has no phase to rotate: shifting its
 * output is a translation or an offset, not a phase change. These words are built from kernels
 * that have one — localised sinusoids, which is sparse Gabor noise.
 *
 * #### The construction
 *
 * Each octave is a jittered lattice with one kernel per cell: a Gaussian window
 * `exp(-2 r²)`, truncated to zero at `r = 1.5` cells, times a sinusoid with a random direction, a
 * random phase, and a carrier of [PhaseNoiseConstants.CARRIER] cycles per cell, jittered by up to
 * ±[PhaseNoiseConstants.CARRIER_JITTER_OCTAVES]/2 octaves per kernel so the spectrum is continuous
 * across a band rather than a comb. The same kernels are summed twice, once through `cos` and once
 * through `sin` — a quadrature pair — so the result is `(Nc, Ns)` and every phase rotation of it
 * is `Nc·cos θ − Ns·sin θ`, exactly. Each octave is divided by the square root of its own window
 * energy, which is independent of phase, so the identity survives normalisation.
 *
 * Octave `o` is the lattice scaled by `lacunarity^o` and rotated by
 * [PhaseNoiseConstants.OCTAVE_ROTATION], so a kernel's frequency in cycles per unit of the input
 * coordinate is its carrier times `lacunarity^o`. That number is what a phase response sees.
 *
 * #### Output and range
 *
 * `vec4(Nc, Ns, |N|, 1)`: the quadrature pair, its envelope (phase-invariant, so still right after
 * a `phrot`), and an opaque alpha. `Nc` and `Ns` are signed and zero-mean,
 * with an RMS near [PhaseNoiseConstants.OUTPUT_SCALE]·0.7 ≈ 0.35, so they sit mostly inside
 * `-1..1`; `PhaseNoiseTest` pins those statistics.
 *
 * #### Cost
 *
 * Sixteen kernels per octave, each a `sin`/`cos` pair, two hashes and an `exp`: five octaves is
 * eighty kernels. That is more than `fbm3` and less than `curl3`. The family is 2D over `input.xy`;
 * `input.w` reaches a phase response as time.
 *
 * Phase is in **turns** throughout, like `phacelle`'s offset and unlike `rotate`'s radians:
 * `0.25` is a quarter cycle.
 */
object PhaseNoiseConstants {
    /** Loop bound in the emitted GLSL, shared with the volumetric family. */
    const val MAX_OCTAVES = VolumetricNoiseConstants.MAX_OCTAVES

    /**
     * Cycles of the carrier per lattice cell, before jitter. With a window of standard deviation
     * half a cell the band is about ±0.32 cycles wide around this, so at lacunarity 2 consecutive
     * octaves' bands meet rather than leaving gaps.
     */
    const val CARRIER = 1.0f

    /** Per-kernel carrier jitter, as a total width in octaves (±half of it). */
    const val CARRIER_JITTER_OCTAVES = 0.5f

    /** The window `exp(-WINDOW_FALLOFF r²)`. */
    const val WINDOW_FALLOFF = 2.0f

    /**
     * `exp(-WINDOW_FALLOFF * 1.5²)`, subtracted from the window so it reaches zero at 1.5 cells.
     * A 4x4 neighbourhood with jitter of ±half a cell is complete for that radius, so no kernel
     * that should contribute is ever skipped and the field has no seams.
     */
    const val WINDOW_FLOOR = 0.011108996f

    /** Added under the energy square root so an octave never divides by zero. */
    const val ENERGY_EPSILON = 1.0e-6f

    /** Final scale on the quadrature pair; see the class notes for the resulting RMS. */
    const val OUTPUT_SCALE = 0.5f

    /** Per-octave lattice rotation (cos, sin) — a 3-4-5 triangle, so it is exactly orthonormal. */
    const val OCTAVE_ROTATION_COS = 0.8f
    const val OCTAVE_ROTATION_SIN = 0.6f

    /** Translation after each octave's rotate-and-scale, so the lattices do not share an origin. */
    const val OCTAVE_OFFSET_X = 17.17f
    const val OCTAVE_OFFSET_Y = 9.53f

    const val DEFAULT_OCTAVES = VolumetricNoiseConstants.DEFAULT_OCTAVES
    const val DEFAULT_LACUNARITY = VolumetricNoiseConstants.DEFAULT_LACUNARITY
    const val DEFAULT_GAIN = VolumetricNoiseConstants.DEFAULT_GAIN
}

/** Helpers the phase family emits through the shared helper set rather than per call site. */
internal fun phaseNoiseHelperSource(prefix: String, helpers: Set<String>): String {
    if ("phrot" !in helpers) return ""
    return buildString {
        appendLine("vec4 ${prefix}_phrot(vec4 q, float turns) {")
        appendLine("    float a = 6.283185307179586 * turns;")
        appendLine("    float c = cos(a);")
        appendLine("    float s = sin(a);")
        appendLine("    return vec4(q.x * c - q.y * s, q.x * s + q.y * c, q.z, q.w);")
        appendLine("}")
        appendLine()
    }
}

/**
 * One quadrature noise function, named [helperName]. [shiftExpression] is a GLSL `float` in turns
 * added to every kernel's phase; it may read `kernel`, a `vec4(frequency, octave, random, time)`
 * describing the kernel being summed, and any parameter in [extraParameters]. It is evaluated
 * once per kernel, which is what lets the shift depend on frequency.
 *
 * Calls `${prefix}_hash33`, so the caller must also request the volumetric `hash33` helper.
 */
internal fun quadratureNoiseFunctionSource(
    prefix: String,
    helperName: String,
    shiftExpression: String,
    extraParameters: String,
    wireParamPlaceholder: String
): String {
    val k = PhaseNoiseConstants
    fun g(value: Float) = glslFloatLiteral(value)
    val maxOct = k.MAX_OCTAVES
    return """
        vec4 $helperName(vec4 pValue, float octaves, float lacunarity, float gain$extraParameters$wireParamPlaceholder) {
            const float tau = 6.283185307179586;
            int n = int(clamp(octaves, 1.0, ${g(maxOct.toFloat())}));
            mat2 rotation = mat2(${g(k.OCTAVE_ROTATION_COS)}, ${g(k.OCTAVE_ROTATION_SIN)}, ${g(-k.OCTAVE_ROTATION_SIN)}, ${g(k.OCTAVE_ROTATION_COS)});
            vec2 q = pValue.xy;
            float scale = 1.0;
            float amp = 1.0;
            float norm = 0.0;
            vec2 quad = vec2(0.0);
            for (int o = 0; o < $maxOct; o++) {
                if (o >= n) break;
                vec2 cellBase = floor(q);
                vec2 f = q - cellBase;
                vec2 octaveSum = vec2(0.0);
                float energy = 0.0;
                for (int i = -1; i <= 2; i++) {
                    for (int j = -1; j <= 2; j++) {
                        vec2 cell = cellBase + vec2(float(i), float(j));
                        vec3 h0 = ${prefix}_hash33(vec3(cell, float(o) * 2.0));
                        vec3 h1 = ${prefix}_hash33(vec3(cell, float(o) * 2.0 + 1.0));
                        vec2 r = f - vec2(float(i), float(j)) - (h0.xy - 0.5);
                        float w = max(exp(-${g(k.WINDOW_FALLOFF)} * dot(r, r)) - ${g(k.WINDOW_FLOOR)}, 0.0);
                        float angle = tau * h0.z;
                        vec2 dir = vec2(cos(angle), sin(angle));
                        float cycles = ${g(k.CARRIER)} * exp2((h1.x - 0.5) * ${g(k.CARRIER_JITTER_OCTAVES)});
                        vec4 kernel = vec4(cycles * scale, float(o), h1.z, pValue.w);
                        float shift = $shiftExpression;
                        float phase = tau * (cycles * dot(r, dir) + h1.y + shift);
                        octaveSum += w * vec2(cos(phase), sin(phase));
                        energy += w * w;
                    }
                }
                quad += amp * octaveSum * inversesqrt(energy + ${g(k.ENERGY_EPSILON)});
                norm += amp * amp;
                q = rotation * q * lacunarity + vec2(${g(k.OCTAVE_OFFSET_X)}, ${g(k.OCTAVE_OFFSET_Y)});
                scale *= lacunarity;
                amp *= gain;
            }
            quad *= ${g(k.OUTPUT_SCALE)} * inversesqrt(max(norm, ${g(k.ENERGY_EPSILON)}));
            return vec4(quad, length(quad), 1.0);
        }
    """.trimIndent()
}

/**
 * `phnoise`'s shift: `phases` holds the turns for octaves 0, 1 and 2, and for octave 3 and
 * everything finer. The weights are a one-hot of the octave index built from `abs`/`clamp`, not a
 * variable vector index, so the CPU backend's GLSL subset runs it.
 */
internal const val PER_OCTAVE_SHIFT_EXPRESSION =
    "dot(phases, clamp(1.0 - abs(vec4(0.0, 1.0, 2.0, 3.0) - min(kernel.y, 3.0)), 0.0, 1.0))"
