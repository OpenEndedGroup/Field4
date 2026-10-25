package afield.examples

import afield.document.graph.Box
import afield.document.graph.box
import afield.document.persistence.FileWatchManager
import afield.document.persistence.SavingContext
import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: examples.file-watch.sidecar-reedit
runner: jvm-main
entrypoint: afield.examples.SidecarReeditSmokeTest
tags: [smoke, file-watch, sidecar]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "SidecarReeditSmokeTest ok"
*/

/**
 * Regression guard for a second external sidecar edit being lost.
 *
 * Reloading an externally edited sidecar marks it pending, so the next save refreshes the inline
 * copy in `metadata.edn`. Until that save lands, `FileWatchManager` skipped any further event for
 * the sidecar as an unsaved local edit, and the save then wrote the first edit back over the file:
 * observed on 2026-10-18 by writing `println("latency 1")` and then `println("latency 2")` to a
 * box's `_code_.kt` two seconds apart, after which the file held `latency 1` again.
 *
 * The decision is [FileWatchManager.pendingIsLocalEdit]: a pending save yields to the disk only
 * when the property still holds what the watcher itself loaded.
 */
object SidecarReeditSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val workspace = Files.createTempDirectory("afield-sidecar-reedit").toFile()
        val document = File(workspace, "docA").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")

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
            val target = box("greeter", createIfMissing = true, initIfCreated = false, root = session)
            val watcher = FileWatchManager(session)

            // 1. Nothing pending: an event is never held back.
            target.setPropertyRaw("code", "println(\"v0\")", doResync = false)
            require(!watcher.pendingIsLocalEdit("greeter", "code")) { "no pending save, nothing to protect" }

            // 2. The user edited in Field and has not saved: the disk must not win.
            session.markSidecarPending("greeter", "code")
            require(watcher.pendingIsLocalEdit("greeter", "code")) { "an unsaved Field edit must be protected" }

            // 3. The pending save is the watcher's own reload: a second external edit must be read.
            target.setPropertyRaw("code", "println(\"latency 1\")", doResync = false)
            watcher.recordWatcherLoad("greeter", "code", "println(\"latency 1\")")
            session.markSidecarPending("greeter", "code")
            require(!watcher.pendingIsLocalEdit("greeter", "code")) {
                "a pending save caused only by the watcher's reload swallowed a second external edit"
            }

            // 4. After that reload the user types in Field: protected again.
            target.setPropertyRaw("code", "println(\"typed in Field\")", doResync = false)
            require(watcher.pendingIsLocalEdit("greeter", "code")) {
                "a Field edit made after a reload must still be protected"
            }
        } finally {
            thisSessionInfo.remove()
        }

        println("SidecarReeditSmokeTest ok")
    }
}
