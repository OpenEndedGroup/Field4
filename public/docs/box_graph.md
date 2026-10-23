# The box graph

Boxes in a document are connected into a graph: each line on the canvas runs from a **parent** down
to a **child**. The lines decide what a box can read. A child can use a value that one of its
parents, or a parent's parent, has set, without being told where it lives. This page shows how that
lookup works, what wins when several boxes have an answer, and what happens when none does.

To draw a connection, select the parent, shift-select the child and press `g`; `u` with the same two
boxes selected removes it ([Connecting boxes](tour_connecting_boxes.md) shows this on the canvas).
A box can have any number of parents and children, and the connections are saved with the document.

## A child reads what its parent publishes

`prop` declares a value that boxes beneath can read. **Garden** declares `petals`, an `Int`, and
sets it:

```diffstatic {data-lang=kotlin}
prop<Int>(petals) = 5
```

**Flower** is connected beneath Garden and uses `petals` as if it had declared it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_graph/connect.png" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="box-graph-connect" macro-file="../../dev_docs/agents/transcripts/docs_capture/box_graph_connect.yaml" capture-box="bg_flower" w="560" h="80" alt="Flower's main output, one line: Flower sees petals = 5"}
markdown("Flower sees petals = $petals")
```

Two things have happened. When Flower ran, Field gathered the declaration of `petals` from Garden's
code into Flower's, which is why the name compiles at all: a box that is not beneath Garden gets
`Unresolved reference 'petals'`. And when Flower read `petals`, it looked at itself first, found
nothing, and moved up the connection to Garden, which had a value because it had run.

The value stays on Garden. Flower reads it each time its code reads `petals`, so after Garden runs
again with a new number, Flower sees the new number the next time it runs. To rerun Flower
automatically when Garden changes, declare the value with `propDown`
([Properties that re-run boxes](reactive_properties.md)).

## Nearer wins

Assigning to an inherited name writes it on the box doing the assigning. It does not change the
parent's value; it hides it from that box and from everything beneath it.

Below, Garden still sets `petals = 5`. Its children **Left** (`petals = 7`) and **Right**
(`petals = 9`) set their own. **Sibling** is a third child of Garden that only reads, and **Bloom**
has two parents, Right and Left:

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_graph/diamond.png" capture="window" open-outputs="false" clip="20,20,840,420" wait="1.0" timeout="180" embed=true hidden=true name="box-graph-diamond-canvas" macro-file="../../dev_docs/agents/transcripts/docs_capture/box_graph_diamond.yaml" capture-box="bgd_bloom" alt="The Field canvas with five boxes: Garden at the top; Left, Right and Sibling in a row beneath it, each joined to Garden by a curved line (the one to Sibling arcs over Right); Bloom at the bottom, joined to both Left and Right. Sibling's output reads Sibling sees petals = 5 and Bloom's reads Bloom sees petals = 9"}
// The canvas after the transcript has run.
```

Sibling still sees Garden's value; Left and Right writing theirs did not touch it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_graph/sibling.png" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="box-graph-sibling" macro-file="../../dev_docs/agents/transcripts/docs_capture/box_graph_diamond.yaml" capture-box="bgd_sibling" w="560" h="80" alt="Sibling's main output, one line: Sibling sees petals = 5"}
markdown("Sibling sees petals = $petals")
```

## Two parents

A read searches up the graph one generation at a time: the box itself, then all of its parents,
then all of their parents, and so on, stopping at the first box with a value. Garden is two steps
from Bloom and Left and Right are one, so Garden's 5 never reaches Bloom. Left and Right are equally
near, and between equally near boxes the one connected first wins. Bloom was connected beneath Right
first:

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_graph/bloom.png" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="box-graph-bloom" macro-file="../../dev_docs/agents/transcripts/docs_capture/box_graph_diamond.yaml" capture-box="bgd_bloom" w="560" h="80" alt="Bloom's main output, one line: Bloom sees petals = 9"}
markdown("Bloom sees petals = $petals")
```

Disconnect Bloom from Right and it sees Left's 7; connect it again and Right is now the later
connection, so Bloom keeps seeing 7. When two equally near boxes hold different values Field writes
a warning on the `context` log channel, since the answer depends on connection order. `why.petals`
reports which box answered and which others could have: see [Why does this value resolve
here?](why.md).

## A value for the whole document: `rootProp`

`rootProp` is `prop` for the whole document. The value is stored on the document's root box, and
every box in the document can read it, connected or not:

```diffstatic {data-lang=kotlin}
rootProp<Double>(tempo) = 120.0
```

Use it for settings that really are global, such as a tempo or an output folder. For anything that
belongs to one part of a document, `prop` and a connection say more about the structure.

## Finding a value by type or by name

`prop` names a slot ahead of time. `fromContext` instead finds a value that is already up the
graph, by its type. `fromContextWithNameOrNull` finds one by name. Both see the ordinary top-level
variables of the boxes above, not only their properties, so a parent needs no declaration at all.
**Studio** just has two variables:

```diffstatic {data-lang=kotlin}
val ink = java.awt.Color(200, 40, 40)
val title = "Spring"
```

**Sketch**, beneath it, asks for the first `Color` it can find, for something called `title`, and
for something called `subtitle`, which nothing defines:

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_graph/context.png" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="box-graph-context" macro-file="../../dev_docs/agents/transcripts/docs_capture/box_graph_context.yaml" capture-box="bgc_sketch" w="620" h="80" alt="Sketch's main output, one line: ink = (200, 40, 40), title = Spring, subtitle = null"}
val ink = fromContext<java.awt.Color>()
val title = fromContextWithNameOrNull<String>("title")
val missing = fromContextWithNameOrNull<String>("subtitle")
markdown("ink = (${ink.red}, ${ink.green}, ${ink.blue}), title = $title, subtitle = $missing")
```

`fromContext<T>()` looks, in order, at:

1. the box itself, if it is a `T`;
2. the box's own properties;
3. each generation of ancestors in turn, nearest first: each box's properties, then its top-level
   variables.

The box's own top-level variables are left out on purpose. Sketch's `val ink = fromContext<Color>()`
leaves a `Color` in Sketch's variables, and if Sketch searched those it would find its own previous
answer on every later run and never see Studio's again. Pass `includeLocalVariables = true` when a
box does want to find a value it made itself. `fromContextWithNameOrNull` never matches the box
itself, and returns `null` (or the `defaultValue` you pass) when nothing has the name.

This is how graphics code finds its renderer. The box that opens a window publishes `renderer`, and
every box beneath it reads it, or calls `fromContext<Renderer>()`, without looking for it.

## Disconnecting a running box

A box reads an inherited value at the moment its code reads the name. Disconnecting a box does not
stop it or change the code it compiled; the next read just searches a graph that no longer has the
parent in it. **Ticker** runs a `go` block that reads Garden's `petals` every 300 ms. Partway
through, its connection to Garden was cut:

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_graph/disconnect.png" capture="box" open-outputs="true" wait="0.2" timeout="180" embed=true name="box-graph-disconnect" macro-file="../../dev_docs/agents/transcripts/docs_capture/box_graph_disconnect.yaml" capture-box="bgx_ticker" w="760" h="100" alt="Ticker's main output after its connection to Garden was cut: read 10: Can't find a value for inherited property 'petals' in 'Ticker' or any of its 2 parent boxes. Are you sure the parent boxes that define 'petals' have been executed?"}
go {
    repeat(40) { n ->
        val seen = try { "petals = $petals" } catch (e: Exception) { e.message }
        markdown("read $n: $seen")
        delay(300)
    }
}
```

Before the cut each read found Garden's 5; the capture is read 10, after it. Ticker's code still
compiles and keeps running, but every read now fails. A long-running box that must survive
being rewired should read the value once, before it starts, and keep it.

## When a lookup fails

There are three different failures, and the message says which one you have.

**`Unresolved reference 'petals'`** is a compile error: no box above this one declares `petals`.
The box is not connected where you think it is, or the declaring box uses a different name.

**`Can't find a value for inherited property ...`** means the declaration was found but no box up
the graph has a value yet. Usually the parent has not run, or it failed. Here Garden declares
`petals` but was never run, and Flower reports:

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_graph/missing.png" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="box-graph-missing" macro-file="../../dev_docs/agents/transcripts/docs_capture/box_graph_missing.yaml" capture-box="bgm_flower" w="760" h="100" alt="Flower's main output: Can't find a value for inherited property 'petals' in 'Flower' or any of its 3 parent boxes. Are you sure the parent boxes that define 'petals' have been executed?"}
val seen = try { "petals = $petals" } catch (e: Exception) { e.message }
markdown("$seen")
```

**`must pass in a value for context parameter ...`** comes from `fromContext<T>()` when nothing up
the graph is a `T`; for a renderer it names `afield.graphics.vulkan.Renderer`. Pass a default,
`fromContext(defaultValue = ...)`, if the box should run without one.

A missing renderer is almost always the graph, not timing. The renderer box has not been run, or
the box is not connected beneath it, or the lookup happens inside a `start { }` or `go { }` block
that runs later, after the box has been rewired. Fix the connection, or read `renderer` once before
starting the work and pass it in. Waiting longer does not help.

See also:

- [Connecting boxes](tour_connecting_boxes.md), the short Tour version of this page.
- [Why does this value resolve here?](why.md), for asking a box where a value came from.
- [Properties that re-run boxes](reactive_properties.md), for `propDown`, `propUp` and the layer
  that draws who reads what.
- [Running code in a box](running_code.md#property-inheritance-how-children-see-a-parents-values),
  for the preamble and the `Inheritance` delegates underneath `prop`.
