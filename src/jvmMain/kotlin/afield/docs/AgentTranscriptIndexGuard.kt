package afield.docs

import afield.examples.AgentTranscriptMaterializer
import afield.examples.DemoTranscriptExecSmokeTest
import java.io.File
import kotlin.system.exitProcess

/*
FIELD_TEST
id: docs.agent-transcript-index.guard
runner: jvm-main
entrypoint: afield.docs.AgentTranscriptIndexGuard
tags: [smoke, docs, agents, transcript, verification]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "AgentTranscriptIndexGuard ok"
*/
/**
 * Fails the build when `public/docs/agent_transcripts.md` stops being a true index of
 * `dev_docs/agents/transcripts/`.
 *
 * ### The failure this exists to stop
 *
 * The page calls itself "the illustrated index" of the transcript corpus and is the public front door
 * to Field's agent-automation story. On `2026-08-06` it listed 28 of 52 transcripts while asserting
 * that *"every one of them is exercised end-to-end in CI"* — 42 % incomplete, in a sentence claiming
 * completeness. The shape of that failure is worth naming, because it is the argument for this file:
 * the *generated* half of the same system was fine. `AgentTranscriptMaterializer` and
 * `checkAgentTranscripts` guard the committed starting-point bundle byte-for-byte, so that stayed
 * current for months. The hand-curated page beside it, which nothing checked, drifted the moment
 * anybody added a transcript without remembering it existed.
 *
 * The page is deliberately **not** generated. Its entries quote the number a box prints and say why
 * that number is the interesting one — `0.985 forwards, 0.246 backwards on a parabola`; `473 diagonal
 * breaks against 0`. None of that is in the yaml, which carries op verbs, box ids, rects and Kotlin
 * source; a generator over it could emit a name and a box count, which would replace the writing with
 * a table. So the trade here is [KDocAttachmentGuard]'s, not [StdlibReferenceGenerator]'s: keep the
 * prose hand-written and make its *incompleteness* a build failure, so a new transcript cannot ship
 * without somebody writing a sentence about it.
 *
 * ### The five things it checks
 *
 * 1. **Completeness** — every `*.yaml` in the corpus has exactly one entry. An entry is a `###`
 *    heading whose whole text is the transcript's name, `### transcript_name`, at column zero.
 *    (Consequence worth knowing: `###` on that page is reserved for entries, so a sub-heading inside
 *    an entry has to be `####` or deeper; a `###` naming anything else is read as an entry for a
 *    transcript that does not exist. The entries were bold run-in lead-ins until `2026-09-13`, when
 *    102 of them had become one wall of text with no way to link or scroll to a single scene.)
 * 2. **No orphans** — every entry names a transcript that is still in the corpus, so deleting or
 *    renaming a yaml turns the stale paragraph red instead of leaving it to be read as current.
 * 3. **The CI claim, per entry** — each `##` section declares how CI verifies the scenes under it,
 *    with an invisible marker line beneath the heading:
 *    ```
 *    <!-- transcript-index: verified-by = execute -->
 *    ```
 *    The value is one or more of `execute`, `compile`, `kotlinjs`, naming
 *    [DemoTranscriptExecSmokeTest.EXECUTE], [DemoTranscriptExecSmokeTest.COMPILE_ONLY] and
 *    [DemoTranscriptExecSmokeTest.KOTLINJS] — which is where classification is decided, and stays the
 *    single source of truth. An entry whose transcript is not in one of its section's declared sets is
 *    a failure, which is what makes the section prose ("these build and run headless", "each one
 *    builds and compiles in CI") an accurate statement about every entry beneath it rather than about
 *    most of them. The marker is an HTML comment, so it renders as nothing.
 * 4. **Open ↗ links that exist** — a transcript is offered as an openable starting point exactly when
 *    the materializer ships it, i.e. when it is not in [AgentTranscriptMaterializer.EXCLUDE]. So an
 *    entry must carry an `assets/agent_transcripts/<name>/metadata.edn` link when its transcript is
 *    materialized and must not when it is not, and the committed bundle directory must really be
 *    there. This is the check that stops the page advertising a document the welcome screen cannot
 *    open.
 * 5. **The counts in the CI paragraph** — the paragraph marked
 *    ```
 *    <!-- transcript-index: totals -->
 *    ```
 *    must contain, as standalone numbers, the corpus size and the size of each of the three sets. Four
 *    numerals, so the reader gets told the shape of the verification, and adding a transcript makes
 *    exactly one of them wrong until someone looks.
 *
 * ### Running it
 *
 * ```
 * ./gradlew checkAgentTranscriptIndex          # also runs as part of `check`
 * ```
 * Reads five kinds of text file and nothing else: no Field server, no runloop, no boxes, no GPU. That
 * is deliberate — its heavyweight sibling `checkAgentTranscripts` builds real boxes and costs ~25 s,
 * and a completeness check has no business being that expensive. See
 * `dev_docs/agent_transcript_index.md` for how to add an entry.
 */
object AgentTranscriptIndexGuard {

    private const val INDEX_REL = "public/docs/agent_transcripts.md"
    private const val CORPUS_REL = "dev_docs/agents/transcripts"
    private const val BUNDLE_REL = "public/docs/assets/agent_transcripts"

    /** How CI verifies a transcript — the three sets [DemoTranscriptExecSmokeTest] partitions it into. */
    private enum class Level(val marker: String, val describe: String) {
        EXECUTE("execute", "replayed end to end"),
        COMPILE_ONLY("compile", "compiled, not run"),
        KOTLINJS("kotlinjs", "compiled to JavaScript"),
    }

    /** One `### name` heading, with the section it sits in and the Open link (if any) beneath it. */
    private data class Entry(val name: String, val line: Int, val section: Section?, val openTarget: String?)

    /** One `##` heading, with the CI levels its marker line declares. */
    private data class Section(val title: String, val line: Int, val levels: Set<Level>)

    private val ENTRY = Regex("""^###\s+([a-z0-9_]+)\s*$""")
    private val HEADING = Regex("""^##\s+(.*\S)\s*$""")
    private val VERIFIED_BY = Regex("""<!--\s*transcript-index:\s*verified-by\s*=\s*([a-z, ]+?)\s*-->""")
    private val TOTALS = Regex("""<!--\s*transcript-index:\s*totals\s*-->""")
    private val OPEN_LINK = Regex("""assets/agent_transcripts/([a-z0-9_]+)/metadata\.edn""")

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = repoRoot()
        val index = File(repo, INDEX_REL)
        val corpusDir = File(repo, CORPUS_REL)
        require(index.isFile) { "no index page at ${index.absolutePath}" }
        require(corpusDir.isDirectory) { "no transcript corpus at ${corpusDir.absolutePath}" }

        val corpus = (corpusDir.listFiles { f -> f.isFile && f.extension == "yaml" } ?: emptyArray())
            .map { it.nameWithoutExtension }
            .sorted()
        require(corpus.isNotEmpty()) { "no transcript yaml in ${corpusDir.absolutePath}" }

        val lines = index.readLines()
        val problems = mutableListOf<String>()

        // ---- parse the page ----
        val sections = mutableListOf<Section>()
        val entries = mutableListOf<Entry>()
        var current: Section? = null
        var totalsParagraph: String? = null
        // A fenced code block on this page can legitimately contain a `## …` line or an entry-shaped
        // line (the "Running one" fence does not, but a future one might), and reading either as page
        // structure would be a confusing false failure. So fences are skipped rather than parsed.
        var inFence = false
        for ((i, raw) in lines.withIndex()) {
            if (raw.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) continue
            val heading = HEADING.find(raw)
            if (heading != null) {
                val title = heading.groupValues[1]
                current = Section(title, i + 1, declaredLevels(lines, i, title, problems))
                sections.add(current)
            }
            val entry = ENTRY.find(raw)
            if (entry != null) {
                entries.add(Entry(entry.groupValues[1], i + 1, current, openLinkTarget(lines, i)))
            }
            if (TOTALS.containsMatchIn(raw)) {
                totalsParagraph = lines.drop(i + 1).takeWhile { it.isNotBlank() }.joinToString(" ")
            }
        }

        // ---- 1 & 2: completeness and orphans ----
        val byName = entries.groupBy { it.name }
        for (name in corpus) {
            val found = byName[name]
            when {
                found == null -> problems.add(
                    "$name: in the corpus (dev_docs/agents/transcripts/$name.yaml) but has NO entry on " +
                        "$INDEX_REL. Write one: a `### $name` heading and a paragraph under the section whose " +
                        "verified-by marker matches how CI checks it. See dev_docs/agent_transcript_index.md."
                )
                found.size > 1 -> problems.add(
                    "$name: ${found.size} entries on $INDEX_REL (lines ${found.joinToString(", ") { it.line.toString() }}) — keep one."
                )
            }
        }
        for (entry in entries) {
            if (entry.name !in corpus) problems.add(
                "line ${entry.line}: entry `### ${entry.name}` names no transcript — dev_docs/agents/transcripts/" +
                    "${entry.name}.yaml does not exist. Delete the entry, or fix the name if the yaml was renamed."
            )
        }

        // ---- 3: each entry's CI level is one its section declares ----
        val sectionless = sections.filter { it.levels.isEmpty() && entries.any { e -> e.section == it } }
        for (section in sectionless) problems.add(
            "line ${section.line}: section \"${section.title}\" holds entries but declares no CI level. Add a " +
                "`<!-- transcript-index: verified-by = execute -->` marker (or compile / kotlinjs, comma-separated) " +
                "under the heading."
        )
        for (entry in entries) {
            if (entry.name !in corpus) continue
            val level = levelOf(entry.name)
            val section = entry.section
            if (level == null) {
                problems.add(
                    "${entry.name}: not in EXECUTE, COMPILE_ONLY or KOTLINJS in DemoTranscriptExecSmokeTest, so " +
                        "nothing in CI verifies it and the page cannot honestly say how it is checked."
                )
                continue
            }
            if (section == null) {
                problems.add("line ${entry.line}: entry `### ${entry.name}` sits above the first `##` section heading.")
                continue
            }
            if (section.levels.isNotEmpty() && level !in section.levels) problems.add(
                "${entry.name}: entry at line ${entry.line} is under \"${section.title}\", which declares " +
                    "verified-by = ${section.levels.joinToString(", ") { it.marker }}, but CI verifies this " +
                    "transcript as ${level.marker} (${level.describe}). Move the entry, or widen the section's marker " +
                    "and its prose."
            )
        }

        // ---- 4: Open links exist exactly for materialized transcripts, and resolve ----
        val bundleRoot = File(repo, BUNDLE_REL)
        for (entry in entries) {
            if (entry.name !in corpus) continue
            val materialized = entry.name !in AgentTranscriptMaterializer.EXCLUDE
            val target = entry.openTarget
            when {
                materialized && target == null -> problems.add(
                    "${entry.name}: entry at line ${entry.line} has no `Open ↗` link, but the transcript IS " +
                        "materialized into $BUNDLE_REL/${entry.name}/ and is offered on the welcome screen. Link it: " +
                        "[Open ↗](/offline.html?file=/field4/assets/agent_transcripts/${entry.name}/metadata.edn)"
                )
                !materialized && target != null -> problems.add(
                    "${entry.name}: entry at line ${entry.line} links a starting point, but the transcript is in " +
                        "AgentTranscriptMaterializer.EXCLUDE, so no document is generated for it and the link is dead. " +
                        "Drop the link and say in the prose why this one is agent-only."
                )
                target != null && target != entry.name -> problems.add(
                    "${entry.name}: entry at line ${entry.line} links $BUNDLE_REL/$target/metadata.edn — the wrong " +
                        "transcript's starting point (a copy-paste)."
                )
                target != null && !File(File(bundleRoot, target), "metadata.edn").isFile -> problems.add(
                    "${entry.name}: entry at line ${entry.line} links $BUNDLE_REL/$target/metadata.edn, which is not " +
                        "committed. Regenerate with `./gradlew regenerateAgentTranscriptStartingPoints`."
                )
            }
        }

        // ---- 5: the counts in the CI paragraph ----
        val expected = buildList {
            add(corpus.size to "the corpus size")
            Level.entries.forEach { level -> add(setFor(level).size to "the ${level.marker} set") }
        }
        val totals = totalsParagraph
        if (totals == null) {
            problems.add(
                "$INDEX_REL has no `<!-- transcript-index: totals -->` marker. Put it directly above the paragraph " +
                    "that quotes how many transcripts there are and how each is verified."
            )
        } else {
            val numbers = Regex("""\d+""").findAll(totals).map { it.value }.toSet()
            for ((n, what) in expected) {
                if (n.toString() !in numbers) problems.add(
                    "the totals paragraph does not mention $n — $what. It currently reads: " +
                        "\"${totals.trim()}\". Expected numbers: " +
                        expected.joinToString(", ") { "${it.first} (${it.second})" }
                )
            }
        }

        if (problems.isNotEmpty()) {
            System.err.println("AgentTranscriptIndexGuard FAILED with ${problems.size} problem(s):")
            problems.forEach { System.err.println("  - $it") }
            System.err.println(
                "\n$INDEX_REL is the public index of the transcript corpus. It is hand-written on purpose — see " +
                    "dev_docs/agent_transcript_index.md — so these are fixed by editing prose, not by regenerating."
            )
            exitProcess(1)
        }
        println(
            "AgentTranscriptIndexGuard ok — ${corpus.size} transcript(s), ${entries.size} entry/entries, " +
                sections.filter { it.levels.isNotEmpty() }
                    .joinToString("; ") { "\"${it.title}\" = ${it.levels.joinToString("+") { l -> l.marker }}" }
        )
    }

    /**
     * The CI levels the `verified-by` marker under the heading at [headingIndex] declares. The marker
     * is looked for on the next few lines only, so it has to sit under its heading rather than
     * anywhere in the section; an unrecognised token is reported into [problems].
     */
    private fun declaredLevels(
        lines: List<String>, headingIndex: Int, title: String, problems: MutableList<String>,
    ): Set<Level> {
        for (j in headingIndex + 1 until minOf(headingIndex + 5, lines.size)) {
            val match = VERIFIED_BY.find(lines[j]) ?: continue
            val levels = mutableSetOf<Level>()
            for (token in match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val level = Level.entries.firstOrNull { it.marker == token }
                if (level == null) {
                    problems.add(
                        "line ${j + 1}: section \"$title\" declares verified-by = \"$token\", which is not one of " +
                            Level.entries.joinToString("/") { it.marker }
                    )
                } else levels.add(level)
            }
            return levels
        }
        return emptySet()
    }

    /**
     * The transcript name in the `Open ↗` link belonging to the entry starting at [entryIndex] — the
     * first such link before the next entry, the next heading, or the end of the page — or null when
     * this entry offers no starting point.
     */
    private fun openLinkTarget(lines: List<String>, entryIndex: Int): String? {
        for (j in entryIndex until lines.size) {
            if (j > entryIndex && (ENTRY.containsMatchIn(lines[j]) || HEADING.containsMatchIn(lines[j]))) return null
            OPEN_LINK.find(lines[j])?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun setFor(level: Level): Set<String> = when (level) {
        Level.EXECUTE -> DemoTranscriptExecSmokeTest.EXECUTE
        Level.COMPILE_ONLY -> DemoTranscriptExecSmokeTest.COMPILE_ONLY
        Level.KOTLINJS -> DemoTranscriptExecSmokeTest.KOTLINJS
    }

    private fun levelOf(name: String): Level? = Level.entries.firstOrNull { name in setFor(it) }

    /**
     * The repository root, found by walking up from the working directory looking for the pair of
     * directories only the repo root has — the same rule [KDocAttachmentGuard] and
     * [StdlibReferenceGenerator] use, so the verification tasks agree about where the tree is however
     * Gradle sets the working directory.
     */
    private fun repoRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (File(candidate, "public/docs").isDirectory && File(candidate, "src/jvmMain/kotlin").isDirectory) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
