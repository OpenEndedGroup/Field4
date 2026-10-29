package afield.animation.mocap

import afield.math.linalg.Quat
import afield.math.linalg.Vec3
import afield.graphics.three.ThreeCameraFollowSpec
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class MotionAuthoringTest {
    private val skeleton = MotionSkeleton("body-map", "1", listOf("root", "foot-joint"), listOf(-1,0),
        listOf(0.0,0.0,0.0, 0.0,1.0,0.0), listOf(0.0,0.0,0.0,1.0, 0.0,0.0,0.0,1.0),
        bodies=listOf(MotionBody("physical-foot", "foot-joint", 1.0)))
    private fun pose(): MotionPose {
        val q=Quat().setAngleAxis((PI/2).toFloat(),0f,0f,1f)
        return MotionPose(listOf(0.0,0.0,0.0),listOf(q.x.toDouble(),q.y.toDouble(),q.z.toDouble(),q.w.toDouble(),0.0,0.0,0.0,1.0),listOf(0.0,0.0,0.0,0.0,1.0,0.0))
    }
    private inner class Backend : MotionBackend {
        override val skeleton get() = this@MotionAuthoringTest.skeleton
        override val capabilities = MotionCapabilities("closd",MotionOperation.entries.toSet(),JSONObject().put("fixedControlStep",1.0/30))
        val commands=CopyOnWriteArrayList<MotionCommand>()
        override fun command(command:MotionCommand):MotionApplied { commands.add(command); return MotionApplied(.1,0) }
        override fun advance(seconds:Double)=MotionUpdate(listOf(MotionFrame(.1,0,0,0,pose())))
        override fun close()=Unit
    }
    @Test fun bodyPointsUseTheExplicitMappingAndComposedRotation() {
        val backend=Backend();val session=MotionSession(backend)
        session.use {
            session.advance(.1).get(3,TimeUnit.SECONDS)
            assertFails { session.body("foot-joint") }
            val local=Vec3(.25f,0f,0f)
            val tether=session.body("physical-foot").tether("left") { point=local;stiffness=8000.0;maxForce=2500.0;ramp=300.milliseconds }
            tether.accepted.get(3,TimeUnit.SECONDS);local.x=9f
            val p=backend.commands.last().payload
            assertEquals("physical-foot",p.getString("body"))
            assertEquals(.25,p.getJSONArray("localPoint").getDouble(0))
            assertEquals(0.0,p.getJSONArray("anchor").getDouble(0),1e-6)
            assertEquals(1.25,p.getJSONArray("anchor").getDouble(1),1e-6)
            assertEquals(.3,p.getDouble("engagementSeconds"))
            tether.tune { damping=120.0;breakAbove(2000.0,200.milliseconds) }.get(3,TimeUnit.SECONDS)
            assertEquals(2000.0,backend.commands.last().payload.getDouble("breakForce"))
            session.body("physical-foot").impulse(Vec3(10f,0f,0f)).get(3,TimeUnit.SECONDS)
            tether.release().get(3,TimeUnit.SECONDS)
            assertEquals(listOf(MotionOperation.ATTACH,MotionOperation.UPDATE_ATTACHMENT,MotionOperation.IMPULSE,MotionOperation.DETACH),backend.commands.map{it.operation})
        }
    }
    @Test fun telemetryReportsCapBreakAndResetInsteadOfPretendingAnAttachmentSurvives() {
        val t=MotionTelemetry()
        val payload=MotionAttachment("left","physical-foot",listOf(0.0,0.0,0.0),listOf(0.0,0.0,0.0)).json()
        fun accept(vararg e:JSONObject)=t.accept(MotionUpdate(emptyList(),e.toList()))
        accept(JSONObject().put("type","applied").put("operation","ATTACH").put("payload",payload).put("appliedTime",0.0))
        accept(JSONObject().put("type","tetherPhysics").put("id","left").put("physicsTime",.1)
            .put("forceN",50.0).put("rawForceN",100.0).put("errorM",.3).put("powerWatts",-4.0)
            .put("point",listOf(1.0,0.0,0.0)).put("anchor",listOf(0.0,0.0,0.0)).put("force",listOf(-50.0,0.0,0.0)))
        assertTrue(t.tether("left")!!.active);assertTrue(t.tether("left")!!.saturated)
        assertEquals(.3,t.tether("left")!!.stretchMetres)
        accept(JSONObject().put("type","tetherBreak").put("id","left").put("time",.2))
        assertEquals("broken",t.tether("left")!!.reason);assertFalse(t.tether("left")!!.active)
        accept(JSONObject().put("type","reset").put("reason","manual").put("time",1.0))
        assertEquals("reset",t.tether("left")!!.reason);assertTrue(t.lastEvent.contains("released"))
    }
    @Test fun wallPacedReplayClampsAtEndRatherThanWaitingForever() = kotlinx.coroutines.runBlocking {
        val frames=listOf(MotionFrame(0.0,0,0,0,pose()),MotionFrame(.05,1,0,0,pose()))
        MotionSession.replay(MotionClip(skeleton,frames)).use { session ->
            var shown=0
            kotlinx.coroutines.withTimeout(3000) {
                afield.runtime.lifecycle.Frames().playMotion(session,500.milliseconds) { shown++ }
            }
            assertTrue(shown>0);assertEquals(1L,session.latest!!.index)
        }
    }
    @Test fun cameraFollowRejectsInvalidTargetsAndTimeConstants() {
        assertFails { ThreeCameraFollowSpec(listOf(0.0,Double.NaN,0.0)) }
        assertFails { ThreeCameraFollowSpec(listOf(0.0,0.0,0.0),0.0) }
        assertEquals(.4,ThreeCameraFollowSpec(listOf(0.0,0.0,0.0)).halfLifeSeconds)
    }
}
