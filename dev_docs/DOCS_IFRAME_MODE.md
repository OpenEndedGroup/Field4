# Documentation iframe integration

The message protocol and security boundary are documented at the top of
`src/commonMain/resources/docsIframeApi.js`. Layout and mode switching are owned by
`src/commonMain/resources/index.html`, `bootstrapMonaco.js`, and the
`.docs-iframe-container` rules in `field.css`.

## Open and close

The command-palette action **Switch to Documentation Iframe** opens the fixed default URL
`https://openendedgroup.com/field4/`. Code running in Field's page can choose another URL:

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
