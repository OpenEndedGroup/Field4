import { sendWithReply, clearReplyHandler } from "./afield.mjs";
import { boxCostSnapshot } from "./profileTimelineModel.js";

// The Profiling panel's "Box functions" tab: per-function self and total time of the boxes opted in
// to per-function cost (afield.runtime.bytecode.BoxCost, over the `box-cost` RPC). Measuring a box
// takes effect at its next run. Polls while the tab is shown.

const node = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = text; return n; };
const ms = n => n >= 100 ? n.toFixed(0) : n >= 1 ? n.toFixed(1) : n.toFixed(3);

export function createProfileBoxCost(parent) {
    const toolbar = node("div", "profile-toolbar");
    const measure = node("button", "", "Measure selected box"); measure.id = "profile-box-cost-measure";
    measure.title = "Instrument the selected box's functions from its next run: self and total time per function";
    const clear = node("button", "", "Clear totals"); clear.id = "profile-box-cost-clear";
    const status = node("span", "profile-status", ""); status.id = "profile-box-cost-status"; status.setAttribute("role", "status");
    toolbar.append(measure, clear, status);
    const boxes = node("div", "profile-box-cost-boxes"); boxes.id = "profile-box-cost-boxes";
    const table = node("table", "profile-box-cost-table"); table.id = "profile-box-cost-table";
    const note = node("p", "", "Self time excludes the measured functions a function calls; total includes them and counts a recursive function once. Functions with no calls and no loops are not measured: their time is their caller's. Each measured call costs about 30 ns.");
    parent.append(toolbar, boxes, table, note);

    let shown = false, timer = null, pending = false, serial = 0, replyName = "", data = null;

    function request(action = "snapshot", uid = "") {
        if (pending) return;
        pending = true;
        replyName = `profile-box-cost-${++serial}`;
        sendWithReply("box-cost", { action, uid }, replyName, (_m, response) => {
            pending = false;
            data = boxCostSnapshot(response);
            render();
            schedule();
            return "remove";
        });
    }

    function schedule() { clearTimeout(timer); if (shown) timer = setTimeout(() => request(), 500); }

    function selectedUid() {
        const list = window.selectedList || [];
        return list.length === 1 ? list[0].uid : null;
    }

    measure.addEventListener("click", () => {
        const uid = selectedUid();
        if (!uid) { status.textContent = "Select exactly one box"; return; }
        pending = false; request("measure", uid);
    });
    clear.addEventListener("click", () => { pending = false; request("clear"); });

    function render() {
        if (!data) return;
        if (!data.ok) { status.textContent = data.error || "Box cost request failed"; return; }
        status.textContent = `${data.boxes.filter(b => b.measuring).length} measured · ${(data.elapsedMs / 1000).toFixed(1)} s since cleared`;
        boxes.replaceChildren();
        if (!data.boxes.length) boxes.append(node("p", "", "No box is measured. Select a box, press Measure selected box, then run it."));
        for (const b of data.boxes) {
            const row = node("div", "profile-box-cost-box"); row.dataset.boxId = b.boxId;
            row.append(node("strong", "", b.name), node("span", "", ` · ${b.runs} evaluations and resumes · ${ms(b.totalMs)} ms `));
            const toggle = node("button", "", b.measuring ? "Stop measuring" : "Measure");
            toggle.addEventListener("click", () => { pending = false; request(b.measuring ? "unmeasure" : "measure", b.boxId); });
            row.append(toggle);
            boxes.append(row);
        }
        table.replaceChildren();
        const head = node("tr");
        for (const h of ["Function", "Box", "Line", "Calls", "Self ms", "Total ms"]) head.append(node("th", "", h));
        table.append(head);
        const names = new Map(data.boxes.map(b => [b.boxId, b.name]));
        for (const f of data.functions) {
            const tr = node("tr", f.running ? "profile-box-cost-running" : "");
            tr.append(node("td", "", f.function + (f.running ? " ●" : "")), node("td", "", names.get(f.boxId) || f.boxId));
            const line = node("td");
            if (f.line > 0) {
                const go = node("button", "", String(f.line));
                go.addEventListener("click", () => window.navigateToBoxProperty?.(f.boxId, f.property || "code", null, f.line));
                line.append(go);
            } else line.textContent = "—";
            tr.append(line, node("td", "", f.calls.toLocaleString()), node("td", "", ms(f.selfMs)), node("td", "", ms(f.totalMs)));
            table.append(tr);
        }
    }

    return {
        setShown(on) {
            if (on === shown) return;
            shown = on;
            if (on) { pending = false; request(); }
            else { clearTimeout(timer); if (replyName) clearReplyHandler(replyName); pending = false; }
        },
    };
}
