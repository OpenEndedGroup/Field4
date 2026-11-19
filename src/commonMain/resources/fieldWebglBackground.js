(function () {
    // The canvas background: a vignette per pane, two dot lattices that are the visual unit for box
    // frames, the world axes, and a flat band past a pane's world. It is drawn with a 2D canvas, not
    // WebGL: Chromium keeps at most 16 live WebGL contexts per renderer and evicts the least recently
    // drawn one past that, and a document full of three.js viewers used to evict this one and leave
    // the canvas blank. The names (`FieldWebGLBackground`, `.field-webgl-background`,
    // `__fieldRenderWebglBackground`) predate the change and are kept for their callers.
    const DEFAULTS = {
        fixed: false,
        dotSmall: 0.02,
        dotLarge: 0.04,
        axis: 0.0,
        translationX: 0,
        translationY: 0,
        zIndex: 0
    };

    // Vignette: darkens towards `edge` over this many CSS pixels from each pane edge, at most this much.
    const FALLOFF_CSS = 450;
    const SHADE_MAX = 0.28;
    // Dot lattices, in world units; the radii are CSS pixels on screen and do not scale with zoom.
    const LARGE_SPACING = 100;
    const SMALL_R0 = 0.75, SMALL_R1 = 1.25;
    const LARGE_R0 = 1.25, LARGE_R1 = 2.05;
    const DOT_ALPHA_MAX = 0.32;
    const AXIS_R0 = 0.45, AXIS_R1 = 1.05;
    const UNBOUNDED = 1e9;

    function numberOr(value, fallback) {
        const next = Number(value);
        return Number.isFinite(next) ? next : fallback;
    }

    function clamp01(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
    function smoothstep(e0, e1, x) { const t = clamp01((x - e0) / (e1 - e0)); return t * t * (3 - 2 * t); }
    function fract(x) { return x - Math.floor(x); }
    // A cheap stable hash for the dither.
    function hash(x, y) {
        let px = fract(x * 0.1031), py = fract(y * 0.11369);
        const d = px * (py + 19.19) + py * (px + 19.19);
        px += d; py += d;
        return fract((px + py) * px);
    }
    function css(rgb, alpha) {
        const c = rgb.map(v => Math.round(clamp01(v) * 255));
        return alpha === undefined ? `rgb(${c[0]},${c[1]},${c[2]})` : `rgba(${c[0]},${c[1]},${c[2]},${alpha})`;
    }

    function install(host, options) {
        const config = Object.assign({}, DEFAULTS, options || {});
        if (!host || host.__fieldWebglBackground) return host && host.__fieldWebglBackground;

        const canvas = document.createElement("canvas");
        canvas.className = "field-webgl-background";
        canvas.style.position = config.fixed ? "fixed" : "absolute";
        canvas.style.inset = "0";
        canvas.style.width = "100%";
        canvas.style.height = "100%";
        canvas.style.zIndex = String(config.zIndex);
        canvas.style.display = "block";
        canvas.style.pointerEvents = "none";
        host.insertBefore(canvas, host.firstChild);

        const ctx = canvas.getContext("2d", { alpha: false });
        if (!ctx) {
            canvas.remove();
            host.__fieldWebglBackground = { ok: false };
            return host.__fieldWebglBackground;
        }

        // The dark theme's values, for a page that loads this without field.css or theme.js.
        const DARK_COLORS = {
            center: [24 / 255, 24 / 255, 24 / 255],
            edge: [2 / 255, 2 / 255, 2 / 255],
            ink: [1, 1, 1],
            band: [52 / 255, 52 / 255, 52 / 255]
        };
        function readColors() {
            const theme = window.FieldTheme;
            if (!theme || !theme.rgb) return DARK_COLORS;
            return {
                center: theme.rgb("--field-canvas-center-rgb", DARK_COLORS.center),
                edge: theme.rgb("--field-canvas-edge-rgb", DARK_COLORS.edge),
                ink: theme.rgb("--field-canvas-ink-rgb", DARK_COLORS.ink),
                band: theme.rgb("--field-canvas-band-rgb", DARK_COLORS.band)
            };
        }
        let colors = readColors();

        // The vignette depends only on the canvas size and the pane rectangles, so it is computed into
        // its own canvas when they change and copied under every frame. One sample per CSS pixel (the
        // gradient spans hundreds of pixels), drawn unsmoothed so the dither survives.
        const vignette = { canvas: document.createElement("canvas"), key: "" };
        function paintVignette(width, height, dpr, rects) {
            const key = [width, height, dpr, JSON.stringify(rects), JSON.stringify(colors)].join("|");
            if (vignette.key === key) return;
            vignette.key = key;
            const cw = Math.ceil(width / dpr), ch = Math.ceil(height / dpr);
            vignette.canvas.width = cw;
            vignette.canvas.height = ch;
            const vctx = vignette.canvas.getContext("2d");
            const image = vctx.createImageData(cw, ch);
            const data = image.data;
            const falloff = FALLOFF_CSS * dpr;
            const c = colors.center, e = colors.edge;
            const px = new Float32Array(cw), py = new Float32Array(ch);
            for (const [x, y, w, h] of rects) {
                const i0 = Math.max(0, Math.floor(x / dpr)), i1 = Math.min(cw, Math.ceil((x + w) / dpr));
                const j0 = Math.max(0, Math.floor(y / dpr)), j1 = Math.min(ch, Math.ceil((y + h) / dpr));
                for (let i = i0; i < i1; i++) {
                    const f = (i + 0.5) * dpr;
                    px[i] = 1 - clamp01(Math.min(f - x, x + w - f) / falloff);
                }
                for (let j = j0; j < j1; j++) {
                    const f = (j + 0.5) * dpr;
                    py[j] = 1 - clamp01(Math.min(f - y, y + h - f) / falloff);
                }
                for (let j = j0; j < j1; j++) {
                    const ey = py[j] * py[j];
                    let o = (j * cw + i0) * 4;
                    for (let i = i0; i < i1; i++, o += 4) {
                        const shade = Math.min(Math.sqrt(px[i] * px[i] + ey), 1) * SHADE_MAX;
                        const dither = (hash(i, j) - 0.5) * 2.4;
                        data[o] = (c[0] + (e[0] - c[0]) * shade) * 255 + dither;
                        data[o + 1] = (c[1] + (e[1] - c[1]) * shade) * 255 + dither;
                        data[o + 2] = (c[2] + (e[2] - c[2]) * shade) * 255 + dither;
                        data[o + 3] = 255;
                    }
                }
            }
            vctx.putImageData(image, 0, 0);
        }

        // One tile of the large lattice holds a large dot at its centre and a 4×4 grid of small dots,
        // so the two combine as a clamped sum. A tile is a whole number of pixels; the pattern
        // transform scales it to the exact spacing, and the radii are computed at that scale.
        // Returns null when no dot would be visible.
        const tile = { canvas: document.createElement("canvas"), key: "", pattern: null, size: 0, scale: 1 };
        function dotTile(zoom, dpr, dotSmall, dotLarge) {
            // Zoomed far out the 25-unit dots would crowd into a haze (under ~9 screen px apart);
            // fade them, then the 100-unit dots, rather than draw noise.
            const aSmall = dotSmall * smoothstep(0.35, 0.7, zoom);
            const aLarge = dotLarge * smoothstep(0.1, 0.2, zoom);
            if (!(aSmall > 0) && !(aLarge > 0)) return null;
            const spacing = LARGE_SPACING * zoom * dpr;
            const key = [spacing, dpr, aSmall, aLarge, colors.ink.join()].join("|");
            if (tile.key === key) return tile;
            tile.key = key;
            const n = Math.max(1, Math.round(spacing));
            const k = spacing / n;  // device pixels per tile pixel
            tile.canvas.width = n;
            tile.canvas.height = n;
            tile.size = spacing;
            tile.scale = k;
            const small = new Float32Array(n * n), large = new Float32Array(n * n);
            function stamp(into, cx, cy, r0, r1) {
                const reach = Math.ceil(r1 / k) + 1;
                for (let dy = -reach; dy <= reach; dy++) {
                    const ty = Math.floor(cy) + dy;
                    const row = ((ty % n) + n) % n;
                    const ddy = (ty + 0.5 - cy) * k;
                    for (let dx = -reach; dx <= reach; dx++) {
                        const tx = Math.floor(cx) + dx;
                        const col = ((tx % n) + n) % n;
                        const ddx = (tx + 0.5 - cx) * k;
                        const m = 1 - smoothstep(r0, r1, Math.sqrt(ddx * ddx + ddy * ddy));
                        const o = row * n + col;
                        if (m > into[o]) into[o] = m;
                    }
                }
            }
            if (aSmall > 0) {
                for (let a = 0; a < 4; a++) for (let b = 0; b < 4; b++) {
                    stamp(small, n / 2 + a * n / 4, n / 2 + b * n / 4, SMALL_R0 * dpr, SMALL_R1 * dpr);
                }
            }
            if (aLarge > 0) stamp(large, n / 2, n / 2, LARGE_R0 * dpr, LARGE_R1 * dpr);
            const tctx = tile.canvas.getContext("2d");
            const image = tctx.createImageData(n, n);
            const data = image.data;
            const ink = colors.ink.map(v => Math.round(clamp01(v) * 255));
            for (let o = 0; o < n * n; o++) {
                const alpha = Math.min(DOT_ALPHA_MAX, small[o] * aSmall + large[o] * aLarge);
                if (alpha <= 0) continue;
                data[o * 4] = ink[0]; data[o * 4 + 1] = ink[1]; data[o * 4 + 2] = ink[2];
                data[o * 4 + 3] = Math.round(alpha * 255);
            }
            tctx.putImageData(image, 0, 0);
            tile.pattern = ctx.createPattern(tile.canvas, "repeat");
            return tile;
        }

        // One pane, in device pixels: its dots and axes through its own translation, then flat grey
        // past its world band.
        function drawPane(rect, tx, ty, band, dpr, zoom, dots, axis) {
            const [x, y, w, h] = rect;
            ctx.save();
            ctx.beginPath();
            ctx.rect(x, y, w, h);
            ctx.clip();
            if (dots) {
                // The tile's centre dot goes at the world origin's pixel centre.
                const ox = tx + 0.5 * dpr - dots.size / 2, oy = ty + 0.5 * dpr - dots.size / 2;
                dots.pattern.setTransform(new DOMMatrix([dots.scale, 0, 0, dots.scale, ox, oy]));
                ctx.fillStyle = dots.pattern;
                ctx.fillRect(x, y, w, h);
            }
            if (axis > 0) {
                const half = (AXIS_R0 + AXIS_R1) / 2 * dpr;
                ctx.fillStyle = css(colors.ink, axis);
                ctx.fillRect(tx - half, y, 2 * half, h);
                ctx.fillRect(x, ty - half, w, 2 * half);
            }
            const s = zoom * dpr;
            const bx0 = band[0] <= -UNBOUNDED ? -Infinity : tx + band[0] * s;
            const by0 = band[1] <= -UNBOUNDED ? -Infinity : ty + band[1] * s;
            const bx1 = band[2] >= UNBOUNDED ? Infinity : tx + band[2] * s;
            const by1 = band[3] >= UNBOUNDED ? Infinity : ty + band[3] * s;
            if (bx0 > x || by0 > y || bx1 < x + w || by1 < y + h) {
                ctx.fillStyle = css(colors.band);
                const l = Math.max(x, Math.min(x + w, bx0)), r = Math.min(x + w, Math.max(x, bx1));
                const t = Math.max(y, Math.min(y + h, by0)), b = Math.min(y + h, Math.max(y, by1));
                if (l > x) ctx.fillRect(x, y, l - x, h);
                if (r < x + w) ctx.fillRect(r, y, x + w - r, h);
                if (t > y) ctx.fillRect(l, y, r - l, t - y);
                if (b < y + h) ctx.fillRect(l, b, r - l, y + h - b);
            }
            ctx.restore();
        }

        const state = {
            ok: true,
            kind: "2d",
            hasFrame: false,
            width: 0,
            height: 0,
            dpr: 1,
            tx: numberOr(config.translationX, 0),
            ty: numberOr(config.translationY, 0),
            dotSmall: numberOr(config.dotSmall, DEFAULTS.dotSmall),
            dotLarge: numberOr(config.dotLarge, DEFAULTS.dotLarge),
            axis: numberOr(config.axis, DEFAULTS.axis),
            zoom: numberOr(config.zoom, 1),
            panesKey: "",
            panes: null,
            renderCount: 0,
            skippedCount: 0,
            // panes (optional): [[x, y, w, h, tx, ty, minX, minY, maxX, maxY], ...]: one per pane
            // of a split canvas in CSS pixels, each drawn through its own translation, and the
            // world band it shows. Absent, the whole canvas
            // is one pane at (tx, ty).
            render(tx, ty, dotSmall, dotLarge, axis, zoom, panes) {
                const dpr = Math.max(1, window.devicePixelRatio || 1);
                const cssWidth = config.fixed ? window.innerWidth : host.clientWidth;
                const cssHeight = config.fixed ? window.innerHeight : host.clientHeight;
                const width = Math.max(1, Math.round(cssWidth * dpr));
                const height = Math.max(1, Math.round(cssHeight * dpr));
                const nextTx = numberOr(tx, 0);
                const nextTy = numberOr(ty, 0);
                const nextDotSmall = numberOr(dotSmall, 0);
                const nextDotLarge = numberOr(dotLarge, 0);
                const nextAxis = numberOr(axis, 0);
                // Optional: callers that predate canvas zoom pass five arguments.
                const nextZoom = numberOr(zoom, state.zoom);
                const nextPanes = Array.isArray(panes) && panes.length ? panes : null;
                const nextPanesKey = nextPanes ? JSON.stringify(nextPanes) : "";

                if (
                    state.hasFrame &&
                    state.width === width &&
                    state.height === height &&
                    state.dpr === dpr &&
                    state.tx === nextTx &&
                    state.ty === nextTy &&
                    state.dotSmall === nextDotSmall &&
                    state.dotLarge === nextDotLarge &&
                    state.axis === nextAxis &&
                    state.zoom === nextZoom &&
                    state.panesKey === nextPanesKey
                ) {
                    state.skippedCount += 1;
                    return false;
                }

                state.tx = nextTx;
                state.ty = nextTy;
                state.dotSmall = nextDotSmall;
                state.dotLarge = nextDotLarge;
                state.axis = nextAxis;
                state.zoom = nextZoom;
                state.panes = nextPanes;
                state.panesKey = nextPanesKey;
                state.width = width;
                state.height = height;
                state.dpr = dpr;

                if (canvas.width !== width || canvas.height !== height) {
                    canvas.width = width;
                    canvas.height = height;
                }

                // Pane rectangles in device pixels, rounded as the old scissor rectangles were.
                const list = state.panes
                    ? state.panes.map(p => ({
                        rect: [Math.round(p[0] * dpr), Math.round(p[1] * dpr), Math.round(p[2] * dpr), Math.round(p[3] * dpr)],
                        tx: numberOr(p[4], 0) * dpr, ty: numberOr(p[5], 0) * dpr,
                        band: [numberOr(p[6], -UNBOUNDED), numberOr(p[7], -UNBOUNDED), numberOr(p[8], UNBOUNDED), numberOr(p[9], UNBOUNDED)]
                    }))
                    : [{ rect: [0, 0, width, height], tx: state.tx * dpr, ty: state.ty * dpr,
                        band: [-UNBOUNDED, -UNBOUNDED, UNBOUNDED, UNBOUNDED] }];

                paintVignette(width, height, dpr, list.map(p => p.rect));
                ctx.setTransform(1, 0, 0, 1, 0, 0);
                ctx.fillStyle = css(colors.center);
                ctx.fillRect(0, 0, width, height);
                ctx.imageSmoothingEnabled = false;
                ctx.drawImage(vignette.canvas, 0, 0, vignette.canvas.width * dpr, vignette.canvas.height * dpr);
                ctx.imageSmoothingEnabled = true;
                const dots = dotTile(state.zoom, dpr, state.dotSmall, state.dotLarge);
                for (const p of list) drawPane(p.rect, p.tx, p.ty, p.band, dpr, state.zoom, dots, state.axis);

                state.hasFrame = true;
                state.renderCount += 1;
                return true;
            }
        };

        host.__fieldWebglBackground = state;
        window.__fieldRenderWebglBackground = function (tx, ty, dotSmall, dotLarge, axis, zoom, panes) {
            if (host.__fieldWebglBackground && host.__fieldWebglBackground.ok) {
                return host.__fieldWebglBackground.render(tx, ty, dotSmall, dotLarge, axis, zoom, panes);
            }
            return false;
        };

        const rerender = function () {
            state.render(state.tx, state.ty, state.dotSmall, state.dotLarge, state.axis, state.zoom, state.panes);
        };
        if (!config.fixed && window.ResizeObserver) {
            new ResizeObserver(rerender).observe(host);
        }
        window.addEventListener("resize", function () {
            // A resize can reset the canvas's backing store without changing its extent; repaint
            // even when every logical parameter is unchanged.
            state.hasFrame = false;
            rerender();
        });
        window.addEventListener("field-theme-change", function () {
            colors = readColors();
            vignette.key = "";
            tile.key = "";
            state.hasFrame = false;
            rerender();
        });
        try {
            state.render(state.tx, state.ty, state.dotSmall, state.dotLarge, state.axis);
        } catch (error) {
            console.warn("[field-background] background disabled", error);
            canvas.remove();
            host.__fieldWebglBackground = { ok: false, error: String(error && error.message || error) };
        }
        return host.__fieldWebglBackground;
    }

    window.FieldWebGLBackground = Object.assign(window.FieldWebGLBackground || {}, { install });
}());
