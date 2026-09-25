package afield

import afield.docs.CitationGuard
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A new writer into a Field document directory cannot be added without declaring itself.
 *
 * Three producers were found on 2026-09-07 writing into a document a user had opened read-only —
 * `writeFileProxyContents`, `createProxyTarget`, and the `saved {}` delegate — and the reason all
 * three survived is that **nothing counted them**. `SavingContext.save` has a funnel guard and the
 * autosave loop has four session-level gates, so anyone reasoning about read-only looked at those
 * nine sites and concluded the surface was covered. It was not, and there was no way to find out
 * short of reading 870 files.
 *
 * So this counts them. It derives the population rather than listing it: every `src/jvmMain`
 * Kotlin file that contains **both** a filesystem write primitive **and** an expression that
 * derives a path from a session or document, and asserts that set against a ledger which classifies
 * each entry as gated or explicitly exempt with a reason.
 *
 * ### What this can and cannot catch
 *
 * It catches a **new file** that starts writing into a document directory: it fails as
 * "undeclared". It catches a new write added to a file currently on the ungated list, because those
 * carry an exact primitive count. It catches a gate being *deleted* from one of the gated files.
 *
 * It does **not** prove a gate is on the right code path — [GATED] means the file mentions
 * `ReadOnlyDocuments`, `writesToDisk` or `readOnly` somewhere, not that the mention guards the
 * write beside it. `ReadOnlyWriteSurfaceTest` and `DocumentWriteBackLedgerTest` are what actually
 * assert refusal behaviour; this is the inventory that tells you where to point them. Nor is it a
 * proof of reachability: several entries here are dev harnesses writing into their own temp
 * directories, which is why the ledger carries a reason per line rather than a bare path.
 *
 * A file-level rather than function-level ledger on purpose. Function-level would be the better
 * unit and is not derivable with a regex over Kotlin — a brace-matching parse that got it subtly
 * wrong would be worse than a coarse ledger that is honest about being coarse.
 */
class DocumentDirectoryWriterLedgerTest {

    /**
     * Filesystem write primitives. `safeSave` is Field's own atomic writer, `mkdirs` counts because
     * creating a directory inside a document is itself a write the read-only promise covers, and
     * `ensureDirectory`'s ungated `mkdirs` is a real instance of exactly that.
     */
    private val writePrimitive = Regex(
        """\.writeText\(|\.writeBytes\(|\.safeSave\(|\.mkdirs\(\)|Files\.writeString\(|Files\.write\(|Files\.createDirectories\(|\.createNewFile\(\)"""
    )

    /**
     * Expressions that mean "this path came from a document or a session".
     *
     * `SessionInformation.filename` is a *directory* path despite the name, which is why so many of
     * these read like a file reference and are not one.
     */
    private val documentPathExpression = Regex(
        """inside\.filename|thisSessionInfo\.get\(\)\.filename|info\.filename|session\.filename|File\(documentPath|normalizeDocumentPath|documentDir|documentDirectory"""
    )

    /** A file that at least mentions the read-only predicate. See the class KDoc for the caveat. */
    private val gateMention = Regex("""ReadOnlyDocuments|writesToDisk|\breadOnly\b""")

    @Test
    fun everyFileWritingIntoADocumentDirectoryIsGatedOrDeclared() {
        val root = CitationGuard.repoRoot()
        val sources = jvmMainKotlinFiles(root)

        // Premise: we are searching the population we claim to. A pathspec that quietly matched
        // nothing would make every assertion below pass vacuously — and this repository's shell is
        // zsh, where exactly that failure has happened before.
        assertTrue(
            sources.size >= 800,
            "premise failed: only ${sources.size} jvmMain Kotlin files found, so this survey is not " +
                "searching the source set it claims to (870 on 2026-09-07)"
        )

        val found = mutableMapOf<String, Int>()
        val gated = mutableSetOf<String>()
        sources.forEach { (relative, text) ->
            if (!writePrimitive.containsMatchIn(text)) return@forEach
            if (!documentPathExpression.containsMatchIn(text)) return@forEach
            found[relative] = writePrimitive.findAll(text).count()
            if (gateMention.containsMatchIn(text)) gated += relative
        }

        // Premise: the detector works. These four are known to write into a document directory and
        // to consult the predicate — two of them because this branch made them. If the regexes ever
        // stop matching them, the ledger below is measuring nothing.
        listOf(
            "src/jvmMain/kotlin/afield/server/fileproxy.kt",
            "src/jvmMain/kotlin/afield/stdlib/saved.kt",
            "src/jvmMain/kotlin/afield/history.kt",
            "src/jvmMain/kotlin/afield/Server.kt"
        ).forEach {
            assertTrue(
                it in found,
                "premise failed: $it is a known document-directory writer and the detector missed it"
            )
            assertTrue(
                it in gated,
                "premise failed: $it consults the read-only predicate and the detector missed it"
            )
        }

        assertEquals(
            LEDGER.keys.toSortedSet(),
            found.keys.toSortedSet(),
            "the set of jvmMain files that write into a path derived from a document has changed.\n" +
                "A new entry means new code can write into a document directory. Decide whether a " +
                "read-only session can reach it:\n" +
                "  - if it can, gate it on ReadOnlyDocuments.blocksWritesFrom(box), " +
                "blocksWritesFromCurrentSession() or blocksWritesTo(path), log the refusal through " +
                "ReadOnlyDocuments.refuseWrite, and add it to LEDGER as GATED;\n" +
                "  - if it cannot, add it to LEDGER with a one-line reason saying why.\n" +
                "A missing entry means a writer was removed or renamed: delete its line."
        )

        // The ungated entries carry their write-primitive count, so adding a write to a file that
        // is already exempt fails too rather than hiding behind an entry that already exists.
        val ungatedCounts = found.filterKeys { it !in gated }.toSortedMap()
        val expectedUngated = LEDGER.filterValues { it != GATED }.mapValues { it.value.writes }.toSortedMap()
        assertEquals(
            expectedUngated,
            ungatedCounts,
            "the number of filesystem writes in an ungated document-directory writer has changed. " +
                "If you added one, it needs the same decision as a new file (see above). If you " +
                "removed one, lower the number here. If you gated the file, move it to GATED."
        )
    }

    /** Every tracked `src/jvmMain` Kotlin file, as relative path to contents. */
    private fun jvmMainKotlinFiles(root: File): List<Pair<String, String>> {
        val base = root.toPath()
        return File(root, "src/jvmMain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { base.relativize(it.toPath()).toString().replace(File.separatorChar, '/') to it.readText() }
            .toList()
    }

    private data class Entry(val writes: Int, val why: String)

    companion object {
        /** Sentinel for "this file consults the read-only predicate"; its write count is not pinned. */
        private val GATED = DocumentDirectoryWriterLedgerTest.Entry(-1, "gated")

        private fun exempt(writes: Int, why: String) = DocumentDirectoryWriterLedgerTest.Entry(writes, why)

        /**
         * Every jvmMain file that writes to a document-derived path, as of 2026-09-23.
         *
         * Nineteen files: seven gated, twelve not. The twelve are **not** all bugs — most write
         * somewhere a read-only session never reaches. The ones that are genuinely reachable are
         * marked `REACHABLE`, and they are the backlog this test exists to keep visible rather than
         * a claim that tonight fixed them. They were surveyed by an audit of 693 raw write-primitive
         * hits across 178 files; see `dev_docs/document_write_back.md`.
         */
        private val LEDGER: Map<String, Entry> = mapOf(
            // ---- consult the read-only predicate ----
            "src/jvmMain/kotlin/afield/Server.kt" to GATED,
            "src/jvmMain/kotlin/afield/agent_backdoor.kt" to GATED,
            // Arrived 2026-09-23 with the AI4 function workbench (`948602d5a`). Two separate
            // things matched here and both are answered. The mutator, `patch`, opens on
            // `check(!info.readOnly && !ReadOnlyDocuments.global)` and then edits through
            // `setPropertyRaw`, with the sidecar flush behind `info.writesToDisk` — it performs no
            // filesystem write of its own. The raw writes the detector counted (`install.yaml`,
            // the guide copies, `provenance.yaml`) all belong to the *export* path, which builds
            // them in a temp directory under a destination that line 83 explicitly
            // `require`s to be outside the live document. So nothing here writes into a document
            // directory; the file matches because this ledger is deliberately file-level.
            "src/jvmMain/kotlin/afield/ai/core/DocumentSourceTools.kt" to GATED,
            "src/jvmMain/kotlin/afield/history.kt" to GATED,
            "src/jvmMain/kotlin/afield/server/fileproxy.kt" to GATED,
            // Reachable: the Guides panel's save and the AI4 create/patch/append tools both write
            // <doc>/*.md, <doc>/docs/** and <doc>/guides/**. Gated on the read-only predicate at
            // every mutator — `blocksWritesFromCurrentSession()` for the handler, `info.readOnly`
            // for the tool factory — and each refusal goes through `refuseWrite`.
            "src/jvmMain/kotlin/afield/server/documentGuides.kt" to GATED,
            "src/jvmMain/kotlin/afield/stdlib/saved.kt" to GATED,

            // ---- ungated and reachable from a read-only session: the backlog ----
            "src/jvmMain/kotlin/afield/operation_transcript.kt" to exempt(
                11,
                "REACHABLE. The macro/transcript command handler writes <doc>/operations.yaml and " +
                    "<doc>/macros/*.yaml, and `reveal` mkdirs <doc>/macros/ outright. Wants the same " +
                    "treatment the three writers got, red-first."
            ),
            "src/jvmMain/kotlin/afield/stdlib/FileManager.kt" to exempt(
                5,
                "REACHABLE. `documentFilesDirectory` calls createDirectories in a *getter* on the " +
                    "path of every filename resolution from box code, so resolving \"data.csv\" " +
                    "creates <doc>/files/. Its own KDoc already warns that asking has a side effect."
            ),
            "src/jvmMain/kotlin/afield/HistoryMetrics.kt" to exempt(
                5,
                "REACHABLE but conditional. Opening the history panel can rewrite " +
                    "<doc>/history_metrics.edn, though recordEditStats bails unless a record for " +
                    "that version already exists. Its screenshot writers are gated only by their " +
                    "single callers, not by a predicate."
            ),
            "src/jvmMain/kotlin/afield/SharedTypes.kt" to exempt(
                1,
                "REACHABLE. `Box.sharedTypes {}` publishes into <doc>/.field-shared-types/gen-N/; " +
                    "the receiving parameter is called `workspace` but is handed the document dir."
            ),

            // ---- ungated, and out of scope with a reason ----
            "src/jvmMain/kotlin/afield/workspace/dir.kt" to exempt(
                9,
                "Workspace lifecycle: create/delete/duplicate/zip a document. Explicit destructive " +
                    "user actions on a different document than the one open, so a read-only policy " +
                    "for them is a separate decision, not a silent write-back."
            ),
            "src/jvmMain/kotlin/afield/workspace/transcript.kt" to exempt(
                2,
                "Writes <doc>/transcript.edn, but its only caller is reached from SavingContext.save, " +
                    "already behind the funnel guard. Caller-side protection, hence listed."
            ),
            "src/jvmMain/kotlin/afield/docs/screenshot.kt" to exempt(
                9,
                "Paths come from Path.of(request.path) — explicit agent/tool-chosen export targets."
            ),
            "src/jvmMain/kotlin/afield/ai/SemanticRetrievalService.kt" to exempt(
                2,
                "Lucene index and model cache under cacheRoot, not a document directory."
            ),
            "src/jvmMain/kotlin/afield/execution.kt" to exempt(
                2,
                ".field-backdoor/logs/completion-trace.log, behind an off-by-default flag."
            ),
            "src/jvmMain/kotlin/afield/examples/HistoryFileInlineSmokeTest.kt" to exempt(
                4,
                "Dev smoke harness; builds and writes its own temp document."
            ),
            "src/jvmMain/kotlin/afield/examples/SemanticRetrievalSmokeTest.kt" to exempt(
                5,
                "Dev smoke harness; writes its own temp fixture."
            ),
            "src/jvmMain/kotlin/afield/examples/stdlib/FileManagerSmokeTest.kt" to exempt(
                11,
                "Dev smoke harness for FileManager; writes its own temp workspace."
            )
        )
    }
}
