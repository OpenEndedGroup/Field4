package afield.examples

import afield.four.physics.OctreeBarnesHutPool
import afield.four.physics.SimdVerlet
import afield.generator2
import afield.graphics.FLine
import afield.stdlib.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.sqrt

private class ConstraintBuffer(initialSize: Int) {
    private var capacity: Int = max(0, initialSize)

    var n0: IntArray = IntArray(capacity)
    var n1: IntArray = IntArray(capacity)
    var n2: IntArray = IntArray(capacity)
    var r0: FloatArray = FloatArray(capacity)
    var r1: FloatArray = FloatArray(capacity)
    var r2: FloatArray = FloatArray(capacity)
    var s0: FloatArray = FloatArray(capacity)
    var s1: FloatArray = FloatArray(capacity)
    var s2: FloatArray = FloatArray(capacity)

    private var constraints: SimdVerlet.DirectedConstraints3 =
        SimdVerlet.DirectedConstraints3(n0, n1, n2, r0, r1, r2, s0, s1, s2)

    private fun grownCapacity(minCapacity: Int): Int {
        var next = max(16, max(1, capacity))
        while (next < minCapacity) {
            next += max(1, next / 2)
        }
        return next
    }

    fun ensureSize(newSize: Int): SimdVerlet.DirectedConstraints3 {
        require(newSize >= 0) { "newSize must be >= 0" }
        if (newSize <= capacity) return constraints

        capacity = grownCapacity(newSize)
        n0 = IntArray(capacity)
        n1 = IntArray(capacity)
        n2 = IntArray(capacity)
        r0 = FloatArray(capacity)
        r1 = FloatArray(capacity)
        r2 = FloatArray(capacity)
        s0 = FloatArray(capacity)
        s1 = FloatArray(capacity)
        s2 = FloatArray(capacity)
        constraints = SimdVerlet.DirectedConstraints3(n0, n1, n2, r0, r1, r2, s0, s1, s2)
        return constraints
    }
}

private fun updateConstraintsAll(
    graph: GrowingGraph,
    sim: SimdVerlet,
    buffers: ConstraintBuffer,
    stiffness: Float
) {
    val state = sim.state
    val count = state.size
    val n0 = buffers.n0
    val n1 = buffers.n1
    val n2 = buffers.n2
    val r0 = buffers.r0
    val r1 = buffers.r1
    val r2 = buffers.r2
    val s0 = buffers.s0
    val s1 = buffers.s1
    val s2 = buffers.s2

    for (i in 0 until count) {
        val a = graph.neighborAt(i, 0)
        val b = graph.neighborAt(i, 1)
        val c = graph.neighborAt(i, 2)

        n0[i] = a
        n1[i] = b
        n2[i] = c

        val dx0 = state.x[a] - state.x[i]
        val dy0 = state.y[a] - state.y[i]
        val dz0 = state.z[a] - state.z[i]
        val dx1 = state.x[b] - state.x[i]
        val dy1 = state.y[b] - state.y[i]
        val dz1 = state.z[b] - state.z[i]
        val dx2 = state.x[c] - state.x[i]
        val dy2 = state.y[c] - state.y[i]
        val dz2 = state.z[c] - state.z[i]

        r0[i] = max(4.5f, 0.9f*sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0))
        r1[i] = max(4.5f, 0.9f*sqrt(dx1 * dx1 + dy1 * dy1 + dz1 * dz1))
        r2[i] = max(4.5f, 0.9f*sqrt(dx2 * dx2 + dy2 * dy2 + dz2 * dz2))
//        r0[i] = 5f
//        r1[i] = 5f
//        r2[i] = 5f

        s0[i] = stiffness
        s1[i] = stiffness
        s2[i] = stiffness
    }
}

private fun addNewNodes(
    graph: GrowingGraph,
    sim: SimdVerlet,
    newNodeStart: Int,
    newNodeCount: Int
) {
    if (newNodeCount <= 0) return
    val state = sim.state
    val startIndex = sim.addZeroed(newNodeCount)
    require(startIndex == newNodeStart) { "sim index drifted: $startIndex != $newNodeStart" }

    val end = newNodeStart + newNodeCount
    for (i in newNodeStart until end) {
        var cx = 0f
        var cy = 0f
        var cz = 0f
        var c = 0
        for (slot in 0 until 3) {
            val n = graph.neighborAt(i, slot)
            if (n < newNodeStart) {
                cx += state.x[n]
                cy += state.y[n]
                cz += state.z[n]
                c++
            }
        }
        if (c > 0) {
            val inv = 1f / c.toFloat()
            cx *= inv
            cy *= inv
            cz *= inv
        }

        val jitter = 0.05f * ((i * 97) % 3 - 1)
        val x = cx + jitter
        val y = cy - jitter
        val z = cz + jitter * 0.5f

        state.x[i] = x
        state.y[i] = y
        state.z[i] = z
        state.px[i] = x
        state.py[i] = y
        state.pz[i] = z
    }
}

private fun buildEdgeFLine(graph: GrowingGraph, sim: SimdVerlet): FLine {
    val line = FLine()
    val state = sim.state
    val count = state.size
    for (i in 0 until count) {
        for (slot in 0 until 3) {
            val j = graph.neighborAt(i, slot)
            if (j <= i) continue
            line.moveTo(vec(state.x[i], state.y[i], state.z[i]))
            line.lineTo(vec(state.x[j], state.y[j], state.z[j]))
        }
    }
    return line
}

private fun buildEdgeFLine(constraints: SimdVerlet.DirectedConstraints3, sim: SimdVerlet): FLine {
    val line = FLine()
    val state = sim.state
    val count = state.size
    val n0 = constraints.n0
    val n1 = constraints.n1
    val n2 = constraints.n2

    fun emitEdge(i: Int, j: Int) {
        if (j <= i || j >= count) return
        line.moveTo(vec(state.x[i], state.y[i], state.z[i]))
        line.lineTo(vec(state.x[j], state.y[j], state.z[j]))
    }

    for (i in 0 until count) {
        emitEdge(i, n0[i])
        emitEdge(i, n1[i])
        emitEdge(i, n2[i])
    }
    return line
}

private fun buildPointFLine(sim: SimdVerlet, size: Double = 5.0): FLine {
    val line = FLine()
    line.pointed = true
    line.pointSize = size
    val state = sim.state
    val count = state.size
    for (i in 0 until count) {
        line.moveTo(vec(state.x[i], state.y[i], state.z[i]))
    }
    return line
}

private fun scheduleGrow(
    executor: ExecutorService,
    graph: GrowingGraph
): Future<GrowingGraph.GrowDelta> = executor.submit<GrowingGraph.GrowDelta> { graph.grow() }

private class SequencePerf(private val reportEveryFrames: Int) {
    private var frames = 0
    private var growthNs = 0L
    private var simNs = 0L
    private var edgeBuildNs = 0L
    private var pointBuildNs = 0L
    private var totalNs = 0L
    private var growthAppliedFrames = 0
    private var maxNodes = 0

    private fun ms(ns: Long): Double = ns.toDouble() / 1_000_000.0

    fun record(
        nodeCount: Int,
        growthApplied: Boolean,
        growthNs: Long,
        simNs: Long,
        edgeBuildNs: Long,
        pointBuildNs: Long,
        totalNs: Long
    ) {
        frames++
        if (growthApplied) growthAppliedFrames++
        if (nodeCount > maxNodes) maxNodes = nodeCount
        this.growthNs += growthNs
        this.simNs += simNs
        this.edgeBuildNs += edgeBuildNs
        this.pointBuildNs += pointBuildNs
        this.totalNs += totalNs

        if (frames % reportEveryFrames == 0) {
            val f = reportEveryFrames.toDouble()
            val avgGrowth = ms(this.growthNs) / f
            val avgSim = ms(this.simNs) / f
            val avgEdges = ms(this.edgeBuildNs) / f
            val avgPoints = ms(this.pointBuildNs) / f
            val avgTotal = ms(this.totalNs) / f
            val growthPct = if (avgTotal > 0.0) avgGrowth * 100.0 / avgTotal else 0.0
            val simPct = if (avgTotal > 0.0) avgSim * 100.0 / avgTotal else 0.0
            val edgePct = if (avgTotal > 0.0) avgEdges * 100.0 / avgTotal else 0.0
            val pointPct = if (avgTotal > 0.0) avgPoints * 100.0 / avgTotal else 0.0
            println(
                "perf/$reportEveryFrames frames: avgMs total=$avgTotal sim=$avgSim " +
                    "growth=$avgGrowth edges=$avgEdges points=$avgPoints " +
                    "share[%] sim=$simPct growth=$growthPct edges=$edgePct points=$pointPct " +
                    "growthAppliedFrames=$growthAppliedFrames maxNodes=$maxNodes"
            )
            this.growthNs = 0L
            this.simNs = 0L
            this.edgeBuildNs = 0L
            this.pointBuildNs = 0L
            this.totalNs = 0L
            this.growthAppliedFrames = 0
            this.maxNodes = 0
        }
    }
}


fun main1(): Pair<FLine, FLine> {
    val graph = GrowingGraph(initialNodeCount = 10, rule = 2182, flipProb = 0.0, seed = 1234L)
    graph.setNeighbors(0, 9, 1, 2)
    graph.setNeighbors(1, 0, 2, 4)
    graph.setNeighbors(2, 1, 3, 0)
    graph.setNeighbors(3, 2, 4, 6)
    graph.setNeighbors(4, 3, 5, 1)
    graph.setNeighbors(5, 4, 6, 8)
    graph.setNeighbors(6, 5, 7, 3)
    graph.setNeighbors(7, 6, 8, 9)
    graph.setNeighbors(8, 7, 9, 5)
    graph.setNeighbors(9, 8, 0, 7)

    val initStates = intArrayOf(0, 0, 0, 1, 0, 1, 0, 1, 1, 1)
    for (i in 0 until 10) graph.setState(i, initStates[i])

    val state = SimdVerlet.State3(
        x = FloatArray(0),
        y = FloatArray(0),
        z = FloatArray(0),
        px = FloatArray(0),
        py = FloatArray(0),
        pz = FloatArray(0),
        ax = FloatArray(0),
        ay = FloatArray(0),
        az = FloatArray(0),
        invMass = FloatArray(0)
    )

    var constraintBuffer = ConstraintBuffer(graph.nodeCount)
    var constraints = constraintBuffer.ensureSize(graph.nodeCount)

    val sim = SimdVerlet(state, constraints)

    val radius = 0.7f
    for (i in 0 until graph.nodeCount) {
        val angle = (i.toFloat() / graph.nodeCount.toFloat()) * (Math.PI * 2.0).toFloat()
        val x = radius * kotlin.math.cos(angle)
        val y = radius * kotlin.math.sin(angle)
        val z = 0.15f * ((i % 3) - 1)
        sim.addParticle(x, y, z)
    }

    constraints = constraintBuffer.ensureSize(graph.nodeCount)
    sim.constraints = constraints
    updateConstraintsAll(graph, sim, constraintBuffer, stiffness = 0.4f)

    val pool = OctreeBarnesHutPool(maxTrees = 1, initialCapacity = 256)
    sim.addForceGenerator(0, "bh-repel", generator2 {
        while (true) {
            val ctx = yield()
            val s = ctx.state
            val n = s.size
            if (n == 0) continue

            val tree = pool.acquire()
            tree.build(s.x, s.y, s.z, massIn = null, invMass = s.invMass, count = n)
            tree.applyForces(
                x = s.x,
                y = s.y,
                z = s.z,
                ax = s.ax,
                ay = s.ay,
                az = s.az,
                massIn = null,
                invMass = s.invMass,
                count = n,
                theta = 0.6f,
                softening = 0.06f,
                g = -10000.2f
            )
            pool.release(tree)
        }
    })

    val dt = 1f / 60f
    repeat(240) { step ->
        val delta = graph.grow()
        if (delta.newNodeCount > 0) {
            addNewNodes(graph, sim, delta.newNodeStart, delta.newNodeCount)
            constraints = constraintBuffer.ensureSize(graph.nodeCount)
            sim.constraints = constraints
            updateConstraintsAll(graph, sim, constraintBuffer, stiffness = 0.4f)
        }

        10 {
            sim.step(dt = dt, damping = 0.995f, gravity = 0f, iterations = 4)
        }

        if (step % 20 == 0) {
            val count = sim.state.size
            println("step=$step nodes=$count")
            sim.state.x.take(count).forEachIndexed { i, x -> println("$i $x ${sim.state.y[i]} ${sim.state.z[i]}") }
            val edges = buildEdgeFLine(graph, sim)
            val points = buildPointFLine(sim)
            println("debug edges=${edges.nodes.size} points=${points.nodes.size}")
        }
    }

    5000 {
        sim.step(dt = dt, damping = 0.995f, gravity = 0f, iterations = 4)
    }

    val edges = buildEdgeFLine(graph, sim)
    val points = buildPointFLine(sim)
    println("debug edges=${edges.nodes.size} points=${points.nodes.size}")
    return edges to points
}
fun main() {
    main1()
}

var lastBarnesHutFLine = FLine()
var lastBarnesHutFLine_depth = 5

data class SimdNodePick3D(
    val nodeIndex: Int,
    val queryPoint: afield.stdlib.Vec3,
    val nodePosition: afield.stdlib.Vec3,
    val distanceToNode: Float
)

class GrowingSequenceRuntime internal constructor(
    val frames: Sequence<Pair<FLine, FLine>>,
    private val grabFn: (
        queryPoint: afield.stdlib.Vec3,
        controllerPosition: afield.stdlib.Vec3,
        controllerRotation: Quat,
        maxOutsideDistance: Double
    ) -> SimdNodePick3D?,
    private val continueFn: (controllerPosition: afield.stdlib.Vec3, controllerRotation: Quat) -> Boolean,
    private val releaseFn: () -> Int?,
    private val grabbedNodeIndexFn: () -> Int?,
    private val closeFn: () -> Unit
) : AutoCloseable {
    fun grab(
        queryPoint: afield.stdlib.Vec3,
        controllerPosition: afield.stdlib.Vec3 = vec(queryPoint.x, queryPoint.y, queryPoint.z),
        controllerRotation: Quat = Quat(),
        maxOutsideDistance: Double = Double.POSITIVE_INFINITY
    ): SimdNodePick3D? = grabFn(queryPoint, controllerPosition, controllerRotation, maxOutsideDistance)

    fun grab(
        controllerPosition: afield.stdlib.Vec3,
        controllerRotation: Quat,
        maxOutsideDistance: Double = Double.POSITIVE_INFINITY
    ): SimdNodePick3D? = grab(
        queryPoint = controllerPosition,
        controllerPosition = controllerPosition,
        controllerRotation = controllerRotation,
        maxOutsideDistance = maxOutsideDistance
    )

    fun continueGrab(controllerPosition: afield.stdlib.Vec3, controllerRotation: Quat): Boolean =
        continueFn(controllerPosition, controllerRotation)

    fun `continue`(controllerPosition: afield.stdlib.Vec3, controllerRotation: Quat): Boolean =
        continueGrab(controllerPosition, controllerRotation)

    fun release(): Int? = releaseFn()

    fun grabbedNodeIndex(): Int? = grabbedNodeIndexFn()

    override fun close() {
        closeFn()
    }
}

fun growingSequence(): Sequence<Pair<FLine, FLine>> = growingSequenceInteractive().frames

fun growingSequenceInteractive(): GrowingSequenceRuntime {
    val graph = GrowingGraph(initialNodeCount = 10, rule = 2182, flipProb = 0.0, seed = 1234L)
    graph.setNeighbors(0, 9, 1, 2)
    graph.setNeighbors(1, 0, 2, 4)
    graph.setNeighbors(2, 1, 3, 0)
    graph.setNeighbors(3, 2, 4, 6)
    graph.setNeighbors(4, 3, 5, 1)
    graph.setNeighbors(5, 4, 6, 8)
    graph.setNeighbors(6, 5, 7, 3)
    graph.setNeighbors(7, 6, 8, 9)
    graph.setNeighbors(8, 7, 9, 5)
    graph.setNeighbors(9, 8, 0, 7)

    val initStates = intArrayOf(0, 0, 0, 1, 0, 1, 0, 1, 1, 1)
    for (i in 0 until 10) graph.setState(i, initStates[i])

    val state = SimdVerlet.State3(
        x = FloatArray(0),
        y = FloatArray(0),
        z = FloatArray(0),
        px = FloatArray(0),
        py = FloatArray(0),
        pz = FloatArray(0),
        ax = FloatArray(0),
        ay = FloatArray(0),
        az = FloatArray(0),
        invMass = FloatArray(0)
    )

    var constraintBuffer = ConstraintBuffer(graph.nodeCount)
    var constraints = constraintBuffer.ensureSize(graph.nodeCount)

    val sim = SimdVerlet(state, constraints)

    val radius = 0.7f
    for (i in 0 until graph.nodeCount) {
        val angle = (i.toFloat() / graph.nodeCount.toFloat()) * (Math.PI * 2.0).toFloat()
        val x = radius * kotlin.math.cos(angle)
        val y = radius * kotlin.math.sin(angle)
        val z = 0.15f * ((i % 3) - 1)
        sim.addParticle(x, y, z)
    }

    constraints = constraintBuffer.ensureSize(graph.nodeCount)
    sim.constraints = constraints
    updateConstraintsAll(graph, sim, constraintBuffer, stiffness = 0.4f)

    val pool = OctreeBarnesHutPool(maxTrees = 1, initialCapacity = 256)
    val bhForceThreads = max(1, Runtime.getRuntime().availableProcessors() - 1)
    val bhForceExecutor = SimdVerlet.ForceExecutor(bhForceThreads)
    val simulationLock = Any()
    var closed = false
    var grabbedNodeIndex: Int? = null
    var targetX = 0f
    var targetY = 0f
    var targetZ = 0f
    val grabControllerName = "vr-grabbed"

    fun releaseGrabLocked(): Int? {
        val idx = grabbedNodeIndex ?: return null
        if (idx in 0 until sim.state.forceGenerators.size) {
            sim.state.forceGenerators[idx].remove(grabControllerName)
        }
        grabbedNodeIndex = null
        return idx
    }

    sim.addForceGenerator(0, "bh-repel", generator2 {
        var t = 0
        while (t++<10000) {
            val ctx = yield()
            lastBarnesHutFLine.clear()
            val s = ctx.state
            val n = s.size
            if (n == 0) continue

            val tree = pool.acquire()
            tree.build(
                x = s.x,
                y = s.y,
                z = s.z,
                massIn = null,
                invMass = s.invMass,
                count = n,
                buildExecutor = bhForceExecutor,
                minParallelCount = 4096
            )
            tree.applyForces(
                x = s.x,
                y = s.y,
                z = s.z,
                ax = s.ax,
                ay = s.ay,
                az = s.az,
                massIn = null,
                invMass = s.invMass,
                count = n,
                theta = 0.6f,
                softening = 0.06f,
                g = -1000.2f,
                forceExecutor = bhForceExecutor,
                minChunk = 1024
            )
            tree.appendCuboidsTo(lastBarnesHutFLine, lastBarnesHutFLine_depth)
            pool.release(tree)
        }
    })

    val growthExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "growing-graph-worker").apply { isDaemon = true }
    }
    var growthFuture: Future<GrowingGraph.GrowDelta>? = null
    fun closeRuntime() {
        synchronized(simulationLock) {
            if (closed) return
            releaseGrabLocked()
            growthFuture?.cancel(true)
            growthFuture = null
            growthExecutor.shutdownNow()
            bhForceExecutor.close()
            closed = true
        }
    }

    val frames = kotlin.sequences.sequence {
        try {
            synchronized(simulationLock) {
                if (!closed && growthFuture == null) {
                    growthFuture = scheduleGrow(growthExecutor, graph)
                }
            }

            val dt = 1f / 20f
            val growthSteps = 1700
            val dumpCoordinatesEvery = 0
            val perf = SequencePerf(reportEveryFrames = 12)
            var completedGrowthSteps = 0
            var frame = 0
            while (completedGrowthSteps < growthSteps) {
                val frameResult = synchronized(simulationLock) {
                    if (closed) {
                        return@synchronized null
                    }

                    val frameStartNs = System.nanoTime()

                    val growthStartNs = System.nanoTime()
                    val pendingGrowth = growthFuture ?: error("growth future missing")
                    var growthAppliedThisFrame = false
                    if (pendingGrowth.isDone) {
                        val delta = pendingGrowth.get()
                        growthFuture = null
                        if (delta.newNodeCount > 0) {
                            addNewNodes(graph, sim, delta.newNodeStart, delta.newNodeCount)
                            constraints = constraintBuffer.ensureSize(graph.nodeCount)
                            sim.constraints = constraints
                            updateConstraintsAll(graph, sim, constraintBuffer, stiffness = 0.4f)
                        }

                        val flip = (Math.random() * graph.nodeCount).i
                        graph.setState(flip, 1 - graph.stateOf(flip))

                        completedGrowthSteps++
                        growthAppliedThisFrame = true
                        if (completedGrowthSteps < growthSteps) {
                            growthFuture = scheduleGrow(growthExecutor, graph)
                        }
                    }
                    val growthNs = System.nanoTime() - growthStartNs

                    val simStartNs = System.nanoTime()
                    1 {
                        sim.step(dt = dt, damping = 0.995f, gravity = 0f, iterations = 10)
                    }
                    val simNs = System.nanoTime() - simStartNs

                    if (dumpCoordinatesEvery > 0 && frame % dumpCoordinatesEvery == 0) {
                        val count = sim.state.size
                        println("step=$frame growth=$completedGrowthSteps nodes=$count")
                        sim.state.x.take(count).forEachIndexed { i, x -> println("$i $x ${sim.state.y[i]} ${sim.state.z[i]}") }
                    }

                    val edgeStartNs = System.nanoTime()
                    val edges = buildEdgeFLine(constraints, sim)
                    val edgeBuildNs = System.nanoTime() - edgeStartNs
                    val pointStartNs = System.nanoTime()
                    val points = buildPointFLine(sim)
                    val pointBuildNs = System.nanoTime() - pointStartNs
                    val totalNs = System.nanoTime() - frameStartNs
                    perf.record(
                        nodeCount = sim.state.size,
                        growthApplied = growthAppliedThisFrame,
                        growthNs = growthNs,
                        simNs = simNs,
                        edgeBuildNs = edgeBuildNs,
                        pointBuildNs = pointBuildNs,
                        totalNs = totalNs
                    )

                    if (sim.state.size > 26000) {
                        return@synchronized null
                    }

                    frame++
                    edges to points
                } ?: break

                yield(frameResult)
            }

            500 { settleFrame ->
                val frameResult = synchronized(simulationLock) {
                    if (closed) {
                        return@synchronized null
                    }

                    val frameStartNs = System.nanoTime()
                    val simStartNs = System.nanoTime()
                    sim.step(dt = dt * 2, damping = 0.995f, gravity = 0f, iterations = 10)
                    val simNs = System.nanoTime() - simStartNs
                    val edgeStartNs = System.nanoTime()
                    val edges = buildEdgeFLine(constraints, sim)
                    val edgeBuildNs = System.nanoTime() - edgeStartNs
                    val pointStartNs = System.nanoTime()
                    val points = buildPointFLine(sim)
                    val pointBuildNs = System.nanoTime() - pointStartNs
                    val totalNs = System.nanoTime() - frameStartNs
                    perf.record(
                        nodeCount = sim.state.size,
                        growthApplied = false,
                        growthNs = 0L,
                        simNs = simNs,
                        edgeBuildNs = edgeBuildNs,
                        pointBuildNs = pointBuildNs,
                        totalNs = totalNs
                    )
                    if (dumpCoordinatesEvery > 0 && settleFrame % dumpCoordinatesEvery == 0) {
                        println("settleFrame=$settleFrame nodes=${sim.state.size}")
                    }
                    edges to points
                } ?: return@sequence

                yield(frameResult)
            }
        } finally {
            closeRuntime()
        }
    }

    return GrowingSequenceRuntime(
        frames = frames,
        grabFn = { queryPoint, controllerPosition, _controllerRotation, maxOutsideDistance ->
            synchronized(simulationLock) {
                if (closed || sim.state.size == 0) {
                    return@synchronized null
                }

                var nearestIdx = -1
                var nearestDist = Float.POSITIVE_INFINITY
                for (i in 0 until sim.state.size) {
                    val dx = sim.state.x[i] - queryPoint.x
                    val dy = sim.state.y[i] - queryPoint.y
                    val dz = sim.state.z[i] - queryPoint.z
                    val dist = sqrt(dx * dx + dy * dy + dz * dz)
                    if (dist < nearestDist) {
                        nearestDist = dist
                        nearestIdx = i
                    }
                }

                if (nearestIdx < 0) {
                    return@synchronized null
                }

                if (nearestDist.toDouble() > maxOutsideDistance) {
                    return@synchronized null
                }

                releaseGrabLocked()
                grabbedNodeIndex = nearestIdx
                targetX = controllerPosition.x
                targetY = controllerPosition.y
                targetZ = controllerPosition.z

                sim.addPidPositionController(
                    nearestIdx,
                    grabControllerName,
                    targetX = { targetX },
                    targetY = { targetY },
                    targetZ = { targetZ },
                    kp = 160f,
                    ki = 0f,
                    kd = 15f,
                    maxForce = 2500f
                )

                SimdNodePick3D(
                    nodeIndex = nearestIdx,
                    queryPoint = vec(queryPoint.x, queryPoint.y, queryPoint.z),
                    nodePosition = vec(sim.state.x[nearestIdx], sim.state.y[nearestIdx], sim.state.z[nearestIdx]),
                    distanceToNode = nearestDist
                )
            }
        },
        continueFn = { controllerPosition, _controllerRotation ->
            synchronized(simulationLock) {
                if (closed || grabbedNodeIndex == null) {
                    return@synchronized false
                }
                targetX = controllerPosition.x
                targetY = controllerPosition.y
                targetZ = controllerPosition.z
                true
            }
        },
        releaseFn = {
            synchronized(simulationLock) {
                if (closed) {
                    return@synchronized null
                }
                releaseGrabLocked()
            }
        },
        grabbedNodeIndexFn = {
            synchronized(simulationLock) {
                if (closed) {
                    return@synchronized null
                }
                grabbedNodeIndex
            }
        },
        closeFn = ::closeRuntime
    )
}
