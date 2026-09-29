/** Opt-in tools for a viewer. Applications own their tools; the viewer owns their lifecycle. */
export function createViewerTools({host, context, requestRender}) {
    const tools = new Map();
    let refreshing = false;
    const clone = value => {
        const json = JSON.stringify(value, (_, v) => {
            if (typeof v === 'number' && !Number.isFinite(v)) throw new Error('Tool state must be finite JSON');
            if (typeof v === 'function' || typeof v === 'undefined') throw new Error('Tool state must be JSON');
            return v;
        });
        if (json.length > 32000) throw new Error('Tool state exceeds 32 KB');
        return JSON.parse(json);
    };
    function snapshot() {
        return Object.fromEntries([...tools].filter(([, t]) => t.tool.snapshot).map(([id, t]) => [id, clone(t.tool.snapshot())]));
    }
    function validate(states = {}) {
        if (!states || typeof states !== 'object' || Array.isArray(states)) throw new Error('Invalid viewer tool states');
        for (const [id, state] of Object.entries(states)) {
            const entry = tools.get(id);
            if (!entry?.tool.restore) throw new Error('This view needs the tool: ' + id);
            entry.tool.validate(clone(state));
        }
    }
    function restore(states = {}) {
        validate(states);
        // An older view has no tool state: reset active tools to their registration state.
        for (const [id, entry] of tools) if (entry.tool.restore)
            entry.tool.restore(clone(Object.hasOwn(states, id) ? states[id] : entry.initial));
    }
    function refresh() {
        if (refreshing) return;
        refreshing = true;
        try { for (const entry of tools.values()) entry.tool.refresh?.(); }
        finally { refreshing = false; }
    }
    function register(tool) {
        if (!tool || !/^[a-zA-Z][\w.-]{0,79}$/.test(tool.id) || tools.has(tool.id)) throw new Error('Viewer tool needs a unique id');
        const stateful = ['snapshot', 'validate', 'restore'].some(key => tool[key] != null);
        if (stateful && !['snapshot', 'validate', 'restore'].every(key => typeof tool[key] === 'function'))
            throw new Error('Stateful tools need snapshot, validate and restore');
        const element = document.createElement('div'); element.className = 'hud-group viewer-tool';
        element.dataset.tool = tool.id; host.appendChild(element);
        const ctx = context(element), entry = {tool, initial: null};
        let disposed = false;
        function dispose() {
            if (disposed) return;
            disposed = true;
            try { tool.dispose?.(); }
            finally { tools.delete(tool.id); ctx.release(); element.remove(); requestRender(); }
        }
        try {
            tool.mount?.(Object.freeze({...ctx, element, requestRender}));
            if (stateful) { entry.initial = clone(tool.snapshot()); tool.validate(entry.initial); }
            tools.set(tool.id, entry); tool.refresh?.(); requestRender();
            return dispose;
        } catch (error) { dispose(); throw error; }
    }
    const installed = new Map();
    let previousPaths = '', synchronization = Promise.resolve();
    function sync(paths = []) {
        if (!Array.isArray(paths) || paths.some(p => typeof p !== 'string')) throw new Error('Invalid viewer tool modules');
        const next = JSON.stringify([...new Set(paths)]);
        if (next === previousPaths) return synchronization;
        previousPaths = next;
        synchronization = synchronization.catch(() => {}).then(async () => {
            for (const [path, dispose] of installed) if (!paths.includes(path)) { dispose(); installed.delete(path); }
            for (const path of paths) if (!installed.has(path)) installed.set(path, await load(path));
        });
        return synchronization;
    }
    // Loading is explicit application code. Saved annotations never import scripts.
    async function load(path) {
        const url = new URL(path, location.href);
        if (url.origin !== location.origin || !['http:', 'https:'].includes(url.protocol))
            throw new Error('Viewer tools must be served from the same origin');
        const module = await import(url.href);
        if (typeof module.default !== 'function') throw new Error('Viewer tool module must export a default factory');
        return register(module.default());
    }
    return Object.freeze({register, load, sync, snapshot, validate, restore, refresh, ids: () => [...tools.keys()]});
}
