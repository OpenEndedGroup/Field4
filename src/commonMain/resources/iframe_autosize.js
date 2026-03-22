(() => {
    if (window.top === window) return;

    const frame = window.frameElement;
    if (!frame || String(frame.tagName || "").toUpperCase() !== "IFRAME") return;
    if ((frame.getAttribute("data-afield-autosize") || "").toLowerCase() === "off") return;

    const html = document.documentElement;
    const body = document.body;
    const PASSES = [0, 1, 2, 4, 8, 14, 22];
    let scheduleToken = 0;

    function markEmbedded() {
        html?.setAttribute("data-afield-output-iframe", "true");
        if (document.body) {
            document.body.setAttribute("data-afield-output-iframe", "true");
        }
        frame.dataset.afieldAutosize = "true";
    }

    function isVisible(element) {
        if (!(element instanceof Element)) return false;
        const style = getComputedStyle(element);
        if (style.display === "none" || style.visibility === "hidden") return false;
        const rect = element.getBoundingClientRect();
        return rect.width > 0 || rect.height > 0;
    }

    function contentRoot() {
        return document.body || document.documentElement;
    }

    function measureContentSize() {
        markEmbedded();

        const root = contentRoot();
        if (!(root instanceof Element)) {
            return { width: 0, height: 0 };
        }

        const rootRect = root.getBoundingClientRect();
        const frameRect = frame.getBoundingClientRect();
        let width = Math.ceil(Math.max(frame.clientWidth || 0, frameRect.width || 0, rootRect.width || 0));
        let height = 0;

        [document.documentElement, document.body].forEach((node) => {
            if (!(node instanceof Element)) return;
            height = Math.max(height, Math.ceil(node.scrollHeight || 0), Math.ceil(node.clientHeight || 0));
        });

        const descendants = Array.from(root.querySelectorAll("*"));
        descendants.forEach((node) => {
            if (!isVisible(node)) return;
            const rect = node.getBoundingClientRect();
            height = Math.max(height, Math.ceil(rect.bottom - rootRect.top), Math.ceil(node.scrollHeight || 0));
        });

        width = Math.max(width, 40);
        height = Math.max(height, Math.ceil(rootRect.height), 24);
        return { width, height };
    }

    function publishSize(reason = "content") {
        const next = measureContentSize();
        const width = Math.max(0, Math.ceil(next.width || 0));
        const height = Math.max(0, Math.ceil(next.height || 0));
        const prevWidth = parseFloat(frame.dataset.afieldIntrinsicWidth || "0") || 0;
        const prevHeight = parseFloat(frame.dataset.afieldIntrinsicHeight || "0") || 0;
        const changed = Math.abs(prevWidth - width) > 1 || Math.abs(prevHeight - height) > 1;

        frame.dataset.afieldIntrinsicWidth = String(width);
        frame.dataset.afieldIntrinsicHeight = String(height);
        frame.dispatchEvent(new CustomEvent("afield-iframe-size", {
            detail: { width, height, reason, changed }
        }));
    }

    function runPasses(token, passIndex, reason) {
        requestAnimationFrame(() => {
            if (token !== scheduleToken) return;
            publishSize(reason);
            if (passIndex >= PASSES.length - 1) return;
            window.setTimeout(() => {
                if (token !== scheduleToken) return;
                runPasses(token, passIndex + 1, reason);
            }, PASSES[passIndex + 1] * 16);
        });
    }

    function schedule(reason = "content") {
        markEmbedded();
        scheduleToken += 1;
        runPasses(scheduleToken, 0, reason);
    }

    markEmbedded();

    if (document.readyState === "complete" || document.readyState === "interactive") {
        schedule("boot");
    } else {
        document.addEventListener("DOMContentLoaded", () => schedule("dom"), { once: true });
    }

    window.addEventListener("load", () => schedule("load"));

    if (document.fonts?.ready) {
        document.fonts.ready.then(() => schedule("fonts")).catch(() => {});
    }

    window.FieldIframeAutosize = {
        schedule,
        reportNow: () => publishSize("manual")
    };
})();
