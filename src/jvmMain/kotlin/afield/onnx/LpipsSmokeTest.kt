package afield.onnx

import afield.stdlib.Img
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: onnx.lpips.smoke
runner: jvm-main
entrypoint: afield.onnx.LpipsSmokeTest
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

        if (failures.isNotEmpty()) {
            println("[lpips] ${failures.size} failure(s):")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
        println("ONNX LPIPS smoke tests passed")
    }
}
