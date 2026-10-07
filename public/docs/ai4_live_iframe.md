# Let AI4 drive a live webpage

An output iframe can be a small browser that your Field box and AI4 share. The page below is
served by Field itself; its textbox is waiting for the agent to type into the *same iframe you
see* in the Electron app.

```diffstatic_runner {data-lang=kotlin screenshot="assets/ai4_live_iframe/hello.png" alt="A Field box with a dark webpage in its page output: a heading A textbox for AI4, an empty Message textbox, and a line saying Nothing typed yet" capture="box" open-outputs="true" wait-output=true wait-output-kind=display wait-output-channel="page" wait="2" timeout="180" embed=true name="ai4-iframe-hello" w="800" h="470" padding="18"}
display("page", """<iframe src="/iframe-ai4-demo.html" style="border:0;width:640px;height:380px;display:block"></iframe>""")
exposeOutputIframeTools("page", "hello")
Unit
```

[Open the Iframe hello starting point ↗](offline.html?file=assets/agent_transcripts/ai4_iframe_hello/metadata.edn)
in Field, **run the box**, and open its AI Chat panel with `command/ctrl-4`. Try:

> Write “hello” in the webpage's Message textbox, then inspect the page and tell me what it says.

The hosting box opts in with `exposeOutputIframeTools("page", "hello")`. That call gives AI4 seven
tools named `iframe_hello_inspect`, `iframe_hello_wait`, `iframe_hello_navigate`,
`iframe_hello_screenshot`, `iframe_hello_click`, `iframe_hello_fill`, and
`iframe_hello_evaluate`. They belong to this document and disappear when the box no longer
registers them. Other documents do not receive them.

`fill` waits for a CSS selector, sets an input's value, and emits the input/change events that
web apps listen for. `inspect` can return the whole live DOM and inline script source, or matching
elements for a selector. `wait` retries through page changes. `navigate` loads a URL and waits
for the iframe's load event. `screenshot` sends AI4 an image of the iframe's visible pixels in the
Electron tab. `evaluate` runs arbitrary JavaScript in the iframe and returns a JSON value, for
work that does not fit the named tools.

To use your own page, change the iframe's `src` in the hosting box to its URL and run the box
again. The bridge evaluates code *inside* that iframe, including pages on another localhost port;
it does not change Chromium's normal web security. A page must be visible in the Field Electron
app for these AI4 tools to reach it. For a screenshot, reveal the output area first if it is
offscreen.
