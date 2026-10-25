// The lens viewer: a lens (a shorter chat written over a longer one) beside the chat it summarizes, every
// column drawn by the chat panel's own renderer, with bands from each lens block to the stretch it stands for.
// A lens over a lens shows the whole chain, top level first. The server side is afield.ai.conversation.chatlog.ChatLensViewer;
// a box shows one with showChatLens(chat, lens). Behaviour (bands, follow, hover) is chatLensWire.js, which a
// saved viewer carries with it.
import { aiChatHistoryToHtml } from "./afield.mjs";
import { renderAiChatFences } from "./aiChatFenceRenderers.js";
import { wireChatLens } from "./chatLensWire.js";

const escapeText = (s) => String(s ?? "").replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"}[c]));

function columnLabel(column) {
        const record = column.record || {};
        const stats = record.stats || {};
        const parts = [record.model, stats.compression ? `${stats.compression}× shorter` : ""].filter(Boolean);
        return `<span class="chat-lens-col-name">${escapeText(column.name)}</span>` +
                (parts.length ? ` <span class="chat-lens-col-meta">${escapeText(parts.join(" · "))}</span>` : "");
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
        const columns = data.columns || [];
        const top = columns[0] || {};
        const record = top.record || {};
        const template = columns.map((_, i) => i === columns.length - 1 ? "minmax(0, 1.25fr)" : "minmax(0, 1fr) 56px").join(" ");
        root.innerHTML = `
                <div class="chat-lens-head">
                        <span class="chat-lens-title">${escapeText(top.name || "chat")}</span>
                        <span class="chat-lens-meta">${escapeText(data.title)}</span>
                        <span class="chat-lens-tools">
                                <label class="chat-lens-follow"><input type="checkbox" checked> follow</label>
                                <button type="button" class="chat-lens-save" title="Save this view as one HTML file that opens without Field">save</button>
                        </span>
                        ${record.brief ? `<div class="chat-lens-brief">${escapeText(record.brief)}</div>` : ""}
                </div>
                <div class="chat-lens-labels" style="grid-template-columns:${template}">
                        ${columns.map((c, i) => `<div class="chat-lens-label">${columnLabel(c)}</div>${i < columns.length - 1 ? "<div></div>" : ""}`).join("")}
                </div>
                <div class="chat-lens-body" style="grid-template-columns:${template}">
                        ${columns.map((c, i) => {
                                const role = i === 0 ? "chat-lens-lens" : i === columns.length - 1 ? "chat-lens-source" : "chat-lens-middle";
                                return `<div class="chat-lens-col ${role} ai-chat-messages" data-chat="${escapeText(c.id)}"></div>` +
                                        (i < columns.length - 1 ? `<svg class="chat-lens-gutter" xmlns="http://www.w3.org/2000/svg"></svg>` : "");
                        }).join("")}
                </div>`;
        const cols = [...root.querySelectorAll(".chat-lens-col")];
        columns.forEach((column, i) => {
                const el = cols[i];
                el.innerHTML = aiChatHistoryToHtml(column.markdown || "");
                // A lens block is labelled with its lens and the model that wrote it.
                if (column.kind === "lens") {
                        el.querySelectorAll("[data-ai-sources] > .ai-chat-model").forEach(badge => {
                                badge.textContent = [column.name, column.record?.model].filter(Boolean).join(" · ") || "Lens";
                        });
                }
                renderAiChatFences(el, () => {});
        });
        // The renderer defers real-URL pictures (loading="lazy"); here a picture that arrives late moves every
        // band below it, and the pictures come from this Field, so load them all now.
        root.querySelectorAll("img").forEach(img => { img.loading = "eager"; });
        wireChatLens(root);
        root.querySelector(".chat-lens-save").addEventListener("click", () => saveChatLens(root, top.name || "lens"));
        return root;
}

/** Every custom property in force on [el], as one declaration list: the theme travels with a saved view. */
function themeOf(el) {
        const style = getComputedStyle(el);
        const out = [];
        for (const name of style) if (name.startsWith("--")) out.push(`${name}:${style.getPropertyValue(name)}`);
        return out.join(";");
}

async function asDataUrl(src) {
        const blob = await (await fetch(src)).blob();
        return await new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onload = () => resolve(reader.result);
                reader.onerror = reject;
                reader.readAsDataURL(blob);
        });
}

/** The view as one self-contained HTML document: what the save button writes. */
export async function chatLensHtml(root, name) {
        const copy = root.cloneNode(true);
        copy.querySelectorAll(".chat-lens-save").forEach(b => b.remove());
        copy.querySelectorAll(".active, .chat-lens-active").forEach(el => el.classList.remove("active", "chat-lens-active"));
        const live = root.querySelectorAll(".chat-lens-col");
        copy.querySelectorAll(".chat-lens-col").forEach((col, i) => { col.dataset.scrollTop = String(live[i].scrollTop); });
        for (const img of copy.querySelectorAll("img")) {
                const src = img.getAttribute("src");
                if (src && !src.startsWith("data:")) img.setAttribute("src", await asDataUrl(src));
        }
        copy.style.height = "100vh";
        const [css, wire] = await Promise.all([
                fetch(new URL("./field.css", import.meta.url)).then(r => r.text()),
                fetch(new URL("./chatLensWire.js", import.meta.url)).then(r => r.text()),
        ]);
        const title = `${name} · ${root.querySelector(".chat-lens-meta")?.textContent ?? ""}`;
        return `<!doctype html>
<html><head><meta charset="utf-8"><title>${escapeText(title)}</title>
<style>${css}</style>
<style>:root{${themeOf(root)}} html,body{margin:0;background:#000;height:100%} .chat-lens{border-radius:0}</style>
</head><body>
${copy.outerHTML}
<script type="module">
${wire.replace(/<\/script/gi, "<\\/script")}
const root = document.querySelector(".chat-lens");
wireChatLens(root);
root.querySelectorAll(".chat-lens-col").forEach(col => { col.scrollTop = Number(col.dataset.scrollTop || 0); });
</script>
</body></html>`;
}

/**
 * Save the view as it is rendered now: one HTML file holding the columns, Field's chat CSS, the theme's
 * colours, every picture as a data URL, and chatLensWire.js, so bands, follow and hover work with no Field.
 */
export async function saveChatLens(root, name) {
        const button = root.querySelector(".chat-lens-save");
        if (button) { button.disabled = true; button.textContent = "saving…"; }
        try {
                const html = await chatLensHtml(root, name);
                const link = document.createElement("a");
                link.href = URL.createObjectURL(new Blob([html], {type: "text/html"}));
                link.download = `${name}.html`;
                document.body.appendChild(link);
                link.click();
                link.remove();
                setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
                return html.length;
        } finally {
                if (button) { button.disabled = false; button.textContent = "save"; }
        }
}
