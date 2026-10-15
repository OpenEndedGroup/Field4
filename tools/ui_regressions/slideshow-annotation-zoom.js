// A slideshow annotation ends on its target editor line, and starts on its note, after a pan and
// at canvas zoom 0.5 and 2. The arrow is drawn in client coordinates on a viewport-fixed layer,
// so every endpoint is compared against the client rects of the note and the editor line.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const check=(v,m)=>{if(!v)throw Error(m);};
try {
    const layer=()=>document.querySelector('#slide-annotation-layer g[data-name="slideAnnotations"]');
    for(let i=0;i<200&&!(layer()&&layer().querySelector('path'));i++)await pause(50);
    check(layer()&&layer().querySelector('path'),'annotation path drawn');
    const host=[...document.querySelectorAll('.box')].find(b=>b.textContent.includes('SlideHost'));
    check(host,'SlideHost box');
    const measure=()=>{
        const circles=[...layer().querySelectorAll('circle')];
        check(circles.length>=2,'start and end circles');
        const start={x:+circles[0].getAttribute('cx'),y:+circles[0].getAttribute('cy')};
        const end={x:+circles[circles.length-1].getAttribute('cx'),y:+circles[circles.length-1].getAttribute('cy')};
        const ed=window.globalEditor;const er=ed.getDomNode().getBoundingClientRect();
        const lh=ed.getOption(window.globalMonaco.editor.EditorOption.lineHeight);
        const lineY=er.top+ed.getTopForLineNumber(3)-ed.getScrollTop()+lh/2;
        const note=document.getElementById('slide-note-overlay').getBoundingClientRect();
        const hr=host.getBoundingClientRect();
        return {start,end,lineY,lh,editorLeft:er.left,note:{l:note.left,t:note.top,r:note.right,b:note.bottom},host:{l:hr.left,t:hr.top,r:hr.right,b:hr.bottom}};
    };
    const assertOnTarget=(m,label)=>{
        check(Math.abs(m.end.y-m.lineY)<=m.lh,`${label}: arrow end y ${m.end.y} not on line 3 at ${m.lineY} (line height ${m.lh})`);
        check(Math.abs(m.end.x-m.editorLeft)<=40,`${label}: arrow end x ${m.end.x} not at the editor's left edge ${m.editorLeft}`);
        const n=m.note,s=m.start,tol=3;
        check(s.x>=n.l-tol&&s.x<=n.r+tol&&s.y>=n.t-tol&&s.y<=n.b+tol,`${label}: arrow start ${JSON.stringify(s)} not on the note ${JSON.stringify(n)}`);
        // The note follows its anchor box: it sits beside it, level with its top (or clamped on-screen).
        check(n.l>=m.host.r-1||n.r<=m.host.l+1,`${label}: note ${JSON.stringify(n)} not beside its host ${JSON.stringify(m.host)}`);
    };
    const settle=async()=>{for(let i=0;i<12;i++)await frame();};
    await settle();
    const results={};
    results.start=measure();assertOnTarget(results.start,'initial');
    const viewport=document.querySelector('.field-main-viewport');
    viewport.dispatchEvent(new WheelEvent('wheel',{deltaX:-60,deltaY:-40,clientX:5,clientY:5,bubbles:true,cancelable:true}));
    await settle();
    results.panned=measure();assertOnTarget(results.panned,'panned');
    check(Math.abs(results.panned.host.l-results.start.host.l-60)<1,'pan moved the host box');
    const hr=host.getBoundingClientRect();
    for(const z of [0.5,2]){
        window.setCanvasZoom(z,hr.left,hr.top);
        await settle();
        check(Math.abs(window.canvasZoom()-z)<1e-9,`zoom ${z} applied`);
        results['zoom'+z]=measure();assertOnTarget(results['zoom'+z],`zoom ${z}`);
        if(window.__slideZoomHold)await pause(window.__slideZoomHold);
    }
    window.setCanvasZoom(0.5,hr.left,hr.top);
    await settle();
    return {ok:true,...results};
} catch(e){return {ok:false,reason:String(e)};}
