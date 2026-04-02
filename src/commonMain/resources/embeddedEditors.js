/**
 * Embedded Monaco editors for output-area disclosures.
 *
 * Each embedded editor is bound to a specific (uid, property) of a box
 * and lives inside a disclosure panel in that box's outputArea.
 *
 * The main editor (window.globalEditor) remains the selection-tracking
 * singleton; embedded editors are fixed to one box+property and managed
 * here via a simple registry.
 */

import { send, sendWithReply, monacoHandleOutput } from "./afield.mjs";

// ── Registry ──────────────────────────────────────────────────────────

/** @type {Map<string, EmbeddedEditorEntry>} */
const registry = new Map();

/**
 * @typedef {Object} EmbeddedEditorEntry
 * @property {string}  editorId   - Unique key in the registry.
 * @property {*}       editor     - Monaco IStandaloneCodeEditor instance.
 * @property {*}       model      - Monaco ITextModel instance.
 * @property {string}  uid        - Box UID this editor is bound to.
 * @property {string}  property   - Property name (e.g. "fragment", "vertex").
 * @property {string}  language   - Monaco language id.
 * @property {Element} container  - The DOM element the editor was mounted into.
 * @property {Function|null} disposeContentListener - Disposable for onDidChangeModelContent.
 * @property {number|null}   syncTimer   - Pending debounce timer id.
 * @property {string}  lastSentValue   - Last value sent to the server (avoid echo).
 */

let nextId = 1;

// ── Property → language mapping (mirrors BootstrapMonacoInit.kt) ─────

const PROPERTY_LANGUAGES = {
	code:         "kotlin",
	vertex:       "glsl",
	fragment:     "glsl",
	vertex_fragment:"slang",
	compute:      "glsl",
	task:         "glsl",
	mesh:         "glsl",
	vertex_s:     "slang",
	fragment_s:   "slang",
	compute_s:    "slang",
	task_s:       "slang",
	mesh_s:       "slang",
	preamble:     "kotlin",
	introduction: "kotlin",
	metadata:     "yaml",
	customization:"javascript",
	remote_js:    "javascript",
};

function languageForProperty(property) {
	return PROPERTY_LANGUAGES[property] || "plaintext";
}

// ── Create ────────────────────────────────────────────────────────────

const SYNC_DEBOUNCE_MS = 300;

const GLSL_PROPERTIES = new Set([
	"fragment",
	"vertex",
	"vertex_fragment",
	"compute",
	"task",
	"mesh",
	"fragment_s",
	"vertex_s",
	"compute_s",
	"task_s",
	"mesh_s",
]);

const EMBEDDED_EDITOR_OPTIONS = {
	theme: "dark",
	automaticLayout: true,
	scrollBeyondLastLine: false,
	lineNumbersMinChars: 2,
	lineDecorationsWidth: 12,
	padding: { top: 2, bottom: 2 },
	cursorStyle: "block",
	cursorBlinking: "blink",
	matchBrackets: "never",
	renderLineHighlight: "none",
	bracketPairColorization: { enabled: true },
	scrollbar: { verticalScrollbarSize: 4 },
	fontFamily: "Iosevka Web",
	fontSize: 13,
	fontLigatures: true,
	quickSuggestions: false,
	wordWrap: "on",
	minimap: { enabled: false },
	suggest: { showWords: false },
	glyphMargin: true,
	folding: false,
	overviewRulerLanes: 0,
	fixedOverflowWidgets: true,
	contextmenu: true,
	renderLineHighlightOnlyWhenFocus: false,
};

/**
 * Create a Monaco editor inside `container`, bound to the given box
 * uid and property.
 *
 * @param {HTMLElement} container - The DOM element to host the editor.
 * @param {string}     uid       - Box UID.
 * @param {string}     property  - Property name.
 * @param {string}     [language] - Monaco language id (auto-detected if omitted).
 * @param {string}     [initialValue] - Initial text content.
 * @returns {string}   The editorId for later lookup / disposal.
 */
function createEmbeddedEditor(container, uid, property, language, initialValue) {
	const monacoApi = window.monaco;
	if (!monacoApi || !monacoApi.editor) {
		console.error("[embedded-editor] Monaco not available");
		return null;
	}

	// Guard: don't allow two embedded editors for the same (uid, property)
	for (const entry of registry.values()) {
		if (entry.uid === uid && entry.property === property) {
			console.warn(`[embedded-editor] editor already exists for (${uid}, ${property})`);
			return entry.editorId;
		}
	}

	const lang = language || languageForProperty(property);
	const value = (typeof initialValue === "string") ? initialValue : "";
	const editorId = `emb-${nextId++}`;

	// Ensure the container has a fixed minimum size so Monaco can boot
	container.style.minHeight = "120px";
	container.style.position = "relative";

	const editor = monacoApi.editor.create(container, {
		...EMBEDDED_EDITOR_OPTIONS,
		language: lang,
		value: value,
	});

	const model = editor.getModel();

	// Attach custom data for the completion provider to find
	model._afieldBoxUid = uid;
	model._afieldProperty = property;
	model._afieldEmbeddedEditorId = editorId;

	// Tag the container for disposal lookup
	container.dataset.embeddedEditorId = editorId;

	// Override the disclosure content wrapper's overflow to prevent it
	// from clipping the Monaco gutter or adding double scrollbars.
	// Monaco handles its own scrolling internally.
	// Also override font-size: the CSS default is 10px, which Monaco
	// inherits for gutter-width measurements.  Match the editor's own
	// fontSize so line-numbers and margin controls are sized correctly.
	const disclosureContentWrapper = container.closest(".disclosure-content") || container.parentElement;
	if (disclosureContentWrapper && disclosureContentWrapper !== container) {
		disclosureContentWrapper.style.overflow = "hidden";
		disclosureContentWrapper.style.padding = "0px";
		disclosureContentWrapper.style.fontSize = "13px";
		disclosureContentWrapper.style.background = "#000000";
	}

	// ── Content sync → server ─────────────────────────────────────────
	const entry = {
		editorId,
		editor,
		model,
		uid,
		property,
		language: lang,
		container,
		disposeContentListener: null,
		syncTimer: null,
		lastSentValue: value,
		suppressNextContentSync: false,
	};

	const contentListener = editor.onDidChangeModelContent(() => {
		if (entry.suppressNextContentSync) {
			entry.suppressNextContentSync = false;
			return;
		}
		if (entry.syncTimer !== null) clearTimeout(entry.syncTimer);
		entry.syncTimer = setTimeout(() => {
			entry.syncTimer = null;
			const current = model.getValue();
			if (current === entry.lastSentValue) return;
			entry.lastSentValue = current;

			// Also update the DOM property on the box element
			const box = findBoxElement(uid);
			if (box) box[property] = current;
			const selected = window.currentSelectedCode;
			const selectedUid = selected && selected.length === 1 ? selected[0]?.uid : null;
			const selectedProperty = window.currentSelectedProperty || "code";
			const globalEditor = window.globalEditor;
			const mainEditorHasFocus = !!(globalEditor && typeof globalEditor.hasTextFocus === "function" && globalEditor.hasTextFocus());
			if (selected && selected.length === 1) {
				selected[0][property] = current;
			}
			if (globalEditor && !mainEditorHasFocus && selectedUid === uid && selectedProperty === property) {
				const mainCurrent = globalEditor.getModel && globalEditor.getModel() ? globalEditor.getModel().getValue() : "";
				if (mainCurrent !== current) {
					const selection = globalEditor.getSelection ? globalEditor.getSelection() : null;
					globalEditor.setValue(current);
					if (selection) globalEditor.setSelection(selection);
				}
			}

			send("set-string", { uid, property, value: current });
		}, SYNC_DEBOUNCE_MS);
	});

	// ── Register minimal actions on this editor ───────────────────────
	registerEmbeddedActions(editor, uid, property);

	if (typeof window.installSymbolContextMonitor === "function") {
		window.installSymbolContextMonitor(editor, () => ({ uid, property }));
	}

	entry.disposeContentListener = contentListener;
	registry.set(editorId, entry);

	return editorId;
}

// ── Actions for embedded editors ──────────────────────────────────────

/**
 * For GLSL properties, Alt+Enter should save-and-reload the shader
 * rather than executing code.
 */
function shaderReloadAction(property) {
	switch (property) {
		case "vertex":
		case "fragment":
		case "vertex_fragment":
		case "vertex_s":
		case "fragment_s":
			return "moduleHere_reload";
		case "compute":
		case "compute_s":
			return "computeModuleHere_reload";
		case "mesh":
		case "task":
		case "mesh_s":
		case "task_s":
			return "meshModuleHere_reload";
		default:
			return null;
	}
}

function extractSelectionOrScope(ed) {
	const selection = ed.getSelection();
	const model = ed.getModel();

	// If there's a non-empty selection, use it
	if (selection && !selection.isEmpty()) {
		const belowLine = selection.startLineNumber - 1;
		const code = model.getValueInRange(selection);
		return { code, belowLine, singleLine: false };
	}

	// Try enclosing scope
	const lineNumber = selection.endLineNumber;
	if (typeof window.getEnclosingScope === "function") {
		const getLine = (n) => {
			if (n < 0 || n >= model.getLineCount()) return null;
			return model.getLineContent(n + 1);
		};
		const scope = window.getEnclosingScope(lineNumber - 1, getLine);
		if (scope) {
			const lines = scope[0];
			const start = scope[1];
			return { code: lines.join("\n"), belowLine: start, singleLine: true };
		}
	}

	// Fallback: current line
	const line = model.getLineContent(lineNumber);
	return { code: line, belowLine: lineNumber - 1, singleLine: true };
}

function makeOutputCallback(uid) {
	return (message, response) => {
		monacoHandleOutput(response, message, uid);
		return true;
	};
}

function registerEmbeddedActions(editor, uid, property) {
	const monacoApi = window.monaco;
	if (!monacoApi) return;

	function keycode(v) { return v | 0; }
	function keybinding(...values) {
		let r = 0;
		for (const v of values) r |= keycode(v);
		return r;
	}

	// ── F1 → open Monaco's own command palette for this editor ─────────
	editor.addAction({
		id: "embedded-command-palette",
		label: "Command Palette",
		keybindings: [monacoApi.KeyCode.F1],
		run: (ed) => {
			ed.focus();
			ed.trigger("keyboard", "editor.action.quickCommand", null);
		},
	});

	// ── Cmd/Ctrl+P → quick command (same as F1) ──────────────────────
	editor.addCommand(keybinding(monacoApi.KeyMod.CtrlCmd, monacoApi.KeyCode.KeyP), () => {
		editor.trigger("", "editor.action.quickCommand", null);
	});

	// ── Alt+Enter: execute fragment or reload shader ─────────────────
	editor.addAction({
		id: "execute-fragment",
		label: "Run Line / Selection",
		keybindings: [keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyCode.Enter)],
		contextMenuGroupId: "execution",
		contextMenuOrder: 1.5,
		run: (ed) => {
			const reloadAction = shaderReloadAction(property);
			if (reloadAction) {
				const value = ed.getModel().getValue();
				send("set-string", { uid, property, value });
				send("perform-action", { uid_currentSelection: [uid], uid_action: reloadAction });
				return;
			}
			const { code, belowLine, singleLine } = extractSelectionOrScope(ed);
			if (!code || !code.trim()) return;
			const payload = { uid, type: "fragment", line: belowLine, code, singleLine };
			sendWithReply("execute-fragment", payload, "exec_" + uid, makeOutputCallback(uid));
		},
	});

	// ── Alt+0: execute all ────────────────────────────────────────────
	editor.addAction({
		id: "execute-all",
		label: "Run Box",
		keybindings: [keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyCode.Digit0)],
		contextMenuGroupId: "execution",
		contextMenuOrder: 2.0,
		run: (ed) => {
			const code = ed.getModel().getValue();
			if (!code || !code.trim()) return;
			const reloadAction = shaderReloadAction(property);
			if (reloadAction) {
				send("set-string", { uid, property, value: code });
				send("perform-action", { uid_currentSelection: [uid], uid_action: reloadAction });
				return;
			}
			const payload = { uid, type: "all", line: 0, code, singleLine: false };
			sendWithReply("execute-all", payload, "exec_" + uid, makeOutputCallback(uid));
		},
	});

	// ── Alt+1: launch fragment ───────────────────────────────────────
	editor.addAction({
		id: "launch-fragment",
		label: "Launch Line / Selection",
		keybindings: [keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyCode.Digit1)],
		contextMenuGroupId: "execution",
		contextMenuOrder: 1.5,
		run: (ed) => {
			const reloadAction = shaderReloadAction(property);
			if (reloadAction) {
				const value = ed.getModel().getValue();
				send("set-string", { uid, property, value });
				send("perform-action", { uid_currentSelection: [uid], uid_action: reloadAction });
				return;
			}
			const { code, belowLine, singleLine } = extractSelectionOrScope(ed);
			if (!code || !code.trim()) return;
			// addExecutionMarker is for main editor only; skip it for embedded
			const payload = { uid, type: "fragment", line: belowLine, code, singleLine, strand: "default" };
			sendWithReply("launch-fragment", payload, "exec_" + uid, makeOutputCallback(uid));
		},
	});

	// ── Alt+Up: launch all ───────────────────────────────────────────
	editor.addAction({
		id: "launch-all",
		label: "Launch Box",
		keybindings: [keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyCode.UpArrow)],
		contextMenuGroupId: "execution",
		contextMenuOrder: 2.0,
		run: (ed) => {
			const code = ed.getModel().getValue();
			if (!code || !code.trim()) return;
			const reloadAction = shaderReloadAction(property);
			if (reloadAction) {
				send("set-string", { uid, property, value: code });
				send("perform-action", { uid_currentSelection: [uid], uid_action: reloadAction });
				return;
			}
			if (typeof window.launchAll === "function") {
				window.launchAll(uid, code);
			} else {
				const payload = { uid, type: "fragment", line: 0, code, singleLine: false, strand: "default" };
				sendWithReply("launch-fragment", payload, "exec_" + uid, makeOutputCallback(uid));
			}
		},
	});

	// ── Alt+Shift+Up: continue all ───────────────────────────────────
	editor.addAction({
		id: "continue-all",
		label: "Continue Box",
		keybindings: [keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyMod.Shift, monacoApi.KeyCode.UpArrow)],
		contextMenuGroupId: "execution",
		contextMenuOrder: 2.1,
		run: (ed) => {
			const code = ed.getModel().getValue();
			if (!code || !code.trim()) return;
			if (typeof window.continueAll === "function") {
				window.continueAll(uid, code);
			} else if (typeof window.launchAll === "function") {
				window.launchAll(uid, code);
			}
		},
	});

	// ── Alt+Down / Alt+Shift+Down: stop all ──────────────────────────
	editor.addAction({
		id: "stop-all",
		label: "Stop Box",
		keybindings: [
			keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyCode.DownArrow),
			keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyMod.Shift, monacoApi.KeyCode.DownArrow),
		],
		contextMenuGroupId: "execution",
		contextMenuOrder: 2.0,
		run: () => {
			const code = editor.getModel().getValue();
			const line = code.split("\n").length + 1;
			const payload = { uid, type: "launch", line, code, singleLine: false };
			sendWithReply("stop-all", payload, "stop_" + uid, () => true);
		},
	});

	// ── Alt+Shift+P: send selection to preamble ──────────────────────
	editor.addAction({
		id: "send-to-preamble",
		label: "Move code to preamble",
		keybindings: [keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyMod.Shift, monacoApi.KeyCode.KeyP)],
		precondition: "editorHasSelection",
		run: (ed) => {
			const model = ed.getModel();
			const selection = ed.getSelection();
			const selectedText = model.getValueInRange(selection);
			const box = findBoxElement(uid);
			const existing = (box && box.preamble) ? box.preamble : "";
			const updated = selectedText.trim() + "\n" + existing;
			if (box) box.preamble = updated;

			ed.executeEdits("remove-selection", [{ range: selection, text: "", forceMoveMarkers: true }]);
			send("set-string", { uid, property: "preamble", value: updated });
		},
	});

	// ── Alt+O: AI assist ─────────────────────────────────────────────
	editor.addAction({
		id: "ai-assist",
		label: "Apply Intelligence",
		keybindings: [keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyCode.KeyO)],
		contextMenuGroupId: "execution",
		contextMenuOrder: 1.5,
		run: (ed) => {
			const model = ed.getModel();
			const selection = ed.getSelection();
			const fullText = model.getValue();
			const startPos = selection.getStartPosition();
			const endPos = selection.getEndPosition();
			const startOffset = model.getOffsetAt(startPos);
			const endOffset = model.getOffsetAt(endPos);

			let allText, kind;
			if (startOffset !== endOffset) {
				allText = fullText.substring(0, startOffset)
					+ "<rewrite>" + fullText.substring(startOffset, endOffset) + "</rewrite>"
					+ fullText.substring(endOffset);
				kind = "rewrite";
			} else {
				allText = fullText.substring(0, startOffset) + "<cursor>" + fullText.substring(startOffset);
				kind = "cursor";
			}
			const startLine = Math.max(startPos.lineNumber - 1, 0);
			send("ai-assist", { uid, prompt: allText, startLine, extraPrompt: "", property, kind });
		},
	});

	// ── Alt+Shift+O: AI assist on error ──────────────────────────────
	editor.addAction({
		id: "ai-assist-error",
		label: "Apply Intelligence to Error message",
		keybindings: [keybinding(monacoApi.KeyMod.Alt, monacoApi.KeyMod.Shift, monacoApi.KeyCode.KeyO)],
		contextMenuGroupId: "execution",
		contextMenuOrder: 1.5,
		run: (ed) => {
			const lastErrorIs = window.lastErrorIs;
			if (!lastErrorIs || lastErrorIs.length !== 2) return;
			const model = ed.getModel();
			let lineNumber = lastErrorIs[0] | 0;
			if (lineNumber <= 0) lineNumber = 1;
			lastErrorIs[0] = lineNumber;
			const endColumn = model.getLineMaxColumn(lineNumber);
			const offset = model.getOffsetAt({ lineNumber, column: endColumn });
			const fullText = model.getValue();
			const errText = (lastErrorIs[1] || "").trim();
			const allText = fullText.substring(0, offset)
				+ "<error>" + errText + "</error>\n"
				+ fullText.substring(offset);
			send("ai-assist", { uid, prompt: allText, kind: "error" });
		},
	});

	// ── Cmd+3: AI chat inline ────────────────────────────────────────
	editor.addAction({
		id: "ai-chat",
		label: "AI Chat in editor",
		keybindings: [keybinding(monacoApi.KeyMod.CtrlCmd, monacoApi.KeyCode.Digit3)],
		run: (ed) => {
			const selection = ed.getSelection();
			const start = selection.startLineNumber - 1;
			const fullText = ed.getModel().getValue();
			if (typeof window.addPromptBox === "function") {
				window.addPromptBox(ed, start, (extraPrompt) => {
					send("ai-assist", { uid, prompt: fullText, startLine: start, extraPrompt, property, kind: "chat" });
				});
			}
		},
	});

	// ── Cmd+4: AI chat panel ─────────────────────────────────────────
	editor.addAction({
		id: "ai-chat-panel",
		label: "AI Chat (panel)",
		keybindings: [keybinding(monacoApi.KeyMod.CtrlCmd, monacoApi.KeyCode.Digit4)],
		run: () => {
			if (typeof window.openAiChatForBox === "function") {
				window.openAiChatForBox(uid, property);
			}
		},
	});

	// ── Provisional accept/reject ────────────────────────────────────
	editor.addAction({
		id: "provisional-accept-current",
		label: "Accept All Provisional (This Editor)",
		keybindings: [],
		contextMenuGroupId: "execution",
		contextMenuOrder: 1.6,
		run: () => { send("provisional-accept", { uid, property }); },
	});
	editor.addAction({
		id: "provisional-reject-current",
		label: "Reject All Provisional (This Editor)",
		keybindings: [],
		contextMenuGroupId: "execution",
		contextMenuOrder: 1.61,
		run: () => { send("provisional-reject", { uid, property }); },
	});
	editor.addAction({
		id: "provisional-accept-all",
		label: "Accept All Provisional (Everywhere)",
		keybindings: [],
		contextMenuGroupId: "execution",
		contextMenuOrder: 1.62,
		run: () => { send("provisional-accept-all", {}); },
	});
	editor.addAction({
		id: "provisional-reject-all",
		label: "Reject All Provisional (Everywhere)",
		keybindings: [],
		contextMenuGroupId: "execution",
		contextMenuOrder: 1.63,
		run: () => { send("provisional-reject-all", {}); },
	});

	// ── Cmd+': toggle fold ───────────────────────────────────────────
	editor.addAction({
		id: "toggle-fold-current-line",
		label: "Toggle Fold on Current Line",
		keybindings: [keybinding(monacoApi.KeyMod.CtrlCmd, monacoApi.KeyCode.Quote)],
		run: (ed) => { ed.trigger("keyboard", "editor.toggleFold", null); },
	});

	// ── Cmd+. / Cmd+, : next/prev property ──────────────────────────
	// For embedded editors, these switch the *main* editor's property
	// via the global selectedProperty variable that the selection monitor reads.
	editor.addAction({
		id: "forward-next-property",
		label: "Next Property",
		keybindings: [keybinding(monacoApi.KeyMod.CtrlCmd, monacoApi.KeyCode.Period)],
		run: () => {
			// Trigger a property change on the global editor if available
			const ge = window.globalEditor;
			if (ge) {
				const action = ge.getAction("forward-next-property");
				if (action) action.run();
			}
		},
	});
	editor.addAction({
		id: "back-next-property",
		label: "Previous Property",
		keybindings: [keybinding(monacoApi.KeyMod.CtrlCmd, monacoApi.KeyCode.Comma)],
		run: () => {
			const ge = window.globalEditor;
			if (ge) {
				const action = ge.getAction("back-next-property");
				if (action) action.run();
			}
		},
	});
}

// ── Dispose ───────────────────────────────────────────────────────────

/**
 * Dispose a single embedded editor by its id.
 * @param {string} editorId
 */
function disposeEmbeddedEditor(editorId) {
	const entry = registry.get(editorId);
	if (!entry) return;

	if (entry.syncTimer !== null) clearTimeout(entry.syncTimer);
	if (entry.disposeContentListener && typeof entry.disposeContentListener.dispose === "function") {
		entry.disposeContentListener.dispose();
	}
	try { entry.editor.dispose(); } catch (e) { console.warn("[embedded-editor] dispose error", e); }

	registry.delete(editorId);
}

/**
 * Dispose all embedded editors for a given box UID.
 * Called when a box is deleted.
 * @param {string} uid
 */
function disposeEmbeddedEditorsForBox(uid) {
	const toRemove = [];
	for (const [id, entry] of registry) {
		if (entry.uid === uid) toRemove.push(id);
	}
	toRemove.forEach(disposeEmbeddedEditor);
}

// ── Lookup helpers ────────────────────────────────────────────────────

/**
 * Find the entry for a model (used by the completion provider).
 * @param {*} model - Monaco ITextModel
 * @returns {EmbeddedEditorEntry|null}
 */
function getEmbeddedEditorByModel(model) {
	if (!model) return null;
	const eid = model._afieldEmbeddedEditorId;
	if (eid && registry.has(eid)) return registry.get(eid);
	// Fallback: linear search
	for (const entry of registry.values()) {
		if (entry.model === model) return entry;
	}
	return null;
}

/**
 * Return the embedded editor entry that currently has text focus, or null.
 * @returns {EmbeddedEditorEntry|null}
 */
function getFocusedEmbeddedEditor() {
	for (const entry of registry.values()) {
		if (entry.editor.hasTextFocus()) return entry;
	}
	return null;
}

/**
 * Find an embedded editor for a given (uid, property).
 * @param {string} uid
 * @param {string} property
 * @returns {EmbeddedEditorEntry|null}
 */
function getEmbeddedEditorFor(uid, property) {
	for (const entry of registry.values()) {
		if (entry.uid === uid && entry.property === property) return entry;
	}
	return null;
}

/**
 * Return all entries as an array.
 * @returns {EmbeddedEditorEntry[]}
 */
function getAllEmbeddedEditors() {
	return Array.from(registry.values());
}

// ── Remote update handling ────────────────────────────────────────────

/**
 * Called when a `set-string` arrives from the server for a property
 * that might be open in an embedded editor.
 *
 * @param {string} uid
 * @param {string} property
 * @param {string} newValue
 */
function notifyEmbeddedEditorOfRemoteUpdate(uid, property, newValue) {
	const entry = getEmbeddedEditorFor(uid, property);
	if (!entry) return;

	const currentValue = entry.model.getValue();
	// Avoid echo: if the new value matches what we last sent, skip
	if (newValue === entry.lastSentValue) return;
	if (newValue === currentValue) return;
	if (entry.syncTimer !== null) {
		clearTimeout(entry.syncTimer);
		entry.syncTimer = null;
	}
	const previousSyncedValue = entry.lastSentValue;
	entry.lastSentValue = newValue;

	// Try 3-way merge if diff module is available
	if (window.diff && typeof window.diff.mergeDigIn === "function") {
		const base = previousSyncedValue || "";
		// mergeDigIn expects arrays of lines and returns { result: [...lines] }
		const merged = window.diff.mergeDigIn(
			currentValue.split("\n"),
			base.split("\n"),
			newValue.split("\n")
		);
		const result = (merged && merged.result) ? merged.result.join("\n") : newValue;
		if (result !== currentValue) {
			entry.suppressNextContentSync = true;
			const sel = entry.editor.getSelection();
			entry.editor.setValue(result);
			if (sel) entry.editor.setSelection(sel);
		}
	} else {
		// No diff module — overwrite if user hasn't changed anything
		if (currentValue === entry.lastSentValue || currentValue === "") {
			entry.suppressNextContentSync = true;
			entry.editor.setValue(newValue);
		}
	}
}

// ── DOM helpers ───────────────────────────────────────────────────────

function findBoxElement(uid) {
	const boxes = document.getElementsByClassName("box");
	for (let i = 0; i < boxes.length; i++) {
		if (boxes[i].uid === uid) return boxes[i];
	}
	return null;
}

// ── Expose on window ──────────────────────────────────────────────────

window.embeddedEditors = {
	create:          createEmbeddedEditor,
	dispose:         disposeEmbeddedEditor,
	disposeForBox:   disposeEmbeddedEditorsForBox,
	getByModel:      getEmbeddedEditorByModel,
	getFocused:      getFocusedEmbeddedEditor,
	getFor:          getEmbeddedEditorFor,
	getAll:          getAllEmbeddedEditors,
	notifyRemote:    notifyEmbeddedEditorOfRemoteUpdate,
	languageFor:     languageForProperty,
	PROPERTY_LANGUAGES,
};
