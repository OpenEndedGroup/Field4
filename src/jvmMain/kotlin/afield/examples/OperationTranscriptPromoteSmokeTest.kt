package afield.examples

import afield.Box
import afield.OperationTranscript
import afield.SavingContext
import afield.SessionInformation
import afield.thisSessionInfo
import afield.createWorkspaceProject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: examples.operation-transcript.macro-promote
runner: jvm-main
entrypoint: afield.examples.OperationTranscriptPromoteSmokeTest
tags: [smoke, agents, transcript, macro]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "OperationTranscriptPromoteSmokeTest ok"
*/
object OperationTranscriptPromoteSmokeTest {

    private const val MACRO_YAML = """name: promote_me
operations:
  - create:
      box: promoted_box
      name: Promoted Box
      frame:
        x: 10.0
        y: 20.0
        w: 180.0
        h: 90.0
"""

    @JvmStatic
    fun main(args: Array<String>) {
        // --- Case 1: a document that is not in any project promotes to the workspace. ---
        run {
            val workspace = Files.createTempDirectory("afield-promote-workspace").toFile()
            val document = File(workspace, "loneDoc").also { it.mkdirs() }
            File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
            File(document, "macros").mkdirs()
            File(document, "macros/promote_me.yaml").writeText(MACRO_YAML)

            withSession(document) {
                val result = OperationTranscript.promoteMacro(document.absolutePath, "promote_me", scope = "document")
                require(result.success) { "workspace promote failed: ${result.message}" }
                require(result.scope == "workspace") { "expected workspace scope, got ${result.scope}: ${result.message}" }
            }

            val promoted = File(workspace, "macros/promote_me.yaml")
            require(promoted.isFile) { "expected promoted macro at ${promoted.absolutePath}" }
            require(File(document, "macros/promote_me.yaml").isFile) { "original document macro should remain" }
        }

        // --- Case 2: a document inside a project promotes to that project, then up to the workspace. ---
        run {
            val workspace = Files.createTempDirectory("afield-promote-project").toFile()
            val document = File(workspace, "projDoc").also { it.mkdirs() }
            File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
            File(document, "macros").mkdirs()
            File(document, "macros/promote_me.yaml").writeText(MACRO_YAML)
            createWorkspaceProject(workspace.absolutePath, "ProjA", listOf("projDoc"))

            withSession(document) {
                val toProject = OperationTranscript.promoteMacro(document.absolutePath, "promote_me", scope = "document")
                require(toProject.success) { "project promote failed: ${toProject.message}" }
                require(toProject.scope == "project") { "expected project scope, got ${toProject.scope}: ${toProject.message}" }
                require(toProject.projectName == "ProjA") { "expected project ProjA, got '${toProject.projectName}'" }

                val toWorkspace = OperationTranscript.promoteMacro(document.absolutePath, "promote_me", scope = "project")
                require(toWorkspace.success) { "workspace re-promote failed: ${toWorkspace.message}" }
                require(toWorkspace.scope == "workspace") { "expected workspace scope, got ${toWorkspace.scope}: ${toWorkspace.message}" }

                // Promoting something already at the top scope is a friendly no-op, not a success.
                val again = OperationTranscript.promoteMacro(document.absolutePath, "promote_me", scope = "workspace")
                require(!again.success) { "expected promoting a workspace macro to report already-there" }
            }

            require(File(workspace, "_projects/ProjA/macros/promote_me.yaml").isFile) {
                "expected project-scoped promoted macro"
            }
            require(File(workspace, "macros/promote_me.yaml").isFile) {
                "expected workspace-scoped promoted macro"
            }
        }

        println("OperationTranscriptPromoteSmokeTest ok")
    }

    private inline fun withSession(document: File, block: () -> Unit) {
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
            block()
        } finally {
            thisSessionInfo.remove()
        }
    }
}
