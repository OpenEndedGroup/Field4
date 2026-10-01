@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Data")

package afield.stdlib

import kotlin.math.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Time-varying point-and-edge systems: point clouds that move every frame while the graph joining
 * them is continually reinforced and decayed. State lives in [DynamicPointState] (points, optional
 * `Vec4` labels, a clock), edges in [DynamicEdgeList] (weight / age / ttl per edge, thinned by
 * [DynamicEdgeList.decayLinear] or [DynamicEdgeList.decayExponential]), and neighbour queries go
 * through [Vec3SpatialHash], which is rebuilt each step because the cloud moves.
 *
 * There are two extension points. A [DynamicGraphSystem] only adds and reinforces edges —
 * [Vec3BreathingAnisotropicKnnSystem], [Vec3FlowlineAdvectionGraphSystem],
 * [Vec3LocalTangentTriangulationSystem], [Vec3PersistentProximityHysteresisSystem],
 * [Vec3SignalRoutingGraphSystem], [Vec3AlphaEventHypergraphCollapseSystem]. A
 * [DynamicPointProcess] moves, adds and removes points against [DynamicPointKinetics]
 * (velocity / resource / alive) and several are composed in order by [DynamicProcessPipeline].
 *
 * Drive one frame with [stepDynamicGraph] or its decaying variants; [dynamicGraphSnapshot] and
 * [DynamicEdgeList.toPointGraph] convert the live edge store into the static `PointGraph` the rest
 * of the graph library expects, and [exampleDynamicMorphogenesisStep] wires a whole frame
 * together. Pure Kotlin, single-threaded, no external dependency.
 *
 * **The population is not fixed, and three things have to agree about its size**: `state.points`,
 * [DynamicPointKinetics] (via [DynamicPointKinetics.ensureSize]) and [DynamicEdgeList.pointCount]
 * (via [DynamicEdgeList.ensurePointCount] / [DynamicEdgeList.setPointCount]). [stepDynamicGraph],
 * [DynamicProcessPipeline.step] and [exampleDynamicMorphogenesisStep] keep all three in step for
 * you; a hand-written [DynamicPointProcess] that appends a point *and* joins an edge to it in the
 * same call has to widen the store itself. See [DynamicEdgeList] for what happens when it does not.
 */

/**
 * Dynamic point state for time-varying graph systems.
 *
 * Keeps your existing Vec3 point list plus optional labels and time.
 */
data class DynamicPointState(
    val points: MutableList<Vec3>,
    val labels: List<Vec4>? = null,
    var time: Float = 0f
) {
    val pointCount: Int get() = points.size
}

/**
 * Edge with dynamic state. This is separate from GraphEdge so the original static graph API stays clean.
 */
data class DynamicGraphEdge(
    val i: Int,
    val j: Int,
    var weight: Double,
    var age: Float = 0f,
    var ttl: Float = 0f,
    var flags: Int = 0
) {
    fun toGraphEdge(): GraphEdge = GraphEdge(i, j, weight)
}

fun dynamicGraphSnapshot(
    dynamicEdges: Collection<DynamicGraphEdge>,
    pointCount: Int,
    minWeight: Double = 0.0
): PointGraph {
    return PointGraph(
        dynamicEdges
            .asSequence()
            .filter { it.weight > minWeight }
            .map { GraphEdge(it.i, it.j, it.weight) }
            .toList(),
        pointCount
    )
}

/**
 * Evolving edge store. Undirected by default to match GraphEdge equality semantics.
 *
 * [pointCount] is the store's **index space**, and it has to follow the cloud rather than the cloud's
 * size at construction: three of the [DynamicPointProcess]es append points and two of them remove
 * points. It used to be a constructor `val`, which made the whole process half of this file unable to
 * complete a run — the first birth in [DirectedTipGrowthProcess] reinforces `parent -> newIndex` in
 * the same step it creates the child, and [getOrCreate]'s range check threw. Grow it with
 * [ensurePointCount] after adding points; set it exactly with [setPointCount] after compacting.
 * [stepDynamicGraph] and [DynamicProcessPipeline.step] both call [ensurePointCount] for you, so the
 * only code that has to think about this is a process that adds a point *and* an edge to it.
 */
class DynamicEdgeList(
    pointCount: Int,
    val undirected: Boolean = true
) {
    /** The index space: every edge endpoint must lie in `0 until pointCount`. */
    var pointCount: Int = pointCount
        private set

    private val edges = LinkedHashMap<Long, DynamicGraphEdge>()

    // Incidence index for `incident()`, rebuilt lazily whenever `version` has moved. Every mutator
    // below bumps `version`; the index is O(V + E) to build and is what makes a per-point neighbour
    // walk linear instead of quadratic. See `incident`.
    private var version = 0L
    private var indexedVersion = -1L
    private var incidence: Array<MutableList<DynamicGraphEdge>>? = null

    private fun edgeKey(a: Int, b: Int): Long {
        val lo = min(a, b).toLong() and 0xffffffffL
        val hi = max(a, b).toLong() and 0xffffffffL
        return if (undirected) {
            (lo shl 32) or hi
        } else {
            ((a.toLong() and 0xffffffffL) shl 32) or (b.toLong() and 0xffffffffL)
        }
    }

    fun values(): Collection<DynamicGraphEdge> = edges.values

    fun get(a: Int, b: Int): DynamicGraphEdge? = edges[edgeKey(a, b)]

    /**
     * Widen the index space to at least [n]. Never narrows, so it is always safe: call it right after
     * appending points to a [DynamicPointState] and before joining an edge to the new index.
     */
    fun ensurePointCount(n: Int) {
        require(n >= 0) { "DynamicEdgeList.ensurePointCount: n must be >= 0, found $n" }
        if (n > pointCount) {
            pointCount = n
            version++
        }
    }

    /**
     * Set the index space to exactly [n], which is what a *compaction* needs — after
     * `removeDeadPointsInPlace` has renumbered the survivors, a store still claiming the old count
     * makes [toPointGraph] hand every traversal downstream a `PointGraph` full of vertices that no
     * longer exist.
     *
     * Refuses to narrow past a live edge rather than leaving one dangling.
     */
    fun setPointCount(n: Int) {
        require(n >= 0) { "DynamicEdgeList.setPointCount: n must be >= 0, found $n" }
        val dangling = edges.values.firstOrNull { it.i >= n || it.j >= n }
        require(dangling == null) {
            "DynamicEdgeList.setPointCount: edge (${dangling?.i}, ${dangling?.j}) would dangle outside 0 until $n"
        }
        if (n != pointCount) {
            pointCount = n
            version++
        }
    }

    /**
     * Every edge with [index] as an endpoint, in O(1) after an O(V + E) index build that is shared by
     * every call until the store next changes.
     *
     * The reason this is on the store rather than a `values().filter { … }` at the call site — which is
     * what the five processes in this file used to do — is that the filter is O(E) *per point*, so a
     * process that walks each point's neighbours is O(V·E) per step. Under
     * [exampleDynamicMorphogenesisStep], whose population grows, that alone made the whole pipeline
     * unrunnable: a 40-point ring stepped past ten seconds of clip went from "finishes" to "still
     * running after ten minutes" once the growth defect above was fixed and the cloud could actually
     * grow.
     *
     * The returned lists hold the live [DynamicGraphEdge] objects, so mutating an edge's weight
     * through one of them is intended and does not invalidate anything. *Adding or removing* an edge
     * does, and is detected.
     */
    fun incident(index: Int): List<DynamicGraphEdge> {
        require(index in 0 until pointCount) { "DynamicEdgeList.incident: index out of range: $index" }
        return incidenceIndex()[index]
    }

    private fun incidenceIndex(): Array<MutableList<DynamicGraphEdge>> {
        val cached = incidence
        if (cached != null && indexedVersion == version) return cached
        val built = Array(pointCount) { mutableListOf<DynamicGraphEdge>() }
        for (e in edges.values) {
            built[e.i].add(e)
            if (e.j != e.i) built[e.j].add(e)
        }
        incidence = built
        indexedVersion = version
        return built
    }

    fun getOrCreate(a: Int, b: Int, initialWeight: Double = 0.0, ttl: Float = 0f): DynamicGraphEdge {
        require(a in 0 until pointCount) { "DynamicEdgeList.getOrCreate: a out of range: $a" }
        require(b in 0 until pointCount) { "DynamicEdgeList.getOrCreate: b out of range: $b" }

        val key = edgeKey(a, b)
        edges[key]?.let { return it }
        val i0: Int
        val j0: Int
        if (undirected && a > b) {
            i0 = b
            j0 = a
        } else {
            i0 = a
            j0 = b
        }
        val created = DynamicGraphEdge(i0, j0, initialWeight, 0f, ttl, 0)
        edges[key] = created
        version++
        return created
    }

    fun reinforce(a: Int, b: Int, addWeight: Double, ttl: Float, maxWeight: Double) {
        val e = getOrCreate(a, b, 0.0, ttl)
        e.weight = min(maxWeight, e.weight + addWeight)
        e.ttl = max(e.ttl, ttl)
    }

    fun clear() {
        if (edges.isNotEmpty()) version++
        edges.clear()
    }

    fun decayLinear(dt: Float, weightDecayPerSecond: Double) {
        val it = edges.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.age += dt
            e.ttl -= dt
            e.weight = max(0.0, e.weight - weightDecayPerSecond * dt)
            if (e.ttl <= 0f || e.weight <= 0.0) {
                it.remove()
                version++
            }
        }
    }

    fun decayExponential(dt: Float, lambda: Double) {
        val factor = exp(-lambda * dt.toDouble())
        val it = edges.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.age += dt
            e.ttl -= dt
            e.weight *= factor
            if (e.ttl <= 0f || e.weight <= 1e-9) {
                it.remove()
                version++
            }
        }
    }

    /**
     * Snapshot into your existing static PointGraph type.
     */
    fun toPointGraph(minWeight: Double = 0.0): PointGraph {
        val snapshot = edges.values
            .asSequence()
            .filter { it.weight > minWeight }
            .map { it.toGraphEdge() }
            .toList()
        return PointGraph(snapshot, pointCount)
    }
}

/**
 * Spatial hash for dynamic local-neighborhood queries.
 * This is the best fit here because the point cloud is expected to move each frame.
 *
 * **Every query here is required to return exactly what brute force would**, which is not a free
 * property of a cell hash and was not true of this one; `DynamicGraphSystemsTest` pins all three
 * against an O(N²) oracle. Two things have to be got right, and both were wrong:
 *
 *  - **The bucket key is lossy.** [hash] XORs three multiplied cell indices into a `Long`, so two
 *    different cells can land in the same bucket. That never produces a *wrong* neighbour (the
 *    distance test filters), but it does produce a **duplicated** one, because the scan visits one
 *    bucket once per colliding cell offset in its window. A ring of eight points answered
 *    `sample(centre, 0.31)` with ten entries. Every query therefore deduplicates by index.
 *  - **"I found k" is not "I found the nearest k".** Scanning the cells within `cr` of the query
 *    guarantees only that every point within `cr · cellSize` has been seen; a point one ring further
 *    out can still be nearer than one already in hand from a diagonal corner cell. [kNearest] used to
 *    stop at the first `cr` that produced `k` candidates and returned a wrong answer for it — on a
 *    300-point disc, the 9th of 12 neighbours came back 17 % too far. It now keeps expanding until
 *    the k-th distance is inside the guaranteed radius.
 */
class Vec3SpatialHash(var cellSize: Double) {
    private val buckets = HashMap<Long, MutableList<Int>>()
    private var points: List<Vec3> = emptyList()

    // The occupied cell extent, recorded at rebuild. A ring wider than this cannot reach a cell that
    // holds anything, which is the exact termination condition kNearest was missing: with the default
    // maxRadius of +Infinity its `searchRadius >= maxRadius` exit never fires, so a cloud holding
    // fewer than k other points used to grind out to the hard cr = 128 cap -- 257^3 bucket probes,
    // measured at 2569 ms for a single call on a four-point cloud.
    private var minCell = intArrayOf(0, 0, 0)
    private var maxCell = intArrayOf(0, 0, 0)

    fun rebuild(points: List<Vec3>) {
        require(cellSize > 0.0) { "Vec3SpatialHash.rebuild: cellSize must be > 0, found $cellSize" }
        this.points = points
        buckets.clear()
        minCell = intArrayOf(0, 0, 0)
        maxCell = intArrayOf(0, 0, 0)
        for (i in points.indices) {
            val p = points[i]
            val ix = floor(p.x / cellSize).toInt()
            val iy = floor(p.y / cellSize).toInt()
            val iz = floor(p.z / cellSize).toInt()
            if (i == 0) {
                minCell = intArrayOf(ix, iy, iz)
                maxCell = intArrayOf(ix, iy, iz)
            } else {
                minCell[0] = min(minCell[0], ix); maxCell[0] = max(maxCell[0], ix)
                minCell[1] = min(minCell[1], iy); maxCell[1] = max(maxCell[1], iy)
                minCell[2] = min(minCell[2], iz); maxCell[2] = max(maxCell[2], iz)
            }
            buckets.getOrPut(hash(ix, iy, iz)) { ArrayList() }.add(i)
        }
    }

    /** The smallest cell ring around `(cx, cy, cz)` that already encloses every occupied cell. */
    private fun enclosingRing(cx: Int, cy: Int, cz: Int): Int = max(
        max(max(cx - minCell[0], maxCell[0] - cx), max(cy - minCell[1], maxCell[1] - cy)),
        max(cz - minCell[2], maxCell[2] - cz)
    )

    fun radiusNeighbors(i: Int, radius: Double): List<Neighbor> {
        require(i in points.indices) { "Vec3SpatialHash.radiusNeighbors: i out of range: $i" }
        require(radius >= 0.0) { "Vec3SpatialHash.radiusNeighbors: radius must be >= 0, found $radius" }

        val p = points[i]
        val r2 = radius * radius
        val cx = floor(p.x / cellSize).toInt()
        val cy = floor(p.y / cellSize).toInt()
        val cz = floor(p.z / cellSize).toInt()
        val cr = max(1, ceil(radius / cellSize).toInt())

        val out = ArrayList<Neighbor>()
        val seen = HashSet<Int>()
        for (dz in -cr..cr) {
            for (dy in -cr..cr) {
                for (dx in -cr..cr) {
                    val cell = buckets[hash(cx + dx, cy + dy, cz + dz)] ?: continue
                    for (j in cell) {
                        if (j == i || !seen.add(j)) continue
                        val d2 = points[i].distanceSquared(points[j]).toDouble()
                        if (d2 <= r2) out.add(Neighbor(j, d2))
                    }
                }
            }
        }
        return out.sortedBy { it.distanceSquared }
    }

    fun kNearest(i: Int, k: Int, maxRadius: Double = Double.POSITIVE_INFINITY): List<Neighbor> {
        require(i in points.indices) { "Vec3SpatialHash.kNearest: i out of range: $i" }
        require(k > 0) { "Vec3SpatialHash.kNearest: k must be > 0, found $k" }

        val p = points[i]
        val cx = floor(p.x / cellSize).toInt()
        val cy = floor(p.y / cellSize).toInt()
        val cz = floor(p.z / cellSize).toInt()

        // Rings beyond this reach only empty cells, so no expansion past it can ever help. The cap
        // also keeps the pathological case (a cloud with fewer than k other points and no maxRadius)
        // bounded by the cloud's own extent instead of by the hard cr = 128 ceiling.
        val enclosing = enclosingRing(cx, cy, cz)

        var cr = 1
        var out = ArrayList<Neighbor>()
        val seen = HashSet<Int>()

        while (true) {
            out = ArrayList()
            seen.clear()
            for (dz in -cr..cr) {
                for (dy in -cr..cr) {
                    for (dx in -cr..cr) {
                        val cell = buckets[hash(cx + dx, cy + dy, cz + dz)] ?: continue
                        for (j in cell) {
                            if (j == i || !seen.add(j)) continue
                            val d2 = points[i].distanceSquared(points[j]).toDouble()
                            if (d2 <= maxRadius * maxRadius) {
                                out.add(Neighbor(j, d2))
                            }
                        }
                    }
                }
            }

            // Cells within `cr` of the query cell cover, at minimum, the ball of radius
            // `cr * cellSize` about p -- p sits somewhere inside its own cell, and the nearest face
            // of the scanned box is at least that far away on every axis. So the k-th candidate is
            // only certainly the k-th *nearest* once it lies inside that ball.
            val guaranteed = cr * cellSize
            if (out.size >= k) {
                val kth = out.sortedBy { it.distanceSquared }[k - 1].distanceSquared
                if (kth <= guaranteed * guaranteed) break
            }
            if (guaranteed >= maxRadius || cr >= enclosing || cr > 128) break
            cr++
        }

        return out.sortedBy { it.distanceSquared }.take(k)
    }

    /**
     * Direct sample query for off-point field evaluation. Unlike [radiusNeighbors] this includes a
     * point sitting exactly on [center] — it is a query about a position, not about a member.
     */
    fun sample(center: Vec3, radius: Double): List<Neighbor> {
        require(radius >= 0.0) { "Vec3SpatialHash.sample: radius must be >= 0, found $radius" }

        val r2 = radius * radius
        val cx = floor(center.x / cellSize).toInt()
        val cy = floor(center.y / cellSize).toInt()
        val cz = floor(center.z / cellSize).toInt()
        val cr = max(1, ceil(radius / cellSize).toInt())
        val out = ArrayList<Neighbor>()
        val seen = HashSet<Int>()

        for (dz in -cr..cr) {
            for (dy in -cr..cr) {
                for (dx in -cr..cr) {
                    val cell = buckets[hash(cx + dx, cy + dy, cz + dz)] ?: continue
                    for (j in cell) {
                        if (!seen.add(j)) continue
                        val d2 = points[j].distanceSquared(center).toDouble()
                        if (d2 <= r2) out.add(Neighbor(j, d2))
                    }
                }
            }
        }
        return out.sortedBy { it.distanceSquared }
    }

    private fun hash(ix: Int, iy: Int, iz: Int): Long {
        val a = ix.toLong() * 73856093L
        val b = iy.toLong() * 19349663L
        val c = iz.toLong() * 83492791L
        return a xor b xor c
    }
}

data class Neighbor(val index: Int, val distanceSquared: Double)

interface DynamicGraphSystem {
    fun step(
        dt: Float,
        state: DynamicPointState,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    )
}

// -------------------------------------------------------------------------------------
// Shared helpers
// -------------------------------------------------------------------------------------

private fun safeInvSqrt(x: Double): Double = 1.0 / sqrt(max(1e-12, x))

private fun labelDistanceSquared(a: Vec4, b: Vec4): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    val dw = a.w - b.w
    return (dx * dx + dy * dy + dz * dz + dw * dw).toDouble()
}

private fun nearestPoint(points: List<Vec3>, x: Vec3, exclude: Int = -1): Int {
    var best = -1
    var bestD2 = Double.POSITIVE_INFINITY
    for (j in points.indices) {
        if (j == exclude) continue
        val d2 = points[j].distanceSquared(x).toDouble()
        if (d2 < bestD2) {
            bestD2 = d2
            best = j
        }
    }
    return best
}

// -------------------------------------------------------------------------------------
// System 1: Breathing anisotropic k-NN
// -------------------------------------------------------------------------------------

class Vec3BreathingAnisotropicKnnSystem(
    var k: Int,
    var ttl: Float,
    var reinforce: Double,
    var maxWeight: Double,
    var mutualOnly: Boolean = true,
    var queryRadius: Double = Double.POSITIVE_INFINITY
) : DynamicGraphSystem {

    private fun warpedDistanceSquared(i: Int, j: Int, state: DynamicPointState): Double {
        val pi = state.points[i]
        val pj = state.points[j]
        val d = Vec3(pj).sub(pi)
        val t = state.time

        val axis = vec(
            cos(0.73f * t).toDouble(),
            sin(1.11f * t).toDouble(),
            cos(0.37f * t + 1.3f).toDouble()
        ).normalize()

        val parallel = d.dot(axis).toDouble()
        val perp2 = d.lengthSquared().toDouble() - parallel * parallel
        val stretch = 0.25 + 1.75 * (0.5 + 0.5 * sin(0.9f * t).toDouble())
        val geom = perp2 + stretch * stretch * parallel * parallel

        val labels = state.labels
        val labelTerm = if (labels != null) 0.35 * labelDistanceSquared(labels[i], labels[j]) else 0.0
        return geom + labelTerm
    }

    override fun step(dt: Float, state: DynamicPointState, edges: DynamicEdgeList, spatialHash: Vec3SpatialHash) {
        val all = Array(state.pointCount) { mutableListOf<Neighbor>() }

        for (i in 0 until state.pointCount) {
            val candidates = if (queryRadius.isFinite()) {
                spatialHash.radiusNeighbors(i, queryRadius)
            } else {
                spatialHash.kNearest(i, max(k * 3, k))
            }

            val warped = candidates
                .map { Neighbor(it.index, warpedDistanceSquared(i, it.index, state)) }
                .sortedBy { it.distanceSquared }
                .take(k)

            all[i].addAll(warped)
        }

        for (i in 0 until state.pointCount) {
            for (n in all[i]) {
                val j = n.index
                if (mutualOnly) {
                    val back = all[j].any { it.index == i }
                    if (!back) continue
                }
                val w = reinforce * safeInvSqrt(n.distanceSquared) * dt
                edges.reinforce(i, j, w, ttl, maxWeight)
            }
        }

        state.time += dt
    }
}

// -------------------------------------------------------------------------------------
// System 2: Flowline advection graph
// -------------------------------------------------------------------------------------

class Vec3FlowlineAdvectionGraphSystem(
    var influenceRadius: Double,
    var stepLength: Double,
    var ttl: Float,
    var reinforce: Double,
    var maxWeight: Double,
    var swirlMix: Double = 0.45
) : DynamicGraphSystem {

    private fun fieldAt(x: Vec3, state: DynamicPointState, spatialHash: Vec3SpatialHash): Vec3 {
        var acc = Vec3(0f, 0f, 0f)

        for (n in spatialHash.sample(x, influenceRadius)) {
            val j = n.index
            val p = state.points[j]
            val d = Vec3(p).sub(x)
            val d2 = n.distanceSquared
            if (d2 < 1e-12 || d2 > influenceRadius * influenceRadius) continue

            val falloff = 1.0 - d2 / (influenceRadius * influenceRadius)
            val lab = state.labels?.get(j)
            val axis = vec(
                (((lab?.x ?: 0.3f) * 2f) - 1f).toDouble(),
                (((lab?.y ?: 0.7f) * 2f) - 1f).toDouble(),
                (((lab?.z ?: 0.2f) * 2f) - 1f).toDouble()
            ).normalize()

            val swirl = Vec3(d).cross(axis).normalize()
            val attract = Vec3(d).normalize()
            val contrib = attract.mul((1.0 - swirlMix).toFloat()).add(swirl.mul(swirlMix.toFloat()))
            acc.add(contrib.mul(falloff.toFloat()))
        }
        return acc
    }

    override fun step(dt: Float, state: DynamicPointState, edges: DynamicEdgeList, spatialHash: Vec3SpatialHash) {
        for (i in 0 until state.pointCount) {
            val x = state.points[i]
            val v = fieldAt(x, state, spatialHash)
            val len = v.length().toDouble()
            if (len < 1e-10) continue

            val x2 = Vec3(x).add(Vec3(v).mul((stepLength * dt / max(1e-6, len)).toFloat()))
            val j = nearestPoint(state.points, x2, i)
            if (j >= 0) {
                val d = state.points[i].distance(state.points[j]).toDouble()
                val w = reinforce * dt / (1.0 + d)
                edges.reinforce(i, j, w, ttl, maxWeight)
            }
        }

        state.time += dt
    }
}

// -------------------------------------------------------------------------------------
// System 3: Local tangent triangulation
// -------------------------------------------------------------------------------------

class Vec3LocalTangentTriangulationSystem(
    var neighborhoodK: Int,
    var ttl: Float,
    var reinforce: Double,
    var maxWeight: Double,
    var maxNeighborRadius: Double = Double.POSITIVE_INFINITY
) : DynamicGraphSystem {

    override fun step(dt: Float, state: DynamicPointState, edges: DynamicEdgeList, spatialHash: Vec3SpatialHash) {
        for (i in 0 until state.pointCount) {
            val nbrs = spatialHash.kNearest(i, neighborhoodK, maxNeighborRadius)
            if (nbrs.size < 3) continue

            val pi = state.points[i]
            var normal = Vec3(0f, 0f, 0f)

            for (a in nbrs.indices) {
                val va = Vec3(state.points[nbrs[a].index]).sub(pi)
                for (b in (a + 1) until nbrs.size) {
                    val vb = Vec3(state.points[nbrs[b].index]).sub(pi)
                    normal.add(Vec3(va).cross(vb))
                }
            }

            if (normal.lengthSquared().toDouble() < 1e-10) continue
            normal.normalize()

            val tangentU = Vec3(state.points[nbrs[0].index]).sub(pi)
            tangentU.sub(Vec3(normal).mul(tangentU.dot(normal)))
            if (tangentU.lengthSquared().toDouble() < 1e-10) continue
            tangentU.normalize()
            val tangentV = Vec3(normal).cross(tangentU).normalize()

            data class PolarNeighbor(val index: Int, val angle: Double, val radius: Double)
            val polar = nbrs.map { n ->
                val d = Vec3(state.points[n.index]).sub(pi)
                val u = d.dot(tangentU).toDouble()
                val v = d.dot(tangentV).toDouble()
                PolarNeighbor(n.index, atan2(v, u), sqrt(u * u + v * v))
            }.sortedBy { it.angle }

            for (a in polar.indices) {
                val p0 = polar[a]
                val p1 = polar[(a + 1) % polar.size]
                val w0 = reinforce * dt / (1.0 + p0.radius)
                val w1 = reinforce * dt / (1.0 + 0.5 * (p0.radius + p1.radius))
                edges.reinforce(i, p0.index, w0, ttl, maxWeight)
                edges.reinforce(p0.index, p1.index, w1, ttl, maxWeight)
            }
        }

        state.time += dt
    }
}

// -------------------------------------------------------------------------------------
// System 4: Persistent proximity hysteresis
// -------------------------------------------------------------------------------------

class Vec3PersistentProximityHysteresisSystem(
    var rEnter: Double,
    var rExit: Double,
    var ttl: Float,
    var reinforce: Double,
    var maxWeight: Double,
    var labelBias: Double = 0.0
) : DynamicGraphSystem {
    init {
        require(rExit >= rEnter) { "Vec3PersistentProximityHysteresisSystem: rExit must be >= rEnter" }
    }

    override fun step(dt: Float, state: DynamicPointState, edges: DynamicEdgeList, spatialHash: Vec3SpatialHash) {
        val t = state.time
        for (i in 0 until state.pointCount) {
            val neighbors = spatialHash.radiusNeighbors(i, rExit * 1.5)
            for (n in neighbors) {
                val j = n.index
                if (j <= i) continue

                val d = sqrt(n.distanceSquared)
                val bias = if (state.labels != null) {
                    labelBias * exp(-labelDistanceSquared(state.labels[i], state.labels[j]))
                } else 0.0

                var localEnter = rEnter * (0.8 + 0.4 * (0.5 + 0.5 * sin((t + 0.17f * i).toDouble())))
                var localExit = rExit * (0.8 + 0.4 * (0.5 + 0.5 * sin((t + 0.17f * i).toDouble())))
                localEnter *= (1.0 + bias)
                localExit *= (1.0 + bias)

                val existing = edges.get(i, j)
                val alive = existing != null && existing.weight > 0.0 && existing.ttl > 0f

                if (!alive) {
                    if (d <= localEnter) {
                        edges.reinforce(i, j, reinforce * dt / (1.0 + d), ttl, maxWeight)
                    }
                } else {
                    if (d <= localExit) {
                        edges.reinforce(i, j, 0.5 * reinforce * dt / (1.0 + d), ttl, maxWeight)
                    }
                }
            }
        }

        state.time += dt
    }
}

// -------------------------------------------------------------------------------------
// System 5: Signal routing graph
// -------------------------------------------------------------------------------------

class Vec3SignalRoutingGraphSystem(
    var neighborhoodK: Int,
    var ttl: Float,
    var reinforce: Double,
    var maxWeight: Double,
    var routeCount: Int = 1,
    var maxNeighborRadius: Double = Double.POSITIVE_INFINITY
) : DynamicGraphSystem {

    private fun signal(i: Int, state: DynamicPointState): Double {
        val p = state.points[i]
        var base =
            0.6 * sin(1.7 * p.x + 0.8 * state.time) +
            0.5 * cos(1.3 * p.y - 1.1 * state.time) +
            0.4 * sin(1.9 * p.z + 0.3 * state.time)

        state.labels?.get(i)?.let { l ->
            base += 0.7 * l.w + 0.3 * l.x - 0.2 * l.y
        }
        return base
    }

    override fun step(dt: Float, state: DynamicPointState, edges: DynamicEdgeList, spatialHash: Vec3SpatialHash) {
        val sig = DoubleArray(state.pointCount) { i -> signal(i, state) }

        for (i in 0 until state.pointCount) {
            val nbrs = spatialHash.kNearest(i, neighborhoodK, maxNeighborRadius)
            if (nbrs.isEmpty()) continue

            data class Candidate(val j: Int, val score: Double)
            val candidates = nbrs.map { n ->
                val j = n.index
                val downhill = sig[i] - sig[j]
                val geometric = 1.0 / (1.0 + sqrt(n.distanceSquared))
                val labelAffinity = if (state.labels != null) {
                    exp(-0.5 * labelDistanceSquared(state.labels[i], state.labels[j]))
                } else 1.0
                Candidate(j, 1.4 * downhill + 0.8 * geometric + 0.9 * labelAffinity)
            }.sortedByDescending { it.score }

            val count = min(routeCount, candidates.size)
            for (k in 0 until count) {
                val c = candidates[k]
                if (c.score > 0.0) {
                    edges.reinforce(i, c.j, reinforce * c.score * dt, ttl, maxWeight)
                }
            }
        }

        state.time += dt
    }
}

// -------------------------------------------------------------------------------------
// System 6: Alpha-event / simplex-birth inspired hypergraph collapse
// -------------------------------------------------------------------------------------

/**
 * Closes triangles rather than adding edges one at a time: every triple whose three sides all fit
 * inside [alphaRadius] and whose area clears [minArea] gets all three of its sides reinforced by the
 * same amount, so the graph this builds is made of *faces*.
 *
 * @param reinforcePerUnitArea the increment per unit of triangle area, not per triangle: each side gets
 *   `reinforcePerUnitArea · min(1, area) · compactness · dt`. The other five systems take a plain
 *   `reinforce`; this one is named differently because a triangle in a cloud one unit across has an
 *   area of a few thousandths, so the same number here lands about three orders of magnitude lower.
 *   On the 220-point disc of the docs page, 4.0 reaches 0.3 % of the ceiling where the others reach at
 *   least a sixth of it; scale it up by ~100× to put this system's weights on their footing.
 * @param minArea the smallest triangle that counts, **in area** — the guard used to compare it
 *   against the *squared* area, which made its effective units area² and the default 1e-5 a rejection
 *   of everything under 3.2e-3, 316× larger than it reads. On a 140-point disc of radius 0.5 with
 *   `alphaRadius = 0.1` that left three edges standing where there should have been hundreds.
 */
class Vec3AlphaEventHypergraphCollapseSystem(
    var neighborhoodK: Int,
    var alphaRadius: Double,
    var ttl: Float,
    var reinforcePerUnitArea: Double,
    var maxWeight: Double,
    var maxNeighborRadius: Double = Double.POSITIVE_INFINITY,
    var minArea: Double = 1e-5
) : DynamicGraphSystem {

    override fun step(dt: Float, state: DynamicPointState, edges: DynamicEdgeList, spatialHash: Vec3SpatialHash) {
        val alpha2 = alphaRadius * alphaRadius

        for (i in 0 until state.pointCount) {
            val nbrs = spatialHash.kNearest(i, neighborhoodK, maxNeighborRadius)
            if (nbrs.size < 2) continue
            val pi = state.points[i]

            for (a in nbrs.indices) {
                val ja = nbrs[a].index
                val pa = state.points[ja]
                val dia2 = pi.distanceSquared(pa).toDouble()
                if (dia2 > alpha2) continue

                for (b in (a + 1) until nbrs.size) {
                    val jb = nbrs[b].index
                    val pb = state.points[jb]
                    val dib2 = pi.distanceSquared(pb).toDouble()
                    val dab2 = pa.distanceSquared(pb).toDouble()
                    if (dib2 > alpha2 || dab2 > alpha2) continue

                    val va = Vec3(pa).sub(pi)
                    val vb = Vec3(pb).sub(pi)
                    val cross = Vec3(va).cross(vb)
                    // |va x vb| is twice the triangle's area, so this is the area SQUARED. Compare it
                    // against minArea squared -- comparing it against minArea itself is the units
                    // error described on the class.
                    val area2 = cross.lengthSquared().toDouble() * 0.25
                    if (area2 < minArea * minArea) continue

                    val labelFactor = if (state.labels != null) {
                        val l0 = state.labels[i]
                        val l1 = state.labels[ja]
                        val l2 = state.labels[jb]
                        val avg = (
                            labelDistanceSquared(l0, l1) +
                            labelDistanceSquared(l0, l2) +
                            labelDistanceSquared(l1, l2)
                        ) / 3.0
                        exp(-0.35 * avg)
                    } else 1.0

                    val compactness = 1.0 / (1.0 + sqrt((dia2 + dib2 + dab2) / 3.0))
                    val areaFactor = min(1.0, sqrt(area2))
                    val w = reinforcePerUnitArea * labelFactor * compactness * areaFactor * dt

                    edges.reinforce(i, ja, w, ttl, maxWeight)
                    edges.reinforce(i, jb, w, ttl, maxWeight)
                    edges.reinforce(ja, jb, w, ttl, maxWeight)
                }
            }
        }

        state.time += dt
    }
}

// -------------------------------------------------------------------------------------
// Convenience frame helpers
// -------------------------------------------------------------------------------------

fun stepDynamicGraph(
    dt: Float,
    state: DynamicPointState,
    edges: DynamicEdgeList,
    spatialHash: Vec3SpatialHash,
    system: DynamicGraphSystem
) {
    // The cloud may have grown since the store was built -- a DynamicPointProcess run between frames
    // adds points, and every system below indexes `0 until state.pointCount`.
    edges.ensurePointCount(state.pointCount)
    spatialHash.rebuild(state.points)
    system.step(dt, state, edges, spatialHash)
}

fun stepDynamicGraphWithLinearDecay(
    dt: Float,
    state: DynamicPointState,
    edges: DynamicEdgeList,
    spatialHash: Vec3SpatialHash,
    system: DynamicGraphSystem,
    weightDecayPerSecond: Double
) {
    stepDynamicGraph(dt, state, edges, spatialHash, system)
    edges.decayLinear(dt, weightDecayPerSecond)
}

fun stepDynamicGraphWithExponentialDecay(
    dt: Float,
    state: DynamicPointState,
    edges: DynamicEdgeList,
    spatialHash: Vec3SpatialHash,
    system: DynamicGraphSystem,
    lambda: Double
) {
    stepDynamicGraph(dt, state, edges, spatialHash, system)
    edges.decayExponential(dt, lambda)
}

// -------------------------------------------------------------------------------------
// Mutable point kinetics and processes
// -------------------------------------------------------------------------------------

data class DynamicPointKinetics(
    val velocity: MutableList<Vec3>,
    val resource: MutableList<Double>,
    val alive: MutableList<Boolean>
) {
    init {
        require(velocity.size == resource.size && resource.size == alive.size) {
            "DynamicPointKinetics: all lists must have identical sizes"
        }
    }

    fun ensureSize(n: Int) {
        while (velocity.size < n) velocity.add(Vec3(0f, 0f, 0f))
        while (resource.size < n) resource.add(0.0)
        while (alive.size < n) alive.add(true)
    }
}

interface DynamicPointProcess {
    fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    )
}

private fun clampLength(v: Vec3, maxLen: Double): Vec3 {
    val len = v.length().toDouble()
    return if (len <= maxLen || len <= 1e-12) v else Vec3(v).mul((maxLen / len).toFloat())
}

private fun averageVec3(values: List<Vec3>): Vec3 {
    if (values.isEmpty()) return Vec3(0f, 0f, 0f)
    val acc = Vec3(0f, 0f, 0f)
    for (v in values) acc.add(v)
    return acc.mul(1f / values.size)
}

/**
 * Kept as a name because five processes read better for it, but it is now the store's O(1) incidence
 * lookup rather than the O(E) scan it used to be. See [DynamicEdgeList.incident].
 */
private fun edgeNeighbors(index: Int, edges: DynamicEdgeList): List<DynamicGraphEdge> =
    edges.incident(index)

private fun otherIndex(index: Int, edge: DynamicGraphEdge): Int {
    return if (edge.i == index) edge.j else edge.i
}

private fun weightedDirectionFromEdges(index: Int, state: DynamicPointState, edges: DynamicEdgeList): Vec3 {
    val acc = Vec3(0f, 0f, 0f)
    for (edge in edgeNeighbors(index, edges)) {
        val j = otherIndex(index, edge)
        val delta = Vec3(state.points[j]).sub(state.points[index])
        if (delta.lengthSquared() <= 1e-12f) continue
        acc.add(delta.normalize().mul(edge.weight.toFloat()))
    }
    return acc
}

private fun requireMutableLabels(state: DynamicPointState): MutableList<Vec4>? {
    val labels = state.labels ?: return null
    require(labels is MutableList<Vec4>) {
        "DynamicPointState.labels must be a MutableList<Vec4> for processes that add or remove labeled points"
    }
    return labels
}

private fun removeDeadPointsInPlace(
    state: DynamicPointState,
    kinetics: DynamicPointKinetics,
    edges: DynamicEdgeList
) {
    if (kinetics.alive.all { it }) return

    val labels = state.labels
    val mutableLabels = if (labels != null) requireMutableLabels(state) else null
    val remap = IntArray(state.pointCount) { -1 }
    val newPoints = mutableListOf<Vec3>()
    val newLabels = mutableLabels?.let { mutableListOf<Vec4>() }
    val newVelocity = mutableListOf<Vec3>()
    val newResource = mutableListOf<Double>()
    val newAlive = mutableListOf<Boolean>()

    for (i in 0 until state.pointCount) {
        if (!kinetics.alive[i]) continue
        remap[i] = newPoints.size
        newPoints += state.points[i]
        if (newLabels != null && labels != null) newLabels += labels[i]
        newVelocity += kinetics.velocity[i]
        newResource += kinetics.resource[i]
        newAlive += true
    }

    val rebuiltEdges = DynamicEdgeList(pointCount = newPoints.size, undirected = edges.undirected)
    for (edge in edges.values()) {
        val ni = remap[edge.i]
        val nj = remap[edge.j]
        if (ni < 0 || nj < 0 || ni == nj) continue
        val rebuilt = rebuiltEdges.getOrCreate(ni, nj, edge.weight, edge.ttl)
        rebuilt.age = edge.age
        rebuilt.flags = edge.flags
    }

    state.points.clear()
    state.points.addAll(newPoints)

    if (mutableLabels != null && newLabels != null) {
        mutableLabels.clear()
        mutableLabels.addAll(newLabels)
    }

    kinetics.velocity.clear()
    kinetics.velocity.addAll(newVelocity)
    kinetics.resource.clear()
    kinetics.resource.addAll(newResource)
    kinetics.alive.clear()
    kinetics.alive.addAll(newAlive)

    edges.clear()
    // The index space has to shrink with the cloud, or toPointGraph() hands every traversal
    // downstream a PointGraph full of vertices that were compacted away.
    edges.setPointCount(newPoints.size)
    for (edge in rebuiltEdges.values()) {
        val restored = edges.getOrCreate(edge.i, edge.j, edge.weight, edge.ttl)
        restored.age = edge.age
        restored.flags = edge.flags
    }
}

// -------------------------------------------------------------------------------------
// Mutable point processes
// -------------------------------------------------------------------------------------

/**
 * Hooke's law along the live edges: each edge pulls its endpoints toward a rest length, damped, with
 * the speed clamped.
 *
 * @param restLengthTimesWeight the rest length of an edge multiplied by its weight: the rest length is
 *   `restLengthTimesWeight / weight`, so a heavily reinforced edge wants to be short and a faint one
 *   wants to be long, and the units are length × weight. That matters because the weights these systems actually
 *   produce are small — `reinforce · dt / (1 + d)` is of order 0.02 — and the default 1.0 therefore
 *   asks for a rest length of **50 world units** in a cloud one unit across, i.e. every spring is a
 *   saturated repulsion. Set it to `desiredRestLength × typicalWeight`.
 * @param edgeWeightFloor edges at or below this weight are skipped entirely.
 */
class SpringMotionProcess(
    var springK: Double = 1.0,
    var damping: Double = 0.15,
    var restLengthTimesWeight: Double = 1.0,
    var maxSpeed: Double = 0.25,
    var edgeWeightFloor: Double = 1e-6
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)
        val forces = MutableList(state.pointCount) { Vec3(0f, 0f, 0f) }

        for (edge in edges.values()) {
            if (edge.weight <= edgeWeightFloor) continue
            val a = state.points[edge.i]
            val b = state.points[edge.j]
            val delta = Vec3(b).sub(a)
            val len = delta.length().toDouble()
            if (len <= 1e-9) continue

            val dir = delta.mul((1.0 / len).toFloat())
            // `edge.weight > edgeWeightFloor` is guaranteed by the skip above, so no clamp is needed
            // here -- the max() this replaces could never take its first branch.
            val rest = restLengthTimesWeight / edge.weight
            val magnitude = springK * edge.weight * (len - rest)
            val force = dir.mul(magnitude.toFloat())

            forces[edge.i].add(force)
            forces[edge.j].sub(force)
        }

        for (i in 0 until state.pointCount) {
            if (!kinetics.alive[i]) continue
            val velocity = kinetics.velocity[i]
            val damp = Vec3(velocity).mul(damping.toFloat())
            val newVelocity = clampLength(
                Vec3(velocity).add(Vec3(forces[i]).sub(damp).mul(dt)),
                maxSpeed
            )
            kinetics.velocity[i] = newVelocity
            state.points[i] = Vec3(state.points[i]).add(Vec3(newVelocity).mul(dt))
        }
    }
}

class DirectedEdgeAdvectionProcess(
    var gain: Double = 0.2,
    var damping: Double = 0.1,
    var maxSpeed: Double = 0.3
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)

        for (i in 0 until state.pointCount) {
            if (!kinetics.alive[i]) continue
            val flow = weightedDirectionFromEdges(i, state, edges)
            val velocity = kinetics.velocity[i]
            val accel = Vec3(flow).mul(gain.toFloat())
            val newVelocity = clampLength(
                Vec3(velocity)
                    .mul((1.0 - damping * dt.toDouble()).toFloat())
                    .add(accel.mul(dt)),
                maxSpeed
            )
            kinetics.velocity[i] = newVelocity
            state.points[i] = Vec3(state.points[i]).add(Vec3(newVelocity).mul(dt))
        }
    }
}

/**
 * Births a child ahead of any point whose incident edges pull *coherently* in one direction — the
 * family's growth rule, and the one process here that makes the population a variable.
 *
 * **It has no intrinsic ceiling, and nothing else in this file supplies one.** Measured on the
 * 40-point ring of `DynamicGraphSystemsTest`, driven by
 * [Vec3PersistentProximityHysteresisSystem] through [exampleDynamicMorphogenesisStep]: the population
 * sits at exactly 40 for 109 frames while the ring's edges are reinforced up toward
 * [coherenceThreshold], the first birth lands on frame 109 (1.82 s of simulated time), and from there
 * it multiplies by about 1.05 **per frame** — 3603 points and 78 360 edges by frame 199. That is the
 * correct behaviour of an uncapped growth rule and it is not usable in a live box, so set
 * [maxPointCount].
 *
 * @param coherenceThreshold the length of the weighted sum of unit directions to the point's
 *   neighbours that counts as "a tip". Note the scale: the weights these systems produce *per step*
 *   are of order 0.02, so 1.25 means an edge set that has been reinforced for a while, not a fresh one
 *   — which is why the ring above takes 109 frames to grow at all.
 * @param maxPointCount no birth happens once the cloud has reached this many points. The default is
 *   no ceiling, which is what the process has always done.
 */
class DirectedTipGrowthProcess(
    var coherenceThreshold: Double = 1.25,
    var spawnDistance: Double = 0.05,
    var cooldownWeight: Double = 0.25,
    var childEdgeWeight: Double = 1.0,
    var childEdgeTtl: Float = 2.0f,
    var maxWeight: Double = 8.0,
    var inheritResourceFactor: Double = 0.5,
    var localDensityRadius: Double = 0.10,
    var localDensityMax: Int = 8,
    var maxPointCount: Int = Int.MAX_VALUE
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)

        val budget = maxPointCount - state.pointCount
        if (budget <= 0) return

        val births = mutableListOf<Triple<Int, Vec3, Vec4?>>()
        for (i in 0 until state.pointCount) {
            if (births.size >= budget) break
            if (!kinetics.alive[i]) continue
            val dir = weightedDirectionFromEdges(i, state, edges)
            val magnitude = dir.length().toDouble()
            if (magnitude < coherenceThreshold) continue

            val localDensity = spatialHash.sample(state.points[i], localDensityRadius).size
            if (localDensity > localDensityMax) continue

            val childPos = Vec3(state.points[i]).add(Vec3(dir).normalize().mul(spawnDistance.toFloat()))
            births += Triple(i, childPos, state.labels?.get(i))
        }

        val mutableLabels = if (births.isNotEmpty() && state.labels != null) requireMutableLabels(state) else null
        for ((parent, pos, label) in births) {
            val newIndex = state.points.size
            state.points += pos
            if (mutableLabels != null && label != null) mutableLabels += label

            // The child is joined to its parent three lines below, so the edge store's index space
            // has to admit it first.
            edges.ensurePointCount(state.points.size)
            kinetics.ensureSize(state.points.size)
            kinetics.velocity[newIndex] = Vec3(kinetics.velocity[parent]).mul(0.35f)
            kinetics.resource[newIndex] = kinetics.resource[parent] * inheritResourceFactor
            kinetics.resource[parent] *= (1.0 - inheritResourceFactor)
            kinetics.alive[newIndex] = true

            edges.reinforce(parent, newIndex, childEdgeWeight, childEdgeTtl, maxWeight)
            for (edge in edgeNeighbors(parent, edges)) {
                edge.weight = max(0.0, edge.weight - cooldownWeight)
            }
        }
    }
}

/** Set on an edge that arrived on its endpoint by way of a [MergeClosePointsProcess] transfer. */
const val DYNAMIC_EDGE_FLAG_MERGED = 1

/**
 * Collapses pairs of points that are both within [mergeDistance] of each other *and* joined by an
 * edge of at least [minCombinedWeight]: the higher-indexed one is absorbed into the lower, which
 * takes the blended position, the blended velocity, the summed resource **and every edge the absorbed
 * point had**.
 *
 * That last clause is the part that used to be missing. The process marked the absorbed point's
 * incident edges with [DYNAMIC_EDGE_FLAG_MERGED] and then let `removeDeadPointsInPlace` delete
 * exactly those edges — the flag was written to objects discarded three lines later, so a "merge"
 * severed the survivor from everything only the absorbed point had reached. They are now re-pointed
 * at the survivor first, with their weights added and their ttls taken at the max.
 *
 * @param minCombinedWeight the weight the joining edge must carry. Note that the weights these
 *   systems produce per step are of order 0.02, so the default 1.5 means "an edge that has been
 *   continuously reinforced for a while", not "any edge".
 */
class MergeClosePointsProcess(
    var mergeDistance: Double = 0.025,
    var minCombinedWeight: Double = 1.5,
    var blend: Double = 0.5
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)
        val consumed = BooleanArray(state.pointCount)

        for (i in 0 until state.pointCount) {
            if (consumed[i] || !kinetics.alive[i]) continue
            val neighbors = spatialHash.radiusNeighbors(i, mergeDistance)
            for (neighbor in neighbors) {
                val j = neighbor.index
                if (j <= i || consumed[j] || !kinetics.alive[j]) continue

                val edge = edges.get(i, j)
                val combinedWeight = edge?.weight ?: 0.0
                if (combinedWeight < minCombinedWeight) continue

                val pi = state.points[i]
                val pj = state.points[j]
                state.points[i] = Vec3(pi).mul((1.0 - blend).toFloat()).add(Vec3(pj).mul(blend.toFloat()))
                kinetics.velocity[i] = Vec3(kinetics.velocity[i]).mul((1.0 - blend).toFloat())
                    .add(Vec3(kinetics.velocity[j]).mul(blend.toFloat()))
                kinetics.resource[i] += kinetics.resource[j]

                // Re-point j's edges at i BEFORE j is compacted away, or the merge disconnects the
                // survivor. Snapshot first: getOrCreate inserts into the same map being iterated.
                val inherited = edges.incident(j).toList()
                for (existing in inherited) {
                    val other = otherIndex(j, existing)
                    if (other == i) continue
                    val moved = edges.getOrCreate(i, other, 0.0, existing.ttl)
                    moved.weight += existing.weight
                    moved.ttl = max(moved.ttl, existing.ttl)
                    moved.age = max(moved.age, existing.age)
                    moved.flags = moved.flags or DYNAMIC_EDGE_FLAG_MERGED
                }

                kinetics.alive[j] = false
                consumed[j] = true
                break
            }
        }

        removeDeadPointsInPlace(state, kinetics, edges)
    }
}

class SignalDiffusionAndChemotaxisProcess(
    var diffusionRate: Double = 0.35,
    var chemoGain: Double = 0.2,
    var downhill: Boolean = false,
    var damping: Double = 0.08,
    var maxSpeed: Double = 0.25
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)
        val delta = DoubleArray(state.pointCount)

        for (edge in edges.values()) {
            val amount = diffusionRate * edge.weight * (kinetics.resource[edge.i] - kinetics.resource[edge.j]) * dt
            delta[edge.i] -= amount
            delta[edge.j] += amount
        }
        for (i in 0 until state.pointCount) {
            kinetics.resource[i] += delta[i]
        }

        for (i in 0 until state.pointCount) {
            if (!kinetics.alive[i]) continue
            val gradient = Vec3(0f, 0f, 0f)
            for (edge in edgeNeighbors(i, edges)) {
                val j = otherIndex(i, edge)
                val dir = Vec3(state.points[j]).sub(state.points[i])
                if (dir.lengthSquared() <= 1e-12f) continue
                dir.normalize()
                val sign = if (downhill) {
                    kinetics.resource[i] - kinetics.resource[j]
                } else {
                    kinetics.resource[j] - kinetics.resource[i]
                }
                gradient.add(dir.mul((sign * edge.weight).toFloat()))
            }

            val velocity = kinetics.velocity[i]
            val accel = Vec3(gradient).mul(chemoGain.toFloat())
            val newVelocity = clampLength(
                Vec3(velocity)
                    .mul((1.0 - damping * dt.toDouble()).toFloat())
                    .add(accel.mul(dt)),
                maxSpeed
            )
            kinetics.velocity[i] = newVelocity
            state.points[i] = Vec3(state.points[i]).add(Vec3(newVelocity).mul(dt))
        }
    }
}

class AddNearLowDensityProcess(
    var probeRadius: Double = 0.10,
    var attemptsPerStep: Int = 1,
    var offsetScale: Double = 0.06
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)
        repeat(attemptsPerStep) {
            val sparse = (0 until state.pointCount)
                .filter { kinetics.alive[it] }
                .minByOrNull { spatialHash.sample(state.points[it], probeRadius).size }
                ?: return

            val dir = vec(
                cos((state.time + sparse).toDouble()),
                sin((0.7f * state.time + sparse).toDouble()),
                cos((1.3f * state.time - sparse).toDouble())
            ).normalize()

            val newIndex = state.points.size
            state.points += Vec3(state.points[sparse]).add(Vec3(dir).mul(offsetScale.toFloat()))

            val mutableLabels = if (state.labels != null) requireMutableLabels(state) else null
            if (mutableLabels != null && mutableLabels.size > sparse) {
                mutableLabels += mutableLabels[sparse]
            }

            edges.ensurePointCount(state.points.size)
            kinetics.ensureSize(state.points.size)
            kinetics.velocity[newIndex] = Vec3(dir).mul(0.02f)
            kinetics.resource[newIndex] = kinetics.resource[sparse] * 0.5
            kinetics.alive[newIndex] = true
        }
    }
}

class AddNearHighDensityProcess(
    var probeRadius: Double = 0.10,
    var minNeighbors: Int = 8,
    var attemptsPerStep: Int = 1,
    var jitterScale: Double = 0.02
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)
        repeat(attemptsPerStep) {
            val dense = (0 until state.pointCount)
                .filter { kinetics.alive[it] }
                .maxByOrNull { spatialHash.sample(state.points[it], probeRadius).size }
                ?: return

            if (spatialHash.sample(state.points[dense], probeRadius).size < minNeighbors) return

            val jitter = vec(
                cos((state.time * 1.7f + dense).toDouble()) * jitterScale,
                sin((state.time * 1.1f - dense).toDouble()) * jitterScale,
                cos((state.time * 0.9f + 0.37f * dense).toDouble()) * jitterScale
            )

            val newIndex = state.points.size
            state.points += Vec3(state.points[dense]).add(jitter)

            val mutableLabels = if (state.labels != null) requireMutableLabels(state) else null
            if (mutableLabels != null && mutableLabels.size > dense) {
                mutableLabels += mutableLabels[dense]
            }

            edges.ensurePointCount(state.points.size)
            kinetics.ensureSize(state.points.size)
            kinetics.velocity[newIndex] = Vec3(jitter)
            kinetics.resource[newIndex] = kinetics.resource[dense] * 0.35
            kinetics.alive[newIndex] = true
        }
    }
}

class RelaxToMidpointProcess(
    var gain: Double = 0.12,
    var degreeFloor: Int = 1
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)
        val updated = MutableList(state.pointCount) { state.points[it] }

        for (i in 0 until state.pointCount) {
            val incident = edgeNeighbors(i, edges)
            if (incident.size < degreeFloor) continue
            val neighbors = incident.map { state.points[otherIndex(i, it)] }
            val midpoint = averageVec3(neighbors)
            updated[i] = Vec3(state.points[i]).add(Vec3(midpoint).sub(state.points[i]).mul((gain * dt).toFloat()))
        }

        for (i in 0 until state.pointCount) {
            state.points[i] = updated[i]
        }
    }
}

/**
 * Kills, and then compacts away, every point below [minDegree] incident edges or below [minResource]
 * resource.
 *
 * The default [minResource] cannot fire (`resource < -Double.MAX_VALUE` never holds), so with the
 * default the prune is by degree alone. `minDegree = 0` is for pruning by resource alone; with both
 * left at their inert values (`minDegree = 0` and the default [minResource]) it marks nothing.
 */
class PruneIsolatedPointsProcess(
    var minDegree: Int = 1,
    var minResource: Double = -Double.MAX_VALUE
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)
        for (i in 0 until state.pointCount) {
            val degree = edgeNeighbors(i, edges).size
            if (degree < minDegree || kinetics.resource[i] < minResource) {
                kinetics.alive[i] = false
            }
        }
        removeDeadPointsInPlace(state, kinetics, edges)
    }
}

class JitterProcess(
    var amplitude: Double = 0.005
) : DynamicPointProcess {
    override fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        kinetics.ensureSize(state.pointCount)
        for (i in 0 until state.pointCount) {
            if (!kinetics.alive[i]) continue
            val jitter = vec(
                cos((state.time * 1.7f + i).toDouble()) * amplitude * dt,
                sin((state.time * 1.1f - i).toDouble()) * amplitude * dt,
                cos((state.time * 0.9f + 0.37f * i).toDouble()) * amplitude * dt
            )
            state.points[i] = Vec3(state.points[i]).add(jitter)
        }
    }
}

class DynamicProcessPipeline(
    var processes: List<DynamicPointProcess>
) {
    fun step(
        dt: Float,
        state: DynamicPointState,
        kinetics: DynamicPointKinetics,
        edges: DynamicEdgeList,
        spatialHash: Vec3SpatialHash
    ) {
        for (process in processes) {
            // A stage that grew the cloud leaves the next stage indexing points the store has never
            // heard of; a stage that shrank it has already called setPointCount itself.
            edges.ensurePointCount(state.pointCount)
            kinetics.ensureSize(state.pointCount)
            spatialHash.rebuild(state.points)
            process.step(dt, state, kinetics, edges, spatialHash)
        }
    }
}

/**
 * One whole frame of morphogenesis: build and decay the graph with [edgeSystem], then run five
 * standard processes over it in order — diffuse resource and climb its gradient, relax the springs,
 * grow tips, merge what has collided, smooth.
 *
 * **Read [DirectedTipGrowthProcess] before using this.** The composition has a growth rule and no
 * death rule (it used to end with a `PruneIsolatedPointsProcess(minDegree = 0)`, which could not mark
 * anything, so removing it changed nothing). On the 40-point ring in `DynamicGraphSystemsTest` the
 * population is exactly 40 for 109 frames and then multiplies by about 1.05 per frame: 3603 points by
 * frame 199. [maxPointCount] is the one knob that bounds it; it defaults to no ceiling. Add a
 * [PruneIsolatedPointsProcess] to your own pipeline if points should die. A real piece is better served by writing its own
 * [DynamicProcessPipeline] — the point of this function is to show what the pieces are and what order
 * they go in.
 *
 * @param maxPointCount forwarded to [DirectedTipGrowthProcess.maxPointCount]
 */
fun exampleDynamicMorphogenesisStep(
    state: DynamicPointState,
    kinetics: DynamicPointKinetics,
    edges: DynamicEdgeList,
    spatialHash: Vec3SpatialHash,
    edgeSystem: DynamicGraphSystem,
    dt: Float = 1f / 60f,
    maxPointCount: Int = Int.MAX_VALUE
) {
    edges.ensurePointCount(state.pointCount)
    kinetics.ensureSize(state.pointCount)
    spatialHash.rebuild(state.points)
    edgeSystem.step(dt, state, edges, spatialHash)
    edges.decayExponential(dt, 0.35)

    val pipeline = DynamicProcessPipeline(
        listOf(
            SignalDiffusionAndChemotaxisProcess(),
            SpringMotionProcess(),
            DirectedTipGrowthProcess(maxPointCount = maxPointCount),
            MergeClosePointsProcess(),
            RelaxToMidpointProcess(gain = 0.08)
        )
    )
    pipeline.step(dt, state, kinetics, edges, spatialHash)
}
