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
    const view = button('Reading', () => {
        view.value = view.value === 'reading' ? 'outline' : 'reading'; signature = ''; render();
    });
    view.value = 'reading'; view.setAttribute('aria-label', 'Conversation view');
    view.title = 'Switch between Reading and Outline';
    const focus = button('Path to root', () => { focused = !focused; render(); });
    const filter = el('input'); filter.placeholder = 'Search or #tag'; filter.setAttribute('aria-label', 'Filter conversation paths');
    const refresh = button('Refresh', () => load());
    const newRoot = button('New root', () => { anchor = null; composer.hidden = false; signature = ''; render(); input.focus(); });
    const tools = button('', () => {
        showTools = !showTools; host.classList.toggle('garden-show-tools', showTools);
        tools.setAttribute('aria-pressed', String(showTools));
    }, 'garden-tools-toggle');
    tools.innerHTML = '<svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M12 3a5 5 0 0 0-6 6L2 13a2 2 0 0 0 3 3l4-4a5 5 0 0 0 6-6l-3 3-3-3z"/></svg>';
    tools.setAttribute('aria-label', 'Show tool activity'); tools.setAttribute('aria-pressed', 'false');
    tools.title = 'Show or hide tool activity';
    controls.append(view, focus, filter, refresh, newRoot, tools);
    controls.hidden = true;
    bar.append(controls);
    const status = el('div', 'garden-status'); status.setAttribute('role', 'status'); status.hidden = true;
    const body = el('div', 'garden-body');
    const entries = el('div', 'garden-entries');
    const composer = el('div', 'garden-composer');
    const target = el('div', 'garden-target');
    const input = el('textarea'); input.rows = 1;
    input.placeholder = '>';
    input.setAttribute('aria-label', 'Garden reply');
    const menu = el('div', 'garden-dispatch-menu'); menu.hidden = true;
    menu.tabIndex = -1;
    menu.setAttribute('role', 'menu'); menu.setAttribute('aria-label', 'Reply options');
    composer.append(target, input);
    document.body.append(menu);
    body.append(entries, composer);
    panel.append(status, body);
    body.addEventListener('click', event => {
        if (event.target === body || event.target === composer) { composer.hidden = false; input.focus(); }
    });
    host.classList.add('garden-mounted');
    host.prepend(bar); host.append(panel);
    let active = false, owner = '', generation = 0, timer = null, garden = {nodes: [], texts: {}}, busy = false;
    let anchor = null, anchorText = '', selected = '', focused = false, playing = false, pending = false, requestPending = false;
    let signature = '', pollError = '', lastRun = '', showTools = false;
    const foldedBranches = new Set();
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
    function setStatus(message, error = false) { status.textContent = message; status.hidden = !message; status.classList.toggle('error', error); }
    function fail(error) { setStatus(String(error.message || error), true); }
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
        host.classList.add('garden-open'); panel.hidden = false; controls.hidden = false; toggle.setAttribute('aria-expanded', 'true');
        input.value = drafts.get(owner) || '';
        try { if (await call({action: 'initialize'})) { render(); await load(); } }
        catch (error) { fail(error); }
        if (active) timer = setInterval(() => { if (!pending) load(); }, 1200);
    }
    function close() {
        drafts.set(owner, input.value); active = false; playing = false; generation++;
        closeMenu();
        clearInterval(timer); timer = null; host.classList.remove('garden-open'); panel.hidden = true; controls.hidden = true;
        toggle.setAttribute('aria-expanded', 'false');
    }
    const sourcePassage = () => anchor && nodeById(anchor.node)?.passages.find(p => p.id === anchor.passage);
    function updateAnchor() {
        const p = sourcePassage();
        const snippet = anchor?.quote || (p ? text(p) : anchorText);
        target.textContent = anchor ? `Reply to ${p?.role === 'you' ? 'human' : 'agent'} · “${snippet.slice(0, 120)}”` : '';
        target.hidden = !anchor;
        composer.classList.toggle('is-anchored', !!anchor);
        input.placeholder = '>';
    }
    function sizeInput() {
        input.style.height = 'auto';
        input.style.height = Math.max(38, input.scrollHeight) + 'px';
    }
    function placeComposer() {
        let source = null;
        if (anchor) source = [...entries.querySelectorAll('[data-garden-passage]')].find(e => e.dataset.gardenNode === anchor.node && e.dataset.gardenPassage === anchor.passage);
        if (source) source.after(composer);
        else body.append(composer);
        updateAnchor();
    }
    function attach(n, p, quote = '', source = text(p), quoteStart = -1, mode = 'through') {
        anchor = {node: n.id, passage: p.id, revision: p.revision, quote: quote.slice(0, 4000), mode, quoteStart, quoteEnd: quoteStart < 0 ? -1 : quoteStart + Math.min(quote.length, 4000)};
        anchorText = source; selected = n.id;
        composer.hidden = false; placeComposer(); input.focus(); sizeInput(); updateActions();
    }
    body.addEventListener('pointerup', event => {
        if (branchDrag || event.metaKey || event.button !== 0 || event.target.closest('button, textarea, select, summary, a')) return;
        const selection = window.getSelection();
        const start = selection?.anchorNode?.parentElement?.closest('[data-garden-passage]');
        const end = selection?.focusNode?.parentElement?.closest('[data-garden-passage]');
        const quote = selection?.toString().trim();
        if (quote) {
            if (!start || !end || start.dataset.gardenNode !== end.dataset.gardenNode || !body.contains(start) || !body.contains(end)) return;
            const range = selection.getRangeAt(0);
            const sourceWrap = (range.endContainer.nodeType === Node.ELEMENT_NODE ? range.endContainer : range.endContainer.parentElement).closest('[data-garden-passage]');
            if (!sourceWrap) return;
            const prose = sourceWrap.querySelector('.garden-prose'); if (!prose) return;
            let offset = -1;
            if (start === end) {
                const prefix = range.cloneRange(); prefix.selectNodeContents(prose); prefix.setEnd(range.startContainer, range.startOffset);
                const raw = selection.toString(); offset = prefix.toString().length + raw.length - raw.trimStart().length;
            }
            selection.removeAllRanges();
            attach(nodeById(sourceWrap.dataset.gardenNode), sourceWrap.__gardenRef, quote, sourceWrap.__gardenSource, offset);
            return;
        }
        const passage = event.target.closest('[data-garden-passage]');
        if (!passage || !body.contains(passage) || !passage.querySelector('.garden-prose')) return;
        const n = nodeById(passage.dataset.gardenNode), p = passage.__gardenRef;
        const wholeResponse = p.role === 'ai';
        const last = wholeResponse ? n.passages.filter(p => p.role === 'ai').at(-1) : p;
        attach(n, last || p, '', text(last || p), -1, wholeResponse ? 'whole' : 'through');
    });
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const motionOverlays = new Set();
    const blockKey = element => element.matches('.garden-branch > summary') ? `fold:${element.parentElement.dataset.branch}` : element.dataset.gardenPassage ? `${element.dataset.gardenNode}:${element.dataset.gardenPassage}` :
        element.dataset.edit ? `edit:${element.dataset.edit}` : `running:${element.closest('article')?.dataset.gardenNode}`;
    function textBlocks() {
        return [...entries.querySelectorAll('.garden-passage, .garden-progress, .garden-edit, .garden-running, .garden-branch > summary')]
            .filter(e => {
                const parent = e.matches('.garden-branch > summary') ? e.parentElement.parentElement : e.parentElement;
                return !parent.closest('details:not([open])') && e.getClientRects().length && e.getBoundingClientRect().height > 0;
            });
    }
    function captureBlocks() {
        if (reduceMotion.matches) return new Map();
        return new Map(textBlocks().map(element => [blockKey(element), {element, rect: element.getBoundingClientRect()}]));
    }
    function animateBlocks(before) {
        if (reduceMotion.matches) return;
        const after = new Map(textBlocks().map(element => [blockKey(element), element]));
        const bodyRect = body.getBoundingClientRect(), scale = bodyRect.width / body.offsetWidth || 1;
        // Measure the destination layout without an earlier FLIP still offsetting the same row.
        for (const element of after.values()) for (const animation of element.getAnimations()) {
            if (animation.effect?.getKeyframes().some(frame => frame.transform !== undefined)) animation.cancel();
        }
        for (const [key, element] of after) {
            const old = before.get(key);
            if (!old) element.animate([{opacity: 0}, {opacity: 1}], {duration: 190, easing: 'ease-out'});
            else {
                const rect = element.getBoundingClientRect(), dx = (old.rect.x - rect.x) / scale, dy = (old.rect.y - rect.y) / scale;
                if (Math.abs(dx) + Math.abs(dy) > .5) element.animate([
                    {transform: `translate(${dx}px, ${dy}px)`}, {transform: 'translate(0, 0)'}
                ], {duration: 240, easing: 'cubic-bezier(.2,.7,.2,1)'});
            }
        }
        for (const [key, old] of before) if (!after.has(key)) {
            if (old.element.tagName === 'SUMMARY') continue;
            const branch = old.element.closest('.garden-branch');
            if (old.element.__gardenRef?.role === 'you' && branch && entries.querySelector(`[data-branch="${branch.dataset.branch}"]:not([open])`)) continue;
            const ghost = old.element.cloneNode(true);
            ghost.setAttribute('aria-hidden', 'true'); ghost.classList.add('garden-fading-block');
            Object.assign(ghost.style, {position:'absolute', boxSizing:'border-box', pointerEvents:'none', margin:'0',
                left:`${(old.rect.x-bodyRect.x)/scale + body.scrollLeft}px`, top:`${(old.rect.y-bodyRect.y)/scale + body.scrollTop}px`,
                width:`${old.rect.width/scale}px`, height:`${old.rect.height/scale}px`, zIndex:'5'});
            body.append(ghost); motionOverlays.add(ghost);
            ghost.animate([{opacity: 1}, {opacity: 0}], {duration: 150, easing: 'ease-out', fill: 'forwards'}).finished
                .catch(() => {}).finally(() => { ghost.remove(); motionOverlays.delete(ghost); });
        }
    }
    function toggleBranch(branch) {
        const before = captureBlocks(); branch.open = !branch.open; branch.__syncFold?.(); animateBlocks(before);
    }
    let branchDrag = null, suppressedDisclosure = '', suppressTimer = null;
    function dropCandidate(x, y, id) {
        const passage = document.elementsFromPoint(x, y).map(e => e.closest?.('[data-garden-passage]'))
            .find(e => e && entries.contains(e) && e.querySelector('.garden-prose'));
        if (!passage || path(passage.dataset.gardenNode).has(id)) return null;
        const n = nodeById(passage.dataset.gardenNode), p = passage.__gardenRef, rect = passage.getBoundingClientRect();
        if (p.id === n.id + ':request' && (y < rect.top + rect.height * .25 || y > rect.bottom - rect.height * .25)) {
            const before = y < rect.top + rect.height * .25;
            const siblings = garden.nodes.filter(s => s.id !== id && s.anchor?.node === n.anchor?.node && s.anchor?.passage === n.anchor?.passage);
            const next = siblings[siblings.findIndex(s => s.id === n.id) + 1];
            const branch = passage.closest('.garden-branch'), bounds = (branch || passage.closest('article')).getBoundingClientRect();
            return {anchor:n.anchor || null, beforeNode:before ? n.id : next?.id || '', rect:bounds, line:before ? bounds.top : bounds.bottom,
                label:before ? 'Move before this branch' : 'Move after this branch'};
        }
        return {anchor:{node:n.id, passage:p.id, revision:p.revision, quote:'', mode:'through', quoteStart:-1, quoteEnd:-1},
            beforeNode:'', rect, line:rect.bottom, label:'Attach below this paragraph'};
    }
    function paintDrag() {
        const d = branchDrag; if (!d?.started) return;
        const r = body.getBoundingClientRect();
        if (d.x >= r.left && d.x <= r.right && d.y >= r.top && d.y <= r.bottom) {
            if (d.y < r.top+32) body.scrollTop -= Math.ceil((r.top+32-d.y)/3);
            else if (d.y > r.bottom-32) body.scrollTop += Math.ceil((d.y-r.bottom+32)/3);
        }
        d.candidate = dropCandidate(d.x, d.y, d.id);
        d.label.textContent = d.candidate?.label || 'Choose a paragraph outside this branch';
        Object.assign(d.label.style, {left:`${Math.min(d.x+14, innerWidth-260)}px`, top:`${Math.min(d.y+14, innerHeight-35)}px`});
        d.line.hidden = !d.candidate;
        if (d.candidate) Object.assign(d.line.style, {left:`${d.candidate.rect.left}px`, top:`${d.candidate.line}px`, width:`${d.candidate.rect.width}px`});
        d.frame = requestAnimationFrame(paintDrag);
    }
    function moveDrag(event) {
        const d = branchDrag; if (!d || event.pointerId !== d.pointerId) return;
        d.x = event.clientX; d.y = event.clientY;
        if (!d.started && Math.hypot(d.x-d.startX, d.y-d.startY) > 5) {
            d.started = true; host.classList.add('garden-dragging');
            d.source = entries.querySelector(`[data-branch="${d.id}"]`) || entries.querySelector(`article[data-garden-node="${d.id}"]`);
            d.source?.classList.add('garden-drag-source');
            d.label = el('div', 'garden-drag-label'); d.line = el('div', 'garden-drop-indicator');
            d.label.setAttribute('role', 'status'); document.body.append(d.label, d.line); paintDrag();
        }
        if (d.started) event.preventDefault();
    }
    async function endDrag(event, cancel = false) {
        const d = branchDrag; if (!d || (event?.pointerId != null && event.pointerId !== d.pointerId)) return;
        if (d.started && !cancel && event) { d.x = event.clientX; d.y = event.clientY; d.candidate = dropCandidate(d.x,d.y,d.id); }
        cancelAnimationFrame(d.frame); d.label?.remove(); d.line?.remove(); d.source?.classList.remove('garden-drag-source');
        host.classList.remove('garden-dragging'); branchDrag = null;
        suppressedDisclosure = d.id; clearTimeout(suppressTimer); suppressTimer = setTimeout(() => { suppressedDisclosure = ''; }, 0);
        if (!cancel && d.started && d.candidate) {
            pending = true; updateActions();
            try {
                if (await call({action:'move', node:d.id, anchor:d.candidate.anchor, beforeNode:d.candidate.beforeNode})) {
                    if (d.candidate.anchor) for (const id of path(d.candidate.anchor.node)) foldedBranches.delete(id);
                    signature = ''; render();
                }
            } catch (error) { fail(error); signature = ''; render(); }
            finally { pending = false; updateActions(); }
        } else { signature = ''; render(); }
    }
    const releaseDrag = event => endDrag(event);
    const cancelDrag = event => endDrag(event, true);
    const escapeDrag = event => { if (event.key === 'Escape' && branchDrag) { event.preventDefault(); event.stopPropagation(); endDrag(null,true); } };
    window.addEventListener('pointermove', moveDrag, {passive:false});
    window.addEventListener('pointerup', releaseDrag);
    window.addEventListener('pointercancel', cancelDrag);
    window.addEventListener('blur', cancelDrag);
    document.addEventListener('keydown', escapeDrag, true);
    function dragDisclosure(control, id) {
        control.addEventListener('pointerdown', event => {
            if (!event.metaKey || event.altKey || event.button !== 0 || pending) return;
            event.preventDefault(); event.stopPropagation();
            branchDrag = {id, pointerId:event.pointerId, startX:event.clientX, startY:event.clientY, x:event.clientX, y:event.clientY, started:false};
            if (event.isTrusted) control.setPointerCapture?.(event.pointerId);
        });
    }
    function disclosureModes(event) {
        host.classList.toggle('garden-delete-mode', !!event.altKey);
        host.classList.toggle('garden-focus-mode', !event.altKey && !!event.shiftKey);
    }
    const clearDisclosureModes = () => { host.classList.remove('garden-delete-mode', 'garden-focus-mode'); };
    document.addEventListener('keydown', disclosureModes);
    document.addEventListener('keyup', disclosureModes);
    host.addEventListener('pointermove', disclosureModes);
    window.addEventListener('blur', clearDisclosureModes);
    async function disclosureAction(event, id) {
        if (event.metaKey || suppressedDisclosure === id) { event.preventDefault(); event.stopPropagation(); return true; }
        if (event.altKey) {
            event.preventDefault(); event.stopPropagation();
            if (pending) return true;
            pending = true; updateActions();
            try {
                if (await call({action: 'delete', node: id})) {
                    const remaining = new Set(garden.nodes.map(n => n.id));
                    for (const id of foldedBranches) if (!remaining.has(id)) foldedBranches.delete(id);
                    if (anchor && !remaining.has(anchor.node)) { anchor = null; anchorText = ''; composer.hidden = true; }
                    if (!remaining.has(selected)) { selected = ''; focused = false; }
                    signature = ''; render();
                }
            } catch (error) { fail(error); }
            finally { pending = false; updateActions(); }
            return true;
        }
        if (event.shiftKey) {
            event.preventDefault(); event.stopPropagation();
            focused = !(focused && selected === id); selected = id; view.value = 'reading'; signature = ''; render();
            return true;
        }
        return false;
    }
    function markingMenu(event, n, p) {
        const children = garden.nodes.filter(c => c.anchor?.node === n.id && c.anchor.passage === p.id);
        const options = [
            {text: 'Reply here', action: () => attach(n, p)},
            {text: 'Copy reference', action: async () => {
                const url = new URL(location.href); url.hash = 'garden/' + [owner, n.id, p.id].map(encodeURIComponent).join('/');
                try { await navigator.clipboard.writeText(url.href); }
                catch (error) { fail(error); }
            }},
            {text: 'Focus path', action: () => { selected = n.id; focused = true; view.value = 'reading'; signature = ''; render(); }},
        ];
        if (children.length) {
            options.push({text: 'Collapse replies', action: () => { children.forEach(c => foldedBranches.add(c.id)); signature = ''; render(); }});
            options.push({text: 'Expand replies', action: () => { children.forEach(c => foldedBranches.delete(c.id)); signature = ''; render(); }});
        }
        if (n.anchor) options.push({text: 'Collapse branch', action: () => { foldedBranches.add(n.id); if (anchor?.node === n.id) anchor = null; signature = ''; render(); }});
        if (typeof goMarkingMenu !== 'function') return;
        let fired = false;
        goMarkingMenu(event.clientX, event.clientY, options.map((o, i) => ({
            text: o.text, angle: 180 - i * 360 / options.length, radius: .85,
            callback: () => { if (!fired) { fired = true; o.action(); } }
        })), event, () => {});
    }
    function passageOperations(wrap, n, p) {
        wrap.addEventListener('contextmenu', event => {
            if (event.target.closest('a, button')) return;
            event.preventDefault(); markingMenu(event, n, p);
        });
    }
    function updateActions() {
        input.readOnly = pending;
        view.textContent = view.value === 'outline' ? 'Outline' : 'Reading';
        view.setAttribute('aria-pressed', String(view.value === 'outline'));
        focus.disabled = !selected;
        focus.setAttribute('aria-pressed', String(focused));
    }
    function closeMenu(restore = false) {
        menu.hidden = true;
        if (restore) input.focus({preventScroll: true});
    }
    function openReplyMenu() {
        menu.replaceChildren();
        const choose = (label, run, needsDraft = true) => {
            const item = button(label, async () => { closeMenu(true); await run(); });
            item.setAttribute('role', 'menuitem'); item.disabled = pending || (needsDraft && !input.value.trim()); menu.append(item);
        };
        choose('Queue', () => submit(false));
        choose('Queue and play', async () => { if (await submit(false)) { playing = true; await advance(); } });
        choose('Whole document', () => submit(true, true));
        if (playing) choose('Pause queue', () => { playing = false; }, false);
        else if (garden.nodes.some(n => n.status === 'queued')) choose('Play queue', () => { playing = true; advance(); }, false);
        const rect = input.getBoundingClientRect();
        menu.hidden = false;
        const r = menu.getBoundingClientRect();
        menu.style.left = Math.max(8, Math.min(rect.left, innerWidth - r.width - 8)) + 'px';
        menu.style.top = Math.max(8, Math.min(rect.top + 24, innerHeight - r.height - 8)) + 'px';
        (menu.querySelector('button:not(:disabled)') || menu).focus();
    }
    menu.addEventListener('keydown', event => {
        const items = [...menu.querySelectorAll('button:not(:disabled)')], i = items.indexOf(document.activeElement);
        if (event.key === 'Escape' || event.key === 'Tab') { event.preventDefault(); closeMenu(true); }
        else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault(); items[(i + (event.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length]?.focus();
        }
        event.stopPropagation();
    });
    const dismissMenu = event => { if (!menu.hidden && !menu.contains(event.target)) closeMenu(); };
    document.addEventListener('pointerdown', dismissMenu, true);
    body.addEventListener('scroll', () => closeMenu());
    async function submit(dispatch, wholeDocument = false) {
        if (pending || !input.value.trim()) return false;
        const prompt = input.value.trim(), oldIds = new Set(garden.nodes.map(n => n.id));
        pending = true; updateActions();
        try {
            if (!await call({action: 'enqueue', prompt, anchor, anchorText, property: chatProperty(), wholeDocument})) return;
            input.value = ''; input.style.height = ''; drafts.delete(owner);
            anchor = null;
            const added = garden.nodes.find(n => !oldIds.has(n.id));
            selected = added?.id || selected; render();
            if (dispatch && added) {
                if (busy) { setStatus('Saved in queue · an agent is already running in this box'); }
                else { await run(added.id); }
            } else setStatus('Reply saved in queue');
            return true;
        } catch (error) { fail(error); return false; }
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
    function markQuotedReplies(prose, children) {
        const marked = new Set(), plain = prose.textContent;
        const ranges = children.filter(c => c.anchor.quote).map(c => {
            const a = c.anchor, q = a.quote;
            let start = a.quoteStart;
            if (start < 0 || plain.slice(start, a.quoteEnd) !== q) {
                start = plain.indexOf(q);
                if (start !== plain.lastIndexOf(q)) start = -1; // older ambiguous quotes keep their branch spine
            }
            return {child: c, start, end: start + q.length};
        }).filter(r => r.start >= 0).sort((a, b) => b.start - a.start);
        for (const {child, start, end} of ranges) {
            const walker = document.createTreeWalker(prose, NodeFilter.SHOW_TEXT), range = document.createRange();
            let offset = 0, node, started = false, ended = false;
            while ((node = walker.nextNode())) {
                const next = offset + node.textContent.length;
                if (!started && start < next) { range.setStart(node, start - offset); started = true; }
                if (started && end <= next) { range.setEnd(node, end - offset); ended = true; break; }
                offset = next;
            }
            if (!ended) continue;
            const highlight = el('mark', 'garden-quoted-range'); highlight.append(range.extractContents()); range.insertNode(highlight);
            const marker = button('', async event => {
                if (await disclosureAction(event, child.id)) return;
                const branch = entries.querySelector(`[data-branch="${child.id}"]`);
                if (branch) toggleBranch(branch);
            }, 'garden-quote-anchor');
            dragDisclosure(marker, child.id);
            marker.dataset.quoteBranch = child.id;
            marker.setAttribute('aria-expanded', String(!foldedBranches.has(child.id)));
            marker.setAttribute('aria-label', `Toggle reply to selected text: ${child.anchor.quote}`);
            marker.title = `Replies to “${child.anchor.quote}” · Alt-click deletes branch · Shift-click focuses`;
            marker.addEventListener('mousedown', event => event.preventDefault());
            marker.addEventListener('mouseenter', () => highlight.classList.add('is-highlighted'));
            marker.addEventListener('mouseleave', () => highlight.classList.remove('is-highlighted'));
            highlight.before(marker); marked.add(child.id);
        }
        return marked;
    }
    function renderBranch(n, allowed, ancestors, quoted = false) {
        const branch = el('details', 'garden-branch'); branch.dataset.branch = n.id; branch.classList.toggle('garden-quoted-branch', quoted);
        branch.open = !foldedBranches.has(n.id);
        const summary = el('summary'), glyph = el('span', 'garden-fold-glyph'), label = el('span', 'garden-fold-label', n.prompt.slice(0, 100) || 'Conversation branch');
        summary.append(glyph, label);
        summary.title = 'Collapse or expand · Alt-click deletes · Shift-click focuses · ⌘-drag moves branch';
        dragDisclosure(summary, n.id);
        summary.addEventListener('click', event => {
            event.preventDefault();
            if (event.altKey || event.shiftKey || event.metaKey || suppressedDisclosure === n.id) disclosureAction(event, n.id);
            else toggleBranch(branch);
        });
        const update = () => { glyph.classList.toggle('is-open', branch.open); label.hidden = branch.open; summary.setAttribute('aria-label', branch.open ? 'Collapse branch' : 'Expand branch'); };
        branch.__syncFold = update; update(); branch.append(summary, renderNode(n, allowed, ancestors));
        branch.addEventListener('toggle', () => { if (branch.open) foldedBranches.delete(n.id); else foldedBranches.add(n.id); update();
            const marker = entries.querySelector(`[data-quote-branch="${n.id}"]`); marker?.setAttribute('aria-expanded', String(branch.open));
        });
        return branch;
    }
    function runningPlaceholder() {
        const placeholder = el('div', 'garden-running');
        placeholder.setAttribute('role', 'status');
        placeholder.setAttribute('aria-label', 'Agent responding');
        for (let i = 0; i < 3; i++) {
            const dot = el('span', '', '.'); dot.setAttribute('aria-hidden', 'true');
            dot.style.animationDelay = `${i * 180 - performance.now() % 1500}ms`;
            placeholder.append(dot);
        }
        return placeholder;
    }
    function renderNode(n, allowed, ancestors = new Set()) {
        if (ancestors.has(n.id)) return el('p', '', 'Invalid conversation cycle');
        const seen = new Set(ancestors); seen.add(n.id);
        const article = el('article', 'garden-node'); article.dataset.gardenNode = n.id;
        const heading = el('div', 'garden-node-heading');
        if (n.status === 'queued') heading.append(el('span', '', 'queued'));
        if (n.status === 'queued') heading.append(button('Run', async () => {
            if (pending) return; pending = true; updateActions();
            try { await run(n.id); } catch (error) { fail(error); } finally { pending = false; updateActions(); }
        }));
        if (n.contextMode === 'document') heading.append(el('span', '', 'whole document'));
        if (n.tags.length) heading.append(el('span', 'garden-tags', n.tags.map(t => '#' + t).join(' ')));
        article.append(heading);
        if (view.value === 'outline') {
            article.append(el('div', 'garden-human', n.prompt), el('p', 'garden-outline-summary', n.passages.filter(p => p.role === 'ai').map(text).join(' ').slice(0, 220)));
            if (n.status === 'running') article.append(runningPlaceholder());
            return article;
        }
        const children = garden.nodes.filter(c => c.anchor?.node === n.id && allowed.has(c.id));
        let passages = n.passages;
        if (focused && selected !== n.id) {
            const child = children.find(c => path(selected).has(c.id));
            if (child?.anchor?.mode === 'through') {
                const i = passages.findIndex(p => p.id === child.anchor.passage);
                if (i >= 0) passages = passages.slice(0, i + 1).map(p => p.id === child.anchor.passage ? {...p, revision: child.anchor.revision} : p);
            }
        }
        // Older servers may not yet expose the request's virtual paragraph address.
        let responseTail = heading;
        if (n.prompt && !passages.some(p => p.id === n.id + ':request')) {
            responseTail = el('div', 'garden-human', n.prompt); article.append(responseTail);
        }
        const renderedChildren = new Set();
        let activity = null;
        const activityRow = () => { if (!activity) { activity = el('div', 'garden-activity'); article.append(activity); } return activity; };
        for (const p of passages) {
            const progress = p.role.startsWith('progress');
            const wrap = el('div', progress ? 'garden-progress' : `garden-passage ${p.role === 'you' ? 'garden-human' : 'garden-agent'}`);
            wrap.__gardenRef = p; wrap.__gardenSource = text(p);
            wrap.dataset.gardenPassage = p.id; wrap.dataset.gardenNode = n.id; wrap.dataset.revision = p.revision;
            let markedQuotes = new Set();
            if (progress) {
                const lines = text(p).split('\n');
                if (!/^(\S+) (finished|failed)(?: \((\d+) ms\))?/.test(lines[0])) continue;
                if (lines.slice(1).join('').trim()) {
                    const folded = el('details'); folded.append(el('summary', '', lines[0]));
                    const content = el('div'); content.innerHTML = markdown(lines.slice(1).join('\n')); folded.append(content); wrap.append(folded);
                } else wrap.textContent = lines[0];
                responseTail = activityRow(); responseTail.append(wrap);
            } else {
                const content = el('div', 'garden-prose');
                if (p.role === 'you') content.textContent = text(p); else content.innerHTML = markdown(text(p));
                markedQuotes = markQuotedReplies(content, children.filter(c => c.anchor.passage === p.id));
                wrap.append(content); passageOperations(wrap, n, p); article.append(wrap); responseTail = wrap; activity = null;
            }
            for (const child of children.filter(c => c.anchor.passage === p.id)) {
                article.append(renderBranch(child, allowed, seen, markedQuotes.has(child.id))); renderedChildren.add(child.id); activity = null;
            }
        }
        for (const edit of n.edits) { responseTail = renderEdit(edit); article.append(responseTail); }
        if (n.status === 'running') responseTail.after(runningPlaceholder());
        for (const child of children.filter(c => !renderedChildren.has(c.id))) article.append(renderBranch(child, allowed, seen));
        if (n.error) article.append(el('p', 'garden-error', n.error));
        return article;
    }
    function render() {
        if (!active || branchDrag) return;
        updateActions(); updateAnchor();
        const nextSignature = `${garden.revision}|${view.value}|${focused}|${selected}|${filter.value}`;
        if (signature === nextSignature) return;
        // Do not replace a passage while the reader is selecting its text.
        const s = window.getSelection();
        if (s && !s.isCollapsed && body.contains(s.anchorNode)) return;
        signature = nextSignature;
        const beforeMotion = captureBlocks();
        const scrollTop = body.scrollTop, openEdits = new Set([...body.querySelectorAll('.garden-edit[open]')].map(d => d.dataset.edit));
        const hadFocus = document.activeElement === input, caret = [input.selectionStart, input.selectionEnd];
        body.append(composer);
        const allowed = visibleIds(); entries.replaceChildren();
        if (view.value === 'outline') {
            for (const n of garden.nodes.filter(n => allowed.has(n.id))) {
                const row = renderNode(n, allowed); row.style.marginLeft = Math.min(path(n.id).size - 1, 6) * 14 + 'px'; entries.append(row);
            }
        } else for (const n of garden.nodes.filter(n => !n.anchor && allowed.has(n.id))) entries.append(renderNode(n, allowed));
        for (const d of body.querySelectorAll('.garden-edit')) if (openEdits.has(d.dataset.edit)) d.open = true;
        if (!entries.children.length && garden.nodes.length) entries.append(el('p', 'garden-empty', 'No matching paths.'));
        decorate(entries); placeComposer();
        if (hadFocus) { input.focus({preventScroll: true}); input.setSelectionRange(...caret); }
        body.scrollTop = scrollTop; animateBlocks(beforeMotion);
    }
    view.addEventListener('change', () => { signature = ''; render(); });
    filter.addEventListener('input', () => render());
    input.addEventListener('input', sizeInput);
    input.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
            event.preventDefault(); event.stopPropagation();
            if (!pending) { input.value = ''; input.style.height = ''; anchor = null; anchorText = ''; composer.hidden = true; input.blur(); placeComposer(); }
            return;
        }
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
            event.preventDefault(); event.stopPropagation();
            if (event.altKey) openReplyMenu(); else submit(true);
        }
    });
    updateAnchor();
    return {viewState() { return {active, draft: input.value, anchor, anchorText, selected, focused, showTools, draftVisible: !composer.hidden, folded: [...foldedBranches], scroll: body.scrollTop}; },
    async restore(state) {
        if (!state?.active) return;
        await open(); input.value = state.draft || ''; anchor = state.anchor; anchorText = state.anchorText || '';
        selected = state.selected || ''; focused = !!state.focused; showTools = !!state.showTools;
        host.classList.toggle('garden-show-tools', showTools); tools.setAttribute('aria-pressed', String(showTools));
        composer.hidden = state.draftVisible === false;
        (state.folded || []).forEach(id => foldedBranches.add(id)); signature = ''; render(); sizeInput(); body.scrollTop = state.scroll || 0;
    }, dispose() { host.classList.remove('garden-mounted'); endDrag(null,true); clearTimeout(suppressTimer); window.removeEventListener('pointermove', moveDrag); window.removeEventListener('pointerup', releaseDrag); window.removeEventListener('pointercancel', cancelDrag); window.removeEventListener('blur', cancelDrag); document.removeEventListener('keydown', escapeDrag, true); for (const ghost of motionOverlays) ghost.remove(); close(); document.removeEventListener('keydown', disclosureModes); document.removeEventListener('keyup', disclosureModes); host.removeEventListener('pointermove', disclosureModes); window.removeEventListener('blur', clearDisclosureModes); document.removeEventListener('pointerdown', dismissMenu, true); menu.remove(); bar.remove(); panel.remove(); }, async reveal(node, passage) {
        if (!active) await open(); else await load();
        selected = node; focused = true; view.value = 'reading'; filter.value = '';
        for (const id of path(node)) foldedBranches.delete(id);
        signature = ''; render();
        const destination = [...entries.querySelectorAll('[data-garden-passage]')].find(e => e.dataset.gardenNode === node && e.dataset.gardenPassage === passage);
        destination?.scrollIntoView({block: 'center'});
        return !!destination;
    }, reset() { close(); garden = {nodes: [], texts: {}}; anchor = null; selected = ''; focused = false; filter.value = ''; }};
}
