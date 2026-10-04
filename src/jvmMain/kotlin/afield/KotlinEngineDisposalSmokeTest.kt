package afield

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.system.exitProcess

/*
FIELD_TEST
id: kotlin.engine-disposal-vs-compile.smoke
runner: jvm-main
entrypoint: afield.KotlinEngineDisposalSmokeTest
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
        }

        if (failures.isNotEmpty()) {
            println("Kotlin engine disposal smoke FAILED:")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
        println("Kotlin engine disposal smoke passed")
    }
}
