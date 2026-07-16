@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package afield.four.util

import afield.RunsAt
import afield.newRoot
import afield.registerValuePrinter
import afield.stdlib.LeastSquaresOptions
import afield.stdlib.LeastSquaresResult
import afield.stdlib.leastSquares
import afield.templatesRegistration
import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.Vector8h
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import java.util.Locale

val FM_CHIRP_GLSL = """
// ============================================================================
// FM Chirp Curve8
// ============================================================================
//
// 8 half-float parameter layout:
//
//   a = freq
//   b = mod
//   c = modPhase
//   d = phase
//   f = chirp
//   g = modChirp
//   n = inputPow
//   n2 = outputPow
//
// Packed as uvec4:
//
//   h.x = packHalf2x16(vec2(a, b))
//   h.y = packHalf2x16(vec2(c, d))
//   h.z = packHalf2x16(vec2(f, g))
//   h.w = packHalf2x16(vec2(n, n2))
//
// q(x) = sin(
//     TAU * (a*x + d + f*x*x)
//     + sin(TAU * (b*x + c + g*x*x))
// )
//
// Direct01 mode:
//
//   y = pow(0.5 + 0.5*q(pow(x, n)), n2)
//
// Residual mode:
//
//   y = x + gain*signedPow(q(pow(x,n)), n2)
//
// Anchored residual mode:
//
//   y = x + gain*x*(1-x)*signedPow(q(pow(x,n)), n2)
//
// ============================================================================

const float FM_CURVE_TAU = 6.28318530717958647692;
const float FM_CURVE_EPS = 1e-8;
const float FM_CURVE_MIN_EXPONENT = 1e-4;

const int FM_CURVE_MODE_DIRECT_01 = 0;
const int FM_CURVE_MODE_RESIDUAL  = 1;
const int FM_CURVE_MODE_ANCHORED_RESIDUAL = 2;

struct FMChirpCurve8 {
    float freq;
    float mod;
    float modPhase;
    float phase;
    float chirp;
    float modChirp;
    float inputPow;
    float outputPow;
};

float fmSafePow01(float x, float exponent) {
    x = clamp(x, 0.0, 1.0);
    exponent = max(exponent, FM_CURVE_MIN_EXPONENT);

    if (x <= 0.0) {
        return 0.0;
    }

    if (x >= 1.0) {
        return 1.0;
    }

    return exp2(log2(max(x, FM_CURVE_EPS)) * exponent);
}

float fmSafeSignedPow(float x, float exponent) {
    float ax = abs(x);
    exponent = max(exponent, FM_CURVE_MIN_EXPONENT);

    if (ax <= 0.0) {
        return 0.0;
    }

    float y = exp2(log2(max(ax, FM_CURVE_EPS)) * exponent);

    return sign(x) * y;
}

FMChirpCurve8 fmCurve8Make(
    float freq,
    float mod,
    float modPhase,
    float phase,
    float chirp,
    float modChirp,
    float inputPow,
    float outputPow
) {
    FMChirpCurve8 p;

    p.freq = freq;
    p.mod = mod;
    p.modPhase = modPhase;
    p.phase = phase;
    p.chirp = chirp;
    p.modChirp = modChirp;
    p.inputPow = inputPow;
    p.outputPow = outputPow;

    return p;
}

uvec4 fmCurve8PackToUVec4(FMChirpCurve8 p) {
    return uvec4(
        packHalf2x16(vec2(p.freq,      p.mod)),
        packHalf2x16(vec2(p.modPhase, p.phase)),
        packHalf2x16(vec2(p.chirp, p.modChirp)),
        packHalf2x16(vec2(p.inputPow,     p.outputPow))
    );
}

FMChirpCurve8 fmCurve8UnpackFromUVec4(uvec4 h) {
    vec2 ab = unpackHalf2x16(h.x);
    vec2 cd = unpackHalf2x16(h.y);
    vec2 fg = unpackHalf2x16(h.z);
    vec2 nn = unpackHalf2x16(h.w);

    FMChirpCurve8 p;

    p.freq = ab.x;
    p.mod = ab.y;

    p.modPhase = cd.x;
    p.phase = cd.y;

    p.chirp = fg.x;
    p.modChirp = fg.y;

    p.inputPow = nn.x;
    p.outputPow = nn.y;

    return p;
}

vec4 fmCurve8PackToOpaqueVec4(FMChirpCurve8 p) {
    uvec4 u = fmCurve8PackToUVec4(p);

    return vec4(
        uintBitsToFloat(u.x),
        uintBitsToFloat(u.y),
        uintBitsToFloat(u.z),
        uintBitsToFloat(u.w)
    );
}

FMChirpCurve8 fmCurve8UnpackFromOpaqueVec4(vec4 v) {
    uvec4 u = uvec4(
        floatBitsToUint(v.x),
        floatBitsToUint(v.y),
        floatBitsToUint(v.z),
        floatBitsToUint(v.w)
    );

    return fmCurve8UnpackFromUVec4(u);
}

float fmCurve8QAtWarpedX(float xWarped, FMChirpCurve8 p) {
    float x = xWarped;
    float xx = x * x;

    float modPhaseValue =
        FM_CURVE_TAU * (
            p.mod * x +
            p.modPhase +
            p.modChirp * xx
        );

    float phaseValue =
        FM_CURVE_TAU * (
            p.freq * x +
            p.phase +
            p.chirp * xx
        );

    return sin(phaseValue + sin(modPhaseValue));
}

float fmCurve8Q(float x, FMChirpCurve8 p) {
    float xWarped =
        fmSafeSignedPow(x, p.inputPow);

    return fmCurve8QAtWarpedX(xWarped, p);
}

float fmCurve8Direct01(float x, FMChirpCurve8 p) {
    float q = fmCurve8Q(x, p);

    float y = 0.5 + 0.5 * q;

    return fmSafePow01(y, p.outputPow);
}

float fmCurve8Residual(
    float x,
    FMChirpCurve8 p,
    float gain,
    bool shapeResidualWithPow
) {
    float q = fmCurve8Q(x, p);

    if (shapeResidualWithPow) {
        q = fmSafeSignedPow(q, p.outputPow);
    }

    return x + gain * q;
}

float fmCurve8AnchoredResidual(
    float x,
    FMChirpCurve8 p,
    float gain,
    bool shapeResidualWithPow
) {
    float q = fmCurve8Q(x, p);

    if (shapeResidualWithPow) {
        q = fmSafeSignedPow(q, p.outputPow);
    }

    return x + gain * x * (1.0 - x) * q;
}

float fmCurve8Eval(
    float x,
    FMChirpCurve8 p,
    int mode,
    float gain
) {
    if (mode == FM_CURVE_MODE_DIRECT_01) {
        return fmCurve8Direct01(x, p);
    }

    if (mode == FM_CURVE_MODE_ANCHORED_RESIDUAL) {
        return fmCurve8AnchoredResidual(
            x,
            p,
            gain,
            true
        );
    }

    return fmCurve8Residual(
        x,
        p,
        gain,
        true
    );
}

float fmCurve8EvalPackedUVec4(
    float x,
    uvec4 packed,
    int mode,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromUVec4(packed);
    return fmCurve8Eval(x, p, mode, gain);
}

float fmCurve8ResidualPackedUVec4(
    float x,
    uvec4 packed,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromUVec4(packed);

    return fmCurve8Residual(
        x,
        p,
        gain,
        true
    );
}

float fmCurve8Direct01PackedUVec4(
    float x,
    uvec4 packed
) {
    FMChirpCurve8 p = fmCurve8UnpackFromUVec4(packed);
    return fmCurve8Direct01(x, p);
}

float fmCurve8AnchoredResidualPackedUVec4(
    float x,
    uvec4 packed,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromUVec4(packed);

    return fmCurve8AnchoredResidual(
        x,
        p,
        gain,
        true
    );
}

float fmCurve8EvalOpaqueVec4(
    float x,
    vec4 packedOpaque,
    int mode,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromOpaqueVec4(packedOpaque);
    return fmCurve8Eval(x, p, mode, gain);
}

float fmCurve8ResidualOpaqueVec4(
    float x,
    vec4 packedOpaque,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromOpaqueVec4(packedOpaque);

    return fmCurve8Residual(
        x,
        p,
        gain,
        true
    );
}

float fmCurve8Direct01OpaqueVec4(
    float x,
    vec4 packedOpaque
) {
    FMChirpCurve8 p = fmCurve8UnpackFromOpaqueVec4(packedOpaque);
    return fmCurve8Direct01(x, p);
}

float fmCurve8AnchoredResidualOpaqueVec4(
    float x,
    vec4 packedOpaque,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromOpaqueVec4(packedOpaque);

    return fmCurve8AnchoredResidual(
        x,
        p,
        gain,
        true
    );
}

""".trimIndent()

@RunsAt(templatesRegistration)
fun registerFMChirpShaderTemplate() {
    registerGlobalShaderTemplate("fmchirp", FM_CHIRP_GLSL)
}

@RunsAt(newRoot)
fun registerFMChirpVisualizers() {
    registerValuePrinter<FMChirpCurves.Params> { it.summaryHtml() }
}

/*
Example GLSL


#version 450

// ============================================================================
// FM Chirp Curve8
// ============================================================================
//
// 8 half-float parameter layout:
//
//   a = freq
//   b = mod
//   c = modPhase
//   d = phase
//   f = chirp
//   g = modChirp
//   n = inputPow
//   n2 = outputPow
//
// Packed as uvec4:
//
//   h.x = packHalf2x16(vec2(a, b))
//   h.y = packHalf2x16(vec2(c, d))
//   h.z = packHalf2x16(vec2(f, g))
//   h.w = packHalf2x16(vec2(n, n2))
//
// q(x) = sin(
//     TAU * (a*x + d + f*x*x)
//     + sin(TAU * (b*x + c + g*x*x))
// )
//
// Direct01 mode:
//
//   y = pow(0.5 + 0.5*q(pow(x, n)), n2)
//
// Residual mode:
//
//   y = x + gain*signedPow(q(pow(x,n)), n2)
//
// Anchored residual mode:
//
//   y = x + gain*x*(1-x)*signedPow(q(pow(x,n)), n2)
//
// ============================================================================

const float FM_CURVE_TAU = 6.28318530717958647692;
const float FM_CURVE_EPS = 1e-8;
const float FM_CURVE_MIN_EXPONENT = 1e-4;

// For fmCurve8Eval(...)
const int FM_CURVE_MODE_DIRECT_01 = 0;
const int FM_CURVE_MODE_RESIDUAL  = 1;
const int FM_CURVE_MODE_ANCHORED_RESIDUAL = 2;

struct FMChirpCurve8 {
    float freq;       // a
    float mod;       // b
    float modPhase;  // c
    float phase;  // d
    float chirp;  // f
    float modChirp;  // g
    float inputPow;      // n
    float outputPow;     // n2
};

// ----------------------------------------------------------------------------
// Safe power helpers
// ----------------------------------------------------------------------------

float fmSafePow01(float x, float exponent) {
    x = clamp(x, 0.0, 1.0);
    exponent = max(exponent, FM_CURVE_MIN_EXPONENT);

    if (x <= 0.0) {
        return 0.0;
    }

    if (x >= 1.0) {
        return 1.0;
    }

    return exp2(log2(max(x, FM_CURVE_EPS)) * exponent);
}

float fmSafeSignedPow(float x, float exponent) {
    float ax = abs(x);
    exponent = max(exponent, FM_CURVE_MIN_EXPONENT);

    if (ax <= 0.0) {
        return 0.0;
    }

    float y = exp2(log2(max(ax, FM_CURVE_EPS)) * exponent);

    return sign(x) * y;
}

// ----------------------------------------------------------------------------
// Construction / packing
// ----------------------------------------------------------------------------

FMChirpCurve8 fmCurve8Make(
    float freq,
    float mod,
    float modPhase,
    float phase,
    float chirp,
    float modChirp,
    float inputPow,
    float outputPow
) {
    FMChirpCurve8 p;

    p.freq = freq;
    p.mod = mod;
    p.modPhase = modPhase;
    p.phase = phase;
    p.chirp = chirp;
    p.modChirp = modChirp;
    p.inputPow = inputPow;
    p.outputPow = outputPow;

    return p;
}

uvec4 fmCurve8PackToUVec4(FMChirpCurve8 p) {
    return uvec4(
        packHalf2x16(vec2(p.freq,      p.mod)),
        packHalf2x16(vec2(p.modPhase, p.phase)),
        packHalf2x16(vec2(p.chirp, p.modChirp)),
        packHalf2x16(vec2(p.inputPow,     p.outputPow))
    );
}

FMChirpCurve8 fmCurve8UnpackFromUVec4(uvec4 h) {
    vec2 ab = unpackHalf2x16(h.x);
    vec2 cd = unpackHalf2x16(h.y);
    vec2 fg = unpackHalf2x16(h.z);
    vec2 nn = unpackHalf2x16(h.w);

    FMChirpCurve8 p;

    p.freq = ab.x;
    p.mod = ab.y;

    p.modPhase = cd.x;
    p.phase = cd.y;

    p.chirp = fg.x;
    p.modChirp = fg.y;

    p.inputPow = nn.x;
    p.outputPow = nn.y;

    return p;
}

// Only use these vec4 bitcast versions if the vec4 is an opaque bit container.
// Do not pass these floats through interpolation, filtering, color attachments,
// normalized formats, or anything that may canonicalize NaNs.

vec4 fmCurve8PackToOpaqueVec4(FMChirpCurve8 p) {
    uvec4 u = fmCurve8PackToUVec4(p);

    return vec4(
        uintBitsToFloat(u.x),
        uintBitsToFloat(u.y),
        uintBitsToFloat(u.z),
        uintBitsToFloat(u.w)
    );
}

FMChirpCurve8 fmCurve8UnpackFromOpaqueVec4(vec4 v) {
    uvec4 u = uvec4(
        floatBitsToUint(v.x),
        floatBitsToUint(v.y),
        floatBitsToUint(v.z),
        floatBitsToUint(v.w)
    );

    return fmCurve8UnpackFromUVec4(u);
}

// ----------------------------------------------------------------------------
// Evaluation
// ----------------------------------------------------------------------------

float fmCurve8QAtWarpedX(float xWarped, FMChirpCurve8 p) {
    float x = xWarped;
    float xx = x * x;

    float modPhaseValue =
        FM_CURVE_TAU * (
            p.mod * x +
            p.modPhase +
            p.modChirp * xx
        );

    float phaseValue =
        FM_CURVE_TAU * (
            p.freq * x +
            p.phase +
            p.chirp * xx
        );

    return sin(phaseValue + sin(modPhaseValue));
}

float fmCurve8Q(float x, FMChirpCurve8 p) {
    float xWarped =
        fmSafeSignedPow(x, p.inputPow);

    return fmCurve8QAtWarpedX(xWarped, p);
}

// Bounded [0,1] version.
// Note: zero params produce flat 0.5, not identity.
float fmCurve8Direct01(float x, FMChirpCurve8 p) {
    float q = fmCurve8Q(x, p);

    float y = 0.5 + 0.5 * q;

    return fmSafePow01(y, p.outputPow);
}

// Free residual version.
// Zero params produce identity.
// This is probably the best default shader-art remap primitive.
float fmCurve8Residual(
    float x,
    FMChirpCurve8 p,
    float gain,
    bool shapeResidualWithPow
) {
    float q = fmCurve8Q(x, p);

    if (shapeResidualWithPow) {
        q = fmSafeSignedPow(q, p.outputPow);
    }

    return x + gain * q;
}

// Endpoint-anchored version.
// Zero params produce identity and y(0)=0, y(1)=1.
float fmCurve8AnchoredResidual(
    float x,
    FMChirpCurve8 p,
    float gain,
    bool shapeResidualWithPow
) {
    float q = fmCurve8Q(x, p);

    if (shapeResidualWithPow) {
        q = fmSafeSignedPow(q, p.outputPow);
    }

    return x + gain * x * (1.0 - x) * q;
}

float fmCurve8Eval(
    float x,
    FMChirpCurve8 p,
    int mode,
    float gain
) {
    if (mode == FM_CURVE_MODE_DIRECT_01) {
        return fmCurve8Direct01(x, p);
    }

    if (mode == FM_CURVE_MODE_ANCHORED_RESIDUAL) {
        return fmCurve8AnchoredResidual(
            x,
            p,
            gain,
            true
        );
    }

    return fmCurve8Residual(
        x,
        p,
        gain,
        true
    );
}

// ----------------------------------------------------------------------------
// Convenience overloads for packed data
// ----------------------------------------------------------------------------

float fmCurve8EvalPackedUVec4(
    float x,
    uvec4 packed,
    int mode,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromUVec4(packed);
    return fmCurve8Eval(x, p, mode, gain);
}

float fmCurve8ResidualPackedUVec4(
    float x,
    uvec4 packed,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromUVec4(packed);

    return fmCurve8Residual(
        x,
        p,
        gain,
        true
    );
}

float fmCurve8Direct01PackedUVec4(
    float x,
    uvec4 packed
) {
    FMChirpCurve8 p = fmCurve8UnpackFromUVec4(packed);
    return fmCurve8Direct01(x, p);
}

float fmCurve8AnchoredResidualPackedUVec4(
    float x,
    uvec4 packed,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromUVec4(packed);

    return fmCurve8AnchoredResidual(
        x,
        p,
        gain,
        true
    );
}

float fmCurve8EvalOpaqueVec4(
    float x,
    vec4 packedOpaque,
    int mode,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromOpaqueVec4(packedOpaque);
    return fmCurve8Eval(x, p, mode, gain);
}

float fmCurve8ResidualOpaqueVec4(
    float x,
    vec4 packedOpaque,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromOpaqueVec4(packedOpaque);

    return fmCurve8Residual(
        x,
        p,
        gain,
        true
    );
}

float fmCurve8Direct01OpaqueVec4(
    float x,
    vec4 packedOpaque
) {
    FMChirpCurve8 p = fmCurve8UnpackFromOpaqueVec4(packedOpaque);
    return fmCurve8Direct01(x, p);
}

float fmCurve8AnchoredResidualOpaqueVec4(
    float x,
    vec4 packedOpaque,
    float gain
) {
    FMChirpCurve8 p = fmCurve8UnpackFromOpaqueVec4(packedOpaque);

    return fmCurve8AnchoredResidual(
        x,
        p,
        gain,
        true
    );
}

// ----------------------------------------------------------------------------
// Example usage
// ----------------------------------------------------------------------------
//
// layout(std430, binding = 0) readonly buffer CurveBuffer {
//     uvec4 curves[];
// };
//
// void main() {
//     uint id = gl_GlobalInvocationID.x;
//
//     uvec4 packed = curves[id];
//     float x = 0.37;
//
//     float y = fmCurve8ResidualPackedUVec4(
//         x,
//         packed,
//         1.0   // gain
//     );
// }
//
// ----------------------------------------------------------------------------
//
*/

object FMChirpCurves {
    private const val PARAMETER_COUNT = 8
    private const val EPS = 1e-8f
    private const val MIN_EXPONENT = 1e-4f
    private const val LOG2 = 0.6931471805599453
    private const val TAU = (2.0 * PI)

    enum class Mode {
        /**
         * y = pow(0.5 + 0.5*q(pow(x,n)), n2)
         *
         * Bounded in [0,1], but zero params produce flat 0.5, not identity.
         */
        Direct01,

        /**
         * y = x + gain*signedPow(q(pow(x,n)), n2)
         *
         * Free residual remap. Zero params produce identity. This is deliberately
         * not endpoint anchored, so low-x and high-x targets still have real
         * authority for art-directed fitting.
         */
        Residual,

        /**
         * y = x + gain*x*(1-x)*signedPow(q(pow(x,n)), n2)
         *
         * Endpoint anchored. Zero params produce identity.
         */
        AnchoredResidual
    }

    data class Range(val min: Float, val max: Float)

    data class FitOptions(
        val leastSquares: LeastSquaresOptions = LeastSquaresOptions(maxIterations = 60),
        val regularizationWeight: Float = 0.005f,
        val parameterScales: FloatArray = floatArrayOf(8f, 8f, 1f, 1f, 8f, 8f, 2f, 2f),
        val maxFreq: Float = 64f,
        val maxChirp: Float = 64f,
        val maxPow: Float = 8f
    ) {
        init {
            require(regularizationWeight >= 0f) { "regularizationWeight must be >= 0" }
            require(parameterScales.size == PARAMETER_COUNT) { "parameterScales must have $PARAMETER_COUNT entries" }
            require(parameterScales.all { it > 0f && it.isFinite() }) { "parameterScales must be positive finite values" }
        }
    }

    data class FitStep(
        val modifier: CurveModifier,
        val before: Params,
        val after: Params,
        val result: LeastSquaresResult
    )

    data class FitTrace(
        val start: Params,
        val result: Params,
        val steps: List<FitStep>
    )

    fun interface CurveModifier {
        fun residuals(params: Params, base: Params): DoubleArray
    }

    data class Params(
        /** Carrier frequency over x in [0,1]. Corresponds to `a`. */
        val freq: Float = 0f,

        /** Modulator frequency over x in [0,1]. Corresponds to `b`. */
        val mod: Float = 0f,

        /** Modulator phase. Corresponds to `c`. */
        val modPhase: Float = 0f,

        /** Carrier phase. Corresponds to `d`. */
        val phase: Float = 0f,

        /** Carrier quadratic chirp. Corresponds to `f`. */
        val chirp: Float = 0f,

        /** Modulator quadratic chirp. Corresponds to `g`. */
        val modChirp: Float = 0f,

        /** Input shaping exponent. */
        val inputPow: Float = 1f,

        /** Output shaping exponent. */
        val outputPow: Float = 1f
    ) {
        fun toVector8h(): Vector8h =
            Vector8h(freq, mod, modPhase, phase, chirp, modChirp, inputPow, outputPow)

        fun toVector(): DoubleArray =
            doubleArrayOf(
                freq.toDouble(),
                mod.toDouble(),
                modPhase.toDouble(),
                phase.toDouble(),
                chirp.toDouble(),
                modChirp.toDouble(),
                inputPow.toDouble(),
                outputPow.toDouble()
            )

        fun safeInputPow(): Float =
            max(inputPow, MIN_EXPONENT)

        fun safeOutputPow(): Float =
            max(outputPow, MIN_EXPONENT)

        fun withInputPow(pow: Float): Params =
            copy(inputPow = max(pow, MIN_EXPONENT))

        fun withOutputPow(pow: Float): Params =
            copy(outputPow = max(pow, MIN_EXPONENT))

        fun withPow(input: Float, output: Float): Params =
            withInputPow(input).withOutputPow(output)

        fun withWrappedPhases(): Params =
            copy(
                modPhase = wrap01(modPhase),
                phase = wrap01(phase)
            )

        fun clampedForHalf(
            maxFreq: Float = 64f,
            maxChirp: Float = 64f,
            maxPow: Float = 8f
        ): Params {
            return copy(
                freq = freq.coerceIn(-maxFreq, maxFreq),
                mod = mod.coerceIn(-maxFreq, maxFreq),
                modPhase = modPhase.coerceIn(-maxFreq, maxFreq),
                phase = phase.coerceIn(-maxFreq, maxFreq),
                chirp = chirp.coerceIn(-maxChirp, maxChirp),
                modChirp = modChirp.coerceIn(-maxChirp, maxChirp),
                inputPow = inputPow.coerceIn(MIN_EXPONENT, maxPow),
                outputPow = outputPow.coerceIn(MIN_EXPONENT, maxPow)
            )
        }

        /**
         * Raw q:
         *
         * q(x) = sin(
         *     TAU*(freq*x + phase + chirp*x*x)
         *     + sin(TAU*(mod*x + modPhase + modChirp*x*x))
         * )
         *
         * Input x is already input-power warped here.
         */
        fun qRawAtWarpedX(xWarped: Float): Float {
            val x = xWarped
            val xx = x * x

            val modPhaseValue =
                TAU * (
                        mod * x +
                                modPhase +
                                modChirp * xx
                        )

            val phaseValue =
                TAU * (
                        freq * x +
                                phase +
                                chirp * xx
                        )

            return sin(phaseValue + sin(modPhaseValue)).toFloat()
        }

        fun qRaw(x: Float): Float {
            val xWarped = signedPow(x, safeInputPow())
            return qRawAtWarpedX(xWarped)
        }

        /**
         * Direct bounded transfer:
         *
         * y = pow(0.5 + 0.5*q(pow(x,n)), n2)
         *
         * This stays in [0,1]. It does not preserve endpoint identity unless
         * the phases/frequencies happen to make that true.
         */
        fun evalDirect01(x: Float): Float {
            val q = qRaw(x)
            val y = 0.5f + 0.5f * q
            return safePow01(y, safeOutputPow())
        }

        /**
         * Free residual transfer:
         *
         * y = x + gain*signedPow(q(pow(x,n)), n2)
         *
         * This intentionally permits endpoint movement. Use
         * [evalAnchoredResidual] when the curve must keep y(0)=0 and y(1)=1.
         */
        fun evalResidual(
            x: Float,
            gain: Float = 1f,
            shapeResidualWithPow: Boolean = true
        ): Float {
            val q = qRaw(x)

            val shapedQ =
                if (shapeResidualWithPow) {
                    safeSignedPow(q, safeOutputPow())
                } else {
                    q
                }

            return x + gain * shapedQ
        }

        /**
         * Endpoint-anchored residual transfer:
         *
         * y = x + gain*x*(1-x)*signedPow(q(pow(x,n)), n2)
         */
        fun evalAnchoredResidual(
            x: Float,
            gain: Float = 1f,
            shapeResidualWithPow: Boolean = true
        ): Float {
            val q = qRaw(x)

            val shapedQ =
                if (shapeResidualWithPow) {
                    safeSignedPow(q, safeOutputPow())
                } else {
                    q
                }

            return x + gain * x * (1f - x) * shapedQ
        }

        fun eval(
            x: Float,
            mode: Mode = Mode.Residual,
            gain: Float = 1f
        ): Float {
            return when (mode) {
                Mode.Direct01 -> evalDirect01(x)
                Mode.Residual -> evalResidual(x, gain)
                Mode.AnchoredResidual -> evalAnchoredResidual(x, gain)
            }
        }

        fun sample(
            count: Int = 256,
            mode: Mode = Mode.Residual,
            gain: Float = 1f
        ): FloatArray {
            require(count >= 2) { "count must be >= 2" }

            return FloatArray(count) { i ->
                val x = i.toFloat() / (count - 1).toFloat()
                eval(x, mode, gain)
            }
        }

        fun range(
            count: Int = 512,
            mode: Mode = Mode.Residual,
            gain: Float = 1f
        ): Range {
            val s = sample(count, mode, gain)

            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY

            for (v in s) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }

            return Range(lo, hi)
        }

        /**
         * Useful cheap measure for sorting generated curves.
         * Larger values generally mean more folds / wiggles / local acceleration.
         */
        fun roughness(
            count: Int = 512,
            mode: Mode = Mode.Residual,
            gain: Float = 1f
        ): Float {
            val s = sample(count, mode, gain)
            var total = 0f

            for (i in 1 until s.lastIndex) {
                val secondDifference = s[i - 1] - 2f * s[i] + s[i + 1]
                total += abs(secondDifference)
            }

            return total
        }

        fun foldover(
            count: Int = 256,
            mode: Mode = Mode.Residual,
            gain: Float = 1f,
            xMin: Float = -0.2f,
            xMax: Float = 1.2f
        ): Float {
            require(count >= 2) { "count must be >= 2" }
            var total = 0f
            var px = xMin
            var py = eval(px, mode, gain)

            for (i in 1 until count) {
                val t = i.toFloat() / (count - 1).toFloat()
                val x = xMin + t * (xMax - xMin)
                val y = eval(x, mode, gain)
                val dx = x - px
                if (dx > 0f && y < py) {
                    total += (py - y) / dx * dx
                }
                px = x
                py = y
            }

            return total
        }

        fun area(
            count: Int = 256,
            mode: Mode = Mode.Residual,
            gain: Float = 1f,
            xMin: Float = 0f,
            xMax: Float = 1f
        ): Float {
            require(count >= 2) { "count must be >= 2" }
            var total = 0f
            var px = xMin
            var py = eval(px, mode, gain)

            for (i in 1 until count) {
                val t = i.toFloat() / (count - 1).toFloat()
                val x = xMin + t * (xMax - xMin)
                val y = eval(x, mode, gain)
                total += (x - px) * (py + y) * 0.5f
                px = x
                py = y
            }

            return total
        }

        fun fit(
            modifier: CurveModifier,
            options: FitOptions = FitOptions()
        ): FitStep {
            val base = this
            val baseVector = base.toVector()

            val result = leastSquares(
                initial = baseVector,
                options = options.leastSquares
            ) { vector ->
                val candidate = fromVector(vector).clampedForHalf(
                    maxFreq = options.maxFreq,
                    maxChirp = options.maxChirp,
                    maxPow = options.maxPow
                )
                val candidateVector = candidate.toVector()
                val modifierResiduals = modifier.residuals(candidate, base)
                val reg = DoubleArray(PARAMETER_COUNT) { i ->
                    ((candidateVector[i] - baseVector[i]) / options.parameterScales[i].toDouble()) *
                        options.regularizationWeight.toDouble()
                }
                modifierResiduals + reg
            }

            val after = fromVector(result.x).clampedForHalf(
                maxFreq = options.maxFreq,
                maxChirp = options.maxChirp,
                maxPow = options.maxPow
            )
            return FitStep(modifier = modifier, before = base, after = after, result = result)
        }

        fun shape(
            vararg modifiers: CurveModifier,
            options: FitOptions = FitOptions()
        ): Params =
            shapeWithTrace(*modifiers, options = options).result

        fun shapeWithTrace(
            vararg modifiers: CurveModifier,
            options: FitOptions = FitOptions()
        ): FitTrace {
            var current = this
            val steps = mutableListOf<FitStep>()
            for (modifier in modifiers) {
                val step = current.fit(modifier, options)
                steps += step
                current = step.after
            }
            return FitTrace(start = this, result = current, steps = steps)
        }

        fun summaryHtml(): String {
            val cells = listOf(
                "freq" to freq,
                "mod" to mod,
                "modPhase" to modPhase,
                "phase" to phase,
                "chirp" to chirp,
                "modChirp" to modChirp,
                "inputPow" to inputPow,
                "outputPow" to outputPow
            )

            val rows = cells.joinToString("") { (label, value) ->
                """
                <tr>
                  <td style="padding:1px 10px 1px 0;color:#8a8a8a;">$label</td>
                  <td style="padding:1px 0;text-align:right;color:#f2f2f2;">${fmt(value)}</td>
                </tr>
                """.trimIndent()
            }

            return """
            <div style="display:inline-block;background:#050505;color:#e8e8e8;border:1px solid #303030;border-radius:4px;padding:7px 9px;font-family:Iosevka, ui-monospace, SFMono-Regular, Menlo, monospace;font-size:12px;line-height:1.35;">
              <div style="margin-bottom:4px;color:#ffffff;">FMChirp <span style="color:#777;">Params</span></div>
              <table style="border-collapse:collapse;">$rows</table>
            </div>
            """.trimIndent()
        }

        fun curveSvg(
            width: Int = 560,
            height: Int = 260,
            samples: Int = 320,
            mode: Mode = Mode.Residual,
            gain: Float = 1f
        ): String {
            val w = width.coerceAtLeast(240)
            val h = height.coerceAtLeast(160)
            val n = samples.coerceAtLeast(16)
            val left = 42f
            val right = 14f
            val top = 16f
            val bottom = 30f
            val plotW = (w - left - right).coerceAtLeast(1f)
            val plotH = (h - top - bottom).coerceAtLeast(1f)
            val xMin = -0.2f
            val xMax = 1.2f
            val yMin = -0.2f
            val yMax = 1.2f
            val grid = floatArrayOf(0f, 0.2f, 0.4f, 0.5f, 0.6f, 0.8f, 1f)

            fun sx(x: Float): Float = left + (x - xMin) / (xMax - xMin) * plotW
            fun sy(y: Float): Float = top + (yMax - y) / (yMax - yMin) * plotH
            fun p(v: Float): String = fmt(v)

            val sampledYs = FloatArray(n) { i ->
                val t = i.toFloat() / (n - 1).toFloat()
                val x = xMin + t * (xMax - xMin)
                eval(x, mode, gain)
            }

            val gridLines = buildString {
                for (g in grid) {
                    val x = sx(g)
                    append("""<line x1="${p(x)}" y1="${p(top)}" x2="${p(x)}" y2="${p(top + plotH)}" stroke="#242424" stroke-width="1"/>""")
                    append("""<text x="${p(x)}" y="${p(h - 9f)}" fill="#777" font-size="10" text-anchor="middle">$g</text>""")

                    val y = sy(g)
                    append("""<line x1="${p(left)}" y1="${p(y)}" x2="${p(left + plotW)}" y2="${p(y)}" stroke="#242424" stroke-width="1"/>""")
                    append("""<text x="${p(left - 8f)}" y="${p(y + 3f)}" fill="#777" font-size="10" text-anchor="end">$g</text>""")
                }
            }

            val path = buildString {
                for (i in 0 until n) {
                    val t = i.toFloat() / (n - 1).toFloat()
                    val x = xMin + t * (xMax - xMin)
                    val y = sampledYs[i]
                    if (!y.isFinite()) continue
                    if (isEmpty()) append("M") else append(" L")
                    append(p(sx(x)))
                    append(' ')
                    append(p(sy(y.coerceIn(yMin, yMax))))
                }
            }

            val zeroY = sy(0f)
            val oneY = sy(1f)
            val zeroX = sx(0f)
            val oneX = sx(1f)
            val plottedValues = sampledYs.filter { it.isFinite() }
            val rangeMin = plottedValues.minOrNull() ?: Float.NaN
            val rangeMax = plottedValues.maxOrNull() ?: Float.NaN
            val hoverValues = sampledYs.joinToString(prefix = "[", postfix = "]") { y ->
                if (y.isFinite()) fmt(y) else "null"
            }

            return """
            <div style="display:inline-block;background:#050505;color:#e8e8e8;border:1px solid #303030;border-radius:4px;padding:8px;font-family:Iosevka, ui-monospace, SFMono-Regular, Menlo, monospace;">
              <svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h" data-fmchirp-curve="true" data-values='$hoverValues' data-left="${p(left)}" data-top="${p(top)}" data-plot-w="${p(plotW)}" data-plot-h="${p(plotH)}" data-x-min="${p(xMin)}" data-x-max="${p(xMax)}" data-y-min="${p(yMin)}" data-y-max="${p(yMax)}" style="display:block;overflow:visible;background:#050505;">
                <rect x="0" y="0" width="$w" height="$h" fill="#050505"/>
                <rect x="${p(left)}" y="${p(top)}" width="${p(plotW)}" height="${p(plotH)}" fill="#080808" stroke="#343434" stroke-width="1"/>
                $gridLines
                <line x1="${p(zeroX)}" y1="${p(top)}" x2="${p(zeroX)}" y2="${p(top + plotH)}" stroke="#6f6f6f" stroke-width="1.2"/>
                <line x1="${p(oneX)}" y1="${p(top)}" x2="${p(oneX)}" y2="${p(top + plotH)}" stroke="#4a4a4a" stroke-width="1"/>
                <line x1="${p(left)}" y1="${p(zeroY)}" x2="${p(left + plotW)}" y2="${p(zeroY)}" stroke="#6f6f6f" stroke-width="1.2"/>
                <line x1="${p(left)}" y1="${p(oneY)}" x2="${p(left + plotW)}" y2="${p(oneY)}" stroke="#4a4a4a" stroke-width="1"/>
                <path d="$path" fill="none" stroke="#f5f5f5" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>
                <text x="${p(left)}" y="12" fill="#f2f2f2" font-size="11">FMChirp residual</text>
                <text x="${p(left + plotW)}" y="12" fill="#777" font-size="10" text-anchor="end">x -0.2..1.2  y ${fmt(rangeMin)}..${fmt(rangeMax)}</text>
                <g data-role="hover" visibility="hidden">
                  <line data-role="hover-x" x1="${p(left)}" y1="${p(top)}" x2="${p(left)}" y2="${p(top + plotH)}" stroke="#48c9ff" stroke-width="1" stroke-opacity="0.95" stroke-dasharray="3 4"/>
                  <line data-role="hover-y" x1="${p(left)}" y1="${p(top)}" x2="${p(left + plotW)}" y2="${p(top)}" stroke="#48c9ff" stroke-width="1" stroke-opacity="0.72" stroke-dasharray="3 4"/>
                  <circle data-role="hover-point" cx="${p(left)}" cy="${p(top)}" r="4" fill="#050505" stroke="#48c9ff" stroke-width="1.8"/>
                  <g data-role="hover-label" transform="translate(${p(left + 8f)} ${p(top + 8f)})">
                    <rect width="122" height="34" rx="3" fill="#061014" stroke="#48c9ff" stroke-width="1"/>
                    <text fill="#d9f6ff" font-size="10">
                      <tspan data-role="hover-label-x" x="7" y="13">x</tspan>
                      <tspan data-role="hover-label-y" x="7" y="27">y</tspan>
                    </text>
                  </g>
                </g>
                <rect data-role="hover-overlay" x="${p(left)}" y="${p(top)}" width="${p(plotW)}" height="${p(plotH)}" fill="transparent" style="cursor:crosshair;pointer-events:all;"/>
              </svg>
              <script>
              (() => {
                const script = document.currentScript;
                const root = (script && script.parentElement) || document;
                const curves = root.querySelectorAll('svg[data-fmchirp-curve]');

                function clamp(v, lo, hi) {
                  return Math.min(hi, Math.max(lo, v));
                }

                function formatValue(v) {
                  if (!Number.isFinite(v)) return String(v);
                  const a = Math.abs(v);
                  if ((a >= 1000) || (a > 0 && a < 0.001)) return v.toExponential(3);
                  let s = v.toFixed(4);
                  while (s.indexOf('.') !== -1 && s.endsWith('0')) s = s.slice(0, -1);
                  if (s.endsWith('.')) s = s.slice(0, -1);
                  return s;
                }

                function valueAt(values, x, xMin, xMax) {
                  if (values.length === 1) return values[0];
                  const t = (x - xMin) / (xMax - xMin) * (values.length - 1);
                  const i = clamp(Math.floor(t), 0, values.length - 2);
                  const f = clamp(t - i, 0, 1);
                  const a = values[i];
                  const b = values[i + 1];
                  if (Number.isFinite(a) && Number.isFinite(b)) return a + (b - a) * f;
                  if (Number.isFinite(a)) return a;
                  if (Number.isFinite(b)) return b;
                  return NaN;
                }

                curves.forEach((svg) => {
                  if (svg.dataset.fmchirpHoverReady === '1') return;
                  svg.dataset.fmchirpHoverReady = '1';

                  const values = JSON.parse(svg.getAttribute('data-values') || '[]');
                  const hover = svg.querySelector('[data-role="hover"]');
                  const overlay = svg.querySelector('[data-role="hover-overlay"]');
                  const xLine = svg.querySelector('[data-role="hover-x"]');
                  const yLine = svg.querySelector('[data-role="hover-y"]');
                  const point = svg.querySelector('[data-role="hover-point"]');
                  const label = svg.querySelector('[data-role="hover-label"]');
                  const labelX = svg.querySelector('[data-role="hover-label-x"]');
                  const labelY = svg.querySelector('[data-role="hover-label-y"]');
                  if (!values.length || !hover || !overlay || !xLine || !yLine || !point || !label || !labelX || !labelY) return;

                  const left = parseFloat(svg.dataset.left || '0');
                  const top = parseFloat(svg.dataset.top || '0');
                  const plotW = parseFloat(svg.dataset.plotW || '1');
                  const plotH = parseFloat(svg.dataset.plotH || '1');
                  const xMin = parseFloat(svg.dataset.xMin || '0');
                  const xMax = parseFloat(svg.dataset.xMax || '1');
                  const yMin = parseFloat(svg.dataset.yMin || '0');
                  const yMax = parseFloat(svg.dataset.yMax || '1');
                  const labelW = 122;
                  const labelH = 34;

                  overlay.addEventListener('mousemove', (event) => {
                    const bounds = overlay.getBoundingClientRect();
                    if (!bounds.width) return;
                    const u = clamp((event.clientX - bounds.left) / bounds.width, 0, 1);
                    const x = xMin + u * (xMax - xMin);
                    const y = valueAt(values, x, xMin, xMax);
                    const px = left + u * plotW;
                    const py = top + (yMax - clamp(y, yMin, yMax)) / (yMax - yMin) * plotH;
                    const lx = clamp(px + 10, left + 4, left + plotW - labelW - 4);
                    const ly = clamp(py - labelH - 8, top + 4, top + plotH - labelH - 4);

                    hover.setAttribute('visibility', 'visible');
                    xLine.setAttribute('x1', String(px));
                    xLine.setAttribute('x2', String(px));
                    yLine.setAttribute('y1', String(py));
                    yLine.setAttribute('y2', String(py));
                    point.setAttribute('cx', String(px));
                    point.setAttribute('cy', String(py));
                    label.setAttribute('transform', 'translate(' + lx + ' ' + ly + ')');
                    labelX.textContent = 'x ' + formatValue(x);
                    labelY.textContent = 'y ' + formatValue(y);
                  });

                  overlay.addEventListener('mouseleave', () => {
                    hover.setAttribute('visibility', 'hidden');
                  });
                });
              })();
              </script>
            </div>
            """.trimIndent()
        }
    }

    fun fromVector8h(v: Vector8h): Params {
        return Params(
            freq = v.x(),
            mod = v.y(),
            modPhase = v.z(),
            phase = v.w(),

            chirp = v.a(),
            modChirp = v.b(),
            inputPow = v.c(),
            outputPow = v.d()
        )
    }

    fun make(
        freq: Float = 0f,
        mod: Float = 0f,
        modPhase: Float = 0f,
        phase: Float = 0f,
        chirp: Float = 0f,
        modChirp: Float = 0f,
        inputPow: Float = 1f,
        outputPow: Float = 1f
    ): Params {
        return Params(
            freq = freq,
            mod = mod,
            modPhase = modPhase,
            phase = phase,
            chirp = chirp,
            modChirp = modChirp,
            inputPow = max(inputPow, MIN_EXPONENT),
            outputPow = max(outputPow, MIN_EXPONENT)
        )
    }

    fun vector8h(
        freq: Float = 0f,
        mod: Float = 0f,
        modPhase: Float = 0f,
        phase: Float = 0f,
        chirp: Float = 0f,
        modChirp: Float = 0f,
        inputPow: Float = 1f,
        outputPow: Float = 1f
    ): Vector8h {
        return make(
            freq = freq,
            mod = mod,
            modPhase = modPhase,
            phase = phase,
            chirp = chirp,
            modChirp = modChirp,
            inputPow = inputPow,
            outputPow = outputPow
        ).toVector8h()
    }

    fun fromVector(values: DoubleArray): Params {
        require(values.size == PARAMETER_COUNT) { "FMChirp parameter vector must have $PARAMETER_COUNT entries" }
        return make(
            freq = values[0].toFloat(),
            mod = values[1].toFloat(),
            modPhase = values[2].toFloat(),
            phase = values[3].toFloat(),
            chirp = values[4].toFloat(),
            modChirp = values[5].toFloat(),
            inputPow = values[6].toFloat(),
            outputPow = values[7].toFloat()
        )
    }

    object Modifiers {
        fun hit(
            x: Float,
            y: Float,
            weight: Float = 20f,
            mode: Mode = Mode.Residual,
            gain: Float = 1f
        ): CurveModifier = CurveModifier { params, _ ->
            doubleArrayOf((params.eval(x, mode, gain) - y).toDouble() * weight.toDouble())
        }

        fun area(
            target: Float,
            weight: Float = 1f,
            count: Int = 128,
            mode: Mode = Mode.Residual,
            gain: Float = 1f,
            xMin: Float = 0f,
            xMax: Float = 1f
        ): CurveModifier = CurveModifier { params, _ ->
            doubleArrayOf(
                (params.area(count, mode, gain, xMin, xMax) - target).toDouble() *
                    weight.toDouble()
            )
        }

        fun areaDelta(
            delta: Float,
            weight: Float = 1f,
            count: Int = 128,
            mode: Mode = Mode.Residual,
            gain: Float = 1f,
            xMin: Float = 0f,
            xMax: Float = 1f
        ): CurveModifier = CurveModifier { params, base ->
            val target = base.area(count, mode, gain, xMin, xMax) + delta
            doubleArrayOf(
                (params.area(count, mode, gain, xMin, xMax) - target).toDouble() *
                    weight.toDouble()
            )
        }

        fun roughness(
            target: Float,
            weight: Float = 1f,
            count: Int = 256,
            mode: Mode = Mode.Residual,
            gain: Float = 1f
        ): CurveModifier = CurveModifier { params, _ ->
            doubleArrayOf((params.roughness(count, mode, gain) - target).toDouble() * weight.toDouble())
        }

        fun roughnessDelta(
            delta: Float,
            weight: Float = 1f,
            count: Int = 256,
            mode: Mode = Mode.Residual,
            gain: Float = 1f
        ): CurveModifier = CurveModifier { params, base ->
            val target = base.roughness(count, mode, gain) + delta
            doubleArrayOf((params.roughness(count, mode, gain) - target).toDouble() * weight.toDouble())
        }

        fun foldover(
            target: Float,
            weight: Float = 1f,
            count: Int = 256,
            mode: Mode = Mode.Residual,
            gain: Float = 1f,
            xMin: Float = -0.2f,
            xMax: Float = 1.2f
        ): CurveModifier = CurveModifier { params, _ ->
            doubleArrayOf(
                (params.foldover(count, mode, gain, xMin, xMax) - target).toDouble() *
                    weight.toDouble()
            )
        }

        fun foldoverDelta(
            delta: Float,
            weight: Float = 1f,
            count: Int = 256,
            mode: Mode = Mode.Residual,
            gain: Float = 1f,
            xMin: Float = -0.2f,
            xMax: Float = 1.2f
        ): CurveModifier = CurveModifier { params, base ->
            val target = base.foldover(count, mode, gain, xMin, xMax) + delta
            doubleArrayOf(
                (params.foldover(count, mode, gain, xMin, xMax) - target).toDouble() *
                    weight.toDouble()
            )
        }
    }

    object Presets {
        /**
         * Direct01 mode: flat 0.5.
         * Residual mode: identity.
         */
        fun neutral(): Params = Params()

        fun lazyBend(): Params =
            make(
                freq = 0.45f,
                mod = 0.10f,
                modPhase = 0.00f,
                phase = 0.00f,
                chirp = 0.00f,
                modChirp = 0.00f,
                inputPow = 1.0f,
                outputPow = 1.0f
            )

        fun softThreshold(): Params =
            make(
                freq = 0.80f,
                mod = 0.35f,
                modPhase = 0.15f,
                phase = -0.20f,
                chirp = 0.85f,
                modChirp = -0.35f,
                inputPow = 1.15f,
                outputPow = 0.85f
            )

        fun humpShoulder(): Params =
            make(
                freq = 0.15f,
                mod = 0.85f,
                modPhase = 0.20f,
                phase = 0.05f,
                chirp = -0.55f,
                modChirp = 0.35f,
                inputPow = 1.0f,
                outputPow = 1.0f
            )

        fun delayedRise(): Params =
            make(
                freq = -0.25f,
                mod = 0.60f,
                modPhase = 0.30f,
                phase = -0.10f,
                chirp = 0.75f,
                modChirp = 0.20f,
                inputPow = 1.65f,
                outputPow = 1.0f
            )

        fun doubleHump(): Params =
            make(
                freq = 1.15f,
                mod = 1.40f,
                modPhase = 0.00f,
                phase = 0.00f,
                chirp = -0.75f,
                modChirp = 2.25f,
                inputPow = 1.0f,
                outputPow = 1.0f
            )

        fun parameterFoldback(): Params =
            make(
                freq = 1.60f,
                mod = 1.80f,
                modPhase = 0.10f,
                phase = -0.25f,
                chirp = -1.20f,
                modChirp = 2.75f,
                inputPow = 1.0f,
                outputPow = 0.75f
            )

        fun brokenTransfer(): Params =
            make(
                freq = 2.20f,
                mod = 1.10f,
                modPhase = 0.25f,
                phase = 0.00f,
                chirp = -1.60f,
                modChirp = 3.10f,
                inputPow = 0.85f,
                outputPow = 1.20f
            )

        fun smallTurbulence(): Params =
            make(
                freq = 2.80f,
                mod = 5.00f,
                modPhase = 0.07f,
                phase = 0.00f,
                chirp = -1.90f,
                modChirp = 7.00f,
                inputPow = 1.0f,
                outputPow = 1.0f
            )

        fun aggressiveTurbulence(): Params =
            make(
                freq = 4.20f,
                mod = 6.00f,
                modPhase = 0.00f,
                phase = 0.10f,
                chirp = -3.25f,
                modChirp = 9.00f,
                inputPow = 1.0f,
                outputPow = 0.85f
            )
    }

    // Optional CPU-side half packing helpers.
    // Useful for tests/debugging against GLSL packHalf2x16.
    object HalfPacking {
        fun packHalf2x16(x: Float, y: Float): Int {
            val lo = floatToHalfBits(x)
            val hi = floatToHalfBits(y)
            return lo or (hi shl 16)
        }

        fun packToUvec4Bits(p: Params): IntArray {
            return intArrayOf(
                packHalf2x16(p.freq, p.mod),
                packHalf2x16(p.modPhase, p.phase),
                packHalf2x16(p.chirp, p.modChirp),
                packHalf2x16(p.inputPow, p.outputPow)
            )
        }

        /**
         * Only use this if you intentionally want opaque bit-pattern floats,
         * e.g. to match uintBitsToFloat(packHalf2x16(...)).
         *
         * Do not treat these as numeric floats.
         */
        fun packToOpaqueFloatVec4(p: Params): Vector4f {
            val u = packToUvec4Bits(p)

            return Vector4f(
                java.lang.Float.intBitsToFloat(u[0]),
                java.lang.Float.intBitsToFloat(u[1]),
                java.lang.Float.intBitsToFloat(u[2]),
                java.lang.Float.intBitsToFloat(u[3])
            )
        }

        fun floatToHalfBits(value: Float): Int {
            val f = java.lang.Float.floatToRawIntBits(value)

            val sign = (f ushr 16) and 0x8000
            val exp = (f ushr 23) and 0xff
            val mant = f and 0x7fffff

            if (exp == 0xff) {
                return if (mant == 0) {
                    sign or 0x7c00
                } else {
                    sign or 0x7e00
                }
            }

            val halfExpUnbiased = exp - 127 + 15

            if (halfExpUnbiased >= 0x1f) {
                return sign or 0x7c00
            }

            if (halfExpUnbiased <= 0) {
                if (halfExpUnbiased < -10) {
                    return sign
                }

                val mantWithHiddenBit = mant or 0x800000
                val shift = 14 - halfExpUnbiased

                var halfMant = mantWithHiddenBit ushr shift

                val roundBit = (mantWithHiddenBit ushr (shift - 1)) and 1
                val stickyMask = (1 shl (shift - 1)) - 1
                val sticky = mantWithHiddenBit and stickyMask

                if (roundBit == 1 && (sticky != 0 || (halfMant and 1) != 0)) {
                    halfMant += 1
                }

                return sign or halfMant
            }

            var halfExp = halfExpUnbiased shl 10
            var halfMant = mant ushr 13

            val roundBits = mant and 0x1fff

            if (
                roundBits > 0x1000 ||
                (roundBits == 0x1000 && (halfMant and 1) != 0)
            ) {
                halfMant += 1

                if (halfMant == 0x400) {
                    halfMant = 0
                    halfExp += 0x400

                    if (halfExp >= 0x7c00) {
                        return sign or 0x7c00
                    }
                }
            }

            return sign or halfExp or halfMant
        }
    }

    fun safePow01(x: Float, exponent: Float): Float {
        val cx = x.coerceIn(0f, 1f)
        val e = max(exponent, MIN_EXPONENT)

        if (cx <= 0f) return 0f
        if (cx >= 1f) return 1f

        return exp2(log2(cx) * e)
    }

    fun safeSignedPow(x: Float, exponent: Float): Float {
        val ax = abs(x)
        val e = max(exponent, MIN_EXPONENT)

        if (ax <= 0f) return 0f

        val y = exp2(log2(ax) * e)

        return when {
            x < 0f -> -y
            x > 0f -> y
            else -> 0f
        }
    }

    fun signedPow(x: Float, exponent: Float): Float {
        val ax = abs(x)
        val e = max(exponent, MIN_EXPONENT)

        if (ax <= 0f) return 0f

        val y = exp2(log2(ax) * e)

        return if (x < 0f) -y else y
    }

    fun exp2(x: Float): Float =
        exp(LOG2 * x.toDouble()).toFloat()

    fun log2(x: Float): Float =
        (ln(x.toDouble()) / LOG2).toFloat()

    fun wrap01(x: Float): Float {
        val f = floor(x.toDouble()).toFloat()
        return x - f
    }

    private fun fmt(value: Float): String =
        when {
            value.isNaN() -> "NaN"
            value == Float.POSITIVE_INFINITY -> "Inf"
            value == Float.NEGATIVE_INFINITY -> "-Inf"
            abs(value) >= 1000f || abs(value) < 0.001f && value != 0f ->
                String.format(Locale.US, "%.3e", value)
            else -> String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
        }
}

val FMChirpCurves.Params.h: String
    get() = summaryHtml()

val FMChirpCurves.Params.h2: String
    get() = curveSvg()
