// Fold a Kotlin box's import block away, to its first line, whenever a box's code is opened.
//
// Nobody wants to read a box's imports, but agents (and people) need them in the same text as the
// code to write it, so they stay in the source and the editor folds them instead. The block is the
// run of `import` lines at the top of the code, along with the blank lines and comments between
// them; it is folded only when it holds two imports or more.
//
// Two triggers, because Field swaps a box's text into one editor model with setValue rather than
// creating a model per box: `foldingImportsByDefault` covers a newly attached model, and a
// content flush (what setValue fires) folds the block again. Typing never refolds it.
const ImportFolding = (() => {
    const LANGUAGES = ["kotlin"];
    const MIN_IMPORTS = 2;

    /** 1-based [start, end] of the import block in [lines], or null when there is nothing to fold. */
    function importBlock(lines) {
        let start = -1, end = -1, count = 0;
        for (let i = 0; i < lines.length; i++) {
            const t = lines[i].trim();
            if (t.startsWith("import ")) {
                if (start < 0) start = i;
                end = i;
                count++;
            } else if (t === "" || t.startsWith("//")) {
                continue;
            } else if (start >= 0 || !(t.startsWith("@file:") || t.startsWith("package "))) {
                break;
            }
        }
        return count >= MIN_IMPORTS ? [start + 1, end + 1] : null;
    }

    let registered = false;

    function registerProvider(monaco) {
        if (registered || !monaco?.languages?.registerFoldingRangeProvider) return;
        registered = true;
        const kind = monaco.languages.FoldingRangeKind?.Imports;
        for (const language of LANGUAGES) {
            monaco.languages.registerFoldingRangeProvider(language, {
                provideFoldingRanges(model) {
                    const block = importBlock(model.getLinesContent());
                    return block ? [{ start: block[0], end: block[1], kind }] : [];
                }
            });
        }
    }

    function foldImports(editor) {
        const model = editor.getModel && editor.getModel();
        if (!model || !LANGUAGES.includes(model.getLanguageId?.())) return;
        const block = importBlock(model.getLinesContent());
        if (!block) return;
        // The folding model is rebuilt asynchronously after the text changes, and Field restores
        // the box's saved view state (folds included, so for every box saved before this existed,
        // an open import block) a moment after switching models. So keep asking for the fold for
        // a short window, each time only if the region is not collapsed already; after that, a fold
        // the user opens stays open. Collapsed state comes from the folding model, not from line
        // tops: the editor can still be hidden here, and then every line's top is 0.
        const token = (editor._afieldImportFoldToken = (editor._afieldImportFoldToken || 0) + 1);
        const isCollapsed = async () => {
            const folding = editor.getContribution?.("editor.contrib.folding");
            const foldingModel = await folding?.getFoldingModel?.();
            const regions = foldingModel?.regions;
            if (!regions) return false;
            for (let i = 0; i < regions.length; i++) {
                if (regions.getStartLineNumber(i) === block[0]) return regions.isCollapsed(i);
            }
            return false;
        };
        [30, 150, 400, 800, 1500].forEach((delay) => setTimeout(async () => {
            if (token !== editor._afieldImportFoldToken || editor.getModel() !== model) return;
            if (!(await isCollapsed())) {
                editor.trigger("afield.importFolding", "editor.fold", { selectionLines: [block[0] - 1] });
            }
        }, delay));
    }

    function install(editor, monaco = window.globalMonaco) {
        if (!editor || editor._afieldImportFoldingInstalled) return;
        editor._afieldImportFoldingInstalled = true;
        registerProvider(monaco);
        editor.updateOptions?.({ foldingImportsByDefault: true });
        editor.onDidChangeModelContent?.((e) => { if (e.isFlush) foldImports(editor); });
        editor.onDidChangeModel?.(() => foldImports(editor));
        foldImports(editor);
    }

    return { install, importBlock };
})();

window.ImportFolding = ImportFolding;
if (window.globalEditor) {
    setTimeout(() => ImportFolding.install(window.globalEditor, window.globalMonaco), 0);
}
