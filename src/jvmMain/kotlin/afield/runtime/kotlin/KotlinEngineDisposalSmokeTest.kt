package afield.runtime.kotlin

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.notation.with

import afield.app.server.SessionInformation
import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.intro
import afield.document.graph.makeKI
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.system.exitProcess

/*
FIELD_TEST
id: kotlin.engine-disposal-vs-compile.smoke
runner: jvm-main
entrypoint: afield.runtime.kotlin.KotlinEngineDisposalSmokeTest
tags: [smoke, kotlin, k2, regression]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "Kotlin engine disposal smoke passed"
*/
/**
 * Disposing a box's [KotlinInterface2] while one of its compiles is queued or running.
 *
 * A document's last websocket leaving retires its boxes, and with them their engines, from a Ktor
 * IO thread, whatever those engines are doing. A backdoor client that drops just after
 * `agent-create-box` replies is enough, and so is a SIGINT, since Ktor's shutdown hook closes every
 * socket. Until 2026-10-04 the box's queued stdlib-import compile then ran against a disposed
 * compiler. Because its project was the last in the JVM, the compiler had also disposed the
 * shared IntelliJ application. The compile died with
 * `ApplicationManager.getApplication() is null` (or `NoClassDefFoundError` from the DataFrame
 * plugin's closed classloader). When it was the JVM's first compile, the NPE was thrown inside
 * `FileSizeLimit`'s static initializer, and no Kotlin box in that JVM compiled again.
 *
 * The test pins `kotlin.environment.keepalive=false`. [KotlinInterface2] sets that property to
 * `true` by default precisely so the application outlives its last engine, and leaving it on
 * would hide the cases below rather than test the engine's own guarantee, which is:
 *
 *  - **A.** An engine disposed before it compiles refuses the compile with a diagnostic that says
 *    so, and never enters the compiler. This is also the JVM's first compile, the shape that used
 *    to poison it.
 *  - **B.** An engine disposed during a compile releases its compiler only after the compile ends.
 *    The compile succeeds, or is refused if the disposal won the race, and is never a compiler
 *    exception.
 *  - **C.** A fresh engine still compiles and evaluates afterwards.
 *  - **D.** A box retired while `box.ki` is still building its engine: [Box.disposeKi] returns
 *    without waiting for the build, and the engine the build returns is disposed, is never stored,
 *    and its stdlib bootstrap would skip. The build is held open just after [makeKI] returns, the
 *    point at which the old `disposeKi`, seeing an uninitialized lazy, skipped the box and the engine
 *    outlived it.
 */
object KotlinEngineDisposalSmokeTest {

    private val failures = mutableListOf<String>()

    private const val REFUSAL = "Kotlin engine was disposed before the code compiled"

    private fun engine(tag: String): KotlinInterface2 = KotlinInterface2(
        implicitReceivers = listOf(KotlinType(Box::class)),
        filePrefix = "DISPOSAL_${tag}_"
    ).also { it.associatedBox = Box("disposal-$tag") }

    private fun describe(result: ResultWithDiagnostics<*>): String = when (result) {
        is ResultWithDiagnostics.Success -> "compiled"
        is ResultWithDiagnostics.Failure -> result.reports.joinToString(" | ") { report ->
            report.message.lineSequence().first() + (report.exception?.let { " [${it::class.simpleName}]" } ?: "")
        }.take(400)
    }

    private fun isRefusal(result: ResultWithDiagnostics<*>) =
        result is ResultWithDiagnostics.Failure && result.reports.any { REFUSAL in it.message } &&
            result.reports.none { it.exception != null }

    private fun check(label: String, ok: Boolean, detail: String) {
        println("  ${if (ok) "ok  " else "FAIL"} $label: $detail")
        if (!ok) failures += "$label: $detail"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("kotlin.environment.keepalive", "false")

        runBlocking {
            // A: disposed first, compiled second.
            val a = engine("A")
            a.dispose()
            val refused = a.compile("val answer = 40 + 2")
            check("A disposed, then compiled", isRefusal(refused), describe(refused))

            // B: disposed while the intro compiles on another thread. Each engine is the only
            // compiler project alive, which is when its disposal used to take the application down.
            repeat(3) { round ->
                val b = engine("B$round")
                val compiling = async(Dispatchers.Default) { b.compile(intro) }
                delay(25)
                b.dispose()
                val result = compiling.await()
                val ok = result is ResultWithDiagnostics.Success || isRefusal(result)
                check("B$round disposed during compile", ok, describe(result))
            }

            // C: the JVM can still compile and run a box.
            val c = engine("C")
            try {
                val box = c.associatedBox as Box
                c.exec(intro, implicits = listOf(box))
                val value = c.exec("40 + 2", implicits = listOf(box))?.toString().orEmpty()
                check("C fresh engine afterwards", "42" in value, value.ifBlank { "no value" })
            } catch (t: Throwable) {
                check("C fresh engine afterwards", false, "${t::class.simpleName}: ${t.message?.lineSequence()?.first()}")
            } finally {
                c.dispose()
            }

            // D: retired mid-build, with a real engine.
            val root = Box("disposal-D-root")
            val session = SessionInformation(filename = "<disposal-D>", documentName = "disposal-D", root = root, ephemeral = true)
            root.inside = session
            val d = Box("disposal-D").also { it.inside = session }
            val built = CountDownLatch(1)
            val finish = CountDownLatch(1)
            var superseded: () -> Boolean = { false }
            d.kiBuilder = { box, reason, isSuperseded ->
                superseded = isSuperseded
                makeKI(box, reason, isSuperseded).also {
                    built.countDown()
                    finish.await(60, TimeUnit.SECONDS)
                }
            }
            val reading = async(Dispatchers.Default) { d.ki }
            withContext(Dispatchers.IO) { built.await(120, TimeUnit.SECONDS) }
            val started = System.nanoTime()
            d.disposeKi()
            val disposeMs = (System.nanoTime() - started) / 1_000_000
            check("D disposeKi did not wait for the build", !reading.isCompleted, "returned in $disposeMs ms with the build still open")
            check("D bootstrap sees the retire", superseded(), "superseded=${superseded()}")
            finish.countDown()
            val orphan = reading.await()
            check("D engine built mid-retire is disposed", orphan.isDisposed, "isDisposed=${orphan.isDisposed}")
            check("D and never stored", d.liveKi == null, "liveKi=${d.liveKi}")
            val late = (orphan as KotlinInterface2).compile("val x = 1")
            check("D its compiles are refused", isRefusal(late), describe(late))
        }

        if (failures.isNotEmpty()) {
            println("Kotlin engine disposal smoke FAILED:")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
        println("Kotlin engine disposal smoke passed")
    }
}
