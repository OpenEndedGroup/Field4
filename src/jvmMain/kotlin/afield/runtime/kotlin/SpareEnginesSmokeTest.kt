package afield.runtime.kotlin

import afield.app.protocol.Execute
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.createAgentBox
import afield.app.server.executeAllRequest
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.preamble
import afield.document.graph.sparePreludeOf
import kotlinx.coroutines.runBlocking
import afield.document.persistence.SavingContext
import afield.runtime.lifecycle.Runloop
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
FIELD_TEST
id: kotlin.spare-engines.smoke
runner: jvm-main
entrypoint: afield.runtime.kotlin.SpareEnginesSmokeTest
tags: [smoke, kotlin, k2, regression]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "SpareEnginesSmokeTest ok"
*/
/**
 * Running a box takes the engine [SpareEngines] built after its previous run, and the run still
 * starts from a fresh REPL.
 *
 * Drives `execute-all`'s own body ([executeAllRequest]) with a live [Runloop], as Field's agent does:
 *
 *  - **A.** A run declares `kept` and succeeds; afterwards a spare engine is built for the box.
 *  - **B.** The next run is given that spare, and cannot see `kept`: the spare is a fresh engine,
 *    not the previous one kept alive.
 *  - **C.** The previous run's engine is disposed when the spare replaces it.
 *  - **D.** [Box.disposeKi] disposes a waiting spare.
 *  - **E.** A spare built before a jar joined the shared class loader is refused, and disposed.
 *  - **F.** A box whose preamble is imports only gets a spare that has already run it, and its
 *    next run sees those imports.
 *  - **G.** A spare that ran an earlier preamble is refused once the box's preamble changes, and
 *    its imports do not reach the run: they cannot be taken back out of an engine.
 */
object SpareEnginesSmokeTest {

    private val failures = mutableListOf<String>()

    private fun check(label: String, ok: Boolean, detail: String) {
        println("  ${if (ok) "ok  " else "FAIL"} $label: $detail")
        if (!ok) failures += "$label: $detail"
    }

    private var requests = 0

    /** One `execute-all` of [code] in [box]; its verdict's success and error text. */
    private fun run(info: SessionInformation, box: Box, code: String): Pair<Boolean, String> {
        val verdict = CompletableFuture<JsonObject>()
        val exec = Execute(uid = box.id, type = "code", line = 0, code = code, singleLine = false, requestId = "spare-${requests++}")
        executeAllRequest(box, exec, { message: Message ->
            if (message.name == "execution-verdict") verdict.complete(message.payload as JsonObject)
        }, null, info)
        val payload = verdict.get(180, TimeUnit.SECONDS)
        return (payload["success"]?.jsonPrimitive?.content == "true") to (payload["error"]?.jsonPrimitive?.content.orEmpty())
    }

    private fun awaitSpare(box: Box): KotlinScriptEngine? {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            SpareEngines.peek(box)?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()
        val info = freshSession()
        try {
            val box = createAgentBox(info, "spare", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())

            // A
            val (firstOk, firstError) = run(info, box, "val kept = 41\nprintln(kept + 1)")
            check("A first run", firstOk, firstError.ifBlank { "succeeded" })
            val firstEngine = box.ki
            val spare = awaitSpare(box)
            check("A spare built after the run", spare != null && spare !== firstEngine, "spare=$spare")

            // B
            val (secondOk, secondError) = run(info, box, "println(kept)")
            check("B next run uses the spare", box.ki === spare, "ki=${box.ki} spare=$spare")
            check("B and starts from a fresh REPL", !secondOk && "kept" in secondError,
                "success=$secondOk error=${secondError.lineSequence().firstOrNull()}")

            // C
            check("C previous engine disposed", firstEngine.isDisposed, "isDisposed=${firstEngine.isDisposed}")

            // D
            run(info, box, "println(1)")
            val waiting = awaitSpare(box)
            box.disposeKi()
            check("D disposeKi disposes the waiting spare",
                waiting != null && waiting.isDisposed && SpareEngines.peek(box) == null, "spare=$waiting disposed=${waiting?.isDisposed}")

            // E
            run(info, box, "println(2)")
            val stale = awaitSpare(box)
            val jarDir = Files.createTempDirectory("spare-engines-classpath").toFile().also { it.deleteOnExit() }
            val loader = KotlinInterface2.sharedClassLoader as URLClassLoader
            URLClassLoader::class.java.getDeclaredMethod("addURL", java.net.URL::class.java)
                .apply { isAccessible = true }.invoke(loader, jarDir.toURI().toURL())
            val (thirdOk, thirdError) = run(info, box, "println(3)")
            check("E stale spare refused", stale != null && box.ki !== stale && stale.isDisposed,
                "spare=$stale disposed=${stale?.isDisposed} ki=${box.ki}")
            check("E and the run still works", thirdOk, thirdError.ifBlank { "succeeded" })

            // F
            box.preamble = "import java.util.concurrent.TimeUnit.SECONDS"
            run(info, box, "println(1)")
            val prepared = awaitSpare(box)
            val (piOk, piError) = run(info, box, "println(SECONDS)")
            check("F spare with the preamble is used", prepared != null && box.ki === prepared, "spare=$prepared ki=${box.ki}")
            check("F and the run sees its imports", piOk, piError.ifBlank { "succeeded" })

            // G
            val outdated = awaitSpare(box)
            val ranInOutdated = outdated?.let { runBlocking { sparePreludeOf(it) } }
            check("G the spare ran the preamble", ranInOutdated?.contains("SECONDS") == true, "ran=$ranInOutdated")
            box.preamble = "import java.util.concurrent.TimeUnit.MINUTES"
            val (eOk, eError) = run(info, box, "println(MINUTES)")
            check("G spare with an older preamble refused", outdated != null && box.ki !== outdated && outdated.isDisposed,
                "spare=$outdated disposed=${outdated?.isDisposed} ki=${box.ki}")
            check("G and the run sees the new preamble", eOk, eError.ifBlank { "succeeded" })
            val (oldOk, oldError) = run(info, box, "println(SECONDS)")
            check("G and not the old one", !oldOk && "SECONDS" in oldError, "success=$oldOk error=${oldError.lineSequence().firstOrNull()}")
        } catch (t: Throwable) {
            failures += "harness threw ${t.javaClass.simpleName}: ${t.message}"
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("SpareEnginesSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("SpareEnginesSmokeTest ok")
        exitProcess(0)
    }

    private fun freshSession(): SessionInformation {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-spare-engines").toFile()
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
        val t = Thread({ Runloop.runLoop.start() }, "spare-engines-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
    }
}
