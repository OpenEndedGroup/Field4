package afield.four.physics

import afield.graphics.FLine
import afield.stdlib.vec
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Octree + Barnes-Hut utilities for SoA x/y/z particle data.
 * Designed to minimize per-step allocations.
 * Mutating operations (`build`, `clear`) are not thread-safe.
 */
class OctreeBarnesHut(
    initialCapacity: Int = 1024,
    private val allowParallelBuild: Boolean = true
) {

    private var capacity = max(1, initialCapacity)

    private var cx = FloatArray(capacity)
    private var cy = FloatArray(capacity)
    private var cz = FloatArray(capacity)
    private var half = FloatArray(capacity)

    private var mass = FloatArray(capacity)
    private var comx = FloatArray(capacity)
    private var comy = FloatArray(capacity)
    private var comz = FloatArray(capacity)

    private var leafIndex = IntArray(capacity) { -1 }
    private var children = IntArray(capacity * 8) { -1 }

    private var stack = IntArray(capacity)
    private var stackVisited = IntArray(capacity)
    private val forceStackTLS = ThreadLocal.withInitial { IntArray(max(1, capacity)) }
    private var parallelScratchTrees: Array<OctreeBarnesHut>? = null

    var nodeCount: Int = 0
        private set

    fun clear() {
        nodeCount = 0
    }

    fun build(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        massIn: FloatArray?,
        invMass: FloatArray?,
        count: Int,
        buildExecutor: SimdVerlet.ForceExecutor? = null,
        minParallelCount: Int = 8192
    ) {
        if (count <= 0) {
            nodeCount = 0
            return
        }

        ensureCapacity(max(1, count * 2))

        var minX = x[0]
        var minY = y[0]
        var minZ = z[0]
        var maxX = x[0]
        var maxY = y[0]
        var maxZ = z[0]

        for (i in 1 until count) {
            val xi = x[i]
            val yi = y[i]
            val zi = z[i]
            if (xi < minX) minX = xi
            if (yi < minY) minY = yi
            if (zi < minZ) minZ = zi
            if (xi > maxX) maxX = xi
            if (yi > maxY) maxY = yi
            if (zi > maxZ) maxZ = zi
        }

        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val centerZ = (minZ + maxZ) * 0.5f

        val extentX = maxX - minX
        val extentY = maxY - minY
        val extentZ = maxZ - minZ
        val maxExtent = max(extentX, max(extentY, extentZ))
        val rootHalf = max(1.0e-3f, maxExtent * 0.5f)

        nodeCount = 1
        cx[0] = centerX
        cy[0] = centerY
        cz[0] = centerZ
        half[0] = rootHalf
        leafIndex[0] = -1
        zeroChildren(0)

        val executor = buildExecutor
        if (!allowParallelBuild || executor == null || count < minParallelCount) {
            buildFromContiguousRange(x, y, z, massIn, invMass, count)
            return
        }

        val bucketCounts = IntArray(8)
        for (i in 0 until count) {
            val slot =
                (if (x[i] >= centerX) 1 else 0) or
                    ((if (y[i] >= centerY) 1 else 0) shl 1) or
                    ((if (z[i] >= centerZ) 1 else 0) shl 2)
            bucketCounts[slot]++
        }

        val bucketIndices = Array(8) { IntArray(bucketCounts[it]) }
        val bucketWrite = IntArray(8)
        for (i in 0 until count) {
            val slot =
                (if (x[i] >= centerX) 1 else 0) or
                    ((if (y[i] >= centerY) 1 else 0) shl 1) or
                    ((if (z[i] >= centerZ) 1 else 0) shl 2)
            bucketIndices[slot][bucketWrite[slot]++] = i
        }

        val scratchTrees = ensureParallelScratchTrees()
        val childHalf = rootHalf * 0.5f
        executor.runRanges(n = 8, minChunk = 1) { start, endExclusive ->
            for (slot in start until endExclusive) {
                val bucketSize = bucketCounts[slot]
                if (bucketSize == 0) continue
                val sx = if ((slot and 1) != 0) 1f else -1f
                val sy = if ((slot and 2) != 0) 1f else -1f
                val sz = if ((slot and 4) != 0) 1f else -1f
                val subtree = scratchTrees[slot]
                subtree.buildFromIndices(
                    x = x,
                    y = y,
                    z = z,
                    massIn = massIn,
                    invMass = invMass,
                    indices = bucketIndices[slot],
                    indexCount = bucketSize,
                    rootCenterX = centerX + sx * childHalf,
                    rootCenterY = centerY + sy * childHalf,
                    rootCenterZ = centerZ + sz * childHalf,
                    rootHalf = childHalf
                )
            }
        }

        zeroChildren(0)
        leafIndex[0] = -1
        var nextNode = 1
        for (slot in 0 until 8) {
            val bucketSize = bucketCounts[slot]
            if (bucketSize == 0) continue
            val subtree = scratchTrees[slot]
            val subtreeNodeCount = subtree.nodeCount
            if (subtreeNodeCount == 0) continue

            ensureCapacity(nextNode + subtreeNodeCount)
            val offset = nextNode
            copySubtreeInto(subtree, offset)
            children[slot] = offset
            nextNode += subtreeNodeCount
        }
        nodeCount = nextNode
        computeRootMassAndCenter(centerX, centerY, centerZ)
    }

    private fun buildFromContiguousRange(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        massIn: FloatArray?,
        invMass: FloatArray?,
        count: Int
    ) {
        for (i in 0 until count) {
            insert(0, i, x, y, z)
        }
        computeMassAndCenter(x, y, z, massIn, invMass)
    }

    private fun buildFromIndices(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        massIn: FloatArray?,
        invMass: FloatArray?,
        indices: IntArray,
        indexCount: Int,
        rootCenterX: Float,
        rootCenterY: Float,
        rootCenterZ: Float,
        rootHalf: Float
    ) {
        if (indexCount <= 0) {
            nodeCount = 0
            return
        }

        ensureCapacity(max(1, indexCount * 2))
        nodeCount = 1
        cx[0] = rootCenterX
        cy[0] = rootCenterY
        cz[0] = rootCenterZ
        half[0] = rootHalf
        leafIndex[0] = -1
        zeroChildren(0)

        for (k in 0 until indexCount) {
            insert(0, indices[k], x, y, z)
        }

        computeMassAndCenter(x, y, z, massIn, invMass)
    }

    private fun copySubtreeInto(subtree: OctreeBarnesHut, dstOffset: Int) {
        val subCount = subtree.nodeCount
        for (local in 0 until subCount) {
            val dst = dstOffset + local
            cx[dst] = subtree.cx[local]
            cy[dst] = subtree.cy[local]
            cz[dst] = subtree.cz[local]
            half[dst] = subtree.half[local]
            mass[dst] = subtree.mass[local]
            comx[dst] = subtree.comx[local]
            comy[dst] = subtree.comy[local]
            comz[dst] = subtree.comz[local]
            leafIndex[dst] = subtree.leafIndex[local]

            val srcBase = local * 8
            val dstBase = dst * 8
            for (c in 0 until 8) {
                val child = subtree.children[srcBase + c]
                children[dstBase + c] = if (child >= 0) child + dstOffset else -1
            }
        }
    }

    private fun computeRootMassAndCenter(defaultCx: Float, defaultCy: Float, defaultCz: Float) {
        var m = 0f
        var cxAcc = 0f
        var cyAcc = 0f
        var czAcc = 0f
        for (slot in 0 until 8) {
            val child = children[slot]
            if (child < 0) continue
            val mc = mass[child]
            if (mc == 0f) continue
            m += mc
            cxAcc += comx[child] * mc
            cyAcc += comy[child] * mc
            czAcc += comz[child] * mc
        }
        mass[0] = m
        if (m != 0f) {
            val inv = 1f / m
            comx[0] = cxAcc * inv
            comy[0] = cyAcc * inv
            comz[0] = czAcc * inv
        } else {
            comx[0] = defaultCx
            comy[0] = defaultCy
            comz[0] = defaultCz
        }
    }

    private fun ensureParallelScratchTrees(): Array<OctreeBarnesHut> {
        val existing = parallelScratchTrees
        if (existing != null) return existing
        val created = Array(8) {
            OctreeBarnesHut(initialCapacity = max(32, capacity / 4), allowParallelBuild = false)
        }
        parallelScratchTrees = created
        return created
    }

    fun applyForces(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        ax: FloatArray,
        ay: FloatArray,
        az: FloatArray,
        massIn: FloatArray?,
        invMass: FloatArray?,
        count: Int,
        theta: Float,
        softening: Float,
        g: Float,
        forceExecutor: SimdVerlet.ForceExecutor? = null,
        minChunk: Int = 512
    ) {
        if (nodeCount == 0 || count == 0) return

        val theta2 = theta * theta
        val eps2 = softening * softening

        if (forceExecutor == null || count <= minChunk) {
            ensureStackCapacity(nodeCount)
            applyForcesRange(
                x = x,
                y = y,
                z = z,
                ax = ax,
                ay = ay,
                az = az,
                theta2 = theta2,
                eps2 = eps2,
                g = g,
                start = 0,
                endExclusive = count,
                stackLocal = stack
            )
            return
        }

        forceExecutor.runForces(n = count, minChunk = minChunk) { start, endExclusive ->
            val stackLocal = threadLocalForceStack(nodeCount)
            applyForcesRange(
                x = x,
                y = y,
                z = z,
                ax = ax,
                ay = ay,
                az = az,
                theta2 = theta2,
                eps2 = eps2,
                g = g,
                start = start,
                endExclusive = endExclusive,
                stackLocal = stackLocal
            )
        }
    }

    private fun applyForcesRange(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        ax: FloatArray,
        ay: FloatArray,
        az: FloatArray,
        theta2: Float,
        eps2: Float,
        g: Float,
        start: Int,
        endExclusive: Int,
        stackLocal: IntArray
    ) {
        for (i in start until endExclusive) {
            var sp = 0
            stackLocal[sp++] = 0

            val xi = x[i]
            val yi = y[i]
            val zi = z[i]

            var fx = 0f
            var fy = 0f
            var fz = 0f

            while (sp > 0) {
                val node = stackLocal[--sp]
                val m = mass[node]
                if (m == 0f) continue

                val li = leafIndex[node]
                if (li == i && li >= 0) continue

                val dx = comx[node] - xi
                val dy = comy[node] - yi
                val dz = comz[node] - zi

                val dist2 = dx * dx + dy * dy + dz * dz + eps2

                val h = half[node]
                val size2 = (h + h) * (h + h)

                if (li >= 0 || size2 / dist2 < theta2) {
                    val invDist = 1.0f / sqrt(dist2)
                    val invDist3 = invDist * invDist * invDist
                    val f = g * m * invDist3
                    fx += dx * f
                    fy += dy * f
                    fz += dz * f
                } else {
                    val base = node * 8
                    for (c in 0 until 8) {
                        val child = children[base + c]
                        if (child >= 0) {
                            stackLocal[sp++] = child
                        }
                    }
                }
            }

            ax[i] += fx
            ay[i] += fy
            az[i] += fz
        }
    }

    private fun threadLocalForceStack(minCapacity: Int): IntArray {
        var local = forceStackTLS.get()
        if (local.size < minCapacity) {
            local = IntArray(minCapacity)
            forceStackTLS.set(local)
        }
        return local
    }

    fun forEachCuboid(depthLimit: Int = Int.MAX_VALUE, consumer: (cx: Float, cy: Float, cz: Float, half: Float) -> Unit) {
        if (nodeCount == 0) return
        ensureStackCapacity(nodeCount)

        var sp = 0
        stack[sp] = 0
        stackVisited[sp] = 0
        sp++

        while (sp > 0) {
            sp--
            val node = stack[sp]
            val depth = stackVisited[sp]

            consumer(cx[node], cy[node], cz[node], half[node])

            if (depth >= depthLimit) continue

            val base = node * 8
            val nextDepth = depth + 1
            for (c in 0 until 8) {
                val child = children[base + c]
                if (child >= 0) {
                    stack[sp] = child
                    stackVisited[sp] = nextDepth
                    sp++
                }
            }
        }
    }

    fun appendCuboidsTo(out: FLine, depthLimit: Int = Int.MAX_VALUE) {
        forEachCuboid(depthLimit) { cxi, cyi, czi, hi ->
            appendCuboidTo(out, cxi, cyi, czi, hi)
        }
    }

    private fun insert(node: Int, index: Int, x: FloatArray, y: FloatArray, z: FloatArray) {
        val li = leafIndex[node]
        val base = node * 8
        val hasChildren = children[base] >= 0

        if (!hasChildren && li < 0) {
            leafIndex[node] = index
            return
        }

        if (!hasChildren && li >= 0) {
            val oldIndex = li
            leafIndex[node] = -1
            createChildren(node)
            insertChild(node, oldIndex, x, y, z)
        }

        insertChild(node, index, x, y, z)
    }

    private fun insertChild(node: Int, index: Int, x: FloatArray, y: FloatArray, z: FloatArray) {
        val ox = x[index]
        val oy = y[index]
        val oz = z[index]

        val cxn = cx[node]
        val cyn = cy[node]
        val czn = cz[node]
        val h = half[node] * 0.5f

        val ix = if (ox >= cxn) 1 else 0
        val iy = if (oy >= cyn) 1 else 0
        val iz = if (oz >= czn) 1 else 0
        val childSlot = ix or (iy shl 1) or (iz shl 2)

        val childIndex = getOrCreateChild(node, childSlot, h)
        insert(childIndex, index, x, y, z)
    }

    private fun getOrCreateChild(node: Int, slot: Int, childHalf: Float): Int {
        val base = node * 8
        var child = children[base + slot]
        if (child >= 0) return child

        child = nodeCount++
        ensureCapacity(nodeCount)

        val sx = if ((slot and 1) != 0) 1f else -1f
        val sy = if ((slot and 2) != 0) 1f else -1f
        val sz = if ((slot and 4) != 0) 1f else -1f

        cx[child] = cx[node] + sx * childHalf
        cy[child] = cy[node] + sy * childHalf
        cz[child] = cz[node] + sz * childHalf
        half[child] = childHalf
        leafIndex[child] = -1
        zeroChildren(child)
        children[base + slot] = child
        return child
    }

    private fun createChildren(node: Int) {
        val h = half[node] * 0.5f
        for (slot in 0 until 8) {
            getOrCreateChild(node, slot, h)
        }
    }

    private fun computeMassAndCenter(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        massIn: FloatArray?,
        invMass: FloatArray?
    ) {
        var sp = 0
        stack[sp] = 0
        stackVisited[sp] = 0
        sp++

        while (sp > 0) {
            sp--
            val node = stack[sp]
            val visited = stackVisited[sp]

            if (visited == 0) {
                stack[sp] = node
                stackVisited[sp] = 1
                sp++

                val base = node * 8
                for (c in 0 until 8) {
                    val child = children[base + c]
                    if (child >= 0) {
                        stack[sp] = child
                        stackVisited[sp] = 0
                        sp++
                    }
                }
                continue
            }

            val li = leafIndex[node]
            if (li >= 0) {
                val m = when {
                    massIn != null -> massIn[li]
                    invMass != null -> if (invMass[li] != 0f) 1f / invMass[li] else 0f
                    else -> 1f
                }
                mass[node] = m
                comx[node] = x[li]
                comy[node] = y[li]
                comz[node] = z[li]
                continue
            }

            var m = 0f
            var cxAcc = 0f
            var cyAcc = 0f
            var czAcc = 0f

            val base = node * 8
            for (c in 0 until 8) {
                val child = children[base + c]
                if (child >= 0) {
                    val mc = mass[child]
                    if (mc != 0f) {
                        m += mc
                        cxAcc += comx[child] * mc
                        cyAcc += comy[child] * mc
                        czAcc += comz[child] * mc
                    }
                }
            }

            mass[node] = m
            if (m != 0f) {
                val inv = 1f / m
                comx[node] = cxAcc * inv
                comy[node] = cyAcc * inv
                comz[node] = czAcc * inv
            } else {
                comx[node] = cx[node]
                comy[node] = cy[node]
                comz[node] = cz[node]
            }
        }
    }

    private fun zeroChildren(node: Int) {
        val base = node * 8
        for (i in 0 until 8) {
            children[base + i] = -1
        }
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= capacity) return

        var newCap = capacity
        while (newCap < minCapacity) {
            newCap = max(1, newCap + newCap / 2)
        }

        cx = cx.copyOf(newCap)
        cy = cy.copyOf(newCap)
        cz = cz.copyOf(newCap)
        half = half.copyOf(newCap)

        mass = mass.copyOf(newCap)
        comx = comx.copyOf(newCap)
        comy = comy.copyOf(newCap)
        comz = comz.copyOf(newCap)

        leafIndex = leafIndex.copyOf(newCap)
        children = children.copyOf(newCap * 8)

        stack = stack.copyOf(newCap)
        stackVisited = stackVisited.copyOf(newCap)

        for (i in nodeCount until newCap) {
            leafIndex[i] = -1
        }

        capacity = newCap
    }

    private fun ensureStackCapacity(minCapacity: Int) {
        if (minCapacity <= stack.size) return
        stack = stack.copyOf(minCapacity)
        stackVisited = stackVisited.copyOf(minCapacity)
    }
}

private fun appendCuboidTo(out: FLine, cx: Float, cy: Float, cz: Float, half: Float) {
    val minX = cx - half
    val minY = cy - half
    val minZ = cz - half
    val maxX = cx + half
    val maxY = cy + half
    val maxZ = cz + half

    out.moveTo(vec(minX, minY, minZ))
    out.lineTo(vec(maxX, minY, minZ))
    out.lineTo(vec(maxX, maxY, minZ))
    out.lineTo(vec(minX, maxY, minZ))
    out.lineTo(vec(minX, minY, minZ))

    out.moveTo(vec(minX, minY, maxZ))
    out.lineTo(vec(maxX, minY, maxZ))
    out.lineTo(vec(maxX, maxY, maxZ))
    out.lineTo(vec(minX, maxY, maxZ))
    out.lineTo(vec(minX, minY, maxZ))

    out.moveTo(vec(minX, minY, minZ))
    out.lineTo(vec(minX, minY, maxZ))
    out.moveTo(vec(maxX, minY, minZ))
    out.lineTo(vec(maxX, minY, maxZ))
    out.moveTo(vec(maxX, maxY, minZ))
    out.lineTo(vec(maxX, maxY, maxZ))
    out.moveTo(vec(minX, maxY, minZ))
    out.lineTo(vec(minX, maxY, maxZ))
}

class OctreeBarnesHutPool(
    private val maxTrees: Int,
    private val initialCapacity: Int = 1024,
    private val allowGrow: Boolean = true
) {
    private val pool = ArrayList<OctreeBarnesHut>(max(1, maxTrees))

    init {
        repeat(max(1, maxTrees)) {
            pool.add(OctreeBarnesHut(initialCapacity))
        }
    }

    fun acquire(): OctreeBarnesHut {
        if (pool.isNotEmpty()) {
            return pool.removeAt(pool.size - 1)
        }
        if (allowGrow) {
            return OctreeBarnesHut(initialCapacity)
        }
        error("OctreeBarnesHutPool exhausted")
    }

    fun release(tree: OctreeBarnesHut) {
        tree.clear()
        if (pool.size < max(1, maxTrees)) {
            pool.add(tree)
        }
    }
}
