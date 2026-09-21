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
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real GPU checks for `PathTracingRenderer.emitterSampling`, the path tracer's direct sampling of
 * emissive triangles: four scenes, each measured against a number that exists outside this file.
 *
 * Unbiasedness is checked before variance. A variance win from a biased estimator reduces noise
 * and changes the image, which is what a wrong area-to-solid-angle Jacobian, a missing cosine and
 * a double-counted emission arm all do as well, so the noise reduction on its own says nothing
 * about which of the two happened.
 *
 * Which scene a check uses decides what it can resolve. A small bright lamp in the dark is where
 * BSDF sampling barely works, so its own converged image stays noisy, and a reference that noisy
 * cannot resolve a two-percent bias at any tolerance. Three of the four arms below are therefore
 * uniform-radiance scenes, where both sides converge and the comparison is sharp:
 *
 *  - `inertWithoutEmitters` — a scene with nothing emissive in it, where on and off must agree bit
 *    for bit. Which means the two runs have to stop on the same sample, and a polled sample count
 *    cannot be made to land there, so the second run targets whatever the first reached and the
 *    pair is retried until they match.
 *  - `enclosureAgainstEnvironment` — a sphere inside a uniformly emissive cube, one bounce. Every
 *    direction carries radiance one, so this is the same integral as the same sphere under a
 *    constant unit *environment* map, which the tracer solves through a separate, independently
 *    checked next-event path. One bounce means neither run applies an MIS weight at all, so what is
 *    under test is the new pdf alone: its normalisation and its Jacobian. Both furnace means are
 *    also asserted against 1.0, so the arm is absolute and not a self-comparison.
 *  - `enclosureComposition` — the same enclosure with six differently coloured faces and two
 *    bounces, emitter sampling on against off. Both arms fire and both MIS weights matter, and a
 *    selection probability that has come off its triangle tints the answer. Uniform near-field
 *    radiance again means the off run converges, so this resolves a fraction of a percent.
 *  - the lamp box, driven from [run] — two small lamps in the dark, which is the scene the variance
 *    claim is measured in. Its bias residual is reported at two reference budgets: a residual that
 *    falls like one over the square root of the budget is the reference's own noise, and one that
 *    holds is bias. That decay is asserted, because a fixed tolerance there would be a tolerance on
 *    noise.
 *
 * The lamp box is built around the three failures the uniform scenes cannot show. Its two lamps
 * differ in size, colour and power, so a table whose probability and triangle index have come apart
 * shows as a colour shift rather than as noise. One of them is one-sided and faces down, because a
 * back face culled on a BSDF hit has to be dark to the direct sampler too. And a sphere sits on the
 * floor, where its shadow is a shadow ray: the integrator's occlusion query stops a thousandth
 * short of the sampled point, so that a lamp cannot occlude its own connection, and a query that
 * ran the whole way would discard every direct contribution and leave a correct image with no
 * variance improvement in it. The RMSE ratio asserted here is what would notice.
 */

/**
 * Four arms measured at 96x72 on the software BVH backend, then three figures at 480x360. Each arm
 * prints a `PASS` line naming the population it measured. A live renderer is required throughout,
 * so this carries no `FIELD_TEST` annotation and is driven from a box instead:
 * `dev_docs/agents/demos/path_tracing_emitters/verify.yaml` is the transcript that does it.
 */
object PathTraceEmitterGpuSmoke {
    private const val WIDTH = 96
    private const val HEIGHT = 72
    /** Enough for the two enclosure scenes, whose incident radiance field is uniform and near-field. */
    private const val ENCLOSURE_SAMPLES = 16384
    /** The lamp box's high reference, and the low one it is compared against to separate noise from bias. */
    private const val LAMP_REFERENCE = 65536
    private const val LAMP_LOW_REFERENCE = 4096
    /** The equal, stated budget at which RMSE is compared. One sample per frame keeps it exact. */
    private const val MEASURED_SAMPLES = 256

    // ---------------------------------------------------------------- scenes

    /** Sphere inside a two-sided emissive cube. Radiances are y-, y+, z-, z+, x-, x+. */
    private fun enclosure(radiances: List<Vector3f>): PathTraceScene = PathTraceScene().apply {
        require(radiances.size == 6)
        sphere(Vector3f(), 0.6f, material(PathTraceMaterial.plastic(1f, 1f, 1f, 0.4f)), 48, 24)
        val h = 3f
        val faces = radiances.map { material(PathTraceMaterial.emissive(it.x, it.y, it.z)) }
        quad(Vector3f(-h,-h,-h), Vector3f(h,-h,-h), Vector3f(h,-h,h), Vector3f(-h,-h,h), faces[0])
        quad(Vector3f(-h, h,-h), Vector3f(h, h,-h), Vector3f(h, h,h), Vector3f(-h, h,h), faces[1])
        quad(Vector3f(-h,-h,-h), Vector3f(-h,h,-h), Vector3f(h,h,-h), Vector3f(h,-h,-h), faces[2])
        quad(Vector3f(-h,-h, h), Vector3f(-h,h, h), Vector3f(h,h, h), Vector3f(h,-h, h), faces[3])
        quad(Vector3f(-h,-h,-h), Vector3f(-h,h,-h), Vector3f(-h,h,h), Vector3f(-h,-h,h), faces[4])
        quad(Vector3f( h,-h,-h), Vector3f( h,h,-h), Vector3f( h,h,h), Vector3f( h,-h,h), faces[5])
    }

    /** The same sphere with no enclosure at all: its light is a constant environment map instead. */
    private fun bareSphere(): PathTraceScene = PathTraceScene().apply {
        sphere(Vector3f(), 0.6f, material(PathTraceMaterial.plastic(1f, 1f, 1f, 0.4f)), 48, 24)
    }

    private fun enclosureCamera(renderer: Renderer) = Camera(renderer).also {
        it.at.set(0f, 0f, 2f); it.target.set(0f, 0f, 0f); it.p.fov = 1.2
    }

    /**
     * Close enough that every pixel, corner included, lands on the sphere. The emissive faces are
     * not perfectly black reflectors — nothing here is, `evaluate` gives every dielectric an f0 of
     * 0.04 — so a directly visible face carries its own emission *plus* a few percent of its
     * neighbours', which a constant environment map has no counterpart for. Keeping the faces out
     * of frame is what makes the two mechanisms comparable pixel for pixel.
     */
    private fun furnaceCamera(renderer: Renderer) = Camera(renderer).also {
        it.at.set(0f, 0f, 1.25f); it.target.set(0f, 0f, 0f); it.p.fov = 0.5
    }

    private fun lampBox(): PathTraceScene = PathTraceScene().apply {
        val floor = material(PathTraceMaterial.diffuse(0.72f, 0.70f, 0.66f))
        val wall = material(PathTraceMaterial.diffuse(0.58f, 0.22f, 0.18f))
        val ball = material(PathTraceMaterial.diffuse(0.75f, 0.75f, 0.78f))
        val warm = material(PathTraceMaterial.emissive(24f, 21f, 15f))
        // One-sided and facing down: quad(a,b,c,d) winds so that cross(b-a,c-a) points at -y.
        val cool = material(PathTraceMaterial.emissive(60f, 74f, 110f).copy(doubleSided = false))
        quad(Vector3f(-5f, 0f, -5f), Vector3f(-5f, 0f, 5f), Vector3f(5f, 0f, 5f), Vector3f(5f, 0f, -5f), floor)
        quad(Vector3f(-5f, 0f, -3.2f), Vector3f(-5f, 5f, -3.2f), Vector3f(5f, 5f, -3.2f), Vector3f(5f, 0f, -3.2f), wall)
        sphere(Vector3f(0.55f, 0.6f, 0.7f), 0.6f, ball, 40, 20)
        quad(Vector3f(-1.3f, 2.6f, -0.9f), Vector3f(-0.5f, 2.6f, -0.9f),
            Vector3f(-0.5f, 2.6f, -0.1f), Vector3f(-1.3f, 2.6f, -0.1f), warm)
        quad(Vector3f(1.0f, 2.2f, 0.2f), Vector3f(1.35f, 2.2f, 0.2f),
            Vector3f(1.35f, 2.2f, 0.55f), Vector3f(1.0f, 2.2f, 0.55f), cool)
    }

    private fun lampCamera(renderer: Renderer) = Camera(renderer).also {
        it.at.set(0.2f, 1.9f, 6.4f); it.target.set(0f, 0.9f, 0f); it.p.fov = 0.78
    }

    // ------------------------------------------------------------- machinery

    private class Shot(val pixels: FloatArray, val samples: Int, val emitters: Int)

    /**
     * The sibling helper in [PathTraceGpuSmoke] gives up after sixty seconds, which a
     * 65536-sample reference does not fit into on a machine that is doing anything else. Two runs
     * died there having reached 63168 and 10496 samples, which reads as a hang and is only load.
     */
    private suspend fun awaitSamples(tracer: PathTracingRenderer, count: Int, seconds: Long = 900) {
        val deadline = System.nanoTime() + seconds*1_000_000_000L
        while (tracer.samples < count) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $count samples; got ${tracer.samples}" }
            delay(5)
        }
    }

    private suspend fun converge(renderer: Renderer, backend: PathTracingRenderer.Backend,
                                 scene: PathTraceScene, environment: PathTraceEnvironment,
                                 camera: Camera, emitters: Boolean, bounces: Int, target: Int,
                                 perFrame: Int, jitter: Float = 0f, showBackground: Boolean = false): Shot {
        val tracer = PathTracingRenderer(renderer, scene, environment, WIDTH, HEIGHT, camera, backend,
            name = "emitter-check")
        return tracer.use {
            tracer.emitterSampling = emitters
            tracer.maxBounces = bounces
            tracer.showEnvironment = showBackground
            tracer.background = Vector4f(0f, 0f, 0f, 1f)
            // Both configurations must sample the identical pixel footprint, or a per-pixel
            // comparison is measuring antialiasing rather than the estimator.
            tracer.pixelJitter = jitter
            tracer.samplesPerFrame = perFrame
            tracer.install()
            awaitSamples(tracer, target)
            tracer.paused = true
            val pixels = tracer.readLinear()
            check(pixels.all { it.isFinite() && it >= 0f }) {
                "Nonfinite or negative radiance: ${pixels.withIndex().filter { !it.value.isFinite() || it.value < 0f }.take(8)}"
            }
            Shot(pixels, tracer.samples, tracer.emitterCount)
        }
    }

    private fun luminance(p: FloatArray, i: Int) = 0.2126*p[i*4] + 0.7152*p[i*4+1] + 0.0722*p[i*4+2]

    /**
     * Two runs that stopped on the same sample. They cannot be *made* to: `awaitSamples` polls and
     * the render thread may fit one more dispatch in between. An equal-budget claim has to be true,
     * so the second run targets whatever the first actually reached and the pair is retried.
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

    /** Relative agreement of two converged images: whole-image, worst tile, and the per-pixel spread. */
    private class Agreement(val pixels: Int, val global: Double, val worstTile: String, val worstTileValue: Double,
                            val tiles: Int, val median: Double, val p95: Double) {
        override fun toString() = "${pixels} px: global ${show(global)}, worst of $tiles tiles " +
            "${show(worstTileValue)} (tile $worstTile), per-pixel median ${show(median)}, p95 ${show(p95)}"
    }

    /**
     * A whole-image mean can average away a spatially structured bias, which is exactly the shape a
     * wrong Jacobian has, so the tiles are the assertion that matters and the global mean is the
     * tightest one. Errors are independent per pixel, so a tile of 288 of them is a far stronger
     * statement than any single pixel.
     */
    private fun agree(a: FloatArray, b: FloatArray, pixels: List<Int>): Agreement {
        var sumA = 0.0; var sumB = 0.0
        pixels.forEach { i -> sumA += luminance(a, i); sumB += luminance(b, i) }
        val tiles = (0 until 4).flatMap { ty -> (0 until 6).map { tx -> tx to ty } }.mapNotNull { (tx, ty) ->
            val inTile = pixels.filter { it%WIDTH in tx*16 until tx*16+16 && it/WIDTH in ty*18 until ty*18+18 }
            if (inTile.size < 32) null else {
                val ta = inTile.sumOf { luminance(a, it) }
                val tb = inTile.sumOf { luminance(b, it) }
                "$tx,$ty" to abs(ta-tb)/tb
            }
        }
        check(tiles.size >= 12) { "Only ${tiles.size} usable tiles" }
        val worst = tiles.maxBy { it.second }
        val relative = pixels.map { i -> abs(luminance(a, i)-luminance(b, i))/luminance(b, i) }.sorted()
        return Agreement(pixels.size, abs(sumA-sumB)/sumB, worst.first, worst.second, tiles.size,
            relative[relative.size/2], relative[(relative.size*95)/100])
    }

    private fun rmse(image: FloatArray, reference: FloatArray, pixels: List<Int>): Double {
        var sum = 0.0
        pixels.forEach { i -> for (c in 0..2) { val d = image[i*4+c]-reference[i*4+c]; sum += d.toDouble()*d } }
        return sqrt(sum / (pixels.size*3))
    }

    private fun srgb(v: Double) = (if (v <= 0.0031308) 12.92*v else 1.055*v.pow(1/2.4)-0.055).coerceIn(0.0, 1.0)
    private fun show(v: Double) = "%.5f".format(v)

    /** Exposure plus the same Reinhard curve the tracer's own presentation pass uses, then sRGB. */
    private fun writePng(pixels: FloatArray, width: Int, height: Int, exposure: Float, file: File) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y*width+x)*4
            val alpha = maxOf(pixels[i+3], 1e-20f)
            var rgb = 0
            for (c in 0..2) {
                val hdr = (pixels[i+c]/alpha*exposure).coerceAtLeast(0f).toDouble()
                rgb = (rgb shl 8) or (srgb(hdr/(1.0+hdr))*255.0+0.5).toInt().coerceIn(0, 255)
            }
            image.setRGB(x, y, rgb)
        }
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
        check(file.length() > 512) { "Wrote a suspiciously small PNG: ${file.absolutePath} (${file.length()} bytes)" }
    }

    // ---------------------------------------------------------------- checks

    /**
     * A scene with nothing to sample must reach the previous integrator exactly, bit for bit.
     *
     * Bit-for-bit means the two runs have to stop on the same sample, and they cannot be *made* to:
     * `awaitSamples` polls, the render thread may fit one more dispatch in between, and a
     * sixteen-sample overshoot is a one-percent difference that looks exactly like a shading change.
     * So the second run targets whatever the first actually reached, and the pair is retried until
     * the counts match. The assertion is then on the pixels and not on the timing.
     */
    private suspend fun inertWithoutEmitters(renderer: Renderer, backend: PathTracingRenderer.Backend) {
        val dark = PathTraceScene().apply {
            val grey = material(PathTraceMaterial.plastic(1f, 1f, 1f, 0.4f))
            quad(Vector3f(-20f,-20f,0f), Vector3f(20f,-20f,0f), Vector3f(20f,20f,0f), Vector3f(-20f,20f,0f), grey)
        }
        val camera = Camera(renderer).also { it.at.set(0f,0f,3f); it.target.set(0f,0f,0f) }
        suspend fun shot(emitters: Boolean, target: Int) =
            PathTracingRenderer(renderer, dark, PathTraceEnvironment.constant(), 48, 32, camera, backend,
                name = "emitter-inert").use { tracer ->
                check(tracer.emitterCount == 0)
                tracer.emitterSampling = emitters
                tracer.maxBounces = 3; tracer.pixelJitter = 0f; tracer.samplesPerFrame = 1
                tracer.install(); awaitSamples(tracer, target); tracer.paused = true
                Shot(tracer.readLinear(), tracer.samples, tracer.emitterCount)
            }
        val (on, off) = matched({ shot(true, it) }, { shot(false, it) }, 96)
        val both = listOf(on, off)
        check(both[0].pixels.contentEquals(both[1].pixels)) {
            val worst = both[0].pixels.indices.maxBy { abs(both[0].pixels[it]-both[1].pixels[it]) }
            "A scene with no emitter must be unchanged: worst $worst ${both[0].pixels[worst]} vs ${both[1].pixels[worst]}"
        }
        println("PASS emitter sampling is inert with no emitters: 48x32 identical at ${both[0].samples} samples")
    }

    /**
     * The absolute check. A uniformly emissive enclosure and a constant environment map present the
     * identical incident radiance field, and at one bounce neither run weights anything, so this
     * compares the new area pdf against the environment sampler that was already trusted. A pdf off
     * by a constant shows as a flat offset; a Jacobian off by cos or by distance squared shows as a
     * gradient across the sphere, which is why the tiles are asserted and not just the mean.
     */
    private suspend fun enclosureAgainstEnvironment(renderer: Renderer, backend: PathTracingRenderer.Backend) {
        val emissive = converge(renderer, backend, enclosure(List(6) { Vector3f(1f, 1f, 1f) }),
            PathTraceEnvironment.constant(0f), furnaceCamera(renderer), true, 1, ENCLOSURE_SAMPLES, 64)
        check(emissive.emitters == 12) { "Expected twelve emissive cube triangles, got ${emissive.emitters}" }
        val environment = converge(renderer, backend, bareSphere(), PathTraceEnvironment.constant(1f),
            furnaceCamera(renderer), false, 1, ENCLOSURE_SAMPLES, 64, showBackground = true)
        check(environment.emitters == 0)
        val pixels = (0 until WIDTH*HEIGHT).filter { environment.pixels[it*4+3] == 1f }
        check(pixels.size == WIDTH*HEIGHT)
        // Every pixel must be sphere, in both. A frame corner that slipped past the silhouette would
        // compare a cube face against an environment miss and the whole check would be about that.
        check(pixels.all { luminance(environment.pixels, it) > 0.5 && luminance(emissive.pixels, it) > 0.5 })
        val result = agree(emissive.pixels, environment.pixels, pixels)
        val meanEmissive = pixels.sumOf { luminance(emissive.pixels, it) }/pixels.size
        val meanEnvironment = pixels.sumOf { luminance(environment.pixels, it) }/pixels.size
        println("emitter pdf against the environment sampler at ${emissive.samples} samples, one bounce: " +
            "$result; white-furnace mean ${show(meanEmissive)} emissive vs ${show(meanEnvironment)} environment")
        // Measured 0.00001 / 0.00011 / 0.00206 on an M1 Max. The floors are a few times those, not
        // orders of magnitude: a floor loose enough never to flake certifies nothing.
        check(result.global < 0.0001) { "Whole-image disagreement ${result.global} over ${pixels.size} pixels" }
        check(result.worstTileValue < 0.0005) { "Tile ${result.worstTile} disagrees by ${result.worstTileValue}" }
        check(result.p95 < 0.006) { "Per-pixel p95 disagreement ${result.p95}" }
        // Unit radiance from every direction onto a white surface: the same furnace the environment
        // path is already held to, reached entirely through emissive geometry.
        check(abs(meanEmissive-1.0) < 0.01) { "Emissive furnace mean $meanEmissive" }
        check(abs(meanEnvironment-1.0) < 0.01) { "Environment furnace mean $meanEnvironment" }
        println("PASS emitter pdf is right in absolute terms: ${WIDTH}x$HEIGHT, ${pixels.size} pixels, " +
            "${emissive.samples} samples, emissive enclosure matches a constant environment map")
    }

    /**
     * The composition check. Six differently coloured faces at two bounces: both MIS weights matter
     * now, and a double count or a probability that has come off its triangle changes the answer.
     * Uniform near-field radiance means the off run converges too, so this resolves a fraction of a
     * percent rather than a few percent.
     */
    private suspend fun enclosureComposition(renderer: Renderer, backend: PathTracingRenderer.Backend) {
        val radiances = listOf(Vector3f(0.9f,0.35f,0.2f), Vector3f(1.4f,1.4f,1.3f), Vector3f(0.2f,0.4f,1.1f),
            Vector3f(0.5f,0.9f,0.35f), Vector3f(1.1f,0.9f,0.3f), Vector3f(0.3f,0.8f,0.9f))
        suspend fun shot(emitters: Boolean, target: Int, bounces: Int) =
            converge(renderer, backend, enclosure(radiances), PathTraceEnvironment.constant(0f),
                enclosureCamera(renderer), emitters, bounces, target, 64)
        // maxBounces is a *path length* limit, and next-event estimation reaches one segment past
        // it: the connection from the last vertex to a light is a scattering event a BSDF hit would
        // have needed another bounce to make. So on at two bounces transports exactly what off at
        // three does, and comparing equal maxBounces instead would report that extra segment --
        // 0.8% of this image -- as bias. It is not bias; it is more transport for the same budget.
        val (a, b) = matched({ shot(true, it, 2) }, { shot(false, it, 3) }, ENCLOSURE_SAMPLES)
        val shots = listOf(a, b)
        val pixels = (0 until WIDTH*HEIGHT).filter { shots[1].pixels[it*4+3] == 1f }
        val result = agree(shots[0].pixels, shots[1].pixels, pixels)
        println("emitter MIS composition at ${shots[0].samples} samples, on at two bounces against off at " +
            "three, six coloured faces: $result")
        // Measured 0.00004 / 0.00054 / 0.00360 on an M1 Max.
        check(result.global < 0.0003) { "Whole-image disagreement ${result.global}" }
        check(result.worstTileValue < 0.002) { "Tile ${result.worstTile} disagrees by ${result.worstTileValue}" }
        check(result.p95 < 0.012) { "Per-pixel p95 disagreement ${result.p95}" }
        println("PASS emitter arms compose without double counting: ${WIDTH}x$HEIGHT, ${pixels.size} pixels, " +
            "${shots[0].samples} samples each, on and off agree")
    }

    // ------------------------------------------------------------------ run

    /**
     * Drives every check and writes the before/after captures under [outputDirectory].
     * Call from a suspend body in a box below a live renderer, as with the other path-trace smokes.
     */
    suspend fun run(renderer: Renderer, outputDirectory: String) {
        val backend = PathTracingRenderer.Backend.COMPUTE_BVH
        val out = File(outputDirectory)
        inertWithoutEmitters(renderer, backend)
        enclosureAgainstEnvironment(renderer, backend)
        enclosureComposition(renderer, backend)

        // The lamp box. Two reference budgets, because its own off-reference is the noisiest thing
        // in this file and a fixed tolerance on that number would be a tolerance on noise.
        suspend fun lamp(emitters: Boolean, target: Int, perFrame: Int, jitter: Float = 0f, bounces: Int = 3) =
            converge(renderer, backend, lampBox(), PathTraceEnvironment.constant(0f), lampCamera(renderer),
                emitters, bounces, target, perFrame, jitter).also {
                check(it.emitters == 4) { "Expected four emissive lamp triangles, got ${it.emitters}" }
            }
        val referenceOn = lamp(true, LAMP_REFERENCE, 64)
        // Four bounces against on's three: the same matched transport depth as the enclosure above.
        val referenceOff = lamp(false, LAMP_REFERENCE, 64, bounces = 4)
        val lowOn = lamp(true, LAMP_LOW_REFERENCE, 64)
        val lowOff = lamp(false, LAMP_LOW_REFERENCE, 64, bounces = 4)
        // Lit surface only: the background carries no signal and the visible lamps are exact in both.
        val pixels = (0 until WIDTH*HEIGHT).filter {
            luminance(referenceOn.pixels, it) in 0.004..6.0 && referenceOn.pixels[it*4+3] == 1f
        }
        check(pixels.size > WIDTH*HEIGHT/4) { "Lit population is only ${pixels.size} pixels" }
        val high = agree(referenceOn.pixels, referenceOff.pixels, pixels)
        val low = agree(lowOn.pixels, lowOff.pixels, pixels)
        val decay = low.median/high.median
        val expected = sqrt(LAMP_REFERENCE.toDouble()/LAMP_LOW_REFERENCE)
        println("lamp-box bias at ${referenceOff.samples} samples: $high")
        println("lamp-box bias at ${lowOff.samples} samples: $low")
        println("lamp-box residual decay ${show(decay)} for a ${show(expected)} budget ratio: " +
            "noise decays, bias does not")
        // A residual that shrinks with the reference's own budget is the reference's noise. Half of
        // the ideal square-root decay is a generous floor; a real bias would hold the ratio near one.
        check(decay > 3.0) { "Residual did not decay with budget: $decay against $expected" }
        check(high.median < 0.06) { "Per-pixel median residual ${high.median} at ${referenceOff.samples} samples" }
        check(high.global < 0.004) { "Whole-image residual ${high.global} at ${referenceOff.samples} samples" }
        println("PASS lamp-box residual is reference noise, not bias: ${pixels.size} lit pixels of " +
            "${WIDTH}x$HEIGHT, ${LAMP_LOW_REFERENCE} -> ${LAMP_REFERENCE} samples shrank it ${show(decay)}x")

        // Variance, against the better-converged reference now that the two are known to agree.
        val (measuredOn, measuredOff) = matched({ lamp(true, it, 1) }, { lamp(false, it, 1) }, MEASURED_SAMPLES)
        val errorOn = rmse(measuredOn.pixels, referenceOn.pixels, pixels)
        val errorOff = rmse(measuredOff.pixels, referenceOn.pixels, pixels)
        val ratio = errorOff/errorOn
        println("lamp-box variance at ${measuredOn.samples} samples over ${pixels.size} lit pixels: " +
            "RMSE ${show(errorOff)} off, ${show(errorOn)} on, ratio ${show(ratio)}")
        // The denominator of this ratio is set by the scene, not by the estimator: two lamps of 0.64
        // and 0.12 square units in a black environment at three bounces, 96x72, and the lit-surface
        // population above. A larger light, or any environment light at all, moves it down.
        // Measured 35.3 on an M1 Max; the floor is 28.
        check(ratio > 28.0) { "RMSE ratio $ratio at ${measuredOn.samples} samples: off $errorOff, on $errorOn" }
        println("PASS emitter sampling reduces error: RMSE ratio ${show(ratio)} at ${measuredOn.samples} " +
            "samples per pixel, ${pixels.size} lit pixels of ${WIDTH}x$HEIGHT, two lamps, black environment, " +
            "three bounces")

        // Closing the loop on the bias check quantitatively. The off reference's own standard error
        // at its budget follows from the error just measured at a known smaller budget, and the two
        // references differ by about that much -- which is the statement "no bias I can resolve",
        // with the resolution named instead of implied.
        val predicted = errorOff*sqrt(measuredOff.samples.toDouble()/referenceOff.samples)
        val observed = rmse(referenceOn.pixels, referenceOff.pixels, pixels)
        println("lamp-box reference noise: references differ by ${show(observed)} RMS, and off's own " +
            "standard error at ${referenceOff.samples} samples predicts ${show(predicted)} " +
            "(ratio ${show(observed/predicted)})")
        check(observed < 2.0*predicted) { "References differ by $observed against a $predicted noise floor" }
        println("PASS lamp-box bias is below the reference's own resolution: ${show(observed)} against " +
            "${show(predicted)} over ${pixels.size} lit pixels")

        captures(renderer, backend, out)
        println("PASS path-tracing emitter GPU smoke")
    }

    /**
     * The figures, at a size someone can read, from the same scene and the same code the numbers
     * above came from. Pixel jitter is on here, because a figure should be antialiased, and off in
     * every measurement above, because a per-pixel comparison of two differently antialiased images
     * measures the antialiasing.
     */
    private suspend fun captures(renderer: Renderer, backend: PathTracingRenderer.Backend, out: File) {
        val width = 480
        val height = 360
        suspend fun shot(emitters: Boolean, target: Int, perFrame: Int): Shot {
            val tracer = PathTracingRenderer(renderer, lampBox(), PathTraceEnvironment.constant(0f),
                width, height, lampCamera(renderer), backend, name = "emitter-capture")
            return tracer.use {
                tracer.emitterSampling = emitters
                tracer.maxBounces = 3
                tracer.showEnvironment = false
                tracer.background = Vector4f(0f, 0f, 0f, 1f)
                tracer.samplesPerFrame = perFrame
                tracer.install()
                awaitSamples(tracer, target)
                tracer.paused = true
                Shot(tracer.readLinear(), tracer.samples, tracer.emitterCount)
            }
        }
        val (on, off) = matched({ shot(true, it, 4) }, { shot(false, it, 4) }, MEASURED_SAMPLES)
        // 4096 rather than the 65536 of the measurement above: this resolution is 25x the pixels
        // of the measured runs, and a figure does not need a 65536-sample reference to be legible.
        val reference = shot(true, 4096, 64)
        writePng(off.pixels, width, height, 1.6f, File(out, "emitter_off_${off.samples}spp.png"))
        writePng(on.pixels, width, height, 1.6f, File(out, "emitter_on_${on.samples}spp.png"))
        writePng(reference.pixels, width, height, 1.6f, File(out, "emitter_reference_${reference.samples}spp.png"))
        println("PASS emitter captures at ${width}x$height: ${out.absolutePath}/" +
            "emitter_{off,on}_${on.samples}spp.png and emitter_reference_${reference.samples}spp.png")
    }
}
