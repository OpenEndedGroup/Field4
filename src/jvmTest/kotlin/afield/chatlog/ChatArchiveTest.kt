package afield.chatlog

import afield.AiChatBlock
import afield.AiChatRound
import afield.images
import afield.parseAiChatBlocks
import afield.programCalls
import afield.toolCall
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

/**
 * The chat archive reads a saved AI4 chat from a document folder as a tree: the shared transcript parser, tool
 * calls and their families, tool programs' own calls, images and their blobs, rewound branches, and transcript
 * text the session tree has not recorded yet.
 */
class ChatArchiveTest {

    @Test fun theParserSplitsBlocksAndReadsTheModelMarker() {
        val blocks = parseAiChatBlocks(
            "<<you>>\nmake a plan\n<<ai>>\n<<model:{\"provider\":\"openai\",\"requestedModel\":\"gpt-6-astra\",\"responseModel\":\"gpt-6-astra\"}>>\nOn it.\n" +
                "<<progress>>\nWaiting for model\n<<progress>>\nListBoxesRequest finished (20 ms)\n3 boxes"
        )
        assertEquals(listOf("you", "ai", "progress", "progress"), blocks.map { it.role })
        assertEquals("gpt-6-astra", blocks[1].model?.responseModel)
        assertEquals("On it.", blocks[1].content)
        assertTrue(blocks[2].isStatusOnly)
        assertFalse(blocks[3].isStatusOnly)
        assertEquals(listOf(AiChatBlock("ai", "no markers")), parseAiChatBlocks("no markers"))
    }

    @Test fun toolTitlesAndProgramLinesBecomeCalls() {
        val ok = AiChatBlock("progress", "_field_sketch_edit_e0b4aeb5c8da0381fe14367f finished (88 ms)").toolCall()!!
        assertEquals("_field_sketch_edit", ok.name)
        assertEquals(88L, ok.ms)
        assertTrue(ok.ok)
        val failed = AiChatBlock("progress", "AttachmentRequest failed (0 ms)\nkeys=error").toolCall()!!
        assertEquals("Attachment", failed.name)
        assertFalse(failed.ok)
        assertNull(AiChatBlock("progress", "Waiting for model").toolCall())

        val program = AiChatBlock("progress", """
            RunToolProgramRequest finished (1150 ms)
            - ✓ `DocumentGuideAppend` `{"path":"docs/a.md"}` — 4 ms
            - ✗ `_field_services_previewEdit_5934acd4743a29fadd988cf7` `{"expectedRevision":"empty","expectedSourceHash":"66ef9c475c84a064350ecc98d4534b` — 291 ms — Provide 1–64 atomic edits.
        """.trimIndent())
        val calls = program.programCalls()
        assertEquals(listOf("DocumentGuideAppend", "_field_services_previewEdit"), calls.map { it.name })
        assertEquals(listOf(true, false), calls.map { it.ok })
        assertEquals("Provide 1–64 atomic edits.", calls[1].message)
        assertEquals(291L, calls[1].ms)
    }

    @Test fun familiesComeFromTheCatalogFirstMatchWinning() {
        val f = ChatToolFamilies.default
        assertEquals("notes", f.familyOf("DocumentGuideAppend"))
        assertEquals("recipes", f.familyOf("_field_recipe_candidate"))
        assertEquals("design", f.familyOf("_field_sketch_edit"))
        assertEquals("reading", f.familyOf("_field_services_inspectRoutes"))
        assertEquals("computing", f.familyOf("_field_services_previewEdit"))
        assertEquals("looking", f.familyOf("ViewBoxOutput"))
        assertEquals("notes", f.familyOf("ReportGeometryFinding"))
        assertEquals("other", f.familyOf("SomethingNew"))
    }

    @Test fun imagesKeepTheirCaptionAndReference() {
        val images = AiChatBlock("ai", "_Output of box 'Plan' (main), 10x10 — looking for: the hall_\n\n![tool image](data:image/jpeg;base64,AAAA)\n![again](ai4img:0123456789abcdef.png)").images()
        assertEquals(2, images.size)
        assertEquals("Output of box 'Plan' (main), 10x10 — looking for: the hall", images[0].caption)
        assertEquals("jpeg", images[0].mediaType?.removePrefix("image/"))
        assertNull(images[0].ref)
        assertEquals("ai4img:0123456789abcdef.png", images[1].ref)
    }

    /** A document with a rewound branch, a blob, a missing blob, and a transcript one block past the tree. */
    private fun document(): File {
        val dir = Files.createTempDirectory("chat-archive").toFile()
        val data = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val ref = ChatArchive.asBlobRefs("data:image/png;base64,$data").removePrefix("ai4img:")
        File(dir, "ai4_blobs").mkdirs()
        File(dir, "ai4_blobs/$ref").writeBytes(byteArrayOf(1, 2, 3))
        val box = "b1"
        File(dir, "metadata.edn").writeText(
            """{:version 2 :boxes [{"id" "root" "properties" {"name" "root"}} {"id" "$box" "properties" {"name" "House conversation" "ai4_chat_history" #afield/file "${box}_ai4_chat_history_.md" "ai4_session" #afield/file "${box}_ai4_session_.jsonl"}}]}"""
        )
        val t1 = "<<you>>\nplan it\n<<ai>>\n_Output of box 'Plan' (main)_\n\n![tool image](ai4img:$ref)\n<<progress>>\nViewBoxOutputRequest finished (5 ms)\nok\n"
        val dead = "<<you>>\nroof?\n<<progress>>\nViewBoxOutputRequest failed (2995040 ms)\ntimeout\n<<ai>>\n![lost](ai4img:ffffffffffffffff.png)\n"
        val t2 = "<<you>>\nroof again\n<<ai>>\nGable it is.\n"
        File(dir, "${box}_ai4_session_.jsonl").writeText(listOf(
            """{"t":"header","v":1}""",
            """{"t":"turn","id":"t-1","parent":null,"ts":1000,"markdown":${q(t1)},"user":"plan it","usage":{"in":100,"out":10,"rounds":2}}""",
            """{"t":"turn","id":"t-dead","parent":"t-1","ts":2000,"markdown":${q(dead)},"user":"roof?"}""",
            """{"t":"turn","id":"t-2","parent":"t-1","ts":3000,"markdown":${q(t2)},"user":"roof again","usage":{"in":50,"out":5,"rounds":1}}""",
            """{"t":"head","id":"t-2","renderedSha":"x"}""",
        ).joinToString("\n"))
        val inlined = (t1 + t2).replace("ai4img:$ref", "data:image/png;base64,$data")
        File(dir, "${box}_ai4_chat_history_.md").writeText(inlined + "<<progress>>\n✓ box-exists:X — PASS\n")
        return dir
    }

    private fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

    @Test fun aDocumentReadsAsATreeWithItsRewoundBranchAndUnrecordedTail() {
        val dir = document()
        try {
            val chats = ChatArchive.chats(dir)
            assertEquals(listOf("b1"), chats.map { it.boxId })
            assertEquals("House conversation", chats[0].boxName)
            val tree = ChatArchive.tree(chats[0])

            assertEquals(listOf("t-1", "t-dead", "t-2", "unrecorded-tail"), tree.nodes.map { it.id })
            assertEquals("unrecorded-tail", tree.head)
            assertEquals(listOf("t-1", "t-2", "unrecorded-tail"), tree.headPath.map { it.id })
            assertEquals(2, tree.tips.size, "the rewound turn is its own branch")
            assertFalse(tree.node("t-dead")!!.onHead)

            assertEquals(listOf(1, 2), tree.nodes.flatMap { it.blocks }.mapNotNull { it.turn }, "turns count along the current branch only")
            val img = tree.block("t-1.b1")!!.images.single()
            assertEquals(true, img.resolved)
            assertEquals("Output of box 'Plan' (main)", img.image.caption)
            assertEquals(false, tree.block("t-dead.b2")!!.images.single().resolved)
            assertEquals("looking", tree.block("t-1.b2")!!.call!!.family)

            val stats = ChatStats.of(tree)
            assertEquals(2, stats.userTurns)
            assertEquals(1, stats.abandonedUserTurns)
            assertEquals(1, stats.failedCalls)
            assertEquals(1, stats.missingImages)
            assertEquals(150L, stats.inputTokens)
            assertTrue(stats.unrecorded)
            assertTrue(ChatArchiveCli.table(listOf(stats)).contains("House conversation"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aTranscriptThatMatchesTheTreeAddsNothing() {
        val dir = document()
        try {
            val md = File(dir, "b1_ai4_chat_history_.md")
            md.writeText(md.readText().substringBefore("<<progress>>\n✓ box-exists"))
            val tree = ChatArchive.tree(ChatArchive.chats(dir).single())
            assertEquals("t-2", tree.head)
            assertTrue(tree.nodes.none { it.type == "unrecorded" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun roundsAndSourceMarkersAreShared() {
        val run = parseAiChatBlocks("<<progress>>\nWaiting for model\n<<progress>>\nDocumentGuideAppendRequest finished (1500 ms)\nok\n<<progress>>\nAttachmentRequest failed (0 ms)\nno")
        assertTrue(AiChatRound.folds(run))
        assertEquals("2 tool calls · 1.5 s · 1 failed · 1 note · 1 look", AiChatRound(run, ChatToolFamilies.default).line())
        val lens = parseAiChatBlocks("<<ai>>\n<<source:t-1.b0..t-1.b2, t-2.b1>>\nPlanned it, then a gable.")
        assertEquals(listOf("t-1.b0..t-1.b2", "t-2.b1"), lens.single().sources)
        assertEquals("Planned it, then a gable.", lens.single().content)
    }

    @Test fun agentsReadTheChatByAddress() {
        val dir = document()
        try {
            val tree = ChatArchive.tree(ChatArchive.chats(dir).single())
            val outline = ChatLogViews.outline(tree)
            assertTrue("## Turn 1 · t-1.b0" in outline, outline)
            assertTrue("> plan it" in outline)
            assertTrue("t-1.b1 reply:" in outline && "Output of box 'Plan' (main)" in outline, "replies carry their image captions")
            assertTrue("## Abandoned branch t-dead" in outline, "rewound turns are on the map")

            val whole = ChatLogViews.read(tree, null, null)
            assertNull(whole.next)
            assertTrue("[t-1.b0] <<you>>\nplan it" in whole.text, whole.text)
            assertFalse("roof?" in whole.text, "reading with no range follows the current branch")
            val dead = ChatLogViews.read(tree, "t-1.b0", "t-dead.b2")
            assertTrue("roof?" in dead.text, "a range can end on an abandoned branch")
            val paged = ChatLogViews.read(tree, null, null, maxChars = 60)
            assertNotNull(paged.next)
            assertNull(tree.span("t-2.b0", "t-dead.b0"), "no stretch crosses from one branch to another")

            assertEquals(listOf("t-2.b1"), ChatLogViews.search(tree, "gable").map { it.address })
            assertEquals(listOf("t-1.b2", "t-dead.b1"), ChatLogViews.search(tree, "", family = "looking").map { it.address })
            val images = ChatLogViews.images(tree)
            assertEquals(listOf("t-1.b1", "t-dead.b2"), images.map { it.address })
            assertEquals("Output of box 'Plan' (main)", images[0].caption)

            val good = "<<ai>>\n<<source:t-1.b0..t-1.b2>>\nPlanned.\n<<ai>>\n<<source:t-2.b0..t-2.b1>>\nGable."
            assertEquals(emptyList(), ChatLogViews.lensProblems(tree, good))
            val stats = ChatLogViews.lensStats(tree, good)
            assertEquals(5, stats["citedBlocks"])
            val bad = "<<ai>>\n<<source:t-2.b1..t-1.b0>>\nBackwards.\n<<ai>>\nUncited."
            assertEquals(1, ChatLogViews.lensProblems(tree, bad).size)
            assertTrue(ChatLogViews.lensProblems(tree, "<<ai>>\nNothing cited.").any { "no block cites" in it })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun lensesAreStoredBesideTheirBrief() {
        val dir = document()
        val lenses = Files.createTempDirectory("lenses").toFile()
        try {
            val store = ChatlogStore(listOf(dir), lenses)
            val id = store.all().map { it.first }.single()
            assertEquals(dir.name, id, "a document with one chat names it")
            val tree = store.tree(id)
            assertSame(tree, store.tree(id), "an unchanged chat is read once")
            store.writeLens(id, "storyline", "<<ai>>\n<<source:t-1.b0>>\nPlanned.", "one line per turn", mapOf("blocks" to 1))
            assertEquals(listOf("storyline"), store.lenses(id))
            assertTrue(store.readLens(id, "storyline")!!.contains("one line per turn"))
            assertFailsWith<IllegalArgumentException> { store.writeLens(id, "../escape", "x", "", emptyMap()) }
        } finally {
            dir.deleteRecursively()
            lenses.deleteRecursively()
        }
    }

    @Test fun aLensIsAChatSoALensCanBeWrittenOverALens() {
        val dir = document()
        val lenses = Files.createTempDirectory("lenses").toFile()
        try {
            val store = ChatlogStore(listOf(dir), lenses)
            val chat = dir.name
            store.writeLens(chat, "steps", "<<you>>\n<<source:t-1.b0>>\nPlan it.\n<<ai>>\n<<source:t-1.b1..t-1.b2>>\nPlanned and looked.\n<<ai>>\n<<source:t-2.b1>>\nGable.", "", emptyMap())
            val lens = store.tree("$chat#steps")
            assertEquals(listOf("lens:steps.b0", "lens:steps.b1", "lens:steps.b2"), lens.headBlocks.map { it.address })
            assertEquals(listOf("t-1.b1..t-1.b2"), lens.block("lens:steps.b1")!!.sources, "a lens block keeps what it cites")
            assertTrue("## Turn 1 · lens:steps.b0" in ChatLogViews.outline(lens), "a lens outlines like a chat")

            val over = "<<summary>>\n<<source:lens:steps.b0..lens:steps.b2>>\nPlanned, looked, then a gable."
            assertEquals(emptyList(), ChatLogViews.lensProblems(lens, over), "a lens over a lens cites the lens's addresses")
            assertEquals("summary", ChatArchive.lensTree(ChatArchive.chats(dir).single(), "lens:x", "x", over).headBlocks.single().kind)
            store.writeLens("$chat#steps", "overview", over, "", emptyMap())
            assertEquals(listOf("$chat#steps#overview", "$chat#steps", chat), store.chain("$chat#steps#overview"))
            assertEquals("lens:overview.b0", store.tree("$chat#steps#overview").headBlocks.single().address)

            val transcript = ChatLogViews.addressedTranscript(lens.headBlocks)
            assertTrue("<<at:lens:steps.b1>>\n<<source:t-1.b1..t-1.b2>>" in transcript, "the viewer gets both the address and the citation")
            val back = parseAiChatBlocks(transcript)
            assertEquals(listOf("lens:steps.b0", "lens:steps.b1", "lens:steps.b2"), back.map { it.address })
            assertFailsWith<IllegalArgumentException> { store.tree("$chat#missing") }
        } finally {
            dir.deleteRecursively()
            lenses.deleteRecursively()
        }
    }
}
