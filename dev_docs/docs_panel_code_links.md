# Completion documentation code links

The completion documentation panel can follow resolved KDoc references into
recursive symbol cards and can show a guarded raw-source excerpt. Public behavior
and the live capture are in `public/docs/completion_doc_browser.md`.

## Code map

- `KotlinCompletionSupport.linkifyKDocSymbols(...)`: resolves supported KDoc
  references and emits `field-source:` Markdown links only for known locations.
- `KotlinCompletionSupport.resolveSourceCard(...)`: builds the clicked symbol's
  project-source card from `KotlinSourceIndex`.
- `KotlinCompletionSupport.readSourceExcerpt(...)`: reads bounded project or
  source-jar windows for the raw-source view.
- `rpc.kt`: `SourceCardRequest`, `SourceCardResult`, and `SourceFileResponse`.
- `Server.kt`: `resolve-source` RPC and `GET /api/source`.
- `fieldSuggest.js` / `fieldSuggest.css`: navigation stack, recursive rendering,
  back/forward control, and inline source toggle.
- `welcome.html` and `quickpick.js`: independent `/api/source` toggles for search
  results. They do not reuse the completion-card navigation stack.

## Exact linkification boundary

`linkifyKDocSymbols` currently resolves against the project Kotlin source index.
It supports bare `[Ident]`, qualified `[Owner.member]`, and KDoc alternate links
`[display][Target]`. Qualified members are accepted only when the candidate's
owner matches. Existing Markdown links, fenced code, and single-backtick inline
code are preserved. Unresolved and generic-looking references remain literal.

Classpath declarations can still have completion cards assembled by the normal
completion resolver, and `/api/source` can read a `-sources.jar`, but
classpath-only KDoc references are not currently turned into recursive
`field-source:` links.

`resolve-source` accepts a symbol or an indexed path and line. It returns a card
only for a definition found in `KotlinSourceIndex`; returned documentation is
linkified again, which makes project-source navigation recursive.

The frontend stores navigation on one `FieldSuggestController`. Following a
link truncates forward history, appends the card, and re-renders the same
documentation panel. Closing the suggestion widget resets that history.

## Raw-source security boundary

`GET /api/source?path=&line=&context=` clamps the excerpt length to 1–400 lines.
Filesystem paths must canonicalize under the checkout's `src/` directory. Jar
paths must use the `jar:file:...!/entry` shape and the archive filename must end
in `-sources.jar`. Anything else returns `found=false`.

The welcome search and double-shift similar-code palette use this endpoint to
toggle highlighted source below a result. These are deliberately separate,
lighter renderers; conversational docs search is separate again.

## Verification

```bash
./gradlew runDocsPanelCodeLinksSmokeTest
```

The same entry point is catalogued as `examples.docs-panel-code-links.smoke`.
The public page's `diffstatic_runner` capture additionally proves that a real
completion KDoc link renders in the browser. Do not replace that capture with a
hard-coded card.
