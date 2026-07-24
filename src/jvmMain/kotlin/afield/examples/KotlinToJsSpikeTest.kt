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
        // The default path is in-process; if it silently fell back to a subprocess the env clash is
        // back and we want to know.
        require(ok.mode == KotlinToJsCompiler.Mode.IN_PROCESS) {
            "expected in-process compile, got ${ok.mode}; log:\n${ok.rawLog.takeLast(1200)}"
        }
        val js = ok.js
        require(js.isNotBlank() && js.contains("field-kotlinjs")) { "produced JS missing the literal" }
        // plain module self-invokes main and is eval-able
        require(js.contains("mainWrapper()") || js.contains("main(")) { "plain module does not invoke main" }
        // DCE must tree-shake the stdlib out: a trivial program is a few KB, not ~600 KB.
        require(js.length < 50_000) { "DCE did not shrink output (got ${js.length} chars) — is -Xir-dce set?" }
        println("compiled ${good.length} chars of Kotlin -> ${js.length} chars of JS (mode=${ok.mode}, DCE'd)")

        // --- 1b. implicit main: script-like box code (no `fun main`), external hoisted ---
        val implicit = """
            external val console: dynamic
            data class V(val x: Double, val y: Double) {
                operator fun plus(o: V) = V(x + o.x, y + o.y)
            }
            val a = V(1.0, 2.0) + V(10.0, 20.0)
            console.log("field-implicit-main:" + a.x + "," + a.y)
        """.trimIndent()
        val wrapped = KotlinToJsCompiler.ensureImplicitMain(implicit)
        require(wrapped.contains("fun main()")) { "no synthesized main:\n$wrapped" }
        require(Regex("(?m)^external val console").containsMatchIn(wrapped)) { "external not hoisted to file scope:\n$wrapped" }
        val impl = KotlinToJsCompiler.compile(implicit, moduleName = "spike_implicit")
        require(impl.success && impl.js != null) { "implicit-main compile failed:\n${impl.rawLog.takeLast(1200)}" }
        // a pre-existing `fun main` must be left untouched (no double-wrap)
        require(KotlinToJsCompiler.ensureImplicitMain(good) == good) { "wrapping should be a no-op when fun main is present" }
        println("implicit-main wrapped ${implicit.length} chars → compiled ${impl.js.length} chars JS")

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

                // implicit-main output must run identically
                tmp.writeText(impl.js)
                val p2 = ProcessBuilder(node, tmp.absolutePath).redirectErrorStream(true).start()
                val out2 = p2.inputStream.bufferedReader().readText().trim()
                p2.waitFor()
                require(p2.exitValue() == 0 && out2.contains("field-implicit-main:11,22")) { "implicit-main node run: $out2" }
                println("node executed the implicit-main module -> $out2")
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
