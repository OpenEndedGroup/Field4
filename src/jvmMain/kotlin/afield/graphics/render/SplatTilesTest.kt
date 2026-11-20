package afield.graphics.render

import afield.math.linalg.Vector3f
import kotlin.math.abs
import kotlin.math.max

/*
FIELD_TEST
id: four.splat-tiles.smoke
runner: jvm-main
entrypoint: afield.graphics.render.SplatTilesTestKt
tags: [smoke, four, splat, raster, image]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "splat tiles tests PASSED"
*/
/**
 * Tests [SplatTiles] against [SplatRaster.rasterize], the CPU reference for the GPU splat renderer.
 *
 * The tile-binned rasterizer blends front to back, per tile, on several threads, optionally in
 * merged chunks; the reference blends back to front over the whole image on one. The two must
 * agree on every pixel to within one 8-bit code value, under both schedules, at an image size that
 * is not a multiple of the tile, and in buffer order. A negative control reverses the blend order
 * and requires the comparison to fail, so the tolerance cannot be passing everything.
 */
object SplatTilesTest {

    /** Largest per-channel 8-bit difference allowed between binned and reference. */
    private const val MAX_CODE_DELTA = 1

    fun runTests(): Boolean {
        var passed = true
        passed = check("binned matches the reference, naive and balanced, cutoff 0") {
            agreement(250, 190, 6000, 0f)
        } && passed
        passed = check("binned matches the reference with early termination at 1e-4") {
            agreement(320, 240, 20000, 1e-4f)
        } && passed
        passed = check("buffer order matches the reference's buffer order") {
            val (packed, view) = scene(160, 120, 3000)
            val config = SplatRaster.RasterConfig(depthOrder = SplatRaster.DepthOrder.BufferOrder)
            val ref = SplatRaster.rasterize(packed, 3000, view, config).image.toArgb()
            val tiled = SplatTiles.render(SplatTiles.bin(packed, 3000, view, config), threads = 3).image.toArgb()
            val d = maxDelta(ref, tiled)
            require(d <= MAX_CODE_DELTA) { "buffer-order delta $d" }
            "max delta $d"
        } && passed
        passed = check("negative control: the opposite order is rejected") {
            val (packed, view) = scene(160, 120, 3000)
            val ref = SplatRaster.rasterize(packed, 3000, view).image.toArgb()
            val reversed = SplatRaster.RasterConfig(depthOrder = SplatRaster.DepthOrder.BufferOrder)
            val wrong = SplatTiles.render(SplatTiles.bin(packed, 3000, view, reversed)).image.toArgb()
            val d = maxDelta(ref, wrong)
            require(d > 8 * MAX_CODE_DELTA) { "an unsorted blend is only $d codes off, the comparison is blind" }
            "unsorted blend is $d codes off"
        } && passed
        passed = check("tile counts are the splats whose bounds touch each tile") {
            val (packed, view) = scene(200, 150, 4000)
            val b = SplatTiles.bin(packed, 4000, view)
            val recount = IntArray(b.counts.size)
            for (s in 0 until b.drawable) {
                val o = s * 4
                for (ty in b.bounds[o + 2] / SplatTiles.TILE..b.bounds[o + 3] / SplatTiles.TILE)
                    for (tx in b.bounds[o] / SplatTiles.TILE..b.bounds[o + 1] / SplatTiles.TILE)
                        recount[ty * b.tilesX + tx]++
            }
            require(recount.contentEquals(b.counts)) { "counts disagree with a recount" }
            require(b.counts.sum() == b.pairs) { "counts sum ${b.counts.sum()} but ${b.pairs} pairs" }
            // The reference counts a splat whose every sample misses as offscreen; binning cannot know
            // that until it blends, so it bins those too.
            val stats = SplatRaster.rasterize(packed, 4000, view).stats
            require(b.drawable >= stats.drawn && b.drawable <= stats.drawn + stats.culledOffscreen) {
                "binned ${b.drawable} splats, reference drew ${stats.drawn} with ${stats.culledOffscreen} offscreen"
            }
            b.toString()
        } && passed
        // Timing is not asserted here; SplatTilesBench measures it.
        return passed
    }

    private fun agreement(width: Int, height: Int, count: Int, cutoff: Float): String {
        val (packed, view) = scene(width, height, count)
        val ref = SplatRaster.rasterize(packed, count, view).image.toArgb()
        val b = SplatTiles.bin(packed, count, view)
        val notes = mutableListOf<String>()
        for (schedule in listOf(SplatTiles.Schedule.Naive, SplatTiles.Schedule.Balanced(64))) {
            for (threads in listOf(1, 4)) {
                val tiled = SplatTiles.render(b, schedule, threads, cutoff).image.toArgb()
                val d = maxDelta(ref, tiled)
                require(d <= MAX_CODE_DELTA) { "$schedule on $threads threads: max delta $d" }
                notes += "${if (schedule is SplatTiles.Schedule.Naive) "naive" else "balanced"}/$threads=$d"
            }
        }
        require(b.maxCount > 64) { "no tile exceeded the chunk, so chunk merging was not exercised" }
        return "max deltas ${notes.joinToString(" ")}; $b"
    }

    private fun scene(width: Int, height: Int, count: Int) =
        SplatTiles.clusteredCloud(count) to SplatRaster.View.lookAt(
            eye = Vector3f(0f, 0.3f, 2.6f), target = Vector3f(0f, 0f, 0f), width = width, height = height
        )

    private fun maxDelta(a: IntArray, b: IntArray): Int {
        var worst = 0
        for (i in a.indices) for (shift in intArrayOf(24, 16, 8, 0)) {
            worst = max(worst, abs(((a[i] shr shift) and 255) - ((b[i] shr shift) and 255)))
        }
        return worst
    }

    private inline fun check(name: String, body: () -> String): Boolean {
        print("$name... ")
        return try {
            println("OK  ${body()}")
            true
        } catch (e: Throwable) {
            println("FAIL: ${e.message}")
            false
        }
    }
}

fun main() {
    val ok = SplatTilesTest.runTests()
    println(if (ok) "splat tiles tests PASSED" else "splat tiles tests FAILED")
    kotlin.system.exitProcess(if (ok) 0 else 1)
}
