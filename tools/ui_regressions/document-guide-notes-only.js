const {send} = await import('./afield.mjs');
const get = id => document.getElementById(id);
const wait = async (test, error) => {
    const deadline = Date.now() + 7000;
    while (Date.now() < deadline) {
        if (test()) return;
        await new Promise(resolve => setTimeout(resolve, 50));
    }
    throw new Error(error);
};
const box = [...document.querySelectorAll('.box')].find(b => b.name === 'GuideTarget');
const write = value => send('set-string', {uid: box.uid, property: 'notes', value});

await wait(() => get('document-guide-launcher')?.hidden, 'Empty document should hide the guide launcher');
await wait(() => !document.querySelector('.field-startup-progress:not(.field-startup-progress-done)'),
    'Startup progress did not clear');
window.notify('Toast shown before the Notes launcher', 'guide-launcher-test');
// This test's own toast: the first `.bubble` is whatever setup left on screen, and the oldest
// toast is the first evicted once more than four are live.
const toast = [...document.querySelectorAll('.bubble')].find(b => b.cat === 'guide-launcher-test');
if (!toast) throw new Error('The test toast was not shown');
const toastBottom = parseFloat(toast.style.bottom);
write('# Just a box note');
await wait(() => get('document-guide-launcher')?.matches(':not([hidden])') &&
    get('document-guide-launcher').textContent === 'Notes · 1', 'Adding a first box note did not reveal the launcher');
await wait(() => toast.isConnected && parseFloat(toast.style.bottom) > toastBottom &&
    toast.getBoundingClientRect().bottom <= get('document-guide-launcher').getBoundingClientRect().top,
    'A toast already on screen overlapped the Notes launcher');
get('document-guide-launcher').click();
await wait(() => !get('document-guide')?.hidden && get('guide-reader')?.querySelector('h1')?.textContent === 'Just a box note',
    'The notes-only launcher did not open the box note');
if (get('guide-files').querySelector('button')) throw new Error('The fixture should have no guide files');
get('guide-close').click();
write('');
await wait(() => get('document-guide-launcher')?.hidden, 'Clearing the last box note did not hide the launcher');
await wait(() => toast.isConnected && parseFloat(toast.style.bottom) === toastBottom,
    'The toast did not return to its original position when the launcher hid');
return {ok: true};
