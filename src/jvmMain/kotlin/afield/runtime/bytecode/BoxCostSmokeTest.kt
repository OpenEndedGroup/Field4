package afield.runtime.bytecode

import afield.app.protocol.Execute
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
id: runtime.box-cost.smoke
runner: jvm-main
entrypoint: afield.runtime.bytecode.BoxCostSmokeTest
tags: [smoke, kotlin, k2, runloop, bytecode]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "BoxCostSmokeTest ok"
*/
/**
 * Per-function cost ([BoxCostPass], [BoxCost]) on real K2 box code under a live [Runloop], driving
 * `execute-all`'s own body:
 *
 *  - **A.** A box run while it is not measured carries no cost sites.
 *  - **B.** Measured: the box's own functions are named, on their box lines, with their call counts;
 *    an outer function's total is its self time plus its callees' totals; an exception caught in the
 *    box is reconciled; the run boundary gives the box's run count.
 *  - **C.** An uncaught exception through two box functions: the evaluation boundary closes them.
 *  - **D.** The cost, printed: a tight loop with no calls (one frame a run) and a loop calling a
 *    small box function, each measured and not.
 */
object BoxCostSmokeTest {

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
        val exec = Execute(uid = box.id, type = "code", line = 0, code = code, singleLine = false, requestId = "box-cost-${requests++}")
        executeAllRequest(box, exec, { message: Message ->
            if (message.name == "execution-verdict") verdict.complete(message.payload as JsonObject)
            else synchronized(out) { out.append(message.payload.toString()).append('\n') }
        }, null, info)
        val payload = verdict.get(180, TimeUnit.SECONDS)
        return Outcome(payload["success"]?.jsonPrimitive?.content == "true",
            payload["error"]?.jsonPrimitive?.content.orEmpty(), synchronized(out) { out.toString() })
    }

    private fun sitesOf(uid: String): List<Site> =
        (0 until SnippetSites.size).mapNotNull { SnippetSites[it] }.filter { it.kind == BoxCostPass.KIND && it.origin.boxId == uid }

    private fun costs(uid: String): Map<String, BoxCost.FunctionCost> =
        BoxCost.functions { it == uid }.associateBy { it.function }

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()
        val info = freshSession()
        try {
            val warm = createAgentBox(info, "warm", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            run(info, warm, "println(\"warm\")")

            val box = createAgentBox(info, "cost", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val code = listOf(
                "fun inner(n: Int): Long {",                                                // 1
                "    var s = 0L",                                                           // 2
                "    for (i in 0 until n) s += i.toLong() * i",                             // 3
                "    return s",                                                             // 4
                "}",                                                                        // 5
                "fun outer(n: Int): Long = inner(n) + inner(n / 2)",                        // 6
                "fun fails(): Int = error(\"boom\")",                                        // 7
                "fun guarded(): Int = try { fails() } catch (e: IllegalStateException) { 3 }", // 8
                "println(\"r=\" + outer(1000) + \" g=\" + guarded())",                      // 9
            ).joinToString("\n")

            val a = run(info, box, code)
            check("A unmeasured run succeeds", a.ok, a.error.ifBlank { "succeeded" })
            check("A unmeasured box has no cost sites", sitesOf(box.id).isEmpty(), "${sitesOf(box.id).size} sites")

            BoxCost.measure(box.id)
            val b = run(info, box, code + "\n// measured")
            check("B measured run succeeds", b.ok && "g=3" in b.stdout, b.error.ifBlank { b.stdout.lineSequence().filter { "r=" in it }.joinToString(" | ") })
            val sites = sitesOf(box.id)
            println("  sites: ${sites.map { "${it.className.substringAfterLast('/')}.${it.methodName} -> ${it.detail} line ${it.line}" }}")
            val c = costs(box.id)
            println("  functions: ${c.values.joinToString("; ")}")
            check("B inner: 2 calls on box line 2", c["inner"]?.calls == 2L && c["inner"]?.line == 2, c["inner"].toString())
            check("B outer: 1 call on box line 6", c["outer"]?.calls == 1L && c["outer"]?.line == 6, c["outer"].toString())
            check("B fails and guarded: 1 call each", c["fails"]?.calls == 1L && c["guarded"]?.calls == 1L, "${c["fails"]} / ${c["guarded"]}")
            // The engine's prelude snippets, evaluated for the box, are not its code and carry no sites.
            check("B one box body, run once", c["box body"]?.calls == 1L, c["box body"].toString())
            check("B only the box's own snippet has sites", sites.map { it.className }.toSet().size == 1, sites.map { it.className }.toSet().toString())
            val o = c["outer"]; val i = c["inner"]
            check("B outer total = outer self + inner total", o != null && i != null && o.totalNs == o.selfNs + i.totalNs, "$o / $i")
            check("B nothing is left running", c.values.none { it.running }, c.values.filter { it.running }.toString())
            val (runs, ns) = BoxCost.boxTotal(box.id)
            check("B the run boundary counted the run", runs >= 1 && ns > 0, "runs=$runs ns=$ns")
            val snap = BoxCost.snapshot(nameOf = { if (it == box.id) "cost" else null })
            check("B the snapshot lists the box and its functions", snap.boxes.any { it.boxId == box.id && it.measuring } &&
                snap.functions.any { it.boxId == box.id && it.function == "inner" && it.line == 2 }, snap.toString().take(400))

            // C. An exception out of two box functions and out of the box: the evaluation boundary closes them.
            val thrower = createAgentBox(info, "thrower", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            BoxCost.measure(thrower.id)
            val tc = run(info, thrower, listOf(
                "fun deeper(): Int = error(\"uncaught\")",
                "fun shallow(): Int = deeper() + 1",
                "println(shallow())",
            ).joinToString("\n"))
            check("C the run fails", !tc.ok, tc.error.take(120))
            val ct = costs(thrower.id)
            println("  functions: ${ct.values.joinToString("; ")}")
            check("C deeper and shallow were charged once each", ct["deeper"]?.calls == 1L && ct["shallow"]?.calls == 1L, ct.toString())
            check("C nothing is left running after the boundary", ct.values.none { it.running }, ct.values.filter { it.running }.toString())

            // D. Cost: best of three, measured and not, on separate boxes so each compiles its own snippet.
            fun best(name: String, measured: Boolean, body: String): Long {
                val bx = createAgentBox(info, name, Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
                if (measured) BoxCost.measure(bx.id)
                return (1..3).minOf {
                    val r = run(info, bx, "// $name\n$body")
                    Regex("ms=(\\d+)").find(r.stdout)?.groupValues?.get(1)?.toLong() ?: Long.MAX_VALUE
                }
            }
            val tight = listOf(
                "var s = 0L",
                "val t0 = System.nanoTime()",
                "for (i in 0 until 200_000_000) {",
                "    s += i.toLong() xor (s ushr 7)",
                "}",
                "println(\"tight ms=\" + (System.nanoTime() - t0) / 1_000_000 + \" s=\" + s)",
            ).joinToString("\n")
            val calls = listOf(
                "fun step(i: Int): Int = Math.floorMod(i * 31, 1009)",
                "var s = 0L",
                "val t0 = System.nanoTime()",
                "for (i in 0 until 20_000_000) s += step(i)",
                "println(\"calls ms=\" + (System.nanoTime() - t0) / 1_000_000 + \" s=\" + s)",
            ).joinToString("\n")
            val tightOff = best("tight-off", false, tight)
            val tightOn = best("tight-on", true, tight)
            val callsOff = best("calls-off", false, calls)
            val callsOn = best("calls-on", true, calls)
            println("  D tight loop of 2e8 (no calls): off $tightOff ms, measured $tightOn ms")
            println("  D 2e7 calls of a small box function: off $callsOff ms, measured $callsOn ms " +
                "(%.1f ns per measured call)".format((callsOn - callsOff) * 1e6 / 2e7))
            check("D measured", listOf(tightOff, tightOn, callsOff, callsOn).none { it == Long.MAX_VALUE }, "$tightOff $tightOn $callsOff $callsOn")
        } catch (t: Throwable) {
            failures += "harness threw ${t.javaClass.simpleName}: ${t.message}"
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("BoxCostSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("BoxCostSmokeTest ok")
        exitProcess(0)
    }

    private fun freshSession(): SessionInformation {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-box-cost").toFile()
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
        val t = Thread({ Runloop.runLoop.start() }, "box-cost-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
    }
}
