// Faust editor extension: inline syntax highlighting, a context badge, hover docs, lexical
// diagnostics and completions for #faust regions embedded in Kotlin """...""" strings.
//
// The shape follows rectsl.js and sonicpi.js — register with FieldLanguageExtensions, find the
// embedded regions, decorate with the shared afield-stacksl-token-* palette — with three differences
// that come from Faust rather than from taste.
//
// First, the header is *not* part of the program. `#` is a comment in Ruby, so `#sonicpi` can stay in
// the source Sonic Pi is handed and its line numbers stay in step. Faust has no `#` comment at all:
// a `#faust` line is a syntax error, so FaustEngine strips it and carries a line offset. This file
// therefore excludes the header from the region body, the way rectsl.js does, and the offsets here
// and in stripFaustHeader have to agree.
//
// Second, the vocabulary is generated, not written. Faust's ~50 standard libraries document
// themselves in a rigid comment convention, and tools/generate_faust_vocabulary.py extracts a
// thousand-odd signatures, summaries and parameter lists from the libraries *inside the vendored
// compiler* into faust-vocabulary.json, which this file fetches. Hand-typing that would be wrong
// somewhere immediately and stale at the next Faust release.
//
// Third, Faust is qualified. Almost everything a person writes is `os.osc` or `fi.lowpass` — a
// two-letter environment prefix bound in stdfaust.lib. That makes completion much better than a flat
// list: after `os.` this offers only the 78 oscillator functions, with their real signatures.
(function () {
  "use strict";

  const installFaustExtension = () => {
  const root = window;
  const extensionId = "faust";
  const extensionLabel = "Faust";
  const triple = '"""';
  const headerRe = /^(?:[ \t]*(?:\r?\n|$))*[ \t]*#faust\b[^\n]*(?:\r?\n|$)/i;
  const VOCABULARY_URL = "/faust-vocabulary.json";

  // The language's own words — everything that is syntax rather than a library call.
  const keywords = new Set([
    "process", "with", "letrec", "where", "case", "import", "declare", "library",
    "environment", "component", "ffunction", "fconstant", "fvariable", "seq", "par",
    "sum", "prod", "route", "waveform", "soundfile", "rdtable", "rwtable",
    "select2", "select3", "attach", "int", "float", "mem", "prefix", "single", "double", "quad",
  ]);

  // The UI primitives, highlighted apart from the rest: in Field these are the things that become
  // real controls, so they are worth seeing at a glance.
  const uiPrimitives = new Set([
    "hslider", "vslider", "nentry", "button", "checkbox",
    "hbargraph", "vbargraph", "hgroup", "vgroup", "tgroup",
  ]);

  const mathPrimitives = new Set([
    "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "exp", "log", "log10",
    "pow", "sqrt", "abs", "min", "max", "fmod", "remainder", "floor", "ceil", "rint", "trunc",
  ]);

  // Enough to colour before the generated vocabulary arrives over the wire; everything else, and
  // every signature and doc string, comes from the JSON.
  const fallbackPrefixes = new Set([
    "os", "fi", "no", "en", "ef", "re", "ba", "ma", "si", "an", "co", "de", "dm",
    "sy", "ve", "pm", "sp", "ro", "it", "qu", "rm", "so", "pl", "vl", "aa", "hy",
    "mi", "mo", "fd", "ho", "la", "pf", "wd", "dx", "sf", "db",
  ]);

  const vocabulary = {
    loaded: false,
    version: "",
    prefixes: new Map(),      // "os" -> "oscillators.lib"
    byPrefix: new Map(),      // "os" -> [entry, ...]
    functions: new Map(),     // "os.osc" -> entry
    primitives: new Map(),    // "hslider" -> entry
    operators: new Map(),     // ":" -> summary
  };

  const monacoApi = () => root.globalMonaco || root.monaco || (typeof monaco !== "undefined" ? monaco : null);

  const indexVocabulary = (data) => {
    if (!data || typeof data !== "object") return;
    vocabulary.version = String(data.libfaustVersion || "");
    Object.keys(data.prefixes || {}).forEach((prefix) => {
      vocabulary.prefixes.set(prefix, String(data.prefixes[prefix]));
    });
    (data.functions || []).forEach((fn) => {
      if (!fn || !fn.name) return;
      vocabulary.functions.set(String(fn.name), fn);
      const list = vocabulary.byPrefix.get(fn.prefix) || [];
      list.push(fn);
      vocabulary.byPrefix.set(fn.prefix, list);
    });
    (data.primitives || []).forEach((p) => {
      if (p && p.name) vocabulary.primitives.set(String(p.name), p);
    });
    (data.operators || []).forEach((o) => {
      if (o && o.symbol) vocabulary.operators.set(String(o.symbol), String(o.summary || ""));
    });
    vocabulary.loaded = true;
  };

  let vocabularyRequest = null;
  const ensureVocabulary = (onLoaded) => {
    if (vocabulary.loaded) return Promise.resolve(vocabulary);
    if (!vocabularyRequest) {
      if (typeof fetch !== "function") return Promise.resolve(vocabulary);
      vocabularyRequest = fetch(VOCABULARY_URL)
        .then((response) => (response && response.ok ? response.json() : null))
        .then((data) => {
          indexVocabulary(data);
          if (typeof onLoaded === "function") onLoaded();
          return vocabulary;
        })
        .catch((error) => {
          console.warn("Faust vocabulary could not be loaded", error);
          return vocabulary;
        });
    }
    return vocabularyRequest;
  };

  const knownPrefix = (prefix) =>
    vocabulary.prefixes.has(prefix) || (!vocabulary.loaded && fallbackPrefixes.has(prefix));

  // ── regions ──────────────────────────────────────────────────────────────────────────────

  const rangeFromOffsets = (monaco, model, start, end) => {
    const s = model.getPositionAt(start);
    const e = model.getPositionAt(Math.max(start, end));
    return new monaco.Range(s.lineNumber, s.column, e.lineNumber, e.column);
  };

  /**
   * The `#faust` regions of a Kotlin model.
   *
   * `bodyStart` is *after* the header line, because the header is not Faust and FaustEngine removes
   * it before compiling. Anything that maps a compiler line number back onto the editor has to add
   * the same offset — see stripFaustHeader.
   */
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
        regions.push({
          markerStart: contentStart + header[0].search(/#faust\b/i),
          bodyStart: contentStart + header[0].length,
          bodyEnd: close,
        });
      }
      cursor = close + triple.length;
    }
    return regions;
  };

  const regionScopes = (model) => {
    const source = model.getValue();
    return findEmbeddedRegions(model).map((region) => ({
      markerStart: region.markerStart,
      bodyStart: region.bodyStart,
      bodyEnd: region.bodyEnd,
      source: source.slice(region.bodyStart, region.bodyEnd),
    }));
  };

  const sourceScope = (model, offset) =>
    regionScopes(model).find((region) => region.bodyStart <= offset && offset <= region.bodyEnd) || null;

  // ── tokenizing ───────────────────────────────────────────────────────────────────────────

  const pushToken = (tokens, kind, start, end, source, baseOffset) => {
    if (end <= start) return;
    tokens.push({ kind, start: baseOffset + start, end: baseOffset + end, text: source.slice(start, end) });
  };

  // Longest first, so `<:` is not read as `<` then `:`.
  const operators = [
    "<:", ":>", "<=", ">=", "==", "!=", "&&", "||", "<<", ">>",
    ":", ",", "~", "'", "+", "-", "*", "/", "%", "^", "<", ">", "&", "|", "!", "@",
  ];

  /**
   * Lexical only, deliberately. This has to colour a half-typed line in a live editor, where a real
   * Faust parse would spend most of its time failing — and the compiler is 600ms away, which is far
   * too slow to run per keystroke.
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

      if (ch === "/" && source[i + 1] === "*") {
        const start = i;
        i += 2;
        while (i < source.length && !(source[i] === "*" && source[i + 1] === "/")) i++;
        i = Math.min(i + 2, source.length);
        pushToken(tokens, "comment", start, i, source, baseOffset);
        continue;
      }

      if (ch === '"') {
        const start = i;
        i++;
        while (i < source.length && source[i] !== '"') {
          if (source[i] === "\\") i++;
          i++;
        }
        i = Math.min(i + 1, source.length);
        pushToken(tokens, "string", start, i, source, baseOffset);
        continue;
      }

      if (/[0-9]/.test(ch) || (ch === "." && /[0-9]/.test(source[i + 1] || ""))) {
        const start = i;
        while (i < source.length && /[0-9._eE]/.test(source[i])) {
          // `1e-3`: the sign belongs to the exponent, not to a following operator.
          if ((source[i] === "e" || source[i] === "E") && /[-+]/.test(source[i + 1] || "")) i++;
          i++;
        }
        pushToken(tokens, "number", start, i, source, baseOffset);
        continue;
      }

      if (/[A-Za-z_]/.test(ch)) {
        const start = i;
        while (i < source.length && /[A-Za-z0-9_]/.test(source[i])) i++;
        // A qualified name is one token: `os.osc` means more than `os`, `.`, `osc` do.
        if (source[i] === "." && /[A-Za-z_]/.test(source[i + 1] || "")) {
          i++;
          while (i < source.length && /[A-Za-z0-9_]/.test(source[i])) i++;
          pushToken(tokens, "qualified", start, i, source, baseOffset);
        } else {
          pushToken(tokens, "word", start, i, source, baseOffset);
        }
        continue;
      }

      const operator = operators.find((op) => source.startsWith(op, i));
      if (operator) {
        pushToken(tokens, "operator", i, i + operator.length, source, baseOffset);
        i += operator.length;
        continue;
      }

      const start = i;
      i++;
      pushToken(tokens, "punctuation", start, i, source, baseOffset);
    }
    return tokens;
  };

  const classForToken = (token) => {
    if (token.kind === "comment") return "afield-stacksl-token-comment";
    if (token.kind === "string") return "afield-stacksl-token-variable";
    if (token.kind === "number") return "afield-stacksl-token-number";
    if (token.kind === "operator") return "afield-stacksl-token-operator";
    if (token.kind === "qualified") {
      const prefix = token.text.split(".")[0];
      // A known library reads as a call; an unknown prefix is probably a typo and should not be
      // dressed up as one.
      return knownPrefix(prefix) ? "afield-stacksl-token-creative" : "afield-stacksl-token-identifier";
    }
    if (token.kind === "word") {
      const text = token.text;
      if (text === "process") return "afield-stacksl-token-definition";
      if (uiPrimitives.has(text)) return "afield-stacksl-token-modifier";
      if (keywords.has(text)) return "afield-stacksl-token-keyword";
      if (mathPrimitives.has(text)) return "afield-stacksl-token-control";
      if (knownPrefix(text)) return "afield-stacksl-token-constant";
      return "afield-stacksl-token-identifier";
    }
    return null;
  };

  // ── highlighting ─────────────────────────────────────────────────────────────────────────

  const setCollection = (editor, key, decorations) => {
    const existing = editor[key];
    if (existing && typeof existing.set === "function") {
      existing.set(decorations);
      return;
    }
    if (typeof editor.createDecorationsCollection === "function") {
      editor[key] = editor.createDecorationsCollection(decorations);
      return;
    }
    // Older Monaco: deltaDecorations returns ids we have to keep hold of ourselves.
    const previous = editor[key + "_ids"] || [];
    editor[key + "_ids"] = editor.deltaDecorations(previous, decorations);
  };

  const updateEmbeddedHighlight = (editor) => {
    const monaco = monacoApi();
    const model = editor && editor.getModel && editor.getModel();
    if (!monaco || !model) return;
    const decorations = [];
    for (const region of regionScopes(model)) {
      // The marker itself, so it reads as a directive rather than as broken Faust.
      decorations.push({
        range: rangeFromOffsets(monaco, model, region.markerStart, region.bodyStart),
        options: { inlineClassName: "afield-stacksl-token-directive" },
      });
      for (const token of tokenize(region.source, region.bodyStart)) {
        const className = classForToken(token);
        if (!className) continue;
        decorations.push({
          range: rangeFromOffsets(monaco, model, token.start, token.end),
          options: { inlineClassName: className },
        });
      }
    }
    setCollection(editor, "_faustHighlightDecorations", decorations);
  };

  // ── diagnostics ──────────────────────────────────────────────────────────────────────────

  /**
   * Lexical checks only, and on purpose.
   *
   * The real compiler produces far better diagnostics than anything here could, but it lives in the
   * JVM and takes ~600ms; those arrive as box error markers when the box runs. What is worth doing
   * per keystroke is the small set of mistakes that are unambiguous without a parse.
   */
  const analyzeLexical = (source) => {
    const issues = [];
    const tokens = tokenize(source);
    const stack = [];
    const pairs = { "(": ")", "[": "]", "{": "}" };
    const openers = new Set(Object.keys(pairs));
    const closers = new Set(Object.values(pairs));

    for (const token of tokens) {
      if (token.kind === "punctuation" && openers.has(token.text)) {
        stack.push(token);
      } else if (token.kind === "punctuation" && closers.has(token.text)) {
        const top = stack.pop();
        if (!top) {
          issues.push({ start: token.start, end: token.end, severity: "error", message: `Unmatched '${token.text}'.` });
        } else if (pairs[top.text] !== token.text) {
          issues.push({
            start: token.start, end: token.end, severity: "error",
            message: `'${top.text}' is closed by '${token.text}'.`,
          });
        }
      } else if (token.kind === "qualified") {
        const prefix = token.text.split(".")[0];
        if (vocabulary.loaded && !vocabulary.prefixes.has(prefix)) {
          issues.push({
            start: token.start, end: token.start + prefix.length, severity: "warning",
            message: `'${prefix}' is not a library bound by stdfaust.lib.`,
          });
        } else if (vocabulary.loaded && !vocabulary.functions.has(token.text)) {
          // A known library but an unknown member: usually a typo, occasionally an undocumented
          // internal, so a warning rather than an error.
          issues.push({
            start: token.start, end: token.end, severity: "warning",
            message: `'${token.text}' is not a documented function in ${vocabulary.prefixes.get(prefix)}.`,
          });
        }
      } else if (token.kind === "word" && token.text === "faust" && token.start < 8) {
        // A second `#faust` inside the body: the marker is stripped once, and a stray one is a
        // syntax error the compiler will report unhelpfully.
        const before = source.slice(Math.max(0, token.start - 1), token.start);
        if (before === "#") {
          issues.push({
            start: token.start - 1, end: token.end, severity: "error",
            message: "A second #faust marker — '#' is not a comment in Faust.",
          });
        }
      }
    }

    for (const unclosed of stack) {
      issues.push({
        start: unclosed.start, end: unclosed.end, severity: "error",
        message: `'${unclosed.text}' is never closed.`,
      });
    }

    // An unterminated string or block comment swallows the rest of the program.
    const last = tokens[tokens.length - 1];
    if (last && last.kind === "string" && !/"$/.test(last.text.slice(1))) {
      issues.push({ start: last.start, end: last.end, severity: "error", message: "Unterminated string." });
    }
    if (last && last.kind === "comment" && last.text.startsWith("/*") && !last.text.endsWith("*/")) {
      issues.push({ start: last.start, end: last.end, severity: "error", message: "Unterminated /* comment." });
    }

    // Every Faust program needs a `process`. Warn rather than error: a region can legitimately be
    // half-written, and the compiler will say so properly when it runs.
    const hasProcess = /(^|[\s;])process\s*(\(|=)/.test(source);
    if (source.trim().length > 0 && !hasProcess) {
      issues.push({
        start: 0, end: Math.min(source.length, 1), severity: "warning",
        message: "No 'process' definition — a Faust program needs one.",
      });
    }

    return issues;
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
    monaco.editor.setModelMarkers(model, "faust-diagnostics", markers);
  };

  // ── completion ───────────────────────────────────────────────────────────────────────────

  const documentationFor = (entry) => {
    if (!entry) return null;
    const lines = [];
    if (entry.usage) lines.push("```faust\n" + entry.usage + "\n```");
    if (entry.summary) lines.push(entry.summary);
    if (entry.params && entry.params.length) {
      lines.push(entry.params.map((p) => "* `" + p.name + "` — " + p.doc).join("\n"));
    }
    if (entry.lib) lines.push("_" + entry.lib + "_");
    return lines.length ? { value: lines.join("\n\n"), isTrusted: false } : null;
  };

  /**
   * What is being completed at `offset`.
   *
   * The interesting case is a qualified name: `os.sa<caret>` should offer oscillators, not the
   * thousand functions in the language. `partial` is what has been typed after the dot.
   */
  const completionContext = (source, offset) => {
    let i = offset;
    while (i > 0 && /[A-Za-z0-9_]/.test(source[i - 1])) i--;
    const partial = source.slice(i, offset);
    if (source[i - 1] === ".") {
      let j = i - 1;
      while (j > 0 && /[A-Za-z0-9_]/.test(source[j - 1])) j--;
      const prefix = source.slice(j, i - 1);
      if (prefix) return { kind: "qualified", prefix, partial, replaceFrom: i };
    }
    return { kind: "bare", partial, replaceFrom: i };
  };

  const provideCompletionItems = (editor, model, position, context = {}) => {
    const monaco = monacoApi();
    if (!monaco || !model) return [];
    const offset = model.getOffsetAt(position);
    const scope = sourceScope(model, offset);
    if (!scope) return [];

    // Kick the fetch off on first use and refresh once it lands, so an early keystroke is not lost.
    ensureVocabulary(() => {
      const active = root.globalEditor;
      if (active) updateEmbeddedHighlight(active);
    });

    const localOffset = offset - scope.bodyStart;
    const ctx = completionContext(scope.source, localOffset);
    const kinds = monaco.languages.CompletionItemKind || {};
    const suggestions = [];

    const wordStart = model.getPositionAt(scope.bodyStart + ctx.replaceFrom);
    const range = new monaco.Range(
      wordStart.lineNumber, wordStart.column, position.lineNumber, position.column
    );

    const add = (label, detail, entry, kind, sortText, insertText) => {
      suggestions.push({
        label,
        kind: kind !== undefined ? kind : kinds.Function,
        detail,
        documentation: documentationFor(entry),
        insertText: insertText || label,
        range,
        sortText: sortText || label,
        extensionId,
      });
    };

    if (ctx.kind === "qualified") {
      const entries = vocabulary.byPrefix.get(ctx.prefix) || [];
      for (const entry of entries) {
        add(entry.short, entry.usage || entry.summary, entry, kinds.Function, entry.short);
      }
      return suggestions;
    }

    // A bare word: the language, the UI primitives, the library prefixes. Sorted so the things a
    // person reaches for most come first — prefixes and UI before the long tail of math.
    for (const [name, entry] of vocabulary.primitives) {
      const isUi = uiPrimitives.has(name);
      add(
        name,
        entry.summary,
        { summary: entry.summary },
        isUi ? kinds.Field : kinds.Keyword,
        (isUi ? "1" : "3") + name,
        insertTextForPrimitive(name)
      );
    }
    if (!vocabulary.loaded) {
      for (const name of uiPrimitives) add(name, "Faust UI primitive", null, kinds.Field, "1" + name, insertTextForPrimitive(name));
      for (const name of keywords) add(name, "Faust keyword", null, kinds.Keyword, "3" + name);
    }
    for (const [prefix, lib] of (vocabulary.prefixes.size ? vocabulary.prefixes : new Map())) {
      const count = (vocabulary.byPrefix.get(prefix) || []).length;
      add(
        prefix,
        lib + (count ? ` — ${count} functions` : ""),
        { summary: `The ${lib} environment. Type \`${prefix}.\` to see what is in it.` },
        kinds.Module,
        "2" + prefix,
        prefix + "."
      );
    }
    if (!vocabulary.loaded) {
      for (const prefix of fallbackPrefixes) add(prefix, "Faust library", null, kinds.Module, "2" + prefix, prefix + ".");
    }

    return suggestions;
  };

  // The UI primitives are the ones worth a snippet: their argument order is the thing people
  // actually misremember (Faust puts `init` first, before `min`).
  const insertTextForPrimitive = (name) => {
    switch (name) {
      case "hslider":
      case "vslider":
      case "nentry":
        return name + '("${1:label}", ${2:0.5}, ${3:0}, ${4:1}, ${5:0.01})';
      case "button":
      case "checkbox":
        return name + '("${1:label}")';
      case "hbargraph":
      case "vbargraph":
        return name + '("${1:label}", ${2:0}, ${3:1})';
      case "hgroup":
      case "vgroup":
      case "tgroup":
        return name + '("${1:label}", ${2:expr})';
      default:
        return name;
    }
  };

  // ── context badge ────────────────────────────────────────────────────────────────────────

  const escapeHtml = (text) => String(text)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");

  const tokenAt = (tokens, offset) =>
    tokens.find((token) => token.start <= offset && offset <= token.end) || null;

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
    if (!active) return null;

    const resolved = resolveContext ? resolveContext(editor) : null;
    const contextKey = resolved && resolved.uid && resolved.property
      ? resolved.uid + "::" + resolved.property
      : extensionId;

    let title = active.text;
    let signature = "";
    let detail = "";

    if (active.kind === "qualified") {
      const entry = vocabulary.functions.get(active.text);
      if (entry) {
        signature = entry.usage || "";
        detail = entry.summary || "";
        if (entry.params && entry.params.length) {
          detail += (detail ? " " : "") + entry.params.map((p) => p.name + ": " + p.doc).join("; ");
        }
      } else {
        const prefix = active.text.split(".")[0];
        detail = knownPrefix(prefix)
          ? "Not a documented function in " + (vocabulary.prefixes.get(prefix) || prefix)
          : "Unknown library '" + prefix + "'";
      }
    } else if (active.kind === "word") {
      const entry = vocabulary.primitives.get(active.text);
      if (entry) {
        detail = entry.summary || "";
      } else if (knownPrefix(active.text)) {
        const lib = vocabulary.prefixes.get(active.text) || "";
        const count = (vocabulary.byPrefix.get(active.text) || []).length;
        signature = lib;
        detail = count ? count + " functions" : "";
      } else if (keywords.has(active.text)) {
        detail = "Faust keyword";
      } else {
        return null;
      }
    } else if (active.kind === "operator") {
      const summary = vocabulary.operators.get(active.text);
      if (!summary) return null;
      detail = summary;
    } else {
      return null;
    }

    const html = '<div class="afield-stacksl-context"><strong>Faust</strong> ' +
      '<span class="afield-stacksl-word"><code>' + escapeHtml(title) + '</code> ' +
      '<span class="afield-stacksl-signature">' + escapeHtml(signature) + '</span></span>' +
      (detail ? '<div class="afield-stacksl-muted">' + escapeHtml(detail) + '</div>' : '') +
      '</div>';

    return {
      key: contextKey + "::" + (model.getVersionId ? model.getVersionId() : 0) + "::" + offset + "::" + title,
      editor,
      html,
      isLiveValue: false,
    };
  };

  // ── install ──────────────────────────────────────────────────────────────────────────────

  const install = (editor, monaco, registry) => {
    if (!editor || editor._faustExtensionInstalled) return;
    editor._faustExtensionInstalled = true;

    ensureVocabulary(() => {
      updateEmbeddedHighlight(editor);
      updateDiagnostics(editor);
    });

    const schedule = () => setTimeout(() => {
      updateEmbeddedHighlight(editor);
      updateDiagnostics(editor);
    }, 0);
    if (editor.onDidChangeCursorPosition) editor.onDidChangeCursorPosition(schedule);
    if (editor.onDidChangeModelContent) editor.onDidChangeModelContent(schedule);
    if (editor.onDidChangeModel) editor.onDidChangeModel(schedule);
    const monacoForEvents = monaco || monacoApi();
    if (monacoForEvents && monacoForEvents.editor &&
        typeof monacoForEvents.editor.onDidChangeModelLanguage === "function") {
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
  root.__faustExtensionProof = {
    registered: true,
    extension,
    findEmbeddedRegions,
    tokenize,
    classForToken,
    analyzeLexical,
    completionContext,
    indexVocabulary,
    vocabulary,
    provideCompletionItems,
    buildContextPayload,
  };
  };

  // A standalone "faust" language so a loose .dsp / .lib file highlights when opened directly.
  // Colour still comes from the decoration highlighter above rather than a Monarch grammar — one
  // bad Monarch rule wedges initMonaco with no console error, and there is no reason to keep two
  // tokenizers in step.
  const registerFaustLanguage = (monaco) => {
    if (!monaco || !monaco.languages || typeof monaco.languages.register !== "function") return;
    const languageId = "faust";
    const alreadyRegistered = typeof monaco.languages.getLanguages === "function" &&
      monaco.languages.getLanguages().some((language) => language && language.id === languageId);
    if (!alreadyRegistered) {
      monaco.languages.register({
        id: languageId,
        extensions: [".dsp", ".fst"],
        aliases: ["Faust", "faust"],
      });
    }
    monaco.languages.setLanguageConfiguration(languageId, {
      comments: { lineComment: "//", blockComment: ["/*", "*/"] },
      brackets: [["(", ")"], ["[", "]"], ["{", "}"]],
      autoClosingPairs: [
        { open: "(", close: ")" }, { open: "[", close: "]" }, { open: "{", close: "}" },
        { open: '"', close: '"' },
      ],
      surroundingPairs: [
        { open: "(", close: ")" }, { open: "[", close: "]" }, { open: "{", close: "}" },
        { open: '"', close: '"' },
      ],
    });
  };

  if (typeof define === "function" && define.amd) {
    define("bithero/faust", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
      const monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
      registerFaustLanguage(monaco);
      installFaustExtension();
      return {};
    });
  } else {
    installFaustExtension();
  }
})();
