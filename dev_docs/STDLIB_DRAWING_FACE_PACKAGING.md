# Should the `*_field.kt` drawing faces move out of `afield.stdlib`?

**Status:** report only. Nothing has been moved. Recommendation at the end needs a decision before any
file is touched.

**Context.** A box can carry a real `import` line — `KotlinInterface2.kt` classifies
`s.trimStart().startsWith("import ")` as `"imports-or-preamble"`, and `preambleAfterImports` gives it
an insert point. So "it has to live in `afield.stdlib` or a box cannot reach it" is no longer a
reason to put anything in `afield.stdlib`. That was the mistaken reason `afield.morpho` was briefly
moved into `afield.stdlib`, and it has been moved back. This report asks which of the six
`*_field.kt` files were placed in `afield.stdlib` for that same mistaken reason.

## The convention, as the files themselves describe it

The six files are a deliberate **dependency-inversion seam**, not demo scaffolding. Each one exists so
that an algorithm family's own files stay free of any dependency on Field's visualizer. `wfc.kt`'s
header states the intent directly — the solver "knows about integer tile IDs, `BufferedImage` and
`FloatArray`, and nothing else" — and `alife_field.kt`, `dynamic_graph_field.kt` and `spatial_field.kt`
each explicitly cite the others as the precedent they are copying. `alife_field.kt` even propagates a
shared *range policy* ("every scale is named in the caption", "domains are fixed by meaning, not by the
frame", "one pixel per cell, never resampled"), with the note that per-frame auto-ranging once made a
saturated Lenia field render as solid black and "looked exactly like death".

So the convention is deliberate and worth preserving. The question is only which package it belongs in.

## Measured

`*_field.kt` symbols that are declared in exactly one stdlib file (so the count is not confused by
overloads of generic names like `toFLines`), counted against the 71 pages in `public/docs`:

| file | lines | docs pages using it | called back into by its own family? |
| --- | --- | --- | --- |
| `imaging_field.kt` | 679 | **17** | — |
| `mesh_field.kt` | 624 | **12** | `tubeMesh` ← `mesh_generate.kt` |
| `spatial_field.kt` | 499 | **5** | `showPlane` ← `autodiff_geometry.kt` |
| `wfc_field.kt` | 930 | **1** (`wfc_examples.md`) | `wfcSolverHtml`, `wfcRulesHtml` ← `wfc.kt` |
| `dynamic_graph_field.kt` | 414 | **1** (`dynamic_graph_systems_examples.md`) | **none** |
| `alife_field.kt` | 299 | **0** | `alifeGridHtml` ← `alife.kt`; `alifeGrayScottHtml`, `alifeLeniaRunHtml` ← `alife_continuous.kt` |

Two clusters fall out cleanly, and the page count is what separates them.

## Genuinely general-purpose — leave in `afield.stdlib`

**`imaging_field.kt` (17 pages), `mesh_field.kt` (12 pages), `spatial_field.kt` (5 pages).**

These are not the drawing face of one family. They are Field's generic presentation vocabulary for
three of its core currency types — `Img`, `TriMesh`, `FLine` — and the pages using them span imaging,
alife, functions, statistics, rectsl, spectral, graph connectivity, interaction nets, dough/DSP and
the interactive guides. `showImg` / `showImgGrid` / `montage` / `colormap` / `hstack` / `vstack` /
`overlay`, `showMesh` / `showMeshGrid` / `renderPng`, and `showPlane` / `showPlaneGrid` /
`planeFigureHtml` are the answer to "how do I look at this" for any box, whatever family produced the
value. `spatial_field.kt` has the fewest pages of the three but belongs with them on type, not count:
it is the plane twin of `mesh_field.kt` (its own header says so), and `showPlane`/`showPlaneGrid` are
the 2D counterpart of `showMesh`/`showMeshGrid`. Splitting the pair would be worse than leaving both.

Unqualified availability is the right default for these. Moving them would add an import line to
boxes on 17, 12 and 5 pages respectively to buy nothing.

## Demo-shaped — reasonable to move, in this order

**`dynamic_graph_field.kt` (414 lines) — clearest candidate.** One page. Every uniquely-declared
symbol is literally prefixed `dynamicGraph*` (`dynamicGraphFLines`, `dynamicGraphPanel`,
`dynamicGraphWindow`, `dynamicGraphSummary`, …), which is a package name spelled out as a prefix. And
it is the one file in the set with **no back-reference**: nothing in `dynamic_graph_systems.kt` calls
into it, so it is a pure outward bridge and moving it costs one import line on one docs page.

**`wfc_field.kt` (930 lines) — largest, and one page.** Same shape, with one caveat below: `wfc.kt`
implements `ToHTML` by delegating to `wfcSolverHtml` / `wfcRulesHtml`.

**`alife_field.kt` (299 lines) — one page's worth of value, zero symbols named in docs.** Its
symbols never appear in any fence because they are pure `ToHTML` plumbing, reached implicitly by
evaluating an `IntGrid` or a `GrayScottState`. Same `ToHTML` caveat, ×3.

Together these are 1,643 of the 3,445 lines.

## The caveat that decides how much this is worth

For `alife_field.kt` and `wfc_field.kt` the dependency runs **backward**. The host type implements
`ToHTML` in the *algorithm* file by calling into the *drawing* file:

- `alife.kt:229#override fun toHTML(): String = alifeGridHtml` — `override fun toHTML(): String = alifeGridHtml(this)`
- `alife_continuous.kt:114#override fun toHTML(): String = alifeGrayScottHtml` — `override fun toHTML(): String = alifeGrayScottHtml(this)`
- `wfc.kt:393`, `wfc.kt:868` — `WfcSolver` / rules types implement `afield.ToHTML` the same way

And it runs backward in the other direction too, so the pair is genuinely mutually dependent:
`alife_field.kt:125#fun alifeGridHtml(grid: IntGrid)` is `fun alifeGridHtml(grid: IntGrid)`, and `IntGrid` is declared at
`alife.kt:79#class IntGrid(`. Likewise every `wfc_field.kt` extension hangs off `PngTileSet` / `WfcSolver` /
`FloatTileSet`, all declared in `wfc.kt`.

**Why the back-call is structural rather than sloppiness.** `ToHTML` is declared in `package afield`
(`ValueVisualizer.kt:54#interface ToHTML`) and dispatched at `ValueVisualizer.kt:225#is ToHTML -> rewriteBoxReferencesInHtml` by `is ToHTML ->
rewriteBoxReferencesInHtml(value.toHTML())` — a runtime type test on the value. The *type* must
implement the interface, so the type must reach its own renderer. There is no registry for attaching
a renderer to a type from outside.

**What moving the file does *not* cost.** It does not force an import into the solver. `alife.kt` has
**no** `import afield.*` lines at all and already writes `) : afield.ToHTML {` fully qualified
(`alife.kt:84`); fully-qualifying the one outside reference is the file's existing house style. After
a move it could just as well write `afield.draw.alifeGridHtml(this)` — no import, header unchanged,
and "a reader of `alife.kt` sees integer lattices and nothing about HTML" still holds. The dependency
already exists and these headers say so ("names exactly one symbol from here"); a move makes it
*visible*, it does not create it.

**What it does cost: a package cycle.** `afield.draw` would depend on `afield.stdlib` (for `IntGrid`,
`WfcSolver`, `PngTileSet`) while `afield.stdlib` depends on `afield.draw` (for `alifeGridHtml`,
`wfcSolverHtml`). Kotlin permits that — packages are not compilation units and this is one source
set — so it compiles and runs. But it makes the seam **decorative**: the two packages could never be
built, extracted or reasoned about as layers, which is most of the reason to separate them at all.

`dynamic_graph_field.kt` has no such problem, which is why it is first — verified, not assumed:
`dynamic_graph_systems.kt` implements no `ToHTML` and calls nothing in `dynamic_graph_field.kt`, and
every function there takes family types and returns `FLine` / `Img` / `String` / `Rect`. Moving it
yields a clean **acyclic** edge `afield.draw → afield.stdlib`.

The display machinery itself is not at risk in any of these moves: `ToHTML` dispatch is virtual on
the class, so where the *helper* lives does not affect whether evaluating a value shows a picture.

**The escape hatch, if the other two are ever wanted.** Change the display end, not the file
location: have `ValueVisualizer` consult a renderer registry keyed by type instead of requiring the
type to implement `ToHTML`. The drawing package would then register renderers for the family's types
and the family would never name the drawing package at all — the cycle disappears for all six files
at once. That is a change to the value-display path, not a file move, and should be costed as such.

## Recommendation

1. **Leave `imaging_field.kt`, `mesh_field.kt`, `spatial_field.kt` in `afield.stdlib`.** They are
   general-purpose by use, not demo-shaped. 1,802 lines stay.
2. **Move `dynamic_graph_field.kt` first, on its own**, to a package such as `afield.draw` (or
   `afield.dynamicgraph`). No back-reference, one page to update, so it is a clean test of whether a
   box importing a drawing package is actually pleasant to read.
3. **Then decide `wfc_field.kt` and `alife_field.kt` deliberately**, on the package-cycle question
   above — not on import hygiene, which is a non-issue for them. Either accept the cycle and treat
   the split as a readability device only, or leave them where they are and accept `afield.stdlib`
   as the home for `ToHTML` plumbing specifically. That second line is defensible and is a different
   line from the one for callable helpers: a helper you *call* wants to be importable, whereas
   `ToHTML` plumbing is reached implicitly by evaluating a value, so nothing is gained by making it
   nameable from a box. Note that none of these six symbols is ever named in a fence.
4. **Only if the cycle is the blocker and the move is still wanted**, do the registry change to
   `ValueVisualizer` first; then 3 becomes a plain file move with no cycle.

Do not batch 2 and 3. The point of 2 is to find out what the ergonomics actually feel like before
committing 1,229 more lines to the same shape.
