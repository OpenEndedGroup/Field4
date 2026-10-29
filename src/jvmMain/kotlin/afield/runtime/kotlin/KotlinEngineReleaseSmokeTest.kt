package afield.runtime.kotlin

import java.lang.ref.WeakReference
import kotlin.script.experimental.api.ResultValue
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/*
FIELD_TEST
id: kotlin.engine-release.smoke
runner: jvm-main
entrypoint: afield.runtime.kotlin.KotlinEngineReleaseSmokeTest
tags: [smoke, kotlin, k1, k2, regression]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinEngineReleaseSmokeTest ok"
*/
/**
 * A disposed Kotlin engine is garbage: nothing static keeps its compiler alive.
 *
 * A K1 [KotlinInterface]'s compiler project hangs off IntelliJ's static `Disposer` tree, which
 * reaches back to the engine, so until [KotlinInterface.dispose] released it a dropped engine was
 * never collected. [KotlinInterface2] builds one as its completion delegate, and rebuilt it on every
 * Kotlin edit of a launched box: ~60 MB each, an 8 GB heap gone after ~120 edits (2026-10-27).
 *
 *  - **Control.** One dropped without being disposed stays reachable, so check A can fail.
 *  - **A.** A K1 engine that compiled and ran a snippet is collected once disposed.
 *  - **B.** Compiling in a disposed K1 engine fails, rather than building a new compiler project.
 *  - **C.** A K2 engine's K1 completion delegate is collected with it.
 *
 * The K2 engine created first stays alive throughout, so no dispose here is the JVM's last compiler
 * project (whose disposal also takes down the shared IntelliJ application).
 */
object KotlinEngineReleaseSmokeTest {

    private val failures = mutableListOf<String>()

    private fun check(label: String, ok: Boolean, detail: String) {
        println("  ${if (ok) "ok  " else "FAIL"} $label: $detail")
        if (!ok) failures += "$label: $detail"
    }

    /** Whether [ref] is cleared within a few collections. */
    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(20) {
            if (ref.get() == null) return true
            System.gc()
            Thread.sleep(100)
        }
        return ref.get() == null
    }

    private fun KotlinScriptEngine.run(s: String): Any? {
        var failure: String? = null
        val r = execAndWait(s, { failure = "compile $it" }, { failure = "eval $it" }, { failure = "exception $it" }, emptyList())
        check(failure == null) { "`$s` failed: $failure" }
        return (r as? ResultValue.Value)?.value
    }

    /** A K1 engine that compiled and ran a snippet, then was dropped — disposed first when [dispose]. */
    private fun usedK1Engine(dispose: Boolean): WeakReference<KotlinInterface> {
        val k1 = KotlinInterface()
        val value = k1.run("listOf(1, 2, 3).sum()")
        check("K1 engine runs", value == 6, "value=$value")
        if (dispose) {
            k1.dispose()
            check("and reports disposed", k1.isDisposed, "isDisposed=${k1.isDisposed}")
        }
        return WeakReference(k1)
    }

    /** The K1 completion delegate of a K2 engine that was disposed and dropped. */
    private fun delegateOfDisposedK2Engine(): WeakReference<KotlinInterface> {
        val k2 = KotlinInterface2()
        k2.run("val x = 2")
        runBlocking { k2.warmCompletion() }
        val delegate = k2.k1CompletionDelegateForTest()
        check("C K2 engine built a K1 delegate", delegate != null, "delegate=$delegate")
        k2.dispose()
        return WeakReference(delegate)
    }

    // Each engine lives in a function of its own: a local of `main` (or of an inlined block in it)
    // stays on `main`'s frame, and would keep the engine reachable whatever dispose did.
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val keeper = KotlinInterface2()
            keeper.run("val kept = 1")

            // Control: dropped without dispose, the static Disposer tree keeps it, so the check below can fail.
            val control = collected(usedK1Engine(dispose = false))
            check("control: an undisposed K1 engine stays reachable", !control, if (control) "it was collected" else "still reachable")

            // A
            val a = collected(usedK1Engine(dispose = true))
            check("A disposed K1 engine is collected", a, if (a) "collected" else "still reachable")

            // B
            val k1 = KotlinInterface()
            k1.dispose()
            var compileError: String? = null
            k1.execAndWait("1 + 1", { compileError = it.joinToString { d -> d.message } }, {}, {}, emptyList())
            check("B a disposed K1 engine refuses to compile", compileError?.contains("disposed") == true, "error=$compileError")

            // C
            val c = collected(delegateOfDisposedK2Engine())
            check("C K1 delegate is collected with its K2 engine", c, if (c) "collected" else "still reachable")

            keeper.dispose()
        } catch (t: Throwable) {
            failures += "harness threw ${t.javaClass.simpleName}: ${t.message}"
            t.printStackTrace(System.err)
        }

        if (failures.isNotEmpty()) {
            System.err.println("KotlinEngineReleaseSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("KotlinEngineReleaseSmokeTest ok")
        exitProcess(0)
    }
}
