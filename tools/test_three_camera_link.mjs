import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const source=await readFile(new URL('../src/commonMain/resources/threejs_camera_link.js',import.meta.url),'utf8');
const {createCameraLink,createTurntable,cameraLinkFrame,cameraToModel,cameraFromModel}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const base={eye:[200,300,400],target:[100,120,130],up:[0,1,0],fov:45,zoom:1,near:.1,far:100000};
const close=(a,b)=>{if(Array.isArray(a))a.forEach((x,i)=>close(x,b[i]));else if(typeof a==='object')Object.keys(a).forEach(k=>close(a[k],b[k]));else assert.ok(Math.abs(a-b)<1e-9,`${a} != ${b}`)};
const a=cameraLinkFrame({scope:'one',origin:[1000,2000,3000],upAxis:'Z',units:'mm'});
const b=cameraLinkFrame({scope:'one',origin:[1,2,3],upAxis:'Y',units:'m'});
const world=cameraToModel({...base,selection:'never transmit',hidden:['part']},a);
close(world.eye,[1.2,1.6,3.3]);close(world.target,[1.1,1.87,3.12]);
close(cameraFromModel(world,a),base);
const peer=cameraFromModel(world,b);close(peer.eye,[.2,-.4,.3]);close(peer.up,[0,0,1]);close(peer.near,.0001);
close(cameraToModel(peer,b),world);assert.equal(world.selection,undefined);assert.equal(world.hidden,undefined);
assert.equal(cameraLinkFrame(null),null);assert.equal(cameraLinkFrame({scope:'',origin:[0,0,0]}),null);
assert.throws(()=>cameraFromModel({...world,eye:[NaN,0,0]},a),/Invalid/);

// Real asynchronous channel semantics, including join alignment, isolation and echo prevention.
const buses=[];let messages=0;
class Bus {
 constructor(name){this.name=name;this.closed=false;buses.push(this)}
 postMessage(data){messages++;for(const b of buses)if(b!==this&&!b.closed&&b.name===this.name)queueMicrotask(()=>{if(!b.closed)b.onmessage?.({data:structuredClone(data)})})}
 close(){this.closed=true}
}
let nextId=0;
function client(frame){
 let pose=structuredClone(base),writes=0;const display={hidden:['private'],xray:true,explode:72,selection:'own note'};
 const c=createCameraLink({id:String(++nextId),channel:n=>new Bus(n),read:()=>pose,write:p=>{pose=p;writes++;c.localChanged()}});
 c.configure(frame);return {c,display,get pose(){return pose},get writes(){return writes},move:p=>{pose=p;c.localChanged()}};
}
const frame={scope:'one',origin:[1000,2000,3000],upAxis:'Z',units:'mm'};
const x=client(frame),y=client({...frame,origin:[1,2,3],upAxis:'Y',units:'m'}),other=client({...frame,scope:'another-document'}),off=client(frame);
const tick=()=>new Promise(r=>setTimeout(r,0));
x.c.setEnabled(true);other.c.setEnabled(true);await tick();y.c.setEnabled(true);await tick();
close(y.pose,cameraFromModel(cameraToModel(x.pose,a),b));assert.equal(y.writes,1);assert.equal(other.writes,0);assert.equal(off.writes,0);
const count=messages;x.move({...base,eye:[500,600,700],fov:32,zoom:1.4});await tick();assert.equal(messages,count+1);assert.equal(y.writes,2);
assert.deepEqual(y.display,{hidden:['private'],xray:true,explode:72,selection:'own note'});
y.c.setEnabled(false);x.move({...base,eye:[800,900,1000]});await tick();assert.equal(y.writes,2);
y.c.setEnabled(true);await tick();close(cameraToModel(y.pose,b),cameraToModel(x.pose,a));
y.c.dragging(true);const before=y.writes;x.move({...base,eye:[700,400,800]});await tick();assert.equal(y.writes,before);y.c.dragging(false);
const keep=cameraToModel(y.pose,b);y.c.configure({...frame,origin:[8,9,10],upAxis:'Y',units:'m'});close(cameraToModel(y.pose,cameraLinkFrame({...frame,origin:[8,9,10],upAxis:'Y',units:'m'})),keep);
const invalid=buses.find(s=>!s.closed&&s.name.includes(':one:'));invalid.postMessage({sender:'bad',type:'pose',pose:{eye:['bad']}});await tick();
const beforeDispose=x.writes;x.c.dispose();y.move(base);await tick();assert.equal(x.writes,beforeDispose);
for(const c of [y,other,off])c.c.dispose();assert.ok(buses.every(b=>b.closed));
console.log('PASS: model-space origins, axes, units, projection, opt-in, join, unlink, document isolation, no echo, drag ownership, rebase and disposal');

// Groups: the default group keeps its channel name; a named group links only within itself.
assert.equal(cameraLinkFrame({scope:'one',group:''}),null);assert.equal(cameraLinkFrame({scope:'one',group:'g'.repeat(65)}),null);
const grouped={...frame,scope:'grouped'};
const g1=client({...grouped,group:'houses'}),g2=client({...grouped,group:'houses'}),g3=client(grouped);
g1.c.setEnabled(true);g3.c.setEnabled(true);await tick();g2.c.setEnabled(true);await tick();
assert.equal(g2.writes,1);assert.equal(g3.writes,0);
g1.move({...base,eye:[50,60,70]});await tick();assert.equal(g2.writes,2);assert.equal(g3.writes,0);
assert.ok(buses.some(b=>!b.closed&&b.name==='field-three-camera-v1:grouped:length'));
for(const g of [g1,g2,g3])g.c.dispose();

// Turntable bookkeeping on a fake clock.
let clock=0;const woke=[];
const spin=(key,extra={})=>createTurntable({key,now:()=>clock,wake:ms=>woke.push(ms),...extra});
const deg=Math.PI/180;
const t=spin('a');t.configure({degreesPerSecond:6,resumeAfterSeconds:2});
assert.equal(t.step(),0);assert.ok(t.driving());clock+=100;close(t.step(),.6*deg);
clock+=5000;close(t.step(),.6*deg); // a long gap (a hidden page) is one clamped step, not a jump
t.hold(true);assert.equal(t.step(),null);assert.equal(t.driving(),false);clock+=10000;assert.equal(t.step(),null);
t.hold(false);assert.equal(woke.at(-1),2000);clock+=1999;assert.equal(t.step(),null);clock+=1;assert.equal(t.step(),0);
t.nudge();assert.equal(t.step(),null);clock+=2000;assert.equal(t.step(),0);
assert.equal(t.heard({drive:null}),true);assert.equal(t.step(),null);clock+=2000;assert.equal(t.step(),0);
t.heard({busy:true});assert.equal(t.step(),null);clock+=2000;assert.equal(t.step(),0);
// Election: the worse key yields to the better, waits out a second of silence, then takes over.
const b2=spin('b');b2.configure({degreesPerSecond:6});assert.equal(b2.step(),0);clock+=16;assert.ok(b2.step()>0);
assert.equal(b2.heard({drive:'a'}),true);assert.equal(b2.driving(),false);assert.equal(b2.step(),null);
assert.equal(t.heard({drive:'b'}),false); // a driving view ignores a worse driver's pose
assert.equal(b2.heard({drive:'0-better-than-a'}),true);assert.equal(b2.heard({drive:'a'}),false); // follows only the best
clock+=999;assert.equal(b2.step(),null);clock+=2;assert.equal(b2.step(),0);
let shown=true;const v=spin('v',{visible:()=>shown});v.configure({degreesPerSecond:-6});
assert.ok(v.step()===0);clock+=16;assert.ok(v.step()<0);shown=false;assert.equal(v.step(),null);shown=true;assert.ok(v.step()===0);
v.linked(true);assert.equal(v.step(),null);clock+=500;assert.ok(v.step()===0); // joining waits for the alignment pose
for(const bad of [null,{degreesPerSecond:0},{degreesPerSecond:NaN},{degreesPerSecond:6,resumeAfterSeconds:-1},{degreesPerSecond:1e4}]){
 const x=spin('x');x.configure(bad);assert.equal(x.step(),null);assert.equal(x.heard({drive:'a'}),true);x.nudge();assert.equal(x.status().enabled,false);
}

// Over the real link: two views ask to turn and one plain view follows; exactly one drives.
const turnPose=(p,a)=>{const [x,y,z]=p.eye.map((n,i)=>n-p.target[i]),c=Math.cos(-a),s=Math.sin(-a);
 return {...p,eye:[x*c+z*s+p.target[0],y+p.target[1],-x*s+z*c+p.target[2]]};};
function view(key,rate){
 let pose=structuredClone(base),on=false,posts=0;const tt=spin(key);if(rate)tt.configure({degreesPerSecond:rate});
 const c=createCameraLink({id:key,channel:n=>{const b=new Bus(n),send=b.postMessage.bind(b);b.postMessage=d=>{if(d.type==='pose'&&!d.to)posts++;send(d)};return b},
  read:()=>pose,write:p=>{pose=p;c.localChanged()},tag:()=>tt.driving()?{drive:tt.key()}:null,heard:m=>tt.heard(m),
  changed:s=>{if(s.enabled!==on){on=s.enabled;tt.linked(on)}}});
 c.configure({scope:'turning',units:'m'});
 return {c,tt,frame(){const a=tt.step();if(a!==null){pose=turnPose(pose,a);c.localChanged()}},
  get pose(){return pose},get posts(){return posts},move:p=>{pose=p;c.localChanged()}};
}
const A=view('scope a',6),B=view('scope b',12),F=view('scope f',0),views=[A,B,F];
async function run(ms){for(let i=0;i<ms/16;i++){clock+=16;views.forEach(x=>x.frame());await tick()}}
A.c.setEnabled(true);await tick();B.c.setEnabled(true);F.c.setEnabled(true);await tick();
await run(400);assert.ok(views.every(x=>!x.tt.driving()),'nobody turns before the join has settled');
await run(1000);
assert.ok(A.tt.driving());assert.ok(!B.tt.driving());assert.ok(!F.tt.driving());
close(B.pose,A.pose);close(F.pose,A.pose);assert.notDeepEqual(A.pose.eye,base.eye);
const yaw=p=>Math.atan2(p.eye[0]-p.target[0],p.eye[2]-p.target[2]);
let y0=yaw(A.pose);await run(992);const turned=(y0-yaw(A.pose))/deg;assert.ok(Math.abs(turned-6)<.5,`the driver's 6 deg/s, got ${turned}`);
// A person moving the plain view pauses both turntables; they resume after the pause, with the same driver.
F.c.dragging(true);F.c.busy();await tick();assert.ok(!A.tt.driving());
const aPosts=A.posts;F.move(turnPose(F.pose,.3));await run(800);assert.equal(A.posts,aPosts,'a paused driver sends nothing');
close(A.pose,F.pose);close(B.pose,F.pose);F.c.dragging(false);
await run(1100);assert.ok(!A.tt.driving()&&!B.tt.driving(),"still within the pause");await run(1000);
assert.ok(A.tt.driving());assert.ok(!B.tt.driving());close(B.pose,A.pose);close(F.pose,A.pose);
// The driver closes: the other view that asked takes over after a second of silence, at its own rate.
A.c.dispose();await run(1100);assert.ok(B.tt.driving());assert.ok(!F.tt.driving());close(F.pose,B.pose);
y0=yaw(B.pose);await run(992);assert.ok(Math.abs((y0-yaw(B.pose))/deg-12)<.8);
for(const x of [B,F])x.c.dispose();assert.ok(buses.every(b=>b.closed));
console.log('PASS: link groups; turntable rate, clamp, hold, nudge, resume, election, takeover, visibility, settle and validation');

await import("./test_three_camera_follow.mjs");
