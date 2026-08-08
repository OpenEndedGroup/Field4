// Petri notation editor extension: inline syntax highlighting, lexical diagnostics, hover
// documentation, completions and a context badge for #petri regions embedded in Kotlin
// """...""" strings.
//
// This is the single source of truth for the extension. It is served as a static AMD module
// (loaded via bithero/petri in bootstrapMonaco.js) in the browser, and is also read verbatim
// by afield.petri.PetriEditorExtension for the node-probe smoke test. The wrapper below
// supports both worlds: it registers an AMD module when a Monaco loader is present, and
// otherwise self-installs immediately (as it does under bare eval).
//
// Diagnostics here are LEXICAL ONLY, exactly as in rectsl.js, and that is a deliberate line
// rather than an unfinished job. This file can see that `@1/0` is not a duration and that
// `=> wobble` is not a combinator, because those are properties of the text. It cannot see
// that a transition will never fire or that a place is unreachable, because those are
// properties of the *net* — and the net is built by the Kotlin parser, which reports them at
// run time with the whole graph in front of it. Duplicating half of that here would produce a
// second, worse answer that disagrees with the first.
(function () {
  "use strict";

  const installPetriExtension = () => {
  const root = window;
  const extensionId = "petri";
  const extensionLabel = "Petri";
  const triple = '"""';
  const headerRe = /^(?:[ \t]*(?:\r?\n|$))*[ \t]*#petri\b[^\n]*(?:\r?\n|$)/i;

  // Kept in step with PetriCombinators.names in PetriNotation.kt. The docs coverage guard in
  // PetriGallery.kt checks the cheatsheet against the Kotlin registry, and PetriNotationTest
  // checks this list against it, so the three cannot drift apart silently.
  const combinatorWords = [
    "carry", "black", "swap", "stack", "cat", "fastcat", "rev",
    "palindrome", "degrade", "fast2", "slow2", "sum", "inc", "count",
  ];

  const directiveWords = ["timing", "grid", "conflict", "seed"];
  const timingWords = ["step", "event", "timed"];
  const conflictWords = ["order", "priority", "round-robin", "random"];
  const settingWords = ["delay", "pri", "w"];

  const combinatorDocs = new Map([
    ["carry", "the first consumed colour, unchanged — the default"],
    ["black", "no colour: produce a plain black token"],
    ["swap", "the second consumed colour, falling back to the first"],
    ["stack", "all consumed patterns layered on top of each other"],
    ["cat", "all consumed patterns, one per cycle"],
    ["fastcat", "all consumed patterns squeezed side by side into one cycle"],
    ["rev", "the first consumed pattern, reversed"],
    ["palindrome", "the first consumed pattern, then its reverse"],
    ["degrade", "the first consumed pattern with half its events dropped"],
    ["fast2", "the first consumed pattern at double speed"],
    ["slow2", "the first consumed pattern at half speed"],
    ["sum", "the numeric consumed colours added together"],
    ["inc", "the first numeric consumed colour plus one"],
    ["count", "how many tokens were consumed"],
  ]);

  const syntaxDocs = new Map([
    ["!timing", "step (maximal step on a grid), event (one at a time) or timed (firing takes time)"],
    ["!grid", "the tick, as a rational number of cycles: 1/16"],
    ["!conflict", "how competitors for one token are resolved: order, priority, round-robin, random"],
    ["!seed", "the seed for !conflict random — determinism is what makes a net a pattern"],
    ["@", "how long the firing's event lasts, in cycles. Under !timing timed it also holds the tokens"],
    ["delay=", "shifts the emitted event without touching the net's logic — how you nudge a hit late"],
    ["pri=", "higher fires first when two transitions want the same token"],
    ["w=", "relative likelihood under !conflict random"],
    ["~", "inhibitor arc: enabled only while that place is empty"],
    ["&", "read arc: needs a token there, consumes none"],
    ["*", "arc weight: 2*hats needs two tokens"],
    ["=>", "a colour combinator for the tokens this transition produces"],
    [":", "in a place declaration, what it holds; inside -[ ]-, what the firing sounds"],
  ]);

  const monacoApi = () => root.globalMonaco || root.monaco;

  const escapeHtml = (value) => String(value == null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");

  const rangeFromOffsets = (monaco, model, start, end) => {
    const s = model.getPositionAt(start);
    const e = model.getPositionAt(Math.max(start, end));
    return new monaco.Range(s.lineNumber, s.column, e.lineNumber, e.column);
  };

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
        const headerStart = contentStart + header[0].search(/#petri\b/i);
        const bodyStart = contentStart + header[0].length;
        regions.push({
          source: content.slice(header[0].length),
          bodyStart,
          bodyEnd: close,
          headerStart,
          headerEnd: headerStart + "#petri".length,
          embedded: true,
        });
      }
      cursor = close + triple.length;
    }
    return regions;
  };

  const sourceScope = (model, offset) => {
    if (!model) return null;
    if (model.getLanguageId && model.getLanguageId() === extensionId) {
      const value = model.getValue();
      return { source: value, bodyStart: 0, bodyEnd: value.length, offset, embedded: false };
    }
    return findEmbeddedRegions(model).find((region) => region.bodyStart <= offset && offset <= region.bodyEnd) || null;
  };

  const regionScopes = (model) => {
    if (!model) return [];
    if (model.getLanguageId && model.getLanguageId() === extensionId) {
      const value = model.getValue();
      return [{ source: value, bodyStart: 0, bodyEnd: value.length, embedded: false }];
    }
    return findEmbeddedRegions(model);
  };

  // ── tokens ───────────────────────────────────────────────────────────────────────────────

  const pushToken = (tokens, kind, start, end, source, baseOffset) => {
    if (end <= start) return;
    tokens.push({ kind, start: baseOffset + start, end: baseOffset + end, text: source.slice(start, end) });
  };

  /**
   * One flat token stream for a whole region. Line structure matters to the *parser* but not to
   * the highlighter, so this stays a scanner rather than growing a second grammar that could
   * disagree with the Kotlin one.
   */
  const tokenize = (source, baseOffset = 0) => {
    const tokens = [];
    let i = 0;
    while (i < source.length) {
      const ch = source[i];
      if (/\s/.test(ch)) { i++; continue; }

      if (ch === "/" && source[i + 1] === "/") {
        const start = i;
        while (i < source.length && source[i] !== "\n") i++;
        pushToken(tokens, "comment", start, i, source, baseOffset);
        continue;
      }
      if (ch === "#") {
        const start = i;
        i++;
        while (i < source.length && /[A-Za-z0-9_.\-]/.test(source[i])) i++;
        // `#petri` is the header marker; `#60` is a numeric token colour.
        const text = source.slice(start, i);
        pushToken(tokens, /^#\d/.test(text) ? "number" : "directive", start, i, source, baseOffset);
        continue;
      }
      if (ch === "!") {
        const start = i;
        i++;
        while (i < source.length && /[A-Za-z\-]/.test(source[i])) i++;
        pushToken(tokens, "directive", start, i, source, baseOffset);
        continue;
      }
      if (ch === '"' || ch === "'") {
        const quote = ch;
        const start = i;
        i++;
        while (i < source.length && source[i] !== quote && source[i] !== "\n") i++;
        if (i < source.length && source[i] === quote) i++;
        // A double-quoted string is a pattern, a single-quoted one is a literal. Colouring them
        // apart is the whole reason the notation distinguishes them.
        pushToken(tokens, quote === '"' ? "pattern" : "string", start, i, source, baseOffset);
        continue;
      }
      if (source.startsWith("-[", i)) { pushToken(tokens, "control", i, i + 2, source, baseOffset); i += 2; continue; }
      if (source.startsWith("]->", i)) { pushToken(tokens, "control", i, i + 3, source, baseOffset); i += 3; continue; }
      if (source.startsWith("=>", i)) { pushToken(tokens, "operator", i, i + 2, source, baseOffset); i += 2; continue; }
      if (ch === "~" || ch === "&") { pushToken(tokens, "modifier", i, i + 1, source, baseOffset); i++; continue; }
      if (ch === "@") {
        const start = i;
        i++;
        while (i < source.length && /[0-9/]/.test(source[i])) i++;
        pushToken(tokens, "duration", start, i, source, baseOffset);
        continue;
      }
      if (ch === "^") {
        const start = i;
        i++;
        while (i < source.length && /[0-9]/.test(source[i])) i++;
        pushToken(tokens, "capacity", start, i, source, baseOffset);
        continue;
      }
      if (ch === "+" || ch === "*" || ch === "," || ch === ":") {
        pushToken(tokens, "operator", i, i + 1, source, baseOffset);
        i++;
        continue;
      }
      if (/[0-9]/.test(ch)) {
        const start = i;
        while (i < source.length && /[0-9./]/.test(source[i])) i++;
        pushToken(tokens, "number", start, i, source, baseOffset);
        continue;
      }
      if (/[A-Za-z_]/.test(ch)) {
        const start = i;
        while (i < source.length && /[A-Za-z0-9_.\-]/.test(source[i])) i++;
        const text = source.slice(start, i);
        let kind = "name";
        if (source[i] === "=") kind = "setting";
        else if (combinatorDocs.has(text)) kind = "combinator";
        else if (timingWords.indexOf(text) >= 0 || conflictWords.indexOf(text) >= 0) kind = "keyword";
        pushToken(tokens, kind, start, i, source, baseOffset);
        continue;
      }
      // Anything else is a single unknown character; the diagnostics pass complains about it.
      pushToken(tokens, "unknown", i, i + 1, source, baseOffset);
      i++;
    }
    return tokens;
  };

  const classForToken = (token, insideBracket) => {
    switch (token.kind) {
      case "comment": return "afield-stacksl-token-comment";
      case "directive": return "afield-stacksl-token-directive";
      case "pattern": return "afield-stacksl-token-creative";
      case "string": return "afield-stacksl-token-constant";
      case "number": return "afield-stacksl-token-number";
      case "duration": return "afield-stacksl-token-number";
      case "capacity": return "afield-stacksl-token-modifier";
      case "modifier": return "afield-stacksl-token-modifier";
      case "control": return "afield-stacksl-token-control";
      case "operator": return "afield-stacksl-token-operator";
      case "combinator": return "afield-stacksl-token-keyword";
      case "keyword": return "afield-stacksl-token-keyword";
      case "setting": return "afield-stacksl-token-variable";
      // A name inside -[ ]- is a transition; outside it is a place. Colouring them apart is the
      // single most useful thing the highlighter does, because it makes the bipartite structure
      // of the net visible in the text.
      case "name": return insideBracket ? "afield-stacksl-token-definition" : "afield-stacksl-token-identifier";
      default: return "afield-stacksl-token-identifier";
    }
  };

  // ── lexical analysis ─────────────────────────────────────────────────────────────────────

  const parseRationalText = (text) => {
    const trimmed = String(text || "").trim();
    if (!trimmed.length) return null;
    const parts = trimmed.split("/");
    if (parts.length > 2) return null;
    const n = Number(parts[0]);
    const d = parts.length === 2 ? Number(parts[1]) : 1;
    if (!isFinite(n) || !isFinite(d) || d === 0) return null;
    return n / d;
  };

  /**
   * Everything about a region that can be decided from the text alone. See the file header for
   * where this stops on purpose.
   */
  const analyzeLexical = (source) => {
    const issues = [];
    const complain = (start, end, message, severity) =>
      issues.push({ start, end, message, severity: severity || "error" });

    let offset = 0;
    for (const raw of String(source == null ? "" : source).split("\n")) {
      const line = raw.split("//")[0];
      const trimmed = line.trim();
      const lead = line.length - line.trimStart().length;
      const lineStart = offset + lead;
      const lineEnd = lineStart + trimmed.length;
      offset += raw.length + 1;
      if (!trimmed.length) continue;
      if (/^#petri\b/i.test(trimmed)) continue;

      if (trimmed[0] === "!") {
        const parts = trimmed.slice(1).trim().split(/\s+/);
        const key = (parts[0] || "").toLowerCase();
        const value = parts.slice(1).join(" ");
        if (directiveWords.indexOf(key) < 0) {
          complain(lineStart, lineEnd, "unknown directive '!" + key + "' — try " + directiveWords.join(", "));
        } else if (key === "timing" && timingWords.indexOf(value.toLowerCase()) < 0) {
          complain(lineStart, lineEnd, "!timing must be " + timingWords.join(", ") + " — not '" + value + "'");
        } else if (key === "conflict" && conflictWords.indexOf(value.toLowerCase().replace("roundrobin", "round-robin")) < 0) {
          complain(lineStart, lineEnd, "!conflict must be " + conflictWords.join(", ") + " — not '" + value + "'");
        } else if (key === "grid") {
          const q = parseRationalText(value);
          if (q === null || q <= 0) complain(lineStart, lineEnd, "!grid must be a positive number of cycles, like 1/16");
        } else if (key === "seed" && !/^-?\d+$/.test(value.trim())) {
          complain(lineStart, lineEnd, "!seed needs a whole number, not '" + value + "'");
        }
        continue;
      }

      const opens = (trimmed.match(/-\[/g) || []).length;
      const closes = (trimmed.match(/]->/g) || []).length;
      if (opens !== closes) {
        complain(lineStart, lineEnd, opens > closes ? "this -[ has no matching ]->" : "this ]-> has no matching -[");
        continue;
      }

      if (opens === 0) {
        if (trimmed.indexOf(":") < 0) {
          complain(
            lineStart, lineEnd,
            "expected a place declaration ('name : 1'), an arc ('a -[ t ]-> b') or a '!' directive"
          );
          continue;
        }
        const name = trimmed.slice(0, trimmed.indexOf(":")).trim();
        if (!/^[A-Za-z_][A-Za-z0-9_.\-]*$/.test(name)) {
          complain(lineStart, lineStart + Math.max(1, name.length),
            "'" + name + "' is not a usable place name (letters, digits, . _ - and no spaces)");
        }
        const body = trimmed.slice(trimmed.indexOf(":") + 1);
        const quotes = (body.match(/"/g) || []).length;
        if (quotes % 2 === 1) complain(lineStart, lineEnd, 'unterminated "" string');
        const apostrophes = (body.match(/'/g) || []).length;
        if (apostrophes % 2 === 1) complain(lineStart, lineEnd, "unterminated '' string");
        continue;
      }

      const inner = trimmed.slice(trimmed.indexOf("-[") + 2, trimmed.indexOf("]->"));
      const innerStart = lineStart + trimmed.indexOf("-[") + 2;
      const head = inner.trim().split(/\s+/)[0] || "";
      if (!head.length) {
        complain(innerStart, innerStart + Math.max(1, inner.length), "a transition needs a name — write -[ hit ]->");
        continue;
      }
      if (!/^[A-Za-z_][A-Za-z0-9_.\-]*$/.test(head)) {
        complain(innerStart, innerStart + head.length,
          "'" + head + "' is not a usable transition name (letters, digits, . _ - and no spaces)");
      }

      const arrow = inner.indexOf("=>");
      if (arrow >= 0) {
        const word = inner.slice(arrow + 2).trim().split(":")[0].trim();
        if (word.length && !combinatorDocs.has(word)) {
          const at = innerStart + inner.indexOf(word, arrow);
          complain(at, at + word.length, "'" + word + "' is not a combinator — try " + combinatorWords.join(", "));
        }
      }

      // `head` came from the *trimmed* inner text, so slicing it off the untrimmed one would be
      // short by the leading whitespace and leave a stray character behind — which then reads as
      // an unknown setting and underlines perfectly good source.
      const afterHead = inner.indexOf(head) + head.length;
      const settings = inner.slice(afterHead).split(":")[0].split("=>")[0];
      for (const piece of settings.split(/\s+/)) {
        if (!piece.length) continue;
        const at = innerStart + inner.indexOf(piece, afterHead);
        if (piece[0] === "@") {
          const q = parseRationalText(piece.slice(1));
          if (q === null) complain(at, at + piece.length, "'" + piece.slice(1) + "' is not a number of cycles — write 4 or 1/16");
          else if (q < 0) complain(at, at + piece.length, "a duration cannot be negative");
        } else if (piece.indexOf("=") > 0) {
          const key = piece.slice(0, piece.indexOf("=")).toLowerCase();
          const value = piece.slice(piece.indexOf("=") + 1);
          if (settingWords.indexOf(key) < 0 && key !== "priority" && key !== "weight") {
            complain(at, at + key.length, "unknown setting '" + key + "=' — try delay=, pri= or w=");
          } else if ((key === "pri" || key === "priority") && !/^-?\d+$/.test(value)) {
            complain(at, at + piece.length, "pri= needs a whole number, not '" + value + "'");
          } else if ((key === "w" || key === "weight") && !(Number(value) > 0)) {
            complain(at, at + piece.length, "w= needs a number greater than zero, not '" + value + "'");
          } else if (key === "delay" && parseRationalText(value) === null) {
            complain(at, at + piece.length, "delay= needs a number of cycles, like 1/32");
          }
        } else {
          complain(at, at + piece.length, "did not understand '" + piece + "' inside -[ … ]-");
        }
      }

      const post = trimmed.slice(trimmed.indexOf("]->") + 3);
      for (const term of post.split("+")) {
        const text = term.trim();
        if (!text.length) continue;
        if (text[0] === "~" || text[0] === "&") {
          const at = lineStart + trimmed.indexOf(text);
          complain(at, at + 1, "'" + text[0] + "' only makes sense on the left of a transition");
        }
      }
    }
    return issues;
  };

  // ── decorations, diagnostics, hover, completion ──────────────────────────────────────────

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
          options: { inlineClassName: "afield-stacksl-token-directive", hoverMessage: { value: "Embedded Petri net" } },
        });
      }
      let insideBracket = false;
      for (const token of tokenize(region.source, region.bodyStart)) {
        if (token.kind === "control" && token.text === "-[") insideBracket = true;
        const inlineClassName = classForToken(token, insideBracket);
        decorations.push({ range: rangeFromOffsets(monaco, model, token.start, token.end), options: { inlineClassName } });
        if (token.kind === "control" && token.text === "]->") insideBracket = false;
      }
    }
    setCollection(editor, "_petriHighlightDecorations", decorations);
  };

  const updateDiagnostics = (editor) => {
    const monaco = monacoApi();
    const model = editor && editor.getModel && editor.getModel();
    if (!monaco || !monaco.editor || !model) return;
    const severityFor = (level) => {
      const S = monaco.MarkerSeverity;
      if (!S) return level === "error" ? 8 : 4;
      return level === "error" ? S.Error : S.Warning;
    };
    const markers = [];
    for (const region of regionScopes(model)) {
      for (const issue of analyzeLexical(region.source)) {
        const start = model.getPositionAt(region.bodyStart + issue.start);
        const end = model.getPositionAt(region.bodyStart + issue.end);
        markers.push({
          severity: severityFor(issue.severity),
          message: issue.message,
          source: extensionLabel,
          startLineNumber: start.lineNumber,
          startColumn: start.column,
          endLineNumber: end.lineNumber,
          endColumn: end.column,
        });
      }
    }
    monaco.editor.setModelMarkers(model, "petri-diagnostics", markers);
  };

  const tokenAt = (tokens, offset) => tokens.find((token) => token.start <= offset && offset <= token.end);

  const describe = (token) => {
    if (!token) return null;
    const text = String(token.text || "");
    if (combinatorDocs.has(text)) return { title: text, detail: combinatorDocs.get(text) };
    if (syntaxDocs.has(text)) return { title: text, detail: syntaxDocs.get(text) };
    const head = text.slice(0, text.indexOf("=") + 1);
    if (head && syntaxDocs.has(head)) return { title: head, detail: syntaxDocs.get(head) };
    if (text[0] === "!" && syntaxDocs.has(text)) return { title: text, detail: syntaxDocs.get(text) };
    switch (token.kind) {
      case "duration": return { title: text, detail: syntaxDocs.get("@") };
      case "capacity": return { title: text, detail: "the most tokens this place will hold" };
      case "modifier": return { title: text, detail: syntaxDocs.get(text) };
      case "pattern": return { title: text, detail: "one token whose colour is this pattern — a section" };
      case "string": return { title: text, detail: "one token coloured with this literal string" };
      case "control": return { title: text, detail: "pre -[ transition ]-> post" };
      default: return null;
    }
  };

  const buildContextPayload = (editor, resolveContext, options = {}) => {
    if (!editor || (!options.allowUnfocused && editor.hasTextFocus && !editor.hasTextFocus())) return null;
    const model = editor.getModel && editor.getModel();
    const position = editor.getPosition && editor.getPosition();
    if (!model || !position) return null;
    const offset = model.getOffsetAt(position);
    const scope = sourceScope(model, offset);
    if (!scope) return null;
    const tokens = tokenize(scope.source, scope.bodyStart).filter((token) => token.kind !== "comment");
    const active = tokenAt(tokens, offset);
    const described = describe(active);
    if (!described) return null;
    const resolved = resolveContext ? resolveContext(editor) : null;
    const contextKey = resolved && resolved.uid && resolved.property ? resolved.uid + "::" + resolved.property : extensionId;
    const html = '<div class="afield-stacksl-context"><strong>Petri</strong> ' +
      '<span class="afield-stacksl-word"><code>' + escapeHtml(described.title) + '</code></span>' +
      '<div class="afield-stacksl-muted">' + escapeHtml(described.detail) + '</div></div>';
    return {
      key: contextKey + "::" + (model.getVersionId ? model.getVersionId() : 0) + "::" + offset + "::" + described.title,
      editor,
      html,
      isLiveValue: false,
    };
  };

  const completionPrefixAt = (model, position) => {
    const offset = model.getOffsetAt(position);
    const value = model.getValue();
    let start = offset;
    while (start > 0 && /[A-Za-z0-9_.\-]/.test(value[start - 1])) start--;
    return { offset, start, text: value.slice(start, offset) };
  };

  /**
   * Offers the vocabulary that makes sense where the caret is: combinators after `=>`, timing
   * words after `!timing`, and otherwise the words that start a line. A completion list that
   * ignores context is worse than none in a notation this small.
   */
  const provideCompletionItems = (editor, model, position, context = {}) => {
    const monaco = context.monaco || monacoApi();
    if (!model || !position || !monaco) return { suggestions: [] };
    const offset = model.getOffsetAt(position);
    const scope = sourceScope(model, offset);
    if (!scope) return { suggestions: [] };

    const prefix = completionPrefixAt(model, position);
    const start = model.getPositionAt(prefix.start);
    const range = new monaco.Range(start.lineNumber, start.column, position.lineNumber, position.column);
    const kinds = monaco.languages && monaco.languages.CompletionItemKind;

    const before = model.getValue().slice(scope.bodyStart, prefix.start);
    const line = before.slice(before.lastIndexOf("\n") + 1);

    let pool;
    if (/=>\s*[A-Za-z0-9_.\-]*$/.test(line)) {
      pool = combinatorWords.map((word) => ({ word, detail: combinatorDocs.get(word), kind: kinds ? kinds.Function : 1 }));
    } else if (/!timing\s+\S*$/.test(line)) {
      pool = timingWords.map((word) => ({ word, detail: "firing semantics", kind: kinds ? kinds.EnumMember : 1 }));
    } else if (/!conflict\s+\S*$/.test(line)) {
      pool = conflictWords.map((word) => ({ word, detail: "conflict resolution", kind: kinds ? kinds.EnumMember : 1 }));
    } else if (/-\[[^\]]*$/.test(line)) {
      pool = settingWords.map((word) => ({
        word: word + "=",
        detail: syntaxDocs.get(word + "="),
        kind: kinds ? kinds.Property : 1,
      }));
    } else if (line.trim().length === 0 || /^!\w*$/.test(line.trim())) {
      pool = directiveWords.map((word) => ({ word: "!" + word, detail: syntaxDocs.get("!" + word), kind: kinds ? kinds.Keyword : 1 }));
    } else {
      pool = [];
    }

    const typed = prefix.text.toLowerCase();
    const suggestions = pool
      .filter((entry) => typed.length === 0 || entry.word.toLowerCase().indexOf(typed.replace(/^!/, "")) === 0 ||
        entry.word.toLowerCase().indexOf(typed) === 0)
      .map((entry, index) => ({
        label: entry.word,
        insertText: entry.word,
        filterText: entry.word,
        sortText: "0200" + String(index).padStart(4, "0") + entry.word,
        kind: entry.kind,
        range,
        detail: entry.detail,
        documentation: {
          value: "**" + entry.word + "**\n\n" + (entry.detail || ""),
          isTrusted: true,
          supportHtml: true,
        },
        data: { source: extensionId, aiDocResolved: true },
      }));
    return { suggestions };
  };

  const install = (editor, monaco) => {
    if (!editor || editor._petriExtensionInstalled) return;
    editor._petriExtensionInstalled = true;
    const schedule = () => setTimeout(() => {
      updateEmbeddedHighlight(editor);
      updateDiagnostics(editor);
    }, 0);
    if (editor.onDidChangeModelContent) editor.onDidChangeModelContent(schedule);
    if (editor.onDidChangeModel) editor.onDidChangeModel(schedule);
    const monacoForEvents = monaco || monacoApi();
    if (monacoForEvents && monacoForEvents.editor && typeof monacoForEvents.editor.onDidChangeModelLanguage === "function") {
      monacoForEvents.editor.onDidChangeModelLanguage(schedule);
    }
    schedule();
  };

  const extension = {
    id: extensionId,
    label: extensionLabel,
    languageIds: ["kotlin", extensionId],
    findEmbeddedRegions,
    install,
    updateEmbeddedHighlight,
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
  root.__petriExtensionProof = { registered: true, extension, findEmbeddedRegions, tokenize, analyzeLexical, combinatorWords };
  };

  // A standalone "petri" Monaco language so loose .petri files highlight when opened, and get
  // //-comment toggling. Colouring is done by the decoration highlighter above, which already
  // treats a whole "petri" model as one scope — so there is exactly one tokenizer and no Monarch
  // grammar to disagree with it.
  const registerPetriLanguage = (monaco) => {
    if (!monaco || !monaco.languages || typeof monaco.languages.register !== "function") return;
    const languageId = "petri";
    const alreadyRegistered = typeof monaco.languages.getLanguages === "function" &&
      monaco.languages.getLanguages().some((language) => language && language.id === languageId);
    if (!alreadyRegistered) {
      monaco.languages.register({ id: languageId, extensions: [".petri"], aliases: ["Petri", "petri"] });
    }
    monaco.languages.setLanguageConfiguration(languageId, {
      comments: { lineComment: "//" },
      brackets: [["[", "]"]],
      autoClosingPairs: [{ open: "[", close: "]" }, { open: '"', close: '"' }, { open: "'", close: "'" }],
      surroundingPairs: [{ open: "[", close: "]" }, { open: '"', close: '"' }],
    });
  };

  if (typeof define === "function" && define.amd) {
    define("bithero/petri", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
      const monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
      registerPetriLanguage(monaco);
      installPetriExtension();
      return {};
    });
  } else {
    installPetriExtension();
  }
})();
