// Sonic Pi editor extension: inline syntax highlighting, a context badge, hover docs and
// completions for #sonicpi regions embedded in Kotlin """...""" strings.
//
// The shape follows rectsl.js — register with FieldLanguageExtensions, find the embedded regions,
// decorate with the shared afield-stacksl-token-* palette — with two differences that come from
// Sonic Pi rather than from taste.
//
// First, `#` is a comment in Ruby, so the `#sonicpi` header is a legal line of the program it
// introduces. Sonic Pi counts it, which means an error it reports on line N is line N of the region
// with no adjustment. Nothing here has to strip the header before running, and nothing has to shift
// a line number to explain it.
//
// Second, the vocabulary is not written down here. Sonic Pi describes its own language through
// DocSystem and SynthInfo, so tools/generate_sonic_pi_vocabulary.py extracts every function
// signature, opt, synth, FX and sample name from the installed app into sonicpi-vocabulary.json,
// which this file fetches. Several hundred signatures typed out by hand would be wrong somewhere and
// would rot at the next Sonic Pi release; this way `link_audio` and `set_drive!` showed up in
// completions the moment v5 was installed, without anyone noticing they were new.
(function () {
  "use strict";

  const installSonicPiExtension = () => {
  const root = window;
  const extensionId = "sonicpi";
  const extensionLabel = "Sonic Pi";
  const triple = '"""';
  const headerRe = /^(?:[ \t]*(?:\r?\n|$))*[ \t]*#sonicpi\b[^\n]*(?:\r?\n|$)/i;
  const VOCABULARY_URL = "/sonicpi-vocabulary.json";

  // Ruby's own words. Highlighted as control flow so the Sonic Pi vocabulary stands out against
  // the language it is embedded in.
  const rubyKeywords = new Set([
    "do", "end", "if", "elsif", "else", "unless", "then", "while", "until", "for", "in",
    "begin", "rescue", "ensure", "raise", "return", "next", "break", "redo", "retry",
    "case", "when", "yield", "def", "class", "module", "self", "nil", "true", "false",
    "and", "or", "not", "lambda", "proc", "puts", "print", "require", "loop",
  ]);

  // Enough to colour and complete before the real vocabulary arrives over the wire. Everything
  // else — and every signature, opt and doc string — comes from the generated file.
  const fallbackFns = new Set([
    "play", "sample", "sleep", "live_loop", "with_fx", "with_synth", "use_synth", "use_bpm",
    "sync", "cue", "chord", "scale", "rrand", "rrand_i", "choose", "control", "osc", "use_osc",
    "density", "at", "spread", "ring", "stop", "midi", "use_random_seed", "sample_duration",
  ]);

  // Which argument a symbol after these functions names, so `use_synth :<caret>` offers synths and
  // `sample :<caret>` offers samples rather than one flat list of every name in Sonic Pi.
  const symbolDomains = new Map([
    ["use_synth", "synths"], ["with_synth", "synths"], ["synth", "synths"],
    ["use_fx", "fx"], ["with_fx", "fx"],
    ["sample", "samples"], ["sample_duration", "samples"], ["sample_free", "samples"],
    ["load_sample", "samples"], ["use_sample_defaults", "samples"],
  ]);

  const vocabulary = {
    loaded: false,
    version: "",
    fns: new Map(),
    synths: new Map(),
    fx: new Map(),
    samples: new Map(),
  };

  const monacoApi = () => root.globalMonaco || root.monaco || (typeof monaco !== "undefined" ? monaco : null);

  const indexVocabulary = (data) => {
    if (!data || typeof data !== "object") return;
    vocabulary.version = String(data.sonicPiVersion || "");
    (data.fns || []).forEach((fn) => {
      if (fn && fn.name) vocabulary.fns.set(String(fn.name), fn);
    });
    (data.synths || []).forEach((s) => {
      if (s && s.name) vocabulary.synths.set(String(s.name), s);
    });
    (data.fx || []).forEach((s) => {
      if (s && s.name) vocabulary.fx.set(String(s.name), s);
    });
    Object.keys(data.sampleGroups || {}).forEach((group) => {
      const entry = data.sampleGroups[group] || {};
      (entry.samples || []).forEach((name) => {
        vocabulary.samples.set(String(name), { name: String(name), group, summary: entry.desc || "" });
      });
    });
    vocabulary.loaded = true;
  };

  let vocabularyRequest = null;
  const ensureVocabulary = (onLoaded) => {
    if (vocabulary.loaded) return Promise.resolve(vocabulary);
    if (!vocabularyRequest) {
      if (typeof fetch !== "function") {
        vocabularyRequest = Promise.resolve(null);
      } else {
        // Cache-busted on purpose: Field's static handler sets a long max-age, and a stale
        // vocabulary silently completes against the previous Sonic Pi install.
        vocabularyRequest = fetch(VOCABULARY_URL + "?v=" + encodeURIComponent(String(root.__fieldBuildId || Date.now())))
          .then((response) => (response && response.ok ? response.json() : null))
          .then((data) => {
            indexVocabulary(data);
            return data;
          })
          .catch((err) => {
            console.info("Sonic Pi vocabulary not loaded", err && (err.message || err));
            return null;
          });
      }
    }
    return vocabularyRequest.then((data) => {
      if (data && typeof onLoaded === "function") onLoaded();
      return vocabulary;
    });
  };

  const isKnownFn = (word) => vocabulary.fns.has(word) || (!vocabulary.loaded && fallbackFns.has(word));

  const fnInfo = (word) => vocabulary.fns.get(word) || null;

  const domainMap = (domain) => {
    if (domain === "synths") return vocabulary.synths;
    if (domain === "fx") return vocabulary.fx;
    if (domain === "samples") return vocabulary.samples;
    return null;
  };

  const symbolKnown = (word) =>
    vocabulary.synths.has(word) || vocabulary.fx.has(word) || vocabulary.samples.has(word);

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
        const headerStart = contentStart + header[0].search(/#sonicpi\b/i);
        regions.push({
          // The header stays part of the region's source: it is a Ruby comment, Sonic Pi counts it
          // as line 1, and removing it here would put every subsequent line one out of step with
          // the line numbers Sonic Pi reports in its errors.
          source: content,
          bodyStart: contentStart,
          bodyEnd: close,
          headerStart,
          headerEnd: headerStart + "#sonicpi".length,
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

  const sourceScope = (model, offset) =>
    regionScopes(model).find((region) => region.bodyStart <= offset && offset <= region.bodyEnd) || null;

  const pushToken = (tokens, kind, start, end, source, baseOffset) => {
    if (end <= start) return;
    tokens.push({ kind, start: baseOffset + start, end: baseOffset + end, text: source.slice(start, end) });
  };

  /**
   * Lexical only, deliberately. This has to colour a half-typed line in a live editor, where a
   * parser would spend most of its time failing.
   */
  const tokenize = (source, baseOffset = 0) => {
    const tokens = [];
    let i = 0;
    while (i < source.length) {
      const ch = source[i];
      if (/\s/.test(ch)) { i++; continue; }

      if (ch === "#") {
        const start = i;
        while (i < source.length && source[i] !== "\n") i++;
        pushToken(tokens, "comment", start, i, source, baseOffset);
        continue;
      }

      if (ch === '"' || ch === "'") {
        const quote = ch;
        const start = i;
        i++;
        while (i < source.length && source[i] !== quote) {
          if (source[i] === "\\") i++;
          i++;
        }
        i = Math.min(i + 1, source.length);
        pushToken(tokens, "string", start, i, source, baseOffset);
        continue;
      }

      // `:foo` is a symbol; `foo:` is an opt key. Both matter, and they are told apart by which
      // side the colon is on.
      if (ch === ":" && /[A-Za-z_]/.test(source[i + 1] || "")) {
        const start = i;
        i++;
        while (i < source.length && /[A-Za-z0-9_?!]/.test(source[i])) i++;
        pushToken(tokens, "symbol", start, i, source, baseOffset);
        continue;
      }

      if (/[0-9]/.test(ch) || (ch === "." && /[0-9]/.test(source[i + 1] || ""))) {
        const start = i;
        while (i < source.length && /[0-9._eE]/.test(source[i])) i++;
        pushToken(tokens, "number", start, i, source, baseOffset);
        continue;
      }

      if (/[A-Za-z_]/.test(ch)) {
        const start = i;
        while (i < source.length && /[A-Za-z0-9_]/.test(source[i])) i++;
        // Trailing ? and ! belong to the name in Ruby (`sample_loaded?`, `set_drive!`).
        if (i < source.length && (source[i] === "?" || source[i] === "!")) i++;
        if (source[i] === ":" && source[i + 1] !== ":") {
          i++;
          pushToken(tokens, "opt", start, i, source, baseOffset);
        } else {
          pushToken(tokens, "word", start, i, source, baseOffset);
        }
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
    if (token.kind === "opt") return "afield-stacksl-token-operator";
    if (token.kind === "symbol") {
      const name = token.text.slice(1);
      // A synth, FX or sample name is a different kind of thing from a bare symbol: it names a
      // sound, and seeing that at a glance is most of the value of highlighting this language.
      return symbolKnown(name) ? "afield-stacksl-token-creative" : "afield-stacksl-token-constant";
    }
    if (token.kind === "word") {
      const word = token.text;
      if (rubyKeywords.has(word)) return "afield-stacksl-token-control";
      if (isKnownFn(word)) return "afield-stacksl-token-keyword";
      return "afield-stacksl-token-identifier";
    }
    return "afield-stacksl-token-identifier";
  };

  const hoverFor = (token) => {
    if (token.kind === "word") {
      const info = fnInfo(token.text);
      if (!info) return null;
      return { value: describeFn(info, true) };
    }
    if (token.kind === "symbol") {
      const name = token.text.slice(1);
      const entry = vocabulary.synths.get(name) || vocabulary.fx.get(name) || vocabulary.samples.get(name);
      if (!entry) return null;
      const kind = vocabulary.synths.has(name) ? "synth" : vocabulary.fx.has(name) ? "FX" : "sample";
      const summary = entry.summary ? "\n\n" + entry.summary : "";
      return { value: "**:" + name + "** — " + kind + summary };
    }
    return null;
  };

  const signatureOf = (info) =>
    (info.args || []).map((a) => a.name + (a.type ? " (" + a.type + ")" : "")).join(", ");

  const describeFn = (info, full) => {
    const parts = ["**" + info.name + "** " + signatureOf(info)];
    if (info.acceptsBlock) parts[0] += " *do … end*";
    if (info.summary) parts.push(info.summary);
    if (full && info.doc) parts.push(info.doc);
    if (full && (info.opts || []).length) parts.push("opts: `" + info.opts.join("` `") + "`");
    if (full && (info.examples || []).length) parts.push("```ruby\n" + info.examples[0] + "\n```");
    return parts.join("\n\n");
  };

  const ensureCollection = (editor, key) => {
    if (!editor[key]) editor[key] = editor.createDecorationsCollection ? editor.createDecorationsCollection([]) : null;
    return editor[key];
  };

  const setCollection = (editor, key, decorations) => {
    const collection = ensureCollection(editor, key);
    if (collection && collection.set) collection.set(decorations);
  };

  const updateEmbeddedHighlight = (editor) => {
    const monaco = monacoApi();
    const model = editor && editor.getModel && editor.getModel();
    if (!monaco || !model) return;
    ensureVocabulary(() => updateEmbeddedHighlight(editor));
    const decorations = [];
    for (const region of regionScopes(model)) {
      if (region.embedded) {
        const label = vocabulary.version ? "Embedded Sonic Pi v" + vocabulary.version : "Embedded Sonic Pi";
        decorations.push({
          range: rangeFromOffsets(monaco, model, region.headerStart, region.headerEnd),
          options: { inlineClassName: "afield-stacksl-token-directive", hoverMessage: { value: label } },
        });
      }
      for (const token of tokenize(region.source, region.bodyStart)) {
        // Skip the header itself; it already carries the directive decoration above.
        if (region.embedded && token.start >= region.headerStart && token.end <= region.headerEnd) continue;
        const options = { inlineClassName: classForToken(token) };
        const hover = hoverFor(token);
        if (hover) options.hoverMessage = hover;
        decorations.push({ range: rangeFromOffsets(monaco, model, token.start, token.end), options });
      }
    }
    setCollection(editor, "_sonicpiHighlightDecorations", decorations);
  };

  const completionPrefixAt = (model, position) => {
    const line = model.getLineContent(position.lineNumber).slice(0, position.column - 1);
    const match = /(:?[A-Za-z_][A-Za-z0-9_?!]*)$/.exec(line);
    return match ? match[1] : "";
  };

  /** The nearest known function name before the caret on this line, for opt completion. */
  const enclosingFnAt = (model, position) => {
    const line = model.getLineContent(position.lineNumber).slice(0, position.column - 1);
    const words = line.match(/[A-Za-z_][A-Za-z0-9_?!]*/g) || [];
    for (let i = words.length - 1; i >= 0; i--) {
      if (isKnownFn(words[i])) return words[i];
    }
    return null;
  };

  const provideCompletionItems = (editor, model, position, context = {}) => {
    const monaco = monacoApi();
    if (!monaco || !model) return null;
    const offset = model.getOffsetAt(position);
    if (!sourceScope(model, offset)) return null;
    ensureVocabulary();

    const prefix = completionPrefixAt(model, position);
    const kinds = monaco.languages && monaco.languages.CompletionItemKind;
    const word = model.getWordUntilPosition ? model.getWordUntilPosition(position) : null;
    const range = word
      ? new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn)
      : undefined;

    const suggestions = [];
    const seen = new Set();
    const add = (label, detail, documentation, kind, insertText) => {
      if (seen.has(label)) return;
      seen.add(label);
      suggestions.push({
        label,
        kind: kind !== undefined ? kind : (kinds ? kinds.Function : 1),
        detail,
        documentation: documentation ? { value: documentation } : undefined,
        insertText: insertText !== undefined ? insertText : label,
        range,
      });
    };

    const fnBefore = enclosingFnAt(model, position);

    if (prefix.startsWith(":")) {
      // A symbol: offer the names that make sense for whatever function we are inside.
      const domain = fnBefore ? symbolDomains.get(fnBefore) : null;
      const maps = domain ? [domainMap(domain)] : [vocabulary.synths, vocabulary.fx, vocabulary.samples];
      maps.filter(Boolean).forEach((map) => {
        map.forEach((entry, name) => {
          add(":" + name, entry.group ? "sample · " + entry.group : (map === vocabulary.fx ? "FX" : "synth"),
            entry.summary, kinds ? kinds.EnumMember : 1, ":" + name);
        });
      });
      return { suggestions };
    }

    // Opts for the function being called, `amp:`-style, before the general function list so the
    // thing you are most likely typing sorts first.
    if (fnBefore) {
      const info = fnInfo(fnBefore);
      (info ? info.opts || [] : []).forEach((opt) => {
        const doc = info.optDocs ? info.optDocs[opt] : "";
        add(opt + ":", "opt of " + fnBefore, doc, kinds ? kinds.Property : 1, opt + ": ");
      });
    }

    vocabulary.fns.forEach((info, name) => {
      add(name, signatureOf(info) || undefined, describeFn(info, true), kinds ? kinds.Function : 1);
    });
    if (!vocabulary.loaded) fallbackFns.forEach((name) => add(name, undefined, undefined, kinds ? kinds.Function : 1));
    rubyKeywords.forEach((name) => add(name, "Ruby", undefined, kinds ? kinds.Keyword : 1));

    return { suggestions };
  };

  const wordTokenAt = (model, offset) => {
    const region = sourceScope(model, offset);
    if (!region) return null;
    return tokenize(region.source, region.bodyStart).find((t) => t.start <= offset && offset <= t.end) || null;
  };

  const buildContextPayload = (editor, resolveContext, options = {}) => {
    const model = editor && editor.getModel && editor.getModel();
    if (!model) return null;
    const position = editor.getPosition && editor.getPosition();
    if (!position) return null;
    const offset = model.getOffsetAt(position);
    if (!sourceScope(model, offset)) return null;
    ensureVocabulary();

    const token = wordTokenAt(model, offset);
    const contextKey = (options && options.contextKey) || extensionId;
    const version = model.getVersionId ? model.getVersionId() : 0;

    let inner = "";
    if (token && token.kind === "word" && fnInfo(token.text)) {
      const info = fnInfo(token.text);
      inner = '<span class="afield-stacksl-word"><code>' + escapeHtml(info.name) + "</code> " +
        '<span class="afield-stacksl-signature">' + escapeHtml(signatureOf(info)) + "</span></span>" +
        (info.summary ? " " + escapeHtml(info.summary) : "");
    } else if (token && token.kind === "symbol" && symbolKnown(token.text.slice(1))) {
      const name = token.text.slice(1);
      const kind = vocabulary.synths.has(name) ? "synth" : vocabulary.fx.has(name) ? "FX" : "sample";
      inner = '<span class="afield-stacksl-word"><code>:' + escapeHtml(name) + "</code> " +
        '<span class="afield-stacksl-signature">' + kind + "</span></span>";
    } else {
      inner = '<span class="afield-stacksl-signature">' +
        (vocabulary.loaded
          ? vocabulary.fns.size + " fns · " + vocabulary.synths.size + " synths · " +
            vocabulary.fx.size + " FX · " + vocabulary.samples.size + " samples"
          : "loading vocabulary…") +
        "</span>";
    }

    const version_label = vocabulary.version ? " v" + escapeHtml(vocabulary.version) : "";
    const html = '<div class="afield-stacksl-context"><strong>Sonic Pi' + version_label + "</strong> " + inner + "</div>";
    return {
      key: contextKey + "::" + version + "::" + offset + "::" + (token ? token.text : ""),
      editor,
      html,
      isLiveValue: false,
    };
  };

  const escapeHtml = (text) =>
    String(text == null ? "" : text)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");

  const install = (editor, monaco, registry) => {
    if (!editor || editor._sonicpiExtensionInstalled) return;
    editor._sonicpiExtensionInstalled = true;
    const schedule = () => setTimeout(() => updateEmbeddedHighlight(editor), 0);
    if (editor.onDidChangeModelContent) editor.onDidChangeModelContent(schedule);
    if (editor.onDidChangeModel) editor.onDidChangeModel(schedule);
    const monacoForEvents = monaco || monacoApi();
    if (monacoForEvents && monacoForEvents.editor &&
        typeof monacoForEvents.editor.onDidChangeModelLanguage === "function") {
      monacoForEvents.editor.onDidChangeModelLanguage(schedule);
    }
    ensureVocabulary(() => updateEmbeddedHighlight(editor));
    schedule();
  };

  const extension = {
    id: extensionId,
    label: extensionLabel,
    languageIds: ["kotlin", extensionId],
    findEmbeddedRegions,
    install,
    updateEmbeddedHighlight,
    buildContextPayload,
    provideCompletionItems,
  };

  if (root.FieldLanguageExtensions && typeof root.FieldLanguageExtensions.register === "function") {
    root.FieldLanguageExtensions.register(extension);
  } else {
    root.__fieldLanguageExtensionQueue = root.__fieldLanguageExtensionQueue || [];
    root.__fieldLanguageExtensionQueue.push(extension);
  }
  root.__sonicpiExtensionProof = {
    registered: true,
    extension,
    findEmbeddedRegions,
    tokenize,
    classForToken,
    indexVocabulary,
    vocabulary,
    provideCompletionItems,
    buildContextPayload,
  };
  };

  // A standalone "sonicpi" language so a loose .sonicpi / .rb Sonic Pi file highlights when opened
  // directly. Colour still comes from the decoration highlighter above, so there is one tokenizer
  // rather than a Monarch grammar shadowing it.
  const registerSonicPiLanguage = (monaco) => {
    if (!monaco || !monaco.languages || typeof monaco.languages.register !== "function") return;
    const languageId = "sonicpi";
    const alreadyRegistered = typeof monaco.languages.getLanguages === "function" &&
      monaco.languages.getLanguages().some((language) => language && language.id === languageId);
    if (!alreadyRegistered) {
      monaco.languages.register({ id: languageId, extensions: [".sonicpi"], aliases: ["Sonic Pi", "sonicpi", "sonic-pi"] });
    }
    monaco.languages.setLanguageConfiguration(languageId, {
      comments: { lineComment: "#" },
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
    define("bithero/sonicpi", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
      const monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
      registerSonicPiLanguage(monaco);
      installSonicPiExtension();
      return {};
    });
  } else {
    installSonicPiExtension();
  }
})();
