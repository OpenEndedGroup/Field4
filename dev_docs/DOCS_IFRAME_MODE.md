# Documentation iframe integration

The message protocol and security boundary are documented at the top of
`src/commonMain/resources/docsIframeApi.js`. Layout and mode switching are owned by
`src/commonMain/resources/index.html`, `bootstrapMonaco.js`, and the
`.docs-iframe-container` rules in `field.css`.

## Where the pages come from

The command-palette action **Read the Field Documentation** (`switch-to-docs-iframe` — the id
is unchanged, because that is what a keybinding matches on) opens `/field-docs/`, Field's
own mirror of the documentation. The mirror serves **this checkout's built site
(`public/field4/`) when there is one**, and the published
`https://openendedgroup.com/field4/` when there is not. That order is deliberate twice
over: the local build is the only copy that exists offline, and it is the copy that
contains the paragraph you edited ten minutes ago — which is the point of annotating a
page in order to improve it.

Whichever copy answered, the injected panel keys its notes on the *published* URL, so a
note taken on your build and a note taken on the real site are notes on the same page.
The panel labels itself `local build` or `published` so you can tell which one you are
reading.

| variable | default | what it does |
| --- | --- | --- |
| `AFIELD_DOCS_MIRROR_SOURCE` (`-Dafield.docsMirror.source`) | unset → `auto` | `local` serves only the built site (a missing page is a 404, not a silent fetch from the internet); `upstream` restores the old always-proxy behaviour. Anything unrecognised is `auto`; the startup line prints what was resolved. |
| `AFIELD_DOCS_MIRROR_DIR` (`-Dafield.docsMirror.dir`) | `public/field4` under Field's working directory | the built site to serve. Not required to exist, and checked per request, so a docs build that finishes while Field is running is picked up by the next page load. |

Build the site with the loop in `AGENTS.md`:

```bash
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

When neither copy can answer, the pane shows a page naming both failures and that command
— the exception's *class* as well as its message, because the earlier version printed
`documentation unavailable: null` whenever the message was absent and logged nothing.

## Open and close

Code running in Field's page can choose another URL:

```javascript
window.switchToDocsIframe("/my-doc.html")
window.switchToMonaco()
```

The iframe replaces the visible Monaco surface and uses
`sandbox="allow-scripts allow-same-origin allow-forms"`. Its size follows the editor container.

## Child-page API

After a same-origin page loads, Field attempts to inject `/docsIframeApi.js`, which publishes
`window.FieldAPI`. Cross-origin access prevents Field from injecting into an external page; an
external page must explicitly load the script from the Field server if it wants the API:

```html
<script src="https://localhost:9092/docsIframeApi.js"></script>
<button onclick="FieldAPI.switchToEditor()">Back to Field</button>
```

Use the actual Field scheme, host, and port. The API currently exposes:

- `runCommand`, `runAction`
- `executeBox`, `beginBoxes`, `endBoxes`
- `selectBoxes`, `navigateToBox`, `getSelection`
- `switchToProperty`, `switchToEditor`
- `createBox`

Signatures and payloads belong in `docsIframeApi.js`; do not duplicate them here. `getSelection`
is asynchronous and takes a callback. `createBox` routes to the `new-box` palette action.

## Security and verification

Field verifies that incoming messages came from the iframe window, but it intentionally accepts any
origin loaded into that iframe. Such a page can run code and mutate the document. Load only trusted
content; `postMessage` is a transport mechanism, not an authorization boundary.

Verify changes through the browser regression suite and by loading both a same-origin page and an
explicitly instrumented cross-origin page. A normal standalone browser page has no parent Field
surface for the API to control.
