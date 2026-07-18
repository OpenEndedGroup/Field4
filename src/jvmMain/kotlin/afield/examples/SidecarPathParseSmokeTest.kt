package afield.examples

import afield.Box
import afield.FileWatchManager
import afield.SavingContext
import afield.SessionInformation
import afield.box
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: examples.file-watch.sidecar-path-parse
runner: jvm-main
entrypoint: afield.examples.SidecarPathParseSmokeTest
tags: [smoke, file-watch, sidecar]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "SidecarPathParseSmokeTest ok"
*/

/**
 * Regression guard for the "dragging a macro spawns a spurious phantom box" bug.
 *
 * A box whose id contains underscores (e.g. `fn_fit_demo`, created by the
 * fn_differentiable_fit_demo transcript) saves its `code` property to the sidecar file
 * `fn_fit_demo_code_.kt`. FileWatchManager.parseSidecarPath used to split on the FIRST
 * underscore, mis-parsing that into box id "fn" / property "fit_demo_code". Because box "fn"
 * did not exist, the orphan scanner auto-created a phantom box named "Fit demo code".
 *
 * The parse must instead recover box id "fn_fit_demo" / property "code" so the existing box
 * owns the file and no phantom is created.
 */
object SidecarPathParseSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val workspace = Files.createTempDirectory("afield-sidecar-parse").toFile()
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
            // The box the transcript actually defines.
            box("fn_fit_demo", createIfMissing = true, initIfCreated = false, root = session)

            val watcher = FileWatchManager(session)

            fun parse(name: String) = watcher.parseSidecarPath(File(document, name))

            // 1. The exact regression: underscore-bearing box id resolves to the real box, not "fn".
            val fitDemo = parse("fn_fit_demo_code_.kt")
            require(fitDemo != null) { "expected fn_fit_demo_code_.kt to parse as a sidecar" }
            require(fitDemo.boxId == "fn_fit_demo") {
                "expected box id 'fn_fit_demo', got '${fitDemo.boxId}' (property '${fitDemo.propertyName}')"
            }
            require(fitDemo.propertyName == "code") { "expected property 'code', got '${fitDemo.propertyName}'" }

            // 2. Underscore-bearing property name (remote_js) resolves correctly for a not-yet-created box.
            val remote = parse("some_box_remote_js_.js")
            require(remote != null && remote.boxId == "some_box" && remote.propertyName == "remote_js") {
                "expected some_box/remote_js, got $remote"
            }

            // 3. Underscores on BOTH sides at once — the hardest case for the old first-underscore split.
            val both = parse("my_cool_box_remote_js_.js")
            require(both != null && both.boxId == "my_cool_box" && both.propertyName == "remote_js") {
                "expected my_cool_box/remote_js, got $both"
            }

            // 4. Longest known property suffix wins (vertex_fragment over fragment) when no box matches.
            val longest = parse("mybox_vertex_fragment_.slang")
            require(longest != null && longest.boxId == "mybox" && longest.propertyName == "vertex_fragment") {
                "expected mybox/vertex_fragment, got $longest"
            }

            // 5. Fallback: a name matching no known property keeps the legacy first-underscore split.
            val loose = parse("foo_stuff_.md")
            require(loose != null && loose.boxId == "foo" && loose.propertyName == "stuff") {
                "expected foo/stuff fallback, got $loose"
            }
        } finally {
            thisSessionInfo.remove()
        }

        println("SidecarPathParseSmokeTest ok")
    }
}
