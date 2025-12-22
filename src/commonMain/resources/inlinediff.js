// monaco-ai-diff.mjs
//
// Changes vs previous:
// - Green zones are accepted via editor.onMouseDown when clicking a VIEW_ZONE target.
// - Adds debug logs to show click target type/detail.
// - Stores viewZoneId -> hunk key mapping.
// - Keeps red-line semantics: delete=accept, replace=reject.
//
// Usage:
//   import AIDiff from "./monaco-ai-diff.mjs";
//   AIDiff.apply(editor, proposedText, { monaco, debug: true });

function splitLinesPreserveEmpty(text) {
    const norm = String(text).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
    return norm.split("\n");
}
function last(arr) { return arr[arr.length - 1]; }
function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }

function fnv1a32(str) {
    let h = 0x811c9dc5;
    for (let i = 0; i < str.length; i++) {
        h ^= str.charCodeAt(i);
        h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0;
    }
    return h.toString(16).padStart(8, "0");
}
function hunkKey(h) {
    const del = (h.deleteLines || []).join("\n");
    const ins = (h.insertLines || []).join("\n");
    return `${fnv1a32(del)}:${fnv1a32(ins)}:${h.deleteCount || 0}:${h.insertCount || 0}`;
}

// --- Myers diff (line-based) -----------------------------------------------
function myersDiff(aLines, bLines) {
    const N = aLines.length;
    const M = bLines.length;
    const max = N + M;
    const offset = max;

    let V = new Array(2 * max + 1).fill(0);
    const trace = [];

    for (let D = 0; D <= max; D++) {
        trace.push(V.slice());
        for (let k = -D; k <= D; k += 2) {
            const kIndex = k + offset;

            let x;
            if (k === -D || (k !== D && V[kIndex - 1] < V[kIndex + 1])) x = V[kIndex + 1];
            else x = V[kIndex - 1] + 1;

            let y = x - k;

            while (x < N && y < M && aLines[x] === bLines[y]) { x++; y++; }
            V[kIndex] = x;

            if (x >= N && y >= M) return backtrackMyers(trace, aLines, bLines, offset);
        }
    }
    return [{ type: "delete", lines: aLines }, { type: "insert", lines: bLines }];
}

function backtrackMyers(trace, aLines, bLines, offset) {
    let x = aLines.length;
    let y = bLines.length;
    const ops = [];

    for (let D = trace.length - 1; D >= 0; D--) {
        const V = trace[D];
        const k = x - y;
        const kIndex = k + offset;

        let prevK;
        if (k === -D || (k !== D && V[kIndex - 1] < V[kIndex + 1])) prevK = k + 1;
        else prevK = k - 1;

        const prevX = V[prevK + offset];
        const prevY = prevX - prevK;

        while (x > prevX && y > prevY) {
            ops.push({ type: "equal", line: aLines[x - 1] });
            x--; y--;
        }
        if (D === 0) break;

        if (x === prevX) { ops.push({ type: "insert", line: bLines[y - 1] }); y--; }
        else { ops.push({ type: "delete", line: aLines[x - 1] }); x--; }
    }

    ops.reverse();
    const grouped = [];
    for (const op of ops) {
        const g = last(grouped);
        if (!g || g.type !== op.type) grouped.push({ type: op.type, lines: [op.line] });
        else g.lines.push(op.line);
    }
    return grouped;
}

// --- Hunks -----------------------------------------------------------------
function buildHunksFromOps(ops) {
    const hunks = [];
    let oldLineNo = 1;

    for (let i = 0; i < ops.length; ) {
        const op = ops[i];
        if (op.type === "equal") { oldLineNo += op.lines.length; i++; continue; }

        const h = { oldStartLine: oldLineNo, deleteLines: [], insertLines: [] };

        while (i < ops.length && ops[i].type !== "equal") {
            const t = ops[i].type;
            if (t === "delete") { h.deleteLines.push(...ops[i].lines); oldLineNo += ops[i].lines.length; }
            else if (t === "insert") { h.insertLines.push(...ops[i].lines); }
            i++;
        }

        h.deleteCount = h.deleteLines.length;
        h.insertCount = h.insertLines.length;
        h.oldEndLine = h.deleteCount ? h.oldStartLine + h.deleteCount - 1 : h.oldStartLine - 1;
        h.kind = h.deleteCount > 0 && h.insertCount > 0 ? "replace" : h.deleteCount > 0 ? "delete" : "insert";
        h.key = hunkKey(h);
        hunks.push(h);
    }
    return hunks;
}

// --- Monaco state -----------------------------------------------------------
const STATE = new WeakMap();
function getState(editor) {
    let s = STATE.get(editor);
    if (!s) {
        s = {
            decorationIds: [],
            zoneIds: [],
            zoneIdToInfo: new Map(),     // viewZoneId -> { key, anchorLine }
            targetText: "",
            rejectedKeys: new Set(),
            deleteLineToInfo: new Map(), // lineNumber -> { key, kind }
            _mouseDownDisposable: null,
            _lastOptions: null,
            _viewZoneTypeSet: null
        };
        STATE.set(editor, s);
    }
    return s;
}

function resolveMonaco(options) {
    const m = options?.monaco ?? globalThis.monaco;
    if (!m || !m.Range || !m.editor) {
        throw new Error("AIDiff: Monaco API not found. Pass { monaco } to apply(editor, text, { monaco }).");
    }
    return m;
}

function dbg(options, ...args) {
    if (options?.debug) console.log("[AIDiff]", ...args);
}

function ensureStyles() {
    if (typeof document === "undefined") return;
    if (document.getElementById("monaco-ai-diff-styles")) return;

    const style = document.createElement("style");
    style.id = "monaco-ai-diff-styles";
    style.textContent = `
.monaco-ai-diff-delete-line { background: rgba(255,0,0,0.16); cursor: pointer;   border-right: 8px solid rgba(255,0,0,1);
}
.monaco-ai-diff-zone {
  box-sizing: border-box;
  width: 100%;
  background: rgba(0,200,0,0.12);
  border-right: 8px solid rgba(0,200,0,1);
  overflow: hidden;
  user-select: text;
  cursor: pointer;
}
.monaco-ai-diff-zone-pre { margin: 0; padding: 0; white-space: pre; }
.monaco-ai-diff-zone-line { display: block; }
`;
    document.head.appendChild(style);
}

function clear(editor) {
    const s = getState(editor);

    if (s.decorationIds.length) s.decorationIds = editor.deltaDecorations(s.decorationIds, []);
    if (s.zoneIds.length) {
        editor.changeViewZones((accessor) => { for (const zid of s.zoneIds) accessor.removeZone(zid); });
        s.zoneIds = [];
    }
    s.zoneIdToInfo.clear();
    s.deleteLineToInfo.clear();
}

function dispose(editor) {
    const s = getState(editor);
    clear(editor);
    if (s._mouseDownDisposable) {
        try { s._mouseDownDisposable.dispose(); } catch (_) {}
        s._mouseDownDisposable = null;
    }
    STATE.delete(editor);
}

// --- Font/metrics -----------------------------------------------------------
function getEditorTextMetrics(editor, monaco, options) {
    const forcedFamily = options?.zoneFontFamily;
    let family = "", fontSize = 0, lineHeight = 0;

    try {
        family = forcedFamily || editor.getOption(monaco.editor.EditorOption.fontFamily) || "";
        fontSize = editor.getOption(monaco.editor.EditorOption.fontSize) || 0;
        lineHeight = editor.getOption(monaco.editor.EditorOption.lineHeight) || 0;
    } catch (_) {}

    if ((!family || !fontSize || !lineHeight) && editor.getDomNode) {
        const dom = editor.getDomNode();
        if (dom && typeof window !== "undefined" && window.getComputedStyle) {
            const cs = window.getComputedStyle(dom);
            family = forcedFamily || family || cs.fontFamily || "";
            fontSize = fontSize || parseFloat(cs.fontSize) || 0;
            lineHeight = lineHeight || parseFloat(cs.lineHeight) || 0;
        }
    }

    if (!family) family = forcedFamily || `"Iosevka Web", Iosevka, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace`;
    if (!fontSize) fontSize = 14;
    if (!lineHeight) lineHeight = Math.round(fontSize * 1.4);

    return { fontFamily: family, fontSize, lineHeight };
}

// --- Accept/Reject ----------------------------------------------------------
function computeCurrentHunks(editor, targetText) {
    const model = editor.getModel();
    if (!model) return [];
    const aLines = splitLinesPreserveEmpty(model.getValue());
    const bLines = splitLinesPreserveEmpty(targetText);
    return buildHunksFromOps(myersDiff(aLines, bLines));
}

function chooseHunkByKey(hunks, key, approxLine) {
    const matches = hunks.filter((h) => h.key === key);
    if (matches.length === 0) return null;
    if (matches.length === 1) return matches[0];

    if (typeof approxLine !== "number") return matches[0];

    let best = null, bestDist = Infinity;
    for (const h of matches) {
        const d = Math.abs(h.oldStartLine - approxLine);
        if (d < bestDist) { bestDist = d; best = h; }
    }
    return best || matches[0];
}

function acceptHunk(editor, hunk) {
    const model = editor.getModel();
    if (!model) return;

    const curLines = splitLinesPreserveEmpty(model.getValue());
    const startIndex = clamp(hunk.oldStartLine, 1, curLines.length + 1) - 1;

    const nextLines = curLines.slice();
    nextLines.splice(startIndex, hunk.deleteCount, ...hunk.insertLines);

    editor.executeEdits("ai-diff-accept", [{ range: model.getFullModelRange(), text: nextLines.join("\n") }]);
}

function acceptByKey(editor, key, approxLine, options) {
    const s = getState(editor);
    const target = String(s.targetText || "");
    if (!target) return;

    const hunks = computeCurrentHunks(editor, target);
    const chosen = chooseHunkByKey(hunks, key, approxLine);

    dbg(options, "acceptByKey()", { key, approxLine, hunks: hunks.length, matches: hunks.filter(h => h.key === key).length });

    if (!chosen) { dbg(options, "acceptByKey(): no match"); return; }

    acceptHunk(editor, chosen);
    dbg(options, "acceptByKey(): applied; redraw");
    apply(editor, target, options || s._lastOptions || {});
}

function rejectByKey(editor, key, options) {
    const s = getState(editor);
    s.rejectedKeys.add(key);
    dbg(options, "rejectByKey()", key);
    apply(editor, s.targetText, options || s._lastOptions || {});
}

function acceptAllRemaining(editor, options = {}) {
    const s = getState(editor);
    const target = String(s.targetText || "");
    if (!target) return;

    const model = editor.getModel();
    if (!model) return;

    const hunks = computeCurrentHunks(editor, target).filter((h) => !s.rejectedKeys.has(h.key));
    dbg(options, "acceptAllRemaining()", { remaining: hunks.length });
    if (hunks.length === 0) return;

    const curLines = splitLinesPreserveEmpty(model.getValue());
    const sorted = hunks.slice().sort((a, b) => b.oldStartLine - a.oldStartLine);

    const nextLines = curLines.slice();
    for (const h of sorted) {
        const startIndex = clamp(h.oldStartLine, 1, nextLines.length + 1) - 1;
        nextLines.splice(startIndex, h.deleteCount, ...h.insertLines);
    }

    editor.executeEdits("ai-diff-accept-remaining", [{ range: model.getFullModelRange(), text: nextLines.join("\n") }]);
    apply(editor, target, options || s._lastOptions || {});
}

function rejectAllRemaining(editor, options = {}) {
    const s = getState(editor);
    const target = String(s.targetText || "");
    if (!target) return;

    const hunks = computeCurrentHunks(editor, target);
    dbg(options, "rejectAllRemaining()", { remaining: hunks.length });
    for (const h of hunks) s.rejectedKeys.add(h.key);
    apply(editor, target, options || s._lastOptions || {});
}

function acceptAll(editor) {
    const s = getState(editor);
    const model = editor.getModel();
    if (!model) return;
    editor.executeEdits("ai-diff-accept-all", [{ range: model.getFullModelRange(), text: String(s.targetText || "") }]);
    clear(editor);
}

// --- Main apply -------------------------------------------------------------
function buildViewZoneTypeSet(monaco) {
    // Robust: any enum entry containing VIEW_ZONE counts
    const set = new Set();
    const mtt = monaco.editor.MouseTargetType;
    for (const k of Object.keys(mtt)) {
        if (k.includes("VIEW_ZONE")) set.add(mtt[k]);
    }
    return set;
}

function extractViewZoneId(mouseTarget) {
    // Monaco typically provides it in target.detail.viewZoneId
    const d = mouseTarget?.detail;
    if (!d) return null;
    if (typeof d.viewZoneId === "number" || typeof d.viewZoneId === "string") return d.viewZoneId;
    if (d.viewZoneId && (typeof d.viewZoneId.id === "number" || typeof d.viewZoneId.id === "string")) return d.viewZoneId.id;
    if (d.viewZone && (typeof d.viewZone.id === "number" || typeof d.viewZone.id === "string")) return d.viewZone.id;
    return null;
}

function apply(editor, newText, options = {}) {
    ensureStyles();
    const monaco = resolveMonaco(options);

    const s = getState(editor);
    s._lastOptions = { ...options };
    s.targetText = String(newText);

    if (!s._viewZoneTypeSet) s._viewZoneTypeSet = buildViewZoneTypeSet(monaco);

    const model = editor.getModel();
    if (!model) return;

    clear(editor);

    const aLines = splitLinesPreserveEmpty(model.getValue());
    const bLines = splitLinesPreserveEmpty(s.targetText);

    const hunks = buildHunksFromOps(myersDiff(aLines, bLines));
    dbg(options, "apply()", { hunks: hunks.length, rejected: s.rejectedKeys.size, viewZoneTypes: [...s._viewZoneTypeSet] });

    const decorations = [];
    const zonesToAdd = [];
    const metrics = getEditorTextMetrics(editor, monaco, options);

    function addDeleteDecoration(startLine, endLine, key, kind) {
        const lineCount = model.getLineCount();
        startLine = clamp(startLine, 1, lineCount);
        endLine = clamp(endLine, 1, lineCount);

        for (let ln = startLine; ln <= endLine; ln++) {
            if (!s.deleteLineToInfo.has(ln)) s.deleteLineToInfo.set(ln, { key, kind });
        }

        decorations.push({
            range: new monaco.Range(startLine, 1, endLine, 1),
            options: { isWholeLine: true, className: "monaco-ai-diff-delete-line" }
        });
    }

    for (const h of hunks) {
        if (s.rejectedKeys.has(h.key)) continue;

        if (h.deleteCount > 0) addDeleteDecoration(h.oldStartLine, h.oldEndLine, h.key, h.kind);

        if (h.insertCount > 0) {
            const after = Math.max(0, h.oldStartLine - 1);
            const anchorLine = clamp(h.oldStartLine, 1, model.getLineCount());
            zonesToAdd.push({
                afterLineNumber: clamp(after, 0, model.getLineCount()),
                heightInLines: Math.max(1, h.insertLines.length),
                insertedLines: h.insertLines,
                key: h.key,
                anchorLine
            });
        }
    }

    s.decorationIds = editor.deltaDecorations(s.decorationIds, decorations);

    editor.changeViewZones((accessor) => {
        for (const z of zonesToAdd) {
            const dom = document.createElement("div");
            dom.className = "monaco-ai-diff-zone";

            dom.style.fontFamily = metrics.fontFamily;
            dom.style.fontSize = `${metrics.fontSize}px`;
            dom.style.lineHeight = `${metrics.lineHeight}px`;

            const pre = document.createElement("pre");
            pre.className = "monaco-ai-diff-zone-pre";
            pre.style.fontFamily = "inherit";
            pre.style.fontSize = "inherit";
            pre.style.lineHeight = "inherit";

            for (const line of z.insertedLines) {
                const span = document.createElement("span");
                span.className = "monaco-ai-diff-zone-line";
                span.textContent = line;
                pre.appendChild(span);
            }
            dom.appendChild(pre);

            const zoneId = accessor.addZone({
                afterLineNumber: z.afterLineNumber,
                heightInLines: z.heightInLines,
                domNode: dom,
                suppressMouseDown: true
            });

            s.zoneIds.push(zoneId);
            s.zoneIdToInfo.set(zoneId, { key: z.key, anchorLine: z.anchorLine });

            dbg(options, "zone create", { zoneId, key: z.key, anchor: z.anchorLine, lines: z.insertedLines.length });
        }
    });

    // Install one editor-wide click handler that handles:
    // - VIEW_ZONE clicks (green) => accept
    // - red line clicks: delete=>accept, replace=>reject
    if (!s._mouseDownDisposable) {
        s._mouseDownDisposable = editor.onMouseDown((e) => {
            try {
                const t = e.target;
                const pos = t?.position;
                const be = e.event?.browserEvent;
                if (!be || be.button !== 0) return;

                // 1) If Monaco reports this click is on a view zone, accept that zone's hunk.
                if (s._viewZoneTypeSet.has(t.type)) {
                    const vzid = extractViewZoneId(t);
                    dbg(s._lastOptions, "onMouseDown VIEW_ZONE", { type: t.type, vzid, detail: t.detail });

                    if (vzid != null) {
                        const info = s.zoneIdToInfo.get(vzid);
                        dbg(s._lastOptions, "VIEW_ZONE lookup", { vzid, info });
                        if (info) {
                            e.event.preventDefault(); e.event.stopPropagation();
                            acceptByKey(editor, info.key, info.anchorLine, s._lastOptions);
                        }
                    } else {
                        dbg(s._lastOptions, "VIEW_ZONE click but no viewZoneId found in target.detail", t.detail);
                    }
                    return;
                }

                // 2) Red deleted lines: click behavior depends on hunk kind.
                if (pos) {
                    const info = s.deleteLineToInfo.get(pos.lineNumber);
                    if (info) {
                        dbg(s._lastOptions, "onMouseDown red line", { line: pos.lineNumber, kind: info.kind, key: info.key });
                        e.event.preventDefault(); e.event.stopPropagation();
                        if (info.kind === "replace") rejectByKey(editor, info.key, s._lastOptions);
                        else acceptByKey(editor, info.key, pos.lineNumber, s._lastOptions);
                        return;
                    }
                }

                // Optional: when debugging, show what Monaco thinks you clicked (handy!)
                if (s._lastOptions?.debug) {
                    dbg(s._lastOptions, "onMouseDown other", {
                        type: t.type,
                        typeName: Object.entries(monaco.editor.MouseTargetType).find(([, v]) => v === t.type)?.[0],
                        hasPos: !!pos,
                        elementClass: t.element?.className
                    });
                }
            } catch (_) {}
        });
    }
}

export const AIDiff = {
    ensureStyles,
    apply,
    clear,
    dispose,
    acceptAll,
    acceptAllRemaining,
    rejectAllRemaining,
    _diffLines: myersDiff
};

export default AIDiff;

window.AIDiff = AIDiff;
