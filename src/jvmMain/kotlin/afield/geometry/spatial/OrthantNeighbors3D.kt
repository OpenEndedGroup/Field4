package afield.geometry.spatial

import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.sqrt

/** One Euclidean nearest neighbour in each sign octant of a packed XYZ point cloud. */
object OrthantNeighbors3D {
    /** The bits mark negative X, Y and Z respectively. Zero is assigned to the positive side. */
    fun octant(dx: Double, dy: Double, dz: Double): Int =
        (if (dx < 0) 1 else 0) or (if (dy < 0) 2 else 0) or (if (dz < 0) 4 else 0)

    /** A directed network with eight slots per point, indexed by `point * 8 + octant`. */
    class Result internal constructor(
        /** Point indices, with -1 for a slot in which the search found no point. */
        val neighbors: IntArray,
        /** Euclidean distance in each slot, with zero in missing slots. */
        val distances: DoubleArray,
        /** Width of one grid cell in input units. */
        val cellSize: Double,
        /** Number of points for which the ring cap left cells that could improve a slot. */
        val ringLimited: Int,
    ) {
        /** Number of points in the input cloud. */
        val count: Int get() = neighbors.size / 8
    }

    /**
     * Search grid rings in increasing Chebyshev distance, up to [maxRings] rings beyond the
     * starting cell. Results can be approximate when the cap is reached. Set [maxRings] to
     * [Int.MAX_VALUE] for an exhaustive search wherever the distance bound cannot prove a result.
     * A zero [cellsPerAxisHint] derives the grid resolution from point count. Equal distances
     * prefer the lower point index, including when [parallel] evaluates queries concurrently.
     *
     * This yields directed neighbour slots. Use the graph connectivity functions for symmetric
     * point graphs and custom distance metrics.
     */
    fun build(
        xyz: FloatArray,
        cellsPerAxisHint: Int = 0,
        maxRings: Int = 6,
        parallel: Boolean = true,
    ): Result = build(xyz, cellsPerAxisHint, maxRings, parallel, excludeAxisAligned = false)

    // The original PointGraph API treats octants as open and has no ring cap.
    internal fun build(
        xyz: FloatArray,
        cellsPerAxisHint: Int,
        maxRings: Int,
        parallel: Boolean,
        excludeAxisAligned: Boolean,
    ): Result {
        require(xyz.size % 3 == 0 && xyz.all { it.isFinite() }) { "Expected finite packed XYZ triples" }
        require(cellsPerAxisHint >= 0 && maxRings >= 0)
        val count = xyz.size / 3
        if (count == 0) return Result(IntArray(0), DoubleArray(0), 0.0, 0)
        val slots = Math.multiplyExact(count, 8)
        val grid = Grid(xyz, cellsPerAxisHint.takeIf { it > 0 } ?: cbrt(count.toDouble()).toInt().coerceAtLeast(1))
        val neighbors = IntArray(slots) { -1 }
        val distances = DoubleArray(slots)
        if (excludeAxisAligned && grid.flat) return Result(neighbors, distances, grid.cellSize, 0)
        val limited = AtomicInteger()

        fun search(i: Int) {
            val offset = i * 3
            val px = xyz[offset].toDouble()
            val py = xyz[offset + 1].toDouble()
            val pz = xyz[offset + 2].toDouble()
            val x = grid.cellOf(px, 0)
            val y = grid.cellOf(py, 1)
            val z = grid.cellOf(pz, 2)
            val lastRing = grid.lastRing(x, y, z)
            val best = DoubleArray(8) { Double.POSITIVE_INFINITY }
            var proven = false
            for (ring in 0..minOf(lastRing, maxRings)) {
                grid.forEachInRing(x, y, z, ring) { j ->
                    if (j != i) {
                        val dx = xyz[j * 3] - px
                        val dy = xyz[j * 3 + 1] - py
                        val dz = xyz[j * 3 + 2] - pz
                        if (excludeAxisAligned && (dx == 0.0 || dy == 0.0 || dz == 0.0)) return@forEachInRing
                        val distance = sqrt(dx * dx + dy * dy + dz * dz)
                        val octant = octant(dx, dy, dz)
                        val slot = i * 8 + octant
                        if (distance < best[octant] || (distance == best[octant] && j < neighbors[slot])) {
                            best[octant] = distance
                            neighbors[slot] = j
                        }
                    }
                }
                // Keep equal-distance ties eligible, including at rounded cell boundaries.
                val lowerBound = ring * grid.cellSize * (1.0 - 1e-12)
                if (best.all { it < lowerBound }) {
                    proven = true
                    break
                }
            }
            if (!proven && maxRings < lastRing) limited.incrementAndGet()
            for (octant in best.indices) {
                val slot = i * 8 + octant
                if (neighbors[slot] >= 0) distances[slot] = best[octant]
            }
        }

        val indices = IntStream.range(0, count)
        (if (parallel) indices.parallel() else indices).forEach { search(it) }
        return Result(neighbors, distances, grid.cellSize, limited.get())
    }

    private class Grid(xyz: FloatArray, cellsAlongLongestAxis: Int) {
        private val min = DoubleArray(3) { Double.POSITIVE_INFINITY }
        private val size = IntArray(3)
        val cellSize: Double
        val flat: Boolean
        private val starts: IntArray
        private val points: IntArray

        init {
            val max = DoubleArray(3) { Double.NEGATIVE_INFINITY }
            val count = xyz.size / 3
            for (i in xyz.indices) {
                val axis = i % 3
                val p = xyz[i].toDouble()
                min[axis] = minOf(min[axis], p)
                max[axis] = maxOf(max[axis], p)
            }
            val extent = DoubleArray(3) { max[it] - min[it] }
            flat = extent.any { it == 0.0 }
            val longest = extent.max()
            cellSize = if (longest == 0.0) 1.0 else longest / cellsAlongLongestAxis
            var cellCount = 1L
            for (axis in size.indices) {
                val dim = floor(extent[axis] / cellSize).toLong() + 1
                require(dim in 1 until Int.MAX_VALUE.toLong() && cellCount <= (Int.MAX_VALUE - 1L) / dim) {
                    "Orthant search grid is too large. Reduce cellsPerAxisHint."
                }
                size[axis] = dim.toInt()
                cellCount *= dim
            }
            starts = IntArray(cellCount.toInt() + 1)
            val pointCells = IntArray(count) { i ->
                index(cellOf(xyz[i * 3].toDouble(), 0), cellOf(xyz[i * 3 + 1].toDouble(), 1), cellOf(xyz[i * 3 + 2].toDouble(), 2))
            }
            for (cell in pointCells) starts[cell + 1]++
            for (i in 1 until starts.size) starts[i] += starts[i - 1]
            val cursor = starts.copyOf()
            points = IntArray(count)
            for (i in pointCells.indices) points[cursor[pointCells[i]]++] = i
        }

        fun cellOf(p: Double, axis: Int): Int =
            ((p - min[axis]) / cellSize).toInt().coerceIn(0, size[axis] - 1)

        private fun index(x: Int, y: Int, z: Int) = (z * size[1] + y) * size[0] + x

        fun lastRing(x: Int, y: Int, z: Int): Int =
            maxOf(x, size[0] - 1 - x, y, size[1] - 1 - y, z, size[2] - 1 - z)

        inline fun forEachInRing(x: Int, y: Int, z: Int, ring: Int, visit: (Int) -> Unit) {
            val xStart = maxOf(0, x - ring)
            val xEnd = x + minOf(ring, size[0] - 1 - x)
            val left = x - ring >= 0
            val right = ring <= size[0] - 1 - x
            val yEnd = y + minOf(ring, size[1] - 1 - y)
            val zEnd = z + minOf(ring, size[2] - 1 - z)
            for (cz in maxOf(0, z - ring)..zEnd) {
                for (cy in maxOf(0, y - ring)..yEnd) {
                    if (kotlin.math.abs(cz - z) == ring || kotlin.math.abs(cy - y) == ring) {
                        for (cx in xStart..xEnd) forEachInCell(cx, cy, cz, visit)
                    } else {
                        if (left) forEachInCell(xStart, cy, cz, visit)
                        if (right) forEachInCell(xEnd, cy, cz, visit)
                    }
                }
            }
        }

        private inline fun forEachInCell(x: Int, y: Int, z: Int, visit: (Int) -> Unit) {
            val cell = index(x, y, z)
            for (i in starts[cell] until starts[cell + 1]) visit(points[i])
        }
    }
}
