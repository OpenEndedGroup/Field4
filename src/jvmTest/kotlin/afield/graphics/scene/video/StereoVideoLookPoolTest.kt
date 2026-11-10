package afield.graphics.scene.video

import java.nio.file.Files
import kotlin.test.*

class StereoVideoLookPoolTest {
    private val slow = StereoVideoLook(1f, .48, 1.06, true, 12.0, 11.7f, .68f, .245f)
    private val sharp = StereoVideoLook(0f, .1, .5, false, 6.0, 0f, 0f, 0f, opacity = .3f,
        blackLevel = .1f, whiteLevel = .7f, gamma = 1.6f, autoExposureMix = .35f, accumulationColourTransfer = .8f, shadowCooling = .3f, accumulationShadowCooling = .6f)

    @Test fun capturesOneScreenAndRecallsAnySubsetWithOptionalSharedSpeed() {
        val directory = Files.createTempDirectory("field-screen-looks")
        try {
            val views = MutableList(8) { sharp }
            views[5] = slow
            var speed = 1.0
            val pool = StereoVideoLookPool(directory, { views.size }, { views[it] },
                { i, look -> views[i] = look }, { speed }, { speed = it })
            val before = views.toList()
            val key = pool.store("slow blur", screen = 5)
            assertEquals(before, views)
            assertEquals(1.0, key["playback.speed"])
            assertEquals(listOf("slow blur"), pool.names)

            speed = 8.0
            assertTrue(pool.recall("slow blur", listOf(1, 3, 7), withSpeed = false))
            assertEquals(8.0, speed)
            assertEquals(listOf(sharp, slow, sharp, slow, sharp, slow, sharp, slow), views)
            assertTrue(pool.recall("slow blur", listOf(0, 2), withSpeed = true))
            assertEquals(1.0, speed)
            assertEquals(sharp, views[4])
            assertEquals(sharp, views[6])

            // Native keyframeBrowser Apply takes the same gated path, without the recall helper.
            speed = 4.0
            pool.restoreSpeed = false
            pool.targetScreens = setOf(6)
            pool.scope.loadKey(pool.path("slow blur"))()
            assertEquals(slow, views[6])
            assertEquals(4.0, speed)
            pool.restoreSpeed = true
            pool.scope.loadKey(pool.path("slow blur"))()
            assertEquals(1.0, speed)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun versionsSurviveNewPoolAndBindingsFollowReplacementScreens() {
        val directory = Files.createTempDirectory("field-screen-looks")
        try {
            var views = MutableList(3) { slow }
            var speed = 2.0
            fun pool() = StereoVideoLookPool(directory, { views.size }, { views[it] },
                { i, look -> views[i] = look }, { speed }, { speed = it })
            pool().store("look", 0)
            views[1] = sharp
            pool().store("look", 1)
            val reopened = pool()
            assertEquals(2, reopened.scope.keyVersions(reopened.path("look")).size)
            views = MutableList(3) { slow }
            assertTrue(reopened.recall("look", setOf(2)))
            assertEquals(listOf(slow, slow, sharp), views)
            assertEquals(2.0, speed)
            val restored = views.toList()
            assertFalse(reopened.recall("missing", setOf(0)))
            assertEquals(restored, views)
            assertFailsWith<IllegalArgumentException> { reopened.path("../outside") }
            assertFailsWith<IllegalArgumentException> { reopened.store("bad", 3) }
            assertFailsWith<IllegalArgumentException> { reopened.recall("look", emptySet(), false) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
