// Inline autocomplete for the AI4 chat composer (`Ai4ChatAutocomplete` on the server, `ai-chat-suggest`).
//
// After a pause in typing, with the cursor at the end of the draft, the fast model's guess at how the message
// goes on appears as grey text after the cursor. Tab takes all of it, → takes its next word, Escape drops it.
// Typing the same letters it shows keeps the rest; any other edit, a click or a cursor move drops it.
// The grey text is a mirror laid exactly over the textarea, so the textarea keeps its own caret, selection,
// undo and spellcheck. `/autocomplete off` turns it off in this browser; the server can turn it off for a Field.
import { sendWithReply } from "./afield.mjs";

const STORAGE_KEY = "afield.ai4.chatAutocomplete";
const DELAY_MS = 450;
const ERROR_BACKOFF_MS = 30000;
// Text metrics the mirror copies so its words wrap where the textarea's do.
const MIRRORED = ["fontFamily", "fontSize", "fontWeight", "fontStyle", "fontVariant", "letterSpacing", "lineHeight",
        "textTransform", "wordSpacing", "textIndent", "tabSize", "paddingTop", "paddingRight", "paddingBottom",
        "paddingLeft", "whiteSpace", "wordBreak", "overflowWrap", "direction"];

let offForThisField = false;

export function chatAutocompleteState() {
        if (offForThisField) return "off for this Field (AFIELD_AI4_CHAT_AUTOCOMPLETE)";
        return chatAutocompleteOn() ? "on" : "off";
}

function chatAutocompleteOn() {
        if (offForThisField) return false;
        try {
                return window.localStorage?.getItem(STORAGE_KEY) !== "off";
        } catch (_) {
                return true;
        }
}

export function setChatAutocomplete(on) {
        try {
                window.localStorage?.setItem(STORAGE_KEY, on ? "on" : "off");
        } catch (_) {
        }
}

/**
 * Lays the suggestion mirror over `textarea` (inside `inputWrap`, which must be positioned) and returns
 * `{clear}`; call `clear()` whenever the panel empties or replaces the draft itself.
 */
export function mountChatAutocomplete(inputWrap, textarea, { chatUid, chatProperty }) {
        const ghost = document.createElement("div");
        ghost.className = "ai-chat-ghost";
        ghost.setAttribute("aria-hidden", "true");
        const typed = document.createElement("span");
        typed.className = "ai-chat-ghost-typed";
        const rest = document.createElement("span");
        rest.className = "ai-chat-ghost-rest";
        ghost.append(typed, rest);
        inputWrap.appendChild(ghost);

        let suggestion = "";
        // The draft `suggestion` follows; a draft changed by the panel itself (send, rewind) makes it stale.
        let suggestionFor = "";
        let lastValue = textarea.value;
        let timer = 0;
        let seq = 0;
        let backoffUntil = 0;

        const cursorAtEnd = () => textarea.selectionStart === textarea.value.length && textarea.selectionEnd === textarea.value.length;

        function render() {
                if (!suggestion || textarea.value !== suggestionFor) {
                        suggestion = "";
                        ghost.style.display = "none";
                        return;
                }
                const style = getComputedStyle(textarea);
                for (const name of MIRRORED) ghost.style[name] = style[name];
                ghost.style.left = `${textarea.offsetLeft + textarea.clientLeft}px`;
                ghost.style.top = `${textarea.offsetTop + textarea.clientTop}px`;
                ghost.style.width = `${textarea.clientWidth}px`;
                ghost.style.height = `${textarea.clientHeight}px`;
                typed.textContent = textarea.value;
                rest.textContent = suggestion;
                ghost.style.display = "block";
                ghost.scrollTop = textarea.scrollTop;
        }

        function clear() {
                clearTimeout(timer);
                seq++;
                suggestion = "";
                lastValue = textarea.value;
                render();
        }

        function eligible() {
                const value = textarea.value;
                return chatAutocompleteOn() && Date.now() >= backoffUntil && document.activeElement === textarea
                        && !textarea.disabled && cursorAtEnd() && value.trim().length >= 3 && !value.trimStart().startsWith("/");
        }

        function schedule() {
                clearTimeout(timer);
                if (eligible()) timer = setTimeout(request, DELAY_MS);
        }

        function request() {
                if (!eligible()) return;
                const uid = chatUid();
                if (!uid) return;
                const draft = textarea.value;
                const mine = ++seq;
                sendWithReply("ai-chat-suggest", { uid, property: chatProperty(), draft, seq: mine },
                        `ai-chat-suggest-${uid}-${mine}-${Date.now()}`, (_message, response) => {
                                if (response?.disabled) {
                                        offForThisField = true;
                                        clear();
                                } else if (response?.error) {
                                        backoffUntil = Date.now() + ERROR_BACKOFF_MS;
                                } else if (mine === seq) {
                                        show(draft, response?.suggestion || "");
                                }
                                return "remove";
                        });
        }

        // The reply is for `draft`; whatever the person typed since must be the start of the suggestion.
        function show(draft, text) {
                const value = textarea.value;
                if (!text || !value.startsWith(draft) || !cursorAtEnd() || document.activeElement !== textarea) return;
                const typedSince = value.slice(draft.length);
                if (!text.startsWith(typedSince) || text.length === typedSince.length) return;
                suggestion = text.slice(typedSince.length);
                suggestionFor = value;
                render();
        }

        function accept(text) {
                if (!text) return;
                textarea.focus();
                textarea.setSelectionRange(textarea.value.length, textarea.value.length);
                // insertText keeps the textarea's own undo; the input event it fires consumes the suggestion.
                if (!document.execCommand?.("insertText", false, text)) {
                        textarea.setRangeText(text, textarea.value.length, textarea.value.length, "end");
                        textarea.dispatchEvent(new Event("input", { bubbles: true }));
                }
        }

        textarea.addEventListener("input", () => {
                const value = textarea.value;
                if (suggestion && suggestionFor === lastValue && value.startsWith(lastValue) && cursorAtEnd()) {
                        const added = value.slice(lastValue.length);
                        if (added && suggestion.startsWith(added)) {
                                suggestion = suggestion.slice(added.length);
                                suggestionFor = value;
                                lastValue = value;
                                render();
                                if (!suggestion) schedule();
                                return;
                        }
                }
                lastValue = value;
                suggestion = "";
                render();
                schedule();
        });

        textarea.addEventListener("keydown", (event) => {
                if (!suggestion || event.isComposing) return;
                if (textarea.value !== suggestionFor) {
                        clear();
                        return;
                }
                const plain = !event.shiftKey && !event.altKey && !event.ctrlKey && !event.metaKey;
                if (event.key === "Tab" && plain) {
                        event.preventDefault();
                        event.stopPropagation();
                        accept(suggestion);
                } else if (event.key === "ArrowRight" && plain && cursorAtEnd()) {
                        event.preventDefault();
                        event.stopPropagation();
                        accept(suggestion.match(/^\s*\S+/)?.[0] || suggestion);
                } else if (event.key === "Escape") {
                        event.preventDefault();
                        event.stopPropagation();
                        clear();
                } else if (/^(Arrow|Home$|End$|Page)/.test(event.key)) {
                        clear();
                }
        });

        textarea.addEventListener("mousedown", clear);
        textarea.addEventListener("blur", clear);
        textarea.addEventListener("scroll", () => { ghost.scrollTop = textarea.scrollTop; });
        new ResizeObserver(render).observe(textarea);

        return { clear };
}
