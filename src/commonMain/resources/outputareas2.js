import { aiChatMarkdownToHtml, aiChatHistoryToHtml, send, sendWithReply } from "./afield.mjs";

const DEFAULT_W = 480;
const DEFAULT_H = 150;
const CLOSED_W = 200;
const INLINE_CHANNEL_PREFIX = "*";
const NESTED_MAX_W_PX = 1400;
const NESTED_MAX_H_PX = 900;
const NESTED_MAX_VIEWPORT_W_RATIO = 0.9;
const NESTED_MAX_VIEWPORT_H_RATIO = 0.82;
const NESTED_MIN_W = 220;
const NESTED_MIN_H = 120;
const AI_CHAT_MAX_W_PX = 960;
const AI_CHAT_MAX_H_PX = 560;
const AI_CHAT_MIN_W = 320;
const AI_CHAT_MIN_H = 180;
const AUTO_SIZE_PASSES = [0, 1, 2, 4, 8, 14, 22];

// --- Disclosure handling (new behavior) ---
function disclosureInterface(container) {
        if (container.__disclosureAPI) return container.__disclosureAPI;

        const PRESERVE_CONTENT = "__AFIELD_PRESERVE_DISCLOSURE_CONTENT__";

        const disclosures = new Map(); // id -> { wrap, hdr, cw, arr }
        const states = new Map(); // id -> { open, width, height, scrollTop, scrollLeft }

        function clamp(value, min, max) {
                const lo = Math.min(min, max);
                const hi = Math.max(min, max);
                return Math.max(lo, Math.min(hi, value));
        }

        function parsePixels(value) {
                if (typeof value !== "string") return 0;
                const parsed = parseFloat(value);
                return Number.isFinite(parsed) ? parsed : 0;
        }

        function isElementVisible(element) {
                if (!(element instanceof Element)) return false;
                const style = getComputedStyle(element);
                if (style.display === "none" || style.visibility === "hidden") return false;
                const rect = element.getBoundingClientRect();
                return rect.width > 0 || rect.height > 0;
        }

        function headerWidth(d) {
                if (!d || !d.hdr) return CLOSED_W;
                const rect = d.hdr.getBoundingClientRect();
                return Math.max(Math.ceil(rect.width), Math.ceil(d.hdr.scrollWidth), CLOSED_W);
        }

        function rangeContentSize(root) {
                if (!(root instanceof Element)) return { width: 0, height: 0 };
                const ownerDocument = root.ownerDocument || document;
                const range = ownerDocument.createRange();
                try {
                        const box = root.getBoundingClientRect();
                        let width = 0;
                        let height = 0;
                        const nodeFilter = ownerDocument.defaultView?.NodeFilter || NodeFilter;
                        const walker = ownerDocument.createTreeWalker(
                                root,
                                nodeFilter.SHOW_TEXT,
                                {
                                        acceptNode(node) {
                                                return node?.textContent?.trim()
                                                        ? nodeFilter.FILTER_ACCEPT
                                                        : nodeFilter.FILTER_REJECT;
                                        }
                                }
                        );
                        let textNode = walker.nextNode();
                        while (textNode) {
                                range.selectNodeContents(textNode);
                                const rects = Array.from(range.getClientRects ? range.getClientRects() : []);
                                rects.forEach((rect) => {
                                        width = Math.max(width, Math.ceil(rect.right - box.left));
                                        height = Math.max(height, Math.ceil(rect.bottom - box.top));
                                });
                                textNode = walker.nextNode();
                        }
                        return { width, height };
                } catch (_error) {
                        return { width: 0, height: 0 };
                } finally {
                        range.detach?.();
                }
        }

        function normalizedCssValue(value) {
                return typeof value === "string" ? value.trim().toLowerCase() : "";
        }

        function hasConcreteCssSize(value) {
                const normalized = normalizedCssValue(value);
                if (
                        !normalized ||
                        normalized === "auto" ||
                        normalized === "none" ||
                        normalized === "normal" ||
                        normalized === "stretch" ||
                        normalized === "initial" ||
                        normalized === "inherit" ||
                        normalized === "unset" ||
                        normalized === "revert" ||
                        normalized === "revert-layer" ||
                        normalized === "fit-content" ||
                        normalized === "min-content" ||
                        normalized === "max-content" ||
                        normalized === "content" ||
                        normalized === "available"
                ) {
                        return false;
                }
                if (normalized.endsWith("%")) return false;
                const parsed = parseFloat(normalized);
                if (Number.isFinite(parsed) && Math.abs(parsed) <= 0.0001) return false;
                return true;
        }

        function hasExplicitAxisSize(node, axis) {
                if (!(node instanceof Element)) return false;
                const attrName = axis === "width" ? "width" : "height";
                const styleValue = axis === "width" ? node.style.width : node.style.height;
                const minStyleValue = axis === "width" ? node.style.minWidth : node.style.minHeight;
                const maxStyleValue = axis === "width" ? node.style.maxWidth : node.style.maxHeight;
                return hasConcreteCssSize(styleValue) ||
                        hasConcreteCssSize(minStyleValue) ||
                        hasConcreteCssSize(maxStyleValue) ||
                        hasConcreteCssSize(node.getAttribute(attrName) || "");
        }

        function parentContentExtent(node, axis) {
                const parent = node?.parentElement;
                if (!(parent instanceof Element)) return 0;
                const rect = parent.getBoundingClientRect();
                const style = getComputedStyle(parent);
                if (axis === "width") {
                        return Math.max(
                                0,
                                rect.width - parsePixels(style.paddingLeft) - parsePixels(style.paddingRight)
                        );
                }
                return Math.max(
                        0,
                        rect.height - parsePixels(style.paddingTop) - parsePixels(style.paddingBottom)
                );
        }

        function fillsParentExtent(node, rect, axis) {
                const parentExtent = parentContentExtent(node, axis);
                if (parentExtent <= 0) return false;
                const size = axis === "width" ? rect.width : rect.height;
                return Math.abs(size - parentExtent) <= 2;
        }

        function alignsWithParentEdge(node, rect, axis) {
                const parent = node?.parentElement;
                if (!(parent instanceof Element)) return false;
                const parentRect = parent.getBoundingClientRect();
                const style = getComputedStyle(parent);
                if (axis === "width") {
                        const parentRight = parentRect.right - parsePixels(style.paddingRight);
                        return Math.abs(rect.right - parentRight) <= 2;
                }
                const parentBottom = parentRect.bottom - parsePixels(style.paddingBottom);
                return Math.abs(rect.bottom - parentBottom) <= 2;
        }

        function axisOverflows(node, axis) {
                if (!(node instanceof HTMLElement)) return false;
                if (axis === "width") {
                        return node.scrollWidth > node.clientWidth + 1;
                }
                return node.scrollHeight > node.clientHeight + 1;
        }

        function isStretchDisplay(display) {
                return display === "block" ||
                        display === "grid" ||
                        display === "flex" ||
                        display === "table" ||
                        display === "flow-root" ||
                        display === "list-item";
        }

        function shouldUseRectExtent(node, rect, axis) {
                if (!(node instanceof Element)) return false;

                const size = axis === "width" ? rect.width : rect.height;
                if (size <= 0.5) return false;

                const style = getComputedStyle(node);
                const display = normalizedCssValue(style.display);
                const position = normalizedCssValue(style.position);
                const overflow = axisOverflows(node, axis);
                const explicitSize = hasExplicitAxisSize(node, axis);

                // A responsive child that currently fills the disclosure is not evidence that
                // the disclosure itself should grow. Measuring it and then adding the parent's
                // padding on every pass creates a slow 480 -> 504 -> ... feedback loop. Canvas
                // backing-store dimensions are especially misleading here: they are resolution,
                // not requested CSS layout size.
                if (!overflow && fillsParentExtent(node, rect, axis)) return false;

                if (
                        node instanceof SVGElement ||
                        node instanceof HTMLImageElement ||
                        node instanceof HTMLCanvasElement ||
                        node instanceof HTMLVideoElement ||
                        node instanceof HTMLIFrameElement
                ) {
                        return true;
                }

                if (overflow || explicitSize) return true;
                if (position === "absolute" || position === "fixed" || position === "sticky") return true;
                if (
                        display === "inline" ||
                        display === "inline-block" ||
                        display === "inline-flex" ||
                        display === "inline-grid" ||
                        display === "table-cell" ||
                        display === "table-caption"
                ) {
                        return true;
                }

                if (isStretchDisplay(display) && (fillsParentExtent(node, rect, axis) || alignsWithParentEdge(node, rect, axis))) {
                        return false;
                }

                const parentExtent = parentContentExtent(node, axis);
                if (parentExtent > 0 && size < parentExtent - 2) {
                        return true;
                }

                return node.children.length === 0;
        }

        function svgIntrinsicSize(svg) {
                if (!(svg instanceof SVGElement)) return { width: 0, height: 0 };

                let width = 0;
                let height = 0;

                const widthAttr = parsePixels(svg.getAttribute("width") || "");
                const heightAttr = parsePixels(svg.getAttribute("height") || "");
                width = Math.max(width, widthAttr);
                height = Math.max(height, heightAttr);

                const viewBox = (svg.getAttribute("viewBox") || "").trim().split(/\s+/);
                if (viewBox.length === 4) {
                        width = Math.max(width, parsePixels(viewBox[2]));
                        height = Math.max(height, parsePixels(viewBox[3]));
                }

                try {
                        if (typeof svg.getBBox === "function") {
                                const box = svg.getBBox();
                                width = Math.max(width, Math.ceil(box.x + box.width));
                                height = Math.max(height, Math.ceil(box.y + box.height));
                        }
                } catch (_error) {
                        // getBBox can fail while SVG content is still settling.
                }

                const rect = svg.getBoundingClientRect();
                width = Math.max(width, Math.ceil(rect.width));
                height = Math.max(height, Math.ceil(rect.height));
                return { width, height };
        }

        // `data-afield-autosize="off"` says two things: do not wait for this frame to report a
        // size, and -- when it also declares no width of its own -- it has none to report.
        // `iframe_autosize.js` returns early for such a frame, so `afieldIntrinsicWidth` never
        // arrives and the width fallback below measures the frame's own box, which for a
        // `width:100%` frame *is* the panel we are trying to size. The measurement can only ever
        // confirm the width the panel already had; a `.h2` value explorer opened at the 200px
        // header width and stayed there forever.
        function isFillWidthIframe(iframe) {
                if (!(iframe instanceof HTMLIFrameElement)) return false;
                if (normalizedCssValue(iframe.getAttribute("data-afield-autosize")) !== "off") return false;
                if (parsePixels(iframe.dataset.afieldIntrinsicWidth || "") > 0) return false;
                return !hasConcreteCssSize(iframe.style.width) &&
                        !hasConcreteCssSize(iframe.getAttribute("width") || "");
        }

        // The honest natural width for a fill frame is the room the owning box gives the panel:
        // from the panel's own left edge to the box's right edge. Measuring outwards like this is
        // the whole point -- anything inside the panel is downstream of the width we are choosing.
        // It seeds the width and never caps it: a non-nested panel is deliberately left
        // `maxW: POSITIVE_INFINITY` by `boundsForId`, and content with a real intrinsic width still
        // wins the `Math.max` below. With no owning box (a standalone page) it contributes nothing
        // and the old behaviour stands.
        function fillWidthForDisclosure(d) {
                if (!d || d.inline || !d.cw || !d.wrap) return 0;
                if (!Array.from(d.cw.querySelectorAll("iframe")).some(isFillWidthIframe)) return 0;
                const box = typeof d.wrap.closest === "function" ? d.wrap.closest(".box") : null;
                if (!box) return 0;
                return Math.max(0, Math.floor(
                        box.getBoundingClientRect().right - d.wrap.getBoundingClientRect().left
                ));
        }

        function iframeIntrinsicSize(iframe) {
                if (!(iframe instanceof HTMLIFrameElement)) return { width: 0, height: 0 };

                let width = Math.max(
                        parsePixels(iframe.getAttribute("width") || ""),
                        parsePixels(iframe.style.width || "")
                );
                let height = Math.max(
                        parsePixels(iframe.getAttribute("height") || ""),
                        parsePixels(iframe.style.height || "")
                );
                width = Math.max(width, parsePixels(iframe.dataset.afieldIntrinsicWidth || ""));
                height = Math.max(height, parsePixels(iframe.dataset.afieldIntrinsicHeight || ""));

                if (width <= 0) {
                        const rect = iframe.getBoundingClientRect();
                        width = Math.ceil(Math.max(iframe.clientWidth || 0, rect.width || 0));
                }
                if (height <= 0) {
                        const rect = iframe.getBoundingClientRect();
                        height = Math.ceil(Math.max(iframe.clientHeight || 0, rect.height || 0));
                }

                return { width, height };
        }

        function plotlyRootFor(node) {
                if (!(node instanceof Element) || typeof node.closest !== "function") return null;
                return node.closest(".js-plotly-plot, .plotly-graph-div");
        }

        function plotlyIntrinsicSize(root) {
                if (!(root instanceof Element)) return { width: 0, height: 0 };

                const fullLayout = root._fullLayout || {};
                let width = Number(fullLayout.width) || 0;
                let height = Number(fullLayout.height) || 0;

                const mainSvg = root.querySelector("svg.main-svg");
                if (mainSvg instanceof SVGElement) {
                        width = Math.max(width, parsePixels(mainSvg.getAttribute("width") || ""));
                        height = Math.max(height, parsePixels(mainSvg.getAttribute("height") || ""));
                }

                if (width <= 0 || height <= 0) {
                        const rect = root.getBoundingClientRect();
                        width = Math.max(width, Math.ceil(rect.width || 0));
                        height = Math.max(height, Math.ceil(rect.height || 0));
                }

                return { width, height };
        }

        function contentSizeForDisclosure(d) {
                if (!d || d.inline) {
                        return { width: 0, height: 0 };
                }

                const style = getComputedStyle(d.cw);
                const paddingLeft = parsePixels(style.paddingLeft);
                const paddingRight = parsePixels(style.paddingRight);
                const paddingTop = parsePixels(style.paddingTop);
                const paddingBottom = parsePixels(style.paddingBottom);
                const paddingX = paddingLeft + paddingRight;
                const paddingY = paddingTop + paddingBottom;
                const box = d.cw.getBoundingClientRect();
                const clientWidth = Math.ceil(d.cw.clientWidth || 0);
                const clientHeight = Math.ceil(d.cw.clientHeight || 0);
                const scrollWidth = Math.ceil(d.cw.scrollWidth || 0);
                const scrollHeight = Math.ceil(d.cw.scrollHeight || 0);
                const hasIframeContent = !!d.cw.querySelector("iframe");
                let width = 0;
                let height = 0;

                if (scrollWidth > clientWidth + 1) {
                        width = Math.max(width, scrollWidth);
                }
                if (scrollHeight > clientHeight + 1) {
                        height = Math.max(height, scrollHeight);
                }

                if (!hasIframeContent) {
                        const rangeSize = rangeContentSize(d.cw);
                        // rangeContentSize is measured from the disclosure border edge, so it
                        // already includes the leading padding. Remove that here; both sides are
                        // added exactly once below.
                        width = Math.max(width, Math.max(0, rangeSize.width - paddingLeft));
                        height = Math.max(height, Math.max(0, rangeSize.height - paddingTop));
                }

                Array.from(d.cw.querySelectorAll("*")).forEach((node) => {
                        if (!(node instanceof Element) || !isElementVisible(node)) return;

                        // Rich visualizers can state their preferred outer layout size while
                        // remaining responsive with max-width:100%. This lets the disclosure jump
                        // directly to the intended size instead of trying to infer it from a child
                        // that is temporarily constrained to the current disclosure width.
                        const intrinsicWidth = parsePixels(node.dataset?.afieldIntrinsicWidth || "");
                        const intrinsicHeight = parsePixels(node.dataset?.afieldIntrinsicHeight || "");
                        if (intrinsicWidth > 0) width = Math.max(width, intrinsicWidth);
                        if (intrinsicHeight > 0) height = Math.max(height, intrinsicHeight);

                        const plotlyRoot = plotlyRootFor(node);
                        if (plotlyRoot) {
                                if (plotlyRoot !== node) return;
                                const plotlySize = plotlyIntrinsicSize(plotlyRoot);
                                width = Math.max(width, plotlySize.width);
                                height = Math.max(height, plotlySize.height);
                                return;
                        }

                        if (node instanceof HTMLIFrameElement) {
                                const iframeSize = iframeIntrinsicSize(node);
                                // An iframe stacked under a sibling -- the `.h2` explorer sits below its
                                // own summary row of `outliner` / `columns` links, in one flex column --
                                // starts partway down the content box, so its own height is not the whole
                                // content height. Every non-iframe node below already measures from the
                                // content edge; the iframe branch skipping that left the panel short by
                                // exactly the height of whatever sat above the frame.
                                const frameRect = node.getBoundingClientRect();
                                const offsetLeft = Math.max(0, frameRect.left - box.left - paddingLeft);
                                const offsetTop = Math.max(0, frameRect.top - box.top - paddingTop);
                                if (iframeSize.width > 0) {
                                        width = Math.max(width, Math.ceil(offsetLeft + iframeSize.width));
                                }
                                if (iframeSize.height > 0) {
                                        height = Math.max(height, Math.ceil(offsetTop + iframeSize.height));
                                }
                                return;
                        }

                        const rect = node.getBoundingClientRect();
                        if (shouldUseRectExtent(node, rect, "width")) {
                                width = Math.max(width, Math.ceil(rect.right - box.left - paddingLeft));
                        }
                        if (shouldUseRectExtent(node, rect, "height")) {
                                height = Math.max(height, Math.ceil(rect.bottom - box.top - paddingTop));
                        }

                        if (node instanceof SVGElement) {
                                const svgSize = svgIntrinsicSize(node);
                                width = Math.max(width, svgSize.width);
                                height = Math.max(height, svgSize.height);
                        }
                });

                width = Math.max(width + paddingX, headerWidth(d), fillWidthForDisclosure(d));
                height = Math.max(height + paddingY, 20);
                return { width, height };
        }

        function scheduleAutoSize(id, reason = "content") {
                const d = disclosures.get(id);
                if (!usesContentAutoSize(d)) return;

                const st = ensureState(id);
                st.pendingAutoSize = true;
                st.autoSizeReason = reason;
                st.autoSizeToken = (st.autoSizeToken || 0) + 1;
                states.set(id, st);

                if (!st.open) return;

                const token = st.autoSizeToken;
                const runPass = (index) => {
                        requestAnimationFrame(() => {
                                const current = disclosures.get(id);
                                const state = ensureState(id);
                                if (!current || current.inline || !state.open || state.autoSizeToken !== token) return;

                                const natural = contentSizeForDisclosure(current);
                                const currentWidth = Number(state.width) || DEFAULT_W;
                                const currentHeight = Number(state.height) || DEFAULT_H;
                                const clamped = clampSizeForId(
                                        id,
                                        Math.max(natural.width, headerWidth(current), currentWidth),
                                        Math.max(natural.height, 20, currentHeight)
                                );

                                state.width = clamped.width;
                                state.height = clamped.height;
                                state.pendingAutoSize = true;
                                states.set(id, state);
                                applyOpen(id, current);
                                refreshConnectionLayoutForNested(id);

                                if (index >= AUTO_SIZE_PASSES.length - 1) {
                                        state.pendingAutoSize = false;
                                        state.lastAutoSizedContentVersion = state.contentVersion || 0;
                                        states.set(id, state);
                                        return;
                                }

                                window.setTimeout(() => {
                                        const latest = ensureState(id);
                                        if (!latest.open || latest.autoSizeToken !== token) return;
                                        runPass(index + 1);
                                }, AUTO_SIZE_PASSES[index + 1] * 16);
                        });
                };

                runPass(0);
        }

        /**
         * A `fixed` panel whose iframe states a size should be given that size, once.
         *
         * `autoSizeMode = "fixed"` turns auto-size off, which is right: a viewer keeps the size its
         * author chose rather than being measured. But the iframe output view
         * (`outputareas.kt:186-193`) sets that mode and applies the requested height to the IFRAME
         * without ever sizing the PANEL, so the panel keeps the constants it was born with --
         * `DEFAULT_W` x `DEFAULT_H` plus the header, i.e. 480 x 174 -- and no later pass corrects it
         * because correcting it is exactly what `fixed` disabled. Dragging it was the only way out.
         *
         * So adopt the declared size here, under four deliberate limits:
         *
         *  - only for a `fixed` panel (a `content` panel already auto-sizes, and better);
         *  - only from an IFRAME's own declared size, so the div-based Monaco editor disclosure at
         *    `outputareas.kt:1039` -- also `fixed` -- is untouched;
         *  - never against the user: `userSized` still wins;
         *  - grow only, so this can never crop a panel that is already bigger.
         *
         * See `dev_docs/OUTPUT_PANEL_SIZING.md` for the trace, and
         * `run_ui_regressions.py --id viewer-panel-sizing` for the pin.
         */
        function adoptDeclaredSizeForFixedPanel(id, d) {
                if (!d || d.inline || !d.cw) return false;
                const st = ensureState(id);
                if (st.userSized) return false;

                let best = { width: 0, height: 0 };
                Array.from(d.cw.querySelectorAll("iframe")).forEach((iframe) => {
                        if (!isElementVisible(iframe)) return;
                        const size = iframeIntrinsicSize(iframe);
                        if (size.width > best.width) best.width = size.width;
                        if (size.height > best.height) best.height = size.height;
                });
                if (best.width <= 0 && best.height <= 0) return false;

                const style = getComputedStyle(d.cw);
                const paddingX = parsePixels(style.paddingLeft) + parsePixels(style.paddingRight);
                const paddingY = parsePixels(style.paddingTop) + parsePixels(style.paddingBottom);
                const clamped = clampSizeForId(
                        id,
                        Math.max(best.width + paddingX, headerWidth(d), Number(st.width) || DEFAULT_W),
                        Math.max(best.height + paddingY, 20, Number(st.height) || DEFAULT_H)
                );
                if (Math.abs(clamped.width - (Number(st.width) || 0)) <= 1 &&
                        Math.abs(clamped.height - (Number(st.height) || 0)) <= 1) {
                        return false;
                }

                st.width = clamped.width;
                st.height = clamped.height;
                states.set(id, st);
                applyOpen(id, d);
                refreshConnectionLayoutForNested(id);
                return true;
        }

        function markContentChanged(id, reason = "content-change") {
                const st = ensureState(id);
                st.contentVersion = (st.contentVersion || 0) + 1;
                states.set(id, st);
                const d = disclosures.get(id);
                if (!st.userSized && usesContentAutoSize(d)) {
                        scheduleAutoSize(id, reason);
                } else if (!st.userSized) {
                        adoptDeclaredSizeForFixedPanel(id, d);
                }
        }

        function observeContentTargets(id, d) {
                if (!d || d.inline || !d.contentResizeObserver) return;
                if (d.observedContentTargets) {
                        d.observedContentTargets.forEach((target) => {
                                try {
                                        d.contentResizeObserver.unobserve(target);
                                } catch (_error) {
                                }
                        });
                }

                const targets = new Set();
                Array.from(d.cw.children).forEach((child) => {
                        if (child instanceof Element) targets.add(child);
                });
                d.cw.querySelectorAll("iframe, svg, img, .plotly, .value-viz-h2").forEach((node) => {
                        if (node instanceof Element) targets.add(node);
                });

                d.observedContentTargets = Array.from(targets);
                d.observedContentTargets.forEach((target) => {
                        try {
                                d.contentResizeObserver.observe(target);
                        } catch (_error) {
                        }
                });
        }

        function bindContentLoadEvents(id, d) {
                if (!d || d.inline) return;
                d.cw.querySelectorAll("iframe, img").forEach((node) => {
                        if (!(node instanceof Element)) return;
                        if (!node.__afieldAutoSizeBound) {
                                node.__afieldAutoSizeBound = true;
                                node.addEventListener("load", () => markContentChanged(id, "load"));
                                node.addEventListener("error", () => markContentChanged(id, "error"));
                        }
                        if (node instanceof HTMLIFrameElement && !node.__afieldIframeSizeBound) {
                                node.__afieldIframeSizeBound = true;
                                node.addEventListener("afield-iframe-size", (event) => {
                                        const detail = event?.detail || {};
                                        const width = Math.max(0, Math.ceil(Number(detail.width) || 0));
                                        const height = Math.max(0, Math.ceil(Number(detail.height) || 0));
                                        const changed =
                                                Math.abs(parsePixels(node.dataset.afieldIntrinsicWidth || "") - width) > 1 ||
                                                Math.abs(parsePixels(node.dataset.afieldIntrinsicHeight || "") - height) > 1;
                                        if (width > 0) node.dataset.afieldIntrinsicWidth = String(width);
                                        if (height > 0) node.dataset.afieldIntrinsicHeight = String(height);
                                        if (changed || detail.changed) {
                                                markContentChanged(id, "iframe-report");
                                        }
                                });
                        }
                });
        }

        function boundsForId(id) {
                if (id === "ai-chat") {
                        const viewportW = Math.max(window.innerWidth || 0, DEFAULT_W);
                        const viewportH = Math.max(window.innerHeight || 0, DEFAULT_H);
                        return {
                                minW: AI_CHAT_MIN_W,
                                minH: AI_CHAT_MIN_H,
                                maxW: Math.max(AI_CHAT_MIN_W, Math.min(AI_CHAT_MAX_W_PX, Math.floor(viewportW * 0.9))),
                                maxH: Math.max(AI_CHAT_MIN_H, Math.min(AI_CHAT_MAX_H_PX, Math.floor(viewportH * 0.65)))
                        };
                }
                if (id !== "nested") {
                        return {
                                minW: 50,
                                minH: 20,
                                maxW: Number.POSITIVE_INFINITY,
                                maxH: Number.POSITIVE_INFINITY
                        };
                }

                const viewportW = Math.max(window.innerWidth || 0, DEFAULT_W);
                const viewportH = Math.max(window.innerHeight || 0, DEFAULT_H);
                return {
                        minW: NESTED_MIN_W,
                        minH: NESTED_MIN_H,
                        maxW: Math.max(NESTED_MIN_W, Math.min(NESTED_MAX_W_PX, Math.floor(viewportW * NESTED_MAX_VIEWPORT_W_RATIO))),
                        maxH: Math.max(NESTED_MIN_H, Math.min(NESTED_MAX_H_PX, Math.floor(viewportH * NESTED_MAX_VIEWPORT_H_RATIO)))
                };
        }

        function clampSizeForId(id, width, height) {
                const bounds = boundsForId(id);
                return {
                        width: clamp(width, bounds.minW, bounds.maxW),
                        height: clamp(height, bounds.minH, bounds.maxH)
                };
        }

        function applySizeBoundsForId(id, d) {
                if (!d || d.inline) return;
                if (id !== "nested") {
                        d.cw.style.maxWidth = "none";
                        d.cw.style.maxHeight = "none";
                        d.cw.style.minWidth = "0px";
                        d.cw.style.minHeight = "0px";
                        d.wrap.style.maxWidth = "none";
                        return;
                }
                const bounds = boundsForId(id);
                d.cw.style.maxWidth = Number.isFinite(bounds.maxW) ? `${bounds.maxW}px` : "none";
                d.cw.style.maxHeight = Number.isFinite(bounds.maxH) ? `${bounds.maxH}px` : "none";
                d.cw.style.minWidth = `${bounds.minW}px`;
                d.cw.style.minHeight = `${bounds.minH}px`;
                d.wrap.style.maxWidth = Number.isFinite(bounds.maxW) ? `${bounds.maxW}px` : "none";
        }

        function refreshConnectionLayoutForNested(id) {
                if (id !== "nested") return;
                const gestures = window.canvasGestures;
                const rebuild = (gestures && typeof gestures.rebuildConnectionGroup === "function")
                        ? () => gestures.rebuildConnectionGroup(false)
                        : (typeof window.rebuildConnectionGroup === "function")
                                ? () => window.rebuildConnectionGroup(false)
                                : null;
                if (!rebuild) return;
                requestAnimationFrame(() => {
                        rebuild();
                });
                requestAnimationFrame(() => {
                        requestAnimationFrame(() => {
                                rebuild();
                        });
                });
        }

        function isInlineId(id) {
                return typeof id === "string" && id.startsWith(INLINE_CHANNEL_PREFIX);
        }

        function autoSizeModeForDisclosure(d) {
                if (!d || d.inline) return "content";
                const mode = d.cw?.dataset?.afieldAutoSizeMode;
                return typeof mode === "string" && mode.trim()
                        ? mode.trim().toLowerCase()
                        : "content";
        }

        function usesContentAutoSize(d) {
                return !d?.inline && autoSizeModeForDisclosure(d) !== "fixed";
        }

        function inlineTitle(title) {
                if (typeof title !== "string") return "";
                const cleaned = title.replace(/^\*+/, "").trim();
                return cleaned || title;
        }

        function ensureState(id) {
            if (!states.has(id)) {
                states.set(id, {
                        open: false,
                        width: DEFAULT_W,
                        height: DEFAULT_H,
                        scrollTop: 0,
                        scrollLeft: 0,
                        contentVersion: 0,
                        lastAutoSizedContentVersion: -1,
                        autoSizeToken: 0,
                        pendingAutoSize: false,
                        userSized: false
                });
            }
            return states.get(id);
        }

        function saveScroll(id, d) {
                if (d.inline) return;
                const st = ensureState(id);
                st.scrollTop = d.cw.scrollTop;
                st.scrollLeft = d.cw.scrollLeft;
                states.set(id, st);
        }

        function rememberSize(id, d) {
                if (d.inline) return;
                if (d.cw.style.display === "none") return;
                const st = ensureState(id);
                const rect = d.cw.getBoundingClientRect();
                const clamped = clampSizeForId(id, Math.max(rect.width, 50), Math.max(d.cw.clientHeight, 20));
                const changed = Math.abs((st.width || DEFAULT_W) - clamped.width) > 2 ||
                        Math.abs((st.height || DEFAULT_H) - clamped.height) > 2;
                st.width = clamped.width;
                st.height = clamped.height;
                if (changed) {
                        st.userSized = true;
                }
                states.set(id, st);
                d.wrap.style.width = `${st.width}px`;
                refreshConnectionLayoutForNested(id);
        }

        function applyClosed(d) {
                if (d.inline) return;
                d.selfResizeUntil = Date.now() + 250;
                d.wrap.classList.remove("open");
                d.cw.style.display = "none";
                d.cw.style.height = "0px";
                d.cw.style.padding = "0px";
                d.wrap.style.width = `${CLOSED_W}px`;
        }

        function applyOpen(id, d) {
                if (d.inline) return;
                const st = ensureState(id);
                applySizeBoundsForId(id, d);
                const clamped = clampSizeForId(id, st.width || DEFAULT_W, st.height || DEFAULT_H);
                const w = clamped.width;
                const h = clamped.height;
                st.width = w;
                st.height = h;
                d.wrap.classList.add("open");
                d.cw.style.display = "block";
                d.cw.style.padding = d.cw.dataset.origPadding || "0px";
                d.cw.style.width = `${w}px`;
                d.cw.style.height = `${h}px`;
                d.cw.scrollTop = st.scrollTop || 0;
                d.cw.scrollLeft = st.scrollLeft || 0;
                d.wrap.style.width = `${w}px`;
                // We just resized d.cw. Content sized relative to the container will
                // resize too and fire d.contentResizeObserver; mark a short window so
                // that self-caused resize is NOT treated as a real content change and
                // does not reschedule auto-size. Without this, content whose natural
                // size tracks the container (e.g. forth-visualization-helpers) drove an
                // applyOpen->resize->markContentChanged->auto-size feedback loop of
                // ~11k layout passes per capture -> visible flicker and an apparent hang.
                d.selfResizeUntil = Date.now() + 250;
        }

        function primeAutoSizeForOpen(id, d) {
                if (!d || d.inline || !usesContentAutoSize(d)) return;
                const st = ensureState(id);
                if (st.userSized || st.lastAutoSizedContentVersion === (st.contentVersion || 0)) return;

                const previous = {
                        display: d.cw.style.display,
                        visibility: d.cw.style.visibility,
                        padding: d.cw.style.padding,
                        width: d.cw.style.width,
                        height: d.cw.style.height,
                        overflow: d.cw.style.overflow,
                        wrapWidth: d.wrap.style.width
                };

                // Same guard applyOpen uses: this function resizes d.cw to measure it, and the
                // user-resize observer must not read that as a person dragging the grip.
                d.selfResizeUntil = Date.now() + 250;
                applySizeBoundsForId(id, d);
                d.wrap.classList.add("open");
                d.cw.style.display = "block";
                d.cw.style.visibility = "hidden";
                d.cw.style.padding = d.cw.dataset.origPadding || "0px";
                d.cw.style.width = `${st.width || DEFAULT_W}px`;
                d.cw.style.height = "auto";
                d.cw.style.overflow = "visible";
                d.wrap.style.width = `${st.width || DEFAULT_W}px`;

                const natural = contentSizeForDisclosure(d);
                const clamped = clampSizeForId(
                        id,
                        Math.max(natural.width, headerWidth(d)),
                        Math.max(natural.height, 20)
                );
                st.width = clamped.width;
                st.height = clamped.height;
                states.set(id, st);

                d.cw.style.display = previous.display;
                d.cw.style.visibility = previous.visibility;
                d.cw.style.padding = previous.padding;
                d.cw.style.width = previous.width;
                d.cw.style.height = previous.height;
                d.cw.style.overflow = previous.overflow;
                d.wrap.style.width = previous.wrapWidth;
                d.wrap.classList.remove("open");
        }

        function ensureBehaviors(id, d) {
                if (d.inline) return;
                d.cw.style.overflow = "auto";
                d.cw.style.resize = "both";
                d.cw.style.boxSizing = "border-box";
                d.cw.style.maxWidth = "none";
                d.cw.style.minHeight = "0px";
                applySizeBoundsForId(id, d);

                if (!d.cw.dataset.origPadding) {
                        const cs = getComputedStyle(d.cw);
                        d.cw.dataset.origPadding = cs.padding;
                }

                d.cw.addEventListener("scroll", () => saveScroll(id, d));
                d.cw.addEventListener("mouseup", () => rememberSize(id, d));
                d.cw.addEventListener("mouseleave", () => rememberSize(id, d));

                // A drag of the native `resize: both` grip is only recorded if `rememberSize` runs,
                // and the two listeners above are both mouse events on `d.cw` — which is exactly the
                // element a panel full of iframe does not reliably get them on. Mouse events raised
                // inside an iframe belong to that iframe's document and never reach this one
                // (measured), so a release over the frame is simply lost; then the next auto-size
                // pass reads the stale `st.width`/`st.height` and the panel snaps back to the size
                // it had before the drag.
                //
                // Observing the box removes the question of where the pointer went. The native
                // resizer's whole effect is inline width/height on `d.cw`, so the box changing size
                // outside a self-caused window *is* the user having resized it.
                if (!d.userResizeObserver && typeof ResizeObserver !== "undefined") {
                        d.userResizeObserver = new ResizeObserver(() => {
                                // applyOpen and primeAutoSizeForOpen both resize d.cw themselves;
                                // recording those would set `userSized` and permanently disable
                                // auto-size for a panel nobody touched.
                                if (d.selfResizeUntil && Date.now() < d.selfResizeUntil) return;
                                if (d.cw.style.display === "none") return;
                                rememberSize(id, d);
                        });
                        d.userResizeObserver.observe(d.cw);
                }

                if (!d.contentMutationObserver && typeof MutationObserver !== "undefined") {
                        d.contentMutationObserver = new MutationObserver(() => {
                                bindContentLoadEvents(id, d);
                                observeContentTargets(id, d);
                                markContentChanged(id, "mutation");
                        });
                        d.contentMutationObserver.observe(d.cw, {
                                childList: true,
                                subtree: true,
                                characterData: true
                        });
                }

                if (!d.contentResizeObserver && typeof ResizeObserver !== "undefined") {
                        d.contentResizeObserver = new ResizeObserver(() => {
                                // Ignore resizes we caused ourselves in applyOpen (see
                                // d.selfResizeUntil) -- reacting to them reschedules auto-size
                                // and forms a non-converging feedback loop. Real content
                                // growth still arrives via the MutationObserver path.
                                if (d.selfResizeUntil && Date.now() < d.selfResizeUntil) return;
                                markContentChanged(id, "resize");
                        });
                }
                bindContentLoadEvents(id, d);
                observeContentTargets(id, d);

                if (id === "nested" && typeof ResizeObserver !== "undefined" && !d.resizeObserver) {
                        let prevW = -1;
                        let prevH = -1;
                        d.resizeObserver = new ResizeObserver((entries) => {
                                const entry = entries && entries[0];
                                if (!entry) return;
                                const w = Math.round(entry.contentRect.width);
                                const h = Math.round(entry.contentRect.height);
                                if (w === prevW && h === prevH) return;
                                prevW = w;
                                prevH = h;
                                refreshConnectionLayoutForNested(id);
                        });
                        d.resizeObserver.observe(d.cw);
                }
        }

        function open(id) {
                const d = disclosures.get(id);
                if (!d) return;
                if (d.inline) {
                        const st = ensureState(id);
                        st.open = true;
                        states.set(id, st);
                        return;
                }
                const st = ensureState(id);
                if (!st.width) st.width = DEFAULT_W;
                if (!st.height) st.height = DEFAULT_H;
                primeAutoSizeForOpen(id, d);
                applyOpen(id, d);
                st.open = true;
                states.set(id, st);
                bindContentLoadEvents(id, d);
                observeContentTargets(id, d);
                if (usesContentAutoSize(d) && !st.userSized && st.lastAutoSizedContentVersion !== (st.contentVersion || 0)) {
                        scheduleAutoSize(id, "open");
                }
                refreshConnectionLayoutForNested(id);
        }

        function close(id) {
                const d = disclosures.get(id);
                if (!d) return;
                if (d.inline) {
                        const st = ensureState(id);
                        st.open = false;
                        states.set(id, st);
                        return;
                }
                rememberSize(id, d);
                saveScroll(id, d);
                const st = ensureState(id);
                st.open = false;
                states.set(id, st);
                applyClosed(d);
                refreshConnectionLayoutForNested(id);
        }

        function toggle(id) {
                const st = ensureState(id);
                st.open ? close(id) : open(id);
        }

        function remove(id) {
                const d = disclosures.get(id);
                if (!d) return;
                if (d.resizeObserver && typeof d.resizeObserver.disconnect === "function") {
                        d.resizeObserver.disconnect();
                        d.resizeObserver = null;
                }
                if (d.contentMutationObserver && typeof d.contentMutationObserver.disconnect === "function") {
                        d.contentMutationObserver.disconnect();
                        d.contentMutationObserver = null;
                }
                if (d.userResizeObserver && typeof d.userResizeObserver.disconnect === "function") {
                        d.userResizeObserver.disconnect();
                        d.userResizeObserver = null;
                }
                if (d.contentResizeObserver && typeof d.contentResizeObserver.disconnect === "function") {
                        d.contentResizeObserver.disconnect();
                        d.contentResizeObserver = null;
                }
                d.observedContentTargets = null;
                // Dispose any embedded Monaco editor inside this disclosure
                const embEditorId = d.cw && d.cw.dataset && d.cw.dataset.embeddedEditorId;
                if (embEditorId && window.embeddedEditors && typeof window.embeddedEditors.dispose === "function") {
                        window.embeddedEditors.dispose(embEditorId);
                }
                d.wrap.remove();
                disclosures.delete(id);
                states.delete(id);
        }

        function closeAll() {
                for (const id of disclosures.keys()) {
                        close(id);
                }
        }

        function solo(id) {
                const st = ensureState(id);
                const wasOpen = st.open;
                closeAll();
                if (!wasOpen) {
                        open(id);
                }
        }

        function removeAllExcept(id) {
                const ids = Array.from(disclosures.keys());
                for (const otherId of ids) {
                        if (otherId !== id) {
                                remove(otherId);
                        }
                }
        }

        function clearRemoveIndicators() {
                for (const d of disclosures.values()) {
                        if (d.inline) continue;
                        d.arr.classList.remove("remove-mode");
                }
        }

        function updateRemoveIndicators(id, event) {
                const current = disclosures.get(id);
                if (!current || current.inline) return;
                if (event.altKey && event.shiftKey) {
                        for (const [otherId, d] of disclosures.entries()) {
                                if (d.inline) continue;
                                d.arr.classList.toggle("remove-mode", otherId !== id);
                        }
                        return;
                }
                if (event.altKey) {
                        for (const [otherId, d] of disclosures.entries()) {
                                if (d.inline) continue;
                                d.arr.classList.toggle("remove-mode", otherId === id);
                        }
                        return;
                }
                clearRemoveIndicators();
        }

        function upsertDisclosure(id, title, content) {
                let d = disclosures.get(id);
                if (!d) {
                        const inline = isInlineId(id);
                        const wrap = document.createElement("div");
                        wrap.className = inline ? "disclosure disclosure-inline" : "disclosure";
                        wrap.__disclosureId = id;
                        const hdr = document.createElement("div");
                        hdr.className = inline ? "disclosure-header disclosure-header-inline no-hover" : "disclosure-header";
                        const arr = document.createElement("div");
                        arr.className = inline ? "inline-channel" : "arrow";
                        const ttl = document.createElement("div");
                        ttl.className = inline ? "inline-value" : "title";
                        if (inline) {
                                arr.textContent = inlineTitle(title);
                                ttl.innerHTML = content || "";
                                decorateAfieldLinks(ttl);
                        } else {
                                ttl.textContent = title;
                        }
                        hdr.append(arr, ttl);
                        const cw = document.createElement("div");
                        cw.className = "disclosure-content";
                        cw.innerHTML = content || "";
                        decorateAfieldLinks(cw);

                        if (!inline) {
                                const updateIndicators = (e) => {
                                        updateRemoveIndicators(id, e);
                                };

                                arr.addEventListener("mouseenter", updateIndicators);
                                arr.addEventListener("mousemove", updateIndicators);
                                arr.addEventListener("mousedown", updateIndicators);
                                arr.addEventListener("mouseleave", () => clearRemoveIndicators());
                        }

                        hdr.addEventListener("click", (e) => {
                                if (!e.target.closest("input, label")) {
                                        if (inline) {
                                                if (e.altKey && e.shiftKey) {
                                                        removeAllExcept(id);
                                                        e.stopPropagation();
                                                } else if (e.altKey) {
                                                        remove(id);
                                                        e.stopPropagation();
                                                }
                                                return;
                                        }
                                        if (e.altKey && e.shiftKey) {
                                                // Alt+Shift+click removes all other disclosures
                                                removeAllExcept(id);
                                                clearRemoveIndicators();
                                                e.stopPropagation();
                                        } else if (e.altKey) {
                                                // Alt+click removes the disclosure
                                                remove(id);
                                                e.stopPropagation();
                                        } else if (e.shiftKey) {
                                                // Shift+click solos this disclosure
                                                solo(id);
                                        } else {
                                                // Normal click toggles
                                                toggle(id);
                                        }
                                }
                        });

                        wrap.append(hdr, cw);
                        container.appendChild(wrap);
                        d = { wrap, hdr, cw, arr, inline, inlineValue: ttl };
                        disclosures.set(id, d);
                        ensureBehaviors(id, d);
                        if (!inline) {
                                applyClosed(d);
                        }
                } else {
                        const { hdr, cw, inline, arr, inlineValue } = d;
                        if (inline) {
                                arr.textContent = inlineTitle(title);
                                if (content !== PRESERVE_CONTENT) {
                                        inlineValue.innerHTML = content || "";
                                        decorateAfieldLinks(inlineValue);
                                }
                        } else {
                                hdr.querySelector(".title").textContent = title;
                                if (content !== PRESERVE_CONTENT) {
                                        cw.innerHTML = content || "";
                                        decorateAfieldLinks(cw);
                                }
                        }
                }

                if (!d.inline) {
                        bindContentLoadEvents(id, d);
                        observeContentTargets(id, d);
                        markContentChanged(id, "upsert");
                }

                const current = disclosures.get(id);
                return current.inline ? current.inlineValue : current.cw;
        }

        function setContent(id, content) {
                const d = disclosures.get(id);
                if (!d) return;
                if (d.inline) {
                        d.inlineValue.innerHTML = content || "";
                        decorateAfieldLinks(d.inlineValue);
                        return;
                }
                d.cw.innerHTML = content || "";
                decorateAfieldLinks(d.cw);
                bindContentLoadEvents(id, d);
                observeContentTargets(id, d);
                markContentChanged(id, "set-content");
        }

        if (!container.__boxLinkSelectionInstalled) {
                container.__boxLinkSelectionInstalled = true;
                container.addEventListener("click", (event) => {
                        const target = event.target;
                        if (!(target instanceof Element)) return;

                        const link = target.closest("a[data-box-uid], a[href^='#box:']");
                        if (!link || !container.contains(link)) return;

                        const navigation = decodeBoxNavigationTarget(link);
                        if (!navigation.uid) return;

                        event.preventDefault();
                        event.stopPropagation();
                        selectBoxByUidFromOutput(
                                navigation.uid,
                                navigation.property,
                                navigation.scrollToLine,
                                navigation.highlightLine
                        );
                });
        }

        const api = { upsertDisclosure, setContent, toggle, open, close, remove, closeAll, solo };
        container.__disclosureAPI = api;
        return api;
}

window.disclosureInterface = disclosureInterface;
window.aiChatMarkdownToHtml = aiChatMarkdownToHtml;
window.aiChatHistoryToHtml = aiChatHistoryToHtml;

function findBoxByUid(uid) {
        const boxes = document.getElementsByClassName("box");
        for (let i = 0; i < boxes.length; i++) {
                const box = boxes[i];
                if (box.uid === uid) return box;
        }
        return null;
}

function parseAfieldRef(raw) {
        if (typeof raw !== "string") return null;
        const trimmed = raw.trim();
        const match = trimmed.match(/^afield:\/\/([^/\s]+)\/([^/\s]+)\/([A-Za-z0-9_-]+)(?:[?#][^\s)\]}<>"']*)?$/);
        if (!match) return null;
        return {
                document: match[1],
                uid: match[2],
                property: match[3]
        };
}

function afieldRefHref(uid, property) {
        const params = new URLSearchParams();
        if (property) params.set("property", property);
        const query = params.toString();
        return `#box:${encodeURIComponent(uid)}${query ? "?" + query : ""}`;
}

function shortUid(uid) {
        if (typeof uid !== "string") return "";
        return uid.length > 8 ? uid.slice(0, 8) : uid;
}

function boxDisplayNameForAfieldRef(uid, documentName) {
        const box = findBoxByUid(uid);
        const dynamicName = box && typeof box.name === "string" ? box.name.trim() : "";
        if (dynamicName) return dynamicName;
        const attrName = box && typeof box.getAttribute === "function"
                ? (box.getAttribute("data-name") || box.getAttribute("name") || "").trim()
                : "";
        if (attrName) return attrName;
        return shortUid(uid) || documentName || "box";
}

function afieldRefLabel(ref) {
        return `${boxDisplayNameForAfieldRef(ref.uid, ref.document)} / ${ref.property || "code"}`;
}

function configureAfieldRefLink(link, ref) {
        if (!link || !ref || !ref.uid) return;
        link.href = afieldRefHref(ref.uid, ref.property || "code");
        link.dataset.boxUid = ref.uid;
        link.dataset.boxProperty = ref.property || "code";
        link.dataset.afieldDocument = ref.document || "";
        link.dataset.afieldRef = `afield://${ref.document}/${ref.uid}/${ref.property}`;
        link.classList.add("afield-ref-link");
        link.title = link.dataset.afieldRef;
        link.textContent = afieldRefLabel(ref);
}

function linkifyAfieldRefsInTextNode(textNode) {
        const text = textNode.nodeValue || "";
        const pattern = /afield:\/\/([^/\s]+)\/([^/\s]+)\/([A-Za-z0-9_-]+)(?:[?#][^\s)\]}<>"']*)?/g;
        let match;
        let cursor = 0;
        const fragment = document.createDocumentFragment();
        let changed = false;

        while ((match = pattern.exec(text)) !== null) {
                const raw = match[0];
                const ref = parseAfieldRef(raw);
                if (!ref) continue;
                if (match.index > cursor) {
                        fragment.appendChild(document.createTextNode(text.slice(cursor, match.index)));
                }
                const link = document.createElement("a");
                configureAfieldRefLink(link, ref);
                fragment.appendChild(link);
                cursor = match.index + raw.length;
                changed = true;
        }

        if (!changed) return;
        if (cursor < text.length) {
                fragment.appendChild(document.createTextNode(text.slice(cursor)));
        }
        textNode.parentNode?.replaceChild(fragment, textNode);
}

function decorateAfieldLinks(root) {
        if (!root) return;
        const anchors = root.querySelectorAll("a[href^='afield://']");
        anchors.forEach((link) => {
                const ref = parseAfieldRef(link.getAttribute("href") || "");
                if (ref) configureAfieldRefLink(link, ref);
        });

        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                acceptNode(node) {
                        const parent = node.parentElement;
                        if (!parent) return NodeFilter.FILTER_REJECT;
                        if (parent.closest("a, pre, code, textarea, input, script, style")) {
                                return NodeFilter.FILTER_REJECT;
                        }
                        return (node.nodeValue || "").includes("afield://")
                                ? NodeFilter.FILTER_ACCEPT
                                : NodeFilter.FILTER_REJECT;
                }
        });
        const nodes = [];
        while (walker.nextNode()) nodes.push(walker.currentNode);
        nodes.forEach(linkifyAfieldRefsInTextNode);
}

function decodeBoxNavigationTarget(link) {
        const href = (link && link.getAttribute && link.getAttribute("href")) || "";
        const fromHref = decodeBoxTargetFromHref(href);
        const uid = ((link.getAttribute("data-box-uid") || "").trim()) || fromHref.uid || "";
        const property = ((link.getAttribute("data-box-property") || "").trim()) || fromHref.property || "";
        const scrollToLine = parseOptionalInt(link.getAttribute("data-box-scroll-to-line")) ?? fromHref.scrollToLine;
        const highlightLine = parseOptionalInt(link.getAttribute("data-box-highlight-line")) ?? fromHref.highlightLine;
        return { uid, property, scrollToLine, highlightLine };
}

function decodeBoxTargetFromHref(href) {
        if (typeof href !== "string") return { uid: "", property: "", scrollToLine: null, highlightLine: null };
        if (!href.startsWith("#box:")) return { uid: "", property: "", scrollToLine: null, highlightLine: null };

        const raw = href.slice(5).trim();
        if (!raw) return { uid: "", property: "", scrollToLine: null, highlightLine: null };

        const queryStart = raw.indexOf("?");
        const encodedUid = queryStart >= 0 ? raw.slice(0, queryStart) : raw;
        const query = queryStart >= 0 ? raw.slice(queryStart + 1) : "";
        let uid = encodedUid;
        try {
                uid = decodeURIComponent(encodedUid);
        } catch (_error) {}

        const params = new URLSearchParams(query);
        return {
                uid,
                property: params.get("property") || "",
                scrollToLine: parseOptionalInt(params.get("scrollToLine") || params.get("line")),
                highlightLine: parseOptionalInt(params.get("highlightLine") || params.get("line"))
        };
}

function parseOptionalInt(value) {
        if (value === null || value === undefined || value === "") return null;
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) ? parsed : null;
}

function selectBoxByUidFromOutput(uid, property = "", scrollToLine = null, highlightLine = null) {
        if (!uid) return false;
        const navigate = window.navigateToBoxProperty;
        if (typeof navigate === "function") {
                try {
                        return navigate(uid, property || "code", scrollToLine, highlightLine) !== false;
                } catch (error) {
                        console.info("[output] navigateToBoxProperty failed", error);
                }
        }

        const box = findBoxByUid(uid);
        if (!box) {
                if (typeof window.notify === "function") {
                        window.notify(`Box not found: ${uid}`, "output");
                }
                return false;
        }

        const currentlySelected = Array.isArray(window.selectedList)
                ? Array.from(window.selectedList)
                : Array.from(window.selected || []);
        currentlySelected.forEach((selectedBox) => {
                if (typeof window.deselectBox === "function") {
                        window.deselectBox(selectedBox);
                }
        });

        if (typeof window.selectBox === "function") {
                window.selectBox(box);
        }
        if (property && typeof window.switchToProperty === "function") {
                try {
                        window.switchToProperty(uid, property);
                } catch (error) {
                        console.info("[output] switchToProperty fallback failed", error);
                }
        }
        if (typeof box.scrollIntoView === "function") {
                box.scrollIntoView({ behavior: "smooth", block: "center", inline: "nearest" });
        }
        return true;
}

function readBoxProperty(box, property) {
        if (!box) return "";
        if (property && typeof box[property] === "string") return box[property];
        if (typeof box.code === "string") return box.code;
        return "";
}

function buildAiChatPayload(uid, property, prompt, images = []) {
        let code = "";
        if (window.globalEditor && window.currentSelectedCode && window.currentSelectedCode.length === 1) {
                const currentUid = window.currentSelectedCode[0].uid;
                if (currentUid === uid && window.currentSelectedProperty === property) {
                        const model = window.globalEditor.getModel && window.globalEditor.getModel();
                        if (model) code = model.getValue();
                }
        }
        if (!code) {
                const box = findBoxByUid(uid);
                code = readBoxProperty(box, property);
        }
        return {
                uid: uid,
                prompt: code || "",
                startLine: 0,
                extraPrompt: prompt,
                property: property || "code",
                kind: "general",
                images: images || []
        };
}

// ---- chat scroll ----------------------------------------------------------
//
// The chat re-renders from scratch on every streamed `set-output`, and assigning `innerHTML` resets
// `scrollTop` to 0. The old code set `scrollTop = scrollHeight` on the next line, which looks like a
// recovery and is not: inline chat images are `data:` URLs emitted without width/height, so at that
// instant they contribute no height at all, `scrollHeight` is barely more than one viewport, and the
// clamp leaves the reader near the *top*. Once a screenshot is anywhere in the history that happens
// on every update of the turn, which is what "the panel keeps scrolling itself to the top" is.
//
// Two rules. Stick to the bottom only if the reader was already there, so scrolling up to re-read
// something earlier is not yanked away mid-turn. And re-apply the stick when late content actually
// changes the height, instead of measuring once against a layout that has not happened yet.

const CHAT_BOTTOM_SLACK_PX = 48;

function chatPinnedToBottom(messages) {
        const slack = messages.scrollHeight - messages.scrollTop - messages.clientHeight;
        return slack <= CHAT_BOTTOM_SLACK_PX;
}

/**
 * Re-render the chat transcript, keeping the reader where they were.
 *
 * `options.forceBottom` is for the moments where jumping to the newest turn is the point rather than
 * an interruption: the local echo of a message you just sent, and switching the panel to another box.
 */
function renderChatMessages(messages, markdown, options) {
        if (!messages) return;
        const forceBottom = !!(options && options.forceBottom);
        // Both measured before the teardown, because the teardown is what loses the position.
        const stick = forceBottom || chatPinnedToBottom(messages);
        const previousTop = messages.scrollTop;

        messages.innerHTML = aiChatHistoryToHtml(markdown || "");
        decorateAfieldLinks(messages);
        applyCodeHighlight(messages);

        if (!stick) {
                const maxTop = Math.max(0, messages.scrollHeight - messages.clientHeight);
                messages.scrollTop = Math.min(previousTop, maxTop);
                // Deliberately no image handler on this path: the reader is parked somewhere above,
                // content grows below them, and browser scroll anchoring already holds them there.
                // Re-asserting a stored offset later would fight whatever they did in the meantime.
                return;
        }

        const settle = () => {
                messages.scrollTop = messages.scrollHeight;
        };
        settle();
        // An inline screenshot gains its height after this call returns, so settle again per image
        // rather than guessing a delay that is always wrong on one machine or another.
        messages.querySelectorAll("img").forEach((img) => {
                if (img.complete) return;
                const once = () => {
                        img.removeEventListener("load", once);
                        img.removeEventListener("error", once);
                        settle();
                };
                img.addEventListener("load", once);
                img.addEventListener("error", once);
        });
}

// Fetch on panel creation, without replacing a newer streamed/local turn or another box's chat.
function restoreAiChat(container, uid) {
        const before = container.__aiChatHistory || "";
        const epoch = container.__aiChatEpoch = (container.__aiChatEpoch || 0) + 1;
        sendWithReply("ai-chat-history", {uid}, `ai-chat-load-${uid}-${Date.now()}-${Math.random()}`, (_message, response) => {
                if (container.__aiChatEpoch !== epoch || container.__aiChat?.uid !== uid) return "remove";
                if ((container.__aiChatHistory || "") !== before || response?.uid !== uid) return "remove";
                if (typeof response.history === "string") aiChatUpdate(container, uid, response.history);
                return "remove";
        });
}

function ensureAiChat(container, uid, property) {
        if (container.__aiChat) {
                const existing = container.__aiChat;
                if (existing.root && container.contains(existing.root)) {
                        const changedBox = existing.uid !== uid;
                        existing.uid = uid;
                        existing.property = property || existing.property;
                        existing.root.dataset.uid = uid || existing.root.dataset.uid || "";
                        existing.root.dataset.property = existing.property || "code";
                        if (changedBox) {
                                container.__aiChatHistory = controllerChatHistory.get(uid) || "";
                                renderChatMessages(existing.messages, container.__aiChatHistory);
                                restoreAiChat(container, uid);
                        }
                        return existing;
                }
        }

        const root = document.createElement("div");
        root.className = "ai-chat-window";
        root.dataset.uid = uid;
        root.dataset.property = property || "code";

        const messages = document.createElement("div");
        messages.className = "ai-chat-messages";
        messages.addEventListener("click", (event) => {
                const target = event.target;
                if (!(target instanceof HTMLElement)) return;
                const button = target.closest(".ai-chat-copy");
                if (!button) return;
                const block = button.closest(".ai-chat-code-block");
                if (!block) return;
                const code = block.querySelector("code");
                if (!code) return;
                const text = code.textContent || "";
                if (navigator.clipboard && navigator.clipboard.writeText) {
                        navigator.clipboard.writeText(text).then(() => {
                                if (typeof window.notify === "function") {
                                        window.notify("Copied to clipboard", "clipboard");
                                }
                        });
                } else {
                        const textarea = document.createElement("textarea");
                        textarea.value = text;
                        document.body.appendChild(textarea);
                        textarea.select();
                        document.execCommand("copy");
                        textarea.remove();
                        if (typeof window.notify === "function") {
                                window.notify("Copied to clipboard", "clipboard");
                        }
                }
        });

        const inputWrap = document.createElement("div");
        inputWrap.className = "ai-chat-input";

        const preview = document.createElement("div");
        preview.className = "ai-chat-preview";
        preview.style.display = "none";
        preview.style.gap = "8px";
        preview.style.flexWrap = "wrap";
        preview.style.marginBottom = "6px";
        preview.style.alignItems = "center";

        const textarea = document.createElement("textarea");
        textarea.rows = 4;
        textarea.placeholder = "Ask about this box...";

        const sendButton = document.createElement("button");
        sendButton.textContent = "Send";
        sendButton.style.display = "none";

        function sendAiChat(payload) {
                send("ai-assist", payload);
        }

        const attachments = [];

        function currentSelectedUid() {
                const selected = window.currentSelectedCode;
                if (selected && selected.length === 1 && selected[0] && typeof selected[0].uid === "string") {
                        return selected[0].uid;
                }
                return "";
        }

        function chatUid() {
                return root.dataset.uid || container.__aiChat?.uid || uid || currentSelectedUid();
        }

        function chatProperty() {
                return root.dataset.property || container.__aiChat?.property || property || window.currentSelectedProperty || "code";
        }

        function attachmentSrc(attachment) {
                if (typeof attachment === "string") return attachment;
                return attachment && typeof attachment.src === "string" ? attachment.src : "";
        }

        function renderMessages(options) {
                renderChatMessages(messages, container.__aiChatHistory || "", options);
        }

        function appendLocalChatTurn(role, markdown) {
                const history = container.__aiChatHistory || "";
                const prefix = history ? history + "\n" : "";
                container.__aiChatHistory = prefix + (role === "you" ? "<<you>>" : "<<ai>>") + "\n" + markdown;
                renderMessages({ forceBottom: true });
        }

        function renderPreview() {
                preview.innerHTML = "";
                if (attachments.length === 0) {
                        preview.style.display = "none";
                        return;
                }
                preview.style.display = "flex";
                attachments.forEach((attachment, idx) => {
                        const src = attachmentSrc(attachment);
                        if (!src) return;
                        const wrap = document.createElement("div");
                        wrap.className = "ai-chat-thumb";
                        wrap.style.position = "relative";

                        const img = document.createElement("img");
                        img.src = src;
                        img.alt = `attachment ${idx + 1}`;
                        img.style.maxWidth = "72px";
                        img.style.maxHeight = "72px";
                        img.style.borderRadius = "4px";
                        img.style.border = "1px solid rgba(255,255,255,0.25)";
                        img.style.display = "block";

                        const remove = document.createElement("button");
                        remove.textContent = "x";
                        remove.title = "Remove image";
                        remove.style.position = "absolute";
                        remove.style.top = "-6px";
                        remove.style.right = "-6px";
                        remove.style.width = "20px";
                        remove.style.height = "20px";
                        remove.style.borderRadius = "50%";
                        remove.style.border = "none";
                        remove.style.background = "#333";
                        remove.style.color = "#fff";
                        remove.style.cursor = "pointer";
                        remove.addEventListener("click", (e) => {
                                e.preventDefault();
                                attachments.splice(idx, 1);
                                renderPreview();
                        });

                        wrap.appendChild(img);
                        wrap.appendChild(remove);
                        preview.appendChild(wrap);
                });
        }

        function addDataUrlAttachment(src, options = {}) {
                if (typeof src !== "string" || src.length === 0) return;
                attachments.push({
                        src,
                        shownInHistory: !!options.shownInHistory
                });
                renderPreview();
        }

        function addImageAttachment(file) {
                const reader = new FileReader();
                reader.onload = (e) => {
                        const result = e.target?.result;
                        if (typeof result === "string") {
                                addDataUrlAttachment(result);
                        }
                };
                reader.readAsDataURL(file);
        }

        function setCommandBusy(isBusy) {
                textarea.disabled = isBusy;
                textarea.classList.toggle("ai-chat-command-busy", isBusy);
                if (isBusy) {
                        textarea.dataset.previousPlaceholder = textarea.placeholder;
                        textarea.placeholder = "Running command...";
                } else {
                        textarea.placeholder = textarea.dataset.previousPlaceholder || "Ask about this box...";
                        delete textarea.dataset.previousPlaceholder;
                }
        }

        function commandHelpMarkdown() {
                return [
                        "**Commands**",
                        "- `/help` show available commands",
                        "- `/clear` delete this box’s saved conversation and start fresh",
                        "- `/screenshot [window]` capture an `afield.four` window and attach it as an image",
                        "- `/context` show what the last AI turn was told besides the conversation, and its size",
                        "- `/box \"box name\"/property` reference a box property in the conversation",
                        "",
                        "`[window]` can be a 1-based window number or part of a window title. Quotes around box names are optional when the name/property split is unambiguous."
                ].join("\n");
        }

        function captureScreenshotCommand(target) {
                const request = {
                        uid: chatUid(),
                        target: target || ""
                };
                const callbackName = `ai-chat-screenshot-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
                setCommandBusy(true);
                appendLocalChatTurn("you", target ? `/screenshot ${target}` : "/screenshot");
                appendLocalChatTurn("ai", "_Capturing screenshot..._");
                let completed = false;
                const timeout = window.setTimeout(() => {
                        if (completed) return;
                        completed = true;
                        setCommandBusy(false);
                        const historyBeforeCaptureStatus = container.__aiChatHistory || "";
                        container.__aiChatHistory = historyBeforeCaptureStatus
                                .replace(/\n?<<ai>>\n_Capturing screenshot\.\.\._\s*$/m, "");
                        appendLocalChatTurn("ai", "_Screenshot capture timed out._");
                        textarea.focus();
                }, 20000);
                sendWithReply("ai-chat-screenshot", request, callbackName, (_message, response) => {
                        if (completed) return;
                        completed = true;
                        window.clearTimeout(timeout);
                        setCommandBusy(false);
                        const historyBeforeCaptureStatus = container.__aiChatHistory || "";
                        container.__aiChatHistory = historyBeforeCaptureStatus
                                .replace(/\n?<<ai>>\n_Capturing screenshot\.\.\._\s*$/m, "");
                        if (response && response.success && typeof response.dataUrl === "string" && response.dataUrl.length > 0) {
                                const title = response.title ? ` from ${response.title}` : "";
                                const caption = response.message || `Captured screenshot${title}`;
                                appendLocalChatTurn(
                                        "ai",
                                        `_${caption}_\n\n![screenshot${title}](${response.dataUrl})`
                                );
                                addDataUrlAttachment(response.dataUrl, {shownInHistory: true});
                                if (typeof window.notify === "function") {
                                        window.notify(response.message || "Attached screenshot", "AI4");
                                }
                                textarea.focus();
                                return;
                        }
                        const message = response?.message || "Screenshot failed";
                        appendLocalChatTurn("ai", `_${message}_`);
                        textarea.focus();
                });
        }

        function handleSlashCommand(rawText) {
                const match = rawText.match(/^\/([?]|[A-Za-z][\w-]*)(?:\s+([\s\S]*))?$/);
                if (!match) return false;
                const command = match[1].toLowerCase();
                const rest = (match[2] || "").trim();

                if (command === "help" || command === "?") {
                        textarea.value = "";
                        appendLocalChatTurn("ai", commandHelpMarkdown());
                        return true;
                }

                if (command === "clear") {
                        textarea.value = "";
                        const targetUid = chatUid();
                        setCommandBusy(true);
                        sendWithReply("ai-chat-history", {uid: targetUid, clear: true},
                                `ai-chat-clear-${targetUid}-${Date.now()}`, (_message, response) => {
                                        setCommandBusy(false);
                                        if (response?.error) {
                                                window.notify?.(response.error, "AI4");
                                                return "remove";
                                        }
                                        if (chatUid() !== targetUid) return "remove";
                                        aiChatUpdate(container, targetUid, response?.history || "");
                                        attachments.length = 0;
                                        renderPreview();
                                        return "remove";
                                });
                        return true;
                }

                if (command === "screenshot" || command === "shot") {
                        textarea.value = "";
                        captureScreenshotCommand(rest);
                        return true;
                }

                if (command === "context") {
                        textarea.value = "";
                        const targetUid = chatUid();
                        // Shown locally like /help: never saved, so never fed back to the model.
                        sendWithReply("ai-chat-context", {uid: targetUid},
                                `ai-chat-context-${targetUid}-${Date.now()}`, (_message, response) => {
                                        if (chatUid() === targetUid) appendLocalChatTurn("ai", response?.markdown || "_No context report._");
                                        return "remove";
                                });
                        return true;
                }

                if (command === "box") {
                        return false;
                }

                textarea.value = "";
                appendLocalChatTurn("ai", `_Unknown command: /${command}. Type \`/help\` for available commands._`);
                return true;
        }

        function submitPrompt() {
                const text = textarea.value.trim();
                if (!text && attachments.length === 0) return;
                if (text.startsWith("/")) {
                        if (handleSlashCommand(text)) return;
                }
                textarea.value = "";
                const history = container.__aiChatHistory || "";
                const nextHistory = history ? history + "\n" : "";
                const imageMarkdown = attachments
                        .filter((attachment) => !attachment?.shownInHistory)
                        .map((attachment, idx) => `![image ${idx + 1}](${attachmentSrc(attachment)})`)
                        .filter((line) => !line.endsWith("]()"))
                        .join("\n");
                const withImages = imageMarkdown
                        ? [text, imageMarkdown].filter((part) => part && part.length > 0).join("\n")
                        : text;
                if (withImages.length > 0) {
                        container.__aiChatHistory = nextHistory + "<<you>>\n" + withImages;
                        renderMessages({ forceBottom: true });
                }
                const images = attachments.map(attachmentSrc).filter((src) => src.length > 0);
                sendAiChat(buildAiChatPayload(chatUid(), chatProperty(), text, images));
                attachments.length = 0;
                renderPreview();
        }

        textarea.addEventListener("keydown", (event) => {
            if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    submitPrompt();
            }
        });
        sendButton.addEventListener("click", submitPrompt);

        textarea.addEventListener("paste", (event) => {
                const items = event.clipboardData?.items;
                if (!items || items.length === 0) return;
                let handled = false;
                for (let i = 0; i < items.length; i++) {
                        const item = items[i];
                        if (item && item.type && item.type.startsWith("image/")) {
                                const file = item.getAsFile();
                                if (file) {
                                        addImageAttachment(file);
                                        handled = true;
                                }
                        }
                }
                if (handled) {
                        event.preventDefault();
                }
        });

        inputWrap.appendChild(preview);
        inputWrap.appendChild(textarea);
        inputWrap.appendChild(sendButton);

        root.appendChild(messages);
        root.appendChild(inputWrap);
        container.innerHTML = "";
        container.appendChild(root);

        container.__aiChat = {root, messages, textarea, preview, attachments, uid, property: property || "code"};
        restoreAiChat(container, uid);
        return container.__aiChat;
}

function applyCodeHighlight(container) {
        if (!container) return;
        const blocks = container.querySelectorAll("pre code");
        blocks.forEach((block) => highlightCodeBlock(block));
        // Inline `code` carries no fence language, so it is read as the language of the property the
        // chat is about. Only plain-text spans: anything already holding markup (a link) is left alone.
        const inlineLanguage = aiChatInlineCodeLanguage(container);
        container.querySelectorAll(".ai-chat-markdown code").forEach((code) => {
                if (code.closest("pre") || code.childElementCount > 0) return;
                highlightInlineCode(code, inlineLanguage);
        });
}

function aiChatInlineCodeLanguage(element) {
        for (let node = element; node; node = node.parentElement) {
                const property = node.__aiChat?.property;
                if (property) return SHADER_CHAT_PROPERTIES.has(property) ? "glsl" : "kotlin";
        }
        return "kotlin";
}

const SHADER_CHAT_PROPERTIES = new Set(["fragment", "vertex", "compute", "task", "mesh", "geometry"]);

function highlightInlineCode(code, language) {
        const text = code.textContent || "";
        if (!text.trim()) return;
        code.classList.add("ai-chat-inline-code");
        if (language === "glsl") {
                code.innerHTML = highlightGlslCode(text);
                return;
        }
        if (!window.hljs || typeof window.hljs.highlight !== "function") return;
        if (window.hljs.getLanguage && !window.hljs.getLanguage(language)) return;
        try {
                code.innerHTML = window.hljs.highlight(text, { language, ignoreIllegals: true }).value;
        } catch (_) {
        }
}

function normalizeCodeFenceLanguage(language) {
        const raw = String(language || "").trim().toLowerCase().replace(/^language-/, "");
        if (!raw) return "";
        const aliases = {
                kt: "kotlin",
                kts: "kotlin",
                glsl: "glsl",
                frag: "glsl",
                fragment: "glsl",
                vert: "glsl",
                vertex: "glsl",
                comp: "glsl",
                compute: "glsl",
                shader: "glsl",
                js: "javascript",
                ts: "typescript",
                sh: "bash",
                shell: "bash"
        };
        return aliases[raw] || raw;
}

function escapeCodeHtml(value) {
        return String(value ?? "")
                .replace(/&/g, "&amp;")
                .replace(/</g, "&lt;")
                .replace(/>/g, "&gt;")
                .replace(/"/g, "&quot;")
                .replace(/'/g, "&#39;");
}

const GLSL_KEYWORDS = new Set([
        "attribute", "break", "buffer", "case", "centroid", "coherent", "const", "continue", "default",
        "discard", "do", "else", "flat", "for", "highp", "if", "in", "inout", "invariant", "layout",
        "lowp", "mediump", "noperspective", "out", "patch", "precision", "readonly", "restrict",
        "return", "sample", "shared", "smooth", "struct", "subroutine", "switch", "uniform", "varying",
        "volatile", "while", "writeonly"
]);

const GLSL_TYPES = new Set([
        "void", "bool", "int", "uint", "float", "double",
        "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4",
        "dvec2", "dvec3", "dvec4", "mat2", "mat3", "mat4", "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3",
        "mat3x4", "mat4x2", "mat4x3", "mat4x4", "dmat2", "dmat3", "dmat4", "sampler1D", "sampler2D",
        "sampler3D", "samplerCube", "sampler2DArray", "samplerCubeArray", "isampler2D", "usampler2D",
        "image1D", "image2D", "image3D", "imageCube", "atomic_uint"
]);

const GLSL_LITERALS = new Set(["true", "false"]);

const GLSL_BUILTINS = new Set([
        "abs", "acos", "all", "any", "asin", "atan", "ceil", "clamp", "cos", "cross", "degrees", "determinant",
        "distance", "dot", "dFdx", "dFdy", "equal", "exp", "exp2", "faceforward", "floor", "fract", "fwidth",
        "greaterThan", "greaterThanEqual", "inversesqrt", "length", "lessThan", "lessThanEqual", "log", "log2",
        "max", "min", "mix", "mod", "normalize", "not", "notEqual", "pow", "radians", "reflect", "refract",
        "round", "sign", "sin", "smoothstep", "sqrt", "step", "tan", "texture", "textureGrad", "textureLod",
        "transpose", "gl_FragCoord", "gl_FragDepth", "gl_GlobalInvocationID", "gl_InstanceIndex",
        "gl_LocalInvocationID", "gl_Position", "gl_VertexIndex"
]);

function highlightGlslCode(source) {
        let out = "";
        let i = 0;
        let lineStart = true;
        const text = String(source || "");
        const push = (className, value) => {
                out += `<span class="${className}">${escapeCodeHtml(value)}</span>`;
        };

        while (i < text.length) {
                const ch = text[i];
                const next = text[i + 1];
                if (lineStart && ch === "#") {
                        const end = text.indexOf("\n", i);
                        const stop = end === -1 ? text.length : end;
                        push("hljs-meta", text.slice(i, stop));
                        i = stop;
                        lineStart = false;
                        continue;
                }
                if (ch === "/" && next === "/") {
                        const end = text.indexOf("\n", i);
                        const stop = end === -1 ? text.length : end;
                        push("hljs-comment", text.slice(i, stop));
                        i = stop;
                        lineStart = false;
                        continue;
                }
                if (ch === "/" && next === "*") {
                        const end = text.indexOf("*/", i + 2);
                        const stop = end === -1 ? text.length : end + 2;
                        push("hljs-comment", text.slice(i, stop));
                        lineStart = text.slice(i, stop).endsWith("\n");
                        i = stop;
                        continue;
                }
                if (ch === "\"" || ch === "'") {
                        const quote = ch;
                        let j = i + 1;
                        while (j < text.length) {
                                if (text[j] === "\\" && j + 1 < text.length) {
                                        j += 2;
                                        continue;
                                }
                                if (text[j] === quote) {
                                        j++;
                                        break;
                                }
                                j++;
                        }
                        push("hljs-string", text.slice(i, j));
                        i = j;
                        lineStart = false;
                        continue;
                }
                const number = text.slice(i).match(/^(?:0x[0-9a-fA-F]+|(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?)[uUlLfF]?/);
                if (number) {
                        push("hljs-number", number[0]);
                        i += number[0].length;
                        lineStart = false;
                        continue;
                }
                const ident = text.slice(i).match(/^[A-Za-z_][A-Za-z0-9_]*/);
                if (ident) {
                        const word = ident[0];
                        if (GLSL_KEYWORDS.has(word)) push("hljs-keyword", word);
                        else if (GLSL_TYPES.has(word)) push("hljs-type", word);
                        else if (GLSL_LITERALS.has(word)) push("hljs-literal", word);
                        else if (GLSL_BUILTINS.has(word)) push("hljs-built_in", word);
                        else out += escapeCodeHtml(word);
                        i += word.length;
                        lineStart = false;
                        continue;
                }
                out += escapeCodeHtml(ch);
                lineStart = ch === "\n" || (lineStart && /\s/.test(ch));
                i++;
        }
        return out;
}

function highlightCodeBlock(block) {
        const rawLanguage = block.className.match(/(?:^|\s)language-([A-Za-z0-9_+-]+)/)?.[1] || block.closest(".ai-chat-code-block")?.dataset?.lang || "";
        const language = normalizeCodeFenceLanguage(rawLanguage);
        block.className = language ? `language-${language}` : "";
        if (language === "glsl") {
                block.innerHTML = highlightGlslCode(block.textContent || "");
                block.classList.add("hljs", "language-glsl");
                return;
        }
        if (!window.hljs || typeof window.hljs.highlightElement !== "function") return;
        try {
                if (language && window.hljs.getLanguage && !window.hljs.getLanguage(language)) {
                        block.className = "language-plaintext";
                }
                window.hljs.highlightElement(block);
        } catch (_) {
        }
}

function aiChatUpdate(container, uid, markdown) {
        const chat = ensureAiChat(container, uid, container.__aiChat?.property || "code");
        container.__aiChatEpoch = (container.__aiChatEpoch || 0) + 1;
        container.__aiChatHistory = markdown || "";
        renderChatMessages(chat.messages, container.__aiChatHistory);
        updateControllerChat(uid, markdown);
}

const controllerChatHistory = new Map();

function controllerChatSelectedUid() {
        const selectedList = window.selectedList;
        const selected = selectedList && selectedList.length === 1 ? selectedList : window.currentSelectedCode;
        if (!selected || selected.length !== 1 || !selected[0]) return "";
        return selected[0].uid || selected[0].getAttribute?.("uid") || selected[0].getAttribute?.("data-uid") || "";
}

function updateControllerChat(uid, markdown) {
        if (uid) controllerChatHistory.set(uid, markdown || "");
        const root = document.querySelector("#controller-chat-root");
        if (!root || controllerChatSelectedUid() !== uid) return;
        const container = root.querySelector(".controller-chat-container");
        if (!container || container.__controllerChatUpdating) return;
        container.__controllerChatUpdating = true;
        try {
                const chat = ensureAiChat(container, uid, window.selectedProperty || window.currentSelectedProperty || "code");
                container.__aiChatHistory = markdown || "";
                renderChatMessages(chat.messages, container.__aiChatHistory);
        } finally {
                container.__controllerChatUpdating = false;
        }
}

function syncControllerChatSelection() {
        const root = document.querySelector("#controller-chat-root");
        if (!root) return;
        const container = root.querySelector(".controller-chat-container");
        const heading = root.querySelector(".controller-chat-heading");
        const uid = controllerChatSelectedUid();
        if (!uid) {
                heading.textContent = "Select a box to begin an AI4 conversation";
                container.innerHTML = '<div class="controller-chat-empty">Use the Select tab to choose a box.</div>';
                container.__aiChat = null;
                return;
        }
        const box = findBoxByUid(uid);
        const name = box?.name || box?.getAttribute?.("data-name") || uid;
        const property = window.selectedProperty || window.currentSelectedProperty || "code";
        heading.textContent = `AI4 · ${String(name).trim() || uid} · ${property}`;
        const history = controllerChatHistory.get(uid) || "";
        const chat = ensureAiChat(container, uid, property);
        container.__aiChatHistory = history;
        renderChatMessages(chat.messages, history, { forceBottom: true });
}

function installControllerChatProjection() {
        const params = new URLSearchParams(window.location.search);
        if (!params.has("controllerChat")) return;
        document.body.classList.add("mode-controller-chat", "mode-controller-mobile");
        const root = document.createElement("main");
        root.id = "controller-chat-root";
        root.innerHTML = '<div class="controller-chat-heading">Select a box to begin an AI4 conversation</div><div class="controller-chat-container"><div class="controller-chat-empty">Use the Select tab to choose a box.</div></div>';
        document.body.appendChild(root);
        window.addEventListener("field-selection-changed", () => window.setTimeout(syncControllerChatSelection, 0));
        const ready = () => {
                if (!window.fieldBackdoorUiReady) {
                        window.setTimeout(ready, 100);
                        return;
                }
                syncControllerChatSelection();
        };
        ready();
}

function openAiChatForBox(uid, property) {
        const box = findBoxByUid(uid);
        if (!box) return;
        const outputArea = box.querySelector(".outputArea");
        if (!outputArea) return;
        outputArea.style.visibility = "visible";
        const contain = outputArea.querySelector(".disclosure-group");
        if (!contain) return;
        const inter = disclosureInterface(contain);
        const content = inter.upsertDisclosure("ai-chat", "AI Chat", "__AFIELD_PRESERVE_DISCLOSURE_CONTENT__");
        const chat = ensureAiChat(content, uid, property || "code");
        inter.open("ai-chat");
        setTimeout(() => {
                chat.textarea.focus();
        }, 0);
}

window.aiChatUpdate = aiChatUpdate;
window.openAiChatForBox = openAiChatForBox;
installControllerChatProjection();
