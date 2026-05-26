package afield.examples

import afield.Box
import afield.HistoryManager
import afield.SavingContext
import afield.SessionInformation
import afield.thisSessionInfo
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
FIELD_TEST
id: examples.history.file-inline
runner: jvm-main
entrypoint: afield.examples.HistoryFileInlineSmokeTest
tags: [smoke, history, persistence]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "History file-inline smoke tests passed"
*/
object HistoryFileInlineSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val documentDir = Files.createTempDirectory("afield-history-inline")
        val backupsDir = documentDir.resolve("backups").createDirectories()

        documentDir.resolve("box_code_.kt").writeText("""print("current")""")
        documentDir.resolve("empty_code_.kt").writeText("""print("stale")""")
        backupsDir.resolve("metadata_backup_00000.edn").writeText(
            metadata(boxCode = """print(\"old\")""", emptyCode = null)
        )
        documentDir.resolve("metadata.edn").writeText(
            metadata(boxCode = """print(\"metadata-current\")""", emptyCode = """print(\"stale\")""")
        )

        val savingContext = SavingContext()
        val rawCode = savingContext
            .readEdnTransfersFromString(backupsDir.resolve("metadata_backup_00000.edn").toFile().readText())
            .first { it.id == "box" }
            .properties["code"]

        require(savingContext.resolveStoredValue(rawCode, documentDir.toString()) == """print("current")""") {
            "Live resolution should prefer the current sidecar"
        }
        require(
            savingContext.resolveStoredValue(
                rawCode,
                documentDir.toString(),
                preferInlineFiles = true
            ) == """print("old")"""
        ) {
            "Backup resolution should prefer the inline snapshot"
        }

        val root = Box("root")
        val session = SessionInformation(
            filename = documentDir.toString(),
            documentName = "history-inline-test",
            root = root,
            ephemeral = true
        )
        root.inside = session
        thisSessionInfo.set(session)
        try {
            val resolvedWithBoxes = savingContext.resolveStoredValueWithBoxes(
                rawCode,
                documentDir.toString(),
                root = session,
                preferInlineFiles = true
            )
            require(resolvedWithBoxes == """print("old")""") {
                "Restore-style resolution should prefer the inline snapshot"
            }
        } finally {
            thisSessionInfo.remove()
        }

        HistoryManager.clearCache(documentDir.toString())
        val versions = HistoryManager.listVersions(documentDir.toString())
        require(versions.size == 2) { "Expected backup plus current metadata, got ${versions.size}" }

        val diff = HistoryManager.computeDiffStats(documentDir.toString(), versions)[1]
            ?: error("Expected diff stats for current version")
        require(diff.totalLinesAdded >= 2 && diff.totalLinesRemoved >= 2) {
            "Expected file-inline code changes in history diff, got $diff"
        }
        require(diff.changedCodeBoxes == listOf("sample", "empty")) {
            "Expected changed code box names in history diff, got ${diff.changedCodeBoxes}"
        }

        println("History file-inline smoke tests passed")
    }

    private fun metadata(boxCode: String, emptyCode: String?): String {
        val emptyCodeProperty = if (emptyCode == null) {
            ""
        } else {
            """ "code" #afield/file-inline {"path" "empty_code_.kt" "inline" "$emptyCode"}"""
        }

        return """
            {"version" 2
             "boxes" [
               {"id" "box"
                "properties" {"children" #{}
                              "parents" #{#afield/box "root"}
                              "name" "sample"
                              "code" #afield/file-inline {"path" "box_code_.kt" "inline" "$boxCode"}}}
               {"id" "empty"
                "properties" {"children" #{}
                              "parents" #{#afield/box "root"}
                              "name" "empty"$emptyCodeProperty}}
               {"id" "root"
                "properties" {"children" #{#afield/box "box" #afield/box "empty"}
                              "parents" #{}
                              "name" "root"}}
             ]
             "persistentProperties" ["name" "code" "children" "parents" "_tombstone" ".*_editorState"]}
        """.trimIndent()
    }
}
