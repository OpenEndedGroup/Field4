package afield.four.targets

import afield.logger.lprintln
import org.lwjgl.openxr.XR10
import org.lwjgl.openxr.XrInstance
import org.lwjgl.openxr.XrSession
import org.lwjgl.openxr.XrSpace
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

/**
 * Hand-rolled bindings for `XR_MNDX_xdev_space`.
 *
 * This is a Monado *vendor* extension: it never made it into the official OpenXR registry, so
 * LWJGL generates no bindings for it. We resolve the entry points through `xrGetInstanceProcAddr`
 * and lay the structs out by hand. Definitions transcribed from Monado's
 * `src/external/openxr_includes/openxr/XR_MNDX_xdev_space.h` (spec version 3, extension 445).
 *
 * We need it because it is the only route to WiVRn's body trackers — see
 * [OpenXRHmdTarget.initBodyTracking] for why `XR_FB_body_tracking` is a dead end there.
 *
 * The extension enumerates every "xdev" (tracked device) the runtime knows about — HMD,
 * controllers, body trackers — and hands back an `XrSpace` per device, which we then locate with
 * the ordinary [XR10.xrLocateSpace].
 */
internal class MndxXDevSpace private constructor(
    private val pCreateXDevList: Long,
    private val pGetGenerationNumber: Long,
    private val pEnumerateXDevs: Long,
    private val pGetXDevProperties: Long,
    private val pDestroyXDevList: Long,
    private val pCreateXDevSpace: Long,
) {
    /** One tracked device, as reported by `xrGetXDevPropertiesMNDX`. */
    class XDev(val id: Long, val name: String, val serial: String, val canCreateSpace: Boolean) {
        override fun toString() = "XDev(id=$id, name='$name', serial='$serial', canCreateSpace=$canCreateSpace)"
    }

    /** Returns the `XrXDevListMNDX` handle, or null on failure. */
    fun createXDevList(session: XrSession): Long? {
        MemoryStack.stackPush().use { stack ->
            val info = stack.ncalloc(8, 1, CREATE_XDEV_LIST_INFO_SIZE)
            MemoryUtil.memPutInt(info + TYPE, XR_TYPE_CREATE_XDEV_LIST_INFO_MNDX)
            val out = stack.ncalloc(8, 1, 8)
            val res = JNI.callPPPI(session.address(), info, out, pCreateXDevList)
            if (res != XR10.XR_SUCCESS) {
                lprintln("warning,openxr", "xrCreateXDevListMNDX failed: $res")
                return null
            }
            return MemoryUtil.memGetAddress(out)
        }
    }

    fun destroyXDevList(xdevList: Long) {
        val res = JNI.callPI(xdevList, pDestroyXDevList)
        if (res != XR10.XR_SUCCESS) lprintln("warning,openxr", "xrDestroyXDevListMNDX failed: $res")
    }

    /**
     * Bumps whenever the device list changes. Cheap to poll, so it's how we notice trackers
     * appearing after the session started (the user enabling body tracking mid-stream, or WiVRn
     * reconnecting).
     *
     * Null on failure rather than a sentinel value: callers compare this against the generation
     * they last saw, and any in-band sentinel could collide with a real generation number and
     * wedge them into never refreshing again.
     */
    fun generationNumber(xdevList: Long): Long? {
        MemoryStack.stackPush().use { stack ->
            val out = stack.ncalloc(8, 1, 8)
            val res = JNI.callPPI(xdevList, out, pGetGenerationNumber)
            if (res != XR10.XR_SUCCESS) return null
            return MemoryUtil.memGetLong(out)
        }
    }

    fun enumerateXDevs(xdevList: Long): LongArray {
        MemoryStack.stackPush().use { stack ->
            val count = stack.ncalloc(4, 1, 4)
            var res = JNI.callPPPI(xdevList, 0, count, 0L, pEnumerateXDevs)
            if (res != XR10.XR_SUCCESS) {
                lprintln("warning,openxr", "xrEnumerateXDevsMNDX(count) failed: $res")
                return LongArray(0)
            }
            val n = MemoryUtil.memGetInt(count)
            if (n <= 0) return LongArray(0)

            val ids = stack.ncalloc(8, n, 8)
            res = JNI.callPPPI(xdevList, n, count, ids, pEnumerateXDevs)
            if (res != XR10.XR_SUCCESS) {
                lprintln("warning,openxr", "xrEnumerateXDevsMNDX failed: $res")
                return LongArray(0)
            }
            return LongArray(MemoryUtil.memGetInt(count)) { MemoryUtil.memGetLong(ids + it * 8L) }
        }
    }

    fun getProperties(xdevList: Long, id: Long): XDev? {
        MemoryStack.stackPush().use { stack ->
            val info = stack.ncalloc(8, 1, GET_XDEV_INFO_SIZE)
            MemoryUtil.memPutInt(info + TYPE, XR_TYPE_GET_XDEV_INFO_MNDX)
            MemoryUtil.memPutLong(info + GET_XDEV_INFO_ID, id)

            val props = stack.ncalloc(8, 1, XDEV_PROPERTIES_SIZE)
            MemoryUtil.memPutInt(props + TYPE, XR_TYPE_XDEV_PROPERTIES_MNDX)

            val res = JNI.callPPPI(xdevList, info, props, pGetXDevProperties)
            if (res != XR10.XR_SUCCESS) {
                lprintln("warning,openxr", "xrGetXDevPropertiesMNDX($id) failed: $res")
                return null
            }
            return XDev(
                id = id,
                name = MemoryUtil.memUTF8(props + XDEV_PROPERTIES_NAME, strlen(props + XDEV_PROPERTIES_NAME)),
                serial = MemoryUtil.memUTF8(props + XDEV_PROPERTIES_SERIAL, strlen(props + XDEV_PROPERTIES_SERIAL)),
                canCreateSpace = MemoryUtil.memGetInt(props + XDEV_PROPERTIES_CAN_CREATE_SPACE) != 0,
            )
        }
    }

    /** Creates an identity-offset space tracking the given device. Caller owns the space. */
    fun createXDevSpace(session: XrSession, xdevList: Long, id: Long): XrSpace? {
        MemoryStack.stackPush().use { stack ->
            val info = stack.ncalloc(8, 1, CREATE_XDEV_SPACE_INFO_SIZE)
            MemoryUtil.memPutInt(info + TYPE, XR_TYPE_CREATE_XDEV_SPACE_INFO_MNDX)
            MemoryUtil.memPutAddress(info + CREATE_XDEV_SPACE_INFO_XDEV_LIST, xdevList)
            MemoryUtil.memPutLong(info + CREATE_XDEV_SPACE_INFO_ID, id)
            // XrPosef offset: identity. Quaternion is (x, y, z, w), so only w is non-zero.
            MemoryUtil.memPutFloat(info + CREATE_XDEV_SPACE_INFO_OFFSET + 12, 1f)

            val out = stack.ncalloc(8, 1, 8)
            val res = JNI.callPPPI(session.address(), info, out, pCreateXDevSpace)
            if (res != XR10.XR_SUCCESS) {
                lprintln("warning,openxr", "xrCreateXDevSpaceMNDX($id) failed: $res")
                return null
            }
            return XrSpace(MemoryUtil.memGetAddress(out), session)
        }
    }

    /** The struct fields are fixed-capacity char arrays, not pointers, so find the terminator. */
    private fun strlen(address: Long): Int {
        var i = 0
        while (i < XDEV_NAME_CAPACITY && MemoryUtil.memGetByte(address + i) != 0.toByte()) i++
        return i
    }

    companion object {
        const val EXTENSION_NAME = "XR_MNDX_xdev_space"

        // Struct type enums, from the 1000444xxx block.
        private const val XR_TYPE_CREATE_XDEV_LIST_INFO_MNDX = 1000444002
        private const val XR_TYPE_GET_XDEV_INFO_MNDX = 1000444003
        private const val XR_TYPE_XDEV_PROPERTIES_MNDX = 1000444004
        private const val XR_TYPE_CREATE_XDEV_SPACE_INFO_MNDX = 1000444005

        // Struct layouts for LP64 (4-byte enum, 8-byte pointer, natural alignment). Every struct
        // starts with `XrStructureType type; void *next;`, so `type` is always at 0 and the first
        // real field lands at 16.
        private const val TYPE = 0L

        private const val CREATE_XDEV_LIST_INFO_SIZE = 16

        private const val GET_XDEV_INFO_SIZE = 24
        private const val GET_XDEV_INFO_ID = 16L           // XrXDevIdMNDX (atom, uint64)

        private const val XDEV_PROPERTIES_SIZE = 536        // 532 rounded up to pointer alignment
        private const val XDEV_PROPERTIES_NAME = 16L        // char name[256]
        private const val XDEV_PROPERTIES_SERIAL = 272L     // char serial[256]
        private const val XDEV_PROPERTIES_CAN_CREATE_SPACE = 528L
        private const val XDEV_NAME_CAPACITY = 256

        private const val CREATE_XDEV_SPACE_INFO_SIZE = 64  // 60 rounded up
        private const val CREATE_XDEV_SPACE_INFO_XDEV_LIST = 16L
        private const val CREATE_XDEV_SPACE_INFO_ID = 24L
        private const val CREATE_XDEV_SPACE_INFO_OFFSET = 32L // XrPosef: quat xyzw, then vec3 xyz

        /**
         * Resolves the entry points. Returns null unless every one is present, so callers get an
         * all-or-nothing handle rather than having to null-check each call.
         *
         * The instance must have been created with [EXTENSION_NAME] enabled.
         */
        fun load(instance: XrInstance): MndxXDevSpace? {
            MemoryStack.stackPush().use { stack ->
                fun proc(name: String): Long {
                    val out = stack.callocPointer(1)
                    val res = XR10.xrGetInstanceProcAddr(instance, name, out)
                    if (res != XR10.XR_SUCCESS || out[0] == 0L) {
                        lprintln("warning,openxr", "xrGetInstanceProcAddr($name) failed: $res")
                        return 0L
                    }
                    return out[0]
                }

                val create = proc("xrCreateXDevListMNDX")
                val generation = proc("xrGetXDevListGenerationNumberMNDX")
                val enumerate = proc("xrEnumerateXDevsMNDX")
                val properties = proc("xrGetXDevPropertiesMNDX")
                val destroy = proc("xrDestroyXDevListMNDX")
                val space = proc("xrCreateXDevSpaceMNDX")
                if (create == 0L || generation == 0L || enumerate == 0L ||
                    properties == 0L || destroy == 0L || space == 0L
                ) return null

                return MndxXDevSpace(create, generation, enumerate, properties, destroy, space)
            }
        }
    }
}
