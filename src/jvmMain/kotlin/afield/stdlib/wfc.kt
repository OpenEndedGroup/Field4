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
 * - The one concession to the rest of Field: [WfcSolver] implements [afield.ToHTML], so evaluating a
 *   solver in a box draws the collapse instead of a reflective property tree. Every pixel of that
 *   view lives in `wfc_field.kt` ([afield.stdlib.wfcSolverHtml]); this file names it once, in
 *   [WfcSolver.toHTML], and depends on nothing else outside itself.
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

private fun weightsFromSampleIds(tileCount: Int, samplePatternIds: IntArray?): DoubleArray {
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

/**
 * Widen every empty `allowed[direction][tile]` mask to "any tile", **keeping the relation symmetric**.
 *
 * WFC's propagation is only sound if adjacency is a symmetric relation — that is, if
 * `allowed[d][a]` contains `b` exactly when `allowed[opposite(d)][b]` contains `a`. Propagation only
 * ever pushes constraints *outward* from a cell that changed, so if `allowed[EAST][a]` admits `b`
 * while `allowed[WEST][b]` does not admit `a`, nothing in the solver will ever notice: the arc is
 * checked in one direction only, and the solver can happily emit `a` with `b` to its east — a pair
 * its own rules forbid when read the other way round.
 *
 * Naively assigning `allowed[d][t] = every tile` therefore *introduces* that asymmetry, because it
 * says "anything may sit at `d` from `t`" without also telling each of those tiles that `t` may sit
 * on their opposite side. So the widening has to be done in both directions at once, and the set of
 * empty masks has to be collected up front — filling one mask can make another non-empty.
 */
private fun widenEmptyMasksSymmetrically(
    allowed: Array<Array<LongArray>>,
    neighborhood: WfcNeighborhood,
    tileCount: Int,
    wordCount: Int
): Array<BooleanArray> {
    val all = LongArray(wordCount)
    all.fillAllBits(tileCount)
    val widened = Array(neighborhood.directionCount) { BooleanArray(tileCount) }

    // Collect first: widening allowed[opposite(d)][s] below can un-empty a mask we have not tested.
    val emptyDirections = ArrayList<Int>()
    val emptyTiles = ArrayList<Int>()
    var d = 0
    while (d < neighborhood.directionCount) {
        var t = 0
        while (t < tileCount) {
            if (allowed[d][t].isEmptyBits()) {
                emptyDirections.add(d)
                emptyTiles.add(t)
            }
            t++
        }
        d++
    }

    var i = 0
    while (i < emptyDirections.size) {
        val direction = emptyDirections[i]
        val tile = emptyTiles[i]
        allowed[direction][tile].copyFrom(all)
        widened[direction][tile] = true
        val opposite = neighborhood.opposite(direction)
        var s = 0
        while (s < tileCount) {
            allowed[opposite][s].setBit(tile)
            s++
        }
        i++
    }
    return widened
}

/** Deep copy of an adjacency table, for keeping the pre-widening state around. */
private fun copyAllowed(allowed: Array<Array<LongArray>>): Array<Array<LongArray>> =
    Array(allowed.size) { d -> Array(allowed[d].size) { t -> allowed[d][t].copyOf() } }

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
    val allowed: Array<Array<LongArray>>,
    /**
     * `[direction][tile]` = this mask was empty and got filled in by the widening fallback, or null
     * when no widening happened (the overwhelmingly common case, and then observed == allowed).
     */
    private val widenedMasks: Array<BooleanArray>? = null,
    /** [allowed] as it stood *before* widening, or null when nothing was widened. */
    private val observedAllowed: Array<Array<LongArray>>? = null
) : afield.ToHTML {
    val wordCount: Int = wordsFor(tileCount)

    /**
     * May tile [b] sit at [direction] from tile [a]?
     *
     * The same question [allowed] answers, without making you index a bitset by hand. Worth reaching
     * for twice: to *inspect* a learned model — print the adjacency table and you can see what a
     * sample image actually taught the solver, which is usually more informative than looking at the
     * output — and to *check* one, since every adjacent pair in a solved grid must satisfy this.
     */
    fun allows(direction: Int, a: Int, b: Int): Boolean {
        require(direction in 0 until neighborhood.directionCount) {
            "direction $direction outside 0 until ${neighborhood.directionCount}"
        }
        require(a in 0 until tileCount) { "tile $a outside 0 until $tileCount" }
        require(b in 0 until tileCount) { "tile $b outside 0 until $tileCount" }
        return allowed[direction][a].containsBit(b)
    }

    /**
     * The first `(direction, a, b)` where adjacency is *not* symmetric, or `null` when the rules are
     * sound. Returned as a human-readable string because it is a diagnostic, not a control path.
     *
     * WFC propagates constraints outward from cells that changed, and it does so in one direction per
     * arc. That is only sufficient if `allows(d, a, b) == allows(opposite(d), b, a)` for every triple:
     * an asymmetric rule set has arcs the solver structurally cannot check, and it will cheerfully
     * emit adjacent pairs its own rules forbid when read the other way round. Every factory in this
     * file maintains the invariant; this is here for rules you build yourself, and for a test to
     * assert against.
     */
    fun findAsymmetry(): String? {
        var d = 0
        while (d < neighborhood.directionCount) {
            val opposite = neighborhood.opposite(d)
            var a = 0
            while (a < tileCount) {
                var b = 0
                while (b < tileCount) {
                    if (allowed[d][a].containsBit(b) != allowed[opposite][b].containsBit(a)) {
                        return "allows($d, $a, $b)=${allowed[d][a].containsBit(b)} but " +
                            "allows($opposite, $b, $a)=${allowed[opposite][b].containsBit(a)}"
                    }
                    b++
                }
                a++
            }
            d++
        }
        return null
    }

    /** How many tiles may sit at [direction] from [a] — the branching factor of the model. */
    fun allowedCount(direction: Int, a: Int): Int {
        require(direction in 0 until neighborhood.directionCount)
        require(a in 0 until tileCount)
        return allowed[direction][a].countBits()
    }

    // ------------------------------------------------------------------------------------------
    // Observed vs invented adjacency
    //
    // [fromSampleGrid] widens any `(direction, tile)` mask its sample left empty to "any tile may
    // sit there", because a tile the sample only ever showed at its own edge would otherwise be
    // unplaceable. That fallback is often what you want and it is *never* what you want silently:
    // it fabricates adjacencies the sample never contained, and — because adjacency has to stay
    // symmetric — filling one mask also tells every other tile that this one may sit on its far
    // side. One empty mask therefore injects `2 x tileCount` pairs, so a handful of them can invent
    // most of the table. Measured on the terrain source behind `wfc_examples.md`: at 1x1 tiles no
    // mask is empty and nothing is invented, while at 2x2 tiles 10 of 144 masks are empty and the
    // resulting table is 282 observed pairs plus 704 invented ones.
    //
    // None of that is visible in [allows], which is the problem. These four are how a caller — and
    // the figure gallery — can hold the model to what the sample actually showed.
    // ------------------------------------------------------------------------------------------

    /** Did the widening fallback fill in `allowed[direction][tile]` because the sample left it empty? */
    fun wasWidened(direction: Int, tile: Int): Boolean {
        require(direction in 0 until neighborhood.directionCount) {
            "direction $direction outside 0 until ${neighborhood.directionCount}"
        }
        require(tile in 0 until tileCount) { "tile $tile outside 0 until $tileCount" }
        return widenedMasks?.get(direction)?.get(tile) ?: false
    }

    /** How many `(direction, tile)` masks the widening fallback filled in; 0 when it never fired. */
    val widenedMaskCount: Int
        get() = widenedMasks?.sumOf { row -> row.count { it } } ?: 0

    /**
     * [allows], restricted to what the sample actually contained — false for a pair that exists
     * only because of the widening fallback.
     *
     * This is the predicate to assert a *learned* model against. [allows] is the right question for
     * "is this output legal", since the solver enforces the widened table; this is the right
     * question for "did the output reuse the source's structure", which the widened table cannot
     * answer because it permits pairs the source never showed.
     */
    fun observedAllows(direction: Int, a: Int, b: Int): Boolean {
        val table = observedAllowed ?: return allows(direction, a, b)
        require(direction in 0 until neighborhood.directionCount) {
            "direction $direction outside 0 until ${neighborhood.directionCount}"
        }
        require(a in 0 until tileCount) { "tile $a outside 0 until $tileCount" }
        require(b in 0 until tileCount) { "tile $b outside 0 until $tileCount" }
        return table[direction][a].containsBit(b)
    }

    /**
     * How many allowed pairs exist only because of widening, and how many the sample showed.
     *
     * Returned as a pair of `(observed, invented)` counts over all directions, so a caller can
     * print the ratio that says how much of this table is evidence and how much is fallback.
     */
    fun observedAndInventedPairCounts(): Pair<Int, Int> {
        var observed = 0
        var invented = 0
        var d = 0
        while (d < neighborhood.directionCount) {
            var a = 0
            while (a < tileCount) {
                var b = 0
                while (b < tileCount) {
                    if (allows(d, a, b)) {
                        if (observedAllows(d, a, b)) observed++ else invented++
                    }
                    b++
                }
                a++
            }
            d++
        }
        return observed to invented
    }

    override fun toString(): String =
        "WfcRules($tileCount tiles, ${neighborhood.directionCount} directions" +
            (if (findAsymmetry() == null) "" else ", ASYMMETRIC") +
            (if (widenedMaskCount == 0) "" else ", $widenedMaskCount widened mask" +
                (if (widenedMaskCount == 1) "" else "s")) + ")"

    /**
     * The rule set's own inline view: one adjacency matrix per direction plus the weights strip, with
     * the three things that silently break a solve — empty masks, asymmetry, zero weights — marked.
     *
     * Delegates to [afield.stdlib.wfcRulesHtml] in `wfc_field.kt`; see its KDoc.
     */
    override fun toHTML(): String = wfcRulesHtml(this)

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
            //
            // Snapshot the table first, but only when the fallback is actually going to fire —
            // keeping the observed table costs a second copy of every bitset, and for the common
            // case (no empty mask, so nothing invented) there is nothing to distinguish it from.
            val anyEmpty = (0 until neighborhood.directionCount).any { d ->
                (0 until tileCount).any { t -> allowed[d][t].isEmptyBits() }
            }
            val observed = if (anyEmpty) copyAllowed(allowed) else null
            val widened = widenEmptyMasksSymmetrically(allowed, neighborhood, tileCount, wordCount)

            return WfcRules(tileCount, weights, neighborhood, allowed, if (anyEmpty) widened else null, observed)
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
            val weights = weightsFromSampleIds(tileCount, samplePatternIds)
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
            val weights = weightsFromSampleIds(tileCount, samplePatternIds)
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

        /**
         * Assemble rules from **hand-authored** adjacency, rather than learning them from a sample.
         *
         * Every other factory here infers what may touch what by looking at a sample grid or a set
         * of overlapping patterns. That is the point of WFC, but it is a bad way to *learn* WFC:
         * the constraints stay implicit in a picture. This factory is the explicit form — you name
         * the tiles by integer ID and say which pairs may be neighbours:
         *
         * ```
         * val rules = wfcRules(tileCount = 3) {
         *     weight(SEA, 3.0)                  // sea is three times as likely as anything else
         *     allow(WFC_EAST, SEA, SEA)         // sea may sit east of sea
         *     allow(WFC_EAST, SEA, SAND)        // ... and sand east of sea
         *     allow(WFC_EAST, SAND, GRASS)      // but grass never touches sea
         * }
         * ```
         *
         * Unlike [fromSampleGrid], **nothing is widened for you**: a `(direction, tile)` pair you
         * never mention stays empty, which says "no tile may sit there at all". That is a real
         * constraint (a tile with no legal east neighbour can only occur on the east edge) and
         * sometimes a deliberate one, so it is left exactly as authored — but it is also the most
         * common authoring mistake, and the usual symptom is an immediate
         * [WfcStatus.Contradiction]. [WfcRulesBuilder.allowMissingAnything] opts in to the
         * `fromSampleGrid` fallback if that is what you meant.
         *
         * @param tileCount how many distinct tile IDs exist; IDs are `0 until tileCount`
         * @param neighborhood which relative offsets are constrained, default [WfcNeighborhood.VonNeumann]
         */
        fun build(
            tileCount: Int,
            neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann,
            block: WfcRulesBuilder.() -> Unit
        ): WfcRules {
            require(tileCount > 0) { "tileCount must be positive, got $tileCount" }
            val builder = WfcRulesBuilder(tileCount, neighborhood)
            builder.block()
            return WfcRules(
                tileCount,
                builder.weightSnapshot(),
                neighborhood,
                builder.allowedSnapshot(),
                builder.widenedSnapshot(),
                builder.observedSnapshot()
            )
        }
    }
}

/**
 * Accumulates hand-authored WFC adjacency; see [WfcRules.build], which is how you get one.
 *
 * Adjacency here is always **symmetric**, exactly as it is when learned from a sample: saying that
 * `b` may sit east of `a` also says that `a` may sit west of `b`. You therefore only ever author
 * half the directions of a [WfcNeighborhood] — for the default von Neumann set, east and south are
 * enough.
 */
class WfcRulesBuilder internal constructor(
    val tileCount: Int,
    val neighborhood: WfcNeighborhood
) {
    private val wordCount = wordsFor(tileCount)
    private val weights = DoubleArray(tileCount) { 1.0 }
    private val allowed = Array(neighborhood.directionCount) { Array(tileCount) { LongArray(wordCount) } }
    private var widened: Array<BooleanArray>? = null
    private var observed: Array<Array<LongArray>>? = null

    /**
     * Set the relative frequency of [tile]. Weights bias both which tile a collapse picks and the
     * entropy that decides *which cell* to collapse next; they default to `1.0` (uniform).
     */
    fun weight(tile: Int, weight: Double) {
        requireTile(tile)
        require(weight > 0.0) { "WFC tile weights must be positive, got $weight for tile $tile" }
        weights[tile] = weight
    }

    /** Set every tile weight at once, in tile-ID order. */
    fun weights(vararg values: Double) {
        require(values.size == tileCount) { "expected $tileCount weights, got ${values.size}" }
        var t = 0
        while (t < values.size) {
            weight(t, values[t])
            t++
        }
    }

    /**
     * Allow tile [b] to sit at [direction] from tile [a] — and, symmetrically, [a] at the opposite
     * direction from [b].
     *
     * @param direction an index into [neighborhood]; for the default von Neumann set that is one of
     *   [WFC_NORTH], [WFC_EAST], [WFC_SOUTH], [WFC_WEST]
     */
    fun allow(direction: Int, a: Int, b: Int) {
        require(direction in 0 until neighborhood.directionCount) {
            "direction $direction outside 0 until ${neighborhood.directionCount}"
        }
        requireTile(a)
        requireTile(b)
        allowed[direction][a].setBit(b)
        allowed[neighborhood.opposite(direction)][b].setBit(a)
    }

    /** [allow], addressing the direction by its offset instead of its index. */
    fun allow(dx: Int, dy: Int, a: Int, b: Int) {
        val direction = neighborhood.directionOf(dx, dy)
        require(direction >= 0) { "offset ($dx, $dy) is not in $neighborhood" }
        allow(direction, a, b)
    }

    /** `allow(direction, a, b)` for every `b` in [others] — the common "a tolerates any of" case. */
    fun allowAny(direction: Int, a: Int, vararg others: Int) {
        for (b in others) allow(direction, a, b)
    }

    /**
     * Fill in "anything goes" for every `(direction, tile)` pair still left empty, which is the
     * defensive fallback [WfcRules.fromSampleGrid] applies to sample tiles it only ever saw at an
     * edge. Off by default for hand-authored rules, because there an empty mask is more often a
     * typo than an intention.
     *
     * Widening stays **symmetric**: saying "anything may sit east of `t`" also tells every one of
     * those tiles that `t` may sit to *their* west. Doing only half of that produces rules the
     * solver cannot enforce — see `widenEmptyMasksSymmetrically`.
     *
     * What it filled in is recorded, so the finished rules can still be asked what you actually
     * authored: see [WfcRules.wasWidened] and [WfcRules.observedAllows].
     */
    fun allowMissingAnything() {
        if (observed == null) observed = copyAllowed(allowed)
        val justWidened = widenEmptyMasksSymmetrically(allowed, neighborhood, tileCount, wordCount)
        val soFar = widened
        widened = if (soFar == null) justWidened
        else Array(soFar.size) { d -> BooleanArray(tileCount) { t -> soFar[d][t] || justWidened[d][t] } }
    }

    private fun requireTile(tile: Int) {
        require(tile in 0 until tileCount) { "tile $tile outside 0 until $tileCount" }
    }

    internal fun weightSnapshot(): DoubleArray = weights.copyOf()

    internal fun allowedSnapshot(): Array<Array<LongArray>> =
        Array(allowed.size) { d -> Array(tileCount) { t -> allowed[d][t].copyOf() } }

    internal fun widenedSnapshot(): Array<BooleanArray>? =
        widened?.let { w -> Array(w.size) { d -> w[d].copyOf() } }

    internal fun observedSnapshot(): Array<Array<LongArray>>? = observed?.let { copyAllowed(it) }
}

/**
 * Hand-author WFC adjacency constraints; shorthand for [WfcRules.build].
 *
 * ```
 * val rules = wfcRules(tileCount = 2) {
 *     allow(WFC_EAST, 0, 1)
 *     allow(WFC_SOUTH, 0, 1)
 * }
 * ```
 */
fun wfcRules(
    tileCount: Int,
    neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann,
    block: WfcRulesBuilder.() -> Unit
): WfcRules = WfcRules.build(tileCount, neighborhood, block)

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
) : afield.ToHTML {
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

    /**
     * Return every cell to "any tile is still possible" and clear [status] and [lastObservation].
     *
     * **Does not** reset the random stream: the solver's [SplittableRandom] is built
     * once, from `seed`, and keeps going across resets. That is what makes a retry loop useful —
     * `reset()` after a [WfcStatus.Contradiction] gives you a genuinely *different* attempt rather
     * than replaying the one that just failed. See [solveWithRetries].
     *
     * Reproducibility is therefore a property of the whole *session*, not of each attempt: the same
     * `seed` and the same sequence of calls give the same output, but the third attempt after two
     * contradictions is not the same as a fresh solver's first attempt.
     */
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

    /**
     * Cells collapsed to a single possibility — the WFC notion of "done" is all of them.
     *
     * Not monotonic in general: propagation can decide several cells at once, and this is a full
     * scan rather than a maintained counter, so treat it as a progress read-out (see
     * [afield.stdlib.resolvedFraction]) and not as a step count.
     */
    fun resolvedCellCount(): Int {
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

    override fun toString(): String =
        "WfcSolver(${width}x$height, ${rules.tileCount} tiles, $status, " +
            "${resolvedCellCount()}/$cellCount decided)"

    /**
     * The solver's own inline view, so that evaluating it in a box — or `solver.h` — shows the
     * collapse: remaining possibilities per cell, entropy, what has been decided, and any
     * contradiction, each panel captioned with the colour scale that produced it.
     *
     * Delegates to [afield.stdlib.wfcSolverHtml] in `wfc_field.kt`; see its KDoc for the panels and
     * the range policy. This is the only line in this file that looks outside it.
     */
    override fun toHTML(): String = wfcSolverHtml(this)

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

/** What [solveWithRetries] ended up doing: the final [status] and how many attempts it took. */
data class WfcOutcome(val status: WfcStatus, val attempts: Int) {
    val solved: Boolean get() = status == WfcStatus.Solved
}

/**
 * Run the solver to completion, restarting after a contradiction, up to [maxAttempts] times.
 *
 * This solver does not backtrack. When propagation empties some cell's possibility set the run is
 * over — there is no undo stack to unwind to the choice that doomed it. The standard remedy, and the
 * one the original WFC used, is simply to start again: contradictions are usually the product of an
 * unlucky early choice, so a fresh attempt on the same rules very often succeeds.
 *
 * Restarting works because [WfcSolver.reset] keeps the random stream running (see its KDoc), so each
 * attempt explores a different set of choices. A solver that has exhausted [maxAttempts] is left in
 * whatever state its last attempt reached.
 *
 * ```
 * val outcome = solver.solveWithRetries()
 * if (!outcome.solved) println("still stuck after ${outcome.attempts} attempts — loosen the rules")
 * ```
 *
 * [WfcSolver.reset] clears the whole wave, boundary conditions included, so anything you pinned with
 * [WfcSolver.forceTile] has to be re-applied on every attempt. That is what [prepare] is for — it
 * runs on the fresh solver before each attempt, including the first:
 *
 * ```
 * val outcome = solver.solveWithRetries { for (x in 0 until width) forceTile(x, height - 1, groundTile) }
 * ```
 *
 * If this needs a large [maxAttempts] to succeed, that is a signal about the *rules*, not the seed:
 * over-constrained adjacency, or an output much larger than the sample can consistently tile.
 *
 * @param prepare boundary conditions to (re-)apply before each attempt; may itself provoke a
 *   contradiction, which simply costs that attempt
 */
fun WfcSolver.solveWithRetries(maxAttempts: Int = 16, prepare: WfcSolver.() -> Unit = {}): WfcOutcome {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1, got $maxAttempts" }
    var attempt = 1
    while (true) {
        prepare()
        if (run() == WfcStatus.Solved) return WfcOutcome(WfcStatus.Solved, attempt)
        if (attempt >= maxAttempts) return WfcOutcome(status, attempt)
        attempt++
        reset()
    }
}

/**
 * Which symmetries of the square a learned pattern set should be closed under.
 *
 * WFC learns from *observed* windows, so it learns your source's orientation along with its content.
 * That is right when the source has a real up — sky over soil, text, a tileset drawn with lighting
 * from one side. It is wrong, and quite badly wrong, when the source is a sample of something with
 * no preferred direction, because the model then reproduces whichever orientation the source
 * happened to contain *most* of and tiles the plane with it.
 *
 * The classic symptom is in [`wfc_examples.md`](../wfc_examples.md) §7: a concentric island, sliced
 * into 3×3 windows, generates horizontal stripes. Nothing is broken — the wide top and bottom of a
 * concentric shape contribute far more "changes vertically, constant horizontally" windows than its
 * narrow left and right edges contribute the reverse, so that is the arrangement the model believes
 * in. Closing the pattern set under [All] takes the anisotropy from 7.7× to 1.0× and turns the
 * stripes into plateaus and basins.
 *
 * Use [None] when up matters. Use [All] for heightfields, masks, noise, terrain — anything where a
 * rotated copy of a legal arrangement is also legal. [Rotations] and [Reflections] are there for the
 * in-between cases: a source with a mirror symmetry but a real up wants [Reflections] alone.
 *
 * The cost is pattern count, which drives both rule learning (every pattern is compared against
 * every other) and the per-step entropy scan: on that same source, 180 patterns become 541 under
 * [All]. It is not `×8` — variants that coincide are deduplicated, and a window that is already
 * symmetric contributes nothing new.
 *
 * @see FloatOverlappingTileSet.expandSymmetry
 * @see PngOverlappingTileSet.expandSymmetry
 */
enum class WfcSymmetry(
    /** How many 90° rotations to include, 1 (identity only) or 4. */
    internal val rotations: Int,
    /** Whether each rotation also contributes its mirror image. */
    internal val reflect: Boolean
) {
    /** Patterns exactly as observed. The default everywhere, and correct when the source has an up. */
    None(1, false),

    /** Each pattern plus its mirror image. For a source with left-right symmetry but a real up. */
    Reflections(1, true),

    /** Each pattern plus its three 90° rotations. */
    Rotations(4, false),

    /** The full symmetry group of the square: four rotations, each with its mirror. Eight variants. */
    All(4, true);

    /** How many variants of one pattern this generates, before deduplication. */
    val variantCount: Int get() = rotations * (if (reflect) 2 else 1)
}

/** One 90° clockwise rotation of a square row-major pattern. */
private fun rotatePatternInts(p: IntArray, n: Int): IntArray {
    val out = IntArray(n * n)
    for (y in 0 until n) for (x in 0 until n) out[(n - 1 - y) + x * n] = p[x + y * n]
    return out
}

/** Mirror a row-major pattern left-to-right. Works for non-square patterns too. */
private fun mirrorPatternInts(p: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) + y * w] = p[x + y * w]
    return out
}

private fun rotatePatternFloats(p: FloatArray, n: Int): FloatArray {
    val out = FloatArray(n * n)
    for (y in 0 until n) for (x in 0 until n) out[(n - 1 - y) + x * n] = p[x + y * n]
    return out
}

private fun mirrorPatternFloats(p: FloatArray, w: Int, h: Int): FloatArray {
    val out = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) + y * w] = p[x + y * w]
    return out
}

/**
 * The shared body of both `expandSymmetry` implementations, over an opaque pattern type.
 *
 * Kept generic because the only thing that differs between the int and float cases is how a pattern
 * is rotated, mirrored and hashed — and getting the *weight* arithmetic wrong in two places
 * independently is exactly the kind of thing that produces a subtly wrong model twice.
 *
 * Weight handling is the part worth stating. A pattern observed `k` times contributes `k / v` to
 * each of its `v` variants, so the expansion is weight-preserving: the total stays `k`, and a
 * pattern does not become eight times more likely merely because it has eight distinct orientations.
 * Variants that coincide accumulate, which is why an already-symmetric window keeps its full weight
 * instead of being diluted.
 *
 * @return the deduplicated variant list, its weights, and `oldId -> newId` for the identity variants
 */
private fun <P> expandPatternsUnderSymmetry(
    patterns: Array<P>,
    baseWeights: DoubleArray,
    symmetry: WfcSymmetry,
    rotate: (P) -> P,
    mirror: (P) -> P,
    key: (P) -> Any
): Triple<List<P>, DoubleArray, IntArray> {
    val index = HashMap<Any, Int>()
    val out = ArrayList<P>()
    val weights = ArrayList<Double>()
    val remap = IntArray(patterns.size)

    for (i in patterns.indices) {
        val variants = ArrayList<P>(symmetry.variantCount)
        var current = patterns[i]
        var r = 0
        while (r < symmetry.rotations) {
            variants.add(current)
            if (symmetry.reflect) variants.add(mirror(current))
            current = rotate(current)
            r++
        }
        val share = baseWeights[i] / variants.size
        for ((v, variant) in variants.withIndex()) {
            val k = key(variant)
            val at = index[k]
            if (at == null) {
                index[k] = out.size
                // The identity variant is always first, so this is where oldId lands.
                if (v == 0) remap[i] = out.size
                out.add(variant)
                weights.add(share)
            } else {
                if (v == 0) remap[i] = at
                weights[at] = weights[at] + share
            }
        }
    }
    return Triple(out, weights.toDoubleArray(), remap)
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
    val sampleHeightInPatterns: Int,
    /**
     * Explicit per-pattern weights, or null to count occurrences in [sampleIds] as usual.
     *
     * Set by [expandSymmetry], whose extra variants were never *observed* anywhere in the sample and
     * so have no occurrence count to derive a weight from — counting would hand every one of them a
     * zero weight and make them unchoosable with no warning.
     */
    val patternWeights: DoubleArray? = null
) {
    val tileCount: Int get() = patterns.size

    fun toRules(neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann): WfcRules {
        val rules = WfcRules.fromOverlappingIntPatterns(
            patterns, patternWidth, patternHeight,
            if (patternWeights == null) sampleIds else null, neighborhood
        )
        patternWeights?.let { it.copyInto(rules.weights) }
        return rules
    }

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
    val sampleHeightInPatterns: Int,
    /** Explicit per-pattern weights, or null to count occurrences in [sampleIds]; see [PngOverlappingTileSet.patternWeights]. */
    val patternWeights: DoubleArray? = null
) {
    val tileCount: Int get() = patterns.size

    fun toRules(neighborhood: WfcNeighborhood = WfcNeighborhood.VonNeumann): WfcRules {
        val rules = WfcRules.fromOverlappingFloatPatterns(
            patterns, patternWidth, patternHeight,
            if (patternWeights == null) sampleIds else null, neighborhood
        )
        patternWeights?.let { it.copyInto(rules.weights) }
        return rules
    }

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

/**
 * Close this pattern set under [symmetry], so the model stops caring which way up the source was.
 *
 * Returns a **new** set whose patterns are the deduplicated orbit of the originals, with weights
 * shared out so the expansion does not change how likely any observed arrangement is (see
 * [WfcSymmetry] for what this is for and what it costs). `sampleIds` is remapped, so the returned
 * set still renders and reports as the original did; [patternWeights] is populated, so [toRules]
 * uses the shared-out weights rather than re-counting occurrences.
 *
 * ```
 * val patterns = source.sliceIntoOverlappingPatterns2D(w, h, 3, 3)
 *     .expandSymmetry(WfcSymmetry.All)      // <- terrain has no up
 * ```
 *
 * Rotations require square patterns; reflections do not. [WfcSymmetry.None] returns `this`.
 */
fun FloatOverlappingTileSet.expandSymmetry(symmetry: WfcSymmetry): FloatOverlappingTileSet {
    if (symmetry == WfcSymmetry.None) return this
    require(symmetry.rotations == 1 || patternWidth == patternHeight) {
        "${symmetry.name} needs square patterns to rotate, got ${patternWidth}x$patternHeight"
    }
    val base = patternWeights ?: weightsFromSampleIds(tileCount, sampleIds)
    val (expanded, weights, remap) = expandPatternsUnderSymmetry(
        patterns, base, symmetry,
        rotate = { rotatePatternFloats(it, patternWidth) },
        mirror = { mirrorPatternFloats(it, patternWidth, patternHeight) },
        key = { FloatArrayKey(it) }
    )
    return FloatOverlappingTileSet(
        patternWidth, patternHeight, expanded.toTypedArray(),
        IntArray(sampleIds.size) { remap[sampleIds[it]] },
        sampleWidthInPatterns, sampleHeightInPatterns, weights
    )
}

/** [FloatOverlappingTileSet.expandSymmetry] for ARGB pattern sets; see its KDoc and [WfcSymmetry]. */
fun PngOverlappingTileSet.expandSymmetry(symmetry: WfcSymmetry): PngOverlappingTileSet {
    if (symmetry == WfcSymmetry.None) return this
    require(symmetry.rotations == 1 || patternWidth == patternHeight) {
        "${symmetry.name} needs square patterns to rotate, got ${patternWidth}x$patternHeight"
    }
    val base = patternWeights ?: weightsFromSampleIds(tileCount, sampleIds)
    val (expanded, weights, remap) = expandPatternsUnderSymmetry(
        patterns, base, symmetry,
        rotate = { rotatePatternInts(it, patternWidth) },
        mirror = { mirrorPatternInts(it, patternWidth, patternHeight) },
        key = { IntArrayKey(it) }
    )
    return PngOverlappingTileSet(
        patternWidth, patternHeight, expanded.toTypedArray(),
        IntArray(sampleIds.size) { remap[sampleIds[it]] },
        sampleWidthInPatterns, sampleHeightInPatterns, weights
    )
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
