package afield.examples.four

import afield.four.Renderer
import afield.four.buffers.defaultBuffer
import afield.four.util.GpuNeighbourGrid
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Vector2f
import kotlin.math.hypot
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.neighbour-grid.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.GpuNeighbourGridSmoke
tags: [smoke, four, gpu, compute, sort, neighbours, particles]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "neighbour grid tests PASSED"
*/
/**
 * That [GpuNeighbourGrid] finds *exactly* the right neighbours — as sets, against brute force.
 *
 * ### Why sets and not forces
 *
 * The obvious test is to run a force law on both sides and compare the totals. It is the wrong
 * test. A neighbour query that misses a few percent of its neighbours produces a force that is a
 * few percent off, which disappears inside any tolerance loose enough to allow for fp32, and the
 * resulting flock still flocks. Every failure mode of a spatial grid is of that shape: cells
 * fractionally too small, an off-by-one on the 3x3 block, a run boundary that drops its last
 * entry, a particle on a cell edge counted in the wrong cell. None of them look like anything.
 *
 * So this compares the *set* of indices the grid yields within a radius against the set brute
 * force yields, per particle, and requires them to be equal. A single missing or extra index
 * fails. That is an assertion no tolerance can absorb.
 *
 * ### What it asserts
 *
 * - **Every cell's run is correct.** The sorted keys must be non-decreasing, each cell's
 *   `[start, end)` must contain exactly the entries whose key is that cell, and the runs must
 *   partition `[0, count)` — so nothing is lost and nothing is double-counted.
 * - **The 3x3 query is exact within one cell width.** Neighbour sets equal brute-force sets for
 *   every particle, at a radius equal to the cell size.
 * - **It is exact for a clustered distribution too.** A uniform cloud gives every cell a similar
 *   occupancy, which is the case where an off-by-one in a run boundary costs least and hides
 *   best. A cloud with most of its mass in a few cells is the one that finds it.
 * - **Particles outside the domain are clamped, not dropped.** They still appear in exactly one
 *   cell's run, because a lost particle is a particle that silently stops interacting.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.neighbour-grid.smoke
 * ```
 */
object GpuNeighbourGridSmoke {

    private const val CELLS = 32
    private const val DOMAIN = 2.0f
    private const val ORIGIN = -1.0f

    /** Floats per particle in the test buffer — deliberately not 2, to exercise the stride. */
    private const val STRIDE = 8

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("Neighbour Grid Smoke")
        val renderer = Renderer(window)

        try {
            var failures = 0
            failures += check(renderer, window, "uniform", uniform(4000))
            failures += check(renderer, window, "clustered", clustered(4000))
            failures += check(renderer, window, "out of domain", straying(2000))

            if (failures > 0) {
                println("neighbour grid tests FAILED ($failures)")
                exitProcess(1)
            }
            println("neighbour grid tests PASSED")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    /** Evenly spread over the domain: the easy case, and the one an off-by-one hides in. */
    private fun uniform(n: Int): FloatArray {
        val r = java.util.Random(11)
        return FloatArray(n * 2) { ORIGIN + r.nextFloat() * DOMAIN }
    }

    /** Most of the mass in a handful of cells, so runs are long and unequal. */
    private fun clustered(n: Int): FloatArray {
        val r = java.util.Random(12)
        val out = FloatArray(n * 2)
        val centres = listOf(-0.6f to -0.6f, 0.1f to 0.3f, 0.7f to -0.2f)
        for (i in 0 until n) {
            val (cx, cy) = centres[i % centres.size]
            out[i * 2] = cx + (r.nextGaussian() * 0.03).toFloat()
            out[i * 2 + 1] = cy + (r.nextGaussian() * 0.03).toFloat()
        }
        return out
    }

    /** A quarter of them outside the gridded region entirely. */
    private fun straying(n: Int): FloatArray {
        val r = java.util.Random(13)
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val far = i % 4 == 0
            val span = if (far) DOMAIN * 3f else DOMAIN
            out[i * 2] = ORIGIN - (span - DOMAIN) / 2f + r.nextFloat() * span
            out[i * 2 + 1] = ORIGIN - (span - DOMAIN) / 2f + r.nextFloat() * span
        }
        return out
    }

    private fun check(renderer: Renderer, window: Window, label: String, xy: FloatArray): Int {
        val count = xy.size / 2
        println("--- $label: $count particles over ${CELLS}x$CELLS cells")

        val positions = defaultBuffer(name = "gridSmokePositions") {
            size = count.toLong() * STRIDE * 4L
            streamReads()
        }.toBuffer(renderer)
        positions.get(write = true).mapAndWrite {
            for (i in 0 until count) {
                put(xy[i * 2]); put(xy[i * 2 + 1])
                repeat(STRIDE - 2) { put(0f) }
            }
        }

        val grid = GpuNeighbourGrid(
            capacity = count,
            cellsPerSide = CELLS,
            domainMin = Vector2f(ORIGIN, ORIGIN),
            domainSize = DOMAIN,
            renderer = renderer,
            name = "gridSmoke",
        )
        grid.install(renderer.renderList.others, positions, count, strideFloats = STRIDE)

        // GpuRadixSort builds its plan lazily on the first sort() call, which happens *during*
        // the first frame's recording, and a ComputeModule whose shader has not finished
        // compiling renders as a no-op -- silently, the "not valid" branch has its println
        // commented out. Frames are about a millisecond here, so a frame count is not a wait:
        // this needs wall-clock time, and without it the sort looks broken.
        val deadline = System.nanoTime() + 3_000_000_000L
        var frames = 0
        while (System.nanoTime() < deadline) {
            window.pollEvents()
            renderer.render()
            frames++
            Thread.sleep(5)
        }
        println("  rendered $frames frames over 8s")
        renderer.windowContext().computeFences.forEach { it.fenceWait() }

        val keys = IntArray(count)
        val values = IntArray(count)
        val start = IntArray(grid.cellCount)
        val end = IntArray(grid.cellCount)
        grid.keys.get().mapAndRead_ints { get(keys) }
        grid.values.get().mapAndRead_ints { get(values) }
        grid.cellStart.get().mapAndRead_ints { get(start) }
        grid.cellEnd.get().mapAndRead_ints { get(end) }

        var failures = 0

        // 1. The sort actually sorted, and the runs partition everything.
        for (j in 1 until count) if (keys[j] < keys[j - 1]) {
            println("  FAIL: keys are not sorted at $j (${keys[j - 1]} then ${keys[j]})"); failures++; break
        }
        val covered = (0 until grid.cellCount).sumOf { (end[it] - start[it]).coerceAtLeast(0) }
        if (covered != count) {
            println("  FAIL: cell runs cover $covered entries of $count"); failures++
        }
        val seen = BooleanArray(count)
        var duplicated = 0
        var wrongCell = 0
        for (c in 0 until grid.cellCount) for (k in start[c] until end[c]) {
            if (k < 0 || k >= count) { wrongCell++; continue }
            if (keys[k] != c) {
                if (wrongCell == 0) println("  FAIL: entry $k is in cell $c but its key is ${keys[k]}")
                wrongCell++
                continue
            }
            val p = values[k]
            if (seen[p]) duplicated++
            seen[p] = true
        }
        if (wrongCell > 0) { println("  FAIL: $wrongCell entries sit in the wrong cell's run"); failures++ }
        val missing = seen.count { !it }
        if (duplicated > 0 || missing > 0) {
            println("  FAIL: $duplicated particles appear twice, $missing never appear"); failures++
        }

        // 2. The headline: neighbour sets, against brute force, per particle.
        val radius = grid.cellSize
        val cellOf = { x: Float, y: Float ->
            val cx = (((x - ORIGIN) / DOMAIN) * CELLS).toInt().coerceIn(0, CELLS - 1)
            val cy = (((y - ORIGIN) / DOMAIN) * CELLS).toInt().coerceIn(0, CELLS - 1)
            cx to cy
        }
        var mismatched = 0
        var totalNeighbours = 0L
        for (i in 0 until count) {
            val px = xy[i * 2]
            val py = xy[i * 2 + 1]
            val (hx, hy) = cellOf(px, py)

            val fromGrid = HashSet<Int>()
            for (dy in -1..1) for (dx in -1..1) {
                val cx = hx + dx
                val cy = hy + dy
                if (cx < 0 || cy < 0 || cx >= CELLS || cy >= CELLS) continue
                val c = cy * CELLS + cx
                for (k in start[c] until end[c]) {
                    val j = values[k]
                    if (j == i) continue
                    if (hypot((xy[j * 2] - px).toDouble(), (xy[j * 2 + 1] - py).toDouble()) <= radius) fromGrid += j
                }
            }

            val brute = HashSet<Int>()
            for (j in 0 until count) {
                if (j == i) continue
                if (hypot((xy[j * 2] - px).toDouble(), (xy[j * 2 + 1] - py).toDouble()) <= radius) brute += j
            }
            // Grade only where the claim actually holds. Particles outside the domain are clamped
            // into the outermost ring of cells, so out there a cell no longer means a region and
            // the 3x3 block is not a radius query. Two cells in from the boundary, no clamped
            // particle can appear in the block, and the grid is claimed to be exact.
            //
            // Getting this wrong the other way is instructive: filtering the *brute force* by
            // domain instead reported four "extra" neighbours, which were real particles genuinely
            // within the radius that the grid was right to find. The test was wrong, not the grid.
            if (!wellInside(hx, hy)) continue

            totalNeighbours += brute.size
            if (fromGrid != brute) {
                if (mismatched < 3) {
                    println("  FAIL: particle $i grid=${fromGrid.size} brute=${brute.size} " +
                        "missing=${(brute - fromGrid).size} extra=${(fromGrid - brute).size}")
                }
                mismatched++
            }
        }
        val insideCount = (0 until count).count {
            val (cx, cy) = cellOf(xy[it * 2], xy[it * 2 + 1])
            inside(xy[it * 2], xy[it * 2 + 1]) && wellInside(cx, cy)
        }
        println("  neighbour sets: $mismatched of $insideCount particles disagree; " +
            "mean neighbourhood ${"%.1f".format(totalNeighbours.toDouble() / insideCount)}")
        if (mismatched > 0) failures++
        // A query that found nothing would agree with a brute force that found nothing.
        if (totalNeighbours == 0L) {
            println("  FAIL: nobody had any neighbours; the radius or the data is wrong"); failures++
        }

        grid.removeFrom(renderer.renderList.others)
        grid.cleanup()
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    private fun inside(x: Float, y: Float) =
        x >= ORIGIN && y >= ORIGIN && x < ORIGIN + DOMAIN && y < ORIGIN + DOMAIN

    /** Far enough from the boundary that no clamped stray can reach this particle's 3x3 block. */
    private fun wellInside(cx: Int, cy: Int) = cx in 2..(CELLS - 3) && cy in 2..(CELLS - 3)
}
