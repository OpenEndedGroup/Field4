package afield.geometry.topology.trees

import afield.media.image.Img
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContourTreesTest {
    private fun bumps(peaks: List<Triple<Double, Double, Double>>, w: Int = 48, h: Int = 40) =
        Img(w, h, 1, FloatArray(w * h) { i ->
            val x = (i % w).toDouble(); val y = (i / w).toDouble()
            peaks.sumOf { (px, py, a) -> a * exp(-((x - px) * (x - px) + (y - py) * (y - py)) / 30.0) }.toFloat() - 0.0002f * ((x - 10) * (x - 10) + (y - 10) * (y - 10)).toFloat() / (w * h)
        })

    private val three = listOf(Triple(10.0, 10.0, 1.0), Triple(36.0, 12.0, 0.8), Triple(24.0, 30.0, 0.6))

    @Test fun splitTreeHasOneLeafPerPeak() {
        val t = ContourTrees.splitTree(bumps(three))
        assertEquals(3, t.nodes.count { it.type == TreeNodeType.MAXIMUM }, t.toString())
        assertEquals(t.nodes.size - 1, t.arcs.size)
    }

    @Test fun joinTreeOfSinglePeakIsOneArc() {
        val t = ContourTrees.joinTree(bumps(listOf(Triple(24.0, 20.0, 1.0))))
        assertTrue(t.nodes.count { it.type == TreeNodeType.MINIMUM } >= 1)
        assertEquals(t.nodes.size - 1, t.arcs.size)
    }

    @Test fun contourTreeIsATreeCoveringEveryPixel() {
        val f = bumps(three)
        val t = ContourTrees.contourTree(f)
        assertEquals(t.nodes.size - 1, t.arcs.size, t.toString())
        assertEquals(3, t.nodes.count { it.type == TreeNodeType.MAXIMUM })
        assertTrue(t.arcOfPixel.all { it >= 0 })
        assertEquals(f.width * f.height, t.arcs.sumOf { it.vertices.size } + t.nodes.size)
    }

    @Test fun simplifyRemovesTheSmallPeak() {
        val f = bumps(three + Triple(40.0, 34.0, 0.05))
        val t = ContourTrees.contourTree(f)
        assertEquals(4, t.nodes.count { it.type == TreeNodeType.MAXIMUM })
        val s = t.simplify(0.1)
        assertEquals(3, s.nodes.count { it.type == TreeNodeType.MAXIMUM }, s.toString())
        assertTrue(s.arcOfPixel.all { it >= 0 })
    }

    @Test fun outputs() {
        val t = ContourTrees.contourTree(bumps(three))
        assertEquals(t.arcs.size + 1, t.plot().size)
        assertEquals(t.nodes.size, t.network().nodes.ids.size)
        val img = t.segmentationImage(); assertEquals(3, img.channels)
        assertTrue(t.arcsAt(t.nodes[0].id).isNotEmpty())
    }

    @Test fun contourTreeLeavesMatchJoinAndSplitTrees() {
        for (seed in 0 until 6) {
            val r = java.util.Random(seed.toLong())
            val f = bumps(List(5) { Triple(r.nextDouble() * 48, r.nextDouble() * 40, 0.2 + r.nextDouble()) })
            val c = ContourTrees.contourTree(f)
            assertEquals(c.nodes.size - 1, c.arcs.size)
            assertEquals(ContourTrees.joinTree(f).nodes.count { it.type == TreeNodeType.MINIMUM }, c.nodes.count { it.type == TreeNodeType.MINIMUM }, "seed $seed")
            assertEquals(ContourTrees.splitTree(f).nodes.count { it.type == TreeNodeType.MAXIMUM }, c.nodes.count { it.type == TreeNodeType.MAXIMUM }, "seed $seed")
        }
    }
}
