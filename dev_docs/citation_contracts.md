# Citation contracts: how `dev_docs` cites code without rotting

*2026-08-12.*

A **citation contract** is the arrangement that makes a `file:line` in a `dev_docs` page a checked
fact rather than a remembered one. There are three of them in this repo, they cover different things,
and this page is the map — written because the machinery changed on 2026-08-12 and because three
separate incidents in six days all came from the same misunderstanding of what it guaranteed.

Every claim below about what a guard does was read out of the guard's source. Where a guard's own
KDoc said something the code no longer did, the code won.

## The problem, stated once

Field's dev_docs are unusually citation-dense: when this work started there were 427 `file:line`
citations across 17 pages, 195 on `small_language_anatomy.md` alone, and only two pages had any guard
at all. That density is a deliberate style, and a good one. A line number puts a
reader on the exact spot, and a page of architecture prose with no citations is a page nobody can
check.

It is also the most perishable thing a document can contain, and it perishes in a way that is
specifically nasty:

> **A line-number citation guard is never path-disjoint.**

That sentence was written down on 2026-08-07 after two branches whose *file sets did not overlap at
all* collided anyway. It is worth being precise about why, because the conclusion is not obvious. A
guard over `small_language_anatomy.md` looks like it touches one markdown file. It does not: to check
that page it opens every file the page cites — `Server.kt`, `bootstrapMonaco.js`, `FieldMLInterface.kt`,
forty others — and asserts something about their contents. So *any* branch that edits *any* cited file
is editing an input to that guard, whether or not it goes near the doc. There is no way to schedule
around it and no file-level review that predicts it. (conjecture, 2026-08-07 — generalised from two
recorded collisions, not from an exhaustive search of schedules.)

## The three guards

| guard | source | guards | how it resolves | slack |
|---|---|---|---|---|
| `CitationContractTest` | `src/jvmTest/kotlin/afield/docs/CitationContractTest.kt` | corpus-wide, opt-in pages | symbol search | n/a — line advisory |
| `SmallLanguageAnatomyTest` | `src/jvmTest/kotlin/afield/docs/SmallLanguageAnatomyTest.kt` | `small_language_anatomy.md` | symbol search | n/a — line advisory |
| `CloseNodeCitationDriftTest` | `src/jvmTest/kotlin/afield/graphics/CloseNodeCitationDriftTest.kt` | `fline_close_nodes.md` | symbol search | n/a — line advisory |

All three now resolve by symbol; **no line window survives in the repo.** `CloseNodeCitationDriftTest`
was the last, and until 2026-08-12 its window was **±1** — `subList((line - 2), (line + 1))` is 0-based,
so it spanned the cited line and one line either side.

It still differs structurally, and this is the one to know: its table of **52 citations lives in the
test**, not in the page, so the two can disagree about what is worth citing, and
`FIELD_CITATIONS_WRITE=1` cannot refresh its numbers. `theDevDocOnlyCitesSitesThisTestChecks` stops the
page citing a site the table does not know about; its regex is `([A-Za-z_0-9]+\.kt):(\d+)` — **`.kt`
only**, so the page's one `.js`/`.html` citation is invisible to it, and a cited *range* is matched at
its first line. Three citations point into `src/personal`, which is gitignored and simply absent; they
are listed in `outsideThisRepo` as an explicit admission that CI cannot check them.

Migrating it also produced the sharpest evidence for the limit described under *Headroom* below:
**32 of its 52 anchors were ambiguous**, and several had headroom 0 or 1 — `}` (684 occurrences in
`FLineGeometry.kt`), bare `toOrNull` (7), bare `endPointOf` (26). They resolved correctly only because
the advisory line happened to sit nearest the right occurrence. That is worse than it sounds: one
inserted line would have moved such a citation to a different site **silently**, where the old window
would at least have gone red. Seven were lengthened until unique, and
`noAnchorIsTooVagueToSurviveAnEdit` now keeps the rest out. The lesson generalises: *switching to
symbol resolution does not make a corpus robust; it makes a corpus's robustness measurable, and then
you still have to fix the weak anchors it finds.*

## How resolution works now

Since 2026-08-12 none of the three guards count lines. Every citation carries an **anchor**
— a substring that must still be present — and `CitationGuard` **searches the whole cited file for
it**. Where the anchor turns out to be is the answer.

The line number stays, and stays useful, but it is **advisory**: it is what the reader is shown, and
it disambiguates an anchor that occurs more than once.

### How the *path* resolves, and why only a full suffix counts

Prose cites `` `FloatEarcut.kt:705#linkedList` ``, not
`src/jvmMain/kotlin/afield/graphics/FloatEarcut.kt`, and it should keep being able to — a sentence
carrying a forty-character path is a sentence nobody reads. Measured 2026-08-17: **83 of the corpus's
222 citations are abbreviated**, so this is the majority case, not a convenience.

Two tiers, in order:

1. **Exact repo-relative path.** `File(root, path)`, if it is a file. An unambiguous citation never pays
   for the index at all.
2. **Full path *suffix*, and only if exactly one file matches.** So `graphics/StandardDrawing.kt`
   resolves even though `StandardDrawing.kt` exists twice in this repo (`graphics/` and `stdlib/`) —
   which is the case that makes "just require unique filenames" a non-answer.

Tier 2's **refusal** is the part worth guarding, and worth being careful about how you prove it. Measured
2026-08-17 in a clean worktree: **70 filenames are duplicated inside the repo proper**, 16 of them `.kt`.
That population is what keeps the refusal reachable at all, so it is floored
(`MIN_DUPLICATED_FILENAMES = 40`) and the refusal is asserted against a *real* duplicate rather than a
planted one, by `aFilenameDuplicatedWithinThisCheckoutIsStillRefused`.

The reason for that care is a hole in this night's own first pass, and it generalises. *"83 unresolvable
before, 0 after"* proves nested checkouts are excluded. It proves **nothing** about whether an ambiguous
path is still refused — had the fix accidentally been "return the first candidate", all 222 citations
would still have resolved, the number would still have read 0, and no assertion in the suite would have
objected. The check that closes it is a mutation: change tier 2's `singleOrNull()` to `firstOrNull()` and
`aFilenameDuplicatedWithinThisCheckoutIsStillRefused` fails on `StandardDrawing.kt`. Run that mutation if
you ever rewrite `locate` — a resolver suite that survives it is not testing resolution.

There used to be a third tier: *the only file with this name, wherever it is*, written
`suffix.singleOrNull() ?: byName.singleOrNull()`. It is worth knowing why that is gone, because the
shape recurs. The `singleOrNull` reads as "only answer if unambiguous" and the elvis behind it then
answers regardless — so `wrong/dir/FLine.kt` resolved happily to `graphics/FLine.kt`, and a citation
whose directory component was simply wrong could sit in a doc forever with nothing to say so. Measured
before removing it: it resolved **0** of the 222 citations, every one of which matched on the full
suffix. A tier whose only reachable effect is to launder an error.
`everyCitationResolvesByPathSuffixNotByBareName` asserts the corpus still does not need it, and will
name any citation that starts to.

This is the same shape as an ambiguity guard found neutralised elsewhere in the tree on 2026-08-16 —
`categories.singleOrNull() ?: categories.firstOrNull()`, which is *identical to `firstOrNull()`*: with
one element `single` == `first`, with several `single` == null and the elvis takes the first anyway. If
you write `singleOrNull` because ambiguity should be refused, do not put a fallback behind it.

### The two tiers, and why the obvious assertion is the wrong one

**Tier 1 — the anchor. Absolute.** It must exist. Insertion-invariant, so no sibling branch can ever
turn it red; it fires only when something is renamed or deleted, which is an editorial event a person
should look at.

**Tier 2 — the number. Measured, not asserted.** Requiring every advisory number to be exact rebuilds
the hazard immediately: a sibling inserting lines above a citation turns a docs guard red. The later
40-line ceiling only postponed that failure. Measured history found that 36% of commits touching a
cited file insert more than 40 lines, so an unrelated source branch could still fail the guard while
changing no citation identity.

The inventory therefore reports every drift and names the worst one, but source motion is not a hard
failure. The load-bearing checks are the anchor resolving absolutely, the per-page citation floors,
and the corpus floor of **184 unique anchors**. A stale number is repaired mechanically; a missing or
ambiguous symbol remains red.

So: **the anchor is guaranteed by CI absolutely; the number is reported and refreshed on demand.**

Refresh a page's worth of numbers with:

```
FIELD_CITATIONS_WRITE=1 ./gradlew --no-daemon jvmTest --tests '*CitationContract*'
```

It rewrites only the numbers, leaving anchors and prose untouched, so the diff is reviewable. It is
deliberately not wired into `check`; a guard that rewrites tracked files during an ordinary build is
a worse problem than the one being solved.

### Headroom, which replaces slack

A symbol anchor is unbreakable only when it is **unique in its file**. When it is not, the advisory
line picks the nearest occurrence, and enough drift will make a different occurrence nearest. That is
a real limit and the guard measures it rather than glossing it: `headroom` is the number of lines that
can be inserted above a cited site before it would resolve to the wrong occurrence. A unique anchor
reports `UNBOUNDED`.

This is strictly better than a global slack constant, for a reason worth internalising: **slack can
only tell you something has already broken; headroom tells you how close to breaking something is.**
The 2026-08-10 report asked for a warning about citations "surviving at exactly full slack" — headroom
is that warning, generalised, and it is asserted (`MAX_FRAGILE = 0`) rather than printed.

### The inventory, machine-readable

Every guard run writes `build/reports/citations/inventory.json` — one record per citation, with its
page, path, anchor, resolved line, occurrence count, headroom and advisory drift. It exists for the
query that would have turned two of the three recorded collisions from discoveries into predictions:

```
jq -r '.citations[] | select(.path | endswith("Server.kt")) | .page' \
  build/reports/citations/inventory.json | sort -u
```

Ask that *before* starting a branch that edits a heavily-cited file, and you know in advance which
docs are in your blast radius.

## Adding a guarded citation to a new page

1. **Write the citation with an anchor.** In prose, `` `path:line#anchor` ``, or `` `path#anchor` ``
   with no number. In a ledger table, `` | n | what it is | `path:line` | `anchor` | `` — the fourth
   cell is the anchor and always has been.
2. **Make the anchor unique in its file.** `` `runNodeProbe` `` occurs twice;
   `` `private fun runNodeProbe` `` occurs once. Uniqueness is what buys unbounded headroom. If it
   cannot be unique, the advisory number will disambiguate, and the guard will tell you how much room
   you have.
3. **Abbreviate the path if you like.** `FloatEarcut.kt:703#private fun linkedList` resolves by unique path-suffix
   match, so prose does not have to carry forty-character paths. An ambiguous abbreviation is a
   failure, not a guess.
4. **Add the page to `GUARDED_PAGES`** in `CitationContractTest`, with a floor for its citation count.
5. **Run it**: `./gradlew --no-daemon jvmTest --tests '*CitationContract*'`.

Anchors may contain spaces, quotes and parentheses. In a *table cell* a `|` must be written `\|`; the
guard unescapes it, so FieldML's `\|>` can be cited.

## Pinning a number written in prose

A citation guard proves an anchor exists. It says nothing about the *sentence* around the anchor, and
the arithmetic in that sentence is the part that rots. `ProseNumeralContractTest` closes that gap for
the same 18<!--count:guardedCitationPages--> pages `GUARDED_PAGES` already covers — this one among
them since 2026-08-23, because a page that documents the mechanism and then states three numbers
derived by it should be the first thing the mechanism checks.

### The defect it was built against

`DATAFRAME_PLUGIN.md`, 2026-08-22, arguing the blast radius of a change to `ValuePrinterRegistry`:

> In this repository there are exactly four registrations, all of them DataFrame's … so no
> non-DataFrame value's rendering can change.

There are 54<!--count:valuePrinterRegistrationsInSource--> in compiled source. The sentence carried a
citation that **resolved perfectly**, on a page that passed
`citationGates` — one of the 8<!--count:citationGuardTestClasses--> focused test classes that task
runs — with every anchor green. The grep behind it saw `ValuePrinterRegistry.register` and
missed the reified wrapper `registerValuePrinter<T>`, which is how all but four of them are written.
The conclusion happened to survive, for a reason the page never gave — see the rewritten paragraph
under *The dispatch bug this page's captures found* in that page, which now argues from *class versus
registered interface* and is checked.

The postscript is the part worth keeping. The nightly brief that commissioned this guard recounted the
same population and produced six per-file numbers, **every one exactly one too high**, because
`import afield.registerValuePrinter` matched the recount's grep. Two wrong counts, by the same
mechanism, in the evidence for and against the same sentence. Derive; do not grep.

### The marker

```markdown
Compiled source carries 4<!--count:valuePrinterInterfaceKeys--> interface-keyed
registrations and 50<!--count:valuePrinterClassKeys--> class-keyed ones.
```

An HTML comment, so the rendered sentence is unchanged. Three rules:

- **Adjacent.** The marker must abut its numeral — only `*`, `` ` ``, `_` and at most one space
  between. "Nearest number on the line" is ambiguous the first time a sentence carries two, and these
  sentences carry two constantly. A marker written after the *noun* is reported as dangling, not
  silently mis-parsed.
- **Digits or words.** `50`, `5,000`, or `four` — the sentence that motivated all of this said "four",
  and a mechanism that forces prose to write `4` in order to be checkable has made the prose worse in
  order to check it. The word list is `zero`…`twenty` plus the tens; anything else is a failure naming
  the list.
- **Never inside code.** Markers in inline code spans and fenced blocks are inert, which is why the
  example above is a fence and why `` `Foo.kt:412` `` can never be pinned. That refusal is the same
  one this page makes about advisory line numbers under *The two tiers*: 36% of commits touching cited
  files insert more than the old ceiling, so a guard that pinned a line number would rebuild the merge
  hazard under a new name. **Every derivable number here is a population size**, which no unrelated
  insertion can move.

### Adding one

1. **Add a `Derivation`** to `ProseNumeralContractTest.derivations`: a key, the page that must state
   it, a noun phrase for the failure message, a floor, a set of landmarks, and a function from the
   repo root to the **population**.
2. **Return a set, not an `Int`.** That is the whole design. An integer derivation that silently stops
   matching after a refactor presents as a green tick over zero — the failure this repo has
   rediscovered often enough to have a name for. A set can additionally be checked against its floor,
   against its landmarks, and against a sibling derivation that partitions the same population.
3. **Write the marker into the page.**
4. **Run it**: `./gradlew --no-daemon citationGates --tests '*ProseNumeralContract*'`.

### What it checks, and in which direction

| assertion | direction | fails when |
|---|---|---|
| `everyMarkedNumeralMatchesItsDerivation` | value | the sentence says 4 and the code has 54 |
| `everyMarkerNamesAKnownDerivation` | numeral → derivation | a marker names a key nobody derives |
| `everyDerivationIsStillUsedByItsPage` | derivation → numeral | **the sentence was deleted** |
| `everyMarkerIsAttachedToANumeral` | shape | a marker sits after the noun |
| `everyDerivedPopulationClearsItsFloorAndLandmarks` | anti-vacuity | a scrape shrank, and says whether that is a deletion or a broken derivation |
| `markedNumeralsAreFlooredAndUnmarkedOnesAreCounted` | anti-vacuity | every marker was deleted at once |

The third row is the one that is easy to leave out and expensive to be without: without it, the
cheapest repair for a red build is to delete the prose, and the class goes green over a page that no
longer says anything.

### The unchecked remainder, counted

A guard over three numerals on a page of forty is a guarded *sentence*, not a guarded page. So the
last assertion also prints, per page, how many numerals carry **no** marker. On the day the mechanism
landed that remainder ran to several hundred, against a handful marked — and the exact figures are
deliberately *not* written here, because a corpus-wide census total moves on every docs edit and
pinning it would rebuild the merge hazard this page spends its first half refusing. Read it off the
`citationGates` output instead; it is printed on every run.

The ratio is not embarrassing, it is the point. Most of those digits are one-off measurements
("1.617", "4157 ms") that no derivation could produce, and the honest thing is to say how many there
are rather than to let a green tick imply a page's numbers are all checked. The census excludes table
rows, fenced and inline code, ISO dates, bare years, ordered-list markers, heading anchors and link
targets — categories whose digits are addresses rather than claims about the code.

## The failure modes, with dated examples

These are different problems. Symbol anchors solve exactly one of them, and flattening the
distinction is how the wrong fix gets applied.

**1. Stale line number.** The cited code moved. *Now largely dissolved* — the guard follows the
symbol. Worked example, `fline_fill_orientation.md`, found 2026-08-12: eight numbers wrong, because
2026-08-11's merged branch rewrote `ThreeJsViewer.kt` and `threejs_viewer.html` underneath a page that
had **no guard at all**. `appendFilledMesh` had moved 843→930, `DoubleSide` 915→1010. Nobody noticed
because nothing was looking.

**2. Stale derived count.** A number in prose that was computed by hand once. *Not* a citation
problem, and symbol anchors do nothing for it; the fix is a ratchet that recomputes the number from
the code. Worked example, 2026-08-11→12: the Sonic Pi v5 merge added `"bithero/sonicpi"` to
`FIELD_LANGUAGE_MODULES`, taking it from 14 to 15. `small_language_anatomy.md` still said 14, and
`everyEditorLanguageModuleIsOnThePage` was red on `main` for about eighteen hours. The ratchet worked
— that is what it is for — but note what it caught: a large, careful, well-tested commit that had no
reason to think it was touching documentation. The same page also claimed `computeVertexNormals()` at
four sites when there were three.

**3. Unguarded prose.** A citation no regex ever saw. Before 2026-08-12 `SmallLanguageAnatomyTest`'s
pattern matched **table rows only**, so every `file.kt:NNNN` in a sentence was unchecked. Prose can now
opt in via `path:line#anchor`. **65** bare citations remain on the two guarded pages; that number is
printed on every build by `unguardedCitationsAreCountedNotIgnored`, deliberately with no floor,
because it is a measurement and pretending otherwise would be the very thing this page is against.

**4. The environment, not the corpus — a nested checkout.** New on 2026-08-17, and the one to read
first if this guard is red for you and green for everybody else.

`CitationContractTest` was a **permanent false red in `/Users/marc/field4`** — the one tree the gates
are actually run in — while passing in every clean worktree. Measured there on 2026-08-17 with the real
resolver, read-only: **83 of 222 citations unresolvable, and the 83 were exactly the 83 written as an
abbreviated path**. Zero genuine breaks. The nightly journal recorded 81 on 08-14 and 82 on 08-16; those
are the same measurement, and the count rose because the corpus gained one abbreviated citation on each
of those dates — not because anything drifted.

The cause was `CitationGuard`'s filename index. It walked the whole tree skipping
`.git`/`build`/`node_modules`/`.gradle`/`out`/`.idea`, and **not** `.claude/worktrees/`, which on this
machine holds complete nested checkouts left by agents — three of them that day. Every source filename
therefore matched four times, and `locate()` answers only when exactly one file matches, so every
abbreviated citation resolved to null at once. `ThreeJsViewer.kt`: 4 files in the primary tree, 1 in a
worktree.

Two lessons, and the second is the more useful:

- **A guard that is red only in the tree you work in is worse than no guard**, because it trains you to
  skip the output. This one had already produced a wrong diagnosis that was written down and then had to
  be retracted.
- **The diagnostic was a participant in the failure.** The message was *"cited file X does not exist, or
  the abbreviated path matches more than one file"*, which conflates two causes needing opposite repairs
  — restore the file, versus lengthen the path. Reading 83 of those, twice, produced "83 files were
  deleted" both times. The two cases are now separate messages and an ambiguity **names the files it
  matched**, so a nested checkout diagnoses itself in the failure text.

The index now skips any directory that is the root of a *different* checkout. **The marker is `.git`,
not `settings.gradle.kts`**, and that is measured rather than assumed: `tools/code-use-semantic-sidecar`
is an in-repo Gradle build with its own `settings.gradle.kts` whose sources genuinely are part of this
checkout, and the guarded page `completion_aa_sidecar.md` cites two of them (`AaCompletion.kt`,
`CodeUseSemanticSidecar.kt`). Skipping on `settings.gradle.kts` repairs the 83 and breaks those 4 — it
trades one false red for a smaller one. `.git` separates the two cases exactly on the real inputs: a
nested clone or worktree always has one, an in-repo subproject never does.
`anInRepoGradleSubprojectIsNotMistakenForANestedCheckout` and
`theInputThatRulesOutTheGradleMarkerIsReallyInThisRepo` hold both halves down.

**`.git` is two shapes, and both have to be covered.** `git clone` leaves a `.git` *directory*;
`git worktree add` leaves a `.git` *file* holding a `gitdir:` pointer. Measured 2026-08-17: all three
checkouts under `.claude/worktrees/` are the **file** shape, because the agent harness makes worktrees
rather than clones. `File(dir, ".git").exists()` is true of both — but on the evidence actually available
the rule had only ever been exercised against one of them, so `bothShapesOfANestedCheckoutAreExcluded`
plants one of each and asserts the fixture really made one of each. Otherwise the fix works today and
breaks the first time somebody clones into the tree.

The residual cost is narrow and deliberate: a git **submodule** also carries a `.git` file, so an
*abbreviated* citation into `services/field-audio-ai` will not resolve. A full repo-relative path still
does, because `locate()` tries that before it consults the index at all. That trade is **pinned by
`noGuardedPageCitesIntoASubmoduleByAnAbbreviatedPath`**, not merely asserted about the present: it fails
and names the citation the moment somebody writes one, instead of surfacing later as a mysterious "does
not exist" on a file that plainly does. It also fails if the repo stops declaring any submodule at all,
which is the point at which this paragraph should be deleted rather than left to mislead.

**5. The gate that did not run.** The worst one, because it is self-concealing. Verified against
`build.gradle.kts` and against a real build log on 2026-08-12:

At the time of the 2026-08-12 failure, `build.gradle.kts` added six documentation gates to `check`
with `dependsOn` and **no ordering constraint whatsoever**. So when a test failed, Gradle stopped
scheduling and an arbitrary subset of those gates never ran. A seventh, `checkAgentDemos`, now guards
loadable demo metadata; the scheduling lesson is unchanged.

In the 2026-08-12 baseline, `:checkPythonTools`, `:checkAgentTranscriptIndex` and
`:checkAgentTranscripts` ran and passed; `:jvmTest` then failed; and `:checkKDocAttachment`,
`:checkCodebaseReference` and `:checkCompletionCorpusFixtures` **never ran at all**. The build
reported "2120 tests completed, 1 failed", which reads as one thing to fix, while three documentation
gates had silently not executed. Worse, `./gradlew check --dry-run` lists all six *after* `:allTests` —
a different order from the one the real build used — which proves the ordering is emergent rather
than specified, so *which* gates get skipped is not reproducible from the build file.

Compounding it: `checkCodebaseReference` is a `JavaExec`, not a test. It produces no test XML, so it
is invisible to every "N tests, 0 failures" summary anyone quotes.

The practical consequence, and it is the reason this section exists: **"the build was green except for
one test" is not evidence that the documentation gates passed.**

So there is now a task that makes the evidence unconditional:

```
./gradlew --no-daemon docsGates
```

It depends on all seven generated-artifact gates plus a focused `citationGates` test task — no
`:allTests` in the way. `citationGates` runs `CitationContractTest`, `SmallLanguageAnatomyTest`,
`CloseNodeCitationDriftTest`, and the build-wiring regression that pins this dependency. Its filter
fails when it matches no tests, and it runs after the seven non-test gates so a citation failure cannot
hide whether those gates ran. It does not alter what `check` does; `check --continue` remains the way
to run the complete test suite as well.

So one command now verifies a citation change at both layers:

```
./gradlew --no-daemon docsGates
```

Read the citation task's **XML** — `build/test-results/citationGates/TEST-*.xml`, its `tests=` and
`failures=` attributes — as well as the seven named gate results, not merely "BUILD SUCCESSFUL".

## What none of this checks

- **That the prose is true.** A guard proves the anchor exists. It cannot prove the sentence beside it
  describes what that code does. This is the failure mode this project keeps rediscovering — as one
  agent put it after a night of it, *"four for four, every bug was a false documentation sentence, and
  in three cases I copied it into my own prose before checking."* Falsify before you quote, including
  when the thing you are quoting is a guard's own KDoc.
  Since 2026-08-23 one *slice* of this is checked: a **number** in a sentence can be pinned to a
  population derived from the code — see *Pinning a number written in prose* above. Only the numerals
  that carry a marker, and the count of those that do not is printed on every run so that the size of
  the gap is never in doubt.
- **Anything in `public/docs/`.** Out of scope for all three guards.
- **`kdoc_worklist.md`**, 120 citations, which is *generated* — it belongs to
  `checkCodebaseReference`, not here, and hand-anchoring it would be overwritten on the next
  regeneration. (Listed twice here until 2026-08-23, in two wordings, which is its own small lesson
  about hand-kept lists.)
- **Moving `CloseNodeCitationDriftTest`'s 52-row table out of the test and into
  `fline_close_nodes.md`**, which would make it an ordinary guarded page and remove the last place
  where a citation table and the page it guards can disagree. Roughly an hour of careful
  restructuring; the obvious next step.
