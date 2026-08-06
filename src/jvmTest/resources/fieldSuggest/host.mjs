// The minimum browser + Monaco host that the real `fieldSuggest.js` needs in order to run under
// plain `node`. Deliberately small and deliberately dumb: everything interesting about the
// completion popup lives in fieldSuggest.js, and this file exists only so that file can be the code
// under test rather than a Kotlin transcription of it.
//
// The one rule that keeps the harness honest: **assertions read the HTML string the popup actually
// wrote**, never this file's model of it. The DOM below stores each `innerHTML` verbatim and only
// indexes it well enough for fieldSuggest.js's own `querySelector` calls to find the list and doc
// panes. So a bug in the tag-soup index can make a test fail, but it cannot make one pass.
//
// What is NOT modelled, on purpose (see dev_docs/completion_popup_cover.md):
//  - layout: `getBoundingClientRect` is a constant, so `reposition`/scroll math is exercised for
//    "does not throw", not for correctness.
//  - the element tree is FLAT: `innerHTML` indexes every tag it sees as a direct child. Descendant
//    selectors therefore match on their last simple selector only. Every selector fieldSuggest.js
//    uses is a class (or tag.class) lookup, which survives that.
//  - `./afield.mjs` — the compiled Kotlin/JS bundle — is stubbed (see afield.mjs here), so the
//    markdown renderer and the real transport are out of scope.

const CLASS_ATTR = /\sclass\s*=\s*"([^"]*)"/;
const DATA_ATTR = /\sdata-([\w-]+)\s*=\s*"([^"]*)"/g;
const TAG = /<([a-zA-Z][\w-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;

/** Tag-soup index of an innerHTML string: every opening tag becomes one child element. */
const parseChildren = (html) => {
  const children = [];
  TAG.lastIndex = 0;
  let match;
  while ((match = TAG.exec(html)) !== null) {
    const [whole, tag, attrs] = match;
    if (whole.startsWith("</")) continue;
    const element = new Element(tag);
    const classes = CLASS_ATTR.exec(attrs);
    if (classes) element.className = classes[1];
    DATA_ATTR.lastIndex = 0;
    let data;
    while ((data = DATA_ATTR.exec(attrs)) !== null) {
      element.dataset[data[1].replace(/-(\w)/g, (_, c) => c.toUpperCase())] = data[2];
    }
    // Immediate text: whatever sits between this tag and the next one. Enough for a section header.
    const after = html.slice(match.index + whole.length);
    const next = after.indexOf("<");
    element.textContent = (next < 0 ? after : after.slice(0, next)).trim();
    element.disabled = /\sdisabled(?=[\s>])/.test(attrs);
    children.push(element);
  }
  return children;
};

const simpleSelector = (selector) => {
  const last = String(selector).trim().split(/\s+/).pop() || "";
  const tag = /^[a-zA-Z][\w-]*/.exec(last);
  return {
    tag: tag ? tag[0].toUpperCase() : null,
    classes: last
      .slice(tag ? tag[0].length : 0)
      .split(".")
      .filter(Boolean),
  };
};

class Element {
  constructor(tag) {
    this.tagName = String(tag).toUpperCase();
    this.classList = new Set();
    this.style = {};
    this.dataset = {};
    this.attributes = {};
    this.textContent = "";
    this.scrollTop = 0;
    this.disabled = false;
    this._children = [];
    this._parent = null;
    this._html = "";
  }

  get className() {
    return [...this.classList].join(" ");
  }

  set className(value) {
    this.classList = new Set(String(value).split(/\s+/).filter(Boolean));
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === "class") this.className = value;
  }

  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name)
      ? this.attributes[name]
      : null;
  }

  get innerHTML() {
    return this._html;
  }

  set innerHTML(html) {
    this._html = String(html);
    this._children = parseChildren(this._html);
  }

  appendChild(child) {
    child._parent = this;
    this._children.push(child);
    return child;
  }

  insertBefore(child, reference) {
    child._parent = this;
    const at = this._children.indexOf(reference);
    if (at < 0) this._children.push(child);
    else this._children.splice(at, 0, child);
    return child;
  }

  addEventListener() {}
  removeEventListener() {}
  /** Real, because `viewSource` toggles its inline source block by removing it. */
  remove() {
    const siblings = this._parent && this._parent._children;
    if (!siblings) return;
    const at = siblings.indexOf(this);
    if (at >= 0) siblings.splice(at, 1);
    this._parent = null;
  }
  select() {}
  focus() {}
  scrollIntoView() {}
  matches(selector) {
    const { tag, classes } = simpleSelector(selector);
    if (selector === ":hover") return false;
    if (tag && tag !== this.tagName) return false;
    return classes.every((c) => this.classList.has(c));
  }
  closest() {
    return null;
  }

  querySelectorAll(selector) {
    const found = [];
    const walk = (element) => {
      for (const child of element._children) {
        if (child.matches(selector)) found.push(child);
        walk(child);
      }
    };
    walk(this);
    return found;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  getBoundingClientRect() {
    return { top: 0, left: 0, bottom: 360, right: 520, width: 520, height: 360 };
  }
}

/** A localStorage that behaves like the browser's for the two keys fieldSuggest.js touches. */
class MemoryStorage {
  constructor() {
    this.map = new Map();
  }
  getItem(key) {
    return this.map.has(key) ? this.map.get(key) : null;
  }
  setItem(key, value) {
    this.map.set(key, String(value));
  }
  removeItem(key) {
    this.map.delete(key);
  }
  clear() {
    this.map.clear();
  }
}

/**
 * A Monaco text model over one string. The four methods fieldSuggest.js leans on —
 * `getWordUntilPosition`, `getValueInRange`, `getOffsetAt`, `getPositionAt` — follow Monaco's
 * 1-based line/column contract, because the completion request payload and every range in the popup
 * are built out of them.
 */
export const makeModel = (text, languageId = "kotlin") => {
  let value = String(text);
  const lines = () => value.split("\n");
  const model = {
    _edits: [],
    getLanguageId: () => languageId,
    getValue: () => value,
    getVersionId: () => 1,
    getLineContent: (lineNumber) => lines()[lineNumber - 1] ?? "",
    getLineCount: () => lines().length,
    getOffsetAt: ({ lineNumber, column }) => {
      const all = lines();
      let offset = 0;
      for (let i = 0; i < lineNumber - 1; i++) offset += all[i].length + 1;
      return offset + column - 1;
    },
    getPositionAt: (offset) => {
      const before = value.slice(0, Math.max(0, offset)).split("\n");
      return { lineNumber: before.length, column: before[before.length - 1].length + 1 };
    },
    getWordUntilPosition: ({ lineNumber, column }) => {
      const line = model.getLineContent(lineNumber);
      const before = line.slice(0, column - 1);
      const word = /[A-Za-z0-9_]*$/.exec(before)[0];
      return { word, startColumn: column - word.length, endColumn: column };
    },
    getValueInRange: ({ startLineNumber, startColumn, endLineNumber, endColumn }) => {
      const all = lines();
      if (startLineNumber === endLineNumber) {
        return (all[startLineNumber - 1] ?? "").slice(startColumn - 1, endColumn - 1);
      }
      const parts = [(all[startLineNumber - 1] ?? "").slice(startColumn - 1)];
      for (let i = startLineNumber; i < endLineNumber - 1; i++) parts.push(all[i] ?? "");
      parts.push((all[endLineNumber - 1] ?? "").slice(0, endColumn - 1));
      return parts.join("\n");
    },
    applyEdits: (edits) => {
      model._edits.push(...edits);
    },
    setValue: (next) => {
      value = String(next);
    },
  };
  return model;
};

/** The slice of `monaco` the popup uses: one Range class and the two key enums. */
export const makeMonaco = () => ({
  Range: class Range {
    constructor(startLineNumber, startColumn, endLineNumber, endColumn) {
      this.startLineNumber = startLineNumber;
      this.startColumn = startColumn;
      this.endLineNumber = endLineNumber;
      this.endColumn = endColumn;
    }
  },
  KeyMod: { CtrlCmd: 2048, WinCtrl: 256, Shift: 1024, Alt: 512 },
  KeyCode: {
    Space: 10,
    Period: 84,
    DownArrow: 18,
    UpArrow: 16,
    PageDown: 12,
    PageUp: 11,
    Enter: 3,
    Tab: 2,
    Escape: 9,
  },
  languages: { CompletionItemKind: { Function: 1, Class: 5, Variable: 4, Snippet: 27 } },
  MarkerSeverity: { Warning: 4, Error: 8 },
  editor: { setModelMarkers() {} },
});

/** The slice of a Monaco editor the popup uses, plus recorders for what it did to us. */
export const makeEditor = (model, position) => {
  const node = new Element("div");
  const editor = {
    _actions: [],
    _commands: [],
    _edits: [],
    _triggers: [],
    _handlers: {},
    _position: position,
    getModel: () => model,
    getPosition: () => editor._position,
    setPosition: (next) => {
      editor._position = next;
    },
    getDomNode: () => node,
    getScrolledVisiblePosition: () => ({ left: 120, top: 40, height: 18 }),
    hasTextFocus: () => true,
    focus: () => {},
    trigger: (source, handlerId, payload) => editor._triggers.push({ source, handlerId, payload }),
    addCommand: (chord, handler) => editor._commands.push({ chord, handler }),
    addAction: (action) => editor._actions.push(action),
    executeEdits: (source, edits) => {
      editor._edits.push({ source, edits });
      // Apply for real, so a follow-on completion in the same case sees the inserted text.
      for (const edit of edits) {
        const start = model.getOffsetAt({
          lineNumber: edit.range.startLineNumber,
          column: edit.range.startColumn,
        });
        const end = model.getOffsetAt({
          lineNumber: edit.range.endLineNumber,
          column: edit.range.endColumn,
        });
        const value = model.getValue();
        model.setValue(value.slice(0, start) + edit.text + value.slice(end));
      }
      return true;
    },
  };
  const on = (name) => (handler) => {
    (editor._handlers[name] = editor._handlers[name] || []).push(handler);
    return { dispose() {} };
  };
  for (const name of [
    "onDidChangeModelContent",
    "onDidChangeCursorPosition",
    "onDidScrollChange",
    "onDidLayoutChange",
    "onKeyDown",
    "onDidBlurEditorWidget",
    "onDidChangeModel",
  ]) {
    editor[name] = on(name);
  }
  return editor;
};

/**
 * Install the globals fieldSuggest.js reads at module-evaluation time and at run time. Must be
 * called BEFORE the dynamic `import()` of fieldSuggest.js, which is why the probe imports it.
 */
export const installHost = ({ uid = "box-under-test", property = "code" } = {}) => {
  const body = new Element("body");
  const document = {
    body,
    createElement: (tag) => new Element(tag),
    querySelectorAll: (selector) => body.querySelectorAll(selector),
    querySelector: (selector) => body.querySelector(selector),
    execCommand: () => false,
  };
  const window = {
    document,
    innerWidth: 1600,
    innerHeight: 1000,
    localStorage: new MemoryStorage(),
    currentSelectedCode: [{ uid }],
    currentSelectedProperty: property,
    setEditorContextBadge: (editor, badge) => {
      window._badges.push({ editor, badge });
    },
    _badges: [],
    notify: () => {},
  };
  globalThis.window = window;
  globalThis.document = document;
  // node ships a getter-only `navigator`; the popup only reads `navigator.clipboard`, so leave it.
  globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0);
  globalThis.cancelAnimationFrame = (id) => clearTimeout(id);
  // `viewSource` fetches GET /api/source. Route it to a table the probe fills in, and parse the body
  // with JSON.parse — i.e. as strictly as a browser does. `curl` and python will happily accept a
  // body containing bare NaN/Infinity where a browser's `response.json()` throws, and that exact gap
  // once hid a "semantic search returns nothing" bug in this repo, so the harness must be the strict
  // one.
  window._httpRoutes = new Map();
  window._httpRequests = [];
  globalThis.fetch = (url) => {
    window._httpRequests.push(String(url));
    const path = String(url).split("?")[0];
    const route = window._httpRoutes.get(path);
    if (!route) return Promise.reject(new Error(`no route for ${url} in the popup harness`));
    const body = typeof route === "function" ? route(String(url)) : route;
    return Promise.resolve({
      ok: true,
      status: 200,
      text: () => Promise.resolve(body),
      json: () => Promise.resolve(JSON.parse(body)),
    });
  };
  return window;
};

/** Let every already-resolved promise and 0ms timer settle — the popup merges items in `.then`. */
export const settle = async (rounds = 6) => {
  for (let i = 0; i < rounds; i++) await new Promise((resolve) => setTimeout(resolve, 0));
};

export { Element };
