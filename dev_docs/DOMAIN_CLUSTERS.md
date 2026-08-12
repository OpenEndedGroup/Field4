# The domain-cluster pattern

Written 2026-08-08, prompted by a question worth asking of any new subsystem: is `afield.rect` a one-off, or
a specific version of something Field already does repeatedly?

It is the latter. Field has built the same shape at least five times, nobody has named it, and the slots
are consistent enough that the next one should be able to work from a checklist. This note names the slots,
fills in the table, and says what is worth factoring and what is not.

## The shape

A **domain cluster** is a package that takes one subject matter seriously enough to give it its own model,
its own way of being written down, its own pictures and its own tests. The recurring slots:

| slot | what it is | why it recurs |
| --- | --- | --- |
| **model** | immutable types for the domain | everything else is a function of it |
| **notation** | text ↔ model, round-tripping | how a person and a language model both read a whole design cheaply |
| **figures** | model → `FLine`s | Field's native way of showing anything |
| **validation** | what is wrong, with stable codes | the thing an editing loop reads after every change |
| **solver / search** | find or repair a configuration | domains have constraints; someone will want them met |
| **lowering** | model → a foreign target | the cluster earns its keep by leaving Field |
| **editor extension** | Monaco support for the notation | a notation nobody can type is a notation nobody uses |
| **AI tools** | a typed tool surface over the model | the model becomes something to talk to |
| **examples / smokes** | worked programs that run in CI | a cluster is only as true as its last green run |

## Filled in

| slot | `petri` | `rectsl` | `morpho` | `nano` | `stdlib.polytope` | a private cluster |
| --- | --- | --- | --- | --- | --- | --- |
| model | `PetriNets` | `RectSL` | `MorphoSpec`, `MorphoCircuit` | `NanoVM` | `Polyhedron` | (out of tree) |
| notation | `PetriNotation` | the language itself | `MorphoGenome` | `NanoAssembler` / `NanoDisassembler` | Conway strings | (out of tree) |
| figures | `PetriVisual` | `RectSLFitFigure` | `MorphoFLines`, `MorphoSearchFLines`, `MorphoLayout3D` | `NanoVisualization` | mesh + FLine | (out of tree) |
| validation | `PetriAnalysis` | lexical diagnostics | — | — | `canonicalize` convergence | (out of tree) |
| solver | `PetriLayoutSolver` | `RectSLFit` | `MorphoSearch` | — | `CeresRealization` | (out of tree) |
| lowering | — | `RectSLGlsl` | `MorphoGraal` | `NanoComputeShader` | — | (out of tree) |
| editor ext. | `PetriEditorExtension` | `RectSLEditorExtension` | — | — | — | (out of tree) |
| AI tools | — | — | — | — | — | (out of tree) |
| examples | `PetriGallery` + workout | gallery + 6 transcripts | — | — | `polytope_examples.md` | 6 smokes + transcript + docs page |

Two things fall out of that table immediately.

**`afield.rect` is the first cluster with an AI tool surface.** Nothing else has one. That is the novel part
of this work, and the generalisable part of it has already been factored out: `CarriesToolImages` in
`ai/core/Tools.kt` lets *any* tool result carry pictures into the agent loop, and the tool-use logging in
`OpenAiIdeCodexBridge` is generic over tool name. A petri or a polytope tool set would need no new
infrastructure.

*Updated 2026-08-11:* the registration side is factored out too, and the cell is no longer empty in the
public tree. `AgentToolExtensions` is the seam — core no longer names any cluster — and
`afield.four.modules.GeometryAgentTools` is a second, public tool surface built on it, over the
geometry-from-disk loaders. Two tools, no change to `afield.ai.core`. Filling this slot for a cluster
that already has a model and figures is now a small job, and `dev_docs/ai/AGENT_TOOL_EXTENSIONS.md` is
the checklist for it — including the two things the seam does *not* do (nested request objects,
suspending handlers) and the one thing an example is most likely to leave out, which is that a tool
argument is a value a language model chose.

**The gap this survey turned up has since been filled.** `afield.rect` had no editor extension when the table
was first written, which two other clusters did. It now has one, built by copying `PetriEditorExtension`'s
shape — which is the best evidence that a table like this is worth keeping: the missing cell was the work item.

Two things `house.js` does that the earlier extensions do not, and that the next one should copy: completion
of the **ids declared in the region**, filtered by what the line can actually name (a `wall` sits on a storey,
an `open` sits on a host), and a smoke that fails the build when its copied word lists drift from the Kotlin
enums or when a decoration class has no CSS rule.

## What is worth factoring, and what is not

The temptation on seeing a table like that is to build a `DomainCluster` abstraction. That would be a
mistake, and it is worth saying why so the argument does not have to be had again.

**The slots share a shape, not an implementation.** `PetriNotation` parses a graph notation with places and
transitions; `HouseText` parses keyword-led lines of millimetres; `NanoAssembler` assembles instructions.
There is no common superclass in there that is not just "has a parse function". The same is true of the
figures: `PetriVisual` lays out a graph, `HouseFLines` projects plates. A shared base class would capture
the word and none of the work.

**What genuinely is shared has already been factored, and it is all in Field's core, not in a cluster
framework:** `FLine` and `outputTo` for pictures, `display` and the output areas for showing them, the
`FIELD_TEST` smoke runner for CI, the transcript machinery for worked examples, `ToolRegistry` and
`CarriesToolImages` for tool surfaces. Every cluster gets those by using Field rather than by inheriting.

**So the right artefact is this note.** Three concrete uses:

1. **A checklist for a new cluster.** Work the slots in order — model, notation, validation, figures,
   examples, then lowering, then tools. In `afield.rect` that order was also the dependency order, and each
   slot's tests caught bugs in the one below it.
2. **A gap finder for existing ones.** The table says `petri` has no lowering and `morpho` and `nano` have no
   notation-level validation. It also said `rect` had no editor extension, and that cell is now filled — which
   is the argument for the table: an empty cell turned straight into a work item.
3. **A place to put a convention once.** Two examples from this pass that any cluster could hit: a value
   type whose `equals` must fold negative zero before a text round-trip can hold (see `P2` in
   `RectPrimitives.kt`), and the rule that a figure's anchors scale with the drawing while its label sizes
   do not (`HouseLabel`).

## The one abstraction that might be worth it

Not a base class — a **shared "issue" vocabulary**. `HouseValidation`'s `HouseIssue(severity, code,
elementId, message)` with stable kebab-case codes turned out to be the single most useful thing in the
cluster, because it is what makes an editing loop closable: an agent reads codes, not prose. `PetriAnalysis`
returns its findings in its own shapes; the polytope realizer reports convergence its own way.

A `afield.Issue` with that four-field shape, plus `errors()` / `warnings()` / `report()`, would be perhaps
sixty lines and would let one UI, one tool-result field and one prompt convention serve every cluster. That
is a real candidate, and it should be extracted the *second* time a cluster wants it rather than in advance —
`afield.rect` is currently the only caller, and one caller is not a pattern.
