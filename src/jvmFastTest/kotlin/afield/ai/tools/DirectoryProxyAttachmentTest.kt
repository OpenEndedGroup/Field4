package afield.ai.tools

import afield.app.server.refreshFileProxyBox
import afield.document.graph.Box
import java.nio.file.Files
import kotlin.test.*

class DirectoryProxyAttachmentTest {
    @Test fun catalogueAndSearchReferencesReadLiveChildren() {
        val root = Files.createTempDirectory("proxy-attachment").toFile()
        try {
            val file = root.resolve("docs/readme.md").also { it.parentFile.mkdirs(); it.writeText("# Hello\nfirst version") }
            val box = Box("context-root").also {
                it.name = "Context folder"
                it.setPropertyRaw("file_proxy_mode", "absolute")
                it.setPropertyRaw("file_proxy_path", root.absolutePath)
            }
            refreshFileProxyBox(box)
            assertContains(box.getPropertyRaw("contents").toString(), "Directory context proxy")
            val catalogue = resolveProxyAttachment(listOf(box), box.id)!!
            assertContains(catalogue.content, "proxy:context-root/docs/readme.md")
            val child = resolveProxyAttachment(listOf(box), "proxy:context-root/docs/readme.md")!!
            assertEquals("# Hello\nfirst version", child.content)
            assertEquals("markdown", child.language)
            file.writeText("updated content")
            assertEquals("updated content", resolveProxyAttachment(listOf(box), child.file)!!.content)
            assertContains(resolveProxyAttachment(listOf(box), "Context folder")!!.content, "catalogue")
            root.resolve(".gitignore").writeText("docs/\n")
            assertFailsWith<IllegalArgumentException> { resolveProxyAttachment(listOf(box), child.file) }
            assertFalse(resolveProxyAttachment(listOf(box), box.id)!!.content.contains(child.file))
        } finally { root.deleteRecursively() }
    }

    @Test fun malformedMissingAndAmbiguousReferencesNeverFallBack() {
        val root = Files.createTempDirectory("proxy-attachment-ref").toFile()
        try {
            root.resolve("readme.md").writeText("text")
            fun proxy(id: String) = Box(id).also {
                it.name = "Duplicate"
                it.setPropertyRaw("file_proxy_mode", "absolute")
                it.setPropertyRaw("file_proxy_path", root.absolutePath)
            }
            val a = proxy("one"); val b = proxy("two")
            assertFailsWith<IllegalArgumentException> { resolveProxyAttachment(listOf(a, b), "Duplicate") }
            for (ref in listOf("proxy:missing/file.md", "proxy:one/../secret", "proxy:one/.git/config", "proxy:/etc/passwd"))
                assertFailsWith<IllegalArgumentException>(ref) { resolveProxyAttachment(listOf(a), ref) }
            assertNull(resolveProxyAttachment(listOf(a), "ordinary.md"))
            a.setPropertyRaw("_tombstone", true)
            assertFailsWith<IllegalArgumentException> { resolveProxyAttachment(listOf(a), "proxy:one/readme.md") }
        } finally { root.deleteRecursively() }
    }
}
