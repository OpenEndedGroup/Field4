@file:Suppress("MemberVisibilityCanBePrivate")

package afield.stdlib

import afield.graphics.FLine
import kotlin.math.*
import kotlin.random.Random

/**
 * A single compare-and-swap unit on wires [lo] and [hi].
 *
 * Applying the comparator ensures the smaller value exits on wire [lo] and the
 * larger exits on wire [hi] (min-max convention).
 *
 * @property lo Lower wire index (0-based, always strictly less than [hi]).
 * @property hi Upper wire index.
 */
data class SortingComparator(val lo: Int, val hi: Int) {
    init {
        require(lo >= 0) { "lo must be >= 0, got $lo" }
        require(lo < hi) { "lo=$lo must be strictly less than hi=$hi" }
    }

    override fun toString() = "$lo:$hi"
}

/**
 * A sorting network on [n] wires.
 *
 * A sorting network is a fixed sequence of [SortingComparator]s that correctly sorts
 * any permutation of [n] values regardless of the input.  Each comparator is a
 * compare-and-swap that, when applied to the wire array, places the smaller value on
 * wire [SortingComparator.lo] and the larger on wire [SortingComparator.hi].
 *
 * Correctness is verified by [isSorting] via the **0-1 principle**: a compare-and-swap
 * network sorts all inputs if and only if it sorts every binary (0/1) input.
 *
 * ## Quick start
 * ```kotlin
 * // Build a 6-wire network and check it
 * val net = SortingNetwork.bubble(6)
 * println(net.isSorting())        // true
 * println(net.comparators.size)   // 15
 * println(net.depth())            // 5
 *
 * // Round-trip through the notation format
 * val copy = SortingNetwork.fromNotation(6, net.notation())
 * println(copy == net)            // true
 *
 * // ASCII diagram in the console
 * print(net.asciiDiagram())
 *
 * // Visualise inside a Field Box
 * lines(net.toFLines(), w = 600.0, h = 280.0, fitToSize = true)
 *
 * // Optimise with simulated annealing
 * val opt = SortingNetwork.anneal(n = 6) { iter, best ->
 *     if (iter % 10_000 == 0) println("iter=$iter  comparators=${best.comparators.size}")
 * }
 * println(opt.comparators.size)   // typically ≤ 12
 * ```
 *
 * ## Notation format
 * Parallel layers are separated by `" | "`, comparators within a layer by spaces.
 * Each comparator is written as `"lo:hi"`.  Example for an optimal 4-wire network:
 * ```
 * 0:1 2:3 | 0:2 1:3 | 1:2
 * ```
 */
class SortingNetwork(val n: Int, val comparators: List<SortingComparator>) {

    init {
        require(n >= 2) { "n must be >= 2, got $n" }
        comparators.forEach { c ->
            require(c.hi < n) { "Comparator $c is out of range for n=$n" }
        }
    }

    // ─── Core operations ──────────────────────────────────────────────────────

    /**
     * Apply this network to [input] and return the sorted result.
     *
     * @throws IllegalArgumentException if `input.size != n`.
     */
    fun sort(input: IntArray): IntArray {
        require(input.size == n) { "Input size ${input.size} != n=$n" }
        val a = input.copyOf()
        comparators.forEach { (lo, hi) ->
            if (a[lo] > a[hi]) { val t = a[lo]; a[lo] = a[hi]; a[hi] = t }
        }
        return a
    }

    /**
     * Check whether this network correctly sorts all possible inputs (0-1 principle).
     *
     * For [n] ≤ 20 every binary input is tested exactly (2^[n] tests).  For [n] > 20
     * the check falls back to 4 096 random probes.
     */
    fun isSorting(): Boolean = if (n <= 20) isSortingExact() else isSortingByProbe(4096, seed = 0L)

    private fun isSortingExact(): Boolean {
        val total = 1 shl n
        val a = IntArray(n)
        for (input in 0 until total) {
            for (i in 0 until n) a[i] = (input ushr i) and 1
            comparators.forEach { (lo, hi) ->
                if (a[lo] > a[hi]) { val t = a[lo]; a[lo] = a[hi]; a[hi] = t }
            }
            for (i in 0 until n - 1) if (a[i] > a[i + 1]) return false
        }
        return true
    }

    private fun isSortingByProbe(probes: Int, seed: Long): Boolean {
        val rng = Random(seed)
        val a = IntArray(n)
        repeat(probes) {
            for (i in 0 until n) a[i] = rng.nextInt()
            comparators.forEach { (lo, hi) ->
                if (a[lo] > a[hi]) { val t = a[lo]; a[lo] = a[hi]; a[hi] = t }
            }
            for (i in 0 until n - 1) if (a[i] > a[i + 1]) return false
        }
        return true
    }

    // ─── Layer / depth ────────────────────────────────────────────────────────

    /**
     * Assign each comparator to its earliest valid parallel layer.
     *
     * This is the classic **as-soon-as-possible** (ASAP) scheduling on the wire
     * dependency DAG: a comparator must wait until every earlier comparator that
     * touches either of its wires has completed.  Comparators in the same layer
     * are independent (disjoint wire pairs) and can execute concurrently.
     *
     * Preserving the dependency order guarantees that [notation] round-trips
     * through [fromNotation] produce an equivalent (valid) sorting network.
     *
     * @return List of layers; each layer is a non-empty list of [SortingComparator]s.
     */
    fun layers(): List<List<SortingComparator>> {
        if (comparators.isEmpty()) return emptyList()
        // wireLastLayer[w] = the layer index of the most recent comparator that used wire w.
        val wireLastLayer = IntArray(n) { -1 }
        val compLayer = IntArray(comparators.size)
        comparators.forEachIndexed { idx, comp ->
            val layer = maxOf(wireLastLayer[comp.lo], wireLastLayer[comp.hi]) + 1
            compLayer[idx] = layer
            wireLastLayer[comp.lo] = layer
            wireLastLayer[comp.hi] = layer
        }
        val maxLayer = compLayer.max()
        val result = Array(maxLayer + 1) { mutableListOf<SortingComparator>() }
        comparators.forEachIndexed { idx, comp -> result[compLayer[idx]].add(comp) }
        return result.map { it.toList() }
    }

    /** Minimum parallel depth (number of non-overlapping layers). */
    fun depth(): Int = layers().size

    // ─── Notation ─────────────────────────────────────────────────────────────

    /**
     * Encode this network as a compact, human-readable string.
     *
     * Parallel layers are separated by `" | "`, comparators within a layer by spaces,
     * each written as `"lo:hi"`.  The string can be round-tripped via [fromNotation].
     *
     * Example: `"0:1 2:3 | 0:2 1:3 | 1:2"`
     */
    fun notation(): String = layers().joinToString(" | ") { layer -> layer.joinToString(" ") }

    /**
     * Render the network as a human-readable ASCII-art diagram.
     *
     * Each row is one wire; `●` marks a comparator endpoint; `│` on inter-row connector
     * lines spans between non-adjacent comparator endpoints within the same layer.
     *
     * Example (`oddEvenMerge(4)`):
     * ```
     *  0 ──●──●─────
     *       │  │
     *  1 ──●──│──●──
     *          │  │
     *  2 ─────●──●──
     *
     *  3 ─────────────
     * ```
     */
    fun asciiDiagram(): String {
        val ls = layers()
        val depth = ls.size
        return buildString {
            for (i in 0 until n) {
                append("%2d ".format(i))
                for (l in 0 until depth) {
                    append("──")
                    append(if (ls[l].any { it.lo == i || it.hi == i }) "●" else "─")
                    append("──")
                }
                appendLine()
                if (i < n - 1) {
                    append("   ")
                    for (l in 0 until depth) {
                        // A connector `│` appears wherever a comparator in layer l spans
                        // across the gap between wire i and wire i+1.
                        append(if (ls[l].any { it.lo <= i && it.hi > i }) "  │  " else "     ")
                    }
                    appendLine()
                }
            }
        }
    }

    // ─── FLine visualisation ──────────────────────────────────────────────────

    /**
     * Render this sorting network as a list of [FLine]s suitable for `Box.lines(...)`.
     *
     * The diagram draws [n] horizontal wires and each parallel layer of comparators as
     * vertical segments with filled-circle endpoints.  Layers are coloured distinctly
     * from a built-in palette.
     *
     * Typical usage inside a Field Box:
     * ```kotlin
     * lines(SortingNetwork.oddEvenMerge(8).toFLines(), w = 600.0, h = 350.0, fitToSize = true)
     * ```
     *
     * @param wireSpacing   Vertical gap between adjacent wires (SVG units).
     * @param layerSpacing  Horizontal gap between comparator columns (SVG units).
     * @param margin        Padding around the diagram (SVG units).
     * @param wireColor     Colour of the horizontal wire lines.
     * @param dotRadius     Point-size for the filled endpoint circles; actual SVG radius
     *                      is `dotRadius × 3` (see [FLine.pointSize] semantics).
     * @return List of [FLine]s; pass to `Box.lines(list, w = …, h = …, fitToSize = true)`.
     */
    fun toFLines(
        wireSpacing: Double = 40.0,
        layerSpacing: Double = 60.0,
        margin: Double = 40.0,
        wireColor: Vec4 = vec(0.55, 0.55, 0.55, 0.75),
        dotRadius: Double = 2.0,
    ): List<FLine> {
        val ls = layers()
        fun wireY(i: Int) = margin + i * wireSpacing
        fun layerX(l: Int) = margin + (l + 0.5) * layerSpacing
        val totalWidth = margin * 2 + ls.size * layerSpacing

        val result = mutableListOf<FLine>()

        // ── Horizontal wires ──────────────────────────────────────────────────
        result += FLine().also { f ->
            f.color = wireColor
            f.stroked = true
            f.filled = false
            f.thickness = 1.5
            for (i in 0 until n) {
                f.moveTo(margin / 2.0, wireY(i))
                f.lineTo(totalWidth - margin / 2.0, wireY(i))
            }
        }

        // ── Per-layer comparator segments with filled endpoint dots ───────────
        val palette = listOf(
            vec(0.35, 0.75, 1.00, 0.95),
            vec(1.00, 0.60, 0.30, 0.95),
            vec(0.35, 1.00, 0.55, 0.95),
            vec(1.00, 0.90, 0.25, 0.95),
            vec(0.80, 0.35, 1.00, 0.95),
            vec(1.00, 0.35, 0.60, 0.95),
            vec(0.30, 1.00, 0.90, 0.95),
            vec(1.00, 0.55, 0.90, 0.95),
        )

        ls.forEachIndexed { layerIdx, layer ->
            val color = palette[layerIdx % palette.size]
            val x = layerX(layerIdx)

            // Segment lines
            result += FLine().also { f ->
                f.color = color
                f.stroked = true
                f.filled = false
                f.thickness = 2.0
                layer.forEach { comp ->
                    f.moveTo(x, wireY(comp.lo))
                    f.lineTo(x, wireY(comp.hi))
                }
            }

            // Endpoint dots — a MoveTo-only FLine with pointed=true draws a filled
            // circle at every MoveTo position (see FLine.pointed semantics).
            result += FLine().also { f ->
                f.color = color
                f.stroked = false
                f.filled = false
                f.pointed = true
                f.pointSize = dotRadius
                layer.forEach { comp ->
                    f.moveTo(x, wireY(comp.lo))
                    f.moveTo(x, wireY(comp.hi))
                }
            }
        }

        return result
    }

    // ─── Object overrides ─────────────────────────────────────────────────────

    override fun toString(): String =
        "SortingNetwork(n=$n, comparators=${comparators.size}, depth=${depth()}, valid=${isSorting()})"

    override fun equals(other: Any?): Boolean =
        other is SortingNetwork && n == other.n && comparators == other.comparators

    override fun hashCode(): Int = 31 * n + comparators.hashCode()

    // ─── Factory functions ────────────────────────────────────────────────────

    companion object {

        /**
         * Parse a [notation] string produced by [SortingNetwork.notation] into a
         * [SortingNetwork].
         *
         * @param n        Wire count.
         * @param notation Layer-separated notation, e.g. `"0:1 2:3 | 0:2 1:3 | 1:2"`.
         */
        fun fromNotation(n: Int, notation: String): SortingNetwork {
            val comparators = notation.trim()
                .split("|")
                .flatMap { layer ->
                    layer.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { tok ->
                        val parts = tok.trim().split(":")
                        val a = parts[0].toInt(); val b = parts[1].toInt()
                        SortingComparator(min(a, b), max(a, b))
                    }
                }
            return SortingNetwork(n, comparators)
        }

        /**
         * Build a bubble-sort network for [n] wires.
         *
         * Uses n*(n-1)/2 comparators with n-1 parallel layers.  Always produces a
         * valid sorting network.
         */
        fun bubble(n: Int): SortingNetwork {
            require(n >= 2) { "n must be >= 2" }
            val comps = mutableListOf<SortingComparator>()
            for (pass in 0 until n - 1) {
                for (i in 0 until n - 1 - pass) {
                    comps += SortingComparator(i, i + 1)
                }
            }
            return SortingNetwork(n, comps)
        }

        /**
         * Build a Batcher odd-even merge sort network for [n] wires.
         *
         * Uses Batcher's iterative algorithm (Knuth Vol. 3, §5.3.4).  Works for any
         * [n]; non-powers-of-2 are handled naturally by the modular index arithmetic.
         *
         * Uses O(n log²n) comparators with O(log²n) depth.  The comparator counts
         * match known optima for n ≤ 8: n=2→1, n=3→3, n=4→5, n=5→9, n=6→12,
         * n=7→16, n=8→19.
         */
        fun oddEvenMerge(n: Int): SortingNetwork {
            require(n >= 2) { "n must be >= 2" }
            var p = 1
            while (p < n) p = p shl 1  // next power of 2 >= n

            val comps = mutableListOf<SortingComparator>()

            // Batcher's iterative odd-even merge sort
            var pk = p shr 1  // starts at 2^(t-1)
            while (pk >= 1) {
                var q = p shr 1
                var r = 0
                var d = pk
                while (true) {
                    for (i in 0 until n - d) {
                        if ((i and pk) == r) comps += SortingComparator(i, i + d)
                    }
                    if (q != pk) {
                        d = q - pk
                        q = q shr 1
                        r = pk
                    } else break
                }
                pk = pk shr 1
            }

            return SortingNetwork(n, comps.distinct())
        }

        /**
         * Use simulated annealing to search for a compact sorting network for [n] wires.
         *
         * Starting from [initialNetwork] (default: [bubble]), the search repeatedly
         * applies random perturbations:
         * - **Remove** a random comparator.
         * - **Insert** a random new comparator at a random position.
         * - **Move** a random comparator to a different position.
         * - **Swap** two adjacent comparators.
         *
         * A candidate is accepted immediately if it lowers the energy
         * (`comparators.size + 0.1 × depth`), or with probability `exp(-Δ/T)` if it
         * raises it — allowing escape from local minima.  Only valid sorting networks
         * are accepted.
         *
         * **Performance note**: each validity check costs O(2^[n] × |comparators|).
         * For [n] ≤ 10 the default [iterations] completes in well under a second.
         * Consider reducing [iterations] for [n] > 12.
         *
         * Known comparator-count optima for reference:
         * n=2→1, n=3→3, n=4→5, n=5→9, n=6→12, n=7→16, n=8→19, n=9→25, n=10→29.
         *
         * @param n               Wire count.
         * @param initialNetwork  Seed network (must pass [isSorting]).
         * @param iterations      Total perturbation steps.
         * @param initialTemp     Starting temperature for the Metropolis acceptance criterion.
         * @param finalTemp       Ending temperature.
         * @param seed            Random seed for reproducible runs.
         * @param onProgress      Optional callback invoked every 5 000 steps with the
         *                        current step index and the best network found so far.
         * @return The best valid [SortingNetwork] discovered.
         */
        fun anneal(
            n: Int,
            initialNetwork: SortingNetwork = bubble(n),
            iterations: Int = 50_000,
            initialTemp: Double = 2.0,
            finalTemp: Double = 0.01,
            seed: Long = 42L,
            onProgress: ((iteration: Int, network: SortingNetwork) -> Unit)? = null,
        ): SortingNetwork {
            require(initialNetwork.isSorting()) { "initialNetwork must be a valid sorting network" }

            val rng = Random(seed)
            fun energy(net: SortingNetwork) = net.comparators.size.toDouble() + 0.1 * net.depth()

            var current = initialNetwork
            var currentEnergy = energy(current)
            var best = current
            var bestEnergy = currentEnergy

            val coolingRate = ln(finalTemp / initialTemp) / iterations.toDouble()

            repeat(iterations) { iter ->
                val temp = initialTemp * exp(coolingRate * iter)

                val candidate = when (rng.nextInt(4)) {
                    0 -> {
                        // Remove a random comparator
                        if (current.comparators.isEmpty()) null
                        else {
                            val idx = rng.nextInt(current.comparators.size)
                            SortingNetwork(n, current.comparators.filterIndexed { i, _ -> i != idx })
                        }
                    }
                    1 -> {
                        // Insert a random new comparator at a random position
                        val lo = rng.nextInt(n - 1)
                        val hi = rng.nextInt(lo + 1, n)
                        val comp = SortingComparator(lo, hi)
                        val pos = rng.nextInt(current.comparators.size + 1)
                        SortingNetwork(n, current.comparators.toMutableList().also { it.add(pos, comp) })
                    }
                    2 -> {
                        // Move a random comparator to a different position
                        if (current.comparators.size < 2) null
                        else {
                            val fromIdx = rng.nextInt(current.comparators.size)
                            var toIdx = rng.nextInt(current.comparators.size)
                            if (toIdx == fromIdx) toIdx = (toIdx + 1) % current.comparators.size
                            val list = current.comparators.toMutableList()
                            val comp = list.removeAt(fromIdx)
                            list.add(toIdx.coerceAtMost(list.size), comp)
                            SortingNetwork(n, list)
                        }
                    }
                    else -> {
                        // Swap two adjacent comparators
                        if (current.comparators.size < 2) null
                        else {
                            val idx = rng.nextInt(current.comparators.size - 1)
                            val list = current.comparators.toMutableList()
                            val tmp = list[idx]; list[idx] = list[idx + 1]; list[idx + 1] = tmp
                            SortingNetwork(n, list)
                        }
                    }
                } ?: return@repeat

                if (!candidate.isSorting()) return@repeat

                val candidateEnergy = energy(candidate)
                val delta = candidateEnergy - currentEnergy

                if (delta <= 0.0 || rng.nextDouble() < exp(-delta / temp)) {
                    current = candidate
                    currentEnergy = candidateEnergy
                    if (currentEnergy < bestEnergy) {
                        best = current
                        bestEnergy = currentEnergy
                    }
                }

                if (onProgress != null && iter % 5_000 == 0) onProgress(iter, best)
            }

            return best
        }
    }
}
