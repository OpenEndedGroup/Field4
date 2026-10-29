package afield.tools.docs

import afield.document.persistence.SavingContext
import java.io.File
import kotlin.system.exitProcess

/**
 * Checks that each loadable document under `dev_docs/agents/demos/` agrees with the loose source
 * files beside it.
 *
 * A `#afield/file-inline` property has two copies: the fallback embedded in `metadata.edn`, and the
 * loose file beside it. Field prefers the loose file, so an edited demo can run correctly while the
 * metadata that is supposed to be canonical stays stale. This guard compares the two
 * byte-for-byte. `--write` refreshes the inline fallbacks through the EDN codec in [SavingContext].
 *
 * `--write` rewrites `metadata.edn` for every document it visits, and re-encoding is not always
 * byte-identical to what is committed — a hand-edited artifact can carry `\uXXXX` escapes that the
 * codec re-emits as the literal character. That turns "add one demo" into a diff against unrelated
 * documents, which is an invitation to a merge conflict between two branches that each added one.
 * `--only <document>[,<document>…]` narrows the run to the documents named after the directory
 * under `dev_docs/agents/demos`, so adding a demo touches that demo alone.
 */

/** Compares each demo's `#afield/file-inline` fallbacks with the loose files beside them. */
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
            // `--write` copies each loose sidecar over its inline copy, so an edit made inside
            // metadata.edn would be thrown away without a word. Refuse when metadata.edn has changed
            // against git and the sidecar it now disagrees with has not: that is a hand edit.
            val lost = demos.flatMap { demo -> handEditedInlines(root, demo, saving) }
            if (lost.isNotEmpty()) {
                System.err.println("AgentDemoDriftGuard: refusing to regenerate over edits made in metadata.edn:")
                lost.forEach { System.err.println("  - $it") }
                System.err.println("Make the edit in the named sidecar (or in Field), restore metadata.edn " +
                    "(git checkout -- <demo>/metadata.edn), then regenerate.")
                exitProcess(1)
            }
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
                        "differs from metadata.edn at line ${at + 1} — the sidecar is the copy to edit; " +
                        "regenerating copies it into metadata.edn\n" +
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

    /**
     * Inline copies in [demo]'s metadata.edn that disagree with their sidecar when metadata.edn differs
     * from git HEAD and the sidecar does not. Outside a git checkout nothing is reported.
     */
    private fun handEditedInlines(root: File, demo: File, saving: SavingContext): List<String> {
        fun changed(file: File): Boolean? = runCatching {
            val rel = file.relativeTo(root).path
            val p = ProcessBuilder("git", "diff", "--quiet", "HEAD", "--", rel).directory(root)
                .redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            when (p.waitFor()) { 0 -> false; 1 -> true; else -> null }
        }.getOrNull()
        val metadata = File(demo, SavingContext.METADATA_EDN)
        if (changed(metadata) != true) return emptyList()
        return saving.readInlineFileSnapshots(demo.absolutePath).mapNotNull { snapshot ->
            val sidecar = File(demo, snapshot.path)
            if (!sidecar.isFile || sidecar.readText() == snapshot.inline) return@mapNotNull null
            if (changed(sidecar) != false) return@mapNotNull null
            "${demo.name}/${snapshot.boxId}.${snapshot.property}: edit ${demo.name}/${snapshot.path}, not metadata.edn"
        }
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
