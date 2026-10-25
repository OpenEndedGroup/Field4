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

    const catalog = await (await fetch('./ai4-fence-renderers.json')).json();
    check(catalog.fences.some(f => f.language === 'mermaid' && f.purpose && f.syntaxGuidance && f.example), 'Mermaid missing from shared catalog');
    const { registerAiChatFenceCatalog } = await import('./aiChatFenceCatalog.js');
    const unregister = registerAiChatFenceCatalog({version: 1, fences: [{
        language: 'fixture', name: 'Fixture', purpose: 'A browser registration check',
        syntaxGuidance: 'Write a word', example: 'hello', modulePath: '/fixture.js', exportName: 'renderFixture'
    }]}, async () => ({ renderFixture: async source => {
        const node = document.createElement('span');
        node.textContent = `registered: ${source.trim()}`;
        return node;
    } }));
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

    // Math: inline and display delimiters in prose, a math fence, and TeX that does not parse.
    check(catalog.fences.some(f => f.language === 'math' && f.modulePath === '/katex/field-katex.js'), 'math missing from shared catalog');
    const prose = 'The form \\(Q = ax^2 + bxy + cy^2\\) costs $5, not $10.\n\n$$\\Delta = b^2 - 4ac$$\n\nBroken \\(\\frac{1}{\\)';
    push(prose + '\n\n```math\n\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}\n```');
    await wait(() => messages.querySelector('.ai-math .katex'), 'inline math typeset');
    await wait(() => messages.querySelector('.ai-math-fence .katex-display'), 'math fence typeset');
    const inline = messages.querySelector('.ai-math:not(.ai-math-display)');
    check(inline.querySelector('annotation')?.textContent === 'Q = ax^2 + bxy + cy^2', 'inline TeX survives escaping');
    check(messages.querySelector('.ai-math-display .katex-display'), 'display math typeset');
    check(messages.querySelector('.ai-math-error')?.textContent === '\\(\\frac{1}{\\)', 'bad TeX keeps its source');
    check(messages.textContent.includes('costs $5, not $10.'), 'dollar amounts stay prose');
    const katexFont = getComputedStyle(inline.querySelector('.katex')).fontFamily;
    check(/KaTeX/.test(katexFont), `KaTeX stylesheet not applied (${katexFont})`);
    // Once loaded, a re-render (a streamed chunk) typesets synchronously: no raw TeX between chunks.
    push(prose + ' more');
    check(messages.querySelector('.ai-math .katex'), 'math not typeset synchronously on re-render');
    await document.fonts.ready;
    return {ok: true, renderers: ['fixture', 'mermaid', 'math'], fallback: true};
} catch (error) { return {ok: false, reason: String(error)}; }
