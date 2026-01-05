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

    let enabled = false;
    let buffer = "";
    let lastStamp = 0;
    let fadeTimer = null;
    let overlay = null;
    let textEl = null;
    let listenersAttached = false;

    function installStyles() {
        if (document.getElementById("keycast-style")) return;
        const style = document.createElement("style");
        style.id = "keycast-style";
        style.textContent = `
      #keycast-overlay {
        position: fixed;
        left: 0;
        bottom: 12%;
        display: flex;
        justify-content: center;
        pointer-events: none;
        z-index: 9000;
        padding: 0 12px;
      }
      #keycast-overlay .keycast-text {
        min-width: 160px;
        max-width: 100%;
        text-align: center;
        padding: 12px 18px;
        font-family: "Iosevka Web", system-ui, monospace;
        font-size: 36px;
        font-weight: 480;
        letter-spacing: 0.8px;
        color: #e8f7ff;
        background: rgba(0, 0, 0, 0.55);
        border: 1px solid rgba(255, 255, 255, 0.2);
        border-radius: 12px;
        box-shadow: 0 8px 30px rgba(0, 0, 0, 0.45);
        backdrop-filter: blur(6px) saturate(1.05);
        opacity: 0;
        transition: opacity ${fadeDurationMs}ms linear;
        text-shadow: 0 2px 12px rgba(0, 0, 0, 0.6);
        user-select: none;
      }
    `;
        document.head.appendChild(style);
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

    function enable() {
        if (enabled) return;
        enabled = true;
        ensureElements();
        alignOverlay();
        if (window.notify) window.notify("Keypress display on", "info");
    }

    function disable() {
        if (!enabled) return;
        enabled = false;
        buffer = "";
        if (fadeTimer) window.clearTimeout(fadeTimer);
        if (textEl) {
            textEl.textContent = "";
            textEl.style.opacity = "0";
        }
        if (window.notify) window.notify("Keypress display off", "info");
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
        window.addEventListener("resize", alignOverlay);
        listenersAttached = true;
    }

    attachListenersOnce();

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", registerAction);
    } else {
        registerAction();
    }
})();
