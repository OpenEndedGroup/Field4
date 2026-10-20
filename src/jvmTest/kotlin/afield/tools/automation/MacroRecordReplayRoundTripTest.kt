package afield.tools.automation

import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.isTrue
import afield.document.graph.needsResync
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * The macro recorder round trip: what a person does on the canvas, recorded between Start and
 * Save, replays into a fresh document as the same document. Each "canvas action" below does what
 * the server's handler for it does — apply the change, then tell [OperationTranscript] — in the
 * order the frontend sends them (`createBox` sends the name, then the frame, then `newBox` sends
 * the code; `g` sends `-connect`; delete sends `-delete-box`).
 *
 * Before 2026-10-20 the recorder had no `create`, `delete`, `connect` or `disconnect`, so the
 * recording of this session replayed into a fresh document as edits of boxes that did not exist,
 * and the fresh document stayed empty.
 */
class MacroRecordReplayRoundTripTest {

    private val opened = mutableListOf<Pair<SessionInformation, File>>()

    private fun document(name: String): SessionInformation {
        val directory = Files.createTempDirectory("macro-roundtrip-$name").toFile()
        val documentDir = File(directory, name).also { it.mkdirs() }
        val root = Box("root")
        val session = SessionInformation(documentDir.path, documentName = name, root = root)
        root.inside = session
        opened += session to directory
        return session
    }

    @AfterTest
    fun cleanUp() {
        thisSessionInfo.remove()
        opened.forEach { (session, directory) ->
            needsResync.removeAll { it.first.isInsideInitialized() && it.first.inside === session }
            directory.deleteRecursively()
        }
    }

    // --- canvas actions, as the handlers perform and record them ---

    private fun canvasCreate(session: SessionInformation, uid: String, name: String, frame: Rect, code: String? = null): Box {
        val created = box(uid, createIfMissing = true, initIfCreated = false, root = session)
        created.name = name
        OperationTranscript.recordCreate(created) // set-string "name" on an unknown uid
        val before = created.frame
        created.frame = frame
        OperationTranscript.recordMove(created, before, created.frame) // set-rect "frame"
        if (code != null) {
            created.setPropertyRaw("code", code, doResync = false)
            OperationTranscript.recordEdit(created, "code", "", code) // set-string "code"
        }
        return created
    }

    private fun canvasMove(target: Box, to: Rect) {
        val before = target.frame
        target.frame = to
        OperationTranscript.recordMove(target, before, to)
    }

    private fun canvasEdit(target: Box, property: String, to: String) {
        val before = target.getPropertyRaw(property)?.toString().orEmpty()
        target.setPropertyRaw(property, to, doResync = false)
        OperationTranscript.recordEdit(target, property, before, to)
    }

    private fun canvasConnect(parent: Box, child: Box) {
        parent.connect(child)
        OperationTranscript.recordConnect(parent, child, connected = true)
    }

    private fun canvasDelete(session: SessionInformation, target: Box) {
        OperationTranscript.recordDelete(target)
        target.setPropertyRaw("_tombstone", true, doResync = false)
        session.root.disconnect(target)
    }

    /** The document as a person sees it: every live box's name, frame, code and children. */
    private fun projection(session: SessionInformation): Map<String, String> =
        session.boxes.values
            .filter { it.id != "root" && !it.getPropertyRaw("_tombstone").isTrue() }
            .associate { b ->
                b.id to listOf(
                    "name=${b.name}",
                    "frame=${b.frame.x},${b.frame.y},${b.frame.w},${b.frame.h}",
                    "code=${b.getPropertyRaw("code") ?: ""}",
                    "children=${b.children.map { it.id }.sorted()}",
                    "underRoot=${b in session.root.children}",
                ).joinToString(" ")
            }
            .toSortedMap()

    private fun recordSession(): Pair<SessionInformation, String> {
        val source = document("recorded")
        thisSessionInfo.set(source)
        assertTrue(OperationTranscript.markStart("round-trip").success)
        val a = canvasCreate(source, "box-a", "Source", Rect(100.0, 100.0, 160.0, 80.0), code = "println(\"a\")")
        val b = canvasCreate(source, "box-b", "Sink", Rect(320.0, 100.0, 160.0, 80.0))
        val c = canvasCreate(source, "box-c", "Scratch", Rect(100.0, 260.0, 120.0, 60.0))
        canvasMove(b, Rect(340.0, 140.0, 160.0, 80.0))
        canvasEdit(b, "code", "println(\"b\")")
        canvasEdit(a, "name", "Source two")
        canvasConnect(a, b)
        canvasDelete(source, c)
        val saved = OperationTranscript.markEndAndSave("round-trip")
        assertTrue(saved.success, saved.message)
        return source to File(saved.path).readText()
    }

    @Test
    fun aRecordingReplaysIntoAFreshDocumentAsTheSameDocument() {
        val (source, yaml) = recordSession()
        val expected = projection(source)
        assertEquals(setOf("box-a", "box-b"), expected.keys)

        val fresh = document("replayed")
        thisSessionInfo.set(fresh)
        assertTrue(OperationTranscript.writeMacro(fresh.filename, "round-trip", yaml).success)
        val run = OperationTranscript.runMacro(fresh.filename, "round-trip", initializeCreatedBoxes = false)
        assertTrue(run.success, run.message + " " + run.errors)
        assertEquals(expected, projection(fresh), "replayed document\n$yaml")
    }

    @Test
    fun aRecordingReadsAsSemanticOperations() {
        val (_, yaml) = recordSession()
        val verbs = Regex("""^  - ([a-z-]+):""", RegexOption.MULTILINE).findAll(yaml).map { it.groupValues[1] }.toList()
        assertEquals(listOf("create", "create", "create", "move", "edit", "edit", "connect", "delete"), verbs.filter { it != "wait" }, yaml)
        // A new box's frame and first code are part of its create, not a move / edit from nothing.
        val firstCreate = yaml.substringAfter("  - create:").substringBefore("  - create:")
        assertTrue(firstCreate.contains("frame:") && firstCreate.contains("properties:") && firstCreate.contains("println(\"a\")"), firstCreate)
    }

    @Test
    fun undoingAReplayLeavesTheFreshDocumentEmpty() {
        val (_, yaml) = recordSession()
        val fresh = document("undone")
        thisSessionInfo.set(fresh)
        OperationTranscript.writeMacro(fresh.filename, "round-trip", yaml)
        assertTrue(OperationTranscript.runMacro(fresh.filename, "round-trip", initializeCreatedBoxes = false).success)
        OperationTranscript.runMacro(fresh.filename, "round-trip", direction = "undo", initializeCreatedBoxes = false)
        assertEquals(emptyMap(), projection(fresh))
    }

    @Test
    fun replayingAsACopyMakesNewBoxesAtAnOffset() {
        val (source, yaml) = recordSession()
        val original = projection(source)
        thisSessionInfo.set(source)
        OperationTranscript.writeMacro(source.filename, "round-trip", yaml)
        val run = OperationTranscript.runMacro(
            source.filename, "round-trip", initializeCreatedBoxes = false,
            replay = OperationTranscript.MacroReplayOptions(asCopy = true, offsetX = 0.0, offsetY = 400.0),
        )
        assertTrue(run.success, run.message + " " + run.errors)
        val after = projection(source)
        assertEquals(4, after.size, "two originals, two copies: $after")
        original.forEach { (uid, line) -> assertEquals(line, after[uid], "the originals are untouched") }
        val copies = source.boxes.values.filter { it.id !in original.keys && it.id != "root" && !it.getPropertyRaw("_tombstone").isTrue() }
        val sink = copies.single { it.name == "Sink" }
        assertEquals(Rect(340.0, 540.0, 160.0, 80.0), sink.frame)
        val sourceCopy = copies.single { it.name == "Source two" }
        assertEquals(listOf(sink.id), sourceCopy.children.map { it.id }, "the copy's connection joins the copies")
    }

    @Test
    fun steppingReplaysOneOperationAtATime() {
        val (_, yaml) = recordSession()
        val fresh = document("stepped")
        thisSessionInfo.set(fresh)
        OperationTranscript.writeMacro(fresh.filename, "round-trip", yaml)
        val step = OperationTranscript.runMacro(
            fresh.filename, "round-trip", initializeCreatedBoxes = false,
            replay = OperationTranscript.MacroReplayOptions(step = 0),
        )
        assertTrue(step.success, step.message)
        assertEquals(setOf("box-a"), projection(fresh).keys)
        val past = OperationTranscript.runMacro(
            fresh.filename, "round-trip", initializeCreatedBoxes = false,
            replay = OperationTranscript.MacroReplayOptions(step = 99),
        )
        assertFalse(past.success)
    }

    /**
     * The canvas sends a new box's name, frame and code at once and the server handles them
     * concurrently, so the frame or the code can be recorded before the create (seen live,
     * 2026-10-20: three `create`s of one box). Whatever order they land in, a box is one create.
     */
    @Test
    fun aNewBoxIsOneCreateWhateverOrderItsFirstMessagesLandIn() {
        val session = document("racing")
        thisSessionInfo.set(session)
        OperationTranscript.markStart("race")
        val made = box("box-r", createIfMissing = true, initIfCreated = false, root = session)
        made.setPropertyRaw("code", "println(\"r\")", doResync = false)
        OperationTranscript.recordEdit(made, "code", "", "println(\"r\")") // code handler, first
        made.frame = Rect(10.0, 20.0, 100.0, 50.0)
        OperationTranscript.recordMove(made, Rect(0.0, 0.0, 0.0, 0.0), made.frame) // frame handler
        OperationTranscript.recordCreate(made) // the handler that made it records last
        made.name = "Racer"
        OperationTranscript.recordEdit(made, "name", "", "Racer") // name handler
        val saved = OperationTranscript.markEndAndSave("race")
        val yaml = File(saved.path).readText()
        val verbs = Regex("""^  - ([a-z-]+):""", RegexOption.MULTILINE).findAll(yaml).map { it.groupValues[1] }.filter { it != "wait" }.toList()
        assertEquals(listOf("create"), verbs, yaml)
        assertTrue(yaml.contains("Racer") && yaml.contains("x: 10.0") && yaml.contains("println(\"r\")"), yaml)
    }

    @Test
    fun renameAndDeleteKeepTheListAndTheYamlInStep() {
        val (source, _) = recordSession()
        val path = source.filename
        val renamed = OperationTranscript.renameMacro(path, "round-trip", "two boxes")
        assertTrue(renamed.success, renamed.message)
        assertEquals("two-boxes", renamed.name)
        assertTrue(renamed.content.startsWith("name: \"two-boxes\""), renamed.content)
        assertEquals(listOf("two-boxes"), OperationTranscript.listMacros(path).macros)
        val deleted = OperationTranscript.deleteMacro(path, "two-boxes")
        assertTrue(deleted.success, deleted.message)
        assertEquals(emptyList(), OperationTranscript.listMacros(path).macros)
        assertTrue(File(deleted.path).exists(), "a deleted macro is kept under .deleted/")
    }
}
