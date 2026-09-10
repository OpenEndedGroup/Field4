# The KDoc → reference contract

Everything Field says about its own API in the editor and on the docs site comes from one place: the
`/** … */` blocks in `src/`. There is no second source, no hand-maintained API page, and no Dokka.
That makes the pipeline cheap and honest — and it means a KDoc that fails to *attach* to the right
declaration is published as another symbol's description, silently.

This note is the contract: what the extractor can and cannot see, what the guard enforces, and how
to write a block that will actually land where you meant.

## The pipeline

```
src/**/*.kt
  └─ KDocExtractor.extractKDocs            (afield/ai/core/completion/KDocExtractor.kt)
       └─ KotlinSourceIndex.parseTopLevelDefinitions   — kdoc joined to declaration BY LINE NUMBER
            ├─ the completion documentation panel + semantic index (in-app)
            └─ StdlibReferenceGenerator    → public/docs/reference/*.md   (committed, drift-guarded)
                                           → dev_docs/kdoc_worklist.md
```

Two Gradle tasks guard it, both wired into `check`, neither needing a Field server, a GPU or a
network:

| task | fails when |
| --- | --- |
| `checkCodebaseReference` | `public/docs/reference/` is not byte-for-byte what the current KDoc produces. Fix with `./gradlew generateCodebaseReference`. |
| `checkKDocAttachment` | a KDoc block in `src/` does not attach to the declaration it sits above. Fix by moving the block (or teaching the parser). |

## How attachment actually works

`extractKDocs` is a **line scanner**, not a parser. For each block it walks *forward* from the
closing marker and asks `parseDeclaration(line)` about each line in turn:

- blank lines are skipped;
- a line beginning with `@` is skipped **whole** (this is how annotations are tolerated);
- a line it cannot read is **skipped, not stopped at**;
- the first line that parses wins, and the block is attached to it;
- a second `/**` ends the walk, and the block is dropped.

The third rule is the dangerous one. A KDoc above something the parser cannot see does not merely
disappear — it **slides down onto whatever parses next**, and that symbol is then published with a
description of something else. That shipped on 2026-07-26: a `seg(...)` row on the graphics reference
page captioned with `FLine.hoverColor`'s description, because the delegated property between them
parsed as nothing.

### What `parseDeclaration` can see

After stripping modifiers (`public`, `private`, `internal`, `protected`, `open`, `final`, `abstract`,
`sealed`, `data`, `enum`, `annotation`, `value`, `inline`, `suspend`, `tailrec`, `operator`, `infix`,
`external`, `override`, `const`, `lateinit`, `companion`, `inner`, `actual`, `expect`) it recognises:

`class` · `interface` · `object` · `fun` (including `fun interface`) · `val` · `var` · `typealias` ·
`constructor`

…with a receiver and generics allowed, backticked names unwrapped, and a delegated property's `by`
clause cut before the type annotation (`var FLine.color by Delegation<Vector4f> { … }` parses).

### What it cannot see, and what happens instead

| Written above… | Result |
| --- | --- |
| a property accessor — `get() = …` / `set(v) { … }` | slides onto the next declaration. Put the block above the property. |
| an `init { }` block | slides. `init` documents nothing; use a line comment, or document the function it calls. |
| an annotation *and* a declaration on the same line — `@SerialName("x") val y = 1` | the whole line is skipped, so it slides. Break the annotation onto its own line. |
| a statement inside a function body | slides onto the next declaration. Use `//`. |
| a line inside a block comment that happens to look like a declaration | attaches to the comment. (`playlist_example.kt` had a whole example's `val box1 = …` captured this way.) |
| another `/** … */` block | this block is dropped entirely. See "stacked blocks" below. |
| nothing (end of file) | dropped. |

### What the guard is *not* — its one structural blind spot

`checkKDocAttachment` proves that a block **attaches to some declaration**. It cannot prove the block
describes *that* declaration, and there is a recurring shape where those two come apart: a `//`-style
section heading gets written as `/** … */`, and a later commit inserts a declaration underneath it. The
heading is now legally attached, so the guard is silent, and the block confidently documents something
several declarations away.

Two live examples were found in `shaders.kt` on 2026-07-28, both dating to 2025-11-06:

- `private data class CompileResult(…)` — an inert two-field result holder — carried
  *"Unified compile/introspect helper that: runs the GLSL/spirv compilation for a stage; reports errors
  via box.error…"*, which describes `compileStage`.
- `private val preamblePrefixRegex = Regex(…)` — a one-line marker regex — carried
  *"Applies an Insert's preamble(s) into the target box."*

Both passed the guard. Neither published anything, because both are `private` and the reference only
walks public symbols — which is also why nobody noticed for eight months.

No automated check is proposed for this: any heuristic strong enough to catch it (does the prose's verb
match the declaration's kind?) fires on far too many legitimately-worded blocks to be worth 6768 false
positives. **The mitigation is a review habit:** when a block reads like a section heading, make it a
`//` comment so a future insertion cannot adopt it.

## Stacked blocks

Two KDoc blocks with no declaration between them means the first documents nothing. The usual causes
are a stale duplicate, a lone `@param` block that someone appended above rather than inside, and a
block that was left behind when its function moved. The guard reports all of them.

The one benign case is a **file overview**, and it has a precise shape:

```kotlin
@file:afield.CompletionCategory("Math")

package afield.stdlib

/**
 * What this file is, in one paragraph, then as much prose as it deserves.
 */
                        // ← this blank line is load-bearing
/**
 * The first declaration's own KDoc.
 */
fun interface RbfKernel { … }
```

`StdlibReferenceGenerator.fileOverview` finds the first block itself — first KDoc in the file, ending
before the first declaration, **separated from what follows by at least one blank or `//` line** —
and prints it as the section prose on the category page and as the "What it is" cell on the reference
index. `extractKDocs` drops it, which costs nothing because a file is not a symbol.

Remove that blank line and the meaning changes: the generator treats the block as the *following
declaration's* summary. If that declaration is `internal`, or a kind the reference does not emit, the
prose is published nowhere and the file's index row goes blank. `imaging_simd.kt` shipped in exactly
that state — a good essay in the source, an empty cell on the page — until a blank line was added on
2026-07-28.

Trailing block tags (`@see`, `@param`, …) are stripped from a file overview, because unlike a symbol
summary it is printed whole rather than first-paragraph-only.

## Enum constants

A KDoc on an enum constant is *unused* rather than misplaced: `parseDeclaration` does not recognise
`HANN,`, and the reference reads an enum's constants out of the class body itself
(`StdlibReferenceGenerator.enumConstantsAt`). Write them if they help a reader of the source; they
will not reach the page. The guard treats them as benign, annotated or not.

## Writing a block that lands

1. Put it **immediately above the declaration**, with nothing between except whole-line annotations.
2. First sentence first: only the first paragraph becomes the summary on the reference page and the
   completion card. Write it as a claim about behaviour, not a restatement of the name.
3. Never write `/**` or `*/` inside a KDoc. Kotlin block comments **nest**, so an inner `/**` needs a
   matching close, and an inner `*/` ends the block early — a `"**/*.wav"` glob quoted in a comment
   broke a build on 2026-07-27. Say "the opening marker" instead.
4. If you touch a summary in `afield.stdlib` or `afield.visualization`, run
   `./gradlew generateCodebaseReference` and commit the regenerated pages. **And note that those two
   packages are not the only input** — see below.

### The reference also depends on every public docs page

This is the part that surprises people, and it has already caused one confident misattribution. The
generated pages carry `Used in:` back-links, and those are *derived from the docs pages themselves*,
not from KDoc. `StdlibReferenceGenerator.buildEvidenceIndex` (`StdlibReferenceGenerator.kt:1559#private fun buildEvidenceIndex`)
lists **every** `public/docs/*.md` except the nine in `EVIDENCE_PAGE_BLOCKLIST`
(`StdlibReferenceGenerator.kt:625#EVIDENCE_PAGE_BLOCKLIST`), reduces each to its *code* text only — fenced blocks plus
inline code spans, with bare filename spans dropped (`codeTextOf`, `:1091`) — and links a page to
a source file when at least `MIN_EVIDENCE_HITS` = 2 (`:94`) tokens that are **uniquely owned** by
that one file (ownership built at `:1038-1040`, applied at `:1049`) match in it.

Three consequences, in increasing order of nastiness:

- **Editing a public page's code spans can change `public/docs/reference/`.** A page that starts
  naming an API starts being linked from it. That is by design.
- **The drift is not always additive.** Only `MAX_EVIDENCE_LINKS` = 3 pages are kept per source file
  (`:97`, applied by the `.take(...)` at `:915`), ranked by hit count then filename. So a new page
  with a higher count can **displace** an existing link, and `checkCodebaseReference` then reports a
  removed line on a page nobody touched.
- **Uniqueness is corpus-global.** Adding a public declaration whose *name* an already-documented
  file also defines makes that token ambiguous and it stops counting for **both**
  (`tokenOwners[it]?.size == 1`, `:906`), so links can vanish from a file you did not edit.

Practically: if you are editing a public docs page and want to leave the reference alone, keep the
edit **prose-only** — correct numbers, alt text and sentences, but do not add or remove backticks or
fenced blocks. If you do change code spans, run `./gradlew generateCodebaseReference` and commit the
regenerated pages in the same commit, exactly as for a KDoc change.

**Adding a whole new page always drifts it**, so there is no prose-only escape there — a page that
names stdlib symbols in code spans is exactly what `buildEvidenceIndex` is looking for. And the
"same commit" rule inverts when **several branches are in flight at once**: whichever branch
regenerates is stale the moment a sibling's page lands, because the ranked `Used in:` links are
computed over the whole corpus. In that situation the right move is to commit the page *without*
regenerating, say so in the commit message (`git commit --no-verify`, since the pre-commit hook runs
`checkCodebaseReference`), and regenerate **once** after the merges. A red
`checkCodebaseReference` on a docs-only branch is then expected, not a failure to investigate.
5. Check your claims against the implementation. The reference is only as trustworthy as its worst
   row, and `afield.stdlib.DocumentedBehaviourTest` exists because several of those rows were wrong
   in ways only reading the code could reveal.

## A KDoc `[Symbol]` reference, and what a *qualified* one means

Only **top-level** types get an anchor on these pages, so a nested type has none. That leaves
`[Outer.Nested]` with nothing to resolve to, and until 2026-09 `linkify` fell back to the last
segment alone — which is a false link waiting for a name collision anywhere in the corpus.

Adding the simulation roots supplied one, and it is a good illustration of how far this drift
reaches. `afield.stdlib/tuio.kt` documents `[TuioReceiver.Frame]`; `TuioReceiver.Frame` is a nested
`data class` with no anchor; `afield.four.physics/CSVReader.kt` declares a top-level `Frame` for
motion-capture samples. The moment the physics root was published, a sentence on `data.md` about a
TUIO cursor frame acquired a link into the physics page — on a page nobody had edited, from a change
in a different package, and it would have shipped had the diff not been read.

`TypeAnchors.resolveReference` is the narrowest rule that closes it: **when a reference is qualified
and this reference knows the qualifier, the match has to live in the same source file the qualifier
does**, because a nested type cannot live anywhere else. An unknown qualifier — a package name, a
JDK type — still falls back to the last segment, so `[java.nio.ByteBuffer]` behaves exactly as
before. Measured over the whole corpus at the time it landed: it changed nothing except that one
line, and `data.md` went back to byte-identical with base.

## Which page a file lands on, and the one label that is not an annotation

A generated page is a **completion category**, and a file's category is normally the
`@file:CompletionCategory("…")` it carries. `StdlibReferenceGenerator.fileCategory` reads exactly
that annotation and nothing else; a file under a source root with no annotation falls through to
`inferredCategory`, and a file that reaches `Uncategorised` is a **build failure** rather than a page
(`StdlibReferenceGenerator.kt:439#UNCATEGORISED` says why: the drift guard certifies faithfulness, not
correctness, so once an `uncategorised.md` is committed every guard goes green while the site ships a
page named after the fact that nobody categorised it).

That leaves a question with a sharp answer. Field's ten small-language packages carry **no**
`@file:CompletionCategory` at all, and the whole point of publishing them was that the reference did
not know they existed. The obvious move — annotate them — is the one move that is not available.

### Why annotating is not a documentation-only change

`@file:CompletionCategory` is not inert. Three facts compose:

| fact | site |
| --- | --- |
| one spelling, `Internal`, makes completion **drop the candidate from the popup** | `src/jvmMain/kotlin/afield/KotlinCompletionSupport.kt:403#fun isHiddenCompletionCategory` |
| `KotlinSourceIndex` files **every** declaration in an annotated file under that file's label, with no visibility filter | `src/jvmMain/kotlin/afield/ai/core/completion/KotlinSourceIndex.kt:766#private fun fileCompletionCategory` |
| the symbol lookup ends in a **name-only** first-wins | `src/jvmMain/kotlin/afield/ai/core/completion/KotlinSourceIndex.kt:302#fun getCompletionCategoryForSymbol` |

So a `@file:` line added to publish a page can change — or delete — rows in a working programmer's
autocomplete, and can do it for a declaration in a different package that merely shares a name. The
measured cost of the near-miss is recorded on `EXCLUDE_PREFIXES`
(`src/jvmMain/kotlin/afield/docs/StdlibReferenceGenerator.kt:232#Excluding by path costs nothing`):
eleven candidate files, 574 declaration names, 235 of them also declared elsewhere *and* present in
real box code.

The `Simulation` page was measured the same way in 2026-09, and the numbers are worth keeping
because they say something the synth ones did not. Population: the **18** of that page's 26 files
that carry no annotation today, which is exactly what an "annotate instead" route would have had to
annotate. Counted with the real parser (`KotlinSourceIndex.scanDefinitions`, no visibility filter,
simple names) against the whole index corpus of 45618 declarations / 26468 distinct simple names:

| | count | denominator |
| --- | --- | --- |
| declarations in the 18 files | 916 | — |
| distinct names they would newly file under one label | **670** | 916 declarations |
| …also declared elsewhere in the tree | **190** | 670 names |
| …*and* appearing in real box code | **157** | 190 names |

The box-code population is 247 sources — 95 transcript yaml, 36 corpus preambles, 116 published
pages reduced to their code text. On the headline ratio this is *milder* than the synth case
(157/670 = 23% against 235/574 = 41%), and on composition it is worse: the 157 include `Box`,
`lines`, `add`, `get`, `size`, `copy`, `close`, `start`, `state`, `step`, `time`, `update`, `x`, `y`
and `z`. `Box` is the type every box has in scope and `lines` is how anything gets drawn.

One difference from the synth case is worth stating precisely rather than dramatised. `Internal`
*hides*; `Simulation` does not, so the counterfactual harm here is 157 names at risk of having a
name-only first-wins lookup answer "Simulation" for them in the popup's category header, not 235
names at risk of vanishing. Different severity, same mechanism, same conclusion — and the realised
cost of doing it by path instead is zero lines changed under any of the three roots, which `git
diff` can be asked rather than believed.

### The mechanism actually used

`CATEGORY_BY_PATH` (`src/jvmMain/kotlin/afield/docs/StdlibReferenceGenerator.kt:366#private val CATEGORY_BY_PATH`)
maps a repo-relative path prefix to a page label, first match wins, and is read by the generator and
by nothing else. It is not new machinery: the `afield/visualization/` entry in it *is* the hardcoded
`if` it generalises, which has assigned a category by path since the reference shipped.

Two properties make it safe, and both are checked rather than argued:

- **A file's own annotation always wins.** The code is literally
  `fileCategory(text) ?: inferredCategory(rel)`, so the path map only ever fills in for a file that
  has no label. Nothing is ever moved out of the category its own source chose.
- **`Languages` never enters the completion vocabulary.**
  `src/jvmTest/kotlin/afield/docs/LanguagesCategoryTest.kt` rebuilds the vocabulary the popup can
  see — every `CompletionCategory("…")` literal in `src/`, plus the fixed set inside
  `KotlinCompletionSupport` — and fails if `Languages` appears in it, if any file under a
  path-labelled prefix grows an annotation, if the label ever becomes a hidden category, or if a
  path-labelled category has no page blurb. It also checks that every committed reference page is
  listed in `public/zensical.yml`, because a page that is generated, committed and drift-guarded is
  still not *published* until something links to it.

**The plural in `Languages` is load-bearing.** `normalizedCompletionCategory` maps the singular
`"language"` onto `"Kotlin"`, so a rename to the tidier-looking `Language` would file the page under
an existing, unrelated category the moment anything normalised it. The test pins both directions.

### The same mechanism, used for a label that is not reference-only

`Simulation` (added 2026-09) is the second thing `CATEGORY_BY_PATH` files, and it is a weaker case
than `Languages` in one way that matters. `Languages` is a label that exists nowhere but that map.
`Simulation` is a real completion category **eight files already carry** — six in `afield.kratos`,
two in `afield.tetgen` — so the two prefixes are not inventing a label, they are extending one the
source chose to the eighteen files beside it that never got annotated.

That makes two of `LanguagesCategoryTest`'s assertions inapplicable rather than violated, and the
test says so itself: the "never enters the completion vocabulary" check `continue`s for anything
that is not `Languages`, and the "no file under a path prefix carries its own annotation" check runs
only over the prefixes filed under `Languages`. Both are right to. A file's own annotation always
wins, so a `Simulation`-annotated file under a `Simulation` prefix is not a conflict — it is the map
agreeing with the source. The three checks that *do* apply to it all pass: the label is not hidden
from completion, its prefixes are real directories inside `SOURCE_ROOTS`, and it has a page blurb.

`afield.tetgen` deliberately has **no** prefix even though it is a source root, because every file
it has annotates itself. A prefix there would label nothing today, and if a fourth file lands
unannotated the `Uncategorised` build failure telling its author to add the annotation its siblings
carry is correct advice in that package. In `afield.kratos` it would not be — two of its published
files have never had one.

### Which packages are on the Languages page, and which are not

Membership is derived, not curated: a package is there because it is on one of the two language
integration routes that `dev_docs/small_language_anatomy.md` documents — it calls
`registerMainLanguageRuntime` (Route B, the language a *box* is written in) or it registers a
browser-side editor extension with a Kotlin compiler behind it (Route A). That gives `clojure`,
`datalog`, `forth`, `ml`, `morpho`, `nano`, `python`, `rectsl`, `remotejs` and `stacksl`.

Four packages that pass that test are deliberately **not** there, and one fails it:

| package | why not |
| --- | --- |
| `petri` | labels its own files `Visualization`/`Audio`. Re-labelling would *move* symbols between completion categories, which is the change this whole design exists to avoid. |
| `solidsl` | labels its own files `Graphics`, and the `Graphics` blurb already claims "the small shading languages' host side". |
| `brief` | labels its own files `Solvers`. The closest call on the list. |
| `macros` | labels `RewriteDsl.kt` `Workspace`, and the `Function` blurb already covers user-authored rewrite patterns. |
| `graphquery` | fails the route test: a query notation over the reflective object graph, with no `BoxExecution` and no editor extension. |

The rule underneath all five rows is the same one: **a file's own label wins**, so a package that has
already chosen a category has already answered this question. Adding one to the Languages page means
editing its annotations, which means changing completion, which means it is not a documentation task.

### Choosing a page is not the same as appearing on one — 26 files, measured

The four rows above used to end "so each is already published under a category its own source chose",
and that sentence was **false**, in a way that hid a real gap for as long as it stood. A file's label
decides *which* page it belongs on. `SOURCE_ROOTS` decides *whether the file is read at all*. A file
can have answered the first question and still be invisible, and 26 of them are.

Measured 2026-09-10 over all 18 committed pages read whole, with the exact section heading the
generator emits (`## <basename> { #`) as the predicate:

| package | files | label they chose |
| --- | --- | --- |
| `afield.solidsl` | 9 | `Graphics` |
| `afield.petri` | 6 | `Visualization` (5), `Audio` (1) |
| `afield.onnx` | 4 | `Machine learning` |
| `afield.brief` | 2 | `Solvers` |
| `afield.manifold` | 2 | `Graphics` |
| `afield.graphics` | 2 | `Graphics` |
| `afield.macros` | 1 | `Workspace` |

Denominator: 248 files in `src/jvmMain` carry a file-level `@file:CompletionCategory`; 219 sit inside
a source root and are published; 28 do not, of which 2 say `Internal` and are correctly absent
(`ManifoldNative.kt` and `StdlibReferenceGenerator.kt` itself), leaving these 26.

Two of them are worth naming. `Solid.kt` and `Section.kt` say `Graphics`, and `Solid` is the type
`manifold.md`, `tetgen.md`, `mesh_examples.md` and `solid_notation.md` are all written about. And the
`Graphics` blurb's claim to cover "the small shading languages' host side" is, today, a promise the
page does not keep: every `solidsl` file that phrase refers to is one of the 26.

Nothing here is fixed by the change that measured it, deliberately — six more roots is a different
diff, and the `Used in:` links are corpus-global, so it wants a night of its own. But the false
sentence is gone, and the number has a denominator now.

### Adding a language package to the reference

1. Add the root to `SOURCE_ROOTS`. `SourcePageGenerator` reads the same list, so its "read the
   source" pages follow automatically — that coupling exists because the reference emits those links
   from each file's own partition and would otherwise link to pages nobody rendered.
2. Add a `CATEGORY_BY_PATH` prefix for it *only if* its files carry no annotation of their own. If
   they do, they are already on a page; leave them there.
3. Keep harnesses out via `EXCLUDE_PREFIXES` — a FIELD_TEST runner or a docs-figure generator, and
   nothing more. The bar is deliberately low otherwise: on a page about languages a lexer, a parser
   and a bytecode VM *are* the subject.
4. `./gradlew generateCodebaseReference`, then read the diff on the pages you did not expect to
   change. Widening the corpus changes cross-file `[Symbol]` resolution and the `Used in:` evidence
   links, both of which are corpus-global.
5. Add the page to `public/zensical.yml`, or `LanguagesCategoryTest` will tell you that you didn't.

## If the guard fires and the parser is the problem

Sometimes the right fix is to teach `KDocExtractor.parseDeclaration` the form it cannot read — that
is how `fun interface`, delegated properties, `typealias`, `actual`/`expect` and `constructor` all
got in, each of them after a real block had slid somewhere wrong. Two rules if you do:

- `parseDeclaration` is the *definition* of attachment, so the guard calls it directly. Keep them
  agreeing; do not special-case a form in one and not the other.
- Extraction feeds the committed reference, so a parser change usually moves summaries onto symbols
  that had none. Run `./gradlew generateCodebaseReference` and read the diff — it is the evidence
  that the change did what you thought.
