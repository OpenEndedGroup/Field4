# Completion Engine

Field has one user-facing completion engine: the custom FieldSuggest panel in
`src/commonMain/resources/fieldSuggest.js`.

Monaco's built-in suggest widget and `monaco.languages.registerCompletionItemProvider`
path are intentionally retired. They were difficult to order, hard to style and
read, and the documentation surface was too easy to ignore. Do not add a second
completion path through Monaco's provider API.

Current routing:

- `bootstrapMonaco.js` installs FieldSuggest for each editor.
- `fieldSuggest.js` owns ranking, grouping, recency, rendering, documentation,
  AI completions, and the final insert behavior.
- Server completion requests still use the shared `complete`,
  `complete-kotlin-ai`, and `resolve-completion` RPCs.
- Language extensions may still expose `provideCompletionItems(...)`, but
  FieldSuggest consumes those results and renders them in its own panel.
- Server-side, `InfixCompletion` (`src/jvmMain/kotlin/afield/execution.kt`) resolves
  the operand type(s) after `receiver op <caret>` and boosts (`contextScore`)
  candidates whose return type matches. It is overload-aware: an operator overloaded
  on operand type (e.g. `Fn.over(Domain)` / `Fn.over(ClosedRange<*>)`) contributes the
  parameter type of every single-parameter overload, so operands of any overload rank.

If completion behavior needs to change, extend FieldSuggest or the server RPC
payloads. Do not revive Monaco's native completion provider.

## Context-dependent completion expectations (Phase 0 corpus)

Some completion behavior cannot be expressed as a `(source string, cursor)` fixture because
it depends on the box's **inherited preamble** and its **`@exported` republications** — i.e. on
where the box sits in the tree, not just on the text being edited. These are documented here and
verified by a context-aware fixture (`CompletionContextProbe`), not the string corpus
(`CompletionCorpusProbe` / `CompletionDocScrapeProbe`).

The load-bearing invariant: **completion must resolve against the box's inherited preamble and
`@exported` aliases, not only the current snippet.** Today this works because the K1 completion
delegate bootstraps `gatherPreambleUpwards(box)` with `Box` as an implicit receiver
(`KotlinInterface2.kt` ~605/669). Any K2 Analysis-API replacement MUST preserve it.

1. **`@exported` members surface under their underscore alias.** A member marked `@exported`
   (also `@rootExported` / `@metarootExported`) in an ancestor box is republished under its
   underscore alias — `exportedAlias(name)` = `_name` (`KotlinInterface2.kt:2970`) — onto the
   box / root / metaroot (`processExportedFunction` → `Box.setPropertyRaw` +
   `upsertExportedDeclaration`) and inherited downward. **Completion in a descendant box must
   offer the `_name` alias** (e.g. an exported `foo()` → `_foo`).

2. **`prop<Foo>(bar)` / `rootProp<Foo>(bar)` inherited properties surface.** `prop` is a *macro*
   (`stdlib/props.kt` `macro_prop`), not a callable — it expands in the preamble to
   `var Box.bar by Inheritance<Foo>()` (+ the `Foo` import) and `val Once.bar by Onceing<Foo>()`.
   Because the preamble is inherited via `gatherPreambleUpwards`, **completion in that box and its
   descendants must offer `bar`, typed as `Foo`.** `rootProp` targets `box.root`.

Both are the same underlying requirement — completion sees the *inherited preamble*, which is
where `@exported` declarations and prop-macro expansions both land — so a single context fixture
that sets up a parent/child box pair with one exported symbol and one `prop<>` covers both.
