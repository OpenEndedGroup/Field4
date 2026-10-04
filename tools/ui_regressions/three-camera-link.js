const checks=[],wait=async f=>{for(let i=0;i<180;i++){if(f())return;await new Promise(r=>setTimeout(r,100));}throw Error('Linked camera readiness timeout');};
const expect=(value,label)=>{checks.push({label,passed:!!value});if(!value)throw Error(label)};
const frames=()=>[0,1,2].map(i=>[...document.querySelectorAll('iframe')].find(f=>f.src.includes(`camera-link-${i}`)));
await wait(()=>frames().every(f=>f?.contentWindow.FieldViewer?.captureStatus().ready));
const [a,b,c]=frames().map(f=>f.contentWindow),apis=[a,b,c].map(w=>w.FieldInspector),buttons=[a,b,c].map(w=>w.document.getElementById('camera-link'));
const originals=apis.map(api=>api.snapshot()),same=(x,y)=>JSON.stringify(x)===JSON.stringify(y);
try {
 expect(buttons.every(b=>!b.disabled&&b.getAttribute('aria-pressed')==='false'),'Camera links start disabled by user choice');
 apis[1].xray(true,.3);
 buttons[0].click();buttons[1].click();
 expect([a,b].every((w,i)=>w.getComputedStyle(buttons[i]).backgroundColor!=='rgb(0, 0, 0)'), 'Linked state has a visible highlighted button');
 const project=(api,unit)=>[[100,100,100],[650,150,100],[100,700,100],[100,100,700]].map(p=>api.project('drawable:0',p.map(v=>v/unit)));
 const aligned=()=>{const p=project(apis[0],1),q=project(apis[1],1000);return p.every((p,i)=>Math.abs(p.x-q[i].x)<1e-6&&Math.abs(p.y-q[i].y)<1e-6)};
 await wait(aligned);expect(true,'Initial link aligns real projected features across origins, units and display axes');
 const local=apis[1].snapshot(),off=apis[2].snapshot();
 const pose=apis[0].snapshot();apis[0].restore({...pose,camera:pose.camera.map((v,i)=>v+(i===0?340:90)),target:pose.target.map((v,i)=>v+(i===2?120:0)),cameraFov:38,cameraZoom:1.2});
 await wait(aligned);
 expect(apis[1].snapshot().xray&&apis[1].snapshot().xrayOpacity===.3,'Linked cameras retain independent X-ray');
 for(const key of ['hidden','selection','explode','tools','sectionEnabled'])expect(same(local[key],apis[1].snapshot()[key]),`Link does not copy ${key}`);
 expect(same(off,apis[2].snapshot()),'A third view stays independent');
 const settled=apis[1].snapshot();await new Promise(r=>setTimeout(r,500));
 expect(same(settled,apis[1].snapshot()),'Remote poses settle without camera echo or damping drift');
 buttons[1].click();const unlinked=apis[1].snapshot();apis[0].restore({...apis[0].snapshot(),camera:pose.camera});
 await new Promise(r=>setTimeout(r,200));expect(same(unlinked,apis[1].snapshot()),'Unlink restores independent camera motion');
 buttons[1].click();await wait(aligned);expect(true,'Relinking follows the active peer');
 return {ok:true,checks,linkedSnapshots:apis.slice(0,2).map(api=>api.snapshot()),independentSnapshot:apis[2].snapshot()};
} catch(e){return {ok:false,reason:String(e),checks};}
finally{buttons.forEach(b=>{if(b.getAttribute('aria-pressed')==='true')b.click()});apis.forEach((api,i)=>api.restore(originals[i]));}
