const pause=ms=>new Promise(r=>setTimeout(r,ms));
const get=id=>document.getElementById(id);
const check=(v,m)=>{if(!v)throw Error(m);};
const wait=async(f,m)=>{for(let i=0;i<400;i++){if(f())return;await pause(50);}throw Error(m+': '+get('profile-stack-status')?.textContent);};
const marks=()=>window.globalEditor?.getModel()?.getAllDecorations().filter(d=>d.options.linesDecorationsClassName?.startsWith('afield-profile-heat '))||[];
const change=(id,value)=>{get(id).value=value;get(id).dispatchEvent(new Event('change'));};
const toggle=id=>{get(id).checked=!get(id).checked;get(id).dispatchEvent(new Event('change'));};
try {
    const twin=window.currentSelectedCode?.[0]?.uid;
    check(twin,'The identical, unexecuted box must be open before recording');
    window.showProfilingTimeline();
    await wait(()=>get('profile-status')?.textContent.startsWith('Ready'),'initial state');
    get('profile-enable-stacks').checked=true;get('profile-duration').value='3';get('profile-record').click();
    get('profile-tab-stacks').click();
    await wait(()=>Number(get('profile-stacks')?.dataset.sampleCount)>0,'JFR samples');
    await wait(()=>!get('profile-save').disabled,'all stack pages loaded');
    check(get('profile-stack-status').textContent.includes('whole JVM'),'JVM scope must be explicit');
    get('profile-stack-group').value='line';get('profile-stack-group').dispatchEvent(new Event('change'));
    const buttons=[...get('profile-hot-list').querySelectorAll('button')];
    let mapped=false;
    for(const b of buttons){
        b.click();
        const open=get('profile-open-sampled-source');
        if(open&&!open.disabled){open.click();mapped=true;break;}
    }
    check(mapped,'No sampled box line resolved');
    await wait(()=>marks().length>0,'gutter heatmap in real Monaco');
    check(window.globalEditor.getModel().getValue().includes('sustained box workload'),'Wrong source opened');
    const boxId=window.currentSelectedCode[0].uid;
    check(boxId!==twin,'Matched the identical text in a different box');
    const markCount=marks().length;
    const original=window.globalEditor.getModel().getValue();
    const describe=()=>marks().map(d=>d.options.hoverMessage?.[0]?.value||d.options.hoverMessage?.value||'').join('\n');
    check(marks().every(d=>d.options.linesDecorationsClassName.includes('-cpu-')),'CPU heat color');
    const beforeBrush=describe();
    get('profile-overview').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
    await wait(()=>marks().length>0&&describe()!==beforeBrush,'Heat must recompute when brushing');
    get('profile-reset').click();
    change('profile-stack-kind','allocation');
    check(Number(get('profile-stacks').dataset.sampleCount)>0,'No weighted allocation samples');
    await wait(()=>marks().some(d=>d.options.linesDecorationsClassName.includes('-allocation-')),'Allocation heat color');
    change('profile-stack-kind','cpu');
    toggle('profile-stack-heatmap');check(marks().length===0,'Heatmap toggle did not clear gutter');
    toggle('profile-stack-heatmap');check(marks().length>0,'Heatmap toggle did not restore gutter');
    window.navigateToBoxProperty(twin,'code',null,1);
    await wait(()=>window.currentSelectedCode?.[0]?.uid===twin,'Navigate to twin');
    await pause(350);
    check(marks().length===0,'Profile leaked to an identical unexecuted box');
    window.navigateToBoxProperty(boxId,'code',null,6);
    await wait(()=>marks().length>0,'Heat did not follow navigation back to sampled box');
    // Editing invalidates the captured source mapping immediately.
    window.globalEditor.executeEdits('profile-test',[{range:{startLineNumber:1,startColumn:1,endLineNumber:1,endColumn:1},text:'// edited\n'}]);
    check(marks().length===0,'Stale heat survived an edit');
    window.globalEditor.getModel().setValue(original);
    await wait(()=>marks().length>0,'Restoring captured text should restore heat');
    get('profile-close').click();check(marks().length===0,'Closing panel left gutter heat');
    window.showProfilingTimeline();
    await wait(()=>marks().length>0,'Reopening capture did not restore heat');
    // Other heatmap channels must survive the profile's independent decoration collection.
    window.LineHeatmap.setVisible(window.globalEditor,'exec',true);
    await wait(()=>window.globalEditor.getModel().getAllDecorations().some(d=>d.options.lineNumberClassName?.includes('afield-line-heat-exec')),'Execution density beside profile heat');
    check(marks().length>0,'Execution density removed profiling heat');
    window.LineHeatmap.setVisible(window.globalEditor,'churn',true);
    await pause(500);
    check(marks().length>0,'History churn removed profiling heat');
    change('profile-stack-group','class');
    const count=Number(get('profile-stacks').dataset.sampleCount);
    get('profile-overview').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
    check(Number(get('profile-stacks').dataset.sampleCount)<count,'Time brush did not filter stack samples');
    get('profile-reset').click();
    change('profile-stack-group','line');
    for(const b of get('profile-hot-list').querySelectorAll('button')){b.click();if(get('profile-open-sampled-source')&&!get('profile-open-sampled-source').disabled)break;}
    await wait(()=>document.querySelector('.monaco-editor .afield-profile-heat'),'Painted gutter stripe');
    const color=getComputedStyle(document.querySelector('.monaco-editor .afield-profile-heat')).backgroundColor;
    check(color!=='rgba(0, 0, 0, 0)'&&color!=='transparent','Heat gutter has no visible color');
    check(get('profile-stack-heatmap').getBoundingClientRect().width<32,'Heatmap checkbox inherited the search input width');
    return {ok:true,marks:markCount,gutterColor:color,cpuSamples:count,status:get('profile-stack-status').textContent};
} catch(e){return {ok:false,reason:String(e),status:get('profile-stack-status')?.textContent};}
