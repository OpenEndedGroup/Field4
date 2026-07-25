(function() {
  const HIGHLIGHT_URL = "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.8.0/highlight.min.js";
  let highlightPromise = null;
  let forthRegistered = false;

  function registerForthLanguage() {
    if (!window.hljs || forthRegistered || hljs.getLanguage("forth")) {
      forthRegistered = true;
      return;
    }

    hljs.registerLanguage("forth", function(hljs) {
      const BUILTINS = [
        "!", "+!", ",", ".", ".r", "*", "*/", "*/mod", "+", "1+", "1-", "-", "/", "/mod", "2!",
        "2*", "2/", "2@", "2drop", "2dup", "2over", "2swap", "<", "<>", "=", ">", ">body",
        ">in", ">number", ">r", "?", "?do", "?dup", "@", "abs", "accept", "again", "align",
        "aligned", "allot", "and", "base", "begin", "bl", "blank", "c!", "c,", "c@", "case",
        "catch", "cell+", "cells", "char", "chars", "constant", "count", "cr", "create", "depth",
        "do", "does>", "drop", "dup", "else", "emit", "endcase", "endof", "evaluate", "execute",
        "exit", "false", "fill", "find", "fm/mod", "forget", "here", "hold", "i", "if", "immediate",
        "invert", "j", "leave", "literal", "loop", "lshift", "max", "min", "mod", "move", "negate",
        "nip", "of", "or", "over", "pick", "postpone", "r>", "r@", "recurse", "repeat", "roll",
        "rot", "rshift", "s>d", "see", "sm/rem", "source", "space", "spaces", "state", "swap",
        "then", "throw", "true", "tuck", "type", "u.", "u<", "u.r", "um*", "um/mod", "until",
        "variable", "while", "within", "word", "words", "xor"
      ];
      const FIELD_WORDS = [
        "@?", "@?!", "bar", "class-for", "cubicto", "cubicto3", "display", "display-channel",
        "fline", "fline-close", "fline-color", "fline-filled", "fline-lineto-v2",
        "fline-lineto-v3", "fline-moveto-v2", "fline-moveto-v3", "fline-node-color",
        "fline-stroked", "fline-thickness", "frame", "html", "lineto", "lineto3", "moveto",
        "moveto3", "newInstance", "plot-stack", "println", "stack-display", "str", "str+",
        "vec2", "vec2*", "vec2+", "vec2-", "vec2@y", "vec2@x", "vec2dot", "vec2len",
        "vec2norm", "vec3", "vec4", "watch"
      ];
      const WORD_BOUNDARY = "(?=\\s|$)";

      return {
        name: "Forth",
        aliases: ["forth", "fth", "fs"],
        case_insensitive: true,
        keywords: {
          keyword: "if else then begin while repeat until again do ?do loop +loop leave case of endof endcase recurse exit",
          built_in: BUILTINS.join(" ") + " " + FIELD_WORDS.join(" "),
          literal: "true false"
        },
        contains: [
          hljs.COMMENT("\\\\", "$"),
          hljs.COMMENT("\\(", "\\)"),
          {
            scope: "string",
            begin: "\\bs\\\"",
            end: "\\\"",
            relevance: 0
          },
          {
            scope: "string",
            begin: "\\.\\\"",
            end: "\\\"",
            relevance: 0
          },
          {
            scope: "title",
            begin: "(:|variable|constant|create|value)\\s+",
            end: WORD_BOUNDARY,
            excludeBegin: true
          },
          {
            scope: "number",
            begin: "[-+]?\\b(?:0x[0-9a-f]+|\\d+(?:\\.\\d+)?(?:e[-+]?\\d+)?)\\b",
            relevance: 0
          }
        ]
      };
    });
    forthRegistered = true;
  }

  function ensureHighlight() {
    if (window.hljs) {
      registerForthLanguage();
      return Promise.resolve();
    }
    if (highlightPromise) return highlightPromise;
    highlightPromise = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = HIGHLIGHT_URL;
      script.crossOrigin = "anonymous";
      script.referrerPolicy = "no-referrer";
      script.onload = () => {
        registerForthLanguage();
        resolve();
      };
      script.onerror = (err) => reject(err);
      document.head.appendChild(script);
    }).catch((err) => {
      console.warn("highlight.js failed to load", err);
    });
    return highlightPromise;
  }

  function normalizeBlock(text) {
    if (!text) return "";
    const lines = text.replace(/\r\n?/g, "\n").split("\n");
    while (lines.length && lines[0].trim() === "") lines.shift();
    while (lines.length && lines[lines.length - 1].trim() === "") lines.pop();
    let indent = null;
    for (const line of lines) {
      if (!line.trim()) continue;
      const match = line.match(/^\s*/);
      const spaces = match ? match[0].length : 0;
      indent = indent === null ? spaces : Math.min(indent, spaces);
    }
    if (indent && indent > 0) {
      return lines.map((l) => l.slice(indent)).join("\n");
    }
    return lines.join("\n");
  }

  function toLines(text) {
    if (!text) return [];
    const arr = text.split("\n");
    if (arr.length && arr[0] === "") arr.shift();
    if (arr.length && arr[arr.length - 1] === "") arr.pop();
    return arr;
  }

  // Parse `:::fold [label]` / `:::endfold` region markers out of a static block. The marker
  // lines are removed (they never render or execute); the enclosed lines stay in the code and
  // are recorded as a collapsible region [start, end) in the CLEANED line indexing.
  function parseFoldMarkers(code) {
    const beginRe = /^\s*:::fold(?:\s+(.*\S))?\s*$/;
    const endRe = /^\s*:::endfold\s*$/;
    const lines = toLines(code);
    const out = [];
    const regions = [];
    let open = null;
    let id = 0;
    for (const line of lines) {
      const begin = line.match(beginRe);
      if (begin) {
        open = { start: out.length, label: (begin[1] || "").trim() };
        continue;
      }
      if (endRe.test(line)) {
        if (open) {
          regions.push({ id: id++, start: open.start, end: out.length, label: open.label, expanded: false });
          open = null;
        }
        continue;
      }
      out.push(line);
    }
    if (open) regions.push({ id: id++, start: open.start, end: out.length, label: open.label, expanded: false });
    return { code: out.join("\n"), regions };
  }

  function commonPrefixLen(aLines, bLines) {
    const len = Math.min(aLines.length, bLines.length);
    let i = 0;
    while (i < len && aLines[i] === bLines[i]) i += 1;
    return i;
  }

  function commonSuffixLen(aLines, bLines, skipPrefix = 0) {
    const lenA = aLines.length;
    const lenB = bLines.length;
    const maxLen = Math.min(lenA, lenB) - skipPrefix;
    let i = 0;
    while (i < maxLen && aLines[lenA - 1 - i] === bLines[lenB - 1 - i]) i += 1;
    return i;
  }

  function diffMyers(a, b) {
    const N = a.length;
    const M = b.length;
    const max = N + M;
    const offset = max;
    const V = new Array(2 * max + 1).fill(-1);
    const trace = [];

    V[offset + 1] = 0;

    for (let d = 0; d <= max; d++) {
      const Vd = V.slice();
      trace.push(Vd);
      for (let k = -d; k <= d; k += 2) {
        const idx = offset + k;
        let x;
        if (k === -d || (k !== d && V[idx - 1] < V[idx + 1])) {
          x = V[idx + 1];
        } else {
          x = V[idx - 1] + 1;
        }
        let y = x - k;
        while (x < N && y < M && a[x] === b[y]) {
          x += 1;
          y += 1;
        }
        V[idx] = x;
        if (x >= N && y >= M) {
          trace[trace.length - 1] = V.slice();
          return backtrack(trace, a, b, offset);
        }
      }
    }
    return [];
  }

  function backtrack(trace, a, b, offset) {
    let x = a.length;
    let y = b.length;
    const ops = [];

    for (let d = trace.length - 1; d >= 0; d--) {
      const V = trace[d];
      const k = x - y;
      const idx = offset + k;
      let prevK;
      if (k === -d || (k !== d && V[idx - 1] < V[idx + 1])) {
        prevK = k + 1;
      } else {
        prevK = k - 1;
      }
      const prevX = V[offset + prevK];
      const prevY = prevX - prevK;

      while (x > prevX && y > prevY) {
        ops.push({ type: "equal", line: a[x - 1] });
        x -= 1;
        y -= 1;
      }

      if (d === 0) break;

      if (x === prevX) {
        ops.push({ type: "insert", line: b[prevY] });
        y -= 1;
      } else {
        ops.push({ type: "delete", line: a[prevX] });
        x -= 1;
      }
    }

    return ops.reverse();
  }

  function diffChars(a, b) {
    const m = a.length;
    const n = b.length;
    const dp = Array.from({ length: m + 1 }, () => Array(n + 1).fill(0));
    for (let i = m - 1; i >= 0; i--) {
      for (let j = n - 1; j >= 0; j--) {
        dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
      }
    }

    const segs = [];
    let i = 0;
    let j = 0;
    while (i < m || j < n) {
      if (i < m && j < n && a[i] === b[j]) {
        segs.push({ type: "eq", text: a[i] });
        i += 1; j += 1;
      } else if (j < n && (i === m || dp[i][j + 1] >= dp[i + 1][j])) {
        segs.push({ type: "ins", text: b[j] });
        j += 1;
      } else {
        segs.push({ type: "del", text: a[i] });
        i += 1;
      }
    }

    const merged = [];
    for (const s of segs) {
      if (merged.length && merged[merged.length - 1].type === s.type) {
        merged[merged.length - 1].text += s.text;
      } else {
        merged.push({ ...s });
      }
    }
    return merged;
  }

  function buildSteps(from, to) {
    const a = toLines(from);
    const b = toLines(to);
    const ses = diffMyers(a, b);

    const stepsOut = [];
    let cursor = 0;
    for (let i = 0; i < ses.length;) {
      const op = ses[i];
      if (op.type === "equal") {
        cursor += 1;
        i += 1;
        continue;
      }

      const idx = cursor;
      const dels = [];
      const ins = [];

      while (i < ses.length && ses[i].type === "delete") {
        dels.push(ses[i].line);
        i += 1;
      }
      while (i < ses.length && ses[i].type === "insert") {
        ins.push(ses[i].line);
        i += 1;
      }

      if (dels.length && ins.length) {
        stepsOut.push({ kind: "replace", index: idx, deletes: dels, inserts: ins });
        cursor += ins.length;
      } else if (dels.length) {
        stepsOut.push({ kind: "delete", index: idx, deletes: dels });
      } else if (ins.length) {
        stepsOut.push({ kind: "insert", index: idx, inserts: ins });
        cursor += ins.length;
      }
    }

    return stepsOut;
  }

  function escapeHtml(str) {
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function highlightLine(line, language) {
    if (!window.hljs) return escapeHtml(line === "" ? "\u00a0" : line);
    registerForthLanguage();
    const source = line === "" ? " " : line;
    let html;
    if (hljs.getLanguage(language)) {
      html = hljs.highlight(source, { language, ignoreIllegals: true }).value;
    } else {
      html = hljs.highlightAuto(source).value;
    }
    return line === "" ? html.replace(" ", "&nbsp;") : html;
  }

  function applyInlineToHighlighted(html, segments) {
    const container = document.createElement("div");
    container.innerHTML = html === "" ? "&nbsp;" : html;
    let segIdx = 0;
    let segOffset = 0;

    function walk(node) {
      if (segIdx >= segments.length) return;
      if (node.nodeType === Node.TEXT_NODE) {
        const text = node.nodeValue || "";
        const parts = [];
        let pos = 0;
        while (pos < text.length && segIdx < segments.length) {
          const seg = segments[segIdx];
          const remainingSeg = seg.text.length - segOffset;
          const remainingText = text.length - pos;
          const take = Math.min(remainingSeg, remainingText);
          const chunk = text.slice(pos, pos + take);

          if (seg.type === "eq") {
            parts.push(document.createTextNode(chunk));
          } else {
            const span = document.createElement("span");
            span.className = seg.type === "ins" ? "ch-ins" : "ch-del";
            span.textContent = chunk;
            parts.push(span);
          }

          pos += take;
          segOffset += take;
          if (segOffset >= seg.text.length) {
            segIdx += 1;
            segOffset = 0;
          }
        }
        if (pos < text.length) {
          parts.push(document.createTextNode(text.slice(pos)));
        }

        const parent = node.parentNode;
        if (parent) {
          for (const part of parts) parent.insertBefore(part, node);
          parent.removeChild(node);
        }
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        const children = Array.from(node.childNodes);
        for (const child of children) walk(child);
      }
    }

    walk(container);
    return container.innerHTML;
  }

  class DiffPlayer {
    constructor(container, options) {
      this.container = container;
      this.language = options.language || "kotlin";
      this.codeStart = options.before || "";
      this.codeEnd = options.after || options.before || "";
      // Pull out :::fold/:::endfold regions before anything else counts lines or diffs.
      const foldStart = parseFoldMarkers(this.codeStart);
      const foldEnd = parseFoldMarkers(this.codeEnd);
      this.codeStart = foldStart.code;
      this.codeEnd = foldEnd.code;
      this.foldRegions = foldStart.regions;
      this.staticMode = Boolean(options.static) || this.codeStart === this.codeEnd;
      this.showFinalFirst = options.showFinalFirst !== false; // default: render final state first
      this.steps = buildSteps(this.codeStart, this.codeEnd);
      this.markers = new Map();
      this.inlineHighlights = new Map();
      const startLines = toLines(this.codeStart);
      const endLines = toLines(this.codeEnd);
      const hasDiff = this.codeStart !== this.codeEnd;
      this.prefixLen = hasDiff ? commonPrefixLen(startLines, endLines) : 0;
      this.suffixLen = hasDiff ? commonSuffixLen(startLines, endLines, this.prefixLen) : 0;
      this.foldTop = this.prefixLen > 2;
      this.foldBottom = this.suffixLen > 2;
      this.stateLines = this.staticMode || !this.showFinalFirst ? [...startLines] : [...endLines];
      this.currentStep = 0;
      this.timer = null;
      this.isPlaying = false;
      this.debug = options.debug || false;
      if (this.showFinalFirst && !this.staticMode && this.steps.length) {
        this.fastForwardToEnd();
      }
      this.buildDom();
      this.render(this.stateLines);
      ensureHighlight().then(() => this.render(this.stateLines));
    }

    buildDom() {
      const wrap = document.createElement("div");
      wrap.className = "code-diff-shell";
      const codeWrap = document.createElement("div");
      codeWrap.className = "code-diff-wrap";

      const actionsBottom = document.createElement("div");
      actionsBottom.className = "code-diff-actions-bottom";
      this.copyBtn = document.createElement("button");
      this.copyBtn.className = "code-diff-copy";
      this.copyBtn.title = "Copy to clipboard";
      this.copyBtn.setAttribute("aria-label", "Copy code");
      actionsBottom.appendChild(this.copyBtn);
      this.playBtn = document.createElement("button");
      this.playBtn.className = "code-diff-btn play-icon";
      this.playBtn.textContent = this.showFinalFirst ? "▶" : "▶";
      this.playBtn.setAttribute("aria-label", "Play diff");
      actionsBottom.appendChild(this.playBtn);

      const pre = document.createElement("pre");
      pre.className = "code-diff-pre";
      const code = document.createElement("code");
      code.className = "hljs";
      pre.appendChild(code);

      codeWrap.appendChild(actionsBottom);
      codeWrap.appendChild(pre);
      wrap.appendChild(codeWrap);
      this.container.appendChild(wrap);

      this.codeEl = code;
      this.preEl = pre;

      this.playBtn.addEventListener("click", () => this.play());
      this.copyBtn.addEventListener("click", () => this.copy());

      if (this.staticMode || this.steps.length === 0) {
        this.playBtn.style.display = "none";
        this.container.classList.add("code-diff-static");
      }
    }

    render(lines, opts = {}) {
      const inserted = new Set(opts.inserted || []);
      const deleting = new Set(opts.deleting || []);
      const closing = new Set(opts.closing || []);
      const inline = new Map(this.inlineHighlights);
      if (opts.inline) {
        for (const [k, v] of opts.inline.entries()) inline.set(k, v);
      }
      const markers = new Map(this.markers);
      if (opts.markers) {
        for (const [k, v] of opts.markers.entries()) markers.set(k, v);
      }
      const hasAnyMarker = markers.size > 0;
      const useHl = Boolean(window.hljs);
      const highlightedLines = useHl ? lines.map((line) => highlightLine(line, this.language)) : null;

      const startIdx = this.foldTop ? this.prefixLen : 0;
      const endIdx = this.foldBottom ? lines.length - this.suffixLen : lines.length;

      const renderLine = (idx) => {
        const line = lines[idx];
        const segments = inline.get(idx);
        let content;

        if (segments && segments.length) {
          const base = highlightedLines
            ? highlightedLines[idx]
            : escapeHtml(line === "" ? "\u00a0" : line);
          content = applyInlineToHighlighted(base, segments);
        } else if (highlightedLines) {
          const safe = highlightedLines[idx];
          content = safe === "" ? "&nbsp;" : safe;
        } else {
          content = escapeHtml(line === "" ? "\u00a0" : line);
        }

        let cls = "code-diff-line";
        if (closing.has(idx)) cls += " closing";
        else if (deleting.has(idx)) cls += " deleting";
        else if (inserted.has(idx)) cls += " inserted";
        const marker = markers.get(idx) || "";
        const markerHtml = marker ? `<span class="marker ${marker}"></span>` : "";
        cls += marker ? " with-marker" : " no-marker";
        return `<div class="${cls}"><span class="line-no">${idx + 1}</span>${markerHtml}<span class="code-text">${content}</span></div>`;
      };

      // Explicit :::fold regions still collapsed, clipped to the visible [startIdx,endIdx) window.
      const regions = (this.foldRegions || [])
        .filter((r) => !r.expanded)
        .map((r) => ({ id: r.id, start: Math.max(r.start, startIdx), end: Math.min(r.end, endIdx), label: r.label }))
        .filter((r) => r.end > r.start)
        .sort((a, b) => a.start - b.start);

      const visible = [];
      let idx = startIdx;
      let ri = 0;
      while (idx < endIdx) {
        while (ri < regions.length && regions[ri].start < idx) ri += 1;
        if (ri < regions.length && idx === regions[ri].start) {
          const r = regions[ri];
          const count = r.end - r.start;
          const plural = count === 1 ? "" : "s";
          const labelHtml = r.label
            ? `${escapeHtml(r.label)} <span class="fold-count">(${count} line${plural})</span>`
            : `\u2026 ${count} line${plural}`;
          visible.push(`<div class="code-diff-fold fold-region" data-fold-region="${r.id}"><span class="fold-icon">+</span> ${labelHtml}</div>`);
          idx = r.end;
          ri += 1;
          continue;
        }
        visible.push(renderLine(idx));
        idx += 1;
      }

      const wrapped = visible.join("");

      let finalHtml = wrapped;

      if (this.foldTop && startIdx > 0) {
        const count = startIdx;
        const fold = `<div class="code-diff-fold fold-top" data-fold="top"><span class="fold-icon">+</span> … ${count} line${count === 1 ? "" : "s"}</div>`;
        finalHtml = fold + finalHtml;
      }

      if (this.foldBottom && endIdx < lines.length) {
        const count = lines.length - endIdx;
        const fold = `<div class="code-diff-fold fold-bottom" data-fold="bottom"><span class="fold-icon">+</span> … ${count} line${count === 1 ? "" : "s"}</div>`;
        finalHtml = finalHtml + fold;
      }

      this.codeEl.className = `hljs language-${this.language}`;
      this.codeEl.innerHTML = finalHtml;
      this.preEl.classList.toggle("has-markers", hasAnyMarker);

      // Attach fold handlers
      const folds = this.codeEl.parentElement.querySelectorAll(".code-diff-fold");
      folds.forEach((el) => {
        el.addEventListener("click", () => {
          const dir = el.dataset.fold;
          if (dir === "top") this.foldTop = false;
          if (dir === "bottom") this.foldBottom = false;
          const regionId = el.dataset.foldRegion;
          if (regionId !== undefined && this.foldRegions) {
            const region = this.foldRegions.find((r) => String(r.id) === regionId);
            if (region) region.expanded = true;
          }
          this.render(this.stateLines);
        });
      });
    }

    copy() {
      const text = this.stateLines.join("\n");
      const done = () => this.showCopyFeedback();
      const fail = () => this.copyFallback(text, done);
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(text).then(done).catch(fail);
      } else {
        fail();
      }
    }

    copyFallback(text, cb) {
      const ta = document.createElement("textarea");
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      document.body.removeChild(ta);
      if (cb) cb();
    }

    showCopyFeedback() {
      if (!this.copyBtn) return;
      this.copyBtn.classList.add("copied");
      clearTimeout(this.copyTimer);
      this.copyTimer = setTimeout(() => {
        this.copyBtn.classList.remove("copied");
      }, 900);
    }

    flash(kind) {
      this.preEl.classList.remove("flash-insert", "flash-delete");
      if (kind) this.preEl.classList.add(kind === "insert" ? "flash-insert" : "flash-delete");
      setTimeout(() => this.preEl.classList.remove("flash-insert", "flash-delete"), 280);
    }

    reset() {
      clearTimeout(this.timer);
      this.stateLines = this.staticMode || !this.showFinalFirst ? toLines(this.codeStart) : toLines(this.codeEnd);
      this.markers.clear();
      this.inlineHighlights.clear();
      this.foldTop = this.prefixLen > 2;
      this.foldBottom = this.suffixLen > 2;
      if (this.foldRegions) this.foldRegions.forEach((r) => { r.expanded = false; });
      if (this.showFinalFirst && !this.staticMode && this.steps.length) {
        this.fastForwardToEnd();
      }
      this.render(this.stateLines);
      this.isPlaying = false;
      this.playBtn.disabled = false;
      this.playBtn.textContent = "▶";
      this.currentStep = 0;
    }

    play() {
      if (this.isPlaying) return;
      if (this.currentStep >= this.steps.length) this.reset();
      if (this.showFinalFirst && !this.staticMode) {
        // jump back to start before animating, and pause one frame so the user sees the untouched text
        clearTimeout(this.timer);
        this.stateLines = toLines(this.codeStart);
        this.markers.clear();
        this.render(this.stateLines);
        this.currentStep = 0;
        this.isPlaying = true;
        this.playBtn.disabled = true;
        this.timer = setTimeout(() => this.runStep(this.currentStep), 420);
        return;
      }
      this.isPlaying = true;
      this.playBtn.disabled = true;
      this.runStep(this.currentStep);
    }

    runStep(idx) {
      if (this.staticMode || this.steps.length === 0) {
        this.render(this.stateLines);
        this.playBtn.disabled = false;
        return;
      }
      if (idx >= this.steps.length) {
        this.stateLines = toLines(this.codeEnd);
        this.render(this.stateLines);
        this.flash("insert");
        this.isPlaying = false;
        this.playBtn.disabled = false;
        this.playBtn.textContent = "▶";
        return;
      }

      const step = this.steps[idx];

      const finish = (delay) => {
        this.currentStep = idx + 1;
        this.timer = setTimeout(() => this.runStep(this.currentStep), delay);
      };

      if (step.kind === "delete") {
        const delIdx = Array.from({ length: step.deletes.length }, (_, k) => step.index + k);
        const markers = new Map(delIdx.map((i) => [i, "marker-delete"]));
        delIdx.forEach((i) => this.markers.set(i, "marker-delete"));
        this.render(this.stateLines, { deleting: delIdx, markers });
        this.flash("delete");
        setTimeout(() => {
          this.render(this.stateLines, { closing: delIdx, markers });
          setTimeout(() => {
            this.stateLines.splice(step.index, step.deletes.length);
            delIdx.forEach((i) => this.markers.delete(i));
            this.shiftMarkers(step.index, step.deletes.length, 0);
            delIdx.forEach((i) => this.inlineHighlights.delete(i));
            this.shiftInlineHighlights(step.index, step.deletes.length, 0);
            this.render(this.stateLines);
            finish(360);
          }, 240);
        }, 420);
        return;
      }

      if (step.kind === "replace") {
        const inline = new Map();
        const overlap = Math.min(step.deletes.length, step.inserts.length);
        for (let k = 0; k < overlap; k++) {
          const oldLine = step.deletes[k];
          const newLine = step.inserts[k];
          inline.set(step.index + k, diffChars(oldLine, newLine));
        }

        for (let k = 0; k < step.deletes.length; k++) this.markers.delete(step.index + k);

        this.stateLines.splice(step.index, step.deletes.length, ...step.inserts);

        const insertedLines = [];
        for (let k = overlap; k < step.inserts.length; k++) {
          insertedLines.push(step.index + k);
        }

        this.shiftMarkers(step.index, step.deletes.length, step.inserts.length);

        for (let k = 0; k < step.deletes.length; k++) this.inlineHighlights.delete(step.index + k);
        this.shiftInlineHighlights(step.index, step.deletes.length, step.inserts.length);
        for (let k = 0; k < overlap; k++) this.inlineHighlights.set(step.index + k, inline.get(step.index + k));

        for (let k = 0; k < overlap; k++) this.markers.set(step.index + k, "marker-replace");
        for (const idxIns of insertedLines) this.markers.set(idxIns, "marker-insert");

        this.render(this.stateLines, { inserted: insertedLines, inline });
        this.flash("insert");
        finish(520);
        return;
      }

      if (step.kind === "insert") {
        this.stateLines.splice(step.index, 0, ...step.inserts);
        const anim = step.inserts.map((_, k) => step.index + k);
        this.shiftMarkers(step.index, 0, step.inserts.length);
        this.shiftInlineHighlights(step.index, 0, step.inserts.length);
        anim.forEach((i) => this.markers.set(i, "marker-insert"));
        this.render(this.stateLines, { inserted: anim });
        this.flash("insert");
        finish(480);
      }
    }

    shiftMarkers(start, removed, added) {
      if (added === removed) return;
      const delta = added - removed;
      const entries = Array.from(this.markers.entries()).sort((a, b) => a[0] - b[0]);
      this.markers.clear();
      for (const [idx, val] of entries) {
        if (idx < start) {
          this.markers.set(idx, val);
        } else if (idx >= start + removed) {
          this.markers.set(idx + delta, val);
        }
      }
    }

    shiftInlineHighlights(start, removed, added) {
      if (added === removed) return;
      const delta = added - removed;
      const entries = Array.from(this.inlineHighlights.entries()).sort((a, b) => a[0] - b[0]);
      this.inlineHighlights.clear();
      for (const [idx, val] of entries) {
        if (idx < start) {
          this.inlineHighlights.set(idx, val);
        } else if (idx >= start + removed) {
          this.inlineHighlights.set(idx + delta, val);
        }
      }
    }

    fastForwardToEnd() {
      this.stateLines = toLines(this.codeStart);
      this.markers.clear();
      this.inlineHighlights.clear();
      for (const step of this.steps) {
        if (step.kind === "delete") {
          for (let k = 0; k < step.deletes.length; k++) this.markers.delete(step.index + k);
          this.stateLines.splice(step.index, step.deletes.length);
          this.shiftMarkers(step.index, step.deletes.length, 0);
          for (let k = 0; k < step.deletes.length; k++) this.inlineHighlights.delete(step.index + k);
          this.shiftInlineHighlights(step.index, step.deletes.length, 0);
          continue;
        }

        if (step.kind === "replace") {
          for (let k = 0; k < step.deletes.length; k++) this.markers.delete(step.index + k);
          this.stateLines.splice(step.index, step.deletes.length, ...step.inserts);
          const overlap = Math.min(step.deletes.length, step.inserts.length);
          const insertedLines = [];
          for (let k = overlap; k < step.inserts.length; k++) insertedLines.push(step.index + k);
          this.shiftMarkers(step.index, step.deletes.length, step.inserts.length);
          for (let k = 0; k < step.deletes.length; k++) this.inlineHighlights.delete(step.index + k);
          this.shiftInlineHighlights(step.index, step.deletes.length, step.inserts.length);
          for (let k = 0; k < overlap; k++) {
            this.inlineHighlights.set(step.index + k, diffChars(step.deletes[k], step.inserts[k]));
          }
          for (let k = 0; k < overlap; k++) this.markers.set(step.index + k, "marker-replace");
          for (const idxIns of insertedLines) this.markers.set(idxIns, "marker-insert");
          continue;
        }

        if (step.kind === "insert") {
          this.stateLines.splice(step.index, 0, ...step.inserts);
          this.shiftMarkers(step.index, 0, step.inserts.length);
          this.shiftInlineHighlights(step.index, 0, step.inserts.length);
          step.inserts.forEach((_, k) => this.markers.set(step.index + k, "marker-insert"));
        }
      }
      // keep currentStep at 0 so play starts from the true beginning
    }
  }

  function extractCode(container) {
    const beforeNode = container.querySelector('pre[data-state="before"], pre[data-role="before"]');
    const afterNode = container.querySelector('pre[data-state="after"], pre[data-role="after"]');
    const singleNode = container.querySelector('pre[data-state="single"], pre[data-state="only"], pre[data-role="single"], pre[data-role="only"]');

    if (!beforeNode && !afterNode && !singleNode) return null;

    const lang = container.dataset.language || container.dataset.lang || "kotlin";

    if (singleNode) {
      const text = normalizeBlock(singleNode.textContent || "");
      singleNode.hidden = true;
      return { before: text, after: text, language: lang, static: true };
    }

    if (beforeNode && !afterNode) {
      const text = normalizeBlock(beforeNode.textContent || "");
      beforeNode.hidden = true;
      return { before: text, after: text, language: lang, static: true };
    }

    if (!beforeNode || !afterNode) return null;

    const before = normalizeBlock(beforeNode.textContent || "");
    const after = normalizeBlock(afterNode.textContent || "");
    beforeNode.hidden = true;
    afterNode.hidden = true;
    return { before, after, language: lang };
  }

  function mountAll() {
    const nodes = document.querySelectorAll(".code-diff-player:not([data-code-diff-bound])");
    nodes.forEach((node) => mountOne(node));
  }

  function mountOne(node, opts) {
    if (!node || node.dataset.codeDiffBound === "true") return null;
    const data = extractCode(node);
    if (!data) return null;
    node.dataset.codeDiffBound = "true";
    const player = new DiffPlayer(node, { ...data, ...(opts || {}) });
    return player;
  }

  window.CodeDiffPlayer = {
    mountAll,
    mountOne,
    DiffPlayer,
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => mountAll());
  } else {
    mountAll();
  }
})();
