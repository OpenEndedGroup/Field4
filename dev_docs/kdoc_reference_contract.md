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
   `./gradlew generateCodebaseReference` and commit the regenerated pages.
5. Check your claims against the implementation. The reference is only as trustworthy as its worst
   row, and `afield.stdlib.DocumentedBehaviourTest` exists because several of those rows were wrong
   in ways only reading the code could reveal.

## If the guard fires and the parser is the problem

Sometimes the right fix is to teach `KDocExtractor.parseDeclaration` the form it cannot read — that
is how `fun interface`, delegated properties, `typealias`, `actual`/`expect` and `constructor` all
got in, each of them after a real block had slid somewhere wrong. Two rules if you do:

- `parseDeclaration` is the *definition* of attachment, so the guard calls it directly. Keep them
  agreeing; do not special-case a form in one and not the other.
- Extraction feeds the committed reference, so a parser change usually moves summaries onto symbols
  that had none. Run `./gradlew generateCodebaseReference` and read the diff — it is the evidence
  that the change did what you thought.
