// Typesets the `.arithmatex` elements pymdownx.arithmatex writes for `\(…\)` and `\[…\]`, with the KaTeX
// build Field already vendors for AI chat (src/jsMain/resources/katex, copied to the site root by
// tools/sync_offline_assets.py). KaTeX loads only on a page that has math; an expression that does not
// parse keeps its source text.
(function() {
  const KATEX = new URL("../katex/", document.currentScript.src);
  let katex = null;

  function load() {
    if (!katex) {
      const css = document.createElement("link");
      css.rel = "stylesheet";
      css.href = new URL("katex.min.css", KATEX).href;
      document.head.appendChild(css);
      katex = import(new URL("field-katex.js", KATEX).href);
    }
    return katex;
  }

  function typesetAll() {
    const nodes = [...document.querySelectorAll(".arithmatex:not([data-typeset])")];
    if (nodes.length === 0) return;
    load().then(module => {
      for (const node of nodes) {
        node.dataset.typeset = "";
        const source = node.textContent.trim();
        const display = node.tagName === "DIV";
        const tex = source.replace(/^\\[([]/, "").replace(/\\[)\]]$/, "");
        try {
          node.innerHTML = module.mathToHtml(tex, display);
        } catch (error) {
          node.title = String(error?.message || error);
        }
      }
    });
  }

  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(() => setTimeout(typesetAll, 0));
  } else {
    document.addEventListener("DOMContentLoaded", typesetAll);
  }
})();
