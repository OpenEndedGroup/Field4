package afield

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/*
FIELD_TEST
id: kotlin.safe-save-atomic.smoke
runner: jvm-main
entrypoint: afield.SafeSaveAtomicSmokeTest
tags: [smoke, saving, transcript]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "SafeSave atomic smoke passed"
*/
/**
 * Smoke for [File.safeSave]'s `atomic = true` path — the fix for transient `operations.yaml`
 * corruption (a reader catching a truncated/interleaved write). The operation-transcript writer
 * (`OperationTranscript.checkpoint` and friends) now writes atomically: to a temp file in the same
 * directory, then an atomic rename into place, so a concurrent reader (or another process sharing
 * the workspace) only ever observes a complete file.
 *
 * The load-bearing case is [concurrentAtomicWritesNeverTear]: many writer threads hammer one file
 * while a reader parses it in a tight loop; with atomic writes the reader must never see a
 * half-written (unparseable) file — which is exactly the SnakeYAML "expected <block end>" failure
 * this fix eliminates.
 */
object SafeSaveAtomicSmokeTest {

    fun runTests(): Boolean {
        val dir = Files.createTempDirectory("safe-save-atomic").toFile()
        return try {
            println("Running SafeSave atomic smoke...")
            atomicWriteRoundTrips(dir)
            atomicReplaceBacksUpAndLeavesNoTemp(dir)
            concurrentAtomicWritesNeverTear(dir)
            println("✅ SafeSave atomic smoke passed")
            true
        } catch (t: Throwable) {
            println("❌ SafeSave atomic smoke failed: ${t.message}")
            t.printStackTrace()
            false
        } finally {
            dir.deleteRecursively()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "SafeSave atomic smoke failed" }
    }

    private fun require(cond: Boolean, lazyMessage: () -> String) {
        if (!cond) throw AssertionError(lazyMessage())
    }

    private fun tempFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") } ?: emptyList()

    /** An atomic write lands the exact content and leaves no temp file behind. */
    private fun atomicWriteRoundTrips(dir: File) {
        val file = File(dir, "round-trip.yaml")
        val yaml = "- move:\n    box: \"db367ae3-471e-4af6-93c9-07c795413735\"\n    to: { x: 1.0 }\n"
        require(file.safeSave(yaml, createBackup = false, atomic = true)) { "first write should report a change" }
        require(file.readText() == yaml) { "content must round-trip exactly" }
        require(Yaml().load<Any?>(file.readText()) is List<*>) { "written YAML must parse to a list" }
        require(tempFiles(dir).isEmpty()) { "no .tmp file should be left behind: ${tempFiles(dir)}" }
    }

    /** Replacing existing content backs up the old file and still leaves no temp behind. */
    private fun atomicReplaceBacksUpAndLeavesNoTemp(dir: File) {
        val file = File(dir, "replace.yaml")
        val v1 = "- wait:\n    frames: 1\n"
        val v2 = "- wait:\n    frames: 2\n"
        file.safeSave(v1, createBackup = true, maxBackups = 10, atomic = true)
        file.safeSave(v2, createBackup = true, maxBackups = 10, atomic = true)
        require(file.readText() == v2) { "file must hold the latest content" }
        val backups = File(dir, "backups").listFiles()?.filter { it.isFile } ?: emptyList()
        require(backups.any { it.readText() == v1 }) { "the previous version must be backed up: $backups" }
        require(tempFiles(dir).isEmpty()) { "no .tmp file should be left behind: ${tempFiles(dir)}" }
    }

    /** THE case: concurrent atomic writers must never leave the file in an unparseable state. */
    private fun concurrentAtomicWritesNeverTear(dir: File) {
        val file = File(dir, "concurrent.yaml")
        // Distinct-but-valid payloads of very different lengths — a torn/interleaved write would
        // splice one into another and fail to parse (the exact bug being guarded).
        val payloads = (0 until 6).map { i ->
            buildString {
                repeat(20 + i * 40) { n -> append("- move:\n    box: \"box-$i-$n\"\n    to: { x: ${i}.$n }\n") }
            }
        }
        file.safeSave(payloads[0], createBackup = false, atomic = true)

        val writers = 6
        val itersPerWriter = 120
        val readerErr = AtomicReference<Throwable?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val start = CountDownLatch(1)

        val reader = Thread {
            start.await()
            val yaml = Yaml()
            while (!stop.get()) {
                try {
                    val parsed = yaml.load<Any?>(file.readText())
                    if (parsed !is List<*>) error("parsed to non-list: ${parsed?.javaClass}")
                } catch (t: Throwable) {
                    readerErr.compareAndSet(null, t); return@Thread
                }
            }
        }
        reader.start()

        val writerThreads = (0 until writers).map { w ->
            Thread {
                start.await()
                repeat(itersPerWriter) { file.safeSave(payloads[(w + it) % payloads.size], createBackup = false, atomic = true) }
            }
        }
        writerThreads.forEach { it.start() }
        start.countDown()
        writerThreads.forEach { it.join() }
        stop.set(true)
        reader.join()

        require(readerErr.get() == null) {
            "a concurrent read observed a torn/unparseable file — atomic write regressed: ${readerErr.get()}"
        }
        require(Yaml().load<Any?>(file.readText()) in payloads.map { Yaml().load<Any?>(it) }) {
            "final file must be one complete payload, not a splice"
        }
    }
}
