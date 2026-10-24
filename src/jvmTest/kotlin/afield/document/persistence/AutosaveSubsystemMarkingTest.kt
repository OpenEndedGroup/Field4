package afield.document.persistence

import afield.animation.interpolation.registerInterpolant
import afield.animation.keyframes.saveKeyframeData
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.app.protocol.handlers
import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.layerFrames
import afield.document.graph.needsResync
import afield.document.graph.textPropertyValue
import afield.ui.notation.NotationSceneUpdate
import afield.ui.notation.installNotationHandlers
import afield.ui.notation.notation
import afield.ui.output.controlGroup
import afield.ui.output.controllerControlsForBox
import afield.ui.output.updateControllerControlValue
import afield.ui.output.updateControllerControlValueProgrammatically
import afield.ui.slides.slideShow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The older subsystems that persist into root or box properties, each driven through its own write
 * path, against the two halves of dirty marking (backlog N1023-6):
 *
 * - *marked*: the write leaves `(box, property)` in `pendingSidecarSaves`, so the 5 s autosave and the
 *   SIGINT flush save it for a reason they can name;
 * - *tripwire quiet*: the save that follows reports no unmarked edit (`DocumentShutdownFlush.lastUnmarked`).
 *   A future write path that mutates a persisted value without marking fails here, not in a user's
 *   document as a `warning,persistence` line;
 * - *survives reload*: the value is in `metadata.edn` as the loader reads it back (`readEdnTransfers`).
 *
 * The matrix of results is in `dev_docs/SIGINT_SHUTDOWN.md` ("Subsystems").
 */
class AutosaveSubsystemMarkingTest {

    private val tempDirs = mutableListOf<File>()
    private val opened = mutableListOf<SessionInformation>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { info ->
            documents.remove(info.filename)
            val mine = info.boxes.values.toSet() + info.root
            needsResync.removeAll { (box, _) -> box in mine }
        }
        opened.clear()
        thisSessionInfo.remove()
        Box.context.remove()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    /** A saved document with box `a` under the root, nothing pending, as `AutosaveDirtyTrackingTest` builds it. */
    private fun savedDocument(label: String): SessionInformation {
        val directory = Files.createTempDirectory("afield-subsystem-$label").toFile().also { tempDirs += it }
        val root = Box("root")
        root.name = "root"
        val info = SessionInformation(filename = directory.absolutePath, documentName = directory.name, root = root)
        root.inside = info
        info.boxes[root.id] = root
        info.agentManaged = true
        info.loading = true
        val box = Box("a")
        box.inside = info
        info.boxes["a"] = box
        box.name = "a"
        box.frame = Rect(10.0, 20.0, 100.0, 50.0)
        box.setPropertyRaw("code", "// a")
        root.connect(box)
        info.loading = false
        documents[info.filename] = info
        thisSessionInfo.set(info)
        opened += info
        info.markSidecarPending("a", "code")
        assertTrue(DocumentShutdownFlush.saveIfPending(info, inline = true, reason = "test fixture"))
        info.pendingSidecarSaves.clear()
        return info
    }

    /** The property as the loader would read it back from `metadata.edn`. */
    private fun SessionInformation.onDisk(uid: String, property: String): Any? =
        SavingContext().readEdnTransfers(filename).firstOrNull { it.id == uid }?.properties?.get(property)

    /** Marked, saved by the flush with the tripwire quiet; returns what disk holds. */
    private fun SessionInformation.assertMarkedAndSaved(uid: String, property: String): Any? {
        assertTrue((uid to property) in pendingSidecarSaves, "$uid.$property was written without marking; pending: $pendingSidecarSaves")
        DocumentShutdownFlush.lastUnmarked = listOf("sentinel" to "unset")
        assertEquals(1, DocumentShutdownFlush.flushPendingDocumentSaves(), "the flush did not save $uid.$property")
        assertEquals(emptyList(), DocumentShutdownFlush.lastUnmarked, "the tripwire saw an unmarked edit")
        // The save's onComplete, which clears what it wrote, runs on the Runloop (`inMain`), and a unit
        // test has none; the fixture clears it by hand for the same reason.
        pendingSidecarSaves.clear()
        return onDisk(uid, property)
    }

    /** What the server's `set-string` handler does with a client's string (bookmarks, drawing, panes). */
    private fun Box.setStringAsTheHandlerDoes(property: String, value: String) =
        setPropertyRaw(property, textPropertyValue(this, property, value), doResync = true)

    @Test
    fun bookmarks() {
        val info = savedDocument("bookmarks")
        info.root.setStringAsTheHandlerDoes("bookmarks", """[{"name":"overview","x":-120,"y":40,"zoom":0.5}]""")
        assertTrue("overview" in info.assertMarkedAndSaved("root", "bookmarks").toString())
    }

    @Test
    fun splitPanes() {
        val info = savedDocument("panes")
        info.root.setStringAsTheHandlerDoes("panes", """{"columns":[0.5],"rows":[],"linked":true}""")
        assertTrue("0.5" in info.assertMarkedAndSaved("root", "panes").toString())
    }

    @Test
    fun drawingLayerSentAsAString() {
        val info = savedDocument("drawing")
        info.root.setStringAsTheHandlerDoes("drawing", """{"type":"excalidraw","version":2,"elements":[{"id":"hand","type":"rectangle","x":1,"y":2,"version":1}]}""")
        assertTrue("hand" in info.assertMarkedAndSaved("root", "drawing").toString())
    }

    @Test
    fun drawingLayerMergedThroughNotationScene() {
        val info = savedDocument("notation-scene")
        val old = handlers["notation-scene"]
        try {
            installNotationHandlers()
            val scene = """{"type":"excalidraw","version":2,"elements":[{"id":"drawn","type":"ellipse","x":5,"y":6,"width":10,"height":10,"version":2,"versionNonce":1,"isDeleted":false}]}"""
            handlers["notation-scene"]!!.single()(Message("notation-scene", payload = Json.encodeToJsonElement(NotationSceneUpdate(scene)))) { }
        } finally {
            if (old == null) handlers.remove("notation-scene") else handlers["notation-scene"] = old
        }
        assertTrue("drawn" in info.assertMarkedAndSaved("root", "drawing").toString())
    }

    @Test
    fun notationWrittenByBoxCode() {
        val info = savedDocument("notation-code")
        val a = info.boxes.getValue("a")
        a.notation { rect(0, 0, 10, 10, name = "from-code") }
        assertTrue("from-code" in info.assertMarkedAndSaved("root", "drawing").toString())
        // An edit of a mark already there, the read-modify-write every notation verb shares.
        a.notation { rect(40, 0, 10, 10, name = "from-code") }
        assertTrue("40" in info.assertMarkedAndSaved("root", "drawing").toString())
    }

    @Test
    fun keyframeEditorKeys() {
        val info = savedDocument("keyframes")
        saveKeyframeData(info.boxes.getValue("a"), "wobble", """[{"t":0.25,"v":0.75}]""")
        assertTrue("wobble" in info.assertMarkedAndSaved("a", "keyframeData").toString())
    }

    @Test
    fun keyframeInterpolantSource() {
        val info = savedDocument("interpolants")
        info.boxes.getValue("a").registerInterpolant("overshoot", "v0 + (v1 - v0) * u * u") { v0 }
        assertTrue("overshoot" in info.assertMarkedAndSaved("a", "keyframeInterpolants").toString())
    }

    @Test
    fun layerFramesThroughEveryMapView() {
        // N1023-5: the PersistentMapView marked put/remove/clear but not writes through entries/keys/values.
        val info = savedDocument("layer-views")
        val a = info.boxes.getValue("a")
        a.layerFrames["one"] = Rect(1.0, 1.0, 10.0, 10.0)
        a.layerFrames["two"] = Rect(2.0, 2.0, 10.0, 10.0)
        a.layerFrames["three"] = Rect(3.0, 3.0, 10.0, 10.0)
        info.assertMarkedAndSaved("a", "layerFrames")

        a.layerFrames.entries.first { it.key == "one" }.setValue(Rect(601.0, 1.0, 10.0, 10.0))
        assertTrue("601" in info.assertMarkedAndSaved("a", "layerFrames").toString(), "entry.setValue")

        a.layerFrames.keys.removeIf { it == "two" }
        assertTrue("two" !in info.assertMarkedAndSaved("a", "layerFrames").toString(), "keys.removeIf")

        val it = a.layerFrames.values.iterator()
        while (it.hasNext()) if (it.next().x == 3.0) it.remove()
        val left = info.assertMarkedAndSaved("a", "layerFrames").toString()
        assertTrue("three" !in left && "one" in left, "values.iterator().remove(): $left")

        // A view write that changes nothing marks nothing.
        a.layerFrames.keys.retainAll(setOf("one"))
        a.layerFrames.entries.first().setValue(Rect(601.0, 1.0, 10.0, 10.0))
        assertTrue(info.pendingSidecarSaves.isEmpty(), "no-op view writes marked: ${info.pendingSidecarSaves}")
    }

    @Test
    fun controlGestureIsMarked() {
        val info = savedDocument("controls")
        val a = info.boxes.getValue("a")
        a.output.displayProxy = { _, _ -> }
        a.controlGroup("controls") { slider("gain", min = 0.0, max = 1.0, initialValue = 0.25) { } }
        info.assertMarkedAndSaved("a", "_controlGroupValues")
        val id = controllerControlsForBox("a").single().id
        updateControllerControlValue(id) { it.copy(value = 0.875) }
        assertTrue("0.875" in info.assertMarkedAndSaved("a", "_controlGroupValues").toString())
    }

    @Test
    fun controlDrivenByKeyframesWritesNothingPersistent() {
        // The programmatic path (a keyframe curve playing a slider) is meant to record no edit. Until
        // 2026-10-24 it still rewrote `_controlGroupValues` in place, so the fingerprint saw an unmarked
        // edit on every autosave tick of a playing curve and saved the document each time.
        val info = savedDocument("controls-programmatic")
        val a = info.boxes.getValue("a")
        a.output.displayProxy = { _, _ -> }
        a.controlGroup("controls") { slider("gain", min = 0.0, max = 1.0, initialValue = 0.25) { } }
        info.assertMarkedAndSaved("a", "_controlGroupValues")
        val id = controllerControlsForBox("a").single().id
        for (step in 1..5) updateControllerControlValueProgrammatically(id) { it.copy(value = step / 10.0) }
        assertTrue(info.pendingSidecarSaves.isEmpty(), "pending: ${info.pendingSidecarSaves}")
        assertEquals(0, DocumentShutdownFlush.flushPendingDocumentSaves(), "a curve playing a slider saved the document")
        // A rerun still starts from the value on screen, not the last gesture.
        a.controlGroup("controls") { slider("gain", min = 0.0, max = 1.0, initialValue = 0.25) { } }
        assertEquals(0.5, controllerControlsForBox("a").single().value)
    }

    @Test
    fun slideshowDefinitionIsMarkedButNotWrittenToDisk() {
        // `slideShow` is in the persistent list, but its value is a live SlideShowInstance (lambdas),
        // which the saver cannot write. Measured: marked, saved, and absent on reload; the box's code
        // rebuilds it. Recorded so a change to either half is seen.
        val info = savedDocument("slideshow")
        val a = info.boxes.getValue("a")
        a.slideShow { step("one") { } }
        assertNotNull(a.getPropertyRaw("slideShow"))
        val onDisk = info.assertMarkedAndSaved("a", "slideShow")
        assertEquals(null, onDisk, "a SlideShowInstance reached metadata.edn as $onDisk")
    }
}
