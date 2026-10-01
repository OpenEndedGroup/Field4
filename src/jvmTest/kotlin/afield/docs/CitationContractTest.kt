package afield.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The corpus-wide citation guard: every page that opts in, checked in one place.
 *
 * Mostly `dev_docs`, and `public/docs/autodiff_examples.md` since 2026-08-26 — see [GUARDED_PAGES].
 *
 * `SmallLanguageAnatomyTest` guards one page and does a great deal else besides (derived-count
 * ratchets, language censuses). This guards the *class* — it knows nothing about small languages, only
 * about citations — so bringing a new page under guard is one line in [GUARDED_PAGES] rather than a
 * new test class.
 *
 * ## What "opting in" means
 *
 * A page is guarded once its citations carry anchors. Two forms are understood, both resolved by
 * [CitationGuard] searching the file for the anchor rather than by counting lines:
 *
 * - ledger rows — `` | n | what it is | `path:line` | `anchor` | ``
 * - prose — `` `path:line#anchor` ``, or `` `path#anchor` `` with no number at all
 *
 * A bare `` `path:line` `` with no anchor is *not* guarded and nothing can check it. Those are counted
 * per page by [unguardedCitationsAreCountedNotIgnored] so that the size of the unchecked remainder is
 * a number in the build log instead of a thing nobody knows.
 *
 * ## Why the assertions are the shape they are
 *
 * Only properties that survive somebody inserting lines into a cited file are asserted, which rules
 * out the most tempting assertion of all: *"every advisory line number is correct"*. Enforcing that
 * would rebuild exactly the merge hazard this machinery exists to remove — one insertion above a
 * densely-cited site turns it red.
 *
 * Advisory drift is still measured, named and mechanically refreshable, but not asserted: measured
 * history found that 36% of commits touching cited files insert more than the old 40-line ceiling.
 * That made a sibling source-only branch fail this docs guard while changing no citation identity.
 * The load-bearing assertions are symbol resolution and [MIN_UNIQUE_ANCHORS].
 */
class CitationContractTest {

    // `internal`, not `private`, for one reason: ProseNumeralContractTest guards the *prose* on the
    // same list of pages, and two classes keeping two copies of "which pages are guarded" is the
    // ledger-that-can-disagree-with-itself shape this whole area exists to refuse. A `//` comment
    // rather than a KDoc block because KDocExtractor.parseDeclaration cannot read `internal companion
    // object` and would publish this prose under whatever declaration parses next — checkKDocAttachment
    // said so, on the first attempt to commit this.
    internal companion object {
        /**
         * The pages under corpus-wide guard, and the floor of anchored citations each must keep.
         *
         * The floor is what stops the cheapest possible repair — deleting the anchor, or the whole
         * citation — from turning a red build green. Raise one when you add citations; lowering one
         * should feel like an admission.
         */
        val GUARDED_PAGES = mapOf(
            "dev_docs/small_language_anatomy.md" to 134,
            // The Standard MIDI File surface, added 2026-09-04 with the page it guards. 30 anchored
            // citations as written, every anchor verified unique-in-file before the page was
            // committed, so all 30 report unbounded headroom and the corpus fragility ceiling is
            // unaffected. Floored at 14 on the DATAFRAME_PLUGIN convention: about half, so a page
            // becoming a little less citation-dense costs a doc edit rather than a red build, while
            // deleting half of them fails.
            //
            // Five of the 29 point into the two test classes rather than at the implementation, and
            // deliberately: the page's claims about *what is verified* are the ones most likely to
            // rot into overstatement, so they cite the assertion that backs them. Those five anchor
            // on a line inside each test rather than on its name, because these are backtick-named
            // functions and an anchor containing a backtick cannot survive a markdown code span.
            "dev_docs/MIDI.md" to 14,
            "dev_docs/fline_fill_orientation.md" to 20,
            "dev_docs/completion_aa_sidecar.md" to 24,
            // The DataFrame compiler-plugin wiring note. Written with 54 anchored citations, 52 of
            // them unique in their file; the two that are not (`box: dataframe_live`, which the
            // transcript writes once as a `create` and once as an `execute`) sit 52 lines apart, so
            // neither comes near the fragility ceiling. Floored under the count on purpose — a page
            // becoming a little less citation-dense should not fail a build, while deleting half of
            // them should.
            "dev_docs/DATAFRAME_PLUGIN.md" to 50,
            // The server route/command ledger. All 9 anchors were chosen unique-in-file, so the
            // corpus fragility ceiling below is unaffected by adding it.
            "dev_docs/server_surface_inventory.md" to 9,
            "dev_docs/FIELDML_HOST_BRIDGE.md" to 9,
            "dev_docs/STDLIB_DRAWING_FACE_PACKAGING.md" to 6,
            "dev_docs/arc_length_spacing.md" to 4,
            "dev_docs/morpho_circuit_search.md" to 4,
            "dev_docs/FLineGeometry.md" to 2,
            "dev_docs/kdoc_reference_contract.md" to 2,
            // The note on how guards in this tree go green over the wrong population, added
            // 2026-08-27 by the branch that found four instances of it. Guarded from its first day
            // rather than later, because a page arguing that guards should be held to their own
            // populations and sitting outside the corpus itself would be the same defect in prose
            // form. Floor 0 for the reason `public/docs/autodiff_examples.md` has one: it points at
            // code by name and by task, not by `path:line#anchor`. Raise it the day it cites a site.
            "dev_docs/guard_population_hygiene.md" to 0,
            // The one-way boundary around `src/personal/`, added 2026-09-05 with the page it guards.
            // 22 anchored citations as written, every anchor verified unique in its file before the
            // page was committed, so all 22 report unbounded headroom, and no bare citation remains.
            // Floored at 10 on the `DATAFRAME_PLUGIN.md` convention: about half, so a page becoming a
            // little less citation-dense costs a doc edit rather than a red build, while deleting
            // half of them fails.
            //
            // Eighteen of the 22 point into `PersonalBoundaryTest` itself, which is unusual and
            // deliberate: the page's subject *is* that class, and its claims about what each
            // assertion checks are the ones most likely to rot into overstatement. They anchor on a
            // declaration, or on a line inside an assertion, rather than on the assertion's name,
            // because those are backtick-named functions and an anchor containing a backtick cannot
            // survive a markdown code span. The other four are the `citationGates` registration and
            // the three sites that name the private root in order to exclude it.
            "dev_docs/private_boundary.md" to 10,
            // The page that documents this machinery, brought under it 2026-08-23. Two anchored
            // citations, both prose `path:line#anchor`; the page is mostly *about* citations rather
            // than made of them, so the floor is the count. It needs to be guarded for a reason
            // beyond symmetry: `ProseNumeralContractTest` only scans pages on this list, and three
            // of its founding derivations state their numbers here.
            "dev_docs/citation_contracts.md" to 2,
            // Folded in 2026-08-23, closing a TODO `citation_contracts.md` had been carrying since
            // the guard was written: "unguarded only because another branch owned it the night this
            // was written. It is two citations and should be folded in." It was three, and one of
            // them was broken — `server_surface_inventory.md#all-method-routes` named a fragment id
            // that page does not contain, which is precisely the class of error an unguarded page
            // cannot report.
            "dev_docs/DOCS_IMPROVEMENT_AGENT.md" to 3,
            "dev_docs/ALIFE.md" to 1,
            "dev_docs/COMPLETION_ENGINE.md" to 1,
            "dev_docs/autodiff.md" to 1,
            "dev_docs/brief_notation.md" to 1,
            "dev_docs/kotlinjs_status_and_roadmap.md" to 1,
            // One of three **published** pages added on 2026-08-26 (the others are `vision.md` and
            // `vision_geometry.md`, from a different branch the same day), and the reason here is
            // `ProseNumeralContractTest` rather than citations: `public/docs/autodiff_examples.md`
            // restated `dev_docs/autodiff.md`'s differentiability fraction and had drifted to
            // "32 of 124" against a derived 35 of 127 — both numbers stale, in a sentence a reader
            // is more likely to see than the dev_docs one. Only this list decides what that class
            // scans, so a published page cannot be pinned without appearing here.
            //
            // Floor 0, which is the honest number and not an admission: the page carries no anchored
            // citation at all, because it points at code by *name* (`FnGradientMode.EXACT`,
            // `FnFitRun.usedExactGradient`) in the register published prose is written in, rather
            // than by `path:line#anchor`. Raise it the day it cites a site — and note that a floor
            // of 0 is inert here rather than vacuous, since the numerals are what is guarded and
            // `everyDerivationIsStillUsedByItsPage` makes deleting the sentence fail.
            "public/docs/autodiff_examples.md" to 0,
            // Added 2026-08-26 alongside `autodiff_examples.md` and `vision_geometry.md`, the three
            // of them the first `public/docs` entries this list has ever had. It is here because of the
            // asymmetry this list previously had: `citationGates` has declared
            // `public/docs/**.md` as a Gradle input since 2026-08-19, so a published-page edit
            // cannot go UP-TO-DATE — but nothing on this list is under `public/docs`, so the
            // published tree was declared-as-input and never read. `public/docs/vision.md` is where
            // that cost the most: it went two whole capabilities behind the code while its
            // `dev_docs` twin stayed current, which is the same shape as
            // `public/docs/autodiff_examples.md:188` carrying pre-correction numbers for five days
            // in August.
            //
            // 49 anchored citations as written: 15 prose `path:line#anchor` in the narrative and 34
            // ledger rows in the reference table. Every anchor was chosen unique-in-file, so all 49
            // report unbounded headroom and the corpus fragility ceiling is unaffected. Floored at
            // 24 rather than 49 on the DATAFRAME_PLUGIN convention — a page becoming a little less
            // citation-dense should cost a doc edit, not a build failure, while deleting half of
            // them should be a red build.
            //
            // 11 of the 49 point at Swift under `native/field-visionos/Field4Vision/`.
            // `CitationGuard.EXT` has carried `swift` all along, so this needed no widening; it is
            // worth doing because this is a two-machine protocol and a page citing only the Kotlin
            // end would be documenting one side of it. (`EXT` still has no `cpp`/`c`/`h`/`hpp`/`cc`,
            // so the `native/field-encode` senders cannot be cited the same way. That reads as a
            // considered boundary rather than an oversight, and it is not this page's problem.)
            "public/docs/vision.md" to 24,
            // The third `public/docs` page, added the same night. Twelve ledger rows, one per
            // authoring method on the in-headset `MeshBuilder`, all anchors unique-in-file.
            //
            // This page is the one place on this branch where member *identity* can be pinned by the
            // stricter tokenised check rather than by verbatim containment, because every member is a
            // bare lowercase identifier. Bringing it under guard found a real gap on the way in:
            // `clear()` was the one builder method the page documented nowhere at all, which is
            // exactly the class of omission a count-only guard cannot see.
            "public/docs/vision_geometry.md" to 6,
            // Four more published pages, 2026-08-26. These were not written with anchors: between
            // them they carried 77 bare `path:line` citations, which `citationGates` has always
            // counted and never been able to check. `CitationGuard.anchorBareCitations` anchored 44
            // of them mechanically, by quoting the cited line — the only anchor that preserves what
            // a citation already pointed at rather than retargeting it at the enclosing declaration.
            //
            // The remaining 33 are deliberate and stay counted. 27 point at a line too structural to
            // quote (`)`, a comment terminator, a blank line, `lineCounter++`); one is a line *range*,
            // which the anchored grammar cannot express; and five are **wrong citations** the
            // anchoring found by refusing to cement them — the page names a symbol that is nowhere
            // near the line it cites. `keyframer_examples.md` says `enum class KeyDiffKind` is at
            // `keyframer.kt:915` and it is at 1040. Those five need an author to say what was meant,
            // and each is named with the true location by `anchorBareCitationsWhenAsked`.
            //
            // Floors are set to about half the anchored count, on the DATAFRAME_PLUGIN convention.
            "public/docs/statistics_examples.md" to 8,
            "public/docs/dataframe.md" to 8,
            "public/docs/keyframer_examples.md" to 5,
            "public/docs/morpho_examples.md" to 2,
            // Added 2026-08-28 with the page it guards, rather than later, and for a reason beyond
            // symmetry: this list decides what `ProseNumeralContractTest` scans, and
            // `public/docs/projection_examples.md` is unusually numeral-dense — about 46 measured
            // figures. The numbers themselves have a stronger guard than either class here
            // (`ProjectionExamplesPageTest` reads the Markdown and recomputes every one of them, in
            // both directions); this entry is about the *citations*, and about the page not sitting
            // outside the corpus whose numeral census gets printed.
            //
            // 13 anchored citations as written, all prose `path:line#anchor`, every anchor chosen
            // unique-in-file, so [MAX_FRAGILE] is unaffected. Eleven point at `smile_mds.kt`, one at
            // `graph_connectivity.kt`, and one at `build.gradle.kts` — that last because the page's
            // warning admonition is about a native-library breakage whose diagnosis lives in the
            // build script and nowhere else. Floored at 6, about half, on the DATAFRAME_PLUGIN
            // convention.
            "public/docs/projection_examples.md" to 6,
            // Added 2026-08-29 with the page it guards. `gpu_state.md` is the state half of the GPU
            // model — buffer residency, ping-pong, per-dispatch bindings, frame parity — and its
            // argument is unusually dependent on pointing at code: the whole residency vocabulary is
            // five short members of one buffer block, and the reader's next move after the page is
            // to go and read them. Twelve anchored citations as written, all prose `path:line#anchor`,
            // every anchor unique-in-file, so [MAX_FRAGILE] is unaffected. Six point at
            // `four/buffers/magic.kt`, two at `stdlib/shaders.kt`, one each at `four/Renderer.kt`,
            // `four/modules/ComputeMesh.kt`, `stacksl/StackSL.kt` and `stacksl/VolumetricNoise.kt` —
            // that last one added 2026-09-05, when the page's force law moved off a hand-rolled
            // three-slice `fbm` onto the builtin volumetric family and started making a claim about
            // the family's amplitude normalisation. Floored at 5, about half, on the DATAFRAME_PLUGIN
            // convention.
            //
            // The numeral census matters here too, and the page is written for it: every measured
            // figure on it — mean per-particle speed, the exact maximum, the sample size — is
            // labelled as an observation from one run rather than stated as a derived count, because
            // it *is* one, and a derivation over a running GPU is not a thing this corpus can offer.
            "public/docs/gpu_state.md" to 5,
            // Added 2026-09-03 with the page it guards. The output-panel sizing contract, which had
            // been documented in exactly one paragraph of `public/docs/three_output.md` and in three
            // commit messages, after producing three shipped bugs in a week.
            //
            // Guarded from its first day for a reason specific to it: the page's whole subject is a
            // set of small named functions in two languages that only make sense pointed at
            // (`isFillWidthIframe`, `fillWidthForDisclosure`, `contentSizeForDisclosure`,
            // `iframeIntrinsicSize`), one of which exists twice — once in
            // `src/commonMain/resources/outputareas2.js` and once in `src/jsMain/kotlin/afield/Main.kt`
            // — and the page's closing section says plainly that nothing compares the two copies.
            // A page whose argument is "these names are the contract" and that cannot report a
            // rename is not doing the job.
            //
            // 44 anchored citations as written, all prose `path:line#anchor`, across 9 files:
            // 18 in `outputareas2.js`, 9 in `Main.kt`, 6 in `run_ui_regressions.py`, 3 in
            // `outputs.kt`, 2 each in `iframe_autosize.js`, `Outputs.kt` and `KeyframeBrowserTest.kt`,
            // and one each in `Three.kt` and `PythonInterface.kt`. Every anchor was chosen
            // unique-in-file — verified by counting containments, not by trusting the choice — so all
            // 44 report unbounded
            // headroom and [MAX_FRAGILE] is unaffected. Every path is written repo-relative rather
            // than abbreviated, deliberately: `Main.kt` matches three files in this tree
            // (`jsMain`, `jsVision`, and the linalg generator), so an abbreviated citation to it
            // could not resolve at all.
            //
            // Floored at 21, about half, on the DATAFRAME_PLUGIN convention. Note for whoever adds the
            // next page: this entry takes the floor sum from 358 to **359**, one under
            // [MIN_UNIQUE_ANCHORS]. Its KDoc asks for a ratchet the moment the sum passes that
            // constant, because past it the corpus-wide floor is dominated by the per-page floors
            // and stops binding on the property it names. The next entry of any size crosses it.
            //
            // No `css` in `CitationGuard.EXT`, so the page's references to the
            // `.disclosure-content iframe { width: 100% !important }` rule in `field.css` are
            // deliberately written as prose without a `path:line` form. A backticked
            // `field.css:491` would look like a citation to a reader and be invisible to every
            // guard here, including the bare-citation census — the worst of both.
            "public/docs/output_panel_sizing.md" to 21,
            // Added 2026-09-04 with the page it guards. The on-disk document format: which of the
            // seven artifacts in a document directory wins on load, when each is written, and what
            // each keeps in `backups/`. Nothing published stated any of that — over the 111
            // `public/docs/*.md` pages, `grep -l 'metadata\.edn'` selected 15 and every one was a
            // demo-bundle path inside an examples page.
            //
            // Guarded from its first day because the page's load-bearing claim is one line of code
            // and cannot be paraphrased safely: `saving.kt`'s `FileInline` branch, where the loose
            // sidecar beats the inline copy inside `metadata.edn` when the file exists. A page that
            // says "the sidecar wins" and cannot report that branch being inverted is not doing the
            // job — and the inversion is a one-token change, since the branch is gated on a
            // defaulted `preferInlineFiles` parameter that one caller in `history.kt` already
            // passes `true`.
            //
            // 47 anchored citations as written, all prose `path:line#anchor`, across 12 files:
            // 26 in `saving.kt`, 7 in `FileWatchManager.kt`, 3 in `Boxes.kt`, 2 each in `history.kt`
            // and `operation_transcript.kt`, and one each in `HistoryMetrics.kt`, `outputs.kt`,
            // `workspace/transcript.kt`, `workspace/dir.kt`, `CompletionCorpus.kt`,
            // `HistoryFileInlineSmokeTest.kt` and the page's own producer
            // `DocumentOnDiskSmokeTest.kt`. Every anchor was checked unique-in-file by
            // counting containments across the whole file rather than by trusting the choice, so all
            // 47 report unbounded headroom and [MAX_FRAGILE] is unaffected. Four anchors were
            // lengthened or moved for that reason: `preferInlineFiles = version.file.name !=
            // "metadata.edn"` occurs three times in `history.kt`, so the citation points at
            // `loadVersionSnapshot` instead, and `Box.ioPersistentProperties.add(propertyName)`
            // occurs five times in one `when` in `outputs.kt`, so it points at the enclosing
            // `syncToProperty`. Paths are repo-relative throughout.
            //
            // Floored at 24, about half, on the DATAFRAME_PLUGIN convention. This entry is what took
            // the floor sum from 359 to 383 and past [MIN_UNIQUE_ANCHORS], so that constant was
            // ratcheted in the same commit, as its KDoc had been asking for.
            "public/docs/document_on_disk.md" to 24,
            // Added 2026-09-07 with the page it guards. The log layer: what a channel is, the
            // dispatcher's sink fan-out, and the three different rules that select on a channel.
            //
            // Nothing published stated any of it. Over the same 540-page corpus these guards read
            // (387 `public/docs` + 153 `dev_docs`), no page names `Logging.kt`, `logger.kt`,
            // `PinnedConsole.kt`, `StatsHeatmap.kt`, `SessionErrorLog.kt` or `SaveReloadLog.kt`,
            // and the phrase "log channel" occurs four times, every one of them assuming the
            // reader already knows what one is. `lprintln` appears in five places and is used,
            // never explained.
            //
            // Guarded from its first day because the page's headline claim is *three* lines of
            // code that have to be read together, and no one of them is surprising alone:
            // `ConsoleMute.isMuted` is `channel in channels` (exact whole string), `matchedPin`
            // splits on `,` and matches a token, and `LogStream`'s filter is
            // `contains(token, ignoreCase = true)` (substring, case-insensitive). The page's
            // argument is the *disagreement* between them. Any one of the three drifting toward
            // either of the others would make the page wrong while leaving each cited line
            // resolvable, which is why the numeral half of it is pinned too — five derivations in
            // `ProseNumeralContractTest`, including the count of real collisions the argument
            // rests on.
            //
            // 15 anchored citations as written, all prose `path:line#anchor`, across six files:
            // 5 in `Logging.kt`, 4 in `PinnedConsole.kt`, 2 each in `logger.kt` and
            // `logstream.kt`, and one each in `StatsHeatmap.kt` and `SessionErrorLog.kt`. Every
            // anchor was checked unique-in-file by counting containments across the whole file
            // rather than by trusting the choice, and two were changed for that reason: the
            // `lprintln` body's `LogDispatcher.log(channel = channel, …)` is character-identical
            // in `lprint` one function below, so the citation points at the `fun lprintln`
            // signature instead, and `StatsHeatmap.kt`'s `TOKEN` regex was cited a line late.
            // Note that `if (ConsoleMute.isMuted(event.channel)) return` is deliberately cited
            // twice, in `Logging.kt` and in `PinnedConsole.kt` — uniqueness is per file, and the
            // point of that pair of citations is that both console sinks check and nothing else
            // does.
            //
            // Floored at 7, about half, on the DATAFRAME_PLUGIN convention. Takes the floor sum
            // from 407 to **414**, still under [MIN_UNIQUE_ANCHORS], so no ratchet is needed.
            //
            // Measured by summing the literal, not carried forward: the entry above says its own
            // addition took the sum "from 359 to 383", which does not reconcile with the 33
            // floors now in this map (407 before this line). One of the two hand-arithmetic
            // steps in that chain is off by 24. Left alone rather than corrected, because it is
            // a comment on someone else's entry and nothing asserts it — but recomputed here
            // rather than incremented, for exactly that reason.
            "public/docs/logging.md" to 7,
            // Added 2026-09-12 with the page it guards. The measurement model behind Field's three
            // profiling instruments: `CPUSpan`'s always-on rolling window, the exact per-resume
            // coroutine counters, and the optional JFR sampler — what each one measures, and what
            // each one structurally cannot see.
            //
            // It is here because of the population, not for symmetry. `public/docs/profiling_timeline.md`
            // documents the same subsystem in 225 lines and carries **zero** `path:line` citations of
            // any kind, anchored or bare — measured with `grep -cE '\.(kt|js|css|py):[0-9]+'`. Over
            // the corpus as it stood that morning — 445 tracked `public/docs/*.md` and 174 tracked
            // `dev_docs/**.md`, the latter now 175 — excluding
            // `assets/`, `transcripts/` and `reference/`, `grep -lE` for
            // `CPUSpan|CoroutineProfile|profilingTimeline|JvmProfileCapture|ProfileStackSample`
            // selected **no prose page at all** before this one; a control predicate over the same
            // population selected 53 files for `renderer`, so the zero was the corpus and not the
            // instrument. A subsystem whose types are named nowhere cannot have a citation rot,
            // which is a bad reason for a page to pass a gate.
            //
            // Guarded from its first day because the page's load-bearing claims are three numeric
            // constants that a reader is invited to reason *arithmetically* from, and any of the
            // three can be changed in one token while leaving every sentence around it resolvable:
            // `WINDOW = 500` in `Renderer.kt` (from which the page derives "eight and a third
            // seconds at 60 Hz" and "p99 is the sixth worst sample"), the 10 ms `jdk.ExecutionSample`
            // period (from which it derives "a 2 ms hitch has about a one in five chance of being
            // sampled"), and the `150/s` allocation throttle (from which it derives why a per-frame
            // byte budget is a guide rather than a guarantee). Halve `WINDOW` and the page is wrong
            // about a piece's whole recent history while pointing at a line that still exists.
            //
            // 43 anchored citations as written, all prose `path:line#anchor`, across eight files:
            // 12 in `CoroutineProfiling.kt`, 11 in `JvmProfileCapture.kt`, 8 in `Renderer.kt`, 5 in
            // `profiling.kt`, 2 each in `Phased.kt`, `rendererBrowsing.kt` and
            // `profileTimelineModel.js`, and one in `lineHeatmap.js`. Every anchor was checked
            // unique-in-file by counting containments across the whole file rather than by trusting
            // the choice, so all 43 report unbounded headroom and [MAX_FRAGILE] is unaffected. No
            // bare citation remains, and the advisory line was correct on all 43 at the time of
            // writing.
            //
            // Note what is *not* cited: `CoroutineProfilingTest.kt` exists and asserts several of
            // the properties the page describes, and the `MIDI.md` and `private_boundary.md`
            // entries above would suggest citing it for them. This page does not, because it makes
            // no claim of the form "this is verified by a test" — every measured number on it names
            // the replay that produced it and the population it came from, which is a claim a
            // reader can re-run rather than one they have to take on the gate's word.
            //
            // Every path is abbreviated, which is safe here and was checked rather than assumed:
            // all nine basenames are unique in this checkout, so `CitationGuard.locate` resolves
            // each to exactly one file. That also feeds [MIN_ABBREVIATED_CITATIONS], which wants
            // this population non-empty.
            //
            // Floored at 21, about half, on the DATAFRAME_PLUGIN convention.
            // Since 2026-10-01 this text is the "Holding a frame budget" part of profiling_timeline.md,
            // which absorbed frame_budget.md; the floor moved with the citations.
            "public/docs/profiling_timeline.md" to 21,
            // The internals companion to the page above, added the same night and guarded for the
            // same reason it exists: its whole argument is a list of four constants and two
            // allocation sites that are load-bearing *for the published page*, and a page making
            // that argument while unable to report one of them being renamed would be the same
            // defect in prose form.
            //
            // 21 anchored citations as written, all prose `path:line#anchor`, across eight files:
            // 8 in `Renderer.kt`, 5 in `JvmProfileCapture.kt`, 3 in `CoroutineProfilingTest.kt`,
            // and one each in `CoroutineProfiling.kt`, `rendererBrowsing.kt`,
            // `profileTimelineModel.js`, `DemoTranscriptExecSmokeTest.kt` and
            // `tools/ui_regressions/profiling-stacks.js`. All 21 were checked unique-in-file by
            // counting containments, so [MAX_FRAGILE] is unaffected; no bare citation remains.
            //
            // That last path is the first citation on this list to point into `tools/ui_regressions`,
            // and it earns its place by being the *only* coverage the Monaco gutter stripes have —
            // the page says so, and says what that regression does not check.
            //
            // Floored at 10, about half, on the same convention. This entry and the one above take
            // the floor sum from 414 to **445**, still under [MIN_UNIQUE_ANCHORS] = 500, so no
            // ratchet is needed. Recomputed by summing the 35 literals in this map rather than
            // incremented off the `logging.md` comment above, which is what that comment asks the
            // next author to do — and note that adding two pages moved
            // `DerivedCountGuard`'s `guardedCitationPages` from 33 to 35, which
            // `ProseNumeralContractTest` correctly failed on until `dev_docs/citation_contracts.md`
            // was updated in the same commit. That is the mechanism working, and is worth recording
            // as the cost of adding a page: it is two edits, not one.
            "dev_docs/profiling_stack.md" to 10,
        )

        /**
         * Corpus floor on anchors that are unique in their file. Unique anchors have unbounded
         * [CitationGuard.headroom]: no amount of unrelated editing can make them resolve to the wrong
         * place. This is the number that actually measures how safe the corpus is.
         *
         * **Ratcheted 2026-08-27, 184 → 360**, and the argument is about domination rather than about
         * the gap. Measured that day: 403 unique of 442 total, across 26 guarded pages. But the
         * per-page floors in [GUARDED_PAGES] sum to **327**, and
         * [everyGuardedPageKeepsItsCitations] asserts every one of them — so another test in this
         * same class already guarantees at least 327 anchored citations, and a corpus-wide floor of
         * 184 could only ever bind if more than 143 of those went non-unique, which [MAX_FRAGILE]
         * independently forbids the worst of. It was not a weak floor; it was a floor that had no
         * reachable failure. At 360 it binds on the property it names — anchor *uniqueness* — rather
         * than on corpus volume, which the per-page floors already cover.
         *
         * Set above that 327 sum on purpose. If a future branch raises the per-page floors past 360,
         * raise this with them or it lapses back into being dominated.
         *
         * **Ratcheted 2026-09-04, 360 → 500**, because that is exactly what happened: adding
         * `public/docs/document_on_disk.md` at a floor of 24 took the [GUARDED_PAGES] sum from 359 to
         * **383**, past 360, and a corpus-wide floor under the sum of the per-page floors is not a
         * floor at all — [everyGuardedPageKeepsItsCitations] would have failed first, every time, and
         * this assertion would have been unreachable. Measured the same run, with the page in:
         * **529 unique of 572 parsed**, 572 resolved by symbol, 0 broken, 0 below the fragility
         * ceiling, across 30 guarded pages. 500 leaves 29 unique anchors of slack — enough that
         * lengthening or relocating a handful of anchors during a merge does not fail a build, and
         * tight enough that a page losing its citations wholesale does.
         *
         * The same reasoning applies next time. This constant has to stay above the per-page floor
         * sum *and* near the measurement; the two are 117 apart today, and it is the second of those
         * that makes the assertion mean anything.
         *
         * Note for whoever greps: there is a **second**, unrelated `MIN_UNIQUE_ANCHORS` in
         * `SmallLanguageAnatomyTest`, floored at 111 over its own single page. It is not this one and
         * does not move with it.
         */
        const val MIN_UNIQUE_ANCHORS = 500

        /**
         * Corpus ceiling on anchors fragile enough to be confused by fewer than
         * [CitationGuard.LOW_HEADROOM] inserted lines. Zero, and it should stay zero — the whole
         * argument of this change is that this population is the one that detonates on merges, and it
         * is cheap to keep empty by lengthening an anchor.
         */
        const val MAX_FRAGILE = 0

        /**
         * Floor on citations written as an **abbreviated** path — the population that only resolves
         * through [CitationGuard.indexFilesUnder], and therefore the population a nested checkout
         * inside the tree breaks all at once.
         *
         * Measured 2026-08-17: 83 of 222. Floored well under that rather than pinned, because a doc
         * legitimately becoming more explicit should not fail a build. The floor exists for one reason:
         * without it, [anAbbreviatedPathStillResolvesWithANestedCheckoutInTheTree] could become a guard
         * over an empty set — every citation spelled out in full, nothing left for the index to resolve,
         * and the regression it protects against silently unreachable.
         */
        const val MIN_ABBREVIATED_CITATIONS = 60

        /**
         * Floor on filenames that are duplicated **inside this one checkout** — the population that keeps
         * [CitationGuard.locate]'s refusal branch alive.
         *
         * This floor exists because of a hole in the first version of this night's work. "Unresolvable
         * citations: 83 before, 0 after" proves nested checkouts are excluded; it proves nothing about
         * whether the resolver still *refuses* a path that is genuinely ambiguous within one tree, which
         * is the entire reason `locate` insists on a single match. If every filename in the repo were
         * unique, that branch would be dead code and no assertion here would notice it being deleted.
         *
         * Measured 2026-08-17 in a clean worktree: **70** duplicated basenames, 16 of them `.kt`, the
         * clearest being `StandardDrawing.kt` (`afield/graphics/` and `afield/stdlib/`). Floored at 40,
         * which is well under the measurement but far enough above zero that
         * [aFilenameDuplicatedWithinThisCheckoutIsStillRefused] cannot become vacuous without saying so.
         */
        const val MIN_DUPLICATED_FILENAMES = 40
    }

    private val root = CitationGuard.repoRoot()

    private fun pages(): List<File> = GUARDED_PAGES.keys.map { rel ->
        File(root, rel).also { assertTrue(it.isFile, "guarded page $rel is missing") }
    }

    /**
     * Every anchored citation in the corpus resolves to a real site.
     *
     * This is the only hard failure in the class, and it fires for exactly one reason: something a doc
     * points at was renamed or deleted. That is an editorial event worth a human's attention. Code
     * merely moving is not, and no longer registers here at all.
     */
    @Test
    fun everyAnchoredCitationInTheCorpusResolves() {
        val broken = pages().flatMap { p ->
            val rel = p.relativeTo(root).path
            CitationGuard.parse(p, rel).map { CitationGuard.resolve(root, it) }
        }.filterIsInstance<CitationGuard.Resolution.Broken>()

        assertTrue(
            broken.isEmpty(),
            "${broken.size} citation(s) in the guarded corpus no longer resolve:\n" +
                broken.joinToString("\n") { "  ${it.citation.describe()}: ${it.reason}" }
        )
    }

    /** Each guarded page keeps at least as many anchored citations as it is on record as having. */
    @Test
    fun everyGuardedPageKeepsItsCitations() {
        val shortfalls = GUARDED_PAGES.mapNotNull { (rel, floor) ->
            val n = CitationGuard.parse(File(root, rel), rel).size
            if (n < floor) "  $rel has $n anchored citations, floor is $floor" else null
        }
        assertTrue(
            shortfalls.isEmpty(),
            "a guarded page lost citations — anchors deleted rather than repaired?\n" +
                shortfalls.joinToString("\n")
        )
    }

    /**
     * The corpus inventory, printed and floored.
     *
     * The printed block is the machine-readable record of how fragile the corpus is on any given
     * build; the assertions under it are the part that makes the number mean something.
     */
    @Test
    fun theCorpusInventoryIsReportedAndFloored() {
        val inv = CitationGuard.inventory(root, pages())
        println("--- citation inventory (${GUARDED_PAGES.size} guarded pages) ---")
        println(inv.render())

        // The same inventory, machine-readable, for questions this class did not anticipate —
        // "which guarded pages cite Server.kt?" chief among them, since that is the query that
        // turns a merge collision from a discovery into a prediction.
        val json = File(root, "build/reports/citations/inventory.json")
        CitationGuard.writeInventoryJson(root, pages(), json)
        println("machine-readable inventory: ${json.relativeTo(root).path}")
        assertTrue(json.isFile && json.length() > 0, "inventory JSON was not written")

        assertEquals(0, inv.broken.size, "unresolvable citations in the corpus")
        assertEquals(
            inv.total, inv.resolved,
            "every parsed citation must resolve by symbol; ${inv.total - inv.resolved} did not"
        )
        assertTrue(
            inv.uniqueAnchors >= MIN_UNIQUE_ANCHORS,
            "only ${inv.uniqueAnchors} of ${inv.total} corpus anchors are unique in their file " +
                "(floor $MIN_UNIQUE_ANCHORS). Lengthen anchors rather than lowering this."
        )
        assertTrue(
            inv.lowHeadroom.size <= MAX_FRAGILE,
            "${inv.lowHeadroom.size} citation(s) sit below ${CitationGuard.LOW_HEADROOM} lines of " +
                "headroom and will misresolve on the next sizeable insertion:\n" +
                inv.lowHeadroom.joinToString("\n") {
                    "  ${it.citation.describe()} — ${it.hits.size} occurrences, headroom ${it.headroom}"
                }
        )
        // Source motion is reported, not failed: the anchor is the citation identity and the
        // one-command refresh below repairs every advisory number without blocking a sibling branch.
    }

    /**
     * Count — do not check, and do not quietly ignore — the bare `path:line` citations that carry no
     * anchor.
     *
     * There are hundreds of these across `dev_docs`, and they are unverifiable by construction. The
     * honest thing is to say how many there are on the pages that are otherwise guarded, so that a
     * page cannot look protected while most of its citations are not. No floor and no ceiling: this is
     * a measurement, and it is labelled as one.
     */
    @Test
    fun unguardedCitationsAreCountedNotIgnored() {
        println("--- unguarded (anchorless) citations on guarded pages ---")
        var total = 0
        GUARDED_PAGES.keys.sorted().forEach { rel ->
            val n = CitationGuard.unguarded(File(root, rel)).size
            total += n
            println("  %-45s %d".format(rel, n))
        }
        println("  total: $total")
        assertTrue(total >= 0, "unreachable; the count is a measurement, not a contract")
    }

    // ---------------------------------------------------------------------------------------------
    // The resolver itself — why an abbreviated citation resolves, and when it must refuse to
    // ---------------------------------------------------------------------------------------------

    /**
     * How a checkout carries its `.git`, which is **not** one shape.
     *
     * `git clone` leaves a `.git` **directory**; `git worktree add` leaves a `.git` **file** holding a
     * `gitdir:` pointer. Measured 2026-08-17: all three checkouts under `.claude/worktrees/` are the
     * *file* shape, because the agent harness makes worktrees rather than clones. A rule tested only
     * against the shape that happens to be there today would work by luck and break the first time
     * somebody cloned into the tree instead. `File(dir, ".git").exists()` is true of both, and
     * [bothShapesOfANestedCheckoutAreExcluded] is what stops that being an accident.
     */
    private enum class GitShape { FILE, DIRECTORY }

    /**
     * A checkout-shaped tree at [dir]: the two markers a repository root carries, plus [files] as
     * repo-relative paths.
     *
     * Both markers, deliberately. A fixture that planted only the one the fix keys on could not tell a
     * correct rule from a rule that happens to key on the same thing for the wrong reason.
     */
    private fun plantCheckout(dir: File, gitShape: GitShape = GitShape.FILE, vararg files: String) {
        dir.mkdirs()
        File(dir, "settings.gradle.kts").writeText("// planted by CitationContractTest\n")
        when (gitShape) {
            GitShape.FILE -> File(dir, ".git").writeText("gitdir: /nowhere/planted\n")
            GitShape.DIRECTORY -> File(dir, ".git").mkdirs()
        }
        files.forEach { rel ->
            File(dir, rel).also { it.parentFile.mkdirs() }.writeText("fun planted() {}\n")
        }
    }

    private fun withPlantedTree(body: (File) -> Unit) {
        val tmp = File.createTempFile("citation-index-", "").let {
            it.delete(); it.mkdirs(); it
        }
        try {
            body(tmp)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * An abbreviated citation still resolves when the tree contains nested checkouts.
     *
     * This is the regression test for the failure that made this whole class a permanent false red in
     * `/Users/marc/field4` — the only tree the gates are actually run in — while staying green in every
     * clean worktree. The index walked `.claude/worktrees/`, which on this machine holds three complete
     * checkouts, so every filename matched four times and every abbreviated citation resolved to null.
     * Measured 2026-08-17: 83 of 222 citations broken there, 0 in a worktree.
     *
     * The decoys are planted rather than the real thing borrowed, and the assertion that they exist is
     * not decoration: without it this test passes just as happily against a tree with nothing nested in
     * it, which is the shape of "measured the easy case" this repo keeps rediscovering.
     */
    @Test
    fun anAbbreviatedPathStillResolvesWithANestedCheckoutInTheTree() = withPlantedTree { tmp ->
        val cited = "src/jvmMain/kotlin/afield/graphics/ThreeJsViewer.kt"
        plantCheckout(tmp, GitShape.FILE, cited)
        // Three, with these names, because that is what this machine actually carries today.
        val nested = listOf("faust-field-4-bindings-2699a4", "pensive-goodall-d0fb94", "serene-panini-93b3ca")
        nested.forEach { plantCheckout(File(tmp, ".claude/worktrees/$it"), GitShape.FILE, cited) }

        nested.forEach {
            assertTrue(
                File(tmp, ".claude/worktrees/$it/$cited").isFile,
                "the decoy that makes this test able to fail was never planted: $it"
            )
        }

        val index = CitationGuard.indexFilesUnder(tmp)
        assertEquals(
            1, index["ThreeJsViewer.kt"]?.size,
            "the index must see this checkout's ThreeJsViewer.kt and none of the nested copies; it saw " +
                index["ThreeJsViewer.kt"]?.map { it.relativeToOrSelf(tmp).path }
        )
        assertEquals(
            File(tmp, cited), CitationGuard.locate(tmp, "ThreeJsViewer.kt", index),
            "an abbreviated citation must survive a nested checkout"
        )
    }

    /**
     * An in-repo Gradle subproject is part of this checkout and stays in the index.
     *
     * This is the input that rules out the obvious implementation. `settings.gradle.kts` is how
     * [CitationGuard.repoRoot] recognises a root, so skipping on it looks right — and it repairs the 83
     * broken citations above while breaking 4 that currently pass, because
     * `tools/code-use-semantic-sidecar` is an in-repo build with its own `settings.gradle.kts` and
     * `dev_docs/completion_aa_sidecar.md` cites into it. `.git` is the marker that separates the two.
     */
    @Test
    fun anInRepoGradleSubprojectIsNotMistakenForANestedCheckout() = withPlantedTree { tmp ->
        plantCheckout(tmp, GitShape.FILE, "src/jvmMain/kotlin/afield/Outer.kt")
        // A subproject: its own settings.gradle.kts, no .git of its own.
        val sub = File(tmp, "tools/code-use-semantic-sidecar")
        File(sub, "src/main/kotlin/AaCompletion.kt")
            .also { it.parentFile.mkdirs() }.writeText("class AaCompleter\n")
        File(sub, "settings.gradle.kts").writeText("// in-repo build\n")
        assertTrue(!File(sub, ".git").exists(), "the fixture must not give the subproject its own .git")

        val index = CitationGuard.indexFilesUnder(tmp)
        assertEquals(
            File(sub, "src/main/kotlin/AaCompletion.kt"),
            CitationGuard.locate(tmp, "AaCompletion.kt", index),
            "a subproject's sources are part of this checkout and must stay resolvable"
        )
    }

    /**
     * The real repo still contains that input, so the test above is guarding something live.
     *
     * An argument about `tools/code-use-semantic-sidecar` is only worth the lines it takes if the
     * directory is still shaped that way and still cited. When this fails, the reasoning on
     * [CitationGuard.isForeignCheckout] has gone stale and wants re-reading, not patching.
     */
    @Test
    fun theInputThatRulesOutTheGradleMarkerIsReallyInThisRepo() {
        val sub = File(root, "tools/code-use-semantic-sidecar")
        assertTrue(
            File(sub, "settings.gradle.kts").isFile,
            "tools/code-use-semantic-sidecar no longer has its own settings.gradle.kts"
        )
        assertTrue(
            !File(sub, ".git").exists(),
            "tools/code-use-semantic-sidecar has acquired a .git and is now a separate checkout"
        )
        val cited = CitationGuard.parse(
            File(root, "dev_docs/completion_aa_sidecar.md"), "dev_docs/completion_aa_sidecar.md"
        ).map { it.path }.filter { it.endsWith("AaCompletion.kt") || it.endsWith("CodeUseSemanticSidecar.kt") }
        assertTrue(
            cited.isNotEmpty(),
            "no guarded page cites into the in-repo subproject any more, so the marker argument is moot"
        )
    }

    /**
     * The corpus really does lean on the filename index, and by how much.
     *
     * The number the nested-checkout fix is worth is the number of citations that cannot resolve without
     * the index — those written as an abbreviated path rather than a full repo-relative one. Measured
     * 2026-08-17: 83 of 222, and the floor under it is what stops the regression test above from
     * quietly becoming a guard over nothing.
     */
    @Test
    fun abbreviatedCitationsAreCountedAndFloored() {
        val cites = pages().flatMap { p -> CitationGuard.parse(p, p.relativeTo(root).path) }
        val abbreviated = cites.filterNot { File(root, it.path).isFile }
        println(
            "--- citations resolved through the filename index: ${abbreviated.size} of ${cites.size} " +
                "(floor $MIN_ABBREVIATED_CITATIONS) ---"
        )
        assertTrue(
            abbreviated.size >= MIN_ABBREVIATED_CITATIONS,
            "only ${abbreviated.size} of ${cites.size} citations are abbreviated (floor " +
                "$MIN_ABBREVIATED_CITATIONS). If docs have genuinely been spelled out in full, lower " +
                "this deliberately — but note that anAbbreviatedPathStillResolvesWithANestedCheckout" +
                "InTheTree is then guarding a case the corpus no longer contains."
        )
    }

    /**
     * Every citation resolves by agreeing with the **whole** cited path suffix.
     *
     * [CitationGuard.locate] used to fall back to "the only file with this name, wherever it is", which
     * accepts a citation whose directory component is simply wrong. That fallback resolved 0 of the 222
     * citations here when measured, so removing it cost nothing and closed a way for a misleading path to
     * live in a doc forever. This asserts the corpus still does not need it.
     */
    @Test
    fun everyCitationResolvesByPathSuffixNotByBareName() {
        val index = CitationGuard.indexFilesUnder(root)
        val laundered = pages()
            .flatMap { p -> CitationGuard.parse(p, p.relativeTo(root).path) }
            .filterNot { File(root, it.path).isFile }
            .filter { c ->
                val byName = index[c.path.substringAfterLast('/')].orEmpty()
                byName.none { it.path.endsWith("/${c.path}") } && byName.size == 1
            }
        assertTrue(
            laundered.isEmpty(),
            "${laundered.size} citation(s) name a directory that does not exist and were only resolving " +
                "because the filename happens to be unique:\n" +
                laundered.joinToString("\n") { "  ${it.describe()}" }
        )
    }

    /**
     * The unresolvable diagnostic distinguishes *missing* from *ambiguous*, and names the files.
     *
     * The message these replace said "does not exist, or the abbreviated path matches more than one
     * file". The two halves want opposite repairs, and handing a reader both is why 83 ambiguous
     * citations were read as 83 deleted files — twice.
     */
    @Test
    fun theUnresolvableDiagnosticSaysWhichFailureItWas() = withPlantedTree { tmp ->
        val cited = "src/jvmMain/kotlin/afield/graphics/ThreeJsViewer.kt"
        plantCheckout(tmp, GitShape.FILE, cited)
        val index = CitationGuard.indexFilesUnder(tmp) +
            mapOf(
                // An ambiguity *within* one checkout — the case that must still be reported, and the
                // reason the fix could not simply be "make everything unique".
                "Twin.kt" to listOf(File(tmp, "a/Twin.kt"), File(tmp, "b/Twin.kt"))
            )

        val missing = CitationGuard.explainUnresolvable(tmp, "NoSuchFile.kt", index)
        assertTrue("does not exist" in missing, "a missing file must be reported as missing: $missing")
        assertTrue(
            "ambiguous" !in missing,
            "a missing file must not be reported as possibly-ambiguous: $missing"
        )

        val ambiguous = CitationGuard.explainUnresolvable(tmp, "Twin.kt", index)
        assertTrue("ambiguous" in ambiguous, "two matches must be reported as ambiguous: $ambiguous")
        assertTrue(
            "a/Twin.kt" in ambiguous && "b/Twin.kt" in ambiguous,
            "an ambiguity must name the files it matched, or the reader has to go and find them: " +
                ambiguous
        )

        val wrongDir = CitationGuard.explainUnresolvable(tmp, "wrong/dir/ThreeJsViewer.kt", index)
        assertTrue(
            "directory in the citation is wrong" in wrongDir,
            "a right filename under a wrong directory should say so: $wrongDir"
        )
    }

    /**
     * Both shapes of nested checkout are excluded — `.git` as a **file** and as a **directory**.
     *
     * `git clone` leaves a `.git` directory; `git worktree add` leaves a `.git` file holding a `gitdir:`
     * pointer. Measured 2026-08-17, all three checkouts under `/Users/marc/field4/.claude/worktrees/`
     * are the *file* shape, because the agent harness creates worktrees. So the rule that fixed the 83
     * broken citations was, on the evidence available, only ever exercised against one of the two shapes
     * it has to handle — and the day somebody clones into the tree instead, "it worked last time" would
     * have been the only reason to believe it still worked.
     *
     * Both are planted here, and the fixture asserts it really made one of each, so this cannot silently
     * degrade into two copies of the shape that already worked.
     */
    @Test
    fun bothShapesOfANestedCheckoutAreExcluded() = withPlantedTree { tmp ->
        val cited = "src/jvmMain/kotlin/afield/graphics/ThreeJsViewer.kt"
        plantCheckout(tmp, GitShape.FILE, cited)
        val asFile = File(tmp, ".claude/worktrees/made-by-git-worktree")
        val asDir = File(tmp, "vendor/made-by-git-clone")
        plantCheckout(asFile, GitShape.FILE, cited)
        plantCheckout(asDir, GitShape.DIRECTORY, cited)

        // The fixture is the experiment; assert it is actually set up the way the argument needs.
        assertTrue(
            File(asFile, ".git").isFile,
            "the worktree-shaped decoy must carry .git as a FILE, or this test is two copies of the " +
                "same case",
        )
        assertTrue(
            File(asDir, ".git").isDirectory,
            "the clone-shaped decoy must carry .git as a DIRECTORY, or the untested shape is still " +
                "untested",
        )

        val index = CitationGuard.indexFilesUnder(tmp)
        assertEquals(
            1, index["ThreeJsViewer.kt"]?.size,
            "both shapes must be excluded; the index saw " +
                index["ThreeJsViewer.kt"]?.map { it.relativeToOrSelf(tmp).path },
        )
        assertEquals(File(tmp, cited), CitationGuard.locate(tmp, "ThreeJsViewer.kt", index))
    }

    /**
     * A filename genuinely duplicated **within this checkout** is still refused, and said to be ambiguous.
     *
     * This is the assertion the first pass of this work was missing, and the omission is the exact shape
     * this repo keeps rediscovering: the headline number could not have failed. "83 unresolvable before,
     * 0 after" demonstrates that nested checkouts no longer pollute the index. It says nothing about
     * whether `locate` still *refuses* an ambiguous path — and refusing is the whole reason it insists on
     * a single match. Had the fix accidentally been "return the first candidate", every citation would
     * still have resolved, the number would still have read 0, and nothing here would have objected.
     *
     * So this drives the **real repo index**, not a planted tree, using a real duplicated name: 70 of them
     * exist in a clean worktree ([MIN_DUPLICATED_FILENAMES]), `StandardDrawing.kt` among them
     * (`afield/graphics/` and `afield/stdlib/`). Neutering `locate`'s `singleOrNull()` to `firstOrNull()`
     * fails this test; that mutation was run, and it does.
     *
     * The second half matters as much as the first: the *disambiguated* form must keep working. It is why
     * "make every filename unique" was never an available answer to the nested-checkout problem.
     */
    @Test
    fun aFilenameDuplicatedWithinThisCheckoutIsStillRefused() {
        val index = CitationGuard.indexFilesUnder(root)
        val duplicated = index.filterValues { it.size > 1 }
        val ktDuplicated = duplicated.filterKeys { it.endsWith(".kt") }.keys.sorted()
        assertTrue(
            ktDuplicated.isNotEmpty(),
            "no .kt filename is duplicated in this checkout any more, so nothing here exercises " +
                "locate()'s refusal branch. Plant a duplicate in a fixture instead of deleting this.",
        )

        // Prefer the named example the KDoc and dev_docs argue from; fall back to whatever exists, so a
        // rename cannot turn a real assertion into a skipped one.
        val name = if ("StandardDrawing.kt" in ktDuplicated) "StandardDrawing.kt" else ktDuplicated.first()
        val matches = index.getValue(name)
        println(
            "--- ambiguous-by-design: $name matches ${matches.size} files in this checkout ---\n" +
                matches.joinToString("\n") { "  " + it.relativeToOrSelf(root).path }
        )

        assertEquals(
            null, CitationGuard.locate(root, name, index),
            "a bare `$name` matches ${matches.size} files in this checkout and must be refused, not " +
                "guessed at",
        )

        val citation = CitationGuard.Citation(
            page = "dev_docs/(synthetic)", pageLine = 1, path = name,
            advisoryLine = 1, anchor = "irrelevant — resolution fails before the file is read",
            form = CitationGuard.Form.PROSE_LINE_SYMBOL,
        )
        val resolution = CitationGuard.resolve(root, citation)
        val reason = (resolution as? CitationGuard.Resolution.Broken)?.reason
            ?: fail("an ambiguous path must resolve to Broken, not to a guess; got $resolution")
        assertTrue("ambiguous" in reason, "and must be reported as ambiguous, not missing: $reason")
        assertTrue(
            matches.take(2).all { it.relativeToOrSelf(root).path in reason },
            "and must name the files it matched, so the reader does not have to go and find them: $reason",
        )

        // The other half of the bargain: written out far enough, the same citation resolves.
        val disambiguated = matches.first().relativeToOrSelf(root).path
            .split('/').takeLast(2).joinToString("/")
        assertEquals(
            matches.first(), CitationGuard.locate(root, disambiguated, index),
            "`$disambiguated` names exactly one file and must resolve — this is why 'make every " +
                "filename unique' was never the answer",
        )
    }

    /** The duplicated-filename population, printed and floored, so the test above cannot go vacuous. */
    @Test
    fun duplicatedFilenamesWithinThisCheckoutAreCountedAndFloored() {
        val index = CitationGuard.indexFilesUnder(root)
        val duplicated = index.filterValues { it.size > 1 }
        println(
            "--- filenames duplicated within this checkout: ${duplicated.size} " +
                "(floor $MIN_DUPLICATED_FILENAMES), worst multiplicity " +
                "${duplicated.values.maxOfOrNull { it.size } ?: 0} ---"
        )
        assertTrue(
            duplicated.size >= MIN_DUPLICATED_FILENAMES,
            "only ${duplicated.size} filenames are duplicated in this checkout (floor " +
                "$MIN_DUPLICATED_FILENAMES). If that is genuinely true now, lower it deliberately — but " +
                "note aFilenameDuplicatedWithinThisCheckoutIsStillRefused is then guarding a case the " +
                "repo barely contains.",
        )
    }

    /**
     * The submodule caveat, pinned rather than asserted about the present.
     *
     * A git submodule carries a `.git` file, so [CitationGuard.isForeignCheckout] treats it as a foreign
     * checkout and its files stay out of the index. That is deliberate — a submodule is a different
     * repository — and it is documented in `dev_docs/citation_contracts.md` as the fix's one residual
     * cost, on the strength of "no citation in the corpus points into one, measured, 0 of 222".
     *
     * That sentence was true about the present with nothing holding it true. This is the holding: a full
     * repo-relative path into a submodule still resolves (tier 1 never touches the index), so only an
     * *abbreviated* one is at risk, and if somebody writes one this fails here and names it — instead of
     * failing as a mysterious "does not exist" on a file that plainly does.
     */
    @Test
    fun noGuardedPageCitesIntoASubmoduleByAnAbbreviatedPath() {
        val gitmodules = File(root, ".gitmodules")
        val submodulePaths = if (!gitmodules.isFile) emptyList() else gitmodules.readLines()
            .mapNotNull { line ->
                line.trim().removePrefix("path").trim().let {
                    if (line.trim().startsWith("path")) it.removePrefix("=").trim() else null
                }
            }
            .filter { it.isNotEmpty() }
        println("--- submodules declared in .gitmodules: $submodulePaths ---")
        assertTrue(
            submodulePaths.isNotEmpty(),
            "this repo no longer declares any submodule, so the caveat in citation_contracts.md about " +
                "abbreviated submodule citations is moot and should be deleted from the page rather " +
                "than left to mislead",
        )

        val offenders = pages()
            .flatMap { p -> CitationGuard.parse(p, p.relativeTo(root).path) }
            .filter { c ->
                // Only an abbreviated path is at risk; a full repo-relative one resolves without the index.
                !File(root, c.path).isFile && submodulePaths.any { c.path.startsWith("$it/") }
            }
        assertTrue(
            offenders.isEmpty(),
            "${offenders.size} citation(s) point into a submodule by an abbreviated path, which the " +
                "filename index deliberately cannot resolve. Write the full repo-relative path — that " +
                "still works:\n" + offenders.joinToString("\n") { "  ${it.describe()}" },
        )
    }

    /**
     * Not a test: the mechanical refresh of advisory line numbers, gated behind an environment
     * variable so an ordinary build can never rewrite tracked files.
     *
     * ```
     * FIELD_CITATIONS_WRITE=1 ./gradlew --no-daemon citationGates --rerun-tasks
     * ```
     *
     * Advisory drift is not a build failure by design; this is how it gets cleaned up without one.
     *
     * The task is `citationGates`, not `jvmTest`: `jvmTest` *excludes* these classes (see
     * `citationGuardTestClasses` in build.gradle.kts) so that a docs-only edit does not rerun the
     * whole JVM suite, and a `--tests` filter cannot re-include an excluded class — the command this
     * line used to give exits with "No tests found for given includes". `--rerun-tasks` because the
     * gate is input-tracked, so an unchanged corpus is UP-TO-DATE and the writer never runs.
     */
    @Test
    fun refreshAdvisoryLineNumbersWhenAsked() {
        if (System.getenv("FIELD_CITATIONS_WRITE") != "1") {
            val drift = CitationGuard.inventory(root, pages()).advisoryDrifted
            println(
                "advisory line numbers drifted: ${drift.size} " +
                    "(set FIELD_CITATIONS_WRITE=1 to rewrite them)"
            )
            drift.take(30).forEach {
                println("  ${it.citation.describe()} → actually at :${it.line}")
            }
            return
        }
        var changed = 0
        GUARDED_PAGES.keys.forEach { rel ->
            changed += CitationGuard.refreshAdvisoryLines(root, File(root, rel), rel)
        }
        println("refreshed $changed advisory line number(s); review the diff before committing")
    }

    /**
     * Not a test: the mechanical **anchoring** of bare citations, so a page can be brought under guard
     * without hand-writing an anchor per citation.
     *
     * ```
     * FIELD_CITATIONS_ANCHOR=public/docs/foo.md,public/docs/bar.md \
     *   ./gradlew --no-daemon citationGates --rerun-tasks
     * ```
     *
     * Takes an explicit page list rather than [GUARDED_PAGES], because the pages that need anchoring
     * are by definition the ones not yet on it. With no variable set it reports what it *would* do for
     * every guarded page plus every page under `public/docs`, which is the survey you want before
     * deciding whether a page is ready — including the citations it cannot anchor and why, since a
     * citation pointing at a blank line is a finding rather than a gap.
     */
    @Test
    fun anchorBareCitationsWhenAsked() {
        val requested = System.getenv("FIELD_CITATIONS_ANCHOR").orEmpty()
        if (requested.isBlank()) {
            val surveyed = (GUARDED_PAGES.keys + publicDocsPages()).sorted()
            val proposals = surveyed.flatMap { rel ->
                val file = File(root, rel)
                if (file.isFile) CitationGuard.proposeAnchors(root, file, rel) else emptyList()
            }
            val (anchorable, skipped) = proposals.partition { it.anchor != null }
            println(
                "bare citations: ${proposals.size} across ${surveyed.size} page(s) — " +
                    "${anchorable.size} anchorable, ${skipped.size} needing a human " +
                    "(set FIELD_CITATIONS_ANCHOR=<pages> to write the anchorable ones)"
            )
            skipped.take(40).forEach { println("  ${it.describe()}") }
            return
        }
        var changed = 0
        requested.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { rel ->
            val file = File(root, rel)
            assertTrue(file.isFile, "page to anchor is missing: $rel")
            changed += CitationGuard.anchorBareCitations(root, file, rel)
        }
        println("anchored $changed citation(s); review the diff before committing")
    }

    /** Every page under `public/docs`, so the survey above cannot miss one by forgetting to list it. */
    private fun publicDocsPages(): List<String> =
        File(root, "public/docs").walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .toList()
}
