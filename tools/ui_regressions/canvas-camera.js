// Every layer that copies the canvas camera agrees with the boxes after a wheel pan, a
// middle-drag pan and a pinch zoom: the box moves by the screen delta, the zoom keeps the world
// point under the pointer, and the connection overlay and Excalidraw report the same camera.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const check=(v,m)=>{if(!v)throw Error(m);};
const near=(a,b,tol,m)=>check(Math.abs(a-b)<=tol,`${m}: ${a} vs ${b}`);
try {
    const viewport=document.querySelector('.field-main-viewport');
    const box=[...document.querySelectorAll('.box')].find(b=>b.textContent.includes('CameraAlpha'));
    check(viewport&&box,'viewport and CameraAlpha box');
    const rect=()=>box.getBoundingClientRect();
    // The camera, read back from the box: screen = world * zoom + t.
    const camera=()=>{const z=window.canvasZoom();const r=rect();return {z,tx:r.left-box.data_x*z,ty:r.top-box.data_y*z};};

    // Mount Excalidraw before moving, so the check below sees the live sync, not its initial camera.
    window.toggleDrawingLayer();
    for(let i=0;i<200&&!window.drawingLayerState().ready;i++)await pause(50);
    check(window.drawingLayerState().ready,'drawing layer loaded');
    window.toggleDrawingLayer();
    await frame();

    const r0=rect();
    viewport.dispatchEvent(new WheelEvent('wheel',{deltaX:40,deltaY:25,clientX:5,clientY:5,bubbles:true,cancelable:true}));
    await frame();
    near(rect().left,r0.left-40,0.5,'wheel pan x');near(rect().top,r0.top-25,0.5,'wheel pan y');

    const r1=rect();
    viewport.dispatchEvent(new MouseEvent('mousemove',{buttons:4,movementX:15,movementY:-10,clientX:5,clientY:5,bubbles:true,cancelable:true}));
    await frame();
    near(rect().left,r1.left+15,0.5,'middle-drag x');near(rect().top,r1.top-10,0.5,'middle-drag y');

    const r2=rect();const cx=r2.left+r2.width/2,cy=r2.top+r2.height/2;const z0=window.canvasZoom();
    viewport.dispatchEvent(new WheelEvent('wheel',{deltaY:-40,ctrlKey:true,clientX:cx,clientY:cy,bubbles:true,cancelable:true}));
    await frame();
    const z1=window.canvasZoom();
    check(z1>z0*1.2,`pinch zoom in: ${z0} -> ${z1}`);
    const r3=rect();
    near(r3.left+r3.width/2,cx,1,'zoom anchor x');near(r3.top+r3.height/2,cy,1,'zoom anchor y');
    near(r3.width,r2.width*z1/z0,1,'box scales with zoom');

    const cam=camera();
    const overlay=document.getElementById('overlaysvglayer');
    const vb=(overlay.getAttribute('viewBox')||'').split(/\s+/).map(Number);
    near(vb[0],-cam.tx/cam.z,1,'overlay viewBox x');near(vb[1],-cam.ty/cam.z,1,'overlay viewBox y');

    const d=window.drawingLayerState();
    // Excalidraw keeps six decimals of zoom.
    near(d.zoom,cam.z,1e-4,'excalidraw zoom');near(d.scrollX,cam.tx/cam.z,1,'excalidraw scrollX');near(d.scrollY,cam.ty/cam.z,1,'excalidraw scrollY');

    window.resetCanvasZoom();
    await frame();
    near(window.canvasZoom(),1,1e-9,'reset zoom');
    return {ok:true,zoom:z1,camera:cam,viewBox:vb,excalidraw:{zoom:d.zoom,scrollX:d.scrollX,scrollY:d.scrollY}};
} catch(e){return {ok:false,reason:String(e)};}
