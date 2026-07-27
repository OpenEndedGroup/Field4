@file:afield.CompletionCategory("Data")

package afield.stdlib

import kotlin.math.max

/**
 * A 3-regular graph that grows by local rewriting. Every node in a [GrowingGraph] has exactly
 * three neighbours and one bit of state, and [GrowingGraph.grow] alternates two phases: compute
 * each node's next state and a "dividing" flag by indexing an integer rule with
 * `neighbour-count + 4 × state`, then apply a division to each flagged node that adds two nodes
 * and rewires their edges.
 *
 * Seed the adjacency with [GrowingGraph.setNeighbors] (it must be a consistent 3-regular graph or
 * the rewrite throws), set states with [GrowingGraph.setState] or [GrowingGraph.randomizeStates],
 * then call `grow()` per step and use the returned [GrowingGraph.GrowDelta] together with
 * [GrowingGraph.newEdgeU] / [GrowingGraph.newEdgeV] to draw only what changed. Storage is flat
 * `IntArray`/`ByteArray` with no per-node allocation and no allocation in the inner loops except
 * when capacity doubles; pure Kotlin, single-threaded, with a built-in xorshift64* generator for
 * the optional `flipProb` noise. [main] is a runnable 10-node demo.
 */

/**
 * Self-contained Kotlin port of the "GrowingGraph" algorithm:
 * - 3-regular graph: each node has exactly 3 neighbors
 * - Binary state per node (0/1)
 * - Two-phase grow():
 *    Phase 0: compute next state + "dividing" flag from local neighborhood
 *    Phase 1: apply local rewrite (division) that adds 2 nodes and rewires edges
 *
 * "Garbage-lite" notes:
 * - Neighbors stored in one flat IntArray (3 * capacity), no per-node arrays.
 * - States/dividing stored in ByteArray (0/1).
 * - No allocations in the inner loops of grow(), except when capacity must expand.
 */
class GrowingGraph(
    initialNodeCount: Int,
    rule: Int = 2182,
    flipProb: Double = 0.0,
    seed: Long = 1L
) {
    private val NN = 3
    private val CASE_N = 8

    // Graph size (active nodes) and capacity (allocated size)
    var nodeCount: Int = initialNodeCount
        private set
    private var capacity: Int = max(4, initialNodeCount)

    // Flat neighbors: neighbors[node*3 + k] is the k-th neighbor of node
    private var neighbors: IntArray = IntArray(NN * capacity)

    // State buffers; swapped each phase-0
    private var states: ByteArray = ByteArray(capacity)     // current
    private var nextStates: ByteArray = ByteArray(capacity) // scratch

    // Dividing flags (computed in phase 0, consumed in phase 1)
    private var dividing: ByteArray = ByteArray(capacity)

    // Rule and noise
    var rule: Int = rule
    var flipProb: Double = flipProb

    // Phase toggles: false -> phase 0, true -> phase 1
    private var phase: Boolean = false

    data class GrowDelta(
        val newNodeStart: Int,
        val newNodeCount: Int,
        val newEdgeStart: Int,
        val newEdgeCount: Int
    )

    private var edgeU: IntArray = IntArray(16)
    private var edgeV: IntArray = IntArray(16)
    private var edgeCount: Int = 0

    val newEdgeU: IntArray get() = edgeU
    val newEdgeV: IntArray get() = edgeV

    private val rng = XorShift64Star(seed)

    /** Set neighbor list for a node (must be exactly 3 neighbors). */
    fun setNeighbors(node: Int, a: Int, b: Int, c: Int) {
        val base = node * NN
        neighbors[base] = a
        neighbors[base + 1] = b
        neighbors[base + 2] = c
    }

    /** Get the neighbor at slot 0..2 for a node. */
    fun neighborAt(node: Int, slot: Int): Int {
        require(slot in 0 until NN) { "slot must be 0..2" }
        return neighbors[node * NN + slot]
    }

    /** Get state (0/1). */
    fun stateOf(node: Int): Int = (states[node].toInt() and 1)

    /** Set state (0/1). */
    fun setState(node: Int, s: Int) {
        states[node] = (s and 1).toByte()
    }

    /** Randomize initial states with probability p of being 1. */
    fun randomizeStates(p: Double) {
        for (i in 0 until nodeCount) {
            states[i] = if (rng.nextDouble01() < p) 1 else 0
        }
    }

    /**
     * One step. Alternates between:
     * - Phase 0: compute next states + dividing flags from current graph
     * - Phase 1: apply division rewrites for nodes with dividing=1
     */
    fun grow(): GrowDelta {
        phase = !phase
        if (!phase) {
            // Phase 0: compute cases -> nextStates + dividing, then swap state buffers
            computeNextStatesAndDividing()
            val tmp = states
            states = nextStates
            nextStates = tmp
            edgeCount = 0
            return GrowDelta(0, 0, 0, 0)
        } else {
            // Phase 1: apply rewrites in increasing node order
            val newNodeStart = nodeCount
            edgeCount = 0
            var i = 0
            while (i < nodeCount) {
                if ((dividing[i].toInt() and 1) != 0) {
                    divideAt(i)
                    dividing[i] = 0
                }
                i++
            }
            return GrowDelta(newNodeStart, nodeCount - newNodeStart, 0, edgeCount)
        }
    }

    // -------------------- internals --------------------

    private fun computeNextStatesAndDividing() {
        val r0 = rule
        val fp = flipProb

        for (i in 0 until nodeCount) {
            val base = i * NN
            val n0 = neighbors[base]
            val n1 = neighbors[base + 1]
            val n2 = neighbors[base + 2]

            val s0 = states[n0].toInt() and 1
            val s1 = states[n1].toInt() and 1
            val s2 = states[n2].toInt() and 1
            val nc = s0 + s1 + s2

            val si = states[i].toInt() and 1
            val r = nc + (si shl 2) // nc + state*4 (since NN=3 => NN+1=4)

            var ns = (r0 ushr r) and 1
            if (fp > 0.0 && rng.nextDouble01() < fp) ns = ns xor 1

            val div = (r0 ushr (r + CASE_N)) and 1

            nextStates[i] = ns.toByte()
            dividing[i] = div.toByte()
        }
    }

    /**
     * Local rewrite (division) for node i with neighbors [a,b,c]:
     *
     * Before: i -- a, i -- b, i -- c
     * After:
     *   i -> [a, j, k]
     *   j -> [i, b, k]
     *   k -> [i, j, c]
     *   b replaces neighbor i with j
     *   c replaces neighbor i with k
     *   states[j] = states[i], states[k] = states[i]
     */
    private fun divideAt(i: Int) {
        val base = i * NN
        val a = neighbors[base]
        val b = neighbors[base + 1]
        val c = neighbors[base + 2]

        val j = nodeCount
        val k = nodeCount + 1

        ensureCapacity(nodeCount + 2)

        // Rewire i
        neighbors[base] = a
        neighbors[base + 1] = j
        neighbors[base + 2] = k

        // Create j
        setNeighbors(j, i, b, k)

        // Create k
        setNeighbors(k, i, j, c)

        // Reconnect b: replace i with j
        replaceNeighbor(b, oldNeighbor = i, newNeighbor = j)

        // Reconnect c: replace i with k
        replaceNeighbor(c, oldNeighbor = i, newNeighbor = k)

        // Track new edges (undirected pairs)
        appendEdge(i, j)
        appendEdge(i, k)
        appendEdge(j, b)
        appendEdge(j, k)
        appendEdge(k, c)

        // Copy state from parent
        states[j] = states[i]
        states[k] = states[i]

        // New nodes shouldn't immediately divide unless later computed in phase 0
        dividing[j] = 0
        dividing[k] = 0

        nodeCount += 2
    }

    private fun replaceNeighbor(node: Int, oldNeighbor: Int, newNeighbor: Int) {
        val base = node * NN
        // Degree is fixed at 3, so linear scan is tiny and allocation-free.
        if (neighbors[base] == oldNeighbor) {
            neighbors[base] = newNeighbor
            return
        }
        if (neighbors[base + 1] == oldNeighbor) {
            neighbors[base + 1] = newNeighbor
            return
        }
        if (neighbors[base + 2] == oldNeighbor) {
            neighbors[base + 2] = newNeighbor
            return
        }
        // If this triggers, your graph isn't consistent 3-regular adjacency.
        throw IllegalStateException("Neighbor $oldNeighbor not found in node $node")
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= capacity) return
        var newCap = capacity
        while (newCap < minCapacity) newCap = newCap * 2

        // Expand arrays (rare; not in steady-state inner loops)
        val newNeighbors = IntArray(NN * newCap)
        neighbors.copyInto(newNeighbors, endIndex = NN * capacity)
        neighbors = newNeighbors

        val newStates = ByteArray(newCap)
        states.copyInto(newStates, endIndex = capacity)
        states = newStates

        val newNextStates = ByteArray(newCap)
        nextStates.copyInto(newNextStates, endIndex = capacity)
        nextStates = newNextStates

        val newDividing = ByteArray(newCap)
        dividing.copyInto(newDividing, endIndex = capacity)
        dividing = newDividing

        capacity = newCap
    }

    private fun appendEdge(a: Int, b: Int) {
        if (edgeCount >= edgeU.size) {
            val newCap = max(4, edgeU.size * 2)
            edgeU = edgeU.copyOf(newCap)
            edgeV = edgeV.copyOf(newCap)
        }
        edgeU[edgeCount] = a
        edgeV[edgeCount] = b
        edgeCount++
    }

    // Simple fast RNG: xorshift64* (not crypto-secure)
    private class XorShift64Star(seed: Long) {
        private var x: Long = if (seed != 0L) seed else 0x9E3779B97FA7C15L

        private fun nextLong(): Long {
            var z = x
            z = z xor (z ushr 12)
            z = z xor (z shl 25)
            z = z xor (z ushr 27)
            x = z
            return z * 2685821657736338717L
        }

        fun nextDouble01(): Double {
            // Use top 53 bits to make a double in [0,1)
            val v = nextLong() ushr 11
            return v.toDouble() * (1.0 / (1L shl 53).toDouble())
        }
    }
}

/** Small demo: start from K4 (tetrahedron), run a few steps, print size/state stats. */
fun main() {
    // K4 is 3-regular: each of 4 nodes connects to the other 3.
    val g = GrowingGraph(initialNodeCount = 10, rule = 2182, flipProb = 0.0, seed = 1234L)
    g.setNeighbors(0, 9, 1, 2)
    g.setNeighbors(1, 0, 2, 4)
    g.setNeighbors(2, 1, 3, 0)
    g.setNeighbors(3, 2, 4, 6)
    g.setNeighbors(4, 3, 5, 1)
    g.setNeighbors(5, 4, 6, 8)
    g.setNeighbors(6, 5, 7, 3)
    g.setNeighbors(7, 6, 8, 9)
    g.setNeighbors(8, 7, 9, 5)
    g.setNeighbors(9, 8, 0, 7)

    // Same states as the JS example:
    // this.states = [0,0,0,1,0, 1,0,1,1,1];
    val initStates = intArrayOf(0, 0, 0, 1, 0, 1, 0, 1, 1, 1)
    for (i in 0 until 10) g.setState(i, initStates[i])

    repeat(100) { step ->
        g.grow()
        // Count live nodes (tiny loop; for demo only)
        var live = 0
        for (i in 0 until g.nodeCount) live += g.stateOf(i)
        println("step=${step + 1} phase=${(step + 1) % 2} nodes=${g.nodeCount} live=$live")
    }
}
