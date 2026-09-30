(() => {
    const install = () => {
        if (window.__fieldDocsAnnotationsInstalled) return;
        window.__fieldDocsAnnotationsInstalled = true;

    const upstream = new URL(window.__FIELD_DOCS_UPSTREAM__ || location.href);
    const proxyRoot = window.__FIELD_DOCS_PROXY__ || "/field-docs/";
    const canonicalPage = `${upstream.origin}${upstream.pathname}`;
    const state = { annotations: [], visible: true, tag: "", from: "", to: "", selected: null, showResolved: false, buildCommit: "" };

    const css = document.createElement("style");
    css.textContent = `
      :root { --f4a-bg:#090909; --f4a-fg:#e8e8e8; --f4a-dim:#8b8b8b; --f4a-line:#333; --f4a-accent:#fff176; --f4a-cyan:rgb(72,201,255); }
      #f4-annotations { position:fixed; z-index:2147483600; right:16px; bottom:16px; width:330px; max-height:70vh; overflow:auto; color:var(--f4a-fg); background:var(--f4a-bg); border:1px solid var(--f4a-line); box-shadow:0 8px 30px #0009; padding:12px; font:12px/1.45 Iosevka,monospace; }
      #f4-annotations * { box-sizing:border-box; font:inherit; } #f4-annotations header { display:flex; align-items:center; gap:8px; }
      #f4-annotations header strong { flex:1; letter-spacing:.05em; } #f4-annotations button,#f4-annotations input,#f4-annotations textarea { color:inherit; background:#111; border:1px solid var(--f4a-line); padding:5px; }
      #f4-annotations button { cursor:pointer; } #f4-annotations textarea { width:100%; min-height:70px; resize:vertical; margin-top:8px; }
      #f4-annotations textarea:focus { outline:none; border-color:var(--f4a-cyan); box-shadow:none; }
      #f4-annotations .f4a-filters { display:grid; grid-template-columns:1fr 1fr; gap:5px; margin:8px 0; } #f4-annotations .f4a-filters input:first-child { grid-column:1/-1; }
      #f4-annotations .f4a-actions { display:flex; gap:5px; margin-top:5px; } #f4-annotations .f4a-actions button:first-child { flex:1; }
      #f4-annotations .f4a-count { color:var(--f4a-dim); font-size:10px; } #f4-annotations .f4a-note { border-top:1px solid var(--f4a-line); padding:10px 0; cursor:pointer; } #f4-annotations .f4a-note:hover { background:#141414; } #f4-annotations .f4a-meta { color:var(--f4a-dim); font-size:10px; }
      #f4-annotations .f4a-quote { border-left:2px solid var(--f4a-accent); padding-left:6px; color:#bbb; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
      ::highlight(field-doc-annotations) { background-color:rgba(255,241,118,.16); text-decoration:underline 1.5px rgba(255,241,118,.9); text-underline-offset:.16em; }
      .f4a-marker { position:absolute; z-index:2147483500; pointer-events:none; }
      .f4a-marker-range { background:rgba(255,241,118,.16); border-bottom:1.5px solid rgba(255,241,118,.9); }
      .f4a-marker-position { width:7px; height:7px; border-radius:50%; background:var(--f4a-accent); box-shadow:0 0 0 3px rgba(255,241,118,.14); transform:translate(-50%,-50%); }
      #f4-annotations-launcher { position:fixed; z-index:2147483600; right:16px; bottom:16px; color:var(--f4a-fg); background:var(--f4a-bg); border:1px solid var(--f4a-line); padding:8px 11px; font:11px/1 Iosevka,monospace; cursor:pointer; letter-spacing:.04em; }
      #f4-annotations .f4a-state { font-size:10px; letter-spacing:.05em; text-transform:uppercase; color:var(--f4a-dim); }
      #f4-annotations .f4a-state[data-state="question"] { color:var(--f4a-accent); } #f4-annotations .f4a-state[data-state="memory"] { color:var(--f4a-cyan); }
      #f4-annotations .f4a-source { color:var(--f4a-cyan); font-size:10px; word-break:break-all; }
      #f4-annotations .f4a-thread { margin:6px 0 0; padding-left:8px; border-left:1px solid var(--f4a-line); }
      #f4-annotations .f4a-entry { font-size:11px; color:#bbb; margin-top:4px; } #f4-annotations .f4a-entry b { color:var(--f4a-dim); font-weight:normal; }
      #f4-annotations .f4a-entry[data-kind="question"] b { color:var(--f4a-accent); } #f4-annotations .f4a-entry[data-kind="memory"] b { color:var(--f4a-cyan); }
      #f4-annotations .f4a-reply { min-height:40px; margin-top:6px; }
      #f4-annotations label.f4a-toggle { grid-column:1/-1; color:var(--f4a-dim); font-size:10px; display:flex; gap:6px; align-items:center; }
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

    // Which copy of the page you are annotating. The notes are keyed on `canonicalPage`
    // either way, so this is not about where a note goes — it is about whether the
    // sentence in front of you is the one you just wrote or the one readers can see.
    const servedLocally = window.__FIELD_DOCS_SOURCE__ === "local";
    const sourceLabel = servedLocally ? "local build" : "published";
    const sourceTitle = servedLocally
        ? `served from this checkout's build of ${canonicalPage}`
        : `served from ${canonicalPage}`;

    const panel = document.createElement("aside");
    panel.id = "f4-annotations";
    panel.innerHTML = `<header><strong>DOC NOTES</strong><span class="f4a-count" data-role="source" title="${sourceTitle}">${sourceLabel}</span><span class="f4a-count" data-role="count">0</span><button data-act="new" title="Select documentation text, then press Ctrl+Shift+A">+ selection</button><button data-act="hide">×</button></header>
      <div class="f4a-filters"><input data-filter="tag" placeholder="@tag"><input data-filter="from" type="date" title="from"><input data-filter="to" type="date" title="to"><label class="f4a-toggle"><input type="checkbox" data-role="show-resolved"> show resolved notes and memories</label></div>
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

    // The heading a node sits under, as rendered, minus Material's permalink glyph. A second
    // anchor for tools/docs_notes.py when the quote alone is a word that occurs ten times.
    const headingAbove = node => {
        const element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
        const headings = [...document.querySelectorAll("article h1, article h2, article h3, article h4, main h1, main h2, main h3, main h4")];
        let found = null;
        for (const heading of headings) {
            if (heading.compareDocumentPosition(element) & Node.DOCUMENT_POSITION_FOLLOWING || heading.contains(element)) found = heading;
        }
        return found ? found.textContent.replace(/\u00b6/g, "").trim() : "";
    };
    // Offsets, prefix and suffix are measured in the same text `annotationRange` searches, at
    // the selection's own position. The old `innerText.indexOf(exact)` took the *first*
    // occurrence, so a note on the fourth "continue" recorded the context of the first.
    const capture = () => {
        const selection = getSelection();
        if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
        const range = selection.getRangeAt(0);
        if (panel.contains(range.commonAncestorContainer)) return null;
        const exact = selection.toString().trim();
        if (!exact) return null;
        const { nodes, text } = searchableText();
        const startNode = nodes.find(value => value.node === range.startContainer);
        let start = startNode ? startNode.start + range.startOffset : text.indexOf(exact);
        if (start >= 0 && text.slice(start, start + exact.length) !== exact) {
            const near = text.indexOf(exact, Math.max(0, start - exact.length - 8));
            start = near >= 0 ? near : text.indexOf(exact);
        }
        const rect = range.getBoundingClientRect();
        // A selection in the header or the navigation is about the site, not this page's
        // Markdown; saying so stops the resolver pinning it on the first matching word below.
        const contentRoot = document.querySelector("article") || document.querySelector("main") || document.body;
        const region = contentRoot.contains(range.startContainer) ? "content" : "chrome";
        return { exact, region, prefix: start >= 0 ? text.slice(Math.max(0,start-48),start) : "", suffix: start >= 0 ? text.slice(start+exact.length,start+exact.length+48) : "", startOffset:start, endOffset:start+exact.length, xRatio:(rect.left+scrollX)/Math.max(1,document.documentElement.scrollWidth), yRatio:(rect.top+scrollY)/Math.max(1,document.documentElement.scrollHeight), heading:headingAbove(range.startContainer), sourceCommit:state.buildCommit, docsSource:servedLocally ? "local" : "published" };
    };
    const openEditor = () => {
        state.selected = capture() || { exact:"", xRatio:.5, yRatio:(scrollY+innerHeight/2)/Math.max(1,document.documentElement.scrollHeight), heading:headingAbove(document.elementFromPoint(innerWidth/2, innerHeight/2) || document.body), sourceCommit:state.buildCommit, docsSource:servedLocally ? "local" : "published" };
        panel.querySelector('[data-role="selection"]').textContent = state.selected.exact || "Position note";
        editorRegion.hidden = false;
        noteEditor.focus();
    };
    const cancelEditor = () => { noteEditor.value = ""; editorRegion.hidden = true; state.selected = null; };
    // Failures here used to be an unhandled promise rejection: the note stayed in the box
    // and nothing said why. That was survivable while every request succeeded. It is not
    // now that the server can refuse — annotations are loopback-only unless
    // AFIELD_DOCS_ANNOTATIONS_ALLOW_REMOTE is set — because the refusal is a sentence
    // telling you what to do, and swallowing it turns "open this over localhost instead"
    // into "the save button is broken". The note is deliberately left in the textarea.
    const reportError = message => {
        const region = panel.querySelector('[data-role="selection"]');
        region.textContent = message;
        editorRegion.hidden = false;
    };
    const save = async () => {
        const note = noteEditor.value.trim();
        if (!note) return;
        try {
            await api("/api/docs-annotations", { method:"POST", body:JSON.stringify({ annotation:{ pageUrl:canonicalPage, note, tags:tags(note), anchor:state.selected || {} } }) });
        } catch (error) { return reportError(error.message); }
        noteEditor.value = ""; editorRegion.hidden = true; state.selected = null; await load();
    };
    // Mirrors `note_state` in tools/docs_notes.py: an agent's question with no reply after it
    // takes the note out of the revision queue until you answer it here.
    const noteState = item => {
        if (item.kind === "memory") return "memory";
        if (item.resolvedAtEpochMs != null) return "resolved";
        let pending = false;
        (item.thread || []).forEach(entry => { if (entry.kind === "question") pending = true; else if (entry.kind === "reply" || entry.kind === "reopen") pending = false; });
        return pending ? "question" : "open";
    };
    const stateLabel = { open:"open", question:"agent asks", resolved:"resolved", memory:"memory" };
    const renderThread = item => {
        const entries = (item.thread || []).filter(entry => entry.kind !== "run");
        if (!entries.length) return "";
        return `<div class="f4a-thread">${entries.map(entry => `<div class="f4a-entry" data-kind="${esc(entry.kind)}"><b>${esc(entry.kind)} · ${esc(entry.author)} · ${new Date(entry.atEpochMs).toLocaleDateString()}</b> ${esc(entry.text)}${entry.commit ? ` <b>${esc(entry.commit.slice(0,10))}</b>` : ""}</div>`).join("")}</div>`;
    };
    const renderSource = item => {
        const source = item.source;
        if (!source) return "";
        const where = source.startLine ? `${source.path}:${source.startLine}${source.endLine && source.endLine !== source.startLine ? "-" + source.endLine : ""}` : (source.path || source.page || "");
        return `<div class="f4a-source" title="anchor ${esc(source.status)}${source.reason ? ": " + esc(source.reason) : ""}">${esc(where)} · ${esc(source.status)}</div>`;
    };
    const filtered = item => {
        if (!state.showResolved && item.kind === "memory") return false;
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
        panel.querySelector('[data-role="list"]').innerHTML = visible.map(item => {
            const itemState = noteState(item);
            const actions = itemState === "resolved"
                ? `<button data-act="reopen">reopen</button><button data-act="delete">delete</button>`
                : `<button data-act="resolve">resolve</button><button data-act="delete">delete</button>`;
            return `<article class="f4a-note" data-id="${esc(item.id)}" title="Show this annotation"><div class="f4a-state" data-state="${itemState}">${stateLabel[itemState]}</div><div>${esc(item.note)}</div>${item.anchor.exact ? `<div class="f4a-quote">${esc(item.anchor.exact)}</div>`:""}${renderSource(item)}<div class="f4a-meta">${new Date(item.createdAtEpochMs).toLocaleDateString()} ${item.tags.map(t=>"@"+esc(t)).join(" ")}</div>${renderThread(item)}${itemState === "memory" ? "" : `<textarea class="f4a-reply" data-role="reply" placeholder="${itemState === "question" ? "Answer the agent — Enter sends" : "Reply — Enter sends"}"></textarea>`}<div class="f4a-actions">${actions}</div></article>`;
        }).join("") || `<div class="f4a-meta">No notes on this page.</div>`;
        renderMarkers();
    };
    const load = async () => { state.annotations = await api(`/api/docs-annotations?page=${encodeURIComponent(canonicalPage)}${state.showResolved ? "&resolved=true" : ""}`); render(); };
    // The build writes the commit it was built from next to the site; a note carries it so the
    // quote is matched against the Markdown that produced *this* rendering. Absent (an old or
    // published build) is fine: the server records its own HEAD as a fallback.
    const loadBuildInfo = async () => {
        try {
            const response = await fetch(new URL(`${proxyRoot}field-docs-build.json`, window.location.origin), { cache:"no-store" });
            if (response.ok) state.buildCommit = String((await response.json()).commit || "");
        } catch (_) { /* no build info; not an error */ }
    };
    const sendReply = async (id, reply) => {
        const item = state.annotations.find(value => value.id === id);
        if (!item || !reply.trim()) return;
        await api("/api/docs-annotations", { method:"POST", body:JSON.stringify({ annotation:{ id:item.id, pageUrl:item.pageUrl, note:item.note, tags:item.tags }, reply }) });
        await load();
    };

    panel.addEventListener("click", async event => {
        const button = event.target.closest("button"); if (!button) return;
        const action = button.dataset.act, id = button.closest("[data-id]")?.dataset.id;
        if (action === "new") openEditor();
        if (action === "hide") { panel.hidden = true; launcher.hidden = false; state.visible = false; renderMarkers(); }
        try {
            if (action === "reopen" && id) { await api(`/api/docs-annotations/${id}/resolve`, {method:"PUT",body:JSON.stringify({resolved:false,resolution:"Reopened by user"})}); await load(); }
            if (action === "resolve" && id) { await api(`/api/docs-annotations/${id}/resolve`, {method:"PUT",body:JSON.stringify({resolved:true,resolution:"Resolved by user"})}); await load(); }
            if (action === "delete" && id && confirm("Delete this annotation?")) { await api(`/api/docs-annotations/${id}`, {method:"DELETE"}); await load(); }
        } catch (error) { reportError(error.message); }
    });
    panel.querySelector('[data-role="list"]').addEventListener("keydown", async event => {
        const reply = event.target.closest('[data-role="reply"]');
        if (!reply || event.isComposing || event.key !== "Enter" || event.shiftKey) return;
        event.preventDefault();
        try { await sendReply(reply.closest("[data-id]")?.dataset.id, reply.value); } catch (error) { reportError(error.message); }
    });
    panel.querySelector('[data-role="show-resolved"]').addEventListener("change", event => { state.showResolved = event.target.checked; load().catch(error => reportError(error.message)); });
    launcher.addEventListener("click", () => { launcher.hidden = true; panel.hidden = false; state.visible = true; renderMarkers(); });
    panel.querySelector('[data-role="list"]').addEventListener("click", event => {
        if (event.target.closest("button, textarea")) return;
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
        const target = new URL(link.href, upstream); if (target.origin === upstream.origin && target.pathname.toLowerCase().startsWith("/field4/")) { event.preventDefault(); location.href = new URL(`${proxyRoot}${target.pathname.slice(8)}${target.search}${target.hash}`, window.location.origin).toString(); }
    });
        addEventListener("resize", syncResponsiveVisibility); syncResponsiveVisibility(); loadBuildInfo(); load().catch(error => { panel.querySelector('[data-role="list"]').textContent = error.message; });
    };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", install, { once:true });
    else install();
})();
