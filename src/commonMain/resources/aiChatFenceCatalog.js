import { registerAiChatFenceRenderer } from "./aiChatFenceRenderers.js";

/** Install every renderer the shared AI4 fence catalog advertises to agents. */
export function registerAiChatFenceCatalog(catalog, loadModule = path => import(path)) {
    if (catalog?.version !== 1 || !Array.isArray(catalog.fences)) {
        throw new Error("Unsupported AI4 fence catalog");
    }
    const languages = new Set();
    const unregister = [];
    for (const spec of catalog.fences) {
        const { language, name, purpose, syntaxGuidance, example, modulePath, exportName } = spec;
        if (!/^[a-z][a-z0-9_+-]*$/.test(language || "") || languages.has(language) ||
            [name, purpose, syntaxGuidance, example].some(value => typeof value !== "string" || !value.trim()) ||
            !/^\/[A-Za-z0-9_./-]+\.(js|mjs)$/.test(modulePath || "") || modulePath.includes("..") ||
            !/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(exportName || "")) {
            unregister.forEach(remove => remove());
            throw new Error(`Invalid AI4 fence catalog entry: ${language || "(unnamed)"}`);
        }
        languages.add(language);
        unregister.push(registerAiChatFenceRenderer(language, async (source, id) => {
            const module = await loadModule(modulePath);
            const render = module[exportName];
            if (typeof render !== "function") throw new Error(`${language} renderer is unavailable`);
            return render(source, id);
        }));
    }
    return () => unregister.forEach(remove => remove());
}

export async function installAiChatFenceCatalog() {
    const response = await fetch(new URL("./ai4-fence-renderers.json", import.meta.url));
    if (!response.ok) throw new Error(`AI4 fence catalog returned HTTP ${response.status}`);
    return registerAiChatFenceCatalog(await response.json());
}
