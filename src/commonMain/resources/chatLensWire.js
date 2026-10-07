// The lens viewer's behaviour, from the DOM alone: bands between neighbouring columns, follow, hover and
// click. chatLens.js renders the columns and calls this; a saved viewer (one HTML file, no Field) embeds this
// file's text and calls it on the saved DOM, so a saved lens behaves as it did in Field.
//
// Columns are .chat-lens-col, top level first, with an svg.chat-lens-gutter between each pair. In column k a
// block's data-ai-sources ("a..b, c") cites blocks of column k+1 by their data-ai-address. A cited block folded
// inside a closed <details> (a round) is represented by the outermost closed <details>: what a reader sees.

const SVG = "http://www.w3.org/2000/svg";

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

/** A band takes the colour of the voice it comes from. */
function voiceOf(block) {
        if (block.classList.contains("you")) return "voice-you";
        if (block.classList.contains("summary")) return "voice-summary";
        return "voice-ai";
}

export function wireChatLens(root) {
        const body = root.querySelector(".chat-lens-body");
        const columns = [...root.querySelectorAll(".chat-lens-col")];
        const gutters = [...root.querySelectorAll(".chat-lens-gutter")];
        const follow = root.querySelector(".chat-lens-follow input");
        // pairs[k]: the bands from column k to column k+1.
        const pairs = gutters.map((gutter, k) => {
                const upper = columns[k], lower = columns[k + 1];
                gutter.replaceChildren();
                const blocks = [...lower.querySelectorAll("[data-ai-address]")];
                const order = new Map(blocks.map((el, i) => [el.dataset.aiAddress, i]));
                const bands = [];
                const covered = new Set();
                upper.querySelectorAll("[data-ai-sources]").forEach(lensBlock => {
                        for (const [from, to] of parseSources(lensBlock.dataset.aiSources)) {
                                const a = order.get(from), b = order.get(to);
                                if (a === undefined || b === undefined) continue;
                                const span = blocks.slice(Math.min(a, b), Math.max(a, b) + 1);
                                span.forEach(el => covered.add(el));
                                const path = document.createElementNS(SVG, "path");
                                path.classList.add("chat-lens-band", voiceOf(lensBlock));
                                gutter.appendChild(path);
                                bands.push({lensBlock, blocks: span, path});
                        }
                });
                // What a level leaves out reads dimmer in the level below: each lens is also a filter.
                blocks.forEach(el => el.classList.toggle("chat-lens-uncited", !covered.has(el)));
                return {upper, lower, gutter, bands, blocks, covered};
        });

        const spanRect = (pair, span) => {
                const first = visibleAnchor(span[0], pair.lower).getBoundingClientRect();
                const last = visibleAnchor(span[span.length - 1], pair.lower).getBoundingClientRect();
                return {top: Math.min(first.top, last.top), bottom: Math.max(first.bottom, last.bottom)};
        };

        const draw = () => {
                for (const pair of pairs) {
                        const g = pair.gutter.getBoundingClientRect();
                        pair.gutter.setAttribute("viewBox", `0 0 ${g.width} ${g.height}`);
                        const mid = g.width / 2;
                        const upperView = pair.upper.getBoundingClientRect(), lowerView = pair.lower.getBoundingClientRect();
                        for (const band of pair.bands) {
                                const l = band.lensBlock.getBoundingClientRect();
                                const visible = l.bottom > upperView.top && l.top < upperView.bottom;
                                band.path.style.display = visible ? "" : "none";
                                if (!visible) continue;
                                const s = spanRect(pair, band.blocks);
                                const clamp = (y, v) => Math.max(v.top, Math.min(v.bottom, y)) - g.top;
                                const lt = clamp(l.top, upperView), lb = clamp(l.bottom, upperView);
                                const st = clamp(s.top, lowerView), sb = clamp(s.bottom, lowerView);
                                band.path.setAttribute("d",
                                        `M0,${lt} C${mid},${lt} ${mid},${st} ${g.width},${st} L${g.width},${sb} C${mid},${sb} ${mid},${lb} 0,${lb} Z`);
                        }
                }
        };
        let pending = false;
        const schedule = () => {
                if (pending) return;
                pending = true;
                requestAnimationFrame(() => { pending = false; draw(); });
        };

        // Follow: the block at the middle of column k pulls its stretch of column k+1 to the middle, in proportion to
        // how far through the block the reader is; column k+1's own scroll then moves column k+2, down the chain.
        const followFrom = (pair) => {
                if (follow && !follow.checked) return;
                const view = pair.upper.getBoundingClientRect();
                const middle = view.top + view.height / 2;
                const band = pair.bands.find(b => { const r = b.lensBlock.getBoundingClientRect(); return r.top <= middle && r.bottom >= middle; });
                if (!band) return;
                const l = band.lensBlock.getBoundingClientRect();
                const s = spanRect(pair, band.blocks);
                const through = (middle - l.top) / Math.max(1, l.height);
                const lowerView = pair.lower.getBoundingClientRect();
                pair.lower.scrollTop += (s.top - lowerView.top) + through * (s.bottom - s.top) - lowerView.height / 2;
        };
        columns.forEach((column, k) => {
                column.addEventListener("scroll", () => { if (pairs[k]) followFrom(pairs[k]); schedule(); }, {passive: true});
                column.addEventListener("toggle", schedule, true);
        });
        root.querySelectorAll("img").forEach(img => { if (!img.complete) img.addEventListener("load", schedule, {once: true}); });
        new ResizeObserver(schedule).observe(body);

        const highlight = (pair, band, on) => {
                band.path.classList.toggle("active", on);
                band.lensBlock.classList.toggle("chat-lens-active", on);
                band.blocks.forEach(el => visibleAnchor(el, pair.lower).classList.toggle("chat-lens-active", on));
        };
        const bringIntoView = (column, rect) => {
                const view = column.getBoundingClientRect();
                column.scrollTo({top: column.scrollTop + rect.top - view.top - 24, behavior: "smooth"});
        };
        for (const pair of pairs) {
                for (const band of pair.bands) {
                        band.lensBlock.addEventListener("mouseenter", () => highlight(pair, band, true));
                        band.lensBlock.addEventListener("mouseleave", () => highlight(pair, band, false));
                }
                // Clicking a citing block brings its stretch below into view; clicking a cited block brings up its citer.
                pair.upper.addEventListener("click", (event) => {
                        if (event.target.closest("a, button, summary")) return;
                        const block = event.target.closest("[data-ai-sources]");
                        const band = block && pair.bands.find(b => b.lensBlock === block);
                        if (band) bringIntoView(pair.lower, spanRect(pair, band.blocks));
                });
                pair.lower.addEventListener("click", (event) => {
                        if (event.target.closest("a, button, summary")) return;
                        const el = event.target.closest("[data-ai-address]");
                        const band = el && pair.bands.find(b => b.blocks.includes(el));
                        if (band) bringIntoView(pair.upper, band.lensBlock.getBoundingClientRect());
                });
        }

        schedule();
        root.chatLensState = () => ({
                columns: columns.length,
                bands: pairs.reduce((n, p) => n + p.bands.length, 0),
                perPair: pairs.map(p => ({bands: p.bands.length, blocks: p.blocks.length, uncited: p.blocks.length - p.covered.size,
                        drawn: p.bands.filter(b => b.path.style.display !== "none" && b.path.getAttribute("d")).length})),
                // The first pair's counts, as the single-lens viewer reported them.
                sourceBlocks: pairs[0]?.blocks.length ?? 0,
                uncited: pairs[0] ? pairs[0].blocks.length - pairs[0].covered.size : 0,
                drawn: pairs.reduce((n, p) => n + p.bands.filter(b => b.path.style.display !== "none" && b.path.getAttribute("d")).length, 0),
        });
        return root;
}
