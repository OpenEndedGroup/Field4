# AI4 fenced diagram renderers

AI4 keeps a fenced block's source in `ai4_chat_history`. The chat view can add a preview for a
complete fence without changing that transcript. The shared
`src/commonMain/resources/ai4-fence-renderers.json` catalog names the supported languages and
provides each one's purpose, syntax guidance, example, local renderer module and export name.
The JVM reads it into AI4's chat instructions; the browser reads it to register preview
renderers. Adding a catalog entry therefore advertises a new fence to AI4 and installs its
browser renderer together.

For a new kind of fence, add its catalog entry and a local module exporting a function that
accepts `(source, id)` and returns a DOM node (or a promise of one). The browser's
`aiChatFenceCatalog.js` imports the module only when that fence appears. The lower-level
`src/commonMain/resources/aiChatFenceRenderers.js` registrar also supports temporary
registrations for tests and page-local extensions:

```js
import { registerAiChatFenceRenderer } from "./aiChatFenceRenderers.js";

registerAiChatFenceRenderer("example", async (source, id) => {
    const result = document.createElement("div");
    result.textContent = source;
    return result;
});
```

The renderer receives the escaped fence's text and a unique DOM id, and returns a DOM node.
Registration returns a function that removes that registration. Chat code calls registered
renderers only after the closing fence arrives. A successful preview has a **Source** toggle and
the existing copy button still copies the source. On failure, the source remains visible with
an error message. The registrar discards results for chat DOM that streaming has replaced.

Mermaid is the first catalog entry. Its bundle lives at
`src/jsMain/resources/mermaid/field-mermaid.js` and is imported only for a Mermaid fence. To
rebuild the committed offline asset after changing its pinned package or wrapper:

```bash
cd tools/mermaid-bundle
npm ci
npm run build
```

The wrapper uses Mermaid's `strict` security level, disables HTML labels, caps text and edge
counts, and renders with a dark monochrome theme. It never loads from a CDN. The browser
regression `ai-chat-fence-renderers` exercises registration, streaming, Mermaid rendering,
source toggling, and syntax-error fallback in Field's real chat view.
