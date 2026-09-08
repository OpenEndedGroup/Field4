package afield.four.targets

import afield.stdlib.Vec3
import org.lwjgl.openxr.XR10.*
import org.lwjgl.openxr.XrSpaceLocation
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memAddress
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CPU/native-memory contract tests; these do not claim to exercise a headset/runtime. */
class OpenXREyeTrackingTest {
    @Test
    fun combinedGazeDistinguishesValidFromTrackedAndUsesNegativeZ() {
        MemoryStack.stackPush().use { stack ->
            val state = EyeGazeState()
            val direction = Vec3(7f, 8f, 9f)
            assertFalse(state.direction(direction))
            assertEquals(7f, direction.x)
            val location = XrSpaceLocation.calloc(stack).type(XR_TYPE_SPACE_LOCATION)
            location.pose().orientation().w(1f)
            location.pose().`position$`().set(1f, 2f, 3f)
            location.locationFlags((XR_SPACE_LOCATION_POSITION_VALID_BIT or XR_SPACE_LOCATION_ORIENTATION_VALID_BIT).toLong())
            state.isActive = true
            state.update(location, 8_000_000_001L)
            assertTrue(state.poseValid)
            assertFalse(state.isTracked)
            assertEquals(8_000_000_001L, state.sampleTime)
            assertTrue(state.direction(direction))
            assertEquals(-1f, direction.z)
            assertEquals(0f, direction.x)
            assertEquals(2f, state.position.y)

            location.locationFlags(location.locationFlags() or
                XR_SPACE_LOCATION_POSITION_TRACKED_BIT.toLong() or XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT.toLong())
            state.update(location, 0) // Runtime may have no sample timestamp.
            assertTrue(state.isTracked)
            assertEquals(0L, state.sampleTime)
            state.orientation.set(0f, 0.70710677f, 0f, 0.70710677f) // +90 degrees around Y
            assertTrue(state.direction(direction))
            assertEquals(-1f, direction.x, 1e-6f)
            assertEquals(0f, direction.z, 1e-6f)
        }
    }

    @Test
    fun combinedGazeIgnoresUndefinedPoseAndClearsAfterTrackingLoss() {
        MemoryStack.stackPush().use { stack ->
            val state = EyeGazeState()
            val location = XrSpaceLocation.calloc(stack).type(XR_TYPE_SPACE_LOCATION)
            location.pose().orientation().w(1f)
            location.pose().`position$`().set(1f, 2f, 3f)
            location.locationFlags((XR_SPACE_LOCATION_POSITION_VALID_BIT or XR_SPACE_LOCATION_ORIENTATION_VALID_BIT).toLong())
            state.isActive = true
            state.update(location, 42)
            location.pose().`position$`().set(Float.NaN, Float.NaN, Float.NaN)
            location.locationFlags(XR_SPACE_LOCATION_ORIENTATION_VALID_BIT.toLong())
            state.update(location, 43)
            assertFalse(state.poseValid)
            assertFalse(state.positionValid)
            assertTrue(state.orientationValid)
            assertEquals(1f, state.position.x)
            assertEquals(0L, state.sampleTime)

            state.invalidate() // Same path used for skipped frames, focus loss and cleanup.
            assertFalse(state.isActive)
            assertFalse(state.orientationValid)
            assertFalse(state.positionTracked)
            assertFalse(state.orientationTracked)
            assertFalse(state.direction(Vec3()))
        }
    }

    // Write native fixtures sequentially in the field order published by HTC. No binding offsets
    // are used here: this catches wrong array strides, swapped fields and 32-bit time truncation.
    private fun ByteBuffer.header(type: Int, time: Long): ByteBuffer =
        putInt(type).putInt(0).putLong(0).putLong(time)

    private fun ByteBuffer.eyeGaze(valid: Int, x: Float): ByteBuffer =
        putInt(valid).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f)
            .putFloat(x).putFloat(2f).putFloat(3f)

    @Test
    fun htcReadsBothInlineEyesAndIndependentObservationStreams() {
        MemoryStack.stackPush().use { stack ->
            val state = HtcEyeTrackingState()
            val gaze = stack.calloc(88).header(1000326003, 5_000_000_001L)
                .eyeGaze(1, -0.03f).eyeGaze(1, 0.03f).flip()
            HtcEyeTracker.readGaze(state, XR_SUCCESS, memAddress(gaze))
            assertTrue(state.left.gazeValid)
            assertTrue(state.right.gazeValid)
            assertEquals(-0.03f, state.left.position.x)
            assertEquals(0.03f, state.right.position.x)
            assertEquals(1f, state.right.orientation.w)
            assertEquals(5_000_000_001L, state.gazeTime)

            val pupil = stack.calloc(64).header(1000326007, 5_000_000_002L)
                .putInt(1).putInt(0).putFloat(3.4f).putFloat(Float.NaN).putFloat(Float.NaN)
                .putInt(0).putInt(1).putFloat(Float.NaN).putFloat(0.4f).putFloat(0.6f).flip()
            HtcEyeTracker.readPupil(state, XR_SUCCESS, memAddress(pupil))
            assertTrue(state.left.pupilDiameterValid)
            assertFalse(state.left.pupilPositionValid)
            assertEquals(3.4f, state.left.pupilDiameter)
            assertEquals(0f, state.left.pupilPosition.x)
            assertFalse(state.right.pupilDiameterValid)
            assertTrue(state.right.pupilPositionValid)
            assertEquals(0.6f, state.right.pupilPosition.y)
            assertEquals(5_000_000_002L, state.pupilTime)

            val geometry = stack.calloc(56).header(1000326010, 5_000_000_003L)
                .putInt(1).putFloat(0.1f).putFloat(0.2f).putFloat(0.3f)
                .putInt(1).putFloat(0.7f).putFloat(0.8f).putFloat(0.9f).flip()
            HtcEyeTracker.readGeometric(state, XR_SUCCESS, memAddress(geometry))
            assertEquals(0.1f, state.left.eyeOpenness)
            assertEquals(0.2f, state.left.eyeWide)
            assertEquals(0.3f, state.left.eyeSqueeze)
            assertEquals(0.7f, state.right.eyeOpenness)
            assertEquals(0.8f, state.right.eyeWide)
            assertEquals(0.9f, state.right.eyeSqueeze)
            assertEquals(5_000_000_003L, state.geometricTime)

            // A failed gaze call must not dereference its output, nor discard pupil/geometry.
            HtcEyeTracker.readGaze(state, XR_ERROR_RUNTIME_FAILURE, 0)
            assertFalse(state.left.gazeValid)
            assertFalse(state.right.gazeValid)
            assertEquals(0L, state.gazeTime)
            assertTrue(state.left.pupilDiameterValid)
            assertTrue(state.right.geometricValid)
            HtcEyeTracker.readPupil(state, XR_ERROR_RUNTIME_FAILURE, 0)
            assertFalse(state.left.pupilDiameterValid)
            assertFalse(state.right.pupilPositionValid)
            assertEquals(0L, state.pupilTime)
            HtcEyeTracker.readGeometric(state, XR_ERROR_RUNTIME_FAILURE, 0)
            assertFalse(state.left.geometricValid)
            assertFalse(state.right.geometricValid)
            assertEquals(0L, state.geometricTime)
        }
    }

    @Test
    fun htcInvalidEyeDoesNotContaminateOtherEyeAndResetInvalidatesRetainedState() {
        MemoryStack.stackPush().use { stack ->
            val state = HtcEyeTrackingState()
            val gaze = stack.calloc(88).header(1000326003, 99)
                .eyeGaze(0, Float.NaN).eyeGaze(1, 0.03f).flip()
            HtcEyeTracker.readGaze(state, XR_SUCCESS, memAddress(gaze))
            assertFalse(state.left.gazeValid)
            assertEquals(0f, state.left.position.x)
            assertTrue(state.right.gazeValid)
            val retained = state.right
            retained.pupilDiameterValid = true
            retained.pupilPositionValid = true
            retained.geometricValid = true
            state.pupilTime = 100
            state.geometricTime = 101
            state.invalidate()
            assertFalse(retained.gazeValid)
            assertFalse(retained.pupilDiameterValid)
            assertFalse(retained.pupilPositionValid)
            assertFalse(retained.geometricValid)
            assertEquals(0L, state.gazeTime)
            assertEquals(0L, state.pupilTime)
            assertEquals(0L, state.geometricTime)
        }
    }
}
