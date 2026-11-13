// Field's colour theme: "dark" (the default) or "light".
//
// Loaded as a plain script at the top of <head>, before field.css paints, so a light-theme user
// never sees a dark frame. The choice is per browser profile (localStorage), which is per user
// in the Electron app. field.css keys its palette off data-field-theme on <html>; everything
// drawn outside CSS (the WebGL canvas background, Monaco, Excalidraw) listens for the
// "field-theme-change" event on window and re-reads the CSS tokens.
(function () {
    const KEY = "field-theme";
    const THEMES = ["dark", "light"];

    function stored() {
        try {
            const value = window.localStorage.getItem(KEY);
            return THEMES.indexOf(value) >= 0 ? value : null;
        } catch (e) {
            return null;
        }
    }

    function fromQuery() {
        try {
            const value = new URLSearchParams(window.location.search).get("theme");
            return THEMES.indexOf(value) >= 0 ? value : null;
        } catch (e) {
            return null;
        }
    }

    function apply(theme) {
        const root = document.documentElement;
        root.setAttribute("data-field-theme", theme);
        const meta = document.querySelector('meta[name="color-scheme"]');
        if (meta) meta.setAttribute("content", theme);
    }

    let current = fromQuery() || stored() || "dark";
    apply(current);

    function set(theme, options) {
        if (THEMES.indexOf(theme) < 0) return current;
        const persist = !options || options.persist !== false;
        if (persist) {
            try {
                window.localStorage.setItem(KEY, theme);
            } catch (e) {
                // A storage-less context still switches for this page.
            }
        }
        if (theme === current) return current;
        current = theme;
        apply(theme);
        window.dispatchEvent(new CustomEvent("field-theme-change", { detail: { theme } }));
        return current;
    }

    // Another Field tab or iframe switched: follow it.
    window.addEventListener("storage", function (event) {
        if (event.key === KEY && THEMES.indexOf(event.newValue) >= 0) set(event.newValue, { persist: false });
    });

    window.FieldTheme = {
        get: function () {
            return current;
        },
        set: set,
        toggle: function () {
            return set(current === "light" ? "dark" : "light");
        },
        isLight: function () {
            return current === "light";
        },
        /** A token's "r, g, b" triple from field.css, as three 0..1 floats; `fallback` when absent. */
        rgb: function (name, fallback) {
            const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
            const parts = raw.split(",").map(function (p) { return Number(p.trim()); });
            if (parts.length !== 3 || parts.some(function (p) { return !Number.isFinite(p); })) return fallback;
            return parts.map(function (p) { return p / 255; });
        }
    };
}());
