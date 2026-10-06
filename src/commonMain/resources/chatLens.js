// The lens viewer: a lens (a shorter chat written over a longer one) beside the chat it summarizes, both
// drawn by the chat panel's own renderer, with a band from each lens block to the stretch of the chat it
// stands for. The server side is afield.chatlog.ChatLensViewer; a box shows one with showChatLens(chat, lens).
//
// Each lens block carries data-ai-sources ("a..b, c"); each source block carries data-ai-address. A band's
// source end is the stretch's visible extent: a block folded inside a closed round is represented by the
// round's line. Scrolling the lens moves the chat along with it (follow), so reading the lens walks the
// chat beside it; clicking either end brings the other into view.
import { aiChatHistoryToHtml } from "./afield.mjs";
import { renderAiChatFences } from "./aiChatFenceRenderers.js";

const SVG = "http://www.w3.org/2000/svg";

const escapeText = (s) => String(s ?? "").replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"}[c]));

/** The outermost closed <details> holding [el], or [el] itself: what a reader actually sees of it. */
function visibleAnchor(el, column) {
        let anchor = el;
        for (let d = el.parentElement?.closest("details"); d && column.contains(d); d = d.parentElement?.closest("details")) {
                if (!d.open) anchor = d;
        }
        return anchor;
}

function parseSources(list) {
        return String(list || "").split(",").map(s => s.trim()).filter(Boolean)
                .map(s => s.includes("..") ? s.split("..").map(x => x.trim()) : [s, s]);
}

export async function mountChatLens(root) {
        if (!root) return;
        root.classList.add("ai-chat-window", "chat-lens");
        let data;
        try {
                const response = await fetch(root.dataset.src);
                if (!response.ok) throw new Error(await response.text());
                data = await response.json();
        } catch (error) {
                root.innerHTML = `<div class="chat-lens-error">Lens unavailable: ${escapeText(error.message)}</div>`;
                return;
        }
        const record = data.record || {};
        const stats = record.stats || {};
        root.innerHTML = `
                <div class="chat-lens-head">
                        <span class="chat-lens-title">${escapeText(data.lens || "chat")}</span>
                        <span class="chat-lens-meta">${escapeText(data.title)}${record.model ? " · " + escapeText(record.model) : ""}${stats.compression ? " · " + escapeText(stats.compression) + "× shorter than what it cites" : ""}</span>
                        <label class="chat-lens-follow"><input type="checkbox" checked> follow</label>
                        ${record.brief ? `<div class="chat-lens-brief">${escapeText(record.brief)}</div>` : ""}
                </div>
                <div class="chat-lens-body">
                        <div class="chat-lens-col chat-lens-lens ai-chat-messages"></div>
                        <svg class="chat-lens-gutter" xmlns="${SVG}"></svg>
                        <div class="chat-lens-col chat-lens-source ai-chat-messages"></div>
                </div>`;
        const body = root.querySelector(".chat-lens-body");
        const lensCol = root.querySelector(".chat-lens-lens");
        const sourceCol = root.querySelector(".chat-lens-source");
        const gutter = root.querySelector(".chat-lens-gutter");
        const follow = root.querySelector(".chat-lens-follow input");

        lensCol.innerHTML = aiChatHistoryToHtml(data.lensMarkdown || "");
        sourceCol.innerHTML = aiChatHistoryToHtml(data.source || "");
        lensCol.querySelectorAll("[data-ai-sources] > .ai-chat-model").forEach(badge => {
                badge.textContent = [data.lens, record.model].filter(Boolean).join(" · ") || "Lens";
        });
        renderAiChatFences(lensCol, () => schedule());
        renderAiChatFences(sourceCol, () => schedule());

        // Source blocks in reading order; a citation is a contiguous run of them.
        const sourceBlocks = [...sourceCol.querySelectorAll("[data-ai-address]")];
        const order = new Map(sourceBlocks.map((el, i) => [el.dataset.aiAddress, i]));
        const bands = [];
        const covered = new Set();
        lensCol.querySelectorAll("[data-ai-sources]").forEach(lensBlock => {
                for (const [from, to] of parseSources(lensBlock.dataset.aiSources)) {
                        const a = order.get(from), b = order.get(to);
                        if (a === undefined || b === undefined) continue;
                        const blocks = sourceBlocks.slice(Math.min(a, b), Math.max(a, b) + 1);
                        blocks.forEach(el => covered.add(el));
                        const path = document.createElementNS(SVG, "path");
                        path.classList.add("chat-lens-band");
                        gutter.appendChild(path);
                        bands.push({lensBlock, blocks, path});
                }
        });
        // What the lens leaves out reads dimmer, so the source column shows the filter as well as the chat.
        sourceBlocks.forEach(el => { if (!covered.has(el)) el.classList.add("chat-lens-uncited"); });

        const spanRect = (blocks) => {
                const first = visibleAnchor(blocks[0], sourceCol).getBoundingClientRect();
                const last = visibleAnchor(blocks[blocks.length - 1], sourceCol).getBoundingClientRect();
                return {top: Math.min(first.top, last.top), bottom: Math.max(first.bottom, last.bottom)};
        };

        const draw = () => {
                const g = gutter.getBoundingClientRect();
                gutter.setAttribute("viewBox", `0 0 ${g.width} ${g.height}`);
                const x0 = 0, x1 = g.width, mid = g.width / 2;
                const lensView = lensCol.getBoundingClientRect(), sourceView = sourceCol.getBoundingClientRect();
                for (const band of bands) {
                        const l = band.lensBlock.getBoundingClientRect();
                        const s = spanRect(band.blocks);
                        const visible = l.bottom > lensView.top && l.top < lensView.bottom;
                        band.path.style.display = visible ? "" : "none";
                        if (!visible) continue;
                        const clamp = (y, v) => Math.max(v.top, Math.min(v.bottom, y)) - g.top;
                        const lt = clamp(l.top, lensView), lb = clamp(l.bottom, lensView);
                        const st = clamp(s.top, sourceView), sb = clamp(s.bottom, sourceView);
                        band.path.setAttribute("d",
                                `M${x0},${lt} C${mid},${lt} ${mid},${st} ${x1},${st} L${x1},${sb} C${mid},${sb} ${mid},${lb} ${x0},${lb} Z`);
                }
        };
        let pending = false;
        const schedule = () => {
                if (pending) return;
                pending = true;
                requestAnimationFrame(() => { pending = false; draw(); });
        };

        // Follow: the lens block at the middle of the lens column pulls its stretch of the chat to the middle of
        // the chat column, in proportion to how far through the block the reader is.
        const followLens = () => {
                if (!follow.checked) return;
                const view = lensCol.getBoundingClientRect();
                const middle = view.top + view.height / 2;
                const band = bands.find(b => { const r = b.lensBlock.getBoundingClientRect(); return r.top <= middle && r.bottom >= middle; });
                if (!band) return;
                const l = band.lensBlock.getBoundingClientRect();
                const s = spanRect(band.blocks);
                const through = (middle - l.top) / Math.max(1, l.height);
                const sourceView = sourceCol.getBoundingClientRect();
                const target = sourceCol.scrollTop + (s.top - sourceView.top) + through * (s.bottom - s.top) - sourceView.height / 2;
                sourceCol.scrollTop = target;
        };
        lensCol.addEventListener("scroll", () => { followLens(); schedule(); }, {passive: true});
        sourceCol.addEventListener("scroll", schedule, {passive: true});
        sourceCol.addEventListener("toggle", schedule, true);
        lensCol.addEventListener("toggle", schedule, true);
        // The renderer defers real-URL pictures (loading="lazy"); here a picture that arrives late moves every
        // band below it, and the pictures come from this Field, so load them all now.
        root.querySelectorAll("img").forEach(img => {
                img.loading = "eager";
                img.addEventListener("load", schedule, {once: true});
        });
        new ResizeObserver(schedule).observe(body);

        const highlight = (band, on) => {
                band.path.classList.toggle("active", on);
                band.lensBlock.classList.toggle("chat-lens-active", on);
                band.blocks.forEach(el => visibleAnchor(el, sourceCol).classList.toggle("chat-lens-active", on));
        };
        for (const band of bands) {
                band.lensBlock.addEventListener("mouseenter", () => highlight(band, true));
                band.lensBlock.addEventListener("mouseleave", () => highlight(band, false));
                band.lensBlock.addEventListener("click", (event) => {
                        if (event.target.closest("a, button, summary")) return;
                        const s = spanRect(band.blocks), view = sourceCol.getBoundingClientRect();
                        sourceCol.scrollTo({top: sourceCol.scrollTop + s.top - view.top - 24, behavior: "smooth"});
                });
        }
        // Clicking a cited stretch brings its lens block into view.
        sourceCol.addEventListener("click", (event) => {
                if (event.target.closest("a, button, summary")) return;
                const el = event.target.closest("[data-ai-address]");
                const band = el && bands.find(b => b.blocks.includes(el));
                if (!band) return;
                const r = band.lensBlock.getBoundingClientRect(), view = lensCol.getBoundingClientRect();
                lensCol.scrollTo({top: lensCol.scrollTop + r.top - view.top - 24, behavior: "smooth"});
        });

        schedule();
        root.chatLensState = () => ({bands: bands.length, sourceBlocks: sourceBlocks.length, uncited: sourceBlocks.length - covered.size,
                drawn: bands.filter(b => b.path.style.display !== "none" && b.path.getAttribute("d")).length});
        return root;
}
