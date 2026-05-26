import { aiChatMarkdownToHtml, aiChatHistoryToHtml, send } from "./afield.mjs";

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

                if (
                        node instanceof SVGElement ||
                        node instanceof HTMLImageElement ||
                        node instanceof HTMLCanvasElement ||
                        node instanceof HTMLVideoElement ||
                        node instanceof HTMLIFrameElement
                ) {
                        return true;
                }

                const style = getComputedStyle(node);
                const display = normalizedCssValue(style.display);
                const position = normalizedCssValue(style.position);
                const overflow = axisOverflows(node, axis);
                const explicitSize = hasExplicitAxisSize(node, axis);

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

        function iframeIntrinsicSize(iframe) {
                if (!(iframe instanceof HTMLIFrameElement)) return { width: 0, height: 0 };

                let width = parsePixels(iframe.getAttribute("width") || "");
                let height = parsePixels(iframe.getAttribute("height") || "");
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
                const paddingX = parsePixels(style.paddingLeft) + parsePixels(style.paddingRight);
                const paddingY = parsePixels(style.paddingTop) + parsePixels(style.paddingBottom);
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
                        width = Math.max(width, rangeSize.width);
                        height = Math.max(height, rangeSize.height);
                }

                Array.from(d.cw.querySelectorAll("*")).forEach((node) => {
                        if (!(node instanceof Element) || !isElementVisible(node)) return;

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
                                width = Math.max(width, iframeSize.width);
                                height = Math.max(height, iframeSize.height);
                                return;
                        }

                        const rect = node.getBoundingClientRect();
                        if (shouldUseRectExtent(node, rect, "width")) {
                                width = Math.max(width, Math.ceil(rect.right - box.left));
                        }
                        if (shouldUseRectExtent(node, rect, "height")) {
                                height = Math.max(height, Math.ceil(rect.bottom - box.top));
                        }

                        if (node instanceof SVGElement) {
                                const svgSize = svgIntrinsicSize(node);
                                width = Math.max(width, svgSize.width);
                                height = Math.max(height, svgSize.height);
                        }
                });

                width = Math.max(width + paddingX, headerWidth(d));
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
                                const clamped = clampSizeForId(
                                        id,
                                        Math.max(natural.width, headerWidth(current)),
                                        Math.max(natural.height, 20)
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

        function markContentChanged(id, reason = "content-change") {
                const st = ensureState(id);
                st.contentVersion = (st.contentVersion || 0) + 1;
                states.set(id, st);
                if (!st.userSized && usesContentAutoSize(disclosures.get(id))) {
                        scheduleAutoSize(id, reason);
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
                        } else {
                                ttl.textContent = title;
                        }
                        hdr.append(arr, ttl);
                        const cw = document.createElement("div");
                        cw.className = "disclosure-content";
                        cw.innerHTML = content || "";

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
                                }
                        } else {
                                hdr.querySelector(".title").textContent = title;
                                if (content !== PRESERVE_CONTENT) {
                                        cw.innerHTML = content || "";
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
                        return;
                }
                d.cw.innerHTML = content || "";
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

function ensureAiChat(container, uid, property) {
        if (container.__aiChat) {
                const existing = container.__aiChat;
                if (existing.root && container.contains(existing.root)) {
                        existing.uid = uid;
                        existing.property = property || existing.property;
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

        function renderPreview() {
                preview.innerHTML = "";
                if (attachments.length === 0) {
                        preview.style.display = "none";
                        return;
                }
                preview.style.display = "flex";
                attachments.forEach((src, idx) => {
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

        function addImageAttachment(file) {
                const reader = new FileReader();
                reader.onload = (e) => {
                        const result = e.target?.result;
                        if (typeof result === "string") {
                                attachments.push(result);
                                renderPreview();
                        }
                };
                reader.readAsDataURL(file);
        }

        function submitPrompt() {
                const text = textarea.value.trim();
                if (!text && attachments.length === 0) return;
                textarea.value = "";
                const history = container.__aiChatHistory || "";
                const nextHistory = history ? history + "\n" : "";
                const imageMarkdown = attachments.map((src, idx) => `![image ${idx + 1}](${src})`).join("\n");
                const withImages = imageMarkdown
                        ? [text, imageMarkdown].filter((part) => part && part.length > 0).join("\n")
                        : text;
                container.__aiChatHistory = nextHistory + "<<you>>\n" + withImages;
                messages.innerHTML = aiChatHistoryToHtml(container.__aiChatHistory);
                applyCodeHighlight(messages);
                messages.scrollTop = messages.scrollHeight;
                const images = attachments.slice();
                sendAiChat(buildAiChatPayload(uid, root.dataset.property, text, images));
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
        return container.__aiChat;
}

function applyCodeHighlight(container) {
        if (!container) return;
        if (!window.hljs || typeof window.hljs.highlightElement !== "function") return;
        const blocks = container.querySelectorAll("pre code");
        blocks.forEach((block) => window.hljs.highlightElement(block));
}

function aiChatUpdate(container, uid, markdown) {
        const chat = ensureAiChat(container, uid, container.__aiChat?.property || "code");
        container.__aiChatHistory = markdown || "";
        chat.messages.innerHTML = aiChatHistoryToHtml(container.__aiChatHistory);
        applyCodeHighlight(chat.messages);
        chat.messages.scrollTop = chat.messages.scrollHeight;
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
        const content = inter.upsertDisclosure("ai-chat", "AI Chat", "");
        const chat = ensureAiChat(content, uid, property || "code");
        inter.open("ai-chat");
        setTimeout(() => {
                chat.textarea.focus();
        }, 0);
}

window.aiChatUpdate = aiChatUpdate;
window.openAiChatForBox = openAiChatForBox;
