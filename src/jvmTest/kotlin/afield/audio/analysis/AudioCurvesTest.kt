package afield.audio.analysis

import afield.audio.engine.WavFloatArrayWriter
import org.junit.Assume
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real `fieldaudio_curve` analyzer through `uv`, as a box calling [audioCurves] does, on a
 * small generated recording. Skipped where `uv` cannot be found.
 */
class AudioCurvesTest {
    private val settings = AudioCurveSettings(fftSize = 256, hopSize = 64, melBands = 16, history = 12)
    private lateinit var directory: Path
    private lateinit var wav: Path

    @BeforeTest
    fun needsUvAndARecording() {
        val home = System.getProperty("user.home")
        val candidates = listOfNotNull(System.getenv("AFIELD_UV")) +
            System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { "$it/uv" } +
            listOf("/opt/homebrew/bin/uv", "/usr/local/bin/uv", "$home/.local/bin/uv")
        Assume.assumeTrue("needs uv", candidates.any { Files.isExecutable(Paths.get(it)) })
        directory = Files.createTempDirectory("audio-curves-test")
        // A rising tone with a burst of a second tone half way: something for the spectra to tell apart.
        val rate = 8000
        val samples = FloatArray(12_000) { i ->
            val t = i / rate.toDouble()
            (0.4 * sin(2 * PI * (200 + 300 * t) * t) + if (i in 5000..7000) 0.3 * sin(2 * PI * 1700 * t) else 0.0).toFloat()
        }
        wav = directory.resolve("tone.wav")
        Files.write(wav, WavFloatArrayWriter.encodeMono16(samples, rate))
    }

    @AfterTest
    fun cleanUp() {
        if (::directory.isInitialized) directory.toFile().deleteRecursively()
    }

    @Test
    fun analysesAWavIntoOneCurvePerHopAndCachesIt() {
        val curve = audioCurves(wav, settings, directory).single()
        curve.use {
            assertEquals(1L + (12_000 - 256) / 64, it.frameCount)
            assertNull(it.emaLongestSeconds)
            assertEquals(12, it.frame(it.frameCount - 1).pointCount)
        }
        val written = Files.list(directory).use { files -> files.filter { it.toString().endsWith(".f4curve") }.toList() }.single()
        val stamp = Files.getLastModifiedTime(written)
        audioCurves(wav, settings, directory).single().close()
        assertEquals(stamp, Files.getLastModifiedTime(written), "a second call reuses the analysis")
    }

    @Test
    fun theStageDumpAgreesWithTheCurveFile() {
        val time = 0.9
        val stages = audioCurveStages(wav, time, settings)
        val fromFile = audioCurves(wav, settings, directory).single().use { it.frameAt(time)!! }
        val aligned = stages.curves.last()
        assertEquals(fromFile.timeSeconds, aligned.time, 1e-9)
        val dumped = aligned.aligned.flatten()
        assertEquals(fromFile.coordinates.size, dumped.size)
        assertTrue(dumped.indices.all { abs(dumped[it] - fromFile.coordinates[it]) < 1e-5 }, "same coordinates, up to float32")
        assertEquals(12, stages.masses.size)
        assertTrue(stages.masses.all { m -> abs(m.sum() - 1.0) < 1e-9 }, "every spectrum has unit mass")
        assertEquals(0.3, stages.compression)
    }

    @Test
    fun theEmaVariantMarksItsFiles() {
        audioCurves(wav, settings.copy(emaLongestSeconds = 0.5), directory).single().use {
            assertEquals(0.5, it.emaLongestSeconds)
            assertEquals(12, it.history)
            assertEquals(12, it.frame(0).pointCount)
        }
    }
}
