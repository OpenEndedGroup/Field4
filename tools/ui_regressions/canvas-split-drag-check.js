// After the drop: the box is where it was let go, it now lives in the right pane, its frame is the
// world point under its corner through the right pane's camera, and the drag canvas is gone.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
await pause(400);
const b=[...document.querySelectorAll('.box')].find(x=>x.textContent.includes('DragA'));
const r=b.getBoundingClientRect(), before=window.__dragBefore, s=window.canvasPanesState();
const right=s.panes[1];
const fail=m=>({ok:false,reason:m,rect:r.toJSON(),before,state:s,frame:[b.data_x,b.data_y]});
if(Math.abs(r.left-(before.rect.left+360))>1||Math.abs(r.top-(before.rect.top+40))>1)return fail('box jumped on drop');
if(![...right.boxes].includes(b.uid))return fail('box not in the right pane');
if(Math.abs(b.data_x-(r.left-right.tx)/s.zoom)>0.5||Math.abs(b.data_y-(r.top-right.ty)/s.zoom)>0.5)return fail('frame not in the right pane camera');
if(b.data_x<s.worldSplitX)return fail('frame left of the world split');
if(document.querySelector('.field-drag-canvas'))return fail('drag canvas left behind');
window.splitCanvasAt(null,null);
await pause(200);
const u=b.getBoundingClientRect();
return {ok:true,frame:[b.data_x,b.data_y],afterUnsplit:u.toJSON()};
