// One checkpoint per call, in the order the regression presses keys. Each names what must have run
// so far and whether the first key is still being held (with its on-screen hint).
const steps = [
    { after: 'cmd+k', hits: [], pending: true },
    { after: 'cmd+k cmd+j', hits: ['sequence'], pending: false },
    { after: 'cmd+k, then the 1 s timeout', hits: ['sequence', 'prefix'], pending: false },
    { after: 'cmd+k escape, then the timeout', hits: ['sequence', 'prefix'], pending: false },
    { after: 'cmd+k x (completes nothing)', hits: ['sequence', 'prefix', 'prefix'], pending: false },
    { after: 'cmd+j alone', hits: ['sequence', 'prefix', 'prefix', 'second'], pending: false },
    { after: 'in the editor: cmd+k cmd+j', hits: ['sequence', 'prefix', 'prefix', 'second', 'sequence'], pending: false, editorText: 'println(1)' },
    { after: 'in the editor: cmd+k, then the timeout', hits: ['sequence', 'prefix', 'prefix', 'second', 'sequence', 'prefix'], pending: false, editorText: 'println(1)' },
];
const step = steps[window.__chordStep++];
const hint = document.getElementById('field-chord-hint');
const hintVisible = !!hint && getComputedStyle(hint).display !== 'none';
const pending = window.fieldKeybindings.pendingChord();
const hits = window.__chordHits.slice();
const problems = [];
if (JSON.stringify(hits) !== JSON.stringify(step.hits)) problems.push(`ran ${JSON.stringify(hits)}, expected ${JSON.stringify(step.hits)}`);
if (step.pending !== (pending !== null)) problems.push(`pending chord ${pending}, expected ${step.pending ? 'one' : 'none'}`);
if (step.pending !== hintVisible) problems.push(`hint ${hintVisible ? 'shown' : 'hidden'}, expected ${step.pending ? 'shown' : 'hidden'}`);
if (step.pending && !/^(Cmd|Ctrl)\+K … /.test(hint?.textContent || '')) problems.push(`hint reads '${hint?.textContent}'`);
if (step.editorText !== undefined && window.globalEditor.getValue() !== step.editorText) problems.push(`editor text became '${window.globalEditor.getValue()}'`);
// A page-eval's `ok` is the transport's; only a throw fails the regression.
if (problems.length) throw new Error(`after ${step.after}: ${problems.join('; ')}`);
return { ok: true, after: step.after, hits, pending, hint: hint?.textContent };
