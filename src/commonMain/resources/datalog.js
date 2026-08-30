// Datalog editor extension: inline syntax highlighting, lexical diagnostics, a context badge
// and completions for #datalog regions embedded in Kotlin """...""" strings, and for whole
// .datalog documents.
//
// This is the single source of truth for the Datalog editor extension. It is served as a
// static AMD module (loaded via bithero/datalog in bootstrapMonaco.js) in the browser, and
// is also read verbatim by afield.datalog.DatalogEditorExtension for the node-probe smoke
// test. The wrapper below supports both worlds: it registers an AMD module when a Monaco
// loader is present, and otherwise self-installs immediately (as it does under bare eval).
//
// Colouring is decoration-based, exactly like rectsl.js and stacksl.js: no Monarch grammar,
// so there is one tokenizer for a .datalog file and for an embedded region, and no grammar
// that can fail to compile and take initMonaco down with it.
(function () {
  "use strict";

  const installDatalogExtension = () => {
  const root = window;
  const extensionId = "datalog";
  const extensionLabel = "Datalog";
  const triple = '"""';
  const headerRe = /^(?:[ \t]*(?:\r?\n|$))*[ \t]*#datalog\b[^\n]*(?:\r?\n|$)/i;

  // Kept in step with afield.datalog.DatalogParser by DatalogEditorExtensionWorkout: the editor
  // is JavaScript in a browser and the parser is Kotlin on a server, so the lists cannot be
  // shared at run time — but they can be compared at build time.
  const aggregateOps = ["collect", "count", "max", "mean", "min", "sum"];
  const functionWords = ["abs", "atan2", "cat", "ceil", "contains", "cos", "endsWith", "exp", "floor", "hypot", "indexOf", "int", "len", "log", "lower", "max", "min", "not", "num", "pow", "round", "sign", "sin", "split", "sqrt", "startsWith", "str", "substr", "tan", "trim", "upper"];
  const directiveWords = ["boxes", "facts", "help", "program", "relations", "reset", "why"];
  // The relations afield.datalog's box bridge defines, offered once a program has said #boxes.
  const boxRelations = [
    ["box", "box(Id) — every live box in the document"],
    ["name", "name(Id, Name) — a box's display name"],
    ["child", "child(Parent, Child) — a graph edge, in the direction execution flows"],
    ["nested", "nested(Outer, Inner) — a box nested inside another"],
    ["frame", "frame(Id, X, Y, W, H) — position and size on the canvas"],
    ["prop", "prop(Id, Key, Value) — one scalar property"],
    ["running", "running(Id, Strand) — a strand currently executing"],
  ];

  const functionDocs = new Map([
    ["abs", "abs(x) — absolute value"],
    ["sign", "sign(x) — -1, 0 or 1"],
    ["sqrt", "sqrt(x)"],
    ["floor", "floor(x)"],
    ["ceil", "ceil(x)"],
    ["round", "round(x) — to the nearest whole number"],
    ["pow", "pow(x, y)"],
    ["exp", "exp(x)"],
    ["log", "log(x) — natural log"],
    ["sin", "sin(x)"],
    ["cos", "cos(x)"],
    ["tan", "tan(x)"],
    ["atan2", "atan2(y, x)"],
    ["hypot", "hypot(x, y)"],
    ["min", "min(a, b) — the lesser of two values, in the total value order"],
    ["max", "max(a, b) — the greater of two values, in the total value order"],
    ["len", "len(s) — string length, or list size"],
    ["upper", "upper(s)"],
    ["lower", "lower(s)"],
    ["trim", "trim(s)"],
    ["cat", "cat(a, b, ...) — concatenate"],
    ["substr", "substr(s, from [, count])"],
    ["indexOf", "indexOf(s, needle) — -1 when absent"],
    ["contains", "contains(s, needle) — usable on its own as a test"],
    ["startsWith", "startsWith(s, prefix)"],
    ["endsWith", "endsWith(s, suffix)"],
    ["split", "split(s, separator) — a list"],
    ["num", "num(x) — coerce to a number"],
    ["str", "str(x) — coerce to a string"],
    ["int", "int(x) — truncate towards minus infinity"],
    ["not", "not(x) — logical negation of a value"],
  ]);

  const aggregateDocs = new Map([
    ["count", "N = count : { body } — how many solutions the subgoal has"],
    ["sum", "S = sum Expr : { body } — 0 when the group is empty"],
    ["min", "M = min Expr : { body } — no value for an empty group, so the rule does not fire"],
    ["max", "M = max Expr : { body } — no value for an empty group, so the rule does not fire"],
    ["mean", "A = mean Expr : { body } — no value for an empty group"],
    ["collect", "L = collect Expr : { body } — a sorted list value"],
  ]);

  const directiveDocs = new Map([
    ["help", "#help — directives, box-graph relations, aggregates and functions"],
    ["boxes", "#boxes — load the live box graph into the base relations"],
    ["relations", "#relations — every relation in the model, with row counts"],
    ["facts", "#facts <relation> — print the rows of one relation"],
    ["program", "#program — print the accumulated program as source"],
    ["why", "#why <goal> — explain how a fact was derived"],
    ["reset", "#reset — forget every fact and rule in this box"],
  ]);

  const operatorText = new Set(["=", "!=", "<>", "<", "<=", ">", ">=", "+", "-", "*", "/", "!"]);
  const wordOperators = new Set(["mod", "div"]);
  const constantWords = new Set(["true", "false", "null"]);

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

  // A model is "ours" when it IS a Datalog document. The comparison is case-insensitive because
  // a box's `code_language` is the language's display label ("Datalog"), while a loose .datalog
  // file gets the registered id ("datalog"), and both have to highlight.
  const isDatalogModel = (model) =>
    !!model && typeof model.getLanguageId === "function" &&
    String(model.getLanguageId() || "").toLowerCase() === extensionId;

  const isKotlinModel = (model) =>
    !!model && typeof model.getLanguageId === "function" &&
    String(model.getLanguageId() || "").toLowerCase() === "kotlin";

  const findEmbeddedRegions = (model) => {
    if (!isKotlinModel(model)) return [];
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
        const headerStart = contentStart + header[0].search(/#datalog\b/i);
        const bodyStart = contentStart + header[0].length;
        regions.push({
          source: content.slice(header[0].length),
          bodyStart,
          bodyEnd: close,
          headerStart,
          headerEnd: headerStart + "#datalog".length,
          embedded: true,
        });
      }
      cursor = close + triple.length;
    }
    return regions;
  };

  // Everything this extension is responsible for in a model: the whole buffer for a Datalog
  // document, or each embedded #datalog region inside a Kotlin """...""" string.
  const regionScopes = (model) => {
    if (!model) return [];
    if (isDatalogModel(model)) {
      const value = model.getValue();
      return [{ source: value, bodyStart: 0, bodyEnd: value.length, embedded: false }];
    }
    return findEmbeddedRegions(model);
  };

  const sourceScope = (model, offset) => {
    if (!model) return null;
    if (isDatalogModel(model)) {
      const value = model.getValue();
      return { source: value, bodyStart: 0, bodyEnd: value.length, offset, embedded: false };
    }
    return findEmbeddedRegions(model).find((region) => region.bodyStart <= offset && offset <= region.bodyEnd) || null;
  };

  const isIdentStart = (ch) => /[A-Za-z_]/.test(ch);
  const isIdentPart = (ch) => /[A-Za-z0-9_]/.test(ch);

  /**
   * One pass, region-local offsets shifted by baseOffset. Kinds:
   * comment | string | number | variable | ident | directive | operator | control | punct.
   */
  const tokenize = (source, baseOffset = 0) => {
    const tokens = [];
    const push = (kind, start, end) => {
      if (end <= start) return;
      tokens.push({ kind, start: baseOffset + start, end: baseOffset + end, text: source.slice(start, end) });
    };
    let i = 0;
    while (i < source.length) {
      const ch = source[i];
      if (/\s/.test(ch)) { i++; continue; }
      if (ch === "%" || (ch === "/" && source[i + 1] === "/")) {
        const start = i;
        while (i < source.length && source[i] !== "\n") i++;
        push("comment", start, i);
        continue;
      }
      if (ch === "/" && source[i + 1] === "*") {
        const start = i;
        i += 2;
        while (i < source.length && !(source[i] === "*" && source[i + 1] === "/")) i++;
        i = Math.min(source.length, i + 2);
        push("comment", start, i);
        continue;
      }
      if (ch === "#") {
        const start = i;
        i++;
        while (i < source.length && isIdentPart(source[i])) i++;
        push("directive", start, i);
        continue;
      }
      if (ch === '"' || ch === "'") {
        const quote = ch;
        const start = i;
        i++;
        while (i < source.length && source[i] !== quote) {
          if (source[i] === "\\" && i + 1 < source.length) i++;
          i++;
        }
        if (i < source.length) i++;
        push("string", start, i);
        continue;
      }
      if (/[0-9]/.test(ch)) {
        const start = i;
        while (i < source.length && /[0-9]/.test(source[i])) i++;
        if (source[i] === "." && /[0-9]/.test(source[i + 1] || "")) {
          i++;
          while (i < source.length && /[0-9]/.test(source[i])) i++;
        }
        if (source[i] === "e" || source[i] === "E") {
          const save = i;
          i++;
          if (source[i] === "+" || source[i] === "-") i++;
          if (/[0-9]/.test(source[i] || "")) {
            while (i < source.length && /[0-9]/.test(source[i])) i++;
          } else i = save;
        }
        push("number", start, i);
        continue;
      }
      if (isIdentStart(ch)) {
        const start = i;
        while (i < source.length && isIdentPart(source[i])) i++;
        const text = source.slice(start, i);
        // The one rule that makes Datalog readable: capital or underscore is a logic variable,
        // lowercase is a predicate or a constant symbol.
        push(/^[A-Z_]/.test(text) ? "variable" : "ident", start, i);
        continue;
      }
      const two = source.slice(i, i + 2);
      if (two === ":-" || two === "?-") { push("control", i, i + 2); i += 2; continue; }
      if (two === "<=" || two === ">=" || two === "!=" || two === "<>") { push("operator", i, i + 2); i += 2; continue; }
      if (ch === ".") { push("control", i, i + 1); i++; continue; }
      if (operatorText.has(ch)) { push("operator", i, i + 1); i++; continue; }
      push("punct", i, i + 1);
      i++;
    }
    // A predicate is an identifier applied to arguments; the marker is a '(' immediately after.
    tokens.forEach((token, index) => {
      const next = tokens[index + 1];
      token.isCall = token.kind === "ident" && !!next && next.kind === "punct" && next.text === "(" &&
        next.start === token.end;
    });
    return tokens;
  };

  const classForToken = (token, atClauseStart) => {
    switch (token.kind) {
      case "comment": return "afield-stacksl-token-comment";
      case "string": return "afield-stacksl-token-variable";
      case "number": return "afield-stacksl-token-number";
      case "directive": return "afield-stacksl-token-directive";
      case "variable": return "afield-stacksl-token-modifier";
      case "control": return "afield-stacksl-token-control";
      case "operator": return "afield-stacksl-token-operator";
      case "punct": return "afield-stacksl-token-operator";
      default: break;
    }
    const word = String(token.text || "");
    if (word === "not") return "afield-stacksl-token-keyword";
    if (wordOperators.has(word)) return "afield-stacksl-token-operator";
    if (constantWords.has(word)) return "afield-stacksl-token-constant";
    // Applied or not is the discriminator, and it has to be tested before anything else: `min` is
    // both a builtin function (`min(a, b)`, always applied) and an aggregate operator
    // (`M = min P : { … }`, never applied), and only the following '(' tells them apart.
    if (token.isCall) {
      if (functionDocs.has(word)) return "afield-stacksl-token-operator";
      return atClauseStart ? "afield-stacksl-token-definition" : "afield-stacksl-token-keyword";
    }
    if (aggregateDocs.has(word)) return "afield-stacksl-token-creative";
    // A bare lowercase identifier in an argument is a constant symbol — `edge(a, b)` and
    // `edge("a", "b")` are the same fact — so it should not read as a predicate.
    return atClauseStart ? "afield-stacksl-token-definition" : "afield-stacksl-token-constant";
  };

  // Which identifiers begin a clause, so a rule head can be coloured differently from the same
  // predicate used in a body. Purely positional: the first token after a '.' or the region start.
  const clauseStarts = (tokens) => {
    const starts = new Set();
    let atStart = true;
    for (const token of tokens) {
      if (token.kind === "comment") continue;
      if (atStart && (token.kind === "ident" || token.kind === "variable")) starts.add(token.start);
      atStart = token.kind === "control" && token.text === ".";
    }
    return starts;
  };

  /** Predicate names, their arities and how often each is a head — for hovers and completion. */
  const analyzeProgram = (source, baseOffset = 0) => {
    const tokens = tokenize(source, baseOffset);
    const starts = clauseStarts(tokens);
    const predicates = new Map();
    tokens.forEach((token, index) => {
      if (token.kind !== "ident") return;
      if (functionDocs.has(token.text) || aggregateDocs.has(token.text) || token.text === "not") return;
      if (!token.isCall && !starts.has(token.start)) return;
      const entry = predicates.get(token.text) || { name: token.text, arities: new Set(), heads: 0, uses: 0 };
      let arity = 0;
      if (token.isCall) {
        let depth = 0;
        let sawArgument = false;
        for (let j = index + 1; j < tokens.length; j++) {
          const t = tokens[j];
          if (t.kind === "punct" && (t.text === "(" || t.text === "{")) { depth++; continue; }
          if (t.kind === "punct" && (t.text === ")" || t.text === "}")) {
            depth--;
            if (depth === 0) break;
            continue;
          }
          if (depth === 1) {
            if (t.kind === "punct" && t.text === ",") arity++;
            else sawArgument = true;
          }
        }
        if (sawArgument || arity > 0) arity += 1;
      }
      entry.arities.add(arity);
      if (starts.has(token.start)) entry.heads++; else entry.uses++;
      predicates.set(token.text, entry);
    });
    return { tokens, starts, predicates };
  };

  /**
   * Lexical checks only. Safety and stratification are whole-program
   * properties the Kotlin engine decides — re-deriving them here from a half-typed buffer would
   * underline correct code, and a highlighter that cries wolf is worse than one that says nothing.
   * Everything below is a bracket, a quote or a missing '.', which cannot be a false positive.
   * Offsets are region-local.
   */
  const analyzeLexical = (source) => {
    const issues = [];
    const parens = [];
    const braces = [];
    let i = 0;
    let lastTerminator = -1;
    let sawClauseText = false;
    let clauseStart = 0;
    while (i < source.length) {
      const ch = source[i];
      // A `#directive` line is a session command, not a clause. Treating it as ordinary text made
      // the unterminated-clause check underline every `#why` in a box, which is the highlighter
      // crying wolf on correct source.
      if (ch === "%" || ch === "#" || (ch === "/" && source[i + 1] === "/")) {
        while (i < source.length && source[i] !== "\n") i++;
        continue;
      }
      if (ch === "/" && source[i + 1] === "*") {
        const start = i;
        i += 2;
        let closed = false;
        while (i < source.length) {
          if (source[i] === "*" && source[i + 1] === "/") { i += 2; closed = true; break; }
          i++;
        }
        if (!closed) issues.push({ start, end: source.length, message: "Datalog block comment is not closed with '*/'.", severity: "error" });
        continue;
      }
      if (ch === '"' || ch === "'") {
        const quote = ch;
        const start = i;
        i++;
        let closed = false;
        while (i < source.length) {
          if (source[i] === "\\" && i + 1 < source.length) { i += 2; continue; }
          if (source[i] === quote) { i++; closed = true; break; }
          if (source[i] === "\n") break;
          i++;
        }
        if (!closed) issues.push({ start, end: Math.min(source.length, i), message: "Datalog string literal is not terminated.", severity: "error" });
        sawClauseText = true;
        continue;
      }
      if (ch === "(") { parens.push(i); i++; sawClauseText = true; continue; }
      if (ch === ")") {
        if (parens.length > 0) parens.pop();
        else issues.push({ start: i, end: i + 1, message: "Datalog ')' has no matching '('.", severity: "warning" });
        i++;
        sawClauseText = true;
        continue;
      }
      if (ch === "{") { braces.push(i); i++; sawClauseText = true; continue; }
      if (ch === "}") {
        if (braces.length > 0) braces.pop();
        else issues.push({ start: i, end: i + 1, message: "Datalog '}' has no matching '{' — an aggregate body is written 'op Expr : { … }'.", severity: "warning" });
        i++;
        sawClauseText = true;
        continue;
      }
      // A '.' only ends a clause when it is not the decimal point of a number.
      if (ch === "." && !/[0-9]/.test(source[i + 1] || "")) {
        lastTerminator = i;
        sawClauseText = false;
        clauseStart = i + 1;
        i++;
        continue;
      }
      if (!/\s/.test(ch)) sawClauseText = true;
      i++;
    }
    for (const open of parens) {
      issues.push({ start: open, end: open + 1, message: "Datalog '(' is not closed with ')'.", severity: "error" });
    }
    for (const open of braces) {
      issues.push({ start: open, end: open + 1, message: "Datalog '{' is not closed with '}'.", severity: "error" });
    }
    if (sawClauseText) {
      issues.push({
        start: Math.max(0, clauseStart),
        end: source.length,
        message: "Datalog clause is not terminated with '.'.",
        severity: "warning",
      });
    }
    void lastTerminator;
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
          options: { inlineClassName: "afield-stacksl-token-directive", hoverMessage: { value: "Embedded Datalog" } },
        });
      }
      const tokens = tokenize(region.source, region.bodyStart);
      const starts = clauseStarts(tokens);
      for (const token of tokens) {
        decorations.push({
          range: rangeFromOffsets(monaco, model, token.start, token.end),
          options: { inlineClassName: classForToken(token, starts.has(token.start)) },
        });
      }
    }
    setCollection(editor, "_datalogHighlightDecorations", decorations);
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
    monaco.editor.setModelMarkers(model, "datalog-diagnostics", markers);
  };

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
    const analysis = analyzeProgram(scope.source, scope.bodyStart);
    const active = tokenAt(analysis.tokens.filter((token) => token.kind !== "comment"), offset);
    const resolved = resolveContext ? resolveContext(editor) : null;
    const contextKey = resolved && resolved.uid && resolved.property ? resolved.uid + "::" + resolved.property : extensionId;

    let detail = "";
    let word = "";
    if (active) {
      word = String(active.text || "");
      if (active.kind === "variable") {
        detail = word === "_" || /^_/.test(word)
          ? "anonymous variable — every '_' is a fresh hole"
          : "logic variable";
      } else if (active.kind === "directive") {
        detail = directiveDocs.get(word.replace(/^#/, "")) || "directive";
      } else if (active.kind === "ident" && aggregateDocs.has(word)) {
        detail = aggregateDocs.get(word);
      } else if (active.kind === "ident" && functionDocs.has(word)) {
        detail = functionDocs.get(word);
      } else if (active.kind === "ident") {
        const entry = analysis.predicates.get(word);
        if (entry) {
          const arities = Array.from(entry.arities).sort((a, b) => a - b).map((n) => word + "/" + n).join(", ");
          const heads = entry.heads === 1 ? "1 clause" : entry.heads + " clauses";
          detail = arities + " — " + (entry.heads > 0 ? heads : "no clause here (a base relation, or defined elsewhere)");
        } else {
          detail = "constant symbol";
        }
      } else if (active.kind === "control" && active.text === ":-") {
        detail = "head :- body — read ':-' as 'if'";
      } else if (active.kind === "control" && active.text === "?-") {
        detail = "?- goal. — run a query";
      } else if (active.kind === "operator" && active.text === "=") {
        detail = "'=' binds when the left side is not bound yet, and compares when it is";
      } else if (active.kind === "operator" && active.text === "!") {
        detail = "negation — every variable must be bound elsewhere, or appear only here";
      }
    }
    if (!word) return null;

    const relations = analysis.predicates.size;
    const html = '<div class="afield-stacksl-context"><strong>Datalog</strong> ' +
      '<span class="afield-stacksl-scope">' + relations + (relations === 1 ? " relation" : " relations") + '</span>' +
      ' · <span class="afield-stacksl-word"><code>' + escapeHtml(word) + '</code></span>' +
      (detail ? ' · <span class="afield-stacksl-muted">' + escapeHtml(detail) + '</span>' : '') +
      '</div>';
    return {
      key: contextKey + "::" + (model.getVersionId ? model.getVersionId() : 0) + "::" + offset + "::" + word,
      editor,
      html,
      isLiveValue: false,
    };
  };

  const completionPrefixAt = (model, position) => {
    const offset = model.getOffsetAt(position);
    const value = model.getValue();
    let start = offset;
    while (start > 0 && (/[A-Za-z0-9_]/.test(value[start - 1]) || value[start - 1] === "#")) start--;
    return { offset, start, text: value.slice(start, offset) };
  };

  const provideCompletionItems = (editor, model, position, context = {}) => {
    const monaco = context.monaco || monacoApi();
    if (!model || !position || !monaco) return { suggestions: [] };
    const offset = model.getOffsetAt(position);
    const scope = sourceScope(model, offset);
    if (!scope) return { suggestions: [] };
    const prefix = completionPrefixAt(model, position);
    const start = model.getPositionAt(prefix.start);
    const range = new monaco.Range(start.lineNumber, start.column, position.lineNumber, position.column);
    const kinds = (monaco.languages && monaco.languages.CompletionItemKind) || {};
    const typed = prefix.text;

    const items = [];
    const add = (label, insertText, detail, documentation, kind, order) => {
      items.push({
        label,
        insertText: insertText || label,
        filterText: label,
        sortText: order + label,
        kind: kind || kinds.Function || 1,
        range,
        detail,
        documentation: { value: documentation, isTrusted: true, supportHtml: true },
        data: { source: extensionId, aiDocResolved: true },
      });
    };

    if (typed.startsWith("#")) {
      // A directive is only a directive at the start of a line; offering them mid-clause is noise.
      for (const name of directiveWords) {
        add("#" + name, "#" + name, "directive", directiveDocs.get(name) || "", kinds.Keyword || 2, "0100");
      }
    } else {
      const analysis = analyzeProgram(scope.source, scope.bodyStart);
      for (const entry of analysis.predicates.values()) {
        const arity = Array.from(entry.arities).sort((a, b) => b - a)[0] || 0;
        const detail = Array.from(entry.arities).sort((a, b) => a - b).map((n) => entry.name + "/" + n).join(", ");
        add(entry.name, arity > 0 ? entry.name + "(" : entry.name, detail,
          "**" + detail + "** — " + (entry.heads > 0 ? entry.heads + " clause(s) here" : "used but not defined here"),
          kinds.Property || 4, "0100");
      }
      for (const name of aggregateOps) {
        add(name, name, "aggregate", "**" + name + "** — " + (aggregateDocs.get(name) || ""), kinds.Keyword || 2, "0200");
      }
      for (const name of functionWords) {
        add(name, name + "(", "function", "**" + name + "** — " + (functionDocs.get(name) || ""), kinds.Function || 1, "0300");
      }
      for (const [name, doc] of boxRelations) {
        if (analysis.predicates.has(name)) continue;
        add(name, name + "(", "box graph", "**" + doc + "**\n\nAvailable after `#boxes`.", kinds.EnumMember || 3, "0400");
      }
    }

    const lower = typed.replace(/^#/, "").toLowerCase();
    const suggestions = items.filter((item) =>
      lower.length === 0 || String(item.label).replace(/^#/, "").toLowerCase().startsWith(lower));
    return { suggestions };
  };

  const install = (editor, monaco, registry) => {
    if (!editor || editor._datalogExtensionInstalled) return;
    editor._datalogExtensionInstalled = true;
    const schedule = () => setTimeout(() => {
      updateEmbeddedHighlight(editor);
      updateDiagnostics(editor);
    }, 0);
    if (editor.onDidChangeCursorPosition) editor.onDidChangeCursorPosition(schedule);
    if (editor.onDidChangeModelContent) editor.onDidChangeModelContent(schedule);
    if (editor.onDidChangeModel) editor.onDidChangeModel(schedule);
    // Re-highlight when a model is assigned the datalog language in place — which is exactly what
    // happens when a box becomes a Datalog box while its editor is already open.
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
  root.__datalogExtensionProof = {
    registered: true,
    extension,
    findEmbeddedRegions,
    tokenize,
    analyzeLexical,
    analyzeProgram,
    vocabulary: { aggregateOps, functionWords, directiveWords, boxRelations: boxRelations.map((r) => r[0]) },
  };
  };

  // A standalone "datalog" Monaco language, so loose .datalog files highlight when opened, and so
  // %-comment toggling and bracket auto-closing work. Colouring itself is done by the decoration
  // highlighter above, which already treats a whole datalog model as one scope — no Monarch
  // grammar, so there is a single tokenizer and nothing that can wedge initMonaco.
  const registerDatalogLanguage = (monaco) => {
    if (!monaco || !monaco.languages || typeof monaco.languages.register !== "function") return;
    const languageId = "datalog";
    const alreadyRegistered = typeof monaco.languages.getLanguages === "function" &&
      monaco.languages.getLanguages().some((language) => language && language.id === languageId);
    if (!alreadyRegistered) {
      monaco.languages.register({ id: languageId, extensions: [".datalog", ".dl"], aliases: ["Datalog", "datalog"] });
    }
    monaco.languages.setLanguageConfiguration(languageId, {
      comments: { lineComment: "%", blockComment: ["/*", "*/"] },
      brackets: [["(", ")"], ["{", "}"]],
      autoClosingPairs: [
        { open: "(", close: ")" },
        { open: "{", close: "}" },
        { open: "\"", close: "\"" },
      ],
      surroundingPairs: [
        { open: "(", close: ")" },
        { open: "{", close: "}" },
        { open: "\"", close: "\"" },
      ],
    });
  };

  if (typeof define === "function" && define.amd) {
    define("bithero/datalog", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
      const monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
      registerDatalogLanguage(monaco);
      installDatalogExtension();
      return {};
    });
  } else {
    // Bare eval — how DatalogEditorExtension.html() injects this into a page, and how the node
    // probe loads it. Register the language here too, or "self-installs immediately" would only
    // be half true: the extension would run but a .datalog model would have no language to be.
    registerDatalogLanguage(window.globalMonaco || window.monaco);
    installDatalogExtension();
  }
})();
