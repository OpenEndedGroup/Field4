package afield.app.server

import afield.document.graph.*
import afield.document.workspace.FileManager
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class TextFrameAssetsTest {
    @Test fun resolvesOnlyAuthoredImagesThroughTheOwningSessionsFileManager() {
        val temp = Files.createTempDirectory("text-frame-assets").toFile()
        val document = temp.resolve("doc").apply { mkdirs() }
        val images = temp.resolve("images").apply { mkdirs() }
        val name = "frame_test_${System.nanoTime()}"
        val root = Box(id = "root")
        val session = SessionInformation(document.absolutePath, "doc", ConcurrentHashMap(), root)
        root.inside = session
        val frame = Box(id = "writer").also { it.inside = session; it.box_kind = "text-frame" }
        session.boxes[frame.id] = frame
        FileManager.add(name, images, search = true)
        try {
            val image = images.resolve("a picture.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val local = document.resolve("files/local.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(4)) }
            frame.contents = "![named](%$name/a picture.png)\n![search](a picture.png)\n![local](%document/local.png)"
            assertEquals(image.canonicalFile, textFrameImageFile(session, frame.id, "%$name/a picture.png"))
            assertEquals(image.canonicalFile, textFrameImageFile(session, frame.id, "a picture.png"))
            assertEquals(local.canonicalFile, textFrameImageFile(session, frame.id, "%document/local.png"))
            assertNull(textFrameImageFile(session, frame.id, image.absolutePath))
            val other = images.resolve("hidden.png").apply { writeBytes(byteArrayOf(1)) }
            assertNull(textFrameImageFile(session, frame.id, "%$name/${other.name}"))
            val code = images.resolve("private.kt").apply { writeText("not an image") }
            frame.contents += "\n![code](%$name/${code.name})"
            assertNull(textFrameImageFile(session, frame.id, "%$name/${code.name}"))
            frame.contents += "\n![escape](%$name/../outside.png)"
            assertNull(textFrameImageFile(session, frame.id, "%$name/../outside.png"))
            frame.box_kind = ""
            assertNull(textFrameImageFile(session, frame.id, "a picture.png"))
            frame.box_kind = "text-frame"; frame.properties["_tombstone"] = true
            assertNull(textFrameImageFile(session, frame.id, "a picture.png"))
        } finally { FileManager.remove(name); temp.deleteRecursively() }
    }
}
