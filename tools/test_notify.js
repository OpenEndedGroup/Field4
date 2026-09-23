// Browser regression: run with Field's backdoor page-eval --file tools/test_notify.js.
// A separate iframe exercises the real toast script without disturbing the document's toasts.
(async () => {
    const frame = document.createElement('iframe');
    frame.style.cssText = 'position:fixed;left:0;top:0;width:900px;height:600px;z-index:20000;background:#1e1e1e';
    document.body.appendChild(frame);
    const w = frame.contentWindow;
    const d = w.document;
    const checks = [];
    const check = (condition, message) => {
        if (!condition) throw new Error(message);
        checks.push(message);
    };
    const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
    const bubbles = () => Array.from(d.querySelectorAll('.bubble'));
    const category = name => bubbles().filter(node => w.sameNotifyCategory(node.cat, name));
    const clear = () => bubbles().forEach(node => node.remove());
    try {
        await Promise.all([
            new Promise((resolve, reject) => {
                const script = d.createElement('script');
                script.src = '/notify.js?regression=' + Date.now();
                script.onload = resolve;
                script.onerror = reject;
                d.head.appendChild(script);
            }),
            new Promise((resolve, reject) => {
                const style = d.createElement('link');
                style.rel = 'stylesheet';
                style.href = '/field.css?notify-regression=' + Date.now();
                style.onload = resolve;
                style.onerror = reject;
                d.head.appendChild(style);
            })
        ]);
        w.notify('AI4: planning', 'AI4');
        await sleep(250);
        const ai = category('AI4')[0];
        const bottom = ai.style.bottom;
        let flickered = false;
        for (let i = 0; i < 40; i++) {
            w.notify('AI4: tool progress ' + i, 'AI4');
            flickered ||= !ai.classList.contains('field-toast-visible') || w.getComputedStyle(ai).opacity !== '1';
            await sleep(10);
        }
        check(category('AI4').length === 1 && category('AI4')[0] === ai, 'Burst updates retain a single toast node');
        check(!flickered && ai.style.bottom === bottom, 'Burst updates stay visible in the same stack position');
        w.notify('Document saved', 'save');
        w.notify('AI4: finished', 'AI4');
        check(bubbles().length === 2 && category('AI4')[0] === ai, 'Interleaved categories replace their own toast');
        await sleep(650);
        w.notify('AI4: finished', 'AI4');
        await sleep(500);
        check(!ai.classList.contains('field-toast-stale'), 'Repeated text refreshes the timer; old timers cannot dim it');
        await sleep(600);
        check(ai.classList.contains('field-toast-stale'), 'Quiet toast dims after its latest update');
        w.notify('AI4: resumed', '*AI4');
        check(category('AI4').length === 1 && category('AI4')[0] === ai, 'Legacy starred categories reuse the same slot');
        const duplicate = d.createElement('div');
        duplicate.className = 'bubble field-toast';
        duplicate.cat = 'AI4';
        d.body.appendChild(duplicate);
        w.notify('AI4: latest', 'AI4');
        check(category('AI4').length === 1, 'Unstarred updates also purge duplicate category nodes');

        clear();
        for (const name of ['a', 'b', 'c', 'd', 'e']) w.notify(name, name);
        const exiting = category('a')[0];
        check(exiting.classList.contains('field-toast-exit'), 'Overflow retires the oldest toast');
        w.notify('a revived', 'a');
        await sleep(300);
        check(category('a')[0] === exiting && !exiting.classList.contains('field-toast-exit'), 'Updating an exiting category cancels its pending removal');
        check(bubbles().length === 4 && category('b').length === 0, 'Overflow retains four toasts and protects the updated one');
        clear();
        w.notify('Anonymous one');
        w.notify('Anonymous two');
        check(bubbles().length === 2, 'Uncategorized notifications remain independent');
        w.notify('Saved <<timeago:1>> <<spinner>>', 'save');
        check(category('save')[0].querySelector('.notify-timeago').textContent.length > 0 &&
            category('save')[0].querySelector('.notify-spinner'), 'Time and spinner markup still render');
        return {ok: true, checks};
    } finally {
        frame.remove();
    }
})()
