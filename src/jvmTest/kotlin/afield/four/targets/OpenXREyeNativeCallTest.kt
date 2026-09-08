package afield.four.targets

import org.lwjgl.openxr.*
import org.lwjgl.openxr.XR10.*
import org.lwjgl.system.Configuration
import org.lwjgl.system.FunctionProvider
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.*
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.test.*

/**
 * A synthetic runtime behind real native function pointers. Exercises the production LWJGL/JNI
 * calls, input headers, output memory, failure paths and ownership, without a loader or headset.
 * This does not prove a headset implements either extension.
 */
class OpenXREyeNativeCallTest {
    @Test
    fun extActionLifecycleActivityAndLocateFailures() = Runtime().use { runtime ->
        val gaze = assertNotNull(ExtEyeGazeInteraction.create(runtime.session, SYSTEM, runtime.actionSet))
        gaze.update(runtime.session, runtime.referenceSpace, TIME)
        assertTrue(gaze.state.isTracked)
        assertEquals(TIME - 19, gaze.state.sampleTime)
        assertEquals(1f, gaze.state.position.x)
        assertEquals(1, runtime.actions)
        assertEquals(1, runtime.spaces)

        runtime.active = false
        gaze.update(runtime.session, runtime.referenceSpace, TIME)
        assertFalse(gaze.state.isActive)
        assertFalse(gaze.state.poseValid)
        assertEquals(0L, gaze.state.sampleTime)
        runtime.active = true
        runtime.fail = "xrLocateSpace"
        gaze.update(runtime.session, runtime.referenceSpace, TIME)
        assertTrue(gaze.state.isActive)
        assertFalse(gaze.state.poseValid)
        runtime.fail = "xrGetActionStatePose"
        gaze.update(runtime.session, runtime.referenceSpace, TIME)
        assertFalse(gaze.state.isActive)

        runtime.fail = null
        gaze.update(runtime.session, runtime.referenceSpace, TIME)
        val retained = gaze.state
        gaze.close()
        assertFalse(retained.poseValid)
        assertEquals(0, runtime.spaces)
        assertEquals(1, runtime.actions) // Owned by the containing action set.
        assertEquals(XR_SUCCESS, xrDestroyActionSet(runtime.actionSet))
        assertEquals(0, runtime.actions)
    }

    @Test
    fun extPartialInitializationReleasesResources() {
        for (failure in listOf("xrGetSystemProperties", "xrStringToPath", "xrCreateAction",
            "xrSuggestInteractionProfileBindings", "xrCreateActionSpace")) {
            Runtime().use { runtime ->
                runtime.fail = failure
                assertNull(ExtEyeGazeInteraction.create(runtime.session, SYSTEM, runtime.actionSet), failure)
                assertEquals(0, runtime.actions, failure)
                assertEquals(0, runtime.spaces, failure)
            }
        }
    }

    @Test
    fun unsupportedExtensionsAndSystemsDoNotCreateResources() {
        Runtime(extEnabled = false).use { runtime ->
            assertNull(ExtEyeGazeInteraction.create(runtime.session, SYSTEM, runtime.actionSet))
            assertTrue(runtime.calls.isEmpty())
        }
        Runtime().use { runtime ->
            runtime.supported = false
            assertNull(ExtEyeGazeInteraction.create(runtime.session, SYSTEM, runtime.actionSet))
            assertNull(HtcEyeTracker.create(runtime.session, SYSTEM))
            assertEquals(listOf("xrGetSystemProperties", "xrGetSystemProperties"), runtime.calls)
        }
    }

    @Test
    fun htcNativeHeadersTimestampsIndependentFailuresAndCleanup() = Runtime().use { runtime ->
        val tracker = assertNotNull(HtcEyeTracker.create(runtime.session, SYSTEM))
        tracker.update(runtime.referenceSpace, TIME)
        assertTrue(tracker.state.left.gazeValid)
        assertEquals(0.03f, tracker.state.right.position.x)
        assertEquals(3.7f, tracker.state.right.pupilDiameter)
        assertEquals(0.9f, tracker.state.right.eyeSqueeze)
        assertEquals(TIME - 1, tracker.state.gazeTime)
        assertEquals(TIME - 2, tracker.state.pupilTime)
        assertEquals(TIME - 3, tracker.state.geometricTime)

        runtime.fail = "xrGetEyePupilDataHTC"
        tracker.update(runtime.referenceSpace, TIME)
        assertTrue(tracker.state.left.gazeValid)
        assertFalse(tracker.state.left.pupilDiameterValid)
        assertFalse(tracker.state.right.pupilPositionValid)
        assertEquals(0L, tracker.state.pupilTime)
        assertTrue(tracker.state.right.geometricValid)
        runtime.fail = "xrGetEyeGazeDataHTC"
        tracker.update(runtime.referenceSpace, TIME)
        assertFalse(tracker.state.left.gazeValid)
        assertTrue(tracker.state.left.pupilDiameterValid)
        runtime.fail = "xrGetEyeGeometricDataHTC"
        tracker.update(runtime.referenceSpace, TIME)
        assertFalse(tracker.state.left.geometricValid)
        assertTrue(tracker.state.left.gazeValid)

        runtime.fail = null
        val retained = tracker.state.right
        tracker.close()
        assertFalse(retained.gazeValid)
        assertFalse(retained.pupilDiameterValid)
        assertEquals(0, runtime.trackers)
    }

    @Test
    fun htcMissingFunctionsAndCreateFailureDoNotAllocateTracker() {
        for (missing in listOf("xrCreateEyeTrackerHTC", "xrDestroyEyeTrackerHTC", "xrGetEyeGazeDataHTC",
            "xrGetEyePupilDataHTC", "xrGetEyeGeometricDataHTC")) {
            Runtime().use { runtime ->
                runtime.missing = missing
                assertNull(HtcEyeTracker.create(runtime.session, SYSTEM), missing)
                assertEquals(0, runtime.trackers)
                assertFalse("xrCreateEyeTrackerHTC" in runtime.calls)
            }
        }
        Runtime().use { runtime ->
            runtime.fail = "xrCreateEyeTrackerHTC"
            assertNull(HtcEyeTracker.create(runtime.session, SYSTEM))
            assertEquals(0, runtime.trackers)
            // One extension's initialization failure does not disable the other.
            val gaze = assertNotNull(ExtEyeGazeInteraction.create(runtime.session, SYSTEM, runtime.actionSet))
            gaze.close()
            xrDestroyActionSet(runtime.actionSet)
        }
    }

    /** Stable JDK foreign-function upcalls; exceptions must never escape across the native ABI. */
    class NativeCall(private val body: (LongArray) -> Int, private val errors: MutableList<Throwable>) {
        private fun call(vararg args: Long): Int = try { body(args) } catch (t: Throwable) {
            errors.add(t)
            XR_ERROR_VALIDATION_FAILURE
        }
        fun p(a: MemorySegment): Int = call(a.address())
        fun pp(a: MemorySegment, b: MemorySegment): Int = call(a.address(), b.address())
        fun ppp(a: MemorySegment, b: MemorySegment, c: MemorySegment): Int = call(a.address(), b.address(), c.address())
        fun pjp(a: MemorySegment, b: Long, c: MemorySegment): Int = call(a.address(), b, c.address())
        fun ppjp(a: MemorySegment, b: MemorySegment, c: Long, d: MemorySegment): Int = call(a.address(), b.address(), c, d.address())
    }

    private class Runtime(extEnabled: Boolean = true) : AutoCloseable {
        private val arena = Arena.ofConfined()
        private val functions = mutableMapOf<String, Long>()
        private val errors = mutableListOf<Throwable>()
        private val oldExplicitInit = Configuration.OPENXR_EXPLICIT_INIT.get()
        val calls = mutableListOf<String>()
        var fail: String? = null
        var missing: String? = null
        var supported = true
        var active = true
        var actions = 0
        var spaces = 0
        var trackers = 0
        val session: XrSession
        val actionSet: XrActionSet
        val referenceSpace: XrSpace

        init {
            Configuration.OPENXR_EXPLICIT_INIT.set(true)
            install("xrGetInstanceProcAddr", "ppp", record = false) { a ->
                val name = memUTF8(a[1])
                val address = if (name == missing) 0 else functions[name] ?: 0
                memPutAddress(a[2], address)
                // Global commands are resolved at initialization but not invoked by these tests.
                if (address != 0L || name in setOf("xrCreateInstance", "xrEnumerateInstanceExtensionProperties",
                        "xrEnumerateApiLayerProperties")) XR_SUCCESS else XR_ERROR_FUNCTION_UNSUPPORTED
            }
            install("xrGetSystemProperties", "pjp") { a ->
                assertEquals(SYSTEM, a[1])
                val properties = XrSystemProperties.create(a[2])
                assertEquals(XR_TYPE_SYSTEM_PROPERTIES, properties.type())
                val ext = properties.next()
                assertTrue(memGetInt(ext) in setOf(1000030000, 1000326005))
                assertEquals(0L, memGetAddress(ext + 8))
                memPutInt(ext + 16, if (supported) 1 else 0)
                XR_SUCCESS
            }
            install("xrStringToPath", "ppp") { a ->
                val path = memUTF8(a[1])
                assertTrue(path in setOf("/interaction_profiles/ext/eye_gaze_interaction", "/user/eyes_ext/input/gaze_ext/pose"))
                memPutLong(a[2], if (path.startsWith("/interaction")) 71 else 72)
                XR_SUCCESS
            }
            install("xrCreateAction", "ppp") { a ->
                val info = XrActionCreateInfo.create(a[1])
                assertEquals(XR_ACTION_TYPE_POSE_INPUT, info.actionType())
                assertEquals(0, info.countSubactionPaths())
                assertEquals("eye_gaze_pose", info.actionNameString())
                actions++
                memPutAddress(a[2], ACTION)
                XR_SUCCESS
            }
            install("xrSuggestInteractionProfileBindings", "pp") { a ->
                val info = XrInteractionProfileSuggestedBinding.create(a[1])
                assertEquals(71L, info.interactionProfile())
                assertEquals(1, info.countSuggestedBindings())
                val binding = assertNotNull(info.suggestedBindings())[0]
                assertEquals(ACTION, binding.action())
                assertEquals(72L, binding.binding())
                XR_SUCCESS
            }
            install("xrCreateActionSpace", "ppp") { a ->
                val info = XrActionSpaceCreateInfo.create(a[1])
                assertEquals(ACTION, info.action())
                assertEquals(XR_NULL_PATH, info.subactionPath())
                assertEquals(1f, info.poseInActionSpace().orientation().w())
                assertEquals(0f, info.poseInActionSpace().`position$`().x())
                spaces++
                memPutAddress(a[2], SPACE)
                XR_SUCCESS
            }
            install("xrDestroyAction", "p") { a -> assertEquals(ACTION, a[0]); actions--; XR_SUCCESS }
            install("xrDestroySpace", "p") { a -> assertEquals(SPACE, a[0]); spaces--; XR_SUCCESS }
            install("xrDestroyActionSet", "p") { _ -> assertEquals(0, spaces); actions = 0; XR_SUCCESS }
            install("xrGetActionStatePose", "ppp") { a ->
                val info = XrActionStateGetInfo.create(a[1])
                assertEquals(ACTION, info.action())
                assertEquals(XR_NULL_PATH, info.subactionPath())
                XrActionStatePose.create(a[2]).isActive(active)
                XR_SUCCESS
            }
            install("xrLocateSpace", "ppjp") { a ->
                assertEquals(SPACE, a[0])
                assertEquals(BASE_SPACE, a[1])
                assertEquals(TIME, a[2])
                val location = XrSpaceLocation.create(a[3])
                val sample = XrEyeGazeSampleTimeEXT.create(location.next())
                assertEquals(1000030001, sample.type())
                sample.time(TIME - 19)
                location.locationFlags(15)
                location.pose().orientation().w(1f)
                location.pose().`position$`().set(1f, 2f, 3f)
                XR_SUCCESS
            }
            install("xrCreateEyeTrackerHTC", "ppp") { a ->
                header(a[1], 1000326001)
                trackers++
                memPutAddress(a[2], TRACKER)
                XR_SUCCESS
            }
            install("xrDestroyEyeTrackerHTC", "p") { a -> assertEquals(TRACKER, a[0]); trackers--; XR_SUCCESS }
            install("xrGetEyeGazeDataHTC", "ppp") { a ->
                assertEquals(TRACKER, a[0])
                header(a[1], 1000326002)
                assertEquals(BASE_SPACE, memGetAddress(a[1] + 16))
                assertEquals(TIME, memGetLong(a[1] + 24))
                header(a[2], 1000326003)
                val buffer = memByteBuffer(a[2], 88).position(16).putLong(TIME - 1)
                for (x in listOf(-0.03f, 0.03f)) {
                    buffer.putInt(1).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f)
                        .putFloat(x).putFloat(2f).putFloat(3f)
                }
                XR_SUCCESS
            }
            install("xrGetEyePupilDataHTC", "ppp") { a ->
                assertEquals(TRACKER, a[0])
                header(a[1], 1000326006)
                header(a[2], 1000326007)
                val buffer = memByteBuffer(a[2], 64).position(16).putLong(TIME - 2)
                for (diameter in listOf(3.6f, 3.7f)) {
                    buffer.putInt(1).putInt(1).putFloat(diameter).putFloat(0.4f).putFloat(0.6f)
                }
                XR_SUCCESS
            }
            install("xrGetEyeGeometricDataHTC", "ppp") { a ->
                assertEquals(TRACKER, a[0])
                header(a[1], 1000326009)
                header(a[2], 1000326010)
                val buffer = memByteBuffer(a[2], 56).position(16).putLong(TIME - 3)
                repeat(2) { buffer.putInt(1).putFloat(0.7f).putFloat(0.8f).putFloat(0.9f) }
                XR_SUCCESS
            }
            try {
                XR.create(FunctionProvider { name -> functions[memUTF8(memAddress(name))] ?: 0 })
                MemoryStack.stackPush().use { stack ->
                    val info = XrInstanceCreateInfo.calloc(stack).type(XR_TYPE_INSTANCE_CREATE_INFO)
                    info.applicationInfo().apiVersion(XR_MAKE_VERSION(1, 0, 0))
                    if (extEnabled) info.enabledExtensionNames(stack.pointers(stack.UTF8("XR_EXT_eye_gaze_interaction")))
                    val instance = XrInstance(0x100000001L, info)
                    session = XrSession(0x200000002L, instance)
                    actionSet = XrActionSet(0x300000003L, instance)
                    referenceSpace = XrSpace(BASE_SPACE, session)
                }
            } catch (t: Throwable) {
                XR.destroy()
                arena.close()
                Configuration.OPENXR_EXPLICIT_INIT.set(oldExplicitInit)
                throw t
            }
        }

        private fun header(address: Long, type: Int) {
            assertEquals(type, memGetInt(address))
            assertEquals(0L, memGetAddress(address + 8))
        }

        private fun install(name: String, signature: String, record: Boolean = true, body: (LongArray) -> Int) {
            val callback = NativeCall({ args ->
                if (record) calls.add(name)
                if (name == fail) XR_ERROR_RUNTIME_FAILURE else body(args)
            }, errors)
            val types = signature.map { if (it == 'j') Long::class.javaPrimitiveType!! else MemorySegment::class.java }
            val layouts = signature.map { if (it == 'j') JAVA_LONG else ADDRESS }
            val method = MethodHandles.lookup().findVirtual(NativeCall::class.java, signature,
                MethodType.methodType(Int::class.javaPrimitiveType, types)).bindTo(callback)
            functions[name] = Linker.nativeLinker().upcallStub(method,
                FunctionDescriptor.of(JAVA_INT, *layouts.toTypedArray()), arena).address()
        }

        override fun close() {
            XR.destroy()
            arena.close()
            Configuration.OPENXR_EXPLICIT_INIT.set(oldExplicitInit)
            if (errors.isNotEmpty()) throw AssertionError("Native callback assertion failed", errors.first())
            assertEquals(0, spaces, "leaked gaze spaces")
            assertEquals(0, trackers, "leaked HTC trackers")
            assertEquals(0, actions, "leaked gaze actions")
        }
    }

    companion object {
        private const val SYSTEM = 0x400000004L
        private const val ACTION = 0x500000005L
        private const val SPACE = 0x600000006L
        private const val BASE_SPACE = 0x700000007L
        private const val TRACKER = 0x800000008L
        private const val TIME = 9_000_000_123L
    }
}
