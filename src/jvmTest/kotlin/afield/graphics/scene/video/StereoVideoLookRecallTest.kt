package afield.graphics.scene.video

import java.nio.file.Files
import kotlin.test.*

class StereoVideoLookRecallTest {
    @Test fun staggerIsOrderedSpeedAppliesOnceAndTargetsStaySelected() {
        val dir = Files.createTempDirectory("field-look-wave")
        try {
            val a = StereoVideoLook(1f, .48, 1.06, true, 12.0, 11.7f, .68f, .245f)
            val b = a.copy(accumulationMix = 0f)
            val views = MutableList(8) { a }
            var speed = 1.0
            var speedWrites = 0
            var now = 0L
            val pool = StereoVideoLookPool(dir, { views.size }, { views[it] },
                { i, look -> views[i] = look }, { speed }, { speed = it; speedWrites++ })
            pool.store("a")
            views.fill(b)
            speed = 8.0
            val recall = StereoVideoLookRecall({ pool }, { speed = it; speedWrites++ }, { now })
            recall.recall("a", setOf(5, 1, 3), withSpeed = true, delaySeconds = .2)
            assertEquals(1.0, speed)
            assertEquals(1, speedWrites)
            assertEquals(a, views[1])
            assertEquals(b, views[3])
            assertEquals(2, recall.pendingCount)
            assertEquals(setOf(1, 3, 5), pool.targetScreens)
            now = 199_000_000
            recall.tick()
            assertEquals(b, views[3])
            now = 200_000_000
            recall.tick()
            assertEquals(a, views[3])
            now = 400_000_000
            recall.tick()
            assertEquals(a, views[5])
            assertEquals(b, views[0])
            assertEquals(0, recall.pendingCount)
            assertEquals(1, speedWrites)
            assertTrue(pool.restoreSpeed)
            assertEquals(setOf(1, 3, 5), pool.targetScreens)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun newRecallReplacesPendingWaveAndCanKeepSpeed() {
        val dir = Files.createTempDirectory("field-look-wave")
        try {
            val a = StereoVideoLook(1f, .48, 1.06, true, 12.0, 11.7f, .68f, .245f)
            val b = a.copy(accumulationMix = 0f)
            val views = MutableList(3) { a }
            var speed = 1.0
            var now = 0L
            val pool = StereoVideoLookPool(dir, { 3 }, { views[it] }, { i, v -> views[i] = v }, { speed }, { speed = it })
            pool.store("a")
            views.fill(b)
            pool.store("b")
            speed = 8.0
            val recall = StereoVideoLookRecall({ pool }, { speed = it }, { now })
            recall.recall("a", setOf(0, 1, 2), false, 1.0)
            recall.recall("b", setOf(0), false, 0.0)
            now = 10_000_000_000
            recall.tick()
            assertEquals(listOf(b, b, b), views)
            assertEquals(8.0, speed)
            assertEquals(0, recall.pendingCount)
        } finally { dir.toFile().deleteRecursively() }
    }
}
