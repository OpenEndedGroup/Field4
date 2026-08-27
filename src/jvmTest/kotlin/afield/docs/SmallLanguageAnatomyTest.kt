package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard for `dev_docs/small_language_anatomy.md`.
 *
 * That page is a map of the integration points a small language in Field4 has to touch, and it is
 * built almost entirely out of `file:line` citations. A page like that is worthless the moment it is
 * one refactor old — worse than worthless, because a confident wrong citation costs more than no
 * citation. So this test holds it to the code in two different ways, and the division of labour is
 * the point.
 *
 * **Citation checking.** [everyCitedSiteStillContainsItsAnchor] parses the page's own ledger tables:
 * any table row whose last two cells are a `` `path:line` `` and a `` `anchor` `` is a citation. The
 * page is therefore the single source of truth — unlike [afield.graphics.CloseNodeCitationDriftTest],
 * whose table lives in the test and can itself drift from the doc it guards. Adding a row to the page
 * adds a checked citation with no edit here.
 *
 * As of 2026-08-12 the anchor is resolved by [CitationGuard], which **searches the whole file for it**
 * instead of looking within a slack window of the cited line. The cited line is now advisory. That
 * change is the point rather than an optimisation: while a slack window was load-bearing, this test
 * reached into every Kotlin file any concurrent branch happened to touch, so it could not be made
 * path-disjoint from anyone and detonated on merges that had nothing to do with small languages
 * (2026-08-07, 2026-08-10). See `dev_docs/citation_contracts.md`.
 *
 * **Ratchets.** The rest of the tests compare a number the page states *in prose* against a set
 * **derived from the code** — the registered main languages, the editor's language modules, the
 * languages Field's suggest widget serves, the extension registry's hook names. A count with nothing
 * behind it is the specific failure mode this repo keeps rediscovering (`dev_docs/autodiff.md` drifted
 * from 25 to 31 to 32 ops across three separate corrections, each time because the number was
 * hand-kept). So every count on that page is asserted here, in the form the page prints it, against
 * something computed.
 *
 * When one goes red: recompute, do not loosen. If a language was added, the page needs a row.
 */
class SmallLanguageAnatomyTest {

    private companion object {
        /**
         * Every citation row on the page. Ratcheted so that deleting the ledger — the cheapest way to
         * make this whole test class pass — fails instead.
         */
        const val EXPECTED_CITATIONS = 135

        /**
         * Floor on ledger anchors that are **unique in their file** and so cannot be confused no
         * matter how much code moves. Asserted as a floor rather than an equality because tightening
         * an anchor is always an improvement and should never need a second edit here.
         *
         * Deliberately *not* asserted: the number of citations whose advisory line is exactly right.
         * That number falls whenever anybody inserts a line above a cited site, so a floor under it
         * would rebuild the merge hazard this whole change exists to remove. It is reported by
         * [theCitationInventoryIsReported] and never enforced.
         */
        const val MIN_UNIQUE_ANCHORS = 111

        /**
         * Ceiling on ledger citations whose anchor is ambiguous enough to be confusable by fewer than
         * [CitationGuard.LOW_HEADROOM] inserted lines — the "surviving only at full slack" population
         * the 2026-08-10 incident report asked to be told about. Zero today; kept as an assertion so
         * that adding a fragile anchor is a build failure rather than a slow leak.
         */
        const val MAX_FRAGILE_ANCHORS = 0

        /** Rows in the "coverage-guard pattern" table. */
        const val EXPECTED_GUARD_ROWS = 9
    }

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").isFile) {
            d = d.parentFile ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return d
    }

    private val root = repoRoot()
    private val pageFile = File(root, "dev_docs/small_language_anatomy.md")
    private val page: String by lazy {
        assertTrue(pageFile.isFile, "${pageFile.path} is missing")
        pageFile.readText()
    }

    /** Citations on the page, parsed and resolved against the source by [CitationGuard]. */
    private fun citations(): List<CitationGuard.Citation> =
        CitationGuard.parse(pageFile, "dev_docs/small_language_anatomy.md")

    private fun resolutions(): List<CitationGuard.Resolution> =
        citations().map { CitationGuard.resolve(root, it) }

    private fun sourceText(relative: String): String {
        val f = File(root, relative)
        assertTrue(f.isFile, "dev_docs/small_language_anatomy.md cites $relative, which does not exist")
        return f.readText()
    }

    /**
     * The anchor named by every citation must still exist in the file it points at.
     *
     * Note what this no longer says: nothing about *where* in the file. A citation breaks when the
     * thing it cites is renamed or deleted — which is a real editorial event someone should look at —
     * and not when it merely moves, which is a thing that happens to innocent files all day.
     */
    @Test
    fun everyCitedSiteStillContainsItsAnchor() {
        val broken = resolutions().filterIsInstance<CitationGuard.Resolution.Broken>()
        assertTrue(
            broken.isEmpty(),
            "dev_docs/small_language_anatomy.md has ${broken.size} citation(s) whose anchor no longer " +
                "exists. Re-point the citation at what the code says now; do not weaken the anchor:\n" +
                broken.joinToString("\n") { "  ${it.citation.describe()}: ${it.reason}" }
        )
    }

    /**
     * The inventory: printed in full, and floored where flooring is safe.
     *
     * "Prints a proud number with no assertion under it" is this repo's clearest recurring weakness,
     * so every number here is either asserted or explicitly documented as unassertable. The division
     * is not arbitrary — it is exactly the set of properties that survive somebody inserting lines
     * into a cited file:
     *
     * - **assertable**: totals, symbol-resolution success, anchor uniqueness, anchor fragility. All
     *   are invariant under insertion, so no sibling branch can turn them red.
     * - **not assertable**: how many advisory line numbers are currently correct. Insertion breaks it
     *   by construction. It is reported so a human can run a refresh, never enforced.
     */
    @Test
    fun theCitationInventoryIsReported() {
        val inv = CitationGuard.inventory(root, listOf(pageFile))
        println("--- small_language_anatomy.md citation inventory ---")
        println(inv.render())
        inv.advisoryDrifted.take(20).forEach {
            println("  advisory drift ${it.citation.describe()} → actually at :${it.line}")
        }

        assertEquals(0, inv.broken.size, "unresolvable citations: ${inv.broken.map { it.reason }}")
        assertTrue(
            inv.uniqueAnchors >= MIN_UNIQUE_ANCHORS,
            "only ${inv.uniqueAnchors} of ${inv.total} anchors are unique in their file, below the " +
                "floor of $MIN_UNIQUE_ANCHORS. An ambiguous anchor is resolved by nearest-to-advisory " +
                "and so is only finitely robust; lengthen anchors rather than lowering this."
        )
        assertTrue(
            inv.lowHeadroom.size <= MAX_FRAGILE_ANCHORS,
            "${inv.lowHeadroom.size} citation(s) would resolve to the wrong occurrence after fewer " +
                "than ${CitationGuard.LOW_HEADROOM} inserted lines — these are the ones that detonate " +
                "on the next merge. Lengthen the anchor until it is unique:\n" +
                inv.lowHeadroom.joinToString("\n") {
                    "  ${it.citation.describe()} — ${it.hits.size} occurrences, headroom ${it.headroom}"
                }
        )
    }

    @Test
    fun theLedgerIsAsLargeAsItClaimsToBe() {
        val found = citations().size
        assertEquals(
            EXPECTED_CITATIONS, found,
            "the page now carries $found checked citations, not $EXPECTED_CITATIONS. If you added " +
                "rows deliberately, update EXPECTED_CITATIONS and the count in the page's " +
                "\"What the guard checks\" section in the same commit."
        )
        assertTrue(
            "**$EXPECTED_CITATIONS** checked citations" in page,
            "the page must state its own citation count as \"**$EXPECTED_CITATIONS** checked citations\""
        )
    }

    /**
     * The registered main languages, derived from the source rather than listed here: every
     * `registerMainLanguageRuntime(` call and the `id = "…"` that follows it.
     */
    private fun registeredMainLanguageIds(): Set<String> {
        val ids = sortedSetOf<String>()
        File(root, "src/jvmMain/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val lines = f.readText().lines()
                lines.forEachIndexed { i, line ->
                    if ("registerMainLanguageRuntime(" !in line) return@forEachIndexed
                    // The descriptor's `id = "…"` is the next few lines; the registry's own
                    // declaration in MainLanguageRegistry.kt has no such id and is skipped.
                    val window = lines.subList(i, (i + 6).coerceAtMost(lines.size))
                    window.firstNotNullOfOrNull {
                        Regex("""id\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
                    }?.let { ids += it }
                }
            }
        return ids
    }

    @Test
    fun everyRegisteredMainLanguageIsOnThePage() {
        val ids = registeredMainLanguageIds()
        assertTrue(
            ids.size >= 5,
            "the registerMainLanguageRuntime scrape looks broken — only found $ids"
        )
        assertTrue(
            "There are **${ids.size}** registered main languages" in page,
            "the page must say \"There are **${ids.size}** registered main languages\"; " +
                "the code registers $ids"
        )
        val undocumented = ids.filterNot { "| `$it` |" in page }
        assertTrue(
            undocumented.isEmpty(),
            "these main languages are registered but have no row on the page: $undocumented"
        )
    }

    /** `FIELD_LANGUAGE_MODULES` in bootstrapMonaco.js, as a set of `bithero/x` ids. */
    private fun editorLanguageModules(): Set<String> {
        val text = sourceText("src/commonMain/resources/bootstrapMonaco.js")
        val body = Regex("""const FIELD_LANGUAGE_MODULES\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
            ?: error("FIELD_LANGUAGE_MODULES not found in bootstrapMonaco.js")
        return Regex(""""(bithero/[\w-]+)"""").findAll(body).map { it.groupValues[1] }.toSortedSet()
    }

    @Test
    fun everyEditorLanguageModuleIsOnThePage() {
        val modules = editorLanguageModules()
        assertTrue(modules.size >= 8, "the FIELD_LANGUAGE_MODULES scrape looks broken — found $modules")
        assertTrue(
            "**${modules.size}** editor language modules" in page,
            "the page must say \"**${modules.size}** editor language modules\"; " +
                "bootstrapMonaco.js loads ${modules.size}: $modules"
        )
        val undocumented = modules.filterNot { it in page }
        assertTrue(
            undocumented.isEmpty(),
            "these modules load at editor boot but are not named on the page: $undocumented"
        )
    }

    /** `DEFAULT_LANGUAGES` in fieldSuggest.js — the opt-in to Field's own suggest widget. */
    private fun suggestServedLanguages(): Set<String> {
        val text = sourceText("src/commonMain/resources/fieldSuggest.js")
        val body = Regex("""const DEFAULT_LANGUAGES\s*=\s*new Set\(\[(.*?)]\)""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
            ?: error("DEFAULT_LANGUAGES not found in fieldSuggest.js")
        return Regex(""""([\w-]+)"""").findAll(body).map { it.groupValues[1] }.toSortedSet()
    }

    @Test
    fun everySuggestServedLanguageIsOnThePage() {
        val languages = suggestServedLanguages()
        assertTrue(languages.size >= 8, "the DEFAULT_LANGUAGES scrape looks broken — found $languages")
        assertTrue(
            "**${languages.size}** languages" in page,
            "the page must say \"**${languages.size}** languages\" for DEFAULT_LANGUAGES; " +
                "fieldSuggest.js serves $languages"
        )
        val undocumented = languages.filterNot { "`$it`" in page }
        assertTrue(
            undocumented.isEmpty(),
            "fieldSuggest.js serves these languages but the page does not name them: $undocumented"
        )
    }

    /**
     * The extension registry's hook surface, derived from the `callExtension(extension, "…")` calls
     * rather than from the public docs page, which is prose and can lag.
     */
    private fun registryHookNames(): Set<String> {
        val text = sourceText("src/commonMain/resources/languageExtensions.js")
        return Regex("""callExtension\(extension,\s*"(\w+)"""")
            .findAll(text).map { it.groupValues[1] }.toSortedSet()
    }

    @Test
    fun everyExtensionRegistryHookIsOnThePage() {
        val hooks = registryHookNames()
        assertTrue(hooks.size >= 5, "the callExtension scrape looks broken — found $hooks")
        assertTrue(
            "**${hooks.size}** optional hooks" in page,
            "the page must say \"**${hooks.size}** optional hooks\"; languageExtensions.js calls $hooks"
        )
        val undocumented = hooks.filterNot { "`$it`" in page }
        assertTrue(
            undocumented.isEmpty(),
            "these registry hooks exist but are not named on the page: $undocumented"
        )
    }

    /**
     * Field routes user-facing completion through its own suggest widget, not Monaco's. The page says
     * exactly one language still registers a Monaco completion provider; this is what makes that
     * claim a fact rather than a memory.
     */
    @Test
    fun monacosOwnCompletionProviderIsStillUsedExactlyOnce() {
        val users = File(root, "src/commonMain/resources").listFiles { f -> f.extension == "js" }
            ?.filter { "registerCompletionItemProvider" in it.readText() }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        assertEquals(
            listOf("msl.js"), users,
            "the page claims msl.js is the only editor resource that registers a Monaco completion " +
                "provider. Now: $users"
        )
    }

    @Test
    fun theGuardTableListsEveryGuardThePageDiscusses() {
        // Rows of the coverage-guard table are the citations whose second cell is a bare shape digit.
        val guardRows = Regex("""^\|[^|]+\|\s*[1-4]\s*\|\s*`[^`]+:\d+`\s*\|""", RegexOption.MULTILINE)
            .findAll(page).count()
        assertEquals(
            EXPECTED_GUARD_ROWS, guardRows,
            "the coverage-guard table now has $guardRows rows, not $EXPECTED_GUARD_ROWS"
        )
        assertTrue(
            "**$EXPECTED_GUARD_ROWS** coverage guards" in page,
            "the page must state its own guard count as \"**$EXPECTED_GUARD_ROWS** coverage guards\""
        )
    }
}
