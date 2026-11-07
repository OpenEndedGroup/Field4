@file:afield.runtime.completion.CompletionCategory("Internal")

package afield.examples

import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import kotlin.system.exitProcess

/**
 * Compile the exact source bodies selected by `tools/audit_collection_literals.py`, without
 * evaluating their bodies. Complete programs pass; a fragment needing other declarations retains
 * its compiler diagnostics for review. This report never treats missing context as a pass.
 *
 * Use `field_backdoor.py run-jvm-main afield.examples.CollectionLiteralCompileAudit`. Compilation
 * belongs on this worker's main thread: waiting for a compiler from a live box's Runloop thread
 * prevents the same Runloop from servicing that compiler's setup.
 */
object CollectionLiteralCompileAudit {
    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val mapper = jacksonObjectMapper()
        val paths = args.filter { it != "--gate" }
        val inputs = mapper.readTree(File(paths.getOrNull(0) ?: "build/example-literals/unowned.json"))
        require(inputs.isArray && !inputs.isEmpty) { "The audit needs a nonempty source-body array" }
        val output = File(paths.getOrNull(1) ?: "build/example-literals/compile-results.json")
        output.parentFile.mkdirs()
        DemoTranscriptExecSmokeTest.startRunloopDaemon()
        val results = mutableListOf<Map<String, Any?>>()
        for ((index, row) in inputs.withIndex()) {
            val errors = mutableListOf<String>()
            try {
                DemoTranscriptExecSmokeTest.freshSession("collection-literal-$index")
                val session = requireNotNull(thisSessionInfo.get())
                val target = Box("literal-audit-$index")
                target.inside = session
                target.name = "collection literal audit $index"
                session.boxes[target.id] = target
                session.root.children.add(target)
                target.code = row["code"].asText()
                errors.addAll(DemoTranscriptExecSmokeTest.compileBoxOnly(target))
            } catch (failure: Throwable) {
                errors.add("audit threw ${failure.javaClass.name}: ${failure.message}")
            } finally {
                thisSessionInfo.get()?.cleanup()
                thisSessionInfo.remove()
            }
            results.add(mapOf("id" to row["id"].asText(), "path" to row["path"].asText(),
                "sha256" to row["sha256"].asText(), "compiled" to errors.isEmpty(), "errors" to errors.toList()))
            mapper.writerWithDefaultPrettyPrinter().writeValue(output, results)
            println("COLLECTION BODY ${index + 1}/${inputs.size()}: ${row["id"].asText()} " +
                if (errors.isEmpty()) "PASS" else "NEEDS REVIEW")
        }
        println("COLLECTION AUDIT DONE: ${results.count { it["compiled"] == true }}/${results.size} standalone bodies compile")
        // Report mode retains rejected fragments; successful completion is not a claim that every
        // input compiled. --gate is for a selected set of complete examples, with no exceptions.
        exitProcess(if ("--gate" in args && results.any { it["compiled"] != true }) 1 else 0)
    }
}
