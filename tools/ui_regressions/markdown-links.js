const delay = ms => new Promise(resolve => setTimeout(resolve,ms));
const check = (value,message) => {if(!value) throw new Error(message);};
const wait = async (fn,label,timeout=15000) => {
    const until=Date.now()+timeout;
    while(Date.now()<until){if(fn())return;await delay(50);}
    throw new Error(label);
};
const box = name => [...document.querySelectorAll('.box')].find(b=>b.name===name);
const clickLink = (root,prefix) => {
    const anchor=root?.querySelector(`a[href^="${prefix}"]`);
    check(anchor,`Missing ${prefix} in ${root?.className}`); anchor.click(); return anchor;
};
try {
    const target=box('GuideTarget'), fixture=box('GuideFixture');
    const result=(b=target)=>b.notes;
    check(!result(),'Rendering links must not run macros');
    document.getElementById('guide-close').click();
    window.navigateToBoxProperty(fixture.uid,'notes',null,null);
    await wait(()=>window.currentSelectedProperty==='notes','open notes');
    window.toggleNotesMarkdownPreview(window.globalEditor,'preview');
    await wait(()=>document.querySelector('.afield-notes-preview-body a[href^="field://box/"]'),'notes reader');
    clickLink(document.querySelector('.afield-notes-preview-body'),'field://box/');
    await wait(()=>window.currentSelectedCode?.[0]?.uid===target.uid&&window.currentSelectedProperty==='fragment','notes opens fragment');
    await wait(()=>window.globalEditor.getPosition().lineNumber===3,'notes opens exact line');
    await delay(650);
    // With the guide hidden, the target must be centered in the canvas left of the editor.
    const rect=target.getBoundingClientRect(), editor=document.querySelector('.monaco-editor-container').getBoundingClientRect();
    const expectedX=(16+editor.left-14)/2;
    check(Math.abs(rect.left+rect.width/2-expectedX)<5,`box not centered: ${rect.left+rect.width/2} vs ${expectedX}`);
    check(Math.abs(rect.top+rect.height/2-window.innerHeight/2)<5,'box not centered vertically');
    window.navigateToBoxProperty(fixture.uid,'notes',null,null);
    await wait(()=>window.currentSelectedProperty==='notes','return to notes');
    window.toggleNotesMarkdownPreview(window.globalEditor,'preview');
    clickLink(document.querySelector('.afield-notes-preview-body'),'field://macro/');
    await wait(()=>result(fixture)==='document:1','macro from notes changes real box state');

    const chat=fixture.querySelector('.ai-chat-messages');
    check(chat,'real chat output exists');
    clickLink(chat,'field://box/');
    await wait(()=>window.currentSelectedProperty==='fragment','chat opens property');
    clickLink(chat,'field://macro/');
    await wait(()=>result()==='document:1','macro from chat');

    await window.showDocumentGuide('README.md');
    const reader=document.getElementById('guide-reader');
    const macro=clickLink(reader,'field://macro/');
    macro.click(); // A second click while compiling must not schedule a second run.
    await wait(()=>result()==='document:2','macro from guide');
    await wait(()=>!macro.hasAttribute('aria-busy'),'macro completed');
    await delay(150);
    check(result()==='document:2','duplicate macro run');

    document.getElementById('guide-pin').click();
    await wait(()=>window.currentSelectedProperty==='contents'&&window.isNotesMarkdownPreviewActive?.(),'file preview');
    const proxy=window.currentSelectedCode[0];
    clickLink(document.querySelector('.afield-notes-preview-body'),'field://macro/');
    await wait(()=>result(proxy)==='document:1','macro from file preview uses selected proxy');
    // The saved workspace macro must run in this document through the same link dispatcher.
    const {activateFieldLink}=await import('./fieldLinks.js');
    check(await activateFieldLink('field://macro/guide_link_scope_demo?scope=workspace'),'workspace macro response');
    await wait(()=>result(proxy)==='workspace:2','workspace macro effect');
    check(!(await activateFieldLink('field://macro/nonexistent_markdown_macro')),'missing macro must fail');
    check(!(await activateFieldLink('field://box/nonexistent_markdown_box')),'missing box must fail');
    check(result(proxy)==='workspace:2','failed actions changed state');
    // Leave a useful reader/code composition, using the real navigation action.
    await window.showDocumentGuide('README.md');
    clickLink(document.getElementById('guide-reader'),'field://box/');
    await delay(600);
    return {ok:true,surfaces:['notes','chat','guide','file-preview'],macroResult:result(proxy),centerError:Math.abs(rect.left+rect.width/2-expectedX)};
} catch(error) {return {ok:false,reason:String(error)};}
