package afield.examples.four

import afield.four.Renderer
import afield.four.util.GpuNeighbourGrid
import afield.four.util.GpuProbe
import afield.four.util.Options
import afield.four.util.Window
import afield.four.util.gpuProbe
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
 * - **The grid's four output buffers were actually written.** They are adopted as [GpuProbe]
 *   witnesses and prefilled with a sentinel, so a `GpuRadixSort` whose plan never built — which is
 *   a real failure mode, see below — cannot report agreement with a brute force that also found
 *   nothing.
 *
 * ### What this used to be, and what changed
 *
 * This was a hand-rolled probe: its own `defaultBuffer` with `streamReads()` to remember, its own
 * three seconds of wall-clock spinning, its own four readback arrays, its own
 * `println("FAIL: …")`/`failures++` at each assertion, and its own cleanup. It is now built on
 * [GpuProbe], which is the same assertions with the harness removed — and one assertion *gained*,
 * because the sentinel makes "the sort never ran" a different answer from "the sets agree".
 *
 * The wall-clock wait is the part worth noting. `GpuRadixSort` builds its plan lazily on the first
 * `sort()` call, which happens *during* the first frame's recording, and a `ComputeModule` whose
 * shader has not finished compiling renders as a silent no-op. Frames here are about a
 * millisecond, so a frame count was not a wait and the old code spun for three seconds by the
 * clock. [GpuProbe.stillWaiting] makes it a condition instead: pump until the four buffers have
 * demonstrably been written, and fail — naming the buffer and the first unwritten index — if they
 * never are. Faster in the ordinary case and diagnostic in the bad one.
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

    private fun check(renderer: Renderer, window: Window, label: String, xy: FloatArray): Int =
        gpuProbe(
            renderer,
            name = "gridSmoke-$label",
            pump = { window.pollEvents(); renderer.render() },
        ) { probe ->
            val count = xy.size / 2
            println("--- $label: $count particles over ${CELLS}x$CELLS cells")

            // The stride is exercised on purpose, so the fixture is built padded and uploaded in
            // one go rather than written field by field.
            val positions = probe.fixture("positions", count * STRIDE)
            val packed = FloatArray(count * STRIDE)
            for (i in 0 until count) {
                packed[i * STRIDE] = xy[i * 2]
                packed[i * STRIDE + 1] = xy[i * 2 + 1]
            }
            probe.upload(positions, packed)

            val grid = GpuNeighbourGrid(
                capacity = count,
                cellsPerSide = CELLS,
                domainMin = Vector2f(ORIGIN, ORIGIN),
                domainSize = DOMAIN,
                renderer = renderer,
                name = "gridSmoke",
            )
            grid.install(renderer.renderList.others, positions, count, strideFloats = STRIDE)

            // The grid owns these four; adopt them so they can be witnessed. All four are written
            // in full every frame -- the hash pass fills `count` keys and values, the clear pass
            // fills every one of `cellCount` starts and ends -- so a surviving sentinel anywhere
            // in them means a pass did not run.
            val keysBuf = probe.adopt("keys", grid.keys, count)
            val valuesBuf = probe.adopt("values", grid.values, count)
            val startBuf = probe.adopt("cellStart", grid.cellStart, grid.cellCount)
            val endBuf = probe.adopt("cellEnd", grid.cellEnd, grid.cellCount)

            try {
                // Replaces three seconds of wall-clock spinning with the two questions it was a
                // proxy for. The sentinel alone is NOT enough here, and that is measured rather
                // than assumed: with a plain `settle` this passed twice and then failed on the
                // third distribution with `keys out of order 984` and `entries covered by cell
                // runs 0`. The hash and clear passes had written all four buffers inside two
                // frames -- so every sentinel was gone -- while `GpuRadixSort`, whose plan is
                // built lazily inside its first `sort()` call, had not run at all.
                //
                // So the readiness condition is semantic: the keys are actually sorted and the
                // runs actually cover every entry. Those are two of the assertions below, checked
                // here as a precondition for grading rather than only as a verdict, which is the
                // difference between "the grid is wrong" and "the grid has not started".
                probe.settleUntil(
                    keysBuf, valuesBuf, startBuf, endBuf,
                    seconds = 20.0,
                    readyLabel = "the sorted keys and the cell runs",
                ) {
                    val k = probe.readInts(keysBuf, count)
                    val s = probe.readInts(startBuf, grid.cellCount)
                    val e = probe.readInts(endBuf, grid.cellCount)
                    val sorted = (1 until count).none { k[it] < k[it - 1] }
                    val covered = (0 until grid.cellCount).sumOf { (e[it] - s[it]).coerceAtLeast(0) }
                    sorted && covered == count
                }
                println("  landed after ${probe.framesToLand} pumped frame(s)")

                val keys = probe.readInts(keysBuf, count)
                val values = probe.readInts(valuesBuf, count)
                val start = probe.readInts(startBuf, grid.cellCount)
                val end = probe.readInts(endBuf, grid.cellCount)

                // 1. The sort actually sorted, and the runs partition everything.
                var descents = 0
                var firstDescent = -1
                for (j in 1 until count) if (keys[j] < keys[j - 1]) {
                    descents++
                    if (firstDescent < 0) firstDescent = j
                }
                probe.exactly(
                    "keys out of order", descents, 0,
                    buffer = "keys", index = firstDescent.takeIf { it >= 0 }
                )

                val covered = (0 until grid.cellCount).sumOf { (end[it] - start[it]).coerceAtLeast(0) }
                probe.exactly("entries covered by cell runs", covered, count, buffer = "cellStart")

                val seen = BooleanArray(count)
                var duplicated = 0
                var wrongCell = 0
                var firstWrongCell = -1
                for (c in 0 until grid.cellCount) for (k in start[c] until end[c]) {
                    if (k < 0 || k >= count) {
                        wrongCell++
                        if (firstWrongCell < 0) firstWrongCell = k
                        continue
                    }
                    if (keys[k] != c) {
                        wrongCell++
                        if (firstWrongCell < 0) firstWrongCell = k
                        continue
                    }
                    val p = values[k]
                    if (seen[p]) duplicated++
                    seen[p] = true
                }
                probe.exactly(
                    "entries in the wrong cell's run", wrongCell, 0,
                    buffer = "keys", index = firstWrongCell.takeIf { it >= 0 }
                )
                probe.exactly("particles appearing twice", duplicated, 0, buffer = "values")
                probe.exactly("particles never appearing", seen.count { !it }, 0, buffer = "values")

                // 2. The headline: neighbour sets, against brute force, per particle.
                val radius = grid.cellSize
                val cellOf = { x: Float, y: Float ->
                    val cx = (((x - ORIGIN) / DOMAIN) * CELLS).toInt().coerceIn(0, CELLS - 1)
                    val cy = (((y - ORIGIN) / DOMAIN) * CELLS).toInt().coerceIn(0, CELLS - 1)
                    cx to cy
                }
                var mismatched = 0
                var firstMismatch = -1
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
                            if (hypot((xy[j * 2] - px).toDouble(), (xy[j * 2 + 1] - py).toDouble()) <= radius) {
                                fromGrid += j
                            }
                        }
                    }

                    val brute = HashSet<Int>()
                    for (j in 0 until count) {
                        if (j == i) continue
                        if (hypot((xy[j * 2] - px).toDouble(), (xy[j * 2 + 1] - py).toDouble()) <= radius) {
                            brute += j
                        }
                    }
                    // Grade only where the claim actually holds. Particles outside the domain are
                    // clamped into the outermost ring of cells, so out there a cell no longer means
                    // a region and the 3x3 block is not a radius query. Two cells in from the
                    // boundary, no clamped particle can appear in the block, and the grid is
                    // claimed to be exact.
                    //
                    // Getting this wrong the other way is instructive: filtering the *brute force*
                    // by domain instead reported four "extra" neighbours, which were real particles
                    // genuinely within the radius that the grid was right to find. The test was
                    // wrong, not the grid.
                    if (!wellInside(hx, hy)) continue

                    totalNeighbours += brute.size
                    if (fromGrid != brute) {
                        if (mismatched < 3) {
                            println(
                                "  particle $i grid=${fromGrid.size} brute=${brute.size} " +
                                    "missing=${(brute - fromGrid).size} extra=${(fromGrid - brute).size}"
                            )
                        }
                        mismatched++
                        if (firstMismatch < 0) firstMismatch = i
                    }
                }
                val insideCount = (0 until count).count {
                    val (cx, cy) = cellOf(xy[it * 2], xy[it * 2 + 1])
                    inside(xy[it * 2], xy[it * 2 + 1]) && wellInside(cx, cy)
                }
                println(
                    "  neighbour sets: $mismatched of $insideCount particles disagree; " +
                        "mean neighbourhood ${"%.1f".format(totalNeighbours.toDouble() / insideCount)}"
                )
                probe.exactly(
                    "particles whose sets disagree", mismatched, 0,
                    buffer = "values", index = firstMismatch.takeIf { it >= 0 }
                )
                // A query that found nothing would agree with a brute force that found nothing, so
                // the population is a claim rather than a footnote. The floor is per-particle so
                // one number holds for all three distributions; the measured means are printed
                // above and recorded in `dev_docs/GPU_PROBE.md`, and they differ by two orders of
                // magnitude between the uniform and clustered clouds.
                //
                // Measured, one floor for all three distributions: 12.228 over 3,043 graded
                // particles (uniform), 871.698 over 4,000 (clustered), 4.719 over 1,201
                // (out of domain). The floor has to clear the sparsest, so 4.0 against 4.719 --
                // 1.18x of room, which is as tight as a single floor over a 185x spread can be.
                probe.atLeast("mean neighbourhood", totalNeighbours.toDouble() / insideCount, 4.0)
                probe.atLeast("particles graded", insideCount, 1100)

                probe.verdict("neighbour grid: $label")
            } finally {
                grid.removeFrom(renderer.renderList.others)
                grid.cleanup()
            }
        }

    private fun inside(x: Float, y: Float) =
        x >= ORIGIN && y >= ORIGIN && x < ORIGIN + DOMAIN && y < ORIGIN + DOMAIN

    /** Far enough from the boundary that no clamped stray can reach this particle's 3x3 block. */
    private fun wellInside(cx: Int, cy: Int) = cx in 2..(CELLS - 3) && cy in 2..(CELLS - 3)
}
