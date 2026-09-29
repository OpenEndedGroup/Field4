package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Holds the one-way boundary around `src/personal/`: Field may **read** private work, and nothing
 * committed may **depend** on it.
 *
 * ### The failure this exists to stop, and why `.gitignore` does not stop it
 *
 * `src/personal/` is airtight as a *location*. It is excluded by `.gitignore:243`, nothing under it
 * has ever been tracked, and `git add -A` cannot reach it. That half has never failed.
 *
 * What fails is the other direction. An agent sent to study private work and generalise a lesson
 * writes **new files in tracked locations** that import a private package, cite a private path, or
 * quote private code. The private tree is still untracked and the private work is now in the public
 * repository anyway — as the study rather than as the studied. No guard checked the *direction of
 * reference*, so the boundary existed as a place and not as a rule, which is precisely the shape a
 * reviewer does not think to look for: every individual file is in a location it belongs in.
 *
 * Three distinct costs, in the order they matter:
 *
 * 1. **A published page that cannot compile for anyone else.** A code fence on
 *    `public/docs/afield_four_graphics.md` and box files in the loadable `canonical_graphics`
 *    bundle named packages that existed on exactly one machine — a correctness bug for every other
 *    reader, not a matter of taste. Two of the three box files turned out to be carrying a *dead*
 *    private import over code `afield` already provided, so the leak was not even buying them
 *    anything; the third was a real dependency and the fix was to move it into `afield`.
 * 2. **A citation nobody can follow, and a ledger that has to track it.** Prose that cites a private
 *    path offers evidence no reader can open. The second-order cost is the bookkeeping: before this
 *    guard, `CloseNodeCitationDriftTest` carried an exemption list naming three private files purely
 *    to declare that CI could not verify them, which is a maintenance burden created by the citation
 *    rather than by the finding.
 * 3. **A general tool promoting a particular artwork.** The reason this is worth enforcing rather
 *    than merely preferring: the intent is to study private work *for the general lessons*, and a
 *    lesson that arrives carrying the name of the piece it came from has not been generalised. The
 *    corollary that makes the rule liveable: a private survey may still *support* a claim about a
 *    population — "the only caller in the private tree is a deprecated shim" is a finding worth
 *    publishing — it just may not be cited as a location.
 *
 * ### Why an allowlist of public roots rather than a list of private packages
 *
 * The obvious rule — "no tracked file may import `drowning`, `euclid`, `organic`, `splat`, …" —
 * needs a list of private package names, which the author of the private code changes without
 * telling anybody, and which cannot be derived on a machine where `src/personal/` does not exist.
 * A guard whose population is a list that silently goes stale is the failure mode this repository
 * has recorded most often.
 *
 * So the rule is inverted. **Every one of Field's own packages is under `afield.`**, so a
 * *single-segment* package import is inherently either a third-party library or private work. This
 * asserts that every single-segment import root appearing in committed Kotlin is on
 * [PUBLIC_IMPORT_ROOTS] — a list of things that are *permitted*, which changes only when a real
 * dependency is added, and which fails closed. A private package invented tomorrow is caught
 * without anybody updating anything.
 *
 * Measured when this was written, over the [SCAN_ROOTS] corpus: 22 legitimate roots accounting for
 * 15 000+ import statements, against **8** occurrences of four private packages. The signal-to-noise
 * is what makes the inverted form practical. The remainder after cleanup is **zero**: see
 * [GRANDFATHERED] for what the twelve entries were and how each was retired.
 *
 * ### The grandfathered set is a debt list, and it is bidirectional
 *
 * [GRANDFATHERED] records the violations that existed when the boundary was first enforced, so this
 * goes green today rather than blocking on an editorial cleanup. Two things stop it from becoming a
 * permanent amnesty:
 *
 * - it may **shrink** freely and **grow** only by editing [MAX_GRANDFATHERED], and
 * - every entry must still *be* a violation ([grandfatheredEntriesAreStillReal]), so fixing one and
 *   leaving its excuse behind fails the build.
 *
 * That second assertion is the half most ledgers omit, and without it a debt list becomes a
 * description of the past rather than of the tree.
 */
class PersonalBoundaryTest {

    companion object {
        /** The private tree. Committed code may name this prefix; it may not reach inside it. */
        private const val PERSONAL_ROOT = "src/personal/"

        /**
         * Where committed Kotlin lives, in every form it is committed in.
         *
         * `.md` and `.edn` are here because box code is committed three ways — as a loose
         * `<uid>_code_.kt` sidecar, as an `#afield/file-inline` string inside `metadata.edn`, and as
         * a fence on a published page — and an import that leaks through only the embedded copy is
         * exactly as broken for a reader as one in the sidecar. `.yaml` covers operation transcripts,
         * which carry box code as literal program text.
         */
        private val SCAN_ROOTS = listOf(
            "src/commonMain",
            "src/jvmMain",
            "src/jvmTest",
            "src/jsMain",
            "dev_docs",
            "public/docs",
            "templates",
        )

        private val SCAN_EXTENSIONS = setOf("kt", "kts", "md", "edn", "yaml")

        /**
         * Single-segment package roots a committed file may import.
         *
         * Derived by enumeration over the corpus rather than from memory, and every entry earned its
         * place by already being there. `afield` is Field itself; the rest are third-party or
         * platform. Three deserve a note because they read like mistakes and are not:
         * `shared` is the DataFrame plugin spike's cross-box generated types, `my` is a placeholder
         * inside a documentation example on `kotlin_overview.md`, and `js` is the Kotlin/JS stdlib.
         *
         * The three Python roots are here because `.md` carries Python fences as well as Kotlin ones
         * and this scans file text rather than parsing fences. That is a deliberate soft edge: the
         * private tree is Kotlin, so a Python-shaped hole in this guard cannot hide it.
         */
        private val PUBLIC_IMPORT_ROOTS = setOf(
            // Field, platform, Kotlin
            "afield", "kotlin", "kotlinx", "java", "javax", "jdk", "js", "kotlinext",
            // `sun.misc.Signal` (module jdk.unsupported): the only public signal-handler API, used
            // by afield.InterruptSignal so Field exits on SIGINT when launched with it ignored.
            "sun",
            // third-party JVM
            "org", "com", "io", "arrow", "smile", "imgui", "okhttp3", "jep", "plotly", "clojure",
            "space", "eu", "ai", "field", "web", "shared",
            // documentation placeholders and non-Kotlin fences
            "my", "urllib", "matplotlib", "asyncio",
        )

        /**
         * References to the private tree that are the mechanism rather than a leak.
         *
         * Field is *supposed* to name `src/personal/kotlin` as an optional source root — some twenty
         * places do, from [afield.ai.KotlinSourceIndex] to the completion sidecar — and two places
         * are supposed to name it in order to *exclude* it. What distinguishes those from a leak is
         * depth: naming the root is a configuration decision that survives the directory being
         * absent, while naming a file inside it is a dependency on one machine's contents.
         */
        private val ALLOWED_PERSONAL_REFERENCES = setOf(
            "src/personal/",
            "src/personal",
            "src/personal/kotlin",
            "src/personal/kotlinJvm",
            "src/personal/resources",
            "src/personal/docs",
            "src/personal/transcripts",
            // A path the *reader* is told to create, not one of the author's files.
            // `dev_docs/HERMETIC_COMPLETION_INPUTS.md` reproduces a completion-fixture failure by
            // writing a throwaway `probe` package into the private root and deleting it again, which
            // is a recipe rather than a dependency: it works on any machine, and it names nothing
            // that exists before the reader runs it. Worth distinguishing explicitly, because the
            // rule this class enforces is about *depending on private content* and a literal string
            // match cannot tell the difference on its own.
            "src/personal/kotlin/probe",
        )

        /**
         * The debt, as it stood when the boundary became a rule. Path to why it is here.
         *
         * Every one of these is a real violation that a human has to decide about, not a false
         * positive to be silenced. The `canonical_graphics` entries are the expensive ones: a
         * loadable bundle whose box code cannot compile for anybody but its author.
         */
        private val GRANDFATHERED = mapOf<String, String>(
            // Empty, and [MAX_GRANDFATHERED] is 0, so it cannot refill without a deliberate edit.
            //
            // It held twelve entries when the boundary became a rule. Nine were paid down by keeping
            // each finding and dropping the path it hung on, and two of those turned out to be *dead*
            // private imports over code `afield` already had. The last three were one dependency:
            // `ShaderDlaSystem`, which section 8 of the graphics page teaches a general lesson
            // through. It now lives at [afield.stdlib.ShaderDlaSystem], beside the rest of the alife
            // family, so the page, its corpus box and that box's file-inline copy all compile for
            // every reader. The lift was mechanical -- the class imported only `afield.*` and lwjgl,
            // and three of its imports were `afield.stdlib` members already.
        )

        /**
         * A ceiling, not a target. Lowering it is the point; raising it should require saying why in
         * a commit message somebody will read.
         */
        private const val MAX_GRANDFATHERED = 0

        /**
         * A floor under the corpus, so a broken [SCAN_ROOTS] entry fails loudly instead of turning
         * every assertion below into a tautology over an empty set. Measured at 2712 when written.
         */
        private const val MIN_SCANNED_FILES = 2000

        private val IMPORT_RE = Regex("""(?:^|\\n|")\s*import\s+([a-z][A-Za-z0-9_]*)\.""", RegexOption.MULTILINE)

        /** A path reaching *inside* the private tree: the root plus at least one more segment. */
        private val DEEP_PERSONAL_RE = Regex("""src/personal/[A-Za-z0-9_.\-]+/[A-Za-z0-9_.\-]+""")

        private fun repoRoot(): File {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            while (!File(dir, ".gitignore").isFile || !File(dir, "src").isDirectory) {
                dir = dir.parentFile ?: error("PersonalBoundaryTest: no repository root above user.dir")
            }
            return dir
        }

        private fun scanned(): List<Pair<String, String>> {
            val repo = repoRoot()
            return SCAN_ROOTS.flatMap { root ->
                val dir = File(repo, root)
                if (!dir.isDirectory) emptyList() else dir.walkTopDown()
                    .filter { it.isFile && it.extension in SCAN_EXTENSIONS }
                    .map { file ->
                        val rel = repo.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                        rel to file.readText()
                    }
                    .toList()
            }.filterNot { (rel, _) -> rel.startsWith(PERSONAL_ROOT) }
                .sortedBy { it.first }
        }
    }

    /**
     * The population, asserted before anything is concluded from it.
     *
     * Every other test here reports an absence, and an absence over an empty corpus is free.
     */
    @Test
    fun `the corpus this guard reasons about is really there`() {
        val files = scanned()
        println("PersonalBoundaryTest: scanned ${files.size} committed files across ${SCAN_ROOTS.size} roots")
        assertTrue(
            files.size >= MIN_SCANNED_FILES,
            "scanned only ${files.size} files, floor $MIN_SCANNED_FILES — a SCAN_ROOTS entry has moved, " +
                "and every absence this class asserts would be vacuous",
        )
        val missing = SCAN_ROOTS.filterNot { File(repoRoot(), it).isDirectory }
        assertTrue(missing.isEmpty(), "SCAN_ROOTS names directories that do not exist: $missing")
    }

    /**
     * No committed file may import a package that is not a known public root.
     *
     * This is the assertion that catches a private package nobody has told the guard about.
     */
    @Test
    fun `no committed file imports a package outside the public roots`() {
        val offenders = mutableMapOf<String, MutableSet<String>>()
        for ((rel, text) in scanned()) {
            for (m in IMPORT_RE.findAll(text)) {
                val root = m.groupValues[1]
                if (root !in PUBLIC_IMPORT_ROOTS) {
                    offenders.getOrPut(rel) { mutableSetOf() } += root
                }
            }
        }
        val unexcused = offenders.filterKeys { it !in GRANDFATHERED }
        assertTrue(
            unexcused.isEmpty(),
            buildString {
                appendLine("committed files import packages outside PUBLIC_IMPORT_ROOTS:")
                unexcused.toSortedMap().forEach { (path, roots) ->
                    appendLine("  $path → ${roots.sorted().joinToString(", ")}")
                }
                appendLine()
                appendLine("If these are private packages under src/personal/, the code does not belong in a")
                appendLine("committed file: take the lesson, express it against afield.*, and leave the private")
                appendLine("names behind. If one is a genuine new public dependency, add its root to")
                appendLine("PUBLIC_IMPORT_ROOTS with a note saying what it is.")
            },
        )
    }

    /**
     * No committed file may cite a path *inside* the private tree.
     *
     * Naming the root is the mechanism and is allowed; reaching into it couples the public repository
     * to one machine's contents.
     */
    @Test
    fun `no committed file cites a path inside the private tree`() {
        val offenders = mutableMapOf<String, MutableSet<String>>()
        for ((rel, text) in scanned()) {
            for (m in DEEP_PERSONAL_RE.findAll(text)) {
                val cited = m.value.trimEnd('.', '-', '/')
                if (cited !in ALLOWED_PERSONAL_REFERENCES) {
                    offenders.getOrPut(rel) { mutableSetOf() } += cited
                }
            }
        }
        val unexcused = offenders.filterKeys { it !in GRANDFATHERED }
        assertTrue(
            unexcused.isEmpty(),
            buildString {
                appendLine("committed files cite paths inside src/personal/:")
                unexcused.toSortedMap().forEach { (path, cites) ->
                    appendLine("  $path → ${cites.sorted().joinToString(", ")}")
                }
                appendLine()
                appendLine("A citation is evidence a reader is meant to be able to open, and nobody else can open")
                appendLine("this one. State the finding in terms of afield.* and cite the afield.* code it applies")
                appendLine("to; if the only evidence is private, the claim belongs in a commit message or the")
                appendLine("nightly journal rather than in a guarded document.")
            },
        )
    }

    /**
     * Every grandfathered entry must still be a real violation.
     *
     * The half a debt list usually omits. Without it, fixing a leak and leaving its excuse behind
     * turns the ledger into a description of a tree that no longer exists — and the next person to
     * read it believes the leak is still there.
     */
    @Test
    fun `grandfathered entries are still real`() {
        val files = scanned().toMap()
        val stale = GRANDFATHERED.keys.filter { path ->
            val text = files[path] ?: return@filter true
            val importsPrivate = IMPORT_RE.findAll(text).any { it.groupValues[1] !in PUBLIC_IMPORT_ROOTS }
            val citesPrivate = DEEP_PERSONAL_RE.findAll(text)
                .any { it.value.trimEnd('.', '-', '/') !in ALLOWED_PERSONAL_REFERENCES }
            !importsPrivate && !citesPrivate
        }
        assertTrue(
            stale.isEmpty(),
            "GRANDFATHERED excuses violations that no longer exist — delete these entries:\n" +
                stale.sorted().joinToString("\n") { "  $it" },
        )
    }

    /** The ledger may shrink without ceremony and grow only deliberately. */
    @Test
    fun `the grandfathered ledger does not grow quietly`() {
        assertTrue(
            GRANDFATHERED.size <= MAX_GRANDFATHERED,
            "GRANDFATHERED has ${GRANDFATHERED.size} entries against a ceiling of $MAX_GRANDFATHERED. " +
                "Adding a new exemption is a decision; say why in the commit message and raise the ceiling.",
        )
        assertTrue(
            GRANDFATHERED.values.all { it.isNotBlank() },
            "every grandfathered path needs a reason a reader can act on",
        )
    }

    /**
     * The list of private packages, kept honest on the one machine that can check it.
     *
     * This does not run in a fresh checkout and is not supposed to: it exists so that the author of
     * the private code finds out, locally, if a package they invented shares a name with something
     * [PUBLIC_IMPORT_ROOTS] permits — which would punch a silent hole in the import assertion above.
     */
    @Test
    fun `no private package name collides with a permitted public root`() {
        val personal = File(repoRoot(), PERSONAL_ROOT)
        if (!personal.isDirectory) {
            println("PersonalBoundaryTest: no src/personal/ here, collision check skipped (expected off-machine)")
            return
        }
        val declared = personal.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("""^package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE).findAll(it.readText()) }
            .map { it.groupValues[1].substringBefore('.') }
            .toSortedSet()
        println("PersonalBoundaryTest: ${declared.size} private top-level packages present locally")
        val collisions = declared.filter { it in PUBLIC_IMPORT_ROOTS }
        assertEquals(
            emptyList(), collisions,
            "private package(s) $collisions share a name with a permitted public root, so " +
                "`import $collisions.*` in a committed file would pass this guard. Rename the private " +
                "package, or narrow PUBLIC_IMPORT_ROOTS.",
        )
    }
}
