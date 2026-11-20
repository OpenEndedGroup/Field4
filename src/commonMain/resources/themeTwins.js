// Field's light theme, derived from the dark one.
//
// The light theme is the dark theme seen through one fixed colour filter (FieldTheme.FILTER, theme.js), but applied
// to the stylesheets rather than to the page: every stylesheet rule that sets a colour gets a light
// "twin" whose colours have been passed through the filter, and only Field's own styling is
// re-coloured. Images, canvases, plots and colours a box chose keep their true colours, which a
// filter on <html> would remap.
//
// Each twin is its rule's selector prefixed with [data-field-theme="light"] (`:root`/`html` gain the
// attribute in place), so it is exactly one attribute more specific than its original and the
// cascade among twins is the cascade among originals. Values are resolved against the dark tokens
// (var() substituted, color-mix evaluated) before mapping, so a derived token gets the filter's
// colour for its own dark value rather than a mix of filtered inputs; the two differ where the
// filter clamps. The filter model (FieldTheme.filterRgb, theme.js) reproduces Chromium's rendering
// of FILTER exactly.
//
// theme.js calls FieldThemeTwins.ensure() before switching to light. The generated sheet is cached
// in localStorage and installed from there at once on a light-theme start, so the first frame is
// already light; it is regenerated after load and whenever the page's stylesheets change (Monaco
// and Kotlin add theirs late). Rules from Excalidraw's own stylesheet are left alone: the drawing
// layer switches Excalidraw to its light theme instead.
(function () {
    const FILTER = window.FieldTheme.FILTER;
    const LIGHT = '[data-field-theme="light"]';
    const CACHE_KEY = "field-theme-twins";
    const STYLE_ID = "field-theme-twins";
    const SKIP_HREF = /\/excalidraw\//;

    // The filter itself, as a colour function (theme.js).
    const filterRgb = window.FieldTheme.filterRgb;

    // ---- Parsing colours. ----
    const NAMED = {
        black: [0, 0, 0], white: [255, 255, 255], gray: [128, 128, 128], grey: [128, 128, 128],
        silver: [192, 192, 192], red: [255, 0, 0], green: [0, 128, 0], blue: [0, 0, 255],
        yellow: [255, 255, 0], orange: [255, 165, 0], lightgray: [211, 211, 211], lightgrey: [211, 211, 211],
        darkgray: [169, 169, 169], darkgrey: [169, 169, 169], dimgray: [105, 105, 105], dimgrey: [105, 105, 105],
        whitesmoke: [245, 245, 245], gainsboro: [220, 220, 220]
    };
    const COLOR_FUNCTIONS = new Set(["rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color", "color-mix"]);

    function splitTop(text, separator) {
        const out = [];
        let depth = 0, start = 0;
        for (let i = 0; i < text.length; i++) {
            const ch = text[i];
            if (ch === "(") depth++;
            else if (ch === ")") depth--;
            else if (ch === separator && depth === 0) {
                out.push(text.slice(start, i));
                start = i + 1;
            }
        }
        out.push(text.slice(start));
        return out.map((s) => s.trim());
    }

    function channel(token, scale) {
        token = token.trim();
        if (token.endsWith("%")) return (parseFloat(token) / 100) * scale;
        return parseFloat(token);
    }

    let probe = null;
    const probed = new Map();
    // A colour this parser does not handle (hsl, oklch, ...): ask the browser.
    function probeColor(text) {
        if (probed.has(text)) return probed.get(text);
        if (!probe) {
            probe = document.createElement("span");
            probe.style.display = "none";
            (document.body || document.documentElement).appendChild(probe);
        }
        probe.style.color = "";
        probe.style.color = text;
        let result = null;
        if (probe.style.color) result = parseColor(getComputedStyle(probe).color, true);
        probed.set(text, result);
        return result;
    }

    /** A colour as {r, g, b (0..255), a (0..1)}, or null. */
    function parseColor(text, noProbe) {
        const t = text.trim();
        const lower = t.toLowerCase();
        if (lower === "transparent") return { r: 0, g: 0, b: 0, a: 0 };
        if (NAMED[lower]) return { r: NAMED[lower][0], g: NAMED[lower][1], b: NAMED[lower][2], a: 1 };
        if (t[0] === "#") {
            const h = t.slice(1);
            if (!/^[0-9a-fA-F]+$/.test(h)) return null;
            const expand = h.length <= 4 ? h.split("").map((x) => x + x).join("") : h;
            if (expand.length !== 6 && expand.length !== 8) return null;
            const n = (i) => parseInt(expand.slice(i, i + 2), 16);
            return { r: n(0), g: n(2), b: n(4), a: expand.length === 8 ? n(6) / 255 : 1 };
        }
        const open = t.indexOf("(");
        if (open < 0 || !t.endsWith(")")) return null;
        const name = lower.slice(0, open).trim();
        const body = t.slice(open + 1, -1);
        if (name === "rgb" || name === "rgba") {
            let parts, alpha = "1";
            if (body.includes(",")) {
                parts = splitTop(body, ",");
                if (parts.length === 4) alpha = parts.pop();
            } else {
                const [colorPart, alphaPart] = body.split("/");
                parts = colorPart.trim().split(/\s+/);
                if (alphaPart) alpha = alphaPart;
            }
            if (parts.length !== 3) return null;
            const [r, g, b] = parts.map((p) => channel(p, 255));
            const a = channel(alpha, 1);
            if ([r, g, b, a].some((x) => !Number.isFinite(x))) return null;
            return { r, g, b, a };
        }
        if (name === "color-mix") {
            const args = splitTop(body, ",");
            if (args.length !== 3 || !/^in\s+srgb$/i.test(args[0])) return noProbe ? null : probeColor(t);
            const part = (s) => {
                const m = s.match(/^(.*?)(?:\s+(-?[\d.]+)%)?$/);
                return { color: parseColor(m[1], noProbe), pct: m[2] !== undefined ? parseFloat(m[2]) : null };
            };
            const one = part(args[1]), two = part(args[2]);
            if (!one.color || !two.color) return null;
            let p1 = one.pct, p2 = two.pct;
            if (p1 === null && p2 === null) { p1 = 50; p2 = 50; }
            else if (p1 === null) p1 = 100 - p2;
            else if (p2 === null) p2 = 100 - p1;
            const sum = p1 + p2;
            if (sum <= 0) return null;
            const multiplier = Math.min(sum, 100) / 100;
            p1 /= sum; p2 /= sum;
            const a = one.color.a * p1 + two.color.a * p2;
            const mix = (k) => (a === 0 ? 0 : (one.color[k] * one.color.a * p1 + two.color[k] * two.color.a * p2) / a);
            return { r: mix("r"), g: mix("g"), b: mix("b"), a: a * multiplier };
        }
        if (name === "color") {
            const m = body.trim().match(/^srgb\s+([\d.e-]+)\s+([\d.e-]+)\s+([\d.e-]+)(?:\s*\/\s*([\d.e-]+%?))?$/i);
            if (m) return { r: +m[1] * 255, g: +m[2] * 255, b: +m[3] * 255, a: m[4] ? channel(m[4], 1) : 1 };
        }
        return noProbe ? null : probeColor(t);
    }

    function formatColor(c) {
        const [r, g, b] = filterRgb(c.r, c.g, c.b);
        const a = Math.round(c.a * 1000) / 1000;
        return a >= 1 ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${a})`;
    }

    // ---- Resolving and mapping a declaration's value. ----
    function matchParen(text, open) {
        let depth = 0;
        for (let i = open; i < text.length; i++) {
            if (text[i] === "(") depth++;
            else if (text[i] === ")" && --depth === 0) return i;
        }
        return -1;
    }

    /** `text` with every var() it can resolve from `vars` substituted; others are left as written. */
    function resolveVars(text, vars, depth) {
        if (depth > 12 || text.indexOf("var(") < 0) return text;
        let out = "", i = 0;
        while (i < text.length) {
            const at = text.indexOf("var(", i);
            if (at < 0 || (at > 0 && /[\w-]/.test(text[at - 1]))) {
                if (at < 0) { out += text.slice(i); break; }
                out += text.slice(i, at + 4);
                i = at + 4;
                continue;
            }
            const close = matchParen(text, at + 3);
            if (close < 0) { out += text.slice(i); break; }
            out += text.slice(i, at);
            const inner = text.slice(at + 4, close);
            const comma = splitTop(inner, ",");
            const name = comma[0];
            const fallback = comma.length > 1 ? inner.slice(inner.indexOf(",") + 1) : null;
            if (vars.has(name)) out += resolveVars(vars.get(name), vars, depth + 1);
            else if (fallback !== null) out += resolveVars(fallback.trim(), vars, depth + 1);
            else out += text.slice(at, close + 1);
            i = close + 1;
        }
        return out;
    }

    /** `value` with each colour in it passed through the filter; `mapped` says whether any was. */
    function mapColors(value) {
        let out = "", i = 0, mapped = false;
        while (i < value.length) {
            const ch = value[i];
            if (ch === '"' || ch === "'") {
                const end = value.indexOf(ch, i + 1);
                const stop = end < 0 ? value.length : end + 1;
                out += value.slice(i, stop);
                i = stop;
                continue;
            }
            if (ch === "#") {
                const m = value.slice(i).match(/^#[0-9a-fA-F]{3,8}\b/);
                const c = m && parseColor(m[0]);
                if (c) {
                    out += formatColor(c);
                    mapped = true;
                    i += m[0].length;
                    continue;
                }
            }
            if (/[a-zA-Z-]/.test(ch) && (i === 0 || !/[\w-]/.test(value[i - 1]))) {
                const m = value.slice(i).match(/^-*[a-zA-Z][\w-]*/);
                if (!m) {
                    // A minus that starts a number (-1px), not a word.
                    out += ch;
                    i++;
                    continue;
                }
                const word = m[0];
                const lower = word.toLowerCase();
                const after = i + word.length;
                if (value[after] === "(") {
                    const close = matchParen(value, after);
                    if (lower === "url" || close < 0) {
                        const stop = close < 0 ? value.length : close + 1;
                        out += value.slice(i, stop);
                        i = stop;
                        continue;
                    }
                    if (COLOR_FUNCTIONS.has(lower)) {
                        const whole = value.slice(i, close + 1);
                        const c = whole.indexOf("var(") < 0 ? parseColor(whole) : null;
                        if (c) {
                            out += formatColor(c);
                            mapped = true;
                        } else {
                            out += whole;
                        }
                        i = close + 1;
                        continue;
                    }
                    out += word + "(";
                    i = after + 1;
                    continue;
                }
                if (NAMED[lower] && lower !== "transparent") {
                    out += formatColor(parseColor(lower));
                    mapped = true;
                } else {
                    out += word;
                }
                i = after;
                continue;
            }
            out += ch;
            i++;
        }
        return { value: out, mapped };
    }

    const TRIPLE = /^\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*$/;
    const COLOR_PROPERTIES = /^(color|background(-color|-image)?|border(-(top|right|bottom|left|block|inline)(-(start|end))?)?(-color)?|outline(-color)?|fill|stroke|box-shadow|text-shadow|caret-color|column-rule(-color)?|text-decoration(-color)?|text-emphasis-color|accent-color|scrollbar-color|stop-color|flood-color|lighting-color|filter|-webkit-text-fill-color|-webkit-text-stroke(-color)?|-webkit-tap-highlight-color)$/;

    /** The light twin of one declaration, or null when it needs none. */
    function twinDeclaration(property, value, vars) {
        if (property.startsWith("--")) {
            const resolved = resolveVars(value, vars, 0);
            const triple = resolved.match(TRIPLE);
            if (triple) return filterRgb(+triple[1], +triple[2], +triple[3]).join(", ");
            const result = mapColors(resolved);
            return result.mapped || value.indexOf("var(") >= 0 ? result.value : null;
        }
        if (!COLOR_PROPERTIES.test(property)) return null;
        // Every colour declaration is twinned, mapped or not, so a less specific rule's twin cannot
        // outrank a more specific original that the generator could not resolve.
        return mapColors(resolveVars(value, vars, 0)).value;
    }

    // ---- Walking the stylesheets. ----
    function declarations(style) {
        const out = [];
        for (const part of splitTop(style.cssText, ";")) {
            const colon = part.indexOf(":");
            if (colon <= 0) continue;
            const property = part.slice(0, colon).trim();
            let value = part.slice(colon + 1).trim();
            let important = false;
            if (/!\s*important$/i.test(value)) {
                important = true;
                value = value.replace(/!\s*important$/i, "").trim();
            }
            out.push({ property, value, important });
        }
        return out;
    }

    const ROOT_SELECTORS = new Set([":root", "html", '[data-field-theme="dark"]', ".field-user-theme"]);

    function twinSelector(selectorText) {
        const parts = [];
        for (const raw of splitTop(selectorText, ",")) {
            const s = raw.trim();
            if (!s || s.includes('data-field-theme="light"') || s.includes("data-field-theme='light'")) return null;
            if (s.includes('[data-field-theme="dark"]')) continue;
            if (s.startsWith("::view-transition")) continue;
            if (s.startsWith(":root")) parts.push(":root" + LIGHT + s.slice(5));
            else if (/^html(?![\w-])/.test(s)) parts.push("html" + LIGHT + s.slice(4));
            else if (s.startsWith(".field-user-theme")) parts.push(LIGHT + s);
            else parts.push(LIGHT + " " + s);
        }
        return parts.length ? parts.join(", ") : null;
    }

    function usableSheets() {
        const sheets = [];
        for (const sheet of Array.from(document.styleSheets)) {
            if (sheet.ownerNode && sheet.ownerNode.id === STYLE_ID) continue;
            if (sheet.href && SKIP_HREF.test(sheet.href)) continue;
            try {
                if (sheet.cssRules) sheets.push(sheet);
            } catch (e) {
                // A cross-origin stylesheet cannot be read; it keeps its own colours.
            }
        }
        return sheets;
    }

    function collectVars(rules, vars) {
        for (const rule of Array.from(rules)) {
            if (rule.styleSheet) {
                try { collectVars(rule.styleSheet.cssRules, vars); } catch (e) { /* unreadable import */ }
                continue;
            }
            if (rule.selectorText) {
                const selectors = splitTop(rule.selectorText, ",");
                if (!selectors.some((s) => ROOT_SELECTORS.has(s))) continue;
                for (let i = 0; i < rule.style.length; i++) {
                    const name = rule.style[i];
                    if (name.startsWith("--")) vars.set(name, rule.style.getPropertyValue(name).trim());
                }
            } else if (rule.cssRules && rule.conditionText !== undefined) {
                collectVars(rule.cssRules, vars);
            }
        }
    }

    function twinRules(rules, vars, out) {
        for (const rule of Array.from(rules)) {
            if (rule.styleSheet) {
                try { twinRules(rule.styleSheet.cssRules, vars, out); } catch (e) { /* unreadable import */ }
                continue;
            }
            if (rule.selectorText !== undefined && rule.style) {
                const selector = twinSelector(rule.selectorText);
                if (!selector) continue;
                const decls = [];
                for (const d of declarations(rule.style)) {
                    const twin = twinDeclaration(d.property, d.value, vars);
                    if (twin !== null) decls.push(`${d.property}: ${twin}${d.important ? " !important" : ""}`);
                }
                if (decls.length) out.push(`${selector} { ${decls.join("; ")} }`);
            } else if (rule.cssRules && (rule.media || rule.conditionText !== undefined)) {
                const inner = [];
                twinRules(rule.cssRules, vars, inner);
                if (!inner.length) continue;
                const at = rule.media ? `@media ${rule.media.mediaText}` : rule.constructor.name === "CSSSupportsRule"
                    ? `@supports ${rule.conditionText}` : rule.constructor.name === "CSSContainerRule"
                        ? `@container ${rule.containerName || ""} ${rule.containerQuery || rule.conditionText}` : null;
                if (at) out.push(`${at} { ${inner.join("\n")} }`);
            }
        }
    }

    function signature(sheets) {
        let s = "";
        for (const sheet of sheets) {
            const node = sheet.ownerNode;
            s += (sheet.href || (node && node.id) || "inline") + ":" + sheet.cssRules.length + ":" +
                (node && !sheet.href ? (node.textContent || "").length : "") + "|";
        }
        return s;
    }

    function generate() {
        const started = performance.now();
        const sheets = usableSheets();
        const vars = new Map();
        for (const sheet of sheets) collectVars(sheet.cssRules, vars);
        const out = [`/* Generated by themeTwins.js from the dark theme through: ${FILTER} */`];
        for (const sheet of sheets) twinRules(sheet.cssRules, vars, out);
        lastStats = { rules: out.length - 1, vars: vars.size, sheets: sheets.length, ms: Math.round(performance.now() - started) };
        return { css: out.join("\n"), sig: signature(sheets) };
    }

    // ---- Installing it. ----
    let lastSig = null;
    let lastStats = null;

    function styleElement() {
        let el = document.getElementById(STYLE_ID);
        if (!el) {
            el = document.createElement("style");
            el.id = STYLE_ID;
            (document.head || document.documentElement).appendChild(el);
        }
        return el;
    }

    function install(css) {
        const el = styleElement();
        if (el.textContent !== css) el.textContent = css;
        // Keep the twins after every other stylesheet in the head, Monaco's late ones included.
        if (el.parentNode && el.nextElementSibling) el.parentNode.appendChild(el);
    }

    function regenerate() {
        const { css, sig } = generate();
        lastSig = sig;
        install(css);
        try { window.localStorage.setItem(CACHE_KEY, css); } catch (e) { /* a full or absent store */ }
    }

    /** Make sure the twins exist and match the current stylesheets. */
    function ensure() {
        const sheets = usableSheets();
        if (lastSig !== null && signature(sheets) === lastSig && document.getElementById(STYLE_ID)) return;
        regenerate();
    }

    function lightActive() {
        return document.documentElement.getAttribute("data-field-theme") === "light";
    }

    // A light-theme start: the cached twins now, a fresh generation once the stylesheets are in.
    if (lightActive()) {
        try {
            const cached = window.localStorage.getItem(CACHE_KEY);
            if (cached) install(cached);
        } catch (e) { /* no store */ }
    }
    const afterLoad = () => { if (lightActive()) ensure(); };
    if (document.readyState === "complete") setTimeout(afterLoad, 0);
    else window.addEventListener("load", afterLoad);

    // Stylesheets arrive late (Monaco's theme, Kotlin's injected rules, insertRule calls that no
    // mutation reports): check the signature now and then while the light theme is showing.
    let pending = null;
    const soon = () => {
        if (pending) return;
        pending = setTimeout(() => { pending = null; if (lightActive()) ensure(); }, 120);
    };
    if (window.MutationObserver) {
        new MutationObserver((records) => {
            if (records.some((r) => !(r.target && (r.target.id === STYLE_ID || (r.target.parentNode && r.target.parentNode.id === STYLE_ID))))) soon();
        }).observe(document.documentElement, { childList: true, subtree: false });
        const watchHead = () => {
            if (!document.head) return;
            new MutationObserver((records) => {
                const foreign = records.some((r) => {
                    const t = r.target;
                    const el = t && t.nodeType === 3 ? t.parentNode : t;
                    if (el && el.id === STYLE_ID) return false;
                    return Array.from(r.addedNodes || []).some((n) => n.id !== STYLE_ID) || r.type === "characterData" ||
                        (el && el.tagName === "STYLE");
                });
                if (foreign) soon();
            }).observe(document.head, { childList: true, subtree: true, characterData: true });
        };
        if (document.head) watchHead();
        else document.addEventListener("DOMContentLoaded", watchHead);
    }
    setInterval(() => { if (lightActive() && document.visibilityState === "visible") ensure(); }, 2000);

    window.FieldThemeTwins = {
        FILTER,
        ensure,
        regenerate,
        filterRgb,
        stats: () => lastStats
    };
}());
