// Run with the backdoor's page-eval --file after launching the transcript with Playwright attached.
// This drives actual frontend pointer handlers; all values below come from real Kratos results.
(async () => {
    const sent = [];
    const originalSend = WebSocket.prototype.send;
    WebSocket.prototype.send = function(data) {
        try { const m = JSON.parse(data); if (m.name === 'slider-update') sent.push(m.payload); } catch (_) {}
        return originalSend.call(this, data);
    };
    try {
    const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
    const result = () => {
        const node = document.querySelector('#kratos-result');
        return node ? { ...node.dataset } : null;
    };
    async function waitFor(predicate, label) {
        const deadline = Date.now() + 45000;
        while (Date.now() < deadline) {
            const value = result();
            if (value && predicate(value)) return value;
            await sleep(100);
        }
        throw new Error(`${label}: sent=${JSON.stringify(sent)} ${document.querySelector('#kratos-playground-status')?.textContent}`);
    }
    function drag(label, value) {
        const control = [...document.querySelectorAll('.field-slider')]
            .find(node => node.querySelector('.field-label')?.textContent === label);
        if (!control) throw new Error(`Missing slider: ${label}`);
        const svg = control.querySelector('svg');
        const rect = svg.getBoundingClientRect();
        if (!rect.width) throw new Error(`Hidden slider: ${label}`);
        const d = control.dataset;
        const t = (value - Number(d.min)) / (Number(d.max) - Number(d.min));
        const x = Number(d.padding) + t * (Number(d.viewWidth) - 2 * Number(d.padding));
        svg.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1,
            clientX: rect.left + x / Number(d.viewWidth) * rect.width, clientY: rect.top + rect.height / 2 }));
        window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1 }));
    }
    const first = await waitFor(() => true, 'Initial solve');
    drag('Arm end x (m)', .61);
    await sleep(40);
    drag('Arm end x (m)', .43);
    const edited = await waitFor(r => Number(r.arm) === .43 && Number(r.revision) > Number(first.revision), 'Geometry edit');
    if (edited.background !== first.background || Number(edited.changed) <= 0 || Number(edited.delta) <= 0)
        throw new Error(`No-remesh/solution-change assertion failed: ${JSON.stringify({ first, edited })}`);
    drag('Conductivity (W/m K)', 4.0);
    const material = await waitFor(r => Number(r.revision) > Number(edited.revision), 'Material edit');
    if (material.background !== first.background || material.delta === edited.delta || material.changed !== edited.changed)
        throw new Error('Material edit did not reuse geometry and change the solution');
    await sleep(700);
    const settled = result();
    if (settled.revision !== material.revision || settled.arm !== edited.arm)
        throw new Error('An obsolete result overwrote the current result');
    function press(label) {
        const button = [...document.querySelectorAll('.field-button')]
            .find(node => node.querySelector('.field-label')?.textContent === label)?.querySelector('button');
        if (!button) throw new Error(`Missing button: ${label}`);
        button.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 2 }));
        window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 2 }));
    }
    press('Cancel pending');
    await sleep(300);
    if (!document.querySelector('#kratos-playground-status').textContent.includes('Cancelled pending work'))
        throw new Error('Cancel button did not invalidate pending work');
    press('Solve now');
    const committed = await waitFor(r => Number(r.revision) > Number(material.revision), 'Committed solve button');
    if (committed.background !== first.background) throw new Error('Committed solve rebuilt the grid');
    return { ok: true, first, edited, material, committed,
        diagnostics: document.querySelector('#kratos-playground-status').textContent };
    } finally { WebSocket.prototype.send = originalSend; }
})()
