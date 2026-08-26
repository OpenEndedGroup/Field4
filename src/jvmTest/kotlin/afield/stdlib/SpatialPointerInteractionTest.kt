package afield.stdlib

import afield.four.targets.OpenXRHmdTarget.ControllerState
import afield.four.targets.OpenXRHmdTarget.HandJoint
import afield.four.targets.OpenXRHmdTarget.HandTrackingState
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three pointer sources and the hover / press / drag / release machine.
 *
 * All of it headless and deterministic: a [PointerState] is a ray and a number, so a controller and a
 * hand can be *constructed* rather than tracked, and a whole gesture can be replayed sample by
 * sample. That is deliberate — the alternative is a feature that can only be tested by wearing it.
 *
 * The hysteresis tests are the load-bearing ones, and each is written as a **counterfactual**: the
 * same input sequence is run with the hysteresis disabled, the flicker is *shown to happen*, and then
 * shown not to happen with it on. A test that only checks the good case cannot tell you the mechanism
 * is doing anything.
 */
class SpatialPointerInteractionTest {

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private fun boxAt(cx: Double, cy: Double, cz: Double, w: Double, h: Double, d: Double): TriMesh {
        val hx = (w / 2).toFloat(); val hy = (h / 2).toFloat(); val hz = (d / 2).toFloat()
        val b = TriMeshBuilder()
        val at = listOf(
            Vec3(-hx, -hy, -hz), Vec3(hx, -hy, -hz), Vec3(hx, hy, -hz), Vec3(-hx, hy, -hz),
            Vec3(-hx, -hy, hz), Vec3(hx, -hy, hz), Vec3(hx, hy, hz), Vec3(-hx, hy, hz),
        ).map { b.vertex(Vec3(it.x + cx.toFloat(), it.y + cy.toFloat(), it.z + cz.toFloat())) }
        b.quad(at[3], at[2], at[1], at[0]); b.quad(at[4], at[5], at[6], at[7])
        b.quad(at[0], at[1], at[5], at[4]); b.quad(at[7], at[6], at[2], at[3])
        b.quad(at[0], at[4], at[7], at[3]); b.quad(at[1], at[2], at[6], at[5])
        return b.build()
    }

    /** Two coplanar slabs meeting exactly at x = 0 — the shared edge a hover flickers on. */
    private fun twoSlabsSharingAnEdge(): PickScene = pickScene {
        add("west", boxAt(-1.0, 0.0, 0.0, 2.0, 1.0, 1.0))
        add("east", boxAt(1.0, 0.0, 0.0, 2.0, 1.0, 1.0))
    }

    /** A ray straight down at world x = [x]. */
    private fun downAt(x: Double) = Ray(x, 5.0, 0.0, 0.0, -1.0, 0.0)

    // ------------------------------------------------------------------------------------------
    // The sources
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a mouse becomes a spatial pointer, and leaving the frame turns it off`() {
        val mesh = boxAt(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        val camera = MeshView(width = 200, height = 150).rayCamera(mesh)
        assertTrue(camera.isNonDegenerate, "premise")

        var cursor = Vec2(100f, 75f)
        var down = false
        val pointer = mousePointer(camera = { camera }, cursor = { cursor }, selecting = { down })

        val middle = pointer.sample()
        assertTrue(middle.active, "the cursor is in the frame")
        assertEquals(0.0, middle.select)
        // Dead centre of the frame must aim at the mesh, which is what the camera is framed on.
        assertNotNull(mesh.bvh().raycast(middle.ray!!), "the centre of the frame must hit the mesh")

        down = true
        assertEquals(1.0, pointer.sample().select, "a pressed button is a full select")

        cursor = Vec2(250f, 75f)
        assertTrue(!pointer.sample().active, "a cursor outside the frame has no pixel and so no ray")
        cursor = Vec2(-3f, 75f)
        assertTrue(!pointer.sample().active, "…on either side")

        // With clamping off it still produces a ray, which is what a drag leaving the window wants.
        val unclamped = mousePointer(
            camera = { camera }, cursor = { Vec2(250f, 75f) }, selecting = { false }, clampToFrame = false,
        )
        assertTrue(unclamped.sample().active, "clampToFrame = false keeps the ray")
    }

    @Test
    fun `a controller's aim follows its rotation, on the convention the rest of the tree uses`() {
        val state = ControllerState(active = true, poseValid = true)
        state.position.set(0f, 0f, -3f)
        val pointer = controllerPointer(state = { state })

        // Identity rotation: +Z local forward, matching ImGuiPanel.controllerRay.
        val forward = pointer.sample()
        assertTrue(forward.active)
        assertEquals(1.0, forward.ray!!.dirZ, 1e-5, "identity rotation aims along +Z")

        // A quarter turn about +Y takes +Z to +X.
        state.rotation.set(Quaternionf().identity().rotateY((Math.PI / 2).toFloat()))
        val turned = pointer.sample()
        assertEquals(1.0, turned.ray!!.dirX, 1e-5, "a +90 deg yaw takes the aim to +X")
        assertEquals(0.0, turned.ray.dirZ, 1e-5)

        state.triggerValue = 0.42f
        assertEquals(0.42, pointer.sample().select, 1e-6, "the trigger is the select value")
        state.triggerValue = 0f
        state.selectPressed = true
        assertEquals(1.0, pointer.sample().select, "a digital select still reads as fully pressed")

        state.poseValid = false
        assertTrue(!pointer.sample().active, "an invalid pose is not a pointer")
        state.poseValid = true; state.active = false
        assertTrue(!pointer.sample().active, "nor is an inactive controller")
        assertTrue(!controllerPointer(state = { null }).sample().active, "nor is no controller at all")
    }

    /** A hand with every joint valid, wrist at the origin, thumb and index [gap] metres apart. */
    private fun hand(gap: Float, pinchAt: Vec3 = Vec3(0f, 0f, 0.25f)): HandTrackingState {
        val s = HandTrackingState()
        s.isActive = true
        for (j in s.joints) { j.positionValid = true; j.orientationValid = true }
        s[HandJoint.WRIST].position.set(0f, 0f, 0f)
        s[HandJoint.THUMB_TIP].position.set(pinchAt.x - gap / 2, pinchAt.y, pinchAt.z)
        s[HandJoint.INDEX_TIP].position.set(pinchAt.x + gap / 2, pinchAt.y, pinchAt.z)
        s[HandJoint.INDEX_PROXIMAL].position.set(pinchAt.x, pinchAt.y - 0.05f, pinchAt.z * 0.6f)
        // The same formula OpenXRHmdTarget computes: 0 at a 50 mm gap, 1 at 10 mm.
        s.pinchStrength = ((0.05f - gap) / 0.04f).coerceIn(0f, 1f)
        return s
    }

    @Test
    fun `a pinched hand aims from the wrist through the pinch and clicks by closing`() {
        var state = hand(gap = 0.05f)
        val pointer = pinchPointer(state = { state })

        val open = pointer.sample()
        assertTrue(open.active, "an open, tracked hand is a live pointer")
        assertEquals(0.0, open.select, 1e-5, "a 50 mm gap is not a pinch")
        // Wrist at the origin, pinch point at +0.25 z: the aim is +z.
        assertEquals(1.0, open.ray!!.dirZ, 1e-5, "the ray runs wrist → pinch point")
        assertEquals(0.25, open.ray.originZ, 1e-5, "and starts at the pinch point")

        state = hand(gap = 0.01f)
        assertEquals(1.0, pointer.sample().select, 1e-5, "a 10 mm gap is a full pinch")

        // The load-bearing property: closing the pinch must not swing the aim. This is why the ray is
        // wrist-through-pinch and not along the index finger.
        val openRay = hand(gap = 0.05f).let { s -> pinchPointer(state = { s }).sample().ray!! }
        val closedRay = hand(gap = 0.01f).let { s -> pinchPointer(state = { s }).sample().ray!! }
        val swing = Math.toDegrees(
            kotlin.math.acos(
                (openRay.dirX * closedRay.dirX + openRay.dirY * closedRay.dirY + openRay.dirZ * closedRay.dirZ)
                    .coerceIn(-1.0, 1.0)
            )
        )
        assertTrue(
            swing < 0.5,
            "committing to the pinch must not move the aim, or you cannot click what you aimed at; " +
                "it swung ${"%.4f".format(swing)} deg",
        )

        // Tracking loss, in each of the ways it happens.
        val untracked = hand(gap = 0.02f).also { it.isActive = false }
        assertTrue(!pinchPointer(state = { untracked }).sample().active, "an inactive hand is not a pointer")
        val lostFinger = hand(gap = 0.02f).also { it[HandJoint.INDEX_TIP].positionValid = false }
        assertTrue(!pinchPointer(state = { lostFinger }).sample().active, "a lost fingertip is not a pointer")
        val collapsed = hand(gap = 0.02f, pinchAt = Vec3(0f, 0f, 0f))
        assertTrue(
            !pinchPointer(state = { collapsed }).sample().active,
            "a pinch point on top of the wrist is a glitch, not a direction",
        )
    }

    // ------------------------------------------------------------------------------------------
    // Hysteresis on the part — the dwell
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a hover alternating across a shared edge flickers without the dwell and not with it`() {
        val scene = twoSlabsSharingAnEdge()
        // Confirm the premise: these two rays really do land on different parts.
        assertEquals("west", scene.pick(downAt(-0.001))!!.objectName)
        assertEquals("east", scene.pick(downAt(0.001))!!.objectName)

        // 20 samples alternating either side of the shared edge — a hand tremor of one thousandth of
        // a world unit.
        val jitter = (0 until 20).map { PointerState(downAt(if (it % 2 == 0) -0.001 else 0.001)) }

        fun run(switchSamples: Int): List<String?> {
            val machine = PointerInteraction(scene, switchSamples = switchSamples)
            return jitter.map { machine.update(it); machine.hover?.objectName }
        }

        val without = run(1)
        val changes = without.zipWithNext().count { (a, b) -> a != b }
        assertTrue(
            changes >= 18,
            "premise for the counterfactual: with the dwell disabled the hover must actually flicker. " +
                "It changed only $changes times in ${without.size} samples: $without",
        )

        val with = run(3)
        val settledChanges = with.zipWithNext().count { (a, b) -> a != b }
        assertTrue(
            settledChanges <= 1,
            "with a 3-sample dwell an alternating ray must settle on one part and stay. " +
                "It changed $settledChanges times: $with",
        )
        assertEquals(
            "west", with.last(),
            "and it should settle on whichever part it saw first, not on nothing",
        )
    }

    @Test
    fun `a real move crosses immediately once it has held for the dwell, and a stray sample does not`() {
        val scene = twoSlabsSharingAnEdge()
        val machine = PointerInteraction(scene, switchSamples = 3)

        // Settle on west.
        repeat(4) { machine.update(PointerState(downAt(-1.0))) }
        assertEquals("west", machine.hover?.objectName)

        // One stray sample on east must not move the hover.
        machine.update(PointerState(downAt(1.0)))
        assertEquals("west", machine.hover?.objectName, "one sample is not a move")
        machine.update(PointerState(downAt(-1.0)))
        assertEquals("west", machine.hover?.objectName)

        // Three in a row does.
        machine.update(PointerState(downAt(1.0)))
        machine.update(PointerState(downAt(1.0)))
        assertEquals("west", machine.hover?.objectName, "two is still not enough")
        machine.update(PointerState(downAt(1.0)))
        assertEquals("east", machine.hover?.objectName, "the third consecutive sample earns the move")
    }

    @Test
    fun `acquiring a hover is instant and losing one waits, which is the asymmetry on purpose`() {
        val scene = twoSlabsSharingAnEdge()
        val machine = PointerInteraction(scene, switchSamples = 3)
        // Aim into empty space.
        repeat(4) { machine.update(PointerState(downAt(50.0))) }
        assertEquals(PointerPhase.IDLE, machine.phase)
        assertNull(machine.hover)

        // Acquisition is immediate: there is nothing to arbitrate against, and a lag here reads as
        // the pointer being broken.
        machine.update(PointerState(downAt(-1.0)))
        assertEquals("west", machine.hover?.objectName, "pointing at a thing must light it up at once")
        assertEquals(PointerPhase.HOVER, machine.phase)

        // Release waits, which is the case it would be easy to miss: a ray grazing a silhouette
        // alternates between hitting and missing, and an instant release would flicker there exactly
        // as badly as a shared edge does.
        machine.update(PointerState(downAt(50.0)))
        assertEquals("west", machine.hover?.objectName, "one missed sample must not drop the hover")
        machine.update(PointerState(downAt(50.0)))
        assertEquals("west", machine.hover?.objectName, "nor two")
        machine.update(PointerState(downAt(50.0)))
        assertNull(machine.hover, "three consecutive misses is genuinely pointing away")
        assertEquals(PointerPhase.IDLE, machine.phase)
    }

    @Test
    fun `a ray grazing a silhouette does not flicker on and off`() {
        val scene = twoSlabsSharingAnEdge()
        // The west slab spans x in -2..0. Alternate either side of its far edge at x = -2.
        val grazing = (0 until 20).map { PointerState(downAt(if (it % 2 == 0) -1.999 else -2.001)) }
        assertNotNull(scene.pick(downAt(-1.999)), "premise: one side hits")
        assertNull(scene.pick(downAt(-2.001)), "premise: the other misses")

        fun run(switchSamples: Int): List<String?> {
            val machine = PointerInteraction(scene, switchSamples = switchSamples)
            return grazing.map { machine.update(it); machine.hover?.objectName }
        }

        val without = run(1)
        val changes = without.zipWithNext().count { (a, b) -> a != b }
        assertTrue(
            changes >= 18,
            "premise for the counterfactual: with the dwell off, grazing a silhouette must flicker. " +
                "It changed $changes times: $without",
        )
        val with = run(3)
        val settled = with.zipWithNext().count { (a, b) -> a != b }
        assertTrue(
            settled == 0,
            "with the dwell on, a grazing ray must hold its hover throughout. It changed $settled " +
                "times: $with",
        )
        assertEquals("west", with.last())
    }

    // ------------------------------------------------------------------------------------------
    // Hysteresis on the select — the Schmitt trigger
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a select value sitting on the threshold chatters with one threshold and not with two`() {
        val scene = twoSlabsSharingAnEdge()
        val ray = downAt(-1.0)
        // A pinch strength wobbling around 0.5 — exactly what a geometric finger-gap measure does.
        val wobble = listOf(0.48, 0.52, 0.47, 0.53, 0.49, 0.51, 0.46, 0.54, 0.5, 0.52)

        fun edges(press: Double, release: Double): Int {
            val machine = PointerInteraction(scene, pressThreshold = press, releaseThreshold = release, switchSamples = 1)
            machine.update(PointerState(ray, 0.0))
            var n = 0
            for (v in wobble) {
                machine.update(PointerState(ray, v))
                if (machine.justPressed || machine.justReleased) n++
            }
            return n
        }

        // Effectively one threshold at 0.5: the wobble crosses it on eight of the ten samples, and
        // the first crossing is the first press, so seven edges follow.
        val chatter = edges(press = 0.5, release = 0.4999)
        assertTrue(
            chatter >= 7,
            "premise for the counterfactual: with no hysteresis the wobble must chatter. " +
                "It produced only $chatter press/release edges out of ${wobble.size} samples",
        )

        // The real thresholds bracket the wobble entirely, so nothing happens at all.
        val quiet = edges(press = 0.6, release = 0.35)
        assertEquals(
            0, quiet,
            "a value wobbling inside the hysteresis band must produce no edges; it produced $quiet",
        )

        // And the thresholds are enforced rather than merely documented.
        val thrown = runCatching { PointerInteraction(scene, pressThreshold = 0.3, releaseThreshold = 0.7) }
            .exceptionOrNull()
        assertNotNull(thrown, "an inverted pair of thresholds must be refused")
        assertTrue(thrown.message!!.contains("hysteresis"), "…and say why: ${thrown.message}")
    }

    @Test
    fun `press, drag and release name the thing that was grabbed throughout`() {
        val scene = twoSlabsSharingAnEdge()
        val machine = PointerInteraction(scene, switchSamples = 1)

        machine.update(PointerState(downAt(-1.0), 0.0))
        assertEquals(PointerPhase.HOVER, machine.phase)
        assertNull(machine.grabbed)

        machine.update(PointerState(downAt(-1.0), 1.0))
        assertEquals(PointerPhase.PRESSED, machine.phase)
        assertTrue(machine.justPressed)
        assertEquals("west", machine.grabbed?.objectName)
        assertEquals(0.0, machine.dragDistance, 1e-9, "a press has not dragged yet")

        // Drag the pointer clean off the west slab and onto the east one. The grab must not move.
        machine.update(PointerState(downAt(1.5), 1.0))
        assertEquals(PointerPhase.DRAGGING, machine.phase)
        assertEquals("west", machine.grabbed?.objectName, "a drag belongs to what it grabbed")
        assertEquals(
            "west", machine.hover?.objectName,
            "and the hover is frozen with it, so a label does not jump mid-drag",
        )
        val offset = machine.dragOffset
        assertNotNull(offset)
        assertEquals(
            2.5f, offset.x, 1e-3f,
            "the drag offset is measured on the grab plane: from x = -1 to x = 1.5",
        )
        assertTrue(abs(offset.y) < 1e-3f, "and a vertical ray moving horizontally has no y component")

        // Release while off the object: the click still resolves, and the hover re-resolves at once.
        machine.update(PointerState(downAt(1.5), 0.0))
        assertTrue(machine.justReleased, "dropping below the release threshold is a release")
        assertNull(machine.grabbed)
        assertEquals("east", machine.hover?.objectName, "and the hover catches up in the same frame")
        assertEquals(PointerPhase.HOVER, machine.phase)
    }

    @Test
    fun `a pointer that vanishes mid-press cancels rather than clicking`() {
        val scene = twoSlabsSharingAnEdge()
        val machine = PointerInteraction(scene, switchSamples = 1)
        machine.update(PointerState(downAt(-1.0), 1.0))
        assertEquals(PointerPhase.PRESSED, machine.phase)

        machine.update(PointerState.inactive)
        assertTrue(machine.justCancelled, "a hand leaving the tracking volume mid-press is not a click")
        assertTrue(!machine.justReleased, "and must not be reported as one")
        assertEquals(PointerPhase.IDLE, machine.phase)
        assertNull(machine.grabbed)
        assertNull(machine.hover)
    }

    @Test
    fun `a whole gesture replays identically from three different devices`() {
        // The point of the one-interface design: the same script drives a mouse, a controller and a
        // hand, and the machine cannot tell which it was.
        val scene = twoSlabsSharingAnEdge()
        val script = listOf(
            downAt(-1.0) to 0.0,
            downAt(-1.0) to 0.9,
            downAt(0.5) to 0.9,
            downAt(0.5) to 0.1,
        )

        fun replay(source: (Ray, Double) -> PointerState): List<String> {
            val machine = PointerInteraction(scene, switchSamples = 1)
            return script.map { (ray, select) ->
                machine.update(source(ray, select))
                "${machine.phase}/${machine.grabbed?.objectName ?: "-"}/${machine.hover?.objectName ?: "-"}"
            }
        }

        val direct = replay { ray, select -> PointerState(ray, select) }

        // A controller aimed along the same ray, with the trigger as the select.
        val viaController = replay { ray, select ->
            val s = ControllerState(active = true, poseValid = true)
            s.position.set(ray.originX.toFloat(), ray.originY.toFloat(), ray.originZ.toFloat())
            s.triggerValue = select.toFloat()
            // Rotate +Z onto the ray's direction; here every ray points along -Y.
            s.rotation.set(Quaternionf().identity().rotateX((Math.PI / 2).toFloat()))
            controllerPointer(state = { s }).sample()
        }

        // A hand whose wrist is above the pinch point, so wrist → pinch also runs along -Y.
        val viaHand = replay { ray, select ->
            val gap = 0.05f - (select.toFloat() * 0.04f)
            val h = HandTrackingState()
            h.isActive = true
            for (j in h.joints) { j.positionValid = true }
            h[HandJoint.WRIST].position.set(ray.originX.toFloat(), ray.originY.toFloat() + 0.3f, ray.originZ.toFloat())
            h[HandJoint.THUMB_TIP].position.set(ray.originX.toFloat() - gap / 2, ray.originY.toFloat(), ray.originZ.toFloat())
            h[HandJoint.INDEX_TIP].position.set(ray.originX.toFloat() + gap / 2, ray.originY.toFloat(), ray.originZ.toFloat())
            h.pinchStrength = ((0.05f - gap) / 0.04f).coerceIn(0f, 1f)
            pinchPointer(state = { h }).sample()
        }

        assertEquals(direct, viaController, "a controller must drive the machine exactly as a raw ray does")
        assertEquals(direct, viaHand, "and so must a hand")
        // And the script must have actually done something, or the agreement is worthless.
        assertTrue(
            direct.distinct().size >= 3,
            "premise: the script must move through at least three distinct states; it was $direct",
        )
        assertTrue(direct.any { it.startsWith("DRAGGING") }, "…including a drag")
    }

    @Test
    fun `a transformed pickable still names the part a pointer touched`() {
        // The composition the whole layer exists for: a transform, a part assignment, a pointer.
        val builder = TriMeshBuilder()
        builder.group("lower"); builder.add(boxAt(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
        builder.group("upper"); builder.add(boxAt(0.0, 2.0, 0.0, 1.0, 1.0, 1.0))
        val scene = PickScene(
            listOf(
                Pickable(
                    "stack", builder.build(), builder.groups(),
                    at = Matrix4f().identity().translate(5f, 0f, 0f),
                )
            )
        )
        val machine = PointerInteraction(scene, switchSamples = 1)
        machine.update(PointerState(Ray(5.0, 9.0, 0.0, 0.0, -1.0, 0.0)))
        assertEquals("upper", machine.hover?.partName, "straight down hits the upper box first")
        machine.update(PointerState(Ray(5.0, 1.0, 0.0, 0.0, -1.0, 0.0)))
        assertEquals("lower", machine.hover?.partName, "starting between them hits the lower one")
        assertEquals(0.5, machine.hover!!.distance, 1e-4, "and reports a world distance")
    }
}
