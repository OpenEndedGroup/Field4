# Live output iframe bridge (incubating)

Field box code can address the iframe in one output channel of the current Electron tab:

```kotlin
val page = outputIframe("fred", "bob")  // box name or uid, output channel
// From fred itself: outputIframe("bob")
// With a Box reference: outputIframe(fredBox, "bob")

page.waitFor("window.proof && document.querySelector('#state')")
    .thenCompose { page.evaluate("document.querySelector('#state').textContent = 'ready'") }
    .thenAccept { println(it) }
```

The hosting box opts its output channel into AI4 when it runs:

```kotlin
display("bob", """<iframe src="https://127.0.0.1:18331/"></iframe>""")
exposeOutputIframeTools("bob")
```

This adds `iframe_bob_inspect`, `iframe_bob_wait`, `iframe_bob_navigate`,
`iframe_bob_screenshot`, `iframe_bob_click`, `iframe_bob_fill`, and
`iframe_bob_evaluate` to AI4 chats for that document. Nothing is added to other documents.
Omitting the exposure call, removing it on a rerun, or retiring the hosting box removes the
tools. If two host boxes use the same channel name, pass an explicit second name argument to
`exposeOutputIframeTools`, such as `exposeOutputIframeTools("bob", "flask")`.

`evaluate` runs JavaScript inside that iframe and returns a `CompletableFuture<JsonElement>`.
The same call can read `document.documentElement.outerHTML`, traverse the DOM, inspect or set
globals, inject a stylesheet, or add and remove elements. `waitFor` re-resolves the frame on
each poll, so a navigation does not strand the wait in an old execution context. The futures do
not block Field's runloop. `fill` and `click` automatically wait for their selector.

`cdp(method, params, sessionId)` sends a raw command through Electron's debugger. CDP attaches
to the owning tab, so a raw CDP command is **not** automatically confined to the iframe. Use
`evaluate` for frame-scoped work. The bridge requires exactly one Electron tab for the document
and exactly one iframe in the named output channel; ambiguous targets fail rather than guess.

## Current boundary

This is a first bridge, not a Playwright-compatible API. The Electron main process resolves the
DOM iframe to a `WebFrameMain`; when a loaded frame does not reflect the transient frame name,
it falls back to an exact URL match only if that URL is unique among child frames. Two frames
with the same URL can therefore be ambiguous. A single in-flight `evaluate` still fails if the
page navigates while its JavaScript is running; issue a new call or use `waitFor` after loading.
The iframe screenshot captures its visible rectangle in the live tab, including UI that overlaps
it. An offscreen iframe has no pixels to capture. AI4's older box-output screenshots still use
the separate capture browser; `iframe_bob_screenshot` uses this live bridge.

## Isolated proof

The first end-to-end proof used a small HTTPS Flask page on `127.0.0.1:18331`, a scratch Field
document in a dedicated workspace on port 9588, and a dedicated Electron user-data directory.
Kotlin waited for `window.proof`, changed its count and a DOM node, then read both back from a
second box through the same visible iframe. `Page.getFrameTree` also succeeded through raw CDP.
The opt-in AI4 tools read the page, waited for its textbox, and filled it. The proof touched no
pre-existing Field session.
