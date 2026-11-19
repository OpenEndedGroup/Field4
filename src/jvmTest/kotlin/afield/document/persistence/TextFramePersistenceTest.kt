package afield.document.persistence

import afield.document.graph.Box
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFramePersistenceTest {
    @Test
    fun `text and typography survive the actual save and read path`() {
        val directory = Files.createTempDirectory("field-text-frame").toFile()
        try {
            val frame = Box(id = "text-frame-persistence")
            val source = "# Shared notes\n\nA **real** document, with français and 日本語.\n"
            val style = """{"fontFamily":"Iosevka","fontSize":22,"sizing":"fixed"}"""
            frame.properties.putAll(mapOf("box_kind" to "text-frame", "contents" to source, "textFrame" to style))
            val context = SavingContext()
            assertEquals(source, context.toTransfer(setOf(frame), directory.path).single().properties["contents"])
            context.save(directory.path, setOf(frame), inline = true)
            val restored = context.readEdnTransfers(directory.path).single().properties
            assertEquals(source, restored["contents"])
            assertEquals(style, restored["textFrame"])
            assertEquals("text-frame", restored["box_kind"])
            assertTrue(Box.isPersistentName("contents"), "typing must trigger autosave dirty tracking")
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `proxy contents remain a disk cache rather than becoming document text`() {
        val directory = Files.createTempDirectory("field-text-proxy").toFile()
        try {
            val proxy = Box(id = "text-frame-proxy")
            proxy.properties.putAll(mapOf("file_proxy_mode" to "absolute", "contents" to "Cached disk contents"))
            val context = SavingContext()
            assertFalse("contents" in context.toTransfer(setOf(proxy), directory.path).single().properties)
            context.save(directory.path, setOf(proxy), inline = true)
            assertFalse("contents" in context.readEdnTransfers(directory.path).single().properties)
        } finally { directory.deleteRecursively() }
    }
}
