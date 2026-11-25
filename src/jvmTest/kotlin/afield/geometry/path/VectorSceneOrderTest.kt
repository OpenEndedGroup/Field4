package afield.geometry.path

import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Draw order for [VectorScene]: the pairwise occlusion order must fix the cases a centroid sort
 * gets wrong, survive cycles, and beat the centroid sort on a per-pixel depth reference.
 */
class VectorSceneOrderTest {

    private val red = vec(0.9, 0.2, 0.2, 1.0)
    private val blue = vec(0.2, 0.3, 0.9, 1.0)

    /** a long floor tile reaching toward the viewer, and a small card standing on its far end */
    private fun plankAndCard() = vectorScene {
        group({ rotateX(-PI / 2) }) { rect(60.0, 400.0, red) }
        group({ translate(0.0, 20.0, -150.0) }) { rect(30.0, 40.0, blue) }
    }

    @Test
    fun centroidSortFailsWherePairwiseSucceeds() {
        val s = plankAndCard()
        val faces = s.project(yaw = 0.0, pitch = -0.4)
        // the plank's centroid is nearer than the card's, so a centroid sort draws the card first and
        // hides it; the card stands above the plank, so it must be drawn last
        assertEquals(blue, s.order(faces, DepthOrder.CENTROID).first().facet.color)
        assertEquals(blue, s.order(faces, DepthOrder.PAIRWISE).last().facet.color)
        val (wc, _) = s.wrongOrderShare(0.0, -0.4, DepthOrder.CENTROID)
        val (wp, _) = s.wrongOrderShare(0.0, -0.4, DepthOrder.PAIRWISE)
        assertTrue(wc > 0, "centroid sort should get some pixels wrong")
        assertEquals(0, wp)
    }

    @Test
    fun cyclicOverlapStillYieldsEveryFace() {
        // three sticks woven over one another: a classic painter's-algorithm cycle
        val s = vectorScene {
            for (k in 0 until 3) group({ rotateZ(2 * PI / 3 * k) }) {
                line(-80.0, -30.0, -20.0, 80.0, -30.0, 20.0, width = 16.0, color = red)
            }
        }
        val faces = s.project(0.0, 0.0)
        val (order, breaks) = VectorScene.pairwiseOrder(faces)
        assertEquals(faces.size, order.size)
        assertEquals(faces.toSet(), order.toSet())
        assertTrue(breaks >= 1, "expected the woven sticks to need a cycle break")
    }

    @Test
    fun pairwiseBeatsCentroidOnLighthouse() {
        val s = lighthouse()
        var c = 0L; var p = 0L; var cov = 0L
        for (k in 0 until 8) {
            val yaw = 2 * PI * k / 8
            val (wc, n) = s.wrongOrderShare(yaw, -0.35, DepthOrder.CENTROID, 160, 160)
            val (wp, _) = s.wrongOrderShare(yaw, -0.35, DepthOrder.PAIRWISE, 160, 160)
            c += wc; p += wp; cov += n
        }
        println("lighthouse wrong-order share: centroid ${"%.2f".format(100.0 * c / cov)}%, pairwise ${"%.2f".format(100.0 * p / cov)}%")
        assertTrue(p < c, "pairwise ($p) should be wrong on fewer pixels than centroid ($c)")
    }

    companion object {
        /** the scene used in the docs: tower, lantern, cap, keeper's house and rocks */
        fun lighthouse() = vectorScene {
            val white = vec(0.94, 0.93, 0.89, 1.0)
            val redC = vec(0.82, 0.22, 0.2, 1.0)
            segments(20)
            group({ translate(0.0, -6.0, 0.0) }) { box(260.0, 12.0, 200.0, vec(0.35, 0.55, 0.4, 1.0)) }
            cylinder(34.0, 70.0, white, topRadius = 30.0)
            group({ translate(0.0, 70.0, 0.0) }) { cylinder(30.0, 60.0, redC, topRadius = 26.0) }
            group({ translate(0.0, 130.0, 0.0) }) { cylinder(26.0, 50.0, white, topRadius = 22.0) }
            group({ translate(0.0, 180.0, 0.0) }) { cylinder(30.0, 6.0, vec(0.25, 0.25, 0.28, 1.0)) }
            group({ translate(0.0, 186.0, 0.0) }) { cylinder(18.0, 22.0, vec(1.0, 0.85, 0.35, 1.0)) }
            group({ translate(0.0, 208.0, 0.0) }) { cone(24.0, 26.0, redC) }
            group({ translate(80.0, 25.0, 30.0) }) { box(70.0, 50.0, 50.0, white, top = redC) }
            group({ translate(-70.0, 0.0, 50.0) }) { hemisphere(22.0, vec(0.55, 0.52, 0.5, 1.0)) }
            group({ translate(-40.0, 0.0, 75.0) }) { hemisphere(14.0, vec(0.5, 0.48, 0.47, 1.0)) }
        }
    }
}
