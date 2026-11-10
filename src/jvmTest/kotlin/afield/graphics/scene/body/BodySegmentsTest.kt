package afield.graphics.scene.body

import afield.math.linalg.Vector3f
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyJoint
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyTrackingState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.ControllerState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.HandJoint
import afield.graphics.vulkan.targets.OpenXRHmdTarget.HandTrackingState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.JointPose
import kotlin.test.*

class BodySegmentsTest {
    private fun pose(x: Float, y: Float, z: Float) = JointPose(position = Vector3f(x,y,z), positionValid = true)
    private fun segment() = BodySegment(0,"limb",Vector3f(0f,0f,0f),Vector3f(0f,1f,0f),.1f)

    @Test fun sideAndRoundedEndHaveMetricStandoff() {
        val side=segment().sample(Vector3f(.3f,.5f,0f),.05f)
        assertEquals(.15f,side.distance,1e-6f)
        assertEquals(.15f,side.point.x,1e-6f)
        val end=segment().sample(Vector3f(0f,1.3f,0f),.05f)
        assertEquals(.15f,end.distance,1e-6f)
        assertEquals(1.15f,end.point.y,1e-6f)
        assertEquals(-.15f,segment().sample(Vector3f(0f,.5f,0f),.05f).distance,1e-6f)
    }

    @Test fun zeroLengthAndAxisHaveFiniteUnitNormals() {
        val sphere=BodySegment(8,"head",Vector3f(),Vector3f(),.1f)
        for(hit in listOf(sphere.sample(Vector3f()),segment().sample(Vector3f(0f,.5f,0f)))) {
            assertTrue(hit.normal.isFinite())
            assertEquals(1f,hit.normal.length(),1e-6f)
            assertTrue(hit.force(1f,1f,.2f).isFinite())
        }
    }

    @Test fun forcePullsOutsidePushesInsideAndVanishesAtSurfaceOrBandEdge() {
        val outside=segment().sample(Vector3f(.2f,.5f,0f))
        assertEquals(-.5f,outside.force(10f,20f,.2f).x,1e-6f)
        val inside=segment().sample(Vector3f(.05f,.5f,0f))
        assertEquals(1f,inside.force(10f,20f,.2f).x,1e-6f)
        assertEquals(0f,outside.force(0f,20f,.2f).length(),1e-6f)
        assertEquals(0f,inside.force(10f,0f,.2f).length(),1e-6f)
        assertEquals(0f,segment().sample(Vector3f(.1f,.5f,0f)).force(10f,20f,.2f).length(),1e-6f)
        assertEquals(0f,segment().sample(Vector3f(.3f,.5f,0f)).force(10f,20f,.2f).length(),1e-6f)
        assertEquals(.1f,inside.force(10f,20f,.2f,maximum=.1f).length(),1e-6f)
    }

    @Test fun interiorInfluenceCanBeConfinedToABand() {
        val hit=segment().sample(Vector3f(.05f,.5f,0f))
        assertEquals(0f,hit.force(10f,20f,.2f,insideReach=.025f).length(),1e-6f)
        assertEquals(.5f,hit.force(10f,20f,.2f,insideReach=.1f).x,1e-6f)
        assertEquals(1f,hit.force(10f,20f,.2f,insideReach=0f).x,1e-6f)
    }

    @Test fun snapshotsCopyTrackingAndNeverKeepLostHandSegments() {
        val hand=HandTrackingState().apply { isActive=true }
        for(j in HandJoint.entries) hand.joints[j.ordinal]=pose(j.ordinal*.01f,0f,0f)
        val first=BodySegments.from(null,left=hand)
        assertEquals(25,first.active.size)
        assertTrue(first.active.all { it.id in 11..35 })
        val copied=Vector3f(first[11]!!.start)
        hand.joints[1].position.set(100f,0f,0f)
        assertEquals(copied,first[11]!!.start)
        hand.isActive=false
        assertTrue(BodySegments.from(null,left=hand).active.isEmpty())
    }

    @Test fun forearmFallsBackToControllerWithoutChangingIdentity() {
        val body=BodyTrackingState()
        body.joints[BodyJoint.LEFT_ARM_LOWER.ordinal]=pose(0f,1f,0f)
        val grip=ControllerState().apply { poseValid=true; position.set(0f,.5f,0f) }
        val first=BodySegments.from(body,leftController=grip)
        assertEquals("LEFT/FOREARM",first[9]!!.name)
        assertEquals(.5f,first[9]!!.end.y)
        grip.poseValid=false
        assertNull(BodySegments.from(body,leftController=grip)[9])
    }

    @Test fun invalidObservationsAreOmittedAndRadiiAreEditable() {
        val body=BodyTrackingState()
        body.joints[BodyJoint.HIPS.ordinal]=pose(0f,0f,0f)
        body.joints[BodyJoint.CHEST.ordinal]=pose(0f,1f,0f)
        val snapshot=BodySegments.from(body,radiusFor={ _,_ -> .25f })
        assertEquals(.25f,snapshot[0]!!.radius)
        assertTrue(BodySegments.from(body,radiusFor={ _,_ -> null }).active.isEmpty())
        body.joints[BodyJoint.CHEST.ordinal].position.x=Float.NaN
        assertTrue(BodySegments.from(body).active.isEmpty())
        assertFailsWith<IllegalArgumentException> { segment().sample(Vector3f(Float.NaN,0f,0f)) }
    }

    @Test fun cachedSearchReevaluatesBothCandidatesAndDropsInvalidPrevious() {
        val body=BodyTrackingState()
        body.joints[BodyJoint.HIPS.ordinal]=pose(0f,0f,0f)
        body.joints[BodyJoint.CHEST.ordinal]=pose(0f,1f,0f)
        val snapshot=BodySegments.from(body,head=pose(1f,1f,0f))
        val query=Vector3f(1f,1f,0f)
        assertEquals(8,snapshot.refine(query,0,8)!!.segment)
        assertEquals(8,snapshot.refine(query,-1,8)!!.segment)
        assertNull(snapshot.refine(query,-1,60))
        // An untested closer capsule is intentionally absent from this approximate answer.
        assertEquals(0,snapshot.refine(query,0,60)!!.segment)
        assertEquals(8,snapshot.closest(query)!!.segment)
    }
}
