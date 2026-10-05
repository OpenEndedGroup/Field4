// Shared annotation controls for Field geometry surfaces. The adapter owns hit testing,
// revision-bound references, captures and transport; this module owns the interaction chrome.
const styleText = `
.field-review-tools, .field-review-composer {
    --ink: #e8f0fb; --muted: #a0a6ae; --accent: #62d7ff;
    color: var(--ink); background: #000; border: 0; border-radius: 8px;
    font-size: 12px; line-height: 1.4; font-weight: 300;
    user-select: none; -webkit-user-select: none; pointer-events: auto;
}
.field-review-tools { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; padding: 4px 6px; }
.field-review-tools label { display: inline-flex; align-items: center; gap: 6px; padding-left: 6px; white-space: nowrap; }
.field-review-tools button, .field-review-tools select, .field-review-composer button, .field-review-composer textarea {
    font: inherit; color: var(--ink); background: #242424; border: 0; border-radius: 6px; outline: none; box-shadow: none;
}
.field-review-tools button { background: #000; color: var(--accent); padding: 4px 8px; height: 28px; white-space: nowrap; cursor: pointer; }
.field-review-tools select {
    appearance: none; max-width: 110px; height: 28px; padding: 4px 30px 4px 10px;
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='8' viewBox='0 0 12 8'%3E%3Cpath d='M1 1.5 6 6.5 11 1.5' fill='none' stroke='%23e7eef8' stroke-width='1.4' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
    background-repeat: no-repeat; background-position: right 10px center;
}
.field-review-tools :focus, .field-review-composer :focus { outline: none; box-shadow: none; }
.field-review-tools button:focus-visible, .field-review-tools select:focus-visible, .field-review-composer button:focus-visible { color: var(--accent); }
.field-review-composer {
    position: absolute; bottom: 38px; left: 12px; z-index: 10; padding: 16px;
    width: min(360px, calc(100% - 24px)); max-height: calc(100% - 110px); overflow: auto;
}
.field-review-composer p { margin: 0 0 12px; overflow-wrap: anywhere; }
.field-review-composer textarea { display: block; width: 100%; resize: vertical; padding: 10px; margin: 6px 0 12px; }
.field-review-composer button { padding: 7px 10px; cursor: pointer; }
.field-review-composer button:disabled, .field-review-tools button:disabled { opacity: .4; cursor: default; }
.field-review-composer [data-role=save] { background: var(--accent); color: #000; }
.field-review-composer [data-role=save]:focus-visible { background: #b4edff; }
.field-review-composer [data-role=actions] { display: flex; gap: 6px; flex-wrap: wrap; }
.field-review-composer [data-role=angle-wrap] { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.field-review-composer [data-role=angle] { width: 140px; accent-color: var(--accent); padding: 0; }
.field-review-composer [data-role=angle-value] { min-width: 4ch; text-align: right; font-variant-numeric: tabular-nums; }
.field-review-composer [data-role=description] { white-space: pre-line; }
.field-review-composer [data-role=message] { color: var(--muted); margin: 10px 0 0 !important; }
.field-review-composer [data-role=message][data-error=true] { color: #ff7070; }
.field-review-composer textarea, .field-review-composer p { user-select: text; -webkit-user-select: text; }
.field-review-tools [hidden], .field-review-composer [hidden], .field-review-tools.hidden, .field-review-composer.hidden,
.field-review-tools[hidden], .field-review-composer[hidden], .field-review-composer .hidden { display: none !important; }
`;
const toolbarHtml = `            <label>Pick <select id="feature-mode" aria-label="Pick a model feature">
                <option value="off">Off</option><option value="point">Point</option><option value="face">Face</option>
                <option value="edge">Edge</option><option value="vertex">Vertex</option><option value="region">Rectangle</option>
            </select></label>
            <button type="button" class="hud-button" id="feature-load">Load note</button>
            <button type="button" class="hud-button" id="feature-inbox">Notes</button>
            <input type="file" id="feature-file" accept=".json,application/json" hidden>
`;
const composerHtml = `    <label id="feature-angle-wrap" data-role="angle-wrap" class="hidden">Angle <input id="feature-angle" data-role="angle" type="range" value="0" min="-180" max="180" step="5" aria-label="Rectangle angle"> <output id="feature-angle-value" data-role="angle-value" for="feature-angle">0°</output></label>
    <p id="feature-description" data-role="description">Click a feature to identify it.</p>
    <label>Note<textarea id="feature-note" rows="2" placeholder="What should change here?"></textarea></label>
    <div id="feature-actions" data-role="actions">
        <button type="button" id="feature-copy">Copy reference</button>
        <button type="button" id="feature-save" data-role="save">Save note</button>
        <button type="button" id="feature-clear">Clear</button>
    </div>
    <p id="feature-message" data-role="message" aria-live="polite"></p>
    <textarea id="feature-reference" class="hidden" rows="4" readonly aria-label="Feature reference to copy"></textarea>`;
export function mountReviewControls(toolbar, composer, options = {}) {
    if (!document.querySelector('style[data-field-review-controls]')) {
        const style = document.createElement('style'); style.dataset.fieldReviewControls = '';
        style.textContent = styleText; document.head.append(style);
    }
    toolbar.classList.add('field-review-tools'); composer.classList.add('field-review-composer');
    toolbar.innerHTML = toolbarHtml; composer.innerHTML = composerHtml;
    const elements = {};
    for (const root of [toolbar, composer]) for (const el of root.querySelectorAll('[id^="feature-"]')) {
        const key = el.id.slice(8); elements[key] = el;
        if (options.idPrefix) el.id = options.idPrefix + key;
    }
    elements['angle-value'].htmlFor = elements.angle.id;
    if (options.modes) for (const option of [...elements.mode.options]) {
        if (!options.modes.includes(option.value)) option.remove();
    }
    const show = () => { composer.hidden = false; composer.classList.remove('hidden'); };
    function message(text, error = false) { elements.message.textContent = text; elements.message.dataset.error = String(error); }
    function protect(callback) { return async (...args) => {
        try { return await callback?.(...args); } catch (error) { message(String(error), true); show(); }
    }; }
    function bind(adapter) {
        elements.mode.onchange = protect(() => { message(''); return adapter.mode?.(elements.mode.value); });
        elements.clear.onclick = protect(() => {
            elements.mode.value = 'off'; elements.note.value = ''; elements.reference.classList.add('hidden');
            message(''); return adapter.clear();
        });
        elements.angle.oninput = protect(() => {
            elements['angle-value'].textContent = elements.angle.value + '°'; return adapter.angle?.(+elements.angle.value, false);
        });
        elements.angle.onchange = protect(() => adapter.angle?.(+elements.angle.value, true));
        elements.copy.onclick = protect(async () => {
            const text = JSON.stringify(await adapter.reference(elements.note.value), null, 2);
            try { await navigator.clipboard.writeText(text); message('Reference copied. Paste it into your agent conversation.'); }
            catch (_) { elements.reference.value = text; elements.reference.classList.remove('hidden');
                elements.reference.focus(); elements.reference.select(); message('Copy this selected reference.'); }
        });
        elements.save.onclick = protect(() => {
            const text = elements.note.value.trim();
            if (!text) { message('Write a note before saving.', true); return; }
            return adapter.save(text);
        });
        elements.load.onclick = () => elements.file.click();
        elements.file.onchange = protect(async () => {
            try { if (elements.file.files[0]) await adapter.load(JSON.parse(await elements.file.files[0].text())); }
            finally { elements.file.value = ''; }
        });
        elements.inbox.onclick = protect(() => adapter.notes());
    }
    return { elements, bind, message, show };
}
