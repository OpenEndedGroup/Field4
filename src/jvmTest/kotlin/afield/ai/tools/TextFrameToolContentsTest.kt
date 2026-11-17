package afield.ai.tools

import afield.app.server.SessionInformation
import afield.document.graph.*
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class TextFrameToolContentsTest {
    @Test fun toolListingReadsLiveMarkdownAndSettingsWithoutRunningTheFrame() {
        val directory = Files.createTempDirectory("frame-tool-contents").toFile()
        try {
            val root = Box(id = "root")
            val session = SessionInformation(directory.absolutePath, directory.name, ConcurrentHashMap(), root)
            root.inside = session
            val frame = Box(id = "live-writer").also {
                it.inside = session; it.name = "Research"; it.box_kind = "text-frame"
                it.contents = "# Unsaved observations\n\nquartzneedle"
                it.textFrame = "fontSize: 16\n"
            }
            session.boxes[frame.id] = frame
            val list = Ai4BackdoorTools.buildContext(session).listFileContents!!
            val first = list().contents
            assertEquals(frame.contents, first.single { it.property == "contents" }.content)
            assertEquals("Markdown", first.single { it.property == "contents" }.language)
            assertEquals(frame.textFrame, first.single { it.property == "textFrame" }.content)
            frame.contents = "A live edit"
            assertEquals("A live edit", list().contents.single { it.property == "contents" }.content)
            assertFalse(directory.resolve("metadata.edn").exists())
        } finally { directory.deleteRecursively() }
    }
}
