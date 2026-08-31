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

## A shader comment documents the Kotlin accessor generated for it

A note written at the end of a push-constant member's declaration reaches the Kotlin completion
popup for the accessor generated from that member:

```glsl
layout(push_constant) uniform Push {
    float time;   // seconds since the box started
} pc;
```

```kotlin
m.time          // the popup's documentation reads "seconds since the box started"
```

Reflection discards comments, so the text is carried by hand, in three legs:

1. **Scrape.** `parseGlslMemberComments` (`four/modules/Introspection.kt`) reads the trailing
   comments straight out of the shader source and `Introspection.memberComments` holds them, keyed
   `Block.member`, `instance.member` and — only when unambiguous — the bare `member`. It is lexical,
   so it sees only *named* blocks (`struct` / `uniform` / `buffer`, never a function body) and only
   text it is given: a push-constant block that arrives through `#include` is resolved by glslang at
   compile time and never appears in `Introspection.source`, so it has no comments to find.
2. **Carry.** `insertForShader` and its compute / mesh / ray-tracing siblings (`stdlib/shaders.kt`,
   via `shaderCommentSuffix`) append the note to *every* generated accessor for that member — the
   `Structure`, `Mesh`/`ComputeMesh`/`MeshMesh`/`RayTracingDispatch` and `…Group` bindings, the UBO
   members, and the members of the generated structure classes — as a **trailing line comment**. Not
   a KDoc block: the preamble is line-oriented and keyed by the leading marker comment
   (`preambleLineKey`), and a shader author's text is arbitrary enough to close a KDoc it was pasted
   into.
3. **Read back.** `ShaderBindingDocs` (`src/jvmMain/kotlin/afield/ShaderBindingDocs.kt`) parses the
   box's *inherited* preamble back into a lookup, which `completionFor` in `execution.kt` consults
   before the source-index KDoc lookups — the note is box-local and receiver-matched, so it should
   win over an index hit for a repository symbol that merely shares the name.

Two rules keep it honest. **A named receiver decides alone**: if the backend says the row is
`List.size`, a push constant called `size` does not get to explain it, and there is no bare-name
fallback in that case. And the lookup is resolved **once per completion request** (a `lazy` in
`completionFor`), never per candidate row — it gathers and parses a preamble, which is far too
expensive to repeat a thousand times per keystroke.

The preamble, not a live registry, is the carrier on purpose: it is saved with the document, so the
note survives a restart and is present for the first completion of a session, before any shader has
been reloaded. Covered by `ShaderCommentAutocompleteTest`.

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
   underscore alias — `exportedAlias(name)` = `_name` (`KotlinInterface2.kt:3131#private fun exportedAlias`) — onto the
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
