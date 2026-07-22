package afield.examples

import afield.remotejs.KotlinToJsCompiler
import java.io.File
import java.nio.file.Files

/*
FIELD_TEST
id: remotejs.kotlin-to-js-spike
runner: jvm-main
entrypoint: afield.examples.KotlinToJsSpikeTest
tags: [smoke, remotejs, kotlinjs]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinToJsSpikeTest ok"
*/

/**
 * Proves the Kotlin->JS RemoteJS seam end to end, in-process from a Field JVM:
 *   1. A self-contained Kotlin snippet that uses `operator fun plus` (the thing ASTA exists to fake
 *      in raw JS) compiles to a single plain-module JS string via [KotlinToJsCompiler].
 *   2. A deliberately broken snippet surfaces a structured compile diagnostic (line + message),
 *      i.e. errors are catchable at the Kotlin source before the browser ever runs anything.
 *   3. If `node` is on PATH, the produced JS is actually executed and its output verified — proving
 *      the module is directly eval-able (which is what the head's `global.eval(code)` needs).
 */
object KotlinToJsSpikeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        // --- 1. good snippet: native Kotlin operator overloading -> JS ---
        val good = """
            external val console: dynamic
            data class V(val x: Double, val y: Double) {
                operator fun plus(o: V) = V(x + o.x, y + o.y)
            }
            fun main() {
                val a = V(1.0, 2.0) + V(10.0, 20.0)
                console.log("field-kotlinjs:" + a.x + "," + a.y)
            }
        """.trimIndent()

        val ok = KotlinToJsCompiler.compile(good, moduleName = "spike_ok")
        require(ok.success && ok.js != null) { "expected a successful compile, log:\n${ok.rawLog.takeLast(1200)}" }
        require(ok.diagnostics.none { it.severity == "error" }) { "unexpected errors: ${ok.diagnostics}" }
        val js = ok.js!!
        require(js.isNotBlank() && js.contains("field-kotlinjs")) { "produced JS missing the literal" }
        // plain module self-invokes main and is eval-able
        require(js.contains("mainWrapper()") || js.contains("main(")) { "plain module does not invoke main" }
        println("compiled ${good.length} chars of Kotlin -> ${js.length} chars of JS")

        // --- 2. broken snippet: structured diagnostic at the right line ---
        val bad = """
            fun main() {
                val x: Int = "not an int"
            }
        """.trimIndent()
        val err = KotlinToJsCompiler.compile(bad, moduleName = "spike_bad")
        require(!err.success) { "broken snippet unexpectedly compiled" }
        val errors = err.diagnostics.filter { it.severity == "error" }
        require(errors.isNotEmpty()) { "no compile diagnostic produced, log:\n${err.rawLog.takeLast(1200)}" }
        require(errors.any { it.line >= 1 }) { "diagnostic has no source line: $errors" }
        println("broken snippet -> ${errors.size} diagnostic(s), first: ${errors.first()}")

        // --- 3. optional: actually run the JS if node is available ---
        val node = which("node")
        if (node != null) {
            val tmp = Files.createTempFile("field-ktjs-spike", ".js").toFile()
            try {
                tmp.writeText(js)
                val proc = ProcessBuilder(node, tmp.absolutePath).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                require(proc.exitValue() == 0) { "node run failed: $out" }
                require(out.contains("field-kotlinjs:11,22")) { "unexpected node output: $out" }
                println("node executed the module -> $out")
            } finally {
                tmp.delete()
            }
        } else {
            println("node not on PATH; skipped runtime execution (compile proof still holds)")
        }

        println("KotlinToJsSpikeTest ok")
    }

    private fun which(exe: String): String? =
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .map { File(it, exe) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
}
