# Value visualizer: ownership and verification

The current user tour and real captures are in `public/docs/reading_your_values.md`. The rendering
contract and limits belong on `ValueVisualizer` in
`src/jvmMain/kotlin/afield/visualization/ValueVisualizer.kt`; graphics-specific registrations live
in `afield/four/FourVisualizers.kt` and synth registrations in `afield/four/synth/Visualizers.kt`.

Extension points:

- implement `ToHTML` for a type-owned representation;
- call `registerValuePrinter<T> { ... }` for a process-wide external formatter;
- call `Box.viz(channel, value)` to display, or use `value.h` to obtain the HTML.

`ToHTML` and registered printers return trusted HTML. Built-in textual branches escape values. Keep
that distinction explicit when adding formatters.

## `.h2`: the same value, three renderings

`.h2` is `LazyValueVisualizer.visualize` in
`src/jvmMain/kotlin/afield/visualization/LazyValueVisualizer.kt`. When it engages it retains the
value under a token and draws **three independent renderings of it**, which is the fact that governs
every change here:

| surface | route | code |
|---|---|---|
| embedded panel | `/h2-explorer?root=<token>`, fed over the `/ws` `__h2_explorer__` channel | `H2Source` / `makeSource` in `afield/server/h2explorer.kt` |
| outliner | `/tree?root=<token>` | `Browsable` from `wrapForInspector` |
| columns | `/browse?root=<token>` | the same `Browsable` |

**A type that reaches only one of the three looks broken in a specific and confusing way.** Before
`H2Reflect.kt`, a domain object forced past the gate would have got `wrapForInspector`'s
unwrapped-value fallthrough (so `/tree` rendered `toString()` as a leaf) and `makeSource`'s
`ScalarSource` (so the panel was one row of one cell) — correct geometry, no content,
indistinguishable at a glance from an empty frame. Adding a family means touching the gate *and*
both renderers, or none.

Extension points, in the order to try them:

1. **Nothing.** `H2Reflect` (`src/jvmMain/kotlin/afield/visualization/H2Reflect.kt`) reflects any
   domain object with two or more public properties. Both renderers read
   `H2Reflect.properties` — deliberately the single enumerator, so `/tree` and the panel cannot
   report different children for one value.
2. **`registerH2Value<T> { H2ValueSnapshot(value = …, summary = …, title = …) }`** when the
   reflective reading is wrong. Return a shape the existing machinery already draws: a
   `LinkedHashMap` inherits map paging in all three surfaces, and a `List<Map<String, Any?>>` hits
   the panel's RECORD mode and becomes a table with one column per key. Registrations live in
   `afield/visualization/H2DomainAdapters.kt` (`Box`, `Functions.Fn`, `FLine`, `Throwable`),
   `afield/four/FourVisualizers.kt` (`FrameBufferGroup`) and
   `afield/visualization/DataFrameVisualizer.kt` (`DataFrame`).
3. **`H2Source.textColumns`** when a new source's column is heterogeneous. The client aligns
   decimals and z-score-bands per column, which is right for a buffer and wrong for a property
   table.

### The two limits worth knowing before you extend this

**A getter is opaque.** There is no way to tell a field read from a computed property that opens a
file, allocates a GPU buffer, or compiles a K2 REPL engine. `Box.ki` is the last of those, which is
why `Box` is served by a hand-written adapter and not by reflection —
`H2StructuredExplorerTest.exploring a Box does not build its Kotlin engine` asserts that by
identity, so deleting the adapter fails there rather than downstream. The general mitigation is
`H2Reflect.readsWellInline`'s refusal of JVM and Kotlin platform packages; it is not a proof. **If a
type's getters are expensive or effectful, give it an adapter.**

**Cycles have two different answers.** `H2ReflectiveBrowsable` threads an identity path chain and
renders `[circular reference]` where the graph closes. `PagedListBrowsable` and `PagedMapBrowsable`
have no such guard, and the Inspector resolves `dir()` on click, so a collection that contains
itself lets a person descend forever. That is pre-existing and unfixed. It is why the `Box` adapter
flattens its four edge kinds to a label table rather than handing the pager the `Box` objects: a box
graph is cyclic by construction now that `nestInto` creates the `parents`/`children` edge.

Verification sources:

- `src/jvmTest/kotlin/afield/LinalgThumbnailVisualizerTest.kt`
- `src/jvmTest/kotlin/afield/H2StructuredExplorerTest.kt` — the gate, the refusals, the reflective
  traps, and the four adapters. It restates the pre-change `isHeavyStructure` predicate as
  `oldGateAdmits` and asserts the premise first, so a sample that is secretly a collection fails
  loudly instead of making the rest vacuous.
- `src/jvmTest/kotlin/afield/server/H2ExplorerRecordRowsTest.kt` — panel cell content for the
  record/table path.
- `src/jvmTest/kotlin/afield/UnitValueVisualizerTest.kt` — the fence that `.h2` on a `Quantity`
  stays with the unit-aware compact renderer.
- `tools/run_ui_regressions.py --id h2-domain-explorer` — pixels, reading the iframe's own DOM.
  `--id h2-panel-sizing` is the list/geometry counterpart.
- `templates/h2-explorer-examples/` — insertable demo, one box per family.
- `dev_docs/viz_demo.kt`
- `dev_docs/agents/transcripts/linalg_thumbnails_demo.yaml`
- the docs-runner fences and captures in `public/docs/reading_your_values.md` and
  `public/docs/vector_algebra.md`

When a type, limit, palette, or thumbnail changes, update the owning KDoc/tests and regenerate the
real public captures. Do not restate private constants in this page.
