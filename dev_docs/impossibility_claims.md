# Impossibility claims must carry their evidence

*2026-08-25.* A number in `dev_docs` already has to carry its derivation — that is what
`DerivedCountGuard` and `ProseNumeralContractTest` are for. A sentence of the form
`X is impossible because Y` did not have to carry anything at all, and it turns out to be the more
expensive of the two.

This page is the convention, the guard that enforces it, and the argument for both. The argument comes
first, because it is the only part that will convince anyone.

## The six weeks nobody could detect

`DOCS_RUNNER_GPU_CAPTURES.md` carried a section headed **"Known limitation: compute + draw in ONE
box"**. It was written the way this repo writes its best work: a reproduction with the exact exception
text, a causal chain naming three source files, a ruled-out alternative, and a named fix. A capability
table above it had a `❌` pointing at it. Anyone reading it would have concluded, correctly on the
evidence presented, that the feature was out of reach and that the fix was known.

It was false on the day it was written — 2026-07-11, commit `36e61332d` — in **three** of the facts the
chain rested on.

| What the chain said | What was true, and since when |
|---|---|
| *"the **async engine** used by `run`/`execute-all` (`box.ki`, `Boxes.kt` `makeKI`)"* | the `execute-all` handler builds the **synchronous** engine, `executing.kt:65#makeKIHere(box, reason = "execute-all handler reset")` — since `8b5ea3a356a`, **2026-05-26** |
| *"`executeAll` does **not** reset the `_preamble` cache, whereas `launchAll` does"* | the same handler clears it on the next line, `executing.kt:66#box.properties["_preamble"] = ""` — since `52a1d563e`, **2025-11-03**, the first commit of this build layout |
| *"`makeKI` … simply **never executes the box preamble at startup**"* | `makeKI` clears the cache itself, `Boxes.kt#box.properties.remove("_preamble")` — since `0c66dcdb7`, **2026-06-18** |

So the named fix — *"make `executeAll` reset the `_preamble` cache like `launchAll`"* — was a
description of code that had already shipped on three paths. And the real cause was probably sitting in
the paragraph that dismissed it — the one spelled `a red herring we ruled out … tested and disproven`.
What had been tested was a neighbouring `var Structure.<name>` declaration, not the generated structure
class beside it, under a different preamble key.

**Nothing in this repo could tell.** `CitationGuard` would have been perfectly content, because every
file the chain named really existed. That is the crux: a citation proves a line is *there*, and this
entire chain was an assertion that something was **absent**.

It is not a one-off. The same disease is on the record at least three more times:

- a ledger row calling a `suspend` type *"unsatisfiable by a dynamic proxy"* stood for eleven days; it
  was a `Function2` taking a `Continuation`, and closing it closed two neighbouring rows;
- a *"language-design question"* row that was a to-do, read as a finding — twice;
- a claim that Plotly could not serve a headless docs capture, which five already-shipped pages
  disproved. Retracted in `chart_primitive.md`, which is worth reading as the shape of an honest
  correction.

## The convention

An impossibility claim carries, **inside its own claim unit**, exactly one of four markers.

| Marker | Written as | Means |
|---|---|---|
| reproducer | `` (reproducer: `SomeTest.kt#fun theRefusalIsObserved`) `` | a test or runnable example that *demonstrates* the refusal |
| citation | `` `SomeFile.kt:120#theSymbol` `` | the code that makes it so — an ordinary citation |
| conjecture | `(conjecture, 2026-08-25)` | reasoned, not measured, and honest about it |
| waiver | `(not a limitation: <why>)` | the detector misfired; say why here, not in the guard |

Three of the four are syntax the repo already used. That is deliberate: the reproducer's payload *is* a
`CitationGuard` symbol citation, so it inherits path resolution, symbol resolution, ambiguity reporting
and the nested-checkout rule for free (`citation_contracts.md`). And `(conjecture, YYYY-MM-DD)` is the
spelling `DATAFRAME_PLUGIN.md` had already invented — its only two uses in the corpus, checked by
nothing until now.

### The one asymmetry: a citation can never substantiate an absence

A **ruled-out** claim — the family spelled `we ruled that out`, `a red herring`, `tested and disproven`,
`verified not sufficient` — is a measurement, and what it measures is a negative. Pointing at code cannot
establish it. Pointing at code is exactly what the six-week page did, and the code it pointed at said
the opposite of what the prose claimed.

So a ruled-out claim takes a **reproducer**, or it admits it is a **conjecture**. A plain citation is
refused, by name, in the guard's failure text. That single rule is the part of this that would have
caught the original.

### What a "claim unit" is

The paragraph, bullet or table row the claim sits in — not the section. A ten-bullet list of
limitations needs ten markers, and that is the point: the six-week section had *one* "what we verified"
paragraph standing behind four independent assertions, three of which were wrong.

### What counts as a reproducer

Not "the path exists". A reproducer is a promise that a future reader can re-run the refusal, so the
guard also checks it is something runnable:

- a `.kt` under `src/jvmTest/` whose anchor names a function with `@Test` ahead of its `fun` on the
  same line (`@Test fun x()`) or in the annotation prelude **directly** above it — not within some
  line window, because a helper sitting a few lines below a real test would inherit that test's
  annotation and be accepted;
- a `.kt` under `src/jvmMain/` with a `fun main(`;
- a `.py` under `tools/` that defines something;
- a transcript under `dev_docs/agents/transcripts/`.

Anything else is refused with the reason. A data class in the middle of a graphics kernel is a
plausible-looking string, not evidence.

## How to add a claim

Write the sentence. Then ask which of these you actually have:

1. **I can make it fail on demand.** Write the test, then
   `` (reproducer: `MyTest.kt#fun theThingIsRefused`) ``. Best outcome; the guard will check the test
   exists, that the anchor is really there, and that something runs it.
2. **The code says so, in a line I can point at.** Cite it: `` `MyFile.kt:412#theSymbol` ``. Good for
   *positive* facts — an `@` annotation that only source can carry, a two-phase pipeline, an ABI list.
   Not good enough for *"X does not happen"*: see the asymmetry above.
3. **I reasoned it out and did not measure it.** `(conjecture, 2026-08-25)`. This is a perfectly
   respectable answer and is what most of a reconnaissance page should say. `NVFLOW_BINDINGS.md` is the
   model: the whole page is explicitly reconnaissance, nothing in `src/` refers to Flow, and the claim
   is marked as read-off-someone-else's-headers.
4. **It is not really an impossibility claim.** `(not a limitation: <why>)`, with the reason in the
   doc where the next reader is looking. The count of these is ceilinged, so it cannot become universal
   suppression.

If the guard fires on a new page, the failure text lists all four forms and the reason it fired. Acting
on the message alone should be enough.

## The guard, and what it is honestly worth

`ImpossibilityClaimGuard` + `ImpossibilityClaimContractTest`, run by `citationGates` and therefore by
`docsGates` and `check`. It is in `citationGates` for one decisive reason: that is the only task that
declares `dev_docs/**.md` as an input, so it is the only one that cannot go `UP-TO-DATE` after a
Markdown-only edit. A guard that can pass stale is worse than no guard, because it reads as evidence —
measured on 2026-08-19, when a deliberately planted bad fence went green until `--rerun-tasks`.

### The general form does not work, and the measurements say so

This is worth stating plainly rather than shipping a broad guard that has to be suppressed everywhere.

- The obvious regex — `cannot|is impossible|no way to|refuses to|will never` — matches **53 lines in 30
  of the 114 pages**. Hand-classified, **roughly 23 of the 53 are not claims at all**. `refuses to` is
  almost always *designed* behaviour ("the model smoke **refuses** to run unless…"); `cannot` turns up
  in relative clauses ("a class file that cannot be read"), in test guarantees ("so an op cannot be
  added without a check"), in git facts ("a commit message, which cannot be amended"), and inside
  quoted error text.
- Loosening `cannot be <verb>` to a bare `cannot <verb>` takes it to **208 lines in 54 pages**. That is
  the collapse: at that width the guard is a tax on writing English.
- And the obvious regex **misses the three lines that mattered most** — every ruled-out sentence on the
  six-week page, because `ruled out` is not an impossibility idiom. The most expensive claim in the
  corpus was invisible to the search designed to find it.

So the detector is narrower and follows how this repo actually writes a finding:

1. **A strong idiom, anywhere in prose.** `is impossible`, `there is no way to`, `nothing can be`,
   `no supported way`, plus every ruled-out idiom. Phrases with no innocent reading.
2. **A weak idiom inside emphasis** — a heading, a `**bold**` span, or a table row bearing `❌`.
   `cannot` in running prose is noise; a bolded lead-in is the author telling you a verdict is being
   delivered.

Two idioms were tried and dropped for **measured** imprecision, recorded so they are not
re-litigated: `never` in emphasis (23 hits, overwhelmingly *prescriptions* — "**never fake a
capture**") and `refuses` in emphasis (6 hits, all designed refusals presented as features).

### What is excluded, and why each exclusion exists

Fenced code, inline code spans, HTML tags, blockquotes, HTML comments — and:

- **Historical before-pictures.** `was no way`, `had no way`, `no longer`, `*Was:`. A page's own
  account of the problem it solved is not a claim that the problem is still there.
- **Limitations-section signposts.** `### What it cannot do` announces a section; the claims are the
  bullets underneath, and those are detected individually. Six of these exist. Narrow on purpose:
  `## Redirects cannot walk the proxy off the docs site` does **not** match, and should not.

### The residual holes, named

- **A claim written inside a blockquote is unreachable by this guard.** Blockquotes are skipped so
  that quoted compiler output — *"Such a configuration is not supported."* in `kotlin_to_js_spike.md` —
  is not read as an authorial verdict. The cost is that `> ` is a way to hide a claim on purpose. The
  exclusion is pinned by fixture rather than assumed, and this bullet is its own worked example: it is
  detected, and it carries a marker
  (reproducer: `ImpossibilityClaimContractTest.kt#fun theDetectorRecognisesEveryClaimShapeAndNoneOfTheMeasuredNoise`).
- **De-emphasising a claim removes it from the corpus.** Un-bolding `**cannot**` makes it stop being
  detected. The per-page floors are what make that a visible edit rather than a silent one, but they
  are floors on *counts*, not on identities — a page can swap one claim for another at the same count.
- **Coverage is `dev_docs` only.** See below.
- **A citation is only as good as the anchor.** `CitationGuard` proves the anchor is at the cited site;
  neither guard can prove the prose around it is a fair reading of that site. The six-week chain would
  have satisfied a naive citation check. It is the *ruled-out asymmetry*, not the citation, that closes
  that door.

### `public/docs` was deliberately left out, and here is the risk that buys

The published tree has **20 claim-shaped lines in 17 of its 348 pages** by the same detector, and the
divergence hazard is live rather than theoretical: `public/docs/fieldml_host_bridge.md` carries
the two bolded verdicts `A suspend member cannot be called` and `internal Kotlin members are
unreachable` — twins of two `dev_docs/FIELDML_HOST_BRIDGE.md` claims annotated today. **The dev_docs side now cites its
evidence and the published side still does not.** A reader gets the published one.

It is out of scope tonight for a reason worth stating rather than hiding: the failure being fixed is
*diagnosis rot* — a developer-facing finding used to decide what to build next. Published prose is
teaching, its "cannot"s are mostly about the reader's inputs rather than Field's limits, and a marker
like `` (reproducer: `X#y`) `` in the middle of a tutorial sentence is a cost paid by every reader.
Extending it properly needs a marker form that does not render — an HTML comment — plus an
`impossibility twin` ledger that pairs a `dev_docs` claim with its published restatement and fails when
only one of them is annotated. That is the obvious next piece of work, and it is written down here
rather than left as an unrecorded gap.

### The numbers, and what each one pins

Both are printed by `theClaimPopulationIsFlooredInBothDirections`, and they are different numbers:

- **scanned** — every `*.md` under `dev_docs`, floored at 100 pages so "zero unmarked" cannot be
  produced by scanning nothing;
- **pinned** — the detected claims, floored corpus-wide and per page, of which a floored subset carries
  a reproducer or a citation rather than a conjecture marker, and of which **zero** may be unmarked.

Reporting only the first is how a branch once recommended a merge on "scan 18 → 216 pages" while the
coverage it actually pinned had gone 9 → 12. Both were true; only one mattered.

## Interaction with other branches

This guard is not path-disjoint, in the same way and for the same reason `CitationGuard` is not
(`citation_contracts.md`): **a new `dev_docs` page carrying an unmarked impossibility sentence will fail
this gate.** That is correct behaviour, not a collision — the failure text says exactly what to write —
but it means a docs-heavy branch should expect it. `citationGates` declares `dev_docs/**.md` as an
input, so the gate really does re-run for a Markdown-only change.
