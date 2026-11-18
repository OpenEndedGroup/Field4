package afield.runtime.bytecode

import afield.app.protocol.Execute
import afield.app.protocol.LineHeatUpdate
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.createAgentBox
import afield.app.server.executeAllRequest
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.persistence.SavingContext
import afield.runtime.lifecycle.Runloop
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
id: runtime.line-heat.smoke
runner: jvm-main
entrypoint: afield.runtime.bytecode.LineHeatSmokeTest
tags: [smoke, kotlin, k2, runloop, bytecode]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "LineHeatSmokeTest ok"
*/
/**
 * Line heat ([LineHeatPass], [LineHeat]) on real K2 box code under a live [Runloop], driving
 * `execute-all`'s own body:
 *
 *  - **A.** A box run while nothing watches it carries no line-heat sites.
 *  - **B.** Watched, then run twice: the Runloop publisher delivers counts on the box's own lines,
 *    across a `//%-` break (the second fragment is compiled on its own, so its bytecode lines are
 *    fragment-relative and must be mapped back by text): the loop body on box line 6 totals 14, the
 *    lines around it 2, the `//%-` line nothing; the update names the hash of the box's text.
 *  - **C.** The cost on a tight loop, with the watch off and on (printed, not gated).
 */
object LineHeatSmokeTest {

    private val failures = mutableListOf<String>()

    private fun check(label: String, ok: Boolean, detail: String) {
        println("  ${if (ok) "ok  " else "FAIL"} $label: $detail")
        if (!ok) failures += "$label: $detail"
    }

    private var requests = 0

    private class Outcome(val ok: Boolean, val error: String, val stdout: String)

    private fun run(info: SessionInformation, box: Box, code: String): Outcome {
        box.properties["code"] = code
        val verdict = CompletableFuture<JsonObject>()
        val out = StringBuilder()
        val exec = Execute(uid = box.id, type = "code", line = 0, code = code, singleLine = false, requestId = "line-heat-${requests++}")
        executeAllRequest(box, exec, { message: Message ->
            if (message.name == "execution-verdict") verdict.complete(message.payload as JsonObject)
            else synchronized(out) { out.append(message.payload.toString()).append('\n') }
        }, null, info)
        val payload = verdict.get(180, TimeUnit.SECONDS)
        return Outcome(payload["success"]?.jsonPrimitive?.content == "true",
            payload["error"]?.jsonPrimitive?.content.orEmpty(), synchronized(out) { out.toString() })
    }

    /** Latest total per box line from the updates [key] received. */
    private fun totals(updates: List<LineHeatUpdate>): Map<Int, Long> {
        val t = HashMap<Int, Long>()
        for (u in updates) for (l in u.lines) t[l.line] = l.total
        return t
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()
        val info = freshSession()
        val key = Any()
        val updates = java.util.Collections.synchronizedList(ArrayList<LineHeatUpdate>())
        LineHeat.sinks += { k, u -> if (k === key) updates += u }
        try {
            val warm = createAgentBox(info, "warm", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            run(info, warm, "println(\"warm\")")

            val box = createAgentBox(info, "heat", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val code = listOf(
                "val a = 2",                 // 1
                "println(\"a=\" + a)",       // 2
                "//%-",                      // 3
                "var s = 0",                 // 4
                "for (i in 0 until 7) {",    // 5
                "    s += i",                // 6
                "}",                         // 7
                "println(\"s=\" + s)",       // 8
            ).joinToString("\n")

            val a = run(info, box, code)
            check("A unwatched run succeeds", a.ok, a.error.ifBlank { "succeeded" })
            check("A unwatched box has no line-heat sites", LineHeat.sitesOf(box.id).isEmpty(), "${LineHeat.sitesOf(box.id).size} sites")

            LineHeat.watch(key, box.id)
            val b1 = run(info, box, code)
            check("B first watched run succeeds", b1.ok && "s=21" in b1.stdout, b1.error.ifBlank { b1.stdout.lineSequence().filter { "s=" in it }.joinToString(" | ") })
            check("B the run carries sites", LineHeat.sitesOf(box.id).isNotEmpty(), "${LineHeat.sitesOf(box.id).size} sites")
            val first = waitFor(3000) { totals(updates)[6] == 7L }
            check("B counts arrive for box line 6 after one run", first, totals(updates).toString())

            val b2 = run(info, box, code)
            check("B second watched run succeeds", b2.ok, b2.error.ifBlank { "succeeded" })
            waitFor(3000) { totals(updates)[6] == 14L && totals(updates)[8] == 2L }
            val t = totals(updates)
            println("  totals by box line after two runs: ${t.toSortedMap()}")
            check("B loop body (line 6, second fragment) totals 14", t[6] == 14L, t.toString())
            check("B line after the loop (line 8) totals 2", t[8] == 2L, t.toString())
            check("B first fragment's lines total 2", t[1] == 2L && t[2] == 2L, t.toString())
            check("B second fragment's first statement (line 4) totals 2", t[4] == 2L, t.toString())
            check("B the //%- line gets nothing", 3 !in t, t.toString())
            check("B no line outside the box", t.keys.all { it in 1..8 }, t.keys.toString())
            val hashes = synchronized(updates) { updates.map { it.codeHash }.toSet() }
            check("B updates name the hash of the box's text", hashes == setOf(LineHeat.codeHash(code)), "$hashes vs ${LineHeat.codeHash(code)}")
            check("B updates are for the watched box", synchronized(updates) { updates.all { it.uid == box.id && it.property == "code" } }, "uids ${updates.map { it.uid }.toSet()}")

            // C. Cost on a tight loop: the same box, watched and not, best of three each.
            val tight = createAgentBox(info, "tight", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val tightCode = listOf(
                "var s = 0L",
                "val t0 = System.nanoTime()",
                "for (i in 0 until 200_000_000) {",
                "    s += i xor (s ushr 7)",
                "}",
                "println(\"tight ms=\" + (System.nanoTime() - t0) / 1_000_000 + \" s=\" + s)",
            ).joinToString("\n")
            fun best(): Long = (1..3).minOf {
                val r = run(info, tight, tightCode)
                Regex("tight ms=(\\d+)").find(r.stdout)?.groupValues?.get(1)?.toLong() ?: Long.MAX_VALUE
            }
            val tightKey = Any()
            val off = best()
            LineHeat.watch(tightKey, tight.id)
            val on = best()
            LineHeat.unwatch(tightKey)
            val offAgain = best()
            println("  C tight loop of 2e8: heat off $off ms, on $on ms, off again $offAgain ms (x${"%.2f".format(on.toDouble() / maxOf(1, minOf(off, offAgain)))})")
            check("C measured", off != Long.MAX_VALUE && on != Long.MAX_VALUE, "off=$off on=$on")
            check("C switching the watch off removes the probes at the next run", on * 2 <= off * 3 || offAgain < on, "off=$off on=$on offAgain=$offAgain")
        } catch (t: Throwable) {
            failures += "harness threw ${t.javaClass.simpleName}: ${t.message}"
            t.printStackTrace(System.err)
        } finally {
            LineHeat.unwatch(key)
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("LineHeatSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("LineHeatSmokeTest ok")
        exitProcess(0)
    }

    private fun freshSession(): SessionInformation {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-line-heat").toFile()
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
        val t = Thread({ Runloop.runLoop.start() }, "line-heat-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
    }
}
