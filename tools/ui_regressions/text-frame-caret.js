// Exercise real DOM hit-testing and textarea scrolling, including ambiguous repeated prose.
const {newBox, send, aiChatMarkdownToHtml, textFrameMarkdownToHtml} = await import('./afield.mjs');
const {markdownOffsetAtPoint} = await import('./markdownCaret.js');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (ok, message) => { if (!ok) throw new Error(message); };
const wait = async (fn, message) => {
    for (let i = 0; i < 160; i++) { if (fn()) return; await delay(50); }
    throw new Error(message);
};
window.setAutoDisplayEditor(false);
newBox({kind:'text-frame', name:'Caret mapping', textFrame:'sizing: fixed\nreflow: false\nfontSize: 18\n', frame:{x:80,y:90,w:380,h:480}});
await wait(() => [...document.querySelectorAll('.text-frame-box')].some(b => b.name==='Caret mapping'), 'Caret fixture missing');
const box = [...document.querySelectorAll('.text-frame-box')].find(b => b.name==='Caret mapping');
const editor = box.querySelector('.text-frame-editor'), preview = box.querySelector('.text-frame-preview');
await wait(() => document.activeElement===editor, 'Caret editor not ready');
await wait(() => box.textFrame?.includes('fixed'), 'Caret settings missing');
const source = '# Repeated\n\n[repeated](https://example.org/' + 'repeated-hidden-'.repeat(110) + ') **repeated** after.\n\n' +
    '- repeated and `repeated`\n- A & B 😀 here\n\n| First | Second |\n| --- | --- |\n| repeated | repeated |\n\n```kotlin\nval repeated = "repeated"\n```';
editor.value = source; editor.dispatchEvent(new Event('input', {bubbles:true}));
const close = () => editor.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true,cancelable:true}));
close();
await wait(() => editor.hidden && preview.querySelector('pre code.hljs'), 'Caret reader missing');
// The tracked reader remains the same renderer, including tables, entities, math and code.
const ordinary = document.createElement('div'), tracked = document.createElement('div');
ordinary.innerHTML=aiChatMarkdownToHtml(source); tracked.innerHTML=textFrameMarkdownToHtml(source);
check(ordinary.textContent === tracked.textContent, 'Source tracking changed rendered text');
const attributes='![**bold** alt](https://example.org/pic.png) [label](https://example.org/a**b**)';
ordinary.innerHTML=aiChatMarkdownToHtml(attributes); tracked.innerHTML=textFrameMarkdownToHtml(attributes);
check(ordinary.innerHTML===tracked.innerHTML.replace(/ data-markdown-start="[0-9]+"/g,'').replace(/<span data-markdown-offsets="[0-9,]+">([^<]*)<\/span>/g,'$1'),
    'Source tracking changed markup inside attributes');
check(textFrameMarkdownToHtml(source.replace(/\n/g,'\r\n'))===textFrameMarkdownToHtml(source), 'Windows line endings changed source positions');
const point = (selector, occurrence, character) => {
    const element = preview.querySelectorAll(selector)[occurrence];
    check(element, 'Missing caret target ' + selector);
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    let text, offset=character;
    while ((text=walker.nextNode())) { if (offset < text.length) break; offset -= text.length; }
    check(text, 'Missing character ' + character + ' in ' + selector);
    const range = document.createRange(); range.setStart(text,offset); range.setEnd(text,offset+1);
    const rect = range.getClientRects()[0];
    return {x:rect.left + Math.min(1,rect.width*.1), y:(rect.top+rect.bottom)/2, target:text.parentElement};
};
const clickAt = async (selector, occurrence, character, expected) => {
    const p=point(selector,occurrence,character);
    check(markdownOffsetAtPoint(preview,p.x,p.y,p.target)===expected, 'Wrong DOM/source mapping: '+selector+' expected '+expected);
    p.target.dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true,clientX:p.x,clientY:p.y}));
    await delay(80);
    check(document.activeElement===editor && editor.selectionStart===expected && editor.selectionEnd===expected,
        'Wrong edit cursor: '+selector+' '+editor.selectionStart+' expected '+expected);
    close(); await delay(50);
};
await clickAt('h1',0,3,source.indexOf('Repeated')+3);
await clickAt('strong',0,4,source.indexOf('**repeated**')+2+4);
await clickAt('a',0,2,source.indexOf('[repeated]')+1+2);
await clickAt('li code',0,3,source.indexOf('`repeated`')+1+3);
await clickAt('li',1,9,source.indexOf('A & B 😀 here')+9);
await clickAt('td',1,5,source.indexOf('| repeated | repeated |')+'| repeated | '.length+5);
await clickAt('pre code',0,6,source.indexOf('val repeated')+6);
for (const zoom of [.75, 1.5, 1]) {
    window.setCanvasZoom(zoom,200,200); await delay(150);
    await clickAt('strong',0,4,source.indexOf('**repeated**')+6);
}
// A short visible paragraph follows a very long, hidden URL: its source caret must scroll into view.
const p=point('strong',0,4), expected=source.indexOf('**repeated**')+6;
p.target.dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true,clientX:p.x,clientY:p.y}));
await delay(100);
check(editor.scrollTop > 200, 'Source cursor was left above the visible editor');
// Measure the actual caret against the textarea viewport without assuming the scroll algorithm.
const mirror=document.createElement('div'), style=getComputedStyle(editor);
for(const key of ['font','fontFamily','fontSize','fontWeight','fontFeatureSettings','lineHeight','letterSpacing','padding','wordBreak','overflowWrap']) mirror.style[key]=style[key];
Object.assign(mirror.style,{position:'fixed',top:'0',left:'-10000px',boxSizing:'border-box',width:editor.clientWidth+'px',whiteSpace:'pre-wrap'});
mirror.textContent=editor.value.slice(0,expected);
const marker=document.createElement('span');marker.textContent=editor.value[expected];mirror.append(marker);document.body.append(mirror);
check(marker.offsetTop >= editor.scrollTop && marker.offsetTop+marker.offsetHeight <= editor.scrollTop+editor.clientHeight,
    'Scrolled source caret is outside the editor');
mirror.remove();
editor.setSelectionRange(expected-2,expected+3,'backward');const savedScroll=editor.scrollTop;
close();await delay(50);
preview.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));
await delay(80);
check(editor.selectionStart===expected-2 && editor.selectionEnd===expected+3 && editor.selectionDirection==='backward' && Math.abs(editor.scrollTop-savedScroll)<2,
    'Keyboard re-entry lost the source selection or scroll: '+JSON.stringify({start:editor.selectionStart,end:editor.selectionEnd,direction:editor.selectionDirection,scroll:editor.scrollTop,expected,savedScroll}));
editor.setSelectionRange(0,expected,'backward'); editor.scrollTop=0;
close(); await delay(50);
preview.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));
await delay(80);
check(editor.selectionStart===0 && editor.selectionEnd===expected && editor.selectionDirection==='backward' && editor.scrollTop===0,
    'Backward selection did not retain its active caret and scroll');
close();
// Leave a known frame for a trusted mouse double-click in the gesture macro.
box.dataset.caretFixture='true';
window.__textFrameCaret={uid:box.uid,expected:source.indexOf('**repeated**')+2};
return {ok:true, mapped:'headings, repeated words, links, code, lists, tables, Unicode', sourceScroll: savedScroll};
