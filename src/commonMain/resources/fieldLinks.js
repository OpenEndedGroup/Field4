import { classifyGuideLink, macroGuideLink } from './documentGuideModel.js';

// Action links are delegated on the page, not attached by a particular Markdown renderer.
// This also covers streamed chat, output areas, inline docs and slide notes as they are replaced.
const runningMacros = new Set();
const report = message => window.notify?.(String(message), 'markdown');

export async function activateFieldLink(href, anchor) {
    const link = classifyGuideLink(href);
    if (link.kind === 'box') {
        if (!window.navigateToBoxFromLink?.(link.uid, link.property, link.line, link.line)) {
            report(`Box “${link.uid}” is not in this document`);
            return false;
        }
        return true;
    }
    if (link.kind === 'guide') {
        await window.openDocumentGuideLink?.(link);
        return true;
    }
    if (link.kind !== 'macro') { report('Unrecognized Field link'); return false; }
    const key = JSON.stringify([link.scope, link.project, link.name]);
    if (runningMacros.has(key)) { report(`Macro “${link.name}” is already running`); return false; }
    runningMacros.add(key);
    anchor?.setAttribute('aria-busy', 'true');
    try {
        if (typeof window.macroPaletteRun !== 'function') throw new Error('Macro runner is not ready');
        // Same selection, scope resolution, read-only checks and transcript reporting as the palette.
        await window.macroPaletteRun(link.name, link.scope, link.project);
        return true;
    } catch (error) {
        report(error?.message || `Could not run macro “${link.name}”`);
        return false;
    } finally {
        runningMacros.delete(key);
        anchor?.removeAttribute('aria-busy');
    }
}

document.addEventListener('click', event => {
    const anchor = event.target?.closest?.('a[href]');
    const href = anchor?.getAttribute('href') || '';
    if (!/^field:/i.test(href)) return;
    event.preventDefault();
    event.stopPropagation();
    // Merely rendering/hovering a link never runs anything. Enter on an anchor emits click too.
    void activateFieldLink(href, anchor);
}, true);

document.addEventListener('mouseover', event => {
    const anchor = event.target?.closest?.('a[href^="field://macro/"]');
    if (!anchor) return;
    const link = classifyGuideLink(anchor.getAttribute('href'));
    if (link.kind === 'macro') anchor.title = `Run macro “${link.name}” · ${link.scope}${link.project ? `: ${link.project}` : ''} · current selection`;
});

window.copyMarkdownMacroLink = async (name, scope = 'document', project = '') => {
    const href = macroGuideLink(name, scope, project);
    if (classifyGuideLink(href).kind !== 'macro') { report('Choose a saved macro first'); return; }
    const label = `Run ${name}`.replace(/[\[\]\\]/g, '\\$&');
    try {
        await navigator.clipboard.writeText(`[${label}](${href})`);
        report('Copied Markdown link to macro');
    } catch (_) { report('Clipboard is unavailable'); }
};
