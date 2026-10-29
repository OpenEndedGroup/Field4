package afield.ai.tools

import afield.ui.slides.*
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class PresentationToolsTest {
    @Test fun agentAddsTwoSlidesAndCannotClobberAConcurrentEdit() {
        val root = createTempDirectory("presentation-tools").toFile()
        try {
            val tools = PresentationTools(root, { false })
            val first = PresentationSlide("predict", "Predict", "# What changes?", notes = "Ask for a prediction.")
            val created = tools.write(PresentationWriteSlideRequest("", first))
            assertTrue(created.success, created.message)
            val second = PresentationSlide("reveal", "Reveal", "# Twice the frequency", cues = listOf(PresentationReveal("Explain", "# Half the wavelength")))
            val added = tools.write(PresentationWriteSlideRequest(created.revision, second, afterId = "predict"))
            assertTrue(added.success, added.message)
            assertEquals(listOf("predict", "reveal"), added.outline.map { it.id })
            assertNull(tools.read(PresentationReadRequest()).slide)
            val read = tools.read(PresentationReadRequest(slideId = "predict"))
            assertEquals(first, read.slide)
            val saved = tools.write(PresentationWriteSlideRequest(read.revision, first.copy(markdown = "# Human's edit")))
            assertTrue(saved.success)
            assertFalse(tools.write(PresentationWriteSlideRequest(read.revision, first.copy(markdown = "# Stale AI edit"))).success)
            assertFalse(tools.write(PresentationWriteSlideRequest("", first)).success)
            assertEquals("# Human's edit", tools.read(PresentationReadRequest(slideId = "predict")).slide!!.markdown)
            assertFalse(PresentationTools(root, { true }).write(PresentationWriteSlideRequest(saved.revision, second)).success)
        } finally { root.deleteRecursively() }
    }

    @Test fun refusesTraversalSymlinksInvalidSlidesAndMissingInsertionTargets() {
        val parent = createTempDirectory("presentation-boundaries").toFile()
        val root = parent.resolve("document").apply { mkdirs() }
        try {
            val tools = PresentationTools(root, { false })
            val slide = PresentationSlide("one", "One", "# One")
            assertFalse(tools.write(PresentationWriteSlideRequest("", slide, "files/../../outside.slides.json")).success)
            root.resolve("files").mkdirs()
            Files.createSymbolicLink(root.resolve("files/escape.slides.json").toPath(), parent.resolve("outside.slides.json").toPath())
            assertFalse(tools.write(PresentationWriteSlideRequest("", slide, "files/escape.slides.json")).success)
            assertFalse(tools.write(PresentationWriteSlideRequest("", slide.copy(id = "bad id"))).success)
            assertFalse(tools.write(PresentationWriteSlideRequest("", slide, afterId = "missing")).success)
            assertFalse(root.resolve("files/talk.slides.json").exists())
        } finally { parent.deleteRecursively() }
    }

    @Test fun chatAndBackdoorExposeTheSameAuthoringSchemasAndEffects() {
        val root = createTempDirectory("presentation-schemas").toFile()
        try {
            val context = ToolExecutionContext(documentGuides = DocumentGuideTools(root) { false })
            val chat = ToolRegistry().also { registerBuiltInAgentTools(it) }
            assertTrue(chat.isAvailable(context, "PresentationReadRequest"))
            assertTrue(Ai4BackdoorTools.registry.isAvailable(context, "PresentationWriteSlideRequest"))
            assertEquals(setOf(ToolEffect.MUTATE_DOCUMENT, ToolEffect.FILESYSTEM_WRITE), ToolMeta.of(PresentationWriteSlideRequest::class.java).effects)
            assertFalse(chat.isAvailable(ToolExecutionContext(), "PresentationWriteSlideRequest"))
        } finally { root.deleteRecursively() }
    }
}
