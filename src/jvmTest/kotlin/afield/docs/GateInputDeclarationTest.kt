package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Every repo file a gate reads must be a declared input of the task that runs it.**
 *
 * ## The bug this ends
 *
 * A Gradle `Test` task with undeclared inputs reports `UP-TO-DATE` when a file it reads changes, and
 * serves the previous run's verdict as though it were fresh. Measured on this tree, 2026-08-27, with
 * a sixth `static let … = "head: …"` span planted in
 * `native/field-visionos/Field4Vision/Pose/PoseLatency.swift` — a population
 * `ProseNumeralContractTest` derives and `public/docs/vision.md` publishes:
 *
 * ```text
 * > Task :citationGates UP-TO-DATE
 * BUILD SUCCESSFUL in 924ms
 * ```
 *
 * Exit code 0, and the gate never ran. With the `native` swift tree declared the identical plant re-runs the
 * task and fails on the count; with the declaration in place and the file clean the task is
 * `UP-TO-DATE` in 935ms, which is what makes the middle run attributable.
 *
 * That hole was found by hand. So were two more the same night — `.gitmodules` and
 * `tools/code-use-semantic-sidecar`, both read by [CitationContractTest] and neither on any
 * classpath. Three instances of one bug, found by three separate acts of noticing. This class is
 * the act of noticing, mechanised.
 *
 * ## What it does
 *
 * Parses the `citationGates` registration out of `build.gradle.kts` — the `fileTree` and
 * `inputs.file` declarations — then scans the sources of the classes that task runs for references
 * to repo paths, and asserts that every reference that **resolves to a file or directory that
 * exists** falls under one of those declarations, the test classpath, or an explicit exemption.
 *
 * A reference is found two ways, and the union matters:
 *
 * - **By context.** `File(root, "…")`, `File(repo, "…")`, `File(r, "…")`,
 *   `DerivedCountGuard.kotlinSourcesUnder(root, "…")`. Whatever the literal's shape, that call is
 *   opening a repo-relative path. This is the rule that sees `.gitmodules`, which has no `/` in it
 *   and would be indistinguishable from a bare filename otherwise.
 * - **By shape.** Any string literal containing `/` that resolves under the repo root. This is the
 *   rule that sees a path held in a *data field* rather than passed to `File` — every
 *   `Derivation(page = "public/docs/vision.md", …)` and every `GUARDED_PAGES` key, none of which
 *   appears next to a `File(` at all.
 *
 * Comments are stripped first ([KotlinSourceText]). A path named in a KDoc is a path the guard talks
 * about, not one it reads, and this package's prose is full of both.
 *
 * ## The blind spot, stated rather than discovered later
 *
 * A reference built by concatenation, or held in a variable whose literal lives outside the scanned
 * set, is invisible here. [theOpaqueSitesAreCountedAndNoGuardIsWhollyInvisible] measures that
 * directly: it counts every `File(root, x)` where `x` is not a literal, and asserts the weaker
 * property that no scanned source consists *only* of such sites — a guard that the scan cannot see
 * at all would be the failure mode worth catching, and a guard whose opaque sites are fed by
 * literals the shape rule already picked up is covered in practice.
 *
 * Four further limits, none of them hidden:
 *
 * 1. **`citationGates` only.** It and `jvmTest` are the two tasks in this build that can serve a
 *    cached verdict; the nine `JavaExec`/`Exec` gates `docsGates` depends on declare no inputs or
 *    outputs at all, so Gradle re-runs them unconditionally. `jvmTest` is not covered here because
 *    its inputs are its own classpath and it excludes every class scanned below.
 * 2. **Sources in this package plus the named test classes.** A guard that delegates its file
 *    reading to a helper in `jvmMain` would be missed.
 * 3. **Only references that resolve today.** A path that does not exist yet is not checked, which is
 *    the conservative direction — it cannot produce a false red.
 * 4. **Declaration, not sufficiency.** That a path is declared says Gradle will notice it changing.
 *    It says nothing about whether the guard reads the right thing.
 */
class GateInputDeclarationTest {

    private companion object {

        /**
         * Floor on the resolving path references the scan finds. Measured 2026-08-27: **72**,
         * across 16 scanned sources.
         *
         * Without it this class is one bad regex away from asserting that all zero of the paths the
         * guards read are declared, which is the exact failure mode the branch that wrote it exists
         * to close. Floored at 55 rather than pinned: adding or removing a guarded page moves this
         * by ones, and a page carries a handful of references at most.
         */
        const val MIN_PATH_REFERENCES = 55

        /** Identifiers that hold the repository root in this package. */
        val ROOT_IDENT = Regex("""\bFile\(\s*(?:root|repo|r|repoRoot\(\))\s*,\s*"([^"\n]+)"""")

        /** [DerivedCountGuard.kotlinSourcesUnder]'s vararg of repo-relative source roots. */
        val SOURCES_UNDER = Regex("""kotlinSourcesUnder\(\s*(?:root|repo|r)\s*,\s*((?:"[^"\n]+"\s*,?\s*)+)\)""")

        /** Any literal that looks like a relative path. */
        val PATHY = Regex(""""((?:[A-Za-z0-9_.\-]+/)+[A-Za-z0-9_.\-]*)"""")

        /** `File(root, x)` where `x` is not a literal — the measured blind spot. */
        val OPAQUE = Regex("""\bFile\(\s*(?:root|repo|r|repoRoot\(\))\s*,\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)""")

        /**
         * Paths a guard genuinely touches that must **not** be declared, with the reason.
         *
         * Read backwards as well as forwards by
         * [everyRepoPathAGuardReadsIsADeclaredInputOfCitationGates]: an entry that has since become
         * covered, or that no guard references any more, fails as stale. Otherwise this becomes the
         * place undeclared inputs are parked, which is a worse hiding place than the one it replaces
         * because it looks like a decision.
         */
        val EXEMPT: Map<String, String> = mapOf(
            "build/reports/citations/inventory.json" to
                "an OUTPUT of this task, not an input. " +
                    "`CitationContractTest.theCorpusInventoryIsReportedAndFloored` writes it and then " +
                    "asserts it is non-empty. Declaring it as an input would make the task dirty on " +
                    "every run — never up to date — which is the opposite failure and a worse one, " +
                    "because a gate that always re-runs stops being cheap and then stops being run.",
        )
    }

    private val root = CitationGuard.repoRoot()
    private val build by lazy { File(root, "build.gradle.kts").readText() }

    // -----------------------------------------------------------------------------------------------
    // What `citationGates` declares
    // -----------------------------------------------------------------------------------------------

    private data class DeclaredTree(val dir: String, val includes: List<String>, val excludes: List<String>)

    private data class Declared(val trees: List<DeclaredTree>, val files: Set<String>)

    /**
     * The `citationGates` registration block, parsed.
     *
     * Read out of the build file rather than restated here, for the reason
     * `ProseNumeralContractTest`'s derivations give: a copy of a declaration cannot disagree with
     * the declaration, so it can only ever confirm itself. `DocsGatesWiringTest` pins the other
     * direction, that the named property strings are still present at all.
     */
    private fun declared(): Declared {
        val block = Regex("""val citationGates = tasks\.register<[^>]*>\("citationGates"\) \{([\s\S]*?)\n\}\n""")
            .find(build)?.groupValues?.get(1)
        assertTrue(block != null, "could not find the citationGates registration in build.gradle.kts")
        val trees = Regex("""inputs\.files\(fileTree\("([^"]+)"\)\s*\{([\s\S]*?)\}\)""")
            .findAll(block)
            .map { m -> DeclaredTree(
                m.groupValues[1],
                Regex("""include\("([^"]+)"\)""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList(),
                Regex("""exclude\("([^"]+)"\)""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList(),
            ) }
            .toList()
        // Inlined rather than bound to a local, and that is not style. KotlinSourceIndex's brace
        // tracking loses this function's body somewhere in the chained `.map { m -> ... }` above, so
        // a `val` here is indexed as a top-level `GateInputDeclarationTest.Companion.files` and
        // counts against SourcePartitionGuard's inside-a-body ratchet of 31. The parser bug is real
        // and pre-existing (31 other instances, none of them this file); this declines to be the
        // 32nd. Binding two locals here instead of one takes the count to 34, measured.
        assertTrue(trees.isNotEmpty(), "parsed no citationGates fileTree inputs at all")
        return Declared(
            trees,
            Regex("""inputs\.file\("([^"]+)"\)""").findAll(block).map { it.groupValues[1] }.toSet()
                .also { assertTrue(it.isNotEmpty(), "parsed no citationGates inputs.file entries at all") },
        )
    }

    /** Why [rel] is covered, or null. */
    private fun coverage(rel: String, d: Declared): String? {
        if (rel in d.files) return "inputs.file(\"$rel\")"
        // The test classpath. A Kotlin source edit recompiles the guards, which invalidates the task
        // through `classpath`, so `src/**` needs no separate declaration — and `SmallLanguageAnatomyTest`
        // reading `src/jvmMain/kotlin` as *text* is covered by the same edge for the same reason.
        if (rel == "src" || rel.startsWith("src/")) return "test classpath"
        val isDir = File(root, rel).isDirectory
        d.trees.forEach { (dir, includes, excludes) ->
            if (rel == dir) return "fileTree(\"$dir\") root"
            if (rel.startsWith("$dir/")) {
                val sub = rel.removePrefix("$dir/")
                // An exclude un-covers, and it is checked before the includes because that is the
                // order Gradle resolves them in. Without this branch the model is *optimistic*: it
                // would keep reporting a path as covered by a tree that no longer carries it, which
                // is the one direction this class must never be wrong in. Added 2026-09-01 with the
                // `exclude("**/build/**")` that closes #71 — the exclude is what makes the citation
                // gate stop overlapping `tools/*/build`, and it is only safe to write once the model
                // that certifies these declarations can see it.
                excludes.forEach { exc -> if (matches(sub, exc)) return null }
                // A directory inside a declared tree: whatever the guard reads under it that matches
                // an include is declared, and a new matching file appearing is a change Gradle sees.
                if (isDir) return "inside fileTree(\"$dir\")"
                includes.forEach { inc -> if (matches(sub, inc)) return "fileTree(\"$dir\") include \"$inc\"" }
            }
            // Deliberately no "this directory contains a declared tree, so call it covered" branch.
            // That leniency was here for one run and it hid a real hole: `ImpossibilityClaimGuard`
            // opens reproducer paths under `tools/`, and `tools` passed as covered because
            // `tools/code-use-semantic-sidecar` is declared underneath it. A partly-declared parent
            // is not a declared input; it is the same "green for an adjacent reason" this class
            // exists to refuse, one level up.
        }
        return null
    }

    /**
     * Ant-style include matching, enough for the shapes this build file uses.
     *
     * Hand-translated rather than routed through `Regex.escape`, which was the first attempt and was
     * wrong in a way worth recording: `Regex.escape` returns a `\Q…\E` quote block, so the
     * follow-up `.replace("*", ".*")` had nothing to act on and every include matched nothing. The
     * check then reported 38 undeclared paths, all of them false, on a tree where the true answer
     * was zero — a guard failing loudly for a reason unrelated to its name, which is at least the
     * survivable direction of this branch's subject.
     */
    private fun matches(sub: String, include: String): Boolean =
        Regex(buildString {
            var i = 0
            while (i < include.length) {
                when {
                    include.startsWith("**/", i) -> { append("(?:.*/)?"); i += 3 }
                    include.startsWith("**", i) -> { append(".*"); i += 2 }
                    include[i] == '*' -> { append("[^/]*"); i++ }
                    include[i] == '?' -> { append("[^/]"); i++ }
                    else -> { append(Regex.escape(include[i].toString())); i++ }
                }
            }
        }).matches(sub)

    // -----------------------------------------------------------------------------------------------
    // What the guards read
    // -----------------------------------------------------------------------------------------------

    /** The classes `citationGates` actually runs, as source files. */
    private fun namedTestSources(): List<File> = Regex("""val citationGuardTestClasses = listOf\(([\s\S]*?)\n\)""").find(build)
            ?.groupValues?.get(1).orEmpty()
            .let { Regex(""""(afield\.[A-Za-z0-9_.]+)"""").findAll(it).map { m -> m.groupValues[1] } }
            .map { File(root, "src/jvmTest/kotlin/${it.replace('.', '/')}.kt") }
            .filter { it.isFile }
            .toList()

    /** Those, plus the helper objects in this package that do file reading on their behalf. */
    private fun scannedSources(): List<File> {
        val pkg = File(root, "src/jvmTest/kotlin/afield/docs")
            .listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty().toList()
        return (namedTestSources() + pkg).distinctBy { it.canonicalPath }.sortedBy { it.name }
    }

    /** `repo-relative path -> the guard sources that reference it`, comments excluded. */
    private fun references(): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        scannedSources().forEach { f ->
            val text = KotlinSourceText.stripComments(f.readText())
            val lits = buildSet {
                ROOT_IDENT.findAll(text).forEach { add(it.groupValues[1]) }
                SOURCES_UNDER.findAll(text).forEach { m ->
                    Regex(""""([^"]+)"""").findAll(m.groupValues[1]).forEach { add(it.groupValues[1]) }
                }
                PATHY.findAll(text).forEach { add(it.groupValues[1]) }
            }
            lits.map { it.trimEnd('/') }
                .filter { it.isNotEmpty() && !it.startsWith("/") }
                .filter { File(root, it).exists() && File(root, it).canonicalPath != root.canonicalPath }
                .forEach { out.getOrPut(it) { mutableSetOf() } += f.name }
        }
        return out
    }

    // -----------------------------------------------------------------------------------------------
    // The assertions
    // -----------------------------------------------------------------------------------------------

    /**
     * The whole point, in one test: nothing a guard reads is invisible to Gradle, and the exemption
     * ledger is read both ways.
     */
    @Test
    fun everyRepoPathAGuardReadsIsADeclaredInputOfCitationGates() {
        val d = declared()
        val refs = references()
        println("--- repo paths referenced by the citationGates guard sources ---")
        println("  sources scanned      : ${scannedSources().size}")
        println("  references resolving : ${refs.size} (floor $MIN_PATH_REFERENCES)")
        println("  declared inputs      : ${d.trees.size} fileTree(s), ${d.files.size} file(s)")

        val undeclared = refs.keys.filter { coverage(it, d) == null && it !in EXEMPT }.sorted()
        val staleExemptions = EXEMPT.keys.filter { it !in refs.keys || coverage(it, d) != null }.sorted()

        undeclared.forEach { println("  UNDECLARED $it — read by ${refs[it]!!.sorted()}") }

        val problems = listOfNotNull(
            if (refs.size >= MIN_PATH_REFERENCES) null else
                "the scan found only ${refs.size} resolving path reference(s), below the floor of " +
                    "$MIN_PATH_REFERENCES. Suspect the scan, not the guards: a regex that matches " +
                    "nothing makes this class assert that all zero undeclared inputs are declared.",
            if (undeclared.isEmpty()) null else
                "${undeclared.size} repo path(s) are read by a guard that `citationGates` runs and " +
                    "are covered by none of its declared inputs, so editing them leaves the task " +
                    "UP-TO-DATE and its previous verdict is served as though it were fresh:\n" +
                    undeclared.joinToString("\n") { "    $it — read by ${refs[it]!!.sorted()}" } +
                    "\n  Add an `inputs.file`/`inputs.files(fileTree(…))` to the citationGates block " +
                    "in build.gradle.kts, or add an EXEMPT row here saying why the path must not be " +
                    "declared.",
            if (staleExemptions.isEmpty()) null else
                "${staleExemptions.size} EXEMPT row(s) no longer describe anything: $staleExemptions. " +
                    "Either the path is now covered by a declared input, or no guard references it. " +
                    "Delete the row — an exemption list that outlives its exemptions is where the " +
                    "next undeclared input will hide.",
        )
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The three holes found by hand on 2026-08-27, kept as a permanent fixture.
     *
     * Two directions, because either one alone is worthless. The paths must still be **seen** by the
     * scan — a regex change that stopped finding them would leave the test above green over a
     * smaller population — and they must still be **covered** by a declaration. The first half is
     * what stops this class quietly narrowing; the second is the regression test for the fix.
     */
    @Test
    fun theThreeHolesFoundByHandAreSeenAndCovered() {
        val d = declared()
        val refs = references()
        listOf(
            "native/field-visionos/Field4Vision/Pose/PoseLatency.swift" to
                "the visionOS head's latency spans, scraped by ProseNumeralContractTest",
            ".gitmodules" to
                "the submodule list CitationContractTest reads",
            "tools/code-use-semantic-sidecar" to
                "the in-repo Gradle subproject whose shape CitationContractTest asserts on",
        ).forEach { (path, what) ->
            assertTrue(
                path in refs,
                "the scan no longer sees `$path` ($what). It was one of the three undeclared inputs " +
                    "this class was written to make findable, so if the scan cannot see it any more " +
                    "the scan has narrowed and the test above is green over less than it was."
            )
            assertTrue(
                coverage(path, d) != null,
                "`$path` is read by a guard and is no longer a declared citationGates input. This is " +
                    "the exact regression the class exists for: editing it now leaves the gate " +
                    "UP-TO-DATE over a changed truth."
            )
        }
    }

    /**
     * The exclude on the `tools` tree is real, and the model can see it.
     *
     * Both halves matter and neither is enough alone.
     *
     * The build file must still exclude every `build` directory under the tree. The glob is written
     * in the assertion below rather than in this sentence, because it contains a comment terminator.
     * Without it `:citationGates` declares an input location that overlaps `:tools:imaging-bench`
     * and `:tools:linalg-bench` output, and Gradle fails the task outright — that was #71, red on
     * `main` from 2026-08-27 to 2026-09-01.
     *
     * And [coverage] must *honour* the exclude, because the danger of an exclude is the quiet one:
     * the declaration narrows while the model that certifies declarations goes on calling the
     * narrowed paths covered, which turns this class optimistic in the one direction it exists to
     * refuse.
     *
     * A committed reproducer stays covered; a generated one under a subproject's `build` does not.
     */
    @Test
    fun theToolsTreeExcludesBuildOutputAndTheModelHonoursIt() {
        val d = declared()
        assertTrue(
            d.trees.any { it.dir == "tools" && "**/build/**" in it.excludes },
            "the citationGates `fileTree(\"tools\")` no longer excludes `**/build/**`. Gradle will " +
                "fail the task with one implicit-dependency validation problem per task writing " +
                "under `tools` — five of them on 2026-09-01 — and `check` goes red for a reason " +
                "that has nothing to do with citations. See #71."
        )
        assertTrue(
            coverage("tools/imaging-bench/build/generated/Reproducer.py", d) == null,
            "a path under an excluded `build` directory is reported as a declared input. The " +
                "exclude narrowed the real declaration and the model did not follow, so every " +
                "`covered` verdict about `tools` is now optimistic."
        )
        assertTrue(
            coverage("tools/completion_diff.py", d) != null,
            "a committed `tools/*.py` reproducer stopped being covered. The exclude was written to " +
                "drop generated output only; if it drops the reproducers too, `ImpossibilityClaimGuard` " +
                "can pass stale over a tool that lost its `def`."
        )
    }

    /**
     * The blind spot, counted, with the one property worth asserting about it.
     *
     * `File(root, rel)` where `rel` is a variable is invisible to the scan. Rather than pretend
     * otherwise, this counts those sites and asserts the weaker thing that actually matters: no
     * scanned source is made *entirely* of them. A guard whose every file read is opaque would be
     * fully outside the check above while appearing to be inside it, and that is the shape worth a
     * red build. A guard with some opaque sites and some literals is covered in practice, because in
     * this package the opaque variable is always fed by a literal elsewhere in the same file — a
     * `GUARDED_PAGES` key, a `Derivation.page` — which the shape rule picks up.
     */
    @Test
    fun theOpaqueSitesAreCountedAndNoGuardIsWhollyInvisible() {
        val d = declared()
        val perSource = scannedSources().associate { f ->
            val text = KotlinSourceText.stripComments(f.readText())
            val opaque = OPAQUE.findAll(text).map { it.groupValues[1] }.toList()
            val literals = PATHY.findAll(text).map { it.groupValues[1].trimEnd('/') }
                .filter { File(root, it).exists() }.toList()
            f.name to (opaque to literals)
        }
        val totalOpaque = perSource.values.sumOf { it.first.size }
        println("--- the scan's blind spot: File(root, <variable>) sites ---")
        println("  opaque sites: $totalOpaque across ${perSource.count { it.value.first.isNotEmpty() }} source(s)")
        perSource.filterValues { it.first.isNotEmpty() }.forEach { (name, v) ->
            println("    %-38s %d opaque, %d resolving literal(s)".format(name, v.first.size, v.second.size))
        }

        // Restricted to the classes the task runs. `CitationGuard` and `DerivedCountGuard` are
        // wholly opaque and correctly so: they are libraries whose relative paths arrive as
        // parameters, and those parameters are literals in the callers, which are scanned. It is a
        // *test class* with no visible path that would be outside this check while appearing to be
        // inside it, and that is what is asserted.
        val named = namedTestSources().map { it.name }.toSet()
        val blind = perSource.filterKeys { it in named }
            .filterValues { it.first.isNotEmpty() && it.second.isEmpty() }.keys.sorted()
        println("  wholly opaque helper(s), by design: " +
            perSource.filterKeys { it !in named }.filterValues { it.first.isNotEmpty() && it.second.isEmpty() }.keys.sorted())
        assertTrue(
            blind.isEmpty(),
            "${blind.size} guard class(es) that citationGates runs reference repo paths only through " +
                "variables, so the declaration check above cannot see a single path they read: " +
                "$blind. Either give the read a literal the scan can resolve, or declare its input " +
                "tree by hand and say so in EXEMPT — but do not leave a guard that is outside the " +
                "check while looking as though it is inside it."
        )
        // Cheap corollary, and free here: the parse the whole class rests on found something.
        assertTrue(
            d.trees.size >= 4 && d.files.size >= 2,
            "parsed only ${d.trees.size} fileTree(s) and ${d.files.size} file(s) out of the " +
                "citationGates block, which is fewer than have been there since 2026-08-19. The " +
                "parse has broken and every 'covered' verdict above is unreliable."
        )
    }
}
