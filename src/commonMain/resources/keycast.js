(() => {
    const joinWindowMs = 1100;      // How close keypresses need to be to stay concatenated
    const fadeDurationMs = 5000;    // Fade-out time for the label
    const maxCharacters = 10;       // Max length of the on-screen string before clearing

    const modifierSymbols = {
        Meta: "⌘",
        Control: "⌃",
        Alt: "⌥",
        AltGraph: "⌥",
        Shift: "⇧",
    };

    const specialKeys = {
        " ": "␣",
        Spacebar: "␣",
        Space: "␣",
        Enter: "⏎",
        Tab: "⇥",
        Backspace: "⌫",
        Escape: "⎋",
        Esc: "⎋",
        ArrowUp: "↑",
        ArrowDown: "↓",
        ArrowLeft: "←",
        ArrowRight: "→",
        Delete: "⌦",
        Home: "⇱",
        End: "⇲",
        PageUp: "⇞",
        PageDown: "⇟",
        CapsLock: "⇪",
    };

    const functionKeys = new Set(Array.from({ length: 24 }, (_, i) => `F${i + 1}`));
    const mouseButtons = {
        0: "LMB",
        1: "MMB",
        2: "RMB",
        3: "MB4",
        4: "MB5",
    };

    let enabled = false;
    let buffer = "";
    let lastStamp = 0;
    let fadeTimer = null;
    let overlay = null;
    let textEl = null;
    let listenersAttached = false;

    function installStyles() {
        // Keycast chrome lives in field.css.
    }

    function ensureElements() {
        if (overlay && textEl) return;
        installStyles();
        overlay = document.createElement("div");
        overlay.id = "keycast-overlay";
        overlay.style.width = "320px";

        textEl = document.createElement("div");
        textEl.className = "keycast-text";
        textEl.textContent = "";

        overlay.appendChild(textEl);
        document.body.appendChild(overlay);
        alignOverlay();
    }

    function alignOverlay() {
        if (!overlay) return;
        const editor = document.querySelector(".monaco-editor-container");
        const leftEdge = editor?.getBoundingClientRect()?.left ?? window.innerWidth * 0.35;
        const usableWidth = Math.max(240, Math.min(window.innerWidth - 20, Math.floor(leftEdge)));
        overlay.style.width = `${usableWidth}px`;
    }

    function formatKey(event) {
        const key = event.key || "";
        if (!key) return "";

        const isModifier = key === "Shift" || key === "Control" || key === "Meta" || key === "Alt" || key === "AltGraph";
        if (isModifier) {
            return modifierSymbols[key] || key;
        }

        const parts = [];
        if (event.metaKey) parts.push(modifierSymbols.Meta);
        if (event.ctrlKey) parts.push(modifierSymbols.Control);
        if (event.altKey) parts.push(modifierSymbols.Alt);
        if (event.shiftKey) parts.push(modifierSymbols.Shift);

        let base = specialKeys[key];
        if (!base) {
            const isFunction = functionKeys.has(key) || functionKeys.has(event.code || "");
            if (isFunction) {
                base = functionKeys.has(key) ? key : (event.code || key);
            } else if (key === "Help") {
                base = "F1";
            } else if (key.length === 1) {
                base = key.toUpperCase();
            } else {
                base = key;
            }
        }

        if (!base && event.code && functionKeys.has(event.code)) {
            base = event.code;
        }
        if (!base) base = event.code || "";
        return (parts.join("") + base).trim();
    }

    function resolveButton(event) {
        if (typeof event.button === "number" && event.button >= 0) return event.button;
        // Fallback for browsers/devices that only expose `buttons` bitmask
        const mask = event.buttons;
        if (mask === 1) return 0; // left
        if (mask === 2) return 2; // right
        if (mask === 4) return 1; // middle
        if (mask === 8) return 3; // X1
        if (mask === 16) return 4; // X2
        if (event.type === "contextmenu") return 2; // right click fallback
        if (event.type === "click" || event.type === "mouseup" || event.type === "mousedown") return 0;
        return null;
    }

    function formatMouse(event) {
        const button = resolveButton(event);
        if (button === null) return "";
        const label = mouseButtons[button];
        return label || "";
    }

    function restartFade() {
        if (!textEl) return;
        if (fadeTimer) window.clearTimeout(fadeTimer);

        textEl.style.transition = "none";
        textEl.style.opacity = "1";
        // Force reflow so the next transition applies
        void textEl.offsetWidth;
        textEl.style.transition = `opacity ${fadeDurationMs}ms linear`;
        fadeTimer = window.setTimeout(() => {
            textEl.style.opacity = "0";
        }, 40);
    }

    function handleKey(event) {
        if (!enabled) return;
        if (event.repeat) return;

        const now = Date.now();
        if (now - lastStamp > joinWindowMs) {
            buffer = "";
        }
        lastStamp = now;

        const label = formatKey(event);
        if (!label) return;

        if (buffer.length + label.length > maxCharacters) {
            buffer = "";
        }
        const next = buffer + label;
        buffer = next.length > maxCharacters ? next.slice(-maxCharacters) : next;
        ensureElements();
        alignOverlay();
        textEl.textContent = buffer;
        restartFade();
    }

    function handleMouse(event) {
        if (!enabled) return;

        const now = Date.now();
        if (now - lastStamp > joinWindowMs) {
            buffer = "";
        }
        lastStamp = now;

        const label = formatMouse(event);
        if (!label) return;

        if (buffer.length + label.length > maxCharacters) {
            buffer = "";
        }
        const next = buffer + label;
        buffer = next.length > maxCharacters ? next.slice(-maxCharacters) : next;
        ensureElements();
        alignOverlay();
        textEl.textContent = buffer;
        restartFade();
    }

    // `quiet` skips the toast, for a docs capture that switches the display on and off itself
    // (DocsUiDriver.keypressDisplay): the toasts persist, and would show in the next capture.
    function enable({ quiet = false } = {}) {
        if (enabled) return;
        enabled = true;
        ensureElements();
        alignOverlay();
        if (window.notify && !quiet) window.notify("Keypress display on", "info");
    }

    function disable({ quiet = false } = {}) {
        if (!enabled) return;
        enabled = false;
        buffer = "";
        if (fadeTimer) window.clearTimeout(fadeTimer);
        if (textEl) {
            textEl.textContent = "";
            textEl.style.opacity = "0";
        }
        if (window.notify && !quiet) window.notify("Keypress display off", "info");
    }

    function toggle() {
        if (enabled) {
            disable();
        } else {
            enable();
        }
    }

    function registerAction() {
        if (!window.allActions) window.allActions = { base: [] };
        const slotName = "keycast";
        const bucket = window.allActions[slotName] || [];
        const exists = bucket.some((a) => a.id === "toggle-keypress-display");
        if (!exists) {
            bucket.push({
                id: "toggle-keypress-display",
                label: "Toggle Keypress Display",
                detail: "Show keypresses on screen (useful for screen recordings)",
                run: toggle,
                canvasKeybinding: () => false,
            });
        }
        window.allActions[slotName] = bucket;
        if (typeof window.updateActions === "function") {
            window.updateActions();
        }
    }

    function attachListenersOnce() {
        if (listenersAttached) return;
        window.addEventListener("keydown", handleKey, { capture: true });
        // Support both Pointer and Mouse events to cover older browsers/iframes
        const targets = [window, document];
        const events = ["pointerdown", "pointerup", "mousedown", "mouseup", "auxclick", "click", "contextmenu"];
        targets.forEach((t) => events.forEach((ev) => t.addEventListener(ev, handleMouse, { capture: true })));
        window.addEventListener("resize", alignOverlay);
        listenersAttached = true;
    }

    attachListenersOnce();
    window.fieldKeycast = { enable, disable, isEnabled: () => enabled };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", registerAction);
    } else {
        registerAction();
    }
})();
