package afield.graphics.vulkan.targets

import org.lwjgl.openxr.XR10.*
import org.lwjgl.openxr.XrInstance
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.Pointer
import java.util.UUID

/**
 * Byte messages over NVIDIA's optional `XR_NV_opaque_data_channel` extension.
 *
 * Bindings follow CloudXR 6.2.3's XR_NV_opaque_data_channel.h (spec version 1).
 * LWJGL does not generate this vendor extension. These layouts require a 64-bit ABI:
 * create info = type@0, next@8, systemId@16, inline UUID@24 (40 bytes);
 * state = type@0, next@8, state@16 (24 bytes).
 *
 * The owning [OpenXRHmdTarget] retires channels before destroying its instance.
 * Calls are serialized with close; the application should poll at a modest rate.
 */
class OpenXROpaqueDataChannel private constructor(
    private var handle: Long,
    private val destroy: Long,
    private val getState: Long,
    private val send: Long,
    private val receive: Long,
    private val shutdown: Long,
) : AutoCloseable {
    /** Connecting, ready, shutting down, or disconnected, as reported by the runtime. */
    enum class State { CONNECTING, CONNECTED, SHUTTING, DISCONNECTED }

    @Synchronized
    fun state(): State {
        if (handle == 0L) return State.DISCONNECTED
        MemoryStack.stackPush().use { stack ->
            val out = stack.ncalloc(8, 1, 24)
            memPutInt(out, TYPE_STATE)
            checkResult(JNI.callPPI(handle, out, getState), "state")
            return State.entries[memGetInt(out + 16)]
        }
    }

    /** Send one message. The current CloudXR protocol limits messages to 1000 bytes. */
    @Synchronized
    fun send(bytes: ByteArray) {
        require(bytes.size in 1..MAX_MESSAGE_BYTES)
        check(handle != 0L) { "Channel is closed" }
        MemoryStack.stackPush().use { stack ->
            val data = stack.bytes(*bytes)
            checkResult(JNI.callPPI(handle, bytes.size, memAddress(data), send), "send")
        }
    }

    /** Receive one available message, or null if no message is queued. */
    @Synchronized
    fun receive(): ByteArray? {
        check(handle != 0L) { "Channel is closed" }
        MemoryStack.stackPush().use { stack ->
            val count = stack.callocInt(1)
            val data = stack.malloc(MAX_MESSAGE_BYTES)
            val result = JNI.callPPPI(handle, MAX_MESSAGE_BYTES, memAddress(count), memAddress(data), receive)
            if (result == XR_TIMEOUT_EXPIRED) return null
            checkResult(result, "receive")
            val size = count[0]
            check(size in 0..MAX_MESSAGE_BYTES) { "Invalid opaque message size $size" }
            return if (size == 0) null else ByteArray(size).also { data.get(it) }
        }
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        val owned = handle
        handle = 0
        // Shutdown before destruction; destruction must still run if shutdown failed.
        try { checkResult(JNI.callPI(owned, shutdown), "shutdown") }
        finally { checkResult(JNI.callPI(owned, destroy), "destroy") }
    }

    companion object {
        const val EXTENSION_NAME = "XR_NV_opaque_data_channel"
        const val MAX_MESSAGE_BYTES = 1000
        private const val TYPE_CREATE = 1000526001
        private const val TYPE_STATE = 1000526002

        internal fun create(instance: XrInstance, systemId: Long, uuid: UUID): OpenXROpaqueDataChannel {
            check(Pointer.POINTER_SIZE == 8) { "Opaque channels require a 64-bit ABI" }
            MemoryStack.stackPush().use { stack ->
                fun proc(name: String): Long {
                    val out = stack.callocPointer(1)
                    checkResult(xrGetInstanceProcAddr(instance, name, out), name)
                    check(out[0] != 0L) { "$name has no entry point" }
                    return out[0]
                }
                val create = proc("xrCreateOpaqueDataChannelNV")
                val destroy = proc("xrDestroyOpaqueDataChannelNV")
                val state = proc("xrGetOpaqueDataChannelStateNV")
                val send = proc("xrSendOpaqueDataChannelNV")
                val receive = proc("xrReceiveOpaqueDataChannelNV")
                val shutdown = proc("xrShutdownOpaqueDataChannelNV")
                val info = stack.ncalloc(8, 1, 40)
                memPutInt(info, TYPE_CREATE)
                memPutLong(info + 16, systemId)
                // XrUuidEXT is 16 canonical UUID bytes, independent of host byte order.
                for (i in 0 until 16) {
                    val half = if (i < 8) uuid.mostSignificantBits else uuid.leastSignificantBits
                    memPutByte(info + 24 + i, (half ushr (56 - (i % 8) * 8)).toByte())
                }
                val out = stack.callocPointer(1)
                checkResult(JNI.callPPPI(instance.address(), info, out.address(), create), "create")
                return OpenXROpaqueDataChannel(out[0], destroy, state, send, receive, shutdown)
            }
        }

        private fun checkResult(result: Int, operation: String) {
            check(result == XR_SUCCESS) { "Opaque data channel $operation failed: $result" }
        }
    }
}
