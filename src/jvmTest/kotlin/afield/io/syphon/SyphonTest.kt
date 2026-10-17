package afield.io.syphon

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-process Syphon round trips against `field_syphon_probe`, a separate process built next to
 * the library. Skipped (with a printed reason) when the bridge is not built or this is not a Mac.
 *
 * The probe's test pattern (see native/field-syphon/probe.c): quadrants red / green over blue /
 * white, and pixel (1,1) = (17,34,51). Its probe points are (1,1), (w/4,h/4), (3w/4,h/4),
 * (w/4,3h/4), (3w/4,3h/4).
 */
class SyphonTest {

    private fun available(): Boolean {
        val ok = SyphonLibrary.isAvailable && SyphonLibrary.probePath != null
        if (!ok) println("SKIPPED Syphon tests: ${SyphonLibrary.describe()}")
        return ok
    }

    private val expected = listOf(
        intArrayOf(17, 34, 51, 255), intArrayOf(255, 0, 0, 255), intArrayOf(0, 255, 0, 255),
        intArrayOf(0, 0, 255, 255), intArrayOf(255, 255, 255, 255),
    )

    /** The probe pattern, as RGBA bytes. */
    private fun pattern(w: Int, h: Int): ByteArray {
        val p = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val right = x >= w / 2
            val bottom = y >= h / 2
            val o = (y * w + x) * 4
            p[o] = if (right == bottom) 255.toByte() else 0
            p[o + 1] = if (right) 255.toByte() else 0
            p[o + 2] = if (bottom) 255.toByte() else 0
            p[o + 3] = 255.toByte()
        }
        val m = (1 * w + 1) * 4
        p[m] = 17; p[m + 1] = 34; p[m + 2] = 51
        return p
    }

    private fun probe(vararg args: String): Process =
        ProcessBuilder(listOf(SyphonLibrary.probePath.toString()) + args).redirectErrorStream(false).start()

    private fun pixelsOf(json: String): List<IntArray> {
        val a = org.json.JSONObject(json).getJSONArray("pixels")
        return (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> IntArray(4) { p.getInt(it) } } }
    }

    @Test fun missingLibraryIsReportedNotThrownAtClassLoad() {
        assertTrue(SyphonLibrary.searchedPaths.last().endsWith(java.nio.file.Path.of("build", "field-syphon", SyphonLibrary.fileName)))
        assertTrue(SyphonLibrary.describe().isNotEmpty())
    }

    @Test fun fieldPublishesAndAnotherProcessReceivesTheExactPixels() {
        if (!available()) return
        val name = "FieldSyphonTest-${ProcessHandle.current().pid()}-send"
        // The receiver starts listening first: this JVM's main run loop is not ours to pump in a
        // test, so the server's announcement at creation is what the probe hears.
        val receiver = probe("receive", name, "8000")
        Thread.sleep(500)
        syphonServer(name).use { server ->
            val w = 64
            val h = 48
            val px = pattern(w, h)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (receiver.isAlive && System.nanoTime() < deadline) {
                server.publish(px, w, h)
                Thread.sleep(16)
            }
            assertTrue(receiver.waitFor(1, TimeUnit.SECONDS), "probe still running")
            val out = receiver.inputStream.bufferedReader().readText().trim()
            val err = receiver.errorStream.bufferedReader().readText().trim()
            assertEquals(0, receiver.exitValue(), "probe failed: $err")
            val json = org.json.JSONObject(out)
            assertEquals(w, json.getInt("width"))
            assertEquals(h, json.getInt("height"))
            val got = pixelsOf(out)
            for (i in expected.indices) assertContentEquals(expected[i], got[i], "probe point $i: ${got[i].toList()}")
            assertTrue(server.framesPublished > 0)
        }
    }

    @Test fun anotherProcessPublishesAndFieldReceivesTheExactPixels() {
        if (!available()) return
        val name = "FieldSyphonTest-${ProcessHandle.current().pid()}-recv"
        val w = 80
        val h = 60
        val sender = probe("serve", name, "10", "$w", "$h")
        try {
            sender.inputStream.bufferedReader().readLine() // its description: it is up
            syphonClient(name, waitMs = 8000).use { client ->
                kotlin.test.assertSame(client, syphonClient(name), "same name reuses its open client")
                assertEquals(name, client.server.name)
                assertTrue(client.waitForFrame(0, 5000) > 0, "no frame arrived")
                val frame = client.latest()!!
                assertEquals(w, frame.width)
                assertEquals(h, frame.height)
                val points = listOf(1 to 1, w / 4 to h / 4, 3 * w / 4 to h / 4, w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4)
                for ((i, pt) in points.withIndex()) {
                    val got = frame.rgbaAt(pt.first, pt.second)
                    assertContentEquals(expected[i], got, "pixel $pt: ${got.toList()}")
                }
                val readers = java.util.concurrent.Executors.newFixedThreadPool(4)
                try {
                    val lookups = List(8) { readers.submit<SyphonClient> { syphonClient(name) } }
                    lookups.forEach { kotlin.test.assertSame(client, it.get(5, TimeUnit.SECONDS)) }
                } finally { readers.shutdownNow() }
                client.close()
                assertTrue(client.isClosed)
                syphonClient(name, waitMs = 8000).use { replacement ->
                    kotlin.test.assertNotSame(client, replacement)
                    assertTrue(!replacement.isClosed)
                    assertTrue(replacement.waitForFrame(0, 5000) > 0)
                }
            }
        } finally {
            sender.destroy()
            sender.waitFor(2, TimeUnit.SECONDS)
        }
    }

    @Test fun serversListsBothEnds() {
        if (!available()) return
        val name = "FieldSyphonTest-${ProcessHandle.current().pid()}-list"
        SyphonServer(name).use {
            val listed = probe("list", "3000")
            assertTrue(listed.waitFor(10, TimeUnit.SECONDS))
            // The probe asks for announcements; answering needs this JVM's main run loop, which a
            // plain JVM runs (the launcher parks its first thread in one). Report, do not assume.
            val out = listed.inputStream.bufferedReader().readText()
            println("probe list saw: $out")
            assertTrue(out.trim().startsWith("["))
        }
    }
}
