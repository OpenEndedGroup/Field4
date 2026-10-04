// A 2x2 split: boxes land in the pane of their quadrant, splitting moves nothing on screen, panes in
// a row share vertical scroll and panes in a column share horizontal scroll, each pane is clamped
// to its quadrant, a connection between panes meets both boxes where they are seen, a bookmark
// restores the split, the divider handles move and remove dividers, the corner grip splits, and
// removing the split returns every box to the one canvas where it was.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const check=(v,m)=>{if(!v)throw Error(m);};
const near=(a,b,tol,m)=>check(Math.abs(a-b)<=tol,`${m}: ${a} vs ${b}`);
const mouse=(target,type,x,y,extra={})=>target.dispatchEvent(new MouseEvent(type,Object.assign({clientX:x,clientY:y,button:0,buttons:type==='mouseup'?0:1,bubbles:true,cancelable:true},extra)));
const key=(k,mods)=>window.dispatchEvent(new KeyboardEvent('keydown',Object.assign({key:k,code:k,bubbles:true,cancelable:true},mods)));
try {
    const byName=n=>[...document.querySelectorAll('.box')].find(b=>b.textContent.includes(n));
    const names=['SplitA','SplitB','SplitC','SplitD'];
    const boxes=Object.fromEntries(names.map(n=>[n,byName(n)]));
    names.forEach(n=>check(boxes[n],`${n} box`));
    const rects=()=>Object.fromEntries(names.map(n=>[n,boxes[n].getBoundingClientRect()]));
    const paneOf=uid=>window.canvasPanesState().panes.find(p=>[...p.boxes].includes(uid));
    const root=[...document.querySelectorAll('.box')].find(b=>b.uid==='root');
    const mainCanvas=()=>document.querySelector('.field-main-canvas:not(.field-pane-canvas)');

    // Divider at the screen point halfway between SplitA (world 100,100) and SplitD (world 500,400).
    const a=rects().SplitA, d=rects().SplitD;
    const sx=(a.right+d.left)/2, sy=(a.bottom+d.top)/2;
    const before=rects();
    window.splitCanvasAt(sx,sy);
    await frame();
    const s=window.canvasPanesState();
    check(s.split&&s.columns===2&&s.rows===2&&s.panes.length===4,'2x2 split: '+JSON.stringify(s));
    check(document.querySelectorAll('.field-pane').length===4,'four pane elements');
    check(document.querySelectorAll('.field-pane-divider').length===2,'two divider handles');
    const after=rects();
    names.forEach(n=>{near(after[n].left,before[n].left,0.5,`${n} did not move x`);near(after[n].top,before[n].top,0.5,`${n} did not move y`);});
    const where=Object.fromEntries(names.map(n=>{const p=paneOf(boxes[n].uid);return [n,p&&`${p.col},${p.row}`];}));
    check(where.SplitA==='0,0'&&where.SplitB==='1,0'&&where.SplitC==='0,1'&&where.SplitD==='1,1','quadrants: '+JSON.stringify(where));
    check(boxes.SplitD.closest('.field-pane')===mainCanvas().closest('.field-pane'),'D lives in the main canvas');

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

    // A connects to D. Pan D well away from A's camera; the spline must be drawn from where each
    // box is seen: its ends, mapped through the main pane's camera, sit on the two screen rects.
    // (The quadrant clamp below returns D to the split-time camera, which A shares, so this check
    // has to happen while they are apart.)
    paneD.dispatchEvent(new WheelEvent('wheel',{deltaX:200,deltaY:150,clientX:pd.right-5,clientY:pd.bottom-5,bubbles:true,cancelable:true}));
    await frame();
    await frame();
    const path=document.querySelector('#connectionsplinelayer #connections path');
    check(path,'a connection spline');
    const pts=(path.getAttribute('d').match(/-?[\d.]+,-?[\d.]+/g)||[]).map(p=>p.split(',').map(Number));
    check(pts.length>=2,'spline points');
    const sw=window.canvasPanesState();
    const m=sw.panes[3];
    check(Math.abs(m.tx-sw.panes[0].tx)>150,'D and A cameras apart');
    const toScreen=([x,y])=>[x*sw.zoom+m.tx,y*sw.zoom+m.ty];
    const gap=([x,y],r)=>Math.hypot(Math.max(r.left-x,0,x-r.right),Math.max(r.top-y,0,y-r.bottom));
    const rc=rects();
    const first=toScreen(pts[0]), last=toScreen(pts[pts.length-1]);
    const ends=Math.min(gap(first,rc.SplitA)+gap(last,rc.SplitD),gap(first,rc.SplitD)+gap(last,rc.SplitA));
    check(ends<=16,`spline ends meet A and D on screen (gap ${ends.toFixed(1)}px; first ${first}, last ${last})`);
    await pause(400);

    // Clamp: scrolling D far back towards smaller world x/y stops at the dividers' world lines.
    paneD.dispatchEvent(new WheelEvent('wheel',{deltaX:-5000,deltaY:-5000,clientX:pd.right-5,clientY:pd.bottom-5,bubbles:true,cancelable:true}));
    await frame();
    const sc=window.canvasPanesState();
    const pD=sc.panes[3];
    near((pD.rect[0]-pD.tx)/sc.zoom,sc.worldSplitX,0.5,'D left edge clamped at worldSplitX');
    near((pD.rect[1]-pD.ty)/sc.zoom,sc.worldSplitY,0.5,'D top edge clamped at worldSplitY');
    await pause(400);

    // The split is saved on the root box.
    const saved=JSON.parse(root.panes||'{}');
    near(saved.sx,sx/innerWidth,1e-6,'saved sx');near(saved.wx,s.worldSplitX,1e-6,'saved wx');

    // Background draws once per pane.
    const bg=viewport.__fieldWebglBackground;
    check(!bg||!bg.ok||(bg.panes&&bg.panes.length===4),'background has four panes');

    // A bookmark records the split and every pane's camera, and restores them.
    const marked=window.canvasPanesState();
    key('F9',{shiftKey:true,metaKey:true});
    if(!(root.bookmarks||'').includes('bookmark_9'))key('F9',{shiftKey:true,ctrlKey:true});
    check((root.bookmarks||'').includes('bookmark_9'),'bookmark 9 saved');
    window.splitCanvasAt(null,null);
    await frame();
    check(!window.canvasPanesState().split,'unsplit before restoring');
    key('F9',{shiftKey:true});
    for(let i=0;i<40&&!window.canvasPanesState().split;i++)await pause(50);
    const back=window.canvasPanesState();
    check(back.split&&back.columns===2&&back.rows===2,'bookmark restored the split');
    // Wait out the server's echoes of the unsplit and the restore: an echo of our own older value
    // must not undo the restore (or reset the panes' cameras by re-applying the split).
    await pause(800);
    const settled=window.canvasPanesState();
    check(settled.split&&settled.columns===2&&settled.rows===2,'split survives the echoes');
    settled.panes.forEach((p,i)=>{near(p.tx,marked.panes[i].tx,0.5,`pane ${i} tx restored`);near(p.ty,marked.panes[i].ty,0.5,`pane ${i} ty restored`);});
    await frame();

    // Dragging the column divider 60px right moves it, and the content beside it travels with it.
    const dv=document.querySelector('.field-pane-divider-v');
    const dvr=dv.getBoundingClientRect();
    const cx=dvr.left+dvr.width/2, cy=innerHeight*0.7;
    const rd0=rects(), sx0=window.canvasPanesState().splitX;
    mouse(dv,'mousedown',cx,cy);
    mouse(window,'mousemove',cx+30,cy);
    mouse(window,'mousemove',cx+60,cy);
    mouse(window,'mouseup',cx+60,cy);
    await frame();
    const sd=window.canvasPanesState();
    near(sd.splitX,sx0+60/innerWidth,1e-6,'divider moved 60px');
    const rd1=rects();
    ['SplitB','SplitD'].forEach(n=>near(rd1[n].left,rd0[n].left+60,0.5,`${n} travels with the divider`));
    near(JSON.parse(root.panes).sx,sd.splitX,1e-6,'moved divider saved');
    await pause(800);
    near(window.canvasPanesState().splitX,sd.splitX,1e-6,'moved divider survives the echoes');
    near(dv.getBoundingClientRect().left+dvr.width/2,cx+60,1,'handle follows the divider');

    // Dragging the row divider to the top edge removes it; the columns stay.
    const dh=document.querySelector('.field-pane-divider-h');
    const dhr=dh.getBoundingClientRect();
    mouse(dh,'mousedown',innerWidth*0.7,dhr.top+dhr.height/2);
    mouse(window,'mousemove',innerWidth*0.7,1);
    mouse(window,'mouseup',innerWidth*0.7,1);
    await frame();
    const sr=window.canvasPanesState();
    check(sr.split&&sr.columns===2&&sr.rows===1,'row divider removed: '+JSON.stringify({c:sr.columns,r:sr.rows}));
    check(document.querySelectorAll('.field-pane-divider').length===1,'one handle left');

    // Double-clicking the column divider removes it too.
    document.querySelector('.field-pane-divider-v').dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true}));
    await frame();
    check(!window.canvasPanesState().split,'double-click removed the last divider');
    names.forEach(n=>check(boxes[n].parentElement===mainCanvas(),`${n} back on the main canvas`));

    // The corner grip: drag it out and release near the top edge, which makes columns only.
    const grip=document.querySelector('.field-split-grip');
    check(grip&&getComputedStyle(grip).display!=='none','grip shown while unsplit');
    mouse(grip,'mousedown',5,5);
    mouse(window,'mousemove',sx,10);
    mouse(window,'mouseup',sx,10);
    await frame();
    const sg=window.canvasPanesState();
    check(sg.split&&sg.columns===2&&sg.rows===1,'grip split into columns');
    near(sg.splitX,sx/innerWidth,1e-6,'grip divider where released');
    check(getComputedStyle(grip).display==='none','grip hidden while split');

    window.splitCanvasAt(null,null);
    await frame();
    const u=window.canvasPanesState();
    check(!u.split&&u.panes.length===1&&document.querySelectorAll('.field-pane, .field-pane-divider').length===0,'unsplit');
    names.forEach(n=>check(boxes[n].parentElement===mainCanvas(),`${n} back on the main canvas`));
    check(root.panes==='','split cleared on the root box');
    return {ok:true,split:s,where,spline:{first,last,gap:ends},dividerMovedTo:sd.splitX};
} catch(e){return {ok:false,reason:String(e),state:window.canvasPanesState&&window.canvasPanesState()};}
