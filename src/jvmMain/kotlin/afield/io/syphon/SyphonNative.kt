@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.io.syphon

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.ref.Cleaner
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The locator and foreign-function bindings for `native/field-syphon`, the optional macOS library
 * under [SyphonServer] and [SyphonClient].
 *
 * The library is searched for in order: the `afield.syphon.library` system property, the
 * `AFIELD_SYPHON_LIBRARY` environment variable, then `build/field-syphon` in the working directory.
 */

/** Locates `libfield_syphon.dylib` and reports whether it is available. */
object SyphonLibrary {
    private val os = System.getProperty("os.name").lowercase()

    val isMac: Boolean get() = os.contains("mac") || os.contains("darwin")

    const val fileName = "libfield_syphon.dylib"

    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.syphon.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_SYPHON_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) +
            // listOf, not a bare `+ Path`: Path is Iterable<Path>, so that appends its name components.
            listOf(Path.of("build", "field-syphon", fileName).toAbsolutePath())

    /** Not cached: building it during a live session is picked up by the next box run. */
    val resolvedPath: Path? get() = if (!isMac) null else searchedPaths.firstOrNull { it.exists() }

    val isAvailable: Boolean get() = resolvedPath != null

    /** The cross-process test peer built next to the library (`field_syphon_probe`). */
    val probePath: Path? get() = resolvedPath?.resolveSibling("field_syphon_probe")?.takeIf { it.exists() }

    const val HOW_TO_BUILD = "Build it with `./gradlew buildSyphonNative` (macOS; needs Xcode, and the network once)."

    fun describe(): String = when {
        !isMac -> "Syphon is macOS-only."
        else -> resolvedPath?.let { "Syphon bridge at $it (ABI ${SyphonNative.version()})" }
            ?: "Syphon bridge not built. $HOW_TO_BUILD"
    }
}

/**
 * The FFM plumbing under [SyphonServer] and [SyphonClient]: one downcall per `fs_*` entry point in
 * `native/field-syphon/field_syphon.h`, whose comments are the contract.
 */
internal object SyphonNative {
    val cleaner: Cleaner = Cleaner.create()

    /** Holds only the handle, never the owner, so the owner can be collected. */
    class Free(private val handle: MemorySegment, private val free: (MemorySegment) -> Unit) : Runnable {
        override fun run() = free(handle)
    }

    private val linker = Linker.nativeLinker()

    private val lookup: SymbolLookup by lazy {
        val path = SyphonLibrary.resolvedPath ?: throw UnsatisfiedLinkError(
            "${SyphonLibrary.describe()}\nLooked at: " + SyphonLibrary.searchedPaths.joinToString(", "))
        SymbolLookup.libraryLookup(path, Arena.global())
    }

    private fun fn(name: String, ret: MemoryLayout?, vararg args: MemoryLayout): Lazy<MethodHandle> = lazy {
        val symbol = lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("${SyphonLibrary.fileName} has no $name; rebuild it with ./gradlew buildSyphonNative")
        }
        linker.downcallHandle(symbol, if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args))
    }

    private val I = JAVA_INT
    private val A = ADDRESS
    private val L = JAVA_LONG

    private val versionH by fn("fs_version", I)
    private val lastErrorH by fn("fs_last_error", A)
    private val freeStringH by fn("fs_free_string", null, A)
    private val pumpH by fn("fs_pump", I, I)
    private val serverNewH by fn("fs_server_new", A, A)
    private val publishRgbaH by fn("fs_server_publish_rgba", I, A, A, I, I, I)
    private val publishBgraH by fn("fs_server_publish_bgra", I, A, A, I, I, I)
    private val publishSurfaceH by fn("fs_server_publish_iosurface", I, A, A, I)
    private val hasClientsH by fn("fs_server_has_clients", I, A)
    private val serverDescH by fn("fs_server_description", A, A)
    private val serverFreeH by fn("fs_server_free", null, A)
    private val surfaceCreateH by fn("fs_iosurface_create", A, I, I)
    private val listH by fn("fs_list_servers", A, I)
    private val clientNewH by fn("fs_client_new", A, A, I)
    private val frameCountH by fn("fs_client_frame_count", L, A)
    private val copyRgbaH by fn("fs_client_copy_rgba", I, A, A, L, A, A)
    private val frameSurfaceH by fn("fs_client_frame_iosurface", A, A, A, A)
    private val waitFrameH by fn("fs_client_wait_frame", L, A, L, I)
    private val clientDescH by fn("fs_client_description", A, A)
    private val clientFreeH by fn("fs_client_free", null, A)

    private fun lastError(): String = (lastErrorH.invokeExact() as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)

    private fun fail(what: String): Nothing = throw IllegalStateException("Syphon: $what failed: ${lastError()}")

    private fun check(status: Int, what: String): Int = if (status < 0) fail(what) else status

    private fun takeString(p: MemorySegment, what: String): String {
        if (p == MemorySegment.NULL) fail(what)
        try { return p.reinterpret(Long.MAX_VALUE).getString(0) } finally { freeStringH.invokeExact(p) }
    }

    fun version(): Int = versionH.invokeExact() as Int

    fun pump(ms: Int): Boolean = (pumpH.invokeExact(ms) as Int) == 1

    fun serverNew(name: String?): MemorySegment = Arena.ofConfined().use { a ->
        val n = if (name == null) MemorySegment.NULL else a.allocateFrom(name)
        val h = serverNewH.invokeExact(n) as MemorySegment
        if (h == MemorySegment.NULL) fail("creating server \"$name\"")
        h
    }

    fun publish(s: MemorySegment, pixels: ByteArray, width: Int, height: Int, bgra: Boolean) {
        require(width > 0 && height > 0 && pixels.size >= width * height * 4) {
            "need ${width * height * 4} bytes for $width x $height, got ${pixels.size}"
        }
        Arena.ofConfined().use { a ->
            val seg = a.allocateFrom(JAVA_BYTE, *pixels)
            val status = if (bgra) publishBgraH.invokeExact(s, seg, width, height, 0) as Int
            else publishRgbaH.invokeExact(s, seg, width, height, 0) as Int
            check(status, "publishing a frame")
        }
    }

    fun publish(s: MemorySegment, pixels: MemorySegment, width: Int, height: Int, stride: Int, bgra: Boolean) {
        val status = if (bgra) publishBgraH.invokeExact(s, pixels, width, height, stride) as Int
        else publishRgbaH.invokeExact(s, pixels, width, height, stride) as Int
        check(status, "publishing a frame")
    }

    fun publishSurface(s: MemorySegment, ioSurfaceRef: Long, flipped: Boolean) {
        check(publishSurfaceH.invokeExact(s, MemorySegment.ofAddress(ioSurfaceRef), if (flipped) 1 else 0) as Int, "publishing an IOSurface")
    }

    /** A new BGRA8, page-padded IOSurface; the caller owns the +1 retain (MetalIOSurface.release). */
    fun ioSurfaceCreate(width: Int, height: Int): Long {
        val p = surfaceCreateH.invokeExact(width, height) as MemorySegment
        if (p == MemorySegment.NULL) fail("creating a $width x $height IOSurface")
        return p.address()
    }

    fun hasClients(s: MemorySegment): Boolean = check(hasClientsH.invokeExact(s) as Int, "hasClients") == 1

    fun serverDescription(s: MemorySegment): String = takeString(serverDescH.invokeExact(s) as MemorySegment, "describing the server")

    fun serverFree(s: MemorySegment) { serverFreeH.invokeExact(s) }

    fun list(waitMs: Int): String = takeString(listH.invokeExact(waitMs) as MemorySegment, "listing servers")

    /** Null when no server matched within [waitMs]; the reason is in [lastErrorMessage]. */
    fun clientNew(match: String?, waitMs: Int): MemorySegment? = Arena.ofConfined().use { a ->
        val m = if (match == null) MemorySegment.NULL else a.allocateFrom(match)
        (clientNewH.invokeExact(m, waitMs) as MemorySegment).takeIf { it != MemorySegment.NULL }
    }

    fun lastErrorMessage(): String = lastError()

    fun frameCount(c: MemorySegment): Long = frameCountH.invokeExact(c) as Long

    fun waitFrame(c: MemorySegment, after: Long, waitMs: Int): Long = waitFrameH.invokeExact(c, after, waitMs) as Long

    /** The latest frame as (RGBA bytes, width, height), or null if none has arrived. */
    fun copyRgba(c: MemorySegment, reuse: ByteArray?): Triple<ByteArray, Int, Int>? = Arena.ofConfined().use { a ->
        val w = a.allocate(I)
        val h = a.allocate(I)
        var cap = (reuse?.size ?: 0).toLong()
        var buf = if (cap > 0) a.allocate(cap) else MemorySegment.NULL
        var status = copyRgbaH.invokeExact(c, buf, cap, w, h) as Int
        if (status == 0) return@use null
        if (status == -2) {
            cap = w.get(I, 0).toLong() * h.get(I, 0) * 4
            buf = a.allocate(cap)
            status = copyRgbaH.invokeExact(c, buf, cap, w, h) as Int
        }
        if (status == 0) return@use null
        check(status, "reading a frame")
        cpuCopies.incrementAndGet()
        val width = w.get(I, 0)
        val height = h.get(I, 0)
        val n = width * height * 4
        val out = if (reuse != null && reuse.size == n) reuse else ByteArray(n)
        MemorySegment.copy(buf, JAVA_BYTE, 0, out, 0, n)
        Triple(out, width, height)
    }

    /**
     * Frames read back through memory by [copyRgba] / [copyRgbaInto] in this JVM (each one a GPU
     * blit into a staging buffer and a CPU swizzle). A zero-copy receiver leaves it unchanged.
     */
    val cpuCopies = java.util.concurrent.atomic.AtomicLong()

    /**
     * The IOSurface the client's latest frame lives in, +1 retained (release with
     * `MetalIOSurface.release`), or 0 if there is no frame yet (or it is
     * not IOSurface-backed; [lastErrorMessage] then says so). No pixels move.
     */
    fun frameSurface(c: MemorySegment): Long = Arena.ofConfined().use { a ->
        val w = a.allocate(I)
        val h = a.allocate(I)
        val p = frameSurfaceH.invokeExact(c, w, h) as MemorySegment
        if (p == MemorySegment.NULL) 0L else p.address()
    }

    /** Copy the latest frame straight into [dst]; returns (width, height), or null if none yet or it does not fit. */
    fun copyRgbaInto(c: MemorySegment, dst: MemorySegment): Pair<Int, Int>? = Arena.ofConfined().use { a ->
        val w = a.allocate(I)
        val h = a.allocate(I)
        val status = copyRgbaH.invokeExact(c, dst, dst.byteSize(), w, h) as Int
        if (status == 1) cpuCopies.incrementAndGet()
        if (status == 1) w.get(I, 0) to h.get(I, 0) else if (status == 0 || status == -2) null else fail("reading a frame")
    }

    fun clientDescription(c: MemorySegment): String = takeString(clientDescH.invokeExact(c) as MemorySegment, "describing the client")

    fun clientFree(c: MemorySegment) { clientFreeH.invokeExact(c) }
}
