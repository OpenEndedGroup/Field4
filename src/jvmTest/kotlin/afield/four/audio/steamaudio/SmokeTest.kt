package afield.four.audio.steamaudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks that the native library loads and a context can be created and released. */
class SmokeTest {

    /**
     * Whether the tests that touch the native library can run.
     *
     * Field does not commit the Steam Audio binaries, so a clone that has never run
     * `fetchSteamAudioNatives` has none. Skipping with a stated reason keeps `check` green there
     * and says what is missing, rather than failing on a link error that names no remedy.
     */
    private fun available(): Boolean {
        if (!SteamAudioNatives.isAvailable) {
            println("SKIPPED Steam Audio smoke tests: ${SteamAudioNatives.describe()}")
            return false
        }
        return true
    }

    @Test
    fun versionMatchesVendoredSdk() {
        if (!available()) return
        SteamAudio.load()
        assertEquals(4, SteamAudio.VERSION_MAJOR)
        assertEquals(8, SteamAudio.VERSION_MINOR)
        assertEquals(1, SteamAudio.VERSION_PATCH)
    }

    @Test
    fun bandCountIsThree() {
        if (!available()) return
        SteamAudio.load()
        // The shipped binaries are built without IPL_ENABLE_OCTAVE_BANDS. Every per-band array
        // in the API is this long, so a change here would silently corrupt material and
        // air-absorption data.
        assertEquals(3, SteamAudio.NUM_BANDS)
    }

    @Test
    fun createAndReleaseContext() {
        if (!available()) return
        SteamAudioContext().use { context ->
            assertTrue(!context.isClosed)
        }
    }

    @Test
    fun contextIsClosedAfterUse() {
        if (!available()) return
        val context = SteamAudioContext()
        context.close()
        assertTrue(context.isClosed)
        // Closing twice must be safe: the reference count is only decremented once.
        context.close()
    }

    @Test
    fun ambisonicChannelCounts() {
        assertEquals(1, SteamAudio.ambisonicChannelCount(0))
        assertEquals(4, SteamAudio.ambisonicChannelCount(1))
        assertEquals(9, SteamAudio.ambisonicChannelCount(2))
        assertEquals(16, SteamAudio.ambisonicChannelCount(3))
    }
}
