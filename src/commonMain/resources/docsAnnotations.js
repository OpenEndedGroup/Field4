(() => {
    const install = () => {
        if (window.__fieldDocsAnnotationsInstalled) return;
        window.__fieldDocsAnnotationsInstalled = true;

    const upstream = new URL(window.__FIELD_DOCS_UPSTREAM__ || location.href);
    const canonicalPage = `${upstream.origin}${upstream.pathname}`;
    const state = { annotations: [], visible: true, tag: "", from: "", to: "", selected: null };

    const css = document.createElement("style");
    css.textContent = `
      :root { --f4a-bg:#090909; --f4a-fg:#e8e8e8; --f4a-dim:#8b8b8b; --f4a-line:#333; --f4a-accent:#fff176; }
      #f4-annotations { position:fixed; z-index:2147483600; right:16px; bottom:16px; width:330px; max-height:70vh; overflow:auto; color:var(--f4a-fg); background:var(--f4a-bg); border:1px solid var(--f4a-line); box-shadow:0 8px 30px #0009; padding:12px; font:12px/1.45 Iosevka,monospace; }
      #f4-annotations * { box-sizing:border-box; font:inherit; } #f4-annotations header { display:flex; align-items:center; gap:8px; }
      #f4-annotations header strong { flex:1; letter-spacing:.05em; } #f4-annotations button,#f4-annotations input,#f4-annotations textarea { color:inherit; background:#111; border:1px solid var(--f4a-line); padding:5px; }
      #f4-annotations button { cursor:pointer; } #f4-annotations textarea { width:100%; min-height:70px; resize:vertical; margin-top:8px; }
      #f4-annotations .f4a-filters { display:grid; grid-template-columns:1fr 1fr; gap:5px; margin:8px 0; } #f4-annotations .f4a-filters input:first-child { grid-column:1/-1; }
      #f4-annotations .f4a-actions { display:flex; gap:5px; margin-top:5px; } #f4-annotations .f4a-actions button:first-child { flex:1; }
      #f4-annotations .f4a-count { color:var(--f4a-dim); font-size:10px; } #f4-annotations .f4a-note { border-top:1px solid var(--f4a-line); padding:10px 0; cursor:pointer; } #f4-annotations .f4a-note:hover { background:#141414; } #f4-annotations .f4a-meta { color:var(--f4a-dim); font-size:10px; }
      #f4-annotations .f4a-quote { border-left:2px solid var(--f4a-accent); padding-left:6px; color:#bbb; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
      ::highlight(field-doc-annotations) { background-color:rgba(255,241,118,.16); text-decoration:underline 1.5px rgba(255,241,118,.9); text-underline-offset:.16em; }
      .f4a-marker { position:absolute; z-index:2147483500; pointer-events:none; }
      .f4a-marker-range { background:rgba(255,241,118,.16); border-bottom:1.5px solid rgba(255,241,118,.9); }
      .f4a-marker-position { width:7px; height:7px; border-radius:50%; background:var(--f4a-accent); box-shadow:0 0 0 3px rgba(255,241,118,.14); transform:translate(-50%,-50%); }
      #f4-annotations-launcher { position:fixed; z-index:2147483600; right:16px; bottom:16px; color:var(--f4a-fg); background:var(--f4a-bg); border:1px solid var(--f4a-line); padding:8px 11px; font:11px/1 Iosevka,monospace; cursor:pointer; letter-spacing:.04em; }
      #f4-annotations[hidden] { display:none; }
      #f4-annotations-launcher[hidden] { display:none; }
      @media (min-width:1180px) {
        #f4-annotations { top:16px; right:16px; bottom:16px; width:360px; max-height:none; border-color:#2a2a2a; box-shadow:none; }
        #f4-annotations header { position:sticky; top:-12px; z-index:2; margin:-12px -12px 0; padding:12px; background:#090909; border-bottom:1px solid var(--f4a-line); }
        #f4-annotations .f4a-filters { position:sticky; top:39px; z-index:2; margin:0 -12px 8px; padding:8px 12px; background:#090909; border-bottom:1px solid #202020; }
        #f4-annotations-launcher { display:none; }
      }
    `;
    document.head.appendChild(css);

    const panel = document.createElement("aside");
    panel.id = "f4-annotations";
    panel.innerHTML = `<header><strong>DOC NOTES</strong><span class="f4a-count" data-role="count">0</span><button data-act="new" title="Select documentation text, then press Ctrl+Shift+A">+ selection</button><button data-act="hide">×</button></header>
      <div class="f4a-filters"><input data-filter="tag" placeholder="@tag"><input data-filter="from" type="date" title="from"><input data-filter="to" type="date" title="to"></div>
      <div data-role="editor" hidden><div class="f4a-quote" data-role="selection"></div><textarea aria-label="Annotation note. Enter saves, Shift+Enter inserts a line, Escape cancels." placeholder="Note — use @tags to direct improvement"></textarea></div>
      <div data-role="list"></div>`;
    document.body.appendChild(panel);
    const launcher = document.createElement("button");
    launcher.id = "f4-annotations-launcher";
    launcher.type = "button";
    launcher.textContent = "notes · 0";
    launcher.hidden = true;
    document.body.appendChild(launcher);
    const editorRegion = panel.querySelector('[data-role="editor"]');
    const noteEditor = panel.querySelector("textarea");

    const syncResponsiveVisibility = () => {
        const narrow = !matchMedia("(min-width:1180px)").matches;
        if (!narrow) { panel.hidden = false; launcher.hidden = true; state.visible = true; }
        else if (!panel.hidden && launcher.hidden) { panel.hidden = true; launcher.hidden = false; state.visible = false; }
        renderMarkers();
    };

    const api = async (url, options = {}) => {
        const response = await fetch(new URL(url, window.location.origin), { ...options, headers: { "Content-Type":"application/json", ...(options.headers || {}) } });
        if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
        return response.json();
    };
    const esc = value => String(value || "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
    const tags = note => [...note.matchAll(/(?:^|[^\w@])@([A-Za-z][\w-]{0,63})/g)].map(m => m[1].toLowerCase());

    const searchableText = () => {
        const root = document.querySelector("article") || document.querySelector("main") || document.body;
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: node => {
                const parent = node.parentElement;
                if (!parent || !node.nodeValue || panel.contains(parent) || launcher.contains(parent) || parent.closest("script,style,noscript,template")) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
            }
        });
        const nodes = []; let text = ""; let node;
        while ((node = walker.nextNode())) { nodes.push({ node, start:text.length, end:text.length + node.nodeValue.length }); text += node.nodeValue; }
        return { nodes, text };
    };
    const rangeAt = (nodes, start, end) => {
        const first = nodes.find(value => value.end > start);
        const last = [...nodes].reverse().find(value => value.start < end);
        if (!first || !last) return null;
        const range = document.createRange();
        range.setStart(first.node, Math.max(0, start - first.start));
        range.setEnd(last.node, Math.min(last.node.nodeValue.length, end - last.start));
        return range;
    };
    const annotationRange = item => {
        const exact = item.anchor.exact;
        if (!exact) return null;
        const { nodes, text } = searchableText();
        const candidates = []; let index = text.indexOf(exact);
        while (index >= 0) {
            const range = rangeAt(nodes, index, index + exact.length);
            if (range) candidates.push(range);
            index = text.indexOf(exact, index + 1);
        }
        if (candidates.length < 2) return candidates[0] || null;
        const expectedY = (item.anchor.yRatio || 0) * document.documentElement.scrollHeight;
        return candidates.reduce((best, range) => Math.abs(range.getBoundingClientRect().top + scrollY - expectedY) < Math.abs(best.getBoundingClientRect().top + scrollY - expectedY) ? range : best);
    };

    const capture = () => {
        const selection = getSelection();
        if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
        const range = selection.getRangeAt(0);
        if (panel.contains(range.commonAncestorContainer)) return null;
        const exact = selection.toString().trim();
        if (!exact) return null;
        const text = document.body.innerText;
        const start = text.indexOf(exact);
        const rect = range.getBoundingClientRect();
        return { exact, prefix: start >= 0 ? text.slice(Math.max(0,start-48),start) : "", suffix: start >= 0 ? text.slice(start+exact.length,start+exact.length+48) : "", startOffset:start, endOffset:start+exact.length, xRatio:(rect.left+scrollX)/Math.max(1,document.documentElement.scrollWidth), yRatio:(rect.top+scrollY)/Math.max(1,document.documentElement.scrollHeight) };
    };
    const openEditor = () => {
        state.selected = capture() || { exact:"", xRatio:.5, yRatio:(scrollY+innerHeight/2)/Math.max(1,document.documentElement.scrollHeight) };
        panel.querySelector('[data-role="selection"]').textContent = state.selected.exact || "Position note";
        editorRegion.hidden = false;
        noteEditor.focus();
    };
    const cancelEditor = () => { noteEditor.value = ""; editorRegion.hidden = true; state.selected = null; };
    const save = async () => {
        const note = noteEditor.value.trim();
        if (!note) return;
        await api("/api/docs-annotations", { method:"POST", body:JSON.stringify({ annotation:{ pageUrl:canonicalPage, note, tags:tags(note), anchor:state.selected || {} } }) });
        noteEditor.value = ""; editorRegion.hidden = true; state.selected = null; await load();
    };
    const filtered = item => {
        const tag = state.tag.replace(/^@/,"").toLowerCase();
        if (tag && !item.tags.includes(tag)) return false;
        if (state.from && item.createdAtEpochMs < new Date(state.from).getTime()) return false;
        if (state.to && item.createdAtEpochMs > new Date(state.to + "T23:59:59").getTime()) return false;
        return true;
    };
    const renderMarkers = () => {
        if (window.CSS?.highlights) CSS.highlights.delete("field-doc-annotations");
        document.querySelectorAll(".f4a-marker").forEach(n => n.remove());
        if (!state.visible) return;
        const ranges = [];
        state.annotations.filter(filtered).forEach(item => {
            const range = annotationRange(item);
            if (range && window.CSS?.highlights && window.Highlight) { ranges.push(range); return; }
            if (range) {
                [...range.getClientRects()].forEach(rect => {
                    const marker = document.createElement("div"); marker.className = "f4a-marker f4a-marker-range"; marker.title = item.note;
                    marker.style.cssText += `left:${rect.left+scrollX}px;top:${rect.top+scrollY}px;width:${rect.width}px;height:${rect.height}px`;
                    document.body.appendChild(marker);
                });
                return;
            }
            const marker = document.createElement("div"); marker.className = "f4a-marker f4a-marker-position"; marker.title = item.note;
            marker.style.cssText += `left:${Math.max(0,(item.anchor.xRatio||.5)*document.documentElement.scrollWidth)}px;top:${(item.anchor.yRatio||0)*document.documentElement.scrollHeight}px`;
            document.body.appendChild(marker);
        });
        if (ranges.length && window.CSS?.highlights && window.Highlight) CSS.highlights.set("field-doc-annotations", new Highlight(...ranges));
    };
    const render = () => {
        const visible = state.annotations.filter(filtered);
        panel.querySelector('[data-role="count"]').textContent = String(visible.length);
        launcher.textContent = `notes · ${visible.length}`;
        panel.querySelector('[data-role="list"]').innerHTML = visible.map(item => `<article class="f4a-note" data-id="${esc(item.id)}" title="Show this annotation"><div>${esc(item.note)}</div>${item.anchor.exact ? `<div class="f4a-quote">${esc(item.anchor.exact)}</div>`:""}<div class="f4a-meta">${new Date(item.createdAtEpochMs).toLocaleDateString()} ${item.tags.map(t=>"@"+esc(t)).join(" ")}</div><div class="f4a-actions"><button data-act="resolve">resolve</button><button data-act="delete">delete</button></div></article>`).join("") || `<div class="f4a-meta">No notes on this page.</div>`;
        renderMarkers();
    };
    const load = async () => { state.annotations = await api(`/api/docs-annotations?page=${encodeURIComponent(canonicalPage)}`); render(); };

    panel.addEventListener("click", async event => {
        const button = event.target.closest("button"); if (!button) return;
        const action = button.dataset.act, id = button.closest("[data-id]")?.dataset.id;
        if (action === "new") openEditor();
        if (action === "hide") { panel.hidden = true; launcher.hidden = false; state.visible = false; renderMarkers(); }
        if (action === "resolve" && id) { await api(`/api/docs-annotations/${id}/resolve`, {method:"PUT",body:JSON.stringify({resolved:true,resolution:"Resolved by user"})}); await load(); }
        if (action === "delete" && id && confirm("Delete this annotation?")) { await api(`/api/docs-annotations/${id}`, {method:"DELETE"}); await load(); }
    });
    launcher.addEventListener("click", () => { launcher.hidden = true; panel.hidden = false; state.visible = true; renderMarkers(); });
    panel.querySelector('[data-role="list"]').addEventListener("click", event => {
        if (event.target.closest("button")) return;
        const id = event.target.closest("[data-id]")?.dataset.id;
        const item = state.annotations.find(value => value.id === id);
        if (!item) return;
        const rect = annotationRange(item)?.getBoundingClientRect();
        const y = rect ? rect.top + scrollY : (item.anchor.yRatio || 0) * document.documentElement.scrollHeight;
        scrollTo({ top: Math.max(0, y - innerHeight * .25), behavior:"smooth" });
    });
    noteEditor.addEventListener("keydown", async event => {
        if (event.isComposing) return;
        if (event.key === "Escape") { event.preventDefault(); cancelEditor(); }
        else if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); await save(); }
    });
    document.addEventListener("keydown", event => {
        if (event.ctrlKey && !event.metaKey && event.shiftKey && event.key.toLowerCase() === "a") {
            event.preventDefault(); event.stopPropagation();
            panel.hidden = false; launcher.hidden = true; state.visible = true; openEditor(); renderMarkers();
        }
    }, true);
    panel.querySelectorAll("[data-filter]").forEach(input => input.addEventListener("input", () => { state[input.dataset.filter] = input.value; render(); }));
    document.addEventListener("click", event => {
        const link = event.target.closest("a[href]"); if (!link || event.defaultPrevented || event.metaKey || event.ctrlKey) return;
        const target = new URL(link.href, upstream); if (target.origin === upstream.origin && target.pathname.toLowerCase().startsWith("/field4/")) { event.preventDefault(); location.href = `/field-docs/${target.pathname.slice(8)}${target.search}${target.hash}`; }
    });
        addEventListener("resize", syncResponsiveVisibility); syncResponsiveVisibility(); load().catch(error => { panel.querySelector('[data-role="list"]').textContent = error.message; });
    };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", install, { once:true });
    else install();
})();
