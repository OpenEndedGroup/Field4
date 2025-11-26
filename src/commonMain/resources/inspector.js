const STYLE_ID = "tree-inspector-styles";
const STYLE_TEXT = `
.tree-inspector {
  --color-scheme: dark;
  --mono-font: "iosevka web", "Iosevka", "SF Mono", Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  --indent-step: 14px;
  --bg: #1e1e1e;
  --text: #e0e0e0;
  --header-bg: #202020;
  --header-border: #303030;
  --panel-border: #303030;
  --hover: #262626;
  --divider: #303030;
  --summary: #888888;
  --caret: #888888;
  --input-bg: #252526;
  --input-border: #3c3c3c;
  --input-text: #f3f3f3;
  --focus: #4f9dff;
  --preview-bg: #181818;
  --preview-border: #000000;
  --preview-text: #b0b0b0;
  --row-outline: #3f86ff;
  color-scheme: var(--color-scheme);
}

.tree-inspector.theme-light {
  --color-scheme: light;
  --bg: #ffffff;
  --text: #222222;
  --header-bg: #dddddd;
  --header-border: #e0e0e0;
  --panel-border: #d4d4d4;
  --hover: #f2f2f2;
  --divider: #e0e0e0;
  --summary: #666666;
  --caret: #555555;
  --input-bg: #ffffff;
  --input-border: #d4d4d4;
  --input-text: #222222;
  --focus: #4f9dff;
  --preview-bg: #ffffff;
  --preview-border: #d4d4d4;
  --preview-text: #333333;
  --row-outline: #4f9dff;
}

.tree-inspector, .tree-inspector * {
  box-sizing: border-box;
}

.tree-inspector {
  background-color: var(--bg);
  color: var(--text);
  font-family: var(--mono-font);
  font-size: 12px;
}

.tree-inspector .app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.tree-inspector .app-header {
  padding: 4px 8px;
  background: var(--header-bg);
  border-bottom: 1px solid var(--header-border);
  display: flex;
  align-items: center;
  gap: 6px;
}

.tree-inspector .app-header h1 {
  font-size: 12px;
  margin: 0;
  font-weight: 500;
  letter-spacing: 0.03em;
  text-transform: uppercase;
  color: var(--summary);
}

.tree-inspector .app-header button {
  margin-left: auto;
  background: transparent;
  border: 1px solid var(--panel-border);
  border-radius: 4px;
  padding: 2px 8px;
  color: var(--text);
  font-size: 11px;
  cursor: pointer;
}

.tree-inspector .app-header button:hover {
  background: var(--hover);
}

.tree-inspector .app-main {
  display: flex;
  flex: 1;
  min-height: 0;
}

.tree-inspector .tree-container {
  flex: 3;
  padding: 0;
  overflow: auto;
  border-right: 1px solid var(--panel-border);
}

.tree-inspector .json-preview {
  flex: 2;
  padding: 3px 5px 5px 5px;
  overflow: auto;
  background: var(--preview-bg);
  font-family: var(--mono-font);
  font-size: 11px;
  white-space: pre;
  border-left: 1px solid var(--preview-border);
  color: var(--preview-text);
  margin: 0;
  display: none;
}

/* TREE VIEW */

.tree-inspector .json-tree {
  list-style: none;
  margin: 0;
  padding-left: 0;
  font-size: 12px;
}

.tree-inspector .tree-item {
  margin: 1px 0;
}

.tree-inspector .tree-row {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) 1px minmax(0, 2fr);
  align-items: center;
  column-gap: 6px;
  padding: 1px 3px;
  border-radius: 4px;
  cursor: default;
}

.tree-inspector .tree-row:hover {
  background-color: var(--hover);
}

.tree-inspector .tree-row:focus-within {
  // outline: 1px solid var(--row-outline);
  // outline-offset: 1px;
}

.tree-inspector .tree-row.no-outline,
.tree-inspector .tree-row.no-outline:focus-within {
  outline: none;
}

.tree-inspector .label-cell {
  display: grid;
  grid-template-columns: 14px minmax(120px, 1fr);
  align-items: center;
  gap: 2px;
  position: relative;
  left: calc(var(--depth) * var(--indent-step));
  min-width: 0;
}

.tree-inspector .caret {
  display: inline-block;
  width: 14px;
  text-align: center;
  user-select: none;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  font-family: "iosevka web", "Iosevka", "SF Mono", Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-weight: 600;
  color: #222222;
}

.tree-inspector .caret.placeholder {
  visibility: hidden;
  cursor: default;
}

.tree-inspector .node-key {
  flex: 0 0 auto;
  color: var(--text);
  margin-right: 0;
  min-width: 0;
}

.tree-inspector .node-key.array-index {
  color: #6c78b8;
}

.tree-inspector .value-divider {
  grid-column: 2;
  align-self: stretch;
  width: 1px;
  background: var(--divider);
}
.tree-inspector .tree-row.non-leaf .value-divider {
  background: transparent;
}

.tree-inspector .node-summary {
  grid-column: 3;
  font-size: 10px;
  color: var(--summary);
  font-style: italic;
  justify-self: end;
  opacity: 0.7;
  display: none;
}

.tree-inspector .node-value {
  grid-column: 3;
  display: flex;
  align-items: center;
  gap: 4px;
  justify-self: start;
  z-index: 1;
}

.tree-inspector .node-value.readonly .readonly-text {
  white-space: normal;
}

.tree-inspector .node-unit {
  font-size: 10px;
  color: var(--summary);
}

.tree-inspector .node-value.mixed input::placeholder {
  color: var(--summary);
  font-style: italic;
}

.tree-inspector .node-value input[type="text"],
.tree-inspector .node-value input[type="number"],
.tree-inspector .node-value select {
  background-color: #252526;
  border: 1px solid #3c3c3c;
  color: #f3f3f3;
  border-radius: 3px;
  padding: 1px 3px;
  font-size: 11px;
  min-width: 80px;
  max-width: 180px;
  font-family: var(--mono-font);
  background-color: var(--input-bg);
  border-color: var(--input-border);
  color: var(--input-text);
}

.tree-inspector .node-value input[type="text"]:focus,
.tree-inspector .node-value input[type="number"]:focus,
.tree-inspector .node-value select:focus {
  outline: none;
  border-color: var(--focus);
  box-shadow: 0 0 0 1px rgba(79, 157, 255, 0.4);
}

.tree-inspector .node-value input[type="checkbox"] {
  cursor: pointer;
}

.tree-inspector .node-value .bool-label {
  font-size: 11px;
  color: var(--summary);
  user-select: none;
}

.tree-inspector .tree-children {
  list-style: none;
  margin: 0;
  padding-left: 0;
  border-left: none;
}

.tree-inspector .tree-children.hidden {
  display: none;
}
`;

const MISSING = Symbol("missing");

function injectStyles() {
        if (typeof document === "undefined") return;
        if (document.getElementById(STYLE_ID)) return;
        var style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = STYLE_TEXT;
        document.head.appendChild(style);
}

function JsonTreeEditor(container, data, typeDefs, options) {
        this.container = container;
        this.roots = Array.isArray(data) ? data : [data];
        this.typeDefs = Array.isArray(typeDefs) ? typeDefs : [];
        this.options = options || {};
        this.openNodes = new Set(); // stores path strings like "user/address/street"
        this._render();
}

JsonTreeEditor.prototype.getData = function () {
        return this.roots;
};

// Replace the root data objects list. Open/close state is preserved.
JsonTreeEditor.prototype.setData = function (newData) {
        this.roots = Array.isArray(newData) ? newData : [newData];
        this._render();
};

// Replace the type descriptor list.
JsonTreeEditor.prototype.setTypeDefs = function (typeDefs) {
        this.typeDefs = Array.isArray(typeDefs) ? typeDefs : [];
        this._render();
};

// Re-render using the current data object (e.g. if modified externally).
JsonTreeEditor.prototype.refresh = function () {
        this._render();
};

JsonTreeEditor.prototype._isObject = function (v) {
        return v && typeof v === "object" && !Array.isArray(v);
};

JsonTreeEditor.prototype._matchesPattern = function (actualPathStr, patternStr) {
        if (!patternStr) return false;
        var actualSegs = actualPathStr.split("/");
        var patternSegs = patternStr.split("/");
        if (actualSegs.length !== patternSegs.length) {
                return false;
        }
        for (var i = 0; i < patternSegs.length; i++) {
                var p = patternSegs[i];
                if (p === "*") continue;
                if (p !== actualSegs[i]) return false;
        }
        return true;
};

JsonTreeEditor.prototype._getTypeConfigForPath = function (pathSegments, value) {
        var pathStr = pathSegments.join("/");
        var result = null;
        // Last matching descriptor wins; you can rely on ordering for specificity.
        for (var i = 0; i < this.typeDefs.length; i++) {
                var spec = this.typeDefs[i];
                if (!spec || !spec.path) continue;
                if (this._matchesPattern(pathStr, spec.path)) {
                        if (!result) {
                                result = {};
                        }
                        for (var key in spec) {
                                if (Object.prototype.hasOwnProperty.call(spec, key)) {
                                        result[key] = spec[key];
                                }
                        }
                }
        }
        return result;
};

JsonTreeEditor.prototype._inferType = function (value, typeConfig) {
        if (typeConfig && typeConfig.type) {
                return typeConfig.type;
        }
        var t = typeof value;
        if (t === "string") return "string";
        if (t === "number") return "number";
        if (t === "boolean") return "boolean";
        // If null or something odd, treat as string so it's at least editable.
        return "string";
};

JsonTreeEditor.prototype._collectValues = function (pathSegments) {
        var values = [];
        var presentValues = [];
        var presentCount = 0;
        for (var i = 0; i < this.roots.length; i++) {
                var res = this._getWithGetter(this.roots[i], pathSegments);
                if (res.has) {
                        values.push(res.value);
                        presentValues.push(res.value);
                        presentCount++;
                } else {
                        values.push(MISSING);
                }
        }
        return {
                values: values,
                presentValues: presentValues,
                presentCount: presentCount
        };
};

JsonTreeEditor.prototype._valuesAreMixed = function (arr) {
        if (!arr.length) return false;
        var first = arr[0];
        for (var i = 1; i < arr.length; i++) {
                if (arr[i] !== first) return true;
        }
        return false;
};

JsonTreeEditor.prototype._getPriorityForPath = function (pathSegments) {
        var cfg = this._getTypeConfigForPath(pathSegments, undefined);
        if (cfg && typeof cfg.priority === "number") {
                return cfg.priority;
        }
        return 0;
};

JsonTreeEditor.prototype._nodeMeta = function (pathSegments) {
        var collected = this._collectValues(pathSegments);
        var present = collected.presentValues;
        var containerKinds = new Set();
        var self = this;
        for (var i = 0; i < present.length; i++) {
                var v = present[i];
                if (Array.isArray(v)) {
                        containerKinds.add("array");
                } else if (this._isObject(v)) {
                        containerKinds.add("object");
                } else {
                        containerKinds.add("primitive");
                }
        }

        var kind = "leaf";
        if (containerKinds.size === 1 && containerKinds.has("object")) {
                kind = "object";
        } else if (containerKinds.size === 1 && containerKinds.has("array")) {
                kind = "array";
        }

        var unionKeys = [];
        var summaryDetail = "";
        if (kind === "object") {
                var keySet = {};
                for (var j = 0; j < present.length; j++) {
                        var obj = present[j];
                        if (!this._isObject(obj)) continue;
                        var keys = Object.keys(obj);
                        for (var k = 0; k < keys.length; k++) {
                                keySet[keys[k]] = true;
                        }
                }
                unionKeys = Object.keys(keySet);
                unionKeys.sort(function (a, b) {
                        var pa = self._getPriorityForPath(pathSegments.concat(a));
                        var pb = self._getPriorityForPath(pathSegments.concat(b));
                        if (pa !== pb) return pb - pa; // higher priority first
                        return a.localeCompare(b);
                });
                summaryDetail = unionKeys.length ? " - " + unionKeys.length + " keys" : "";
        } else if (kind === "array") {
                var maxLen = 0;
                var lens = [];
                for (var j2 = 0; j2 < present.length; j2++) {
                        var arr = present[j2];
                        if (!Array.isArray(arr)) continue;
                        maxLen = Math.max(maxLen, arr.length);
                        lens.push(arr.length);
                }
                for (var idx = 0; idx < maxLen; idx++) {
                        unionKeys.push(String(idx));
                }
                unionKeys.sort(function (a, b) {
                        var pa = self._getPriorityForPath(pathSegments.concat(a));
                        var pb = self._getPriorityForPath(pathSegments.concat(b));
                        if (pa !== pb) return pb - pa; // higher priority first
                        var na = Number(a);
                        var nb = Number(b);
                        var aIsNum = !isNaN(na) && String(na) === a;
                        var bIsNum = !isNaN(nb) && String(nb) === b;
                        if (aIsNum && bIsNum) return na - nb;
                        return a.localeCompare(b);
                });
                var lengthMixed = this._valuesAreMixed(lens);
                summaryDetail = "(" + maxLen + ")" + (lengthMixed ? " - mixed" : "");
        }

        var isMixedLeaf = kind === "leaf" ? this._valuesAreMixed(present) : false;

        return {
                kind: kind, // "leaf" | "object" | "array"
                unionKeys: unionKeys,
                isLeafMixed: isMixedLeaf,
                exampleValue: present.length ? present[0] : undefined,
                summaryDetail: summaryDetail,
                presentCount: collected.presentCount,
                presentValues: present
        };
};

JsonTreeEditor.prototype._getAt = function (root, pathSegments) {
        var obj = root;
        if (!pathSegments.length) {
                return { has: true, value: obj };
        }
        for (var i = 0; i < pathSegments.length; i++) {
                if (obj == null) return { has: false, value: undefined };
                var seg = pathSegments[i];
                if (Array.isArray(obj)) {
                        var idx = Number(seg);
                        if (!Number.isInteger(idx) || idx < 0 || idx >= obj.length) {
                                return { has: false, value: undefined };
                        }
                        obj = obj[idx];
                        continue;
                }
                if (!Object.prototype.hasOwnProperty.call(obj, seg)) {
                        return { has: false, value: undefined };
                }
                obj = obj[seg];
        }
        return { has: true, value: obj };
};

JsonTreeEditor.prototype._getWithGetter = function (root, pathSegments) {
        var base = this._getAt(root, pathSegments);
        if (base.has) return base;
        if (!pathSegments.length) return base;
        var parent = this._getAt(root, pathSegments.slice(0, -1));
        if (!parent.has || parent.value == null) return base;
        var typeConfig = this._getTypeConfigForPath(pathSegments, undefined);
        if (typeConfig && typeof typeConfig.getter === "function") {
                try {
                        var lastKey = pathSegments[pathSegments.length - 1];
                        var val = typeConfig.getter(parent.value, lastKey, pathSegments.slice(), root);
                        return { has: true, value: val, viaGetter: true };
                } catch (e) {
                        return base;
                }
        }
        return base;
};

JsonTreeEditor.prototype._setValueAtPath = function (root, pathSegments, newValue) {
        if (!pathSegments.length) {
                return; // ignore replacing whole root in multi-root mode
        }
        var obj = root;
        for (var i = 0; i < pathSegments.length - 1; i++) {
                var seg = pathSegments[i];
                if (obj == null) return;
                obj = obj[seg];
        }
        var lastSeg = pathSegments[pathSegments.length - 1];
        if (obj != null) {
                obj[lastSeg] = newValue;
        }
};

JsonTreeEditor.prototype._setValueAcrossRoots = function (pathSegments, newValue) {
        var oldSample = null;
        for (var i = 0; i < this.roots.length; i++) {
                var res = this._getAt(this.roots[i], pathSegments);
                if (!res.has) continue;
                if (oldSample === null) oldSample = res.value;
                this._setValueAtPath(this.roots[i], pathSegments, newValue);
        }
        return oldSample;
};

JsonTreeEditor.prototype._createEditorForLeaf = function (leafMeta, typeConfig, onCommit, parentRow, pathStr) {
        var value = leafMeta.exampleValue;
        var editorType = this._inferType(value, typeConfig);
        var container = document.createElement("span");
        var isReadOnly = typeConfig && typeConfig.readOnly;
        container.className = "node-value" + (leafMeta.isLeafMixed ? " mixed" : "") + (isReadOnly ? " readonly" : "");

        function addOutlineGuards(el) {
                if (!el || !parentRow) return;
                el.addEventListener("focus", function () {
                        parentRow.classList.add("no-outline");
                });
                el.addEventListener("blur", function () {
                        parentRow.classList.remove("no-outline");
                });
        }

        function formatNumber(val) {
                if (typeof val !== "number" || !isFinite(val)) return val;
                var fixed = val.toFixed(3);
                return fixed.replace(/\.?0+$/, "");
        }

        if (editorType === "boolean") {
                var checkbox = document.createElement("input");
                checkbox.type = "checkbox";
                checkbox.checked = !!value;
                checkbox.disabled = !!isReadOnly;
                addOutlineGuards(checkbox);
                if (leafMeta.isLeafMixed) {
                        checkbox.indeterminate = true;
                }
                checkbox.addEventListener("change", function () {
                        onCommit(checkbox.checked);
                        checkbox.indeterminate = false;
                });
                var label = document.createElement("span");
                label.className = "bool-label";
                label.textContent = leafMeta.isLeafMixed ? "mixed" : (checkbox.checked ? "true" : "false");
                checkbox.addEventListener("change", function () {
                        label.textContent = checkbox.checked ? "true" : "false";
                });
                if (!isReadOnly) {
                        container.appendChild(checkbox);
                }
                container.appendChild(label);
                return container;
        }

        if (editorType === "choice") {
                var select = document.createElement("select");
                select.disabled = !!isReadOnly;
                addOutlineGuards(select);
                var opts = (typeConfig && typeConfig.options) || [];
                var hasCurrent = false;
                if (leafMeta.isLeafMixed) {
                        var optMixed = document.createElement("option");
                        optMixed.value = "__mixed__";
                        optMixed.textContent = "[mixed]";
                        optMixed.disabled = true;
                        optMixed.selected = true;
                        select.appendChild(optMixed);
                }
                for (var i = 0; i < opts.length; i++) {
                        var opt = document.createElement("option");
                        opt.value = String(opts[i]);
                        opt.textContent = String(opts[i]);
                        if (String(value) === String(opts[i]) && !leafMeta.isLeafMixed) {
                                opt.selected = true;
                                hasCurrent = true;
                        }
                        select.appendChild(opt);
                }
                if (!hasCurrent && value != null && !leafMeta.isLeafMixed) {
                        var optCustom = document.createElement("option");
                        optCustom.value = String(value);
                        optCustom.textContent = String(value) + " (custom)";
                        optCustom.selected = true;
                        select.appendChild(optCustom);
                }
                select.addEventListener("change", function () {
                        if (select.value === "__mixed__") return;
                        onCommit(select.value);
                });
                container.appendChild(select);
                return container;
        }

        if (editorType === "number") {
                if (isReadOnly) {
                        var nLabel = document.createElement("span");
                        nLabel.className = "readonly-text";
                        nLabel.textContent = value == null ? "" : String(formatNumber(value));
                        container.appendChild(nLabel);
                        if (typeConfig && (typeConfig.units || typeConfig.unitsHtml)) {
                                var unit = document.createElement("span");
                                unit.className = "node-unit";
                                if (typeConfig.unitsHtml) {
                                        unit.innerHTML = typeConfig.unitsHtml;
                                } else {
                                        unit.textContent = typeConfig.units;
                                }
                                container.appendChild(unit);
                        }
                        return container;
                } else {
                        var numInput = document.createElement("input");
                        numInput.type = "number";
                        numInput.disabled = !!isReadOnly;
                        if (value != null && value !== "" && !leafMeta.isLeafMixed) {
                                numInput.value = String(formatNumber(value));
                        }
                        if (leafMeta.isLeafMixed) {
                                numInput.placeholder = "[mixed]";
                        }
                        if (typeConfig) {
                                if (typeof typeConfig.min === "number") {
                                        numInput.min = String(typeConfig.min);
                                }
                                if (typeof typeConfig.max === "number") {
                                        numInput.max = String(typeConfig.max);
                                }
                                if (typeof typeConfig.step === "number") {
                                        numInput.step = String(typeConfig.step);
                                }
                        }
                        numInput.addEventListener("change", function () {
                                var v = numInput.value;
                                var parsed = v === "" ? null : Number(v);
                                if (v !== "" && !isNaN(parsed)) {
                                        var rounded = typeof parsed === "number" ? Number(formatNumber(parsed)) : parsed;
                                        onCommit(rounded);
                                } else if (v === "") {
                                        // If you want empty to mean null, we keep that here.
                                        onCommit(null);
                                }
                        });
                        addOutlineGuards(numInput);
                        container.appendChild(numInput);
                        if (typeConfig && (typeConfig.units || typeConfig.unitsHtml)) {
                                var unit = document.createElement("span");
                                unit.className = "node-unit";
                                if (typeConfig.unitsHtml) {
                                        unit.innerHTML = typeConfig.unitsHtml;
                                } else {
                                        unit.textContent = typeConfig.units;
                                }
                                container.appendChild(unit);
                        }
                        return container;
                }
        }

        if (editorType === "action") {
                var actionBtn = document.createElement("button");
                actionBtn.type = "button";
                actionBtn.textContent = (typeConfig && typeConfig.actionTitle) || (value != null ? String(value) : "Action");
                actionBtn.addEventListener("click", function () {
                        onCommit(null);
                });
                addOutlineGuards(actionBtn);
                container.appendChild(actionBtn);
                return container;
        }

        // default: string
        if (isReadOnly) {
                var label = document.createElement("span");
                label.className = "readonly-text";
                label.textContent = value == null ? "" : String(value);
                container.appendChild(label);
                return container;
        } else {
                var textInput = document.createElement("input");
                textInput.type = "text";
                if (leafMeta.isLeafMixed) {
                        textInput.placeholder = "[mixed]";
                } else {
                        textInput.value = value == null ? "" : String(value);
                }
                textInput.addEventListener("change", function () {
                        onCommit(textInput.value);
                });
                addOutlineGuards(textInput);
                container.appendChild(textInput);
                return container;
        }
};

JsonTreeEditor.prototype._makeRowClickHandler = function (pathStr) {
        var self = this;
        return function (event) {
                // Don't toggle when interacting with inputs/selects.
                var tag = event.target.tagName;
                if (tag === "INPUT" || tag === "SELECT" || tag === "TEXTAREA" || tag === "OPTION") {
                        return;
                }
                if (self.openNodes.has(pathStr)) {
                        self.openNodes.delete(pathStr);
                } else {
                        self.openNodes.add(pathStr);
                }
                self._render();
        };
};

JsonTreeEditor.prototype._buildEntry = function (parentListEl, label, pathSegments, isArrayIndex) {
        var meta = this._nodeMeta(pathSegments);
        var isLeaf = meta.kind === "leaf";
        var pathStr = pathSegments.join("/");

        var li = document.createElement("li");
        li.className = "tree-item";

        var row = document.createElement("div");
        row.className = "tree-row" + (isLeaf ? "" : " non-leaf");

        var labelCell = document.createElement("div");
        labelCell.className = "label-cell";
        var depth = Math.max(0, pathSegments.length - 1);
        labelCell.style.setProperty("--depth", depth);

        // Caret or placeholder
        var caret = document.createElement("span");
        caret.className = "caret";
        if (isLeaf) {
                caret.classList.add("placeholder");
                caret.textContent = "▸";
        } else {
                caret.textContent = this.openNodes.has(pathStr) ? "▾" : "▸";
                row.addEventListener("click", this._makeRowClickHandler(pathStr));
        }
        labelCell.appendChild(caret);

        var keySpan = document.createElement("span");
        keySpan.className = "node-key";
        if (isArrayIndex) {
                keySpan.classList.add("array-index");
        }
        keySpan.textContent = label;
        labelCell.appendChild(keySpan);
        row.appendChild(labelCell);

        var divider = document.createElement("span");
        divider.className = "value-divider";
        row.appendChild(divider);

        if (isLeaf) {
                var typeConfig = this._getTypeConfigForPath(pathSegments, meta.exampleValue);
                var self = this;
                var editor = this._createEditorForLeaf(meta, typeConfig, function (newValue) {
                        var oldValue = meta.exampleValue;
                        if (typeConfig && typeConfig.type === "action") {
                                if (typeConfig && typeof typeConfig.onAction === "function") {
                                        try {
                                                typeConfig.onAction({
                                                        path: pathStr,
                                                        root: self.roots
                                                });
                                        } catch (e) {
                                                console.error("onAction callback error for path", pathStr, e);
                                        }
                                }
                                if (typeof self.options.onActionRoot === "function") {
                                        self.options.onActionRoot({ path: pathStr, root: self.roots });
                                }
                                return;
                        }

                        self._setValueAcrossRoots(pathSegments, newValue);

                        if (typeConfig && typeof typeConfig.onChange === "function") {
                                try {
                                        typeConfig.onChange({
                                                path: pathStr,
                                                newValue: newValue,
                                                oldValue: oldValue,
                                                root: self.roots,
                                                setValue: function (val) {
                                                        self._setValueAcrossRoots(pathSegments, val);
                                                }
                                        });
                                } catch (e) {
                                        console.error("onChange callback error for path", pathStr, e);
                                }
                        }

                        if (typeof self.options.onChangeRoot === "function") {
                                self.options.onChangeRoot(self.roots);
                        }
                }, row, pathStr);
                row.appendChild(editor);
        } else {
                var summary = document.createElement("span");
                summary.className = "node-summary";
                if (meta.kind === "array") {
                        summary.textContent = "[Array" + meta.summaryDetail + "]";
                } else {
                        summary.textContent = "{Object" + meta.summaryDetail + "}";
                }
                row.appendChild(summary);
        }

        li.appendChild(row);

        if (!isLeaf) {
                var childList = document.createElement("ul");
                childList.className = "tree-children";
                if (!this.openNodes.has(pathStr)) {
                        childList.classList.add("hidden");
                }

                for (var i = 0; i < meta.unionKeys.length; i++) {
                        var childKey = meta.unionKeys[i];
                        var labelText = meta.kind === "array" ? "[" + childKey + "]" : childKey;
                        this._buildEntry(
                            childList,
                            labelText,
                            pathSegments.concat(childKey),
                            meta.kind === "array"
                        );
                }

                li.appendChild(childList);
        }

        parentListEl.appendChild(li);
};

JsonTreeEditor.prototype._render = function () {
        while (this.container.firstChild) {
                this.container.removeChild(this.container.firstChild);
        }

        var rootList = document.createElement("ul");
        rootList.className = "json-tree";

        var rootMeta = this._nodeMeta([]);
        if (rootMeta.kind === "object") {
                for (var j = 0; j < rootMeta.unionKeys.length; j++) {
                        var k = rootMeta.unionKeys[j];
                        this._buildEntry(
                            rootList,
                            k,
                            [k],
                            false
                        );
                }
        } else if (rootMeta.kind === "array") {
                for (var i = 0; i < rootMeta.unionKeys.length; i++) {
                        var idxLabel = rootMeta.unionKeys[i];
                        this._buildEntry(
                            rootList,
                            "[" + idxLabel + "]",
                            [idxLabel],
                            true
                        );
                }
        } else {
                // Root is a primitive; show a single leaf.
                this._buildEntry(rootList, "(no selection)", [], false);
        }

        this.container.appendChild(rootList);

        if (typeof this.options.onRender === "function") {
                this.options.onRender(this.roots);
        }
};

JsonTreeEditor.create = function (container, data, typeDefs, options) {
        return new JsonTreeEditor(container, data, typeDefs, options);
};

// --- Utility: pattern matching with wildcards and mapping to an output path ---

function matchPatternWithWildcards(actualSegs, patternSegs) {
        if (actualSegs.length !== patternSegs.length) {
                return { matched: false, wildcards: [] };
        }
        var wildcards = [];
        for (var i = 0; i < patternSegs.length; i++) {
                var p = patternSegs[i];
                if (p === "*") {
                        wildcards.push(actualSegs[i]);
                        continue;
                }
                if (p !== actualSegs[i]) {
                        return { matched: false, wildcards: [] };
                }
        }
        return { matched: true, wildcards: wildcards };
}

function buildOutputPathSegments(outputPath, actualSegs, wildcards) {
        var outSegs = outputPath ? outputPath.split("/") : actualSegs.slice();
        var w = 0;
        var result = [];
        for (var i = 0; i < outSegs.length; i++) {
                if (outSegs[i] === "*") {
                        result.push(typeof wildcards[w] !== "undefined" ? wildcards[w] : "*");
                        w++;
                } else {
                        result.push(outSegs[i]);
                }
        }
        return result;
}

function setValueAtPath(root, pathSegments, newValue) {
        if (!pathSegments.length) return;
        var obj = root;
        for (var i = 0; i < pathSegments.length - 1; i++) {
                var seg = pathSegments[i];
                if (obj == null) return;
                obj = obj[seg];
        }
        var lastSeg = pathSegments[pathSegments.length - 1];
        if (obj != null) {
                obj[lastSeg] = newValue;
        }
}

function ensureContainer(parent, seg, nextSegIsNumber) {
        var targetKey = nextSegIsNumber ? Number(seg) : seg;
        if (Array.isArray(parent)) {
                var idx = Number(seg);
                if (!Number.isInteger(idx)) {
                        idx = seg;
                }
                if (typeof parent[idx] === "undefined") {
                        parent[idx] = nextSegIsNumber ? [] : {};
                }
                return parent[idx];
        } else {
                if (!Object.prototype.hasOwnProperty.call(parent, targetKey)) {
                        parent[targetKey] = nextSegIsNumber ? [] : {};
                }
                return parent[targetKey];
        }
}

function setIntermediateValue(targetRoot, pathSegments, value) {
        if (!pathSegments.length) return;
        var obj = targetRoot;
        for (var i = 0; i < pathSegments.length; i++) {
                var seg = pathSegments[i];
                var isLast = i === pathSegments.length - 1;
                var nextSeg = pathSegments[i + 1];
                var nextIsNumber = /^\d+$/.test(String(nextSeg));
                if (isLast) {
                        if (Array.isArray(obj)) {
                                var idx = Number(seg);
                                if (Number.isInteger(idx)) {
                                        obj[idx] = value;
                                } else {
                                        obj[seg] = value;
                                }
                        } else {
                                obj[seg] = value;
                        }
                } else {
                        obj = ensureContainer(obj, seg, nextIsNumber);
                }
        }
}

/**
 * extractMappedData
 * Walks an arbitrary object graph, matches typeDefs by path, builds a new object
 * using typeDef.outputPath (or the original path) and returns setters that write
 * back to the source graph. Circular references are ignored.
 *
 * @param {Object|Array<Object>} sourceRoot
 * @param {Array} typeDefs
 * @returns {Object} { data, typeDefs: mappedTypeDefs, setters }
 */
export function extractMappedData(sourceRoot, typeDefs) {
        var roots = Array.isArray(sourceRoot) ? sourceRoot : [sourceRoot];
        var dataList = [];
        var setterBuckets = {};
        var specs = Array.isArray(typeDefs) ? typeDefs : [];
        var matchedRoots = {};
        var matchedMeta = {};

        function addSetter(pathStr, fn) {
                if (!setterBuckets[pathStr]) setterBuckets[pathStr] = [];
                setterBuckets[pathStr].push(fn);
        }

        function addMatchedRoot(pathStr, rootRef) {
                if (!matchedRoots[pathStr]) matchedRoots[pathStr] = [];
                matchedRoots[pathStr].push(rootRef);
        }

        function addMatchedMeta(pathStr, entry) {
                if (!matchedMeta[pathStr]) matchedMeta[pathStr] = [];
                matchedMeta[pathStr].push(entry);
        }

        function getNodeAndParent(root, pathSegments) {
                var obj = root;
                var parent = null;
                var key = null;
                for (var i = 0; i < pathSegments.length; i++) {
                        parent = obj;
                        key = pathSegments[i];
                        if (parent == null) {
                                return { target: undefined, parent: null, key: null };
                        }
                        obj = parent[key];
                }
                return { target: obj, parent: parent, key: key };
        }

        function buildForRoot(currentRoot) {
                var currentData = {};
                var visited = new WeakSet();

                function walk(node, pathSegments) {
                        var shouldVisitChildren = true;
                        if (node && typeof node === "object") {
                                if (visited.has(node)) {
                                        shouldVisitChildren = false;
                                } else {
                                        visited.add(node);
                                }
                        }

                        // Match against all specs
                        for (var s = 0; s < specs.length; s++) {
                                var spec = specs[s];
                                if (!spec || !spec.path) continue;
                                var patternSegs = String(spec.path).split("/");
                                var match = matchPatternWithWildcards(pathSegments, patternSegs);
                                if (!match.matched) continue;

                                var outSegs = buildOutputPathSegments(spec.outputPath, pathSegments, match.wildcards);
                                var outPathStr = outSegs.join("/");
                                setIntermediateValue(currentData, outSegs, node);
                                addSetter(outPathStr, function (segmentsCopy, rootRef) {
                                        return function (newValue) {
                                                setValueAtPath(rootRef, segmentsCopy, newValue);
                                        };
                                }(pathSegments.slice(), currentRoot));
                                addMatchedRoot(outPathStr, currentRoot);
                                var nodeInfo = getNodeAndParent(currentRoot, pathSegments);
                                addMatchedMeta(outPathStr, {
                                        root: currentRoot,
                                        target: nodeInfo.target,
                                        parent: nodeInfo.parent,
                                        key: nodeInfo.key,
                                        pathSegments: pathSegments.slice()
                                });
                        }

                        // Attempt to materialize getter-based fields that may not exist on the object.
                        for (var sg = 0; sg < specs.length; sg++) {
                                var gSpec = specs[sg];
                                if (!gSpec || !gSpec.getter || !gSpec.path) continue;
                                var gPatternSegs = String(gSpec.path).split("/");
                                if (gPatternSegs.length !== pathSegments.length + 1) continue;
                                var lastSeg = gPatternSegs[gPatternSegs.length - 1];
                                if (lastSeg === "*") continue; // skip wildcard-only materialization
                                var prefixPattern = gPatternSegs.slice(0, -1);
                                var prefixMatch = matchPatternWithWildcards(pathSegments, prefixPattern);
                                if (!prefixMatch.matched) continue;
                                var fullPath = pathSegments.concat(lastSeg);
                                var existing = getNodeAndParent(currentRoot, fullPath);
                                if (existing && existing.parent && typeof existing.parent[lastSeg] !== "undefined") {
                                        continue; // property exists; normal path will handle it
                                }
                                try {
                                        var valFromGetter = gSpec.getter(existing.parent || node, lastSeg, fullPath.slice(), currentRoot);
                                        var outSegsGetter = buildOutputPathSegments(gSpec.outputPath, fullPath, prefixMatch.wildcards.concat([lastSeg]));
                                        var outPathGetterStr = outSegsGetter.join("/");
                                        setIntermediateValue(currentData, outSegsGetter, valFromGetter);
                                        addSetter(outPathGetterStr, function (segmentsCopy, rootRef) {
                                                return function (newValue) {
                                                        setValueAtPath(rootRef, segmentsCopy, newValue);
                                                };
                                        }(fullPath.slice(), currentRoot));
                                        addMatchedRoot(outPathGetterStr, currentRoot);
                                        addMatchedMeta(outPathGetterStr, {
                                                root: currentRoot,
                                                target: valFromGetter,
                                                parent: existing.parent,
                                                key: lastSeg,
                                                pathSegments: fullPath.slice(),
                                                viaGetter: true
                                        });
                                } catch (e) {
                                        // ignore getter errors
                                }
                        }

                        if (!shouldVisitChildren) return;
                        if (Array.isArray(node)) {
                                for (var i = 0; i < node.length; i++) {
                                        walk(node[i], pathSegments.concat(String(i)));
                                }
                        } else if (node && typeof node === "object") {
                                var keys = Object.keys(node);
                                for (var j = 0; j < keys.length; j++) {
                                        var k = keys[j];
                                        walk(node[k], pathSegments.concat(k));
                                }
                        }
                }

                walk(currentRoot, []);
                return currentData;
        }

        for (var r = 0; r < roots.length; r++) {
                dataList.push(buildForRoot(roots[r]));
        }

        var setterMap = {};
        Object.keys(setterBuckets).forEach(function (key) {
                var fns = setterBuckets[key];
                setterMap[key] = function (val) {
                        for (var i = 0; i < fns.length; i++) {
                                fns[i](val);
                        }
                };
        });

        // Remap typeDefs to point at outputPath for the visualizer, and wire setters.
        var mappedTypeDefs = specs.map(function (spec) {
                var clone = {};
                for (var prop in spec) {
                        if (Object.prototype.hasOwnProperty.call(spec, prop) && prop !== "path") {
                                clone[prop] = spec[prop];
                        }
                }
                clone.path = spec.outputPath || spec.path;
                var userOnChange = spec.onChange;
                clone.onChange = function (info) {
                        var setter = setterMap[info.path];
                        if (setter) setter(info.newValue);
                        if (typeof userOnChange === "function") {
                                userOnChange(Object.assign({}, info, {
                                        setOriginal: setter,
                                        sourceRoots: roots,
                                        matchedRoots: matchedRoots[info.path] || [],
                                        matchedMeta: matchedMeta[info.path] || []
                                }));
                        }
                };
                return clone;
        });

        var dataResult = roots.length === 1 ? dataList[0] : dataList;
        return { data: dataResult, typeDefs: mappedTypeDefs, setters: setterMap };
}

export function initTreeInspector(container, data, typeDefs, options) {
        if (!container) {
                throw new Error("Container element is required for tree inspector");
        }
        injectStyles();

        var resolvedOptions = options || {};
        container.classList.add("tree-inspector");
        container.innerHTML = "";

        var shell = document.createElement("div");
        shell.className = "app-shell";

        var header = document.createElement("div");
        header.className = "app-header";
        var refreshBtn = document.createElement("button");
        refreshBtn.type = "button";
        refreshBtn.className = "refresh-btn";
        refreshBtn.textContent = "Refresh from object";
        // header.appendChild(refreshBtn);

        var main = document.createElement("div");
        main.className = "app-main";

        var treeContainer = document.createElement("div");
        treeContainer.className = "tree-container";
        var preview = document.createElement("pre");
        preview.className = "json-preview";

        main.appendChild(treeContainer);
        main.appendChild(preview);

        // shell.appendChild(header);
        shell.appendChild(main);

        container.appendChild(shell);

        function updatePreview(rootList) {
                try {
                        preview.textContent = JSON.stringify(rootList, null, 2);
                } catch (e) {
                        preview.textContent = "[unserializable object]";
                }
        }

        var userOnChange = resolvedOptions.onChangeRoot;
        var userOnRender = resolvedOptions.onRender;
        var userOnAction = resolvedOptions.onActionRoot;
        var theme = resolvedOptions.theme === "light" ? "light" : "dark";

        function applyTheme(next) {
                container.classList.remove("theme-dark", "theme-light");
                container.classList.add(next === "light" ? "theme-light" : "theme-dark");
        }

        applyTheme(theme);

        var mergedOptions = Object.assign({}, resolvedOptions, {
                onChangeRoot: function (roots) {
                        updatePreview(roots);
                        if (typeof userOnChange === "function") {
                                userOnChange(roots);
                        }
                },
                onRender: function (roots) {
                        updatePreview(roots);
                        if (typeof userOnRender === "function") {
                                userOnRender(roots);
                        }
                },
                onActionRoot: function (info) {
                        if (typeof userOnAction === "function") {
                                userOnAction(info);
                        }
                }
        });

        var editor = JsonTreeEditor.create(treeContainer, data, typeDefs, mergedOptions);

        return {
                editor: editor,
                elements: {
                        shell: shell,
                        treeContainer: treeContainer,
                        preview: preview,
                        refreshButton: refreshBtn,
                        header: header
                },
                setTheme: applyTheme
        };
}
