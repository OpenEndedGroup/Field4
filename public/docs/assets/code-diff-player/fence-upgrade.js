(() => {
  const MARKER_LINE_RE = /^\s*:::(before|after|static)?\s*$/;
  const DIFF_CLASS_RE = /\b(?:code-diff-fence|language-diff)\b/;
  const DIFF_STATIC_CLASS_RE = /\b(?:code-diff-fence-static|language-diffstatic)\b/;

  function surroundingInfo(codeEl) {
    const pre = codeEl.closest("pre");
    const holder = pre && pre.parentElement && pre.parentElement.classList.contains("highlight")
      ? pre.parentElement
      : pre;
    const classes = [
      codeEl.className || "",
      pre ? pre.className || "" : "",
      holder && holder !== pre ? holder.className || "" : "",
    ].join(" ");
    return { pre, holder, classes };
  }

  // A `diffstatic` fence may declare the source line its first line really is, as
  // `{data-lang=kotlin data-start-line=82}`. Superfences puts brace attributes on the <pre>, so look
  // there first, then at the <code> and the highlight wrapper, the same order extractLanguage uses.
  function extractStartLine(codeEl) {
    const { pre, holder } = surroundingInfo(codeEl);
    const candidates = [
      codeEl.dataset.startLine,
      pre ? pre.dataset.startLine : null,
      holder && holder.dataset ? holder.dataset.startLine : null,
    ];
    for (const value of candidates) {
      const n = parseInt(value, 10);
      if (Number.isFinite(n) && n > 0) return n;
    }
    return 1;
  }

  function extractLanguage(codeEl) {
    const { pre, holder, classes } = surroundingInfo(codeEl);
    const cls = classes;
    const tokens = Array.from(cls.matchAll(/language-([\w-]+)/g)).map((m) => m[1]);
    const preferred = tokens.find((t) => t !== "diff" && t !== "diffstatic");
    if (preferred) return preferred;
    if (codeEl.dataset.lang) return codeEl.dataset.lang;
    if (codeEl.dataset.language) return codeEl.dataset.language;
    if (pre && pre.dataset.lang) return pre.dataset.lang;
    if (pre && pre.dataset.language) return pre.dataset.language;
    if (holder && holder.dataset && holder.dataset.lang) return holder.dataset.lang;
    if (holder && holder.dataset && holder.dataset.language) return holder.dataset.language;
    return "kotlin";
  }

  function classify(codeEl) {
    const { classes } = surroundingInfo(codeEl);
    if (DIFF_STATIC_CLASS_RE.test(classes)) return "static";
    if (DIFF_CLASS_RE.test(classes)) return "diff";
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
    const { pre, holder } = surroundingInfo(codeEl);
    if (!pre) return;

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
    const startLine = extractStartLine(codeEl);
    if (startLine !== 1) wrapper.dataset.startLine = String(startLine);

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
