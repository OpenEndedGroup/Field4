package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.Phonon

/**
 * Status codes returned by Steam Audio functions that can fail.
 *
 * Note that [AudioEffectState], returned by the various `apply` calls, is *not* an error
 * code and never surfaces as a [SteamAudioException].
 */
public enum class SteamAudioError(internal val value: Int) {
    /** The operation completed successfully. */
    SUCCESS(0),

    /** An unspecified error occurred. */
    FAILURE(1),

    /** The system ran out of memory. */
    OUT_OF_MEMORY(2),

    /** An error occurred while initializing an external dependency. */
    INITIALIZATION(3),
    ;

    internal companion object {
        private val byValue = entries.associateBy(SteamAudioError::value)

        fun from(value: Int): SteamAudioError =
            byValue[value] ?: throw SteamAudioException(FAILURE, "unknown IPLerror value $value")
    }
}

/** Thrown when a Steam Audio call returns a non-success [SteamAudioError]. */
public class SteamAudioException internal constructor(
    public val error: SteamAudioError,
    message: String,
) : RuntimeException(message)

/**
 * Throws [SteamAudioException] unless this `IPLerror` is `IPL_STATUS_SUCCESS`.
 *
 * @param what a short description of the attempted operation, used in the message.
 */
internal fun Int.checkSuccess(what: String) {
    if (this != Phonon.IPL_STATUS_SUCCESS()) {
        val error = SteamAudioError.from(this)
        throw SteamAudioException(error, "$what failed: ${error.name}")
    }
}
