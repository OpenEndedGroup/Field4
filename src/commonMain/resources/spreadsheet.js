const DEFAULT_ROWS = 20;
const DEFAULT_COLS = 12;
const DEFAULT_COL_WIDTH = 120;
const DEFAULT_ROW_HEIGHT = 32;
const MIN_COL_WIDTH = 56;
const MIN_ROW_HEIGHT = 22;
const HEADER_WIDTH = 34;
const FORMULA_PLACEHOLDER = "Enter value or =kotlin formula";
const SYNC_DEBOUNCE_MS = 300;
const OPEN_RETRY_LIMIT = 32;

const query = new URLSearchParams(window.location.search);
const ownerUid = (query.get("uid") || "").trim();
const propertyName = (query.get("property") || "spreadsheet").trim() || "spreadsheet";
const documentName = (query.get("file") || "").trim();
const username = (query.get("user") || "spreadsheet").trim() || "spreadsheet";
const sessionKey = (query.get("sessionKey") || "").trim();
const scratch = query.get("scratch") === "1";

const state = {
    rows: DEFAULT_ROWS,
    cols: DEFAULT_COLS,
    colWidths: Array(DEFAULT_COLS).fill(DEFAULT_COL_WIDTH),
    rowHeights: Array(DEFAULT_ROWS).fill(DEFAULT_ROW_HEIGHT),
    cells: new Map(),
    activeCell: null,
    selection: { start: null, end: null },
    ws: null,
    reconnectTimer: null,
    syncTimer: null,
    isApplyingRemote: false,
    requestCounter: 1,
    pendingRequests: new Map(),
    formulaMenu: {
        open: false,
        items: [],
        activeIndex: -1,
        lastRequestId: null
    },
    highlightedCells: new Set(),
    highlightedOutputs: new Set(),
    highlightedBoxes: new Set(),
    openAttempts: 0
};

const dom = {
    root: null,
    formulaBar: null,
    formulaInput: null,
    formulaBadge: null,
    refreshButton: null,
    grid: null,
    headerRow: null,
    rowNodes: [],
    menu: null,
    menuList: null,
    status: null
};

init();

function init() {
    injectStyles();
    buildShell();
    wireClipboard();
    wireParentDocumentHooks();
    connectWebSocket();
    window.addEventListener("beforeunload", cleanupParentHighlights);
}

function injectStyles() {
    if (document.getElementById("field-sheet-styles")) return;
    const style = document.createElement("style");
    style.id = "field-sheet-styles";
    style.textContent = `
        @import url("/iosevka.css");

        :root {
            color-scheme: dark;
            --field-cyan-rgb: 72, 201, 255;
            --field-cyan: rgb(var(--field-cyan-rgb));
            --sheet-bg: rgba(0, 0, 0, 0.78);
            --sheet-panel: rgba(7, 10, 14, 0.92);
            --sheet-panel-2: rgba(15, 19, 27, 0.96);
            --sheet-grid: rgba(255, 255, 255, 0.12);
            --sheet-grid-strong: rgba(255, 255, 255, 0.22);
            --sheet-text: rgba(242, 247, 255, 0.92);
            --sheet-muted: rgba(198, 209, 227, 0.62);
            --sheet-header: rgba(255, 255, 255, 0.04);
            --sheet-accent: #8ec5ff;
            --sheet-accent-2: #7bf0c0;
            --sheet-selected: rgba(142, 197, 255, 0.16);
            --sheet-selected-ring: rgba(142, 197, 255, 0.9);
            --sheet-ref: rgba(123, 240, 192, 0.9);
            --sheet-error: #ff9ea1;
            --sheet-error-bg: rgba(120, 37, 46, 0.62);
            --sheet-button: rgba(255, 255, 255, 0.06);
            --sheet-button-hover: rgba(255, 255, 255, 0.12);
            --sheet-output-rgb: 255, 188, 92;
            --sheet-shadow: 0 14px 40px rgba(0, 0, 0, 0.28);
        }

        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            background: transparent;
            color: var(--sheet-text);
            font-family: "Iosevka Web", ui-monospace, monospace;
            overflow: hidden;
        }

        body {
            padding: 0;
        }

        #spreadsheet-root {
            width: 100%;
            height: 100%;
            min-height: 0;
            display: flex;
            align-items: stretch;
            justify-content: stretch;
            overflow: hidden;
        }

        .sheet-root {
            flex: 1 1 auto;
            min-width: 0;
            min-height: 0;
            display: flex;
            flex-direction: column;
            padding: 8px;
            gap: 8px;
            overflow: hidden;
            background:
                radial-gradient(circle at top left, rgba(82, 121, 255, 0.12), transparent 30%),
                radial-gradient(circle at bottom right, rgba(45, 231, 165, 0.08), transparent 24%),
                var(--sheet-bg);
            box-sizing: border-box;
        }

        .sheet-toolbar {
            flex: 0 0 auto;
            display: grid;
            grid-template-columns: 1fr auto;
            gap: 8px;
            align-items: stretch;
            min-height: 34px;
            position: relative;
            z-index: 8;
        }

        .sheet-formula-bar {
            position: relative;
            display: grid;
            grid-template-columns: 1fr 42px;
            gap: 6px;
            align-items: center;
            min-width: 0;
            min-height: 34px;
            background: linear-gradient(180deg, rgba(255,255,255,0.03), rgba(255,255,255,0.015));
            border: 1px solid var(--sheet-grid);
            box-shadow: var(--sheet-shadow);
        }

        .sheet-formula-input {
            width: 100%;
            min-width: 0;
            border: none;
            outline: none;
            background: transparent;
            color: var(--sheet-text);
            font: inherit;
            font-size: 12px;
            padding: 8px 10px;
            box-sizing: border-box;
        }

        .sheet-formula-input::placeholder {
            color: var(--sheet-muted);
        }

        .sheet-formula-badge {
            justify-self: center;
            align-self: stretch;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            min-width: 36px;
            color: var(--sheet-accent-2);
            font-size: 11px;
            border-left: 1px solid var(--sheet-grid);
            letter-spacing: 0.06em;
        }

        .sheet-actions {
            flex: 0 0 auto;
            display: flex;
            align-items: stretch;
            gap: 6px;
        }

        .sheet-button {
            border: 1px solid var(--sheet-grid);
            background: var(--sheet-button);
            color: var(--sheet-text);
            font: inherit;
            font-size: 11px;
            min-height: 34px;
            padding: 0 10px;
            cursor: pointer;
            white-space: nowrap;
            transition: background 120ms ease, border-color 120ms ease;
        }

        .sheet-button:hover {
            background: var(--sheet-button-hover);
            border-color: var(--sheet-grid-strong);
        }

        .sheet-grid {
            flex: 1 1 0;
            min-width: 0;
            min-height: 96px;
            overflow: auto;
            position: relative;
            z-index: 1;
            border: 1px solid var(--sheet-grid);
            background: var(--sheet-panel);
            box-shadow: var(--sheet-shadow);
        }

        .sheet-row {
            display: grid;
        }

        .sheet-header-row {
            position: sticky;
            top: 0;
            z-index: 6;
            background: var(--sheet-panel-2);
        }

        .sheet-corner,
        .sheet-col-header,
        .sheet-row-header {
            position: relative;
            display: flex;
            align-items: center;
            justify-content: center;
            box-sizing: border-box;
            border-right: 1px solid var(--sheet-grid);
            border-bottom: 1px solid var(--sheet-grid-strong);
            background: var(--sheet-header);
            color: var(--sheet-muted);
            font-size: 10px;
            user-select: none;
        }

        .sheet-cell {
            position: relative;
            display: flex;
            align-items: center;
            box-sizing: border-box;
            padding: 4px 8px;
            border-right: 1px solid var(--sheet-grid);
            border-bottom: 1px solid var(--sheet-grid);
            color: var(--sheet-text);
            background: transparent;
            outline: none;
            overflow: hidden;
            white-space: pre-wrap;
            word-break: break-word;
            font-size: 12px;
            line-height: 1.05;
        }

        .sheet-cell[data-type="formula"] {
            color: var(--sheet-accent-2);
        }

        .sheet-cell[data-type="error"],
        .sheet-cell[data-display-error="1"] {
            background: var(--sheet-error-bg);
            color: var(--sheet-error);
        }

        .sheet-cell.sheet-selected {
            background: var(--sheet-selected);
            box-shadow: inset 0 0 0 1px var(--sheet-selected-ring);
        }

        .sheet-cell.sheet-html-cell {
            overflow: hidden;
            padding: 2px 4px;
        }

        .sheet-cell.sheet-html-cell .value-viz {
            font-size: 11px;
            line-height: 1.2;
            max-height: 100%;
            overflow: hidden;
        }

        .sheet-cell.sheet-html-cell em {
            font-style: italic;
            color: var(--sheet-accent);
        }

        .sheet-cell.sheet-html-cell strong {
            font-weight: 600;
            color: #fff;
        }

        .sheet-cell.sheet-html-cell code {
            font-family: inherit;
            background: rgba(255, 255, 255, 0.08);
            padding: 0 3px;
            border-radius: 2px;
            color: var(--sheet-accent-2);
        }

        .sheet-cell.sheet-html-cell svg {
            max-width: 100%;
            max-height: 100%;
        }

        .sheet-cell.sheet-ref-highlight {
            box-shadow:
                inset 0 0 0 1px rgba(var(--field-cyan-rgb), 0.96),
                inset 0 0 0 999px rgba(var(--field-cyan-rgb), 0.09);
        }

        .sheet-cell.sheet-output-highlight {
            box-shadow:
                inset 0 0 0 1px rgba(var(--sheet-output-rgb), 0.96),
                inset 0 0 0 999px rgba(var(--sheet-output-rgb), 0.09);
        }

        .sheet-cell.sheet-ref-highlight.sheet-output-highlight {
            box-shadow:
                inset 0 0 0 1px rgba(var(--field-cyan-rgb), 0.96),
                inset 0 0 0 3px rgba(var(--sheet-output-rgb), 0.68),
                inset 0 0 0 999px rgba(var(--field-cyan-rgb), 0.05);
        }

        .sheet-cell:focus {
            box-shadow: inset 0 0 0 2px var(--sheet-selected-ring);
            z-index: 2;
        }

        .sheet-col-resizer,
        .sheet-row-resizer {
            position: absolute;
            background: transparent;
        }

        .sheet-col-resizer {
            top: 0;
            right: -3px;
            width: 6px;
            height: 100%;
            cursor: col-resize;
        }

        .sheet-row-resizer {
            left: 0;
            bottom: -3px;
            width: 100%;
            height: 6px;
            cursor: row-resize;
        }

        .sheet-menu {
            position: absolute;
            left: 0;
            right: 0;
            top: calc(100% + 4px);
            max-height: 240px;
            overflow: auto;
            border: 1px solid var(--sheet-grid-strong);
            background: rgba(5, 8, 12, 0.98);
            box-shadow: 0 18px 34px rgba(0,0,0,0.38);
            z-index: 20;
        }

        .sheet-menu[hidden] {
            display: none;
        }

        .sheet-menu-list {
            list-style: none;
            margin: 0;
            padding: 0;
        }

        .sheet-menu-item {
            padding: 8px 10px;
            border-bottom: 1px solid rgba(255,255,255,0.06);
            cursor: pointer;
        }

        .sheet-menu-item:last-child {
            border-bottom: none;
        }

        .sheet-menu-item.is-active,
        .sheet-menu-item:hover {
            background: rgba(142, 197, 255, 0.12);
        }

        .sheet-menu-item-label {
            display: flex;
            justify-content: space-between;
            gap: 12px;
            color: var(--sheet-text);
            font-size: 12px;
        }

        .sheet-menu-item-kind {
            color: var(--sheet-muted);
            font-size: 10px;
            text-transform: uppercase;
            letter-spacing: 0.08em;
        }

        .sheet-menu-item-detail {
            margin-top: 3px;
            color: var(--sheet-muted);
            font-size: 10px;
            line-height: 1.2;
        }

        .sheet-status {
            display: none;
        }
    `;
    document.head.appendChild(style);
}

function buildShell() {
    const mount = document.getElementById("spreadsheet-root");
    const wrapper = document.createElement("div");
    wrapper.className = "sheet-root";

    const toolbar = document.createElement("div");
    toolbar.className = "sheet-toolbar";

    const formulaBar = document.createElement("div");
    formulaBar.className = "sheet-formula-bar";
    formulaBar.innerHTML = `
        <input class="sheet-formula-input" type="text" spellcheck="false" placeholder="${FORMULA_PLACEHOLDER}" />
        <div class="sheet-formula-badge">fx</div>
        <div class="sheet-menu" hidden><ul class="sheet-menu-list"></ul></div>
    `;

    const actions = document.createElement("div");
    actions.className = "sheet-actions";
    actions.innerHTML = `
        <button class="sheet-button" type="button" data-action="refresh">refresh</button>
    `;

    const grid = document.createElement("div");
    grid.className = "sheet-grid";

    const status = document.createElement("div");
    status.className = "sheet-status";

    toolbar.appendChild(formulaBar);
    toolbar.appendChild(actions);
    wrapper.appendChild(toolbar);
    wrapper.appendChild(grid);
    wrapper.appendChild(status);
    mount.appendChild(wrapper);

    dom.root = wrapper;
    dom.formulaBar = formulaBar;
    dom.formulaInput = formulaBar.querySelector(".sheet-formula-input");
    dom.formulaBadge = formulaBar.querySelector(".sheet-formula-badge");
    dom.menu = formulaBar.querySelector(".sheet-menu");
    dom.menuList = formulaBar.querySelector(".sheet-menu-list");
    dom.grid = grid;
    dom.status = status;
    dom.refreshButton = actions.querySelector('[data-action="refresh"]');

    dom.refreshButton.addEventListener("click", refreshSheet);

    dom.formulaInput.addEventListener("focus", updateReferenceHighlightsFromFormula);
    dom.formulaInput.addEventListener("blur", () => {
        window.setTimeout(() => {
            if (!dom.formulaBar.contains(document.activeElement)) {
                closeCompletionMenu();
                updateReferenceHighlightsFromFormula();
            }
        }, 0);
    });
    dom.formulaInput.addEventListener("input", handleFormulaInput);
    dom.formulaInput.addEventListener("keydown", handleFormulaKeydown);
    dom.formulaInput.addEventListener("click", updateReferenceHighlightsFromFormula);
    document.addEventListener("pointerdown", handleOutsidePointerDown, true);

    renderGrid();
    syncFormulaInput();
}

function connectWebSocket() {
    const socket = new WebSocket(buildWsUrl());
    state.ws = socket;
    console.log("[sheet-ws] connecting to", buildWsUrl());
    socket.addEventListener("open", () => {
        console.log("[sheet-ws] connected");
        state.openAttempts = 0;
        sendHello();
        window.setTimeout(() => openSheet(0), 60);
    });
    socket.addEventListener("message", (event) => handleSocketMessage(event));
    socket.addEventListener("close", (event) => {
        console.warn("[sheet-ws] closed code=", event.code, "reason=", event.reason, "wasClean=", event.wasClean);
        scheduleReconnect();
    });
    socket.addEventListener("error", (event) => {
        console.error("[sheet-ws] error", event);
        socket.close();
    });
}

function buildWsUrl() {
    const override = query.get("ws");
    if (override) return override;
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const host = query.get("wsHost") || window.location.host;
    const path = query.get("wsPath") || "/ws";
    return `${protocol}//${host}${path}`;
}

function sendHello() {
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
    const payload = {
        filename: documentName,
        username,
        sessionKey: sessionKey || null,
        scratch
    };
    const envelope = {
        name: "hello",
        uid: `sheet_hello_${Date.now()}`,
        payload
    };
    state.ws.send(JSON.stringify(envelope));
}

function openSheet(attempt) {
    sendRequest("open", { uid: ownerUid, property: propertyName, watch: true }, (message) => {
        if (!message || message.ok === false) {
            const errorText = String(message?.error || "");
            if (errorText.includes("not attached") && attempt < OPEN_RETRY_LIMIT) {
                window.setTimeout(() => openSheet(attempt + 1), 80 + attempt * 40);
            }
            return;
        }
        if (message.snapshot) {
            applySnapshot(message.snapshot);
        }
    });
}

function scheduleReconnect() {
    if (state.reconnectTimer) return;
    const droppedCount = state.pendingRequests.size;
    if (droppedCount > 0) {
        console.warn("[sheet-ws] reconnect: dropping %d pending requests", droppedCount);
        state.pendingRequests.clear();
    }
    console.log("[sheet-ws] scheduling reconnect in 1s");
    state.reconnectTimer = window.setTimeout(() => {
        state.reconnectTimer = null;
        connectWebSocket();
    }, 1000);
}

function handleSocketMessage(event) {
    let message = null;
    try {
        message = JSON.parse(event.data);
    } catch (_error) {
        console.warn("[sheet-ws] recv unparseable (%d chars)", event.data?.length);
        return;
    }
    if (!message) return;

    const msgType = message.type || (message.reqId ? "reply" : "unknown");
    const ok = message.ok !== false;
    console.log("[sheet-ws] recv type=%s reqId=%s ok=%s (%d chars) pending=%d",
        msgType, message.reqId || "-", ok, event.data.length, state.pendingRequests.size);
    if (!ok && message.error) {
        console.error("[sheet-ws] server error:", message.error);
    }

    if (message.reqId && state.pendingRequests.has(message.reqId)) {
        const callback = state.pendingRequests.get(message.reqId);
        state.pendingRequests.delete(message.reqId);
        callback(message);
    }

    if (message.type === "sheet" && message.snapshot) {
        applySnapshot(message.snapshot);
        return;
    }
    if (message.type === "completion" && Array.isArray(message.items)) {
        openCompletionMenu(message.items);
    }
}

function sendRequest(op, extra = {}, callback = null) {
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
        console.warn("[sheet-ws] sendRequest('%s') skipped — ws not open (readyState=%s)", op, state.ws?.readyState);
        return null;
    }
    const reqId = `sheet_req_${state.requestCounter++}`;
    if (callback) {
        state.pendingRequests.set(reqId, callback);
    }
    const payload = JSON.stringify({
        channel: "__spreadsheet__",
        reqId,
        op,
        uid: ownerUid,
        property: propertyName,
        ...extra
    });
    console.log("[sheet-ws] send op=%s reqId=%s (%d chars) pending=%d", op, reqId, payload.length, state.pendingRequests.size);
    state.ws.send(payload);
    return reqId;
}

function refreshSheet() {
    sendRequest("refresh", {}, (message) => {
        if (message?.snapshot) applySnapshot(message.snapshot);
    });
}

function applySnapshot(snapshot) {
    if (!snapshot) return;
    const focusedEdit = getFocusedEditingCell();
    state.isApplyingRemote = true;
    try {
        state.rows = Number(snapshot.rows) || DEFAULT_ROWS;
        state.cols = Number(snapshot.cols) || DEFAULT_COLS;
        state.colWidths = normalizeLengths(snapshot.colWidths, state.cols, DEFAULT_COL_WIDTH);
        state.rowHeights = normalizeLengths(snapshot.rowHeights, state.rows, DEFAULT_ROW_HEIGHT);
        state.cells = new Map();
        (snapshot.cells || []).forEach((cell) => {
            const row = Number(cell.row);
            const col = Number(cell.col);
            if (!Number.isInteger(row) || !Number.isInteger(col)) return;
            state.cells.set(cellKey(row, col), {
                input: cell.input || "",
                display: cell.display == null ? "" : String(cell.display),
                type: cell.type || classifyInput(cell.input || ""),
                errorDetail: cell.errorDetail || "",
                htmlDisplay: cell.htmlDisplay || null
            });
        });
        if (focusedEdit) {
            const remoteCell = state.cells.get(focusedEdit.key);
            if (!remoteCell || remoteCell.input !== focusedEdit.input) {
                state.cells.set(focusedEdit.key, {
                    ...remoteCell,
                    input: focusedEdit.input,
                    display: focusedEdit.input,
                    type: classifyInput(focusedEdit.input),
                    errorDetail: remoteCell?.errorDetail || ""
                });
            }
        }
        renderGrid();
        syncFormulaInput();
        updateReferenceHighlightsFromFormula();
    } finally {
        state.isApplyingRemote = false;
    }
}

function normalizeLengths(source, count, fallback) {
    const out = Array(count).fill(fallback);
    if (!Array.isArray(source)) return out;
    for (let i = 0; i < count; i += 1) {
        const value = Number(source[i]);
        out[i] = Number.isFinite(value) ? value : fallback;
    }
    return out;
}

function getFocusedEditingCell() {
    if (!state.activeCell || document.activeElement !== dom.formulaInput) return null;
    return {
        key: cellKey(state.activeCell.row, state.activeCell.col),
        input: dom.formulaInput.value || ""
    };
}

function scheduleSync() {
    if (state.isApplyingRemote) return;
    window.clearTimeout(state.syncTimer);
    state.syncTimer = window.setTimeout(pushState, SYNC_DEBOUNCE_MS);
}

function pushState() {
    state.syncTimer = null;
    console.log("[sheet-ws] pushState (update) firing");
    sendRequest("update", { state: exportStoredState() }, (message) => {
        console.log("[sheet-ws] pushState callback: ok=%s snapshot=%s", message?.ok !== false, !!message?.snapshot);
        if (message?.snapshot) applySnapshot(message.snapshot);
    });
}

function exportStoredState() {
    const cells = [];
    state.cells.forEach((cell, key) => {
        if (!cell || !String(cell.input || "").trim()) return;
        const coords = parseCellKey(key);
        if (!coords) return;
        cells.push({
            row: coords.row,
            col: coords.col,
            input: cell.input
        });
    });
    return {
        rows: state.rows,
        cols: state.cols,
        colWidths: state.colWidths.slice(),
        rowHeights: state.rowHeights.slice(),
        cells
    };
}

function renderGrid() {
    if (!dom.grid) return;
    dom.grid.innerHTML = "";
    dom.rowNodes = [];

    const headerRow = document.createElement("div");
    headerRow.className = "sheet-row sheet-header-row";
    headerRow.appendChild(buildCornerCell());
    for (let col = 0; col < state.cols; col += 1) {
        headerRow.appendChild(buildColumnHeader(col));
    }
    dom.grid.appendChild(headerRow);
    dom.headerRow = headerRow;

    for (let row = 0; row < state.rows; row += 1) {
        const rowNode = buildRow(row);
        dom.grid.appendChild(rowNode);
        dom.rowNodes.push(rowNode);
    }

    applySizing();
    renderSelectionOverlay();
}

function buildCornerCell() {
    const corner = document.createElement("div");
    corner.className = "sheet-corner";
    return corner;
}

function buildColumnHeader(colIndex) {
    const header = document.createElement("div");
    header.className = "sheet-col-header";
    header.textContent = columnLabel(colIndex);
    header.dataset.col = String(colIndex);

    const resizer = document.createElement("div");
    resizer.className = "sheet-col-resizer";
    resizer.addEventListener("pointerdown", (event) => startColResize(event, colIndex));
    header.appendChild(resizer);
    return header;
}

function buildRow(rowIndex) {
    const row = document.createElement("div");
    row.className = "sheet-row";
    row.dataset.row = String(rowIndex);

    const rowHeader = document.createElement("div");
    rowHeader.className = "sheet-row-header";
    rowHeader.textContent = String(rowIndex + 1);

    const resizer = document.createElement("div");
    resizer.className = "sheet-row-resizer";
    resizer.addEventListener("pointerdown", (event) => startRowResize(event, rowIndex));
    rowHeader.appendChild(resizer);
    row.appendChild(rowHeader);

    for (let col = 0; col < state.cols; col += 1) {
        row.appendChild(buildCell(rowIndex, col));
    }

    return row;
}

function buildCell(rowIndex, colIndex) {
    const node = document.createElement("div");
    node.className = "sheet-cell";
    node.tabIndex = 0;
    node.dataset.row = String(rowIndex);
    node.dataset.col = String(colIndex);
    updateCellNode(node, rowIndex, colIndex);

    node.addEventListener("focus", () => {
        state.activeCell = { row: rowIndex, col: colIndex };
        setSelection({ row: rowIndex, col: colIndex }, { row: rowIndex, col: colIndex });
        syncFormulaInput();
        updateReferenceHighlightsFromFormula();
    });

    node.addEventListener("mousedown", (event) => {
        if (maybeInsertCellRef(rowIndex, colIndex, event)) {
            event.preventDefault();
            event.stopPropagation();
            return;
        }
        if (event.shiftKey && state.selection.start) {
            setSelection(state.selection.start, { row: rowIndex, col: colIndex });
        } else {
            setSelection({ row: rowIndex, col: colIndex }, { row: rowIndex, col: colIndex });
        }
    });

    node.addEventListener("dblclick", () => beginFormulaEditFromCell(rowIndex, colIndex, null, { append: true }));
    node.addEventListener("keydown", (event) => handleCellKeydown(event, rowIndex, colIndex));
    return node;
}

function updateCellNode(node, rowIndex, colIndex) {
    const cell = getCell(rowIndex, colIndex);
    node.dataset.type = cell.type;
    if (cell.display === "#ERR" || cell.display === "#CYCLE") {
        node.dataset.displayError = "1";
    } else {
        delete node.dataset.displayError;
    }
    node.title = cell.errorDetail || "";
    if (cell.htmlDisplay) {
        node.innerHTML = cell.htmlDisplay;
        node.classList.add("sheet-html-cell");
    } else {
        const display = cell.display == null ? "" : String(cell.display);
        const md = cell.type === "text" ? renderInlineMarkdown(display) : null;
        if (md !== null) {
            node.innerHTML = md;
            node.classList.add("sheet-html-cell");
        } else {
            node.textContent = display;
            node.classList.remove("sheet-html-cell");
        }
    }
}

function renderInlineMarkdown(text) {
    if (!text || !/[_*`]/.test(text)) return null;
    const escaped = text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    const rendered = escaped
        .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
        .replace(/(?<![\w])_(.+?)_(?![\w])/g, "<em>$1</em>")
        .replace(/`([^`]+)`/g, "<code>$1</code>");
    if (rendered === escaped) return null;
    return rendered;
}

function applySizing() {
    const template = `${HEADER_WIDTH}px ${state.colWidths.map((width) => `${width}px`).join(" ")}`;
    if (dom.headerRow) {
        dom.headerRow.style.gridTemplateColumns = template;
        dom.headerRow.style.height = `${DEFAULT_ROW_HEIGHT}px`;
    }
    dom.rowNodes.forEach((rowNode, index) => {
        rowNode.style.gridTemplateColumns = template;
        rowNode.style.height = `${state.rowHeights[index] || DEFAULT_ROW_HEIGHT}px`;
    });
}

function startColResize(event, colIndex) {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = state.colWidths[colIndex];

    const move = (moveEvent) => {
        const next = Math.max(MIN_COL_WIDTH, startWidth + (moveEvent.clientX - startX));
        state.colWidths[colIndex] = next;
        applySizing();
    };

    const up = () => {
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", up);
        scheduleSync();
    };

    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
}

function startRowResize(event, rowIndex) {
    event.preventDefault();
    const startY = event.clientY;
    const startHeight = state.rowHeights[rowIndex];

    const move = (moveEvent) => {
        const next = Math.max(MIN_ROW_HEIGHT, startHeight + (moveEvent.clientY - startY));
        state.rowHeights[rowIndex] = next;
        applySizing();
    };

    const up = () => {
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", up);
        scheduleSync();
    };

    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
}

function getCell(rowIndex, colIndex) {
    return state.cells.get(cellKey(rowIndex, colIndex)) || {
        input: "",
        display: "",
        type: "empty",
        errorDetail: "",
        htmlDisplay: null
    };
}

function setCellInput(rowIndex, colIndex, input) {
    const key = cellKey(rowIndex, colIndex);
    if (!input || !String(input).trim()) {
        state.cells.delete(key);
        return;
    }
    const existing = state.cells.get(key) || {};
    state.cells.set(key, {
        ...existing,
        input,
        type: classifyInput(input)
    });
}

function classifyInput(input) {
    const trimmed = String(input || "").trim();
    if (!trimmed) return "empty";
    if (trimmed.startsWith("=")) return "formula";
    if (!Number.isNaN(Number(trimmed))) return "number";
    return "text";
}

function handleCellKeydown(event, rowIndex, colIndex) {
    if (event.key === "Escape") {
        closeCompletionMenu();
        clearReferenceHighlights();
        clearSelection();
        return;
    }
    if (event.shiftKey && ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(event.key)) {
        event.preventDefault();
        extendSelectionWithArrow(event.key);
        return;
    }
    if (handleNavigation(event, rowIndex, colIndex)) {
        return;
    }
    if (isPrintableKey(event)) {
        event.preventDefault();
        beginFormulaEditFromCell(rowIndex, colIndex, event.key);
        return;
    }
    if (event.key === "Enter") {
        event.preventDefault();
        beginFormulaEditFromCell(rowIndex, colIndex);
        return;
    }
    if (event.key === "F2") {
        event.preventDefault();
        beginFormulaEditFromCell(rowIndex, colIndex, null, { append: true });
    }
}

function handleNavigation(event, rowIndex, colIndex) {
    const key = event.key;
    if (!["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Tab"].includes(key)) return false;
    event.preventDefault();

    let nextRow = rowIndex;
    let nextCol = colIndex;
    if (key === "ArrowUp") nextRow = Math.max(0, rowIndex - 1);
    if (key === "ArrowDown") nextRow = Math.min(state.rows - 1, rowIndex + 1);
    if (key === "ArrowLeft") nextCol = Math.max(0, colIndex - 1);
    if (key === "ArrowRight") nextCol = Math.min(state.cols - 1, colIndex + 1);
    if (key === "Tab") {
        if (event.shiftKey) {
            if (colIndex > 0) nextCol = colIndex - 1;
            else if (rowIndex > 0) {
                nextRow = rowIndex - 1;
                nextCol = state.cols - 1;
            }
        } else if (colIndex < state.cols - 1) {
            nextCol = colIndex + 1;
        } else if (rowIndex < state.rows - 1) {
            nextRow = rowIndex + 1;
            nextCol = 0;
        }
    }
    focusCell(nextRow, nextCol);
    setSelection({ row: nextRow, col: nextCol }, { row: nextRow, col: nextCol });
    return true;
}

function extendSelectionWithArrow(key) {
    const anchor = state.selection.start || state.activeCell;
    const end = state.selection.end || anchor;
    if (!anchor || !end) return;
    let nextRow = end.row;
    let nextCol = end.col;
    if (key === "ArrowUp") nextRow = Math.max(0, end.row - 1);
    if (key === "ArrowDown") nextRow = Math.min(state.rows - 1, end.row + 1);
    if (key === "ArrowLeft") nextCol = Math.max(0, end.col - 1);
    if (key === "ArrowRight") nextCol = Math.min(state.cols - 1, end.col + 1);
    setSelection(anchor, { row: nextRow, col: nextCol });
}

function focusCell(rowIndex, colIndex) {
    const node = dom.grid.querySelector(`.sheet-cell[data-row="${rowIndex}"][data-col="${colIndex}"]`);
    if (node) node.focus();
}

function setSelection(start, end) {
    state.selection = { start, end };
    renderSelectionOverlay();
}

function clearSelection() {
    state.activeCell = null;
    state.selection = { start: null, end: null };
    renderSelectionOverlay();
    syncFormulaInput();
    clearReferenceHighlights();
}

function getNormalizedSelection() {
    const { start, end } = state.selection;
    if (!start || !end) return null;
    return {
        top: Math.min(start.row, end.row),
        bottom: Math.max(start.row, end.row),
        left: Math.min(start.col, end.col),
        right: Math.max(start.col, end.col)
    };
}

function renderSelectionOverlay() {
    dom.grid.querySelectorAll(".sheet-cell.sheet-selected").forEach((node) => {
        node.classList.remove("sheet-selected");
    });
    const sel = getNormalizedSelection();
    if (!sel) return;
    for (let row = sel.top; row <= sel.bottom; row += 1) {
        for (let col = sel.left; col <= sel.right; col += 1) {
            const node = dom.grid.querySelector(`.sheet-cell[data-row="${row}"][data-col="${col}"]`);
            if (node) node.classList.add("sheet-selected");
        }
    }
}

function syncFormulaInput() {
    if (!dom.formulaInput) return;
    const active = state.activeCell;
    if (!active) {
        dom.formulaInput.value = "";
        dom.formulaInput.disabled = true;
        dom.formulaInput.placeholder = "";
        dom.formulaBadge.textContent = "fx";
        return;
    }
    dom.formulaInput.disabled = false;
    dom.formulaInput.placeholder = FORMULA_PLACEHOLDER;
    const cell = getCell(active.row, active.col);
    if (document.activeElement !== dom.formulaInput || dom.formulaInput.value !== cell.input) {
        dom.formulaInput.value = cell.input || "";
    }
    dom.formulaBadge.textContent = cell.type === "number" ? "num" : cell.type === "formula" ? "fx" : "txt";
}

function beginFormulaEditFromCell(rowIndex, colIndex, initialChar = null, options = {}) {
    state.activeCell = { row: rowIndex, col: colIndex };
    setSelection({ row: rowIndex, col: colIndex }, { row: rowIndex, col: colIndex });
    let nextValue = getCell(rowIndex, colIndex).input || "";
    if (typeof initialChar === "string" && initialChar.length === 1) {
        nextValue = options.append ? `${nextValue}${initialChar}` : initialChar;
    }
    setCellInput(rowIndex, colIndex, nextValue);
    syncFormulaInput();
    dom.formulaInput.focus();
    const end = nextValue.length;
    dom.formulaInput.setSelectionRange(end, end);
    scheduleSync();
    requestCompletionIfNeeded();
    updateReferenceHighlightsFromFormula();
}

function handleFormulaInput() {
    const active = state.activeCell;
    if (!active) return;
    const next = dom.formulaInput.value;
    setCellInput(active.row, active.col, next);
    const node = dom.grid.querySelector(`.sheet-cell[data-row="${active.row}"][data-col="${active.col}"]`);
    if (node) {
        node.dataset.type = classifyInput(next);
        node.textContent = next;
    }
    dom.formulaBadge.textContent = classifyInput(next) === "number" ? "num" : classifyInput(next) === "formula" ? "fx" : "txt";
    scheduleSync();
    updateReferenceHighlightsFromFormula();
    requestCompletionIfNeeded();
}

function handleFormulaKeydown(event) {
    if (state.formulaMenu.open) {
        if (event.key === "ArrowDown") {
            event.preventDefault();
            moveCompletionSelection(1);
            return;
        }
        if (event.key === "ArrowUp") {
            event.preventDefault();
            moveCompletionSelection(-1);
            return;
        }
        if (event.key === "Enter" || event.key === "Tab") {
            event.preventDefault();
            applyActiveCompletion();
            return;
        }
        if (event.key === "Escape") {
            event.preventDefault();
            closeCompletionMenu();
            updateReferenceHighlightsFromFormula();
            return;
        }
    }

    if (isCompletionShortcut(event)) {
        event.preventDefault();
        requestCompletion(true);
        return;
    }
    if (event.key === "Enter") {
        event.preventDefault();
        moveFromFormula(event.shiftKey ? -1 : 1, 0);
        closeCompletionMenu();
        return;
    }
    if (event.key === "Tab") {
        event.preventDefault();
        moveFromFormula(0, event.shiftKey ? -1 : 1);
        closeCompletionMenu();
        return;
    }
    if (event.key === "Escape") {
        event.preventDefault();
        closeCompletionMenu();
        updateReferenceHighlightsFromFormula();
        return;
    }
}

function moveFromFormula(deltaRow, deltaCol) {
    if (!state.activeCell) return;
    let row = state.activeCell.row;
    let col = state.activeCell.col;
    if (deltaRow !== 0) {
        row = Math.min(state.rows - 1, Math.max(0, row + deltaRow));
    }
    if (deltaCol !== 0) {
        col += deltaCol;
        if (col < 0 && row > 0) {
            row -= 1;
            col = state.cols - 1;
        } else if (col >= state.cols && row < state.rows - 1) {
            row += 1;
            col = 0;
        } else {
            col = Math.min(state.cols - 1, Math.max(0, col));
        }
    }
    focusCell(row, col);
    setSelection({ row, col }, { row, col });
}

function requestCompletion(force = false) {
    if (!state.activeCell) return;
    const text = dom.formulaInput.value || "";
    const cursor = dom.formulaInput.selectionStart || 0;
    if (!force && !text.trim().startsWith("=")) {
        closeCompletionMenu();
        return;
    }
    const reqId = sendRequest("complete", { text, cursor }, (message) => {
        if (message && Array.isArray(message.items)) {
            openCompletionMenu(message.items);
        }
    });
    state.formulaMenu.lastRequestId = reqId;
}

function requestCompletionIfNeeded() {
    if (!state.formulaMenu.open) return;
    requestCompletion(true);
}

function openCompletionMenu(items) {
    state.formulaMenu.items = Array.isArray(items) ? items : [];
    state.formulaMenu.activeIndex = state.formulaMenu.items.length > 0 ? 0 : -1;
    if (!state.formulaMenu.items.length) {
        closeCompletionMenu();
        return;
    }
    state.formulaMenu.open = true;
    dom.menu.hidden = false;
    renderCompletionMenu();
}

function closeCompletionMenu() {
    state.formulaMenu.open = false;
    state.formulaMenu.items = [];
    state.formulaMenu.activeIndex = -1;
    dom.menu.hidden = true;
    dom.menuList.innerHTML = "";
}

function renderCompletionMenu() {
    dom.menuList.innerHTML = "";
    state.formulaMenu.items.forEach((item, index) => {
        const row = document.createElement("li");
        row.className = "sheet-menu-item";
        if (index === state.formulaMenu.activeIndex) {
            row.classList.add("is-active");
        }
        row.innerHTML = `
            <div class="sheet-menu-item-label">
                <span>${escapeHtml(item.label || "")}</span>
                <span class="sheet-menu-item-kind">${escapeHtml(item.kind || "item")}</span>
            </div>
            <div class="sheet-menu-item-detail">${escapeHtml(item.detail || item.documentation || "")}</div>
        `;
        row.addEventListener("mouseenter", () => {
            state.formulaMenu.activeIndex = index;
            renderCompletionMenu();
        });
        row.addEventListener("pointerdown", (event) => {
            event.preventDefault();
            applyCompletion(item);
        });
        dom.menuList.appendChild(row);
    });
}

function moveCompletionSelection(delta) {
    if (!state.formulaMenu.items.length) return;
    const count = state.formulaMenu.items.length;
    state.formulaMenu.activeIndex = (state.formulaMenu.activeIndex + delta + count) % count;
    renderCompletionMenu();
}

function applyActiveCompletion() {
    const item = state.formulaMenu.items[state.formulaMenu.activeIndex];
    if (item) {
        applyCompletion(item);
    }
}

function applyCompletion(item) {
    const value = dom.formulaInput.value || "";
    const replaceStart = clamp(Number(item.replaceStart), 0, value.length);
    const replaceEnd = clamp(Number(item.replaceEnd), replaceStart, value.length);
    const insertText = String(item.insertText || "");
    const next = `${value.slice(0, replaceStart)}${insertText}${value.slice(replaceEnd)}`;
    dom.formulaInput.value = next;
    dom.formulaInput.focus();

    const baseStart = replaceStart;
    const insertLength = insertText.length;
    if (Number.isInteger(item.cursorOffset)) {
        const caret = baseStart + clamp(Number(item.cursorOffset), 0, insertLength);
        dom.formulaInput.setSelectionRange(caret, caret);
    } else if (item.selectInsertedText) {
        dom.formulaInput.setSelectionRange(baseStart, baseStart + insertLength);
    } else {
        const caret = baseStart + insertLength;
        dom.formulaInput.setSelectionRange(caret, caret);
    }

    closeCompletionMenu();
    handleFormulaInput();
}

function handleOutsidePointerDown(event) {
    if (!state.formulaMenu.open) return;
    if (dom.formulaBar.contains(event.target)) return;
    closeCompletionMenu();
}

function maybeInsertCellRef(rowIndex, colIndex, event) {
    if (!event.altKey || !isFormulaEditing()) return false;
    event.preventDefault();
    event.stopPropagation();
    insertTextIntoFormula(cellRefString(rowIndex, colIndex));
    return true;
}

function insertTextIntoFormula(text, options = {}) {
    if (!dom.formulaInput) return false;
    const input = dom.formulaInput;
    const prependEquals = options.prependEqualsIfBlank !== false && input.value === "";
    const insertText = prependEquals ? `=${text}` : text;
    const selectionStart = input.selectionStart || 0;
    const selectionEnd = input.selectionEnd || 0;
    const next = `${input.value.slice(0, selectionStart)}${insertText}${input.value.slice(selectionEnd)}`;
    input.value = next;
    input.focus();

    const insertStart = selectionStart + (prependEquals ? 1 : 0);
    const insertEnd = insertStart + String(text).length;
    if (Array.isArray(options.selectionOffsets) && options.selectionOffsets.length === 2) {
        const startOffset = clamp(Number(options.selectionOffsets[0]), 0, String(text).length);
        const endOffset = clamp(Number(options.selectionOffsets[1]), startOffset, String(text).length);
        input.setSelectionRange(insertStart + startOffset, insertStart + endOffset);
    } else if (Number.isInteger(options.cursorOffset)) {
        const caret = insertStart + clamp(Number(options.cursorOffset), 0, String(text).length);
        input.setSelectionRange(caret, caret);
    } else if (options.selectInsertedText) {
        input.setSelectionRange(insertStart, insertEnd);
    } else {
        input.setSelectionRange(insertEnd, insertEnd);
    }
    handleFormulaInput();
    if (options.triggerCompletion) {
        requestCompletion(true);
    }
    return true;
}

function isFormulaEditing() {
    if (!state.activeCell) return false;
    return document.activeElement === dom.formulaInput && (dom.formulaInput.value || "").trim().startsWith("=");
}

function updateReferenceHighlightsFromFormula() {
    if (!state.activeCell) {
        clearReferenceHighlights();
        return;
    }
    const formula = currentHighlightedInput();
    highlightCellRefs(parseRefsFromFormula(formula));
    highlightBoxRefs(parseBoxRefsFromFormula(formula));
    highlightOutputRefs(collectDependentCells(state.activeCell.row, state.activeCell.col));
}

function clearReferenceHighlights() {
    highlightCellRefs([]);
    highlightOutputRefs([]);
    highlightBoxRefs([]);
}

function parseRefsFromFormula(formula) {
    return collectCellDependencies(formula);
}

function currentHighlightedInput() {
    if (!state.activeCell) return "";
    if (document.activeElement === dom.formulaInput) {
        return dom.formulaInput.value || "";
    }
    return getCell(state.activeCell.row, state.activeCell.col).input || "";
}

function collectCellDependencies(formula) {
    const source = String(formula || "");
    if (!source.trim().startsWith("=")) return [];
    const refs = new Map();
    let index = 0;
    let quote = null;

    const rememberRef = (ref) => {
        if (!ref) return;
        if (ref.row < 0 || ref.col < 0) return;
        if (ref.row >= state.rows || ref.col >= state.cols) return;
        refs.set(cellKey(ref.row, ref.col), { row: ref.row, col: ref.col });
    };

    const rememberRange = (rangeText) => {
        const expanded = expandRangeRef(rangeText);
        expanded.forEach(rememberRef);
    };

    while (index < source.length) {
        const ch = source[index];
        if (quote) {
            if (ch === "\\" && quote !== "`" && index + 1 < source.length) {
                index += 2;
                continue;
            }
            if (ch === quote) quote = null;
            index += 1;
            continue;
        }
        if (ch === "'" || ch === "\"" || ch === "`") {
            quote = ch;
            index += 1;
            continue;
        }
        if (source.startsWith("range", index) && isRefBoundaryBefore(source, index)) {
            const parsedRangeCall = parseSingleStringCallAt(source, index, "range");
            if (parsedRangeCall) {
                rememberRange(parsedRangeCall.value);
                index = parsedRangeCall.end;
                continue;
            }
        }
        if (source.startsWith("cell", index) && isRefBoundaryBefore(source, index)) {
            const parsedCellCall = parseSingleStringCallAt(source, index, "cell");
            if (parsedCellCall) {
                rememberRef(parseRef(parsedCellCall.value));
                index = parsedCellCall.end;
                continue;
            }
        }
        const parsed = parseRefAt(source, index);
        if (parsed && isRefBoundaryAfter(source, parsed.end)) {
            if (source[parsed.end] === ":") {
                const rangeEnd = parseRefAt(source, parsed.end + 1);
                if (rangeEnd && isRefBoundaryAfter(source, rangeEnd.end)) {
                    rememberRange(`${parsed.token}:${rangeEnd.token}`);
                    index = rangeEnd.end;
                    continue;
                }
            }
            rememberRef(parsed.ref);
            index = parsed.end;
            continue;
        }
        index += 1;
    }

    return Array.from(refs.values());
}

function collectDependentCells(targetRow, targetCol) {
    const targetKey = cellKey(targetRow, targetCol);
    const outputs = [];
    state.cells.forEach((cell, key) => {
        if (!cell || classifyInput(cell.input) !== "formula") return;
        if (key === targetKey) return;
        const dependsOnTarget = collectCellDependencies(cell.input || "").some((ref) => cellKey(ref.row, ref.col) === targetKey);
        if (!dependsOnTarget) return;
        const coords = parseCellKey(key);
        if (coords) outputs.push(coords);
    });
    return outputs;
}

function parseBoxRefsFromFormula(formula) {
    if (!String(formula || "").trim().startsWith("=")) return [];
    const refs = [];
    const source = String(formula || "");
    let index = 0;
    let quote = null;
    while (index < source.length) {
        const ch = source[index];
        if (quote) {
            if (ch === "\\" && quote !== "`") {
                index += 2;
                continue;
            }
            if (ch === quote) quote = null;
            index += 1;
            continue;
        }
        if (ch === "'" || ch === "\"" || ch === "`") {
            quote = ch;
            index += 1;
            continue;
        }
        if (source.startsWith("box", index) && !isWordChar(source[index - 1])) {
            const parsed = parseBoxCallAt(source, index);
            if (parsed) {
                refs.push(parsed.ref);
                index = parsed.end;
                continue;
            }
        }
        if (source.startsWith("self", index) && !isWordChar(source[index - 1])) {
            refs.push({ ref: "self" });
            index += 4;
            continue;
        }
        index += 1;
    }
    return refs;
}

function parseBoxCallAt(source, start) {
    let index = start + 3;
    while (index < source.length && /\s/.test(source[index])) index += 1;
    if (source[index] !== "(") return null;
    index += 1;
    while (index < source.length && /\s/.test(source[index])) index += 1;
    const quote = source[index];
    if (quote !== "'" && quote !== "\"") return null;
    index += 1;
    const begin = index;
    while (index < source.length) {
        if (source[index] === "\\" && index + 1 < source.length) {
            index += 2;
            continue;
        }
        if (source[index] === quote) break;
        index += 1;
    }
    if (index >= source.length) return null;
    const ref = source.slice(begin, index);
    index += 1;
    while (index < source.length && /\s/.test(source[index])) index += 1;
    if (source[index] !== ")") return null;
    return { ref: { ref }, end: index + 1 };
}

function highlightCellRefs(refs) {
    const next = new Set(refs.map((ref) => cellKey(ref.row, ref.col)));
    state.highlightedCells.forEach((key) => {
        if (next.has(key)) return;
        const coords = parseCellKey(key);
        if (!coords) return;
        const node = dom.grid.querySelector(`.sheet-cell[data-row="${coords.row}"][data-col="${coords.col}"]`);
        if (node) node.classList.remove("sheet-ref-highlight");
    });
    next.forEach((key) => {
        if (state.highlightedCells.has(key)) return;
        const coords = parseCellKey(key);
        if (!coords) return;
        const node = dom.grid.querySelector(`.sheet-cell[data-row="${coords.row}"][data-col="${coords.col}"]`);
        if (node) node.classList.add("sheet-ref-highlight");
    });
    state.highlightedCells = next;
}

function highlightOutputRefs(refs) {
    const next = new Set(refs.map((ref) => cellKey(ref.row, ref.col)));
    state.highlightedOutputs.forEach((key) => {
        if (next.has(key)) return;
        const coords = parseCellKey(key);
        if (!coords) return;
        const node = dom.grid.querySelector(`.sheet-cell[data-row="${coords.row}"][data-col="${coords.col}"]`);
        if (node) node.classList.remove("sheet-output-highlight");
    });
    next.forEach((key) => {
        if (state.highlightedOutputs.has(key)) return;
        const coords = parseCellKey(key);
        if (!coords) return;
        const node = dom.grid.querySelector(`.sheet-cell[data-row="${coords.row}"][data-col="${coords.col}"]`);
        if (node) node.classList.add("sheet-output-highlight");
    });
    state.highlightedOutputs = next;
}

function highlightBoxRefs(refs) {
    const parentDoc = getParentDocument();
    if (!parentDoc) return;
    ensureParentHighlightStyles(parentDoc);

    const next = new Set();
    refs.forEach((entry) => {
        const box = resolveParentBox(entry.ref);
        if (box) next.add(box);
    });

    state.highlightedBoxes.forEach((box) => {
        if (next.has(box)) return;
        box.classList.remove("field-sheet-box-highlight");
    });
    next.forEach((box) => box.classList.add("field-sheet-box-highlight"));
    state.highlightedBoxes = next;
}

function cleanupParentHighlights() {
    state.highlightedBoxes.forEach((box) => box.classList.remove("field-sheet-box-highlight"));
    state.highlightedBoxes.clear();
}

function getParentDocument() {
    try {
        if (!window.parent || window.parent === window) return null;
        return window.parent.document || null;
    } catch (_error) {
        return null;
    }
}

function ensureParentHighlightStyles(parentDoc) {
    if (parentDoc.getElementById("field-sheet-parent-highlight")) return;
    const style = parentDoc.createElement("style");
    style.id = "field-sheet-parent-highlight";
    style.textContent = `
        .field-sheet-box-highlight {
            box-shadow: inset 0 0 0 2px rgba(var(--field-cyan-rgb, 72, 201, 255), 0.95),
                        0 0 0 1px rgba(var(--field-cyan-rgb, 72, 201, 255), 0.35) !important;
        }
    `;
    parentDoc.head.appendChild(style);
}

function wireParentDocumentHooks() {
    const parentDoc = getParentDocument();
    if (!parentDoc) return;
    parentDoc.addEventListener("pointerdown", handleParentPointerDown, true);
}

function handleParentPointerDown(event) {
    if (!event.altKey || !isFormulaEditing()) return;
    const box = event.target && event.target.closest ? event.target.closest(".box") : null;
    if (!box) return;
    event.preventDefault();
    event.stopPropagation();
    const ref = preferredParentBoxRef(box);
    const inserted = `box("${escapeForFormula(ref)}")["value"]`;
    const placeholderStart = String(ref).length + 9;
    insertTextIntoFormula(inserted, {
        selectionOffsets: [placeholderStart, placeholderStart + 5],
        triggerCompletion: true
    });
}

function preferredParentBoxRef(box) {
    const uid = String(box.uid || box.getAttribute("data-uid") || "").trim();
    const name = String(box.name || "").trim();
    const normalizedName = name.toLowerCase();
    if (name && normalizedName !== "untitled") {
        const siblings = Array.from(getParentDocument().querySelectorAll(".box"));
        const matches = siblings.filter((entry) => String(entry.name || "").trim() === name);
        if (matches.length === 1) return name;
    }
    return uid || name || "unknown-box";
}

function resolveParentBox(ref) {
    const parentDoc = getParentDocument();
    if (!parentDoc) return null;
    if (!ref || ref === "self") {
        return resolveParentBox(ownerUid);
    }
    const boxes = Array.from(parentDoc.querySelectorAll(".box"));
    const byUid = boxes.find((box) => String(box.uid || "").trim() === ref);
    if (byUid) return byUid;
    const byName = boxes.filter((box) => String(box.name || "").trim() === ref);
    return byName.length === 1 ? byName[0] : null;
}

function isCompletionShortcut(event) {
    if (!(event.metaKey || event.ctrlKey)) return false;
    if (event.altKey || event.shiftKey) return false;
    return event.code === "Space" || event.key === " " || event.key === "Spacebar";
}

function isPrintableKey(event) {
    if (event.ctrlKey || event.metaKey || event.altKey) return false;
    return typeof event.key === "string" && event.key.length === 1 && event.key >= " ";
}

function wireClipboard() {
    document.addEventListener("copy", handleCopy);
    document.addEventListener("cut", handleCut);
    document.addEventListener("paste", handlePaste);
}

function isSheetFocused() {
    return !!dom.root && dom.root.contains(document.activeElement);
}

function handleCopy(event) {
    if (document.activeElement === dom.formulaInput) return;
    if (!isSheetFocused()) return;
    const text = serializeSelection();
    if (!text) return;
    event.preventDefault();
    event.clipboardData.setData("text/plain", text);
}

function handleCut(event) {
    if (document.activeElement === dom.formulaInput) return;
    if (!isSheetFocused()) return;
    const text = serializeSelection();
    if (!text) return;
    event.preventDefault();
    event.clipboardData.setData("text/plain", text);
    clearSelectionCells();
    renderGrid();
    scheduleSync();
}

function handlePaste(event) {
    if (!isSheetFocused()) return;
    if (document.activeElement === dom.formulaInput) return;
    const text = event.clipboardData.getData("text/plain");
    if (!text) return;
    event.preventDefault();
    applyPaste(text);
}

function serializeSelection() {
    const sel = getNormalizedSelection();
    if (!sel) return "";
    const rows = [];
    for (let row = sel.top; row <= sel.bottom; row += 1) {
        const cols = [];
        for (let col = sel.left; col <= sel.right; col += 1) {
            cols.push(getCell(row, col).input || "");
        }
        rows.push(cols.join("\t"));
    }
    return rows.join("\n");
}

function clearSelectionCells() {
    const sel = getNormalizedSelection();
    if (!sel) return;
    for (let row = sel.top; row <= sel.bottom; row += 1) {
        for (let col = sel.left; col <= sel.right; col += 1) {
            state.cells.delete(cellKey(row, col));
        }
    }
}

function applyPaste(text) {
    const sel = getNormalizedSelection();
    if (!sel) return;
    const rows = String(text).split(/\r?\n/).map((line) => line.split("\t"));
    for (let row = 0; row < rows.length; row += 1) {
        for (let col = 0; col < rows[row].length; col += 1) {
            const targetRow = sel.top + row;
            const targetCol = sel.left + col;
            if (targetRow >= state.rows || targetCol >= state.cols) continue;
            setCellInput(targetRow, targetCol, adjustFormulaForOffset(rows[row][col], row, col));
        }
    }
    renderGrid();
    scheduleSync();
}

function adjustFormulaForOffset(input, deltaRow, deltaCol) {
    if (classifyInput(input) !== "formula") return input;
    return rewriteFormulaRefsOutsideQuotedLiterals(input, (token) => {
        const ref = parseRef(token);
        if (!ref) return token;
        const nextRow = ref.rowAbs ? ref.row : Math.max(0, ref.row + deltaRow);
        const nextCol = ref.colAbs ? ref.col : Math.max(0, ref.col + deltaCol);
        return `${ref.colAbs ? "$" : ""}${columnLabel(nextCol)}${ref.rowAbs ? "$" : ""}${nextRow + 1}`;
    });
}

function expandRangeRef(rangeText) {
    const parts = String(rangeText || "").split(":");
    if (parts.length !== 2) return [];
    const start = parseRef(parts[0]);
    const end = parseRef(parts[1]);
    if (!start || !end) return [];
    const refs = [];
    const top = Math.min(start.row, end.row);
    const bottom = Math.max(start.row, end.row);
    const left = Math.min(start.col, end.col);
    const right = Math.max(start.col, end.col);
    for (let row = top; row <= bottom; row += 1) {
        for (let col = left; col <= right; col += 1) {
            refs.push({ row, col });
        }
    }
    return refs;
}

function rewriteFormulaRefsOutsideQuotedLiterals(input, mapRef) {
    let out = "";
    let index = 0;
    let quote = null;
    const regex = /\$?[A-Za-z]+\$?\d+/y;
    while (index < input.length) {
        const ch = input[index];
        if (quote) {
            out += ch;
            if (ch === "\\" && quote !== "`" && index + 1 < input.length) {
                out += input[index + 1];
                index += 2;
                continue;
            }
            if (ch === quote) quote = null;
            index += 1;
            continue;
        }
        if (ch === "'" || ch === "\"" || ch === "`") {
            quote = ch;
            out += ch;
            index += 1;
            continue;
        }
        regex.lastIndex = index;
        const match = regex.exec(input);
        if (match && match.index === index) {
            out += mapRef(match[0]);
            index += match[0].length;
            continue;
        }
        out += ch;
        index += 1;
    }
    return out;
}

function parseRefAt(source, start) {
    if (!isRefBoundaryBefore(source, start)) return null;
    const slice = source.slice(start);
    const match = /^(\$?[A-Za-z]+\$?\d+)/.exec(slice);
    if (!match) return null;
    const token = match[1];
    const ref = parseRef(token);
    if (!ref) return null;
    return {
        token,
        ref,
        end: start + token.length
    };
}

function isRefBoundaryBefore(source, index) {
    return !isWordChar(source[index - 1]);
}

function isRefBoundaryAfter(source, index) {
    return !isWordChar(source[index]);
}

function parseSingleStringCallAt(source, start, name) {
    let index = start + name.length;
    while (index < source.length && /\s/.test(source[index])) index += 1;
    if (source[index] !== "(") return null;
    index += 1;
    while (index < source.length && /\s/.test(source[index])) index += 1;
    const quote = source[index];
    if (quote !== "'" && quote !== "\"") return null;
    index += 1;
    let value = "";
    while (index < source.length) {
        const ch = source[index];
        if (ch === "\\" && index + 1 < source.length) {
            value += source[index + 1];
            index += 2;
            continue;
        }
        if (ch === quote) break;
        value += ch;
        index += 1;
    }
    if (index >= source.length) return null;
    index += 1;
    while (index < source.length && /\s/.test(source[index])) index += 1;
    if (source[index] !== ")") return null;
    return { value, end: index + 1 };
}

function parseRef(ref) {
    const match = /^(\$?)([A-Za-z]+)(\$?)(\d+)$/.exec(String(ref || "").trim());
    if (!match) return null;
    const [, colAbsFlag, colText, rowAbsFlag, rowText] = match;
    let col = 0;
    for (let i = 0; i < colText.length; i += 1) {
        col *= 26;
        col += colText.toUpperCase().charCodeAt(i) - 64;
    }
    return {
        row: Number(rowText) - 1,
        col: col - 1,
        rowAbs: rowAbsFlag === "$",
        colAbs: colAbsFlag === "$"
    };
}

function cellRefString(row, col) {
    return `${columnLabel(col)}${row + 1}`;
}

function columnLabel(index) {
    let label = "";
    let current = index;
    while (current >= 0) {
        label = String.fromCharCode(65 + (current % 26)) + label;
        current = Math.floor(current / 26) - 1;
    }
    return label;
}

function cellKey(row, col) {
    return `${row}:${col}`;
}

function parseCellKey(key) {
    const parts = String(key || "").split(":");
    const row = Number(parts[0]);
    const col = Number(parts[1]);
    if (!Number.isInteger(row) || !Number.isInteger(col)) return null;
    return { row, col };
}

function escapeHtml(text) {
    return String(text || "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function escapeForFormula(text) {
    return String(text || "").replace(/\\/g, "\\\\").replace(/"/g, "\\\"");
}

function isWordChar(ch) {
    return typeof ch === "string" && /[A-Za-z0-9_]/.test(ch);
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}
