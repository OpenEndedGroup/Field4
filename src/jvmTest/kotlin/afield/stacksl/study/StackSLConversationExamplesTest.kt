package afield.stacksl.study

import afield.stdlib.StudyEvidence
import afield.stdlib.SourceStudy
import afield.testRepoRoot
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * An independent replay of the three recorded StackSL conversations under
 * `dev_docs/agents/demos/stacksl_conversation_{ink,tiles,aurora}`.
 *
 * The conversations ran against a live window, so every render the model saw came from the GPU. This
 * replays what they claim on the CPU reference — a different renderer — so a proof that only held
 * because of one renderer, or a revision that no longer passes the check, fails here:
 *
 *  - every saved revision (`studies/stacksl_source/`) still matches its id and passes [StackSLStudy.check];
 *  - the program box holds the revision `HEAD` names;
 *  - every `CompareStackSLStudy` call the model made, read from the turn's saved tool I/O log, gives
 *    the same `preservedOutsideRegion` verdict again;
 *  - every person-side action (`*.human.json`) produced the revision it records.
 *
 * Renders are small (32 px) because stage comparisons are exact at any size and the CPU reference is
 * slow; nothing is written outside a temporary copy of each example's revisions.
 */
class StackSLConversationExamplesTest {
    private val mapper = jacksonObjectMapper()
    private val demos = testRepoRoot().resolve("dev_docs/agents/demos").toFile()
    private val examples = listOf("stacksl_conversation_ink", "stacksl_conversation_tiles", "stacksl_conversation_aurora")

    private fun withReplay(example: String, block: (StackSLStudy, File) -> Unit) {
        val folder = File(demos, example)
        val temp = Files.createTempDirectory("stacksl-replay").toFile()
        try {
            val revisions = File(temp, "revisions").also { it.mkdirs() }
            File(folder, "studies/stacksl_source").listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(revisions, it.name)) }
            var source = File(folder, "stacksl_source_stackSL_.stacksl").readText()
            val study = StackSLStudy(revisions, { source }, { expected, next -> if (source != expected) false else { source = next; true } })
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
                assertEquals(File(folder, "stacksl_source_stackSL_.stacksl").readText(), study.revisions.revision().source,
                    "$example: the program box should hold the revision HEAD names")
            }
        }
    }

    @Test fun everyRecordedComparisonGivesTheSameVerdictOnTheCpuReference() {
        var replayed = 0
        examples.forEach { example ->
            withReplay(example) { study, folder ->
                File(folder, "conversations/tool-io").listFiles().orEmpty().sortedBy { it.name }.forEach { log ->
                    val events = log.readLines().filter { it.isNotBlank() }.map { mapper.readTree(it) }
                    val calls = events.filter { it["event"]?.asText() == "tool_call" && it["toolName"]?.asText() == "CompareStackSLStudy" }
                    calls.forEach { call ->
                        val args = mapper.readTree(call["arguments"].asText())
                        val result = events.firstOrNull { it["event"]?.asText() == "tool_result" && it["callId"]?.asText() == call["callId"]?.asText() }
                            ?: fail("${log.name}: no result for ${call["callId"]}")
                        val recorded = mapper.readTree(result["result"].asText())["facts"]["preservedOutsideRegion"].asBoolean()
                        val replay = study.compare(
                            args["beforeRevision"].asText(), args["afterRevision"]?.asText() ?: "current",
                            args["seconds"]?.map { it.asDouble() } ?: emptyList(), 32,
                            args["mode"]?.asText() ?: "rgb",
                            args["allowedRegion"]?.map { it.asDouble() }?.takeIf { it.isNotEmpty() },
                            args["beforeStage"]?.asInt()?.takeIf { it > 0 }, args["afterStage"]?.asInt()?.takeIf { it > 0 })
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

    @Test fun theHalfSpeedAuroraIsTheOriginalAtHalfTheTime() {
        withReplay("stacksl_conversation_aurora") { study, _ ->
            val all = study.revisions.allRevisions()
            val slowed = all.last { it.description.startsWith("Half-speed", ignoreCase = true) }
            val original = study.revisions.revision(slowed.parent!!)
            val half = study.revisions.revision(slowed.id)
            for (t in listOf(1.0, 2.0)) {
                val a = StackSLStudyShader.renderRaw(original.source, 32, t)
                val b = StackSLStudyShader.renderRaw(half.source, 32, 2 * t)
                val (difference, _) = StudyEvidence.difference(a, b, null, false)
                assertTrue(difference.max <= 1.0, "half-speed at ${2 * t}s should be the original at ${t}s: $difference")
            }
        }
    }
}
