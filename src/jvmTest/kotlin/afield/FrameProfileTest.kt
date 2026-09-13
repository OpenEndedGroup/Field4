package afield

import kotlinx.coroutines.*
import kotlin.test.*

class FrameProfileTest {
    @Volatile private var escaped: Any? = null
    private fun origin(): ProfileOrigin {
        val box=Box("frame-owner")
        box.inside=SessionInformation("frame-profile-test",root=box,ephemeral=true)
        box.properties["code"]="frames { work() }"
        return ProfileOrigin(box)
    }

    @Test fun nestedScopesAndSimulationChildrenKeepSelfAccountingAndNames() {
        val origin=origin()
        val context=origin+Job()
        CoroutineProfiles.request(origin.document,CoroutineProfileRequest("start",3.0))
        try {
            CoroutineProfiles.resume(context, Runnable {
                profileScope("outer") {
                    escaped=ByteArray(8192)
                    SimulationClock(context).use { clock ->
                        clock.launch(CoroutineName("orbit")) { profileScope("steer") { escaped=ByteArray(65536) } }
                        clock.runCurrent()
                    }
                }
            })
            val s=CoroutineProfiles.request(origin.document,CoroutineProfileRequest("stop"))
            val outer=s.spans.single { it.depth==0 }
            assertEquals(outer.wallMs,s.spans.sumOf { it.selfWallMs },1e-5)
            assertEquals(outer.allocatedBytes,s.spans.sumOf { it.selfAllocatedBytes })
            assertTrue(s.lanes.any { it.label=="orbit" && it.boxId==origin.box.id && it.kind=="resume" })
            assertTrue(s.lanes.any { it.label=="steer" && it.kind=="scope" })
            assertTrue(s.spans.maxOf { it.depth }>=3)
            assertTrue(s.spans.all { it.selfWallMs>=0 && it.selfWallMs<=it.wallMs })
        } finally { CoroutineProfiles.request(origin.document,CoroutineProfileRequest("stop")) }
    }

    @Test fun intervalsEstablishFreshBaselinesForEachCaptureAndPageSeparately() {
        val origin=origin()
        FrameProfile(origin,name="Game",allocationKiB=16.0).use { frames ->
            frames.beginFrame() // off: no sample and no stale baseline
            repeat(2) {
                CoroutineProfiles.request(origin.document,CoroutineProfileRequest("start",3.0))
                try {
                    frames.beginFrame()
                    escaped=ByteArray(65536)
                    frames.beginFrame()
                    val s=CoroutineProfiles.request(origin.document,CoroutineProfileRequest("stop"))
                    assertEquals(1,s.frameRecords.size)
                    assertTrue(s.frameRecords.single().allocatedBytes>=65536)
                    assertEquals(16384.0,s.frameRecords.single().allocationGuideBytes)
                    val tail=CoroutineProfiles.request(origin.document,CoroutineProfileRequest(captureId=s.captureId,frameAfter=0))
                    assertTrue(tail.frameRecords.isEmpty())
                } finally { CoroutineProfiles.request(origin.document,CoroutineProfileRequest("stop")) }
            }
        }
    }

    @Test fun frameRingBoundsAndUnsupportedSelfCountersStayExplicit() {
        val frames=ProfileFrames(2)
        repeat(4) { frames.add(1,it*100L,it*100L+20,-1,-1,16.67,512.0) }
        assertEquals(2,frames.oldest)
        val result=frames.snapshot(-1,0)
        assertEquals(listOf(2L,3L),result.map { it.sequence })
        assertTrue(result.all { it.cpuMs==-1.0 && it.allocatedBytes==-1L })
        assertEquals(-1,ProfileCounters.delta(100,-1))
    }
}
