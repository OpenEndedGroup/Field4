package afield.media.video

import afield.geometry.topology.vectorfield.VectorFieldTopology
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParticleFlowTest {
    // One 8x6 frame in which every particle moved by (2, -1) except a patch moving by (5, 0), with the
    // camera fitted as the (2, -1) shift.
    private fun frame(): FlowFrame {
        val n = 48; val u = FloatArray(n); val v = FloatArray(n); val w = FloatArray(n) { 1f }
        for (i in 0 until n) { val patch = i % 8 in 3..4 && i / 8 in 2..3; u[i] = if (patch) 5f else 2f; v[i] = if (patch) 0f else -1f }
        return FlowFrame(u, v, w, doubleArrayOf(2.0, -1.0), doubleArrayOf(2.0, 0.0, 0.0, -1.0, 0.0, 0.0))
    }

    @Test fun cameraMotionIsRemovedOrKept() {
        val flow = ParticleFlow(8, 6, 4, 18.0, List(3) { frame() })
        val keep = flow.at(1, window = 1, camera = CameraMotion.KEEP, prior = 0f)
        val affine = flow.at(1, camera = CameraMotion.AFFINE, prior = 0f)
        val shift = flow.at(1, camera = CameraMotion.SHIFT, prior = 0f)
        assertEquals(2f, keep.data[0], 1e-5f); assertEquals(-1f, keep.data[1], 1e-5f)
        assertEquals(0f, affine.data[0], 1e-5f); assertEquals(0f, affine.data[1], 1e-5f)
        val patch = (2 * 8 + 3) * 3
        assertEquals(3f, affine.data[patch], 1e-5f); assertEquals(1f, affine.data[patch + 1], 1e-5f)
        assertEquals(affine.data.toList(), shift.data.toList())
    }

    @Test fun saveAndLoadRoundTrip() {
        val flow = ParticleFlow(8, 6, 4, 18.0, List(2) { frame() })
        val file = File.createTempFile("flow", ".flow.gz").also { it.deleteOnExit() }
        flow.save(file.path)
        val back = loadParticleFlow(file.path)
        assertEquals(listOf(8, 6, 4, 2), listOf(back.width, back.height, back.cell, back.frameCount))
        val a = flow.at(0, camera = CameraMotion.AFFINE).data; val b = back.at(0, camera = CameraMotion.AFFINE).data
        assertTrue(a.indices.all { abs(a[it] - b[it]) < 1f / 32 })
    }

    @Test fun flowFromRecordedTracks() {
        val video = loadParticleVideo("public/docs/assets/particle_video/example.pvt.gz")
        val flow = video.flow(cell = 10, sigma = 1.5)
        assertEquals(video.frameCount, flow.frameCount)
        assertEquals(video.width / 10, flow.width)
        val img = flow.at(100, camera = CameraMotion.AFFINE)
        assertTrue(img.data.any { it != 0f })
        val topology = VectorFieldTopology.compute(img)
        val still = topology.streamlines(spacing = 4.0, minSpeed = 1e9)
        assertTrue(still.isEmpty(), "no seed is faster than 1e9 px/frame")
        assertTrue(topology.streamlines(spacing = 4.0).isNotEmpty())
    }

    @Test fun videoFramesDecodesTheClip() {
        val clip = "public/docs/assets/vector_field_topology/eagle.mp4"
        assertEquals(480 to 294, videoSize(clip))
        assertEquals(240 to 146, videoSize(clip, width = 240))
        val frames = videoFrames(clip, width = 240, gray = true).take(3).toList()
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.width == 240 && it.height == 146 && it.channels == 1 })
        assertTrue(frames[0].data.all { it in 0f..1f } && frames[0].data.any { it > 0.1f })
        val rgb = videoFrames(clip, start = 2.0, duration = 0.5, fps = 6.0).toList()
        assertEquals(3, rgb.first().channels)
        assertTrue(rgb.size in 2..4, "half a second at 6 fps, got ${rgb.size}")
    }
}
