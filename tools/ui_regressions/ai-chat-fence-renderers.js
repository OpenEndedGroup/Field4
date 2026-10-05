// The real AI4 chat renders a registered fence, lazily loads Mermaid, and preserves source/failure states.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, message) => { if (!value) throw new Error(message); };
const wait = async (fn, label, timeout = 20000) => {
    const until = Date.now() + timeout;
    while (Date.now() < until) { const result = fn(); if (result) return result; await delay(50); }
    throw new Error(label);
};

try {
    const box = [...document.querySelectorAll('.box')].find(b => b.name === 'FenceRenderers');
    check(box, 'fixture box');
    window.openAiChatForBox(box.uid);
    const messages = await wait(() => box.querySelector('.ai-chat-messages'), 'chat opens');
    const push = (text) => {
        const inter = window.disclosureInterface(box.querySelector('.outputArea .disclosure-group'));
        const content = inter.upsertDisclosure('ai-chat', 'AI Chat', '__AFIELD_PRESERVE_DISCLOSURE_CONTENT__');
        content.classList.add('ai-chat-content');
        window.aiChatUpdate(content, box.uid, `<<ai>>\n${text}`);
        inter.open('ai-chat');
    };

    const { registerAiChatFenceRenderer } = await import('./aiChatFenceRenderers.js');
    const unregister = registerAiChatFenceRenderer('fixture', async source => {
        const node = document.createElement('span');
        node.textContent = `registered: ${source.trim()}`;
        return node;
    });
    push('```fixture\nhello\n```');
    await wait(() => messages.querySelector('.ai-chat-fence-preview')?.textContent?.includes('registered: hello'), 'registered renderer');
    check(messages.querySelector('.ai-chat-code-block pre').hidden, 'registered fence source hidden after render');
    unregister();

    push('```mermaid\nflowchart LR\n  A[Hall] --> B[Living room]');
    await delay(250);
    check(!messages.querySelector('.ai-chat-fence-preview'), 'unfinished Mermaid fence rendered');

    const diagram = '```mermaid\nflowchart LR\n  A[Hall] --> B[Living room]\n```';
    push(diagram);
    await wait(() => messages.querySelector('.ai-chat-fence-preview svg'), 'Mermaid SVG');
    check(messages.querySelector('.ai-chat-code-block pre').hidden, 'Mermaid source hidden after render');
    check(messages.querySelector('.ai-chat-fence-preview').textContent.includes('Living room'), 'Mermaid labels missing');
    const toggle = messages.querySelector('.ai-chat-fence-source-toggle');
    check(toggle, 'source toggle missing');
    const blockRect = toggle.closest('.ai-chat-code-block').getBoundingClientRect();
    check(toggle.getBoundingClientRect().left > blockRect.left + blockRect.width * 0.6, 'source toggle covers diagram');
    toggle.click();
    check(!messages.querySelector('.ai-chat-code-block pre').hidden, 'source did not open');
    toggle.click();
    check(messages.querySelector('.ai-chat-code-block pre').hidden, 'source did not close');

    push('```mermaid\nthis is not a diagram\n```');
    await wait(() => messages.querySelector('.ai-chat-fence-error'), 'syntax error fallback');
    check(!messages.querySelector('.ai-chat-code-block pre').hidden, 'source hidden on syntax error');

    push(diagram);
    await wait(() => messages.querySelector('.ai-chat-fence-preview svg'), 'Mermaid rerenders');
    return {ok: true, renderers: ['fixture', 'mermaid'], fallback: true};
} catch (error) { return {ok: false, reason: String(error)}; }
