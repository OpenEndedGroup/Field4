/*
 * Shared by the inspector embeds (tree.html, browser.html, logs.html).
 *
 * 1. inspectorValueHTML(text, kind): a row summary as lightly highlighted HTML, so the user's values
 *    (numbers, strings, booleans, vectors, toString text) read as content in Field orange / cyan,
 *    while counts like "6 entries" stay in the UI's muted grey.
 * 2. inspectorVisibleBounds(): the part of this frame's window that is actually on screen, in this
 *    frame's coordinates. An output panel can be shorter than the iframe it holds, so "visible in
 *    the list" is not "visible to the user".
 * 3. inspectorRevealIn(scroller, el): scroll `scroller` so `el` is inside both the scroller and the
 *    visible bounds.
 */
(() => {
    const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    const CHROME = /^(\d+ (items?|entries|entry)|iterable|\{.*\}|empty)$/;

    function tokens(text) {
        // numbers, words, quoted strings, and everything else as punctuation
        const re = /("(?:[^"\\]|\\.)*"?)|(-?\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?f?)|([A-Za-z_][\w$]*)|(\s+)|(.)/g;
        let out = "", m;
        while ((m = re.exec(text))) {
            if (m[1] !== undefined) out += `<span class="v-str">${esc(m[1])}</span>`;
            else if (m[2] !== undefined) out += `<span class="v-num">${esc(m[2])}</span>`;
            else if (m[3] !== undefined) {
                const w = m[3];
                out += (w === "true" || w === "false") ? `<span class="v-num">${w}</span>`
                    : w === "null" ? `<span class="v-null">null</span>`
                    : `<span class="v-word">${esc(w)}</span>`;
            }
            else if (m[4] !== undefined) out += m[4];
            else out += `<span class="v-punc">${esc(m[5])}</span>`;
        }
        return out;
    }

    window.inspectorValueHTML = function (text, kind) {
        const t = String(text ?? "");
        if (!t) return "";
        if (kind === "dir" && CHROME.test(t)) return `<span class="v-chrome">${esc(t)}</span>`;
        if (t.startsWith("\"")) {
            const end = t.lastIndexOf("\"");
            return `<span class="v-str">${esc(end > 0 ? t.slice(0, end + 1) : t)}</span>` +
                (end > 0 && end < t.length - 1 ? `<span class="v-chrome">${esc(t.slice(end + 1))}</span>` : "");
        }
        if (t === "null") return `<span class="v-null">null</span>`;
        return tokens(t);
    };

    window.inspectorVisibleBounds = function () {
        let top = 0, bottom = window.innerHeight;
        try {
            const fe = window.frameElement;
            if (fe) {
                const pw = fe.ownerDocument.defaultView;
                const fr = fe.getBoundingClientRect();
                let vt = 0, vb = pw.innerHeight;
                for (let el = fe.parentElement; el; el = el.parentElement) {
                    const oy = pw.getComputedStyle(el).overflowY;
                    if (oy !== "visible") { const r = el.getBoundingClientRect(); vt = Math.max(vt, r.top); vb = Math.min(vb, r.bottom); }
                }
                top = Math.max(0, vt - fr.top);
                bottom = Math.min(window.innerHeight, vb - fr.top);
            }
        } catch (_) { /* cross-origin parent: our own window is the best we know */ }
        return { top, bottom: Math.max(top + 1, bottom) };
    };

    window.inspectorRevealIn = function (scroller, el) {
        if (!scroller || !el) return;
        const vis = window.inspectorVisibleBounds();
        const r = el.getBoundingClientRect(), sr = scroller.getBoundingClientRect();
        const top = Math.max(sr.top, vis.top), bottom = Math.min(sr.bottom, vis.bottom);
        if (bottom - top < r.height) { scroller.scrollTop += r.top - top; return; }
        if (r.top < top) scroller.scrollTop -= (top - r.top);
        else if (r.bottom > bottom) scroller.scrollTop += (r.bottom - bottom);
    };
})();
