package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.Phonon

/** Library-wide constants and version information. */
public object SteamAudio {

    /** Major version of the Steam Audio library these bindings were generated against. */
    public const val VERSION_MAJOR: Int = 4

    /** Minor version. */
    public const val VERSION_MINOR: Int = 8

    /** Patch version. */
    public const val VERSION_PATCH: Int = 1

    /** Version string, for logging. */
    public const val VERSION: String = "$VERSION_MAJOR.$VERSION_MINOR.$VERSION_PATCH"

    /**
     * The version value the native library expects in `IPLContextSettings.version`,
     * packed as `(major shl 16) or (minor shl 8) or patch`.
     */
    public val versionCode: Int get() = Phonon.STEAMAUDIO_VERSION()

    /**
     * Number of frequency bands Steam Audio splits the spectrum into.
     *
     * This is a **compile-time constant of the native library** (3 by default, 11 when
     * `IPL_ENABLE_OCTAVE_BANDS` is set), and it determines the length of every per-band array
     * in the API -- material absorption and transmission, air absorption, reverb times, EQ.
     * The shipped binaries use 3.
     */
    public val NUM_BANDS: Int get() = Phonon.IPL_NUM_BANDS()

    /**
     * Loads the native library if it has not already been loaded.
     *
     * Equivalent to [SteamAudioNatives.load]; provided here so that callers have one obvious
     * entry point. Calling this is optional -- creating a [SteamAudioContext] loads the
     * library too -- but doing it explicitly surfaces loading problems early and at a
     * predictable point.
     */
    public fun load() {
        SteamAudioNatives.load()
    }

    /**
     * Number of Ambisonic channels for a given order: `(order + 1)^2`.
     *
     * Order 0 is 1 channel, order 1 is 4, order 2 is 9, order 3 is 16.
     */
    public fun ambisonicChannelCount(order: Int): Int {
        require(order >= 0) { "Ambisonic order cannot be negative: $order" }
        return (order + 1) * (order + 1)
    }
}
