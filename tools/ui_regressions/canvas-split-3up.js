// Up to two dividers per axis, unlinked panes and narrow bands: three columns with boxes in their own
// bands, a third column divider refused, the middle column scrolling alone, a middle band narrower
// than its pane ending in flat grey (read back from the background canvas), a 3x2 split whose corner
// pane is unlinked (dashed edges, scrolling on its own, relinked), what is saved, the palette's
// thumbnail, and a clean unsplit.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const check=(v,m)=>{if(!v)throw Error(m);};
const near=(a,b,tol,m)=>check(Math.abs(a-b)<=tol,`${m}: ${a} vs ${b}`);
const mouse=(target,type,x,y)=>target.dispatchEvent(new MouseEvent(type,{clientX:x,clientY:y,button:0,buttons:type==='mouseup'?0:1,bubbles:true,cancelable:true}));
const wheel=(pane,dx,dy)=>{const r=pane.getBoundingClientRect();pane.dispatchEvent(new WheelEvent('wheel',{deltaX:dx,deltaY:dy,clientX:r.left+20,clientY:r.top+20,bubbles:true,cancelable:true}));};
try {
    const byName=n=>[...document.querySelectorAll('.box')].find(b=>b.textContent.trim()===n);
    const names=['TriQ0','TriQ1','TriQ2','TriP0','TriP1','TriP2'];
    const boxes=Object.fromEntries(names.map(n=>[n,byName(n)]));
    names.forEach(n=>check(boxes[n],`${n} box`));
    const st=()=>window.canvasPanesState();
    const paneOf=uid=>st().panes.find(p=>[...p.boxes].includes(uid));
    const root=[...document.querySelectorAll('.box')].find(b=>b.uid==='root');
    const paneEl=(c,r)=>{const s=st();return document.querySelectorAll('.field-pane')[r*s.columns+c];};

    // Three columns: between the boxes at world x 100, 700 and 1300.
    window.splitCanvasAt(500,null);
    check(window.addCanvasDivider(true,1100)===null,'second column divider added');
    await frame();
    let s=st();
    check(s.columns===3&&s.rows===1&&s.dividersX.length===2,'three columns: '+JSON.stringify({c:s.columns,r:s.rows}));
    near(s.linesX[0],500,0.5,'first world line');near(s.linesX[1],1100,0.5,'second world line');
    const refusal=window.addCanvasDivider(true,1700);
    check(typeof refusal==='string'&&refusal.includes('already has 2 column dividers'),'a third column divider is refused: '+refusal);
    const col=uid=>paneOf(uid)&&paneOf(uid).col;
    check(col(boxes.TriP0.uid)===0&&col(boxes.TriP1.uid)===1&&col(boxes.TriP2.uid)===2,'boxes in their bands: '+[col(boxes.TriP0.uid),col(boxes.TriP1.uid),col(boxes.TriP2.uid)]);
    check(document.querySelectorAll('.field-pane-divider-v').length===2,'two column handles');
    const saved=JSON.parse(root.panes);
    check(Array.isArray(saved.sx)&&saved.sx.length===2&&Array.isArray(saved.wx),'two dividers saved as lists: '+root.panes);

    // A middle band starts exactly as wide as its pane, so it cannot scroll across yet. Narrow the
    // pane by dragging the second divider left; then the band is wider and the column can scroll.
    const dragDivider=async(i,by)=>{
        const dv=document.querySelectorAll('.field-pane-divider-v')[i];
        const dr=dv.getBoundingClientRect(), dx=dr.left+dr.width/2, dy=innerHeight*0.5;
        mouse(dv,'mousedown',dx,dy);mouse(window,'mousemove',dx+by/2,dy);mouse(window,'mousemove',dx+by,dy);mouse(window,'mouseup',dx+by,dy);
        await frame();
    };
    await dragDivider(1,-120);
    const r0=Object.fromEntries(names.map(n=>[n,boxes[n].getBoundingClientRect()]));
    wheel(paneEl(1,0),40,0);
    await frame();
    near(boxes.TriP1.getBoundingClientRect().left,r0.TriP1.left-40,0.5,'middle column scrolls');
    near(boxes.TriP0.getBoundingClientRect().left,r0.TriP0.left,0.5,'left column stays');
    near(boxes.TriP2.getBoundingClientRect().left,r0.TriP2.left,0.5,'right column stays');
    await pause(400);
    // It stops at its band's far line: 80 more is all there is (120 of band beyond the pane, 40 used).
    wheel(paneEl(1,0),400,0);
    await frame();
    near(boxes.TriP1.getBoundingClientRect().left,r0.TriP1.left-120,0.5,'middle column stops at its band');
    await pause(400);

    // Widen the middle pane past its band: the band shows from its left edge; the rest is flat grey.
    await dragDivider(1,240);
    s=st();
    const mid=s.panes[1];
    near(mid.empty[0],120,2,'middle pane has a 120 px grey end');
    // The band's near line sits at the pane's edge (under the divider bar).
    near((mid.rect[0]-mid.tx)/s.zoom,s.linesX[0],0.5,'middle band starts at the pane left');
    const bg=document.querySelector('canvas.field-webgl-background');
    check(bg,'background canvas');
    const probe=document.createElement('canvas');probe.width=bg.width;probe.height=bg.height;
    const ctx=probe.getContext('2d');ctx.drawImage(bg,0,0);
    const dpr=bg.width/innerWidth;
    const px=(x,y)=>Array.from(ctx.getImageData(Math.round(x*dpr),Math.round(y*dpr),1,1).data).slice(0,3);
    const greyAt=px(mid.rect[0]+mid.rect[2]-40,innerHeight*0.6), inBand=px(mid.rect[0]+60,innerHeight*0.6);
    check(greyAt.every(v=>v===52),'flat grey past the band: '+greyAt);
    check(!inBand.every(v=>v===52),'the band itself is not grey: '+inBand);

    // Rows too: a 3x2 split. Unlink the corner pane on both axes.
    check(window.addCanvasDivider(false,200)===null,'row divider added');
    await frame();
    s=st();
    check(s.columns===3&&s.rows===2&&s.panes.length===6,'3x2: '+JSON.stringify({c:s.columns,r:s.rows}));
    const dashShown=()=>[...document.querySelectorAll('.field-pane-dash')].filter(d=>getComputedStyle(d).display!=='none').map(d=>(d.classList.contains('field-pane-dash-v')?'v':'h')+d.dashIndex+'@'+d.dashAlong);
    check(dashShown().length===0,'no dashes while everything is linked: '+dashShown());
    window.unlinkCanvasPane(0,0,true,true);
    await frame();
    const dashes=dashShown().sort().join(',');
    check(dashes==='h0@0,v0@0','dashes on exactly the corner pane\'s two inner edges: '+dashes);
    check(st().panes[0].freeX&&st().panes[0].freeY,'corner is free on both axes');
    const free=JSON.parse(root.panes).free;
    check(free&&free.length===1&&free[0].c===0&&free[0].r===0&&free[0].x&&free[0].y,'unlinking saved: '+root.panes);

    // The corner now scrolls on its own; its row and column neighbours do not take it along. (The top
    // row and the left column start against the far edges of their bands, so they scroll back.)
    const q0=boxes.TriQ0.getBoundingClientRect(), q1=boxes.TriQ1.getBoundingClientRect(), p0=boxes.TriP0.getBoundingClientRect();
    wheel(paneEl(0,0),-30,-20);
    await frame();
    near(boxes.TriQ0.getBoundingClientRect().left,q0.left+30,0.5,'corner scrolls across alone');
    near(boxes.TriQ0.getBoundingClientRect().top,q0.top+20,0.5,'corner scrolls down alone');
    near(boxes.TriP0.getBoundingClientRect().left,p0.left,0.5,'its column stays');
    near(boxes.TriQ1.getBoundingClientRect().top,q1.top,0.5,'its row stays');
    await pause(400);
    wheel(paneEl(1,0),0,-25);
    await frame();
    near(boxes.TriQ1.getBoundingClientRect().top,q1.top+25,0.5,'its row scrolls');
    near(boxes.TriQ0.getBoundingClientRect().top,q0.top+20,0.5,'the corner does not follow its row');
    await pause(400);

    // The palette's thumbnail: with two column dividers, adding a third is refused, and says so.
    const doc=window.canvasSplitPreview('split-canvas-columns-here');
    check(doc.includes('<svg')&&doc.includes('already has 2 column dividers'),'palette preview explains the refusal');
    check(window.canvasSplitPreview('relink-pane').includes('<svg'),'relink has a thumbnail');

    // Relink: the corner rejoins its row and column; the dashes go.
    window.relinkCanvasPane(0,0);
    await frame();
    check(dashShown().length===0,'dashes gone after relinking: '+dashShown());
    check(!st().panes[0].freeX&&!st().panes[0].freeY,'corner linked again');
    near(boxes.TriQ0.getBoundingClientRect().top,boxes.TriQ1.getBoundingClientRect().top,0.5,'corner back in step with its row');

    window.splitCanvasAt(null,null);
    await frame();
    s=st();
    check(!s.split&&document.querySelectorAll('.field-pane, .field-pane-divider, .field-pane-dash').length===0,'unsplit');
    check(root.panes==='','split cleared on the root box');
    return {ok:true,grey:greyAt,band:inBand,dashes};
} catch(e){return {ok:false,reason:String(e),state:window.canvasPanesState&&window.canvasPanesState()};}
