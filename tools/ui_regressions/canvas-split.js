// A 2x2 split: boxes land in the pane of their quadrant, splitting moves nothing on screen, panes in
// a row share vertical scroll and panes in a column share horizontal scroll, each pane is clamped
// to its quadrant, and removing the split returns every box to the one canvas where it was.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const check=(v,m)=>{if(!v)throw Error(m);};
const near=(a,b,tol,m)=>check(Math.abs(a-b)<=tol,`${m}: ${a} vs ${b}`);
try {
    const byName=n=>[...document.querySelectorAll('.box')].find(b=>b.textContent.includes(n));
    const names=['SplitA','SplitB','SplitC','SplitD'];
    const boxes=Object.fromEntries(names.map(n=>[n,byName(n)]));
    names.forEach(n=>check(boxes[n],`${n} box`));
    const rects=()=>Object.fromEntries(names.map(n=>[n,boxes[n].getBoundingClientRect()]));
    const paneOf=uid=>window.canvasPanesState().panes.find(p=>[...p.boxes].includes(uid));

    // Divider at the screen point halfway between SplitA (world 100,100) and SplitD (world 500,400).
    const a=rects().SplitA, d=rects().SplitD;
    const sx=(a.right+d.left)/2, sy=(a.bottom+d.top)/2;
    const before=rects();
    window.splitCanvasAt(sx,sy);
    await frame();
    const s=window.canvasPanesState();
    check(s.split&&s.columns===2&&s.rows===2&&s.panes.length===4,'2x2 split: '+JSON.stringify(s));
    check(document.querySelectorAll('.field-pane').length===4,'four pane elements');
    const after=rects();
    names.forEach(n=>{near(after[n].left,before[n].left,0.5,`${n} did not move x`);near(after[n].top,before[n].top,0.5,`${n} did not move y`);});
    const where=Object.fromEntries(names.map(n=>{const p=paneOf(boxes[n].uid);return [n,p&&`${p.col},${p.row}`];}));
    check(where.SplitA==='0,0'&&where.SplitB==='1,0'&&where.SplitC==='0,1'&&where.SplitD==='1,1','quadrants: '+JSON.stringify(where));
    check(boxes.SplitD.closest('.field-pane')===document.querySelector('.field-main-canvas:not(.field-pane-canvas)').closest('.field-pane'),'D lives in the main canvas');

    // Wheel in D, away from any box: D, B (same column) move in x; D, C (same row) move in y; A does not move.
    const r0=rects();
    const viewport=document.querySelector('.field-main-viewport');
    const paneD=document.querySelectorAll('.field-pane')[3];
    const pd=paneD.getBoundingClientRect();
    // Scroll towards larger world x/y, which the quadrant clamp always allows for the bottom-right pane.
    paneD.dispatchEvent(new WheelEvent('wheel',{deltaX:30,deltaY:20,clientX:pd.right-5,clientY:pd.bottom-5,bubbles:true,cancelable:true}));
    await frame();
    const r1=rects();
    near(r1.SplitD.left,r0.SplitD.left-30,0.5,'D x');near(r1.SplitD.top,r0.SplitD.top-20,0.5,'D y');
    near(r1.SplitB.left,r0.SplitB.left-30,0.5,'B follows D in x');near(r1.SplitB.top,r0.SplitB.top,0.5,'B keeps y');
    near(r1.SplitC.top,r0.SplitC.top-20,0.5,'C follows D in y');near(r1.SplitC.left,r0.SplitC.left,0.5,'C keeps x');
    near(r1.SplitA.left,r0.SplitA.left,0.5,'A keeps x');near(r1.SplitA.top,r0.SplitA.top,0.5,'A keeps y');
    await pause(400); // let the wheel latch expire

    // Clamp: scrolling D far back towards smaller world x/y stops at the dividers' world lines.
    paneD.dispatchEvent(new WheelEvent('wheel',{deltaX:-5000,deltaY:-5000,clientX:pd.right-5,clientY:pd.bottom-5,bubbles:true,cancelable:true}));
    await frame();
    const sc=window.canvasPanesState();
    const pD=sc.panes[3];
    near((pD.rect[0]-pD.tx)/sc.zoom,sc.worldSplitX,0.5,'D left edge clamped at worldSplitX');
    near((pD.rect[1]-pD.ty)/sc.zoom,sc.worldSplitY,0.5,'D top edge clamped at worldSplitY');
    await pause(400);

    // The split is saved on the root box.
    const root=[...document.querySelectorAll('.box')].find(b=>b.uid==='root');
    const saved=JSON.parse(root.panes||'{}');
    near(saved.sx,sx/innerWidth,1e-6,'saved sx');near(saved.wx,s.worldSplitX,1e-6,'saved wx');

    // Background draws once per pane.
    const bg=viewport.__fieldWebglBackground;
    check(!bg||!bg.ok||(bg.panes&&bg.panes.length===4),'background has four panes');

    window.splitCanvasAt(null,null);
    await frame();
    const u=window.canvasPanesState();
    check(!u.split&&u.panes.length===1&&document.querySelectorAll('.field-pane').length===0,'unsplit');
    names.forEach(n=>check(boxes[n].parentElement===document.querySelector('.field-main-canvas'),`${n} back on the main canvas`));
    check(root.panes==='','split cleared on the root box');
    return {ok:true,split:s,where,clamped:{tx:pD.tx,ty:pD.ty}};
} catch(e){return {ok:false,reason:String(e),state:window.canvasPanesState&&window.canvasPanesState()};}
