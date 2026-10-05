// Read-only history browser. The host owns chat identity and draft insertion.
export function mountStateTreeBrowser(host, {request, chatUid, insertReference}) {
    const el = (tag, cls, text) => {
        const node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text !== undefined) node.textContent = text;
        return node;
    };
    const button = (text, action) => {
        const b = el('button', '', text); b.type = 'button'; b.addEventListener('click', action); return b;
    };
    const bar = el('div', 'state-tree-toolbar');
    bar.hidden = true;
    const toggle = button('States', () => panel.hidden ? open() : close());
    toggle.setAttribute('aria-expanded', 'false');
    bar.append(toggle, el('span', 'state-tree-caption', 'Browse history · reference in chat'));
    const panel = el('section', 'state-tree-browser');
    panel.hidden = true;
    panel.setAttribute('aria-label', 'State history browser');
    const controls = el('div', 'state-tree-controls');
    const sources = el('select'); sources.setAttribute('aria-label', 'State history');
    const refresh = button('Refresh', () => open());
    const dismiss = button('Close', close);
    controls.append(sources, refresh, dismiss);
    const status = el('p', 'state-tree-status'); status.setAttribute('role', 'status');
    const body = el('div', 'state-tree-body');
    const graph = el('div', 'state-tree-graph'); graph.setAttribute('aria-label', 'Saved states');
    const inspector = el('div', 'state-tree-inspector');
    const title = el('h3');
    const identity = el('p', 'state-tree-identity');
    const detail = el('pre', 'state-tree-detail');
    const actions = el('div', 'state-tree-actions');
    const reference = button('Reference in chat', () => putInChat(false));
    const pin = button('Keep for comparison', () => { pinned = selected; updateActions(); });
    const compare = button('Discuss differences', () => putInChat(true));
    const unpin = button('Clear comparison', () => { pinned = null; updateActions(); });
    const pinnedLabel = el('p', 'state-tree-pinned');
    actions.append(reference, pin, compare, unpin);
    inspector.append(title, identity, actions, pinnedLabel, detail);
    const paging = el('div', 'state-tree-paging');
    const newer = button('← Newer', () => loadTree(Math.max(0, offset - 400)));
    const older = button('Older →', () => loadTree(offset + 400));
    paging.append(newer, older);
    body.append(graph, inspector, paging);
    panel.append(controls, status, body);
    host.append(bar, panel);
    let generation = 0, selectionGeneration = 0, owner = '', offset = 0;
    let selected = null, pinned = null;
    let availabilityGeneration = 0, availabilityRequest = null, checkedAt = 0, hadProvider = false;
    async function refreshAvailability(force = false) {
        if (!force && (availabilityRequest || Date.now() - checkedAt < 2000)) return availabilityRequest;
        const uid = chatUid(), ticket = ++availabilityGeneration;
        checkedAt = Date.now();
        const pending = (async () => {
            try {
                const data = await request({uid});
                if (ticket !== availabilityGeneration || uid !== chatUid()) return;
                // Ordinary AI4 conversations have history even when the document does not use state.
                const available = !data.error && (data.sources || []).some(s => !s.id.endsWith('/conversation'));
                const lostProvider = hadProvider && !available;
                hadProvider = available;
                bar.hidden = !available;
                if (lostProvider && !panel.hidden) { close(); pinned = null; resetSelection(); }
            } catch (_) {
                if (ticket === availabilityGeneration && uid === chatUid()) bar.hidden = true;
            }
        })();
        availabilityRequest = pending;
        try { await pending; } finally { if (ticket === availabilityGeneration) availabilityRequest = null; }
    }
    function updateActions() {
        reference.disabled = !selected;
        pin.disabled = !selected;
        compare.disabled = !selected || !pinned || selected.key === pinned.key;
        unpin.hidden = !pinned;
        pinnedLabel.hidden = !pinned;
        pinnedLabel.textContent = pinned ? `Comparison A: ${pinned.title}` : '';
    }
    function resetSelection() {
        selected = null; selectionGeneration++;
        title.textContent = ''; identity.textContent = '';
        detail.textContent = '';
        updateActions();
    }
    function close() {
        generation++; selectionGeneration++;
        panel.hidden = true; toggle.setAttribute('aria-expanded', 'false'); toggle.focus();
    }
    function valid(ticket) { return ticket === generation && owner === chatUid() && !panel.hidden; }
    async function open(target = null) {
        const previous = sources.value;
        if (owner !== chatUid()) pinned = null;
        owner = chatUid(); panel.hidden = false; toggle.setAttribute('aria-expanded', 'true');
        resetSelection(); graph.replaceChildren(); sources.replaceChildren();
        status.textContent = 'Reading saved histories…';
        sources.disabled = true; newer.disabled = older.disabled = true;
        const ticket = ++generation;
        try {
            const data = await request({uid: owner});
            if (!valid(ticket)) return;
            if (data.error) throw new Error(data.error);
            for (const source of data.sources || []) {
                const option = el('option', '', source.label); option.value = source.id; sources.append(option);
            }
            sources.disabled = false;
            if (![...sources.options].length) {
                status.textContent = 'No recorded states yet. AI4 turns appear here when saved; document providers can expose their histories too.';
                newer.disabled = older.disabled = true; return;
            }
            if (target && ![...sources.options].some(o => o.value === target.source)) throw new Error("History provider is unavailable. Refresh the tree.");
            sources.value = target ? target.source : [...sources.options].some(o => o.value === previous) ? previous
                : [...sources.options].find(o => o.value === `${owner}/conversation`)?.value || sources.options[0].value;
            if (!await loadTree(target?.offset || 0)) return false;
            if (target?.node) {
                const row = [...graph.querySelectorAll('.state-tree-node')].find(r => r.dataset.stateId === target.node);
                if (!row) { status.textContent = 'Saved state is no longer on this page. Refresh the tree.'; return false; }
                row.click();
                // The caller awaits the same read used by a human selection.
                if (!await pendingSelection) return false;
                row.scrollIntoView({block:'nearest', inline:'nearest'});
            }
            return true;
        } catch (e) { if (valid(ticket)) status.textContent = e.message; return false; }
    }
    async function loadTree(nextOffset) {
        const ticket = ++generation; resetSelection(); graph.replaceChildren();
        status.textContent = 'Reading saved states…'; offset = nextOffset; newer.disabled = older.disabled = true;
        try {
            const data = await request({uid: owner, source: sources.value, offset});
            if (!valid(ticket)) return;
            if (data.error) throw new Error(data.error);
            const nodes = data.tree?.nodes || [];
            const total = data.total ?? nodes.length;
            status.textContent = `${total} saved state${total === 1 ? '' : 's'}. ${data.tree?.note || ''}`;
            if (total > nodes.length) status.textContent += ` Showing ${total - offset - nodes.length + 1}–${total - offset}. Parents outside this page are marked.`;
            newer.disabled = offset === 0; older.disabled = offset + nodes.length >= total;
            draw(nodes, data.tree?.current);
            if (!nodes.length) graph.append(el('p', '', 'No saved states in this history.'));
            return true;
        } catch (e) { if (valid(ticket)) status.textContent = e.message; return false; }
    }
    let pendingSelection = Promise.resolve(false);
    function draw(nodes, current) {
        // One lane per fork, reusing the parent's lane for its first child. IDs, never row numbers, select states.
        const lane = new Map(), used = new Set(); let nextLane = 0;
        for (const n of nodes) {
            const parent = n.parents?.find(p => lane.has(p));
            if (parent && !used.has(parent)) { lane.set(n.id, lane.get(parent)); used.add(parent); }
            else lane.set(n.id, nextLane++);
        }
        const width = Math.max(36, nextLane * 18 + 18), rowHeight = 43;
        const canvas = el('div', 'state-tree-rows'); canvas.style.minWidth = `${width + 200}px`;
        const ns = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(ns, 'svg');
        svg.setAttribute('width', width); svg.setAttribute('height', nodes.length * rowHeight);
        svg.setAttribute('aria-hidden', 'true'); canvas.append(svg);
        const positions = new Map(nodes.map((n, i) => [n.id, {x: lane.get(n.id) * 18 + 12, y: i * rowHeight + 21}]));
        for (const n of nodes) {
            const point = positions.get(n.id);
            for (const parent of n.parents || []) {
                const from = positions.get(parent);
                if (!from) continue;
                const path = document.createElementNS(ns, 'path');
                path.setAttribute('d', `M${from.x} ${from.y} C${from.x} ${point.y - 21},${point.x} ${point.y - 21},${point.x} ${point.y}`);
                svg.prepend(path);
            }
            const dot = document.createElementNS(ns, 'circle');
            dot.setAttribute('cx', point.x); dot.setAttribute('cy', point.y); dot.setAttribute('r', n.id === current ? 5 : 3);
            dot.classList.toggle('is-current', n.id === current);
            svg.append(dot);
            const row = button('', () => { pendingSelection = select(n, row); });
            row.className = 'state-tree-node'; row.dataset.stateId = n.id;
            row.title = `${n.title || n.id}${n.created ? ` · ${n.created}` : ''}`;
            row.style.paddingLeft = `${width + 5}px`; row.setAttribute('aria-pressed', 'false');
            const caption = el('span', 'state-tree-node-title', n.title || n.id);
            const meta = el('small', '', `${n.id === current ? 'current · ' : ''}${n.kind} · ${n.id.slice(0, 12)}${(n.parents || []).some(p => !positions.has(p)) ? ' · earlier parent' : ''}`);
            row.append(caption, meta); canvas.append(row);
        }
        graph.replaceChildren(canvas);
        const currentRow = [...canvas.querySelectorAll('.state-tree-node')].find(row => row.dataset.stateId === current);
        graph.scrollTop = currentRow ? Math.max(0, currentRow.offsetTop - graph.clientHeight / 2) : 0;
    }
    async function select(n, row) {
        const ticket = generation, pick = ++selectionGeneration, source = sources.value;
        selected = null; updateActions();
        graph.querySelectorAll('.state-tree-node').forEach(b => b.setAttribute('aria-pressed', String(b === row)));
        title.textContent = n.title || n.id; identity.textContent = n.id;
        detail.textContent = 'Reading this saved state…';
        try {
            const data = await request({uid: owner, source, node: n.id});
            if (!valid(ticket) || pick !== selectionGeneration) return;
            if (data.error) throw new Error(data.error);
            detail.textContent = data.detail;
            selected = {key: `${source}/${n.id}`, source, id: n.id, title: n.title, reference: data.reference};
            updateActions();
            return true;
        } catch (e) { if (valid(ticket) && pick === selectionGeneration) detail.textContent = e.message; return false; }
    }
    async function putInChat(comparison) {
        const choices = comparison ? [pinned, selected] : [selected];
        if (choices.some(c => !c) || owner !== chatUid()) return;
        const ticket = generation, pick = selectionGeneration;
        reference.disabled = compare.disabled = true;
        try {
            // Re-resolve IDs: a pruned provider must not silently cite a different state or stale row.
            const resolved = await Promise.all(choices.map(c => request({uid: owner, source: c.source, node: c.id})));
            if (!valid(ticket) || pick !== selectionGeneration) return;
            const failure = resolved.find(r => r.error); if (failure) throw new Error(failure.error);
            insertReference((comparison ? 'Compare these two saved states. Explain the changes and their implications.\n\n' : '') +
                resolved.map((r, i) => `${comparison ? `State ${i ? 'B' : 'A'}\n` : ''}${r.reference}`).join('\n\n'));
            status.textContent = 'Reference added to your draft. Add your question, then send.';
        } catch (e) { if (valid(ticket)) status.textContent = e.message; }
        finally { if (valid(ticket)) updateActions(); }
    }
    sources.addEventListener('change', () => loadTree(0));
    panel.addEventListener('keydown', e => { if (e.key === 'Escape') { e.stopPropagation(); close(); } });
    host.addEventListener('pointerenter', () => refreshAvailability());
    host.addEventListener('focusin', () => refreshAvailability());
    updateActions();
    refreshAvailability(true);
    return {open, refreshAvailability, reveal(source, node, offset = 0) { return open({source, node, offset}); }, reset() { bar.hidden = true; hadProvider = false; close(); pinned = null; resetSelection(); refreshAvailability(true); }};
}
