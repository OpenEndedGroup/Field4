# Why does this value resolve here?

A box reads values it did not set: a setting through a `prop`, a variable a parent's code left
behind, a `Renderer` through `fromContext()`. The value comes from the nearest box up the graph that
has one, and when two boxes are equally near, `parents` order decides. [Property
inheritance](running_code.md#property-inheritance-how-children-see-a-parents-values) describes that
walk. `why.speed` asks the box to report it for `speed`: which box supplied the value, by what path,
and which other boxes also had an answer. Nothing runs; it is the walk the box's own read performs.

## A tie and a shadowed ancestor

**Settings** declares `prop<Double>(speed)` and sets it to 0.25. **Fast**, under it, sets
`speed = 3.0`. **Slow** has no parent; it declares its own `prop<Double>(speed)`, sets 0.5, and also
leaves a plain variable, `val gear = 2`. **Mover** is connected under Fast first and Slow second.
**Gearbox** sits under Slow alone. The setup is the transcript
`dev_docs/agents/transcripts/docs_capture/context_why_graph.yaml`.

```diffstatic_runner {data-lang=kotlin screenshot="assets/why/graph.png" alt="the Field canvas with five boxes: Settings at the top left, Fast beneath it, Slow to the right, Mover below between them and Gearbox at the far right, with connection lines from Settings down to Fast, from Fast and Slow down to Mover, and from Slow down to Gearbox" capture="window" open-outputs="false" clip="20,20,1060,540" wait="1.0" timeout="180" embed=true hidden=true name="why-graph" macro-file="../../dev_docs/agents/transcripts/docs_capture/context_why_graph.yaml" capture-box="why_mover"}
// The canvas after the transcript has run.
```

Mover's code is one line:

```diffstatic_runner {data-lang=kotlin screenshot="assets/why/tie.png" alt="Mover's main output, a card headed why speed: supplier Fast (property) = 3.0; path Mover → Fast; competing Slow; shadowed Slow, Settings; then the one-sentence summary, which calls the answer ambiguous because Slow supplies a different value at the same distance and Fast is first in parents order, and names the preamble line that declared speed, from Settings" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="why-tie" macro-file="../../dev_docs/agents/transcripts/docs_capture/context_why_graph.yaml" capture-box="why_mover" w="760" h="200"}
display("main", why.speed.h)
```

Fast and Slow are both one edge from Mover and hold different values, so Slow is listed as
**competing**, and the answer depends on the order the two parents were connected in. **Shadowed**
lists every other box that also has an answer: Slow again, and Settings, which is farther away. The
sentence under the table says the same, and names the preamble line that declared `speed` and the
box it came from.

`why.speed` exists because `prop<Double>(speed)` writes three lines into the preamble, and children
inherit all three: `var Box.speed by Inheritance<Double>()`, `val Once.speed by Onceing<Double>()`, and
`val Why.speed by Whying()`. So every `prop` name is a member of `why` and completes there. `.h` is
the card above; `.h2` is the explorer, with the whole answer under `json`; `print(why.speed)` prints
the sentence.

## Names no `prop` declared

A plain variable has no accessor in the preamble, so there is no `why.gear`. Ask
`ContextResolution.explain` directly, which is what `why.speed` calls. Gearbox's code:

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

## From outside the box

The same answer is available without writing any box code:

- the backdoor: `why <box> <name>` or `why <box> --type <class>`, with `--expect-supplier`,
  `--expect-competing`, `--expect-shadowed` and `--expect-not-found` for smoke tests
  (`dev_docs/agents/FIELD_BACKDOOR.md`, "Why does this value resolve here?");
- the MCP bridge: `field_why`;
- AI4 chat: the tool `WhyDoesThisResolve`, offered whenever a box is focused.

The smoke `context.why.tie-and-shadow` (`src/jvmMain/kotlin/afield/examples/ContextWhySmoke.kt`)
builds a graph like the one above through the backdoor and asserts the supplier, the competitor and
both shadowed candidates.
