import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const source=await readFile(new URL('../src/commonMain/resources/threejs_camera_link.js',import.meta.url),'utf8');
const {createCameraFollow}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const close=(a,b)=>a.forEach((v,i)=>assert.ok(Math.abs(v-b[i])<1e-6,`${a} != ${b}`));
function view(up=[0,1,0]) {
 let t=0,visible=true; const eye=[1,2,5],target=[0,1,0];
 const follow=createCameraFollow({now:()=>t,visible:()=>visible,up:()=>up,
   translate:d=>d.forEach((v,i)=>{eye[i]+=v;target[i]+=v;})});
 return {follow,eye,target,tick:dt=>{t+=dt;return follow.step()},hide:()=>visible=false,show:()=>visible=true};
}
const spec=(target,epoch='one',horizontalOnly=true)=>({target,epoch,halfLifeSeconds:.4,horizontalOnly});
const v=view();v.follow.configure(spec([0,1,0]));v.follow.configure(spec([10,8,0]));
for(let i=0;i<4;i++)v.tick(100);
close(v.target,[5,1,0]);close(v.eye.map((x,i)=>x-v.target[i]),[1,1,5]); // half-life; no vertical bob or orbit reset
// Manual pan persists through subsequent tracking.
v.eye[0]+=2;v.target[0]+=2;for(let i=0;i<4;i++)v.tick(100);close(v.target,[9.5,1,0]);
const fast=view(),slow=view();for(const x of [fast,slow]){x.follow.configure(spec([0,0,0]));x.follow.configure(spec([8,0,0]));}
for(let i=0;i<100;i++)fast.tick(10);for(let i=0;i<10;i++)slow.tick(100);close(fast.target,slow.target);
const before=[...v.target];v.follow.configure(null);v.tick(200);close(v.target,before);
v.follow.configure(spec([100,0,0]));v.follow.configure(spec([0,0,0],'reset'));close(v.target,[before[0]-100,1,0]);
assert.throws(()=>v.follow.configure(spec([NaN,0,0])),/Invalid/);
const z=view([0,0,1]);z.follow.configure(spec([0,0,0]));z.follow.configure(spec([2,4,8]));for(let i=0;i<4;i++)z.tick(100);close(z.target,[1,3,0]);
const y=view();y.follow.configure(spec([0,0,0], 'one', false));y.follow.configure(spec([0,2,0], 'one', false));for(let i=0;i<4;i++)y.tick(100);close(y.target,[0,2,0]);
y.hide();const held=[...y.target];y.tick(100);close(y.target,held);y.show();
console.log('Three camera follow: half-life, cadence, orbit/pan, disable, reset and Y/Z up passed');
