// The Brief editor extension: highlighting, lexical diagnostics, a context badge and completion for
// `#brief` regions embedded in Kotlin, plus a standalone "brief" Monaco language so a loose .brief
// file highlights too.
//
// Brief is a weighted constraint notation that lowers to `afield.stdlib.ceres.ConstraintScope` — see
// `dev_docs/brief_notation.md` and `src/jvmMain/kotlin/afield/brief/Brief.kt`. This file is the single
// source of truth for the browser side; `BriefEditorExtension.javascript()` reads it verbatim off the
// classpath so the node probe in `BriefEditorExtensionWorkout` exercises exactly the bytes that ship.
//
// The vocabulary below is duplicated from `BriefVerbs` in Brief.kt, which is the unavoidable price of
// the JVM/browser split for a Route A language. `BriefEditorExtensionTest` reads this file from Kotlin
// and asserts the two agree in both directions, so the duplication cannot rot silently.
//
// Diagnostics here are deliberately *lexical only*: an unbalanced bracket, an unknown word, a strength
// that is not a strength. Anything that needs to know whether `spine` is a line belongs on the JVM,
// where `compileBrief` already answers it with a located `BriefDiagnostic`. A browser-side extension
// that guesses at resolution produces confident wrong squiggles, which is worse than none.
(function () {

  const installBriefExtension = () => {
  const root = typeof window !== "undefined" ? window : globalThis;
  const extensionId = "brief";
  const extensionLabel = "Brief";
  const triple = '"""';
  const headerRe = /^(?:[ \t]*(?:\r?\n|$))*[ \t]*#brief\b[^\n]*(?:\r?\n|$)/i;

  // --- the vocabulary, mirroring BriefVerbs -------------------------------------------------
  const strengths = new Set(["whisper", "prefer", "insist", "demand"]);
  const declarations = new Set(["point", "line", "plane", "panel"]);
  const directives = new Set(["settle", "robust"]);
  const infixVerbs = new Set([
    "parallel", "perpendicular", "same-vector", "on", "inside", "mirrors", "toward",
    "vanishes-at", "along", "faces", "coincident", "colinear", "at",
    "planar", "level", "same-x", "same-y", "same-z", "same-xy", "same-xz", "same-yz",
  ]);
  const prefixVerbs = new Set(["fix", "hold", "all"]);
  const functions = new Set(["distance", "angle", "cos-angle", "dihedral", "dot", "raw-dot"]);
  const properties = new Set(["length", "area", "x", "y", "z"]);
  const losses = new Set(["none", "huber", "soft-l1", "cauchy"]);
  const glue = new Set([
    "at", "from", "to", "through", "corners", "across", "offset", "clearance", "in",
    "loose", "above-ground", "none", "deg",
  ]);

  const known = new Set([
    ...strengths, ...declarations, ...directives, ...infixVerbs, ...prefixVerbs,
    ...functions, ...properties, ...losses, ...glue,
  ]);

  const docs = new Map([
    ["whisper", "strength 1e-3 — a tie-breaker; loses to anything"],
    ["prefer", "strength 1e0 — the default, and the scale a length residual is measured on"],
    ["insist", "strength 1e3 — wins a direct fight against prefer by a thousand"],
    ["demand", "strength 1e6 — ConstraintScope.lock's own weight. Still soft."],
    ["point", "point NAME at (x, y[, z]) — a free position"],
    ["line", "line NAME from A to B — a named segment, no constraint of its own"],
    ["plane", "plane NAME through A B C — order fixes the normal, and every offset's sign"],
    ["panel", "panel NAME corners A B C D — around the perimeter; area triangulates on a-c"],
    ["settle", "settle WEIGHT | settle none — the whole-cloud prior that stops an underdetermined brief wandering"],
    ["robust", "robust none | huber S | soft-l1 S | cauchy S — default is none, so weights trade off"],
    ["fix", "fix P… [in AXES] — hold at the declared position. Bare, this is 1e6."],
    ["hold", "hold P… [in AXES] — resist motion from wherever it is now. Bare, 1e-6."],
    ["all", "all loose | all above-ground — the whole cloud"],
    ["parallel", "L1 parallel L2 — either sense; antiparallel satisfies it"],
    ["perpendicular", "L1 perpendicular L2 — says nothing about either length"],
    ["same-vector", "L1 same-vector L2 — same length, direction and sense"],
    ["planar", "P… planar, or PANEL planar — only a constraint past three points"],
    ["colinear", "P Q R… colinear — the first two name the line"],
    ["level", "P… level — a shared height; an alias for same-z"],
    ["on", "P on PLANE [offset D] — the offset's sign follows the plane's point order"],
    ["inside", "P inside PLANE [clearance D] — a barrier; free once satisfied"],
    ["mirrors", "P mirrors Q across PLANE"],
    ["toward", "L toward P — no ray: behind counts as well as in front"],
    ["vanishes-at", "L vanishes-at P — toward, named for the perspective case"],
    ["along", "L along (x, y, z) — a world direction; reversed satisfies it too"],
    ["faces", "PLANE faces (x, y, z) [cos] — signed, unlike along"],
    ["coincident", "P coincident Q — a soft weld, not an identity"],
    ["at", "P at F along (U, V) — F of the way from U to V; two-sided and weighted"],
    ["distance", "distance(A, B) — between two points, no line needed"],
    ["angle", "angle(L1, L2) = 30deg — a cosine underneath, so unsigned"],
    ["cos-angle", "cos-angle(L1, L2) = 0.5 — the cosine directly"],
    ["dihedral", "dihedral(EDGE, A, B) = -1 — and -1 is flat, +1 is folded shut"],
    ["dot", "dot(L1, L2) = 0.5 — normalised, so the target is a cosine"],
    ["raw-dot", "raw-dot(L1, L2) = 6 — unnormalised, in length squared"],
    ["length", "LINE.length — in length units, the scale every other weight is judged against"],
    ["area", "PANEL.area — off every length residual by a factor of length squared"],
  ]);

  const monacoApi = () => root.globalMonaco || root.monaco;

  const rangeFromOffsets = (monaco, model, start, end) => {
    const s = model.getPositionAt(start);
    const e = model.getPositionAt(Math.max(start, end));
    return new monaco.Range(s.lineNumber, s.column, e.lineNumber, e.column);
  };

  // Every `#brief` region inside a kotlin """...""" string. Same shape as rectsl.js on purpose:
  // one embedded-region finder per Route A language, and they should be recognisably the same.
  const findEmbeddedRegions = (model) => {
    if (!model || (model.getLanguageId && model.getLanguageId()) !== "kotlin") return [];
    const source = model.getValue();
    const regions = [];
    let cursor = 0;
    while (cursor < source.length) {
      const open = source.indexOf(triple, cursor);
      if (open < 0) break;
      const contentStart = open + triple.length;
      const close = source.indexOf(triple, contentStart);
      if (close < 0) break;
      const content = source.slice(contentStart, close);
      const header = headerRe.exec(content);
      if (header) {
        const headerStart = contentStart + header[0].search(/#brief\b/i);
        regions.push({
          source: content.slice(header[0].length),
          bodyStart: contentStart + header[0].length,
          bodyEnd: close,
          headerStart,
          headerEnd: headerStart + "#brief".length,
          embedded: true,
        });
      }
      cursor = close + triple.length;
    }
    return regions;
  };

  const regionScopes = (model) => {
    if (!model) return [];
    if (model.getLanguageId && model.getLanguageId() === extensionId) {
      const value = model.getValue();
      return [{ source: value, bodyStart: 0, bodyEnd: value.length, embedded: false }];
    }
    return findEmbeddedRegions(model);
  };

  const isWordStart = (ch) => /[A-Za-z_]/.test(ch);
  const isWordPart = (ch) => /[A-Za-z0-9_-]/.test(ch);

  // Region-local offsets in, absolute offsets out when baseOffset is given.
  const tokenize = (source, baseOffset = 0) => {
    const tokens = [];
    const push = (kind, start, end) => tokens.push({
      kind, start: baseOffset + start, end: baseOffset + end, text: source.slice(start, end),
      localStart: start, localEnd: end,
    });
    let i = 0;
    while (i < source.length) {
      const ch = source[i];
      if (ch === "#") {
        const start = i;
        while (i < source.length && source[i] !== "\n") i++;
        push("comment", start, i);
        continue;
      }
      if (/\s/.test(ch)) { i++; continue; }
      if (/[0-9]/.test(ch) || (ch === "-" && /[0-9]/.test(source[i + 1] || ""))) {
        const start = i;
        if (source[i] === "-") i++;
        while (i < source.length && /[0-9.]/.test(source[i])) i++;
        if (/[eE]/.test(source[i] || "") && /[0-9+-]/.test(source[i + 1] || "")) {
          i += 2;
          while (i < source.length && /[0-9]/.test(source[i])) i++;
        }
        if (source.slice(i, i + 3) === "deg") i += 3;
        push("number", start, i);
        continue;
      }
      if (isWordStart(ch)) {
        const start = i;
        while (i < source.length && isWordPart(source[i])) i++;
        while (i > start + 1 && source[i - 1] === "-") i--;
        push("word", start, i);
        continue;
      }
      const two = source.slice(i, i + 2);
      if (two === ">=" || two === "<=") { push("operator", i, i + 2); i += 2; continue; }
      if ("=".includes(ch)) { push("operator", i, i + 1); i++; continue; }
      if ("()".includes(ch)) { push("bracket", i, i + 1); i++; continue; }
      if (",./-".includes(ch)) { push("punct", i, i + 1); i++; continue; }
      push("unknown", i, i + 1);
      i++;
    }
    return tokens;
  };

  const classForToken = (token, previous) => {
    if (token.kind === "comment") return "afield-stacksl-token-comment";
    if (token.kind === "number") return "afield-stacksl-token-number";
    if (token.kind === "operator") return "afield-stacksl-token-operator";
    if (token.kind === "bracket" || token.kind === "punct") return "afield-stacksl-token-control";
    const word = String(token.text || "").toLowerCase();
    if (strengths.has(word)) return "afield-stacksl-token-directive";
    if (declarations.has(word) || directives.has(word)) return "afield-stacksl-token-keyword";
    if (prefixVerbs.has(word) || infixVerbs.has(word)) return "afield-stacksl-token-creative";
    if (functions.has(word)) return "afield-stacksl-token-operator";
    if (properties.has(word)) return "afield-stacksl-token-constant";
    if (glue.has(word) || losses.has(word)) return "afield-stacksl-token-modifier";
    // The name immediately after a declaration keyword is the thing being named.
    if (previous && declarations.has(String(previous.text || "").toLowerCase())) {
      return "afield-stacksl-token-definition";
    }
    return "afield-stacksl-token-identifier";
  };

  // Lexical only, and each of these mirrors a real refusal on the JVM side. Nothing here needs to
  // know what any name refers to, which is the line between this file and `compileBrief`.
  const analyzeLexical = (source) => {
    const issues = [];
    const opens = [];
    const tokens = tokenize(source);
    tokens.forEach((token) => {
      if (token.kind === "bracket") {
        if (token.text === "(") opens.push(token);
        else if (opens.length > 0) opens.pop();
        else issues.push({ start: token.localStart, end: token.localEnd, severity: "warning", message: "Brief ')' has no matching '('." });
      } else if (token.kind === "unknown") {
        issues.push({
          start: token.localStart, end: token.localEnd, severity: "error",
          message: "'" + token.text + "' means nothing in a brief.",
        });
      }
    });
    opens.forEach((token) => issues.push({
      start: token.localStart, end: token.localEnd, severity: "warning",
      message: "Brief '(' is not closed.",
    }));
    // A statement whose first word is a declaration keyword must be followed by a name, and a
    // statement whose first word is a *property* or a glue word cannot start a statement at all.
    source.split(/\n/).forEach((line, index) => {
      const offset = source.split(/\n/).slice(0, index).reduce((n, l) => n + l.length + 1, 0);
      const body = line.replace(/#.*$/, "");
      const first = (body.match(/[A-Za-z_][A-Za-z0-9_-]*/) || [null])[0];
      if (!first) return;
      const word = first.toLowerCase();
      if (glue.has(word) && !infixVerbs.has(word) && !prefixVerbs.has(word) && !losses.has(word)) {
        const at = offset + body.indexOf(first);
        issues.push({
          start: at, end: at + first.length, severity: "warning",
          message: "'" + first + "' cannot begin a brief statement.",
        });
      }
    });
    return issues;
  };

  const ensureCollection = (editor, key) => {
    if (!editor) return null;
    if (!editor[key]) {
      editor[key] = editor.createDecorationsCollection ? editor.createDecorationsCollection([]) : { set: () => {} };
    }
    return editor[key];
  };

  const setCollection = (editor, key, decorations) => {
    const collection = ensureCollection(editor, key);
    if (collection && typeof collection.set === "function") collection.set(decorations);
  };

  const updateEmbeddedHighlight = (editor) => {
    const monaco = monacoApi();
    const model = editor && editor.getModel && editor.getModel();
    if (!monaco || !model) return;
    const decorations = [];
    for (const region of regionScopes(model)) {
      if (region.embedded) {
        decorations.push({
          range: rangeFromOffsets(monaco, model, region.headerStart, region.headerEnd),
          options: { inlineClassName: "afield-stacksl-token-directive", hoverMessage: { value: "Embedded Brief" } },
        });
      }
      let previous = null;
      for (const token of tokenize(region.source, region.bodyStart)) {
        decorations.push({
          range: rangeFromOffsets(monaco, model, token.start, token.end),
          options: { inlineClassName: classForToken(token, previous) },
        });
        if (token.kind !== "comment") previous = token;
      }
    }
    setCollection(editor, "_briefHighlightDecorations", decorations);
  };

  // The strength of each statement, in the margin. This is the one decoration that is *about* the
  // notation's central idea rather than about its syntax: a brief's behaviour is decided by the
  // weights, and a weight written three lines up is easy to lose track of.
  const updateFlowLens = (editor) => {
    const monaco = monacoApi();
    const model = editor && editor.getModel && editor.getModel();
    if (!monaco || !model) {
      setCollection(editor, "_briefFlowDecorations", []);
      return;
    }
    const decorations = [];
    for (const region of regionScopes(model)) {
      let cursor = region.bodyStart;
      region.source.split(/\n/).forEach((line) => {
        const body = line.replace(/#.*$/, "").trim();
        const first = (body.match(/^[A-Za-z_][A-Za-z0-9_-]*|^-?[0-9][0-9.eE+-]*/) || [null])[0];
        if (first) {
          const word = first.toLowerCase();
          let weight = null;
          if (strengths.has(word)) weight = null;
          else if (/^-?[0-9]/.test(first)) weight = null;
          else if (prefixVerbs.has(word) && word === "fix") weight = "implied demand (1e6)";
          else if (prefixVerbs.has(word) && word === "hold") weight = "implied 1e-6";
          else if (!declarations.has(word) && !directives.has(word)) weight = "implied prefer (1e0)";
          if (weight) {
            const start = cursor + line.length;
            decorations.push({
              range: rangeFromOffsets(monaco, model, start, start),
              options: { after: { content: "  " + weight, inlineClassName: "afield-stacksl-token-comment" } },
            });
          }
        }
        cursor += line.length + 1;
      });
    }
    setCollection(editor, "_briefFlowDecorations", decorations);
  };

  const updateDiagnostics = (editor) => {
    const monaco = monacoApi();
    const model = editor && editor.getModel && editor.getModel();
    if (!monaco || !model || !monaco.editor || typeof monaco.editor.setModelMarkers !== "function") return;
    const markers = [];
    for (const region of regionScopes(model)) {
      for (const issue of analyzeLexical(region.source)) {
        const range = rangeFromOffsets(monaco, model, region.bodyStart + issue.start, region.bodyStart + issue.end);
        markers.push({
          severity: issue.severity === "error" ? monaco.MarkerSeverity.Error : monaco.MarkerSeverity.Warning,
          message: issue.message,
          startLineNumber: range.startLineNumber,
          startColumn: range.startColumn,
          endLineNumber: range.endLineNumber,
          endColumn: range.endColumn,
        });
      }
    }
    monaco.editor.setModelMarkers(model, "brief", markers);
  };

  const buildContextPayload = (editor) => {
    const model = editor && editor.getModel && editor.getModel();
    if (!model) return null;
    const position = editor.getPosition && editor.getPosition();
    if (!position) return null;
    const offset = model.getOffsetAt(position);
    for (const region of regionScopes(model)) {
      if (offset < region.bodyStart || offset > region.bodyEnd) continue;
      const statements = region.source.split(/\n/).filter((line) => {
        const body = line.replace(/#.*$/, "").trim();
        if (!body) return false;
        const first = (body.match(/^[A-Za-z_][A-Za-z0-9_-]*/) || [null])[0];
        return !first || (!declarations.has(first.toLowerCase()) && !directives.has(first.toLowerCase()));
      }).length;
      const token = tokenize(region.source, region.bodyStart)
        .find((t) => t.start <= offset && offset <= t.end);
      const word = token && token.kind === "word" ? token.text.toLowerCase() : null;
      return {
        label: extensionLabel,
        detail: statements + " constraint" + (statements === 1 ? "" : "s"),
        documentation: (word && docs.get(word)) || "A weighted constraint notation; lowers to ConstraintScope.",
      };
    }
    return null;
  };

  const provideCompletionItems = (editor, model, position) => {
    if (!model || !position) return [];
    const offset = model.getOffsetAt(position);
    const inside = regionScopes(model).some((r) => offset >= r.bodyStart && offset <= r.bodyEnd);
    if (!inside) return [];
    const items = [];
    const add = (label, category) => items.push({
      label,
      insertText: label,
      kind: "keyword",
      category,
      detail: docs.get(label) || "",
      documentation: docs.get(label) || "",
      language: extensionId,
    });
    strengths.forEach((w) => add(w, "strength"));
    declarations.forEach((w) => add(w, "declaration"));
    directives.forEach((w) => add(w, "directive"));
    prefixVerbs.forEach((w) => add(w, "relation"));
    infixVerbs.forEach((w) => add(w, "relation"));
    functions.forEach((w) => add(w, "function"));
    properties.forEach((w) => add(w, "property"));
    return items;
  };

  const extension = {
    id: extensionId,
    label: extensionLabel,
    languageIds: ["kotlin", extensionId],
    findEmbeddedRegions,
    updateEmbeddedHighlight,
    updateFlowLens,
    updateDiagnostics,
    buildContextPayload,
    provideCompletionItems,
  };

  if (root.FieldLanguageExtensions && typeof root.FieldLanguageExtensions.register === "function") {
    root.FieldLanguageExtensions.register(extension);
  } else {
    root.__fieldLanguageExtensionQueue = root.__fieldLanguageExtensionQueue || [];
    root.__fieldLanguageExtensionQueue.push(extension);
  }
  // The handle the node probe reads. Everything a headless check needs has to be reachable from
  // here, because there is no editor in a node process to ask.
  root.__briefExtensionProof = {
    registered: true, extension, findEmbeddedRegions, tokenize, analyzeLexical, classForToken,
    vocabulary: {
      strengths: [...strengths], declarations: [...declarations], directives: [...directives],
      infix: [...infixVerbs], prefix: [...prefixVerbs], functions: [...functions],
      properties: [...properties], losses: [...losses], glue: [...glue],
    },
  };
  };

  // A standalone "brief" Monaco language so a loose .brief file gets #-comment toggling and bracket
  // matching. No Monarch grammar, deliberately, and for the reason rectsl.js gives: the decoration
  // highlighter above already treats a whole brief model as one scope, so a tokenizer here would be a
  // second one to keep in step — and a bad Monarch rule throws inside the AMD factory and wedges
  // initMonaco with no console error at all.
  const registerBriefLanguage = (monaco) => {
    if (!monaco || !monaco.languages || typeof monaco.languages.register !== "function") return;
    const languageId = "brief";
    const alreadyRegistered = typeof monaco.languages.getLanguages === "function" &&
      monaco.languages.getLanguages().some((language) => language && language.id === languageId);
    if (!alreadyRegistered) {
      monaco.languages.register({ id: languageId, extensions: [".brief"], aliases: ["Brief", "brief"] });
    }
    monaco.languages.setLanguageConfiguration(languageId, {
      comments: { lineComment: "#" },
      brackets: [["(", ")"]],
      autoClosingPairs: [{ open: "(", close: ")" }],
      surroundingPairs: [{ open: "(", close: ")" }],
    });
  };

  if (typeof define === "function" && define.amd) {
    define("bithero/brief", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
      const monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
      registerBriefLanguage(monaco);
      installBriefExtension();
      return {};
    });
  } else {
    installBriefExtension();
  }
})();
