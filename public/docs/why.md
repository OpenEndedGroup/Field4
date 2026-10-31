# Why does this value resolve here?

When a box reads a value inherited from another box, where did that value come from? Ask
`why.speed` and Field will show you which box supplied `speed`, the path to it, and any other
boxes that had an answer. You can inspect the lookup without running the code that uses it.

This works for inherited properties, variables left by a parent's code, and context values such as
`Renderer`. Field looks for the nearest answer up the graph; if two are equally near, their order
in `parents` decides. For the full search, see [Property
inheritance](running_code.md#property-inheritance-how-children-see-a-parents-values).

## A tie and a shadowed ancestor

**Settings** declares `prop<Double>(speed)` and sets it to 0.25. **Fast**, under it, sets
`speed = 3.0`. **Slow** has no parent; it declares its own `prop<Double>(speed)`, sets 0.5, and also
leaves a plain variable, `val gear = 2`. **Mover** is connected under Fast first and Slow second.
**Gearbox** sits under Slow alone.

```diffstatic_runner {data-lang=kotlin screenshot="assets/why/graph.png" alt="the Field canvas with five boxes: Settings at the top left, Fast beneath it, Slow to the right, Mover below between them and Gearbox at the far right, with connection lines from Settings down to Fast, from Fast and Slow down to Mover, and from Slow down to Gearbox" capture="window" open-outputs="false" clip="20,20,1060,540" wait="1.0" timeout="180" embed=true hidden=true name="why-graph" macro-file="../../dev_docs/agents/transcripts/docs_capture/context_why_graph.yaml" capture-box="why_mover"}
// The canvas after the transcript has run.
```

Mover's code is one line:

```diffstatic_runner {data-lang=kotlin screenshot="assets/why/tie.png" alt="Mover's main output, a card headed why speed: supplier Fast (property) = 3.0; path Mover → Fast; competing Slow; shadowed Slow, Settings; then the one-sentence summary, which calls the answer ambiguous because Slow supplies a different value at the same distance and Fast is first in parents order, and names the preamble line that declared speed, from Settings" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="why-tie" macro-file="../../dev_docs/agents/transcripts/docs_capture/context_why_graph.yaml" capture-box="why_mover" w="760" h="200"}
display("main", why.speed.h)
```

Fast and Slow are both one edge from Mover and hold different values. Slow appears as
**competing**: the answer depends on the order in which the parents were connected. **Shadowed**
lists the other boxes with an answer, including Slow and the more distant Settings. The sentence
under the table also names the preamble line that declared `speed` and where it came from.

`why.speed` exists because `prop<Double>(speed)` writes three lines into the preamble, and children
inherit all three: `var Box.speed by Inheritance<Double>()`, `val Once.speed by Onceing<Double>()`, and
`val Why.speed by Whying()`. So every `prop` name is a member of `why` and completes there. `.h` is
the card above; `.h2` is the explorer, with the whole answer under `json`; `print(why.speed)` prints
the sentence.

The walk goes one generation at a time, the box itself, then all its parents, then all of theirs,
and stops at the first generation with an answer; that is why Settings, two edges up, never reaches
Mover. Within a generation the earlier connection wins. Disconnect Mover from Fast and connect it
again, and Fast is now the later connection: Slow answers from then on. A tie between different
values also writes a warning on the `context` log channel.

## Names no `prop` declared

A plain variable has no accessor in the preamble, so there is no `why.gear`. You can call
`ContextResolution.explain` directly, as `why.speed` does. Here's Gearbox's code:

```diffstatic_runner {data-lang=kotlin screenshot="assets/why/gear.png" alt="Gearbox's main output, a card headed why gear: supplier Slow (variable) = 2; path Gearbox → Slow; competing and shadowed both empty; and the sentence saying Gearbox reads gear from variable gear of Slow" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="why-gear" macro-file="../../dev_docs/agents/transcripts/docs_capture/context_why_graph.yaml" capture-box="why_gearbox" w="760" h="200"}
display("main", ContextResolution.explain(thisBox, "gear").h)
```

A value found by type, as `fromContext<Renderer>()` finds it, is asked the same way:
`ContextResolution.explain(thisBox, type = "Renderer").h`. The type is matched by the name of the
value's class, one of its superclasses or one of its interfaces, simple or fully qualified.

The answer is `ContextResolution.explain`'s JSON, which `.json` returns unchanged: `mechanism`,
`supplier`, `matchKind` (`PROPERTY`, `VARIABLE` or `SELF`), `matchedName`, `depth`, `path`, `value`,
`valueType`, `ambiguous`, `competing`, `shadowed` (the first 24, with `shadowedCount`), the
`declaration` and its `from:` tag, `stalePreambleLines`, `preambleConflicts`, and `summary`.

## Found by type or by name

`fromContext<T>()` finds the nearest value of a type, and `fromContextWithNameOrNull<T>("name")` the
nearest value with a name. Both see the plain top-level variables of the boxes above, so a parent
needs no `prop` at all. **Studio** has two variables:

```diffstatic {data-lang=kotlin}
val ink = java.awt.Color(200, 40, 40)
val title = "Spring"
```

**Sketch**, beneath it, asks for the first `Color`, for `title`, and for `subtitle`, which nothing
defines:

```diffstatic_runner {data-lang=kotlin screenshot="assets/why/context.png" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="why-fromcontext" macro-file="../../dev_docs/agents/transcripts/docs_capture/context_why_fromcontext.yaml" capture-box="bgc_sketch" w="620" h="80" alt="Sketch's main output, one line: ink = (200, 40, 40), title = Spring, subtitle = null"}
val ink = fromContext<java.awt.Color>()
val title = fromContextWithNameOrNull<String>("title")
val missing = fromContextWithNameOrNull<String>("subtitle")
markdown("ink = (${ink.red}, ${ink.green}, ${ink.blue}), title = $title, subtitle = $missing")
```

`fromContext<T>()` looks at the box itself, if it is a `T`; then the box's own properties; then each
generation of ancestors, nearest first, at each box's properties and then its top-level variables.
The asking box's own top-level variables are left out on purpose: Sketch's `val ink = fromContext<Color>()`
leaves a `Color` among Sketch's variables, and if Sketch searched those it would find its own previous
answer on every later run and never see Studio's again. Pass `includeLocalVariables = true` when a
box does want a value it made itself. `fromContextWithNameOrNull` never matches the box itself, and
returns `null` (or the `defaultValue` you pass) when nothing has the name.

## From outside the box

The same answer is available without writing any box code:

- the backdoor: `why <box> <name>` or `why <box> --type <class>`, with `--expect-supplier`,
  `--expect-competing`, `--expect-shadowed` and `--expect-not-found` for smoke tests
  (see "Why does this value resolve here?" in
  [FIELD_BACKDOOR.md](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/agents/FIELD_BACKDOOR.md));
- the MCP bridge: `field_why`;
- AI4 chat: the tool `WhyDoesThisResolve`, offered whenever a box is focused.
