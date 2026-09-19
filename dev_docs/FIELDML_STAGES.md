# `fieldml_stages.md` — design notes

The reader-facing page is `public/docs/fieldml_stages.md`. This is the part someone who is about to
change it needs: why it is built the way it is, what was measured to justify building it at all, and
the traps that are easy to walk back into.

## Why the page exists

FieldML was already better documented than the brief that commissioned this page believed. Measured
at `4546d72b5`:

| Population | Count |
| --- | --- |
| `src/jvmMain/kotlin/afield/ml` | 19 files, 8884 lines |
| `src/jvmTest/kotlin/afield/ml/tests` | 15 files, 6409 lines |
| test-to-implementation line ratio | 0.72 |
| published FieldML pages before this one | 4 (`fieldml_cheatsheet.md` 262 lines, `fieldml_examples.md` 453, `fieldml_host_bridge.md` 372, `reference/languages.md`) |
| `afield.ml` files with a generated signature table | 19 of 19 |
| `afield.ml` files with a KDoc file overview | 3 of 19 |
| hits for `Lowerer\|CoreExpr\|Inferencer` in `public/docs/**.md` outside `reference/` | **1** |

That last row is the whole justification. Four pages, a complete signature table, a cheatsheet and a
tour — and exactly one sentence anywhere that named an intermediate representation
(`fieldml_host_bridge.md:363`, pointing at `Inferencer.refineHostIntrinsic` as a place to look). The
language was documented; **the middle of the compiler was not**. So the brief's stated reasons for
this page were wrong on every count and its deliverable was right anyway, which is worth recording
because the two are easy to conflate.

Two claims in circulation that this pass falsified, so they stop being re-derived:

- *"`afield.ml` test coverage is zero; the directory has no files."* False. The glob that produced it
  looked at `src/jvmTest/kotlin/afield/ml/` and the files are one level deeper, in `…/ml/tests/`.
  **A zero deserves the same suspicion as an absence** — this one, and the sibling error in the same
  brief, were both a glob that did not recurse.
- *"A `suspend` function type erases to a `Function` taking a `Continuation`, which a dynamic proxy
  cannot satisfy."* False as a *cause*, and it was row D's mechanism cell in
  `FIELDML_HOST_BRIDGE.md` until 2026-09-18. See that file for the correction. A row whose *cause*
  is false is worse than one whose *status* is stale, because a false cause propagates into briefs
  and gets re-asserted by people who never touch the code.

## Method: the page is a derivation, not a capture

Every listing on the page is machine output, and none of it is pasted-and-trusted.
`FieldMLStagePageTest` reads the published page, extracts the program from the page's own first
`data-lang=fieldml` fence, recomputes all six stage listings from that program text, and asserts
equality against the fences the page publishes.

The distinction that matters: a capture is output pasted onto a page *once*. It is honest the day it
lands and decays silently the first time the renderer it came from changes, and nothing on the page
knows. This page is instead a capture **CI re-derives on every run**. A digit cannot go stale without
the test going red, and it cannot be repaired by editing the page to match a guess, because the
comparison runs the compiler.

Consequences worth knowing before editing:

- **The renderers are part of the page, not test scaffolding.** They are the only thing that decides
  what the listings look like, and the page's prose describes their output. They live in the test
  file beside the assertions because a change to either has to be a change to both.
- **The renderers are deliberately strict.** Each of the three tree renderers handles only the node
  kinds the published program uses and calls `error()` on anything else, rather than falling back to
  a class name. A permissive renderer would keep passing if the program were edited to use a
  construct the page does not explain, and would quietly print a less informative tree.
- **The program lives on the page, not in the test.** Duplicating it would make it impossible to tell
  which copy the listings came from.

## Traps

**Fences are marked with a comment, not located by heading.** `<!-- stage:tokens -->` and friends.
Locating a fence by the heading above it would break the moment a section were reworded — and
renaming a section was a change no guard in the tree caught until `check_doc_site_links.py` gained its `#fragment` check on 2026-09-18, which is also why it breaks
every `](#anchor)` into it. A marker is invisible to a reader and stable against every edit that is
not about this test.

**Trailing whitespace would break the comparison.** `renderTokens` pads the kind column, so the `EOF`
line — whose text is empty — would end in spaces. A published fence with trailing whitespace is one
an editor or a formatter silently repairs, and the comparison is exact, so each line is `trimEnd`ed
at the source rather than trusted to survive the round trip.

**One `TypePrinter` per listing, never two.** A printer names unification variables `'a`, `'b`, … in
order of first appearance and keeps those names for its own lifetime. Two printers each start again
at `'a`, so a tree rendered by two would disagree with itself about which variable is which. The
typed-tree and core renderers thread one printer down the whole walk for exactly this reason.

**Exactly one `data-lang=fieldml` fence on the page, on purpose.** The page is in
`FieldMLSmokeExamplesTest`'s page list at both of its 2 sites, so every FieldML fence is compiled
*and evaluated*. Fragments that are not standalone programs — `5 |> twice bump` on its own, whose
`twice` is unbound, or the `bump "five"` type error — are therefore written as inline code spans in
prose, not as fences. A fence would be a guard failure, and a `data-lang=text` fence holding FieldML
source would be a quiet lie about what was checked.

**No bare ```kotlin fences**, or `citationGates` fails at pre-commit. The tree's fence vocabulary is
`diffstatic` (1064), `diffstatic_runner` (639), `diff_runner` (4), `diff` (3); `data-lang=` carries
the language.

**A new `public/docs/**.md` selects the codebase-reference check at pre-commit.** Run
`./gradlew generateCodebaseReference` and commit what it writes. Expect drift even when the symbol
count does not move, because "Used in" is name-matched: this page's landing added itself as a
cross-reference on `Core.kt`, `Expr.kt`, `FieldMLCompiler.kt` and `TypedAst.kt` at a total of 14173
symbols, unchanged. `dev_docs/kdoc_worklist.md` is a *capped* priority list, so the same landing
pulled `Core.kt`'s undocumented `C*` classes in and displaced an equal number of other entries
(52 lines each way). Never hand-edit either.

## What the page found worth saying

Three readouts justified prose that reasoning alone would not have produced, and they are the reason
showing real output beats describing it:

- `TExprLet twice f x : Int`. A `let` node's type is its **body's** type, so the outermost `let` in a
  file of top-level bindings carries the whole program's type, not the bound function's.
- `twice` has two types in one tree: `('a -> 'a) -> 'a -> 'a` at its definition and
  `(Int -> Int) -> Int -> Int` at its use site. Let-polymorphism, visible in one listing.
- `CLet twice (recursive)`. The flag survives lowering and is set for the `let f x = …` function
  form, which is what lets a FieldML function call itself.

And one structural claim a walkthrough can settle that prose cannot: `5 |> twice bump` and
`twice bump 5` parse to the *same* surface tree. `|>` gets no node of its own. The page publishes one
rendering for two source texts, which is only honest because
`pipeAndJuxtapositionShareOneSurfaceTree` renders both and asserts they are equal.

## Not attempted

The page stops at the value. Extending the walkthrough onto the mesh-writing surface — mechanism E
is closed, and a FieldML box has built a `Module` and a `Mesh` on a real device with no `hostAs` — was
scoped out rather than attempted, because it needs a device this JVM does not have and a rendered
stage nobody has driven is worse than an absent one. **Unverified, not impossible** (2026-09-18):
the shape type-checks here and only the device is missing, which is exactly what
(reproducer: `FieldMLSmokeExamplesTest.kt#fun theAfieldFourDemoTypeChecksEvenThoughItCannotRunHere`)
observes, and `public/docs/assets/fieldml_afield_four_demo/triangle.png` is the capture from the
session where it did run. So this is a scoping decision, not a bound — do not let it harden into one.
