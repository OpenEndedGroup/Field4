package afield.runtime.bytecode

import afield.app.protocol.Execute
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.createAgentBox
import afield.app.server.executeAllRequest
import afield.app.server.thisSessionInfo
import afield.audio.synth.Signal
import afield.audio.synth.SynthContext
import afield.document.graph.Box
import afield.document.persistence.SavingContext
import afield.runtime.lifecycle.Runloop
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
FIELD_TEST
id: runtime.thread-ownership-guards.smoke
runner: jvm-main
entrypoint: afield.runtime.bytecode.ThreadOwnershipSmokeTest
tags: [smoke, kotlin, k2, runloop, audio, regression]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "ThreadOwnershipSmokeTest ok"
*/
/**
 * Thread-ownership guards under real K2 box code and a live [Runloop] ([ThreadOwnershipPass],
 * [ThreadOwnership]). A stand-in audio thread, `smoke-audio`, owns [owned] the way
 * `FieldAudioEngine`'s thread owns its context (no audio device is opened).
 *
 *  - **G1.** A box whose second `//%-` fragment renders a `SineOsc` with the audio thread's context,
 *    on the Runloop, is reported once, naming box line 5 (the pass's check and the library check in
 *    `Signal.render` both see it).
 *  - **G2.** The same box calls a Runloop-owned function from a thread it starts: reported, line 6.
 *  - **G3.** The audio thread renders the box's oscillator; a second box then renders it with an
 *    offline context of its own, which no pass check can see. The library check reports it, naming
 *    that box's line 3 from the Runloop's stack.
 *  - **G4.** With the guards off, the same code reports nothing.
 */
object ThreadOwnershipSmokeTest {

    /** The context the stand-in audio thread owns; box code renders with it to break the rule. */
    val owned = SynthContext(48_000f, 64)

    /** Where the box leaves its oscillator for the stand-in audio thread to render. */
    @Volatile
    var held: Signal? = null

    /** A Runloop-owned function for box code to call from the wrong thread. */
    @ThreadOwned(ThreadOwner.RUNLOOP)
    fun runloopOnly(): Int = 1

    private val failures = mutableListOf<String>()
    private var requests = 0

    private fun check(label: String, ok: Boolean, detail: String) {
        println("  ${if (ok) "ok  " else "FAIL"} $label: $detail")
        if (!ok) failures += "$label: $detail"
    }

    private class Outcome(val ok: Boolean, val error: String, val stdout: String)

    private fun run(info: SessionInformation, box: Box, code: String): Outcome {
        box.properties["code"] = code
        val verdict = CompletableFuture<JsonObject>()
        val out = StringBuilder()
        val exec = Execute(uid = box.id, type = "code", line = 0, code = code, singleLine = false, requestId = "threads-${requests++}")
        executeAllRequest(box, exec, { message: Message ->
            if (message.name == "execution-verdict") verdict.complete(message.payload as JsonObject)
            else synchronized(out) { out.append(message.payload.toString()).append('\n') }
        }, null, info)
        val payload = verdict.get(180, TimeUnit.SECONDS)
        return Outcome(payload["success"]?.jsonPrimitive?.content == "true", payload["error"]?.jsonPrimitive?.content.orEmpty(), synchronized(out) { out.toString() })
    }

    private val audioWork = LinkedBlockingQueue<() -> Unit>()

    /** Run [block] on the stand-in audio thread and wait for it. */
    private fun onAudio(block: () -> Unit) {
        val done = CompletableFuture<Unit>()
        audioWork.put { runCatching(block).fold({ done.complete(Unit) }, { done.completeExceptionally(it) }) }
        done.get(30, TimeUnit.SECONDS)
    }

    private fun reports(boxId: String) = ThreadOwnership.recentReports().filter { it.box?.origin?.boxId == boxId }

    private fun describe(rs: List<ThreadOwnershipReport>) = rs.joinToString(" | ") { "${it.target} line ${it.box?.line} on ${it.thread} owner ${it.ownerThread}" }

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()
        val audio = Thread({
            owned.claimOwnership(Thread.currentThread())
            while (true) audioWork.take()()
        }, "smoke-audio").apply { isDaemon = true; start() }
        val info = freshSession()
        try {
            check("guards on", ThreadOwnership.enabled, "enabled=${ThreadOwnership.enabled}")
            onAudio { check("audio thread owns its context", owned.ownerThread === audio, "owner=${owned.ownerThread?.name}") }

            val one = createAgentBox(info, "renders", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val oneCode = listOf(
                "val sine = afield.audio.synth.SineOsc()",
                "println(\"built\")",
                "//%-",
                "val buf = FloatArray(64)",
                "sine.render(buf, 64, afield.runtime.bytecode.ThreadOwnershipSmokeTest.owned)",
                "val t = Thread { afield.runtime.bytecode.ThreadOwnershipSmokeTest.runloopOnly() }",
                "t.start(); t.join()",
                "afield.runtime.bytecode.ThreadOwnershipSmokeTest.held = sine",
                "println(\"rendered\")",
            ).joinToString("\n")
            val a = run(info, one, oneCode)
            check("G box runs", a.ok && "rendered" in a.stdout, a.error.ifBlank { "succeeded" })
            val r1 = reports(one.id)
            // Named by the call's owner: `sine` is a SineOsc, so the call is `SineOsc.render`.
            val render = r1.filter { it.target == "SineOsc.render" }
            check("G1 render with the audio thread's context reported once, at box line 5",
                render.size == 1 && render[0].box?.line == 5 && render[0].owner == ThreadOwner.AUDIO && render[0].ownerThread == "'smoke-audio'", describe(r1))
            check("G1 found by the pass (a thread-guard site)", render.firstOrNull()?.site?.kind == "thread-guard", "site=${render.firstOrNull()?.site}")
            val off = r1.filter { it.target.endsWith("runloopOnly") }
            check("G2 Runloop-owned call from another thread reported at box line 6",
                off.size == 1 && off[0].box?.line == 6 && off[0].owner == ThreadOwner.RUNLOOP && off[0].ownerThread.startsWith("'${Runloop.mainThread?.name}'"), describe(r1))

            // G3: the oscillator plays on the audio thread, then box code samples it with its own context.
            onAudio {
                val s = held ?: error("the box left no signal")
                val b = FloatArray(64)
                repeat(3) { owned.advanceCycle(); s.render(b, 64, owned) }
            }
            val two = createAgentBox(info, "samples", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val twoCode = listOf(
                "val s = afield.runtime.bytecode.ThreadOwnershipSmokeTest.held!!",
                "val c = afield.audio.synth.SynthContext(48000f, 64)",
                "s.render(FloatArray(64), 64, c)",
                "println(\"sampled\")",
            ).joinToString("\n")
            val before = ThreadOwnership.libraryViolations
            val b = run(info, two, twoCode)
            check("G3 box runs", b.ok && "sampled" in b.stdout, b.error.ifBlank { "succeeded" })
            val r2 = reports(two.id)
            check("G3 library check reports the sampled oscillator at box line 3",
                r2.size == 1 && r2[0].box?.line == 3 && r2[0].target.startsWith("SineOsc") && r2[0].site == null, describe(r2))
            check("G3 counted as a library violation", ThreadOwnership.libraryViolations > before, "library=${ThreadOwnership.libraryViolations}")

            // G4: off, the same code reports nothing.
            ThreadOwnership.enabled = false
            onAudio { val b2 = FloatArray(64); owned.advanceCycle(); held!!.render(b2, 64, owned) }
            val three = createAgentBox(info, "off", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val c = run(info, three, oneCode)
            val four = createAgentBox(info, "off2", Rect(0.0, 0.0, 100.0, 100.0), emptyMap())
            val d = run(info, four, twoCode)
            check("G4 boxes run with the guards off", c.ok && d.ok, (c.error + d.error).ifBlank { "succeeded" })
            val offReports = reports(three.id) + reports(four.id)
            check("G4 nothing reported with the guards off", offReports.isEmpty(), describe(offReports))
            ThreadOwnership.enabled = true
        } catch (t: Throwable) {
            failures += "harness threw ${t.javaClass.simpleName}: ${t.message}"
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("ThreadOwnershipSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("ThreadOwnershipSmokeTest ok")
        exitProcess(0)
    }

    private fun freshSession(): SessionInformation {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-thread-guards").toFile()
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
        val t = Thread({ Runloop.runLoop.start() }, "thread-guards-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
    }
}
