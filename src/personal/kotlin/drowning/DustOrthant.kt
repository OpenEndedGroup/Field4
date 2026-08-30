package drowning

import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The **Orthant Neighborhood Network** over a [DustField]: for each particle, the nearest other
 * particle in each of the eight sign-octants around it.
 *
 * ## Why this network and not k-nearest
 *
 * k-nearest neighbours over a random cloud is *directionally blind*. A particle sitting at the edge of
 * a local clump gets all k of its neighbours from the clump side and none from the open side, so the
 * spring network it induces has one-sided stiffness — pull the field and the clumps hold while the gaps
 * between them do nothing. An orthant network cannot do that: every octant is asked separately, so a
 * particle is always tied in eight directions if there is anything in them at all. The result is a
 * graph that behaves like a *volume* under load rather than like a set of beads, which is the whole
 * point of tethering particles to each other rather than only to their rest positions.
 *
 * It is also cheap and stable: eight nearest-in-a-cone searches over a uniform grid, no k to tune, and
 * a rest-length distribution that inherits the cloud's own spacing.
 *
 * ## Eight octants, six slots
 *
 * The network is directed and up to 8-valent; a [DustLinks] record holds [DustLayout.EDGES] = 6. The
 * gap is closed in [build] by taking the union of the directed edges (so `p -> q` also gives `q -> p`,
 * which is what makes the springs two-sided) and then keeping the six shortest per particle. Both
 * numbers are reported by [DustLinks.describe], because they are the two facts worth knowing about a
 * network before trusting it: how many of the 8·n possible directed edges existed, and how many
 * survived the trim.
 *
 * The trim is where symmetry can break — a particle whose six shortest do not include an edge that its
 * partner kept ends up with a one-sided spring. [DustOrthantSettings.mutualOnly] drops those instead;
 * it is off by default because the asymmetry costs a slow momentum drift that a rest tether absorbs
 * completely, and dropping edges costs connectivity that shows.
 *
 * ## Parallelism and the ring bound
 *
 * The search is a uniform grid, then one `IntStream.parallel()` pass over the particles — each particle
 * writes only its own eight slots, so there is nothing to synchronise. Rings of cells are scanned
 * outward from the particle's own cell; after ring `k` anything unscanned is at least `k * cell` away,
 * so the pass can stop as soon as every octant's best is inside that. Octants that are genuinely empty
 * (which every particle on the hull has) would otherwise scan the whole grid, so
 * [DustOrthantSettings.maxRings] caps it and [DustLinks.ringLimited] counts how often the cap was the
 * reason a search stopped. A non-zero count is not an error — on a cube it is exactly the hull — but a
 * *large* one means the grid is too fine for the cloud.
 */
object DustOrthant {

    /** Sign-octant of `d`, with zero counting as positive: bit 0 is x, bit 1 is y, bit 2 is z. */
    fun octant(dx: Float, dy: Float, dz: Float): Int =
        (if (dx < 0f) 1 else 0) or (if (dy < 0f) 2 else 0) or (if (dz < 0f) 4 else 0)

    /**
     * Build the network, trim it to [DustLayout.EDGES] slots per particle, and return it.
     *
     * Deterministic: the grid, the scan order and the tie-break (lower index wins an exactly equal
     * distance) are all fixed, so the same field gives the same network however many threads run.
     */
    fun build(field: DustField, settings: DustOrthantSettings = DustOrthantSettings()): DustLinks {
        val directed = directedEdges(field, settings)
        return trim(field, directed, settings)
    }

    /**
     * The raw directed network: `8 * count` entries, one per particle per octant, [DustLayout.NONE]
     * where the octant is empty.
     *
     * Exposed separately because the untrimmed network is the thing to look at when a relaxed field
     * misbehaves — [DustLinks] has already thrown two of the eight away.
     */
    fun directedEdges(field: DustField, settings: DustOrthantSettings = DustOrthantSettings()): DirectedNetwork {
        val n = field.count
        val grid = DustGrid(field, settings.cellsPerAxisHint)
        val nearest = IntArray(n * 8) { DustLayout.NONE }
        val nearestDistance = FloatArray(n * 8)
        val limited = java.util.concurrent.atomic.AtomicInteger()

        val body = { i: Int ->
            val px = field.position[i * 3]
            val py = field.position[i * 3 + 1]
            val pz = field.position[i * 3 + 2]
            val best = FloatArray(8) { Float.MAX_VALUE }
            val bestIndex = IntArray(8) { DustLayout.NONE }

            val cx = grid.cellOf(px, 0)
            val cy = grid.cellOf(py, 1)
            val cz = grid.cellOf(pz, 2)
            val maxRing = min(settings.maxRings, grid.maxRingFrom(cx, cy, cz))

            var ring = 0
            var stoppedEarly = false
            while (ring <= maxRing) {
                grid.forEachInRing(cx, cy, cz, ring) { j ->
                    if (j != i) {
                        val dx = field.position[j * 3] - px
                        val dy = field.position[j * 3 + 1] - py
                        val dz = field.position[j * 3 + 2] - pz
                        val d2 = dx * dx + dy * dy + dz * dz
                        val o = octant(dx, dy, dz)
                        // `<` not `<=`, and the scan visits lower cell indices first, so the lower
                        // particle index wins an exact tie and the network is order-independent.
                        if (d2 < best[o]) { best[o] = d2; bestIndex[o] = j }
                    }
                }
                // Everything outside ring `ring` is at least `ring * cell` away, so once every octant's
                // best is inside that bound nothing further can improve it.
                val bound = ring.toFloat() * grid.cell
                val bound2 = bound * bound
                if (ring > 0 && (0 until 8).all { best[it] <= bound2 }) { stoppedEarly = true; break }
                ring++
            }
            if (!stoppedEarly && ring > settings.maxRings) limited.incrementAndGet()

            for (o in 0 until 8) {
                nearest[i * 8 + o] = bestIndex[o]
                nearestDistance[i * 8 + o] = if (bestIndex[o] == DustLayout.NONE) 0f else sqrt(best[o])
            }
        }

        if (settings.parallel) IntStream.range(0, n).parallel().forEach { body(it) }
        else IntStream.range(0, n).forEach { body(it) }

        return DirectedNetwork(n, nearest, nearestDistance, grid.cell, limited.get())
    }

    /**
     * Fit the eight-octant network into [DustLayout.EDGES] slots per particle.
     *
     * **Own edges first, then borrowed.** A particle keeps its own orthant edges — the shortest six of
     * its up to eight — and only then fills any slot it has left over with edges that *other* particles
     * chose toward it. That order is the whole point and the first version of this got it wrong.
     *
     * Filling the slots from the symmetrised union and trimming by length, which is the obvious thing to
     * do, defeats the network: a particle in a dense region is *chosen* by many close neighbours, so its
     * six slots fill with their short edges and evict its own edges toward the sparse side. The result is
     * a graph that is directionally blind in exactly the way an orthant network exists to avoid, and it
     * is blind precisely where the cloud is uneven. Measured on 20k uniform random particles: the union
     * has ~117k undirected edges and only 60k fit, so half the network was being thrown away, and it was
     * the long half — the half that spans the gaps.
     *
     * Own-first keeps ~75% of the orthant edges instead, and every particle keeps its own directional
     * spread. The borrowed edges are pure gain: they cost nothing (the slots were empty) and they make
     * boundary particles' springs two-sided.
     *
     * [DustOrthantSettings.mutualOnly] then drops any edge the partner did not also keep, which makes the
     * graph exactly symmetric at the cost of some connectivity — see the flag's own note.
     */
    fun trim(
        field: DustField,
        directed: DirectedNetwork,
        settings: DustOrthantSettings = DustOrthantSettings()
    ): DustLinks {
        val n = field.count
        val slots = DustLayout.EDGES

        // Own: the particle's own orthant edges, shortest first, at most `slots` of them. Sorted by
        // (length, index) so the choice is deterministic whatever order the parallel search filled in.
        val own = Array(n) { i ->
            (0 until 8)
                .map { o -> directed.neighbour[i * 8 + o] }
                .filter { it != DustLayout.NONE }
                .distinct()
                .map { j -> j to field.distance(i, j) }
                .sortedWith(compareBy({ it.second }, { it.first }))
                .take(slots)
        }

        // Incoming: who chose me. Only consulted for slots `own` left empty.
        val incoming = Array(n) { java.util.TreeSet<Int>() }
        for (i in 0 until n) for ((j, _) in own[i]) incoming[j].add(i)

        val chosen = Array(n) { i ->
            val mine = own[i].toMutableList()
            val have = mine.map { it.first }.toMutableSet()
            if (mine.size < slots) {
                incoming[i]
                    .filter { it !in have }
                    .map { j -> j to field.distance(i, j) }
                    .sortedWith(compareBy({ it.second }, { it.first }))
                    .forEach { if (mine.size < slots && have.add(it.first)) mine.add(it) }
            }
            // Shortest-first across the whole row, so the shader's `edgeSlots` knob (honour only the
            // first N) drops the long springs rather than an arbitrary mix.
            mine.sortedWith(compareBy({ it.second }, { it.first }))
        }

        val neighbour = IntArray(n * slots) { DustLayout.NONE }
        val restLength = FloatArray(n * slots)
        val degree = IntArray(n)
        var ownSlots = 0
        var borrowedSlots = 0

        for (i in 0 until n) {
            val mineSet = own[i].map { it.first }.toSet()
            var s = 0
            for ((j, len) in chosen[i]) {
                if (settings.mutualOnly && chosen[j].none { it.first == i }) continue
                neighbour[i * slots + s] = j
                restLength[i * slots + s] = len * settings.restLengthScale
                if (j in mineSet) ownSlots++ else borrowedSlots++
                s++
            }
            degree[i] = s
        }

        val undirected = HashSet<Long>()
        for (i in 0 until n) for (o in 0 until 8) {
            val j = directed.neighbour[i * 8 + o]
            if (j != DustLayout.NONE) undirected.add(minOf(i, j).toLong() * n + maxOf(i, j))
        }

        return DustLinks(
            count = n,
            neighbour = neighbour,
            restLength = restLength,
            degree = degree,
            directedEdges = directed.edgeCount,
            unionEdges = undirected.size,
            ownSlots = ownSlots,
            borrowedSlots = borrowedSlots,
            cell = directed.cell,
            ringLimited = directed.ringLimited,
            mutualOnly = settings.mutualOnly
        )
    }

    /** The untrimmed, eight-per-particle network. */
    class DirectedNetwork(
        val count: Int,
        /** `8 * count`, indexed `[particle * 8 + octant]`, [DustLayout.NONE] where empty. */
        val neighbour: IntArray,
        /** Distance to each entry of [neighbour]; zero where absent. */
        val distance: FloatArray,
        /** The grid cell size the search used. */
        val cell: Float,
        /** How many particles stopped on [DustOrthantSettings.maxRings] rather than on the bound. */
        val ringLimited: Int
    ) {
        /** Directed edges that exist — at most `8 * count`. */
        val edgeCount: Int get() = neighbour.count { it != DustLayout.NONE }

        /** How often each octant found anything, for spotting a cloud that is really a plane. */
        fun octantOccupancy(): IntArray {
            val out = IntArray(8)
            for (i in 0 until count) for (o in 0 until 8)
                if (neighbour[i * 8 + o] != DustLayout.NONE) out[o]++
            return out
        }
    }
}

/**
 * How to build the network. The defaults are the ones a random cube wants; each field says what moving
 * it does.
 */
data class DustOrthantSettings(
    /**
     * Roughly how many grid cells along the longest axis. The search cost is a trade: too coarse and
     * every ring scans a crowd, too fine and a particle on the hull walks a lot of empty rings looking
     * for an octant that is not there. The default is derived from the particle count instead when
     * this is zero.
     */
    val cellsPerAxisHint: Int = 0,

    /**
     * Cap on how many rings of cells one particle will scan. Reached only by particles whose octants
     * are genuinely empty — the hull of the cloud — and counted in [DustLinks.ringLimited].
     */
    val maxRings: Int = 6,

    /** Multiplier on every rest length. Below 1 the network starts under tension and contracts. */
    val restLengthScale: Float = 1f,

    /**
     * Keep only edges that survived the six-slot trim at *both* ends.
     *
     * Off by default. On, the network is exactly symmetric and momentum is conserved; off, a handful of
     * one-sided springs remain and the field drifts very slowly, which the rest tether absorbs. Turn it
     * on when the field is meant to float free with `restGain` at zero.
     */
    val mutualOnly: Boolean = false,

    /** Run the per-particle search on the common pool. Off makes a timing comparison meaningful. */
    val parallel: Boolean = true
)

/**
 * The trimmed network, in exactly the shape [DustField.writeLinks] wants: [DustLayout.EDGES] slots per
 * particle, [DustLayout.NONE] in the unused ones.
 */
class DustLinks(
    val count: Int,
    /** `count * DustLayout.EDGES` neighbour indices, absent slots [DustLayout.NONE]. */
    val neighbour: IntArray,
    /** Rest length per slot; zero where the slot is absent. */
    val restLength: FloatArray,
    /** How many slots particle `i` actually uses — its degree in the trimmed network. */
    val degree: IntArray,
    /** Directed edges the orthant search found, before the trim. */
    val directedEdges: Int,
    /** Distinct undirected pairs the orthant search found — the whole network, before the trim. */
    val unionEdges: Int,
    /** Filled slots holding one of the particle's *own* orthant edges. */
    val ownSlots: Int,
    /** Filled slots that were spare and got an edge some other particle chose toward this one. */
    val borrowedSlots: Int,
    /** The grid cell the search used. */
    val cell: Float,
    /** Particles whose search stopped on the ring cap. */
    val ringLimited: Int,
    /** Whether the trim dropped one-sided edges. */
    val mutualOnly: Boolean
) {
    /** Undirected edges that survived the trim. Counts each pair once, so a one-sided edge counts half. */
    val edgeCount: Int get() = degree.sum() / 2

    /** Slots filled across the whole network — what the edge draw actually iterates. */
    val slotCount: Int get() = degree.sum()

    /** Mean rest length over the filled slots, or zero when there are none. */
    fun meanRestLength(): Float {
        var sum = 0.0
        var n = 0
        for (i in restLength.indices) if (neighbour[i] != DustLayout.NONE) { sum += restLength[i]; n++ }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    /** Mean rest length of particle [i]'s filled slots — `lenB.z` in the record, for shader use. */
    fun meanLength(i: Int): Float {
        var sum = 0f
        var n = 0
        for (s in 0 until DustLayout.EDGES) {
            val k = i * DustLayout.EDGES + s
            if (neighbour[k] != DustLayout.NONE) { sum += restLength[k]; n++ }
        }
        return if (n == 0) 0f else sum / n
    }

    /** Particles with no edges at all. Should be zero on anything but a single point. */
    fun isolated(): Int = degree.count { it == 0 }

    /** How many particles have each degree, `0..EDGES`. */
    fun degreeHistogram(): IntArray {
        val out = IntArray(DustLayout.EDGES + 1)
        degree.forEach { out[it]++ }
        return out
    }

    /**
     * Whether the network is symmetric: every edge present at both ends.
     *
     * True by construction when [mutualOnly]; the useful case is checking how *close* to symmetric the
     * default union-then-trim came, which [describe] reports as the asymmetric slot count.
     */
    fun asymmetricSlots(): Int {
        var bad = 0
        for (i in 0 until count) for (s in 0 until DustLayout.EDGES) {
            val j = neighbour[i * DustLayout.EDGES + s]
            if (j == DustLayout.NONE) continue
            val back = (0 until DustLayout.EDGES).any { neighbour[j * DustLayout.EDGES + it] == i }
            if (!back) bad++
        }
        return bad
    }

    /**
     * How much of the orthant network survived — the number to look at before trusting a field.
     *
     * `retained` is the fraction of the search's own directed edges that made it into a slot. Below about
     * two thirds means the six slots are the binding constraint on the network rather than the cloud is,
     * and the field will behave like a sparser graph than the octant search found.
     */
    fun describe(): String {
        val h = degreeHistogram()
        val retained = if (directedEdges == 0) 0.0 else ownSlots.toDouble() / directedEdges
        return "onn: $directedEdges directed edges (of ${count * 8} octants, $unionEdges distinct pairs) " +
            "-> $slotCount slots: $ownSlots own + $borrowedSlots borrowed, $edgeCount undirected" +
            (if (mutualOnly) " [mutual only]" else "") + "\n" +
            "     retained %.0f%% of the search, degree ".format(retained * 100) +
            h.indices.joinToString(" ") { "$it:${h[it]}" } +
            ", mean rest length %.4f, cell %.4f".format(meanRestLength(), cell) +
            (if (isolated() > 0) ", ${isolated()} ISOLATED" else "") +
            (if (ringLimited > 0) ", $ringLimited hit the ring cap" else "") +
            (if (!mutualOnly && asymmetricSlots() > 0) ", ${asymmetricSlots()} one-sided slots" else "")
    }
}

/**
 * A uniform grid over a [DustField], for the octant search and nothing else.
 *
 * CSR: [start] is a prefix sum over cell occupancy and [items] holds the particle indices in cell
 * order, so a cell's contents are one contiguous range and the whole structure is two `IntArray`s.
 * Built serially — it is two passes over the points and is not where the time goes.
 */
internal class DustGrid(field: DustField, cellsPerAxisHint: Int) {
    val min = FloatArray(3)
    val dim = IntArray(3)
    val cell: Float
    internal val start: IntArray
    internal val items: IntArray

    init {
        val b = field.bounds()
        for (a in 0 until 3) min[a] = b[a]
        val span = floatArrayOf(
            max(b[3] - b[0], 1e-6f), max(b[4] - b[1], 1e-6f), max(b[5] - b[2], 1e-6f)
        )
        val longest = max(span[0], max(span[1], span[2]))
        // Aim at a handful of particles per cell: n^(1/3) cells along the longest axis puts about one
        // particle in each, which makes ring 1 (27 cells) a ~27-particle scan and ring 2 rarely needed.
        val cells = if (cellsPerAxisHint > 0) cellsPerAxisHint
        else max(1, Math.cbrt(field.count.toDouble()).toInt())
        cell = longest / cells
        for (a in 0 until 3) dim[a] = max(1, (span[a] / cell).toInt() + 1)

        val cellCount = dim[0] * dim[1] * dim[2]
        val counts = IntArray(cellCount + 1)
        val of = IntArray(field.count)
        for (i in 0 until field.count) {
            val c = index(
                cellOf(field.position[i * 3], 0),
                cellOf(field.position[i * 3 + 1], 1),
                cellOf(field.position[i * 3 + 2], 2)
            )
            of[i] = c
            counts[c + 1]++
        }
        for (c in 0 until cellCount) counts[c + 1] += counts[c]
        start = counts
        items = IntArray(field.count)
        val cursor = counts.copyOf()
        for (i in 0 until field.count) items[cursor[of[i]]++] = i
    }

    fun cellOf(v: Float, axis: Int): Int =
        ((v - min[axis]) / cell).toInt().coerceIn(0, dim[axis] - 1)

    internal fun index(x: Int, y: Int, z: Int): Int = (z * dim[1] + y) * dim[0] + x

    /** The largest ring that can still contain a cell, from a given cell. */
    fun maxRingFrom(x: Int, y: Int, z: Int): Int = maxOf(
        max(x, dim[0] - 1 - x), max(y, dim[1] - 1 - y), max(z, dim[2] - 1 - z)
    )

    /**
     * Every particle in the Chebyshev ring [ring] around cell `(x,y,z)` — the shell of cells at exactly
     * that distance, so ring 0 is the cell itself and no cell is ever visited twice across rings.
     */
    internal inline fun forEachInRing(x: Int, y: Int, z: Int, ring: Int, body: (Int) -> Unit) {
        val x0 = max(0, x - ring); val x1 = min(dim[0] - 1, x + ring)
        val y0 = max(0, y - ring); val y1 = min(dim[1] - 1, y + ring)
        val z0 = max(0, z - ring); val z1 = min(dim[2] - 1, z + ring)
        for (cz in z0..z1) for (cy in y0..y1) for (cx in x0..x1) {
            val onShell = ring == 0 ||
                cx == x - ring || cx == x + ring ||
                cy == y - ring || cy == y + ring ||
                cz == z - ring || cz == z + ring
            if (!onShell) continue
            val c = (cz * dim[1] + cy) * dim[0] + cx
            for (k in start[c] until start[c + 1]) body(items[k])
        }
    }
}
