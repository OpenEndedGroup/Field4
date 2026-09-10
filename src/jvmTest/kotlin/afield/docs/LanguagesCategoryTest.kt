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
 * and these tests hold the property that makes that safe: nowhere in `src/` does the word
 * `Languages` appear as a completion category, and nowhere in the packages the generator labels by
 * path does *any* category annotation appear.
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
        for (category in StdlibReferenceGenerator.pathAssignedCategories()) {
            // `Visualization` is a real completion category *and* a path-assigned one, which is fine:
            // the point is not that the two vocabularies are disjoint, it is that a label invented
            // purely for a page never becomes one.
            if (category != StdlibReferenceGenerator.LANGUAGES) continue
            assertFalse(
                category in vocabulary,
                "`$category` is a reference-only page label, but it now appears in the completion " +
                    "category vocabulary. That means a documentation change has become a completion " +
                    "change: see StdlibReferenceGenerator.CATEGORY_BY_PATH for why that must not " +
                    "happen, and add the page label some other way."
            )
        }
    }

    @Test
    fun `no file the reference labels by path carries a category annotation of its own`() {
        val annotation = Regex("""@(?:file:)?(?:[\w.]+\.)?CompletionCategory\s*\(""")
        val offenders = mutableListOf<String>()
        for (prefix in languagePrefixes()) {
            filesUnder(prefix).forEach { file ->
                if (annotation.containsMatchIn(file.readText())) offenders += relative(file)
            }
        }
        assertTrue(
            languagePrefixes().isNotEmpty(),
            "CATEGORY_BY_PATH files nothing under `${StdlibReferenceGenerator.LANGUAGES}`, so this " +
                "guard is checking nothing"
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
        assertTrue(pages.size >= 15, "only found ${pages.size} reference pages — the scan has rotted")
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
     * (16 roots, 306 files, 18 pages) so that ordinary growth does not touch them and a subtraction
     * does. The per-root half is the one that matters — a corpus can stay large while one package
     * vanishes from it.
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
            published.size >= 290,
            "only ${published.size} source files are published across ${pages.size} category " +
                "pages, against a floor of 290. Either a source root was dropped, an " +
                "EXCLUDE_PREFIXES entry grew teeth, or this scan has rotted — check which before " +
                "lowering the number."
        )

        val roots = StdlibReferenceGenerator.sourceRoots()
        assertTrue(roots.size >= 16, "SOURCE_ROOTS has shrunk to ${roots.size}, from 16")
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

    /** The path prefixes the generator files under [StdlibReferenceGenerator.LANGUAGES]. */
    private fun languagePrefixes(): List<String> =
        StdlibReferenceGenerator.pathAssignedPrefixesFor(StdlibReferenceGenerator.LANGUAGES)

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
