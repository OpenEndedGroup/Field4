package afield.tools.docs

import afield.document.persistence.SavingContext
import java.io.File
import kotlin.system.exitProcess

/**
 * Checks that each committed, authored document agrees with the loose source files beside it: every
 * document under `dev_docs/agents/demos/`, and every document directly under [AUTHORED_ROOTS] that
 * carries inline copies.
 *
 * A `#afield/file-inline` property has two copies: the fallback embedded in `metadata.edn`, and the
 * loose file beside it. Field loads the loose file, unless `metadata.edn` is the newer of the two and
 * says something else ([SavingContext.sidecarOrNewerInline] takes that as a hand edit of the inline
 * copy). So a stale inline copy is a trap: an edited sidecar runs fine until someone touches
 * `metadata.edn` for any other reason, and from then on the old code loads instead. On 2026-10-31
 * that put the Khronos `Box.gltf` lesson back into canonical_graphics, whose inline copies were stale
 * for 77 sidecars. This guard compares the two byte-for-byte. `--write` refreshes the inline
 * fallbacks through the EDN codec in [SavingContext].
 *
 * `--write` rewrites `metadata.edn` for every document it visits, and re-encoding is not always
 * byte-identical to what is committed — a hand-edited artifact can carry `\uXXXX` escapes that the
 * codec re-emits as the literal character. That turns "add one demo" into a diff against unrelated
 * documents, which is an invitation to a merge conflict between two branches that each added one.
 * `--only <document>[,<document>…]` narrows the run to the documents named, by directory name or by
 * path from the checkout root, so adding a demo touches that demo alone.
 */

/** Compares each authored document's `#afield/file-inline` fallbacks with the loose files beside them. */
object AgentDemoDriftGuard {
    private const val DEMOS_REL = "dev_docs/agents/demos"

    /**
     * Authored documents outside the demo corpus. Generated copies (`public/docs/assets/web_publications`,
     * `public/docs/assets/agent_transcripts`) sit one level deeper and are written by Field, so they are
     * not visited.
     */
    private val AUTHORED_ROOTS = listOf("public/docs/assets", "templates", "tools/presentation/example")

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
        val demoDocuments = (demosRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, SavingContext.METADATA_EDN).isFile }
            .sortedBy { it.name }
        require(demoDocuments.isNotEmpty()) { "no Field documents under ${demosRoot.absolutePath}" }
        // A document outside the demo corpus need not have sidecars at all; only one that has them can drift.
        val authored = AUTHORED_ROOTS.flatMap { rel ->
            (File(root, rel).listFiles() ?: emptyArray())
                .filter { File(it, SavingContext.METADATA_EDN).let { m -> m.isFile && "#afield/file-inline" in m.readText() } }
                .sortedBy { it.name }
        }
        val all = demoDocuments + authored
        val demos = select(root, all, only)
        // A demo keeps every sidecar beside it. Another document may hold a property only inline (no file
        // beside it), which is what Field then loads; with one copy there is nothing to drift.
        fun inlineOnlyAllowed(document: File) = document !in demoDocuments

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
                    "(git checkout -- <document>/metadata.edn), then regenerate.")
                exitProcess(1)
            }
            demos.forEach { demo ->
                val refreshed = saving.refreshInlineFilesFromDisk(demo.absolutePath, inlineOnlyAllowed(demo))
                println("[regenerate] ${label(root, demo)}: ${refreshed.size} inline file(s)")
            }
        }

        val problems = mutableListOf<String>()
        var inlineFiles = 0
        for (demo in demos) {
            val documentRoot = demo.canonicalFile.toPath().normalize()
            val snapshots = saving.readInlineFileSnapshots(demo.absolutePath)
            if (snapshots.isEmpty()) {
                problems += "${label(root, demo)}: metadata.edn has no #afield/file-inline properties"
                continue
            }
            inlineFiles += snapshots.size
            for (snapshot in snapshots) {
                val target = File(demo, snapshot.path).canonicalFile
                if (!target.toPath().normalize().startsWith(documentRoot)) {
                    problems += "${label(root, demo)}/${snapshot.boxId}.${snapshot.property}: path escapes the demo (${snapshot.path})"
                    continue
                }
                if (!target.isFile) {
                    if (inlineOnlyAllowed(demo)) continue
                    problems += "${label(root, demo)}/${snapshot.boxId}.${snapshot.property}: missing ${snapshot.path}"
                    continue
                }
                val loose = target.readText()
                if (loose != snapshot.inline) {
                    val inlineLines = snapshot.inline.lines()
                    val looseLines = loose.lines()
                    val at = (0 until maxOf(inlineLines.size, looseLines.size))
                        .firstOrNull { inlineLines.getOrNull(it) != looseLines.getOrNull(it) } ?: 0
                    problems += "${label(root, demo)}/${snapshot.boxId}.${snapshot.property}: ${snapshot.path} " +
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
            val canonical = saving.canonicalInlineMetadata(demo.absolutePath, inlineOnlyAllowed(demo)).metadataEdn
            if (committed != canonical) {
                val at = (0 until maxOf(committed.length, canonical.length))
                    .firstOrNull { committed.getOrNull(it) != canonical.getOrNull(it) } ?: 0
                problems += "${label(root, demo)}: metadata.edn is not what Field's EDN codec writes, " +
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
            "${label(root, demo)}/${snapshot.boxId}.${snapshot.property}: edit ${label(root, demo)}/${snapshot.path}, not metadata.edn"
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

    /** How messages name [document]: a demo by its directory, any other document by its path from [root]. */
    private fun label(root: File, document: File): String =
        if (document.parentFile.name == File(DEMOS_REL).name) document.name else document.relativeTo(root).path

    /**
     * Resolves `--only` names, each a directory name or a path from [root], against the documents on
     * disk, naming the alternatives on a typo and refusing a directory name two documents share.
     */
    private fun select(root: File, all: List<File>, only: Set<String>): List<File> {
        if (only.isEmpty()) return all
        val chosen = only.map { name ->
            val matches = all.filter { it.name == name || it.relativeTo(root).path == name }
            require(matches.isNotEmpty()) {
                "no such document: $name — have ${all.joinToString(", ") { label(root, it) }}"
            }
            require(matches.size == 1) {
                "$name names ${matches.size} documents (${matches.joinToString(", ") { it.relativeTo(root).path }}); give its path"
            }
            matches.single()
        }.toSet()
        return all.filter { it in chosen }
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
