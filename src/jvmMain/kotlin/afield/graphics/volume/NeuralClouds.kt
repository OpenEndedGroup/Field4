package afield.graphics.volume

import afield.ai.learning.VectorMLP
import afield.document.graph.Box
import afield.simulation.particles.MlpWeights
import smile.util.function.TimeFunction
import java.awt.image.BufferedImage
import java.io.File
import java.util.SplittableRandom
import java.util.stream.IntStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Neural multiple scattering for a raymarched cloud
 *
 * A reimplementation, from its description, of Charles Grassi's
 * [neural volumetric clouds](https://charlesgrassi.dev/blog/neural-volumetric-clouds): a tiny MLP
 * replaces the multiple-scattering term of a raymarched cloud. No code was copied; the post states
 * no licence.
 *
 * A raymarched cloud is cheap to light with *single* scattering — at each step, the sun dimmed by
 * the optical depth toward it, times the phase function. Everything else that reaches a point
 * (light that has bounced inside the cloud two, five, twenty times, and skylight) is the expensive
 * part, and the usual stand-in is an ambient term. Here a 10 → 16 → 16 → 3 network predicts that
 * in-scattered radiance from features a raymarcher can afford: the density at the point, optical
 * depths toward the sun, the sky, the eye and the ground, the height in the cloud, and the
 * scattering angle.
 *
 * Three pieces live in this file:
 *
 * - [CloudScene] — the cloud (a closed-form density the GLSL in [glslScene] reproduces exactly), the
 *   lighting, and a Monte Carlo volumetric path tracer ([CloudScene.multipleScatter]) that gives
 *   the *target* for any point and view direction: delta tracking through the heterogeneous density,
 *   next-event estimation toward the sun at every collision, sky on escape.
 * - [NeuralCloud.train] — draws training points where a camera actually sees cloud, labels them with
 *   the path tracer, and fits a `VectorMLP` (Field's Smile-backed multi-output MLP).
 * - [CloudScene.render] — one raymarcher with three interchangeable in-scatter terms
 *   ([InScatter]): the path-traced reference, a fitted single-scatter-plus-ambient approximation,
 *   and the network. Same steps, same sun transmittance, so the image differences are the
 *   in-scatter term and nothing else.
 *
 * The network's weights go to the GPU as GLSL constants through [NeuralCloud.glslNetwork].
 */
class CloudScene(
    /** Unit vector pointing *toward* the sun. */
    val sun: DoubleArray = normalize(doubleArrayOf(0.55, 0.62, 0.35)),
    /** Sun irradiance, linear RGB. */
    val sunColor: DoubleArray = doubleArrayOf(3.2, 3.0, 2.7),
    /** Sky radiance at the zenith. */
    val skyZenith: DoubleArray = doubleArrayOf(0.25, 0.42, 0.85),
    /** Sky radiance at the horizon. */
    val skyHorizon: DoubleArray = doubleArrayOf(0.65, 0.72, 0.85),
    /** Radiance from below the horizon. */
    val ground: DoubleArray = doubleArrayOf(0.18, 0.16, 0.14),
    /** Extinction per world unit at density one. */
    val sigma: Double = 9.0,
    /** Single-scattering albedo. Clouds are very nearly white. */
    val albedo: Double = 0.97,
    /** Henyey–Greenstein anisotropy. */
    val g: Double = 0.55,
) {
    val boundsLow = doubleArrayOf(-2.4, 0.0, -1.6)
    val boundsHigh = doubleArrayOf(2.4, 2.2, 1.6)

    private val blobs = arrayOf(
        doubleArrayOf(-1.1, 0.75, 0.0, 0.85),
        doubleArrayOf(0.15, 1.05, 0.2, 1.05),
        doubleArrayOf(1.25, 0.7, -0.15, 0.75),
        doubleArrayOf(0.5, 0.55, -0.6, 0.6),
        doubleArrayOf(-0.4, 1.45, 0.1, 0.6),
    )

    /** Density in `0..1`: a union of soft spheres eroded by a sum of sines. */
    fun density(x: Double, y: Double, z: Double): Double {
        var shape = 0.0
        for (b in blobs) {
            val dx = x - b[0]; val dy = y - b[1]; val dz = z - b[2]
            shape = max(shape, 1.0 - sqrt(dx * dx + dy * dy + dz * dz) / b[3])
        }
        if (shape <= 0.0) return 0.0
        val n = 0.5 * sin(3.1 * x + 1.7) * sin(2.7 * y + 0.4) * sin(2.9 * z + 2.1) +
            0.25 * sin(6.3 * x - 4.1 * z + 0.3) * sin(5.7 * y + 1.1) +
            0.125 * sin(11.9 * y + 7.3 * x) * sin(13.1 * z - 3.0)
        return ((shape + 0.45 * n - 0.12) * 2.2).coerceIn(0.0, 1.0)
    }

    fun density(p: DoubleArray) = density(p[0], p[1], p[2])

    /** Radiance arriving from direction [d] when nothing is in the way — sky above, ground below. */
    fun sky(d: DoubleArray, into: DoubleArray = DoubleArray(3)): DoubleArray {
        if (d[1] < 0.0) { ground.copyInto(into); return into }
        val t = d[1].pow(0.5)
        for (c in 0..2) into[c] = skyHorizon[c] + (skyZenith[c] - skyHorizon[c]) * t
        return into
    }

    /** The parametric span of a ray inside the cloud's box, or `null` if it misses. */
    fun span(o: DoubleArray, d: DoubleArray): DoubleArray? {
        var t0 = 0.0; var t1 = Double.MAX_VALUE
        for (a in 0..2) {
            val inv = 1.0 / d[a]
            var ta = (boundsLow[a] - o[a]) * inv
            var tb = (boundsHigh[a] - o[a]) * inv
            if (ta > tb) { val s = ta; ta = tb; tb = s }
            t0 = max(t0, ta); t1 = min(t1, tb)
            if (t0 > t1) return null
        }
        return doubleArrayOf(t0, t1)
    }

    /** Optical depth from [p] along [d] for at most [maxDistance], by [steps] midpoint samples. */
    fun opticalDepth(p: DoubleArray, d: DoubleArray, maxDistance: Double = Double.MAX_VALUE, steps: Int = 16): Double {
        val s = span(p, d) ?: return 0.0
        val length = min(s[1], maxDistance)
        if (length <= 0.0) return 0.0
        val dt = length / steps
        var tau = 0.0
        for (i in 0 until steps) {
            val t = (i + 0.5) * dt
            tau += density(p[0] + d[0] * t, p[1] + d[1] * t, p[2] + d[2] * t)
        }
        return tau * dt * sigma
    }

    /**
     * The network's inputs at [p] for light leaving toward the eye along [w]. Ten values, all in
     * roughly `0..1`; [glslScene]'s `cloudFeatures` computes the same ten.
     */
    fun features(p: DoubleArray, w: DoubleArray, into: DoubleArray = DoubleArray(FEATURES)): DoubleArray {
        val cosTheta = dot(w, sun)
        into[0] = (p[1] - boundsLow[1]) / (boundsHigh[1] - boundsLow[1])
        into[1] = density(p)
        into[2] = exp(-opticalDepth(p, sun, 0.25))
        into[3] = exp(-opticalDepth(p, sun, 1.0))
        into[4] = exp(-opticalDepth(p, sun))
        into[5] = exp(-opticalDepth(p, UP))
        into[6] = exp(-opticalDepth(p, w))
        into[7] = exp(-opticalDepth(p, DOWN))
        into[8] = cosTheta
        into[9] = hg(cosTheta, g) * 4.0 * PI / 10.0
        return into
    }

    /**
     * One Monte Carlo estimate of the radiance in-scattered at [p] toward [w], *excluding* the direct
     * sun (which the raymarcher adds analytically). Per unit scattering coefficient — multiply by
     * `sigma * density * albedo` to get a source term.
     *
     * The first direction is drawn from the phase function, so its weight is one; the path is
     * delta-tracked with majorant [sigma]; at every collision the sun is added by next-event
     * estimation with a ratio-tracked transmittance; escape adds [sky].
     */
    fun multipleScatter(p: DoubleArray, w: DoubleArray, random: SplittableRandom, maxBounces: Int = 24, into: DoubleArray = DoubleArray(3)): DoubleArray {
        into.fill(0.0)
        val x = p.copyOf()
        var out = w.copyOf()
        var throughput = 1.0
        val skyRgb = DoubleArray(3)
        for (bounce in 0 until maxBounces) {
            // light travelling along `travel` arrives and leaves along `out`; we trace toward its source
            val travel = sampleHg(out, g, random)
            val d = doubleArrayOf(-travel[0], -travel[1], -travel[2])
            val t = deltaTrack(x, d, random)
            if (t < 0.0) {
                sky(d, skyRgb)
                for (c in 0..2) into[c] += throughput * skyRgb[c]
                return into
            }
            for (a in 0..2) x[a] += d[a] * t
            throughput *= albedo
            val sunT = ratioTrack(x, sun, random)
            if (sunT > 0.0) {
                // sun light travels along -sun, leaves toward the previous vertex along -d
                val phase = hg(dot(sun, d), g)
                for (c in 0..2) into[c] += throughput * sunT * phase * sunColor[c]
            }
            out = doubleArrayOf(-d[0], -d[1], -d[2])
            if (bounce > 3) {
                val survive = min(1.0, throughput)
                if (random.nextDouble() > survive) return into
                throughput /= survive
            }
        }
        return into
    }

    private fun deltaTrack(p: DoubleArray, d: DoubleArray, random: SplittableRandom): Double {
        val s = span(p, d) ?: return -1.0
        var t = s[0]
        while (true) {
            t -= ln(1.0 - random.nextDouble()) / sigma
            if (t >= s[1]) return -1.0
            if (random.nextDouble() < density(p[0] + d[0] * t, p[1] + d[1] * t, p[2] + d[2] * t)) return t
        }
    }

    private fun ratioTrack(p: DoubleArray, d: DoubleArray, random: SplittableRandom): Double {
        val s = span(p, d) ?: return 1.0
        var t = s[0]
        var transmittance = 1.0
        while (true) {
            t -= ln(1.0 - random.nextDouble()) / sigma
            if (t >= s[1]) return transmittance
            transmittance *= 1.0 - density(p[0] + d[0] * t, p[1] + d[1] * t, p[2] + d[2] * t)
            if (transmittance < 1e-4) return 0.0
        }
    }

    /** A pinhole camera at [eye] looking at [target], [fovY] degrees vertically. */
    class Camera(val eye: DoubleArray, val target: DoubleArray, val fovY: Double = 38.0) {
        private val forward = normalize(sub(target, eye))
        private val right = normalize(cross(forward, UP))
        private val up = cross(right, forward)
        private val k = kotlin.math.tan(fovY * PI / 360.0)

        /** The ray direction through normalized image coordinates in `-1..1` (y up). */
        fun ray(u: Double, v: Double, aspect: Double): DoubleArray =
            normalize(DoubleArray(3) { forward[it] + right[it] * u * k * aspect + up[it] * v * k })
    }

    /** One way of supplying the multiple-scattering term to [render]. */
    fun interface InScatter {
        /** Radiance per unit scattering coefficient arriving at [p] toward [w], given its [features]. */
        fun at(p: DoubleArray, w: DoubleArray, features: DoubleArray, random: SplittableRandom, into: DoubleArray)
    }

    /** The path-traced reference: the mean of [paths] estimates of [multipleScatter]. */
    fun reference(paths: Int = 32) = InScatter { p, w, _, random, into ->
        val one = DoubleArray(3)
        into.fill(0.0)
        repeat(paths) {
            multipleScatter(p, w, random, into = one)
            for (c in 0..2) into[c] += one[c] / paths
        }
    }

    /** A linear image, row-major from the top, three floats per pixel. */
    class Image(val width: Int, val height: Int, val rgb: FloatArray = FloatArray(width * height * 3))

    /**
     * Raymarches the cloud through [camera] with [steps] steps per ray, single scattering from the
     * sun plus [inScatter] for everything else. Rows run in parallel; each row's random stream is
     * seeded from [seed] and its index, so a render is reproducible.
     */
    fun render(camera: Camera, width: Int, height: Int, inScatter: InScatter, steps: Int = 48, seed: Long = 1L): Image {
        val image = Image(width, height)
        val aspect = width.toDouble() / height
        IntStream.range(0, height).parallel().forEach { row ->
            val random = SplittableRandom(seed * 1_000_003L + row)
            val f = DoubleArray(FEATURES)
            val ms = DoubleArray(3)
            val skyRgb = DoubleArray(3)
            for (col in 0 until width) {
                val u = (col + 0.5) / width * 2.0 - 1.0
                val v = 1.0 - (row + 0.5) / height * 2.0
                val d = camera.ray(u, v, aspect)
                val rgb = march(camera.eye, d, steps, inScatter, random, f, ms)
                sky(d, skyRgb)
                val at = (row * width + col) * 3
                for (c in 0..2) image.rgb[at + c] = (rgb[c] + rgb[3] * skyRgb[c]).toFloat()
            }
        }
        return image
    }

    /** Returns in-scattered radiance (three values) and the remaining transmittance (fourth). */
    private fun march(eye: DoubleArray, d: DoubleArray, steps: Int, inScatter: InScatter, random: SplittableRandom, f: DoubleArray, ms: DoubleArray): DoubleArray {
        val result = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        val s = span(eye, d) ?: return result
        val dt = (s[1] - s[0]) / steps
        val w = doubleArrayOf(-d[0], -d[1], -d[2])
        val phaseSun = hg(dot(w, sun), g)
        val p = DoubleArray(3)
        for (i in 0 until steps) {
            val t = s[0] + (i + 0.5) * dt
            for (a in 0..2) p[a] = eye[a] + d[a] * t
            val rho = density(p)
            if (rho <= 0.0) continue
            val ext = sigma * rho
            features(p, w, f)
            inScatter.at(p, w, f, random, ms)
            val stepT = exp(-ext * dt)
            // ∫ T(s) σs L ds over the step, with L constant across it
            val weight = result[3] * albedo * (1.0 - stepT)
            for (c in 0..2) result[c] += weight * (sunColor[c] * f[4] * phaseSun + ms[c])
            result[3] *= stepT
            if (result[3] < 1e-3) break
        }
        return result
    }

    /**
     * This scene as GLSL — `#include "cloud/scene"` — with the same density, sky, features and
     * raymarcher as the Kotlin above, constants baked in. It expects `cloudNetwork(float[10])` and
     * `cloudAmbient(float[10])` to be declared first, which `#include "cloud/network"` does.
     *
     * `vec3 cloudRender(vec3 eye, vec3 target, float fovY, vec2 ndc, float aspect, int mode)` returns
     * linear radiance for one pixel; `ndc` is `-1..1` with y up. Modes: [MODE_SINGLE] single scattering
     * only, [MODE_AMBIENT] plus the fitted ambient term, [MODE_NETWORK] plus the network, and three debug
     * views — [MODE_DEBUG_INSCATTER] the network's predicted in-scatter, [MODE_DEBUG_SUN] the sun
     * transmittance and [MODE_DEBUG_SKY] the sky visibility, each averaged along the ray by opacity.
     */
    fun glslScene(): String {
        fun f(v: Double) = glslLiteral(v)
        fun v3(a: DoubleArray) = "vec3(${f(a[0])}, ${f(a[1])}, ${f(a[2])})"
        val blobLines = blobs.joinToString("\n") {
            "    { vec3 q = p - ${v3(it)}; shape = max(shape, 1.0 - length(q) / ${f(it[3])}); }"
        }
        return """
// Generated by CloudScene.glslScene(); mirrors afield.graphics.volume.CloudScene line for line.
const vec3 CLOUD_SUN = ${v3(sun)};
const vec3 CLOUD_SUN_COLOR = ${v3(sunColor)};
const vec3 CLOUD_LOW = ${v3(boundsLow)};
const vec3 CLOUD_HIGH = ${v3(boundsHigh)};
const float CLOUD_SIGMA = ${f(sigma)};
const float CLOUD_ALBEDO = ${f(albedo)};
const float CLOUD_G = ${f(g)};
const float CLOUD_PI = 3.14159265358979;

float cloudDensity(vec3 p) {
    float shape = 0.0;
$blobLines
    if (shape <= 0.0) return 0.0;
    float n = 0.5 * sin(3.1 * p.x + 1.7) * sin(2.7 * p.y + 0.4) * sin(2.9 * p.z + 2.1)
            + 0.25 * sin(6.3 * p.x - 4.1 * p.z + 0.3) * sin(5.7 * p.y + 1.1)
            + 0.125 * sin(11.9 * p.y + 7.3 * p.x) * sin(13.1 * p.z - 3.0);
    return clamp((shape + 0.45 * n - 0.12) * 2.2, 0.0, 1.0);
}

vec3 cloudSky(vec3 d) {
    if (d.y < 0.0) return ${v3(ground)};
    return mix(${v3(skyHorizon)}, ${v3(skyZenith)}, sqrt(d.y));
}

bool cloudSpan(vec3 o, vec3 d, out float t0, out float t1) {
    vec3 inv = 1.0 / d;
    vec3 a = (CLOUD_LOW - o) * inv, b = (CLOUD_HIGH - o) * inv;
    vec3 lo = min(a, b), hi = max(a, b);
    t0 = max(0.0, max(lo.x, max(lo.y, lo.z)));
    t1 = min(hi.x, min(hi.y, hi.z));
    return t0 <= t1;
}

float cloudOpticalDepth(vec3 p, vec3 d, float maxDistance) {
    float t0, t1;
    if (!cloudSpan(p, d, t0, t1)) return 0.0;
    float len = min(t1, maxDistance);
    if (len <= 0.0) return 0.0;
    float dt = len / 16.0, tau = 0.0;
    for (int i = 0; i < 16; ++i) tau += cloudDensity(p + d * ((float(i) + 0.5) * dt));
    return tau * dt * CLOUD_SIGMA;
}

float cloudHg(float c, float g) {
    float denom = 1.0 + g * g - 2.0 * g * c;
    return (1.0 - g * g) / (4.0 * CLOUD_PI * denom * sqrt(denom));
}

void cloudFeatures(vec3 p, vec3 w, out float f[10]) {
    float c = dot(w, CLOUD_SUN);
    f[0] = (p.y - CLOUD_LOW.y) / (CLOUD_HIGH.y - CLOUD_LOW.y);
    f[1] = cloudDensity(p);
    f[2] = exp(-cloudOpticalDepth(p, CLOUD_SUN, 0.25));
    f[3] = exp(-cloudOpticalDepth(p, CLOUD_SUN, 1.0));
    f[4] = exp(-cloudOpticalDepth(p, CLOUD_SUN, 1e30));
    f[5] = exp(-cloudOpticalDepth(p, vec3(0.0, 1.0, 0.0), 1e30));
    f[6] = exp(-cloudOpticalDepth(p, w, 1e30));
    f[7] = exp(-cloudOpticalDepth(p, vec3(0.0, -1.0, 0.0), 1e30));
    f[8] = c;
    f[9] = cloudHg(c, CLOUD_G) * 4.0 * CLOUD_PI / 10.0;
}

vec3 cloudRay(vec3 eye, vec3 target, float fovY, vec2 ndc, float aspect) {
    vec3 fw = normalize(target - eye);
    vec3 rt = normalize(cross(fw, vec3(0.0, 1.0, 0.0)));
    vec3 up = cross(rt, fw);
    float k = tan(fovY * CLOUD_PI / 360.0);
    return normalize(fw + rt * ndc.x * k * aspect + up * ndc.y * k);
}

vec3 cloudRender(vec3 eye, vec3 target, float fovY, vec2 ndc, float aspect, int mode) {
    vec3 d = cloudRay(eye, target, fovY, ndc, aspect);
    vec3 radiance = vec3(0.0), debug = vec3(0.0);
    float T = 1.0, t0, t1;
    if (cloudSpan(eye, d, t0, t1)) {
        float dt = (t1 - t0) / 48.0;
        vec3 w = -d;
        float phaseSun = cloudHg(dot(w, CLOUD_SUN), CLOUD_G);
        for (int i = 0; i < 48; ++i) {
            vec3 p = eye + d * (t0 + (float(i) + 0.5) * dt);
            float rho = cloudDensity(p);
            if (rho <= 0.0) continue;
            float f[10];
            cloudFeatures(p, w, f);
            vec3 ms = vec3(0.0);
            if (mode == $MODE_AMBIENT) ms = cloudAmbient(f);
            else if (mode >= $MODE_NETWORK) ms = cloudNetwork(f);
            float stepT = exp(-CLOUD_SIGMA * rho * dt);
            float weight = T * CLOUD_ALBEDO * (1.0 - stepT);
            radiance += weight * (CLOUD_SUN_COLOR * f[4] * phaseSun + ms);
            vec3 view = mode == $MODE_DEBUG_INSCATTER ? ms : (mode == $MODE_DEBUG_SUN ? vec3(f[4]) : vec3(f[5]));
            debug += T * (1.0 - stepT) * view;
            T *= stepT;
            if (T < 1e-3) break;
        }
    }
    if (mode >= $MODE_DEBUG_INSCATTER) return debug;
    return radiance + T * cloudSky(d);
}
""".trimStart()
    }

    companion object {
        const val FEATURES = 10
        const val MODE_SINGLE = 0
        const val MODE_AMBIENT = 1
        const val MODE_NETWORK = 2
        const val MODE_DEBUG_INSCATTER = 3
        const val MODE_DEBUG_SUN = 4
        const val MODE_DEBUG_SKY = 5

        /** A GLSL float literal for [v], always with a decimal point or exponent. */
        fun glslLiteral(v: Double): String {
            val s = String.format(java.util.Locale.ROOT, "%.9g", v)
            return if ('.' in s || 'e' in s) s else "$s.0"
        }

        val UP = doubleArrayOf(0.0, 1.0, 0.0)
        val DOWN = doubleArrayOf(0.0, -1.0, 0.0)

        /** Henyey–Greenstein, normalized over the sphere; [cosTheta] between travel directions. */
        fun hg(cosTheta: Double, g: Double): Double {
            val denom = 1.0 + g * g - 2.0 * g * cosTheta
            return (1.0 - g * g) / (4.0 * PI * denom * sqrt(denom))
        }

        /** A travel direction drawn from Henyey–Greenstein about [axis]. */
        fun sampleHg(axis: DoubleArray, g: Double, random: SplittableRandom): DoubleArray {
            val xi = random.nextDouble()
            val cosTheta = if (abs(g) < 1e-3) 1.0 - 2.0 * xi else {
                val s = (1.0 - g * g) / (1.0 - g + 2.0 * g * xi)
                (1.0 + g * g - s * s) / (2.0 * g)
            }
            val sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta))
            val phi = 2.0 * PI * random.nextDouble()
            val a = if (abs(axis[0]) > 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
            val t = normalize(cross(axis, a))
            val b = cross(axis, t)
            return DoubleArray(3) { axis[it] * cosTheta + (t[it] * cos(phi) + b[it] * sin(phi)) * sinTheta }
        }

        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun normalize(a: DoubleArray): DoubleArray {
            val l = sqrt(dot(a, a)); return doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
        }
    }
}

/** A labelled training sample: [CloudScene.features] and the path-traced in-scatter. */
class CloudSample(val features: DoubleArray, val target: DoubleArray)

/**
 * A trained in-scatter network and the scale its outputs were trained in. Use it as a
 * [CloudScene.InScatter] with [inScatter], or on the GPU through [glslNetwork].
 */
class NeuralCloud(val weights: MlpWeights, val outputScale: Double, val trainingLoss: DoubleArray) {

    val inScatter = CloudScene.InScatter { _, _, f, _, into ->
        val x = FloatArray(f.size) { f[it].toFloat() }
        val y = weights.evaluate(x)
        for (c in 0..2) into[c] = max(0.0, y[c] * outputScale)
    }

    /**
     * The network as a GLSL function `vec3 cloudNetwork(float x[10])` with every weight a literal in
     * a `const float[]` — the "weights in shader constants" form, no buffer needed. Accumulates in
     * the order `MlpWeights.evaluate` does.
     */
    fun glslNetwork(name: String = "cloudNetwork"): String {
        val w = weights
        val out = StringBuilder()
        out.append("// $w, trained by NeuralCloud.train; outputs scaled by $outputScale\n")
        out.append("const float ${name}W[${w.flat.size}] = float[](")
        w.flat.forEachIndexed { i, v -> if (i > 0) out.append(','); if (i % 8 == 0) out.append("\n    "); out.append(glslFloat(v)) }
        out.append("\n);\n")
        out.append("vec3 $name(float x[${w.inputs}]) {\n")
        for (layer in 0 until w.layerCount) {
            val n = w.layerWidths[layer]; val p = w.layerInputs[layer]
            val wo = w.weightOffset(layer); val bo = w.biasOffset(layer)
            val last = layer == w.layerCount - 1
            val src = if (layer == 0) "x" else "h${layer - 1}"
            val dst = if (last) "y" else "h$layer"
            out.append("    float $dst[$n];\n")
            out.append("    for (int o = 0; o < $n; ++o) {\n")
            out.append("        float s = ${name}W[$bo + o];\n")
            out.append("        for (int i = 0; i < $p; ++i) s += ${name}W[$wo + o * $p + i] * $src[i];\n")
            out.append("        $dst[o] = ${if (last) "s" else w.activation.glslExpression("s")};\n")
            out.append("    }\n")
        }
        out.append("    return max(vec3(y[0], y[1], y[2]) * ${glslFloat(outputScale.toFloat())}, vec3(0.0));\n}\n")
        return out.toString()
    }

    companion object {
        /**
         * Draws [count] training points where [cameras] see cloud — a random pixel, a random step
         * along its ray, kept if the density there is non-zero — and labels each with the mean of
         * [pathsPerSample] path-traced estimates.
         */
        fun samples(scene: CloudScene, cameras: List<CloudScene.Camera>, count: Int, pathsPerSample: Int = 8, seed: Long = 7L): List<CloudSample> {
            val chunk = 256
            val chunks = (count + chunk - 1) / chunk
            return IntStream.range(0, chunks).parallel().mapToObj { c ->
                val random = SplittableRandom(seed * 7919L + c)
                val reference = scene.reference(pathsPerSample)
                val list = ArrayList<CloudSample>(chunk)
                while (list.size < min(chunk, count - c * chunk)) {
                    val camera = cameras[random.nextInt(cameras.size)]
                    val d = camera.ray(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, 1.6)
                    val s = scene.span(camera.eye, d) ?: continue
                    val t = s[0] + random.nextDouble() * (s[1] - s[0])
                    val p = DoubleArray(3) { camera.eye[it] + d[it] * t }
                    if (scene.density(p) <= 0.0) continue
                    val w = doubleArrayOf(-d[0], -d[1], -d[2])
                    val f = scene.features(p, w)
                    val y = DoubleArray(3)
                    reference.at(p, w, f, random, y)
                    list += CloudSample(f, y)
                }
                list
            }.toList().flatten()
        }

        /** Fits a `VectorMLP` of [hidden] widths to [samples] with RMSProp and a decaying rate. */
        fun train(
            samples: List<CloudSample>,
            hidden: IntArray = intArrayOf(16, 16),
            steps: Int = 6000,
            batch: Int = 64,
            learningRate: Double = 3e-3,
            finalLearningRate: Double = 1e-4,
            seed: Long = 11L,
        ): NeuralCloud {
            smile.math.MathEx.setSeed(seed)
            val scale = samples.maxOf { it.target.max() }.coerceAtLeast(1e-6) * 0.5
            val net = VectorMLP(CloudScene.FEATURES, 3, hidden, "tanh")
            net.configure(TimeFunction.linear(learningRate, steps.toDouble(), finalLearningRate), 0.0, 0.9)
            val random = SplittableRandom(seed)
            val y = DoubleArray(3)
            val losses = DoubleArray(steps)
            for (step in 0 until steps) {
                var loss = 0.0
                for (i in 0 until batch) {
                    val s = samples[random.nextInt(samples.size)]
                    for (c in 0..2) y[c] = s.target[c] / scale
                    loss += net.accumulate(s.features, y)
                }
                net.step(batch)
                losses[step] = loss / batch
            }
            return NeuralCloud(net.exportWeights(), scale, losses)
        }

        /**
         * The cheap approximation the network is compared against: in-scatter as a constant colour
         * times the sky visibility `exp(-τ_up)` plus a constant colour times the sun transmittance —
         * six coefficients fitted to [samples] by least squares, so it is the best this form can do.
         */
        fun ambient(samples: List<CloudSample>): AmbientFit {
            val coefficients = Array(3) { c ->
                // normal equations for y ≈ a·f5 + b·f4
                var s55 = 0.0; var s54 = 0.0; var s44 = 0.0; var s5y = 0.0; var s4y = 0.0
                for (s in samples) {
                    val a = s.features[5]; val b = s.features[4]; val y = s.target[c]
                    s55 += a * a; s54 += a * b; s44 += b * b; s5y += a * y; s4y += b * y
                }
                val det = s55 * s44 - s54 * s54
                doubleArrayOf((s5y * s44 - s4y * s54) / det, (s4y * s55 - s5y * s54) / det)
            }
            return AmbientFit(coefficients)
        }

        /** The `#include "cloud/network"` text for a network and its ambient baseline, both `vec3 f(float[10])`. */
        fun glslInclude(net: NeuralCloud?, ambient: AmbientFit?): String {
            val n = net?.glslNetwork() ?: "// no network trained yet: cloudNetwork returns zero\nvec3 cloudNetwork(float x[10]) { return vec3(0.0); }\n"
            val a = ambient?.glsl() ?: "vec3 cloudAmbient(float x[10]) { return vec3(0.0); }\n"
            return n + "\n" + a
        }

        private fun glslFloat(v: Float): String {
            val s = v.toString()
            return if ('E' in s || 'e' in s) String.format(java.util.Locale.ROOT, "%.9e", v) else s
        }
    }
}

/** The fitted single-scatter-plus-ambient baseline: per channel, `a·exp(-τ_up) + b·exp(-τ_sun)`. */
class AmbientFit(val coefficients: Array<DoubleArray>) : CloudScene.InScatter {
    override fun at(p: DoubleArray, w: DoubleArray, features: DoubleArray, random: SplittableRandom, into: DoubleArray) {
        for (c in 0..2) into[c] = max(0.0, coefficients[c][0] * features[5] + coefficients[c][1] * features[4])
    }

    /** As GLSL, `vec3 cloudAmbient(float x[10])`. */
    fun glsl(): String {
        fun v(i: Int) = "vec3(${(0..2).joinToString { CloudScene.glslLiteral(coefficients[it][i]) }})"
        return "vec3 cloudAmbient(float x[10]) { return max(${v(0)} * x[5] + ${v(1)} * x[4], vec3(0.0)); }\n"
    }
}

/** Error of [image] against [reference], over pixels where the reference differs from bare sky. */
data class CloudError(val rmse: Double, val relativeMeanAbs: Double, val pixels: Int)

fun cloudError(image: CloudScene.Image, reference: CloudScene.Image, mask: CloudScene.Image): CloudError {
    var se = 0.0; var ae = 0.0; var total = 0.0; var n = 0
    for (i in 0 until image.width * image.height) {
        val k = i * 3
        val cloud = (0..2).any { abs(mask.rgb[k + it] - reference.rgb[k + it]) > 1e-4f }
        if (!cloud) continue
        n++
        for (c in 0..2) {
            val e = (image.rgb[k + c] - reference.rgb[k + c]).toDouble()
            se += e * e; ae += abs(e); total += reference.rgb[k + c]
        }
    }
    return CloudError(sqrt(se / (3 * max(1, n))), ae / max(1e-9, total), n)
}

/** Writes images side by side — top row the renders, bottom row `|image - reference| * gain`. */
fun writeCloudComparison(file: File, reference: CloudScene.Image, others: List<CloudScene.Image>, errorGain: Float = 4f) {
    val w = reference.width; val h = reference.height
    val all = listOf(reference) + others
    val out = BufferedImage(w * all.size, h * 2, BufferedImage.TYPE_INT_RGB)
    fun tone(v: Float) = (255.0 * (1.0 - exp(-v.toDouble() * 1.2)).pow(1 / 2.2)).toInt().coerceIn(0, 255)
    for ((index, image) in all.withIndex()) for (y in 0 until h) for (x in 0 until w) {
        val k = (y * w + x) * 3
        out.setRGB(index * w + x, y, (tone(image.rgb[k]) shl 16) or (tone(image.rgb[k + 1]) shl 8) or tone(image.rgb[k + 2]))
        val e = if (index == 0) 0 else (0..2).sumOf { abs(image.rgb[k + it] - reference.rgb[k + it]).toDouble() }.let { (255 * min(1.0, it / 3 * errorGain)).toInt() }
        out.setRGB(index * w + x, h + y, (e shl 16) or (e shl 8) or e)
    }
    file.parentFile?.mkdirs()
    ImageIO.write(out, "png", file)
}

/** Writes one linear image as a tone-mapped PNG. */
fun writeCloudImage(file: File, image: CloudScene.Image) = writeCloudComparison(file, image, emptyList(), 0f).let {
    // the comparison writer always appends an error row; crop it off
    val full = ImageIO.read(file)
    ImageIO.write(full.getSubimage(0, 0, image.width, image.height), "png", file)
}

/** What `#include "cloud/scene"` and `#include "cloud/network"` resolve to, per box and its ancestors. */
internal object CloudIncludes {
    private val byBox = java.util.WeakHashMap<Box, MutableMap<String, String>>()
    private val defaultScene by lazy { CloudScene().glslScene() }

    @Synchronized fun put(box: Box, name: String, source: String) { byBox.getOrPut(box) { HashMap() }[name] = source }

    @Synchronized fun find(box: Box?, name: String): String? {
        if (box != null) {
            val seen = HashSet<Box>()
            var frontier = listOf(box)
            while (frontier.isNotEmpty()) {
                for (b in frontier) byBox[b]?.get(name)?.let { return it }
                seen += frontier
                frontier = frontier.flatMap { b -> b.parents.filter { it !in seen } }.distinct()
            }
        }
        return when (name) {
            "scene" -> defaultScene
            "network" -> NeuralCloud.glslInclude(null, null)
            else -> null
        }
    }
}

/** The text of `#include "cloud/<name>"` for a shader of [inside]; called by the shader include resolver. */
fun cloudIncludeSource(inside: Box?, name: String): String = CloudIncludes.find(inside, name.removeSuffix(".glsl"))
    ?: throw IllegalStateException("#include \"cloud/$name\": the cloud includes are \"cloud/network\" and \"cloud/scene\"")

/**
 * Makes [net] and [ambient] the `#include "cloud/network"` of this box's shaders and its descendants',
 * and [scene] their `#include "cloud/scene"`. Before any box calls this, the network include
 * returns zero and the scene include is the default [CloudScene], so a shader always compiles.
 *
 * Call it in a box *above* the shader box: a box's shaders compile before its own code runs.
 * Re-run the shader box after retraining to pick up the new weights.
 */
fun Box.cloudHere(net: NeuralCloud?, ambient: AmbientFit?, scene: CloudScene = CloudScene()) {
    CloudIncludes.put(this, "network", NeuralCloud.glslInclude(net, ambient))
    CloudIncludes.put(this, "scene", scene.glslScene())
}
