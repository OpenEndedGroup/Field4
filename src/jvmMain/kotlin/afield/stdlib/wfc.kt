@file:afield.CompletionCategory("Data")

package afield.stdlib
import afield.stdlib.WfcNeighborhood.Companion.VonNeumann
import java.awt.image.BufferedImage
import java.io.File
import java.util.*
import javax.imageio.ImageIO
import kotlin.math.ln

/**
 * Single-file, dependency-free Wave Function Collapse for Kotlin/JVM.
 *
 * What this implements:
 * - Learns unique fixed-size tiles from a source PNG or 2D FloatArray.
 * - Learns adjacency constraints from a configurable symmetric neighborhood.
 * - Learns overlapping N x M patterns from a PNG or 2D FloatArray.
 * - Generates an output tile grid with an explicit step() loop for visualization.
 * - Avoids per-neighbor/per-tile garbage in propagation by using reusable LongArray bitsets.
 *
 * Notes:
 * - The WFC solver operates only on integer tile IDs and constraints.
 * - PNG/FloatArray simple-tiled and overlapping helpers are intentionally separate from the core.
 */

const val WFC_NORTH: Int = 0
const val WFC_EAST: Int = 1
const val WFC_SOUTH: Int = 2
const val WFC_WEST: Int = 3

data class WfcOffset(val dx: Int, val dy: Int)

/**
 * Symmetric set of relative cell offsets used for learning and propagation.
 *
 * The first four directions of [VonNeumann] retain the historical WFC_NORTH/EAST/SOUTH/WEST
 * ordering for old code that indexes directional constraints directly.
 */
class WfcNeighborhood(offsets: Iterable<WfcOffset>) {
    private val offsetData: Array<WfcOffset> = offsets.toList().toTypedArray()
    private val oppositeData: IntArray
    val directionCount: Int get() = offsetData.size
    val offsets: List<WfcOffset> get() = offsetData.asList()

    init {
        require(offsetData.isNotEmpty()) { "A WFC neighborhood needs at least one offset" }

        val byOffset = HashMap<WfcOffset, Int>()
        var i = 0
        while (i < offsetData.size) {
            val offset = offsetData[i]
            require(offset.dx != 0 || offset.dy != 0) { "A WFC neighborhood cannot include the zero offset" }
            require(byOffset.put(offset, i) == null) { "Duplicate WFC neighborhood offset $offset" }
            i++
        }

        oppositeData = IntArray(offsetData.size)
        i = 0
        while (i < offsetData.size) {
            val offset = offsetData[i]
            oppositeData[i] = byOffset[WfcOffset(-offset.dx, -offset.dy)]
                ?: error("WFC neighborhood offset $offset is missing its opposite")
            i++
        }
    }

    fun offset(direction: Int): WfcOffset {
        require(direction in 0 until directionCount)
        return offsetData[direction]
    }

    fun opposite(direction: Int): Int {
        require(direction in 0 until directionCount)
        return oppositeData[direction]
    }

    fun directionOf(dx: Int, dy: Int): Int {
        var i = 0
        while (i < offsetData.size) {
            val offset = offsetData[i]
            if (offset.dx == dx && offset.dy == dy) return i
            i++
        }
        return -1
    }

    override fun toString(): String = "WfcNeighborhood(${offsetData.joinToString()})"

    companion object {
        val VonNeumann: WfcNeighborhood = WfcNeighborhood(
            listOf(
                WfcOffset(0, -1),
                WfcOffset(1, 0),
                WfcOffset(0, 1),
                WfcOffset(-1, 0)
            )
        )

        val Moore: WfcNeighborhood = WfcNeighborhood(
            listOf(
                WfcOffset(0, -1),
                WfcOffset(1, 0),
                WfcOffset(0, 1),
                WfcOffset(-1, 0),
                WfcOffset(1, -1),
                WfcOffset(1, 1),
                WfcOffset(-1, 1),
                WfcOffset(-1, -1)
            )
        )

        fun of(vararg offsets: WfcOffset): WfcNeighborhood = WfcNeighborhood(offsets.asList())

        fun chebyshev(radius: Int): WfcNeighborhood {
            require(radius > 0)
            val offsets = ArrayList<WfcOffset>()
            var dy = -radius
            while (dy <= radius) {
                var dx = -radius
                while (dx <= radius) {
                    if (dx != 0 || dy != 0) offsets.add(WfcOffset(dx, dy))
                    dx++
                }
                dy++
            }
            return WfcNeighborhood(offsets)
        }

        fun manhattan(radius: Int): WfcNeighborhood {
            require(radius > 0)
            val offsets = ArrayList<WfcOffset>()
            var dy = -radius
            while (dy <= radius) {
                var dx = -radius
                while (dx <= radius) {
                    if ((dx != 0 || dy != 0) && kotlin.math.abs(dx) + kotlin.math.abs(dy) <= radius) {
                        offsets.add(WfcOffset(dx, dy))
                    }
                    dx++
                }
                dy++
            }
            return WfcNeighborhood(offsets)
        }
    }
}

typealias WfcNeighbourhood = WfcNeighborhood

val WFC_VON_NEUMANN_NEIGHBORHOOD: WfcNeighborhood = WfcNeighborhood.VonNeumann
val WFC_MOORE_NEIGHBORHOOD: WfcNeighborhood = WfcNeighborhood.Moore
val WFC_VON_NEUMANN_NEIGHBOURHOOD: WfcNeighbourhood = WfcNeighborhood.VonNeumann
val WFC_MOORE_NEIGHBOURHOOD: WfcNeighbourhood = WfcNeighborhood.Moore

private fun wordsFor(bits: Int): Int = (bits + 63) ushr 6
private fun wordIndex(bit: Int): Int = bit ushr 6
private fun bitMask(bit: Int): Long = 1L shl (bit and 63)

private fun lastWordMask(bitCount: Int): Long {
    val rem = bitCount and 63
    return if (rem == 0) -1L else (1L shl rem) - 1L
}

private fun LongArray.fillAllBits(bitCount: Int) {
    java.util.Arrays.fill(this, -1L)
    this[this.lastIndex] = this[this.lastIndex] and lastWordMask(bitCount)
}

private fun LongArray.clearAll() {
    java.util.Arrays.fill(this, 0L)
}

private fun LongArray.copyFrom(src: LongArray) {
    System.arraycopy(src, 0, this, 0, size)
}

private fun LongArray.andInPlace(other: LongArray): Boolean {
    var changed = false
    var i = 0
    while (i < size) {
        val old = this[i]
        val next = old and other[i]
        if (next != old) {
            this[i] = next
            changed = true
        }
        i++
    }
    return changed
}

private fun LongArray.orInPlace(other: LongArray) {
    var i = 0
    while (i < size) {
        this[i] = this[i] or other[i]
        i++
    }
}

private fun LongArray.containsBit(bit: Int): Boolean = (this[wordIndex(bit)] and bitMask(bit)) != 0L

private fun LongArray.setBit(bit: Int) {
    this[wordIndex(bit)] = this[wordIndex(bit)] or bitMask(bit)
}

private fun LongArray.keepOnlyBit(bit: Int) {
    java.util.Arrays.fill(this, 0L)
    this[wordIndex(bit)] = bitMask(bit)
}

private fun LongArray.countBits(): Int {
    var c = 0
    var i = 0
    while (i < size) {
        c += java.lang.Long.bitCount(this[i])
        i++
    }
    return c
}

private fun LongArray.firstSetBit(): Int {
    var i = 0
    while (i < size) {
        val w = this[i]
        if (w != 0L) return (i shl 6) + java.lang.Long.numberOfTrailingZeros(w)
        i++
    }
    return -1
}

private fun LongArray.isEmptyBits(): Boolean {
    var i = 0
    while (i < size) {
        if (this[i] != 0L) return false
        i++
    }
    return true
}

private fun wrapIndex(value: Int, size: Int): Int {
    val r = value % size
    return if (r < 0) r + size else r
}

private fun patternWeights(tileCount: Int, samplePatternIds: IntArray?): DoubleArray {
    val weights = DoubleArray(tileCount)
    if (samplePatternIds == null) {
        java.util.Arrays.fill(weights, 1.0)
    } else {
        var i = 0
        while (i < samplePatternIds.size) {
            val id = samplePatternIds[i]
            require(id in 0 until tileCount) { "Sample pattern id $id is outside 0 until $tileCount" }
            weights[id] += 1.0
            i++
        }
    }
    return weights
}

private fun intPatternsOverlap(
    a: IntArray,
    b: IntArray,
    width: Int,
    height: Int,
    dx: Int,
    dy: Int
): Boolean {
    val x0 = maxOf(0, dx)
    val x1 = minOf(width, width + dx)
    val y0 = maxOf(0, dy)
    val y1 = minOf(height, height + dy)
    if (x0 >= x1 || y0 >= y1) return true

    var y = y0
    while (y < y1) {
        var x = x0
        while (x < x1) {
            if (a[x + y * width] != b[(x - dx) + (y - dy) * width]) return false
            x++
        }
        y++
    }
    return true
}

private fun floatPatternsOverlap(
    a: FloatArray,
    b: FloatArray,
    width: Int,
    height: Int,
    dx: Int,
    dy: Int
): Boolean {
    val x0 = maxOf(0, dx)
    val x1 = minOf(width, width + dx)
    val y0 = maxOf(0, dy)
    val y1 = minOf(height, height + dy)
    if (x0 >= x1 || y0 >= y1) return true

    var y = y0
    while (y < y1) {
        var x = x0
        while (x < x1) {
            if (a[x + y * width] != b[(x - dx) + (y - dy) * width]) return false
            x++
        }
        y++
    }
    return true
}

/** The learned model: tile weights plus directional adjacency masks. */
class WfcRules private constructor(
    val tileCount: Int,
    val weights: DoubleArray,
    val neighborhood: WfcNeighborhood,
    /** allowed[direction][tile] is a bitset of tile IDs allowed in that direction from tile. */
    val allowed: Array<Array<LongArray>>
) {
    val wordCount: Int = wordsFor(tileCount)

    companion object {
        fun fromSampleGrid(
            sampleTileIds: IntArray,
            sampleWidth: Int,
            sampleHeight: Int,
            tileCount: Int,
            wrapX: Boolean = false,
            wrapY: Boolean = false,
            neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann
        ): WfcRules {
            require(sampleWidth > 0 && sampleHeight > 0)
            require(sampleTileIds.size == sampleWidth * sampleHeight)
            require(tileCount > 0)

            val wordCount = wordsFor(tileCount)
            val weights = DoubleArray(tileCount)
            val allowed = Array(neighborhood.directionCount) { Array(tileCount) { LongArray(wordCount) } }

            var i = 0
            while (i < sampleTileIds.size) {
                require(sampleTileIds[i] in 0 until tileCount) { "Sample tile id ${sampleTileIds[i]} is outside 0 until $tileCount" }
                weights[sampleTileIds[i]] += 1.0
                i++
            }

            var y = 0
            while (y < sampleHeight) {
                var x = 0
                while (x < sampleWidth) {
                    val a = sampleTileIds[x + y * sampleWidth]
                    var d = 0
                    while (d < neighborhood.directionCount) {
                        val offset = neighborhood.offset(d)
                        var nx = x + offset.dx
                        var ny = y + offset.dy
                        if (nx < 0 || nx >= sampleWidth) {
                            if (wrapX) nx = wrapIndex(nx, sampleWidth) else { d++; continue }
                        }
                        if (ny < 0 || ny >= sampleHeight) {
                            if (wrapY) ny = wrapIndex(ny, sampleHeight) else { d++; continue }
                        }
                        val b = sampleTileIds[nx + ny * sampleWidth]
                        allowed[d][a].setBit(b)
                        allowed[neighborhood.opposite(d)][b].setBit(a)
                        d++
                    }
                    x++
                }
                y++
            }

            // Defensive fallback: if a direction was never observed for a tile, allow all tiles there.
            // This keeps edge-only sample tiles from becoming unusable in unconstrained output regions.
            val all = LongArray(wordCount)
            all.fillAllBits(tileCount)
            var d = 0
            while (d < neighborhood.directionCount) {
                var t = 0
                while (t < tileCount) {
                    if (allowed[d][t].isEmptyBits()) allowed[d][t].copyFrom(all)
                    t++
                }
                d++
            }

            return WfcRules(tileCount, weights, neighborhood, allowed)
        }

        fun fromOverlappingIntPatterns(
            patterns: Array<IntArray>,
            patternWidth: Int,
            patternHeight: Int,
            samplePatternIds: IntArray? = null,
            neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann
        ): WfcRules {
            require(patterns.isNotEmpty())
            require(patternWidth > 0 && patternHeight > 0)
            val tileCount = patterns.size
            var i = 0
            while (i < tileCount) {
                require(patterns[i].size == patternWidth * patternHeight)
                i++
            }

            val wordCount = wordsFor(tileCount)
            val weights = patternWeights(tileCount, samplePatternIds)
            val allowed = Array(neighborhood.directionCount) { Array(tileCount) { LongArray(wordCount) } }

            var d = 0
            while (d < neighborhood.directionCount) {
                val offset = neighborhood.offset(d)
                var a = 0
                while (a < tileCount) {
                    var b = 0
                    while (b < tileCount) {
                        if (intPatternsOverlap(patterns[a], patterns[b], patternWidth, patternHeight, offset.dx, offset.dy)) {
                            allowed[d][a].setBit(b)
                        }
                        b++
                    }
                    a++
                }
                d++
            }

            return WfcRules(tileCount, weights, neighborhood, allowed)
        }

        fun fromOverlappingFloatPatterns(
            patterns: Array<FloatArray>,
            patternWidth: Int,
            patternHeight: Int,
            samplePatternIds: IntArray? = null,
            neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann
        ): WfcRules {
            require(patterns.isNotEmpty())
            require(patternWidth > 0 && patternHeight > 0)
            val tileCount = patterns.size
            var i = 0
            while (i < tileCount) {
                require(patterns[i].size == patternWidth * patternHeight)
                i++
            }

            val wordCount = wordsFor(tileCount)
            val weights = patternWeights(tileCount, samplePatternIds)
            val allowed = Array(neighborhood.directionCount) { Array(tileCount) { LongArray(wordCount) } }

            var d = 0
            while (d < neighborhood.directionCount) {
                val offset = neighborhood.offset(d)
                var a = 0
                while (a < tileCount) {
                    var b = 0
                    while (b < tileCount) {
                        if (floatPatternsOverlap(patterns[a], patterns[b], patternWidth, patternHeight, offset.dx, offset.dy)) {
                            allowed[d][a].setBit(b)
                        }
                        b++
                    }
                    a++
                }
                d++
            }

            return WfcRules(tileCount, weights, neighborhood, allowed)
        }
    }
}

/** How many WFC steps between progress-bar samples, keeping the resolved-tile scan off the hot path. */
private const val PROGRESS_STRIDE = 16

enum class WfcStatus { Running, Solved, Contradiction }

data class WfcObservation(val x: Int, val y: Int, val tile: Int)

class WfcSolver(
    val width: Int,
    val height: Int,
    val rules: WfcRules,
    seed: Long = 1L,
    private val noise: Double = 1e-6
) {
    private val random = SplittableRandom(seed)
    private val cellCount = width * height
    private val wordCount = rules.wordCount

    /** wave[cell * wordCount + word] is the possibility bitset for that cell. */
    private val wave = LongArray(cellCount * wordCount)
    private val allowedScratch = LongArray(wordCount)

    private var stack = IntArray(cellCount * maxOf(2, rules.neighborhood.directionCount + 1))
    private var stackSize = 0

    var status: WfcStatus = WfcStatus.Running
        private set
    var lastObservation: WfcObservation? = null
        private set

    init {
        require(width > 0 && height > 0)
        reset()
    }

    fun reset() {
        val all = LongArray(wordCount)
        all.fillAllBits(rules.tileCount)
        var cell = 0
        while (cell < cellCount) {
            setCellBits(cell, all)
            cell++
        }
        stackSize = 0
        status = WfcStatus.Running
        lastObservation = null
    }

    fun possibilitiesAt(x: Int, y: Int, out: IntArray): Int {
        val cell = index(x, y)
        var n = 0
        var w = 0
        while (w < wordCount) {
            var bits = wave[cell * wordCount + w]
            while (bits != 0L) {
                val bit = java.lang.Long.numberOfTrailingZeros(bits)
                val id = (w shl 6) + bit
                if (id < rules.tileCount && n < out.size) out[n++] = id
                bits = bits and (bits - 1L)
            }
            w++
        }
        return n
    }

    fun possibilityCountAt(x: Int, y: Int): Int = cellBitCount(index(x, y))

    fun resolvedTileAt(x: Int, y: Int): Int {
        val cell = index(x, y)
        return if (cellBitCount(cell) == 1) firstCellBit(cell) else -1
    }

    fun forceTile(x: Int, y: Int, tile: Int): Boolean {
        require(tile in 0 until rules.tileCount)
        if (status != WfcStatus.Running) return false
        val cell = index(x, y)
        keepOnlyCellBit(cell, tile)
        push(cell)
        propagate()
        return status != WfcStatus.Contradiction
    }

    /** One visualizable WFC iteration: observe one cell, then fully propagate constraints. */
    fun step(): WfcStatus {
        if (status != WfcStatus.Running) return status

        val cell = findLowestEntropyCell()
        if (cell < 0) {
            status = WfcStatus.Solved
            return status
        }

        val chosen = chooseWeightedTile(cell)
        keepOnlyCellBit(cell, chosen)
        lastObservation = WfcObservation(cell % width, cell / width, chosen)
        push(cell)
        propagate()
        return status
    }

    fun run(maxSteps: Int = Int.MAX_VALUE): WfcStatus {
        // Offer resolved-tiles / total-tiles to whichever box is running us (no-op if none).
        // The count isn't guaranteed monotonic, so we clamp the running bar below 100% and only
        // report completion when the solver itself reaches Solved.
        val progress = afield.progressReporter("wfc")
        var steps = 0
        try {
            while (steps < maxSteps && status == WfcStatus.Running) {
                step()
                steps++
                if (steps % PROGRESS_STRIDE == 0) {
                    val resolved = resolvedCellCount().coerceAtMost(cellCount - 1)
                    progress.update(resolved.toLong(), cellCount.toLong())
                }
            }
            if (status == WfcStatus.Solved) progress.update(cellCount.toLong(), cellCount.toLong())
        } finally {
            progress.finish()
        }
        return status
    }

    /** Cells collapsed to a single possibility — the WFC notion of "done" is all of them. */
    private fun resolvedCellCount(): Int {
        var resolved = 0
        var cell = 0
        while (cell < cellCount) {
            if (cellBitCount(cell) == 1) resolved++
            cell++
        }
        return resolved
    }

    fun resolvedTileGridOrNull(): IntArray? {
        if (status != WfcStatus.Solved) return null
        val out = IntArray(cellCount)
        var cell = 0
        while (cell < cellCount) {
            out[cell] = firstCellBit(cell)
            cell++
        }
        return out
    }

    private fun index(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return x + y * width
    }

    private fun setCellBits(cell: Int, bits: LongArray) {
        System.arraycopy(bits, 0, wave, cell * wordCount, wordCount)
    }

    private fun cellBitCount(cell: Int): Int {
        var c = 0
        var w = 0
        val base = cell * wordCount
        while (w < wordCount) {
            c += java.lang.Long.bitCount(wave[base + w])
            w++
        }
        return c
    }

    private fun firstCellBit(cell: Int): Int {
        var w = 0
        val base = cell * wordCount
        while (w < wordCount) {
            val bits = wave[base + w]
            if (bits != 0L) return (w shl 6) + java.lang.Long.numberOfTrailingZeros(bits)
            w++
        }
        return -1
    }

    private fun keepOnlyCellBit(cell: Int, tile: Int) {
        val base = cell * wordCount
        var w = 0
        while (w < wordCount) {
            wave[base + w] = 0L
            w++
        }
        wave[base + wordIndex(tile)] = bitMask(tile)
    }

    private fun push(cell: Int) {
        if (stackSize == stack.size) stack = stack.copyOf(stack.size * 2)
        stack[stackSize++] = cell
    }

    private fun findLowestEntropyCell(): Int {
        var bestCell = -1
        var bestScore = Double.POSITIVE_INFINITY
        var cell = 0
        while (cell < cellCount) {
            val count = cellBitCount(cell)
            if (count == 0) {
                status = WfcStatus.Contradiction
                return -1
            }
            if (count > 1) {
                var sum = 0.0
                var sumLog = 0.0
                var w = 0
                val base = cell * wordCount
                while (w < wordCount) {
                    var bits = wave[base + w]
                    while (bits != 0L) {
                        val b = java.lang.Long.numberOfTrailingZeros(bits)
                        val tile = (w shl 6) + b
                        if (tile < rules.tileCount) {
                            val weight = rules.weights[tile]
                            sum += weight
                            sumLog += weight * ln(weight)
                        }
                        bits = bits and (bits - 1L)
                    }
                    w++
                }
                val entropy = ln(sum) - sumLog / sum
                val score = entropy + random.nextDouble() * noise
                if (score < bestScore) {
                    bestScore = score
                    bestCell = cell
                }
            }
            cell++
        }
        return bestCell
    }

    private fun chooseWeightedTile(cell: Int): Int {
        var total = 0.0
        var w = 0
        val base = cell * wordCount
        while (w < wordCount) {
            var bits = wave[base + w]
            while (bits != 0L) {
                val b = java.lang.Long.numberOfTrailingZeros(bits)
                val tile = (w shl 6) + b
                if (tile < rules.tileCount) total += rules.weights[tile]
                bits = bits and (bits - 1L)
            }
            w++
        }

        var r = random.nextDouble(total)
        w = 0
        while (w < wordCount) {
            var bits = wave[base + w]
            while (bits != 0L) {
                val b = java.lang.Long.numberOfTrailingZeros(bits)
                val tile = (w shl 6) + b
                if (tile < rules.tileCount) {
                    r -= rules.weights[tile]
                    if (r <= 0.0) return tile
                }
                bits = bits and (bits - 1L)
            }
            w++
        }
        return firstCellBit(cell)
    }

    private fun propagate() {
        while (stackSize > 0 && status == WfcStatus.Running) {
            val cell = stack[--stackSize]
            val x = cell % width
            val y = cell / width

            var d = 0
            while (d < rules.neighborhood.directionCount) {
                val offset = rules.neighborhood.offset(d)
                val nx = x + offset.dx
                val ny = y + offset.dy
                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    val neighbor = nx + ny * width
                    buildAllowedFromCell(cell, d, allowedScratch)
                    if (intersectCell(neighbor, allowedScratch)) {
                        if (cellBitCount(neighbor) == 0) {
                            status = WfcStatus.Contradiction
                            return
                        }
                        push(neighbor)
                    }
                }
                d++
            }
        }
    }

    private fun buildAllowedFromCell(cell: Int, direction: Int, out: LongArray) {
        out.clearAll()
        val base = cell * wordCount
        var w = 0
        while (w < wordCount) {
            var bits = wave[base + w]
            while (bits != 0L) {
                val b = java.lang.Long.numberOfTrailingZeros(bits)
                val tile = (w shl 6) + b
                if (tile < rules.tileCount) out.orInPlace(rules.allowed[direction][tile])
                bits = bits and (bits - 1L)
            }
            w++
        }
    }

    private fun intersectCell(cell: Int, mask: LongArray): Boolean {
        var changed = false
        val base = cell * wordCount
        var w = 0
        while (w < wordCount) {
            val old = wave[base + w]
            val next = old and mask[w]
            if (next != old) {
                wave[base + w] = next
                changed = true
            }
            w++
        }
        return changed
    }
}

/** A fixed-size tile atlas extracted from a PNG. Pixels are ARGB ints. */
class PngTileSet(
    val tileWidth: Int,
    val tileHeight: Int,
    val tiles: Array<IntArray>,
    val sampleIds: IntArray,
    val sampleWidthInTiles: Int,
    val sampleHeightInTiles: Int
) {
    val tileCount: Int get() = tiles.size

    fun toRules(
        wrapX: Boolean = false,
        wrapY: Boolean = false,
        neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann
    ): WfcRules =
        WfcRules.fromSampleGrid(sampleIds, sampleWidthInTiles, sampleHeightInTiles, tileCount, wrapX, wrapY, neighborhood)

    fun render(tileIds: IntArray, outWidthInTiles: Int, outHeightInTiles: Int): BufferedImage {
        require(tileIds.size == outWidthInTiles * outHeightInTiles)
        val image = BufferedImage(outWidthInTiles * tileWidth, outHeightInTiles * tileHeight, BufferedImage.TYPE_INT_ARGB)
        var ty = 0
        while (ty < outHeightInTiles) {
            var tx = 0
            while (tx < outWidthInTiles) {
                val tile = tiles[tileIds[tx + ty * outWidthInTiles]]
                var py = 0
                while (py < tileHeight) {
                    var px = 0
                    while (px < tileWidth) {
                        image.setRGB(tx * tileWidth + px, ty * tileHeight + py, tile[px + py * tileWidth])
                        px++
                    }
                    py++
                }
                tx++
            }
            ty++
        }
        return image
    }
}

/** Overlapping WFC patterns extracted from a PNG. Each solver cell is a pattern anchor. */
class PngOverlappingTileSet(
    val patternWidth: Int,
    val patternHeight: Int,
    val patterns: Array<IntArray>,
    val sampleIds: IntArray,
    val sampleWidthInPatterns: Int,
    val sampleHeightInPatterns: Int
) {
    val tileCount: Int get() = patterns.size

    fun toRules(neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann): WfcRules =
        WfcRules.fromOverlappingIntPatterns(patterns, patternWidth, patternHeight, sampleIds, neighborhood)

    fun render(
        patternIds: IntArray,
        outWidth: Int,
        outHeight: Int,
        originX: Int = 0,
        originY: Int = 0
    ): BufferedImage {
        require(patternIds.size == outWidth * outHeight)
        val image = BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB)
        val ox = wrapIndex(originX, patternWidth)
        val oy = wrapIndex(originY, patternHeight)
        val origin = ox + oy * patternWidth

        var y = 0
        while (y < outHeight) {
            var x = 0
            while (x < outWidth) {
                val pattern = patterns[patternIds[x + y * outWidth]]
                image.setRGB(x, y, pattern[origin])
                x++
            }
            y++
        }
        return image
    }
}

fun BufferedImage.sliceIntoUniqueTiles(tileWidth: Int, tileHeight: Int): PngTileSet {
    require(tileWidth > 0 && tileHeight > 0)
    require(width % tileWidth == 0 && height % tileHeight == 0) {
        "Image dimensions must be divisible by tile size. image=${width}x$height tile=${tileWidth}x$tileHeight"
    }

    val sw = width / tileWidth
    val sh = height / tileHeight
    val sampleIds = IntArray(sw * sh)
    val prototypes = ArrayList<IntArray>()
    val map = HashMap<IntArrayKey, Int>()

    var ty = 0
    while (ty < sh) {
        var tx = 0
        while (tx < sw) {
            val pixels = IntArray(tileWidth * tileHeight)
            var py = 0
            while (py < tileHeight) {
                var px = 0
                while (px < tileWidth) {
                    pixels[px + py * tileWidth] = getRGB(tx * tileWidth + px, ty * tileHeight + py)
                    px++
                }
                py++
            }
            val key = IntArrayKey(pixels)
            val id = map.getOrPut(key) {
                prototypes.add(pixels)
                prototypes.lastIndex
            }
            sampleIds[tx + ty * sw] = id
            tx++
        }
        ty++
    }

    return PngTileSet(tileWidth, tileHeight, prototypes.toTypedArray(), sampleIds, sw, sh)
}

fun loadPngTiles(file: File, tileWidth: Int, tileHeight: Int): PngTileSet =
    ImageIO.read(file).sliceIntoUniqueTiles(tileWidth, tileHeight)

fun BufferedImage.sliceIntoOverlappingPatterns(
    patternWidth: Int,
    patternHeight: Int,
    wrapX: Boolean = true,
    wrapY: Boolean = true
): PngOverlappingTileSet {
    require(width > 0 && height > 0)
    require(patternWidth > 0 && patternHeight > 0)
    require(wrapX || patternWidth <= width) {
        "Pattern width must fit inside image when wrapX=false. imageWidth=$width patternWidth=$patternWidth"
    }
    require(wrapY || patternHeight <= height) {
        "Pattern height must fit inside image when wrapY=false. imageHeight=$height patternHeight=$patternHeight"
    }

    val sw = if (wrapX) width else width - patternWidth + 1
    val sh = if (wrapY) height else height - patternHeight + 1
    val sampleIds = IntArray(sw * sh)
    val prototypes = ArrayList<IntArray>()
    val map = HashMap<IntArrayKey, Int>()

    var ay = 0
    while (ay < sh) {
        var ax = 0
        while (ax < sw) {
            val pixels = IntArray(patternWidth * patternHeight)
            var py = 0
            while (py < patternHeight) {
                var px = 0
                while (px < patternWidth) {
                    val sx = if (wrapX) wrapIndex(ax + px, width) else ax + px
                    val sy = if (wrapY) wrapIndex(ay + py, height) else ay + py
                    pixels[px + py * patternWidth] = getRGB(sx, sy)
                    px++
                }
                py++
            }
            val key = IntArrayKey(pixels)
            val id = map.getOrPut(key) {
                prototypes.add(pixels)
                prototypes.lastIndex
            }
            sampleIds[ax + ay * sw] = id
            ax++
        }
        ay++
    }

    return PngOverlappingTileSet(patternWidth, patternHeight, prototypes.toTypedArray(), sampleIds, sw, sh)
}

fun loadPngOverlappingPatterns(
    file: File,
    patternWidth: Int,
    patternHeight: Int,
    wrapX: Boolean = true,
    wrapY: Boolean = true
): PngOverlappingTileSet =
    ImageIO.read(file).sliceIntoOverlappingPatterns(patternWidth, patternHeight, wrapX, wrapY)

fun savePng(image: BufferedImage, file: File) {
    ImageIO.write(image, "png", file)
}

/** A fixed-size tile atlas extracted from a row-major 2D FloatArray. */
class FloatTileSet(
    val tileWidth: Int,
    val tileHeight: Int,
    val tiles: Array<FloatArray>,
    val sampleIds: IntArray,
    val sampleWidthInTiles: Int,
    val sampleHeightInTiles: Int
) {
    val tileCount: Int get() = tiles.size

    fun toRules(
        wrapX: Boolean = false,
        wrapY: Boolean = false,
        neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann
    ): WfcRules =
        WfcRules.fromSampleGrid(sampleIds, sampleWidthInTiles, sampleHeightInTiles, tileCount, wrapX, wrapY, neighborhood)

    fun render(tileIds: IntArray, outWidthInTiles: Int, outHeightInTiles: Int): FloatArray {
        require(tileIds.size == outWidthInTiles * outHeightInTiles)
        val outW = outWidthInTiles * tileWidth
        val outH = outHeightInTiles * tileHeight
        val out = FloatArray(outW * outH)
        var ty = 0
        while (ty < outHeightInTiles) {
            var tx = 0
            while (tx < outWidthInTiles) {
                val tile = tiles[tileIds[tx + ty * outWidthInTiles]]
                var py = 0
                while (py < tileHeight) {
                    var px = 0
                    while (px < tileWidth) {
                        out[(tx * tileWidth + px) + (ty * tileHeight + py) * outW] = tile[px + py * tileWidth]
                        px++
                    }
                    py++
                }
                tx++
            }
            ty++
        }
        return out
    }
}

/** Overlapping WFC patterns extracted from a row-major 2D FloatArray. */
class FloatOverlappingTileSet(
    val patternWidth: Int,
    val patternHeight: Int,
    val patterns: Array<FloatArray>,
    val sampleIds: IntArray,
    val sampleWidthInPatterns: Int,
    val sampleHeightInPatterns: Int
) {
    val tileCount: Int get() = patterns.size

    fun toRules(neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann): WfcRules =
        WfcRules.fromOverlappingFloatPatterns(patterns, patternWidth, patternHeight, sampleIds, neighborhood)

    fun render(
        patternIds: IntArray,
        outWidth: Int,
        outHeight: Int,
        originX: Int = 0,
        originY: Int = 0
    ): FloatArray {
        require(patternIds.size == outWidth * outHeight)
        val out = FloatArray(outWidth * outHeight)
        val ox = wrapIndex(originX, patternWidth)
        val oy = wrapIndex(originY, patternHeight)
        val origin = ox + oy * patternWidth

        var y = 0
        while (y < outHeight) {
            var x = 0
            while (x < outWidth) {
                val pattern = patterns[patternIds[x + y * outWidth]]
                out[x + y * outWidth] = pattern[origin]
                x++
            }
            y++
        }
        return out
    }
}

fun FloatArray.sliceIntoUniqueTiles2D(
    width: Int,
    height: Int,
    tileWidth: Int,
    tileHeight: Int
): FloatTileSet {
    require(width > 0 && height > 0)
    require(size == width * height)
    require(tileWidth > 0 && tileHeight > 0)
    require(width % tileWidth == 0 && height % tileHeight == 0) {
        "Array dimensions must be divisible by tile size. array=${width}x$height tile=${tileWidth}x$tileHeight"
    }

    val sw = width / tileWidth
    val sh = height / tileHeight
    val sampleIds = IntArray(sw * sh)
    val prototypes = ArrayList<FloatArray>()
    val map = HashMap<FloatArrayKey, Int>()

    var ty = 0
    while (ty < sh) {
        var tx = 0
        while (tx < sw) {
            val values = FloatArray(tileWidth * tileHeight)
            var py = 0
            while (py < tileHeight) {
                var px = 0
                while (px < tileWidth) {
                    values[px + py * tileWidth] = this[(tx * tileWidth + px) + (ty * tileHeight + py) * width]
                    px++
                }
                py++
            }
            val key = FloatArrayKey(values)
            val id = map.getOrPut(key) {
                prototypes.add(values)
                prototypes.lastIndex
            }
            sampleIds[tx + ty * sw] = id
            tx++
        }
        ty++
    }

    return FloatTileSet(tileWidth, tileHeight, prototypes.toTypedArray(), sampleIds, sw, sh)
}

fun FloatArray.sliceIntoOverlappingPatterns2D(
    width: Int,
    height: Int,
    patternWidth: Int,
    patternHeight: Int,
    wrapX: Boolean = true,
    wrapY: Boolean = true
): FloatOverlappingTileSet {
    require(width > 0 && height > 0)
    require(size == width * height)
    require(patternWidth > 0 && patternHeight > 0)
    require(wrapX || patternWidth <= width) {
        "Pattern width must fit inside array when wrapX=false. arrayWidth=$width patternWidth=$patternWidth"
    }
    require(wrapY || patternHeight <= height) {
        "Pattern height must fit inside array when wrapY=false. arrayHeight=$height patternHeight=$patternHeight"
    }

    val sw = if (wrapX) width else width - patternWidth + 1
    val sh = if (wrapY) height else height - patternHeight + 1
    val sampleIds = IntArray(sw * sh)
    val prototypes = ArrayList<FloatArray>()
    val map = HashMap<FloatArrayKey, Int>()

    var ay = 0
    while (ay < sh) {
        var ax = 0
        while (ax < sw) {
            val values = FloatArray(patternWidth * patternHeight)
            var py = 0
            while (py < patternHeight) {
                var px = 0
                while (px < patternWidth) {
                    val sx = if (wrapX) wrapIndex(ax + px, width) else ax + px
                    val sy = if (wrapY) wrapIndex(ay + py, height) else ay + py
                    values[px + py * patternWidth] = this[sx + sy * width]
                    px++
                }
                py++
            }
            val key = FloatArrayKey(values)
            val id = map.getOrPut(key) {
                prototypes.add(values)
                prototypes.lastIndex
            }
            sampleIds[ax + ay * sw] = id
            ax++
        }
        ay++
    }

    return FloatOverlappingTileSet(patternWidth, patternHeight, prototypes.toTypedArray(), sampleIds, sw, sh)
}

private class IntArrayKey(private val data: IntArray) {
    private val hash = data.contentHashCode()
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is IntArrayKey && data.contentEquals(other.data)
}

private class FloatArrayKey(private val data: FloatArray) {
    private val hash = data.contentHashCode()
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is FloatArrayKey && data.contentEquals(other.data)
}

/** Tiny CLI demo: java -jar app.jar input.png output.png tileW tileH outTilesW outTilesH [seed] */
fun main(args: Array<String>) {
    if (args.size < 6) {
        println("Usage: input.png output.png tileW tileH outTilesW outTilesH [seed]")
        return
    }

    val input = File(args[0])
    val output = File(args[1])
    val tileW = args[2].toInt()
    val tileH = args[3].toInt()
    val outW = args[4].toInt()
    val outH = args[5].toInt()
    val seed = args.getOrNull(6)?.toLong() ?: 1L

    val tiles = loadPngTiles(input, tileW, tileH)
    val rules = tiles.toRules(wrapX = false, wrapY = false)
    val solver = WfcSolver(outW, outH, rules, seed)

    while (solver.status == WfcStatus.Running) {
        solver.step()
        // Visualization hook: read solver.lastObservation, possibilityCountAt(x, y), resolvedTileAt(x, y), etc.
    }

    if (solver.status == WfcStatus.Solved) {
        val image = tiles.render(solver.resolvedTileGridOrNull()!!, outW, outH)
        savePng(image, output)
        println("Solved. Wrote ${output.absolutePath}")
    } else {
        println("Contradiction. Try another seed or a less restrictive sample/rule setup.")
    }
}
