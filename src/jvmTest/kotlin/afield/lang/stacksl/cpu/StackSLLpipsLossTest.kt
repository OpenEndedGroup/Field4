package afield.lang.stacksl.cpu

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plumbing [StackSLLpipsLoss] puts between the LPIPS gradient and the program, checked without
 * the network: the vector-seeded backward pass, the `k x k` block sum that undoes the enlargement,
 * and the region cut. (The network's own gradient is checked against PyTorch's autograd by
 * `tools/export_lpips_onnx.py` and against finite differences by the `onnx.lpips.smoke` test.)
 */
class StackSLLpipsLossTest {

    private val programs = listOf(
        "uv 3.0 * sin 0.5 + palette",
        "uv 2.0 * sin 0.25 * 0.5 + saturate",
        "p 1.7 twist 2.0 * sin palette saturate",
        "[1,0.82,0.25] [0.03,0.04,0.12] p length 0.28 threshold mix"
    )

    private fun target(dim: Int): CpuRaster = CpuRaster(dim, dim, FloatArray(dim * dim * 4) { i ->
        val p = i / 4
        val x = (p % dim).toFloat() / dim
        val y = (p / dim).toFloat() / dim
        when (i % 4) {
            0 -> 0.5f * x + 0.25f
            1 -> 0.5f * y + 0.2f
            2 -> 0.4f * (1f - x) + 0.3f
            else -> 1f
        }
    })

    /**
     * Seeded with the squared error's own image gradient `2 (out - target) / (3 dim^2)`, the seeded
     * pass must reproduce [StackSLImageLoss.valueAndGradient]'s gradient: the same chain rule,
     * entered from the image instead of from the loss. With and without a step surrogate.
     */
    @Test
    fun seededWithTheSquaredErrorGradientIsTheMseGradient() {
        val dim = 12
        var compared = 0
        for (program in programs) for (surrogate in listOf(AdSurrogate.OFF, AdAnnealing.stepOnly(10).next())) {
            val wired = StackSLWires.lift(program)
            val loss = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), dim, target(dim))
            val values = wired.initial
            val mse = loss.valueAndGradient(values, surrogate).gradient
            val seed = StackSLLpipsLoss.mseSeed(loss.render(values), loss.target)
            val seeded = loss.seededGradient(values, seed, surrogate)
            val scale = max(1e-12, mse.maxOf { abs(it) })
            for (k in mse.indices) {
                // The seed is rounded to Float; everything else is the same arithmetic.
                assertTrue(abs(mse[k] - seeded[k]) <= 1e-6 * scale, "$program wire $k: mse ${mse[k]} vs seeded ${seeded[k]}")
                compared++
            }
        }
        assertTrue(compared >= 20, "compared only $compared wires")
    }

    /** A zero seed moves nothing, and the seeded pass is linear in its seed. */
    @Test
    fun seededGradientIsLinearInTheSeed() {
        val dim = 10
        val wired = StackSLWires.lift(programs[0])
        val loss = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), dim, target(dim))
        val rng = Random(3)
        val a = FloatArray(3 * dim * dim) { rng.nextFloat() - 0.5f }
        val b = FloatArray(3 * dim * dim) { rng.nextFloat() - 0.5f }
        val ga = loss.seededGradient(wired.initial, a)
        val gb = loss.seededGradient(wired.initial, b)
        val gab = loss.seededGradient(wired.initial, FloatArray(a.size) { 2f * a[it] + b[it] })
        assertTrue(loss.seededGradient(wired.initial, FloatArray(a.size)).all { it == 0.0 })
        for (k in ga.indices) assertTrue(abs(gab[k] - (2 * ga[k] + gb[k])) <= 1e-6 * max(1.0, abs(gab[k])), "wire $k")
    }

    /**
     * [StackSLLpipsLoss.blockSum] is the adjoint of [StackSLLpipsLoss.enlarge]: for any render `x`
     * and any gradient `g` on the enlarged image, `<enlarge(x), g> = <x, blockSum(g)>`. And on a
     * hand-built case, each render pixel gets exactly the sum of its `k x k` block.
     */
    @Test
    fun blockSumIsTheAdjointOfTheEnlargement() {
        val rng = Random(11)
        for ((dim, side) in listOf(4 to 8, 8 to 64, 16 to 48, 32 to 96, 5 to 5)) {
            val x = CpuRaster(dim, dim, FloatArray(dim * dim * 4) { rng.nextFloat() })
            val g = FloatArray(3 * side * side) { rng.nextFloat() * 2f - 1f }
            val big = StackSLLpipsLoss.enlarge(x, side)
            val summed = StackSLLpipsLoss.blockSum(g, side, dim)
            var lhs = 0.0
            for (i in big.indices) lhs += big[i].toDouble() * g[i]
            var rhs = 0.0
            val plane = dim * dim
            for (ch in 0 until 3) for (p in 0 until plane) rhs += x.rgba[p * 4 + ch].toDouble() * summed[ch * plane + p]
            assertTrue(abs(lhs - rhs) <= 1e-4 * max(1.0, abs(lhs)), "dim $dim side $side: $lhs vs $rhs")
        }
        // 2x2 into 1x1, k = 2: the one pixel of each channel gets its four values' sum.
        val g = floatArrayOf(1f, 2f, 3f, 4f, 10f, 20f, 30f, 40f, -1f, -1f, -1f, 0.5f)
        val s = StackSLLpipsLoss.blockSum(g, 2, 1)
        assertEquals(listOf(10f, 100f, -2.5f), s.toList())
        // 4 into 2, k = 2, one channel's layout: block (1, 0) is x 2..3, y 0..1.
        val g4 = FloatArray(3 * 16).also { for (y in 0 until 2) for (x in 2 until 4) it[y * 4 + x] = 1f }
        val s4 = StackSLLpipsLoss.blockSum(g4, 4, 2)
        assertEquals(listOf(0f, 4f, 0f, 0f), s4.toList().subList(0, 4))
    }

    /** Cutting into regions and putting them back is the identity, at every grid the search uses. */
    @Test
    fun regionsRoundTrip() {
        val rng = Random(5)
        for ((side, grid) in listOf(64 to 2, 96 to 3, 128 to 4)) {
            val planar = FloatArray(3 * side * side) { rng.nextFloat() }
            val back = StackSLLpipsLoss.unregions(StackSLLpipsLoss.regions(planar, side, grid), side, grid)
            assertTrue(planar.contentEquals(back), "side $side grid $grid")
        }
    }
}
