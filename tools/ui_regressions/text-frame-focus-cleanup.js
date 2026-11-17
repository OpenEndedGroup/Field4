const {send}=await import('./afield.mjs');
const {created,originals,camera}=window.__textFrameFocus;
window.setAutoDisplayEditor(false);
document.activeElement?.blur();
await new Promise(resolve=>setTimeout(resolve,150));
send('perform-action',{uid_currentSelection:created.map(b=>b.uid),uid_action:'-delete-box'});
for(let i=0;i<200;i++){
    if(created.every(b=>b.classList.contains('tombstoned')))break;
    await new Promise(resolve=>setTimeout(resolve,50));
}
if(!created.every(b=>getComputedStyle(b).display==='none'))throw Error('Focus fixtures did not clean up');
window.moveCanvasCamera(camera.panes[0].tx,camera.panes[0].ty,camera.zoom);
for(const {box,selected} of originals)if(selected)window.selectBox(box);
return {ok:true};
