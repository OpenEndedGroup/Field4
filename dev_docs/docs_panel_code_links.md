# Navigable, recursive symbol cards in the documentation panel

**Goal:** every `[Symbol]` KDoc token and every source-location line in the
completion documentation panel becomes a clickable link that re-renders the
panel as *that symbol's card* — same category / highlighted signature /
rendered KDoc — with a floating back/forward + breadcrumb control. Unresolved
tokens stay plain text (never a dead link). Semantic-search results reuse the
same viewer.

## Key insight

The panel already has both renderers it needs — markdown → HTML
(`aiChatMarkdownToHtml`, `OutputAreas2Utils.kt`) and `hljs` syntax
highlighting. So there is no "fidelity tier": a navigated card must look
exactly as good as the card you came from. The feature is therefore **one card
renderer, made recursive, plus a nav stack**, staged by *which targets
resolve* rather than by appearance.

A second surprise: **library-symbol cards do not need the raw-source endpoint.**
Reading a jar's `-sources.jar` and parsing signature + KDoc already happens
server-side (`classpathSourceFor` + `parseJavaSourceMethods` /
`parseJavaSourceClassDoc`). Only showing the *raw code body* needs a new HTTP
route (Stage B, optional).

## Components

### 1. Shared card module (enables recursion + semantic-search reuse)
Extract the reusable card rendering out of `renderDocumentation`
(`src/commonMain/resources/fieldSuggest.js:766`) into a small shared JS module
in `src/commonMain/resources/`:
- `renderSymbolCard(cardData) -> html`
- `highlightCard(rootEl)` (wraps `hljs.highlightElement`)
- `resolveSource(payload)` (wraps `requestWithReply`, `fieldSuggest.js:105`)
- `NavStack` (`{title, cardData}` entries; back/forward; reset)

`fieldSuggest.js` and `welcome.html` both import it.

### 2. Backend: linkify `[Ident]` during doc-build (no dead links, server-side)
`linkifyKDocSymbols(markdown)` rewrites bare `[Ident]` ->
`[Ident](field-source:?symbol=Ident&path=...&line=...)` **only when it
resolves**, leaving the rest literal. Called from
`CallableInfo.documentationMarkdown` (`KotlinCompletionSupport.kt:163`),
`ConstructorInfo` (`:117`), and the KDoc body path.

Resolution order (also the coverage ramp):
- **Increment 1:** `KotlinSourceIndex` project symbols + typealiases
  (`indexedTypealiasReceiverDocumentation`, `:566`).
- **Increment 2:** classpath symbols via `findClassesNamed`
  (`importHelp.kt:19`); prefer project/index over classpath on ambiguity.

Reuses the existing frontend `[label](url)` rendering unchanged.

### 3. Backend: `resolve-source` RPC (mirror the resolve-completion triad)
- `rpc.kt`: `SourceCardRequest(symbol?, path?, line?)` +
  `SourceCardResult(documentation, sourceName, sourcePath, sourceLine, category)`
  near `rpc.kt:274`.
- `Server.kt`: `handlers["resolve-source"]` mirroring `Server.kt:2349`,
  assembling the card from existing resolvers (`resolveSourceCallableInfo`
  `:895`, `parseSourceCallableInfo` `:1402`, `resolveReflectionInfo` `:860`,
  jar parsers). Returned markdown is already linkified -> recursion is free.

### 4. Frontend: click interception + navigation
Extend the delegated handler on `.afield-suggest-doc`
(`fieldSuggest.js:903`): for `field-source:` hrefs, `preventDefault`, parse,
`resolveSource(...)`, push `{title, cardData}`, re-render. Make the
`.afield-suggest-doc-source` block (`:789`) an anchor with the same scheme.
Respect the container `mousedown` `preventDefault` (`:893`).

### 5. Frontend: floating back/forward + breadcrumb
Small control anchored bottom-right of the doc side: `back / forward` + a
breadcrumb title trail (`vec > Vec2 > Vector2f`). Reset the stack when the
widget closes.

### 6. Stage C: semantic search wiring
In `welcome.html` `renderSearchResults` (`welcome.html:4006`), render
`hit.path` (captured but unshown) as a `field-source:` link into the shared
viewer; run `hljs` over `sourcePreview`.

### 7. (Optional) Stage B: raw-source content endpoint
Guarded `GET /api/source?path=...` serving workspace files + `jar:...!/...`
entries (jars located at `KotlinCompletionSupport.kt:1169`),
path-traversal-guarded, restricted to source roots + resolved source jars.
Deferrable; without it, library cards show signature + KDoc but no body.

## Sequencing
1 -> 2(inc.1) -> 3 -> 4 -> 5 gives the full recursive project-symbol
experience. Then 2(inc.2) widens coverage, 6 brings in search, 7 is optional.

## Decisions
- `field-source:` href embeds the resolved `path`+`line` at build time (the
  backend already resolved it) rather than re-resolving `symbol` on click.
- v1 breadcrumb: back/forward only; per-segment clicking later.

## Status (2026-07-14)
Stages 1–5 implemented: backend `linkifyKDocSymbols` + `resolveSourceCard`
(`KotlinCompletionSupport.kt`), `SourceCardRequest`/`SourceCardResult`
(`rpc.kt`), `resolve-source` handler + AI-doc linkify (`Server.kt`), and the
frontend nav stack / floating back-forward+breadcrumb / source-line links
(`fieldSuggest.js` + `fieldSuggest.css`). Increment-1 coverage (project index +
typealiases). Verified against the real source index by
`DocsPanelCodeLinksSmokeTest` (`./gradlew runDocsPanelCodeLinksSmokeTest`, also a
FIELD_TEST catalog entry). Both Kotlin targets compile.

Live in-browser UI captured (doc panel `[Vec2]` link + recursive Vec2 card +
breadcrumb).

### Stage B — raw source view (done)
`GET /api/source?path=&line=&context=` (`Server.kt`) returns a window of a
project source file (guarded to `./src`, canonical-containment) or a classpath
`-sources.jar` entry (guarded to `*-sources.jar`); logic in
`KotlinCompletionSupport.readSourceExcerpt`. The doc panel's `source <name>:line`
footer now opens the actual code region inline (`field-source-view:` scheme →
`viewSource`), hljs-highlighted. Verified live: endpoint returns the correct
window for `linalg.kt:504`, both guards (path traversal, outside-`src`) return
`found=false`, and the inline highlighted source view was captured in the panel.

### Stage C — semantic search (done, UI-drive pending real workspace)
`welcome.html`: loads highlight.js, renders semantic hits' snippets
syntax-highlighted, shows `hit.path` as a link, and opens the real source region
inline via the shared `/api/source` endpoint (capture-phase click interception so
it doesn't also open the document). Compiles, deploys (served page contains all
changes), inline JS syntax-checked, and the `/api/source` dependency verified
live. The full welcome-screen drive (real hits → link → view) was NOT exercised
in the headless test JVM: its workspace is empty and the semantic index rebuild
hangs on DJL/ONNX init — both environment issues, not the Stage C code. Verify on
a real workspace / the user's instance.

Not yet done: Increment-2 classpath symbols in KDoc `[Symbol]` linkification.

## Verification (real, not synthetic)
Drive the real UI (type `var v = vec` via the backdoor/transcript path),
confirm `[Vec2]` is a live link, click it, assert the `Vec2` card renders with
the same styling, hit back. Use the `drive` op / `capture=ui` fence for a live
capture and `run_field_tests` for the RPC round-trip. Never hardcode a card.
