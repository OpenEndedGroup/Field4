// A second view over AI4's harness. All durable mutations go through the server-owned garden.
export function mountConversationGarden(host, {request, chatUid, chatProperty, markdown, decorate, openBox}) {
    const el = (tag, cls = '', text) => {
        const node = document.createElement(tag); node.className = cls;
        if (text !== undefined) node.textContent = text;
        return node;
    };
    const button = (label, action, cls = '') => {
        const b = el('button', cls, label); b.type = 'button'; b.addEventListener('click', action); return b;
    };
    const bar = el('div', 'garden-bar');
    const toggle = button('Garden', () => active ? close() : open());
    toggle.setAttribute('aria-expanded', 'false');
    toggle.title = 'Branch this conversation from a paragraph or selected text';
    bar.append(toggle);
    const panel = el('section', 'conversation-garden'); panel.hidden = true;
    panel.setAttribute('aria-label', 'Conversation garden');
    const controls = el('div', 'garden-controls');
    const view = el('select'); view.setAttribute('aria-label', 'Conversation view');
    for (const [value, label] of [['reading', 'Reading'], ['outline', 'Outline']]) {
        const option = el('option', '', label); option.value = value; view.append(option);
    }
    const focus = button('Path to root', () => { focused = !focused; render(); });
    const filter = el('input'); filter.placeholder = 'Search or #tag'; filter.setAttribute('aria-label', 'Filter conversation paths');
    const refresh = button('Refresh', () => load());
    const newRoot = button('New root', () => { anchor = null; updateAnchor(); input.focus(); });
    controls.append(view, focus, filter, refresh, newRoot);
    const status = el('div', 'garden-status'); status.setAttribute('role', 'status');
    const body = el('div', 'garden-body');
    const composer = el('div', 'garden-composer');
    const target = el('div', 'garden-target');
    const mode = el('select'); mode.setAttribute('aria-label', 'Reply context');
    for (const [value, label] of [['through', 'Through this paragraph'], ['whole', 'Whole response']]) {
        const option = el('option', '', label); option.value = value; mode.append(option);
    }
    mode.addEventListener('change', () => { if (anchor) anchor.mode = mode.value; });
    const input = el('textarea'); input.rows = 3;
    input.placeholder = 'Reply here · Return sends · Option–Return queues · #tags welcome';
    input.setAttribute('aria-label', 'Garden reply');
    const actions = el('div', 'garden-actions');
    const send = button('Send', () => submit(true));
    const queue = button('Queue', () => submit(false));
    const play = button('Play queue', () => { playing = !playing; updateActions(); if (playing) advance(); });
    const selection = button('Reply to selection', () => chooseSelection()); selection.hidden = true;
    selection.addEventListener('pointerdown', event => event.preventDefault());
    actions.append(send, queue, play, selection);
    composer.append(target, mode, input, actions);
    panel.append(controls, status, body, composer);
    host.append(bar, panel);
    let active = false, owner = '', generation = 0, timer = null, garden = {nodes: [], texts: {}}, busy = false;
    let anchor = null, anchorText = '', selected = '', focused = false, playing = false, pending = false, requestPending = false;
    let savedSelection = null, signature = '', pollError = '', lastRun = '';
    const drafts = new Map();
    const text = ref => garden.texts[ref.revision] || '';
    const nodeById = id => garden.nodes.find(n => n.id === id);
    const path = id => {
        const ids = new Set();
        while (id && !ids.has(id)) { ids.add(id); id = nodeById(id)?.anchor?.node; }
        return ids;
    };
    async function call(payload) {
        const uid = owner, ticket = generation;
        const reply = await request({uid, ...payload});
        if (ticket !== generation || uid !== chatUid() || !active) return null;
        if (reply.error) throw new Error(reply.error);
        if (reply.garden) garden = reply.garden;
        busy = !!reply.busy;
        return reply;
    }
    function fail(error) { status.textContent = String(error.message || error); status.classList.add('error'); }
    async function load() {
        if (!active || requestPending) return;
        requestPending = true;
        try {
            if (await call({action: 'read', knownRevision: garden.revision})) {
                pollError = '';
                const failed = playing && nodeById(lastRun)?.status === 'failed';
                if (failed) playing = false;
                render();
                if (failed) fail(new Error('Queue paused after a failed run. Its output is retained.'));
                else if (playing) await advance();
            }
        } catch (error) {
            pollError = String(error.message || error); playing = false; fail(error); updateActions();
        } finally { requestPending = false; }
    }
    async function open() {
        active = true; owner = chatUid(); generation++; signature = '';
        host.classList.add('garden-open'); panel.hidden = false; toggle.setAttribute('aria-expanded', 'true');
        input.value = drafts.get(owner) || '';
        try { if (await call({action: 'initialize'})) { render(); await load(); } }
        catch (error) { fail(error); }
        if (active) timer = setInterval(() => { if (!pending) load(); }, 1200);
    }
    function close() {
        drafts.set(owner, input.value); active = false; playing = false; generation++;
        clearInterval(timer); timer = null; host.classList.remove('garden-open'); panel.hidden = true;
        toggle.setAttribute('aria-expanded', 'false');
    }
    function updateAnchor() {
        const n = anchor && nodeById(anchor.node);
        target.textContent = anchor ? `Reply to ${n?.prompt?.slice(0, 65) || 'conversation'}${anchor.quote ? ' · “' + anchor.quote.slice(0, 160) + '”' : ''}` : 'New conversation root';
        mode.hidden = !anchor; mode.value = anchor?.mode || 'through';
    }
    function attach(n, p, quote = '', source = text(p)) {
        anchor = {node: n.id, passage: p.id, revision: p.revision, quote: quote.slice(0, 4000), mode: mode.value};
        anchorText = source; selected = n.id; updateAnchor(); input.focus(); updateActions();
    }
    function chooseSelection() {
        const s = savedSelection; if (!s) return;
        const n = nodeById(s.node);
        if (!n) return fail(new Error('The reply source no longer exists.'));
        attach(n, s.ref, s.quote, s.source); selection.hidden = true;
    }
    body.addEventListener('pointerup', () => {
        const selectedText = window.getSelection();
        const start = selectedText?.anchorNode?.parentElement?.closest('[data-garden-passage]');
        const end = selectedText?.focusNode?.parentElement?.closest('[data-garden-passage]');
        const quote = selectedText?.toString().trim();
        savedSelection = start && start === end && body.contains(start) && quote
            ? {node: start.dataset.gardenNode, passage: start.dataset.gardenPassage, revision: start.dataset.revision, quote, ref: start.__gardenRef, source: start.__gardenSource} : null;
        selection.hidden = !savedSelection;
    });
    function updateActions() {
        send.disabled = pending; queue.disabled = pending;
        const count = garden.nodes.filter(n => n.status === 'queued').length;
        play.textContent = playing ? `Pause queue (${count})` : `Play queue (${count})`;
        play.disabled = pending || (!playing && count === 0);
        focus.disabled = !selected;
        focus.setAttribute('aria-pressed', String(focused));
    }
    async function submit(dispatch) {
        if (pending || !input.value.trim()) return;
        const prompt = input.value.trim(), oldIds = new Set(garden.nodes.map(n => n.id));
        pending = true; updateActions();
        try {
            if (!await call({action: 'enqueue', prompt, anchor, anchorText, property: chatProperty()})) return;
            input.value = ''; drafts.delete(owner);
            const added = garden.nodes.find(n => !oldIds.has(n.id));
            selected = added?.id || selected; render();
            if (dispatch && added) {
                if (busy) { status.textContent = 'Saved in queue · an agent is already running in this box'; }
                else { await run(added.id); }
            } else status.textContent = 'Reply saved in queue';
        } catch (error) { fail(error); }
        finally { pending = false; updateActions(); }
    }
    async function run(id) {
        if (await call({action: 'run', node: id})) { lastRun = id; render(); }
    }
    async function advance() {
        if (!playing || busy || pending || pollError) return;
        const next = garden.nodes.find(n => n.status === 'queued');
        if (!next) { playing = false; updateActions(); return; }
        pending = true; updateActions();
        try { await run(next.id); }
        catch (error) { playing = false; fail(error); }
        finally { pending = false; updateActions(); }
    }
    function visibleIds() {
        let allowed = focused && selected ? path(selected) : new Set(garden.nodes.map(n => n.id));
        const query = filter.value.trim().toLowerCase();
        if (query) {
            const matching = garden.nodes.filter(n => query.startsWith('#') ? n.tags.includes(query.slice(1)) :
                (n.prompt + '\n' + n.passages.map(text).join('\n')).toLowerCase().includes(query));
            const included = new Set();
            // Tagged entries match all descendant paths that pass through them, plus their ancestry.
            for (const n of garden.nodes) if (matching.some(m => path(n.id).has(m.id))) for (const id of path(n.id)) included.add(id);
            allowed = new Set([...allowed].filter(id => included.has(id)));
        }
        return allowed;
    }
    function renderEdit(edit) {
        const card = el('details', 'garden-edit'); card.dataset.edit = edit.id;
        card.append(el('summary', '', `${edit.name} / ${edit.property} · code change`));
        const choices = el('div', 'garden-actions'), code = el('pre');
        const before = garden.texts[edit.before] || '', after = garden.texts[edit.after] || '';
        const a = before.split('\n'), b = after.split('\n'); let start = 0, end = 0;
        while (start < a.length && start < b.length && a[start] === b[start]) start++;
        while (end < a.length - start && end < b.length - start && a[a.length-1-end] === b[b.length-1-end]) end++;
        const changes = [...a.slice(start, a.length-end).map(l => '- ' + l), ...b.slice(start, b.length-end).map(l => '+ ' + l)].join('\n');
        code.textContent = changes;
        choices.append(button('Change', () => code.textContent = changes), button('Then', () => code.textContent = after),
            button('Now ↗', () => openBox(edit.box, edit.property)));
        card.append(choices, code); return card;
    }
    function renderNode(n, allowed, ancestors = new Set()) {
        if (ancestors.has(n.id)) return el('p', '', 'Invalid conversation cycle');
        const seen = new Set(ancestors); seen.add(n.id);
        const article = el('article', 'garden-node'); article.dataset.gardenNode = n.id;
        const heading = el('div', 'garden-node-heading');
        heading.append(el('span', '', n.status === 'imported' ? 'Imported conversation' : n.status),
            button('Focus', () => { selected = n.id; focused = true; view.value = 'reading'; render(); }));
        if (n.status === 'queued') heading.append(button('Run', async () => {
            if (pending) return; pending = true; updateActions();
            try { await run(n.id); } catch (error) { fail(error); } finally { pending = false; updateActions(); }
        }));
        if (n.tags.length) heading.append(el('span', 'garden-tags', n.tags.map(t => '#' + t).join(' ')));
        article.append(heading);
        if (n.prompt) { const prompt = el('div', 'garden-human'); prompt.textContent = n.prompt; article.append(prompt); }
        if (view.value === 'outline') {
            const summary = el('p', 'garden-outline-summary', n.passages.filter(p => p.role === 'ai').map(text).join(' ').slice(0, 220));
            article.append(summary);
        } else {
            const children = garden.nodes.filter(c => c.anchor?.node === n.id && allowed.has(c.id));
            let passages = n.passages;
            if (focused && selected !== n.id) {
                const child = children.find(c => path(selected).has(c.id));
                if (child?.anchor?.mode === 'through') {
                    const i = passages.findIndex(p => p.id === child.anchor.passage);
                    if (i >= 0) passages = passages.slice(0, i + 1).map(p => p.id === child.anchor.passage ? {...p, revision: child.anchor.revision} : p);
                }
            }
            const renderedChildren = new Set();
            for (const p of passages) {
                const wrap = el('div', `garden-passage ${p.role === 'you' ? 'garden-human' : p.role.startsWith('progress') ? 'garden-progress' : 'garden-agent'}`);
                wrap.__gardenRef = p; wrap.__gardenSource = text(p);
                wrap.dataset.gardenPassage = p.id; wrap.dataset.gardenNode = n.id; wrap.dataset.revision = p.revision;
                if (p.role.startsWith('progress')) {
                    const folded = el('details'); folded.append(el('summary', '', text(p).split('\n')[0]));
                    const content = el('div'); content.innerHTML = markdown(text(p).split('\n').slice(1).join('\n')); folded.append(content); wrap.append(folded);
                } else {
                    if (p.model) wrap.append(el('div', 'garden-model', `${p.model.responseModel || p.model.requestedModel} · ${p.model.provider}`));
                    const content = el('div', 'garden-prose'); content.innerHTML = markdown(text(p)); wrap.append(content);
                }
                const source = text(p);
                const reply = button('↳ Reply', () => attach(n, p, '', source), 'garden-reply');
                reply.setAttribute('aria-label', 'Reply to paragraph'); wrap.append(reply); article.append(wrap);
                for (const child of children.filter(c => c.anchor.passage === p.id)) {
                    const branch = el('div', 'garden-branch'); branch.append(renderNode(child, allowed, seen)); article.append(branch); renderedChildren.add(child.id);
                }
            }
            for (const edit of n.edits) article.append(renderEdit(edit));
            for (const child of children.filter(c => !renderedChildren.has(c.id))) {
                const branch = el('div', 'garden-branch'); branch.append(el('div', 'garden-status', 'Reply to an earlier revision'), renderNode(child, allowed, seen)); article.append(branch);
            }
        }
        if (n.error) article.append(el('p', 'garden-error', n.error));
        return article;
    }
    function render() {
        if (!active) return;
        updateActions(); updateAnchor();
        const nextSignature = `${garden.revision}|${view.value}|${focused}|${selected}|${filter.value}`;
        if (signature === nextSignature) return;
        // Do not replace a passage while the reader is selecting its text.
        const s = window.getSelection();
        if (s && !s.isCollapsed && body.contains(s.anchorNode)) return;
        signature = nextSignature;
        const scrollTop = body.scrollTop, openEdits = new Set([...body.querySelectorAll('.garden-edit[open]')].map(d => d.dataset.edit));
        const allowed = visibleIds(); body.replaceChildren();
        if (view.value === 'outline') {
            for (const n of garden.nodes.filter(n => allowed.has(n.id))) {
                const row = renderNode(n, allowed); row.style.marginLeft = Math.min(path(n.id).size - 1, 6) * 14 + 'px'; body.append(row);
            }
        } else for (const n of garden.nodes.filter(n => !n.anchor && allowed.has(n.id))) body.append(renderNode(n, allowed));
        for (const d of body.querySelectorAll('.garden-edit')) if (openEdits.has(d.dataset.edit)) d.open = true;
        if (!body.children.length) body.append(el('p', 'garden-empty', garden.nodes.length ? 'No matching paths.' : 'A conversation can begin anywhere. Write the first entry below.'));
        decorate(body); body.scrollTop = scrollTop;
        status.classList.remove('error');
        status.textContent = busy ? 'Agent running · replies can be queued anywhere' : `${garden.nodes.length} entries · shared live boxes`;
    }
    view.addEventListener('change', () => { signature = ''; render(); });
    filter.addEventListener('input', () => render());
    input.addEventListener('keydown', event => {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
            event.preventDefault(); submit(!event.altKey);
        }
    });
    updateAnchor();
    return {reset() { close(); garden = {nodes: [], texts: {}}; anchor = null; selected = ''; focused = false; filter.value = ''; }};
}
