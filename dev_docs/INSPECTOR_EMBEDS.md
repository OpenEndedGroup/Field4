# Inspector embeds: `tree()`, `column()`, `logs()`

Renewed 2026-10-12 (nightly). Code: `src/jvmMain/kotlin/afield/server/browsing.kt` (model and
`__browsing__` protocol), `src/commonMain/resources/{tree,browser,logs}.html`, entry points in
`afield/outputs.kt`. Tests: `src/jvmTest/kotlin/afield/server/InspectorTest.kt`. Demo:
`dev_docs/agents/demos/browse_embeds/`. User docs: `public/docs/visualizers.md` (inspector section).

## Protocol (additive)
- `entries` rows are `[name, id, kind, type, summary, flag]`; old clients read `[0..1]`.
  `flag` is `""`, `cycle` or `error`.
- Dir replies carry `valueType`, `total` (null for a bare Iterable), `offset`, `more`.
  New op `more {id, offset, limit}` returns the next page. Default page 200, max 2000.
- Leaf replies carry `value` (full text, ≤200k chars, for copy) and `valueType`.

## Resolution order
Browsable/HasBrowsable → MapEntryView → Map → List → Iterable → array → registered adapters →
plain object fields → leaf. "Plain object" means a class outside JDK/Kotlin/LWJGL packages that is
not a lambda; it opens to its **backing fields** (no getters run; private fields included; `x$delegate`
shown as `x`). Strings, numbers, enums, chars, booleans and classes are always leaves.

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
