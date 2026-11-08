package afield.simulation.agents

import afield.math.linalg.Vec2
import afield.math.linalg.vec
import afield.media.image.Img
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the transport-network numbers in `public/docs/alife_examples.md` §7 "Designing a network":
 * the controls land where they must (a tree has fault tolerance 0, a triangulation 1, the complete
 * graph stretch 1), skeleton extraction reads a drawn network back at the right length and topology,
 * and the CPU Physarum fed at food sources joins them while its blind control does not.
 */
class PhysarumNetworkTest {
    private val triangle = listOf(vec(40, 40), vec(160, 40), vec(100, 150))

    /** A one-channel field with thick roads drawn between the given points. */
    private fun roads(w: Int, h: Int, segments: List<Pair<Vec2, Vec2>>, halfWidth: Double = 2.5, value: Float = 30f): Img {
        val img = Img.zeros(w, h, 1)
        for (y in 0 until h) for (x in 0 until w) {
            val near = segments.any { (a, b) -> distanceToSegment(x.toDouble(), y.toDouble(), a, b) <= halfWidth }
            if (near) img.data[y * w + x] = value
        }
        return img
    }

    private fun distanceToSegment(px: Double, py: Double, a: Vec2, b: Vec2): Double {
        val dx = b.x - a.x.toDouble()
        val dy = b.y - a.y.toDouble()
        val t = (((px - a.x) * dx + (py - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(px - (a.x + t * dx), py - (a.y + t * dy))
    }

    @Test fun controlsLandWhereTheyMust() {
        val pts = PhysarumFood.scattered(200, 200, 9, seed = 4L).points
        val mst = TransportNetwork.mst(pts).score("MST")
        val del = TransportNetwork.delaunay(pts).score("Delaunay")
        val all = TransportNetwork.complete(pts).score("complete")
        println(NetworkScore.table(listOf(mst, del, all)))
        assertEquals(1.0, mst.relativeCost, 1e-9)
        assertEquals(0.0, mst.faultTolerance, 1e-9)
        assertEquals(0, mst.loops)
        assertEquals(1.0, del.faultTolerance, 1e-9)
        assertTrue(del.relativeCost > 1.5 && del.stretch < mst.stretch, "Delaunay costs more and routes straighter")
        assertEquals(1.0, all.stretch, 1e-9)
        assertEquals(36, all.links)
    }

    @Test fun extractionReadsADrawnLoopBack() {
        val (a, b, c) = triangle
        val img = roads(200, 200, listOf(a to b, b to c, c to a, b to vec(190, 10)))
        val net = TransportNetwork.extract(img, triangle, threshold = 10.0)
        val s = net.score("drawn triangle")
        println("$net\n$s")
        val perimeter = TransportNetwork.complete(triangle).length
        assertTrue(abs(s.length - perimeter) / perimeter < 0.06, "length ${s.length} against perimeter $perimeter (the spur is pruned)")
        assertEquals(1, s.loops)
        assertEquals(1.0, s.faultTolerance, 1e-9)
        assertEquals(1.0, s.connectedPairs, 1e-9)
        assertEquals(3, s.links)
    }

    @Test fun extractionFindsTheJunctionOfATree() {
        val (a, b, c) = triangle
        val hub = vec(100, 80)
        val net = TransportNetwork.extract(roads(200, 200, listOf(a to hub, b to hub, c to hub)), triangle, threshold = 10.0)
        val s = net.score("drawn star")
        println("$net\n$s")
        assertEquals(4, s.nodes, "three sources and one junction")
        assertEquals(0.0, s.faultTolerance, 1e-9)
        assertEquals(0, s.loops)
        val junction = net.nodes.indices.first { it !in net.sourceNodes.toList() }
        assertTrue(hypot(net.nodes[junction].x - 100.0, net.nodes[junction].y - 80.0) < 4.0, "junction at ${net.nodes[junction]}")
    }

    @Test fun anUnreachedSourceIsReportedNotInvented() {
        val (a, b, _) = triangle
        val s = TransportNetwork.extract(roads(200, 200, listOf(a to b)), triangle, threshold = 10.0).score("one road")
        assertEquals(1.0 / 3.0, s.connectedPairs, 1e-9)
    }

    private fun grow(agents: Int, food: PhysarumFood, p: PhysarumParams = PhysarumParams()): NetworkScore {
        val trail = PhysarumSwarm.random(200, 200, agents, seed = 5L).meanTrail(p, 3000, averageOver = 300, seed = 5L, food = food)
        val threshold = 0.25 * p.deposit / (1 - p.decay)
        return TransportNetwork.extract(trail, food.points, threshold).score("$agents agents" + if (p.sensing) "" else " blind")
    }

    @Test fun foodJoinsTheNetworkAndTheBlindControlDoesNot() {
        val food = PhysarumFood.scattered(200, 200, 8, seed = 3L)
        val few = grow(1600, food)
        val many = grow(4800, food)
        val blind = grow(2400, food, PhysarumParams(sensing = false))
        println(NetworkScore.table(listOf(TransportNetwork.mst(food.points).score("MST"), few, many, blind)))
        assertTrue(few.connectedPairs > 0.99, "1600 agents join every source: ${few.connectedPairs}")
        assertTrue(many.connectedPairs > 0.99, "4800 agents join every source: ${many.connectedPairs}")
        assertTrue(many.length > 1.5 * few.length, "three times the agents, more road: ${few.length} -> ${many.length}")
        assertTrue(many.loops > few.loops, "and more loops: ${few.loops} -> ${many.loops}")
        assertTrue(few.relativeCost in 1.0..1.6, "a near-tree just above the MST's cost: ${few.relativeCost}")
        assertTrue(blind.connectedPairs < 0.15, "the blind control joins few sources: ${blind.connectedPairs}")
    }

    @Test fun aWideSensorAngleBreaksTheNetwork() {
        val food = PhysarumFood.scattered(200, 200, 8, seed = 3L)
        val wide = grow(2400, food, PhysarumParams(sensorAngle = 3 * Math.PI / 8))
        println(wide.row())
        assertTrue(wide.connectedPairs < 0.5, "67.5 degree sensors leave the sources in separate pieces: ${wide.connectedPairs}")
    }
}
