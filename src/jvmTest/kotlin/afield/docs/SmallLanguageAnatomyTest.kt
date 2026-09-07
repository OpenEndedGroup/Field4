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
        const val EXPECTED_CITATIONS = 137

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

        /**
         * Registered main languages that deliberately do **not** appear in `DEFAULT_LANGUAGES`, and
         * so cannot open Field's own suggest widget. Asserted as an exact set by
         * [everyMainLanguageReachesFieldsOwnSuggestWidget]; every member needs its reason here.
         *
         * - `remotejs` stamps `code_language = "Javascript"`, whose Monaco id `javascript` is stock
         *   Monaco's, shared with every other JavaScript buffer in the app rather than owned by
         *   RemoteJS boxes. Adding `javascript` to `DEFAULT_LANGUAGES` would therefore route the
         *   caret in *any* JS buffer to Field's widget and, through it, to whatever `BoxExecution`
         *   that box happens to hold — Kotlin completions in a `.js` file. So this one is not the
         *   one-word list edit the other gaps were; `RemoteJSExectution.complete` is unreachable
         *   today and closing it needs a decision about scoping the gate to the box's runtime, not
         *   just to its language id.
         */
        val SUGGEST_EXEMPT_MAIN_LANGUAGES = sortedSetOf("remotejs")
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
     * A registered main language, paired with the `code_language` its topology function stamps on
     * every box it claims.
     *
     * The pair is the point. The registry id is what the *action* is called; `code_language`
     * lower-cased is the string the editor hands to `monaco.editor.setModelLanguage`
     * (`Main.kt:1838`, `BootstrapMonacoRuntime.kt:341`, both `code_language.lowercase()`), and so it
     * — not the registry id — is the Monaco language id a box in that language actually reports
     * from `model.getLanguageId()`. Everything downstream keys off that id: the Monarch grammar, and
     * [suggestServedLanguages].
     */
    private data class MainLanguage(val id: String, val codeLanguage: String) {
        /** What `model.getLanguageId()` will report for a box in this language. */
        val monacoId: String get() = codeLanguage.lowercase()

        override fun toString(): String = "$id (code_language=\"$codeLanguage\" → \"$monacoId\")"
    }

    /**
     * The registered main languages, derived from the source rather than listed here: every
     * `registerMainLanguageRuntime(` call, the `id = "…"` that follows it, and the
     * `language = "…"` in the `applyRuntimeToggle` call inside the `applyTopology` function it
     * names.
     */
    private fun registeredMainLanguages(): List<MainLanguage> {
        val found = sortedMapOf<String, MainLanguage>()
        File(root, "src/jvmMain/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val text = f.readText()
                val lines = text.lines()
                lines.forEachIndexed { i, line ->
                    if ("registerMainLanguageRuntime(" !in line) return@forEachIndexed
                    // The descriptor's `id = "…"` is the next few lines; the registry's own
                    // declaration in MainLanguageRegistry.kt has no such id and is skipped.
                    val window = lines.subList(i, (i + 10).coerceAtMost(lines.size))
                    val id = window.firstNotNullOfOrNull {
                        Regex("""id\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
                    } ?: return@forEachIndexed
                    val topology = window.firstNotNullOfOrNull {
                        Regex("""applyTopology\s*=\s*::(\w+)""").find(it)?.groupValues?.get(1)
                    } ?: error("main language '$id' in ${f.name} has no applyTopology = ::…")
                    // Every applyTopology target is a top-level function in the same file, so its
                    // body runs to the first closing brace in column zero.
                    val body = Regex("""fun\s+${Regex.escape(topology)}\s*\([\s\S]*?\n}""")
                        .find(text)?.value
                        ?: error("no top-level 'fun $topology(' in ${f.name}")
                    val codeLanguage = Regex("""language\s*=\s*"([^"]+)"""")
                        .find(body)?.groupValues?.get(1)
                        ?: error("$topology in ${f.name} passes no language = \"…\" to applyRuntimeToggle")
                    found[id] = MainLanguage(id, codeLanguage)
                }
            }
        return found.values.toList()
    }

    private fun registeredMainLanguageIds(): Set<String> =
        registeredMainLanguages().map { it.id }.toSortedSet()

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
     * Every Monaco language id the editor has a grammar for.
     *
     * Two sources, because the editor has two: the `languageId` each `bithero/x` module registers,
     * and the ids stock Monaco's `basic-languages` bundle brings with it (which is why Clojure and
     * Python need no module of their own). A module that registers nothing —
     * `bithero/languageExtensions` — simply contributes no ids.
     */
    private fun monacoGrammarLanguageIds(): Set<String> {
        val ids = sortedSetOf<String>()
        editorLanguageModules().forEach { module ->
            val name = module.removePrefix("bithero/")
            val file = File(root, "src/commonMain/resources/$name.js")
            assertTrue(file.isFile, "$module is loaded at boot but $name.js does not exist")
            Regex("""(?:var|const|let)\s+languageId\s*=\s*"([^"]+)"""")
                .findAll(file.readText())
                .forEach { ids += it.groupValues[1] }
        }
        val builtins = File(root, "src/jsMain/resources/monaco/min/vs/basic-languages")
        assertTrue(builtins.isDirectory, "no stock Monaco basic-languages bundle at ${builtins.path}")
        builtins.listFiles { f: File -> f.isDirectory }?.forEach { ids += it.name }
        return ids
    }

    /**
     * A box in a registered main language must get a Monaco grammar for the id it actually reports.
     *
     * This is the half of the 2026-09-07 morpho bug that no list would have caught. `morpho.js`
     * registered its language as `"morpho"`, but a morpho box's `code_language` is `"MorphoHDL"`, so
     * the editor called `setModelLanguage(model, "morphohdl")` — an id nobody had registered.
     * `setModelLanguage` resolves mime types and then ids, never aliases, and falls back to
     * `"plaintext"` without a word in the console. So the box was plaintext: the Monarch grammar the
     * module exists to supply never ran, and neither did anything keyed on the language id.
     *
     * Being in `FIELD_LANGUAGE_MODULES` proves only that the *module* loads. This is what proves the
     * id inside it is the id the box will ask for.
     */
    @Test
    fun everyMainLanguagesCodeLanguageHasAMonacoGrammar() {
        val languages = registeredMainLanguages()
        assertTrue(languages.size >= 5, "the registerMainLanguageRuntime scrape looks broken — $languages")
        val grammars = monacoGrammarLanguageIds()
        assertTrue(grammars.size >= 40, "the grammar-id scrape looks broken — found ${grammars.size} ids")

        val ungrammared = languages.filterNot { it.monacoId in grammars }
        assertTrue(
            ungrammared.isEmpty(),
            "these main languages stamp a code_language whose lower-cased form is not a registered " +
                "Monaco language id, so monaco.editor.setModelLanguage falls back to \"plaintext\" and " +
                "the box gets no highlighting at all:\n" +
                ungrammared.joinToString("\n") { "  $it" } +
                "\nEither rename the module's languageId to match, or change the code_language. Do not " +
                "add an alias: setModelLanguage does not consult them."
        )
    }

    /**
     * A registered main language must reach Field's own suggest widget, or its `complete` override
     * is dead code.
     *
     * `FieldSuggestController.isSupported` tests `model.getLanguageId()` against
     * `DEFAULT_LANGUAGES`, and nothing else opens the widget — so a language absent from that set
     * never sends the `complete` RPC, and a `BoxExecution.complete` written for it is unreachable.
     * That is how `MorphoExecution.complete` and `DatalogExecution.complete` both sat unreachable
     * until 2026-09-07. Step 5 of the page is the instruction; this is the enforcement.
     *
     * Asserted as an **exact set** rather than a subset, in both directions: a new language that
     * forgets `DEFAULT_LANGUAGES` fails here, and so does closing [SUGGEST_EXEMPT_MAIN_LANGUAGES]
     * without deleting the exemption. Name a new exception only with the reason written down; an
     * exemption that says nothing is the same as deleting this test.
     */
    @Test
    fun everyMainLanguageReachesFieldsOwnSuggestWidget() {
        val languages = registeredMainLanguages()
        assertTrue(languages.size >= 5, "the registerMainLanguageRuntime scrape looks broken — $languages")
        val served = suggestServedLanguages()

        val unreachable = languages.filterNot { it.monacoId in served }
        assertEquals(
            SUGGEST_EXEMPT_MAIN_LANGUAGES, unreachable.map { it.id }.toSortedSet(),
            "the set of main languages that cannot open Field's suggest widget has changed.\n" +
                "  now unreachable: ${if (unreachable.isEmpty()) "(none)" else "$unreachable"}\n" +
                "  documented exemptions: $SUGGEST_EXEMPT_MAIN_LANGUAGES\n" +
                "A newly-unreachable language means its BoxExecution.complete can never run: add its " +
                "code_language, lower-cased, to DEFAULT_LANGUAGES in fieldSuggest.js. If instead you " +
                "have just made an exempt language reachable, delete it from " +
                "SUGGEST_EXEMPT_MAIN_LANGUAGES and from the page's prose."
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
