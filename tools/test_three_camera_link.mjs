import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const source=await readFile(new URL('../src/commonMain/resources/threejs_camera_link.js',import.meta.url),'utf8');
const {createCameraLink,cameraLinkFrame,cameraToModel,cameraFromModel}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
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
