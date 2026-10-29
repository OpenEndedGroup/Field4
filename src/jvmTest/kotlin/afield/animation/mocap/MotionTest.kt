package afield.animation.mocap

import afield.geometry.path.FLine
import afield.math.linalg.Quat
import afield.math.linalg.Vec3
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionTest {
    @Test fun positionsOnlyReferenceSurvivesBinaryAndInterpolation() {
        val reference = MotionPose(listOf(0.0, 0.0, 0.0), emptyList(), listOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0))
        val first = MotionFrame(0.0, 0, 0, 0, pose(), reference)
        val second = MotionFrame(1.0, 1, 0, 0, pose(1.0), reference)
        val decoded = MotionBinary.decode(MotionBinary.encode(first), 2)
        assertFalse(decoded.intended!!.hasRotations)
        assertTrue(decoded.intended.localRotations.isEmpty())
        assertFails { decoded.intended.rotation(0) }
        val halfway = MotionClip(skeleton(), listOf(first, second)).sample(.5).frame
        assertFalse(halfway.intended!!.hasRotations)
        assertEquals(reference.worldPositions, halfway.intended.worldPositions)
    }
    private fun skeleton() = MotionSkeleton("fixture.two-joints", "1", listOf("root", "tip"), listOf(-1, 0),
        listOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0), listOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    private fun pose(x: Double = 0.0, q: List<Double> = listOf(0.0, 0.0, 0.0, 1.0)) =
        MotionPose(listOf(x, 0.0, 0.0), q + q, listOf(x, 0.0, 0.0, x, 1.0, 0.0))
    private fun frame(i: Int, episode: Long = 0, x: Double = i * .1) = MotionFrame(i * .05, i.toLong(), episode, i.toLong(),
        pose(x), pose(x), listOf(.25, .75), "fixture", mapOf("test" to 1.5))
    private fun near(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-5f); assertEquals(expected.y, actual.y, 1e-5f); assertEquals(expected.z, actual.z, 1e-5f)
    }
    @Test fun coordinatesConjugateRotationsAndTreatMomentsAsAxial() {
        val c = MotionCoordinates.isaacGym()
        near(Vec3(2f, 4f, -3f), c.position(Vec3(2f, 3f, 4f)))
        val q = Quat().setAngleAxis((PI / 2).toFloat(), 0f, 0f, 1f)
        near(c.direction(q.transform(Vec3(1f, 0f, 0f))), c.rotation(q).transform(c.direction(Vec3(1f, 0f, 0f))))
        val mirror = MotionCoordinates(sourceHandedness = "left", sourceToField = listOf(-1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        near(Vec3(0f, -1f, 0f), mirror.moment(Vec3(0f, 1f, 0f)))
        val force = Vec3(2f, 1f, 3f); val radius = Vec3(4f, 1f, 2f)
        near(mirror.moment(Vec3(radius).cross(force)), mirror.direction(radius).cross(mirror.direction(force)))
        near(mirror.direction(q.transform(Vec3(1f, 0f, 0f))), mirror.rotation(q).transform(mirror.direction(Vec3(1f, 0f, 0f))))
        assertFails { MotionCoordinates(sourceToField = List(9) { 1.0 }) }
    }
    @Test fun skeletonHashMatchesPythonAndTopologyCannotCycle() {
        val s = skeleton()
        assertEquals("3b8ec9b7fd3af8804c638b5df534f9ed705bd2176fa4f476516cdba1af093acb", s.hash)
        assertEquals(s.hash, MotionSkeleton.fromJson(s.json()).hash)
        assertFails { MotionSkeleton("cycle", "1", s.names, listOf(-1, 1), s.restTranslations, s.restRotations) }
        assertFails { MotionSkeleton.fromJson(s.json().put("hash", "changed")) }
        val globals = s.sceneSkeleton().restGlobals
        near(Vec3(0f, 1f, 0f), globals[1].transformPosition(Vec3()))
        assertFails { s.body("tip") }
    }
    @Test fun interpolationUsesShortestArcAndHoldsAcrossEpisodes() {
        val a = MotionFrame(0.0, 0, 0, 0, pose())
        val b = MotionFrame(1.0, 1, 0, 1, pose(2.0, listOf(0.0, 0.0, 0.0, -1.0)))
        val clip = MotionClip(skeleton(), listOf(a, b))
        val sample = clip.sample(.5)
        assertEquals(1.0, sample.frame.executed.root[0], 1e-6)
        near(Vec3(1f, 0f, 0f), sample.frame.executed.rotation(0).transform(Vec3(1f, 0f, 0f)))
        val reset = MotionClip(skeleton(), listOf(a, MotionFrame(1.0, 1, 1, 1, pose(10.0))))
        assertEquals(0.0, reset.sample(.99).frame.executed.root[0])
        assertTrue(reset.sample(.99).held)
        assertEquals(10.0, reset.sample(1.0).frame.executed.root[0])
        assertTrue(clip.sample(5.0).held)
        assertEquals(4.0, clip.sample(5.0).lagSeconds)
    }
    @Test fun bufferRemainsBoundedAndRejectsOldFrames() {
        val buffer = MotionBuffer(3, .11)
        repeat(20) { buffer.add(frame(it)) }
        assertTrue(buffer.size() <= 3 && buffer.dropped >= 17)
        assertFails { buffer.add(frame(18)) }
        buffer.clear()
        assertNull(buffer.sample(0.0))
    }
    @Test fun chunkReplayPreservesFloatBitsAndRejectsTruncation() {
        val root = Files.createTempDirectory("motion-recording")
        val target = root.resolve("test.f4motion")
        try {
            MotionRecorder(target, skeleton(), JSONObject().put("backend", "fixture"), 2).use { out ->
                repeat(5) { out.append(frame(it, if (it >= 3) 1 else 0)) }
                out.event(JSONObject().put("type", "reset").put("episode", 1))
            }
            val recording = MotionRecording(target)
            assertEquals(3, recording.metadata.getJSONArray("chunks").length())
            val frames = recording.frames().toList()
            assertEquals(5, frames.size)
            frames.forEach { assertTrue(MotionBinary.encode(it).contentEquals(MotionBinary.encode(MotionBinary.decode(MotionBinary.encode(it), 2)))) }
            assertEquals("reset", recording.events().single().getString("type"))
            val chunk = target.resolve("chunk-000002.bin")
            Files.write(chunk, Files.readAllBytes(chunk).dropLast(1).toByteArray())
            assertFails { recording.frames().toList() }
            assertFails { MotionRecorder(target, skeleton(), JSONObject()) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun pointForceIncludesAngularVelocityTorqueCapAndPower() {
        val w = pointTether(Vec3(1f, 0f, 0f), Vec3(), Vec3(), Vec3(0f, 0f, 2f),
            Vec3(1f, 2f, 0f), stiffness = 10.0, damping = 2.0, maximumForce = 5.0)
        near(Vec3(0f, 2f, 0f), w.velocity)
        near(Vec3(0f, 5f, 0f), w.force)
        near(Vec3(0f, 0f, 5f), w.torque)
        assertEquals(16.0, w.rawMagnitude)
        assertEquals(10.0, w.powerWatts)
        val zero = pointTether(Vec3(), Vec3(), Vec3(), Vec3(), Vec3(), stiffness = 0.0, damping = 0.0, maximumForce = 1.0)
        near(Vec3(), zero.force)
    }
    @Test fun routeUsesExistingArcLengthWithIndependentTiming() {
        val route = FLine().moveTo(0, 0, 0).lineTo(0, 0, 4).motionRoute(2.0, 4.0, 3, true)
        assertEquals(listOf(2.0, 4.0, 6.0), route.map { it.time })
        assertEquals(listOf(0.0, 2.0, 4.0), route.map { it.z })
        assertTrue(route.all { it.heading == 0.0 })
        assertFails { FLine().moveTo(0, 0).lineTo(1, 0).motionRoute(0.0, 1.0, distanceFraction = { 1 - it }) }
    }
    @Test fun replayRejectsPhysicalOperationsAndClosesSubscriptions() {
        val session = MotionSession.replay(MotionClip(skeleton(), List(5) { frame(it) }))
        var callbacks = 0
        val subscription = session.subscribe { callbacks++ }
        assertFails { session.setPrompt("walk", 0.0) }
        assertEquals(3, session.advance(.1).get(3, TimeUnit.SECONDS).frames.size)
        assertEquals(3, callbacks)
        subscription.close()
        session.advance(.1).get(3, TimeUnit.SECONDS)
        assertEquals(3, callbacks)
        session.close()
        assertFails { session.advance(.1) }
    }
    @Test fun coalescingRetainsPhysicalEventOrderAndQueueHasABound() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(ArrayList<MotionOperation>())
        val b = object : MotionBackend {
            override val skeleton = skeleton()
            override val capabilities = MotionCapabilities("test-only", MotionOperation.entries.toSet(), JSONObject())
            override fun command(command: MotionCommand): MotionApplied {
                seen.add(command.operation)
                if (command.operation == MotionOperation.PROMPT) { entered.countDown(); release.await() }
                return MotionApplied(command.requestedTime, 0)
            }
            override fun advance(seconds: Double) = MotionUpdate(emptyList())
            override fun close() { release.countDown() }
        }
        val session = MotionSession(b, 3)
        try {
            session.setPrompt("block", 0.0); assertTrue(entered.await(3, TimeUnit.SECONDS))
            val a = session.setRootPlan(emptyList(), 0.0)
            val latest = session.setRootPlan(emptyList(), 0.0)
            assertNull(a.get(3, TimeUnit.SECONDS))
            val detach = session.detach("tether", 0.0)
            val after = session.setRootPlan(emptyList(), 0.0)
            assertEquals(3, session.queued())
            assertFails { session.advance(.1) }
            release.countDown()
            after.get(3, TimeUnit.SECONDS); latest.get(3, TimeUnit.SECONDS); detach.get(3, TimeUnit.SECONDS)
            assertEquals(listOf(MotionOperation.PROMPT, MotionOperation.ROOT_PLAN, MotionOperation.DETACH, MotionOperation.ROOT_PLAN), seen.toList())
        } finally { session.close() }
    }
    @Test fun malformedPoseCannotReachRenderer() {
        assertFails { pose(Double.NaN) }
        assertFails { pose(q = listOf(0.0, 0.0, 0.0, 0.0)) }
        assertFails { MotionBinary.decode(byteArrayOf(127, -1, -1, -1), 2) }
        assertFails { MotionAttachment("pin", "foot", listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0), stiffness = -1.0) }
    }
}
