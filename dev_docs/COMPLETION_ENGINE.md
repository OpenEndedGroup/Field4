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
