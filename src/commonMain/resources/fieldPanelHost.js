// For a panel that lives in an iframe inside a box's output area (keyframe browser, charGrid):
// close it the way the host does, by closing the output disclosure that holds the iframe, and
// close it on Escape like every Field panel. Plain script; loaded with <script src>.
(function () {
    function dismiss() {
        try {
            const frame = window.frameElement;
            const wrap = frame && frame.closest(".disclosure");
            if (!wrap) return false;
            let c = wrap.parentElement;
            while (c && !c.__disclosureAPI) c = c.parentElement;
            if (!c) return false;
            c.__disclosureAPI.close(wrap.__disclosureId);
            return true;
        } catch (e) {
            return false;
        }
    }
    function editing(t) {
        return t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
    }
    window.fieldPanelDismiss = dismiss;
    document.addEventListener("click", (e) => {
        const b = e.target.closest && e.target.closest("[data-field-panel-close]");
        if (b) { e.preventDefault(); dismiss(); }
    });
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape" && !e.defaultPrevented && !editing(e.target) && dismiss()) e.preventDefault();
    });
})();
