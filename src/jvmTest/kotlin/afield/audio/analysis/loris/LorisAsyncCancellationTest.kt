package afield.audio.analysis.loris

import afield.runtime.lifecycle.runAsync
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import kotlin.test.*

class LorisAsyncCancellationTest {
    @Test fun cancellingAnAnalysisTerminatesItsActualNativeChild() {
        if (!LorisNative.isAvailable) {
            println("SKIPPED Loris process cancellation: ${LorisNative.describe()}")
            return
        }
        val cache = Files.createTempDirectory("loris-cancellation").toAbsolutePath()
        val samples = FloatArray(3_000_000) { i -> (0.2 * sin(i * 0.027)).toFloat() }
        var publications = 0
        val operation = runAsync<LorisResult>(onResult = { publications++ }, onError = {}) {
            LorisClient(cache).analyzeAsync(samples, 48000,
                onProgress = progress { _: String -> })
        }
        try {
            var child: ProcessHandle? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (child == null && System.nanoTime() < deadline && !operation.future.isDone) {
                child = ProcessHandle.current().children().use { children ->
                    children.filter { process -> process.info().arguments().orElse(emptyArray())
                        .any { it.contains(cache.toString()) } }.findFirst().orElse(null)
                }
                if (child == null) Thread.sleep(2)
            }
            val worker = assertNotNull(child, "must observe the production Loris worker before cancelling")
            operation.close()
            worker.onExit().get(5, TimeUnit.SECONDS)
            assertFalse(worker.isAlive)
            assertTrue(operation.future.isCancelled)
            assertEquals(0, publications)
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            fun requestFolders() = Files.list(cache).use { paths -> paths.filter { Files.isDirectory(it) }.count() }
            while (requestFolders() > 0 && System.nanoTime() < cleanupDeadline) Thread.sleep(5)
            assertEquals(0, requestFolders(), "request temp files must be removed by the interrupted worker")
        } finally {
            operation.close()
            Files.walk(cache).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
