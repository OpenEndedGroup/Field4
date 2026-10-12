# Inspector embeds: `tree()`, `column()`, `logs()`

Renewed 2026-10-12 (nightly). Code: `src/jvmMain/kotlin/afield/server/browsing.kt` (model and
`__browsing__` protocol), `src/commonMain/resources/{tree,browser,logs}.html`, entry points in
`afield/outputs.kt`. Tests: `src/jvmTest/kotlin/afield/server/InspectorTest.kt`. Demo:
`dev_docs/agents/demos/browse_embeds/`. User docs: `public/docs/visualizers.md` (inspector section).

## Protocol (additive)
- `entries` rows are `[name, id, kind, type, summary, flag, path]`; old clients read `[0..1]`.
  `flag` is `""`, `cycle` or `error`. `path` is the row's GraphQuery address from the root
  (`""` when none reaches it: primitive-array elements, object map keys, repeated map names,
  names containing `/`, non-Collection Iterables).
- Dir replies carry `valueType`, `total` (null for a bare Iterable), `offset`, `more`.
  New op `more {id, offset, limit}` returns the next page. Default page 200, max 2000.
- Leaf replies carry `value` (full text, ≤200k chars, for copy) and `valueType`.
- `query {id, query, limit}` runs `graphPaths(query)` from node `id` (clients send the root) with
  `maxRefs` capped at 20 000; reply `{type:"query", matches:[[path, kind, type, summary]], total,
  truncated}`, first 500 by default. Clients treat filter text containing `/`, `*` or `|` as a
  query (debounced 180 ms) and anything else as the old substring filter; choosing a match
  reveals it by walking `data-path` prefixes, loading `more` pages as needed.

## One walker (2026-10-12)
Objects (anything not Map/List/Iterable/array/leaf) are listed as `GraphWalker.edges(obj,
options)`: `LiveGraphInspectable` edges first, then Kotlin member properties in constructor order.
`Browsable` extends `LiveGraphInspectable` (its `dir()`), so Browsables are reachable by
`graphPaths` too. Collections keep their own paging code (so one throwing element is an error row)
but name their segments as GraphQuery does (`[3]` shown, `3` in the path).

Private fields: the default is public-only (`GraphWalkOptions()`), unlike the old reflective
backing-field walk. Reason: the point of the path column is that a copied path works in
`value.graphPath(path)` with default options; a default that listed private fields would hand out
paths the default walk cannot resolve. `tree(..., includePrivate = true)` / `column(...)` and
`Inspector.linkFor(..., options =)` opt in; the options travel with the link token. Consequence:
opening an object now runs property getters (a `by lazy` is forced), where the old walk read
backing fields only; a throwing getter is an error row.

GraphQuery fixes the unification exposed: a `Set`/non-List `Collection` was reflected as an object
(only `size`); now walked by iteration index, read-only. A class kotlin-reflect refuses no longer
throws out of the whole walk. Tests: `GraphQueryCollectionEdgesTest`, `InspectorTest`.

## Headers
Tree and logs headers are `position: sticky` and, in an output panel, the rows below them scroll
inside the embed (`#tree`/`#log` capped at `--maxh`), so the header never scrolls off.
`iframe_autosize.js` no longer counts the `scrollHeight` of elements that clip their own overflow,
which had stretched a column panel to the full length of a 300-row list.

## Live update
Watched nodes are polled once a second: `Browsable.version()`, else a content fingerprint (size +
first 2000 elements; value hash for plain values, identity for objects). So in-place mutation of a
primitive array element of a boxed-identity-equal value is seen, but replacing an object's field
with an equal-identity object is not. The fingerprint is read on the poller thread, unsynchronised
with the box; any exception just skips that poll.

## Findings from the before pass (Playwright, 2026-10-12)
- data classes, user objects and arrays were `toString` leaves; cycles invisible.
- opening a 5000-list rendered 5009 rows (120 000 px tall document) and the tree's kind probe sent
  one `open` per child (5000 server resolves).
- map entries were keyed by `toString` via `associate`, so `1` and `"1"` collapsed into one row.
- every re-list minted fresh child ids (unbounded `items` growth, lost open state).
- `logs()` emitted an unclosed `<iframe>`; logs.html's BEGIN regex was double-escaped (never
  matched) and block ids went into `RegExp` unescaped (an id with `(` threw and dropped the row);
  no reconnect.

## Known gaps
- `items` still holds strong references to every node ever listed for the life of the JVM.
- Column view keeps the old column width (260px) and fixed-ish styling; no type-ahead.
- No UI regression in `tools/run_ui_regressions.py` yet; `window.inspectorTreeState()`,
  `window.inspectorColumnsState()` and `window.logsEmbedState()` exist as probes for one.
