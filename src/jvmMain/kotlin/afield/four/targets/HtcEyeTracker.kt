package afield.four.targets

import afield.logger.lprintln
import org.lwjgl.openxr.XR10.*
import org.lwjgl.openxr.XrInstance
import org.lwjgl.openxr.XrPosef
import org.lwjgl.openxr.XrSession
import org.lwjgl.openxr.XrSpace
import org.lwjgl.openxr.XrSystemProperties
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.Pointer

/**
 * Native binding for `XR_HTC_eye_tracker`, which our LWJGL version does not generate.
 * Layouts and signatures follow HTC's ViveEyeTrackerHelper.cs and enum values in OpenXRUtils.cs:
 * https://github.com/ViveSoftware/VIVE-OpenXR-Unity/tree/25a5fd212420688952ead9deba735357656278ec/com.htc.upm.vive.openxr/Runtime
 *
 * Only 64-bit ABIs are supported: enum/bool32/float = 4 bytes, pointer/handle/time = 8 bytes.
 * The per-eye arrays are inline, not pointers, and their elements have NO type/next header.
 */
internal class HtcEyeTracker private constructor(
    private val tracker: Long,
    private val pDestroy: Long,
    private val pGaze: Long,
    private val pPupil: Long,
    private val pGeometric: Long,
) {
    val state = HtcEyeTrackingState()

    fun update(referenceSpace: XrSpace, predictedTime: Long) {
        state.invalidate()
        MemoryStack.stackPush().use { stack ->
            val gazeInfo = struct(stack, 32, TYPE_GAZE_INFO)
            memPutAddress(gazeInfo + 16, referenceSpace.address())
            memPutLong(gazeInfo + 24, predictedTime)
            val gaze = struct(stack, 88, TYPE_GAZE)
            readGaze(state, JNI.callPPPI(tracker, gazeInfo, gaze, pGaze), gaze)

            val pupilInfo = struct(stack, 16, TYPE_PUPIL_INFO)
            val pupil = struct(stack, 64, TYPE_PUPIL)
            readPupil(state, JNI.callPPPI(tracker, pupilInfo, pupil, pPupil), pupil)

            val geometricInfo = struct(stack, 16, TYPE_GEOMETRIC_INFO)
            val geometric = struct(stack, 56, TYPE_GEOMETRIC)
            readGeometric(state, JNI.callPPPI(tracker, geometricInfo, geometric, pGeometric), geometric)
        }
    }

    /** Must be called before destroying the session. */
    fun close() {
        state.invalidate()
        val result = JNI.callPI(tracker, pDestroy)
        if (result != XR_SUCCESS) lprintln("warning,openxr", "xrDestroyEyeTrackerHTC failed: $result")
    }

    companion object {
        const val EXTENSION_NAME = "XR_HTC_eye_tracker"
        private const val TYPE_CREATE = 1000326001
        private const val TYPE_GAZE_INFO = 1000326002
        private const val TYPE_GAZE = 1000326003
        private const val TYPE_SYSTEM_PROPERTIES = 1000326005
        private const val TYPE_PUPIL_INFO = 1000326006
        private const val TYPE_PUPIL = 1000326007
        private const val TYPE_GEOMETRIC_INFO = 1000326009
        private const val TYPE_GEOMETRIC = 1000326010

        private fun struct(stack: MemoryStack, size: Int, type: Int): Long =
            stack.ncalloc(8, 1, size).also { memPutInt(it, type) }

        /** Caller must have enabled the extension. Missing functions or device support disable it. */
        fun create(session: XrSession, systemId: Long): HtcEyeTracker? {
            if (Pointer.POINTER_SIZE != 8) {
                lprintln("warning,openxr", "HTC eye tracking requires a 64-bit ABI")
                return null
            }
            val instance = session.instance
            MemoryStack.stackPush().use { stack ->
                val eyeProperties = struct(stack, 24, TYPE_SYSTEM_PROPERTIES)
                val properties = XrSystemProperties.calloc(stack).type(XR_TYPE_SYSTEM_PROPERTIES).next(eyeProperties)
                val propsResult = xrGetSystemProperties(instance, systemId, properties)
                if (propsResult != XR_SUCCESS) {
                    lprintln("warning,openxr", "HTC eye tracking system properties failed: $propsResult")
                    return null
                }
                if (memGetInt(eyeProperties + 16) == 0) return null

                val create = proc(instance, stack, "xrCreateEyeTrackerHTC")
                val destroy = proc(instance, stack, "xrDestroyEyeTrackerHTC")
                val gaze = proc(instance, stack, "xrGetEyeGazeDataHTC")
                val pupil = proc(instance, stack, "xrGetEyePupilDataHTC")
                val geometric = proc(instance, stack, "xrGetEyeGeometricDataHTC")
                if (create == 0L || destroy == 0L || gaze == 0L || pupil == 0L || geometric == 0L) return null

                val info = struct(stack, 16, TYPE_CREATE)
                val out = stack.callocPointer(1)
                val result = JNI.callPPPI(session.address(), info, out.address(), create)
                if (result != XR_SUCCESS) {
                    lprintln("warning,openxr", "xrCreateEyeTrackerHTC failed: $result")
                    return null
                }
                lprintln("info,openxr", "HTC eye tracker enabled")
                return HtcEyeTracker(out[0], destroy, gaze, pupil, geometric)
            }
        }

        private fun proc(instance: XrInstance, stack: MemoryStack, name: String): Long {
            val out = stack.callocPointer(1)
            val result = xrGetInstanceProcAddr(instance, name, out)
            if (result != XR_SUCCESS || out[0] == 0L) {
                lprintln("warning,openxr", "xrGetInstanceProcAddr($name) failed: $result")
                return 0L
            }
            return out[0]
        }

        // All output headers are { type@0, next@8, time@16, inline eyes[2]@24 }.
        // The decoders clear validity on failure and never read undefined numeric fields.
        internal fun readGaze(state: HtcEyeTrackingState, result: Int, data: Long) {
            state.gazeTime = if (result == XR_SUCCESS) memGetLong(data + 16) else 0
            for (i in 0..1) {
                val eye = if (i == 0) state.left else state.right
                val entry = data + 24 + i * 32L // bool32, pose (quat xyzw then position xyz)
                eye.gazeValid = result == XR_SUCCESS && memGetInt(entry) != 0
                if (eye.gazeValid) {
                    val pose = XrPosef.create(entry + 4)
                    eye.position.set(pose.`position$`().x(), pose.`position$`().y(), pose.`position$`().z())
                    eye.orientation.set(
                        pose.orientation().x(), pose.orientation().y(), pose.orientation().z(), pose.orientation().w()
                    )
                }
            }
        }

        internal fun readPupil(state: HtcEyeTrackingState, result: Int, data: Long) {
            state.pupilTime = if (result == XR_SUCCESS) memGetLong(data + 16) else 0
            for (i in 0..1) {
                val eye = if (i == 0) state.left else state.right
                val entry = data + 24 + i * 20L // diameter-valid, position-valid, diameter, vec2
                eye.pupilDiameterValid = result == XR_SUCCESS && memGetInt(entry) != 0
                eye.pupilPositionValid = result == XR_SUCCESS && memGetInt(entry + 4) != 0
                if (eye.pupilDiameterValid) eye.pupilDiameter = memGetFloat(entry + 8)
                if (eye.pupilPositionValid) eye.pupilPosition.set(memGetFloat(entry + 12), memGetFloat(entry + 16))
            }
        }

        internal fun readGeometric(state: HtcEyeTrackingState, result: Int, data: Long) {
            state.geometricTime = if (result == XR_SUCCESS) memGetLong(data + 16) else 0
            for (i in 0..1) {
                val eye = if (i == 0) state.left else state.right
                val entry = data + 24 + i * 16L // valid, openness, wide, squeeze (in that order)
                eye.geometricValid = result == XR_SUCCESS && memGetInt(entry) != 0
                if (eye.geometricValid) {
                    eye.eyeOpenness = memGetFloat(entry + 4)
                    eye.eyeWide = memGetFloat(entry + 8)
                    eye.eyeSqueeze = memGetFloat(entry + 12)
                }
            }
        }
    }
}
