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

export function renderAiChatFences(root, onLayoutChange = () => {}) {
    let pending = 0;
    for (const block of root.querySelectorAll('.ai-chat-code-block[data-fence-closed="true"]')) {
        const language = (block.dataset.lang || "").toLowerCase();
        const render = renderers.get(language);
        const code = block.querySelector("pre code");
        if (!render || !code || !code.textContent?.trim()) continue;
        pending++;

        const source = code.textContent;
        const preview = document.createElement("div");
        preview.className = "ai-chat-fence-preview";
        preview.setAttribute("role", "img");
        preview.setAttribute("aria-label", `${language} diagram`);
        preview.textContent = "Rendering diagram…";
        block.appendChild(preview);

        // AI4 replaces the whole transcript as text streams in. A short delay avoids starting
        // work for intermediate chunks; the connected check discards an obsolete result.
        setTimeout(async () => {
            if (!block.isConnected || !preview.isConnected) return;
            try {
                const id = `ai4-fence-${++nextRenderId}`;
                const result = await render(source, id);
                if (!block.isConnected || !preview.isConnected) return;
                if (!(result instanceof Node)) throw new TypeError(`${language} renderer returned no DOM node`);
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
