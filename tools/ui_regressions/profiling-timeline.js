const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const waitFor = async (condition, label, timeout=12000) => {
    const deadline = Date.now()+timeout;
    while(Date.now()<deadline) { if(condition())return; await delay(50); }
    throw new Error(`Timed out: ${label}; ${document.getElementById('profile-status')?.textContent}`);
};
const get = id => document.getElementById(id);
const check = (value, message) => { if(!value)throw new Error(message); };
try {
    window.openFieldCommandPalette({includeEditor:false,editor:window.globalEditor,query:'Show Profiling Timeline'});
    await waitFor(()=>document.querySelector('.afield-palette-row')?.textContent.includes('Show Profiling Timeline'),'profiling command in canvas palette');
    document.querySelector('.afield-palette-input').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));
    await waitFor(()=>get('profile-status')?.textContent.startsWith('Ready'), 'initial snapshot');
    get('profile-duration').value='3';
    get('profile-record').click();
    await waitFor(()=>get('profile-status').textContent.startsWith('Recording'), 'record started');
    await waitFor(()=>get('profile-status').textContent.startsWith('Latched'), 'capture latched');
    const panel=get('profile-timeline');
    check(getComputedStyle(panel.querySelector('header strong')).userSelect==='none','Panel text can be selected');
    check(get('profile-close').textContent==='×','Standard close icon missing');
    check(panel.querySelectorAll('.profile-resize').length===4,'Corner resize handles missing');
    const before=panel.getBoundingClientRect();
    window.interact(panel).fire({type:'resizemove',target:panel,rect:{width:before.width-90,height:before.height-35},deltaRect:{left:20,top:10}});
    const resized=panel.getBoundingClientRect();
    check(resized.width<before.width-50&&resized.height<before.height-20,'Panel resize did not change dimensions');
    const total=Number(panel.dataset.selectedCount);
    const labels=[...panel.querySelectorAll('.profile-labels button')];
    check(total>30, `Expected live execution, got ${total} resumes`);
    check(labels.filter(b=>b.textContent.includes('ProfileWork')).length>=2, 'Two jobs from the same box need separate lanes');
    check(labels.some(b=>b.textContent.includes('ProfileNeighbour')), 'Missing second box');
    check(!get('profile-summary').textContent.includes('unavailable'), 'JVM counters unavailable');
    check(!get('profile-summary').textContent.includes('allocated 0.00'), 'Live allocation was not measured');
    get('profile-metric').value='bytes';get('profile-metric').dispatchEvent(new Event('change'));
    const overview=get('profile-overview'), rect=overview.getBoundingClientRect();
    // Exercise the real brush handlers; browser pointer capture needs a trusted pointer ID.
    const originalCapture=overview.setPointerCapture;
    overview.setPointerCapture=()=>{};
    try {
        for(const [type,fraction] of [['pointerdown',.2],['pointermove',.4],['pointerup',.4]]) {
            overview.dispatchEvent(new PointerEvent(type,{pointerId:7,clientX:rect.left+rect.width*fraction,clientY:rect.top+20,bubbles:true}));
        }
    } finally { overview.setPointerCapture=originalCapture; }
    const brushed=Number(panel.dataset.selectedCount);
    check(brushed>0&&brushed<total, `Brush did not narrow the capture: ${brushed}/${total}`);
    const originalStart=Number(panel.dataset.rangeStart);
    overview.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
    check(Number(panel.dataset.rangeStart)>originalStart,'Keyboard brush did not move');
    get('profile-zoom').click();
    check(Number(panel.dataset.rangeEnd)-Number(panel.dataset.rangeStart)<1000,'Zoom did not keep the brush range');
    get('profile-reset').click();
    [...panel.querySelectorAll('.profile-labels button')].find(b=>b.textContent.includes('ProfileWork')).click();
    const longest=get('profile-detail').querySelectorAll('button')[1];
    check(longest,'Missing measured resume to inspect');longest.click();
    check(get('profile-detail').textContent.includes('Runloop tick'), 'Missing span detail');
    check(get('profile-detail').textContent.includes('Allocated'), 'Missing span allocation');
    get('profile-open-source').click();
    await waitFor(()=>window.globalEditor?.getModel?.()?.getValue?.().includes('Two independent jobs'), 'source navigation');
    get('profile-close').click();check(panel.hidden,'Close failed');
    window.showProfilingTimeline();
    await waitFor(()=>!panel.hidden&&get('profile-status').textContent.startsWith('Latched'), 'reopen retained capture');
    get('profile-reset').click();
    const retained=Number(panel.dataset.selectedCount);
    check(retained===total, `Reopening duplicated or lost samples: ${retained}/${total}`);
    // A second recording must replace the first. Latch it manually this time.
    get('profile-record').click();
    await waitFor(()=>get('profile-status').textContent.startsWith('Recording'), 'second capture');
    await delay(800);get('profile-stop').click();
    await waitFor(()=>get('profile-status').textContent.startsWith('Latched'), 'manual latch');
    check(Number(panel.dataset.rangeEnd)<2500,'Second capture did not reset its clock');
    check(Number(panel.dataset.selectedCount)>0,'Second capture has no live resumes');
    [...panel.querySelectorAll('.profile-labels button')].find(b=>b.textContent.includes('ProfileWork')).click();
    return {ok:true,total,brushed,retained,summary:get('profile-summary').textContent,lanes:labels.map(b=>b.textContent)};
} catch(error) { return {ok:false,reason:String(error),status:get('profile-status')?.textContent}; }
