# Why does this value resolve here?

A box reads a value it did not set: a `Renderer` through `fromContext()`, a setting through an
`Inheritance` property, a variable a parent's code left behind. The value comes from the nearest box
up the graph that has one, and when two boxes could answer, or an ancestor further up has a
different value, the one you get is decided by distance and by `parents` order. [Property
inheritance](running_code.md#property-inheritance-how-children-see-a-parents-values) describes the
walk. `ContextResolution.explain(box, name)` reports the walk's result for one box and one name:
which box supplied the value, by what path, and which other boxes also had an answer.

It runs nothing. It performs the same walk the box's own code would, and returns the answer as
JSON.

## A tie and a shadowed ancestor

Four boxes. **Settings** holds a property `speed = "0.25"`. Under it, **Fast** has run
`val speed = 3.0`. **Slow**, beside Fast with no parent of its own, has run `val speed = 0.5`.
**Mover** is connected under Fast first and Slow second, and asks where its `speed` comes from:

| Box | What it holds | Distance from Mover |
| --- | --- | --- |
| Fast | variable `speed = 3.0` | 1 (first parent) |
| Slow | variable `speed = 0.5` | 1 (second parent) |
| Settings | property `speed = "0.25"` | 2, through Fast |

```diffstatic_runner {data-lang=kotlin screenshot="assets/why/graph.png" alt="the Field canvas with four boxes: Settings at the top left, Fast beneath it, Slow to Fast's right, and Mover at the bottom, with connection lines from Settings down to Fast and from both Fast and Slow down to Mover" capture="window" open-outputs="false" clip="20,20,600,400" wait="1.0" timeout="180" embed=true hidden=true name="why-graph" macro-file="../../dev_docs/agents/transcripts/docs_capture/context_why_graph.yaml" capture-box="why_mover"}
// The canvas after the transcript below has run.
```

The setup is the transcript `dev_docs/agents/transcripts/docs_capture/context_why_graph.yaml`; this
is Mover's code:

```diffstatic_runner {data-lang=kotlin screenshot="assets/why/tie.png" alt="Mover's main output: supplier Fast, VARIABLE speed = 3.0; path Mover → Fast; competing [Slow]; shadowed [Slow, Settings]; the walk fromContextWithNameOrNull<Double>(speed) = 3.0; then the one-sentence summary, which calls the answer ambiguous because Slow supplies a different value at the same distance and Fast is first in parents order, and says it shadows Slow and Settings" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="why-tie" macro-file="../../dev_docs/agents/transcripts/docs_capture/context_why_graph.yaml" capture-box="why_mover" w="760" h="200"}
val why = ContextWhy.plain(ContextResolution.explain(thisBox, "speed")) as Map<*, *>
fun named(key: String) = (why[key] as List<*>).map { c ->
    val m = c as Map<*, *>
    ((m["supplier"] ?: m) as Map<*, *>)["name"]
}
val supplier = why["supplier"] as Map<*, *>

display("main", "<pre style='white-space:pre-wrap;max-width:720px'>" +
    "supplier   ${supplier["name"]}, ${why["matchKind"]} ${why["matchedName"]} = ${why["value"]}\n" +
    "path       ${named("path").joinToString(" → ")}\n" +
    "competing  ${named("competing")}\n" +
    "shadowed   ${named("shadowed")}\n" +
    "the walk   fromContextWithNameOrNull&lt;Double&gt;(\"speed\") = ${fromContextWithNameOrNull<Double>("speed")}\n\n" +
    "${why["summary"]}</pre>")
```

The answer names Fast, a `VARIABLE` match one edge up. Slow is listed as **competing**: it is as
near as Fast and holds a different value, so the result depends on the order the two parents were
connected in, and the summary says the result is ambiguous. **Shadowed** lists every other box that also has an
answer: Slow again, and Settings, which is farther away. The last line before the summary is the real lookup,
`fromContextWithNameOrNull<Double>("speed")`, and it agrees.

The fields, in full: `mechanism`, `supplier`, `matchKind` (`PROPERTY`, `VARIABLE` or `SELF`),
`matchedName`, `depth`, `path`, `value`, `valueType`, `ambiguous`, `competing`, `shadowed` (the
first 24, with `shadowedCount`), `declaration`, `stalePreambleLines`, `preambleConflicts`, and
`summary`.

## Names, declarations and types

What is walked depends on how the box would read the name:

- a name declared in the box's gathered preamble, such as
  `val Box._speed by InheritanceFromContext<Double>() /**/`, is resolved by that declaration's own
  delegate, the walk the editor's hover uses, and the answer adds the preamble line, its `from:` tag,
  and the box that tag names, so an accessor inherited from a box that is no longer an ancestor shows
  up;
- a name with no declaration is resolved as `fromContextWithNameOrNull(name)` would resolve it,
  which is the case above;
- `explain(box, type = "afield.four.Renderer")` resolves as `fromContext<Renderer>()` would, matching
  a value whose class, superclass or interface has that name.

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
