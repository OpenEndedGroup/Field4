package afield.lang.python

import afield.app.protocol.Execute
import afield.app.protocol.Message
import afield.document.graph.Box
import afield.runtime.lifecycle.Runloop
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Without JEP a Python box used to fall through to Kotlin and fail to compile its Python. It now
 * reports one error saying Python is unavailable, and its verdict fails.
 */
class PythonUnavailableExecutionTest {
    @Test
    fun `a Python box without JEP reports why instead of compiling as Kotlin`() {
        val dir = java.nio.file.Files.createTempDirectory("afield-python-unavailable").toFile()
        val root = Box("root")
        val info = afield.app.server.SessionInformation(filename = dir.absolutePath, documentName = dir.name, root = root)
        root.inside = info
        info.boxes[root.id] = root
        info.loading = false
        val box = Box("python-unavailable-probe")
        box.inside = info
        info.boxes[box.id] = box
        val messages = mutableListOf<Message>()
        PythonUnavailableExecution().executeAll(
            box,
            Execute(uid = box.id, type = "run", line = 0, code = "print('hi')", singleLine = false, requestId = "probe"),
        ) { synchronized(messages) { messages += it } }
        val deadline = System.nanoTime() + 10_000_000_000L
        fun seen() = synchronized(messages) { messages.map { it.name to it.payload.toString() } }
        while (System.nanoTime() < deadline && seen().none { it.first == "execution-verdict" }) {
            Runloop.runLoop.main.frame()
            Thread.sleep(10)
        }
        val all = seen()
        val verdict = all.firstOrNull { it.first == "execution-verdict" }
        assertTrue(verdict != null, "no verdict; saw ${all.map { it.first }}")
        assertTrue("Python unavailable" in verdict.second && "\"success\":false" in verdict.second.replace(" ", ""),
            "verdict should fail and name the problem: ${verdict.second}")
        info.cleanup()
        dir.deleteRecursively()
    }
}
