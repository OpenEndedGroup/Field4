// Browser regression: field_backdoor.py --with-playwright --command
// 'page-eval --file tools/test_ai_chat_model_labels.js'
(() => {
    const marker = (provider, requestedModel, responseModel) =>
        '<<model:' + JSON.stringify({provider, requestedModel, responseModel}) + '>>';
    const history = [
        '<<you>>', 'Which model answered?',
        '<<ai>>', marker('openai', 'gpt-6-astra', 'gpt-6-astra'),
        'This entry records the model returned by OpenAI.',
        '<<ai>>', marker('openrouter', 'vendor/alias', 'vendor/actual-model'),
        'This entry keeps its own provider and returned model.',
        '<<ai>>', marker('anthropic', 'claude-opus-4-8', null),
        'While waiting, the selected model is explicitly marked as requested.',
        '<<ai>>', 'An older entry has no recorded model metadata.'
    ].join('\n');
    const panel = document.createElement('div');
    panel.id = 'ai-model-label-verification';
    panel.style.cssText = 'position:fixed;z-index:100000;left:30px;top:30px;width:800px;max-height:90vh;overflow:auto;padding:24px;background:#080808;color:#ddd;border:1px solid #444;font:15px Iosevka,monospace';
    panel.innerHTML = '<h3>Model labels — renderer verification</h3>' + window.aiChatHistoryToHtml(history);
    document.getElementById(panel.id)?.remove();
    document.body.appendChild(panel);
    const labels = [...panel.querySelectorAll('.ai-chat-model')].map(e => e.textContent);
    const expected = ['gpt-6-astra · OpenAI', 'vendor/actual-model · OpenRouter',
        'claude-opus-4-8 · Anthropic (requested)', 'Model not recorded'];
    if (JSON.stringify(labels) !== JSON.stringify(expected)) throw new Error('Wrong model labels: ' + JSON.stringify(labels));
    if (panel.textContent.includes('<<model:')) throw new Error('Raw metadata leaked into chat');
    if ([...panel.querySelectorAll('.ai-chat-turn.ai')].some(e => !['none', 'normal', '""'].includes(getComputedStyle(e, '::before').content))) {
        throw new Error('A hardcoded pseudo-element badge is still present');
    }
    const hostile = document.createElement('div');
    hostile.innerHTML = window.aiChatHistoryToHtml('<<ai>>\n' + marker('openai', 'test', '<img src=x onerror=alert(1)>') + '\nText');
    if (hostile.querySelector('img')) throw new Error('Model label was not escaped');
    if (!panel.querySelectorAll('.ai-chat-model')[1].title.includes('Requested: vendor/alias.')) throw new Error('Requested alias missing from tooltip');
    return {ok: true, labels, responseModelPreserved: true, requestedAliasInTooltip: true, escaped: true};
})()
