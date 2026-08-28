package afield.four.browser

import afield.four.device.CommandBuffer
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.MetalIOSurface
import afield.four.texture.PreparesSampledImages
import org.lwjgl.system.APIUtil.apiCreateLibrary
import org.lwjgl.system.JNI
import org.lwjgl.system.SharedLibrary
import org.lwjgl.system.windows.Kernel32
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed properties of the resident texture exposed to Field. */
data class ExternalBrowserSurfaceSpec(
    val width: Int,
    val height: Int,
    val pixelFormat: BrowserSurfacePixelFormat = BrowserSurfacePixelFormat.BGRA8_UNORM,
    val slotCount: Int = 3,
    val deviceScaleFactor: Double = 1.0
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(slotCount > 0) { "slotCount must be positive" }
        require(deviceScaleFactor > 0.0 && deviceScaleFactor.isFinite()) {
            "deviceScaleFactor must be finite and positive"
        }
    }
}

/** Formats Electron can currently publish through its accelerated offscreen paint API. */
enum class BrowserSurfacePixelFormat {
    BGRA8_UNORM,
    RGBA8_UNORM,
    RGBA16_FLOAT
}

/** Electron's four color-space fields, preserved without baking its evolving string vocabulary in. */
data class BrowserSurfaceColorSpace(
    val primaries: String,
    val transfer: String,
    val matrix: String,
    val range: String
)

data class BrowserSurfaceRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(width >= 0) { "width must not be negative" }
        require(height >= 0) { "height must not be negative" }
    }
}

/**
 * A receiver-side platform handle. IPC is responsible for turning Electron's process-local handle
 * into one of these values. Each value owns the receiver-local handle(s) until a platform importer
 * either consumes them or closes them. Closing a rejected or superseded frame is therefore enough
 * to keep transport resources bounded.
 */
sealed interface ExternalBrowserImageHandle : AutoCloseable {
    /** Releases any live receiver-side transport resource. Implementations must be idempotent. */
    override fun close() = Unit

    /**
     * A Mach port name in Field's process after the producer transferred an IOSurface send right.
     * This is the normal Electron path: Electron's IOSurfaceRef itself is process-local.
     */
    class MacIOSurfaceMachPort(val value: Int) : ExternalBrowserImageHandle {
        private val closed = AtomicBoolean(false)

        init { require(value != 0) { "IOSurface Mach port must be non-zero" } }

        override fun close() {
            if (closed.compareAndSet(false, true)) MetalIOSurface.deallocateMachPort(value)
        }

        override fun equals(other: Any?): Boolean = other is MacIOSurfaceMachPort && value == other.value
        override fun hashCode(): Int = value
        override fun toString(): String = "MacIOSurfaceMachPort(value=$value)"
    }

    /** Same-process or explicitly global IOSurfaces only; not the normal Electron transport. */
    data class MacIOSurfaceId(val value: Long) : ExternalBrowserImageHandle {
        init { require(value in 1..0xFFFF_FFFFL) { "IOSurfaceID must be an unsigned non-zero 32-bit value" } }
    }

    class WindowsD3D11NtHandle(val value: Long) : ExternalBrowserImageHandle {
        private val closed = AtomicBoolean(false)

        init { require(value != 0L) { "NT handle must be non-zero" } }

        override fun close() {
            if (closed.compareAndSet(false, true)) ExternalBrowserNativeHandles.closeWin32(value)
        }

        override fun equals(other: Any?): Boolean = other is WindowsD3D11NtHandle && value == other.value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = "WindowsD3D11NtHandle(value=$value)"
    }

    class LinuxDmaBuf(
        val planes: List<Plane>,
        /** DRM modifier bits, represented as a signed Long without reinterpretation. */
        val modifier: Long
    ) : ExternalBrowserImageHandle {
        private val closed = AtomicBoolean(false)

        init { require(planes.isNotEmpty()) { "dma-buf must contain at least one plane" } }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                var failure: Throwable? = null
                planes.map { it.fd }.distinct().forEach { fd ->
                    runCatching { ExternalBrowserNativeHandles.closeFd(fd) }
                        .onFailure { if (failure == null) failure = it }
                }
                failure?.let { throw it }
            }
        }

        /** Successful Vulkan fd import consumes ownership; prevent a later frame close from closing it again. */
        internal fun markConsumedByVulkan() {
            check(closed.compareAndSet(false, true)) { "dma-buf fds were already closed or consumed" }
        }

        override fun equals(other: Any?): Boolean =
            other is LinuxDmaBuf && planes == other.planes && modifier == other.modifier

        override fun hashCode(): Int = 31 * planes.hashCode() + modifier.hashCode()
        override fun toString(): String = "LinuxDmaBuf(planes=$planes, modifier=$modifier)"

        data class Plane(val fd: Int, val stride: Int, val offset: Int, val size: Long) {
            init {
                require(fd >= 0) { "dma-buf fd must be non-negative" }
                require(stride > 0) { "dma-buf stride must be positive" }
                require(offset >= 0) { "dma-buf offset must be non-negative" }
                require(size > 0) { "dma-buf size must be positive" }
            }
        }
    }
}

/**
 * One accelerated browser paint. Stream ids increase whenever the producer is replaced. [handle]
 * is sticky within one [streamId]/[slot]: it may be omitted after the first announcement of that
 * slot, but a new stream must announce its handles again. [releaseToken] is echoed when the GPU has
 * finished copying the frame, allowing Electron's main process to call
 * `OffscreenSharedTexture.release()` safely.
 */
data class ExternalBrowserFrame(
    val streamId: Long,
    val frameId: Long,
    val releaseToken: Long = frameId,
    val slot: Int,
    val width: Int,
    val height: Int,
    val pixelFormat: BrowserSurfacePixelFormat,
    val handle: ExternalBrowserImageHandle? = null,
    val colorSpace: BrowserSurfaceColorSpace? = null,
    val visibleRect: BrowserSurfaceRect? = null,
    val contentRect: BrowserSurfaceRect? = null,
    val timestampMicros: Long? = null
)

enum class BrowserFrameReleaseReason {
    /** The copy into Field's resident sampled texture completed on the GPU. */
    PRESENTED,
    /** A newer paint arrived before this one was copied. */
    SUPERSEDED,
    /** The producer sent an old or duplicate frame. */
    STALE,
    /** The frame did not match the negotiated surface. */
    INVALID,
    /** The platform handle could not be imported. */
    IMPORT_FAILED,
    /** The receiving surface was closed or its producer stream was replaced. */
    CLOSED
}

data class BrowserFrameRelease(
    val streamId: Long,
    val frameId: Long,
    val releaseToken: Long,
    val slot: Int,
    val reason: BrowserFrameReleaseReason
)

fun interface BrowserFrameReleaseListener {
    fun released(release: BrowserFrameRelease)
}

enum class BrowserFrameOfferResult {
    ACCEPTED,
    ACCEPTED_REPLACING_PENDING,
    REJECTED_STALE,
    REJECTED_INVALID,
    REJECTED_CLOSED
}

/**
 * A browser surface is deliberately a stable Field texture. Platform textures are copied on-GPU
 * before their producer lease is released, so Chromium may recycle them without changing or
 * corrupting what Field samples between browser paints.
 */
interface ExternalBrowserSurface :
    HasImageView,
    HasImageViewArray,
    PreparesSampledImages,
    AutoCloseable {
    val spec: ExternalBrowserSurfaceSpec
    val presentedFrame: ExternalBrowserFrame?

    /** Thread-safe; intended to be called by the IPC reader. */
    fun offerFrame(frame: ExternalBrowserFrame): BrowserFrameOfferResult

    /** Records any pending platform import/copy before Field binds [imageView]. */
    override fun prepareSampledImagesFor(command: CommandBuffer)
}

/** The platform-neutral construction point; Windows and Linux backends can fill in behind it. */
object ExternalBrowserSurfaces {
    fun create(
        renderer: afield.four.Renderer,
        spec: ExternalBrowserSurfaceSpec,
        releaseListener: BrowserFrameReleaseListener = BrowserFrameReleaseListener { }
    ): ExternalBrowserSurface = when (afield.four.util.OS) {
        afield.four.util.OSType.MACOS -> MacExternalBrowserSurface(renderer, spec, releaseListener)
        afield.four.util.OSType.WINDOWS -> WindowsExternalBrowserSurface(renderer, spec, releaseListener)
        afield.four.util.OSType.LINUX -> LinuxExternalBrowserSurface(renderer, spec, releaseListener)
        afield.four.util.OSType.OTHER -> error("External browser surfaces are unsupported on this operating system")
    }
}

/** Tiny OS-close bindings kept lazy so this source remains loadable on every desktop OS. */
private object ExternalBrowserNativeHandles {
    private val closeHandleAddress: Long by lazy {
        Kernel32.getLibrary().getFunctionAddress("CloseHandle")
            .also { check(it != 0L) { "CloseHandle is unavailable" } }
    }
    private val libc: SharedLibrary by lazy { apiCreateLibrary("libc.so.6") }
    private val closeFdAddress: Long by lazy {
        libc.getFunctionAddress("close").also { check(it != 0L) { "libc close is unavailable" } }
    }

    fun closeWin32(handle: Long) {
        check(JNI.callPI(handle, closeHandleAddress) != 0) { "CloseHandle failed for 0x${handle.toString(16)}" }
    }

    fun closeFd(fd: Int) {
        check(JNI.callI(fd, closeFdAddress) == 0) { "close($fd) failed" }
    }
}

/** Conflates producer paints without ever losing a slot's sticky handle announcement. */
internal class ExternalBrowserFrameMailbox(
    private val spec: ExternalBrowserSurfaceSpec,
    private val releaseListener: BrowserFrameReleaseListener
) {
    private data class PendingRelease(
        val frame: ExternalBrowserFrame,
        val reason: BrowserFrameReleaseReason,
        val closeHandle: Boolean = true
    )

    private val lock = Any()
    private val pending = arrayOfNulls<ExternalBrowserFrame>(spec.slotCount)
    private var streamId: Long? = null
    private var greatestFrameId = -1L
    private var closed = false

    fun offer(frame: ExternalBrowserFrame): BrowserFrameOfferResult {
        val releases = mutableListOf<PendingRelease>()
        val result = synchronized(lock) {
            if (closed) {
                releases += PendingRelease(frame, BrowserFrameReleaseReason.CLOSED)
                BrowserFrameOfferResult.REJECTED_CLOSED
            } else if (!isValid(frame)) {
                releases += PendingRelease(frame, BrowserFrameReleaseReason.INVALID)
                BrowserFrameOfferResult.REJECTED_INVALID
            } else {
                val currentStream = streamId
                when {
                    currentStream != null && frame.streamId < currentStream -> {
                        releases += PendingRelease(frame, BrowserFrameReleaseReason.STALE)
                        BrowserFrameOfferResult.REJECTED_STALE
                    }

                    currentStream == null || frame.streamId > currentStream -> {
                        pending.filterNotNull().forEach {
                            releases += PendingRelease(it, BrowserFrameReleaseReason.CLOSED)
                        }
                        pending.fill(null)
                        streamId = frame.streamId
                        greatestFrameId = -1L
                        accept(frame, releases)
                    }

                    frame.frameId <= greatestFrameId -> {
                        releases += PendingRelease(frame, BrowserFrameReleaseReason.STALE)
                        BrowserFrameOfferResult.REJECTED_STALE
                    }

                    else -> accept(frame, releases)
                }
            }
        }
        releases.forEach(::release)
        return result
    }

    /** Returns only the newest paint; every older pending producer lease is released immediately. */
    fun takeLatest(): ExternalBrowserFrame? {
        val releases = mutableListOf<ExternalBrowserFrame>()
        val newest = synchronized(lock) {
            val selected = pending.filterNotNull().maxByOrNull { it.frameId }
            pending.forEachIndexed { index, frame ->
                if (frame != null && frame !== selected) releases += frame
                pending[index] = null
            }
            selected
        }
        releases.forEach { release(PendingRelease(it, BrowserFrameReleaseReason.SUPERSEDED)) }
        return newest
    }

    fun close() {
        val releases = synchronized(lock) {
            if (closed) return
            closed = true
            pending.filterNotNull().also { pending.fill(null) }
        }
        releases.forEach { release(PendingRelease(it, BrowserFrameReleaseReason.CLOSED)) }
    }

    private fun accept(
        frame: ExternalBrowserFrame,
        releases: MutableList<PendingRelease>
    ): BrowserFrameOfferResult {
        val replaced = pending[frame.slot]
        // Preserve an unconsumed slot announcement only while replacing a pending frame in the
        // same slot. Once a frame reaches the importer, later handle-less frames use its cache.
        val accepted = if (frame.handle == null && replaced?.handle != null) {
            frame.copy(handle = replaced.handle)
        } else frame
        if (replaced != null) {
            releases += PendingRelease(
                frame = replaced,
                reason = BrowserFrameReleaseReason.SUPERSEDED,
                closeHandle = replaced.handle !== accepted.handle
            )
        }
        pending[frame.slot] = accepted
        greatestFrameId = frame.frameId
        return if (replaced == null) BrowserFrameOfferResult.ACCEPTED
        else BrowserFrameOfferResult.ACCEPTED_REPLACING_PENDING
    }

    private fun isValid(frame: ExternalBrowserFrame): Boolean =
        frame.streamId >= 0 &&
            frame.frameId >= 0 &&
            frame.slot in pending.indices &&
            frame.width == spec.width &&
            frame.height == spec.height &&
            frame.pixelFormat == spec.pixelFormat &&
            frame.visibleRect.isInside(spec) &&
            frame.contentRect.isInside(spec) &&
            (frame.timestampMicros == null || frame.timestampMicros >= 0)

    private fun BrowserSurfaceRect?.isInside(spec: ExternalBrowserSurfaceSpec): Boolean {
        if (this == null) return true
        return x >= 0 && y >= 0 &&
            x.toLong() + width <= spec.width.toLong() &&
            y.toLong() + height <= spec.height.toLong()
    }

    private fun release(pending: PendingRelease) {
        val frame = pending.frame
        if (pending.closeHandle) runCatching { frame.handle?.close() }
        releaseListener.released(
            BrowserFrameRelease(
                streamId = frame.streamId,
                frameId = frame.frameId,
                releaseToken = frame.releaseToken,
                slot = frame.slot,
                reason = pending.reason
            )
        )
    }
}
