// The macro must return before its document's queued graph deletion can finish.
const box=document.querySelector('[data-caret-fixture]');
for(let i=0;i<200;i++) {
    if(box.classList.contains('tombstoned') && getComputedStyle(box).display==='none') return {ok:true,deletedFrameHidden:true};
    await new Promise(resolve=>setTimeout(resolve,50));
}
throw new Error('Deleted text frame is still visible: '+box.className+' '+getComputedStyle(box).display);
