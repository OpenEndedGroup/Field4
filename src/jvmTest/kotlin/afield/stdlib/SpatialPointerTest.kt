package afield.stdlib

import afield.four.util.SuperProjection
import afield.stdlib.linalg.Matrix4f
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unprojection and the pick scene, against oracles that can fail.
 *
 * Two disciplines run through this file, both learned the hard way:
 *
 *  1. **A round trip asserts its own premise first.** "Unproject a pixel and project it back" is
 *     satisfied perfectly by a camera that maps the whole world to one point, so every round-trip
 *     test here begins by requiring [RayCamera.nonDegeneracyMargin] to clear a floor. Without that,
 *     a broken projection turns the test into a tautology and the tautology passes.
 *  2. **Every number printed is floored near where it actually lands.** A `println` is not a ratchet
 *     and a floor with three orders of magnitude of slack is not either. The floors below sit within
 *     a small factor of the measured value, and the measured value is in the message so that the
 *     next person can see how much room there was.
 */
class SpatialPointerTest {

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /** A mesh with a lopsided silhouette, so a flipped axis cannot hide behind symmetry. */
    private fun lopsidedMesh(): TriMesh = triMesh {
        group("tall box")
        add(box(0.6, 2.0, 0.6))
        group("wide slab")
        add(boxAt(1.6, 0.0, 0.0, 1.4, 0.3, 0.9))
        group("far cube")
        add(boxAt(-0.2, 0.9, 1.3, 0.5, 0.5, 0.5))
    }

    /** An axis-aligned box centred on the origin, as a standalone mesh. */
    private fun box(w: Double, h: Double, d: Double): TriMesh = boxAt(0.0, 0.0, 0.0, w, h, d)

    private fun boxAt(cx: Double, cy: Double, cz: Double, w: Double, h: Double, d: Double): TriMesh {
        val hx = (w / 2).toFloat(); val hy = (h / 2).toFloat(); val hz = (d / 2).toFloat()
        val b = TriMeshBuilder()
        val corners = listOf(
            Vec3(-hx, -hy, -hz), Vec3(hx, -hy, -hz), Vec3(hx, hy, -hz), Vec3(-hx, hy, -hz),
            Vec3(-hx, -hy, hz), Vec3(hx, -hy, hz), Vec3(hx, hy, hz), Vec3(-hx, hy, hz),
        ).map { Vec3(it.x + cx.toFloat(), it.y + cy.toFloat(), it.z + cz.toFloat()) }
        val at = corners.map { b.vertex(it) }
        // Counter-clockwise seen from outside, on every face.
        b.quad(at[3], at[2], at[1], at[0]) // -z
        b.quad(at[4], at[5], at[6], at[7]) // +z
        b.quad(at[0], at[1], at[5], at[4]) // -y
        b.quad(at[7], at[6], at[2], at[3]) // +y
        b.quad(at[0], at[4], at[7], at[3]) // -x
        b.quad(at[1], at[2], at[6], at[5]) // +x
        return b.build()
    }

    // ------------------------------------------------------------------------------------------
    // The premise
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a camera that spread no rays out would make every other test here vacuous`() {
        val mesh = lopsidedMesh()
        val good = MeshView(width = 320, height = 240).rayCamera(mesh)
        val margin = good.nonDegeneracyMargin()
        // 320x240 at a 42 degree vertical field of view: the frame's quarter points are a quarter of
        // the frame apart vertically, so the smallest pairwise angle is on the order of 10 degrees.
        assertTrue(
            margin > 0.12,
            "the preview camera must actually spread rays across the frame; smallest pairwise angle " +
                "between the quarter-point rays was ${"%.4f".format(margin)} rad " +
                "(${"%.2f".format(Math.toDegrees(margin))} deg)",
        )
        assertTrue(good.isNonDegenerate, "…and it should say so")

        // And the check has teeth: a projection with no extent at all fails it.
        val flat = ClipMatrixRayCamera(
            Matrix4f().set(
                1e-9f, 0f, 0f, 0f,
                0f, 1e-9f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
            320, 240,
        )
        assertTrue(
            !flat.isNonDegenerate,
            "a projection that collapses the frame must be reported degenerate, not silently trusted; " +
                "margin was ${flat.nonDegeneracyMargin()}",
        )
    }

    @Test
    fun `a singular clip matrix is refused rather than producing nonsense rays`() {
        val singular = Matrix4f().zero()
        val thrown = runCatching { ClipMatrixRayCamera(singular, 100, 100) }.exceptionOrNull()
        assertNotNull(thrown, "an all-zero clip matrix must be refused")
        assertTrue(
            thrown.message!!.contains("singular"),
            "…and say why. Got: ${thrown.message}",
        )
    }

    // ------------------------------------------------------------------------------------------
    // The round trip, floored
    // ------------------------------------------------------------------------------------------

    /**
     * Unproject a grid of pixels, walk a distance along each ray, project the point back, and report
     * the worst disagreement in pixels.
     */
    private fun worstRoundTripError(camera: RayCamera, walk: Double, step: Int = 17): Double {
        var worst = 0.0
        var samples = 0
        var y = 1
        while (y < camera.pixelHeight - 1) {
            var x = 1
            while (x < camera.pixelWidth - 1) {
                val ray = camera.rayThrough(x + 0.5, y + 0.5)
                val p = ray.at(walk)
                val back = camera.pixelOf(p[0], p[1], p[2])
                assertNotNull(back, "the point ${walk} along the ray through ($x, $y) must be in front of the eye")
                val error = max(abs(back.x - (x + 0.5)), abs(back.y - (y + 0.5)))
                if (error > worst) worst = error
                samples++
                x += step
            }
            y += step
        }
        assertTrue(samples > 100, "the sweep must actually cover the frame; only $samples samples")
        return worst
    }

    @Test
    fun `the preview camera's unprojection is the rasterizer's projection run backwards`() {
        val mesh = lopsidedMesh()
        val view = MeshView(yawDegrees = 34.0, pitchDegrees = 21.0, width = 480, height = 360)
        val camera = view.rayCamera(mesh)
        assertTrue(camera.isNonDegenerate, "premise: the camera spreads rays out")
        assertEquals(480, camera.pixelWidth)
        assertEquals(360, camera.pixelHeight)

        val worst = worstRoundTripError(camera, walk = camera.frame.radius * 2.0)
        // Double throughout and one division; anything above a thousandth of a pixel would mean a
        // real algebraic disagreement rather than rounding.
        assertTrue(
            worst < 1e-3,
            "a pixel unprojected and projected back must land on itself; worst disagreement over the " +
                "frame was ${"%.3e".format(worst)} px",
        )
    }

    @Test
    fun `a caption band moves the answer, and the camera that ignores it is wrong`() {
        val mesh = lopsidedMesh()
        val view = MeshView(width = 480, height = 360)
        // The same view, once as `renderPng` renders it by default and once with a caption strip.
        val plain = view.rayCamera(mesh)
        val captioned = view.rayCamera(mesh, caption = "a caption reserves the bottom band")

        assertTrue(plain.isNonDegenerate && captioned.isNonDegenerate, "premise")
        assertTrue(
            worstRoundTripError(captioned, walk = captioned.frame.radius * 2.0) < 1e-3,
            "the captioned camera must round-trip too",
        )

        // The point of the test: they are *different* cameras, by a stated amount. If someone
        // "simplifies" `rayCamera` to ignore caption and title, this fails.
        assertTrue(
            captioned.frame.contentHeight < plain.frame.contentHeight,
            "a caption must shorten the content height: ${captioned.frame.contentHeight} vs ${plain.frame.contentHeight}",
        )
        val a = plain.rayThroughPixel(240, 300)
        val b = captioned.rayThroughPixel(240, 300)
        val angle = Math.toDegrees(
            kotlin.math.acos((a.dirX * b.dirX + a.dirY * b.dirY + a.dirZ * b.dirZ).coerceIn(-1.0, 1.0))
        )
        assertTrue(
            angle > 3.0,
            "low in the frame the two cameras must disagree measurably — that is the trap this " +
                "argument exists to avoid. They differed by only ${"%.3f".format(angle)} deg",
        )
    }

    @Test
    fun `the general camera round-trips under perspective, ortho and the blend between them`() {
        val modelView = Matrix4f().lookAtField(Vec3(2.5f, 1.8f, 4.0f), Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f))
        for (orthoness in listOf(0.0, 0.4, 1.0)) {
            val projection = SuperProjection(width = 640, height = 400).also {
                it.orthoness = orthoness
                it.matchDepth = 5.0
                it.setAspect(640f / 400f)
            }
            val camera = rayCameraFor(projection.projectionMatrix, modelView, 640, 400)
            assertTrue(
                camera.isNonDegenerate || orthoness >= 0.999,
                "premise: a perspective or blended camera spreads rays out (orthoness=$orthoness)",
            )
            val worst = worstRoundTripError(camera, walk = 5.0)
            // Float clip matrices, inverted in Double: about 1e-7 relative on entries of order 1,
            // multiplied up by the frame size, lands under a hundredth of a pixel.
            assertTrue(
                worst < 0.02,
                "orthoness=$orthoness: worst round-trip disagreement was ${"%.3e".format(worst)} px",
            )
        }
    }

    @Test
    fun `an orthographic camera has parallel rays and a perspective one does not`() {
        val modelView = Matrix4f().lookAtField(Vec3(0f, 0f, 6f), Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f))
        fun spread(orthoness: Double): Double {
            val projection = SuperProjection(width = 640, height = 400).also {
                it.orthoness = orthoness; it.matchDepth = 6.0; it.setAspect(640f / 400f)
            }
            return rayCameraFor(projection.projectionMatrix, modelView, 640, 400).nonDegeneracyMargin()
        }
        val perspective = spread(0.0)
        val ortho = spread(1.0)
        assertTrue(perspective > 0.15, "a 640x400 perspective frame should spread rays by >8 deg, got $perspective rad")
        assertTrue(
            ortho < 1e-4,
            "an orthographic projection's rays are parallel, so the pairwise angle should be ~0; got $ortho rad",
        )
        assertTrue(
            perspective / max(ortho, 1e-12) > 1e3,
            "and the two must be different by orders of magnitude, not by a hair",
        )
    }

    @Test
    fun `a point behind the eye has no pixel`() {
        val mesh = lopsidedMesh()
        val camera = MeshView(yawDegrees = 0.0, pitchDegrees = 0.0).rayCamera(mesh)
        val behind = camera.eye.let { Vec3(it.x, it.y, it.z + camera.frame.radius.toFloat() * 4f) }
        // yaw 0 / pitch 0 puts the eye on +z looking toward -z, so further along +z is behind it.
        assertNull(camera.pixelOf(behind), "a point behind the eye must report no pixel, not a wrapped one")
        assertNotNull(camera.pixelOf(Vec3(0f, 0f, 0f)), "…and the mesh centre must still have one")
    }

    // ------------------------------------------------------------------------------------------
    // Picking a named part
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a builder's groups become the names a pick reports`() {
        val builder = TriMeshBuilder()
        builder.group("tall box"); builder.add(box(0.6, 2.0, 0.6))
        builder.group("wide slab"); builder.add(boxAt(1.6, 0.0, 0.0, 1.4, 0.3, 0.9))
        val mesh = builder.build()
        val parts = builder.groups()

        assertEquals(2, parts.partCount, "two groups, two parts")
        assertEquals(mesh.triangleCount, parts.triangleCount)
        assertEquals(listOf("tall box", "wide slab"), parts.names)
        assertEquals(12, parts.triangleCounts()[0], "a box is 12 triangles")
        assertEquals(12, parts.triangleCounts()[1])

        val scene = PickScene(listOf(Pickable("model", mesh, parts)))
        // Straight down the +x axis at y = 0: the slab's far side is at x = 2.3, the tall box's at 0.3.
        val fromRight = scene.pick(Ray(6.0, 0.0, 0.0, -1.0, 0.0, 0.0))
        assertNotNull(fromRight)
        assertEquals("wide slab", fromRight.partName, "coming from +x, the slab is in front of the box")
        assertEquals(6.0 - 2.3, fromRight.distance, 1e-4, "the slab's +x face is at x = 2.3")

        // Aim above the slab (it is only 0.3 tall) and the tall box is what you touch.
        val higher = scene.pick(Ray(6.0, 0.6, 0.0, -1.0, 0.0, 0.0))
        assertNotNull(higher)
        assertEquals("tall box", higher.partName, "above the slab, the ray reaches the box")
        assertEquals(6.0 - 0.3, higher.distance, 1e-4, "the box's +x face is at x = 0.3")
    }

    @Test
    fun `moving the pointer changes the part, and the change is a stated number`() {
        val builder = TriMeshBuilder().also {
            it.group("tall box"); it.add(box(0.6, 2.0, 0.6))
            it.group("wide slab"); it.add(boxAt(1.6, 0.0, 0.0, 1.4, 0.3, 0.9))
            it.group("far cube"); it.add(boxAt(-0.2, 0.9, 1.3, 0.5, 0.5, 0.5))
        }
        val mesh = builder.build()
        val parts = builder.groups()
        val scene = PickScene(listOf(Pickable("model", mesh, parts)))
        val view = MeshView(yawDegrees = 0.0, pitchDegrees = 0.0, width = 400, height = 300, wireframe = false)
        val camera = view.rayCamera(mesh)
        assertTrue(camera.isNonDegenerate, "premise")

        // Sweep a horizontal line across the middle of the frame and record which part each pixel
        // touches. The counterfactual is the whole point: a pointer that reported the same part
        // everywhere would be useless and would pass a weaker test.
        val row = camera.pixelHeight / 2
        val found = ArrayList<Pair<Int, PickHit>>()
        for (x in 0 until camera.pixelWidth step 2) {
            val hit = scene.pick(camera.rayThroughPixel(x, row)) ?: continue
            found += x to hit
        }
        val counts = LinkedHashMap<String, Int>()
        for ((_, hit) in found) counts[hit.partName] = (counts[hit.partName] ?: 0) + 1
        assertTrue(
            counts.size >= 2,
            "a horizontal sweep across this model must touch at least two named parts; saw $counts",
        )
        // Each part that appears at all must appear over a real span, not one stray pixel — that is
        // what distinguishes "the pointer resolves parts" from "the pointer is noisy".
        assertTrue(
            counts.values.min() >= 8,
            "every part the sweep found should own a run of samples, not a speck; counts were $counts",
        )

        // And the specific counterfactual: two positions of the *same* pointer, a different part
        // under each, and a difference in depth asserted as a number rather than as "it changed".
        val left = found.first()
        val right = found.last { it.second.partName != left.second.partName }
        assertTrue(
            left.second.partName != right.second.partName,
            "the two ends of the sweep must be different parts; both said '${left.second.partName}'",
        )
        val depthChange = abs(left.second.distance - right.second.distance)
        assertTrue(
            depthChange > 0.05,
            "…and at measurably different depths. Pointer at x=${left.first} found " +
                "'${left.second.partName}' at ${"%.4f".format(left.second.distance)}; at x=${right.first} " +
                "'${right.second.partName}' at ${"%.4f".format(right.second.distance)}; " +
                "difference ${"%.4f".format(depthChange)}",
        )
        // The same pointer, one pixel apart, must not flip part — that would be the flicker the
        // hysteresis in `PointerInteraction` exists to absorb, and it should not be needed mid-face.
        val stableAt = found[found.size / 3]
        val neighbour = scene.pick(camera.rayThroughPixel(stableAt.first + 1, row))
        assertNotNull(neighbour)
        assertEquals(
            stableAt.second.partName, neighbour.partName,
            "one pixel of movement in the middle of a face must not change the part",
        )
    }

    @Test
    fun `a transformed pickable reports world distances and world normals`() {
        val cube = box(1.0, 1.0, 1.0)
        // Squashed flat in y and moved: the case where a model-space parameter is not a world
        // distance and a normal does not transform like a direction.
        val model = Matrix4f().identity().translate(3f, 0f, 0f).scale(1f, 0.25f, 1f)
        val scene = PickScene(listOf(Pickable("squashed", cube, at = model)))

        // Straight down onto the top face, which after the squash sits at y = 0.125.
        val hit = scene.pick(Ray(3.0, 5.0, 0.0, 0.0, -1.0, 0.0))
        assertNotNull(hit)
        assertEquals(5.0 - 0.125, hit.distance, 1e-4, "the squashed top face is at y = 0.125, so 4.875 away")
        assertEquals(0.125f, hit.point.y, 1e-4f)
        // A +y face's normal must still be +y after a y-only scale — which is only true through the
        // inverse transpose. Scaling the normal like a direction would give (0, 4, 0) normalized, also
        // +y; so scale x instead to make the test able to fail.
        assertEquals(1.0f, hit.normal.y, 1e-4f, "the top face still faces up")

        val skew = Matrix4f().identity().scale(4f, 1f, 1f)
        val skewed = PickScene(listOf(Pickable("stretched", cube, at = skew)))
        // A ray onto the +x face of a cube stretched 4x in x: the face is at x = 2.
        val side = skewed.pick(Ray(9.0, 0.0, 0.0, -1.0, 0.0, 0.0))
        assertNotNull(side)
        assertEquals(7.0, side.distance, 1e-4, "the stretched +x face is at x = 2")
        assertEquals(1.0f, side.normal.x, 1e-4f, "and still faces +x")
        assertEquals(2.0f, side.point.x, 1e-4f)
    }

    @Test
    fun `the nearest pickable wins even when a scaled one has a shorter model-space parameter`() {
        val cube = box(1.0, 1.0, 1.0)
        val near = Pickable("near", cube, at = Matrix4f().identity().translate(0f, 0f, 1f))
        // The far one is stretched 10x along the ray, so its model-space hit parameter is a tenth of
        // its world distance. Picking on the model parameter would pick the wrong box.
        val far = Pickable("far", cube, at = Matrix4f().identity().translate(0f, 0f, -4f).scale(1f, 1f, 10f))
        val scene = PickScene(listOf(far, near))
        val hit = scene.pick(Ray(0.0, 0.0, 8.0, 0.0, 0.0, -1.0))
        assertNotNull(hit)
        assertEquals("near", hit.objectName, "the nearer box in *world* units must win")

        val all = scene.pickAll(Ray(0.0, 0.0, 8.0, 0.0, 0.0, -1.0))
        assertEquals(listOf("near", "far"), all.map { it.objectName }, "pickAll orders by world distance")
        assertTrue(all[0].distance < all[1].distance)
    }

    @Test
    fun `a disabled pickable is looked straight through`() {
        val cube = box(1.0, 1.0, 1.0)
        val scene = PickScene(
            listOf(
                Pickable("shield", cube, at = Matrix4f().identity().translate(0f, 0f, 2f), enabled = false),
                Pickable("target", cube),
            )
        )
        val hit = scene.pick(Ray(0.0, 0.0, 8.0, 0.0, 0.0, -1.0))
        assertNotNull(hit)
        assertEquals("target", hit.objectName)
    }

    @Test
    fun `part ids from connected components are ranked by size, so part zero is the big one`() {
        val builder = TriMeshBuilder()
        // A 12-triangle speck first, then a 192-triangle solid: the ranking has to be by triangle
        // count and not by the order the component walk happened to visit them in.
        builder.add(boxAt(0.0, 0.0, 0.0, 0.2, 0.2, 0.2))
        val subdivided = boxAt(3.0, 0.0, 0.0, 2.0, 2.0, 2.0).loopSubdivide(2)
        // loopSubdivide hands back vertex normals; the builder insists on all-or-nothing, and the
        // speck has none, so strip them rather than teach the fixture about attributes.
        builder.add(TriMesh(subdivided.positions, subdivided.indices))
        val mesh = builder.build()
        val parts = PickParts.byComponent(mesh) { rank, tris -> "rank $rank ($tris tris)" }

        assertEquals(2, parts.partCount)
        assertEquals(listOf(192, 12), parts.triangleCounts().toList(), "biggest first")
        val scene = PickScene(listOf(Pickable("two solids", mesh, parts)))
        val bigHit = scene.pick(Ray(9.0, 0.0, 0.0, -1.0, 0.0, 0.0))
        assertNotNull(bigHit)
        assertEquals(
            0, bigHit.part,
            "the larger component must be part 0 regardless of append order; got '${bigHit.partName}'",
        )
        val speckHit = scene.pick(Ray(-9.0, 0.0, 0.0, 1.0, 0.0, 0.0))
        assertNotNull(speckHit)
        assertEquals(1, speckHit.part, "the speck is part 1; got '${speckHit.partName}'")
    }

    @Test
    fun `a PickParts built for one mesh is refused for another`() {
        val small = box(1.0, 1.0, 1.0)
        val parts = PickParts.single(small, "cube")
        val bigger = TriMeshBuilder().also { it.add(small); it.add(boxAt(3.0, 0.0, 0.0, 1.0, 1.0, 1.0)) }.build()
        val thrown = runCatching { Pickable("wrong", bigger, parts) }.exceptionOrNull()
        assertNotNull(thrown, "a part assignment that does not cover the mesh must be refused at construction")
        assertTrue(
            thrown.message!!.contains("keyed by triangle index"),
            "…and explain the rebuild rule. Got: ${thrown.message}",
        )
    }

    @Test
    fun `a merged scene keeps object and part legible in one name`() {
        val scene = pickScene {
            add("floor", boxAt(0.0, -0.5, 0.0, 6.0, 0.2, 6.0))
            add("lamp", box(0.5, 0.5, 0.5), at = Matrix4f().identity().translate(0f, 1.5f, 0f))
        }
        val merged = scene.mergedMesh()
        val mergedParts = scene.mergedParts()
        assertEquals(scene.triangleCount, merged.triangleCount)
        assertEquals(merged.triangleCount, mergedParts.triangleCount)
        assertEquals(listOf("floor / floor", "lamp / lamp"), mergedParts.names)

        // The merge must have actually moved the lamp: its bounds should reach y = 1.75.
        val bounds = merged.boundsArray()
        assertTrue(
            bounds[4] > 1.7f,
            "the merged mesh must carry the lamp's transform; its top was only ${bounds[4]}",
        )
    }
}
