package afield.ai.inference.onnx

import afield.media.image.Img
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: onnx.lpips.smoke
runner: jvm-main
entrypoint: afield.ai.inference.onnx.LpipsSmokeTest
tags: [smoke, onnx, ml, cpu, network]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "ONNX LPIPS smoke tests passed"
*/
/**
 * [Lpips], end to end on the CPU, against PyTorch.
 *
 * The reference distances below are what `lpips.LPIPS(net="alex")` in PyTorch returned for the
 * twelve fixed pairs `tools/export_lpips_onnx.py` builds, so a mismatch means Field's tensor layout
 * or normalisation has drifted from the reference implementation, not that a number was guessed.
 * The pairs are built here with the same integer arithmetic and the same float32 operations as the
 * script's NumPy, so both sides feed the network identical tensors.
 *
 * Also asserted: the graph's I/O names; that a picture is at distance 0 from itself; that a batch
 * scored against one broadcast reference matches the same candidates scored one at a time; that
 * the [Img] path and the planar path agree; and that a disc drawn in grey is nearer a yellow disc
 * than a colour ramp is, at every size. Times per evaluation are printed, not asserted.
 *
 * The gradient model ([Lpips.distanceAndGradientPlanar]) is checked here against the forward model
 * itself: its distance must match, and on textured pictures (see [texturedPairs]) its gradient
 * must match a central finite difference of [Lpips.distancesPlanar] on the values where the
 * gradient is largest and along the gradient's own direction. (The export script checks it against PyTorch's autograd; this checks the file Field
 * actually loads, through Field's own tensor layout.)
 *
 * The first run on a machine exports the model (about a minute, through `uv`; see
 * [Lpips.ensureModel]), which is why the `network` tag is real. Later runs are offline.
 */
object LpipsSmokeTest {

    /** PyTorch LPIPS distances, from `lpips_alex.json`'s `agreement` on 2026-10-07. */
    private val REFERENCE: Map<String, Double> = mapOf(
        "disc-vs-grey-disc@32" to 0.1765889972448349,
        "disc-vs-ramp@32" to 0.42004531621932983,
        "stripes-vs-checker@32" to 0.2889566719532013,
        "noise-vs-itself@32" to 0.0,
        "disc-vs-grey-disc@64" to 0.19228437542915344,
        "disc-vs-ramp@64" to 0.6835983991622925,
        "stripes-vs-checker@64" to 0.6121619939804077,
        "noise-vs-itself@64" to 0.0,
        "disc-vs-grey-disc@128" to 0.1907568871974945,
        "disc-vs-ramp@128" to 0.8330514430999756,
        "stripes-vs-checker@128" to 0.6411727666854858,
        "noise-vs-itself@128" to 0.0
    )

    /** ORT's own arithmetic is all that differs; measured agreement was 1.2e-7. */
    private const val TOLERANCE: Double = 1e-5

    private val failures = ArrayList<String>()

    private fun check(condition: Boolean, message: String) {
        if (condition) println("[lpips] ok  $message") else {
            failures += message
            println("[lpips] FAIL $message")
        }
    }

    /** One fixed pair, as the export script builds it. */
    private class Pair(val name: String, val a: Img, val b: Img)

    private fun pairs(size: Int): List<Pair> {
        val n = size * size
        fun field(f: (x: Int, y: Int) -> Float) = FloatArray(n) { f(it % size, it / size) }
        val r2 = (size * 9 / 32) * (size * 9 / 32)
        val disc = field { x, y -> if ((x - size / 2) * (x - size / 2) + (y - size / 2) * (y - size / 2) < r2) 1f else 0f }
        val ramp = field { x, _ -> (x * 255 / maxOf(1, size - 1)).toFloat() / 255f }
        val stripes = field { x, _ -> ((x * 6 / size) % 2).toFloat() }
        val checker = field { x, y -> (((x * 4 / size) + (y * 4 / size)) % 2).toFloat() }
        fun noiseAt(x: Int, y: Int) = ((x * 73 + y * 151 + x * y * 7) % 256).toFloat() / 255f
        fun rgb(r: FloatArray, g: FloatArray, b: FloatArray): Img {
            val data = FloatArray(n * 3)
            for (p in 0 until n) { data[p * 3] = r[p]; data[p * 3 + 1] = g[p]; data[p * 3 + 2] = b[p] }
            return Img(size, size, 3, data)
        }
        fun map(src: FloatArray, f: (Float) -> Float) = FloatArray(n) { f(src[it]) }
        val yellowDisc = rgb(map(disc) { 0.03f + 0.97f * it }, map(disc) { 0.04f + 0.78f * it }, map(disc) { 0.12f + 0.13f * it })
        // noise[::-1] reverses rows; noise.T transposes.
        val noise = rgb(field { x, y -> noiseAt(x, y) }, field { x, y -> noiseAt(x, size - 1 - y) }, field { x, y -> noiseAt(y, x) })
        return listOf(
            Pair("disc-vs-grey-disc@$size", yellowDisc, rgb(disc, disc, disc)),
            Pair("disc-vs-ramp@$size", yellowDisc, rgb(ramp, map(ramp) { it * 0.5f }, map(ramp) { 1f - it })),
            Pair("stripes-vs-checker@$size", rgb(stripes, stripes, map(stripes) { 1f - it }), rgb(checker, map(checker) { 1f - it }, checker)),
            Pair("noise-vs-itself@$size", noise, noise)
        )
    }

    private fun planarOf(img: Img): FloatArray {
        val plane = img.width * img.height
        return FloatArray(3 * plane) { i -> img.data[(i % plane) * 3 + i / plane] }
    }

    /** Central-difference tolerance: relative to the larger of the two, on the values where the gradient is largest and along it. */
    private const val FD_TOLERANCE: Double = 0.02

    /**
     * The central difference's step. AlexNet is piecewise linear with a kink at every ReLU and
     * every max-pool switch, so a step of 0.01 crosses kinks: measured in float64 PyTorch on
     * `smooth-vs-disc@64`, 0.01 gave a slope 13% off autograd at (x 19, y 41) where 1e-3 to 1e-6
     * agreed to 1e-9. Smaller than 1e-3 and the float32 distance's rounding dominates.
     */
    private const val FD_STEP: Double = 1e-3

    /**
     * Textured candidates for the finite-difference check: no two pixels alike, so no exact ties
     * in AlexNet's max pools. On the flat-coloured [pairs] a one-pixel nudge breaks a tie, and a
     * central difference then averages two one-sided slopes that the (valid) subgradient need not
     * equal; measured on 2026-10-07, `stripes-vs-checker@32` differed by up to 50% that way. That
     * is the function's kink, not the gradient's error, and the export script checks those pairs
     * against autograd instead.
     */
    private fun texturedPairs(size: Int): List<Pair> {
        val n = size * size
        fun field(f: (x: Int, y: Int) -> Float) = FloatArray(n) { f(it % size, it / size) }
        fun rgb(r: FloatArray, g: FloatArray, b: FloatArray): Img {
            val data = FloatArray(n * 3)
            for (p in 0 until n) { data[p * 3] = r[p]; data[p * 3 + 1] = g[p]; data[p * 3 + 2] = b[p] }
            return Img(size, size, 3, data)
        }
        val base = pairs(size)
        val noise = base[3].a
        val smooth = rgb(
            field { x, y -> 0.5f + 0.4f * kotlin.math.sin(0.37f * x + 0.21f * y) },
            field { x, y -> 0.5f + 0.35f * kotlin.math.cos(0.19f * x - 0.43f * y) },
            field { x, y -> 0.45f + 0.3f * kotlin.math.sin(0.11f * x * y / size + 0.3f * x) }
        )
        return listOf(
            Pair("noise-vs-disc@$size", noise, base[0].a),
            Pair("noise-vs-checker@$size", noise, base[2].b),
            Pair("smooth-vs-disc@$size", smooth, base[0].a),
            Pair("smooth-vs-ramp@$size", smooth, base[1].b)
        )
    }

    private fun gradient() {
        val started = System.nanoTime()
        Lpips.ensureGradModel()
        println("[lpips] gradient model ready in %.1f s".format((System.nanoTime() - started) / 1e9))
        val grad = Lpips.gradSession
        check(
            grad.inputNames == listOf("candidate", "reference") && grad.outputNames == listOf("distance", "gradient"),
            "gradient graph signature is (candidate, reference) -> (distance, gradient): ${grad.inputNames} -> ${grad.outputNames}"
        )
        // Its distance is the forward model's, on every fixed pair.
        var worstDistance = 0.0
        for (size in listOf(32, 64, 128)) for (p in pairs(size)) {
            val a = planarOf(p.a)
            val b = planarOf(p.b)
            val d = Lpips.distanceAndGradientPlanar(a, size, size, b).first
            worstDistance = maxOf(worstDistance, abs(d - Lpips.distancesPlanar(a, 1, size, size, b)[0]))
        }
        check(worstDistance <= 1e-6, "the gradient model's distance matches the forward model's on ${REFERENCE.size} pairs (max diff %.1e)".format(worstDistance))

        var worstPixel = 0.0
        var worstDirection = 0.0
        var values = 0
        var kinks = 0
        for (size in listOf(32, 64)) for (p in texturedPairs(size)) {
            val a = planarOf(p.a)
            val b = planarOf(p.b)
            val g = Lpips.distanceAndGradientPlanar(a, size, size, b).second
            fun at(x: FloatArray) = Lpips.distancesPlanar(x, 1, size, size, b)[0]
            // The sixteen values with the largest |gradient|, less any whose +-h step crosses a kink:
            // AlexNet is piecewise linear in a pixel, so on one linear piece the two one-sided
            // slopes agree, and where they do not, a central difference measures neither side.
            val h = FD_STEP.toFloat()
            val centre = at(a)
            var checked = 0
            // The smooth picture at 64 has a kink within +-1e-3 of most of its largest-gradient
            // values, some with one-sided slopes that happen to agree: in float64 PyTorch at (ch 1,
            // x 23, y 21) a step of 1e-3 gives 3.714e-3 where 1e-4 to 1e-6 and autograd give 3.798e-3
            // (and this gradient, 3.798e-3). It gets the directional check only.
            val singles = if (p.name.startsWith("smooth") && size == 64) emptyList() else g.indices.sortedByDescending { abs(g[it]) }.take(16)
            for (i in singles) {
                val plus = at(a.copyOf().also { it[i] += h })
                val minus = at(a.copyOf().also { it[i] -= h })
                val right = (plus - centre) / h
                val left = (centre - minus) / h
                if (abs(right - left) > FD_TOLERANCE * maxOf(abs(right), abs(left))) {
                    kinks++
                    continue
                }
                val fd = (plus - minus) / (2.0 * h)
                val rel = abs(fd - g[i]) / maxOf(abs(fd), abs(g[i].toDouble()))
                worstPixel = maxOf(worstPixel, rel)
                checked++
                check(rel <= FD_TOLERANCE, "${p.name} value $i (ch ${i / (size * size)}, x ${i % size}, y ${(i / size) % size}): gradient %.4e, central difference %.4e (rel %.1e)".format(g[i], fd, rel))
            }
            if (singles.isNotEmpty()) check(checked >= 4, "${p.name}: $checked of the 16 largest-gradient values lie on one linear piece within +-$h (need 4)")
            values += checked
            // Along the gradient's own direction: d(t) = d(a + t g / |g|), so d'(0) should be |g|.
            val norm = kotlin.math.sqrt(g.sumOf { it.toDouble() * it })
            val t = FD_STEP
            val plus = FloatArray(a.size) { a[it] + (t * g[it] / norm).toFloat() }
            val minus = FloatArray(a.size) { a[it] - (t * g[it] / norm).toFloat() }
            val fd = (at(plus) - at(minus)) / (2 * t)
            val rel = abs(fd - norm) / maxOf(abs(fd), norm)
            worstDirection = maxOf(worstDirection, rel)
            check(rel <= FD_TOLERANCE, "${p.name}: |gradient| %.4e against the central difference along it %.4e (rel %.1e)".format(norm, fd, rel))
        }
        println(
            "[lpips] gradient vs central difference (h = %.0e): worst relative %.2e on $values single values ($kinks skipped as straddling a kink), %.2e along the gradient on 8 pairs"
                .format(FD_STEP, worstPixel, worstDirection)
        )

        for (size in listOf(32, 64, 128)) {
            val p = pairs(size)[1]
            val a = planarOf(p.a)
            val b = planarOf(p.b)
            repeat(3) { Lpips.distanceAndGradientPlanar(a, size, size, b) }
            val t0 = System.nanoTime()
            repeat(10) { Lpips.distanceAndGradientPlanar(a, size, size, b) }
            println("[lpips] gradient cost @${size}x$size: %.2f ms for one pair".format((System.nanoTime() - t0) / 1e6 / 10))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val started = System.nanoTime()
        val path = Lpips.ensureModel()
        println("[lpips] model $path (${java.nio.file.Files.size(path)} bytes), ready in %.1f s".format((System.nanoTime() - started) / 1e9))
        val session = Lpips.session
        check(
            session.inputNames == listOf("candidate", "reference") && session.outputNames == listOf("distance"),
            "graph signature is (candidate, reference) -> distance: ${session.inputNames} -> ${session.outputNames}"
        )

        var worst = 0.0
        for (size in listOf(32, 64, 128)) {
            val ps = pairs(size)
            for (p in ps) {
                val got = Lpips.distance(p.a, p.b)
                val want = REFERENCE.getValue(p.name)
                worst = maxOf(worst, abs(got - want))
                check(abs(got - want) <= TOLERANCE, "${p.name}: %.7f against PyTorch %.7f".format(got, want))
            }
            val byName = ps.associateBy { it.name }
            val same = Lpips.distance(byName.getValue("disc-vs-grey-disc@$size").a, byName.getValue("disc-vs-grey-disc@$size").b)
            val different = Lpips.distance(byName.getValue("disc-vs-ramp@$size").a, byName.getValue("disc-vs-ramp@$size").b)
            check(same < different, "@$size: a grey disc (%.3f) is nearer the yellow disc than a ramp is (%.3f)".format(same, different))
            check(Lpips.distance(ps[0].a, ps[0].a) == 0.0, "@$size: a picture is at distance 0 from itself")

            val reference = ps[0].a
            val candidates = ps.map { it.b }
            val batched = Lpips.distances(candidates, reference)
            val singles = candidates.map { Lpips.distance(it, reference) }
            val batchErr = batched.indices.maxOf { abs(batched[it] - singles[it]) }
            check(batchErr <= 1e-6, "@$size: ${candidates.size} candidates against one broadcast reference match one-at-a-time scoring (max diff %.1e)".format(batchErr))
        }
        println("[lpips] max |field - pytorch| = %.2e over ${REFERENCE.size} pairs".format(worst))

        // Cost, printed: per evaluation, alone and in a batch of 16 against one reference.
        for (size in listOf(32, 64, 128)) {
            val p = pairs(size)
            val batch = List(16) { p[it % p.size].b }
            repeat(3) { Lpips.distances(batch, p[0].a) }
            val t0 = System.nanoTime()
            repeat(20) { Lpips.distance(p[1].a, p[1].b) }
            val single = (System.nanoTime() - t0) / 1e6 / 20
            val t1 = System.nanoTime()
            repeat(5) { Lpips.distances(batch, p[0].a) }
            val perInBatch = (System.nanoTime() - t1) / 1e6 / (5 * 16)
            println("[lpips] cost @${size}x$size: %.2f ms for one pair, %.2f ms per candidate in a batch of 16".format(single, perInBatch))
        }

        gradient()

        if (failures.isNotEmpty()) {
            println("[lpips] ${failures.size} failure(s):")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
        println("ONNX LPIPS smoke tests passed")
    }
}
