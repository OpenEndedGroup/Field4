// Typesets the `.ai-math` spans aiChatHtml.kt writes for `\(…\)`, `$…$`, `$$…$$` and `\[…\]`. The span holds
// its source until KaTeX replaces it, and keeps it (marked `ai-math-error`) when the TeX does not parse.
const MODULE = "/katex/field-katex.js";
let katex = null;
let loading = null;

function typeset(module, span) {
    span.dataset.typeset = "";
    try {
        span.innerHTML = module.mathToHtml(span.dataset.tex || "", span.classList.contains("ai-math-display"));
    } catch (error) {
        span.classList.add("ai-math-error");
        span.title = String(error?.message || error);
    }
}

/**
 * Typeset every untypeset math span under [root]. Once KaTeX has loaded this is synchronous, so a chat that
 * rebuilds its transcript per streamed chunk never shows raw TeX between chunks. Before that it returns 1 and
 * calls [onLayoutChange]("settled") when the first load finishes; otherwise 0.
 */
export function typesetAiChatMath(root, onLayoutChange = () => {}) {
    const spans = [...root.querySelectorAll(".ai-math:not([data-typeset])")];
    if (spans.length === 0) return 0;
    if (katex) {
        spans.forEach(span => typeset(katex, span));
        return 0;
    }
    loading ??= import(MODULE).then(module => (katex = module));
    loading.then(module => {
        spans.forEach(span => { if (span.isConnected) typeset(module, span); });
    }, error => {
        loading = null;
        console.warn("AI4 math renderer unavailable:", error);
    }).finally(() => onLayoutChange("settled"));
    return 1;
}
