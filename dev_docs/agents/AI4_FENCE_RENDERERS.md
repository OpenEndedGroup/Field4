# AI4 fenced diagram renderers

AI4 keeps a fenced block's source in `ai4_chat_history`. The chat view can add a preview for a
complete fence without changing that transcript. `src/commonMain/resources/aiChatFenceRenderers.js`
owns the language registrar:

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

Mermaid is the first registration, in `outputareas2.js`. Its bundle lives at
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
