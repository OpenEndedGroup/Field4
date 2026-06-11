import { sendWithReply } from "./afield.mjs";

const HistoryLens = (() => {
    const STATE = new WeakMap();
    let requestSerial = 0;

    function ensureStyles() {
        if (document.getElementById("history-lens-styles")) return;
        const style = document.createElement("style");
        style.id = "history-lens-styles";
        style.textContent = `
.history-lens-zone {
    box-sizing: border-box;
    height: 100%;
    padding: 4px 0;
    color: #d8d8d8;
    font-family: "Iosevka Web", Iosevka, ui-monospace, monospace;
    font-size: 60%;
    position: relative;
    z-index: 20;
    pointer-events: auto;
}
.history-lens-panel {
    box-sizing: border-box;
    height: 100%;
    display: flex;
    flex-direction: column;
    width: calc(100% - 8px);
    margin-left: 3px;
    padding: 6px 6px 4px 6px;
    border-radius: 8px;
    background: #000;
    overflow: hidden;
    position: relative;
    z-index: 21;
    pointer-events: auto;
}
.history-lens-chrome {
    box-sizing: border-box;
    display: grid;
    grid-template-columns: auto auto auto minmax(0, 1fr) auto auto auto auto;
    align-items: center;
    gap: 4px;
    min-width: 0;
    padding: 0;
    background: transparent;
    position: relative;
    transform: translateY(2px);
    z-index: 22;
    pointer-events: auto;
}
.history-lens-title {
    color: #65cdf2;
    margin-right: 8px;
    white-space: nowrap;
}
.history-lens-meta {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: #9da3a8;
}
.history-lens-rollback-label {
    color: #9da3a8;
    white-space: nowrap;
}
.history-lens-button {
    appearance: none;
    border: 2px solid #34383d;
    border-radius: 5px;
    background: #050505;
    color: #dcdcdc;
    font: inherit;
    line-height: 1;
    padding: 4px 6px;
    cursor: pointer;
    pointer-events: auto;
    position: relative;
    z-index: 23;
}
.history-lens-button:hover:not(:disabled) {
    border-color: #65cdf2;
    color: #65cdf2;
}
.history-lens-button:disabled {
    opacity: 0.36;
    cursor: default;
}
.history-lens-button.icon {
    width: 20px;
    height: 20px;
    padding: 0;
}
.history-lens-margin {
    box-sizing: border-box;
    height: 100%;
    border-left: 4px solid rgba(255, 255, 255, 0.23);
    border-top: 4px solid rgba(255, 255, 255, 0.23);
    border-bottom: 4px solid rgba(255, 255, 255, 0.23);
    margin-left: 2px;
    opacity: 0.5;
    z-index: 1000;
    border-radius: 4px;
    padding-top: 4px;
    padding-left: 2px;
    pointer-events: auto;
    user-select: none;
}
.history-lens-margin-close {
    cursor: pointer;
    pointer-events: auto;
}
.history-lens-variants {
    box-sizing: border-box;
    display: none;
    flex: 1 1 auto;
    min-height: 0;
    margin-top: 6px;
    padding: 4px 0;
    overflow-y: auto;
    overflow-x: hidden;
    background: #232323;
    border-radius: 6px;
    scrollbar-width: thin;
    scrollbar-color: #111 transparent;
}
.history-lens-variants.has-history {
    display: block;
}
.history-lens-variants::-webkit-scrollbar {
    width: 6px;
}
.history-lens-variants::-webkit-scrollbar-thumb {
    background: #111;
    border-radius: 6px;
}
.history-lens-variants::-webkit-scrollbar-track {
    background: transparent;
}
.history-lens-variant {
    box-sizing: border-box;
    display: grid;
    grid-template-columns: minmax(64px, auto) minmax(0, 1fr);
    align-items: start;
    gap: 8px;
    padding: 5px 8px;
    border-left: 3px solid transparent;
    cursor: pointer;
    color: #d8d8d8;
}
.history-lens-variant:hover {
    background: #2a2a2a;
}
.history-lens-variant.active {
    border-left-color: #65cdf2;
    background: #1f3036;
}
.history-lens-variant-version {
    color: #9da3a8;
    white-space: nowrap;
}
.history-lens-variant-value {
    min-width: 0;
    margin: 0;
    overflow: visible;
    color: #f0f0f0;
    font: inherit;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    line-height: 1.25;
}
.history-lens-empty-value {
    color: #9da3a8;
}
.history-lens-token-keyword {
    color: #6fb7e9;
}
.history-lens-token-number {
    color: #d6c46f;
}
.history-lens-token-string {
    color: #c79575;
}
.history-lens-token-comment {
    color: #7d858b;
}
.history-lens-token-punctuation {
    color: #d6d6d6;
}
.history-lens-changed {
    border-radius: 2px;
    background: rgba(101, 205, 242, 0.18);
    box-shadow: 0 0 0 1px rgba(101, 205, 242, 0.08) inset;
}
`;
        document.head.appendChild(style);
    }

    function rpc(name, payload) {
        return new Promise((resolve) => {
            const callbackName = `history-lens-${name}-${Date.now()}-${requestSerial++}`;
            sendWithReply(name, payload || {}, callbackName, (_message, response) => {
                resolve(response || {});
            });
        });
    }

    function splitLines(text) {
        const normalized = String(text || "").replace(/\r\n/g, "\n").replace(/\r/g, "\n");
        return normalized ? normalized.split("\n") : [];
    }

    function escapeHtml(text) {
        return String(text ?? "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    const CODE_KEYWORDS = new Set([
        "as", "break", "by", "catch", "class", "continue", "do", "else", "false", "finally",
        "for", "fun", "if", "in", "interface", "is", "null", "object", "package", "return",
        "super", "this", "throw", "true", "try", "typealias", "val", "var", "when", "while",
        "loop", "import"
    ]);

    function changedMaskAgainst(value, compareValue) {
        const text = String(value ?? "");
        if (compareValue == null) return new Array(text.length).fill(false);
        const compare = String(compareValue ?? "");
        const mask = new Array(text.length).fill(false);
        if (!text || text === compare) return mask;

        let prefix = 0;
        const prefixLimit = Math.min(text.length, compare.length);
        while (prefix < prefixLimit && text[prefix] === compare[prefix]) {
            prefix += 1;
        }

        let suffix = 0;
        const suffixLimit = Math.min(text.length - prefix, compare.length - prefix);
        while (
            suffix < suffixLimit &&
            text[text.length - 1 - suffix] === compare[compare.length - 1 - suffix]
        ) {
            suffix += 1;
        }

        for (let i = prefix; i < text.length - suffix; i += 1) {
            mask[i] = true;
        }
        return mask;
    }

    function tokenClass(token) {
        if (/^\/\/.*/.test(token)) return "history-lens-token-comment";
        if (/^"(?:\\.|[^"\\])*"$/.test(token) || /^'(?:\\.|[^'\\])*'$/.test(token)) return "history-lens-token-string";
        if (/^\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(token)) return "history-lens-token-number";
        if (/^[A-Za-z_]\w*$/.test(token)) return CODE_KEYWORDS.has(token) ? "history-lens-token-keyword" : "";
        if (/^[{}()[\].,;:+\-*/%=<>!&|?]+$/.test(token)) return "history-lens-token-punctuation";
        return "";
    }

    function emitHighlightedRun(text, cssClass, changed) {
        if (!text) return "";
        const classes = [cssClass, changed ? "history-lens-changed" : ""].filter(Boolean).join(" ");
        const escaped = escapeHtml(text);
        return classes ? `<span class="${classes}">${escaped}</span>` : escaped;
    }

    function emitHighlightedSegment(text, offset, cssClass, changedMask) {
        if (!text) return "";
        let html = "";
        let start = 0;
        let changed = Boolean(changedMask[offset]);
        for (let i = 1; i <= text.length; i += 1) {
            const nextChanged = i < text.length ? Boolean(changedMask[offset + i]) : !changed;
            if (i === text.length || nextChanged !== changed) {
                html += emitHighlightedRun(text.slice(start, i), cssClass, changed);
                start = i;
                changed = nextChanged;
            }
        }
        return html;
    }

    function renderCodeValue(value, compareValue) {
        const text = String(value ?? "");
        if (!text.length) return "<span class=\"history-lens-empty-value\">∅</span>";
        const changedMask = changedMaskAgainst(text, compareValue);
        const tokenPattern = /\/\/.*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|[A-Za-z_]\w*|[{}()[\].,;:+\-*/%=<>!&|?]+/g;
        let html = "";
        let lastIndex = 0;
        for (const match of text.matchAll(tokenPattern)) {
            const token = match[0];
            const index = match.index || 0;
            html += emitHighlightedSegment(text.slice(lastIndex, index), lastIndex, "", changedMask);
            html += emitHighlightedSegment(token, index, tokenClass(token), changedMask);
            lastIndex = index + token.length;
        }
        html += emitHighlightedSegment(text.slice(lastIndex), lastIndex, "", changedMask);
        return html;
    }

    function formatDuration(ms) {
        const abs = Math.max(0, Math.floor(ms || 0));
        if (abs < 1000) return "just now";
        const minutes = Math.floor(abs / 60000);
        if (minutes < 1) return `${Math.floor(abs / 1000)}s ago`;
        if (minutes < 60) return `${minutes}m ago`;
        const hours = Math.floor(minutes / 60);
        if (hours < 24) return `${hours}h ago`;
        const days = Math.floor(hours / 24);
        if (days < 7) return `${days}d ago`;
        const weeks = Math.floor(days / 7);
        if (weeks < 6) return `${weeks}wk ago`;
        const months = Math.floor(days / 30);
        if (months < 24) return `${months}mo ago`;
        return `${Math.floor(days / 365)}y ago`;
    }

    function formatBytes(bytes) {
        const value = Number(bytes) || 0;
        if (value < 1024) return `${value} B`;
        const units = ["KB", "MB", "GB", "TB"];
        let n = value / 1024;
        let index = 0;
        while (n >= 1024 && index < units.length - 1) {
            n /= 1024;
            index += 1;
        }
        const rounded = n >= 100 ? n.toFixed(0) : n >= 10 ? n.toFixed(1) : n.toFixed(2);
        return `${rounded} ${units[index]}`;
    }

    function versionLabel(version) {
        if (!version) return "No version";
        const name = String(version.message || "").trim();
        if (name && name !== "Current") return `#${version.index} ${name}`;
        return `#${version.index}`;
    }

    function versionMeta(version) {
        if (!version) return "";
        const boxes = version.boxCount === 1 ? "1 box" : `${version.boxCount || 0} boxes`;
        return `${boxes} · ${formatBytes(version.byteSize)} · ${formatDuration(Date.now() - (Number(version.timestamp) || 0))}`;
    }

    function contextFromEditor(editor) {
        const selected = window.currentSelectedCode;
        const len = Number(selected && selected.length) || 0;
        if (len !== 1) return null;
        const uid = selected[0] && selected[0].uid;
        const property = String(window.currentSelectedProperty || window.selectedProperty || "code");
        const model = editor.getModel();
        if (!uid || !model) return null;

        const selection = editor.getSelection();
        const empty = selection.isEmpty();
        const startLine = empty ? selection.positionLineNumber : Math.min(selection.startLineNumber, selection.endLineNumber);
        const endLine = empty ? selection.positionLineNumber : Math.max(selection.startLineNumber, selection.endLineNumber);
        const selectedText = empty ? model.getLineContent(startLine) : model.getValueInRange(selection);
        const startColumn = empty ? 1 : selection.startColumn;
        const endColumn = empty ? selectedText.length + 1 : selection.endColumn;
        return {
            uid,
            property,
            startLine,
            endLine,
            startColumn,
            endColumn,
            emptySelection: empty,
            selectedText,
            propertyText: model.getValue()
        };
    }

    function stateFor(editor) {
        let state = STATE.get(editor);
        if (!state) {
            state = {
                zoneId: null,
                requestId: 0,
                variantRequestId: 0,
                versions: [],
                cursor: 0,
                variants: [],
                variantScrollFrame: null,
                suppressVariantReveal: false,
                context: null,
                zone: null,
                dom: null,
                marginDom: null
            };
            STATE.set(editor, state);
        }
        return state;
    }

    function updateChrome(state) {
        const version = state.versions[state.cursor] || null;
        const title = state.dom?.querySelector(".history-lens-title");
        const meta = state.dom?.querySelector(".history-lens-meta");
        const prev = state.dom?.querySelector("[data-action='previous']");
        const next = state.dom?.querySelector("[data-action='next']");
        if (title) title.textContent = "history";
        if (meta) meta.textContent = version ? `${versionLabel(version)} · ${versionMeta(version)}` : "No matching versions";
        if (prev) prev.disabled = state.cursor <= 0;
        if (next) next.disabled = state.cursor >= state.versions.length - 1;
    }

    function resizeLens(editor, state, lines) {
        if (!state.zone || state.zoneId == null || state.zone.heightInLines === lines) return;
        state.zone.heightInLines = lines;
        editor.changeViewZones((accessor) => accessor.layoutZone(state.zoneId));
    }

    function extractSelectedRange(text, context) {
        const lines = splitLines(text);
        if (!lines.length || !context) return "";
        const first = Math.max(0, Math.min(lines.length - 1, (Number(context.startLine) || 1) - 1));
        const last = Math.max(first, Math.min(lines.length - 1, (Number(context.endLine) || Number(context.startLine) || 1) - 1));
        const startColumn = Math.max(1, Number(context.startColumn) || 1);
        const endColumn = Math.max(startColumn, Number(context.endColumn) || startColumn);
        if (first === last) {
            const line = lines[first] || "";
            if (context.emptySelection) return line;
            return line.slice(startColumn - 1, Math.max(startColumn - 1, endColumn - 1));
        }
        const out = [];
        for (let lineIndex = first; lineIndex <= last; lineIndex += 1) {
            const line = lines[lineIndex] || "";
            if (lineIndex === first) out.push(line.slice(startColumn - 1));
            else if (lineIndex === last) out.push(line.slice(0, Math.max(0, endColumn - 1)));
            else out.push(line);
        }
        return out.join("\n");
    }

    function variantRows(state) {
        return Array.from(state.dom?.querySelectorAll(".history-lens-variant") || []);
    }

    function updateVariantActive(state) {
        const version = state.versions[state.cursor] || null;
        const activeVersionIndex = version ? Number(version.index) : null;
        let activeRow = null;
        for (const row of variantRows(state)) {
            const active = Number(row.dataset.versionIndex) === activeVersionIndex;
            row.classList.toggle("active", active);
            if (active) activeRow = row;
        }
        if (activeRow && !state.suppressVariantReveal) {
            activeRow.scrollIntoView({ block: "nearest" });
        }
    }

    function renderVariants(editor, state, loading = false) {
        const body = state.dom?.querySelector(".history-lens-variants");
        if (!body) return;
        if (!state.versions.length) {
            body.classList.remove("has-history");
            body.innerHTML = "";
            resizeLens(editor, state, 2);
            return;
        }
        body.classList.add("has-history");
        resizeLens(editor, state, 8);
        if (loading) {
            body.innerHTML = `<div class="history-lens-variant"><div class="history-lens-variant-version">...</div><pre class="history-lens-variant-value">loading values</pre></div>`;
            return;
        }
        const variants = Array.isArray(state.variants) ? state.variants : [];
        body.innerHTML = variants.length
            ? variants.map((variant, index) => {
                const compare = variants[index === 0 ? 1 : index - 1]?.value;
                return `
                <div class="history-lens-variant" data-version-index="${Number(variant.version.index)}">
                    <div class="history-lens-variant-version">${escapeHtml(versionLabel(variant.version))}</div>
                    <pre class="history-lens-variant-value">${renderCodeValue(variant.value, compare)}</pre>
                </div>
            `;
            }).join("")
            : `<div class="history-lens-variant"><div class="history-lens-variant-version">...</div><pre class="history-lens-variant-value">no distinct values</pre></div>`;
        updateVariantActive(state);
    }

    async function loadVariants(editor, state) {
        const variantRequestId = ++state.variantRequestId;
        state.variants = [];
        renderVariants(editor, state, true);
        const variants = [];
        const firstValue = Symbol("first");
        let previousValue = firstValue;
        for (let i = state.versions.length - 1; i >= 0; i -= 1) {
            if (variantRequestId !== state.variantRequestId) return;
            const version = state.versions[i];
            const response = await rpc("history-property-version", {
                uid: state.context.uid,
                property: state.context.property,
                versionIndex: version.index
            });
            if (variantRequestId !== state.variantRequestId) return;
            const value = extractSelectedRange(response.value || "", state.context);
            if (previousValue === firstValue || value !== previousValue) {
                variants.push({ version, value });
                previousValue = value;
            }
        }
        state.variants = variants;
        renderVariants(editor, state, false);
    }

    function setCursorToVersion(editor, state, versionIndex, suppressReveal = false) {
        const target = state.versions.findIndex((version) => Number(version.index) === Number(versionIndex));
        if (target < 0) return;
        if (target === state.cursor) {
            updateVariantActive(state);
            return;
        }
        state.cursor = target;
        state.suppressVariantReveal = suppressReveal;
        Promise.resolve(showVersion(editor, state)).finally(() => {
            state.suppressVariantReveal = false;
        });
    }

    function selectVariantFromScroll(editor, state) {
        const body = state.dom?.querySelector(".history-lens-variants");
        if (!body) return;
        const rows = variantRows(state).filter((row) => row.dataset.versionIndex);
        if (!rows.length) return;
        const top = body.scrollTop;
        let best = rows[0];
        let bestDistance = Math.abs(best.offsetTop - top);
        for (const row of rows) {
            const distance = Math.abs(row.offsetTop - top);
            if (distance < bestDistance) {
                best = row;
                bestDistance = distance;
            }
        }
        setCursorToVersion(editor, state, Number(best.dataset.versionIndex), true);
    }

    function close(editor) {
        const state = STATE.get(editor);
        if (!state) return;
        if (state.zoneId != null) {
            editor.changeViewZones((accessor) => accessor.removeZone(state.zoneId));
        }
        state.zoneId = null;
        state.zone = null;
        state.dom = null;
        state.marginDom = null;
        state.variantRequestId++;
        if (window.AIDiff) window.AIDiff.clear(editor);
    }

    async function navigate(editor, delta) {
        const state = stateFor(editor);
        if (!state.versions.length) return;
        const nextIndex = Math.max(0, Math.min(state.versions.length - 1, state.cursor + delta));
        if (nextIndex === state.cursor && delta !== 0) return;
        state.cursor = nextIndex;
        await showVersion(editor, state);
    }

    function preserveLensScreenY(editor, state, work) {
        const before = state.dom?.getBoundingClientRect().top;
        work();
        const adjust = () => {
            if (before == null || !state.dom || typeof editor.getScrollTop !== "function" || typeof editor.setScrollTop !== "function") {
                return;
            }
            const after = state.dom.getBoundingClientRect().top;
            const delta = after - before;
            if (Math.abs(delta) > 0.5) {
                editor.setScrollTop(Number(editor.getScrollTop()) + delta);
            }
        };
        adjust();
        requestAnimationFrame(adjust);
    }

    async function showVersion(editor, state) {
        const version = state.versions[state.cursor];
        updateChrome(state);
        updateVariantActive(state);
        if (!version) {
            preserveLensScreenY(editor, state, () => {
                renderVariants(editor, state, false);
                if (window.AIDiff) window.AIDiff.clear(editor);
            });
            return;
        }
        updateChrome(state);
        const requestId = ++state.requestId;
        const response = await rpc("history-property-version", {
            uid: state.context.uid,
            property: state.context.property,
            versionIndex: version.index
        });
        if (requestId !== state.requestId) return;
        preserveLensScreenY(editor, state, () => {
            updateChrome(state);
            updateVariantActive(state);
            if (window.AIDiff) {
                window.AIDiff.apply(editor, response.value || "", {
                    monaco: window.globalMonaco,
                    readOnly: true
                });
            }
        });
    }

    async function rollback(editor, scope) {
        const state = stateFor(editor);
        const version = state.versions[state.cursor];
        if (!version || !state.context) return;
        const { uid, property } = state.context;
        const label = versionLabel(version);
        if (scope === "document") {
            if (!window.confirm(`Rollback the whole document to ${label}?`)) return;
            await rpc("restore-history-version", { versionIndex: version.index });
        } else {
            const propertyOnly = scope === "property";
            const message = propertyOnly
                ? `Rollback ${property} on this box to ${label}?`
                : `Rollback this box to ${label}?`;
            if (!window.confirm(message)) return;
            await rpc("cherry-pick-history-version", {
                versionIndex: version.index,
                selections: [{ uid, properties: propertyOnly ? [property] : [] }],
                includeTopology: !propertyOnly
            });
        }
        close(editor);
    }

    function createDom(editor, state) {
        const root = document.createElement("div");
        root.className = "history-lens-zone";
        root.innerHTML = `
            <div class="history-lens-panel">
                <div class="history-lens-chrome">
                    <button class="history-lens-button icon" data-action="previous" title="Previous matching version">‹</button>
                    <button class="history-lens-button icon" data-action="next" title="Next matching version">›</button>
                    <div class="history-lens-title"></div>
                    <div class="history-lens-meta"></div>
                    <div class="history-lens-rollback-label">Rollback:</div>
                    <button class="history-lens-button" data-action="property">Property</button>
                    <button class="history-lens-button" data-action="box">Box</button>
                    <button class="history-lens-button" data-action="document">Document</button>
                </div>
                <div class="history-lens-variants"></div>
            </div>
        `;
        for (const eventName of ["pointerdown", "mousedown", "mouseup"]) {
            root.addEventListener(eventName, (event) => {
                event.stopPropagation();
            });
        }
        root.addEventListener("click", (event) => {
            const target = event.target instanceof Element ? event.target : event.target?.parentElement;
            const button = target?.closest("[data-action]");
            if (!button) return;
            event.preventDefault();
            event.stopPropagation();
            const action = button.dataset.action;
            if (action === "previous") navigate(editor, -1);
            else if (action === "next") navigate(editor, 1);
            else if (action === "property") rollback(editor, "property");
            else if (action === "box") rollback(editor, "box");
            else if (action === "document") rollback(editor, "document");
            else if (action === "close") close(editor);
        });
        const variants = root.querySelector(".history-lens-variants");
        variants?.addEventListener("scroll", () => {
            if (state.variantScrollFrame != null) return;
            state.variantScrollFrame = requestAnimationFrame(() => {
                state.variantScrollFrame = null;
                selectVariantFromScroll(editor, state);
            });
        });
        variants?.addEventListener("wheel", (event) => {
            event.preventDefault();
            event.stopPropagation();
            variants.scrollTop += event.deltaY;
            if (Math.abs(event.deltaX) > Math.abs(event.deltaY)) {
                variants.scrollLeft += event.deltaX;
            }
        }, { passive: false });
        variants?.addEventListener("click", (event) => {
            const target = event.target instanceof Element ? event.target : event.target?.parentElement;
            const row = target?.closest(".history-lens-variant[data-version-index]");
            if (!row) return;
            event.preventDefault();
            event.stopPropagation();
            setCursorToVersion(editor, state, Number(row.dataset.versionIndex), false);
        });
        return root;
    }

    function createMarginDom(editor) {
        const margin = document.createElement("div");
        margin.className = "history-lens-margin";
        margin.innerHTML = `<div class="history-lens-margin-close closeBox hoverable" title="Close history lens">×</div>`;
        for (const eventName of ["pointerdown", "mousedown", "mouseup"]) {
            margin.addEventListener(eventName, (event) => {
                event.stopPropagation();
            });
        }
        margin.addEventListener("click", (event) => {
            event.preventDefault();
            event.stopPropagation();
            close(editor);
        });
        return margin;
    }

    async function open(editor, monaco = window.globalMonaco) {
        ensureStyles();
        if (!editor || !monaco) return;
        const context = contextFromEditor(editor);
        if (!context) {
            window.notify?.("Select one box and property first", "history");
            return;
        }

        close(editor);
        const state = stateFor(editor);
        state.context = context;
        state.versions = [];
        state.variants = [];
        state.cursor = 0;
        state.zone = null;
        state.requestId++;
        state.dom = createDom(editor, state);
        state.marginDom = createMarginDom(editor);

        editor.changeViewZones((accessor) => {
            const zone = {
                afterLineNumber: context.endLine,
                heightInLines: 2,
                domNode: state.dom,
                marginDomNode: state.marginDom,
                suppressMouseDown: false
            };
            state.zone = zone;
            state.zoneId = accessor.addZone(zone);
        });
        editor.revealLineInCenterIfOutsideViewport(context.endLine);
        updateChrome(state);

        const requestId = ++state.requestId;
        const result = await rpc("history-selection-versions", context);
        if (requestId !== state.requestId) return;
        state.versions = Array.isArray(result.versions) ? result.versions : [];
        console.debug("[history-lens] selection versions", {
            uid: context.uid,
            property: context.property,
            lines: `${context.startLine}-${context.endLine}`,
            selectedLength: String(context.selectedText || "").length,
            versions: state.versions.length
        });
        state.cursor = Math.max(0, state.versions.length - 1);
        renderVariants(editor, state, state.versions.length > 0);
        if (state.versions.length > 0) {
            loadVariants(editor, state);
        }
        await showVersion(editor, state);
    }

    return { open, close };
})();

window.HistoryLens = HistoryLens;
