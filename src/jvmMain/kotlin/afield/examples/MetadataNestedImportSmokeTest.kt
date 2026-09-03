package afield.examples

import afield.Box
import afield.Rect
import afield.SavingContext
import afield.SessionInformation
import afield.box
import afield.nested
import afield.nestedIn
import afield.server.importMetadataAsNest
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
FIELD_TEST
id: examples.import.metadata-nested
runner: jvm-main
entrypoint: afield.examples.MetadataNestedImportSmokeTest
tags: [smoke, agents, import, metadata, nesting]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "MetadataNestedImportSmokeTest ok"
*/
object MetadataNestedImportSmokeTest {

    @OptIn(ExperimentalUuidApi::class)
    private fun newSession(name: String): SessionInformation {
        val workspace = Files.createTempDirectory("afield-metadata-nested").toFile()
        val document = File(workspace, name).also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
        val root = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true,
        )
        root.inside = session
        session.boxes[root.id] = root
        return session
    }

    @OptIn(ExperimentalUuidApi::class)
    @JvmStatic
    fun main(args: Array<String>) {
        // 1. Build a source document with two top-level boxes and serialize it to
        //    EDN through the real exporter (round-trips the on-disk metadata.edn form).
        val source = newSession("source")
        thisSessionInfo.set(source)
        val ednText = try {
            val a = box(Uuid.random().toString(), createIfMissing = true, initIfCreated = true, root = source)
            a.name = "alpha"
            a.frame = Rect(300.0, 200.0, 180.0, 90.0)
            source.root.connect(a)
            val b = box(Uuid.random().toString(), createIfMissing = true, initIfCreated = true, root = source)
            b.name = "beta"
            b.frame = Rect(520.0, 260.0, 180.0, 90.0)
            source.root.connect(b)
            SavingContext().exportEdnPackage(setOf(a, b), source.filename)
        } finally {
            thisSessionInfo.remove()
        }

        // 2. Import that EDN into a fresh document as a nested subtree.
        val target = newSession("target")
        thisSessionInfo.set(target)
        try {
            val created = importMetadataAsNest(
                ednText = ednText,
                anchorX = 100.0,
                anchorY = 50.0,
                info = target,
            )

            require(created.size == 3) { "Expected 3 boxes (nest parent + 2 imported), got ${created.size}" }

            val nestParent = created[0]
            val imported = created.drop(1)
            require(nestParent.name == "imported boxes") { "Nest parent name wrong: '${nestParent.name}'" }
            require(nestParent.frame.x == 100.0 && nestParent.frame.y == 50.0) {
                "Nest parent should sit at the drop anchor, got ${nestParent.frame}"
            }

            // Fresh UUIDs: none of the imported ids should collide with the source.
            require(imported.none { it.id == "root" }) { "Root must not be re-imported" }
            require(imported.map { it.name }.toSet() == setOf("alpha", "beta")) {
                "Imported names wrong: ${imported.map { it.name }}"
            }

            // Nesting is the nested/nestedIn topology — the client relocates boxes into the nest
            // canvas by nestedIn — AND a connection edge, so the nest parent's preamble and context
            // reach what is inside it. Matches the interactive "-nest-into" action.
            imported.forEach { child ->
                require(child.nestedIn == nestParent) { "Imported box must be nested in the new parent" }
                require(child in nestParent.nested) { "Imported box must be in the parent's nested set" }
                require(nestParent in child.parents) { "Nesting must connect the nest parent to the child" }
                require(child in nestParent.children) { "Nest parent must have the child among its children" }
                // The document-root edge the box arrived with survives alongside it.
                require(target.root in child.parents) { "Nesting must not take away the document-root edge" }
            }

            // nestInto converts world frames to parent-relative coordinates.
            val originX = nestParent.frame.x
            val originY = nestParent.frame.y + nestParent.frame.h
            val alpha = imported.first { it.name == "alpha" }
            require(alpha.frame.x == 300.0 - originX && alpha.frame.y == 200.0 - originY) {
                "Imported frame should be parent-relative, got ${alpha.frame}"
            }

            println("MetadataNestedImportSmokeTest ok")
        } finally {
            thisSessionInfo.remove()
        }
    }
}
