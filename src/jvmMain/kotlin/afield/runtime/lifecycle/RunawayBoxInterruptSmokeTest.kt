package afield.runtime.lifecycle

import afield.app.protocol.Execute
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.createAgentBox
import afield.app.server.executeAllRequest
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.persistence.SavingContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
FIELD_TEST
id: runtime.runaway-box-interrupt.smoke
runner: jvm-main
entrypoint: afield.runtime.lifecycle.RunawayBoxInterruptSmokeTest
tags: [smoke, kotlin, k2, runloop, regression]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "RunawayBoxInterruptSmokeTest ok"
*/
/**
 * Box code that never returns is thrown out of, and Field carries on ([BoxInterrupt]).
 *
 * Drives `execute-all`'s own body with a live [Runloop], with the watchdog set to interrupt after
 * one second of no frames:
 *
 *  - **A.** `while (true) {}` is interrupted by the watchdog within 2 s, its verdict is a failure that
 *    says so, and the frame counter moves again.
 *  - **B.** A runaway recursion (exponential `fib`, which never overflows the stack) likewise.
 *  - **C.** A `while (true)` that catches `Exception` (and `Thread.sleep`s) is still interrupted.
 *  - **D.** The next, ordinary box runs and prints.
 *  - **E.** With the watchdog's auto-interrupt off, [BoxInterrupt.request] from another thread (what
 *    the "Interrupt Running Box" command does) ends a runaway loop.
 */
object RunawayBoxInterruptSmokeTest {

    private val failures = mutableListOf<String>()

    private fun check(label: String, ok: Boolean, detail: String) {
        println("  ${if (ok) "ok  " else "FAIL"} $label: $detail")
        if (!ok) failures += "$label: $detail"
    }

    private var requests = 0

    private class Outcome(val ok: Boolean, val error: String, val stdout: String, val ms: Long)

    private fun run(info: SessionInformation, box: Box, code: String): Outcome {
        val verdict = CompletableFuture<JsonObject>()
        val out = StringBuilder()
        val exec = Execute(uid = box.id, type = "code", line = 0, code = code, singleLine = false, requestId = "runaway-${requests++}")
        val t0 = System.nanoTime()
        var t1 = 0L
        executeAllRequest(box, exec, { message: Message ->
            if (message.name == "execution-verdict") { t1 = System.nanoTime(); verdict.complete(message.payload as JsonObject) }
            else synchronized(out) { out.append(message.payload.toString()).append('\n') }
        }, null, info)
        val payload = verdict.get(180, TimeUnit.SECONDS)
        return Outcome(payload["success"]?.jsonPrimitive?.content == "true",
            payload["error"]?.jsonPrimitive?.content.orEmpty(), synchronized(out) { out.toString() }, (t1 - t0) / 1_000_000)
    }

    /** The Runloop makes frames again: the counter advances by at least 3 within 5 s. */
    private fun framesMove(): Boolean {
        val f0 = Runloop.runLoop.frame
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (Runloop.runLoop.frame >= f0 + 3) return true
            Thread.sleep(10)
        }
        return false
    }

    @JvmStatic
    fun main(args: Array<String>) {
        BoxInterrupt.Watchdog.reportAfterMs = 500
        BoxInterrupt.Watchdog.interruptAfterMs = 1000
        startRunloopDaemon()
        val info = freshSession()
        var stalls = 0
        BoxInterrupt.Watchdog.onStall += { who, ms, _ -> stalls++; println("  stall reported: $who after $ms ms") }
        try {
            // Warm the engine so compile time is not counted against the 2 s.
            val warm = createAgentBox(info, "warm", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            run(info, warm, "println(\"warm\")")

            val spin = createAgentBox(info, "spin", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val a = run(info, spin, "var i = 0L\nwhile (true) { i++ }")
            check("A while(true) fails", !a.ok, a.error.lineSequence().firstOrNull().orEmpty())
            check("A error says interrupted", "interrupted" in a.error, a.error.take(200))
            check("A within 2 s of the watchdog threshold", a.ms < 1000 + 2000, "${a.ms} ms (threshold 1000 ms)")
            check("A frames move again", framesMove(), "frame=${Runloop.runLoop.frame}")
            check("A stall was reported naming the box", stalls > 0, "stalls=$stalls")

            val rec = createAgentBox(info, "recursion", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val b = run(info, rec, "fun fib(n: Int): Long = if (n < 2) n.toLong() else fib(n - 1) + fib(n - 2)\nprintln(fib(90))")
            check("B runaway recursion fails", !b.ok && "interrupted" in b.error, b.error.take(200))
            check("B within 2 s of the threshold", b.ms < 1000 + 2000, "${b.ms} ms")
            check("B frames move again", framesMove(), "frame=${Runloop.runLoop.frame}")

            val swallow = createAgentBox(info, "swallow", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val c = run(info, swallow, "while (true) { try { Thread.sleep(50) } catch (e: Exception) { } }")
            check("C catch(Exception) does not swallow it", !c.ok && "interrupted" in c.error, c.error.take(200))
            check("C frames move again", framesMove(), "frame=${Runloop.runLoop.frame}")

            val normal = createAgentBox(info, "normal", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val d = run(info, normal, "println(\"after the runaway: \" + (6 * 7))")
            check("D next box succeeds", d.ok, d.error.ifBlank { "succeeded" })
            check("D and prints", "after the runaway: 42" in d.stdout, d.stdout.takeLast(300))

            BoxInterrupt.Watchdog.interruptAfterMs = 0
            val manual = createAgentBox(info, "manual", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val asker = Thread {
                Thread.sleep(1500)
                BoxInterrupt.request("smoke test command")
            }.apply { isDaemon = true; start() }
            val e = run(info, manual, "var x = 0\nwhile (true) { x = x xor 1 }")
            asker.join()
            check("E request() interrupts", !e.ok && "smoke test command" in e.error, e.error.take(200))
            check("E frames move again", framesMove(), "frame=${Runloop.runLoop.frame}")
            check("E no request left pending", !BoxInterrupt.isPending(), "pending=${BoxInterrupt.isPending()}")
        } catch (t: Throwable) {
            failures += "harness threw ${t.javaClass.simpleName}: ${t.message}"
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("RunawayBoxInterruptSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("RunawayBoxInterruptSmokeTest ok")
        exitProcess(0)
    }

    private fun freshSession(): SessionInformation {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-runaway-box").toFile()
        workspace.deleteOnExit()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
        val root = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true,
        )
        root.inside = session
        session.boxes[root.id] = root
        thisSessionInfo.set(session)
        return session
    }

    private fun startRunloopDaemon() {
        val t = Thread({ Runloop.runLoop.start() }, "runaway-box-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
    }
}
