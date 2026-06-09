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
    display: block;
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
        return {
            uid,
            property,
            startLine,
            endLine,
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
                versions: [],
                cursor: 0,
                context: null,
                dom: null,
                marginDom: null
            };
            STATE.set(editor, state);
        }
        return state;
    }

    function setBody(state, text) {
        const meta = state.dom?.querySelector(".history-lens-meta");
        if (meta) meta.textContent = text;
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

    function close(editor) {
        const state = STATE.get(editor);
        if (!state) return;
        if (state.zoneId != null) {
            editor.changeViewZones((accessor) => accessor.removeZone(state.zoneId));
        }
        state.zoneId = null;
        state.dom = null;
        state.marginDom = null;
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
        if (!version) {
            preserveLensScreenY(editor, state, () => {
                setBody(state, "No versions changed this selection.");
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
        state.cursor = 0;
        state.requestId++;
        state.dom = createDom(editor, state);
        state.marginDom = createMarginDom(editor);

        editor.changeViewZones((accessor) => {
            state.zoneId = accessor.addZone({
                afterLineNumber: context.endLine,
                heightInLines: 2,
                domNode: state.dom,
                marginDomNode: state.marginDom,
                suppressMouseDown: false
            });
        });
        editor.revealLineInCenterIfOutsideViewport(context.endLine);
        updateChrome(state);

        const requestId = ++state.requestId;
        const result = await rpc("history-selection-versions", context);
        if (requestId !== state.requestId) return;
        state.versions = Array.isArray(result.versions) ? result.versions : [];
        state.cursor = Math.max(0, state.versions.length - 1);
        await showVersion(editor, state);
    }

    return { open, close };
})();

window.HistoryLens = HistoryLens;
