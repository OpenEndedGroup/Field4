package afield.audio.synth

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A daemon an earlier Field left running is ended before the next boot; anything else is left alone. */
class SonicPiReapTest {
    private fun pidFile(pid: Long) = File.createTempFile("sonic-pi", ".pid").apply { writeText("$pid\n"); deleteOnExit() }

    @Test
    fun aRecordedDaemonStillRunningIsEnded() {
        val stray = ProcessBuilder("sleep", "60").start()
        try {
            val file = pidFile(stray.pid())
            val report = SonicPi.reapAbandonedDaemon(file, isDaemon = { it.pid() == stray.pid() })
            assertNotNull(report)
            assertTrue(stray.waitFor(5, TimeUnit.SECONDS), "the stray process must be gone")
            assertFalse(file.exists(), "the record is consumed")
        } finally {
            stray.destroyForcibly()
        }
    }

    @Test
    fun aReusedPidThatIsNotADaemonIsLeftAlone() {
        val bystander = ProcessBuilder("sleep", "60").start()
        try {
            assertNull(SonicPi.reapAbandonedDaemon(pidFile(bystander.pid()), isDaemon = { false }))
            assertTrue(bystander.isAlive)
        } finally {
            bystander.destroyForcibly()
        }
    }

    @Test
    fun noRecordMeansNothingToDo() {
        assertNull(SonicPi.reapAbandonedDaemon(File("/nonexistent/sonic-pi/daemon.pid")))
        assertEquals("daemon.pid", SonicPi.daemonPidFile().name)
    }
}
