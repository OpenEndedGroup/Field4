// "Show where it fails" on a failed StudyBuild card opens the output of the box it names. It used to
// go through the ordinary box-link path, which selects the box and opens its code, so a closed panel
// that drew the failure stayed closed.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = event => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener('error', onError);
const steps = {};
const fail = reason => ({ok: false, reason, steps, errors});
const waitFor = async (predicate, timeoutMs = 10000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const value = predicate();
        if (value) return value;
        await delay(50);
    }
    return predicate();
};
const selected = () => Array.from(window.selectedList || window.selected || []);
try {
    const box = [...document.querySelectorAll('.box')].find(b => String(b.textContent || '').includes('StudyBuildFailureViewRegression'))
        || [...document.querySelectorAll('.box')].at(-1);
    if (!box) return fail('box not found');
    const link = await waitFor(() => [...box.querySelectorAll('a[data-box-reveal="output"]')]
        .find(a => a.textContent.includes('Show where it fails')), 20000);
    if (!link) return fail('the failed card never showed "Show where it fails"');
    steps.link = {uid: link.getAttribute('data-box-uid'), boxUid: box.uid, text: link.parentElement.textContent};
    if (steps.link.uid !== box.uid) return fail(`the link names ${steps.link.uid}, not this box ${box.uid}`);

    const plan = await waitFor(() => box.querySelector('.failure-view-plan'));
    if (!plan) return fail('the "plan" panel was never displayed');
    const wrap = plan.closest('.disclosure');
    const api = window.disclosureInterface(wrap.closest('.disclosure-group'));
    const id = wrap.__disclosureId;
    if (api.isOpen(id)) api.close(id);
    await waitFor(() => !api.isOpen(id), 3000);
    if (api.isOpen(id)) return fail('could not close the "plan" panel before clicking');
    selected().forEach(b => window.deselectBox?.(b));
    await delay(100);
    steps.before = {planOpen: api.isOpen(id), selected: selected().length};

    link.click();
    const opened = await waitFor(() => api.isOpen(id), 5000);
    await delay(300);
    steps.after = {planOpen: api.isOpen(id), selected: selected().length, planHeight: plan.getBoundingClientRect().height};
    if (!opened) return fail('"Show where it fails" left the panel that draws the failure closed');
    if (steps.after.planHeight <= 0) return fail('the "plan" panel opened with no visible content');
    if (selected().length !== 0) return fail('"Show where it fails" changed the selection; finding links leave it alone');

    // A link to a box this document does not have says so beside the link instead of failing silently.
    const missing = link.cloneNode(true);
    missing.setAttribute('data-box-uid', 'no-such-box');
    missing.setAttribute('href', '#box:no-such-box');
    link.parentElement.appendChild(missing);
    missing.click();
    const feedback = await waitFor(() => missing.nextElementSibling?.classList.contains('review-link-error') && missing.nextElementSibling, 2000);
    steps.missing = feedback?.textContent;
    missing.remove(); feedback?.remove();
    if (!feedback || !feedback.textContent.includes('not in this document')) return fail(`a missing box gave ${JSON.stringify(steps.missing)}`);

    return {ok: errors.length === 0, reason: errors.length ? 'page errors' : undefined, steps, errors};
} finally {
    window.removeEventListener('error', onError);
}
