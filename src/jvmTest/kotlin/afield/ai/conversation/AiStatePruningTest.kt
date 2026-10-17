package afield.ai.conversation

import afield.document.graph.Box
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Saved AI4 box states: what the orphan sweep and the explicit prune keep, on session trees written as they are on disk. */
class AiStatePruningTest {
    private fun node(id: String, parent: String?, after: String?, before: String? = null): String {
        val branch = listOfNotNull(before?.let { "\"before\":{\"canvas\":\"$it\"}" }, after?.let { "\"after\":{\"canvas\":\"$it\"}" })
        val b = if (branch.isEmpty()) "" else ",\"branch\":{${branch.joinToString(",")}}"
        return "{\"t\":\"turn\",\"id\":\"$id\",\"parent\":${parent?.let { "\"$it\"" } ?: "null"},\"markdown\":\"\"$b}"
    }

    private fun session(vararg lines: String): AiSessionTree.Session {
        val box = Box("s")
        box.setPropertyRaw(AiSessionTree.PROPERTY, (listOf("{\"t\":\"header\",\"v\":1}") + lines).joinToString("\n"), false)
        return AiSessionTree.load(box)
    }

    private fun folder(vararg shas: String, ageMillis: Long = AiStatePruning.GRACE_MILLIS * 2): File {
        val dir = createTempDirectory("ai4-state-test").toFile()
        shas.forEach { File(dir, "$it.state.gz").apply { writeText(it); setLastModified(System.currentTimeMillis() - ageMillis) } }
        return dir
    }

    private fun names(r: AiStatePruning.Report) = r.removed.map { it.name.removeSuffix(".state.gz") }.toSet()

    @Test
    fun theSweepRemovesOnlyStatesNoConversationRefersTo() {
        // Two boxes' conversations share state "shared"; "orphan" belonged to a cleared conversation.
        val a = session(node("1", null, "a1", before = "shared"), node("2", "1", "a2"))
        val b = session(node("1", null, "shared"))
        val dir = folder("a1", "a2", "shared", "orphan")
        val dry = AiStatePruning.sweep(dir, AiStatePruning.referenced(listOf(a, b)), dryRun = true)
        assertEquals(setOf("orphan"), names(dry))
        assertTrue(File(dir, "orphan.state.gz").exists(), "a dry run deletes nothing")
        AiStatePruning.sweep(dir, AiStatePruning.referenced(listOf(a, b)), dryRun = false)
        assertEquals(setOf("a1", "a2", "shared"), dir.list()!!.map { it.removeSuffix(".state.gz") }.toSet())
    }

    @Test
    fun aStateJustWrittenIsNeverSwept() {
        // A request still writing its turn has stored its state but not yet recorded it.
        val dir = folder("inflight", ageMillis = 1000)
        assertEquals(emptySet(), names(AiStatePruning.sweep(dir, emptySet(), dryRun = false)))
        assertTrue(File(dir, "inflight.state.gz").exists())
    }

    @Test
    fun pruningKeepsTheLatestStatesOfEveryBranchAndEveryFork() {
        // 1 → 2 → 3 → 4 on one branch; 2 forks into 5. Keep the latest two per branch.
        val s = session(node("1", null, "s1"), node("2", "1", "s2"), node("3", "2", "s3"), node("4", "3", "s4"), node("5", "2", "s5"))
        val keep = AiStatePruning.retained(listOf(s), keepPerBranch = 2)
        assertEquals(setOf("s3", "s4", "s2", "s5"), keep, "s2 is kept as the fork and as the latest-but-one of the short branch")
        val dir = folder("s1", "s2", "s3", "s4", "s5")
        assertEquals(setOf("s1"), names(AiStatePruning.sweep(dir, keep, dryRun = false)))
    }
}
