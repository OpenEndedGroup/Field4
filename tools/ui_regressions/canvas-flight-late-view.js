// After canvas-flight-check: a three.js view whose panel opens during a flight joins it there. With
// the sheet's panel closed, filming starts with no view; the panel opens part-way, and from the next
// frame the view is filming and turns 8°/s from where it stood when it joined, not from the start.
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const check=(v,m)=>{if(!v)throw Error(m);};
const near=(a,b,tol,m)=>check(Math.abs(a-b)<=tol,`${m}: ${a} vs ${b}`);
const RATE=8;
const api=window.fieldCanvasFlight;
try {
    const box=[...document.querySelectorAll('.box')].find(b=>b.name==='FlightModel'||b.textContent.includes('FlightModel'));
    check(box,'the FlightModel box');
    const group=box.querySelector('.outputArea .disclosure-group');
    const inter=group&&window.disclosureInterface&&window.disclosureInterface(group);
    check(inter,'its output panels');
    const sheet=inter.ids().filter(id=>id!=='nested'&&[...group.querySelectorAll('iframe')].length>0);
    sheet.forEach(id=>inter.close(id));
    await pause(300);await frame();
    check(api.viewers()===0,`no three.js view showing with the panel closed (${api.viewers()})`);
    const begun=api.beginRecording(JSON.stringify({stops:[{bookmark:'overview'},{bookmark:'close-ups'}],legSeconds:1.5,holdSeconds:0.5}));
    check(begun.ok,`filming began: ${begun.error}`);
    check(begun.viewers===0,`no view at the start (${begun.viewers})`);
    const before=await api.seek(0.5);
    check(before.views.length===0,'still no view at 0.5 s');
    sheet.forEach(id=>inter.open(id));
    const viewer=()=>[...document.querySelectorAll('iframe')].map(f=>{try{return f.contentWindow.FieldViewer}catch(e){return null}}).find(v=>v&&v.film);
    for(let i=0;i<400&&!(viewer()&&viewer().captureStatus().ready&&api.viewers()>0);i++)await pause(50);
    check(api.viewers()>0,'the view shows once its panel opens');
    const joined=await api.seek(1.0);
    check(joined.views.length===1&&joined.views[0]&&joined.views[0].filming,'the view joined the flight at 1 s');
    const later=await api.seek(2.0);
    check(later.views.length===1&&later.views[0].filming,'still filming at 2 s');
    const sub=(a,b)=>a.map((x,i)=>x-b[i]),dot=(a,b)=>a.reduce((s,x,i)=>s+x*b[i],0);
    const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
    const v0=joined.views[0],v1=later.views[0],up=v0.up;
    const flat=v=>{const d=sub(v.eye,v.target),k=dot(d,up);return d.map((x,i)=>x-k*up[i]);};
    const turned=Math.atan2(dot(up,cross(flat(v0),flat(v1))),dot(flat(v0),flat(v1)))*180/Math.PI;
    // One second since it joined, not two since the flight began.
    near(Math.abs(turned),RATE*1.0,0.5,'degrees turned since joining');
    const ended=api.endRecording();
    check(ended.ok,'filming ended');
    check(!viewer().film.status().filming,'the late view stopped filming');
    for(let k=0;k<3;k++)near(viewer().film.status().eye[k],v0.eye[k],1e-6,`late view's eye[${k}] restored`);
    return {ok:true,turnedDegrees:turned};
} catch(e){try{api.endRecording();}catch(_){} return {ok:false,reason:String(e&&e.stack||e)};}
