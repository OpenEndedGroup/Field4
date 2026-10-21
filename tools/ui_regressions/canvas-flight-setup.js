// Saves three bookmarks for a canvas flight: the whole document, the plan, and the three.js sheet,
// each framed (box and its output) in the window as it is, and named as a person would name them.
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const check=(v,m)=>{if(!v)throw Error(m);};
try {
    const field=await import('./afield.mjs');
    check(window.fieldCanvasFlight&&window.moveCanvasCamera,'the canvas flight API');
    const box=name=>[...document.querySelectorAll('.box')].find(b=>b.name===name||b.textContent.includes(name));
    const names=['FlightTitle','FlightPlan','FlightModel'];
    for(let i=0;i<200&&!names.every(box);i++)await pause(50);
    check(names.every(box),`boxes ${names}`);
    // The three.js sheet has drawn before anything is framed or saved.
    const viewer=()=>[...document.querySelectorAll('iframe')].map(f=>{try{return f.contentWindow.FieldViewer}catch(e){return null}}).find(v=>v&&v.film);
    for(let i=0;i<400&&!(viewer()&&viewer().captureStatus().ready);i++)await pause(50);
    check(viewer()&&viewer().captureStatus().ready,'the three.js view is ready');
    // Every output panel open, as a person would have them for the film, and settled before measuring.
    for(const n of names){
        const group=box(n).querySelector('.outputArea .disclosure-group');
        const inter=group&&window.disclosureInterface&&window.disclosureInterface(group);
        if(inter)for(const id of inter.ids())if(id!=='nested'&&!inter.isOpen(id))inter.open(id);
    }
    await pause(800);await frame();
    const world=el=>{const c=window.fieldCanvasFlight.camera();const r=el.getBoundingClientRect();
        return {x:(r.left-c.x)/c.zoom,y:(r.top-c.y)/c.zoom,w:r.width/c.zoom,h:r.height/c.zoom};};
    // The box and its open panels, measured as Frame Selected measures them (frameRectsFor in Main.kt).
    const extent=b=>[b,...b.querySelectorAll('.outputArea .disclosure')].filter(e=>e.getBoundingClientRect().width>0).map(world);
    const union=rs=>{const x=Math.min(...rs.map(r=>r.x)),y=Math.min(...rs.map(r=>r.y));
        return {x,y,w:Math.max(...rs.map(r=>r.x+r.w))-x,h:Math.max(...rs.map(r=>r.y+r.h))-y};};
    const saved=[];
    const frameAndSave=async(slot,name,rects,margin)=>{
        const u=union(rects),W=innerWidth,H=innerHeight;
        const z=Math.max(.1,Math.min(4,margin*Math.min(W/u.w,H/u.h)));
        window.moveCanvasCamera(W/2-(u.x+u.w/2)*z,H/2-(u.y+u.h/2)*z,z);
        await frame();
        field.saveBookmarkSlot(slot);field.nameBookmarkSlot(slot,name);
        saved.push({slot,name,camera:window.fieldCanvasFlight.camera()});
    };
    await frameAndSave(1,'overview',names.flatMap(n=>extent(box(n))),.85);
    await frameAndSave(2,'plan',extent(box('FlightPlan')),.8);
    await frameAndSave(3,'close-ups',extent(box('FlightModel')),.9);
    // Leave the camera somewhere else: the flight must start on its first stop by itself.
    window.moveCanvasCamera(-300,-200,1.7);await frame();
    return {ok:true,saved,viewport:[innerWidth,innerHeight]};
} catch(e){return {ok:false,reason:String(e&&e.stack||e)};}
