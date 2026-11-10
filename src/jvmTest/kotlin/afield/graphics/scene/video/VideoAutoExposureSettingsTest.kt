package afield.graphics.scene.video

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class VideoAutoExposureSettingsTest {
    @Test fun settingsPersistAsOneValidatedObject() {
        val settings = VideoAutoExposure.Settings(enabled = true, mode = VideoAutoExposure.Mode.LINKED, mix = .8f,
            meterHz = 24.0, risingAlpha = .7f, risingBeta = .3f, fallingAlpha = .2f, fallingBeta = .05f,
            maximumVelocity = .6f, blackPadding = .02f, whitePadding = .04f,
            blackMinimum = -.1f, blackMaximum = .4f, whiteMinimum = .6f, whiteMaximum = 1.5f, minimumSpan = .02f)
        val json = Json { encodeDefaults = true }
        assertEquals(settings, json.decodeFromString<VideoAutoExposure.Settings>(json.encodeToString(settings)))
    }

    @Test fun impossibleExposureBoundsAndNonFiniteGainsAreRejected() {
        assertFailsWith<IllegalArgumentException> { VideoAutoExposure.Settings(blackMinimum = 1f, blackMaximum = .5f) }
        assertFailsWith<IllegalArgumentException> { VideoAutoExposure.Settings(whiteMinimum = 2f, whiteMaximum = 1f) }
        assertFailsWith<IllegalArgumentException> { VideoAutoExposure.Settings(blackMinimum = 1f, blackMaximum = 2f, whiteMaximum = 1f) }
        assertFailsWith<IllegalArgumentException> { VideoAutoExposure.Settings(risingBeta = Float.NaN) }
    }
}
