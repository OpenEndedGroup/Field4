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
around it and no file-level review that predicts it.

## The three guards

| guard | source | guards | how it resolves | slack |
|---|---|---|---|---|
| `CitationContractTest` | `src/jvmTest/kotlin/afield/docs/CitationContractTest.kt` | corpus-wide, opt-in pages | symbol search | n/a — line advisory |
| `SmallLanguageAnatomyTest` | `src/jvmTest/kotlin/afield/docs/SmallLanguageAnatomyTest.kt` | `small_language_anatomy.md` | symbol search | n/a — line advisory |
| `CloseNodeCitationDriftTest` | `src/jvmTest/kotlin/afield/graphics/CloseNodeCitationDriftTest.kt` | `fline_close_nodes.md` | **line window** | **±1** |

Read the third row carefully; it is the one people get wrong. `CloseNodeCitationDriftTest` has
**not** been migrated, and its window is **±1**, not ±2 — `subList((line - 2), (line + 1))` is
0-based, so it spans the cited line and one line either side. It also differs structurally: its table
of 52 citations lives **in the test**, not in the doc, so the test and the page it guards can drift
apart from each other. Its second test, `theDevDocOnlyCitesSitesThisTestChecks`, exists to stop that —
it requires every `Foo.kt:NNN` in the page to appear in the test's table. Note the regex is
`([A-Za-z_0-9]+\.kt):(\d+)` — **`.kt` only**, so the page's one `.js`/`.html` citation is invisible
to it, and a cited *range* is checked at its first line only. Three citations point into
`src/personal`, which is gitignored and simply absent; they are listed in `outsideThisRepo` as an
explicit admission that CI cannot check them.

## How resolution works now

Since 2026-08-12 the two `afield.docs` guards do not count lines. Every citation carries an **anchor**
— a substring that must still be present — and `CitationGuard` **searches the whole cited file for
it**. Where the anchor turns out to be is the answer.

The line number stays, and stays useful, but it is **advisory**: it is what the reader is shown, and
it disambiguates an anchor that occurs more than once. A stale advisory number does not fail the
build.

That last sentence is the entire design, and it is worth defending, because the instinct is to
enforce it. If a wrong line number could fail the build, then a sibling inserting twenty lines would
turn the guard red — which is the hazard, exactly as before, wearing a different hat. The trade
accepted here is: **the anchor is guaranteed by CI; the number is a convenience refreshed on demand.**

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
3. **Abbreviate the path if you like.** `` `FloatEarcut.kt:705#…` `` resolves by unique path-suffix
   match, so prose does not have to carry forty-character paths. An ambiguous abbreviation is a
   failure, not a guess.
4. **Add the page to `GUARDED_PAGES`** in `CitationContractTest`, with a floor for its citation count.
5. **Run it**: `./gradlew --no-daemon jvmTest --tests '*CitationContract*'`.

Anchors may contain spaces, quotes and parentheses. In a *table cell* a `|` must be written `\|`; the
guard unescapes it, so FieldML's `\|>` can be cited.

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

**4. The gate that did not run.** The worst one, because it is self-concealing. Verified against
`build.gradle.kts` and against a real build log on 2026-08-12:

`build.gradle.kts:1355-1362` adds six documentation gates to `check` with `dependsOn` and **no
ordering constraint whatsoever** — the only `shouldRunAfter` in the file, at line 1119, is unrelated.
So when a test fails, Gradle stops scheduling and an arbitrary subset of those gates never runs.

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
one test" is not evidence that the documentation gates passed.** To actually check them, run
`./gradlew --no-daemon check --continue`, or invoke the gates directly.

## What none of this checks

- **That the prose is true.** A guard proves the anchor exists. It cannot prove the sentence beside it
  describes what that code does. This is the failure mode this project keeps rediscovering — as one
  agent put it after a night of it, *"four for four, every bug was a false documentation sentence, and
  in three cases I copied it into my own prose before checking."* Falsify before you quote, including
  when the thing you are quoting is a guard's own KDoc.
- **Anything in `public/docs/`.** Out of scope for all three guards.
- **`kdoc_worklist.md`**, which carries 120 citations and is generated — it belongs to
  `checkCodebaseReference`, not here.
- **The other fifteen `dev_docs` pages carrying unanchored citations.** Bringing one under guard is
  now a small, mechanical job; step 4 above is the whole of it. The best next candidate is
  `fline_close_nodes.md`, whose guard is the last one still counting lines and whose citation table
  still lives in the test rather than the page.
