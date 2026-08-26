package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corpus-wide guard on **numbers written in prose** on a guarded page.
 *
 * [CitationContractTest] proves an anchor still exists. This proves the arithmetic beside it. The two
 * are deliberately separate classes over the same [CitationContractTest.GUARDED_PAGES] list, because
 * they fail for different reasons and want different repairs: a broken citation is *"something you
 * point at was renamed"*, a broken numeral is *"the thing you counted changed size"*.
 *
 * ## The defect this was built against
 *
 * `dev_docs/DATAFRAME_PLUGIN.md`, 2026-08-22, arguing the blast radius of a change to
 * `ValuePrinterRegistry`:
 *
 * > In this repository there are exactly four registrations, all of them DataFrame's … so no
 * > non-DataFrame value's rendering can change.
 *
 * There are 54 in compiled source. The sentence carried a citation that resolved perfectly, on a page
 * that passed `citationGates` 307 of 307. The conclusion survived, and it survived for a reason the
 * page never gave — see [everyValuePrinterRegistrationInTheTreeKeysAClassExceptDataFrames], which is
 * the argument the page should have made and now does.
 *
 * The original grep behind that sentence saw `ValuePrinterRegistry.register` and missed the reified
 * wrapper `registerValuePrinter<T>`. The brief that dispatched this night's work recounted it and got
 * six per-file numbers, every one of them exactly one too high, because `import
 * afield.registerValuePrinter` matched the recount's grep. Two wrong counts, same mechanism, in the
 * evidence for and against the same sentence. That is the argument for deriving rather than counting.
 *
 * ## How this refuses to pass vacuously
 *
 * Five ways, in ascending order of how much they have to be believed:
 *
 * 1. **Set equality, not an integer.** A [DerivedCountGuard.Derivation] returns the *population*, and
 *    the numeral must equal its size. So a derivation can also be checked against landmarks and a
 *    floor, and two derivations that partition one population can be checked against each other.
 * 2. **Both directions.** [everyMarkedNumeralMatchesItsDerivation] fails when a numeral drifts;
 *    [everyDerivationIsStillUsedByItsPage] fails when the *sentence* is deleted. Without the second,
 *    the cheapest way to make this class green would be to remove the prose, which is the failure the
 *    whole ledger pattern exists to refuse.
 * 3. **Landmarks.** A scrape that silently stops matching presents as a green tick over an empty set.
 *    [everyDerivedPopulationClearsItsFloorAndLandmarks] distinguishes *deletion* (shrunk, landmarks
 *    intact — edit the doc) from *broken derivation* (shrunk, landmarks gone — fix the code), and the
 *    messages say which.
 * 4. **A floor on the covered population.** [MIN_MARKED_NUMERALS], in the spirit of
 *    [CitationContractTest.MIN_UNIQUE_ANCHORS]: deleting the markers must not be a way to a green
 *    build.
 * 5. **A census of the remainder.** [markedNumeralsAreFlooredAndUnmarkedOnesAreCounted] prints how
 *    many numerals on each guarded page are *not* covered, so nobody can read this class as saying
 *    more than it does.
 *
 * ## What is deliberately not assertable
 *
 * A numeral derived from a **line number**. [DerivedCountGuard] refuses markers inside code spans for
 * exactly this reason, and it is the same refusal [CitationContractTest] makes about advisory lines:
 * 36% of commits touching cited files insert more than the old ceiling, so a guard that pinned a line
 * number would turn red on a sibling branch that changed nothing. Every derivation here is a
 * population size, which no unrelated insertion can move.
 */
class ProseNumeralContractTest {

    private companion object {

        /**
         * Floor on the number of marked numerals in the corpus.
         *
         * Measured 2026-08-23, on the day the mechanism landed: **7** markers across 3 guarded pages,
         * against an unmarked remainder in the hundreds. Floored at 6 rather than pinned at 7,
         * because adding a marker should never need a second edit here and dropping below the
         * founding set should be a decision somebody makes on purpose.
         *
         * The number to watch is not this one but the ratio printed by
         * [markedNumeralsAreFlooredAndUnmarkedOnesAreCounted]. This floor exists for one reason: so
         * that deleting every marker cannot turn this class into six green ticks over nothing, which
         * is precisely how [CitationContractTest.MIN_ABBREVIATED_CITATIONS] earns its place.
         */
        const val MIN_MARKED_NUMERALS = 6

        /**
         * How many unmarked numerals to name in the census before the output stops being readable.
         * The count is the contract; the sample is a courtesy to whoever wants to mark the next one.
         */
        const val CENSUS_SAMPLE = 12

        /**
         * `registerValuePrinter<T> { … }` — the reified wrapper, which is how all but four of the
         * tree's value printers are registered and the form the 2026-08-22 grep missed.
         *
         * Anchored on `<` so that neither the declaration
         * (`ValueVisualizer.kt:2578#inline fun <reified T : Any> registerValuePrinter`) nor the
         * `import afield.registerValuePrinter` line can be mistaken for a call. That second exclusion
         * is not hypothetical: it is the whole of the recount error described in the class KDoc.
         */
        val REIFIED_CALL = Regex("""\bregisterValuePrinter<([A-Za-z0-9_.]+)>""")

        /**
         * `ValuePrinterRegistry.register(…::class.java)` — the raw form, used only by DataFrame's.
         *
         * The elision in that sentence is not decoration. Written out with a real type name, this
         * KDoc would itself match the regex below and this file would scrape its own documentation —
         * which is exactly what happened on the first run (`ProseNumeralContractTest.kt:101 'X'`).
         */
        val RAW_CALL = Regex("""\bValuePrinterRegistry\.register\(([A-Za-z0-9_.]+)::class\.java""")

        /**
         * The one [RAW_CALL] hit that is not a registration: the reified wrapper's own body, which
         * registers whatever *its* caller asked for. Excluded by name rather than by a regex accident,
         * because "the regex happened not to match it" is not a reason anybody can check later.
         */
        const val WRAPPER_TYPE_PARAMETER = "T"

        /**
         * Where a registration may live.
         *
         * `src/jvmMain` and nothing else, for two reasons that point the same way. `registerValuePrinter`
         * is declared in `ValueVisualizer.kt`, a JVM-only source, so a call from `commonMain` or
         * `jsMain` would not compile; and `src/jvmTest` contains *this file*, whose regexes and KDoc
         * mention the very call shapes it looks for. A guard that scrapes itself is not a joke, it is a
         * wrong answer — the first run of this scrape reported an unresolvable type `X` that came from
         * the line above.
         *
         * `dev_docs/viz_demo.kt` is deliberately out of scope too: it is a documentation box, not
         * compiled into the app, so its `Point3D` cannot be resolved reflectively and is not part of
         * any population a running Field has. The page names it in prose, with its own citation.
         */
        val REGISTRATION_ROOTS = arrayOf("src/jvmMain")
    }

    private val root = CitationGuard.repoRoot()

    // -------------------------------------------------------------------------------------------
    // The derivation registry
    // -------------------------------------------------------------------------------------------

    /**
     * Every number a guarded page is allowed to state, and where it comes from.
     *
     * Adding one is three lines here and a marker in the page. Deleting the marker without deleting
     * the entry fails [everyDerivationIsStillUsedByItsPage]; deleting the entry without deleting the
     * marker fails [everyMarkerNamesAKnownDerivation]. Neither half can be removed quietly.
     */
    private val derivations: List<DerivedCountGuard.Derivation> = listOf(
        DerivedCountGuard.Derivation(
            key = "valuePrinterInterfaceKeys",
            page = "dev_docs/DATAFRAME_PLUGIN.md",
            what = "value-printer registrations keyed on an interface",
            // Four is the whole population, so the floor and the count coincide today. Floored at 4
            // rather than 1 because this is the number the page's safety argument turns on: if a
            // fifth interface-keyed registration appears, the argument needs rereading, not
            // renumbering.
            floor = 4,
            landmarks = setOf(
                "DataFrameVisualizer.kt:DataFrame",
                "DataFrameVisualizer.kt:DataColumn",
                "DataFrameVisualizer.kt:DataRow",
                "DataFrameVisualizer.kt:GroupBy",
            ),
            derive = { r -> registrations(r).filter { it.isInterface }.map { it.id }.toSortedSet() },
        ),
        DerivedCountGuard.Derivation(
            key = "valuePrinterClassKeys",
            page = "dev_docs/DATAFRAME_PLUGIN.md",
            what = "value-printer registrations keyed on a class",
            // Measured 2026-08-23: 50, across five files. Floored at 30 — well under, because
            // deleting a visualizer is an ordinary thing to do and should cost a doc edit, not a
            // build failure — but far enough above zero that a derivation which stopped matching
            // would be caught by the floor even if the landmarks below were somehow all deleted too.
            floor = 30,
            landmarks = setOf(
                "FourVisualizers.kt:Renderer",
                "PolytopeVisualizers.kt:Polytope4",
                "Visualizers.kt:ADSR",
                "JoltPhysicsWorld.kt:JoltPhysicsWorld",
                "fmchirp.kt:FMChirpCurves.Params",
            ),
            derive = { r -> registrations(r).filterNot { it.isInterface }.map { it.id }.toSortedSet() },
        ),
        DerivedCountGuard.Derivation(
            key = "guardedCitationPages",
            page = "dev_docs/citation_contracts.md",
            what = "pages under the corpus-wide citation guard",
            // 17 on 2026-08-23. Floored at 10: the page's argument is that the guard covers a
            // corpus rather than a page, and below ten that stops being true in a way a reader
            // should be told about.
            floor = 10,
            landmarks = setOf(
                "dev_docs/small_language_anatomy.md",
                "dev_docs/DATAFRAME_PLUGIN.md",
                "dev_docs/server_surface_inventory.md",
            ),
            derive = { CitationContractTest.GUARDED_PAGES.keys.toSortedSet() },
        ),
        DerivedCountGuard.Derivation(
            key = "valuePrinterRegistrationsInSource",
            page = "dev_docs/citation_contracts.md",
            what = "value-printer registrations in compiled source",
            // The number the 2026-08-22 sentence got wrong, on the page that tells the story of it
            // getting it wrong. A *second* key rather than a reuse of the two on DATAFRAME_PLUGIN.md,
            // because a derivation is scoped to one page on purpose: deleting either page's sentence
            // must fail on its own, and a shared key would let one cover for the other.
            floor = 40,
            landmarks = setOf("DataFrameVisualizer.kt:DataFrame", "FourVisualizers.kt:Renderer"),
            derive = { r -> registrations(r).map { it.id }.toSortedSet() },
        ),
        DerivedCountGuard.Derivation(
            key = "citationGuardTestClasses",
            page = "dev_docs/citation_contracts.md",
            what = "test classes the focused `citationGates` task runs",
            // Seven on 2026-08-23. Floored at 3, which is the number the page's own table names —
            // below that, "the three guards" section is describing something that no longer exists.
            floor = 3,
            landmarks = setOf(
                "afield.docs.CitationContractTest",
                "afield.docs.ProseNumeralContractTest",
            ),
            derive = { r ->
                // Scraped from the build file rather than from a list here, so that this cannot
                // agree with a copy of itself. DocsGatesWiringTest already pins the other direction.
                val build = File(r, "build.gradle.kts").readText()
                val body = Regex("""val citationGuardTestClasses = listOf\(([\s\S]*?)\n\)""")
                    .find(build)?.groupValues?.get(1).orEmpty()
                Regex(""""(afield\.[A-Za-z0-9_.]+)"""").findAll(body).map { it.groupValues[1] }.toSortedSet()
            },
        ),
        DerivedCountGuard.Derivation(
            key = "differentiableFnOps",
            page = "dev_docs/autodiff.md",
            what = "`Fn` ops with an exact derivative",
            // 32 on 2026-08-23. Floored at 20: this page's whole argument is that the differentiable
            // subset is large enough to be worth having, and the ops table below the number would
            // need rewriting long before it reached twenty.
            floor = 20,
            // Lower-case, because `OpRegistry` canonicalises that way while the page writes
            // `hannWindow` in its house style. The mismatch is real and is handled by comparing
            // case-insensitively in everyMemberOfANamedPopulationAppearsOnItsPage, not by pretending
            // it is not there.
            landmarks = setOf("floor", "add", "gaussian", "atan2"),
            derive = { afield.stdlib.FnAutoDiff.differentiableOps.toSortedSet() },
            // The reason this derivation exists at all. `dev_docs/autodiff.md` says of itself:
            // *"This said 'twenty-five ops' until 2026-07-31 while the table below it already listed
            // twenty-eight, and 'thirty-one of 123' until 2026-08-07 while the table was missing
            // `floor` — the hand-kept count and the hand-kept table have now drifted apart twice, in
            // opposite directions."* A guard on the count alone would have caught neither drift,
            // because in both cases the count and the table were each internally consistent.
            membersNamedOnPage = true,
        ),
        DerivedCountGuard.Derivation(
            key = "canonicalFnOps",
            page = "dev_docs/autodiff.md",
            what = "canonical ops in `OpRegistry`, aliases resolved",
            // 124 on 2026-08-23, and the denominator of the coverage fraction the page prints. Read
            // through the same canonicalisation FnOpCoverageTest uses, so the two cannot disagree.
            floor = 80,
            landmarks = setOf("floor", "sign", "quantize"),
            derive = {
                afield.stdlib.OpRegistry.knownOps
                    .map { afield.stdlib.OpRegistry.canonical(it)?.lowercase() ?: it.lowercase() }
                    .toSortedSet()
            },
        ),
        DerivedCountGuard.Derivation(
            key = "visionPoseHostSpans",
            page = "public/docs/vision.md",
            what = "latency spans this process times on its own clock",
            // 4 on 2026-08-26, and the whole population. Floored at 4 rather than lower, on the
            // DATAFRAME_PLUGIN precedent, because this is not a decorative count. The published page
            // prints an *empty* report as its evidence that nothing has been measured on hardware,
            // and the argument for showing an empty table is that every span is named in it whether
            // or not it holds samples. A fifth span means that paragraph needs rereading, not
            // renumbering.
            floor = 4,
            landmarks = setOf("pose uplink transit", "submit to encoded (incl. render fence)"),
            // The span *labels*, not the constant names, because the labels are what the report
            // prints and therefore what the page quotes. Selected by "the literal has no `/` in it":
            // VisionPoseLatency's other three string constants are wire channel names
            // (`visionpose/ping`, `visionpose/pong`, `visionpose/latency`) and every span label is
            // human prose. That is a property of the file rather than of a line number, so it
            // survives reformatting — where scraping the `listOf(...)` inside `report()` would not.
            derive = { r ->
                val src = File(r, "src/jvmMain/kotlin/afield/graphics/VisionPoseLatency.kt")
                Regex("""const val [A-Z_]+ = "([^"]+)"""")
                    .findAll(src.readText())
                    .map { it.groupValues[1] }
                    .filterNot { '/' in it }
                    .toSortedSet()
            },
            // Not the tokenised form: every member is a multi-word phrase, and `membersNamedOnPage`
            // tokenises on `[A-Za-z_][A-Za-z0-9_]*`, so "pose uplink transit" could never match
            // however faithfully the page quotes it. The verbatim form is what such a population
            // needs, and it bites here: all four labels appear character-for-character inside the two
            // report fences in `## Where the latency goes`. Dropping a row from either fence, or
            // renaming a span in Kotlin without touching the page, now fails.
            membersQuotedVerbatim = true,
            // Backward direction. The superset is *every* `const val` string literal in the file, by
            // dropping the "no `/` in it" test that defines the population — so the three excluded
            // candidates are exactly the wire channel names `visionpose/ping`, `visionpose/pong` and
            // `visionpose/latency`. Quoting one of those in a table of span labels is the stale-row
            // case: it reads as a timing stage and is a message type. Measured 2026-08-26: 7
            // candidates, 4 in the population, and the section quotes none of the other 3.
            candidates = { r ->
                val src = File(r, "src/jvmMain/kotlin/afield/graphics/VisionPoseLatency.kt")
                Regex("""const val [A-Z_]+ = "([^"]+)"""")
                    .findAll(src.readText())
                    .map { it.groupValues[1] }
                    .toSortedSet()
            },
        ),
        DerivedCountGuard.Derivation(
            key = "visionPoseSmokeTests",
            page = "public/docs/vision.md",
            what = "`visionpose.*` FIELD_TEST ids runnable without a headset",
            // 3 on 2026-08-26, and the whole population. Floored at 3 because this number is load
            // bearing in the one direction that matters for this page: there is no Vision Pro
            // attached to the machine that builds these docs, so these three smokes are the only
            // executable evidence behind the head-tracking rows of the status table. If one is
            // deleted the page must stop citing it, and the reader should be told which.
            floor = 3,
            landmarks = setOf("visionpose.uplink", "visionpose.stereo-source", "visionpose.latency"),
            // Scraped from the FIELD_TEST blocks themselves rather than from a hand-kept list, so a
            // fourth pose smoke cannot be added without this page's count going red.
            derive = { r ->
                DerivedCountGuard.kotlinSourcesUnder(r, "src/jvmMain/kotlin/afield/examples")
                    .flatMap { f ->
                        Regex("""(?m)^id:\s*(visionpose\.[A-Za-z0-9_.-]+)\s*$""")
                            .findAll(f.readText())
                            .map { it.groupValues[1] }
                    }
                    .toSortedSet()
            },
            // Verbatim rather than tokenised: `visionpose.latency` splits into two word tokens, so it
            // could never be an element of the word set. All three ids appear verbatim in code spans
            // in the status table's third column, inside the section that states the count — so the
            // rows that claim smoke-test backing now cannot lose the test they name.
            membersQuotedVerbatim = true,
        ),
        DerivedCountGuard.Derivation(
            key = "visionPoseDeviceSpans",
            page = "public/docs/vision.md",
            what = "latency spans the visionOS head defines and reports over the wire",
            // 5 on 2026-08-26, and the whole population. The first derivation in this list whose
            // population comes from **Swift** rather than Kotlin — the two machines' span sets are
            // different sizes (4 here, 5 there) and the page states both, so a reader can tell which
            // half of the chain a span belongs to. Floored at 5: the page's sentence exists to say
            // the published fixture exercises *two of five*, and that fraction stops being true the
            // moment the population moves.
            floor = 5,
            landmarks = setOf(
                "head: jitter buffer hold (deliberate)",
                "head: POSE to texture (motion-to-photon lower bound)",
            ),
            // Scraped from `PoseLatency.Span`, whose own comment says these strings "are the wire
            // keys and appear verbatim in the host's readout, so the host does not need to know the
            // set in advance". That is exactly why it is worth pinning from this side: the Kotlin end
            // deliberately cannot enumerate them, so without this nothing in the build would notice
            // a sixth appearing or a fifth going away.
            //
            // Selected on the `head: ` prefix rather than "every string literal in the file". Today
            // those agree — all five `static let` literals in PoseLatency.swift are span names — but
            // the prefix is the load-bearing property, since it is what separates a head span from a
            // host span in the readout. Matching on it keeps the derivation meaning what its name
            // says if an unrelated constant is ever added beside them.
            derive = { r ->
                val src = File(r, "native/field-visionos/Field4Vision/Pose/PoseLatency.swift")
                Regex("""static let [A-Za-z0-9_]+ = "(head: [^"]+)"""")
                    .findAll(src.readText())
                    .map { it.groupValues[1] }
                    .toSortedSet()
            },
            // Count-only, and this one is a genuine choice rather than a tokenisation limit — the
            // verbatim form would work mechanically and it is deliberately not used. Only 2 of the 5
            // head spans appear on the page, because the page's own sentence is that the published
            // fixture *exercises two of them*; the other three are real spans that the fixture does
            // not send. Turning on membersQuotedVerbatim here would fail truthfully but for the wrong
            // reason: it would demand the page quote three spans it has nothing to say about. The
            // landmarks above are exactly the two the page does quote, which is the honest encoding.
            membersQuotedVerbatim = false,
        ),
        DerivedCountGuard.Derivation(
            key = "visionGeometryBuilderMethods",
            page = "public/docs/vision_geometry.md",
            what = "authoring methods on the in-headset `MeshBuilder`",
            // 12 on 2026-08-26, and the whole authoring surface. Floored at 12: the page's sentence is
            // "twelve methods and nothing else", which is a completeness claim rather than a
            // magnitude, and a completeness claim that can shrink silently is not one.
            floor = 12,
            landmarks = setOf("vertex", "triangle", "cube", "tube"),
            // Selected by return type, which needs no hand-kept exclusion list: an authoring call
            // returns the builder (to chain) or an `Int` (the new vertex's index). The two methods
            // this excludes, `packedVertices`/`packedIndices`, return arrays and are the harness's
            // readout rather than anything a box calls — so a future readout is excluded
            // automatically, and a future authoring method is included automatically.
            derive = { r ->
                val text = File(r, "src/jvmMain/kotlin/afield/graphics/VisionMetalKotlinJs.kt").readText()
                // Brace-match the class body so a same-named function elsewhere in the file cannot
                // join the population.
                val open = text.indexOf('{', text.indexOf("class MeshBuilder"))
                var depth = 0
                var close = open
                while (close < text.length) {
                    if (text[close] == '{') depth++
                    else if (text[close] == '}' && --depth == 0) break
                    close++
                }
                Regex("""fun\s+([A-Za-z][A-Za-z0-9_]*)\s*\((?:[^()]|\([^()]*\))*\)\s*:\s*([A-Za-z][A-Za-z0-9_<>?]*)""")
                    .findAll(text.substring(open, close))
                    .filter { it.groupValues[2] == "MeshBuilder" || it.groupValues[2] == "Int" }
                    .map { it.groupValues[1] }
                    .toSortedSet()
            },
            // TRUE, and this is the one population on this branch whose members are shaped for it:
            // every one is a bare lowercase identifier, so the tokenised form works and is stricter
            // than verbatim containment (it respects word boundaries). The table above is inside the
            // section that states the count, so adding a thirteenth builder method fails until this
            // page documents it, and renaming one fails until this page follows.
            membersNamedOnPage = true,
            // Backward direction. The superset drops the return-type test, so it is every `fun` in the
            // class body: the excluded candidates are `packedVertices`, `packedIndices` (the harness's
            // readouts) and the private `index` helper. A row for any of those in a section that says
            // "twelve methods and nothing else" would be a false claim about the authoring surface,
            // and the forward check cannot see it. Measured 2026-08-26: 15 candidates, 12 in the
            // population, and the section names none of the other 3.
            candidates = { r ->
                val text = File(r, "src/jvmMain/kotlin/afield/graphics/VisionMetalKotlinJs.kt").readText()
                val open = text.indexOf('{', text.indexOf("class MeshBuilder"))
                var depth = 0
                var close = open
                while (close < text.length) {
                    if (text[close] == '{') depth++
                    else if (text[close] == '}' && --depth == 0) break
                    close++
                }
                Regex("""fun\s+([A-Za-z][A-Za-z0-9_]*)\s*\(""")
                    .findAll(text.substring(open, close))
                    .map { it.groupValues[1] }
                    .toSortedSet()
            },
        ),
    )

    private fun derivationsByKey() = derivations.associateBy { it.key }

    private fun pageFile(rel: String) = File(root, rel).also {
        assertTrue(it.isFile, "guarded page $rel is missing")
    }

    private fun scans(): List<DerivedCountGuard.PageScan> =
        CitationContractTest.GUARDED_PAGES.keys.sorted().map {
            DerivedCountGuard.scan(pageFile(it), it)
        }

    private fun allMarked(): List<DerivedCountGuard.Marked> = scans().flatMap { it.marked }

    // -------------------------------------------------------------------------------------------
    // The assertions
    // -------------------------------------------------------------------------------------------

    /**
     * A marker must abut a numeral.
     *
     * The likeliest authoring slip by a distance — the comment written after the noun rather than
     * after the number — and if it were merely ignored the result would be a page that looks guarded
     * carrying a number that is not.
     */
    @Test
    fun everyMarkerIsAttachedToANumeral() {
        val dangling = scans().flatMap { it.dangling }
        assertTrue(
            dangling.isEmpty(),
            "${dangling.size} count marker(s) claim no numeral. The marker must sit immediately " +
                "after the number it pins — `**50**<!--count:key-->` — with at most emphasis " +
                "characters and one space between. A number spelled as a word must be one of " +
                "${DerivedCountGuard.WORD_NUMERALS.keys.sorted()}:\n" +
                dangling.joinToString("\n") { "  ${it.page}:${it.pageLine} count:${it.key} — ${it.text}" }
        )
    }

    /** Numeral → derivation. An invented key is a failure, not an unchecked number. */
    @Test
    fun everyMarkerNamesAKnownDerivation() {
        val known = derivationsByKey()
        val unknown = allMarked().filter { it.key !in known }
        assertTrue(
            unknown.isEmpty(),
            "${unknown.size} marker(s) name a derivation that does not exist. Add it to " +
                "ProseNumeralContractTest.derivations, or the number is not actually checked:\n" +
                unknown.joinToString("\n") { "  ${it.describe()}" } +
                "\nknown keys: ${known.keys.sorted()}"
        )

        val misplaced = allMarked().filter { m -> known[m.key]?.let { it.page != m.page } == true }
        assertTrue(
            misplaced.isEmpty(),
            "${misplaced.size} marker(s) sit on a different page than their derivation declares. A " +
                "number stated on two pages wants two derivations, so that deleting either sentence " +
                "still fails:\n" +
                misplaced.joinToString("\n") { "  ${it.describe()} — declared for ${known[it.key]?.page}" }
        )
    }

    /**
     * Derivation → numeral. **This is the half that makes deletion loud.**
     *
     * Without it, the cheapest repair for a red build here would be to delete the sentence, and the
     * class would go green over a page that no longer says anything. It is the same argument
     * `ServerSurfaceLedgerTest` makes for set equality over a one-way containment check, and the same
     * one [CitationContractTest.everyGuardedPageKeepsItsCitations] makes with its floors.
     */
    @Test
    fun everyDerivationIsStillUsedByItsPage() {
        val used = allMarked().map { it.key }.toSet()
        val orphaned = derivations.filter { it.key !in used }
        assertTrue(
            orphaned.isEmpty(),
            "${orphaned.size} derivation(s) are no longer stated by the page they belong to. Either " +
                "the sentence was deleted — in which case delete the derivation, deliberately — or " +
                "the marker was lost in an edit:\n" +
                orphaned.joinToString("\n") { "  count:${it.key} — ${it.page} should state ${it.what}" }
        )

        val ungoverned = derivations.filterNot { it.page in CitationContractTest.GUARDED_PAGES }
        assertTrue(
            ungoverned.isEmpty(),
            "${ungoverned.size} derivation(s) name a page that is not in GUARDED_PAGES, so this " +
                "class never scans it and the derivation is dead: " +
                ungoverned.joinToString(", ") { "${it.key} → ${it.page}" }
        )
    }

    /** The value itself. This is the assertion the 2026-08-22 sentence would have failed. */
    @Test
    fun everyMarkedNumeralMatchesItsDerivation() {
        val known = derivationsByKey()
        val wrong = allMarked().mapNotNull { m ->
            val d = known[m.key] ?: return@mapNotNull null
            val actual = d.derive(root).size
            when {
                m.value == null ->
                    "  ${m.describe()} — `${m.literal}` is not a number this understands. Write a " +
                        "digit, or one of ${DerivedCountGuard.WORD_NUMERALS.keys.sorted()}"

                m.value != actual ->
                    "  ${m.describe()} — the page says ${m.value}, the code has $actual ${d.what}"

                else -> null
            }
        }
        assertTrue(
            wrong.isEmpty(),
            "${wrong.size} number(s) stated in prose disagree with the code they describe:\n" +
                wrong.joinToString("\n") +
                "\nRecount by deriving, not by grepping — every recorded failure of this kind was a " +
                "grep that missed a wrapper."
        )
    }

    /**
     * The derivations themselves, floored and landmarked, so none can quietly become a guard over an
     * empty set.
     */
    @Test
    fun everyDerivedPopulationClearsItsFloorAndLandmarks() {
        println("--- derived populations behind prose numerals ---")
        val problems = derivations.mapNotNull { d ->
            val population = d.derive(root)
            val missing = d.landmarks - population
            println("  %-28s %4d  (floor %d)  %s".format(d.key, population.size, d.floor, d.page))
            when {
                missing.isNotEmpty() ->
                    "  count:${d.key} has ${population.size} member(s) but is missing landmark(s) " +
                        "$missing — this is a BROKEN DERIVATION, not a deletion. Fix the scrape " +
                        "before touching the page."

                population.size < d.floor ->
                    "  count:${d.key} has shrunk to ${population.size}, below its floor of ${d.floor}, " +
                        "with its landmarks intact — this is a real DELETION. Update the page and " +
                        "lower the floor deliberately."

                else -> null
            }
        }
        assertTrue(problems.isEmpty(), "derived populations are not what they claim:\n" + problems.joinToString("\n"))
    }

    /**
     * The other half of a derived count: **the list beside it.**
     *
     * `dev_docs/autodiff.md` is the case that argues for this, in its own words — the count said
     * "twenty-five" while the table listed twenty-eight, and later "thirty-one" while the table was
     * missing `floor`. Both times the count was internally consistent and both times the table was
     * too; they were consistent with *different* answers. A guard on the numeral alone would have
     * passed on both days.
     *
     * Two opt-in forms, because one shape of member cannot use the other's check.
     * [DerivedCountGuard.Derivation.membersNamedOnPage] tests token membership, which only works for
     * bare identifiers; [DerivedCountGuard.Derivation.membersQuotedVerbatim] tests substring
     * containment against the same recovered code text, which is what a multi-word member needs. See
     * [DerivedCountGuard.codeText] for why the tokenised form was unsatisfiable for those, and
     * [DerivedCountGuard.codeSpanWords] for why a whole-page word search is unusable here.
     *
     * Both are one-directional and both are scoped to the section. Neither closes the substitution
     * hole in full — that wants the page parsed as the expected set, both ways, the way
     * `ServerSurfaceLedgerTest.assertLedger` does it.
     */
    @Test
    fun everyMemberOfANamedPopulationAppearsOnItsPage() {
        val marked = allMarked().groupBy { it.key }
        val checked = derivations.filter { it.membersNamedOnPage || it.membersQuotedVerbatim }
        val problems = checked.mapNotNull { d ->
            val f = pageFile(d.page)
            // Scoped to the section the number is stated in, not to the whole page. With the page in
            // scope this check was demonstrably unable to fail — see DerivedCountGuard.sectionBounds
            // for the measurement that says so.
            val scope = marked[d.key].orEmpty().map { DerivedCountGuard.sectionBounds(f, it.pageLine) }
            // Case-insensitive in both forms, deliberately. `OpRegistry` canonicalises op names to
            // lower case; the page writes `hannWindow` and `amplitudeMod`, which is the right way for
            // a page to spell a Kotlin identifier. A case-sensitive comparison would report eight
            // false absences and teach a reader to distrust the message.
            val population = d.derive(root)
            // One `isNamed` used by both directions, so "the page names X" cannot mean two different
            // things depending on which way the comparison runs — which is how a bidirectional check
            // ends up asymmetric without anyone noticing.
            val isNamed: (String) -> Boolean = if (d.membersQuotedVerbatim) {
                val quoted = scope.joinToString("\n") { DerivedCountGuard.codeText(f, it) }.lowercase()
                ({ it.lowercase() in quoted })
            } else {
                val named = scope.flatMap { DerivedCountGuard.codeSpanWords(f, it) }
                    .map { it.lowercase() }.toSet()
                ({ it.lowercase() in named })
            }
            val verb = if (d.membersQuotedVerbatim) "quote" else "name"

            // Forward: population ⊆ page. Catches a deleted row and a rename, and catches
            // substitution too — a swapped-out member is simply absent.
            val absent = population.filterNot(isNamed).sorted()
            // Backward: (page ∩ candidates) ⊆ population, when a candidate superset is supplied.
            // Catches the stale row the forward direction cannot: something named here that the
            // population deliberately excludes.
            val stale = d.candidates?.invoke(root).orEmpty()
                .minus(population)
                .filter(isNamed)
                .sorted()

            listOfNotNull(
                absent.takeIf { it.isNotEmpty() }?.let {
                    "  count:${d.key} — ${d.page} states the number but, in the same section, does " +
                        "not $verb ${it.size} member(s) of ${d.what}: $it"
                },
                stale.takeIf { it.isNotEmpty() }?.let {
                    "  count:${d.key} — ${d.page} ${verb}s ${it.size} name(s) in that section which " +
                        "are NOT ${d.what}, though they sit beside them in the source: $it. Either " +
                        "the row is stale, or the derivation's rule and the section's claim have " +
                        "come apart."
                },
            ).joinToString("\n").takeIf { it.isNotEmpty() }
        }
        assertTrue(
            problems.isEmpty(),
            "a page states a derived count without listing what it counted — the count and the list " +
                "can then drift apart in opposite directions, which is what happened twice on " +
                "dev_docs/autodiff.md:\n" + problems.joinToString("\n")
        )
        val bidi = checked.count { it.candidates != null }
        println(
            "--- member-identity coverage: ${checked.size} of ${derivations.size} derivation(s) " +
                "pin identities, of which $bidi are bidirectional ---"
        )
        derivations.forEach { d ->
            val how = when {
                d.membersQuotedVerbatim -> "verbatim"
                d.membersNamedOnPage -> "token"
                else -> "COUNT ONLY (+${d.landmarks.size} landmark(s))"
            }
            val dir = d.candidates?.let {
                val c = it(root).size
                "both ways (vs $c candidate(s))"
            } ?: "forward only"
            println("  %-38s %-8s %-9s %s".format(d.key, "n=${d.derive(root).size}", how, dir))
        }
    }

    /**
     * The covered population, floored; the uncovered one, counted and printed.
     *
     * Both halves are the point. Without the floor this class can be made green by deleting markers;
     * without the census it reads as though a guarded page's numbers are all checked, and they are
     * emphatically not. No ceiling on the remainder — that is a measurement, and it is labelled as
     * one, exactly like [CitationContractTest.unguardedCitationsAreCountedNotIgnored].
     */
    @Test
    fun markedNumeralsAreFlooredAndUnmarkedOnesAreCounted() {
        val all = scans()
        println("--- prose numerals on guarded pages: marked (checked) vs unmarked (not) ---")
        var marked = 0
        var unmarked = 0
        all.forEach { s ->
            marked += s.marked.size
            unmarked += s.unmarked.size
            if (s.marked.isNotEmpty() || s.unmarked.isNotEmpty()) {
                println("  %-45s marked %2d   unmarked %4d".format(s.page, s.marked.size, s.unmarked.size))
            }
        }
        println("  total marked: $marked (floor $MIN_MARKED_NUMERALS)   total unmarked: $unmarked")
        println("  a sample of the unchecked remainder, for whoever marks the next one:")
        all.flatMap { it.unmarked }.take(CENSUS_SAMPLE).forEach {
            println("    ${it.page}:${it.pageLine}  `${it.literal}`  ${it.context}")
        }

        assertTrue(
            marked >= MIN_MARKED_NUMERALS,
            "only $marked prose numeral(s) in the corpus are pinned to a derivation, below the floor " +
                "of $MIN_MARKED_NUMERALS. If numbers were genuinely removed from the docs, lower this " +
                "deliberately — but note that every other test in this class is then guarding a " +
                "population the corpus barely contains."
        )
    }

    // -------------------------------------------------------------------------------------------
    // The structural claim `dev_docs/DATAFRAME_PLUGIN.md` now makes instead of a count
    // -------------------------------------------------------------------------------------------

    /**
     * **Why the 2026-08-22 blast-radius conclusion is true**, asserted rather than asserted-about.
     *
     * `58b5b3031` widened `ValuePrinterRegistry.getPrinter` from *exact class → superclass chain →
     * the runtime class's directly-declared interfaces* to *at every step of the superclass chain,
     * the whole breadth-first interface closure*. The keys that walk can newly reach are therefore
     * exactly the **interfaces** that were more than one hop out. A key that is a **class** was
     * already reachable by the old superclass-chain walk, so no class-keyed registration's dispatch
     * can have changed — whatever the total happens to be.
     *
     * So the page's conclusion does not rest on there being four registrations. It rests on this:
     * every registration in the tree except DataFrame's four keys a class. Which is a fact about the
     * code, and therefore checkable, and therefore checked here rather than counted in a sentence.
     *
     * Both directions, over set equality: the interface-keyed set must be exactly DataFrame's four,
     * and the two sets must partition the whole population with nothing left over. A registration
     * whose type this cannot resolve at all is a failure and not a skip — an unresolvable type is
     * precisely the case where "we assumed it was a class" would be doing the work.
     */
    @Test
    fun everyValuePrinterRegistrationInTheTreeKeysAClassExceptDataFrames() {
        val all = registrations(root)
        assertTrue(
            all.size >= 40,
            "the value-printer registration scrape found only ${all.size} sites, which looks like a " +
                "broken regex rather than a smaller tree: ${all.map { it.id }}"
        )

        val interfaces = all.filter { it.isInterface }
        val classes = all.filterNot { it.isInterface }
        println(
            "--- value-printer registrations: ${all.size} total, ${interfaces.size} interface-keyed, " +
                "${classes.size} class-keyed ---"
        )
        interfaces.forEach { println("  interface  ${it.id}  (${it.fqn})") }
        println("  class-keyed files: " + classes.groupingBy { it.file.name }.eachCount().toSortedMap())

        assertEquals(
            all.size, interfaces.size + classes.size,
            "the two populations must partition the whole, or the numerals on the page overlap"
        )
        assertEquals(
            setOf("DataFrame", "DataColumn", "DataRow", "GroupBy"),
            interfaces.map { it.typeName }.toSet(),
            "the only interface-keyed value-printer registrations in the tree must be DataFrame's " +
                "four. A new one means the widened getPrinter walk can newly reach it, which is " +
                "exactly the blast radius dev_docs/DATAFRAME_PLUGIN.md argues is empty. Reread that " +
                "argument; do not renumber the page."
        )
        assertTrue(
            interfaces.all { it.file.name == "DataFrameVisualizer.kt" },
            "an interface-keyed registration has appeared outside DataFrameVisualizer.kt: " +
                interfaces.filterNot { it.file.name == "DataFrameVisualizer.kt" }.map { it.id }
        )
    }

    // -------------------------------------------------------------------------------------------
    // The scrape
    // -------------------------------------------------------------------------------------------

    /**
     * One `ValuePrinterRegistry` registration site.
     *
     * @param id the stable identity used in derivations and landmarks — `File.kt:TypeAsWritten`. File
     *   *name*, not path, deliberately: a registration moving between directories is not an event the
     *   page has an opinion about, and a landmark carrying a full path would turn a routine move into
     *   a docs failure. The names are unique enough here that ambiguity would itself be visible.
     */
    private data class Registration(
        val file: File,
        val line: Int,
        val typeName: String,
        val fqn: String,
        val isInterface: Boolean,
    ) {
        val id: String get() = "${file.name}:$typeName"
    }

    private val registrationCache = HashMap<String, List<Registration>>()

    private fun registrations(r: File): List<Registration> =
        registrationCache.getOrPut(r.path) { scrapeRegistrations(r) }

    private fun scrapeRegistrations(r: File): List<Registration> {
        val out = mutableListOf<Registration>()
        val unresolved = mutableListOf<String>()
        DerivedCountGuard.kotlinSourcesUnder(r, *REGISTRATION_ROOTS).forEach { f ->
            val text = f.readText()
            if ("registerValuePrinter" !in text && "ValuePrinterRegistry" !in text) return@forEach
            val lines = text.lines()
            lines.forEachIndexed { i, line ->
                val types = REIFIED_CALL.findAll(line).map { it.groupValues[1] } +
                    RAW_CALL.findAll(line).map { it.groupValues[1] }
                types.forEach { t ->
                    if (t == WRAPPER_TYPE_PARAMETER) return@forEach
                    val cls = resolveType(f, text, t)
                    if (cls == null) {
                        unresolved += "${f.name}:${i + 1} `$t`"
                    } else {
                        out += Registration(f, i + 1, t, cls.name, cls.isInterface)
                    }
                }
            }
        }
        assertTrue(
            unresolved.isEmpty(),
            "${unresolved.size} value-printer registration(s) name a type this cannot load, so " +
                "whether they key a class or an interface is unknown — and 'assume class' is exactly " +
                "the shortcut the guard exists to refuse:\n  " + unresolved.joinToString("\n  ")
        )
        return out
    }

    /**
     * The JVM class a Kotlin type name in [file] refers to, resolved the way the compiler would look:
     * explicit import (including `as` aliases), then the file's own package, then star imports, then
     * `kotlin`/`java.lang`.
     *
     * Reflective rather than textual, and that is not a stylistic preference. Four of the registered
     * simple names are ambiguous in this tree by text alone — `Renderer` is a `class` in
     * `afield.four` **and** an `internal interface` in `afield.four.synth.FieldAudioEngine`, and
     * `Camera`, `Image` and `Scope` each have two declarations too. A grep for `interface Renderer`
     * would answer "interface" for a registration that keys the class, which is the same species of
     * mistake as the sentence this file exists to prevent.
     *
     * Loaded with `initialize = false`, so nothing in `afield.four` runs a static initialiser — these
     * are Vulkan and Jolt-adjacent classes and a gate must not touch a GPU.
     */
    private fun resolveType(file: File, text: String, typeName: String): Class<*>? {
        val pkg = Regex("""(?m)^package\s+([A-Za-z0-9_.]+)""").find(text)?.groupValues?.get(1)
        val head = typeName.substringBefore('.')
        val tail = typeName.removePrefix(head)

        val explicit = mutableListOf<String>()
        val stars = mutableListOf<String>()
        // Anchored at end-of-line and capturing the `*` inside the path, rather than as an optional
        // `(\.\*)?` group. The obvious spelling of that group does not work: `[A-Za-z0-9_.]+` is
        // greedy, includes `.`, and happily swallows the dot the group needs, so every star import
        // parses as an explicit import of a package with a trailing dot. Fifteen of FourVisualizers'
        // twenty-six registrations were unresolvable on the first run for exactly that reason.
        Regex("""(?m)^import\s+([A-Za-z0-9_.*]+)(?:\s+as\s+([A-Za-z0-9_]+))?\s*$""")
            .findAll(text).forEach { m ->
                val path = m.groupValues[1]
                val alias = m.groupValues[2]
                when {
                    path.endsWith(".*") -> stars += path.removeSuffix(".*")
                    alias.isNotEmpty() -> if (alias == head) explicit += path + tail
                    path.substringAfterLast('.') == head -> explicit += path + tail
                }
            }

        val candidates = explicit +
            listOfNotNull(pkg?.let { "$it.$typeName" }) +
            stars.map { "$it.$typeName" } +
            listOf("kotlin.$typeName", "java.lang.$typeName")

        return candidates.firstNotNullOfOrNull { load(it) }
    }

    /**
     * `Class.forName` over a dotted name whose nesting is unknown, trying `a.b.C.D` then `a.b.C$D`.
     *
     * `FMChirpCurves.Params` is why: a nested Kotlin class is `Outer$Inner` on the JVM, and the
     * citation-facing name is the dotted one a reader would write.
     */
    private fun load(dotted: String): Class<*>? {
        var name = dotted
        while (true) {
            try {
                return Class.forName(name, false, javaClass.classLoader)
            } catch (_: Throwable) {
                val i = name.lastIndexOf('.')
                if (i < 0) return null
                name = name.substring(0, i) + "$" + name.substring(i + 1)
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // The mechanism's own unit tests — planted inputs, not the real corpus
    // -------------------------------------------------------------------------------------------

    /**
     * The marker parser, against the shapes it has to get right and the ones it has to refuse.
     *
     * Driven from planted text rather than the corpus, because every one of these cases is a case the
     * corpus does not contain today and the corpus is free to keep not containing.
     */
    @Test
    fun theMarkerParserAcceptsAdjacencyAndRefusesEverythingElse() {
        fun scanText(md: String): DerivedCountGuard.PageScan {
            val tmp = File.createTempFile("prose-numeral-", ".md")
            try {
                tmp.writeText(md)
                return DerivedCountGuard.scan(tmp, "planted.md")
            } finally {
                tmp.delete()
            }
        }

        val marker = "<!--count:k-->"

        val tight = scanText("there are **50**$marker registrations\n")
        assertEquals(listOf("50"), tight.marked.map { it.literal }, "emphasis must not break adjacency")
        assertEquals(listOf(50), tight.marked.map { it.value })
        assertTrue(tight.dangling.isEmpty())

        val word = scanText("exactly *four*$marker of them\n")
        assertEquals(listOf(4), word.marked.map { it.value }, "a word numeral must be understood")

        val spaced = scanText("there are 50 $marker registrations\n")
        assertEquals(listOf(50), spaced.marked.map { it.value }, "one space is allowed")

        val grouped = scanText("a frame of 5,000$marker rows\n")
        assertEquals(listOf(5000), grouped.marked.map { it.value }, "grouping separators must parse")

        val detached = scanText("there are 50 registrations$marker\n")
        assertTrue(detached.marked.isEmpty(), "a marker after the noun claims no numeral")
        assertEquals(1, detached.dangling.size, "and must be reported as dangling, not ignored")

        // The refusal that keeps this out of the merge hazard: a marker inside a code span, where a
        // line number lives, is not a marker at all.
        val inCode = scanText("see `Foo.kt:412$marker` for the walk\n")
        assertTrue(
            inCode.marked.isEmpty() && inCode.dangling.isEmpty(),
            "a marker inside a code span must be inert — pinning a line number is the one thing this " +
                "mechanism must never make possible"
        )

        // And inside a fence, so that citation_contracts.md can show the syntax without the guard
        // trying to check the example.
        val inFence = scanText("before\n```markdown\nthe **17**$marker pages\n```\nafter 3 things\n")
        assertTrue(inFence.marked.isEmpty(), "a fenced example must not be parsed as a real marker")
        assertEquals(
            listOf("3"), inFence.unmarked.map { it.literal },
            "…and the fence's own numerals must stay out of the census: ${inFence.unmarked}"
        )

        // A word the numeral table does not know is a *misplaced marker*, not a wrong value, and the
        // distinction is the reason ATTACHED is built from WORD_NUMERALS rather than from [A-Za-z]+.
        val unknownWord = scanText("some *several*$marker of them\n")
        assertTrue(unknownWord.marked.isEmpty(), "an unrecognised word must not parse as a numeral")
        assertEquals(
            1, unknownWord.dangling.size,
            "…and must be reported as a misplaced marker, with the word list in the message"
        )
    }

    /**
     * Hyphenated numerals, and the compositions that are not numerals.
     *
     * `dev_docs/autodiff.md` writes "thirty-two", so the parser composes tens and units rather than
     * tabulating eighty more words. The refusals matter as much: "one-twenty" and "twenty-thirty" are
     * not numbers anybody writes, and a parser that quietly returned 21 and 50 for them would be
     * inventing agreement.
     */
    @Test
    fun aHyphenatedNumeralComposesAndTheNonNumeralsDoNot() {
        assertEquals(32, DerivedCountGuard.parseNumeral("thirty-two"))
        assertEquals(99, DerivedCountGuard.parseNumeral("ninety-nine"))
        assertEquals(21, DerivedCountGuard.parseNumeral("Twenty-One"), "case must not matter")
        assertEquals(null, DerivedCountGuard.parseNumeral("one-twenty"), "units-tens is not a numeral")
        assertEquals(null, DerivedCountGuard.parseNumeral("twenty-thirty"), "tens-tens is not a numeral")
        assertEquals(null, DerivedCountGuard.parseNumeral("ten-two"), "ten is not a tens prefix")
        assertEquals(null, DerivedCountGuard.parseNumeral("thirty-zero"), "there is no thirty-zero")
    }

    /**
     * Section scoping, which is what makes [everyMemberOfANamedPopulationAppearsOnItsPage] able to
     * fail at all.
     *
     * Measured on the real page before this existed: deleting `dev_docs/autodiff.md`'s whole *angle
     * family* table row left the members check green, because `atan`, `atan2` and `hypot` also appear
     * sixty lines higher in the reverse-mode op vocabulary — a different vocabulary, in a different
     * section, in a paragraph that is not the list the count refers to.
     */
    @Test
    fun aMembersListMustBeInTheSameSectionAsItsCount() {
        val tmp = File.createTempFile("prose-section-", ".md")
        try {
            tmp.writeText(
                """
                # Title
                ## Elsewhere
                A different vocabulary: `atan hypot`.
                ## The count
                Thirty-two of them:
                | group | ops |
                | --- | --- |
                | pointwise | `add subtract` |
                ## After
                `neverSeen`
                """.trimIndent() + "\n"
            )
            // Line 5 (1-based) is inside "## The count".
            val bounds = DerivedCountGuard.sectionBounds(tmp, 5)
            val words = DerivedCountGuard.codeSpanWords(tmp, bounds)
            assertTrue("add" in words, "the section's own list must be visible: $words")
            assertTrue(
                "atan" !in words && "hypot" !in words,
                "a code span in a *different* section must not satisfy the check: $words"
            )
            assertTrue("neverSeen" !in words, "nor must one in the section after: $words")
        } finally {
            tmp.delete()
        }
    }

    /**
     * The gap `membersQuotedVerbatim` exists to close, asserted in both directions.
     *
     * The first two assertions are the *defect*: a multi-word member is unreachable by
     * [DerivedCountGuard.codeSpanWords] no matter how exactly the page quotes it, because the
     * tokeniser cannot emit a token containing a space. That is what forced three derivations in this
     * class to be count-only, and a count is evadable by substitution — swap one member for another
     * at the same total and nothing notices.
     *
     * The rest pin the replacement's shape, including the two properties it deliberately does *not*
     * have, so neither is mistaken for strength later: the section scope still applies, and the line
     * join is a newline so a phrase cannot be assembled across two lines that do not contain it.
     */
    @Test
    fun aMultiWordMemberIsUnreachableByWordsAndReachableVerbatim() {
        val tmp = File.createTempFile("prose-verbatim-", ".md")
        try {
            tmp.writeText(
                """
                # Title
                ## Elsewhere
                ```
                pose age when latched
                ```
                ## The count
                Four of them:
                ```
                pose uplink transit
                submit to encoded (incl. render fence)
                ```
                And inline, in a table cell: | x | `visionpose.latency` |
                ## After
                ```
                latch to frame submit
                ```
                """.trimIndent() + "\n"
            )
            // Line 7 (1-based, "Four of them:") is inside "## The count".
            val bounds = DerivedCountGuard.sectionBounds(tmp, 7)
            val words = DerivedCountGuard.codeSpanWords(tmp, bounds)
            val verbatim = DerivedCountGuard.codeText(tmp, bounds)

            // 1. The defect. Every word of the phrase is present; the phrase is not expressible.
            assertTrue(
                "pose" in words && "uplink" in words && "transit" in words,
                "the tokeniser should see the individual words: $words"
            )
            assertTrue(
                "pose uplink transit" !in words,
                "a Set<String> of `[A-Za-z_][A-Za-z0-9_]*` matches can never contain a phrase — this " +
                    "is why membersNamedOnPage was unusable for multi-word populations"
            )
            assertTrue(
                "visionpose.latency" !in words,
                "nor a dotted id: it splits on the `.` into two tokens"
            )

            // 2. The replacement reaches both shapes — a fenced phrase and an inline dotted id.
            assertTrue("pose uplink transit" in verbatim, "verbatim text must carry the phrase")
            assertTrue(
                "submit to encoded (incl. render fence)" in verbatim,
                "including parens and a full stop, which the tokeniser also cannot represent"
            )
            assertTrue(
                "visionpose.latency" in verbatim,
                "and an inline code span inside a markdown table cell"
            )

            // 3. Still section-scoped: the mechanism must not have widened to the whole page.
            assertTrue(
                "pose age when latched" !in verbatim,
                "a phrase in a *different* section must not satisfy the verbatim check either"
            )
            assertTrue("latch to frame submit" !in verbatim, "nor one in the section after")

            // 4. No cross-line assembly. "transit" ends one line and "submit" starts the next; a
            //    space join would make this fabricated phrase appear present.
            assertTrue(
                "transit submit" !in verbatim,
                "lines must be newline-joined, or containment invents phrases that are nowhere on " +
                    "the page: ${verbatim.replace("\n", "\\n")}"
            )
        } finally {
            tmp.delete()
        }
    }

    /**
     * Why the backward direction is a **candidate-restricted** comparison and not set equality.
     *
     * The first assertion is the one that settles the design: over a realistic section,
     * [DerivedCountGuard.codeSpanWords] returns far more than the population — `fun`, a type name, a
     * parameter, the cited file's own name. So `named == population` is not a strict version of this
     * check, it is an unsatisfiable one, and no edit to the page could make it pass. That is why
     * `ServerSurfaceLedgerTest.assertLedger` can compare sets outright and this cannot: its page side
     * is a parsed table column, a set of the same kind as its population; a prose section's code text
     * is not.
     *
     * The rest pin what the restriction buys: a sibling from the same declaration site that the
     * population excludes IS caught, and ordinary code-span noise is not.
     */
    @Test
    fun theBackwardCheckIsCandidateRestrictedBecauseSetEqualityIsUnsatisfiable() {
        val tmp = File.createTempFile("prose-backward-", ".md")
        try {
            tmp.writeText(
                """
                # Title
                ## The count
                Two of them:
                | what | where | anchor |
                |---|---|---|
                | appends a vertex | `Builder.kt:10` | `fun vertex(` |
                | one face | `Builder.kt:20` | `fun triangle(` |
                | the harness readout | `Builder.kt:30` | `fun packedVertices(` |
                """.trimIndent() + "\n"
            )
            val bounds = DerivedCountGuard.sectionBounds(tmp, 3)
            val named = DerivedCountGuard.codeSpanWords(tmp, bounds)
            val population = setOf("vertex", "triangle")
            val candidates = setOf("vertex", "triangle", "packedVertices", "packedIndices")

            // 1. The design argument, asserted rather than claimed.
            assertTrue(
                named != population,
                "unrestricted set equality is unsatisfiable over a real section: the page side also " +
                    "carries $named"
            )
            assertTrue(
                "fun" in named && "Builder" in named,
                "…because code spans legitimately contain non-members: $named"
            )

            // 2. Forward direction still holds.
            assertTrue(population.all { it in named }, "every member is named: $named")

            // 3. Backward, restricted: the excluded sibling on the page is caught.
            val stale = candidates.minus(population).filter { it in named }
            assertEquals(
                listOf("packedVertices"), stale,
                "a candidate the population excludes, named in the section, is the stale-row case"
            )

            // 4. And a candidate the page does NOT name is not reported — absence is not staleness.
            assertTrue("packedIndices" !in stale, "only what the page actually names can be stale")
        } finally {
            tmp.delete()
        }
    }

    /**
     * The census excludes the categories whose digits are addresses rather than claims.
     *
     * Each of these was a real source of noise when the census was first run over the corpus on
     * 2026-08-23; without them the "unchecked remainder" reads in the thousands and stops being a
     * number anybody looks at, which is the same disease as a proud printed count with no assertion.
     */
    @Test
    fun theCensusCountsClaimsAndNotAddresses() {
        val tmp = File.createTempFile("prose-census-", ".md")
        try {
            tmp.writeText(
                """
                Measured 2026-08-17: the walk found things. In 2026 it still does.
                See [the contract](citation_contracts.md#section-2) and `Server.kt:4632`.
                1. a numbered item that is not a claim
                | 3 | a table row | `a.kt:1` | `x` |
                ## A heading { #anchor-7 }
                But 36% of commits insert more than 40 lines, and that is a claim.
                """.trimIndent() + "\n"
            )
            val s = DerivedCountGuard.scan(tmp, "planted.md")
            assertEquals(
                listOf("36%", "40"), s.unmarked.map { it.literal },
                "the census must see exactly the two claims and none of the addresses; it saw " +
                    s.unmarked.map { "${it.literal}@${it.pageLine}" }
            )
        } finally {
            tmp.delete()
        }
    }
}
