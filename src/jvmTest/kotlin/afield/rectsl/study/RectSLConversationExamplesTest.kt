package afield.rectsl.study

import afield.stdlib.SourceStudy
import afield.testRepoRoot
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * An independent replay of the three recorded RectSL conversations under
 * `dev_docs/agents/demos/rectsl_conversation_{lanterns,tide,score}`.
 *
 * The conversations ran against a live window, so every frame the model saw came from the GPU. This
 * replays what they claim on the CPU reference — a different renderer — so a proof that only held
 * because of one renderer, or a revision that no longer passes the check, fails here:
 *
 *  - every saved revision (`studies/rectsl_source/`) still matches its id and passes [RectSLStudy.check];
 *  - the scene box holds the revision `HEAD` names;
 *  - every `CompareRectSLStudy` call the model made, read from the turn's saved tool I/O log, gives the
 *    same `preservedOutsideRegion` verdict again;
 *  - every person-side action (`*.human.json`) produced the revision it records;
 *  - the two claims the page makes in words: the tide's moon and shimmer did not change when the wave
 *    doubled its speed, and the score's longer accent changed one number.
 *
 * Nothing is written outside a temporary copy of each example's revisions.
 */
class RectSLConversationExamplesTest {
    private val mapper = jacksonObjectMapper()
    private val demos = testRepoRoot().resolve("dev_docs/agents/demos").toFile()
    private val examples = listOf("rectsl_conversation_lanterns", "rectsl_conversation_tide", "rectsl_conversation_score")

    private fun withReplay(example: String, block: (RectSLStudy, File) -> Unit) {
        val folder = File(demos, example)
        val temp = Files.createTempDirectory("rectsl-replay").toFile()
        try {
            val revisions = File(temp, "revisions").also { it.mkdirs() }
            File(folder, "studies/rectsl_source").listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(revisions, it.name)) }
            var source = File(folder, "rectsl_source_rectSL_.rectsl").readText()
            val study = RectSLStudy(revisions, { source }, { expected, next -> if (source != expected) false else { source = next; true } })
            study.resume()
            block(study, folder)
        } finally { temp.deleteRecursively() }
    }

    @Test fun everyRevisionStillPassesItsCheckAndTheBoxHoldsHead() {
        examples.forEach { example ->
            withReplay(example) { study, folder ->
                val ids = study.revisions.allRevisions().map { it.id }
                assertTrue(ids.size >= 4, "$example should record a conversation, found ${ids.size} revisions")
                ids.forEach { id ->
                    val revision = study.revisions.revision(id) // verifies the hash and re-runs the check
                    assertEquals(id.substringAfter('-'), SourceStudy.hash(revision.source).take(12))
                }
                assertEquals(File(folder, "rectsl_source_rectSL_.rectsl").readText(), study.revisions.revision().source,
                    "$example: the scene box should hold the revision HEAD names")
            }
        }
    }

    @Test fun everyRecordedComparisonGivesTheSameVerdictOnTheCpuReference() {
        var replayed = 0
        examples.forEach { example ->
            withReplay(example) { study, folder ->
                File(folder, "conversations/tool-io").listFiles().orEmpty().sortedBy { it.name }.forEach { log ->
                    val events = log.readLines().filter { it.isNotBlank() }.map { mapper.readTree(it) }
                    val calls = events.filter { it["event"]?.asText() == "tool_call" && it["toolName"]?.asText() == "CompareRectSLStudy" }
                    calls.forEach { call ->
                        val args = mapper.readTree(call["arguments"].asText())
                        val result = events.firstOrNull { it["event"]?.asText() == "tool_result" && it["callId"]?.asText() == call["callId"]?.asText() }
                            ?: fail("${log.name}: no result for ${call["callId"]}")
                        val recorded = mapper.readTree(result["result"].asText())["facts"]["preservedOutsideRegion"].asBoolean()
                        val replay = study.compare(
                            args["beforeRevision"].asText(), args["afterRevision"]?.asText() ?: "current",
                            args["seconds"]?.map { it.asDouble() } ?: emptyList(), 96,
                            args["mode"]?.asText() ?: "rgb",
                            args["allowedRegion"]?.map { it.asDouble() }?.takeIf { it.isNotEmpty() })
                        assertEquals(recorded, replay.facts["preservedOutsideRegion"], "$example ${log.name}: $args")
                        replayed++
                    }
                }
            }
        }
        assertTrue(replayed >= 3, "expected the recordings to contain comparisons to replay, found $replayed")
    }

    @Test fun everyPersonSideActionProducedTheRevisionItRecords() {
        examples.forEach { example ->
            withReplay(example) { study, folder ->
                val actions = File(folder, "conversations").listFiles().orEmpty().filter { it.name.endsWith(".human.json") }
                assertTrue(actions.isNotEmpty(), "$example should record a person-side action")
                actions.forEach { file ->
                    val record = mapper.readTree(file)
                    val action = record["action"].asText()
                    val after = record["revisionAfter"].asText()
                    if (action == "edit") {
                        assertEquals(true, record["dirtyAfter"].asBoolean(), "${file.name}: an editor edit leaves an unchecked change")
                    } else if (action == "cursor") {
                        // Putting the cursor on a line writes nothing; Inspect then says where the person is.
                        assertEquals(record["revisionBefore"].asText(), after, "${file.name}: a cursor makes no revision")
                        assertEquals(record["line"].asInt(), record["after"]["facts"]["personAt"]["line"].asInt(), "${file.name}: Inspect reports the cursor's line")
                    } else {
                        assertNotEquals(record["revisionBefore"].asText(), after, "${file.name}: $action should make a revision")
                        val description = study.revisions.revision(after).description
                        when (action) {
                            "adopt" -> assertTrue(description.startsWith("adopt variant"), "${file.name}: $description")
                            "keep" -> assertTrue(description.startsWith("numbers set by hand"), "${file.name}: $description")
                            "checkin" -> assertEquals("edited by hand", description)
                        }
                    }
                }
            }
        }
    }

    @Test fun theTidesMoonAndShimmerAreUnchangedByTheFasterWave() {
        withReplay("rectsl_conversation_tide") { study, _ ->
            val faster = study.revisions.revision("current")
            val before = study.revisions.revision(faster.parent!!)
            val a = RectSLStudy.drawable(before.source).scene
            val b = RectSLStudy.drawable(faster.source).scene
            var compared = 0
            for (step in 0 until 24) {
                val t = step * a.duration / 24
                val moonA = a.sample(t).rects.filter { it.id.startsWith("moon") }.associateBy { it.id }
                val moonB = b.sample(t).rects.filter { it.id.startsWith("moon") }.associateBy { it.id }
                assertEquals(moonA.keys, moonB.keys, "the same moon and shimmer rectangles at $t s")
                moonA.forEach { (id, r) -> assertEquals(r, moonB.getValue(id), "$id at $t s"); compared++ }
                val tideMoved = a.sample(t).rects.filter { it.id.startsWith("tide") }.zip(b.sample(t).rects.filter { it.id.startsWith("tide") })
                    .any { (x, y) -> x.size != y.size }
                if (step in 1..11 && step % 4 != 0) assertTrue(tideMoved, "the wave should differ at $t s")
            }
            assertTrue(compared >= 24 * 90, "expected 64 moon slices and 30 shimmer strokes at every time, compared $compared")
        }
    }

    @Test fun theLanternsThisOneWasTheLineThePersonWasOn() {
        withReplay("rectsl_conversation_lanterns") { study, folder ->
            // The model was told only "this one"; its first look at the study said where the person was.
            val log = File(folder, "conversations/tool-io/05-flame.jsonl").readLines().filter { it.isNotBlank() }.map { mapper.readTree(it) }
            val inspect = log.first { it["event"]?.asText() == "tool_call" && it["toolName"]?.asText() == "InspectRectSLStudy" }
            val seen = mapper.readTree(log.first { it["event"]?.asText() == "tool_result" && it["callId"] == inspect["callId"] }["result"].asText())
            assertEquals(36, seen["facts"]["personAt"]["line"].asInt())
            // And the revision it made changed that line, only its colour.
            val warmer = study.revisions.revision("current")
            val before = study.revisions.revision(warmer.parent!!)
            val changed = before.source.lines().zip(warmer.source.lines()).withIndex().filter { (_, p) -> p.first != p.second }
            assertEquals(listOf(35), changed.map { it.index }, "only line 36 differs: $changed")
            val (x, y) = changed.single().value
            assertEquals(x.replace(Regex("0x[0-9a-f]{6}"), "C"), y.replace(Regex("0x[0-9a-f]{6}"), "C"), "and only its colour: $x → $y")
        }
    }

    @Test fun theScoresLongerAccentChangedOneNumber() {
        withReplay("rectsl_conversation_score") { study, _ ->
            val accent = study.revisions.revision("current")
            val before = study.revisions.revision(accent.parent!!)
            val changed = before.source.lines().zip(accent.source.lines()).filter { (x, y) -> x != y }
            assertEquals(before.source.lines().size, accent.source.lines().size)
            assertEquals(1, changed.size, "one line should differ: $changed")
            val (x, y) = changed.single()
            assertTrue("linear-track \"four-beats\"" in x && "0.22=0.62" in x && "0.34=0.62" in y, "the release moved from 0.22 to 0.34 s: $x → $y")
            assertEquals(x.replace("0.22=0.62", "0.34=0.62"), y, "and nothing else on that line")
        }
    }
}
