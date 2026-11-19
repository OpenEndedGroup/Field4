// Field's colour theme: "dark" (the default) or "light".
//
// Loaded as a plain script at the top of <head>, before field.css paints, so a light-theme user
// never sees a dark frame. The choice is per browser profile (localStorage), which is per user
// in the Electron app.
//
// The light theme is the dark one seen through a fixed colour filter. In Field's main page that
// filter is applied to the stylesheets, not the page (themeTwins.js), so user content keeps its
// colours; this file sets data-field-theme on <html>, and everything drawn outside CSS (the WebGL
// canvas background, Excalidraw) listens for "field-theme-change" and re-reads the CSS tokens.
// A standalone page that was never themed (history, inspector, welcome, ...) opts into the real
// filter instead with <html data-field-theme-filter>; it then gets data-field-theme-filtered and
// the filter itself, and no stylesheet twins.
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

    const FILTER = "invert(1) hue-rotate(178deg) brightness(0.85) contrast(1.2) sepia(0.05) saturate(2)";

    // FILTER as a colour function, the way Chromium applies it: each function in turn, clamped, in
    // sRGB. It matches Chromium's rendering of FILTER exactly (checked against 40 rendered swatches).
    function hueMatrix(deg) {
        const a = deg * Math.PI / 180, c = Math.cos(a), s = Math.sin(a);
        return [
            [0.213 + c * 0.787 - s * 0.213, 0.715 - c * 0.715 - s * 0.715, 0.072 - c * 0.072 + s * 0.928],
            [0.213 - c * 0.213 + s * 0.143, 0.715 + c * 0.285 + s * 0.140, 0.072 - c * 0.072 - s * 0.283],
            [0.213 - c * 0.213 - s * 0.787, 0.715 - c * 0.715 + s * 0.715, 0.072 + c * 0.928 + s * 0.072]
        ];
    }
    function sepiaMatrix(amount) {
        const t = 1 - amount;
        return [
            [0.393 + 0.607 * t, 0.769 - 0.769 * t, 0.189 - 0.189 * t],
            [0.349 - 0.349 * t, 0.686 + 0.314 * t, 0.168 - 0.168 * t],
            [0.272 - 0.272 * t, 0.534 - 0.534 * t, 0.131 + 0.869 * t]
        ];
    }
    function saturateMatrix(s) {
        return [
            [0.213 + 0.787 * s, 0.715 - 0.715 * s, 0.072 - 0.072 * s],
            [0.213 - 0.213 * s, 0.715 + 0.285 * s, 0.072 - 0.072 * s],
            [0.213 - 0.213 * s, 0.715 - 0.715 * s, 0.072 + 0.928 * s]
        ];
    }
    const HUE = hueMatrix(178), SEPIA = sepiaMatrix(0.05), SATURATE = saturateMatrix(2);
    const clamp = (c) => c.map((x) => (x < 0 ? 0 : x > 1 ? 1 : x));
    const times = (m, c) => [0, 1, 2].map((i) => m[i][0] * c[0] + m[i][1] * c[1] + m[i][2] * c[2]);

    /** Dark-theme colour (0..255) to its light-theme colour (0..255). */
    function filterRgb(r, g, b) {
        let c = [1 - r / 255, 1 - g / 255, 1 - b / 255];
        c = clamp(times(HUE, c));
        c = clamp(c.map((x) => x * 0.85));
        c = clamp(c.map((x) => (x - 0.5) * 1.2 + 0.5));
        c = clamp(times(SEPIA, c));
        c = clamp(times(SATURATE, c));
        return c.map((x) => Math.round(x * 255));
    }

    const filterMode = document.documentElement.hasAttribute("data-field-theme-filter");
    // On a filtered page, pictures are filtered back: this is FILTER's inverse (its functions undone
    // in reverse order), so an image or canvas keeps its own hues and lightness. Only the 5% sepia
    // and clamping are not undone. Field's own WebGL canvas background is interface, not a picture.
    const UNFILTER = "saturate(0.5) contrast(0.8333) brightness(1.1765) hue-rotate(-178deg) invert(1)";
    // The root element's background is painted on the canvas behind the page, which a filter on
    // <html> does not reach; a filtered page gets that background filtered by hand.
    function filterCanvasBackground() {
        const root = document.documentElement;
        root.style.backgroundColor = "";
        if (current !== "light") return;
        const opaque = (el) => {
            const c = el && getComputedStyle(el).backgroundColor;
            const m = c && c.match(/rgba?\(([^)]+)\)/);
            if (!m) return null;
            const p = m[1].split(",").map(Number);
            return p.length === 3 || p[3] > 0 ? p : null;
        };
        const bg = opaque(root) || opaque(document.body) || [255, 255, 255];
        const [r, g, b] = filterRgb(bg[0], bg[1], bg[2]);
        root.style.backgroundColor = "rgb(" + r + ", " + g + ", " + b + ")";
    }
    if (filterMode) {
        if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", filterCanvasBackground);
        else setTimeout(filterCanvasBackground, 0);
        const style = document.createElement("style");
        style.textContent = 'html[data-field-theme-filtered="light"] :is(img, video, canvas, picture):not(.field-webgl-background) { filter: ' + UNFILTER + "; }";
        document.head.appendChild(style);
    }

    function apply(theme) {
        const root = document.documentElement;
        if (filterMode) {
            root.setAttribute("data-field-theme-filtered", theme);
            root.style.filter = theme === "light" ? FILTER : "";
            if (document.body) filterCanvasBackground();
        } else {
            if (theme === "light" && window.FieldThemeTwins) window.FieldThemeTwins.ensure();
            root.setAttribute("data-field-theme", theme);
        }
        const meta = document.querySelector('meta[name="color-scheme"]');
        if (meta && !filterMode) meta.setAttribute("content", theme);
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
        const swap = function () {
            apply(theme);
            window.dispatchEvent(new CustomEvent("field-theme-change", { detail: { theme } }));
        };
        if (animated(options)) {
            // A view transition snapshots the whole window, WebGL canvas and Monaco included, swaps the
            // theme underneath and crossfades the two pictures (field.css, "Theme crossfade"). The swap
            // waits a beat before the new picture is taken so React (Excalidraw) has re-rendered; rAF
            // does not run while a transition holds rendering, so that beat is a timeout.
            const root = document.documentElement;
            root.classList.add("field-theme-transition");
            const transition = document.startViewTransition(function () {
                swap();
                return new Promise(function (resolve) { setTimeout(resolve, 40); });
            });
            transition.finished.finally(function () { root.classList.remove("field-theme-transition"); });
        } else {
            swap();
        }
        return current;
    }

    // Animate a switch the user asked for, unless the platform cannot, they prefer reduced motion,
    // or a canvas flight is being filmed (frames must show the flight and nothing else that moves).
    function animated(options) {
        if (options && options.animate === false) return false;
        if (typeof document.startViewTransition !== "function") return false;
        if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) return false;
        if (document.body && document.body.classList.contains("canvas-flight-recording")) return false;
        return true;
    }

    // Another Field tab or iframe switched: follow it.
    window.addEventListener("storage", function (event) {
        if (event.key === KEY && THEMES.indexOf(event.newValue) >= 0) set(event.newValue, { persist: false, animate: false });
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
        FILTER: FILTER,
        /** A dark-theme colour (0..255 channels) as FILTER shows it. */
        filterRgb: filterRgb,
        /** A token's "r, g, b" triple from field.css, as three 0..1 floats; `fallback` when absent. */
        rgb: function (name, fallback) {
            const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
            const parts = raw.split(",").map(function (p) { return Number(p.trim()); });
            if (parts.length !== 3 || parts.some(function (p) { return !Number.isFinite(p); })) return fallback;
            return parts.map(function (p) { return p / 255; });
        }
    };
}());
