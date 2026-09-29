package afield

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Field has to stop on `kill -INT <listener pid>`, which is how every agent brief, the smoke runner
 * and the docs runner stop it, and what backlog N0928-11 found it ignoring.
 *
 * The cause is the launch, not Field: `field.sh` runs the backend as `java … &` from a
 * non-interactive `/bin/sh`, POSIX hands such a job SIGINT as *ignored*, and HotSpot then never
 * installs its own handler. So these tests launch a small child JVM exactly that way — backgrounded
 * by `sh` — and signal it. The child is [InterruptSignalTestChild]; it registers a shutdown hook that
 * writes a marker, standing in for the persist flush that must survive the exit.
 *
 * The control case (no [InterruptSignal.install]) is kept deliberately. It is the premise: if a
 * future JDK or shell stops passing SIGINT through as ignored, it fails and says the fix is no
 * longer what makes the difference. See `dev_docs/SIGINT_SHUTDOWN.md`.
 */
class InterruptSignalTest {

    private val unix = !System.getProperty("os.name").lowercase().contains("windows")

    private class Child(val dir: File, val pid: Long, val waitFor: (Long) -> Int?) {
        val output: String get() = File(dir, "out.txt").takeIf { it.exists() }?.readText().orEmpty()
        val hookRan: Boolean get() = File(dir, "hook-ran").exists()
    }

    private fun javaArgs(dir: File, mode: String): File {
        val java = ProcessHandle.current().info().command().orElse("java")
        val argfile = File(dir, "java.args")
        // An @argfile keeps the test classpath out of the shell's quoting.
        argfile.writeText(
            listOf(
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                InterruptSignalTestChild::class.java.name, mode, dir.absolutePath,
            ).joinToString("\n") { "\"" + it.replace("\\", "\\\\") + "\"" }
        )
        File(dir, "java.cmd").writeText(java)
        return argfile
    }

    /** Starts the child the way `field.sh` does: `java … &` from a non-interactive `sh`. */
    private fun backgroundedBySh(mode: String): Child {
        val dir = Files.createTempDirectory("afield-sigint").toFile()
        val argfile = javaArgs(dir, mode)
        val java = File(dir, "java.cmd").readText()
        val script = "'$java' @'${argfile.absolutePath}' > '${dir}/out.txt' 2>&1 &\n" +
            "echo \$! > '${dir}/pid'\nwait \$!\necho \$? > '${dir}/exit'\n"
        val sh = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true)
            .redirectOutput(File(dir, "sh.txt")).start()
        val pidFile = File(dir, "pid")
        waitUntil(30_000) { pidFile.exists() && pidFile.readText().isNotBlank() }
        val pid = pidFile.readText().trim().toLong()
        return Child(dir, pid) { millis ->
            if (!sh.waitFor(millis, TimeUnit.MILLISECONDS)) null
            else File(dir, "exit").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        }
    }

    /** Started directly: SIGINT arrives with its default disposition, so the JVM's handler is in place. */
    private fun foreground(mode: String): Child {
        val dir = Files.createTempDirectory("afield-sigint").toFile()
        val argfile = javaArgs(dir, mode)
        val p = ProcessBuilder(File(dir, "java.cmd").readText(), "@${argfile.absolutePath}")
            .redirectErrorStream(true).redirectOutput(File(dir, "out.txt")).start()
        return Child(dir, p.pid()) { millis -> if (p.waitFor(millis, TimeUnit.MILLISECONDS)) p.exitValue() else null }
    }

    private fun waitUntil(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun signal(name: String, pid: Long) {
        ProcessBuilder("kill", "-$name", pid.toString()).start().waitFor(10, TimeUnit.SECONDS)
    }

    private fun Child.awaitReady() {
        assertTrue(waitUntil(60_000) { output.contains("ready") }, "child never became ready:\n$output")
    }

    private fun Child.cleanup() {
        if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) signal("KILL", pid)
        waitFor(10_000)
        dir.deleteRecursively()
    }

    @Test
    fun premiseABackgroundedJvmIgnoresSigint() {
        if (!unix) return
        val child = backgroundedBySh("control")
        try {
            child.awaitReady()
            signal("INT", child.pid)
            assertEquals(null, child.waitFor(3_000), "the JVM exited on SIGINT without the fix — the premise is gone")
            signal("TERM", child.pid)
            assertEquals(143, child.waitFor(30_000), "SIGTERM should still stop it:\n${child.output}")
        } finally {
            child.cleanup()
        }
    }

    @Test
    fun aBackgroundedJvmExitsOnSigintAndRunsItsShutdownHooks() {
        if (!unix) return
        val child = backgroundedBySh("install")
        try {
            child.awaitReady()
            assertTrue(child.output.contains("outcome=RESTORED_FROM_IGNORED"), child.output)
            val sent = System.nanoTime()
            signal("INT", child.pid)
            val exit = child.waitFor(15_000)
            val seconds = (System.nanoTime() - sent) / 1e9
            assertEquals(130, exit, "no exit within 15 s of SIGINT (${"%.1f".format(seconds)} s):\n${child.output}")
            assertTrue(child.hookRan, "the shutdown hook did not run")
        } finally {
            child.cleanup()
        }
    }

    @Test
    fun aForegroundJvmKeepsItsOwnHandler() {
        if (!unix) return
        val child = foreground("install")
        try {
            child.awaitReady()
            // ALREADY_HANDLED is the expected answer, but a child inherits its dispositions from
            // this test JVM, which inherits them from whatever started Gradle. If that chain began
            // at a backgrounded shell, the child is ignoring SIGINT too, and either way it has to exit.
            assertTrue(
                child.output.contains("outcome=ALREADY_HANDLED") || child.output.contains("outcome=RESTORED_FROM_IGNORED"),
                child.output
            )
            signal("INT", child.pid)
            assertEquals(130, child.waitFor(15_000), child.output)
            assertTrue(child.hookRan, "the shutdown hook did not run")
        } finally {
            child.cleanup()
        }
    }
}

/** The JVM [InterruptSignalTest] launches and signals. Args: `install|control <dir>`. */
object InterruptSignalTestChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[1])
        if (args[0] == "install") println("outcome=${InterruptSignal.install()}")
        Runtime.getRuntime().addShutdownHook(Thread { File(dir, "hook-ran").writeText("yes") })
        println("ready")
        System.out.flush()
        Thread.sleep(120_000)
    }
}
