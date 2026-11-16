// AI4 chat fence previews. The transcript keeps the original fenced text; renderers only
// decorate complete code blocks after the chat has built its DOM.
const renderers = new Map();
let nextRenderId = 0;

export function registerAiChatFenceRenderer(language, render) {
    const key = String(language || "").trim().toLowerCase();
    if (!/^[a-z][a-z0-9_+-]*$/.test(key) || typeof render !== "function") {
        throw new TypeError("A fence renderer needs a language name and render function");
    }
    renderers.set(key, render);
    return () => {
        if (renderers.get(key) === render) renderers.delete(key);
    };
}

// Rendered previews by language and source. A streaming turn rebuilds its unit on every chunk; a fence
// whose source has not changed shows its diagram again at once instead of flashing back to source.
const rendered = new Map();
const RENDERED_LIMIT = 64;

function remember(key, node) {
    rendered.delete(key);
    rendered.set(key, node);
    if (rendered.size > RENDERED_LIMIT) rendered.delete(rendered.keys().next().value);
}

function install(block, preview, result, onLayoutChange) {
    preview.replaceChildren(result);
    const codeArea = block.querySelector("pre");
    codeArea.hidden = true;
    const toggle = document.createElement("button");
    toggle.className = "ai-chat-fence-source-toggle";
    toggle.type = "button";
    toggle.textContent = "Source";
    toggle.setAttribute("aria-expanded", "false");
    toggle.addEventListener("click", () => {
        const showing = codeArea.hidden;
        codeArea.hidden = !showing;
        toggle.textContent = showing ? "Hide source" : "Source";
        toggle.setAttribute("aria-expanded", String(showing));
        onLayoutChange("layout");
    });
    block.appendChild(toggle);
}

export function renderAiChatFences(root, onLayoutChange = () => {}) {
    let pending = 0;
    for (const block of root.querySelectorAll('.ai-chat-code-block[data-fence-closed="true"]')) {
        const language = (block.dataset.lang || "").toLowerCase();
        const render = renderers.get(language);
        const code = block.querySelector("pre code");
        if (!render || !code || !code.textContent?.trim()) continue;

        const source = code.textContent;
        const key = `${language}\n${source}`;
        const preview = document.createElement("div");
        preview.className = "ai-chat-fence-preview";
        preview.setAttribute("role", "img");
        preview.setAttribute("aria-label", `${language} diagram`);
        block.appendChild(preview);

        // Only from the renderer now registered for this language; a replaced one renders afresh.
        const known = rendered.get(key)?.render === render ? rendered.get(key).node : null;
        if (known) {
            // A copy: the earlier block may still be on the page (another chat, or a unit not yet removed).
            install(block, preview, known.cloneNode(true), onLayoutChange);
            continue;
        }
        pending++;
        preview.textContent = "Rendering diagram…";

        // A short delay avoids starting work for intermediate chunks; the connected check discards an
        // obsolete result.
        setTimeout(async () => {
            if (!block.isConnected || !preview.isConnected) return;
            try {
                const id = `ai4-fence-${++nextRenderId}`;
                const result = await render(source, id);
                if (!block.isConnected || !preview.isConnected) return;
                if (!(result instanceof Node)) throw new TypeError(`${language} renderer returned no DOM node`);
                remember(key, {render, node: result.cloneNode(true)});
                install(block, preview, result, onLayoutChange);
                onLayoutChange("settled");
            } catch (error) {
                if (!block.isConnected || !preview.isConnected) return;
                preview.classList.add("ai-chat-fence-error");
                preview.removeAttribute("role");
                preview.textContent = `Could not render ${language}: ${error?.message || error}`;
                onLayoutChange("settled");
            }
        }, 120);
    }
    return pending;
}
