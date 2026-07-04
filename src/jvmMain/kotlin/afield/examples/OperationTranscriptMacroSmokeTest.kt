package afield.examples

import afield.Box
import afield.OperationTranscript
import afield.Rect
import afield.SavingContext
import afield.SessionInformation
import afield.box
import afield.isTrue
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: examples.operation-transcript.macro-create-run-undo
runner: jvm-main
entrypoint: afield.examples.OperationTranscriptMacroSmokeTest
tags: [smoke, agents, transcript, macro]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "OperationTranscriptMacroSmokeTest ok"
*/
object OperationTranscriptMacroSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val workspace = Files.createTempDirectory("afield-operation-transcript").toFile()
        val document = File(workspace, "docA").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
        File(document, "macros").mkdirs()
        File(document, "macros/agent_macro_smoke.yaml").writeText(
            """
            name: agent_macro_smoke
            operations:
              - create:
                  box: transcript_parent
                  name: Transcript Parent
                  frame:
                    x: 10.0
                    y: 20.0
                    w: 180.0
                    h: 90.0
                  properties:
                    code: println("parent")
              - create:
                  box: transcript_child
                  name: Transcript Child
                  frame:
                    x: 220.0
                    y: 20.0
                    w: 180.0
                    h: 90.0
              - connect:
                  parent: transcript_parent
                  child: transcript_child
              - edit:
                  box: transcript_child
                  property: code
                  from: ""
                  to: println("child")
              - move:
                  box: transcript_child
                  to:
                    x: 240.0
                    y: 60.0
                    w: 200.0
                    h: 100.0
              - wait:
                  millis: 1
            """.trimIndent() + "\n"
        )

        val root = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true
        )
        root.inside = session
        session.boxes[root.id] = root

        thisSessionInfo.set(session)
        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "agent_macro_smoke")
            require(run.success) { run.errors.joinToString("; ").ifBlank { run.message } }
            require(run.applied == 6) { "Expected 6 applied operations, got ${run.applied}: ${run.message}" }

            val parent = box("transcript_parent", createIfMissing = false)
            val child = box("transcript_child", createIfMissing = false)
            require(parent.name == "Transcript Parent") { parent.name }
            require(child.code == """println("child")""") { child.code }
            require(child in parent.children && parent in child.parents) { "Expected parent/child connection" }
            require(child.frame == Rect(240.0, 60.0, 200.0, 100.0)) { "Unexpected child frame: ${child.frame}" }

            val undo = OperationTranscript.runMacro(document.absolutePath, "agent_macro_smoke", direction = "undo")
            require(undo.success) { undo.errors.joinToString("; ").ifBlank { undo.message } }
            require(parent.getPropertyRaw("_tombstone").isTrue()) { "Parent should be tombstoned after undo" }
            require(child.getPropertyRaw("_tombstone").isTrue()) { "Child should be tombstoned after undo" }
        } finally {
            thisSessionInfo.remove()
        }

        println("OperationTranscriptMacroSmokeTest ok")
    }
}
