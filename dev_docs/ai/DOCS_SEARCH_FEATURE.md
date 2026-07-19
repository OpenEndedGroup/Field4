# AI code-search surface

The user-facing action is **Search Documentation & Code**
(`Ctrl/Cmd+Shift+D`). `commands.kt` loads `/docs-search.html` in the existing docs
iframe; the page opens its own Field websocket and sends `docs_search_input`
messages.

Despite the historical name, this is currently an AI-assisted **source search**,
not an index of the Markdown documentation trees. Its retrieval inputs are:

- the startup-built `KotlinSourceIndex` covering `src/jvmMain/kotlin`,
  `src/personal/kotlin`, and `src/generatedLinalg/kotlin`;
- GLSL include-library vector stores selected for a matching query.

If neither store is available, the request is still sent without `file_search`
grounding and the server logs a warning. Changes to the Kotlin roots require a
server restart because the index is built once at startup.

## Ownership

- `src/commonMain/resources/docs-search.html`: websocket session, query UI,
  provisional/final response replacement, reconnect, and keyboard handling.
- `src/commonMain/kotlin/rpc.kt`: `DocsSearch_input(value, phase)` payload.
- `src/jvmMain/kotlin/afield/ai/core/DocsSearchInstaller.kt`: handler,
  per-browser-session state, phase 1/20 replies, and the deliberately small
  Markdown-to-HTML formatter.
- `src/jvmMain/kotlin/afield/ai/core/DocsSearchBridge.kt`: remote conversation,
  retrieval stores, streaming/non-streaming fallback, and model fallback.
- `src/jvmMain/kotlin/afield/ai/core/completion/KotlinSourceIndex.kt`: exact
  indexed roots and startup synchronization.

The authoritative behavioral details are KDoc on the bridge and installer.

## Limits worth preserving in user-facing language

- `OPENAI_API_KEY` and network access are required for useful operation.
- Results are generated answers, not compiler resolution and not proof that a
  claim is current; the prompt asks the model to cite retrieved file paths and
  admit weak grounding.
- The formatter supports headings, paragraphs, simple bullets, emphasis,
  inline code, and fenced blocks. It escapes model text and provides copy
  buttons, but it does not implement a full Markdown grammar, nested lists,
  clickable source navigation, or a syntax-highlighting engine.
- Conversation state lasts while the iframe's generated session id and server
  installer state survive. There is no reset control, eviction, fixed response
  timeout, search history, bookmark, or export facility.

## Verification

With a disposable API configuration, open the action and inspect one query with
an available Kotlin index and one with no vector-store id. Confirm phase 1 is
replaced by phase 20, a second query continues the same session, reconnect does
not send a queued query automatically, and HTML-looking failure text is rendered
as text. There is no dedicated automated regression for this surface today.
