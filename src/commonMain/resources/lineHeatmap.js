import { sendWithReply } from "./afield.mjs";

const LineHeatmap = (() => {
    const STATE = new WeakMap();
    let requestSerial = 0;

    const LEVELS = 12;
    const STORAGE_KEYS = {
        churn: "afield.lineHeatmap.historyChurn.visible",
        exec: "afield.lineHeatmap.executionHistory.visible"
    };

    function clamp01(v) {
        return Math.max(0, Math.min(1, Number(v) || 0));
    }

    function lerp(a, b, t) {
        return a + (b - a) * t;
    }

    function interpolateStops(stops, t) {
        const value = clamp01(t);
        for (let i = 1; i < stops.length; i += 1) {
            const prev = stops[i - 1];
            const next = stops[i];
            if (value <= next[0]) {
                const local = (value - prev[0]) / Math.max(0.000001, next[0] - prev[0]);
                return [
                    lerp(prev[1][0], next[1][0], local),
                    lerp(prev[1][1], next[1][1], local),
                    lerp(prev[1][2], next[1][2], local)
                ];
            }
        }
        return stops[stops.length - 1][1];
    }

    function rybToRgb([r, y, b]) {
        const white = [1, 1, 1];
        const red = [1, 0, 0];
        const yellow = [1, 1, 0];
        const blue = [0.163, 0.373, 0.6];
        const violet = [0.5, 0, 0.5];
        const orange = [1, 0.5, 0];
        const green = [0, 0.66, 0.2];
        const black = [0.2, 0.094, 0];

        const c000 = white.map((v, i) => lerp(v, blue[i], b));
        const c100 = red.map((v, i) => lerp(v, violet[i], b));
        const c010 = yellow.map((v, i) => lerp(v, green[i], b));
        const c110 = orange.map((v, i) => lerp(v, black[i], b));
        const c00 = c000.map((v, i) => lerp(v, c100[i], r));
        const c10 = c010.map((v, i) => lerp(v, c110[i], r));
        return c00.map((v, i) => lerp(v, c10[i], y));
    }

    function rybHeatColor(level, alpha = 0.62) {
        const t = LEVELS <= 1 ? 0 : (level - 1) / (LEVELS - 1);
        const ryb = interpolateStops([
            [0.0, [0.0, 0.0, 1.0]],
            [0.55, [0.0, 1.0, 0.0]],
            [0.75, [1.0, 0.55, 0.0]],
            [1.0, [1.0, 0.0, 0.0]]
        ], t);
        const rgb = rybToRgb(ryb).map((v) => Math.round(clamp01(v) * 255));
        return `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, ${alpha})`;
    }

    function storedVisible(kind) {
        try {
            return window.localStorage?.getItem(STORAGE_KEYS[kind]) === "true";
        } catch (_) {
            return false;
        }
    }

    function storeVisible(kind, value) {
        try {
            window.localStorage?.setItem(STORAGE_KEYS[kind], value ? "true" : "false");
        } catch (_) {
        }
    }

    function ensureStyles() {
        if (document.getElementById("afield-line-heatmap-styles")) return;
        const style = document.createElement("style");
        style.id = "afield-line-heatmap-styles";
        const rules = [];
        rules.push(`
.monaco-editor .line-numbers.afield-line-heat {
    position: relative;
    width: 25px !important;
    padding-right: 10px;
    overflow: hidden;
    border-radius: 0;
}
.monaco-editor .line-numbers.afield-line-heat-exec::after {
    content: "";
    position: absolute;
    left: 2px;
    bottom: 1px;
    height: 2px;
    width: var(--afield-exec-bar-width, 0%);
    background: var(--afield-exec-bar-color, transparent);
    pointer-events: none;
}
`);
        for (let i = 1; i <= LEVELS; i += 1) {
            const color = rybHeatColor(i, 0.58);
            const execColor = rybHeatColor(i, 0.88);
            const execWidth = Math.max(12, Math.round((i / LEVELS) * 100));
            rules.push(`
.monaco-editor .line-numbers.afield-line-heat-churn-${i} {
    background: ${color};
}
.monaco-editor .line-numbers.afield-line-heat-exec-${i} {
    --afield-exec-bar-width: ${execWidth}%;
    --afield-exec-bar-color: ${execColor};
}`);
        }
        style.textContent = rules.join("\n");
        document.head.appendChild(style);
    }

    function stateFor(editor) {
        let state = STATE.get(editor);
        if (!state) {
            state = {
                churnDecorations: editor.createDecorationsCollection([]),
                execDecorations: editor.createDecorationsCollection([]),
                churnLines: [],
                requestId: 0,
                execRequestId: 0,
                refreshTimer: null,
                visible: {
                    churn: storedVisible("churn"),
                    exec: storedVisible("exec")
                }
            };
            STATE.set(editor, state);
        }
        return state;
    }

    function selectedContext(editor) {
        const selected = window.currentSelectedCode || [];
        if (!editor || !editor.getModel || !selected || selected.length !== 1) return null;
        const uid = selected[0] && selected[0].uid;
        const property = String(window.currentSelectedProperty || window.selectedProperty || "code");
        const model = editor.getModel();
        if (!uid || !property || !model) return null;
        return {
            uid,
            property,
            text: model.getValue()
        };
    }

    function rpc(name, payload) {
        return new Promise((resolve) => {
            const callbackName = `line-heatmap-${name}-${Date.now()}-${requestSerial++}`;
            sendWithReply(name, payload || {}, callbackName, (_message, response) => {
                resolve(response || {});
            });
        });
    }

    function level(value, min, max) {
        const v = Number(value) || 0;
        const lo = Number(min) || 0;
        const m = Number(max) || 0;
        if (v <= 0 || m <= 0) return 0;
        if (m <= lo) return 1;
        const normalized = (Math.log1p(v) - Math.log1p(lo)) / Math.max(0.000001, Math.log1p(m) - Math.log1p(lo));
        return Math.max(1, Math.min(LEVELS, Math.round(normalized * (LEVELS - 1)) + 1));
    }

    function escapeHtml(text) {
        return String(text || "").replace(/[&<>"']/g, (ch) => ({
            "&": "&amp;",
            "<": "&lt;",
            ">": "&gt;",
            '"': "&quot;",
            "'": "&#39;"
        })[ch]);
    }

    function heatmapHoverLabel(title) {
        return {
            value: `<span style="font-size:70%">${escapeHtml(title)}</span>`,
            supportHtml: true
        };
    }

    function buildDecorations(lines, kind) {
        const values = lines.map((item) => Number(item.score || item.count || 0)).filter((v) => v > 0);
        const min = values.length ? Math.min(...values) : 0;
        const max = values.length ? Math.max(...values) : 0;
        return lines.map((item) => {
            const line = Math.max(1, Number(item.line) || 1);
            const score = Number(item.score || item.count || 0);
            const l = level(score, min, max);
            if (l <= 0) return null;
            const title = kind === "churn"
                ? `recent churn ${score.toFixed(2)} (${Number(item.count || 0)} changes)`
                : `execution activity ${Math.round(score)}`;
            return {
                range: new monaco.Range(line, 1, line, 1),
                options: {
                    isWholeLine: true,
                    lineNumberClassName: `afield-line-heat afield-line-heat-${kind} afield-line-heat-${kind}-${l}`,
                    stickiness: 1,
                    hoverMessage: heatmapHoverLabel(title)
                }
            };
        }).filter(Boolean);
    }

    async function refreshChurn(editor) {
        ensureStyles();
        const state = stateFor(editor);
        if (!state.visible.churn) {
            state.churnLines = [];
            state.churnDecorations.set([]);
            return;
        }
        const context = selectedContext(editor);
        if (!context) {
            state.churnLines = [];
            state.churnDecorations.set([]);
            return;
        }
        const requestId = ++state.requestId;
        const response = await rpc("history-line-churn", {
            uid: context.uid,
            property: context.property,
            propertyText: context.text,
            maxVersions: 160
        });
        if (requestId !== state.requestId) return;
        if (response.uid !== context.uid || response.property !== context.property) return;
        state.churnLines = Array.isArray(response.lines) ? response.lines : [];
        state.churnDecorations.set(buildDecorations(state.churnLines, "churn"));
    }

    async function refreshExecution(editor) {
        ensureStyles();
        const state = stateFor(editor);
        if (!state.visible.exec) {
            state.execDecorations.set([]);
            return;
        }
        const context = selectedContext(editor);
        if (!context) {
            state.execDecorations.set([]);
            return;
        }
        const requestId = ++state.execRequestId;
        const response = await rpc("transcript-line-activity", {
            uid: context.uid,
            property: context.property,
            propertyText: context.text,
            maxEntries: 4000
        });
        if (requestId !== state.execRequestId) return;
        if (response.uid !== context.uid || response.property !== context.property) return;
        state.execDecorations.set(buildDecorations(Array.isArray(response.lines) ? response.lines : [], "exec"));
    }

    function refresh(editor) {
        if (!editor) return;
        refreshExecution(editor);
        refreshChurn(editor);
    }

    function scheduleRefresh(editor, includeChurn = false) {
        if (!editor) return;
        const state = stateFor(editor);
        if (state.refreshTimer != null) clearTimeout(state.refreshTimer);
        state.refreshTimer = setTimeout(() => {
            state.refreshTimer = null;
            refreshExecution(editor);
            if (includeChurn) refreshChurn(editor);
        }, includeChurn ? 260 : 80);
    }

    function install(editor) {
        if (!editor || editor._afieldLineHeatmapInstalled) return;
        editor._afieldLineHeatmapInstalled = true;
        ensureStyles();
        stateFor(editor);
        installActions(editor);
        const model = editor.getModel && editor.getModel();
        if (model && model.onDidChangeContent) {
            model.onDidChangeContent(() => scheduleRefresh(editor, true));
        }
        setTimeout(() => refresh(editor), 0);
    }

    function setVisible(editor, kind, value) {
        if (!editor || (kind !== "churn" && kind !== "exec")) return;
        const state = stateFor(editor);
        state.visible[kind] = !!value;
        storeVisible(kind, state.visible[kind]);
        if (!state.visible[kind]) {
            if (kind === "churn") state.churnDecorations.set([]);
            else state.execDecorations.set([]);
            return;
        }
        if (kind === "churn") refreshChurn(editor);
        else refreshExecution(editor);
    }

    function toggleVisible(editor, kind) {
        const state = stateFor(editor);
        setVisible(editor, kind, !state.visible[kind]);
    }

    function installActions(editor) {
        if (!editor || editor._afieldLineHeatmapActionsInstalled || typeof editor.addAction !== "function") return;
        editor._afieldLineHeatmapActionsInstalled = true;
        editor.addAction({
            id: "afield.toggleHistoryEditChurnHeatmap",
            label: "Toggle History Edit Churn Heatmap",
            run: () => toggleVisible(editor, "churn")
        });
        editor.addAction({
            id: "afield.toggleExecutionHistoryHeatmap",
            label: "Toggle Execution History Heatmap",
            run: () => toggleVisible(editor, "exec")
        });
    }

    function scheduleExecutionRefreshFor(uid, property) {
        const editor = window.globalEditor;
        const context = selectedContext(editor);
        if (context && context.uid === String(uid || "") && context.property === String(property || "code")) {
            scheduleRefresh(editor, false);
        }
    }

    function recordExecution(payload) {
        if (!payload) return;
        const uid = String(payload.uid || "");
        const property = String(payload.property || "code");
        scheduleExecutionRefreshFor(uid, property);
    }

    function recordExecutionRange(uid, property, startLine, endLine) {
        scheduleExecutionRefreshFor(uid, property);
    }

    return { install, refresh, scheduleRefresh, refreshExecution, refreshChurn, setVisible, toggleVisible, recordExecution, recordExecutionRange };
})();

window.LineHeatmap = LineHeatmap;
if (window.globalEditor) {
    setTimeout(() => LineHeatmap.install(window.globalEditor), 0);
}
