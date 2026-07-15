import {
  sendWithReply,
  clearReplyHandler,
  aiChatMarkdownToHtml,
} from "./afield.mjs";

const DEFAULT_LANGUAGES = new Set([
  "kotlin",
  "python",
  "clojure",
  "glsl",
  "slang",
  "forth",
  "nano",
  "bend",
  "fieldml",
  "yaml",
]);
const MAX_VISIBLE_ITEMS = 80;
const REFRESH_DELAY_MS = 110;
const COMPLETION_RPC_TIMEOUT_MS = 15000;
const RECENCY_STORAGE_KEY = "field.suggest.recency.v1";
const RECENCY_LIMIT = 200;
const CATEGORY_WEIGHT = new Map([
  ["local", 28],
  ["graphics", 26],
  ["functions", 22],
  ["values", 20],
  ["types", 18],
  ["shader", 16],
  ["metadata", 14],
  ["imports", 10],
  ["snippets", 8],
  ["ai", 6],
  ["other", 0],
]);

const LOW_VALUE_MEMBER_NAMES = new Set([
  "equals",
  "hashCode",
  "toString",
  "clone",
  "readExternal",
  "writeExternal",
]);

const KOTLIN_QUICK_COMPLETIONS = [
  { label: "vec", insertText: "vec", category: "functions", icon: "fn" },
  { label: "Vec2", insertText: "Vec2", category: "types", icon: "cl" },
  { label: "Vec3", insertText: "Vec3", category: "types", icon: "cl" },
  { label: "Vec4", insertText: "Vec4", category: "types", icon: "cl" },
  { label: "FLine", insertText: "FLine()", category: "types", icon: "cl" },
  {
    label: "display",
    insertText: 'display("main", "")',
    category: "functions",
    icon: "fn",
  },
  {
    label: "lines",
    insertText: 'lines("main") { }',
    category: "functions",
    icon: "fn",
  },
  { label: "rect", insertText: "rect", category: "functions", icon: "fn" },
  { label: "frame", insertText: "frame", category: "values", icon: "val" },
  {
    label: "fromContext",
    insertText: "fromContext()",
    category: "functions",
    icon: "fn",
  },
  {
    label: "loop",
    insertText: "loop {\n    \n}",
    category: "snippets",
    icon: "sn",
  },
  {
    label: "go",
    insertText: "go {\n    \n}",
    category: "snippets",
    icon: "sn",
  },
  { label: "val", insertText: "val ", category: "snippets", icon: "kw" },
  { label: "var", insertText: "var ", category: "snippets", icon: "kw" },
  { label: "fun", insertText: "fun ", category: "snippets", icon: "kw" },
  { label: "import", insertText: "import ", category: "imports", icon: "kw" },
];

const escapeHtml = (value) =>
  String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");

const loadingMarkup = () =>
  `<div class="afield-suggest-loading" aria-label="Completing"><span class="afield-suggest-spinner" aria-hidden="true"></span></div>`;

let completionReplySequence = 0;

const nextCompletionReplyName = (baseName) => {
  completionReplySequence += 1;
  return `${baseName}-${Date.now().toString(36)}-${completionReplySequence.toString(36)}`;
};

const requestWithReply = (
  name,
  payload,
  replyBaseName,
  timeoutMs = COMPLETION_RPC_TIMEOUT_MS,
) =>
  new Promise((resolve) => {
    const replyName = nextCompletionReplyName(replyBaseName);
    let settled = false;
    let timer = null;
    const finish = (value, removeHandler = true) => {
      if (settled) return;
      settled = true;
      if (timer !== null) clearTimeout(timer);
      if (removeHandler && typeof clearReplyHandler === "function") {
        try {
          clearReplyHandler(replyName);
        } catch (_) {}
      }
      resolve(value);
    };

    timer = setTimeout(() => finish(null), timeoutMs);
    try {
      sendWithReply(name, payload, replyName, (_message, response) => {
        finish(response, false);
        return "remove";
      });
    } catch (_) {
      finish(null);
    }
  });

const titleForCategory = (category) => {
  const key = String(category || "")
    .trim()
    .toLowerCase();
  if (!key) return "Other";
  const known = {
    ai: "AI",
    import: "Imports",
    imports: "Imports",
    type: "Types",
    types: "Types",
    function: "Functions",
    functions: "Functions",
    method: "Functions",
    methods: "Functions",
    value: "Values",
    values: "Values",
    property: "Values",
    properties: "Values",
    graphic: "Graphics",
    graphics: "Graphics",
    local: "Local",
    snippets: "Snippets",
    snippet: "Snippets",
    shader: "Shader",
    shaders: "Shader",
    metadata: "Metadata",
  };
  return (
    known[key] ||
    key.replace(/[-_]+/g, " ").replace(/\b\w/g, (m) => m.toUpperCase())
  );
};

const orderItemsForGroupedDisplay = (items) => {
  const groups = [];
  const byTitle = new Map();
  for (const item of items || []) {
    const title = titleForCategory(item.category);
    let group = byTitle.get(title);
    if (!group) {
      group = [];
      byTitle.set(title, group);
      groups.push(group);
    }
    group.push(item);
  }
  return groups.flat();
};

const categoryFor = (metadata, languageId, source) => {
  if (source === "ai") return "ai";
  if (metadata.category) return metadata.category;
  const display = String(metadata.displayText || metadata.label || "");
  const icon = String(metadata.icon || "").toLowerCase();
  const tail = String(metadata.tail || metadata.detail || "").toLowerCase();
  if (source === "local") return "local";
  if (
    display.startsWith("(import)") ||
    String(metadata.prefix || "").startsWith("import ")
  )
    return "imports";
  if (languageId === "yaml") return "metadata";
  if (languageId === "glsl" || languageId === "slang") {
    if (icon.includes("keyword") || tail.includes("shader")) return "shader";
  }
  if (/(class|interface|type|struct|enum|object)/.test(icon)) return "types";
  if (/(method|function|fun|constructor)/.test(icon)) return "functions";
  if (/(property|field|variable|val|var|value|constant)/.test(icon))
    return "values";
  if (/(snippet)/.test(icon)) return "snippets";
  return "other";
};

const getLanguageId = (model) => {
  try {
    return model && typeof model.getLanguageId === "function"
      ? model.getLanguageId()
      : "plaintext";
  } catch (_) {
    return "plaintext";
  }
};

const isWordCharacter = (text) => /^[A-Za-z0-9_]$/.test(text || "");

const currentWordPrefix = (editor, model) => {
  const position = editor.getPosition();
  if (!model || !position) return "";
  return model.getWordUntilPosition(position).word || "";
};

const isMemberCompletionPosition = (model, position) => {
  if (!model || !position) return false;
  const word = model.getWordUntilPosition(position);
  const beforeWord = model
    .getValueInRange({
      startLineNumber: position.lineNumber,
      startColumn: 1,
      endLineNumber: position.lineNumber,
      endColumn: word.startColumn,
    })
    .trimEnd();
  return beforeWord.endsWith(".") || beforeWord.endsWith("?.");
};

const getCompletionTarget = (editor, model) => {
  const embedded =
    window.embeddedEditors && window.embeddedEditors.getByModel
      ? window.embeddedEditors.getByModel(model)
      : null;
  if (embedded) {
    return {
      editor: embedded.editor,
      uid: embedded.uid,
      property: embedded.property,
      embedded,
    };
  }
  const selected = window.currentSelectedCode || [];
  const uid = selected[0] && selected[0].uid ? selected[0].uid : null;
  return {
    editor,
    uid,
    property: window.currentSelectedProperty || "code",
    embedded: null,
  };
};

const rangeForPayload = (monaco, model, payload) => {
  if (payload.useLineColumns) {
    return new monaco.Range(
      payload.lineStart,
      payload.columnsStart + 1,
      payload.lineStart,
      payload.columnsStart + 1,
    );
  }
  const start = model.getPositionAt(payload.boundsStart);
  const end = model.getPositionAt(payload.boundsEnd);
  return new monaco.Range(
    start.lineNumber,
    start.column,
    end.lineNumber,
    end.column,
  );
};

const normalizeLocalSuggestion = (
  monaco,
  item,
  index,
  model,
  position,
  languageId,
) => {
  const label =
    typeof item.label === "string"
      ? item.label
      : (item.label && item.label.label) || "";
  const word = model.getWordUntilPosition(position);
  const fallbackRange = new monaco.Range(
    position.lineNumber,
    word.startColumn,
    position.lineNumber,
    word.endColumn,
  );
  const data = item.data || {};
  return {
    id: `local-${index}`,
    source: "local",
    label,
    displayText: data.displayText || label,
    insertText: data.insertText || item.insertText || label,
    detail: data.detail || item.detail || "",
    documentation:
      typeof item.documentation === "string"
        ? item.documentation
        : (item.documentation && item.documentation.value) || "",
    icon: data.icon || item.kind || "local",
    category: categoryFor({ ...data, ...item, label }, languageId, "local"),
    range: item.range || fallbackRange,
    prefix: data.prefix || "",
    languageId,
    raw: item,
    index,
  };
};

const quickSuggestionRange = (monaco, model, position) => {
  const word = model.getWordUntilPosition(position);
  return new monaco.Range(
    position.lineNumber,
    word.startColumn,
    position.lineNumber,
    word.endColumn,
  );
};

const normalizeRpcCompletion = (
  monaco,
  payload,
  model,
  languageId,
  offset = 0,
  source = "rpc",
) => {
  const range = rangeForPayload(monaco, model, payload);
  return (payload.metadata || []).map((m, index) => ({
    id: `${source}-${offset + index}`,
    source,
    label: m.displayText || m.text || "",
    displayText: m.displayText || m.text || "",
    insertText: m.text || m.displayText || "",
    detail: m.tail || "",
    documentation: m.documentation || "",
    runtimeDocumentationHtml: m.runtimeDocumentationHtml || "",
    icon: m.icon || "",
    category: categoryFor(m, languageId, source),
    contextScore: Number(m.contextScore) || 0,
    range,
    prefix: m.prefix || "",
    languageId,
    generateDocumentation: !!m.generateDocumentation,
    documentationSourceName: m.documentationSourceName || "",
    documentationSourcePath: m.documentationSourcePath || "",
    documentationSourceLine: m.documentationSourceLine || -1,
    receiverDisplayName: m.receiverDisplayName || "",
    receiverDocumentation: m.receiverDocumentation || "",
    receiverDocumentationSourceName: m.receiverDocumentationSourceName || "",
    receiverDocumentationSourcePath: m.receiverDocumentationSourcePath || "",
    receiverDocumentationSourceLine: m.receiverDocumentationSourceLine || -1,
    raw: m,
    index: offset + index,
  }));
};

const completionPayloadFor = (editor, model, position, target) => ({
  uid: target.uid,
  line: position.lineNumber,
  allCode: model.getValue(),
  column: position.column,
  position: model.getOffsetAt({
    lineNumber: position.lineNumber,
    column: position.column,
  }),
  property: target.property,
});

const normalizeCategory = (category) =>
  String(category || "other")
    .trim()
    .toLowerCase() || "other";

const completionKeyFor = (item) =>
  [
    item.languageId || "",
    normalizeCategory(item.category),
    String(item.insertText || item.displayText || item.label || "")
      .trim()
      .slice(0, 240),
    String(item.detail || "")
      .trim()
      .slice(0, 160),
  ].join("\u001f");

const completionNameFor = (item) => {
  const raw = String(item.displayText || item.label || item.insertText || "")
    .trim()
    .replace(/^(fun|val|var)\s+/, "");
  return raw.split("(")[0].split(/\s+/)[0];
};

const callNameBeforeCursor = (model, position) => {
  if (!model || !position || position.column <= 1) return "";
  const beforeCursor = model
    .getLineContent(position.lineNumber)
    .slice(0, Math.max(0, position.column - 2));
  const match = /([A-Za-z_][A-Za-z0-9_]*)\s*$/.exec(beforeCursor);
  return match ? match[1] : "";
};

const kotlinFenceSignature = (documentation, preferredName = "") => {
  const docs = String(documentation || "");
  const fences = Array.from(
    docs.matchAll(/```(?:kotlin|kt)?[^\S\r\n]*\r?\n([\s\S]*?)```/gi),
  );
  if (fences.length === 0) return "";
  const preferred = String(preferredName || "");
  const blocks = fences
    .map((fence) => {
      const lines = String(fence[1] || "")
        .replace(/\r\n?/g, "\n")
        .split("\n");
      let start = 0;
      let end = lines.length;
      while (start < end && !lines[start].trim()) start++;
      while (end > start && !lines[end - 1].trim()) end--;
      return lines.slice(start, end).join("\n");
    })
    .filter((block) => block.includes("("));

  return (
    blocks.find((block) => preferred && block.includes(preferred)) ||
    blocks[0] ||
    ""
  );
};

const normalizeSignatureText = (signature) =>
  String(signature || "")
    .replace(/\r\n?/g, "\n")
    .replace(
      /^\s*(?:(?:public|private|protected|internal|final|open|override|inline|suspend|operator|infix|external|tailrec)\s+)+/,
      "",
    )
    .trim();

const KOTLIN_SIGNATURE_KEYWORDS = new Set([
  "fun",
  "val",
  "var",
  "suspend",
  "inline",
  "operator",
  "infix",
  "override",
]);

const renderSignatureToken = (token, syntax = "") => {
  const classes = syntax
    ? ` class="afield-call-signature-token afield-call-signature-${syntax}"`
    : "";
  return `<span${classes}>${escapeHtml(token)}</span>`;
};

const renderKotlinSignatureHtml = (signature) => {
  const text = String(signature || "");
  const tokens =
    text.match(
      /[A-Za-z_][A-Za-z0-9_]*|[0-9]+(?:\.[0-9]+)?[A-Za-z]*|::|->|[():,<>.?=]|\s+|./g,
    ) || [];
  const isSpace = (token) => /^\s+$/.test(token);
  const isIdentifier = (token) => /^[A-Za-z_][A-Za-z0-9_]*$/.test(token);
  const previousNonSpace = (index) => {
    for (let i = index - 1; i >= 0; i--) {
      if (!isSpace(tokens[i])) return tokens[i];
    }
    return "";
  };
  const nextNonSpace = (index) => {
    for (let i = index + 1; i < tokens.length; i++) {
      if (!isSpace(tokens[i])) return tokens[i];
    }
    return "";
  };

  return tokens
    .map((token, index) => {
      if (isSpace(token)) return escapeHtml(token);
      if (/^[0-9]/.test(token)) return renderSignatureToken(token, "number");
      if (/^[():,<>.?]$/.test(token))
        return renderSignatureToken(token, "punct");
      if (token === "=" || token === "->" || token === "::")
        return renderSignatureToken(token, "operator");
      if (!isIdentifier(token)) return escapeHtml(token);
      if (KOTLIN_SIGNATURE_KEYWORDS.has(token))
        return renderSignatureToken(token, "kw");
      if (previousNonSpace(index) === "fun")
        return renderSignatureToken(token, "function");
      if (nextNonSpace(index) === ":")
        return renderSignatureToken(token, "param");
      if (
        /^[A-Z]/.test(token) ||
        previousNonSpace(index) === ":" ||
        previousNonSpace(index) === "<"
      ) {
        return renderSignatureToken(token, "type");
      }
      return renderSignatureToken(token, "identifier");
    })
    .join("");
};

const itemSignatureFor = (item) => {
  if (!item) return "";
  const name = completionNameFor(item);
  const display = String(item.displayText || item.label || "").trim();
  const detail = String(item.detail || "").trim();
  const documentationSignature = normalizeSignatureText(
    kotlinFenceSignature(item.documentation, name),
  );
  if (documentationSignature) return documentationSignature;
  if (display.includes("(")) {
    const returnType =
      detail.startsWith(":") && !display.includes(":") ? ` ${detail}` : "";
    return normalizeSignatureText(`${display}${returnType}`);
  }
  if (String(item.insertText || "").includes("("))
    return normalizeSignatureText(String(item.insertText || ""));
  return name ? `${name}(...)` : "";
};

const itemLooksCallable = (item) => {
  if (!item) return false;
  const icon = String(item.icon || "").toLowerCase();
  return (
    itemSignatureFor(item).includes("(") ||
    /(method|function|fun|constructor)/.test(icon)
  );
};

const readRecencyStore = () => {
  try {
    const raw =
      window.localStorage && window.localStorage.getItem(RECENCY_STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : {};
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch (_) {
    return {};
  }
};

const writeRecencyStore = (store) => {
  try {
    const entries = Object.entries(store)
      .sort((a, b) => (b[1]?.lastAccepted || 0) - (a[1]?.lastAccepted || 0))
      .slice(0, RECENCY_LIMIT);
    window.localStorage &&
      window.localStorage.setItem(
        RECENCY_STORAGE_KEY,
        JSON.stringify(Object.fromEntries(entries)),
      );
  } catch (_) {}
};

const touchRecency = (item) => {
  const key = completionKeyFor(item);
  if (!key.trim()) return;
  const store = readRecencyStore();
  const current = store[key] || {};
  store[key] = {
    count: Math.min(50, (Number(current.count) || 0) + 1),
    lastAccepted: Date.now(),
  };
  writeRecencyStore(store);
};

const recencyScoreFor = (item, store, now = Date.now()) => {
  const entry = store[completionKeyFor(item)];
  if (!entry) return 0;
  const ageHours = Math.max(
    0,
    (now - (Number(entry.lastAccepted) || 0)) / 3600000,
  );
  const freshness = Math.max(0, 1 - ageHours / (24 * 14));
  const count = Math.min(10, Number(entry.count) || 0);
  return freshness * 34 + Math.log2(count + 1) * 8;
};

const wordBoundaryBefore = (text, index) => {
  if (index <= 0) return true;
  return (
    /[^A-Za-z0-9_]/.test(text[index - 1]) ||
    /[a-z0-9][A-Z]/.test(text.slice(index - 1, index + 1))
  );
};

const fuzzyScoreText = (query, text, weight = 1) => {
  const q = String(query || "")
    .trim()
    .toLowerCase();
  const raw = String(text || "");
  const t = raw.toLowerCase();
  if (!q) return 0;
  if (!t) return -Infinity;
  if (t === q) return 260 * weight;
  if (t.startsWith(q))
    return (240 - Math.min(80, (t.length - q.length) * 4)) * weight;

  const index = t.indexOf(q);
  if (index >= 0) {
    const boundary = wordBoundaryBefore(raw, index) ? 36 : 0;
    return (168 + boundary - Math.min(60, index * 2)) * weight;
  }

  let ti = 0;
  let first = -1;
  let last = -1;
  let contiguous = 0;
  let boundaryHits = 0;
  let previous = -2;
  for (let qi = 0; qi < q.length; qi++) {
    const ch = q[qi];
    let found = -1;
    while (ti < t.length) {
      if (t[ti] === ch) {
        found = ti;
        ti++;
        break;
      }
      ti++;
    }
    if (found < 0) return -Infinity;
    if (first < 0) first = found;
    if (found === previous + 1) contiguous++;
    if (wordBoundaryBefore(raw, found)) boundaryHits++;
    previous = found;
    last = found;
  }
  const spread = Math.max(0, last - first - q.length);
  const latePenalty = Math.min(45, first * 1.8);
  return (
    (104 + contiguous * 13 + boundaryHits * 18 - spread * 3 - latePenalty) *
    weight
  );
};

const fuzzyScoreItem = (query, item) => {
  const scores = [
    fuzzyScoreText(query, completionNameFor(item), 1.12),
    fuzzyScoreText(query, item.displayText || item.label, 1),
    fuzzyScoreText(query, item.insertText, 0.9),
    fuzzyScoreText(query, item.detail, 0.45),
  ];
  return Math.max(...scores);
};

const memberContextScore = (item, memberContext) => {
  if (!memberContext) return 0;
  const name = completionNameFor(item);
  const icon = String(item.icon || "").toLowerCase();
  if (icon === "keyword") return -180;
  if (LOW_VALUE_MEMBER_NAMES.has(name)) return -120;
  return 0;
};

const rankItems = (items, query, recencyStore, memberContext = false) => {
  const now = Date.now();
  return items
    .map((item, originalIndex) => {
      const fuzzy = fuzzyScoreItem(query, item);
      const category = normalizeCategory(item.category);
      const categoryScore = CATEGORY_WEIGHT.get(category) ?? 0;
      const recency = recencyScoreFor(item, recencyStore, now);
      const aiLateBonus = item.source === "ai" ? 12 : 0;
      const memberScore = memberContextScore(item, memberContext);
      // Server-supplied context boost (e.g. type-matching operands after an infix function).
      const contextScore = Number(item.contextScore) || 0;
      const score =
        (Number.isFinite(fuzzy) ? fuzzy : -Infinity) +
        categoryScore +
        recency +
        aiLateBonus +
        memberScore +
        contextScore -
        originalIndex * 0.012;
      return {
        item,
        score,
        fuzzy,
        recency,
        categoryScore,
        memberScore,
        originalIndex,
      };
    })
    .filter((entry) => !query || Number.isFinite(entry.fuzzy))
    .sort((a, b) => {
      if (b.score !== a.score) return b.score - a.score;
      if (b.recency !== a.recency) return b.recency - a.recency;
      if (b.categoryScore !== a.categoryScore)
        return b.categoryScore - a.categoryScore;
      if (b.memberScore !== a.memberScore) return b.memberScore - a.memberScore;
      return a.originalIndex - b.originalIndex;
    })
    .map((entry) => {
      entry.item.score = entry.score;
      entry.item.recencyScore = entry.recency;
      return entry.item;
    });
};

const syntaxClassForToken = (token) => {
  if (
    /^(fun|val|var|class|object|interface|import|return|for|if|else|when|in)$/.test(
      token,
    )
  )
    return "kw";
  if (/^[A-Z][A-Za-z0-9_]*$/.test(token)) return "type";
  if (/^[0-9]+(?:\.[0-9]+)?$/.test(token)) return "number";
  if (/^[():,<>.?]+$/.test(token)) return "punct";
  return "";
};

const renderSyntaxLabel = (label, query) => {
  const text = String(label || "");
  const queryLower = String(query || "").toLowerCase();
  const parts =
    text.match(
      /[A-Za-z_][A-Za-z0-9_]*|[0-9]+(?:\.[0-9]+)?|[():,<>.?]+|\s+|./g,
    ) || [];
  let consumed = 0;
  return parts
    .map((part) => {
      const classes = ["afield-suggest-token"];
      const syntax = syntaxClassForToken(part);
      if (syntax) classes.push(`afield-suggest-token-${syntax}`);
      if (
        queryLower &&
        text
          .slice(consumed, consumed + part.length)
          .toLowerCase()
          .includes(queryLower)
      ) {
        classes.push("afield-suggest-token-match");
      }
      consumed += part.length;
      return `<span class="${classes.join(" ")}">${escapeHtml(part)}</span>`;
    })
    .join("");
};

const FIELD_SOURCE_SCHEME = "field-source:";
// `[Symbol]` prose links navigate to a symbol card (field-source:); the source
// footer opens the raw code region via GET /api/source (field-source-view:).
const FIELD_SOURCE_VIEW_SCHEME = "field-source-view:";

const sourceCardHref = (name, path, line) => {
  const params = new URLSearchParams();
  if (path) params.set("path", path);
  if (line > 0) params.set("line", String(line));
  if (name) params.set("name", name);
  return `${FIELD_SOURCE_SCHEME}?${params.toString()}`;
};

const sourceViewHref = (path, line) => {
  const params = new URLSearchParams();
  if (path) params.set("path", path);
  if (line > 0) params.set("line", String(line));
  return `${FIELD_SOURCE_VIEW_SCHEME}?${params.toString()}`;
};

// Render the "source <name>:<line>" footer as a link that opens the actual source
// region inline. Works for project files and -sources.jar entries; only plain text
// when there's no path (no dead links).
const renderSourceRef = (display, lineSuffix, name, path, line) => {
  const label = `${escapeHtml(display)}${escapeHtml(lineSuffix)}`;
  if (path) {
    return (
      `<a class="afield-suggest-source-link" title="View source" ` +
      `href="${escapeHtml(sourceViewHref(path, line))}"><code>${label}</code></a>`
    );
  }
  return `<code>${label}</code>`;
};

const renderDocumentation = (item) => {
  const chunks = [];
  const hasRuntimeDocumentation =
    item.runtimeDocumentationHtml && item.runtimeDocumentationHtml.trim();
  const hasItemDocumentation = item.documentation && item.documentation.trim();
  const hasReceiverDocumentation =
    item.receiverDocumentation && item.receiverDocumentation.trim();
  if (hasRuntimeDocumentation) {
    chunks.push(
      `<div class="afield-suggest-runtime-doc">${item.runtimeDocumentationHtml}</div>`,
    );
  }
  if (hasItemDocumentation && !hasRuntimeDocumentation) {
    try {
      chunks.push(aiChatMarkdownToHtml(item.documentation));
    } catch (_) {
      chunks.push(`<pre>${escapeHtml(item.documentation)}</pre>`);
    }
  } else if (!hasReceiverDocumentation && !hasRuntimeDocumentation) {
    chunks.push(
      `<div class="afield-suggest-doc-empty">No documentation available</div>`,
    );
  }
  const sourceName = item.documentationSourceName || "";
  const sourcePath = item.documentationSourcePath || "";
  if (!hasRuntimeDocumentation && (sourceName || sourcePath)) {
    const line =
      item.documentationSourceLine > 0
        ? `:${item.documentationSourceLine}`
        : "";
    chunks.push(
      `<div class="afield-suggest-doc-source">` +
        `<span>source</span>` +
        renderSourceRef(
          sourceName || sourcePath,
          line,
          sourceName,
          sourcePath,
          item.documentationSourceLine,
        ) +
        `</div>`,
    );
  }
  if (hasReceiverDocumentation) {
    const receiverChunks = [];
    try {
      receiverChunks.push(aiChatMarkdownToHtml(item.receiverDocumentation));
    } catch (_) {
      receiverChunks.push(
        `<pre>${escapeHtml(item.receiverDocumentation)}</pre>`,
      );
    }
    const receiverSourceName = item.receiverDocumentationSourceName || "";
    const receiverSourcePath = item.receiverDocumentationSourcePath || "";
    if (receiverSourceName || receiverSourcePath) {
      const line =
        item.receiverDocumentationSourceLine > 0
          ? `:${item.receiverDocumentationSourceLine}`
          : "";
      receiverChunks.push(
        `<div class="afield-suggest-doc-source">` +
          `<span>type source</span>` +
          renderSourceRef(
            receiverSourceName || receiverSourcePath,
            line,
            receiverSourceName,
            receiverSourcePath,
            item.receiverDocumentationSourceLine,
          ) +
          `</div>`,
      );
    }
    const title = item.receiverDisplayName
      ? `About ${item.receiverDisplayName}`
      : "About receiver";
    chunks.push(
      `<div class="afield-suggest-receiver-doc">` +
        `<div class="afield-suggest-receiver-title">${escapeHtml(title)}</div>` +
        receiverChunks.join("") +
        `</div>`,
    );
  }
  return chunks.join("");
};

// A navigated card is shaped like a completion item so the same renderer applies.
// Its category header + `[Symbol]` links are already baked into `documentation` by
// the backend, so navigation recurses without any extra client logic.
const renderNavCard = (card) =>
  renderDocumentation({
    documentation: card.documentation || "",
    documentationSourceName: card.sourceName || "",
    documentationSourcePath: card.sourcePath || "",
    documentationSourceLine: card.sourceLine || -1,
  });

const prepareRuntimeDocumentation = (root) => {
  if (!root) return;
  root
    .querySelectorAll(
      ".afield-suggest-runtime-doc details.afield-symbol-context-disclosure",
    )
    .forEach((details) => {
      details.open = true;
    });
};

const highlightCodeBlocks = (root) => {
  if (
    !root ||
    !window.hljs ||
    typeof window.hljs.highlightElement !== "function"
  )
    return;
  root.querySelectorAll("pre code").forEach((block) => {
    try {
      window.hljs.highlightElement(block);
    } catch (_) {}
  });
};

class FieldSuggestController {
  constructor(editor, monaco, options = {}) {
    this.editor = editor;
    this.monaco = monaco;
    this.languages = options.languages || DEFAULT_LANGUAGES;
    this.items = [];
    this.rawItems = [];
    this.baseItemsLoaded = false;
    this.aiItemsLoaded = false;
    this.activeIndex = -1;
    this.requestGeneration = 0;
    this.refreshTimer = null;
    this.lastQuery = "";
    this.openPosition = null;
    this.navStack = [];
    this.navIndex = -1;
    this.suppressOpenUntil = 0;
    this.root = this.createRoot();
    this.install();
  }

  createRoot() {
    const root = document.createElement("div");
    root.className = "afield-suggest";
    root.setAttribute("role", "listbox");
    root.style.display = "none";
    root.innerHTML = `
            <div class="afield-suggest-list" role="presentation"></div>
            <div class="afield-suggest-doc" role="region" aria-label="Completion documentation"></div>
        `;
    document.body.appendChild(root);
    root.addEventListener("mousedown", (event) => {
      event.preventDefault();
    });
    root.addEventListener("click", (event) => {
      const row = event.target.closest(".afield-suggest-row");
      if (row) {
        this.setActive(Number(row.dataset.index || 0));
        this.accept();
      }
    });
    const doc = root.querySelector(".afield-suggest-doc");
    doc.addEventListener("click", (event) => {
      const navButton = event.target.closest("[data-nav]");
      if (navButton) {
        event.preventDefault();
        const dir = navButton.getAttribute("data-nav");
        if (dir === "back") this.navBack();
        else if (dir === "forward") this.navForward();
        return;
      }
      const link = event.target.closest("a");
      if (!link) return;
      const href = link.getAttribute("href") || "";
      if (href.startsWith(FIELD_SOURCE_VIEW_SCHEME)) {
        event.preventDefault();
        this.viewSource(href, link);
        return;
      }
      if (href.startsWith(FIELD_SOURCE_SCHEME)) {
        event.preventDefault();
        this.navigateToSource(href, link.textContent || "");
        return;
      }
      if (/^https?:\/\//.test(href)) {
        link.setAttribute("target", "_blank");
        link.setAttribute("rel", "noopener noreferrer");
      }
    });
    return root;
  }

  install() {
    const { editor, monaco } = this;
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Space, () => {
      this.open({ manual: true });
    });
    editor.addAction({
      id: "afield.triggerFieldSuggest",
      label: "Field: Trigger Autocomplete",
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Space],
      run: () => this.open({ manual: true }),
    });
    editor.onDidChangeModelContent((event) => {
      if (!this.isSupported()) {
        this.hide();
        return;
      }
      if (this.handleCallTransition(event)) return;
      if (this.isVisible()) this.scheduleOpen({ manual: false });
    });
    editor.onDidChangeCursorPosition(() => {
      if (this.isVisible()) this.scheduleOpen({ manual: false });
    });
    editor.onDidScrollChange(() => this.reposition());
    editor.onDidLayoutChange(() => this.reposition());
    editor.onKeyDown((event) => this.onKeyDown(event));
    editor.onDidBlurEditorWidget(() => {
      setTimeout(() => {
        if (!this.root.matches(":hover")) this.hide();
      }, 120);
    });
  }

  isSupported() {
    const model = this.editor.getModel && this.editor.getModel();
    return !!(model && this.languages.has(getLanguageId(model)));
  }

  isVisible() {
    return this.root.style.display !== "none";
  }

  scheduleOpen(options = {}) {
    if (!options.manual && Date.now() < this.suppressOpenUntil) return;
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => this.open(options), REFRESH_DELAY_MS);
  }

  async open(options = {}) {
    if (!this.isSupported()) return false;
    if (!options.manual && Date.now() < this.suppressOpenUntil) return false;
    const editor = this.editor;
    const model = editor.getModel();
    const position = editor.getPosition();
    if (!model || !position) return false;

    try {
      editor.trigger("afield-suggest", "hideSuggestWidget", {});
    } catch (_) {}

    const word = model.getWordUntilPosition(position);
    this.lastQuery = word.word || "";
    this.memberContext = isMemberCompletionPosition(model, position);
    this.openPosition = position;
    this.requestGeneration += 1;
    const generation = this.requestGeneration;
    this.renderLoading();
    this.reposition();

    const languageId = getLanguageId(model);
    const target = getCompletionTarget(editor, model);
    if (!target.uid) {
      this.renderItems([]);
      return false;
    }

    const quickItems = this.quickItems(model, position, languageId);
    const localItems = this.localItems(model, position, languageId);
    this.rawItems = this.dedupeItems([...quickItems, ...localItems]);
    this.baseItemsLoaded = false;
    this.aiItemsLoaded = languageId !== "kotlin";
    if (localItems.length > 0) this.renderItems(this.rawItems);

    this.remoteItems(model, position, target, languageId, generation)
      .then((remoteItems) => {
        this.mergeLateItems(remoteItems, generation, { base: true });
      })
      .catch(() => {
        this.mergeLateItems([], generation, { base: true });
      });
    if (languageId === "kotlin") {
      this.aiItems(model, position, target, languageId, generation)
        .then((aiItems) => {
          this.mergeLateItems(aiItems, generation, { ai: true });
        })
        .catch(() => {
          this.mergeLateItems([], generation, { ai: true });
        });
    }
    return true;
  }

  localItems(model, position, languageId) {
    if (typeof window.fieldLanguageCompletionItems !== "function") return [];
    const target = getCompletionTarget(this.editor, model);
    const completionEditor = target.embedded
      ? target.embedded.editor
      : this.editor;
    let result = [];
    try {
      result = window.fieldLanguageCompletionItems(
        completionEditor,
        model,
        position,
        { triggerKind: "field-suggest" },
      );
    } catch (_) {
      return [];
    }
    if (!Array.isArray(result) || result.length === 0) return [];
    return result.map((item, index) =>
      normalizeLocalSuggestion(
        this.monaco,
        item,
        index,
        model,
        position,
        languageId,
      ),
    );
  }

  quickItems(model, position, languageId) {
    if (languageId !== "kotlin") return [];
    if (isMemberCompletionPosition(model, position)) return [];
    const range = quickSuggestionRange(this.monaco, model, position);
    return KOTLIN_QUICK_COMPLETIONS.map((item, index) => ({
      id: `quick-${index}`,
      source: "quick",
      label: item.label,
      displayText: item.label,
      insertText: item.insertText,
      detail: "",
      documentation: "",
      icon: item.icon,
      category: item.category,
      range,
      prefix: "",
      languageId,
      raw: item,
      index,
    }));
  }

  remoteItems(model, position, target, languageId, generation) {
    const payload = completionPayloadFor(this.editor, model, position, target);
    return requestWithReply("complete", payload, "complete-me").then(
      (completion) =>
        normalizeRpcCompletion(
          this.monaco,
          completion || {},
          model,
          languageId,
          0,
          "rpc",
        ),
    );
  }

  aiItems(model, position, target, languageId, generation) {
    const payload = completionPayloadFor(this.editor, model, position, target);
    return requestWithReply(
      "complete-kotlin-ai",
      payload,
      "complete-kotlin-ai",
    ).then((completion) =>
      normalizeRpcCompletion(
        this.monaco,
        completion || {},
        model,
        languageId,
        0,
        "ai",
      ),
    );
  }

  mergeLateItems(items, generation, flags = {}) {
    if (generation !== this.requestGeneration) return;
    if (flags.base) this.baseItemsLoaded = true;
    if (flags.ai) this.aiItemsLoaded = true;
    const active = this.items[this.activeIndex];
    this.rawItems = this.dedupeItems(
      this.replaceQuickItems([...this.rawItems, ...(items || [])]),
    );
    this.renderItems(this.rawItems, {
      preserveActiveKey: active ? completionKeyFor(active) : null,
    });
  }

  replaceQuickItems(items) {
    const hasCompilerItems = items.some(
      (item) => item.source === "rpc" || item.source === "local",
    );
    if (!hasCompilerItems) return items;
    return items.filter((item) => item.source !== "quick");
  }

  renderLoading() {
    this.root.style.display = "grid";
    this.root.querySelector(".afield-suggest-list").innerHTML = loadingMarkup();
    this.root.querySelector(".afield-suggest-doc").innerHTML =
      `<div class="afield-suggest-doc-empty"></div>`;
  }

  renderItems(items, options = {}) {
    this.items = this.filterAndLimit(items);
    this.root.style.display = "grid";
    if (this.items.length === 0) {
      this.activeIndex = -1;
      // Only block the popup on the fast base (type-based) completions. AI
      // completions are slow (a model round-trip) and stream in later via
      // mergeLateItems — never make the whole list wait on them, or Kotlin
      // autocomplete shows a spinner for seconds while every other language
      // renders immediately.
      const waiting = !this.baseItemsLoaded;
      this.root.querySelector(".afield-suggest-list").innerHTML = waiting
        ? loadingMarkup()
        : `<div class="afield-suggest-empty">No completions</div>`;
      this.root.querySelector(".afield-suggest-doc").innerHTML = waiting
        ? `<div class="afield-suggest-doc-empty"></div>`
        : `<div class="afield-suggest-doc-empty"></div>`;
      this.reposition();
      return;
    }
    if (options.preserveActiveKey) {
      const preserved = this.items.findIndex(
        (item) => completionKeyFor(item) === options.preserveActiveKey,
      );
      this.activeIndex = preserved >= 0 ? preserved : 0;
    } else {
      this.activeIndex = 0;
    }
    this.renderList();
    this.renderDoc();
    this.reposition();
  }

  dedupeItems(items) {
    const seen = new Set();
    const merged = [];
    for (const item of items) {
      const key = completionKeyFor(item);
      if (seen.has(key)) continue;
      seen.add(key);
      merged.push(item);
    }
    return merged;
  }

  filterAndLimit(items) {
    const query = String(this.lastQuery || "").toLowerCase();
    return orderItemsForGroupedDisplay(rankItems(
      this.dedupeItems(items),
      query,
      readRecencyStore(),
      this.memberContext,
    ).slice(0, MAX_VISIBLE_ITEMS));
  }

  groupedItems() {
    const groups = [];
    const byTitle = new Map();
    for (let index = 0; index < this.items.length; index++) {
      const item = this.items[index];
      const title = titleForCategory(item.category);
      let group = byTitle.get(title);
      if (!group) {
        group = { title, entries: [] };
        byTitle.set(title, group);
        groups.push(group);
      }
      group.entries.push({ item, index });
    }
    return groups;
  }

  renderList() {
    const html = [];
    for (const group of this.groupedItems()) {
      html.push(
        `<div class="afield-suggest-section">${escapeHtml(group.title)}</div>`,
      );
      for (const { item, index } of group.entries) {
        const active = index === this.activeIndex ? " is-active" : "";
        const recent =
          item.recencyScore > 0
            ? `<span class="afield-suggest-recent">recent</span>`
            : "";
        html.push(`
                    <div class="afield-suggest-row${active}" role="option" aria-selected="${index === this.activeIndex}" data-index="${index}">
                        <div class="afield-suggest-icon" data-icon="${escapeHtml(item.icon)}">${escapeHtml(String(item.icon || "").slice(0, 2))}</div>
                        <div class="afield-suggest-main">
                            <div class="afield-suggest-label">${renderSyntaxLabel(item.displayText, this.lastQuery)}</div>
                            <div class="afield-suggest-detail">${escapeHtml(item.detail)}${recent}</div>
                        </div>
                    </div>
                `);
      }
    }
    const list = this.root.querySelector(".afield-suggest-list");
    list.innerHTML = html.join("");
    const active = list.querySelector(".afield-suggest-row.is-active");
    if (active) this.scrollActiveRowIntoView(list, active);
  }

  scrollActiveRowIntoView(list, active) {
    active.scrollIntoView({ block: "nearest" });
    const header = list.querySelector(".afield-suggest-section");
    const headerHeight = header ? header.getBoundingClientRect().height : 0;
    const listRect = list.getBoundingClientRect();
    const activeRect = active.getBoundingClientRect();
    const topLimit = listRect.top + headerHeight;
    const bottomLimit = listRect.bottom;

    if (activeRect.top < topLimit) {
      list.scrollTop -= topLimit - activeRect.top;
    } else if (activeRect.bottom > bottomLimit) {
      list.scrollTop += activeRect.bottom - bottomLimit;
    }
  }

  renderDoc() {
    const doc = this.root.querySelector(".afield-suggest-doc");
    // Navigated into a source card: render it (recursively linkified) plus the
    // floating back/forward control, and stop — no completion lazy-resolve here.
    if (this.navIndex >= 0 && this.navStack[this.navIndex]) {
      doc.innerHTML = renderNavCard(this.navStack[this.navIndex]) + this.renderNavControl();
      highlightCodeBlocks(doc);
      doc.scrollTop = 0;
      return;
    }
    const item = this.items[this.activeIndex];
    if (!item) {
      doc.innerHTML = `<div class="afield-suggest-doc-empty">No documentation available</div>`;
      return;
    }
    doc.innerHTML = renderDocumentation(item) + this.renderNavControl();
    prepareRuntimeDocumentation(doc);
    highlightCodeBlocks(doc);
    this.resolveDocumentation(item);
  }

  baseNavTitle() {
    const item = this.items[this.activeIndex];
    if (!item) return "";
    return String(item.text || item.insertText || item.displayText || item.label || "")
      .split("(")[0]
      .trim();
  }

  renderNavControl() {
    if (this.navStack.length === 0) return "";
    const canBack = this.navIndex >= 0;
    const canForward = this.navIndex < this.navStack.length - 1;
    const trail = [this.baseNavTitle()]
      .concat(this.navStack.slice(0, this.navIndex + 1).map((card) => card.title))
      .filter(Boolean)
      .map((crumb) => escapeHtml(crumb));
    return (
      `<div class="afield-suggest-nav" role="navigation">` +
      `<button type="button" class="afield-suggest-nav-btn" data-nav="back"` +
      `${canBack ? "" : " disabled"} title="Back" aria-label="Back">‹</button>` +
      `<button type="button" class="afield-suggest-nav-btn" data-nav="forward"` +
      `${canForward ? "" : " disabled"} title="Forward" aria-label="Forward">›</button>` +
      `<span class="afield-suggest-nav-crumbs">${trail.join(" › ")}</span>` +
      `</div>`
    );
  }

  navigateToSource(href, label) {
    let params;
    try {
      params = new URLSearchParams(
        href.slice(FIELD_SOURCE_SCHEME.length).replace(/^\?/, ""),
      );
    } catch (_) {
      return;
    }
    const payload = {
      symbol: params.get("name") || label || "",
      path: params.get("path") || "",
      line: Number(params.get("line") || -1),
    };
    requestWithReply("resolve-source", payload, "resolve-source").then((res) => {
      if (!res || !res.found) return;
      // Drop any forward history, then push the new card and show it.
      this.navStack = this.navStack.slice(0, this.navIndex + 1);
      this.navStack.push({
        title: res.title || payload.symbol || label,
        documentation: res.documentation || "",
        category: res.category || "",
        sourceName: res.sourceName || "",
        sourcePath: res.sourcePath || "",
        sourceLine: res.sourceLine || -1,
      });
      this.navIndex = this.navStack.length - 1;
      this.renderDoc();
    });
  }

  // Stage B: fetch and toggle the actual source region (GET /api/source) inline
  // beneath the source footer, syntax-highlighted like the signature block.
  viewSource(href, anchor) {
    const doc = this.root.querySelector(".afield-suggest-doc");
    if (!doc) return;
    const existing = doc.querySelector(".afield-suggest-source-code");
    if (existing) {
      existing.remove();
      return;
    }
    let params;
    try {
      params = new URLSearchParams(
        href.slice(FIELD_SOURCE_VIEW_SCHEME.length).replace(/^\?/, ""),
      );
    } catch (_) {
      return;
    }
    const query = new URLSearchParams({
      path: params.get("path") || "",
      line: params.get("line") || "-1",
      context: "60",
    });
    fetch(`/api/source?${query.toString()}`)
      .then((r) => (r.ok ? r.json() : null))
      .then((res) => {
        if (!res || !res.found || !res.code) return;
        // Guard against a stale response after the panel re-rendered.
        if (doc.querySelector(".afield-suggest-source-code")) return;
        const lang = res.language || "kotlin";
        const block = document.createElement("div");
        block.className = "afield-suggest-source-code";
        block.innerHTML =
          `<div class="afield-suggest-source-code-head"><code>${escapeHtml(res.path)}:${res.startLine}</code></div>` +
          `<pre><code class="language-${escapeHtml(lang)}">${escapeHtml(res.code)}</code></pre>`;
        const footer = anchor.closest(".afield-suggest-doc-source");
        if (footer && footer.parentNode) footer.parentNode.insertBefore(block, footer.nextSibling);
        else doc.appendChild(block);
        highlightCodeBlocks(doc);
        block.scrollIntoView({ block: "nearest" });
      })
      .catch(() => {});
  }

  navBack() {
    if (this.navIndex < 0) return;
    this.navIndex -= 1;
    this.renderDoc();
  }

  navForward() {
    if (this.navIndex >= this.navStack.length - 1) return;
    this.navIndex += 1;
    this.renderDoc();
  }

  resetNav() {
    this.navStack = [];
    this.navIndex = -1;
  }

  resolveDocumentation(item) {
    if (!item || item.source !== "rpc" || item.resolved || item.resolving)
      return;
    if (item.documentation && !item.generateDocumentation) {
      item.resolved = true;
      return;
    }
    const model = this.editor.getModel();
    const position = this.editor.getPosition();
    const target = getCompletionTarget(this.editor, model);
    if (!model || !position || !target.uid) return;
    item.resolving = true;
    const payload = {
      uid: target.uid,
      property: target.property,
      label: item.displayText || item.label || "",
      insertText: item.insertText || "",
      detail: item.detail || "",
      displayText: item.displayText || item.label || "",
      existingDocumentation: item.documentation || "",
      allCode: model.getValue(),
      line: position.lineNumber,
      column: position.column,
      position: model.getOffsetAt({
        lineNumber: position.lineNumber,
        column: position.column,
      }),
      documentationSourceName: item.documentationSourceName || "",
      documentationSourcePath: item.documentationSourcePath || "",
      documentationSourceLine: item.documentationSourceLine || -1,
    };
    requestWithReply("resolve-completion", payload, "resolve-completion").then(
      (response) => {
        item.resolving = false;
        item.resolved = true;
        if (response && response.documentation) {
          item.documentation = item.documentation
            ? `${item.documentation}\n\n---\n\n${response.documentation}`
            : response.documentation;
        }
        if (this.items[this.activeIndex] === item) this.renderDoc();
      },
    );
  }

  setActive(index) {
    if (this.items.length === 0) return;
    const clamped = Math.max(0, Math.min(this.items.length - 1, index));
    // Switching to a different completion resets any in-progress source browse.
    if (clamped !== this.activeIndex) this.resetNav();
    this.activeIndex = clamped;
    this.renderList();
    this.renderDoc();
  }

  move(delta) {
    if (!this.isVisible()) return false;
    this.setActive(this.activeIndex + delta);
    return true;
  }

  handleCallTransition(event) {
    if (!this.isVisible()) return false;
    const typedOpenParen =
      event &&
      Array.isArray(event.changes) &&
      event.changes.some((change) => String(change.text || "").includes("("));
    if (!typedOpenParen) return false;

    const model = this.editor.getModel();
    const position = this.editor.getPosition();
    const item = this.signatureItemForCall(model, position);
    this.suppressOpenUntil = Date.now() + 2500;
    this.hide();
    if (item) this.showSignatureBadge(item);
    return true;
  }

  signatureItemForCall(model, position) {
    const name = callNameBeforeCursor(model, position);
    const pool = [...(this.items || []), ...(this.rawItems || [])];
    const exact = name
      ? pool.find(
          (item) => completionNameFor(item) === name && itemLooksCallable(item),
        )
      : null;
    return (
      exact ||
      this.items[this.activeIndex] ||
      pool.find(itemLooksCallable) ||
      null
    );
  }

  showSignatureBadge(item) {
    const signature = itemSignatureFor(item);
    if (!signature || typeof window.setEditorContextBadge !== "function")
      return false;
    this.editor._afieldSuggestSignatureUntil = Date.now() + 2500;
    window.setEditorContextBadge(this.editor, {
      key: `field-suggest-call:${signature}`,
      isLiveValue: false,
      html: `
                <div class="afield-call-signature-context">
                    <code>${renderKotlinSignatureHtml(signature)}</code>
                </div>
            `,
    });
    return true;
  }

  accept() {
    const item = this.items[this.activeIndex];
    if (!item) return false;
    const model = this.editor.getModel();
    if (!model) return false;
    if (item.prefix) {
      model.applyEdits([
        {
          range: new this.monaco.Range(1, 1, 1, 1),
          text: `${String(item.prefix).trim()}\n`,
          forceMoveMarkers: true,
        },
      ]);
    }
    const range =
      item.range ||
      (() => {
        const position = this.editor.getPosition();
        const word = model.getWordUntilPosition(position);
        return new this.monaco.Range(
          position.lineNumber,
          word.startColumn,
          position.lineNumber,
          word.endColumn,
        );
      })();
    this.editor.executeEdits("afield-suggest", [
      {
        range,
        text: item.insertText || item.displayText || "",
        forceMoveMarkers: true,
      },
    ]);
    touchRecency(item);
    const shouldShowSignature =
      itemLooksCallable(item) &&
      String(item.insertText || item.displayText || "").includes("(");
    this.suppressOpenUntil = Date.now() + 2500;
    this.hide();
    if (shouldShowSignature) {
      requestAnimationFrame(() => this.showSignatureBadge(item));
    }
    this.editor.focus();
    return true;
  }

  onKeyDown(event) {
    if (
      this.isSupported() &&
      !this.isVisible() &&
      event.keyCode === this.monaco.KeyCode.Space &&
      (event.ctrlKey || event.metaKey)
    ) {
      event.preventDefault();
      event.stopPropagation();
      this.open({ manual: true });
      return;
    }
    if (!this.isVisible()) return;
    const code = event.keyCode;
    const monaco = this.monaco;
    const consume = () => {
      event.preventDefault();
      event.stopPropagation();
    };
    if (code === monaco.KeyCode.DownArrow) {
      consume();
      this.move(1);
    } else if (code === monaco.KeyCode.UpArrow) {
      consume();
      this.move(-1);
    } else if (code === monaco.KeyCode.PageDown) {
      consume();
      this.move(8);
    } else if (code === monaco.KeyCode.PageUp) {
      consume();
      this.move(-8);
    } else if (code === monaco.KeyCode.Enter || code === monaco.KeyCode.Tab) {
      consume();
      this.accept();
    } else if (code === monaco.KeyCode.Escape) {
      consume();
      this.hide();
    }
  }

  reposition() {
    if (!this.isVisible()) return;
    const editorNode = this.editor.getDomNode && this.editor.getDomNode();
    const position = this.editor.getPosition && this.editor.getPosition();
    if (!editorNode || !position || !this.editor.getScrolledVisiblePosition)
      return;
    const cursor = this.editor.getScrolledVisiblePosition(position);
    const editorRect = editorNode.getBoundingClientRect();
    const left = editorRect.left + (cursor ? cursor.left : 0);
    const top = editorRect.top + (cursor ? cursor.top + cursor.height : 20);
    const width = Math.min(
      760,
      Math.max(520, Math.floor(window.innerWidth * 0.48)),
    );
    const maxLeft = Math.max(8, window.innerWidth - width - 12);
    const nextLeft = Math.max(8, Math.min(left, maxLeft));
    const below = top + 360 < window.innerHeight;
    const nextTop = below ? top + 4 : Math.max(8, top - 364);
    this.root.style.width = `${width}px`;
    this.root.style.left = `${nextLeft}px`;
    this.root.style.top = `${nextTop}px`;
  }

  hide(options = {}) {
    clearTimeout(this.refreshTimer);
    if (options.invalidate !== false) this.requestGeneration += 1;
    this.root.style.display = "none";
    this.items = [];
    this.rawItems = [];
    this.baseItemsLoaded = false;
    this.aiItemsLoaded = false;
    this.activeIndex = -1;
    this.resetNav();
  }
}

export const installFieldSuggest = (editor, monaco, options = {}) => {
  if (!editor || !monaco) return null;
  if (editor._afieldSuggestController) return editor._afieldSuggestController;
  const controller = new FieldSuggestController(editor, monaco, options);
  editor._afieldSuggestController = controller;
  window.FieldSuggest = window.FieldSuggest || {};
  window.FieldSuggest.open = (targetEditor = editor) => {
    const installed = targetEditor && targetEditor._afieldSuggestController;
    return installed
      ? installed.open({ manual: true })
      : controller.open({ manual: true });
  };
  window.FieldSuggest.isEnabled = true;
  return controller;
};

window.installFieldSuggest = installFieldSuggest;
window.FieldSuggestConfig = {
  enabled: true,
  languages: DEFAULT_LANGUAGES,
};
