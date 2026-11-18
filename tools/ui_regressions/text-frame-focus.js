const {newBox}=await import('./afield.mjs');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const wait=async(fn,message)=>{for(let i=0;i<160;i++){if(fn())return;await delay(50);}throw Error(message);};
await wait(()=>window.allActions?.textFrames?.[0]?.canvasKeybinding,'Canvas Return action missing');
window.setAutoDisplayEditor(false);
const originals=[...document.querySelectorAll('.box')].filter(b=>b.uid!=='root' && !b.classList.contains('tombstoned'))
    .map(box=>({box,selected:box.classList.contains('selected')}));
const camera=window.canvasPanesState();
window.moveCanvasCamera(-4000,-3000,1);
const created=[];
const make=async(name,x,kind='text-frame')=>{
    newBox({kind,name:'Focus '+name,contents:name==='A'?'Alpha':'Bravo',code:kind==='regular'?'// Regular focus target':undefined,
        textFrame:'sizing: fixed\nreflow: false\n',frame:{x:x+4000,y:3100,w:kind==='regular'?180:300,h:200}});
    const box=[...document.querySelectorAll('.box')].find(b=>b.name==='Focus '+name);
    box.dataset.focusFixture=name;created.push(box);
    if(kind==='text-frame'){
        const editor=box.querySelector('.text-frame-editor');
        await wait(()=>document.activeElement===editor,'Frame did not enter editing');
        editor.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
    }
    return box;
};
const a=await make('A',80),b=await make('B',480),c=await make('C',900,'regular');
await wait(()=>c.code==='// Regular focus target','Regular model was not initialized');
window.__textFrameFocus={a,b,c,originals,created,camera};
return {ok:true};
