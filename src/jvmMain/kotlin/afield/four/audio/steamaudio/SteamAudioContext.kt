package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLContextSettings
import afield.four.audio.steamaudio.raw.IPLLogFunction
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/** Receives diagnostic messages from the native library. */
public fun interface LogCallback {
    public fun log(level: LogLevel, message: String)
}

/**
 * The root Steam Audio object. Everything else is created from a context, directly or
 * indirectly, and must be closed before it.
 *
 * A single context per process is the normal arrangement. Creating one loads the native
 * library if it is not already loaded.
 *
 * ```
 * SteamAudioContext(logCallback = { level, message -> logger.info("[$level] $message") }).use { ctx ->
 *     // ...
 * }
 * ```
 */
public class SteamAudioContext private constructor(
    handle: MemorySegment,
    /** Owns the log-callback upcall stub, which must stay alive as long as the context. */
    private val callbackArena: Arena?,
) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplContextRelease

    override val typeName: String get() = "SteamAudioContext"

    override fun onClosed() {
        callbackArena?.close()
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): SteamAudioContext =
        SteamAudioContext(Phonon.iplContextRetain(liveHandle), null)

    /**
     * Computes the direction from listener to source, expressed in the listener's local frame.
     *
     * Useful for feeding [BinauralEffectParams] or [PanningEffectParams] when driving effects
     * directly rather than through a [Simulator].
     */
    public fun calculateRelativeDirection(
        sourcePosition: Vector3,
        listenerPosition: Vector3,
        listenerAhead: Vector3 = Vector3.FORWARD,
        listenerUp: Vector3 = Vector3.UP,
    ): Vector3 {
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            return Vector3.readFrom(
                Phonon.iplCalculateRelativeDirection(
                    arena,
                    handle,
                    sourcePosition.allocate(arena),
                    listenerPosition.allocate(arena),
                    listenerAhead.allocate(arena),
                    listenerUp.allocate(arena),
                ),
            )
        }
    }

    public companion object {
        /**
         * Creates a context.
         *
         * @param simdLevel the widest SIMD instruction set Steam Audio may use. On ARM builds
         *   this is effectively ignored -- see [SimdLevel] for why `NEON` and `SSE2` share a
         *   value upstream.
         * @param flags optional behaviour; [ContextFlags.VALIDATION] is worth enabling in
         *   development builds.
         * @param logCallback receives native diagnostics. Invoked from whichever native thread
         *   produced the message, including Steam Audio's simulation threads, so it must be
         *   thread-safe. Held for the lifetime of the context.
         */
        public operator fun invoke(
            simdLevel: SimdLevel = SimdLevel.AVX2,
            flags: ContextFlags = ContextFlags.NONE,
            logCallback: LogCallback? = null,
        ): SteamAudioContext {
            SteamAudioNatives.load()

            // The upcall stub must outlive this call: native code keeps the pointer for the
            // lifetime of the context, so the wrapper takes ownership of the arena.
            var stubArena: Arena? = null
            try {
                val handle = Arena.ofConfined().use { scratch ->
                    val settings = IPLContextSettings.allocate(scratch)
                    IPLContextSettings.version(settings, Phonon.STEAMAUDIO_VERSION())
                    IPLContextSettings.simdLevel(settings, simdLevel.value)
                    IPLContextSettings.flags(settings, flags.bits)

                    if (logCallback != null) {
                        val arena = Arena.ofShared()
                        stubArena = arena
                        IPLContextSettings.logCallback(
                            settings,
                            IPLLogFunction.allocate(
                                { level, message -> dispatchLog(logCallback, level, message) },
                                arena,
                            ),
                        )
                    }

                    createHandle("iplContextCreate") { out -> Phonon.iplContextCreate(settings, out) }
                }
                return SteamAudioContext(handle, stubArena)
            } catch (failure: Throwable) {
                stubArena?.close()
                throw failure
            }
        }

        /**
         * Bridges a native log message to a Kotlin callback.
         *
         * Exceptions must never escape an upcall into native code, so anything the callback
         * throws is swallowed here.
         */
        private fun dispatchLog(callback: LogCallback, level: Int, message: MemorySegment) {
            try {
                val text = if (message.address() == 0L) "" else message.reinterpret(Long.MAX_VALUE).getString(0L)
                callback.log(LogLevel.from(level), text)
            } catch (_: Throwable) {
                // Never propagate across the FFM boundary.
            }
        }
    }
}
