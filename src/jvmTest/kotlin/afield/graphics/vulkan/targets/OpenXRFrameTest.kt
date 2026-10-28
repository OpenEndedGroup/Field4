package afield.graphics.vulkan.targets

import kotlin.test.*

class OpenXRFrameTest {
    private data class Frame(val displayTime: Long, val pose: Int)

    @Test
    fun sceneUsesCurrentPoseAndSubmissionRetainsItsDisplayTime() {
        var pose = 1 // The previous frame's pose, which the old ordering rendered with.
        val next = Frame(22_222_222, 2)
        val events = mutableListOf<String>()
        var renderedPose = -1
        withOpenXRFrame(
            prepare = { events += "locate"; pose = next.pose; next },
            renderScene = { events += "scene"; renderedPose = pose },
            submit = { frame, rendered ->
                events += "submit"
                assertSame(next, frame)
                assertTrue(rendered)
                assertEquals(renderedPose, frame.pose)
                assertEquals(22_222_222, frame.displayTime)
            },
            release = { assertSame(next, it); events += "release" },
        )
        assertEquals(listOf("locate", "scene", "submit", "release"), events)
        assertEquals(2, renderedPose)
    }

    @Test
    fun sleepingHeadsetStillRendersDesktopWithoutSubmittingAFrame() {
        var rendered = false
        withOpenXRFrame<Frame>(
            prepare = { null },
            renderScene = { rendered = true },
            submit = { _, _ -> fail("No XR frame was begun") },
            release = { fail("No XR frame was allocated") },
        )
        assertTrue(rendered)
    }

    @Test
    fun sceneFailureEndsFrameWithoutALayerAndReleasesItsViews() {
        val original = IllegalStateException("shader failed")
        var submitted = false
        var released = false
        val caught = assertFailsWith<IllegalStateException> {
            withOpenXRFrame(
                prepare = { Frame(123, 2) },
                renderScene = { throw original },
                submit = { frame, rendered ->
                    assertEquals(123, frame.displayTime)
                    assertFalse(rendered)
                    submitted = true
                },
                release = { released = true },
            )
        }
        assertSame(original, caught)
        assertTrue(submitted)
        assertTrue(released)
    }

    @Test
    fun submissionFailureReleasesViewsAndPreservesSceneFailure() {
        val original = IllegalStateException("scene")
        val endFailure = IllegalArgumentException("runtime lost")
        var released = false
        val caught = assertFailsWith<IllegalStateException> {
            withOpenXRFrame(
                prepare = { Frame(123, 2) },
                renderScene = { throw original },
                submit = { _, _ -> throw endFailure },
                release = { released = true },
            )
        }
        assertSame(original, caught)
        assertEquals(listOf(endFailure), caught.suppressed.toList())
        assertTrue(released)
    }

    @Test
    fun runtimeSubmissionFailurePropagatesAfterReleasingViews() {
        var released = false
        assertFailsWith<IllegalArgumentException> {
            withOpenXRFrame(
                prepare = { Frame(123, 2) },
                renderScene = {},
                submit = { _, _ -> throw IllegalArgumentException("runtime lost") },
                release = { released = true },
            )
        }
        assertTrue(released)
    }
}
