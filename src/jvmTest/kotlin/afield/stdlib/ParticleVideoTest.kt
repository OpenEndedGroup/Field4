package afield.stdlib

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Independent little-endian fixture: probation order, two trajectories and valid merge ancestry. */
class ParticleVideoTest {
    private data class Record(val kind: Int, val flags: Int, val id: Long, val frame: Long, val related: Long = 0)
    private val records = listOf(
        Record(1, 1, 1, 0), Record(1, 0, 1, 1), Record(1, 0, 1, 2),
        Record(1, 1, 2, 0), Record(1, 0, 2, 1), Record(1, 0, 2, 2), Record(2, 64, 2, 2, 1),
        Record(1, 0, 1, 3), Record(2, 128, 1, 3),
    )
    private fun bytes(): ByteArray {
        val b = ByteBuffer.allocate(64 + records.size * 80).order(ByteOrder.LITTLE_ENDIAN)
        b.put("PVTRACK1".toByteArray()).putInt(1).putInt(64).putInt(80).putInt(64).putInt(48)
            .putInt(-1).putDouble(25.0).putLong(4).putLong(records.size.toLong()).putLong(1)
        records.forEach { r ->
            b.putInt(r.kind).putInt(r.flags).putLong(r.id).putLong(r.frame).putLong(r.related)
            floatArrayOf(10f + r.frame, 20f, 1f, 0f, .7f, .3f, .2f, .9f, .01f, .02f, -1f, .03f).forEach(b::putFloat)
        }
        return b.array()
    }
    private fun <T> withFile(data: ByteArray = bytes(), action: (File) -> T): T {
        val file = File.createTempFile("particle-reader-", ".pvt")
        return try { file.writeBytes(data); action(file) } finally { file.delete() }
    }

    @Test fun probationOrderingAndMergeAncestryAreIndexedByTimeAndIdentity() = withFile { f ->
        val video = loadParticleVideo(f)
        assertEquals(4294967295L, video.seed)
        assertEquals(listOf(1L, 2L), video.frame(0).samples.map { it.id })
        assertEquals(listOf(0, 1, 2), video.trajectory(2).map { it.frame })
        assertEquals(1L, video.tracks.single { it.id == 2L }.mergedInto)
        assertEquals(listOf(1, 2), video.trajectory(1, 1, 2).map { it.frame })
        assertEquals(emptyList(), video.trajectory(999))
    }

    @Test fun InterpolationNeverConnectsRetiredIdentitiesToAnotherParticle() { withFile { f ->
        val video = loadParticleVideo(f)
        val half = video.at(.5 / 25)
        assertEquals(10.5f, half.samples.first().x)
        assertEquals(12f, video.at(2.5 / 25).samples.single { it.id == 2L }.x)
        assertEquals(3, video.at(99.0).index)
        assertEquals(0, video.at(-99.0).index)
        assertFailsWith<IllegalArgumentException> { video.at(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { video.frame(4) }
    } }

    @Test fun TruncationIncompleteFilesNonfiniteValuesAndBrokenLifecyclesAreRejected() {
        val cases = listOf(
            bytes().dropLast(1).toByteArray(), bytes() + byteArrayOf(0),
            bytes().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(56, 0) },
            bytes().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(64 + 32, Float.NaN) },
            bytes().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(64 + 80 + 16, 3) },
            bytes().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(64 + 6 * 80 + 24, 2) },
            bytes().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(64 + 4, 0) },
        )
        cases.forEach { bad -> withFile(bad) { assertFailsWith<IllegalArgumentException> { loadParticleVideo(it) } } }
    }

    @Test fun GeometryAndRasterUseSampledColorsAndPreserveCoordinates() { withFile { f ->
        val video = loadParticleVideo(f)
        assertEquals(2, video.frame(1).points().nodes.size)
        assertEquals(2, video.trails(2, length = 2).size)
        val image = video.image(1, scale = 1, pointRadius = 2.0)
        assertEquals(64, image.width)
        val rgb = java.awt.Color(image.getRGB(11,20))
        assertTrue(rgb.red > rgb.green && rgb.green > rgb.blue)
        assertFailsWith<IllegalArgumentException> { video.frame(0).points(pointSize = -1.0) }
    } }

    @Test fun InvalidTrackingOptionsFailBeforeStartingAnyProcess() {
        assertFailsWith<IllegalArgumentException> { ParticleTrackingOptions(particles = 0).json() }
        assertFailsWith<IllegalArgumentException> { ParticleTrackingOptions(colorLimit = Double.NaN).json() }
        assertFailsWith<IllegalArgumentException> { ParticleTrackingOptions(fps = 0.0).json() }
        assertEquals(0, ParticleTrackingOptions().json().getInt("width"))
        assertEquals(1.0, ParticleTrackingOptions(colorLimit = 1.0).json().getDouble("color_limit"))
    }

    @Test fun CompressedPortableTracksHaveTheSameCoordinatesAndLifecycles() {
        val file = File.createTempFile("particle-reader-", ".pvt.gz")
        try {
            java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write(bytes()) }
            val video = loadParticleVideo(file)
            assertEquals(2, video.tracks.size)
            assertEquals(11f, video.frame(1).samples.first().x)
        } finally { file.delete() }
    }
}
