import katex from "katex";

// Model-written TeX: no \href/\url/\htmlClass, bounded macro expansion and sizes, and a parse
// error is thrown so the caller can keep the source visible instead of KaTeX's red error text.
const OPTIONS = { throwOnError: true, trust: false, strict: "ignore", maxExpand: 500, maxSize: 20, output: "htmlAndMathml" };
const MAX_SOURCE = 4000;
const cache = new Map();

// The stylesheet and fonts sit beside this module; linking them here means no page has to.
function ensureStylesheet() {
    const href = new URL("./katex.min.css", import.meta.url).href;
    if (document.querySelector(`link[data-field-katex]`)) return;
    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = href;
    link.dataset.fieldKatex = "";
    document.head.appendChild(link);
}

/** The typeset HTML for [tex], cached because the chat re-renders its whole transcript per streamed chunk. */
export function mathToHtml(tex, displayMode) {
    ensureStylesheet();
    if (tex.length > MAX_SOURCE) throw new Error("math exceeds 4,000 characters");
    const key = (displayMode ? "D" : "I") + tex;
    let html = cache.get(key);
    if (html === undefined) {
        html = katex.renderToString(tex, { ...OPTIONS, displayMode });
        if (cache.size > 500) cache.clear();
        cache.set(key, html);
    }
    return html;
}

/** The `math` fence's renderer (ai4-fence-renderers.json): one display equation, or several on their own lines. */
export function renderMathFence(source) {
    const node = document.createElement("div");
    node.className = "ai-math-fence";
    node.innerHTML = mathToHtml(source.trim(), true);
    return node;
}
