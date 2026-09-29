package afield.examples.ai

import afield.Box
import afield.OperationTranscript
import afield.SavingContext
import afield.SessionInformation
import afield.ai.core.DocumentToolRegistry
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolRegistry
import afield.ai.core.aiToolMapper
import afield.stdlib.StudyReport
import afield.thisSessionInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/*
FIELD_TEST
id: study.document.registrar
runner: jvm-main
entrypoint: afield.examples.ai.DocumentStudySmokeTest
tags: [smoke, ai, tools, study, regression]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "DocumentStudySmokeTest ok"
*/
/**
 * A document defines a study as capable as a library one, from box code alone.
 *
 * The box's code builds a [afield.stdlib.SourceFramesStudy] of its own `swatch` property — a
 * five-line CPU painter as its frames, the default plain-number knobs — and hands it to
 * `registerStudyTools("Swatch", …)`. That is the whole study. The harness then drives the ten
 * standard verbs the way an agent does, through [ToolRegistry.executeByName] over the document's
 * box-authored tools, with no model: inspect, edit, set a number, observe against the parent, compare,
 * sweep, try two variants (one refused), adopt one, restore, and a stale edit refused. It runs the
 * real box-execution path (a transcript), so the DSL resolves from the box's import prelude.
 */
object DocumentStudySmokeTest {

    // No `$` templates, so the YAML and Kotlin need no escaping.
    private val CODE = """
        val swatch = sourceFramesStudy("swatch",
            studyFrames("CPU swatch painter") { source: String, w: Int, h: Int, t: Double ->
                val c = StudyKnobs.plainNumbers(source).map { it.value.toFloat() }
                val px = FloatArray(w * h * 4)
                for (i in 0 until w * h) {
                    val u = (i % w + 0.5f) / w
                    px[i * 4] = c[0] * u; px[i * 4 + 1] = c[1]; px[i * 4 + 2] = c[2]; px[i * 4 + 3] = 1f
                }
                afield.stacksl.cpu.CpuRaster(w, h, px)
            },
            directory = java.io.File(thisBox.inside.filename, "studies/" + thisBox.id + "/swatch"))
        registerStudyTools("Swatch", swatch, about = "The source is three numbers: red, green and blue, each 0..1; red fades in left to right.")
        println("swatch study ready")
    """.trimIndent()

    private fun transcript(): String = """
name: document_study
description: A box defines a study with the shared study registrar.
operations:
  - create:
      box: swatch_box
      name: Swatch
      properties:
        swatch: "0.8 0.4 0.2"
        code: |
${CODE.trimEnd().prependIndent("          ")}
  - execute:
      box: swatch_box
      property: code
      type: fragment
  - wait:
      millis: 250
""".trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()
        val document = freshSession("document-study")
        val docKey = document.absolutePath
        File(document, "macros").also { it.mkdirs() }.resolve("document_study.yaml").writeText(transcript())

        val failures = mutableListOf<String>()
        fun check(cond: Boolean, message: String) {
            if (!cond) failures.add(message)
            println((if (cond) "ok   " else "FAIL ") + message)
        }

        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "document_study")
            run.output.forEach { println("  [${it.kind}] ${it.uid}: ${it.html.replace(Regex("<[^>]+>"), " ").trim()}") }
            check(run.success, "transcript run succeeded")
            check(run.output.none { it.kind == "error" || it.kind == "err" }, "no error-kind box output")

            val tools = DocumentToolRegistry.toolsFor(docKey)
            val registry = ToolRegistry()
            val ctx = ToolExecutionContext(dynamicTools = tools)
            val names = registry.describe(ctx).map { it.jsonObject["name"]?.jsonPrimitive?.content }
            val expected = listOf("InspectSwatchStudy", "EditSwatchStudy", "PatchSwatchStudy", "SetSwatchNumbers", "ObserveSwatchStudy",
                "CompareSwatchStudy", "SweepSwatchNumber", "TrySwatchVariants", "AdoptSwatchVariant", "RestoreSwatchRevision")
            check(names.containsAll(expected), "the ten standard verbs are offered by name: ${expected.filter { it !in names }.ifEmpty { "all" }}")
            val box = tools.firstOrNull()?.box ?: run { failures += "no tools registered"; report(failures); return }
            fun live() = box.getPropertyRaw("swatch", "") as String
            fun call(name: String, vararg args: Pair<String, Any?>): StudyReport =
                registry.executeByName(ctx, name, aiToolMapper.writeValueAsString(mapOf(*args))) as StudyReport

            val empty = call("InspectSwatchStudy")
            check(empty.revision == "empty", "a new study starts empty")
            val r1 = call("EditSwatchStudy", "expectedRevision" to "empty", "expectedSourceHash" to empty.facts["liveSourceHash"],
                "source" to "0.8 0.4 0.2", "description" to "the starting swatch")
            check(r1.revision.startsWith("r1-") && r1.toolImages.isNotEmpty(), "Edit checks in the first revision, with a preview image")
            val i1 = call("InspectSwatchStudy")
            check((i1.facts["knobs"] as List<*>).size == 3, "Inspect lists the three numbers as knobs")
            val r2 = call("SetSwatchNumbers", "expectedRevision" to r1.revision, "expectedSourceHash" to i1.facts["liveSourceHash"],
                "numbers" to listOf(mapOf("knob" to 1, "value" to 0.9)), "description" to "more green")
            check(live() == "0.8 0.9 0.2", "SetSwatchNumbers rewrote the number in the box: '${live()}'")
            val observed = call("ObserveSwatchStudy", "compareTo" to r1.revision)
            check(observed.toolImages.size == 1 && observed.facts["differences"] != null, "Observe draws the frames with the parent and a difference")
            val compared = call("CompareSwatchStudy", "beforeRevision" to r1.revision)
            check(compared.facts["preservedOutsideRegion"] == false, "Compare says the green change was not preserved")
            val sameGreen = call("CompareSwatchStudy", "beforeRevision" to r2.revision)
            check(sameGreen.facts["preservedOutsideRegion"] == true, "Compare of a revision with itself is preserved")
            val swept = call("SweepSwatchNumber", "knob" to 0, "values" to listOf(0.0, 0.5, 1.0))
            check((swept.facts["values"] as List<*>).size == 3 && swept.facts["published"] == false, "Sweep draws three values and publishes nothing")
            check(live() == "0.8 0.9 0.2", "Sweep left the source alone")
            val tried = call("TrySwatchVariants", "variants" to listOf(mapOf("label" to "A", "source" to "0.1 0.1 0.9", "note" to "blue"),
                mapOf("label" to "B", "source" to "not a swatch")))
            val facts = tried.facts["variants"] as List<*>
            check((facts[0] as Map<*, *>)["ok"] == true && (facts[1] as Map<*, *>)["ok"] == false, "TryVariants holds A and refuses B, which does not draw")
            val i2 = call("InspectSwatchStudy")
            val adopted = call("AdoptSwatchVariant", "expectedRevision" to i2.revision, "expectedSourceHash" to i2.facts["liveSourceHash"], "label" to "A")
            check(live() == "0.1 0.1 0.9" && adopted.revision.startsWith("r3-"), "Adopt publishes the held variant")
            val i3 = call("InspectSwatchStudy")
            call("RestoreSwatchRevision", "expectedRevision" to i3.revision, "expectedSourceHash" to i3.facts["liveSourceHash"], "revision" to r1.revision)
            check(live() == "0.8 0.4 0.2", "Restore makes r1's source current again")
            val stale = runCatching { call("EditSwatchStudy", "expectedRevision" to r1.revision, "expectedSourceHash" to "stale", "source" to "1 1 1", "description" to "x") }
            check(stale.isFailure && live() == "0.8 0.4 0.2", "a stale edit is refused and changes nothing")

            // The same tools are callable from box code, positionally.
            val direct = runBlocking { DocumentToolRegistry.get(docKey, "InspectSwatchStudy")!!("current") } as StudyReport
            check(direct.facts["source"] == "0.8 0.4 0.2", "the Inspect handle is callable directly")
        } catch (t: Throwable) {
            failures.add("harness threw ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }
        report(failures)
    }

    private fun report(failures: List<String>) {
        if (failures.isNotEmpty()) {
            System.err.println("DocumentStudySmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("DocumentStudySmokeTest ok")
        exitProcess(0)
    }

    private fun freshSession(name: String): File {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-$name").toFile()
        workspace.deleteOnExit()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
        val root = Box("root")
        val session = SessionInformation(filename = document.absolutePath, documentName = document.name,
            boxes = ConcurrentHashMap(), root = root, ephemeral = true)
        root.inside = session
        session.boxes[root.id] = root
        thisSessionInfo.set(session)
        return document
    }

    private fun startRunloopDaemon() {
        val t = Thread({ afield.Runloop.runLoop.start() }, "document-study-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
    }
}
