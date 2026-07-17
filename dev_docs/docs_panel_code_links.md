# Navigable, recursive symbol cards in the documentation panel

**Goal:** every `[Symbol]` KDoc token and every source-location line in the
completion documentation panel becomes a clickable link that re-renders the
panel as *that symbol's card* — same category / highlighted signature /
rendered KDoc — with a floating back/forward + breadcrumb control. Unresolved
tokens stay plain text (never a dead link). Semantic-search surfaces (the
welcome screen and the "find similar code" palette) do not reuse the doc
panel's recursive card viewer; they each got their own lighter "view source
inline" toggle backed by the shared `/api/source` endpoint (see Stage C /
palette parity below).

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

### 1. Frontend: recursive card rendering (self-contained in fieldSuggest.js)
No shared JS card module was extracted. The recursion lives entirely inside
`src/commonMain/resources/fieldSuggest.js`: `renderNavCard()` simply wraps the
existing `renderDocumentation()`, and the nav stack is state on
`FieldSuggestController` (`navStack` / `navIndex` fields) rather than a separate
`NavStack` class. `field-source:` URLs are built by a small helper against the
`FIELD_SOURCE_SCHEME` constant, and `resolve-source` is called inline via
`requestWithReply("resolve-source", …)` (no `resolveSource()` wrapper). The
welcome screen and palette do **not** import any of this (see #6 / palette
parity for their independent code paths).

### 2. Backend: linkify `[Ident]` during doc-build (no dead links, server-side)
`linkifyKDocSymbols(markdown)` rewrites bare `[Ident]` ->
`[Ident](field-source:?symbol=Ident&path=...&line=...)` **only when it
resolves**, leaving the rest literal. Called from
`CallableInfo.documentationMarkdown`, `ConstructorInfo.documentationMarkdown`,
and the KDoc body path (all in `KotlinCompletionSupport.kt`).

Resolution order (also the coverage ramp):
- **Increment 1:** `KotlinSourceIndex` project symbols + typealiases.
- **Increment 2:** classpath symbols via `findClassesNamed`
  (`afield/internal/importHelp.kt`); prefer project/index over classpath on
  ambiguity.

Reuses the existing frontend `[label](url)` rendering unchanged.

### 3. Backend: `resolve-source` RPC (mirror the resolve-completion triad)
- `rpc.kt`: `SourceCardRequest(symbol?, path?, line?)` +
  `SourceCardResult(documentation, sourceName, sourcePath, sourceLine, category)`.
- `Server.kt`: `handlers["resolve-source"]`, assembling the card from existing
  resolvers plus the jar parsers via `resolveSourceCard`
  (`KotlinCompletionSupport.kt`). Returned markdown is already linkified ->
  recursion is free.

### 4. Frontend: click interception + navigation
The delegated `click` listener on `.afield-suggest-doc` intercepts
`field-source:` and `field-source-view:` hrefs (`FIELD_SOURCE_SCHEME` checks),
`preventDefault`s, parses the query, resolves the card, pushes
`{title, cardData}` onto the nav stack, and re-renders. The
`.afield-suggest-doc-source` footer is emitted as an anchor with the same
scheme. The container `mousedown` handler keeps its `preventDefault` but makes
an exception for clicks inside `.afield-suggest-doc` so links stay clickable.

### 5. Frontend: floating back/forward + breadcrumb
Small control anchored bottom-right of the doc side: `back / forward` + a
breadcrumb title trail (`vec > Vec2 > Vector2f`). Reset the stack when the
widget closes.

### 6. Stage C: semantic search wiring (independent, not the card viewer)
`welcome.html` `renderSearchResults` shows each hit's `hit.path` via
`renderMatchPath`, which emits a plain `<a class="welcome-source-link"
data-path=… data-line=…>` (only for `/api/source`-servable paths). A
capture-phase click handler calls `toggleWelcomeSource`, which `fetch`es
`/api/source`, hljs-highlights the result, and inserts an inline `<pre>` region
beneath the match — its own toggle, not the doc panel's recursive card /
breadcrumb viewer.

### 7. Stage B: raw-source content endpoint
Guarded `GET /api/source?path=&line=&context=` serving workspace files (guarded
to `./src`, canonical-containment) + classpath `-sources.jar` entries (guarded
to `*-sources.jar`); logic in `KotlinCompletionSupport.readSourceExcerpt`.
Without it, library cards show signature + KDoc but no body.

## Decisions
- `field-source:` href embeds the resolved `path`+`line` at build time (the
  backend already resolved it) rather than re-resolving `symbol` on click.
- v1 breadcrumb: back/forward only; per-segment clicking later.

## Status
Implemented end-to-end: backend `linkifyKDocSymbols` + `resolveSourceCard`
(`KotlinCompletionSupport.kt`), `SourceCardRequest`/`SourceCardResult`
(`rpc.kt`), `resolve-source` handler + AI-doc linkify (`Server.kt`), and the
frontend nav stack / floating back-forward+breadcrumb / source-line links
(`fieldSuggest.js` + `fieldSuggest.css`). Linkification coverage is Increment-1
only (project index + typealiases); Increment-2 classpath symbols in KDoc
`[Symbol]` linkification is **not yet done**. Regression-pinned against the real
source index by `DocsPanelCodeLinksSmokeTest`
(`./gradlew runDocsPanelCodeLinksSmokeTest`, also a FIELD_TEST catalog entry).

### Stage B — raw source view
The doc panel's `source <name>:line` footer opens the actual code region inline
via the `field-source-view:` scheme → `viewSource`, hljs-highlighted, backed by
`GET /api/source` (see Components #7).

### Stage C — semantic search
`welcome.html` loads highlight.js, renders semantic hits' snippets
syntax-highlighted, shows `hit.path` as a `welcome-source-link`, and opens the
real source region inline via the shared `/api/source` endpoint (capture-phase
click interception so it doesn't also open the document). See Components #6.

### Palette parity + a NaN-JSON bug fix
The double-tap-Shift palette's "Find similar code" (`quickpick.js`,
`openSimilarCodePalette`, corpus `""` → codebase hits) renders each codebase
hit's path as a `/api/source` "view source" link with an inline highlighted
region (`afield-palette-source-link`, `data-source-view`) — the same treatment
as the doc panel and welcome, via its own independent toggle.

A pre-existing bug that broke *all* in-browser semantic search (palette +
welcome) was fixed here: `SemanticRetrievalHit` scores could be `NaN`
(degenerate/zero embedding vector → NaN cosine), which kotlinx serializes as the
bare token `NaN` — invalid JSON that browsers' `response.json()` rejects
(Python's json accepts it, which masked it under curl). `hitFromDoc` now coerces
non-finite scores to 0.

## Linkifier hardening

`linkifyKDocSymbols` / `resolveSymbolLocation` were tightened past Increment-1's
fence-only handling. What is and isn't linkified now:

- **Bare `[Ident]`** that resolves to a project declaration → `field-source:`
  link. Unresolved (e.g. classpath-only `[Float]`) → left literal.
- **Existing `[label](url)` links** → untouched (the label is not re-linkified).
- **Fenced ``` ``` ``` code blocks** → untouched.
- **Inline `` `code` `` spans** → untouched. Each outside-fence region is split
  on single backticks and only the non-code sub-segments are linkified, so
  `` `[Vec2]` `` in inline code stays literal. (Limitation: double-backtick
  `` ``code`` `` inline spans are not tracked; rare in KDoc.)
- **`[display][Target]` alternate-links** → matched as one unit. The display
  text is never linkified on its own; the whole reference links once to
  `Target` (`[display](field-source:…Target)`), or stays fully literal if
  `Target` does not resolve.
- **Qualified `[Foo.bar]`** → resolves on the last segment (`bar`) but only
  accepts a candidate whose owner simple-name is `Foo`. If no candidate's owner
  matches, it is left literal rather than linking to an unrelated `bar` — no
  wrong links.
- **Generic `[Foo<Bar>]`** → `<`/`>` are outside the identifier char class, so
  it never matches and stays literal.

Regression-pinned by `DocsPanelCodeLinksSmokeTest` (cases 2b–2e).

## Verification (real, not synthetic)
Drive the real UI (type `var v = vec` via the backdoor/transcript path),
confirm `[Vec2]` is a live link, click it, assert the `Vec2` card renders with
the same styling, hit back. Use the `drive` op / `capture=ui` fence for a live
capture and `run_field_tests` for the RPC round-trip. Never hardcode a card.
