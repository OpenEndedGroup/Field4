// The drawing layer on a split canvas: one Excalidraw per pane, each sized to its pane and following
// its camera; only the main pane shows the toolbar; the tool follows the pointer into another pane;
// a mark drawn there lands at the world point under the pointer in that pane's camera, reaches the
// other panes' mounts and the server, and removing the split leaves one mount with the mark.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const check=(v,m)=>{if(!v)throw Error(m);};
const near=(a,b,tol,m)=>check(Math.abs(a-b)<=tol,`${m}: ${a} vs ${b}`);
const ptr=(target,type,x,y,buttons)=>target.dispatchEvent(new PointerEvent(type,{clientX:x,clientY:y,button:0,buttons,pointerId:1,pointerType:'mouse',isPrimary:true,bubbles:true,cancelable:true,composed:true}));
try {
    const box=n=>[...document.querySelectorAll('.box')].find(b=>b.textContent.includes(n));
    // Load the layer.
    window.toggleDrawingLayer();
    for(let i=0;i<200&&!window.drawingLayerState().ready;i++)await pause(50);
    check(window.drawingLayerState().ready,'drawing layer loaded');
    window.toggleDrawingLayer();
    check(window.drawingLayerState().mounts===1,'one mount unsplit');

    // Split between DrawA (world 100,100) and DrawD (world 500,400), then pan D apart from A.
    const a=box('DrawA').getBoundingClientRect(), d=box('DrawD').getBoundingClientRect();
    window.splitCanvasAt((a.right+d.left)/2,(a.bottom+d.top)/2);
    for(let i=0;i<100&&window.drawingLayerState().mounts!==4;i++)await pause(50);
    check(window.drawingLayerState().mounts===4,'four mounts: '+window.drawingLayerState().mounts);
    for(let i=0;i<100&&window.drawingLayerState().panes.some(p=>!p.ready);i++)await pause(50);
    const paneD=document.querySelectorAll('.field-pane')[3], pd=paneD.getBoundingClientRect();
    paneD.dispatchEvent(new WheelEvent('wheel',{deltaX:120,deltaY:90,clientX:pd.right-5,clientY:pd.bottom-5,bubbles:true,cancelable:true}));
    await pause(400);
    await frame();

    // Every mount sits on its pane and follows its camera.
    const s=window.canvasPanesState(), ds=window.drawingLayerState();
    const hosts=[...document.querySelectorAll('.field-drawing-pane')];
    check(hosts.length===4,'four mount hosts');
    s.panes.forEach((p,i)=>{
        const hr=hosts[i].getBoundingClientRect();
        // The mount covers the pane less its share of the 3 px dividers.
        check(hr.left>=p.rect[0]-0.5&&hr.right<=p.rect[0]+p.rect[2]+0.5&&hr.width>=p.rect[2]-3.5,`mount ${i} on its pane: ${JSON.stringify(hr)} vs ${p.rect}`);
        check(hr.top>=p.rect[1]-0.5&&hr.bottom<=p.rect[1]+p.rect[3]+0.5&&hr.height>=p.rect[3]-3.5,`mount ${i} on its pane vertically`);
        near(ds.panes[i].zoom,s.zoom,1e-4,`mount ${i} zoom`);
        near(ds.panes[i].scrollX*s.zoom+hr.left,p.tx,1,`mount ${i} follows its pane in x`);
        near(ds.panes[i].scrollY*s.zoom+hr.top,p.ty,1,`mount ${i} follows its pane in y`);
    });
    check(Math.abs(s.panes[0].tx-s.panes[3].tx)>50,'A and D cameras apart');
    check(hosts.slice(0,3).every(h=>h.classList.contains('field-drawing-mirror'))&&!hosts[3].classList.contains('field-drawing-mirror'),'only the main mount shows its UI');

    // Draw a rectangle in pane A: pick it in the main pane, move into A, drag.
    window.toggleDrawingLayer();
    await pause(300);
    // Active: only the main mount shows Excalidraw's controls, whatever layout a pane's width picks.
    const chrome=h=>[...h.querySelectorAll('.excalidraw-container > *')].filter(el=>el.tagName!=='CANVAS'&&!el.classList.contains('excalidraw__canvas-wrapper')&&!el.classList.contains('SVGLayer')&&!el.classList.contains('excalidraw-textEditorContainer')&&getComputedStyle(el).display!=='none'&&el.getBoundingClientRect().width>0);
    hosts.slice(0,3).forEach((h,i)=>check(chrome(h).length===0,`mirror ${i} shows controls: `+chrome(h).map(e=>e.className).join(' | ')));
    check(chrome(hosts[3]).length>0,'main mount shows its controls');
    window.drawingLayerSetTool('rectangle');
    const pa=s.panes[0], ar=pa.rect;
    const p1=[ar[0]+20, ar[1]+ar[3]-90], p2=[ar[0]+70, ar[1]+ar[3]-30];
    check(!document.elementsFromPoint(...p1).some(e=>e.closest&&e.closest('.box')),'p1 is empty canvas');
    // The main pane's tool change commits on React's schedule; the pointer copies what it has.
    for(let i=0;i<40&&window.drawingLayerState().tool!=='rectangle';i++)await pause(25);
    check(window.drawingLayerState().tool==='rectangle','rectangle tool chosen in the main pane');
    ptr(window,'pointermove',p1[0],p1[1],0);
    for(let i=0;i<20&&window.drawingLayerState().panes[0].tool!=='rectangle';i++)await pause(25);
    check(window.drawingLayerState().panes[0].tool==='rectangle','tool followed the pointer into A: hover='+window.drawingLayerState().hoverMount+' '+JSON.stringify(window.drawingLayerState().panes.map(p=>[p.active,p.tool]))+' at '+p1+' pane '+JSON.stringify(ar));
    const canvasA=hosts[0].querySelector('canvas.interactive')||hosts[0].querySelectorAll('canvas')[1];
    check(canvasA,'pane A interactive canvas');
    // Excalidraw handles pointer moves once per frame, so the drag is spread over frames (a burst
    // of moves then an immediate up finalises an empty shape, which a restore then drops).
    ptr(canvasA,'pointerdown',p1[0],p1[1],1);
    await frame();
    for(let k=1;k<=6;k++){ptr(canvasA,'pointermove',p1[0]+(p2[0]-p1[0])*k/6,p1[1]+(p2[1]-p1[1])*k/6,1);await frame();}
    ptr(canvasA,'pointerup',p2[0],p2[1],0);
    await pause(900);

    // The mark is at the world point under the pointer in A's camera, in every mount, and sent.
    const scene=window.drawingLayerScene();
    const rect=scene.find(e=>e.type==='rectangle'&&!e.isDeleted);
    const atP1=document.elementFromPoint(...p1);
    check(rect,'a rectangle in the main mount: '+JSON.stringify(scene.map(e=>e.type))+' counts '+JSON.stringify(window.drawingLayerState().panes.map(p=>[p.elementCount,p.tool]))+' at p1 '+(atP1&&(atP1.tagName+'.'+atP1.className+' in host '+hosts.indexOf(atP1.closest('.field-drawing-pane')))));
    near(rect.x,(p1[0]-pa.tx)/s.zoom,2,'rectangle x in A camera');near(rect.y,(p1[1]-pa.ty)/s.zoom,2,'rectangle y in A camera');
    window.drawingLayerState().panes.forEach((p,i)=>check(p.elementCount===1,`mount ${i} has the mark (${p.elementCount})`));
    // And pane A shows it: its static canvas is on screen and painted (hiding Excalidraw's controls
    // in a mirror must not hide the canvas the marks are drawn on).
    const staticA=hosts[0].querySelector('canvas.static');
    check(staticA&&staticA.getBoundingClientRect().width>0,'pane A static canvas is on screen');
    const px=staticA.getContext('2d').getImageData(0,0,staticA.width,staticA.height).data;
    let lit=0;for(let k=3;k<px.length;k+=4)if(px[k]>0)lit++;
    check(lit>100,'pane A static canvas is painted: '+lit);
    const root=[...document.querySelectorAll('.box')].find(b=>b.uid==='root');
    check((root.drawing||'').includes(rect.id),'mark sent to the server');
    window.toggleDrawingLayer();

    // Unsplit: one mount again, still holding the mark.
    window.splitCanvasAt(null,null);
    await pause(400);
    const u=window.drawingLayerState();
    check(u.mounts===1&&u.elementCount===1,'unsplit keeps one mount with the mark: '+JSON.stringify({m:u.mounts,n:u.elementCount}));
    check(document.querySelectorAll('.field-drawing-pane').length===1,'one mount host');
    return {ok:true,rect:{x:rect.x,y:rect.y},paneA:pa};
} catch(e){return {ok:false,reason:String(e),drawing:window.drawingLayerState&&window.drawingLayerState(),panes:window.canvasPanesState&&window.canvasPanesState()};}
