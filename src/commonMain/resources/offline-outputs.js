/** Optional captured channels for the read-only viewer. No Field connection or original box script. */
(() => {
    let manifest = null, base = null;
    async function resolvePublication(params) {
        if (params.has('outputs') || !params.get('file')) return;
        const root = new URL('./',location.href), source = new URL(params.get('file'),root);
        if (source.origin !== root.origin || !source.pathname.startsWith(root.pathname)) return;
        const response = await fetch(new URL('offline-publications.json',root), {cache:'no-cache'});
        if (response.status === 404) return; // Older standalone distributions have no registry.
        if (!response.ok) throw new Error(`Published documents: HTTP ${response.status}`);
        const registry = await response.json();
        if (registry.version !== 1 || !registry.documents || typeof registry.documents !== 'object') throw new Error('Invalid publication registry');
        const entry = registry.documents[decodeURIComponent(source.pathname.slice(root.pathname.length))];
        if (!entry) return;
        if (!/^assets\/web_publications\/[a-z0-9_-]+\/document\/metadata\.edn$/.test(entry.file) ||
            entry.outputs !== entry.file.replace('metadata.edn','outputs.json')) throw new Error('Invalid publication location');
        params.set('file',entry.file); params.set('outputs',entry.outputs);
    }
    async function load(params) {
        const path = params.get('outputs');
        if (!path) return;
        const url = new URL(path, location.href);
        if (url.origin !== location.origin) throw new Error('Output manifest must be served beside the publication');
        const response = await fetch(url, {cache:'no-cache'});
        if (!response.ok) throw new Error(`Captured outputs: HTTP ${response.status}`);
        const data = await response.json();
        if (data.version !== 1 || !Array.isArray(data.outputs)) throw new Error('Unsupported output snapshot format');
        for (const output of data.outputs) {
            if (!output || typeof output.uid !== 'string' || typeof output.channel !== 'string' ||
                (output.path && !/^outputs\/\d+\.html$/.test(output.path)) ||
                (output.warnings && (!Array.isArray(output.warnings) || output.warnings.some(w => typeof w !== 'string')))) {
                throw new Error('Invalid output snapshot entry');
            }
        }
        manifest = data; base = new URL('../', url);
    }
    const forBox = uid => (manifest?.outputs || []).filter(output => output.uid === uid);
    function mount(box, uid) {
        const outputs = forBox(uid);
        if (!outputs.length) return;
        box.classList.add('has-published-outputs');
        const container = document.createElement('div'); container.className = 'published-outputs';
        for (const output of outputs) {
            const details = document.createElement('details'); details.className = 'published-output'; details.open = output.open;
            const summary = document.createElement('summary');
            summary.textContent = output.channel + (output.kinds?.includes('three-image') ? ' · still image' : '');
            details.append(summary);
            if (output.path && /^outputs\/\d+\.html$/.test(output.path)) {
                const frame = document.createElement('iframe'); frame.title = `Captured ${output.channel}`;
                frame.setAttribute('sandbox','allow-scripts allow-downloads');
                const url = new URL(output.path,base);
                if (manifest.capturedAt) url.searchParams.set('v',manifest.capturedAt);
                frame.src = url.href;
                const width = Math.max(160,Math.min(4096,Number(output.width) || 640));
                const height = Math.max(40,Math.min(8192,Number(output.height) || 420));
                details.style.width = `${width}px`; frame.style.height = `${height}px`;
                // Canvas transforms and overlapping boxes can leave lazy frames asleep even
                // though their open output is visible. Open panels must initialize eagerly.
                frame.loading = output.open ? 'eager' : 'lazy'; details.append(frame);
            }
            for (const warning of output.warnings || []) {
                const note = document.createElement('p'); note.className = 'published-output-warning'; note.textContent = warning;
                details.append(note);
            }
            container.append(details);
        }
        container.addEventListener('click',e => e.stopPropagation());
        container.addEventListener('mousedown',e => e.stopPropagation());
        box.append(container);
    }
    function bounds(box) {
        const outputs = forBox(box.id); const [x,y,w,h] = box.properties?.frame || [0,0,300,200];
        let width = w, height = h;
        for (const output of outputs) {
            width = Math.max(width,Math.min(4096,Number(output.width) || 640));
            height += 30 + (output.open ? Math.min(8192,Number(output.height) || 420) : 0);
        }
        return {x,y,w:width,h:height};
    }
    function info() {
        if (!manifest) return '';
        const count = manifest.outputs.filter(o => o.path).length;
        const warnings = manifest.outputs.reduce((n,o) => n + (o.warnings?.length || 0),0);
        return `${count} captured outputs${warnings ? ` · ${warnings} capture warnings` : ''}`;
    }
    const documentName = () => manifest?.documentName;
    window.FieldOfflineOutputs = Object.freeze({resolvePublication,load,forBox,mount,bounds,info,documentName});
})();
