// A slideshow annotation starts on its note and ends on its target editor line at zoom 0.5, 1
// and 2 on its first, second and third call; the first call also pans the canvas. Run once per zoom, with a
// `screenshot-window --uid <target> --with-editor --preserve-selection --no-pan` after each: a
// plain screenshot-window deselects the box, which hides the editor and with it the arrow.
// The arrow is drawn in client coordinates on a viewport-fixed layer, so its endpoints are
// compared with the client rects of the note and the editor line, and both must be painted.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const check=(v,m)=>{if(!v)throw Error(m);};
const settle=async()=>{for(let i=0;i<12;i++)await frame();};
try {
    const svg=()=>document.getElementById('slide-annotation-layer');
    const layer=()=>svg()?.querySelector('g[data-name="slideAnnotations"]');
    for(let i=0;i<200&&!(layer()&&layer().querySelector('path'));i++)await pause(50);
    check(layer()&&layer().querySelector('path'),'annotation path drawn');
    const host=[...document.querySelectorAll('.box')].find(b=>b.textContent.includes('SlideHost'));
    check(host,'SlideHost box');
    const step=window.__slideZoomStep=(window.__slideZoomStep??-1)+1;
    const z=[0.5,1,2][step%3];
    if(!window.__slideZoomPanned){
        const r0=host.getBoundingClientRect();
        document.querySelector('.field-main-viewport').dispatchEvent(new WheelEvent('wheel',{deltaX:-60,deltaY:-40,clientX:5,clientY:5,bubbles:true,cancelable:true}));
        await settle();
        check(Math.abs(host.getBoundingClientRect().left-r0.left-60)<1,'pan moved the host box');
        window.__slideZoomPanned=true;
    }
    const hr=host.getBoundingClientRect();
    window.setCanvasZoom(z,hr.left,hr.top);
    await settle();
    check(Math.abs(window.canvasZoom()-z)<1e-9,`zoom ${z} applied`);

    const label=`zoom ${z}`;
    const circles=[...layer().querySelectorAll('circle')];
    check(circles.length>=2,`${label}: start and end circles`);
    const start={x:+circles[0].getAttribute('cx'),y:+circles[0].getAttribute('cy')};
    const end={x:+circles[circles.length-1].getAttribute('cx'),y:+circles[circles.length-1].getAttribute('cy')};
    const ed=window.globalEditor;const node=ed.getDomNode();const er=node.getBoundingClientRect();
    const lh=ed.getOption(window.globalMonaco.editor.EditorOption.lineHeight);
    const lineY=er.top+ed.getTopForLineNumber(3)-ed.getScrollTop()+lh/2;
    const note=document.getElementById('slide-note-overlay').getBoundingClientRect();

    // Painted, not just measured: the editor is what the browser hits at line 3, and the layer
    // and the note are displayed and on screen.
    const hit=document.elementFromPoint(er.left+lh*3,lineY);
    check(hit&&node.contains(hit),`${label}: editor not painted at line 3 (hit ${hit&&hit.className})`);
    const cs=getComputedStyle(svg());
    check(cs.display!=='none'&&cs.visibility==='visible'&&+cs.opacity>0,`${label}: annotation layer hidden`);
    for(const p of [start,end])check(p.x>=0&&p.y>=0&&p.x<=innerWidth&&p.y<=innerHeight,`${label}: endpoint ${JSON.stringify(p)} off screen`);
    check(note.width>0&&getComputedStyle(document.getElementById('slide-note-overlay')).visibility==='visible',`${label}: note hidden`);

    check(Math.abs(end.y-lineY)<=lh,`${label}: arrow end y ${end.y} not on line 3 at ${lineY} (line height ${lh})`);
    check(Math.abs(end.x-er.left)<=40,`${label}: arrow end x ${end.x} not at the editor's left edge ${er.left}`);
    const tol=3;
    check(start.x>=note.left-tol&&start.x<=note.right+tol&&start.y>=note.top-tol&&start.y<=note.bottom+tol,`${label}: arrow start ${JSON.stringify(start)} not on the note`);
    const h=host.getBoundingClientRect();
    check(note.left>=h.right-1||note.right<=h.left+1,`${label}: note not beside its host`);
    return {ok:true,zoom:z,start,end,lineY,lh,editorLeft:er.left,note:[note.left,note.top,note.right,note.bottom],host:[h.left,h.top,h.right,h.bottom]};
} catch(e){return {ok:false,reason:String(e)};}
