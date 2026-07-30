package afield.examples

import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinToJsCompiler
import java.io.File
import java.nio.file.Files
import afield.util.NodeTool

/*
FIELD_TEST
id: remotejs.kotlinjs-source-preparation
runner: jvm-main
entrypoint: afield.examples.KotlinJsSourcePreparationSmokeTest
tags: [smoke, remotejs, kotlinjs]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsSourcePreparationSmokeTest ok"
*/
object KotlinJsSourcePreparationSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = """
            // {{ remoteAR }}
            data class V(val x: Double, val y: Double) {
                operator fun plus(o: V) = V(x + o.x, y + o.y)
            }

            val a = V(1.0, 2.0)
            val b = a + V(3.0, 4.0)
            var counter = 1
            counter += 1
            print("unrelated initializer")
            print("vectors: " + a + b + " counter=" + counter)
        """.trimIndent()
        val selection = KotlinJsHeadProfiles.resolve(source)
        val printLine = source.lineSequence().indexOfFirst { it.contains("vectors:") } + 1
        val stateKey = "source_prep_state"

        // Whole execution: script declarations are file scoped, both initializers run, and Kotlin's
        // ordinary print resolves to Field's global print hook.
        val wholePrepared = selection.profile.prepareSourceMapped(selection.source, stateKey = stateKey)
        val whole = KotlinToJsCompiler.compile(
            wholePrepared.text,
            moduleName = "source_prep_whole",
            wrapImplicitMain = false,
        ).remapDiagnostics(wholePrepared)
        require(whole.success && whole.js != null) { whole.rawLog.takeLast(2000) }

        // Fragment execution: compile against every declaration in the current box, but run only
        // the selected initializer. This is the regression from the editor screenshot: a, b and
        // the mutated value of counter remain in scope, while unrelated side effects are not replayed.
        val fragmentPrepared = selection.profile.prepareSourceMapped(
            selection.source,
            activeUserLines = printLine..printLine,
            stateKey = stateKey,
        )
        val fragment = KotlinToJsCompiler.compile(
            fragmentPrepared.text,
            moduleName = "source_prep_fragment",
            wrapImplicitMain = false,
        ).remapDiagnostics(fragmentPrepared)
        require(fragment.success && fragment.js != null) { fragment.rawLog.takeLast(2000) }
        val output = runNode(whole.js, fragment.js)
        require(output.lineSequence().count { it == "FIELD_PRINT:${printLine - 1}:unrelated initializer" } == 1) { output }
        require(output.lineSequence().count {
            it == "FIELD_PRINT:$printLine:vectors: V(x=1, y=2)V(x=4, y=6) counter=2"
        } == 2) { output }

        // A compiler error in the selected initializer maps back through synthesized main to the
        // exact one-based editor line.
        val brokenSource = source.replace(
            """print("vectors: " + a + b + " counter=" + counter)""",
            """print("vectors: " + missingName)""",
        )
        val brokenSelection = KotlinJsHeadProfiles.resolve(brokenSource)
        val brokenPrepared = brokenSelection.profile.prepareSourceMapped(
            brokenSelection.source,
            activeUserLines = printLine..printLine,
            stateKey = stateKey,
        )
        val broken = KotlinToJsCompiler.compile(
            brokenPrepared.text,
            moduleName = "source_prep_broken",
            wrapImplicitMain = false,
        ).remapDiagnostics(brokenPrepared)
        require(!broken.success) { "broken fragment unexpectedly compiled" }
        require(broken.diagnostics.any { it.severity == "error" && it.line == printLine }) {
            "expected diagnostic on editor line $printLine, got ${broken.diagnostics}"
        }

        println("KotlinJsSourcePreparationSmokeTest ok")
    }

    private fun runNode(vararg javascript: String): String {
        val node = requireNotNull(NodeTool.resolveNode()) {
            "node is required to verify generated Kotlin/JS execution and retained state: " +
                NodeTool.unavailableMessage()
        }
        val script = Files.createTempFile("field-kotlinjs-source-prep", ".js").toFile()
        return try {
            script.writeText(
                """
                global.print = function (value, line) {
                    console.log("FIELD_PRINT:" + String(line) + ":" + String(value));
                };
                ${javascript.joinToString("\n")}
                """.trimIndent(),
            )
            val process = ProcessBuilder(node, script.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            require(process.exitValue() == 0) { "node failed: $output" }
            output
        } finally {
            script.delete()
        }
    }
}
