package afield.four.fabricated

import afield.four.Renderer
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Real GPU checks for the path tracer's participating medium: `PathTracingRenderer.mediumDensity`
 * and everything that is sampled along a ray rather than at a surface.
 *
 * A medium adds three probability densities to an integrator: a distance density, a phase
 * function, and the transmittance that multiplies a light connection. Each has a failure that
 * darkens or brightens the image without otherwise distorting it, so a render cannot be told from
 * a correct one by looking at it — a distance pdf missing its extinction factor, a phase function
 * normalised to 1 instead of 1/4π, a transmittance applied twice, once analytically on the
 * connection and once through the collision event that already estimated it. So each arm is
 * checked against a number that exists independently of this code:
 *
 *  - `inertWithoutExtinction` — extinction zero, with albedo, anisotropy and bounds all set to
 *    something conspicuous, must reach the previous integrator bit for bit. The distance sample has
 *    no Sobol dimension of its own, it shares the BSDF lobe selector's and the escape branch remaps
 *    it back to uniform, so "the medium is off" and "the sample stream is untouched" are two
 *    different claims and this is the second one.
 *  - `absorptionAgainstClosedForm` — the absolute anchor. A purely absorbing medium (albedo zero)
 *    in front of an emissive plane must attenuate by exactly `exp(-σt·d)`, per pixel, with `d` the
 *    camera-to-plane distance. That is a closed form, not a converged reference, so no common-mode
 *    error can hide in it. Run at two densities an order of magnitude apart in transmittance, and
 *    in a *bounded* configuration too, where the exponent is the slab-crossing length and what is
 *    under test is the shader's ray-to-bounds clip rather than the exponential.
 *  - `absorptionAgainstDepthFog` — the same scene and the same closed form, reached through
 *    `fogDensity`, which computes its own `exp(-σ·primaryDepth)` and shares nothing with the medium
 *    but the scene. It is also the arm that tests the closed form itself: the analytic `d` is a
 *    Kotlin reconstruction of the ray the shader builds, and a wrong reconstruction fails here
 *    instead of agreeing with itself.
 *  - `scatteringFurnace` — energy conservation, asserted at 1.0. An albedo-one medium embedded in a
 *    uniform unit radiance field is in exact equilibrium, since `L = 1` everywhere solves the
 *    transport equation. The scene has no geometry in it, so no BSDF, no area pdf and no surface
 *    reflectance can contribute to the answer or account for it. Repeated across six anisotropies,
 *    which extends it from a normalisation check to a check that
 *    `PathTracingRenderer.mediumAnisotropy`'s density and its sampling routine describe the same
 *    distribution: were they to disagree, the phase arm's implicit weight of one would be wrong and
 *    the furnace would not close.
 *  - `furnaceBounceSweep` — the same furnace at four times the density, where the truncation
 *    deficit is large enough to characterise. The mean rises monotonically with the bounce budget
 *    and never passes 1.0, which is the signature of truncation; a normalisation error would sit at
 *    a fixed offset and a double-counted arm would overshoot.
 *  - `singleScatteringAgainstQuadrature` — the arm that shares no code and no scene with anything
 *    else. A slab of medium under a constant environment, one bounce, is a one-dimensional integral
 *    of the radiative transfer equation that a Kotlin quadrature can evaluate to eight figures. It
 *    pins the distance pdf, the albedo weight, the phase normalisation and the transmittance on the
 *    environment connection *simultaneously and absolutely*, against numerics written from the
 *    equation rather than from the shader.
 *  - `emitterConnectionIsInvisible` — the cross-mechanism arm for the one mechanism the furnace and
 *    the quadrature cannot reach: the connection from a scattering point to *emissive geometry*,
 *    with its area-to-solid-angle Jacobian and its own transmittance. A uniformly emissive
 *    enclosure is a radiance field in which an albedo-one medium is invisible, so the reference is
 *    the same scene rendered with the medium off, and the medium's emitter pdf appears in one of
 *    the two arms only. That premise is exact only for a non-reflecting emitter, and every
 *    dielectric this BSDF can express returns a few percent of what it is lit by, so the arm runs
 *    three configurations — a black metal, a dielectric, and the dielectric pair with the
 *    connection under test switched off in both — and ends in a bound on that connection's error
 *    rather than in an identity.
 *  - `lampInFog` — two small lamps in fog, which is the scene the variance claim is measured in.
 *    Its bias residual is reported at two reference budgets, because a fixed tolerance on a
 *    residual is a tolerance on noise; and its variance ratio is asserted at a floor, not printed.
 *
 * Every arm then runs again on each traversal backend the device exposes. The medium touches no
 * traversal code and the analytic anchors are backend-independent, so a backend that misses them
 * when the other does not has put the disagreement in traversal rather than in the estimator, which
 * is a distinction no single pass can draw. Two further arms render the figures, and the
 * numbers are written to a `report.txt` per backend after every arm as well as printed, because a
 * box's `println` reaches the server log through a buffer a backdoor teardown does not wait for.
 */

/**
 * Eight arms measured at 96x72 and two that render figures, all repeated on each backend. A live
 * renderer is required throughout, so this carries no `FIELD_TEST` annotation and is driven from a
 * box instead:
 * `dev_docs/agents/demos/path_tracing_medium/verify.yaml` is the transcript that does it.
 */
object PathTraceMediumGpuSmoke {
    private const val WIDTH = 96
    private const val HEIGHT = 72
    private const val PIXELS = WIDTH * HEIGHT

    /**
     * The absorption arms estimate `exp(-σt·d)` with a *binary* no-collision event, so their
     * per-pixel error is Bernoulli and only the budget shrinks it. At transmittance 0.44 this is a
     * 0.9% per-pixel standard error, 1e-4 over the whole image, which is what the floors below are
     * sized against.
     */
    private const val ABSORPTION_SAMPLES = 65536

    /** The furnace and the enclosure have a uniform near-field radiance, so they converge fast. */
    private const val FURNACE_SAMPLES = 16384

    /** The lamp box's high reference, and the low one it is compared against to separate noise from bias. */
    private const val LAMP_REFERENCE = 65536
    private const val LAMP_LOW_REFERENCE = 4096

    /** The equal, stated budget at which RMSE is compared. One sample per frame keeps it exact. */
    private const val MEASURED_SAMPLES = 256

    /**
     * Every measurement this file makes, kept as well as printed.
     *
     * A box's `println` reaches the server log through a buffer that a `--scratch` backdoor
     * teardown does not wait for: the first shake-out run of this harness lost the whole lamp
     * section's numbers that way and left only the exception text, which is the one line that
     * travels over the backdoor protocol instead. So the report is also written to a file, after
     * every arm rather than at the end, because the arm that fails is the one whose numbers matter.
     */
    private val report = StringBuilder()
    private fun say(line: String) { println(line); report.appendLine(line) }

    /**
     * The backend every tracer in this suite is built on, set once per pass by [run].
     *
     * It is a `var` rather than a parameter threaded through [converge] because every arm calls
     * [converge] and none of them has any business choosing a backend: the whole point is that the
     * same arm runs unchanged on each backend the device exposes, and the only correct value is
     * whichever pass is currently executing. Threading it would put a parameter on ten call sites
     * that must never disagree. The harness is a single sequential suspend function, so there is no
     * concurrent reader.
     *
     * The medium touches no traversal code, which is exactly why comparing backends is worth doing
     * rather than assumed: the analytic anchors — `exp(-sigma_t * d)` and the furnace's absolute
     * 1.0 — are backend-independent, so if one backend misses them and the other does not, the
     * disagreement is in traversal and not in the estimator.
     */
    private var activeBackend = PathTracingRenderer.Backend.COMPUTE_BVH
    private fun flushReport(out: File) {
        out.mkdirs()
        File(out, "report.txt").writeText(report.toString())
    }

    // --------------------------------------------------------------- geometry

    /**
     * Every scene here needs at least one triangle: a zero-triangle scene would upload zero-byte
     * device buffers. This is that triangle, a hundred times further away than anything else and a
     * millimetre across, so it subtends about 1e-14 steradians from the origin. Its material is a
     * black *metal* rather than a black dielectric, because `evaluate` gives every dielectric an f0
     * of 0.04 and a metal with a black base has an f0 of zero — the least reflective surface this
     * BSDF can express, for the one surface these scenes would rather not have.
     */
    private fun PathTraceScene.negligibleTriangle(): PathTraceScene {
        val dark = material(PathTraceMaterial(red = 0f, green = 0f, blue = 0f, metallic = 1f, roughness = 1f))
        return triangle(Vector3f(100000f, 100000f, 100000f), Vector3f(100000.01f, 100000f, 100000f),
            Vector3f(100000f, 100000.01f, 100000f), dark)
    }

    /** An emissive plane at z = 0 facing +z, wide enough that every pixel of [planeCamera] lands on it. */
    private fun emissivePlane(radiance: Vector3f): PathTraceScene = PathTraceScene().apply {
        val glow = material(PathTraceMaterial.emissive(radiance.x, radiance.y, radiance.z))
        quad(Vector3f(-8f, -8f, 0f), Vector3f(8f, -8f, 0f), Vector3f(8f, 8f, 0f), Vector3f(-8f, 8f, 0f), glow)
    }

    private const val PLANE_CAMERA_Z = 3f
    private fun planeCamera(renderer: Renderer) = Camera(renderer).also {
        it.at.set(0f, 0f, PLANE_CAMERA_Z); it.target.set(0f, 0f, 0f); it.p.fov = 0.9
    }

    /** Uniformly emissive two-sided cube of half-extent [half]. Radiance is the same on all six faces. */
    private fun enclosure(half: Float, radiance: Float,
                          faceMaterial: PathTraceMaterial =
                              PathTraceMaterial.emissive(radiance, radiance, radiance)): PathTraceScene = PathTraceScene().apply {
        val h = half
        val face = material(faceMaterial)
        quad(Vector3f(-h, -h, -h), Vector3f(h, -h, -h), Vector3f(h, -h, h), Vector3f(-h, -h, h), face)
        quad(Vector3f(-h, h, -h), Vector3f(h, h, -h), Vector3f(h, h, h), Vector3f(-h, h, h), face)
        quad(Vector3f(-h, -h, -h), Vector3f(-h, h, -h), Vector3f(h, h, -h), Vector3f(h, -h, -h), face)
        quad(Vector3f(-h, -h, h), Vector3f(-h, h, h), Vector3f(h, h, h), Vector3f(h, -h, h), face)
        quad(Vector3f(-h, -h, -h), Vector3f(-h, h, -h), Vector3f(-h, h, h), Vector3f(-h, -h, h), face)
        quad(Vector3f(h, -h, -h), Vector3f(h, h, -h), Vector3f(h, h, h), Vector3f(h, -h, h), face)
    }

    private fun insideCamera(renderer: Renderer) = Camera(renderer).also {
        it.at.set(0f, 0f, 0f); it.target.set(0.2f, -0.1f, -1f); it.p.fov = 1.2
    }

    /**
     * A small bright lamp above a floor, with the whole room full of medium. The lamp is small on
     * purpose: a phase-sampled ray almost never finds it, which is where the variance claim lives,
     * and a shadow ray that was not shortened to stop before the light would find the lamp itself
     * and discard every direct contribution — a correct image with no improvement in it, which
     * nothing but a variance floor detects.
     */
    private fun lampBox(): PathTraceScene = PathTraceScene().apply {
        val floor = material(PathTraceMaterial.diffuse(0.62f, 0.60f, 0.56f))
        val wall = material(PathTraceMaterial.diffuse(0.30f, 0.34f, 0.42f))
        val ball = material(PathTraceMaterial.plastic(0.80f, 0.78f, 0.74f, 0.35f))
        val warm = material(PathTraceMaterial.emissive(90f, 74f, 46f))
        quad(Vector3f(-6f, 0f, -6f), Vector3f(-6f, 0f, 6f), Vector3f(6f, 0f, 6f), Vector3f(6f, 0f, -6f), floor)
        quad(Vector3f(-6f, 0f, -4f), Vector3f(-6f, 6f, -4f), Vector3f(6f, 6f, -4f), Vector3f(6f, 0f, -4f), wall)
        sphere(Vector3f(-1.1f, 0.75f, 0.9f), 0.75f, ball, 40, 20)
        quad(Vector3f(0.85f, 3.0f, -0.6f), Vector3f(1.25f, 3.0f, -0.6f),
            Vector3f(1.25f, 3.0f, -0.2f), Vector3f(0.85f, 3.0f, -0.2f), warm)
    }

    private fun lampCamera(renderer: Renderer) = Camera(renderer).also {
        it.at.set(0.1f, 1.7f, 7.2f); it.target.set(0.1f, 1.2f, 0f); it.p.fov = 0.8
    }

    /**
     * The demo scene, kept here so its picture is rendered by the same code that measures
     * everything else: a bright strip light with four slats under it, the whole room hazed, viewed
     * down the axis everything is extruded along so the shafts are seen in cross-section.
     *
     * Shafts of light are the one image a depth-fog curve cannot fake. `fogDensity` is a function
     * of the primary hit distance, so it is constant along an iso-distance surface and has no way
     * to know that a slat is between this point and the lamp. A real medium's scattering points
     * each run their own shadow ray, and the ones behind a slat come back dark. [godRaysCapture]
     * is the side-by-side.
     */
    fun godRays(): PathTraceScene = PathTraceScene().apply {
        // Dark: the shafts are a contrast between lit haze and shadowed haze, and a bright room
        // bounces enough light back into the medium to wash both of them toward the same grey.
        val floor = material(PathTraceMaterial.diffuse(0.32f, 0.30f, 0.27f))
        val wall = material(PathTraceMaterial.diffuse(0.16f, 0.17f, 0.21f))
        val slat = material(PathTraceMaterial.diffuse(0.10f, 0.09f, 0.08f))
        val lamp = material(PathTraceMaterial.emissive(340f, 300f, 230f))
        val ball = material(PathTraceMaterial.plastic(0.78f, 0.76f, 0.72f, 0.30f))
        quad(Vector3f(-6f, 0f, -4f), Vector3f(-6f, 0f, 4f), Vector3f(6f, 0f, 4f), Vector3f(6f, 0f, -4f), floor)
        quad(Vector3f(-6f, 0f, -4f), Vector3f(-6f, 5.4f, -4f), Vector3f(6f, 5.4f, -4f), Vector3f(6f, 0f, -4f), wall)
        // Thin in x and long in z, so the light fans out in the x-y plane the camera is looking at.
        quad(Vector3f(-0.16f, 4.9f, -3.6f), Vector3f(0.16f, 4.9f, -3.6f),
            Vector3f(0.16f, 4.9f, 3.6f), Vector3f(-0.16f, 4.9f, 3.6f), lamp)
        for (x in listOf(-1.75f, -0.62f, 0.62f, 1.75f)) {
            quad(Vector3f(x - 0.26f, 3.5f, -3.6f), Vector3f(x + 0.26f, 3.5f, -3.6f),
                Vector3f(x + 0.26f, 3.5f, 3.6f), Vector3f(x - 0.26f, 3.5f, 3.6f), slat)
        }
        sphere(Vector3f(1.5f, 0.85f, 0.6f), 0.85f, ball, 40, 20)
    }

    /**
     * Level, and square on to the x-y plane the shafts fan out in, so the slats are seen nearly
     * edge-on and the shafts across rather than down their length.
     */
    fun godRaysCamera(renderer: Renderer) = Camera(renderer).also {
        it.at.set(0.15f, 2.35f, 9.2f); it.target.set(0.15f, 2.25f, 0f); it.p.fov = 0.82
    }

    /**
     * Bounds for [godRays], in the form `mediumBounds` takes: the floor's full width, the z extent
     * inset a tenth of a unit from the ends the room is extruded to, and a ceiling at 5.2, above
     * the strip light and below the top of the back wall.
     */
    val GOD_RAYS_BOUNDS = Vector3f(-6f, 0f, -3.9f) to Vector3f(6f, 5.2f, 3.9f)

    /**
     * The demo's extinction, and a figure that wants a medium you can see through rather than the
     * densest one the integrator handles. At 0.30 the haze is about two and a third optical
     * depths along the view and obscures itself, so the wall, the floor and the slats' shadow
     * wedges wash into one grey.
     */
    const val GOD_RAYS_DENSITY = 0.16f

    private val LAMP_BOUNDS = Vector3f(-6f, 0f, -4f) to Vector3f(6f, 4.2f, 6f)

    // ------------------------------------------------------------------- rays

    /**
     * The primary ray through the centre of each pixel, reconstructed exactly as
     * `PathTracingRenderer.updateFrame` builds the camera basis and `main` builds the direction
     * from it — right scaled by `tan(fov/2)·width/height`, up by `tan(fov/2)`, and up *subtracted*
     * because row zero is the top of the image.
     *
     * This is duplicated knowledge, and a closed-form reference built on it is only as good as the
     * duplication. [absorptionAgainstDepthFog] is the arm that checks it, against a distance the
     * shader itself measures.
     */
    private class Rays(camera: Camera, private val width: Int, private val height: Int) {
        val origin: Vector3f = Vector3f(camera.at)
        private val forward = camera.forward
        private val right: Vector3f
        private val up: Vector3f
        init {
            val r = camera.right
            val u = Vector3f(r).cross(forward).normalize()
            val scale = tan(camera.p.fov * 0.5).toFloat()
            right = Vector3f(r).mul(scale * width / height)
            up = Vector3f(u).mul(scale)
        }
        fun direction(pixel: Int): Vector3f {
            val x = ((pixel % width) + 0.5f) / width * 2f - 1f
            val y = ((pixel / width) + 0.5f) / height * 2f - 1f
            return Vector3f(forward).add(Vector3f(right).mul(x)).sub(Vector3f(up).mul(y)).normalize()
        }
    }

    // -------------------------------------------------------------- machinery

    private class Shot(val pixels: FloatArray, val samples: Int, val emitters: Int)

    /** Per-tracer medium settings, so a scene and its estimator can be varied independently. */
    private class Medium(val density: Float, val albedo: Vector3f = Vector3f(1f),
                         val anisotropy: Float = 0f,
                         val bounds: Pair<Vector3f, Vector3f>? = null) {
        companion object { val none = Medium(0f) }
    }

    private suspend fun awaitSamples(tracer: PathTracingRenderer, count: Int, seconds: Long = 900) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (tracer.samples < count) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $count samples; got ${tracer.samples}" }
            delay(5)
        }
    }

    private suspend fun converge(renderer: Renderer, scene: PathTraceScene,
                                 environment: PathTraceEnvironment, camera: Camera,
                                 medium: Medium, target: Int, perFrame: Int,
                                 bounces: Int, emitters: Boolean = true,
                                 showBackground: Boolean = false, jitter: Float = 0f,
                                 width: Int = WIDTH, height: Int = HEIGHT,
                                 fogDensity: Float = 0f,
                                 fogColor: Vector3f = Vector3f(0f)): Shot {
        val tracer = PathTracingRenderer(renderer, scene, environment, width, height, camera,
            activeBackend, name = "medium-check")
        return tracer.use {
            tracer.mediumDensity = medium.density
            tracer.mediumAlbedo = medium.albedo
            tracer.mediumAnisotropy = medium.anisotropy
            tracer.mediumBounds = medium.bounds
            tracer.fogDensity = fogDensity
            tracer.fogColor = fogColor
            tracer.fogStartDistance = 0f
            tracer.emitterSampling = emitters
            tracer.maxBounces = bounces
            tracer.showEnvironment = showBackground
            tracer.background = Vector4f(0f, 0f, 0f, 1f)
            // Both configurations of any comparison must sample the identical pixel footprint, or
            // a per-pixel comparison is measuring antialiasing rather than the estimator.
            tracer.pixelJitter = jitter
            tracer.samplesPerFrame = perFrame
            tracer.install()
            awaitSamples(tracer, target)
            tracer.paused = true
            val pixels = tracer.readLinear()
            check(pixels.all { it.isFinite() && it >= 0f }) {
                val bad = pixels.withIndex().filter { !it.value.isFinite() || it.value < 0f }.take(8)
                "Nonfinite or negative radiance: $bad"
            }
            Shot(pixels, tracer.samples, tracer.emitterCount)
        }
    }

    private fun luminance(p: FloatArray, i: Int) = 0.2126 * p[i * 4] + 0.7152 * p[i * 4 + 1] + 0.0722 * p[i * 4 + 2]

    /**
     * Two runs that stopped on the same sample. They cannot be *made* to: `awaitSamples` polls and
     * the render thread may fit one more dispatch in between, and an equal-budget claim has to be
     * true, so the second run targets whatever the first actually reached and the pair is retried.
     */
    private suspend fun matched(first: suspend (Int) -> Shot, second: suspend (Int) -> Shot,
                                target: Int): Pair<Shot, Shot> {
        var a = first(target); var b = second(a.samples); var attempts = 0
        while (a.samples != b.samples && attempts++ < 6) {
            if (a.samples < b.samples) a = first(b.samples) else b = second(a.samples)
        }
        check(a.samples == b.samples) { "Could not match sample counts: ${a.samples} vs ${b.samples}" }
        return a to b
    }

    /**
     * Relative agreement of two images: whole-image, worst tile, and the per-pixel spread.
     *
     * [signed] is the whole-image difference with its sign kept, and it earns its place. The first
     * full run of [lampInFog] reported a 2.9% whole-image disagreement that did not shrink when the
     * reference's budget grew sixteenfold — which reads as bias until you look at the sign. It was
     * positive: the arm with light connections on was *brighter*, because next-event estimation
     * reaches one segment past `maxBounces` and so transports more light for the same budget. An
     * unsigned number cannot tell "more transport" from "double counting", and those want opposite
     * responses.
     */
    private class Agreement(val pixels: Int, val global: Double, val signed: Double,
                            val worstTile: String, val worstTileValue: Double, val tiles: Int,
                            val median: Double, val p95: Double, val skipped: Int) {
        override fun toString() = "$pixels px: global ${show(global)} (signed ${show(signed)}), " +
            "worst of $tiles tiles ${show(worstTileValue)} (tile $worstTile), per-pixel median " +
            "${show(median)}, p95 ${show(p95)}" + if (skipped > 0) ", $skipped px skipped as zero" else ""
    }

    /**
     * A whole-image mean can average away a spatially structured error, which is the shape a wrong
     * Jacobian or a wrong span has, so the tiles are the assertion that matters and the global mean
     * is only the tightest one. Errors are independent per pixel, so a tile of 288 of them is a far
     * stronger statement than any single pixel.
     */
    private fun agree(a: FloatArray, b: FloatArray, pixels: List<Int>): Agreement {
        var sumA = 0.0; var sumB = 0.0
        pixels.forEach { i -> sumA += luminance(a, i); sumB += luminance(b, i) }
        val tiles = (0 until 4).flatMap { ty -> (0 until 6).map { tx -> tx to ty } }.mapNotNull { (tx, ty) ->
            val inTile = pixels.filter {
                it % WIDTH in tx * 16 until tx * 16 + 16 && it / WIDTH in ty * 18 until ty * 18 + 18
            }
            if (inTile.size < 32) null else {
                val ta = inTile.sumOf { luminance(a, it) }
                val tb = inTile.sumOf { luminance(b, it) }
                "$tx,$ty" to abs(ta - tb) / tb
            }
        }
        check(tiles.size >= 12) { "Only ${tiles.size} usable tiles" }
        val worst = tiles.maxBy { it.second }
        // A reference pixel that is exactly zero has no relative error, and including it as an
        // infinity poisons a percentile silently: the first full run printed "p95 Infinity" for a
        // low-budget reference where a handful of pixels had never received a sample. Skipped and
        // counted, so the population a percentile covers is the population it reports.
        val relative = pixels.filter { luminance(b, it) > 0.0 }
            .map { i -> abs(luminance(a, i) - luminance(b, i)) / luminance(b, i) }.sorted()
        check(relative.size > pixels.size / 8) {
            "Only ${relative.size} of ${pixels.size} reference pixels are positive, which is too " +
                "few for a percentile over them to describe the population it names"
        }
        return Agreement(pixels.size, abs(sumA - sumB) / sumB, (sumA - sumB) / sumB,
            worst.first, worst.second, tiles.size,
            relative[relative.size / 2], relative[(relative.size * 95) / 100], pixels.size - relative.size)
    }

    private fun rmse(image: FloatArray, reference: FloatArray, pixels: List<Int>): Double {
        var sum = 0.0
        pixels.forEach { i -> for (c in 0..2) { val d = image[i * 4 + c] - reference[i * 4 + c]; sum += d.toDouble() * d } }
        return sqrt(sum / (pixels.size * 3))
    }

    private fun srgb(v: Double) = (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055).coerceIn(0.0, 1.0)
    private fun show(v: Double) = "%.5f".format(v)
    private fun show(v: Float) = "%.5f".format(v)

    /** Exposure plus the same Reinhard curve the tracer's own presentation pass uses, then sRGB. */
    private fun writePng(pixels: FloatArray, width: Int, height: Int, exposure: Float, file: File) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            val alpha = maxOf(pixels[i + 3], 1e-20f)
            var rgb = 0
            for (c in 0..2) {
                val hdr = (pixels[i + c] / alpha * exposure).coerceAtLeast(0f).toDouble()
                rgb = (rgb shl 8) or (srgb(hdr / (1.0 + hdr)) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            }
            image.setRGB(x, y, rgb)
        }
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
        check(file.length() > 512) { "Wrote a suspiciously small PNG: ${file.absolutePath} (${file.length()} bytes)" }
    }

    // ------------------------------------------- arm 0: inert without extinction

    /**
     * Extinction zero must reach the previous integrator *exactly*, bit for bit, with every other
     * medium property set to something conspicuous.
     *
     * This is not a formality. The distance sample has no Sobol dimension of its own — it shares
     * the BSDF lobe selector's, and the escape branch remaps it back to uniform — so "the medium is
     * off" and "the sample stream is untouched" are two different claims, and the second is the one
     * that makes a medium-off render comparable to anything rendered before media existed. Bit-for-
     * bit also means the two runs have to stop on the same sample, which they cannot be *made* to,
     * so the second targets whatever the first reached and the pair is retried.
     */
    private suspend fun inertWithoutExtinction(renderer: Renderer) {
        val conspicuous = Medium(0f, Vector3f(0.2f, 0.5f, 0.9f), 0.8f,
            Vector3f(-3f, -3f, -3f) to Vector3f(3f, 3f, 3f))
        suspend fun shot(medium: Medium, target: Int) = converge(renderer, lampBox(),
            PathTraceEnvironment.constant(0.05f), lampCamera(renderer), medium, target, 1, 4)
        val (off, set) = matched({ shot(Medium.none, it) }, { shot(conspicuous, it) }, 96)
        check(off.pixels.contentEquals(set.pixels)) {
            val worst = off.pixels.indices.maxBy { abs(off.pixels[it] - set.pixels[it]) }
            "Extinction zero must be inert: worst component $worst ${off.pixels[worst]} vs ${set.pixels[worst]}"
        }
        say("PASS extinction zero is bit-for-bit inert: ${WIDTH}x$HEIGHT, ${PIXELS} pixels, " +
            "${off.samples} samples, albedo/anisotropy/bounds all set and all ignored")
    }

    // --------------------------------- arms 1 and 1b: absorption in closed form

    private val PLANE_RADIANCE = Vector3f(1.5f, 1.0f, 0.6f)

    /**
     * `L_e·exp(-σt·d)` per pixel, with `d` the distance the ray travels inside the medium: the
     * whole camera-to-plane distance when the medium is unbounded, and the slab-crossing length
     * when it is bounded in z. Premultiplied RGBA to match `readLinear`, whose alpha is one here
     * because every ray either hits the plane or is absorbed inside it.
     */
    private fun absorptionReference(rays: Rays, density: Float, slab: Pair<Float, Float>?): FloatArray {
        val reference = FloatArray(PIXELS * 4)
        for (i in 0 until PIXELS) {
            val direction = rays.direction(i)
            check(direction.z < -1e-6f) { "Pixel $i does not travel toward the plane: $direction" }
            val toPlane = -rays.origin.z / direction.z
            val inside = if (slab == null) toPlane else (slab.second - slab.first) / -direction.z
            val transmittance = exp(-density.toDouble() * inside).toFloat()
            reference[i * 4] = PLANE_RADIANCE.x * transmittance
            reference[i * 4 + 1] = PLANE_RADIANCE.y * transmittance
            reference[i * 4 + 2] = PLANE_RADIANCE.z * transmittance
            reference[i * 4 + 3] = 1f
        }
        return reference
    }

    /** Worst per-channel relative deviation from [reference], and the whole-image per-channel one. */
    private fun channelAgreement(image: FloatArray, reference: FloatArray): Pair<Double, Double> {
        var worst = 0.0
        val sums = DoubleArray(6)
        for (i in 0 until PIXELS) for (c in 0..2) {
            val got = image[i * 4 + c].toDouble(); val want = reference[i * 4 + c].toDouble()
            worst = maxOf(worst, abs(got - want) / want)
            sums[c] += got; sums[3 + c] += want
        }
        val global = (0..2).maxOf { abs(sums[it] - sums[3 + it]) / sums[3 + it] }
        return global to worst
    }

    /**
     * The absolute anchor: pure absorption against the exponential, at two densities and in both
     * the unbounded and the bounded configuration.
     *
     * The estimator here is deliberately the awkward one. Transmittance is not evaluated on a
     * camera ray — it is *estimated*, by whether the distance sample landed before the plane or
     * not — so each pixel is a scaled Bernoulli trial and the per-pixel error falls only as one
     * over the root of the budget. That is why the whole-image floor is two orders tighter than
     * the per-pixel one, and why both are stated with the population they cover.
     */
    private suspend fun absorptionAgainstClosedForm(renderer: Renderer) {
        val rays = Rays(planeCamera(renderer), WIDTH, HEIGHT)
        val slab = 0.5f to 2.0f
        val configurations = listOf(
            Triple(0.25f, null as Pair<Float, Float>?, "unbounded, thin"),
            Triple(0.90f, null as Pair<Float, Float>?, "unbounded, thick"),
            Triple(0.60f, slab, "bounded slab z 0.5..2.0")
        )
        for ((density, bounds, label) in configurations) {
            val medium = Medium(density, Vector3f(0f), 0f,
                bounds?.let { Vector3f(-1e5f, -1e5f, it.first) to Vector3f(1e5f, 1e5f, it.second) })
            val shot = converge(renderer, emissivePlane(PLANE_RADIANCE), PathTraceEnvironment.constant(0f),
                planeCamera(renderer), medium, ABSORPTION_SAMPLES, 64, bounces = 1, emitters = false)
            val reference = absorptionReference(rays, density, bounds)
            val pixels = (0 until PIXELS).toList()
            val result = agree(shot.pixels, reference, pixels)
            val (globalChannel, worstChannel) = channelAgreement(shot.pixels, reference)
            val meanTransmittance = (0 until PIXELS).sumOf {
                shot.pixels[it * 4].toDouble() / PLANE_RADIANCE.x
            } / PIXELS
            say("absorption against exp(-sigma_t*d), $label, sigma_t=${show(density)} at " +
                "${shot.samples} samples: $result; worst per-channel ${show(worstChannel)}, " +
                "whole-image per-channel ${show(globalChannel)}, mean transmittance ${show(meanTransmittance)}")
            // Measured on an M1 Max at 65536 samples: global and whole-image per-channel both
            // below 5e-6 in all three configurations, worst tile 0.00001, per-pixel p95 0.00024,
            // worst single channel of a single pixel 0.00043. These floors are ten to twenty times
            // those.
            //
            // They are two orders tighter than the Bernoulli standard error predicts, and the
            // reason is worth knowing: transmittance here is estimated by a *binary* event driven
            // by one Owen-scrambled Sobol dimension, and a stratified sequence integrates a
            // one-dimensional step function far better than random sampling does. The estimator is
            // noisy per *path* and nearly exact per pixel. A shake-out run at 4096 samples measured
            // 0.00004 global, which is the same story an eighth of the way along.
            check(result.global < 0.0001) {
                "Whole-image disagreement with the closed form ${result.global} over $PIXELS pixels, $label"
            }
            check(result.worstTileValue < 0.0002) {
                "Tile ${result.worstTile} disagrees with the closed form by ${result.worstTileValue}, $label"
            }
            check(globalChannel < 0.0001) { "Whole-image per-channel $globalChannel, $label" }
            check(worstChannel < 0.004) { "Worst per-channel $worstChannel, $label" }
            check(result.p95 < 0.003) { "Per-pixel p95 ${result.p95}, $label" }
            say("PASS absorption matches the closed form ($label): ${WIDTH}x$HEIGHT, $PIXELS pixels, " +
                "${shot.samples} samples, mean transmittance ${show(meanTransmittance)}")
        }
    }

    /**
     * The same closed form through `fogDensity`, which this change does not touch.
     *
     * `depthFog` evaluates `exp(-σ·primaryDepth)` analytically, with `primaryDepth` measured by the
     * shader from its own ray. So this arm has no Bernoulli noise at all and can be held to float
     * precision — and because it agrees with the *same* Kotlin reference the medium is held to, it
     * is what establishes that the reference's reconstructed ray direction is the shader's ray, and
     * not merely self-consistent.
     */
    private suspend fun absorptionAgainstDepthFog(renderer: Renderer) {
        val rays = Rays(planeCamera(renderer), WIDTH, HEIGHT)
        for (density in listOf(0.25f, 0.90f)) {
            val shot = converge(renderer, emissivePlane(PLANE_RADIANCE), PathTraceEnvironment.constant(0f),
                planeCamera(renderer), Medium.none, 256, 64, bounces = 1, emitters = false,
                fogDensity = density)
            val reference = absorptionReference(rays, density, null)
            val (globalChannel, worstChannel) = channelAgreement(shot.pixels, reference)
            say("untouched depthFog against the same closed form, sigma=${show(density)} at " +
                "${shot.samples} samples: whole-image per-channel ${show(globalChannel)}, " +
                "worst per-channel ${show(worstChannel)} over $PIXELS pixels")
            // No estimator noise in this arm: the only error is float precision in exp() and in
            // the reconstructed direction. Measured 3e-7 worst per-channel on an M1 Max.
            check(worstChannel < 1e-5) {
                "depthFog disagrees with the closed form by $worstChannel: the reference's ray " +
                    "reconstruction, not the medium, is what this arm holds to account"
            }
            say("PASS the closed-form reference is the shader's own ray: $PIXELS pixels, " +
                "sigma=${show(density)}, worst per-channel ${show(worstChannel)}")
        }
    }

    // ----------------------------------- arm 2: energy conservation at exactly 1.0

    /**
     * The white furnace, medium version, across six anisotropies.
     *
     * An albedo-one medium embedded in a uniform unit radiance field is in exact equilibrium, so
     * every pixel must read 1.0 whatever the phase function is — which is what makes this a check
     * on *two* things at once. It checks that the phase function integrates to one, and it checks
     * that `phase` and `samplePhase` describe the same distribution: the phase continuation takes
     * its weight to be exactly one on the strength of that, and if the sampler drew from anything
     * else the furnace would not close.
     *
     * The deficit that remains is bounce truncation and nothing else. At the last vertex the
     * connection arm takes the whole weight, so it returns the *attenuated* direct field and misses
     * the in-scattered remainder; the density here is chosen so that the probability of reaching
     * the twelfth vertex makes that under 1e-4. [furnaceBounceSweep] measures the same deficit
     * deliberately, at a density where it is large, to show it is that and not a leak.
     */
    private suspend fun scatteringFurnace(renderer: Renderer) {
        val bounds = Vector3f(-2f, -2f, -2f) to Vector3f(2f, 2f, 2f)
        for (anisotropy in listOf(-0.6f, -0.3f, 0f, 0.3f, 0.6f, 0.85f)) {
            val shot = converge(renderer, PathTraceScene().negligibleTriangle(),
                PathTraceEnvironment.constant(1f), insideCamera(renderer),
                Medium(0.15f, Vector3f(1f), anisotropy, bounds), FURNACE_SAMPLES, 64,
                bounces = 12, emitters = true, showBackground = true)
            val mean = (0 until PIXELS).sumOf { luminance(shot.pixels, it) } / PIXELS
            val worst = (0 until PIXELS).maxOf { abs(luminance(shot.pixels, it) - 1.0) }
            val spread = (0 until PIXELS).map { abs(luminance(shot.pixels, it) - 1.0) }.sorted()
            say("medium white furnace at g=${show(anisotropy)}, sigma_t=0.15, albedo 1, twelve " +
                "bounces, ${shot.samples} samples: mean ${show(mean)} against an analytic 1.0, " +
                "per-pixel p95 ${show(spread[(spread.size * 95) / 100])}, worst ${show(worst)}, " +
                "over $PIXELS pixels")
            // Measured |mean-1| at or below 0.00001 for every one of the six anisotropies on an
            // M1 Max at 16384 samples, and below 0.00004 at 2048. The floor is twenty times the
            // former; a floor loose enough never to flake certifies nothing.
            check(abs(mean - 1.0) < 0.0002) {
                "Medium furnace mean $mean at g=$anisotropy over $PIXELS pixels: an albedo-one " +
                    "medium in a unit field must conserve energy exactly"
            }
            check(spread[(spread.size * 95) / 100] < 0.005) {
                "Per-pixel p95 deviation ${spread[(spread.size * 95) / 100]} at g=$anisotropy"
            }
        }
        say("PASS the medium conserves energy: six anisotropies from -0.6 to 0.85, each within " +
            "0.0002 of an analytic 1.0 over $PIXELS pixels at $FURNACE_SAMPLES samples")
    }

    /**
     * The bounce-truncation deficit, measured on purpose at a density where it is large.
     *
     * A furnace that closes at one density and leaks at another is only evidence if the leak is
     * explained. This shows the same estimator approaching 1.0 monotonically as the bounce budget
     * rises, which is what truncation looks like; a normalisation error would sit at a fixed offset
     * instead, and a double-counted arm would overshoot.
     */
    private suspend fun furnaceBounceSweep(renderer: Renderer) {
        val bounds = Vector3f(-2f, -2f, -2f) to Vector3f(2f, 2f, 2f)
        val means = mutableListOf<Pair<Int, Double>>()
        for (bounces in listOf(1, 2, 4, 8, 12)) {
            val shot = converge(renderer, PathTraceScene().negligibleTriangle(),
                PathTraceEnvironment.constant(1f), insideCamera(renderer),
                Medium(0.6f, Vector3f(1f), 0f, bounds), FURNACE_SAMPLES, 64,
                bounces = bounces, emitters = true, showBackground = true)
            means += bounces to (0 until PIXELS).sumOf { luminance(shot.pixels, it) } / PIXELS
        }
        say("furnace deficit against bounce budget at sigma_t=0.6 (a 2.4 optical-depth box), " +
            "albedo 1, $PIXELS pixels, $FURNACE_SAMPLES samples: " +
            means.joinToString(", ") { "${it.first} -> ${show(it.second)}" })
        check(means.zipWithNext().all { (a, b) -> b.second > a.second - 0.0005 }) {
            "Furnace mean is not monotone in the bounce budget: $means"
        }
        check(means.first().second < means.last().second - 0.02) {
            "Raising the bounce budget did not recover energy: $means"
        }
        check(means.last().second < 1.0005) {
            "Furnace overshoots 1.0 at twelve bounces (${means.last().second}): an arm is double counted"
        }
        say("PASS the remaining furnace deficit is bounce truncation: monotone from " +
            "${show(means.first().second)} at one bounce to ${show(means.last().second)} at twelve, " +
            "never above 1.0, $PIXELS pixels")
    }

    // ------------------------- arm 3: single scattering against a CPU quadrature

    /** Composite Simpson over the unit interval; [intervals] must be even. */
    private fun simpson(intervals: Int, f: (Double) -> Double): Double {
        var sum = f(0.0) + f(1.0)
        for (i in 1 until intervals) sum += f(i.toDouble() / intervals) * (if (i % 2 == 0) 2 else 4)
        return sum / (3.0 * intervals)
    }

    /**
     * `E₁(x) = ∫ₓ^∞ e⁻ᵗ/t dt`: the Taylor series below one, and the modified-Lentz evaluation of
     * the standard continued fraction above it. Both are good to about 1e-15 here, which matters
     * because the quantity built from this is the *reference* the shader is measured against — a
     * tabulated-and-interpolated version of it carried a 4e-4 error near zero, which is the same
     * order as the residual this arm is trying to resolve.
     */
    private fun exponentialIntegralE1(x: Double): Double {
        require(x > 0.0) { "E1 is singular at zero" }
        if (x <= 1.0) {
            var sum = -0.5772156649015329 - kotlin.math.ln(x)
            var term = 1.0
            for (k in 1..40) { term *= -x / k; sum -= term / k }
            return sum
        }
        var b = x + 1.0; var c = 1e300; var d = 1.0 / b; var h = d
        for (i in 1..300) {
            val a = -i.toDouble() * i
            b += 2.0
            d = 1.0 / (a * d + b); c = b + a / c
            val delta = c * d
            h *= delta
            if (abs(delta - 1.0) < 1e-15) break
        }
        return h * exp(-x)
    }

    /**
     * `∫₀¹ exp(-τ/μ) dμ` — the fraction of one hemisphere's isotropic flux that escapes a slab of
     * optical depth τ. Substituting μ = 1/t makes that `E₂(τ)` exactly, and the recurrence gives
     * `E₂(τ) = e^-τ - τ·E₁(τ)`, which is the whole reason no quadrature over μ is needed.
     */
    private fun slabEscape(tau: Double): Double =
        if (tau <= 0.0) 1.0 else exp(-tau) - tau * exponentialIntegralE1(tau)

    /**
     * The single-scattering radiance along a ray crossing a slab of thickness [thickness] at
     * direction cosine [cosine], under a constant unit environment, isotropic phase, albedo [albedo]
     * — written from the radiative transfer equation and evaluated by quadrature.
     *
     * The escape term is `exp(-σt·s)` over the whole crossing. The in-scattered term integrates,
     * over path length `s` inside the slab, the collision density `σt·exp(-σt·s)` times the albedo
     * times the isotropic in-scattered radiance at that depth, which for a slab is the mean of the
     * escape factors upward and downward: `½(E(σt·z) + E(σt·(L-z)))` with `z = s·|cosine|`. The
     * halves are there because an isotropic phase function splits the sphere evenly between the two
     * faces, and `E` is [slabEscape].
     *
     * Nothing in this derivation came from the shader. It is the reason this arm is worth its
     * length: the distance pdf, the albedo weight, the phase normalisation and the transmittance on
     * the environment connection all appear in it, and they appear in a form that a wrong factor of
     * 4π or a wrong `σt` cannot survive.
     */
    private fun singleScattering(density: Double, thickness: Double, cosine: Double,
                                 albedo: Double): Double {
        val crossing = thickness / cosine
        val direct = exp(-density * crossing)
        // 512 intervals, not more: E2 has a tau*ln(tau) kink at both ends of this interval, so
        // Simpson's error is endpoint-limited at about 2e-5 relative and does not improve usefully
        // past here, while the cost is 6912 pixels times two E1 evaluations per node.
        val inScattered = crossing * simpson(512) { fraction ->
            val s = fraction * crossing
            val z = s * cosine
            density * exp(-density * s) * albedo *
                0.5 * (slabEscape(density * z) + slabEscape(density * (thickness - z)))
        }
        return direct + inScattered
    }

    /**
     * Single scattering in a slab against [singleScattering]. One bounce, so the MIS weight is
     * forced to one in both arms and what is left is the estimator itself: distance sampling, the
     * albedo weight, the phase density, and `mediumTransmittance` on an environment connection
     * whose length `mediumSpan` has to get right from a point *inside* the slab.
     */
    private suspend fun singleScatteringAgainstQuadrature(renderer: Renderer) {
        val thickness = 1.5
        val rays = Rays(planeCamera(renderer), WIDTH, HEIGHT)
        for ((density, albedo) in listOf(0.8f to 1f, 1.6f to 0.5f)) {
            val bounds = Vector3f(-1e5f, -1e5f, 0.5f) to Vector3f(1e5f, 1e5f, 0.5f + thickness.toFloat())
            val shot = converge(renderer, PathTraceScene().negligibleTriangle(),
                PathTraceEnvironment.constant(1f), planeCamera(renderer),
                Medium(density, Vector3f(albedo), 0f, bounds), ABSORPTION_SAMPLES, 64,
                bounces = 1, emitters = false, showBackground = true)
            val reference = FloatArray(PIXELS * 4)
            for (i in 0 until PIXELS) {
                val cosine = -rays.direction(i).z.toDouble()
                val value = singleScattering(density.toDouble(), thickness, cosine, albedo.toDouble())
                for (c in 0..2) reference[i * 4 + c] = value.toFloat()
                reference[i * 4 + 3] = 1f
            }
            val pixels = (0 until PIXELS).toList()
            val result = agree(shot.pixels, reference, pixels)
            val mean = pixels.sumOf { luminance(shot.pixels, it) } / PIXELS
            val want = pixels.sumOf { luminance(reference, it) } / PIXELS
            say("single scattering against a CPU quadrature of the transport equation, " +
                "sigma_t=${show(density)}, albedo ${show(albedo)}, slab ${thickness} thick, one bounce, " +
                "${shot.samples} samples: $result; mean ${show(mean)} against ${show(want)}")
            // Measured global below 5e-6 and worst tile 0.00001 on an M1 Max at 65536 samples
            // (0.00002 and 0.00018 at 4096). The quadrature's own error is about 3e-6 relative,
            // checked against both a finer Simpson rule and a from-scratch two-million-sample
            // Monte Carlo of the same physics, so a floor much below this would be measuring the
            // reference rather than the shader.
            check(result.global < 0.0001) {
                "Whole-image disagreement with the quadrature ${result.global} over $PIXELS pixels " +
                    "at sigma_t=$density, albedo $albedo"
            }
            check(result.worstTileValue < 0.0002) {
                "Tile ${result.worstTile} disagrees with the quadrature by ${result.worstTileValue}"
            }
            say("PASS single scattering matches the transport equation: $PIXELS pixels, " +
                "${shot.samples} samples, sigma_t=${show(density)}, albedo ${show(albedo)}, " +
                "mean ${show(mean)} against a quadrature's ${show(want)}")
        }
    }

    // --------------------------- arm 3b: the emitter connection, cross-mechanism

    /**
     * The one mechanism neither the furnace nor the quadrature reaches: the connection from a
     * scattering point to *emissive geometry*, with its area-to-solid-angle Jacobian and its own
     * transmittance.
     *
     * The idea is that a uniformly emissive enclosure is a radiance field in which an albedo-one
     * medium is exactly invisible, so the same scene with the medium off — last night's estimator,
     * independently checked, sharing no line with anything added here — is the reference, and the
     * new emitter-in-medium pdf appears in exactly one of the two arms.
     *
     * **That idea is very nearly right, and the way it is wrong is worth the three runs it takes
     * to establish.** A medium is invisible in a field that is uniform in direction as well as in
     * position. An enclosure of emissive *dielectrics* is not: `evaluate` gives every dielectric an
     * f0 of 0.04, so each face returns a few percent of what it is lit by, and that reflected part
     * is not Lambertian — its magnitude depends on the outgoing direction. So the field has angular
     * structure, and a scattering medium averages over direction, which changes the image. The
     * first version of this arm asserted invisibility and measured a residual of +0.00108 that did
     * not shrink when the budget grew eightfold. That reads as bias, and it is not: it is physics
     * the reference scene has and the premise did not.
     *
     * Three configurations separate the two readings, and they make predictions a pdf error would
     * not make:
     *
     *  - **A near-perfect emitter.** A black *metal* is the least reflective surface this BSDF can
     *    express — metallic one with a black base puts f0 at exactly zero, where a dielectric's is
     *    0.04. Its enclosure settles at 1.00035 rather than 1.03954, so it is within 0.035% of a
     *    pure emitter, and the invisibility premise is very nearly exact. This is the tight arm.
     *  - **A dielectric emitter.** A hundredfold more reflectance, and the residual grows with it.
     *  - **The same dielectric pair with emitter sampling off in both arms**, so the emitter
     *    connection under test is not used at all. If the residual is unchanged there, it cannot be
     *    the emitter connection's error, whatever else it is. It is unchanged: 0.00109 against
     *    0.00108 on an M1 Max.
     *
     * What survives is a bound rather than an identity, and the bound is what this arm claims: an
     * error in the emitter connection through a medium is smaller than 0.00011 whole-image over
     * 6912 pixels. Every *component* of that connection is pinned harder elsewhere —
     * [singleScatteringAgainstQuadrature] pins the distance pdf, the albedo weight and the
     * transmittance absolutely, [scatteringFurnace] pins the phase function, and the area pdf and
     * its Jacobian are `sampleEmitter` and `emitterPdf` unchanged from last night's checked
     * surface arm. What is new here is only their composition.
     */
    private suspend fun emitterConnectionIsInvisible(renderer: Renderer) {
        val bounds = Vector3f(-2.5f, -2.5f, -2.5f) to Vector3f(2.5f, 2.5f, 2.5f)
        val scattering = Medium(0.15f, Vector3f(1f), 0.35f, bounds)
        // Emissive, and as close to non-reflecting as evaluate() can be asked to be.
        val blackMetal = PathTraceMaterial(red = 0f, green = 0f, blue = 0f, metallic = 1f,
            roughness = 1f, emissionRed = 1f, emissionGreen = 1f, emissionBlue = 1f)
        val dielectric = PathTraceMaterial.emissive(1f, 1f, 1f)
        val pixels = (0 until PIXELS).toList()
        suspend fun shot(medium: Medium, emitters: Boolean, faceMaterial: PathTraceMaterial) =
            converge(renderer, enclosure(3f, 1f, faceMaterial), PathTraceEnvironment.constant(0f),
                insideCamera(renderer), medium, FURNACE_SAMPLES, 64, bounces = 12, emitters = emitters)
        /** The residual, and the enclosure's own equilibrium, which is 1 plus its face reflectance. */
        suspend fun residual(label: String, emitters: Boolean, faceMaterial: PathTraceMaterial):
            Pair<Agreement, Double> {
            val on = shot(scattering, emitters, faceMaterial)
            // emitterCount is the scene's emissive-triangle count, not a statement about whether
            // the estimator is using them -- it reads twelve in the emitter-sampling-off arm too.
            check(on.emitters == 12) { "Expected twelve emissive faces, got ${on.emitters}" }
            val off = shot(Medium.none, emitters, faceMaterial)
            val result = agree(on.pixels, off.pixels, pixels)
            val equilibrium = pixels.sumOf { luminance(off.pixels, it) } / PIXELS
            say("albedo-one medium inside a uniformly emissive enclosure, $label, against the same " +
                "scene with no medium, twelve bounces, ${on.samples} samples: $result; the " +
                "enclosure's own equilibrium is ${show(equilibrium)}, so its faces return " +
                "${show(equilibrium - 1.0)} of what they are lit by")
            return result to equilibrium
        }
        val (metal, metalEquilibrium) = residual("black metal faces, f0 exactly zero", true, blackMetal)
        check(metalEquilibrium < 1.001) {
            "The black-metal enclosure settles at $metalEquilibrium, so it is not the near-pure " +
                "emitter this arm needs and its premise is no longer nearly exact"
        }
        // Measured global 0.00011, worst tile 0.00030, per-pixel p95 0.00164 on an M1 Max at 16384
        // samples. The floors are three to five times those.
        check(metal.global < 0.0005) {
            "Whole-image disagreement ${metal.global} over $PIXELS pixels against a near-pure " +
                "emitter: this is the emitter connection through the medium, and it is too large"
        }
        check(metal.worstTileValue < 0.0015) {
            "Tile ${metal.worstTile} disagrees by ${metal.worstTileValue} against a near-pure emitter"
        }
        say("PASS the emitter connection through the medium is right: ${WIDTH}x$HEIGHT, $PIXELS " +
            "pixels, $FURNACE_SAMPLES samples, an albedo-one medium inside an enclosure that is " +
            "within ${show(metalEquilibrium - 1.0)} of a pure emitter is invisible to " +
            "${show(metal.global)} whole-image")

        val (glossy, glossyEquilibrium) = residual("dielectric faces, f0 0.04", true, dielectric)
        val (inert, _) = residual("dielectric faces, emitter sampling OFF in both arms", false, dielectric)
        say("the enclosure residual against its faces' reflectance: ${show(metal.signed)} signed " +
            "where the faces return ${show(metalEquilibrium - 1.0)}, and ${show(glossy.signed)} " +
            "where they return ${show(glossyEquilibrium - 1.0)} -- a hundredfold more reflectance " +
            "for about ten times the residual. Both positive, and the same comparison with the " +
            "emitter connection switched off in BOTH arms gives ${show(inert.signed)}, which is " +
            "the dielectric figure again. So the residual is the enclosure's own angular structure " +
            "being averaged by a scattering medium, and not the connection this arm is about: " +
            "removing the connection does not remove it, and removing the reflectance does.")
        check(glossy.signed > metal.signed) {
            "The residual did not grow with the faces' reflectance (${metal.signed} -> " +
                "${glossy.signed}): it does not track the thing angular averaging would track"
        }
        check(abs(inert.signed - glossy.signed) < 0.35 * abs(glossy.signed)) {
            "Switching the emitter connection off in both arms changed the residual from " +
                "${glossy.signed} to ${inert.signed}: the residual IS in the connection after " +
                "all, and this arm's bound is the wrong bound"
        }
        say("PASS the enclosure residual is the reference scene's physics, not the estimator's " +
            "error: it survives removing the mechanism under test and disappears with the faces' " +
            "reflectance, over $PIXELS pixels at $FURNACE_SAMPLES samples")
    }

    // ------------------------------ arms 4 and 5: bias at two budgets, and variance

    /**
     * The lamp-in-fog scene: the bias residual at two reference budgets, and the variance floor.
     *
     * The residual is reported twice because a fixed tolerance on a residual is a tolerance on
     * noise. If it falls with the *reference's own* budget it was the reference's noise; if it holds
     * still it is bias. The budget growth and the decay that growth predicts are printed as two
     * separate labelled numbers, because they are two different things and conflating them has
     * published a wrong claim in this loop before.
     *
     * **The off arm gets one more bounce than the on arm, and that is not a thumb on the scale.**
     * `maxBounces` limits *path length*, and next-event estimation reaches one segment past it: the
     * connection from the last vertex to a light is a scattering event that a phase-sampled or
     * BSDF-sampled path would have needed another bounce to make. So on at six transports exactly
     * what off at seven does. Comparing equal `maxBounces` instead reports that extra segment as
     * bias, and in a medium it is not a small effect — the first full run of this arm measured a
     * 2.9% whole-image difference at equal bounces that did not shrink at all when the reference's
     * budget grew sixteenfold, and whose *sign* was positive: more transport, not double counting.
     * A medium makes this worse than the surface-only case because every collision spends a bounce,
     * so the last vertex arrives sooner and the extra segment is worth more.
     */
    private suspend fun lampInFog(renderer: Renderer) {
        val medium = Medium(0.22f, Vector3f(0.92f, 0.90f, 0.86f), 0.45f, LAMP_BOUNDS)
        suspend fun lamp(emitters: Boolean, target: Int, perFrame: Int, bounces: Int) =
            converge(renderer, lampBox(), PathTraceEnvironment.constant(0f), lampCamera(renderer),
                medium, target, perFrame, bounces, emitters).also {
                check(it.emitters == 2) { "Expected two emissive lamp triangles, got ${it.emitters}" }
            }
        val referenceOn = lamp(true, LAMP_REFERENCE, 64, 6)
        val referenceOff = lamp(false, LAMP_REFERENCE, 64, 7)
        val lowOn = lamp(true, LAMP_LOW_REFERENCE, 64, 6)
        val lowOff = lamp(false, LAMP_LOW_REFERENCE, 64, 7)
        // The population is defined by the *best converged* render and by nothing else. An earlier
        // version also required the low-budget off arm to be positive at a pixel, to keep a zero
        // out of a percentile's denominator, and that quietly inverted the comparison: it keeps
        // exactly the pixels where the noisiest estimator happened to fire, which are the pixels
        // where it over-estimated. It turned a +0.029 residual into -0.104 and shrank the
        // population from 6907 to 2083. Zeros are handled where they belong, in `agree`, which
        // skips them for the percentiles and counts how many it skipped.
        val pixels = (0 until PIXELS).filter {
            luminance(referenceOn.pixels, it) in 0.002..8.0 && referenceOn.pixels[it * 4 + 3] == 1f
        }
        check(pixels.size > PIXELS / 4) { "Lit population is only ${pixels.size} pixels of $PIXELS" }
        val high = agree(referenceOn.pixels, referenceOff.pixels, pixels)
        // The residual is measured as an RMS difference, not as a relative per-pixel one, and the
        // reason is the low-budget arm. A 4096-sample render of this scene with the light
        // connections off has pixels that never found the lamp at all, and a relative statistic
        // either divides by their zero or drops them -- and dropping them is worse than it looks,
        // because the pixels it drops are exactly the ones where the noisy estimator under-fired,
        // so what is left is a biased subsample. An earlier version of this arm did drop them and
        // reported a -0.104 residual over 2083 pixels where the honest figure was +0.029 over
        // 6907. RMS needs no denominator, so it keeps the whole population.
        val budgetGrowth = LAMP_REFERENCE.toDouble() / LAMP_LOW_REFERENCE
        val predicted = sqrt(budgetGrowth)
        val highRms = rmse(referenceOn.pixels, referenceOff.pixels, pixels)
        val lowRms = rmse(lowOn.pixels, lowOff.pixels, pixels)
        val decay = lowRms / highRms
        // The same pair at equal bounces, for the measurement the paragraph above is about.
        val equalBounces = agree(referenceOn.pixels,
            lamp(false, referenceOn.samples, 64, 6).pixels, pixels)
        say("lamp-in-fog residual at ${referenceOff.samples} samples: ${show(highRms)} RMS, and " +
            "as a relative figure, $high")
        say("lamp-in-fog residual at ${lowOff.samples} samples: ${show(lowRms)} RMS")
        say("lamp-in-fog residual decay ${show(decay)}x OBSERVED. Separately: the reference budget " +
            "grew ${show(budgetGrowth)}x, from $LAMP_LOW_REFERENCE to $LAMP_REFERENCE samples, and " +
            "a residual that is the reference's own noise decays as the root of that, so the " +
            "PREDICTED decay is ${show(predicted)}x. Bias would not decay at all. Those are two " +
            "different numbers and this line is the only place they appear together.")
        check(decay > predicted / 2.0) {
            "Residual did not decay with the reference's budget: ${decay}x observed against a " +
                "${predicted}x prediction for a ${budgetGrowth}x budget growth"
        }
        say("lamp-in-fog at EQUAL maxBounces, on against off both at six, " +
            "${referenceOn.samples} samples: $equalBounces -- against ${show(high.global)} " +
            "whole-image for the matched pair. The gap between those two is the extra next-event " +
            "segment: more transport for the same budget, not bias. Its sign is reported and not " +
            "asserted, because at this budget the off arm's own tail is heavy enough to move it.")
        check(equalBounces.global > high.global) {
            "Matching the bounce budget did not reduce the whole-image residual " +
                "(${equalBounces.global} at equal bounces against ${high.global} matched), so the " +
                "extra next-event segment is not what the equal-bounces difference is"
        }
        // The whole-image figure is the one worth a floor: it averages the off arm's noise down by
        // the root of the population, where a per-pixel percentile does not. Even so this is a
        // loose bound, and saying so is the point -- the off arm of this scene is the noisiest
        // estimator in this file, a 0.16 square-unit lamp found only by chance through fog, and at
        // 65536 samples it is still far from converged. The sharp bias evidence for the same
        // machinery is [emitterConnectionIsInvisible], whose reference does converge. What this
        // arm contributes is the decay above and the standard-error comparison at the end.
        // Measured 0.00003 at 65536 samples and 0.00199 at 4096. The floor is set against the
        // *low*-budget figure rather than the high one: a whole-image mean of a heavy-tailed
        // estimator is erratic between runs in a way that a per-pixel statistic is not, and this
        // one fell 66x for a 16x budget growth, which is faster than noise should.
        check(high.global < 0.002) {
            "Whole-image residual ${high.global} at ${referenceOff.samples} samples over ${pixels.size} pixels"
        }
        say("PASS the lamp-in-fog residual is reference noise, not bias: ${pixels.size} lit " +
            "pixels of $PIXELS, a ${show(budgetGrowth)}x budget growth shrank the RMS residual " +
            "${show(decay)}x against a ${show(predicted)}x prediction, and the whole-image " +
            "relative residual is ${show(high.global)}")

        // Equal bounces here, not the matched pair above: this is a *variance* comparison at a
        // stated budget, and giving the off arm an extra bounce would change its mean as well as
        // its noise. Its RMSE is measured against on's own reference either way.
        val (measuredOn, measuredOff) =
            matched({ lamp(true, it, 1, 6) }, { lamp(false, it, 1, 6) }, MEASURED_SAMPLES)
        val errorOn = rmse(measuredOn.pixels, referenceOn.pixels, pixels)
        val errorOff = rmse(measuredOff.pixels, referenceOn.pixels, pixels)
        val ratio = errorOff / errorOn
        say("lamp-in-fog variance at ${measuredOn.samples} samples over ${pixels.size} lit " +
            "pixels: RMSE ${show(errorOff)} with phase and BSDF sampling alone, ${show(errorOn)} " +
            "with the light connections on, ratio ${show(ratio)}")
        // The denominator is set by the scene, not the estimator: one 0.16-square-unit lamp at
        // radiance 90 in a black environment, a 0.22-per-unit medium at albedo ~0.9 and g 0.45,
        // six bounces, 96x72, and the lit population above. A larger lamp moves it down, and so
        // does the medium itself -- a scattering medium spreads the lamp's light over the whole
        // frame, which gives the arm without light connections far more chance of finding it than
        // the same lamp in clear air would. The comparable surface-only figure in
        // PathTraceEmitterGpuSmoke is 35x for that reason, and quoting that here would be quoting
        // a different measurement.
        //
        // Measured 5.02 at 257 samples against a 4096-sample reference and 4.94 at 256 against
        // the 65536-sample one, over 6907 lit pixels. The floor is 4.0. An earlier draft of this
        // comment claimed 21.8 before anything had been measured, and a later one claimed 5.32
        // for a run that had not happened; both are why the number now comes with its population
        // and its reference budget.
        check(ratio > 4.0) {
            "RMSE ratio $ratio at ${measuredOn.samples} samples over ${pixels.size} lit pixels: " +
                "off $errorOff, on $errorOn"
        }
        say("PASS light connections through the medium reduce error: RMSE ratio ${show(ratio)} " +
            "at ${measuredOn.samples} samples per pixel over ${pixels.size} lit pixels of " +
            "${WIDTH}x$HEIGHT, one small lamp, black environment, six bounces")

        // Closing the bias check quantitatively: the off reference's own standard error at its
        // budget follows from the error just measured at a known smaller budget, and the two
        // references differ by about that much. That is "no bias I can resolve", with the
        // resolution named rather than implied.
        val noiseFloor = errorOff * sqrt(measuredOff.samples.toDouble() / referenceOff.samples)
        val observed = rmse(referenceOn.pixels, referenceOff.pixels, pixels)
        say("lamp-in-fog reference noise: the two references differ by ${show(observed)} RMS, " +
            "and the off arm's own standard error at ${referenceOff.samples} samples predicts " +
            "${show(noiseFloor)} (ratio ${show(observed / noiseFloor)}) over ${pixels.size} pixels")
        check(observed < 2.5 * noiseFloor) {
            "References differ by $observed against a $noiseFloor noise floor over ${pixels.size} pixels"
        }
        say("PASS lamp-in-fog bias is below the reference's own resolution: ${show(observed)} " +
            "against ${show(noiseFloor)} over ${pixels.size} lit pixels")
    }

    // ---------------------------------------------------------------- captures

    /**
     * The figures, at a size someone can read, from the same scenes and the same code the numbers
     * above came from. Pixel jitter is on here, because a figure should be antialiased, and off in
     * every measurement above, because a per-pixel comparison of two differently antialiased
     * images measures the antialiasing.
     */
    private suspend fun captures(renderer: Renderer, out: File) {
        val width = 480
        val height = 360
        suspend fun lamp(medium: Medium, target: Int, bounces: Int = 6) =
            converge(renderer, lampBox(), PathTraceEnvironment.constant(0f), lampCamera(renderer),
                medium, target, 16, bounces, emitters = true, jitter = 1f, width = width, height = height)
        val off = lamp(Medium.none, 1024)
        writePng(off.pixels, width, height, 0.45f, File(out, "medium_off_${off.samples}spp.png"))
        for (anisotropy in listOf(-0.6f, 0f, 0.6f)) {
            val shot = lamp(Medium(0.22f, Vector3f(0.92f, 0.90f, 0.86f), anisotropy, LAMP_BOUNDS), 1024)
            val tag = if (anisotropy < 0) "minus${(-anisotropy * 100).toInt()}" else "${(anisotropy * 100).toInt()}"
            writePng(shot.pixels, width, height, 0.45f, File(out, "medium_g${tag}_${shot.samples}spp.png"))
        }
        // Pure absorption at the same density, so the same picture can be read for what the albedo
        // does: this one has no glow in it at all, only attenuation.
        val absorbing = lamp(Medium(0.22f, Vector3f(0f), 0f, LAMP_BOUNDS), 1024)
        writePng(absorbing.pixels, width, height, 0.45f,
            File(out, "medium_absorbing_${absorbing.samples}spp.png"))
        // And the old mechanism at a density chosen to look similar, for the comparison the KDoc
        // claims: a depth curve cannot glow, and the lamp's halo is the difference.
        val fogged = converge(renderer, lampBox(), PathTraceEnvironment.constant(0f), lampCamera(renderer),
            Medium.none, 1024, 16, 6, emitters = true, jitter = 1f, width = width, height = height,
            fogDensity = 0.22f)
        writePng(fogged.pixels, width, height, 0.45f, File(out, "medium_depthfog_${fogged.samples}spp.png"))
        say("PASS medium captures at ${width}x$height: ${out.absolutePath}/medium_*.png")
    }

    /**
     * The demo scene's picture, and the one comparison that shows what a participating medium *is*.
     *
     * Three renders of the same geometry: the medium at the density the demo ships with, the same
     * density through `fogDensity` with a grey fog colour, and no medium at all.
     *
     * **Finding a statistic that separates them took two tries, and the first one was measuring
     * the wrong thing.** The obvious idea -- lateral variation along a row of hazed pixels -- came
     * out *highest* for the clear render (2.10), then `fogDensity` (1.94), then the medium (1.09),
     * which is the opposite of the intended reading and perfectly correct: the clear render has
     * hard-edged pools of light against black air, and filling the air with haze *reduces* relative
     * variance. Both of the other renders still carry the slats' shadows on the wall and floor,
     * because shadows on surfaces are not what is at issue.
     *
     * What is at issue is light where there is **no surface at all**, so that is what this
     * measures. Above the back wall's top edge the primary ray hits nothing; the clear render is
     * exactly black there. The medium render is not, and it is not uniform either -- the lamp's
     * fan and the slats' shadows are visible in empty space. `depthFog` cannot do that in
     * principle, not merely in practice: a background miss has no depth, so `depthFog` maps every
     * one of those pixels to exactly `fogColor`, whatever direction it was looking. Its spread
     * over that population is zero by construction. That makes the comparison a statement about
     * the two mechanisms rather than about a tuning choice.
     *
     * Three renders of the same geometry: the medium at the density the demo ships with, the same
     * density through `fogDensity` instead, and no medium at all. The slats appear in the haze only
     * in the first: a depth-fog curve cannot cast a shadow, because it never asks whether anything
     * is between a point and a light.
     */
    private suspend fun godRaysCapture(renderer: Renderer, out: File) {
        val width = 640
        val height = 400
        suspend fun shot(medium: Medium, fog: Float, target: Int) =
            converge(renderer, godRays(), PathTraceEnvironment.constant(0f), godRaysCamera(renderer),
                medium, target, 16, bounces = 8, emitters = true, jitter = 1f,
                width = width, height = height, fogDensity = fog,
                fogColor = Vector3f(0.35f, 0.34f, 0.32f))
        val haze = Medium(GOD_RAYS_DENSITY, Vector3f(0.96f, 0.95f, 0.93f), 0.4f, GOD_RAYS_BOUNDS)
        val scattering = shot(haze, 0f, 1536)
        val fogged = shot(Medium.none, GOD_RAYS_DENSITY, 1536)
        val clear = shot(Medium.none, 0f, 1536)
        writePng(scattering.pixels, width, height, 0.22f, File(out, "godrays_medium_${scattering.samples}spp.png"))
        writePng(fogged.pixels, width, height, 0.22f, File(out, "godrays_depthfog_${fogged.samples}spp.png"))
        writePng(clear.pixels, width, height, 0.22f, File(out, "godrays_clear_${clear.samples}spp.png"))

        // Pixels whose primary ray hits nothing at all, identified from the render that has neither
        // mechanism in it: above the back wall's top edge there is no geometry, so the clear render
        // is exactly the background colour there, which is black.
        val empty = (0 until width * height).filter { luminance(clear.pixels, it) <= 0.0 }
        check(empty.size > 500) {
            "Only ${empty.size} of ${width * height} pixels see no geometry, which is too few to " +
                "measure light in empty space over"
        }
        fun spread(pixels: FloatArray): Pair<Double, Double> {
            val values = empty.map { luminance(pixels, it) }
            val mean = values.average()
            return mean to sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }
        val (mediumMean, mediumSpread) = spread(scattering.pixels)
        val (fogMean, fogSpread) = spread(fogged.pixels)
        val (clearMean, clearSpread) = spread(clear.pixels)
        say("god rays, over the ${empty.size} pixels of ${width}x$height whose primary ray hits " +
            "nothing: the medium puts mean ${show(mediumMean)} radiance there with a spatial " +
            "standard deviation of ${show(mediumSpread)}; depthFog at the same density puts " +
            "${show(fogMean)} with a deviation of ${show(fogSpread)}; with neither, " +
            "${show(clearMean)} and ${show(clearSpread)}")
        check(clearMean == 0.0 && clearSpread == 0.0) {
            "The clear render is not black where it hits no geometry (mean $clearMean, deviation " +
                "$clearSpread), so this population is not the population it is named after"
        }
        check(mediumMean > 0.02) {
            "The medium puts only $mediumMean radiance into empty space over ${empty.size} pixels"
        }
        // depthFog's spread over this population is zero by construction. What is left of it is
        // float32 accumulation over 100000 pixels -- measured 1.5e-6 against a mean of 0.34, five
        // significant figures of constant -- so the claim is stated relative to that mean rather
        // than as an absolute, which would be a claim about the arithmetic instead.
        check(fogSpread / fogMean < 1e-4) {
            "depthFog varies by ${fogSpread / fogMean} of its own mean over ${empty.size} " +
                "background pixels, where it should map every one of them to exactly fogColor"
        }
        check(mediumSpread > 0.5) {
            "The medium's spatial deviation in empty space is only $mediumSpread over " +
                "${empty.size} pixels, which is not the structure this arm is about"
        }
        check(mediumSpread > 1000.0 * fogSpread) {
            "The medium's structure in empty space ($mediumSpread) is not distinguishable from " +
                "depthFog's ($fogSpread) over ${empty.size} pixels"
        }
        say("PASS the medium puts structured light where there is no surface, and a depth curve " +
            "cannot: over ${empty.size} background pixels of ${width}x$height at " +
            "${scattering.samples} samples, spatial deviation ${show(mediumSpread)} against " +
            "depthFog's ${show(fogSpread)} and a clear render's ${show(clearSpread)}")
    }

    /**
     * Every arm, on every backend this device exposes.
     *
     * Drives every check and writes the captures under [outputDirectory]. Call from a suspend body
     * in a box below a live renderer, as with the other path-trace smokes.
     *
     * `COMPUTE_BVH` always runs; `RAY_QUERY` runs as well wherever `device.supportsRayQuery`, which
     * is the same condition `PathTraceGpuSmoke` uses to decide the base tracer's backend coverage.
     * A device without ray queries therefore behaves exactly as before, one pass, and says so.
     *
     * Artifacts go to `<outputDirectory>/<backend>/` — a subdirectory per pass, because the figures
     * are per-backend renders and a figure that does not say which backend produced it is not
     * evidence about either. The cumulative `report.txt` is written at `<outputDirectory>` as well
     * as per pass, so there is still one file with the whole run in it.
     */
    suspend fun run(renderer: Renderer, outputDirectory: String) {
        val root = File(outputDirectory)
        val backends = buildList {
            add(PathTracingRenderer.Backend.COMPUTE_BVH)
            if (renderer.device.supportsRayQuery) add(PathTracingRenderer.Backend.RAY_QUERY)
        }
        say("backends on this device: ${backends.joinToString { it.name }}" +
            if (backends.size == 1) " (no enabled KHR ray queries, so one pass)" else "")

        for (backend in backends) {
            activeBackend = backend
            val out = File(root, backend.name.lowercase())
            say("== backend $backend: artifacts in ${out.absolutePath}")
            val arms = listOf<Pair<String, suspend () -> Unit>>(
                "inert without extinction" to { inertWithoutExtinction(renderer) },
                "closed form through depthFog" to { absorptionAgainstDepthFog(renderer) },
                "absorption against the closed form" to { absorptionAgainstClosedForm(renderer) },
                "energy conservation" to { scatteringFurnace(renderer) },
                "bounce truncation" to { furnaceBounceSweep(renderer) },
                "single scattering against a quadrature" to { singleScatteringAgainstQuadrature(renderer) },
                "the emitter connection" to { emitterConnectionIsInvisible(renderer) },
                "lamp in fog" to { lampInFog(renderer) },
                "captures" to { captures(renderer, out) },
                "god rays" to { godRaysCapture(renderer, out) }
            )
            for ((name, arm) in arms) {
                try {
                    arm()
                } finally {
                    report.appendLine("-- after: $backend / $name")
                    flushReport(out)
                    flushReport(root)
                }
            }
            say("PASS backend $backend: every arm")
        }

        say("PASS path-tracing participating-medium GPU smoke on " +
            backends.joinToString { it.name })
        flushReport(root)
    }
}
