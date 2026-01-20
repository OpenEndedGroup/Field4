(() => {
  const MARKER_LINE_RE = /^\s*:::(before|after|static)?\s*$/;

  function extractLanguage(codeEl) {
    const cls = codeEl.className || "";
    const tokens = Array.from(cls.matchAll(/language-([\w-]+)/g)).map((m) => m[1]);
    const preferred = tokens.find((t) => t !== "diff" && t !== "diffstatic");
    if (preferred) return preferred;
    if (codeEl.dataset.lang) return codeEl.dataset.lang;
    if (codeEl.dataset.language) return codeEl.dataset.language;
    return "kotlin";
  }

  function classify(codeEl) {
    const cls = codeEl.className || "";
    const hasDiffClass = cls.includes("code-diff-fence") || cls.includes("language-diff");
    const hasDiffStaticClass = cls.includes("code-diff-fence-static") || cls.includes("language-diffstatic");
    const text = codeEl.textContent || "";
    const hasMarkers = text.includes(":::before") || text.includes(":::after");
    const hasStaticMarker = text.includes(":::static");
    const hasBeforeOnly = text.includes(":::before") && !text.includes(":::after");
    if (hasDiffStaticClass) return "static";
    if (hasStaticMarker) return "static";
    if (hasBeforeOnly) return "static";
    if (hasDiffClass || hasMarkers) return "diff";
    return null;
  }

  function stripMarkerLines(text) {
    return (text || "")
      .split(/\r?\n/)
      .filter((line) => !MARKER_LINE_RE.test(line))
      .join("\n");
  }

  function splitBeforeAfter(text) {
    const lines = (text || "").split(/\r?\n/);

    let mode = null; // null | 'before' | 'after'
    let sawAnyMarker = false;
    const beforeLines = [];
    const afterLines = [];

    for (const line of lines) {
      if (MARKER_LINE_RE.test(line)) {
        sawAnyMarker = true;
        const trimmed = line.trim();
        if (trimmed === ":::before") mode = "before";
        else if (trimmed === ":::after") mode = "after";
        // :::static and ::: are just stripped
        continue;
      }

      if (mode === "after") afterLines.push(line);
      else if (mode === "before") beforeLines.push(line);
      else beforeLines.push(line);
    }

    if (!sawAnyMarker) return { before: text, after: null };
    return { before: beforeLines.join("\n"), after: afterLines.length ? afterLines.join("\n") : null };
  }

  function trimOuterNewlines(str) {
    if (!str) return "";
    return str.replace(/^\s*\n/, "").replace(/\n\s*$/, "");
  }

  function upgradeOne(codeEl, isStatic) {
    const pre = codeEl.closest("pre");
    if (!pre) return;
    const holder = pre.parentElement && pre.parentElement.classList.contains("highlight")
      ? pre.parentElement
      : pre;

    const raw = codeEl.textContent || "";
    const lang = extractLanguage(codeEl);
    let before = raw;
    let after = null;

    if (!isStatic) {
      const parts = splitBeforeAfter(raw);
      before = parts.before;
      after = parts.after;
    } else {
      before = stripMarkerLines(before);
    }

    // In diff mode, we still want to ensure marker-only lines are never shown.
    before = stripMarkerLines(before);
    if (after != null) after = stripMarkerLines(after);

    const wrapper = document.createElement("div");
    wrapper.className = "code-diff-player";
    wrapper.dataset.language = lang;

    const preBefore = document.createElement("pre");
    preBefore.dataset.state = "before";
    const codeBefore = document.createElement("code");
    codeBefore.textContent = trimOuterNewlines(before);
    preBefore.appendChild(codeBefore);
    wrapper.appendChild(preBefore);

    if (!isStatic && after != null) {
      const preAfter = document.createElement("pre");
      preAfter.dataset.state = "after";
      const codeAfter = document.createElement("code");
      codeAfter.textContent = trimOuterNewlines(after);
      preAfter.appendChild(codeAfter);
      wrapper.appendChild(preAfter);
    }

    holder.parentElement.insertBefore(wrapper, holder);
    holder.remove();
  }

  function upgradeFences() {
    const codes = document.querySelectorAll("pre > code, div.highlight > pre > code");
    codes.forEach((code) => {
      if (code.dataset.diffUpgraded) return;
      const kind = classify(code);
      if (!kind) return;
      code.dataset.diffUpgraded = "true";
      upgradeOne(code, kind === "static");
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", upgradeFences, { once: true });
  } else {
    upgradeFences();
  }
})();
