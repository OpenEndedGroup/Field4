package afield.examples

import afield.remotejs.Asta
import afield.remotejs.preferAstaLocationForBrowserSyntaxError
import org.json.JSONObject
import org.openjdk.nashorn.api.tree.Diagnostic

/*
FIELD_TEST
id: remotejs.single-syntax-diagnostic
runner: jvm-main
entrypoint: afield.examples.RemoteJSDiagnosticSmokeTest
tags: [smoke, remotejs, diagnostics]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "RemoteJSDiagnosticSmokeTest ok"
*/
object RemoteJSDiagnosticSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val diagnostics = mutableListOf<Diagnostic>()
        Asta().parseAndReconstruct("clearAFr ame()") { diagnostics.add(it) }
        require(diagnostics.isNotEmpty()) { "Malformed identifier did not produce an ASTA diagnostic" }

        val browserSyntaxError = JSONObject()
            .put("payload", JSONObject()
                .put("kind", "ERROR")
                .put("name", "SyntaxError")
                .put("message", "Unexpected identifier 'ame'")
                .put("line", -1)
                .put("column", -1))

        preferAstaLocationForBrowserSyntaxError(browserSyntaxError, diagnostics)
        val enriched = browserSyntaxError.getJSONObject("payload")
        require(enriched.getInt("line") == 1) { "Expected line 1, got $enriched" }
        require(enriched.getInt("column") > 1) { "Expected a useful source column, got $enriched" }
        require(enriched.getString("locationSource") == "asta") { enriched.toString() }
        require(enriched.getString("message").contains("Expected")) { enriched.toString() }

        val runtimeError = JSONObject()
            .put("payload", JSONObject()
                .put("kind", "ERROR")
                .put("name", "ReferenceError")
                .put("message", "missing is not defined")
                .put("line", 12))
        preferAstaLocationForBrowserSyntaxError(runtimeError, diagnostics)
        val untouched = runtimeError.getJSONObject("payload")
        require(untouched.getInt("line") == 12) { "Runtime error location was overwritten: $untouched" }
        require(untouched.getString("message") == "missing is not defined") { untouched.toString() }

        println("RemoteJSDiagnosticSmokeTest ok")
    }
}
