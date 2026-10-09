# Property dependencies: recording box-to-box reads

Status: recording, persistence and the canvas layer are in (2026-10-09). Re-execution is not; the
plan for it is at the end.

## What is recorded

Every inherited read in Field goes through one walk, `ContextResolution.search`:

| Box code | Delegate / call | Mechanism |
| --- | --- | --- |
| `prop<T>(speed)`, `rootProp<T>(speed)` | `Inheritance<T>` | `INHERITANCE` |
| `@exported val speed = ...` (read in a child) | `InheritanceFromContext<T>` | `INHERITANCE_FROM_CONTEXT` |
| `fromContext<Renderer>()` | `ContextResolution.walk` | `FROM_CONTEXT` |
| `fromContextWithNameOrNull<T>("x")` | `ContextResolution.walk` | `FROM_CONTEXT_NAMED` |
| FieldML `prop` | `inheritedPropertyRaw` | `INHERITANCE` |

`search` hands each non-explain outcome to `PropertyDependencies.recordResolution`. The
inherited delegates' setters call `PropertyDependencies.recordWrite`. Three kinds of entry:

- **READ**: the reader's code got a value that another box held.
- **MISS**: the walk found nothing (a `MissingInheritedPropertyException`, or a nullable `prop`
  that came back null). Misses from `fromContextWithNameOrNull` are not recorded: that call is an
  optional probe, and `magic.kt` and `Module.kt` use it as one for every buffer and acceleration
  structure.
- **WRITE**: the reader's code wrote an inherited property into a different box. This is the
  `rootProp` case, `root.speed = 4.0`. Without it the only supplier visible would be the root.

The **reader** is `Box.context` (the executing box), falling back to the box the walk started
from; the two differ for `named("other").speed`. Reads of one's own value and writes into one's
own box are not dependencies and are not recorded.

Not recorded:

- `explain` walks, `why.<name>`, the Monaco hover. `ContextResolution.peek` is the non-recording
  walk, for tools.
- Plain `Delegation` reads (`box.someProperty` of a non-inherited property on another box) and
  raw `getPropertyRaw` calls. These do not go through the walk. Recording `Delegation` would put
  a thread-local read on every property access in Field, and the walk itself reads `parents`
  through it, so it would need an exclusion list to stay truthful. Revisit it if a real document
  needs it.

One `Edge` is kept per (reader, supplier, name, mechanism, access), holding a count, a
session-only count and first/last-seen times. It is not an event log. A box that draws every
frame reads `renderer` sixty times a second, and what the record has to answer is who depends on
whom.

## Derived views

`PropertyDependencies.flows(store, rootId)` merges the edges into producer → consumer pairs:

- a READ from box H is a flow H → reader, unless H is the document root or the metaroot;
- a READ of `name` from H, when some other box W WROTE `name` into H, is also a flow W → reader.

`upstreamOf(box)` gives the producers of one box. `rememberedSuppliers(store, reader, name)` gives
the boxes that supplied `name` to `reader` on any earlier read. That second one is the question
upstream execution asks.

A MISS that a later READ of the same (reader, name) answered is left out of the graph.

## Persistence

`<document>/dependencies.edn`, version 1: `{:version 1 :edges [{"reader" .. "supplier" ..
"name" .. "mechanism" .. "access" .. "count" .. "firstSeen" .. "lastSeen" ..}]}`. It is loaded on
first use (the first read recorded, or the layer's first request). It is written from the autosave
loop in `Server.server()`:

- a new edge is written at the next tick (≤ 5 s);
- if only counts or times moved, it is written at most every 60 s;
- everything is written on shutdown (`DocumentShutdownFlush`).

Writes go to the misc pool through a temp file and a rename. Scratch and read-only documents
record in memory and never write. Before each write, edges are pruned if their reader has left
the document or their supplier was tombstoned.

The file is not part of history (`backups/`, `versions.edn`). Duplicating a document copies it,
which is correct because box uids survive duplication.

Off switch: `-Dafield.dependencyTelemetry=false` or `AFIELD_DEPENDENCY_TELEMETRY=off`.

## The layer

`src/jsMain/kotlin/afield/dependencyLayer.kt`. Palette: **Toggle Dependency Layer** (remembered
in `localStorage`) and **Clear Dependency Record**. While it is on, the page polls
`dependency-graph` every 750 ms and redraws inside `rebuildConnectionGroup`, so the layer follows
pan, zoom and drags. Encoding:

- amber: read in the last 2 s;
- blue: read this session;
- grey and dashed: remembered from disk only;
- red `? name` beside a box: an unanswered read, with dashed red arrows from its remembered
  suppliers.

`window.dependencyLayerState()` reports what was received and drawn.

## Toward Marimo-style execution

The record is what a reactive scheduler needs. Box code is arbitrary Kotlin, so a static
dependency graph can't be read off the source the way Marimo reads Python cells. The observed
graph is the substitute, and it is persisted so that it is available before anything has run.

1. **Upstream on a miss.** `MissingInheritedPropertyException` already reaches the run path.
   When a box run fails with one, look up `rememberedSuppliers(reader, name)`. Run those boxes
   (recursively, with a visited set), then retry the reader once. This covers "values missing on
   startup" without changing anything for documents that already work. The natural seam is the
   autorun path (`SessionInformation.autorunBoxes`): order the autorun set topologically by
   `flows` before launching, so that suppliers start first.
2. **Downstream on change.** This needs a "value changed" event at the supplier. The candidates
   are `setPropertyRaw` on a property that has recorded readers, or the end of a supplier's run
   for `@exported` variables. Re-run the readers, debounced, and only those whose recorded reads
   of that name are recent, so that a stale edge doesn't wake a box that no longer reads it.
   Per-frame readers (`renderer`, anything read inside `frames {}`) must be excluded; they are
   already live. A per-box opt-in (an `autorun`-like property) is safer than a global switch.
3. **Cycles and staleness.** The observed graph can contain cycles (A writes `root.x`, B reads it
   and writes `root.y`, A reads `y`). The scheduler needs cycle detection and a per-propagation
   visited set. Edges also go stale when code changes. Ageing out edges that have not been seen
   for N runs of their reader would keep the graph honest. The record has `lastSeen` per edge,
   but not yet the reader's last run time, which ageing would need.

Tests: `PropertyDependenciesTest` (jvmTest).
