package afield.docs

import afield.KotlinCompletionSupport
import afield.Metadata
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The **reference-only category label**, and the one property it must never lose: that it cannot
 * reach the completion popup.
 *
 * [StdlibReferenceGenerator] groups its pages by the `@file:CompletionCategory` a source file
 * carries. Field's small languages carry none, so publishing them needed a label from somewhere, and
 * the tempting somewhere was the annotation. That is the foot-gun this whole test exists to keep shut,
 * and it is a measured one rather than a theoretical one — `StdlibReferenceGenerator`'s own KDoc
 * records the numbers. Three facts compose into it:
 *
 *  1. [KotlinCompletionSupport.isHiddenCompletionCategory] treats one spelling, `Internal`, as
 *     "drop this candidate from the popup".
 *  2. `KotlinSourceIndex` files *every* declaration in an annotated file under that file's label and
 *     applies no visibility filter at all.
 *  3. `KotlinSourceIndex.getCompletionCategoryForSymbol` ends in a name-only
 *     `categories.firstOrNull()`, so a label on one file can answer for a same-named declaration
 *     declared somewhere else entirely.
 *
 * Together they mean a documentation-shaped edit — adding a `@file:` line to publish a page — can
 * silently change, or delete, rows in a working programmer's autocomplete. So the label lives in
 * `StdlibReferenceGenerator.CATEGORY_BY_PATH`, which is read by the generator and by nothing else,
 * and these tests hold the property that makes that safe: no reference-only label appears anywhere
 * in `src/` as a completion category, and no file under a prefix carrying one of those labels
 * carries a category annotation at all.
 *
 * There are two such labels now — `Languages` and `Renderers`, the latter for
 * `afield.four.fabricated` — and the class name is a historical accident rather than a scope.
 * Membership is derived here (a path-assigned category no source file declares) rather than named,
 * so a third label joins every guard below without editing one. The assertions that do *not* apply
 * to `Visualization` and `Simulation` are the ones about a label completion cannot see: real files
 * carry both of those, so the map is extending a label the source chose rather than inventing one,
 * and a file's own annotation always wins.
 *
 * **Deliberately not covered.** This does not run the completion popup or the AA sidecar; the
 * argument here is structural (the label is not in the input) rather than behavioural. The popup's
 * own rendering of the category vocabulary is `FieldSuggestPresentationTest`, which enumerates the
 * same annotation scan for the opposite purpose.
 */
class LanguagesCategoryTest {

    @Test
    fun `no category the reference assigns by path can appear as a completion category`() {
        val declared = declaredCompletionCategories()

        // A scan that finds nothing proves nothing. These two are the labels most of the tree
        // carries; if they stop being found, the scan has rotted and every assertion below is
        // vacuous.
        for (sentinel in listOf("Graphics", "Audio AI")) {
            assertTrue(
                sentinel in declared,
                "the `@file:CompletionCategory` scan no longer finds `$sentinel`, so it is not " +
                    "scanning anything — found ${declared.sorted()}"
            )
        }

        val vocabulary = declared + completionSupportCategories()
        // `Visualization` and `Simulation` are real completion categories *and* path-assigned ones,
        // which is fine: the point is not that the two vocabularies are disjoint, it is that a label
        // invented purely for a page never becomes one. So membership is derived — a path-assigned
        // category is reference-only exactly when no source file declares it — rather than named,
        // which is how `Renderers` joined this guard without being added to it.
        val referenceOnly = referenceOnlyPathCategories()
        for (sentinel in listOf(StdlibReferenceGenerator.LANGUAGES, StdlibReferenceGenerator.RENDERERS)) {
            assertTrue(
                sentinel in referenceOnly,
                "`$sentinel` is supposed to be a label no source file carries, and some file in " +
                    "`src/` now declares it as a completion category. That is the change this whole " +
                    "test exists to catch, and it also makes the assertion below stop applying to " +
                    "`$sentinel` — so fix the annotation rather than this line."
            )
        }
        for (category in referenceOnly) {
            assertFalse(
                category in vocabulary,
                "`$category` is a reference-only page label, but it now appears in the completion " +
                    "category vocabulary. That means a documentation change has become a completion " +
                    "change: see StdlibReferenceGenerator.CATEGORY_BY_PATH for why that must not " +
                    "happen, and add the page label some other way."
            )
        }
    }

    /**
     * Runs over the prefixes of every *reference-only* category, not just `Languages`.
     *
     * A `Simulation`-annotated file under a `Simulation` prefix is not a conflict — a file's own
     * annotation always wins, so that is the map agreeing with the source. A file under the
     * `Renderers` or `Languages` prefixes is different: no source file anywhere carries either
     * label, so an annotation appearing there is either a file leaving the page it is published on,
     * or a documentation label becoming a completion label. Both are the failure this test is for.
     */
    @Test
    fun `no file the reference labels by path carries a category annotation of its own`() {
        val annotation = Regex("""@(?:file:)?(?:[\w.]+\.)?CompletionCategory\s*\(""")
        val prefixes = referenceOnlyPathCategories().flatMap {
            StdlibReferenceGenerator.pathAssignedPrefixesFor(it)
        }
        val offenders = mutableListOf<String>()
        for (prefix in prefixes) {
            filesUnder(prefix).forEach { file ->
                if (annotation.containsMatchIn(file.readText())) offenders += relative(file)
            }
        }
        assertTrue(
            prefixes.isNotEmpty(),
            "CATEGORY_BY_PATH files nothing under any reference-only label, so this guard is " +
                "checking nothing"
        )
        assertTrue(
            offenders.isEmpty(),
            "these files are labelled by path in StdlibReferenceGenerator.CATEGORY_BY_PATH and have " +
                "now grown their own `@file:CompletionCategory`, so the reference will silently " +
                "publish them elsewhere *and* the completion popup's view of every declaration in " +
                "them has changed: $offenders"
        )
    }

    @Test
    fun `no category the reference assigns by path is hidden from completion`() {
        for (category in StdlibReferenceGenerator.pathAssignedCategories()) {
            assertFalse(
                KotlinCompletionSupport.isHiddenCompletionCategory(category),
                "`$category` is a page label, and completion treats it as a reason to hide a " +
                    "candidate. Rename the page."
            )
        }
    }

    /**
     * The plural in `Languages` is load-bearing, and nothing else says so out loud.
     *
     * `KotlinCompletionSupport.normalizedCompletionCategory` maps the *singular* `"language"` onto
     * `"Kotlin"`. A future tidy-up that renames the page to the singular would therefore file it
     * under an existing, unrelated category the moment anything normalised it. Observed through
     * `withCategoryDocumentationHeader`, which is the one public route to the normaliser.
     */
    @Test
    fun `the plural spelling of the Languages label is the one that survives normalization`() {
        assertEquals(
            "Languages",
            normalize(StdlibReferenceGenerator.LANGUAGES),
            "the reference's page label no longer normalises to itself"
        )
        assertEquals(
            "Kotlin",
            normalize("Language"),
            "the singular no longer collapses onto `Kotlin` — the reason for the plural may be gone, " +
                "but check KotlinCompletionSupport.normalizedCompletionCategory before relying on it"
        )
    }

    /**
     * `Renderers` is the second page label whose spelling is chosen rather than preferred, and the
     * trap is the same shape as `Languages`' with a different word.
     *
     * `normalizedCompletionCategory` maps `"rendering"` onto `"Graphics"`, so the obvious name for
     * this page would file it under an existing, unrelated category the moment anything normalised
     * it. The same `when` branch takes `"shader"`, `"drawing"`, `"geometry"` and `"vulkan"`, so the
     * near misses are worth pinning too: the next person to tidy this label needs to find out from
     * a test rather than from a reader.
     */
    @Test
    fun `the Renderers label survives normalization and the obvious alternative does not`() {
        assertEquals(
            "Renderers",
            normalize(StdlibReferenceGenerator.RENDERERS),
            "the reference's page label no longer normalises to itself"
        )
        for (collides in listOf("Rendering", "Shader", "Drawing", "Geometry", "Vulkan")) {
            assertEquals(
                "Graphics",
                normalize(collides),
                "`$collides` no longer collapses onto `Graphics` — the reason `Renderers` is " +
                    "spelled the way it is may be gone, but check " +
                    "KotlinCompletionSupport.normalizedCompletionCategory before relying on it"
            )
        }
    }

    @Test
    fun `every category assigned by path is published as a page with a blurb`() {
        for (category in StdlibReferenceGenerator.pathAssignedCategories()) {
            val blurb = StdlibReferenceGenerator.categoryBlurb(category)
            assertTrue(
                !blurb.isNullOrBlank(),
                "`$category` has no CATEGORY_BLURBS entry, so its page would ship with a heading and " +
                    "no description — which is the failure the `Uncategorised` check exists to stop, " +
                    "arriving by a different door"
            )
        }
    }

    @Test
    fun `every path prefix names a real directory inside a documented source root`() {
        val roots = StdlibReferenceGenerator.sourceRoots()
        for (prefix in StdlibReferenceGenerator.pathAssignedPrefixes()) {
            assertTrue(
                File(repoRoot(), prefix).isDirectory,
                "CATEGORY_BY_PATH names `$prefix`, which is not a directory — a dead prefix silently " +
                    "labels nothing"
            )
            assertTrue(
                roots.any { prefix.startsWith("$it/") || prefix == "$it/" },
                "CATEGORY_BY_PATH names `$prefix`, which is not inside any of SOURCE_ROOTS " +
                    "($roots) — so no file is ever read from it and the entry does nothing"
            )
        }
    }

    /**
     * A generated page nobody can navigate to is not published.
     *
     * This is the class-level version of the mistake, not the instance: adding a category to
     * [StdlibReferenceGenerator] emits a page, commits it, and passes the byte-exact drift guard
     * without ever appearing in the site's navigation. `public/zensical.yml` is where that last step
     * lives, and it is hand-maintained.
     */
    @Test
    fun `every committed reference page is reachable from the docs nav`() {
        val nav = File(repoRoot(), "public/zensical.yml").readText()
        val pages = File(repoRoot(), "public/docs/reference")
            .listFiles { f -> f.isFile && f.extension == "md" }
            ?.map { it.name }
            ?.sorted()
            ?: fail("public/docs/reference is not a directory")
        assertTrue(pages.size >= 18, "only found ${pages.size} reference pages — the scan has rotted")
        val missing = pages.filterNot { "reference/$it" in nav }
        assertTrue(
            missing.isEmpty(),
            "these reference pages are committed but not listed in public/zensical.yml, so nothing " +
                "on the site links to them: $missing"
        )
    }

    /**
     * A floor under the corpus, and the failure it is aimed at.
     *
     * The drift guard compares the committed pages against what the source produces, so a root
     * deleted from `SOURCE_ROOTS`, or an `EXCLUDE_PREFIXES` entry widened until it swallows a whole
     * package, is *faithful* — regenerate and every guard goes green while a hundred symbols quietly
     * leave the site. `dev_docs/kdoc_reference_contract.md` records 26 files that are annotated with
     * a category that has a page and appear on no page, which is that failure standing still: it is
     * invisible precisely because nothing counts.
     *
     * Two floors, both deliberately a little below the measurement at the time of writing
     * (17 roots, 330 files, 19 pages, measured 2026-09-11 after `afield.four.fabricated` was
     * published) so that ordinary growth does not touch them and a subtraction does. The per-root
     * half is the one that matters — a corpus can stay large while one package vanishes from it.
     *
     * **These are floors and not pins on purpose, and the reason is operational rather than
     * stylistic.** Several nightly branches regenerate this corpus independently, and a derived
     * count can break across two branches whose file sets are *disjoint*: one owns the page that
     * states the number, another owns the code the number measures, and neither `git merge-tree`
     * nor a path intersection can see the conflict coming. A floor cannot break that way, because
     * every branch in flight is adding. Four times in seven nights a published count broke on merge
     * because someone wrote `assertEquals`.
     */
    @Test
    fun `every documented source root is represented on some committed reference page`() {
        val pages = File(repoRoot(), "public/docs/reference")
            .listFiles { f -> f.isFile && f.extension == "md" && f.name != "index.md" }
            ?.associate { it.name to it.readText() }
            ?: fail("public/docs/reference is not a directory")

        // renderFileSection emits exactly this heading for every file it publishes.
        val published = pages.values.flatMap { text ->
            Regex("""^## (\S+\.kt) \{ #""", RegexOption.MULTILINE).findAll(text)
                .map { it.groupValues[1] }
        }.toSet()

        assertTrue(
            published.size >= 315,
            "only ${published.size} source files are published across ${pages.size} category " +
                "pages, against a floor of 315. Either a source root was dropped, an " +
                "EXCLUDE_PREFIXES entry grew teeth, or this scan has rotted — check which before " +
                "lowering the number."
        )

        val roots = StdlibReferenceGenerator.sourceRoots()
        assertTrue(roots.size >= 17, "SOURCE_ROOTS has shrunk to ${roots.size}, from 17")
        val unrepresented = roots.filter { root ->
            val names = File(repoRoot(), root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }.map { it.name }.toSet()
            assertTrue(names.isNotEmpty(), "`$root` contains no Kotlin at all")
            names.none { it in published }
        }
        assertTrue(
            unrepresented.isEmpty(),
            "these source roots are in SOURCE_ROOTS and contribute no file section to any " +
                "committed page, so the generator is reading them and publishing nothing: " +
                "$unrepresented"
        )
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The path-assigned categories that exist *only* to name a page — no source file declares them.
     *
     * Derived rather than listed, so a third one joins every guard here on its own. `Visualization`
     * and `Simulation` are excluded by construction: real files carry both, so the assertions about
     * a label that completion cannot see do not apply to them and never did.
     */
    private fun referenceOnlyPathCategories(): Set<String> {
        val declared = declaredCompletionCategories()
        return StdlibReferenceGenerator.pathAssignedCategories().filterNot { it in declared }.toSet()
    }

    private fun filesUnder(prefix: String): List<File> =
        File(repoRoot(), prefix).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun relative(file: File): String =
        file.relativeTo(repoRoot()).path.replace('\\', '/')

    /** Every `@file:CompletionCategory("…")` / declaration-level one in `src/`. */
    private fun declaredCompletionCategories(): Set<String> {
        val annotation = Regex("""@(?:file:)?(?:[\w.]+\.)?CompletionCategory\s*\(\s*"([^"]+)"""")
        val found = LinkedHashSet<String>()
        File(repoRoot(), "src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                if ("CompletionCategory" !in text) return@forEach
                annotation.findAll(text).forEach { found += it.groupValues[1].trim() }
            }
        // Prose about the annotation is caught too, including this file's own KDoc. A category has
        // letters in it; a placeholder does not.
        return found.filter { value -> value.any(Char::isLetter) }.toSet()
    }

    /**
     * The fixed vocabulary [KotlinCompletionSupport] can invent, read out of its source rather than
     * listed here so a new `when` branch joins this guard on its own.
     */
    private fun completionSupportCategories(): Set<String> {
        val source = File(repoRoot(), "src/jvmMain/kotlin/afield/KotlinCompletionSupport.kt").readText()
        val literal = Regex("""(?:->|return)\s+"([A-Z][A-Za-z ]*)"""")
        return literal.findAll(source).map { it.groupValues[1].trim() }.toSet()
    }

    private fun normalize(category: String): String {
        val metadata = Metadata(icon = "", tail = "", text = "", displayText = "", category = category)
        val header = KotlinCompletionSupport.withCategoryDocumentationHeader(metadata).documentation
        return header.substringAfter("**Category** `").substringBefore('`')
    }

    /** The checkout root. Shared with the citation guards so there is one answer, not seven. */
    private fun repoRoot(): File = CitationGuard.repoRoot()
}
