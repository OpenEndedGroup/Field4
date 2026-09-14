package afield.four.audio.steamaudio

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base class for the reference-counted Steam Audio objects.
 *
 * Every Steam Audio object follows the same C convention: `iplXxxCreate` writes a handle into
 * an out-parameter, `iplXxxRetain` increments a reference count, and `iplXxxRelease` takes a
 * *pointer to* the handle, decrements the count, and nulls the caller's copy.
 *
 * ### Lifetime
 *
 * Objects are [AutoCloseable]; [close] decrements the native reference count exactly once,
 * however many times it is called. A [Cleaner] provides a safety net so that a dropped object
 * does not leak native memory, but it is not a substitute for closing: cleaner timing is
 * unspecified, and in a long-running audio application leaked impulse responses and scenes are
 * large enough to matter. Use `use { }`.
 *
 * ### Shared ownership
 *
 * [retain] exposes the native reference count directly: it returns a *new* wrapper around the
 * same native object, with its own [close]. Use it when an object genuinely outlives the scope
 * that created it -- for instance a [Scene] referenced both by a [Simulator] and by the meshes
 * inside it. Each wrapper must be closed exactly once.
 */
public abstract class SteamAudioObject internal constructor(
    /** The raw handle: a pointer value, as returned by `iplXxxCreate`. */
    internal val handle: MemorySegment,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val cleanable: Cleaner.Cleanable

    init {
        require(handle.address() != 0L) { "refusing to wrap a null Steam Audio handle" }
        // The cleanup action must not capture `this`, or the object is never collectable.
        // Capturing the handle and a function reference to the (stateless) release routine
        // keeps the action independent of the wrapper.
        val release = releaseAction()
        cleanable = CLEANER.register(this) { releaseHandle(handle, release) }
    }

    /**
     * Returns the `iplXxxRelease` invoker for this object type.
     *
     * Called once during construction. Implementations must return a lambda that does not
     * capture the wrapper object -- typically a direct reference to a generated binding.
     */
    internal abstract fun releaseAction(): (MemorySegment) -> Unit

    /** A short human-readable name for this object type, used in messages. */
    internal open val typeName: String get() = this::class.simpleName ?: "SteamAudioObject"

    /** True once [close] has run. */
    public val isClosed: Boolean get() = closed.get()

    /**
     * Decrements the native reference count, releasing the object when the count reaches zero.
     *
     * Idempotent: subsequent calls do nothing.
     */
    final override fun close() {
        if (closed.compareAndSet(false, true)) {
            cleanable.clean()
            onClosed()
        }
    }

    /**
     * Called exactly once, after the native object has been released.
     *
     * Wrappers that own extra JVM-side resources -- an [Arena] holding upcall stubs, say --
     * free them here.
     */
    internal open fun onClosed() {}

    /** Throws if this object has already been closed. */
    internal fun checkOpen() {
        if (closed.get()) {
            throw IllegalStateException("$typeName has already been closed")
        }
    }

    /**
     * The handle, after verifying the object is still open.
     *
     * Every wrapper method that calls into native code should go through this rather than
     * touching [handle] directly, so that use-after-close produces a clear Kotlin exception
     * instead of a native crash.
     */
    internal val liveHandle: MemorySegment
        get() {
            checkOpen()
            return handle
        }

    override fun toString(): String =
        "$typeName(0x${handle.address().toString(16)}${if (isClosed) ", closed" else ""})"

    internal companion object {
        private val CLEANER: Cleaner = Cleaner.create { runnable ->
            Thread(runnable, "steamaudio-cleaner").apply { isDaemon = true }
        }

        /**
         * Calls an `iplXxxRelease` function.
         *
         * Release takes a pointer to the handle and writes null back through it, so it needs a
         * writable slot. The slot is scratch -- the reference-count decrement lands on the
         * object itself, not the slot -- so a short-lived confined arena is all that is needed.
         */
        private fun releaseHandle(handle: MemorySegment, release: (MemorySegment) -> Unit) {
            Arena.ofConfined().use { arena ->
                val slot = arena.allocate(ValueLayout.ADDRESS)
                slot.set(ValueLayout.ADDRESS, 0L, handle)
                release(slot)
            }
        }
    }
}

/**
 * Runs [block] with a freshly allocated handle-sized out-parameter slot, and returns the
 * handle written into it.
 *
 * Used to bridge the `iplXxxCreate(..., IPLXxx* out)` convention.
 */
internal inline fun createHandle(what: String, block: (MemorySegment) -> Int): MemorySegment {
    Arena.ofConfined().use { arena ->
        val slot = arena.allocate(ValueLayout.ADDRESS)
        block(slot).checkSuccess(what)
        val handle = slot.get(ValueLayout.ADDRESS, 0L)
        if (handle.address() == 0L) {
            throw SteamAudioException(SteamAudioError.FAILURE, "$what returned success but a null handle")
        }
        return handle
    }
}

/** Reads a handle out of a slot without checking a return code, for `void`-returning creators. */
internal inline fun readHandle(block: (MemorySegment) -> Unit): MemorySegment {
    Arena.ofConfined().use { arena ->
        val slot = arena.allocate(ValueLayout.ADDRESS)
        block(slot)
        return slot.get(ValueLayout.ADDRESS, 0L)
    }
}

/** The handle of this object, or `MemorySegment.NULL` when the reference is absent. */
internal fun SteamAudioObject?.handleOrNull(): MemorySegment =
    this?.liveHandle ?: MemorySegment.NULL
