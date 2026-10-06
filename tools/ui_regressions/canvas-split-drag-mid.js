// Mid-drag, the box rides an unclipped canvas above the panes and shows across the divider.
const b=[...document.querySelectorAll('.box')].find(x=>x.textContent.includes('DragA'));
const r=b.getBoundingClientRect(), before=window.__dragBefore;
const ok=!!b.closest('.field-drag-canvas') && r.right>before.divider+20 && Math.abs(r.left-(before.rect.left+360))<2;
return ok?{ok:true,rect:r.toJSON()}:{ok:false,reason:'mid-drag: '+JSON.stringify({inDragCanvas:!!b.closest('.field-drag-canvas'),rect:r.toJSON(),before})};
