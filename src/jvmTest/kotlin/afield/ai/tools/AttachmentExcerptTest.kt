package afield.ai.tools

import afield.ai.tools.AttachmentRequest
import afield.ai.tools.AttachmentResponse
import afield.ai.tools.ContextAttachment
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolRegistry
import afield.ai.tools.aiToolMapper
import afield.ai.tools.excerpt
import afield.ai.tools.registerBuiltInAgentTools
import kotlin.test.*

class AttachmentExcerptTest {
    private val registry = ToolRegistry().also { registerBuiltInAgentTools(it) }
    private fun read(ctx: ToolExecutionContext, request: AttachmentRequest) =
        registry.executeByName(ctx, "AttachmentRequest", aiToolMapper.writeValueAsString(request)) as AttachmentResponse

    @Test fun defaultReadIsBoundedAndPaginationReconstructsTheExactAttachment() {
        val source = "a\n".repeat(25000)
        val ctx = ToolExecutionContext(attachments = listOf(ContextAttachment("large.kt", "kotlin", source)))
        val first = read(ctx, AttachmentRequest("large.kt"))
        assertEquals(6000, first.content.length)
        assertEquals(source.length, first.totalChars)
        assertTrue(first.isExcerpt)
        assertEquals(64, first.sourceHash.length)
        val chunks = mutableListOf(first)
        while (chunks.last().nextOffset != null) {
            chunks += read(ctx, AttachmentRequest("large.kt", chunks.last().nextOffset!!, expectedSourceHash = first.sourceHash))
        }
        assertEquals(source, chunks.joinToString("") { it.content })
        assertNull(chunks.last().nextOffset)
        assertTrue(chunks.all { it.sourceHash == first.sourceHash })
    }

    @Test fun literalSearchSkipsUnrelatedSourceAndKeepsVersionGuards() {
        var source = "irrelevant\n".repeat(10000) + "fun repairCorner() = 42\n" + "trailing\n".repeat(1000)
        val ctx = ToolExecutionContext(resolveAttachment = { AttachmentResponse(it, "kotlin", source) })
        val found = read(ctx, AttachmentRequest("large.kt", find = "fun repairCorner"))
        assertEquals(true, found.searchMatched)
        assertEquals(source.indexOf("fun repairCorner"), found.matchOffset)
        assertTrue(found.offset > 100000)
        assertEquals(6000, found.content.length)
        assertContains(found.content, "fun repairCorner() = 42")
        assertEquals(source.substring(found.offset, found.nextOffset!!), found.content)
        val missing = read(ctx, AttachmentRequest("large.kt", find = "absent", expectedSourceHash = found.sourceHash))
        assertEquals(false, missing.searchMatched)
        assertEquals("", missing.content)
        assertEquals(source.length, missing.totalChars)
        assertNull(missing.nextOffset)
        assertTrue(missing.isExcerpt)
        source += "changed"
        assertFailsWith<IllegalArgumentException> {
            read(ctx, AttachmentRequest("large.kt", find = "repairCorner", expectedSourceHash = found.sourceHash))
        }
    }

    @Test fun resolverReadsKeepGuidesAndRefuseMixedSourceVersions() {
        var source = "abcdef"
        val ctx = ToolExecutionContext(resolveAttachment = { AttachmentResponse(it, "text", source, listOf("guide.md"), listOf("example.kt")) })
        val first = read(ctx, AttachmentRequest("afield://doc/box/code", maxChars = 3))
        assertEquals(listOf("guide.md"), first.relatedGuides)
        assertEquals(listOf("example.kt"), first.relatedExamples)
        source = "abcxyz"
        val error = assertFailsWith<IllegalArgumentException> {
            read(ctx, AttachmentRequest(first.file, first.nextOffset!!, 3, first.sourceHash))
        }
        assertTrue(error.message.orEmpty().contains("Source changed"))
    }

    @Test fun unicodeBoundariesAndInvalidRangesAreExplicit() {
        val source = "a😀b😀"
        val response = AttachmentResponse("unicode", "text", source)
        val first = response.excerpt(AttachmentRequest("unicode", maxChars = 2))
        assertEquals("a", first.content)
        val second = response.excerpt(AttachmentRequest("unicode", first.nextOffset!!, 2, first.sourceHash))
        assertEquals("😀", second.content)
        assertEquals(3, second.nextOffset)
        assertFailsWith<IllegalArgumentException> { response.excerpt(AttachmentRequest("unicode", 2)) }
        assertFailsWith<IllegalArgumentException> { response.excerpt(AttachmentRequest("unicode", -1)) }
        assertFailsWith<IllegalArgumentException> { response.excerpt(AttachmentRequest("unicode", 100)) }
        assertFailsWith<IllegalArgumentException> { response.excerpt(AttachmentRequest("unicode", maxChars = 1)) }
        assertFailsWith<IllegalArgumentException> { response.excerpt(AttachmentRequest("unicode", maxChars = Int.MAX_VALUE)) }
    }

    @Test fun completeAndEmptySourcesAreNeverReportedAsTruncated() {
        for (source in listOf("", "small\r\nsource\n")) {
            val result = read(ToolExecutionContext(attachments = listOf(ContextAttachment("file", "text", source))), AttachmentRequest("file"))
            assertEquals(source, result.content)
            assertFalse(result.isExcerpt)
            assertNull(result.nextOffset)
            assertEquals(source.length, result.totalChars)
        }
    }

    /** 2026-10-28: a local model's `find` misses came back empty, and it cited a char offset as a line. */
    @Test
    fun findReportsLinesAndAMissShowsWhereItsIdentifierOccurs() {
        val source = "package afield.x\n\nclass Proof {\n    fun Shape2.drawn(color: Byrne) = Figure(this)\n}\n"
        val ctx = ToolExecutionContext(attachments = listOf(ContextAttachment("Elements.kt", "kotlin", source)))
        val hit = read(ctx, AttachmentRequest("Elements.kt", find = "Shape2.drawn"))
        assertEquals(4, hit.matchLine)
        assertEquals(1, hit.startLine)
        val miss = read(ctx, AttachmentRequest("Elements.kt", find = "fun drawn("))
        assertEquals(false, miss.searchMatched)
        assertEquals(listOf("4: fun Shape2.drawn(color: Byrne) = Figure(this)"), miss.nearMatches)
    }

    @Test fun sourceRelativePathsResolveUnderSrc() {
        val seen = mutableListOf<String>()
        val ctx = ToolExecutionContext(resolveAttachment = { name ->
            seen += name
            if (name == "src/jvmMain/A.kt") AttachmentResponse(name, "kotlin", "fun a() = 1") else null
        })
        assertEquals("fun a() = 1", read(ctx, AttachmentRequest("jvmMain/A.kt")).content)
        assertEquals(listOf("jvmMain/A.kt", "src/jvmMain/A.kt"), seen)
    }
}
