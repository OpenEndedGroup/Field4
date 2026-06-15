package afield.examples

import afield.Box
import afield.ReturnValueRegistry
import afield.stdlib.JSONLineServer
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket

/*
FIELD_TEST
id: examples.json-line-server.smoke
runner: jvm-main
entrypoint: afield.examples.JSONLineServerSmokeTest
tags: [smoke, examples, json, socket]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "JSONLineServer smoke tests passed"
*/
object JSONLineServerSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "JSONLineServer smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            testCapturesLatestJsonLine()
            testFixedPortConstructionIsIdempotent()
            testAcceptsReconnects()
            testStopsWithBoxLifecycle()
            println("JSONLineServer smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    private fun testCapturesLatestJsonLine() {
        val server = JSONLineServer(0, bindHost = "127.0.0.1")
        try {
            sendLines(
                server.port,
                """{"value": 1}""",
                "not json",
                """{"value": 2, "name": "latest"}"""
            )

            waitFor("latest JSON line") {
                server.output.optInt("value", -1) == 2
            }

            require(server.output.optInt("value", -1) == 2) { "Expected value=2, got ${server.output}" }
            require(server.output.optString("name") == "latest") { "Expected latest object, got ${server.output}" }
            require(server.lineCount == 2L) { "Expected 2 valid JSON lines, got ${server.lineCount}" }
            require(server.invalidLineCount == 1L) { "Expected 1 invalid JSON line, got ${server.invalidLineCount}" }
            require(server.latestLine == """{"value": 2, "name": "latest"}""") { "Unexpected latest line ${server.latestLine}" }
        } finally {
            server.close()
        }
    }

    private fun testFixedPortConstructionIsIdempotent() {
        val port = freePort()
        val first = JSONLineServer(port, bindHost = "127.0.0.1")
        try {
            val second = JSONLineServer(port, bindHost = "127.0.0.1")
            require(first === second) { "Expected fixed-port JSONLineServer constructor to return cached instance" }

            sendLines(port, """{"idempotent": true}""")
            waitFor("cached server output") {
                second.output.optBoolean("idempotent", false)
            }
        } finally {
            first.close()
        }

        val fresh = JSONLineServer(port, bindHost = "127.0.0.1")
        try {
            require(fresh !== first) { "Expected close() to remove cached JSONLineServer instance" }
        } finally {
            fresh.close()
        }
    }

    private fun testAcceptsReconnects() {
        val server = JSONLineServer(0, bindHost = "127.0.0.1")
        try {
            sendLines(server.port, """{"connection": 1}""")
            waitFor("first connection output") {
                server.output.optInt("connection", -1) == 1 && server.connectionCount == 1L
            }

            waitFor("first connection to close") {
                server.activeConnectionCount == 0L && server.latestDisconnectedAtMillis > 0L
            }

            sendLines(server.port, """{"connection": 2}""")
            waitFor("second connection output") {
                server.output.optInt("connection", -1) == 2 && server.connectionCount == 2L
            }

            require(server.output.optInt("connection", -1) == 2) { "Expected reconnected client to update output, got ${server.output}" }
            require(server.latestConnectedAtMillis > 0L) { "Expected connection timestamp to be set" }
            require(server.latestConnectionRemoteAddress != null) { "Expected connection remote address to be set" }
        } finally {
            server.close()
        }
    }

    private fun testStopsWithBoxLifecycle() {
        val box = Box("json_line_server_stop")
        val server = JSONLineServer(0, bindHost = "127.0.0.1", autoStart = false)
        ReturnValueRegistry.launch(box, server, "default") { _, _, _ -> }

        waitFor("server to start") {
            server.running && server.port != 0
        }

        sendLines(server.port, """{"tick": 1}""")
        waitFor("server output") {
            server.output.optInt("tick", -1) == 1
        }

        box.ke.stop(box)

        waitFor("server to stop") {
            !server.running
        }
    }

    private fun freePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun sendLines(port: Int, vararg lines: String) {
        Socket("127.0.0.1", port).use { socket ->
            OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).buffered().use { out ->
                lines.forEach { line ->
                    out.write(line)
                    out.newLine()
                }
                out.flush()
            }
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
