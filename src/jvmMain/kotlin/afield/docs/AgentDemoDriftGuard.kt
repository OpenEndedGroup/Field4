package afield.docs

import afield.SavingContext
import java.io.File
import kotlin.system.exitProcess

/**
 * Keeps loadable documents under `dev_docs/agents/demos/` honest about their loose source files.
 *
 * A `#afield/file-inline` property has two copies: the fallback embedded in `metadata.edn`, and the
 * loose file beside it. Field deliberately prefers the loose file, so an edited demo can run
 * correctly while its supposedly canonical metadata remains stale. This guard compares the two
 * byte-for-byte. `--write` refreshes the inline fallbacks through [SavingContext]'s real EDN codec.
 *
 * `--write` rewrites `metadata.edn` for every document it visits, and re-encoding is not always
 * byte-identical to what is committed — a hand-edited artifact can carry `\uXXXX` escapes that the
 * codec re-emits as the literal character. That turns "add one demo" into a diff against unrelated
 * documents, which is an invitation to a merge conflict between two branches that each added one.
 * `--only <document>[,<document>…]` narrows the run to the documents named after the directory
 * under `dev_docs/agents/demos`, so adding a demo touches that demo alone.
 */
object AgentDemoDriftGuard {
    private const val DEMOS_REL = "dev_docs/agents/demos"

    private const val USAGE = "usage: AgentDemoDriftGuard [--write] [--only <document>[,<document>...]]"

    @JvmStatic
    fun main(args: Array<String>) {
        var write = false
        val only = linkedSetOf<String>()
        var cursor = 0
        while (cursor < args.size) {
            val arg = args[cursor]
            when {
                arg == "--write" -> write = true
                arg == "--only" -> {
                    require(cursor + 1 < args.size) { "--only needs a document name. $USAGE" }
                    only += splitNames(args[cursor + 1])
                    cursor++
                }

                arg.startsWith("--only=") -> only += splitNames(arg.removePrefix("--only="))
                else -> error("unrecognized argument '$arg'. $USAGE")
            }
            cursor++
        }

        val root = repoRoot()
        val demosRoot = File(root, DEMOS_REL)
        require(demosRoot.isDirectory) { "no agent demo directory at ${demosRoot.absolutePath}" }
        val all = (demosRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, SavingContext.METADATA_EDN).isFile }
            .sortedBy { it.name }
        require(all.isNotEmpty()) { "no Field documents under ${demosRoot.absolutePath}" }
        val demos = select(all, only)

        val saving = SavingContext()
        if (write) {
            demos.forEach { demo ->
                val refreshed = saving.refreshInlineFilesFromDisk(demo.absolutePath)
                println("[regenerate] ${demo.name}: ${refreshed.size} inline file(s)")
            }
        }

        val problems = mutableListOf<String>()
        var inlineFiles = 0
        for (demo in demos) {
            val documentRoot = demo.canonicalFile.toPath().normalize()
            val snapshots = saving.readInlineFileSnapshots(demo.absolutePath)
            if (snapshots.isEmpty()) {
                problems += "${demo.name}: metadata.edn has no #afield/file-inline properties"
                continue
            }
            inlineFiles += snapshots.size
            for (snapshot in snapshots) {
                val target = File(demo, snapshot.path).canonicalFile
                if (!target.toPath().normalize().startsWith(documentRoot)) {
                    problems += "${demo.name}/${snapshot.boxId}.${snapshot.property}: path escapes the demo (${snapshot.path})"
                    continue
                }
                if (!target.isFile) {
                    problems += "${demo.name}/${snapshot.boxId}.${snapshot.property}: missing ${snapshot.path}"
                    continue
                }
                val loose = target.readText()
                if (loose != snapshot.inline) {
                    val inlineLines = snapshot.inline.lines()
                    val looseLines = loose.lines()
                    val at = (0 until maxOf(inlineLines.size, looseLines.size))
                        .firstOrNull { inlineLines.getOrNull(it) != looseLines.getOrNull(it) } ?: 0
                    problems += "${demo.name}/${snapshot.boxId}.${snapshot.property}: ${snapshot.path} " +
                        "differs from metadata.edn at line ${at + 1}\n" +
                        "      metadata: ${inlineLines.getOrNull(at)}\n" +
                        "      loose:    ${looseLines.getOrNull(at)}"
                }
            }

            // The comparison above is on decoded values, so it is blind to how metadata.edn spells
            // them: a six-character « escape and a literal « decode alike, and both pass. That
            // is how logging_layer sat in a form the codec does not produce — the parser accepts the
            // escape, the printer only ever emits the character — and every `--write` came back
            // with it modified. Require the committed bytes to be the codec's own output, so a
            // regenerate on a clean tree is a no-op and a demo cannot go back to hand-spelled EDN.
            val committed = File(demo, SavingContext.METADATA_EDN).readText()
            val canonical = saving.canonicalInlineMetadata(demo.absolutePath).metadataEdn
            if (committed != canonical) {
                val at = (0 until maxOf(committed.length, canonical.length))
                    .firstOrNull { committed.getOrNull(it) != canonical.getOrNull(it) } ?: 0
                problems += "${demo.name}: metadata.edn is not what Field's EDN codec writes, " +
                    "first difference at character ${at + 1}\n" +
                    "      committed: ${excerpt(committed, at)}\n" +
                    "      codec:     ${excerpt(canonical, at)}"
            }
        }

        if (problems.isNotEmpty()) {
            System.err.println("AgentDemoDriftGuard FAILED with ${problems.size} problem(s):")
            problems.forEach { System.err.println("  - $it") }
            System.err.println("Fix with: ./gradlew regenerateAgentDemos")
            exitProcess(1)
        }
        val scope = if (demos.size == all.size) "" else " of ${all.size}"
        println("AgentDemoDriftGuard ok — ${demos.size}$scope document(s), $inlineFiles inline file(s)")
    }

    /** A metadata.edn is one long line, so a difference is only legible as a window around it. */
    private fun excerpt(text: String, at: Int): String {
        val from = maxOf(0, at - 30)
        val to = minOf(text.length, at + 30)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        return prefix + text.substring(from, to).replace("\n", "\\n") + suffix
    }

    private fun splitNames(raw: String): List<String> =
        raw.split(',').map(String::trim).filter(String::isNotEmpty)

    /** Resolves `--only` names against the documents on disk, naming the alternatives on a typo. */
    private fun select(all: List<File>, only: Set<String>): List<File> {
        if (only.isEmpty()) return all
        val byName = all.associateBy { it.name }
        val unknown = only - byName.keys
        require(unknown.isEmpty()) {
            "no such demo document: ${unknown.joinToString(", ")} — " +
                "have ${all.joinToString(", ") { it.name }}"
        }
        return all.filter { it.name in only }
    }

    private fun repoRoot(): File {
        var at: File? = File(System.getProperty("user.dir")).absoluteFile
        while (at != null) {
            if (File(at, "settings.gradle.kts").isFile && File(at, DEMOS_REL).isDirectory) return at
            at = at.parentFile
        }
        error("could not locate Field repo from user.dir=${System.getProperty("user.dir")}")
    }
}
