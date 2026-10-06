// A run of tool activity between two pieces of text folds into one round: a single closed line
// ("5 tool calls · 3.4 s · 1 failed · 1 note · 1 edit · 1 look") with the activity as it always
// rendered inside. A round the reader opens stays open while the turn streams on; a running round
// shows its live status on the closed line; status lines alone stay a status row.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, message) => { if (!value) throw new Error(message); };
const wait = async (fn, label, timeout = 15000) => {
    const until = Date.now() + timeout;
    while (Date.now() < until) { if (fn()) return; await delay(50); }
    throw new Error(label);
};

const finished = [
    "<<you>>\nLay out the plan.",
    "<<ai>>\nStarting with the rooms.",
    "<<progress>>\nWaiting for model",
    "<<progress>>\nDocumentGuideReadRequest finished (5 ms)\nRead docs/start-here.md",
    "<<progress>>\n_field_sketch_edit_e0b4aeb5c8da0381fe14367f finished (1200 ms)\nrevision 5",
    "<<progress>>\nViewBoxOutputRequest finished (2100 ms)\nOne image",
    "<<progress>>\nRunToolProgramRequest finished (150 ms)\n" +
        "- ✓ `DocumentGuideAppend` `{\"path\":\"docs/plan.md\"}` — 4 ms\n" +
        "- ✗ `_field_services_previewEdit_5934acd4743a29fadd988cf7` `{\"expectedRevision\":\"empty\"}` — 91 ms — Provide 1–64 atomic edits.",
    "<<ai>>\nThe rooms are in.",
    "<<progress>>\nCompleted",
].join("\n");
const live = finished + "\n" + [
    "<<you>>\nNow the roof.",
    "<<progress>>\nStartStudyBuildRequest finished (40 ms)\nstarted",
    "<<progress-open>>\nWaiting for model",
].join("\n");

try {
const box = [...document.querySelectorAll('.box')].find(b => b.name === 'ChatRounds');
check(box, 'fixture box');
window.openAiChatForBox(box.uid);
await wait(() => box.querySelector('.ai-chat-messages'), 'chat panel opens');
const messages = box.querySelector('.ai-chat-messages');
const push = (markdown) => {
    const inter = window.disclosureInterface(box.querySelector('.outputArea .disclosure-group'));
    const content = inter.upsertDisclosure('ai-chat', 'AI Chat', '__AFIELD_PRESERVE_DISCLOSURE_CONTENT__');
    content.classList.add('ai-chat-content');
    window.aiChatUpdate(content, box.uid, markdown);
    inter.open('ai-chat');
};
const rounds = () => [...messages.querySelectorAll('.ai-chat-round')];

push(finished);
// The family table arrives by fetch; the open chat re-renders once it has.
await wait(() => rounds()[0]?.querySelector('.ai-chat-round-family'), 'round counts families once the table loads');
const first = rounds()[0];
const line = first.querySelector('summary').textContent;
check(rounds().length === 1, `one round, got ${rounds().length}`);
check(!first.open, 'a round starts closed');
check(line.startsWith('5 tool calls'), `program calls count as calls: ${line}`);
check(line.includes('3.4 s'), `time sums the top-level calls: ${line}`);
check(first.querySelector('.ai-chat-round-failed')?.textContent === '1 failed', `failures: ${line}`);
for (const noun of ['1 note', '1 edit', '1 look']) check(line.includes(noun), `"${noun}" in: ${line}`);
check(first.querySelectorAll('.ai-chat-round-body > .ai-chat-progress').length === 4, 'the four tool blocks render inside as before');
check(first.querySelector('.ai-chat-round-body .ai-chat-status')?.textContent === 'Waiting for model', 'the status line renders inside as before');
check(!messages.querySelector('.ai-chat-round + .ai-chat-turn.ai + .ai-chat-round'), 'the lone Completed status is not a round');
check([...messages.querySelectorAll(':scope .ai-chat-thread > .ai-chat-status-row')].some(row => row.textContent === 'Completed'), 'status lines alone stay a status row');

// The reader opens the round; the next streamed render keeps it open.
first.querySelector('summary').click();
await wait(() => rounds()[0].open, 'round opens on click');
push(live);
await wait(() => rounds().length === 2, 'the live turn adds a round');
check(rounds()[0].open, 'an opened round stays open through a re-render');
const running = rounds()[1];
check(!running.open, 'a running round stays closed');
check(running.querySelector('summary .ai-chat-round-live .ai-chat-shimmer')?.textContent === 'Waiting for model', 'the live status shimmers on the closed line');
check(running.querySelector('summary').textContent.includes('1 build step'), 'the live round counts its build step');

// Another transcript starts afresh: its first round is closed though round 0 was open here.
push(finished.replace('Lay out the plan.', 'A different chat.'));
await wait(() => messages.textContent.includes('A different chat.'), 'other transcript renders');
check(!rounds()[0].open, 'remembered states belong to one transcript');
push(live);
await wait(() => rounds().length === 2, 'back to the live transcript');
return {ok: true, line, liveLine: rounds()[1].querySelector('summary').textContent};
} catch (error) { return {ok: false, reason: String(error)}; }
