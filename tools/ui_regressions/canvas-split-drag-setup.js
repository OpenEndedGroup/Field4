// Split the canvas into columns between DragA (world x 100) and DragFar (world x 600), then pan the
// right column so its camera differs from the left's: a box dragged across the divider has to be
// re-expressed in the right pane's camera or it jumps when dropped.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const box=n=>[...document.querySelectorAll('.box')].find(b=>b.textContent.includes(n));
const a=box('DragA').getBoundingClientRect(), far=box('DragFar').getBoundingClientRect();
window.splitCanvasAt((a.right+far.left)/2, null);
await pause(200);
const right=document.querySelectorAll('.field-pane')[1], pr=right.getBoundingClientRect();
right.dispatchEvent(new WheelEvent('wheel',{deltaX:150,deltaY:80,clientX:pr.right-10,clientY:pr.bottom-10,bubbles:true,cancelable:true}));
await pause(500);
const s=window.canvasPanesState();
window.__dragBefore={rect:box('DragA').getBoundingClientRect().toJSON(),divider:s.panes[1].rect[0],panes:s.panes};
return {ok:true,before:window.__dragBefore};
