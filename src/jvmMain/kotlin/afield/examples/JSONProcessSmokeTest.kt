package afield.examples

import afield.Box
import afield.ReturnValueRegistry
import afield.stdlib.JSONProcess
import java.nio.file.Files

/*
FIELD_TEST
id: examples.json-process.smoke
runner: jvm-main
entrypoint: afield.examples.JSONProcessSmokeTest
tags: [smoke, examples, process, json]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "JSONProcess smoke tests passed"
*/
object JSONProcessSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "JSONProcess smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            testCapturesLatestJsonLine()
            testStopsWithBoxLifecycle()
            println("JSONProcess smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    private fun testCapturesLatestJsonLine() {
        val script = Files.createTempFile("json-process-latest", ".py")
        Files.writeString(
            script,
            """
            import json, time
            for i in range(3):
                print(json.dumps({"value": i}), flush=True)
                time.sleep(0.05)
            """.trimIndent()
        )

        val box = Box("json_process_latest")
        val process = JSONProcess(null, "python3", script.toAbsolutePath().toString())
        ReturnValueRegistry.launch(box, process, "default") { _, _, _ -> }

        waitFor("latest JSON output") {
            process.output.optInt("value", -1) == 2 && !process.running
        }

        require(process.output.optInt("value", -1) == 2) { "Expected latest JSON line to be value=2, got ${process.output}" }
    }

    private fun testStopsWithBoxLifecycle() {
        val script = Files.createTempFile("json-process-stop", ".py")
        Files.writeString(
            script,
            """
            import json, time
            i = 0
            while True:
                print(json.dumps({"tick": i}), flush=True)
                i += 1
                time.sleep(0.1)
            """.trimIndent()
        )

        val box = Box("json_process_stop")
        val process = JSONProcess(null, "python3", script.toAbsolutePath().toString())
        ReturnValueRegistry.launch(box, process, "default") { _, _, _ -> }

        waitFor("process to start") {
            process.running && process.output.has("tick")
        }

        box.ke.stop(box)

        waitFor("process to stop") {
            !process.running
        }
    }

    private fun waitFor(description: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        error("Timed out waiting for $description")
    }
}
