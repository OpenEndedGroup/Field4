// After `canvas-flight overview plan close-ups --leg 1.5 --hold 0.5 --fps 10 --record ...`: the film
// has one frame per tenth of a second of the 4.5 s flight, the camera in the frame that reaches each
// stop is exactly where restoring that bookmark puts it, the three.js sheet turned 8°/s about its
// target meanwhile, and it has its own camera back now.
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const check=(v,m)=>{if(!v)throw Error(m);};
const near=(a,b,tol,m)=>check(Math.abs(a-b)<=tol,`${m}: ${a} vs ${b}`);
const FPS=10, DURATION=0.5+2*(1.5+0.5), RATE=8;
try {
    const field=await import('./afield.mjs');
    const rec=window.fieldCanvasFlight.lastRecording();
    check(rec&&rec.stops&&rec.stops.length===3,'a filmed flight with three stops');
    check(rec.frames===Math.round(DURATION*FPS)&&rec.cameras.length===rec.frames,`frames: ${rec.frames} filmed, ${rec.cameras.length} recorded`);
    near(rec.cameras[0].t,0,1e-9,'first frame at 0');near(rec.cameras[rec.frames-1].t,DURATION,1e-9,'last frame at the end');
    const reached=[];
    for(const stop of rec.stops){
        const shot=rec.cameras.find(c=>c.t>=stop.arrival-1e-9);
        check(shot,`a frame at or after ${stop.name}'s arrival`);
        near(shot.x,stop.saved.x,0.5,`${stop.name} x`);near(shot.y,stop.saved.y,0.5,`${stop.name} y`);
        near(shot.zoom,stop.saved.zoom,1e-6,`${stop.name} zoom`);
        // Restoring the bookmark puts the camera exactly where the flight reached it.
        field.restoreBookmarkSlot(stop.slot);await frame();
        const restored=window.fieldCanvasFlight.camera();
        near(restored.x,shot.x,0.5,`restored ${stop.name} x`);near(restored.y,shot.y,0.5,`restored ${stop.name} y`);
        near(restored.zoom,shot.zoom,1e-9,`restored ${stop.name} zoom`);
        reached.push({name:stop.name,t:shot.t,arrival:stop.arrival,camera:shot});
    }
    // The pan between stops moves smoothly: no frame jumps further than the largest step of the ease.
    const steps=rec.cameras.slice(1).map((c,i)=>Math.hypot(c.x-rec.cameras[i].x,c.y-rec.cameras[i].y));
    // The three.js sheet: turned about its target by RATE degrees a second, every frame.
    const views=rec.cameras.map(c=>c.views&&c.views[0]).filter(Boolean);
    check(views.length===rec.frames,`the three.js view reported in every frame (${views.length})`);
    // The signed angle about the view's up axis between the first frame's eye and the last's.
    const sub=(a,b)=>a.map((x,i)=>x-b[i]),dot=(a,b)=>a.reduce((s,x,i)=>s+x*b[i],0);
    const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
    const up=views[0].up,flat=v=>{const d=sub(v.eye,v.target),k=dot(d,up);return d.map((x,i)=>x-k*up[i]);};
    const a=flat(views[0]),b=flat(views.at(-1));
    const wrapped=Math.atan2(dot(up,cross(a,b)),dot(a,b))*180/Math.PI;
    near(Math.abs(wrapped),RATE*DURATION,0.5,'degrees turned over the flight');
    check(views.every(v=>v.filming),'filming in every frame');
    // Afterwards the view is back where it started and no longer filming.
    const viewer=[...document.querySelectorAll('iframe')].map(f=>{try{return f.contentWindow.FieldViewer}catch(e){return null}}).find(v=>v&&v.film);
    const after=viewer.film.status();
    check(!after.filming,'the view stopped filming');
    for(let k=0;k<3;k++)near(after.eye[k],views[0].eye[k],1e-6,`restored eye[${k}]`);
    check(!document.body.classList.contains('canvas-flight-recording'),'recording class removed');
    return {ok:true,frames:rec.frames,reached,turnedDegrees:wrapped,maxStepPx:Math.max(...steps)};
} catch(e){return {ok:false,reason:String(e&&e.stack||e)};}
