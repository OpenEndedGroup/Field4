const {send}=await import('./afield.mjs');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const wait=async(f,message)=>{for(let i=0;i<160;i++){const v=f();if(v)return v;await delay(50);}throw Error(message);};
window.setAutoDisplayEditor(false);window.moveCanvasCamera(0,0,1);
for(const b of [...window.selectedList])window.deselectBox(b);
const box=name=>[...document.querySelectorAll('.box')].find(b=>b.name===name);
const a=box('Curve A'),b=box('Curve B'),c=box('Curve C');
const path=(from,to)=>document.querySelector(`path[data-connection-from="${from.uid}"][data-connection-to="${to.uid}"]`);
for(const [from,to] of [[a,b],[b,c]]){
 const p=await wait(()=>path(from,to),'Missing regular connection');
 await delay(500);
 const length=p.getTotalLength(),s=p.getPointAtLength(0),e=p.getPointAtLength(length),direct=Math.hypot(e.x-s.x,e.y-s.y);
 if(!Number.isFinite(length)||length>direct*1.7+20)throw Error('Nearby boxes produced an oversized loop: '+JSON.stringify({length,direct}));
}
// The effective bend radius should ease down continuously rather than keeping a large loop.
const radii=[];
for(const gap of [350,160,70,25,6]){
 const frames=[{uid:a.uid,property:'frame',value:{x:90,y:60,w:180,h:80}},{uid:b.uid,property:'frame',value:{x:90,y:140+gap,w:180,h:80}}];
 send('set-rects',{values:frames});
 await wait(()=>Math.abs(b.data_y-(140+gap))<.1,'Frame move did not sync');await delay(450);
 const p=path(a,b),r=Number(p.dataset.connectionRadius);radii.push(r);
 const length=p.getTotalLength();
 if(!Number.isFinite(r)||r<=0||length>gap*1.1+5)throw Error('Aligned neighbours stopped connecting directly: '+JSON.stringify({gap,r,length}));
}
if(!radii.every((r,i)=>i===0||r<radii[i-1]))throw Error('Minimum radius did not ease with proximity: '+radii);
send('set-rects',{values:[{uid:a.uid,property:'frame',value:{x:70,y:70,w:290,h:105}},{uid:b.uid,property:'frame',value:{x:340,y:310,w:330,h:80}}]});
await wait(()=>a.data_x===70&&b.data_x===340,'Could not restore the demonstration layout');await delay(600);
window.selectBox(a);
return {ok:true,ordinaryCurves:true,proximityRadii:radii};
