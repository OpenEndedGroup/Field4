import {readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import assert from 'node:assert/strict';
const source=await readFile(new URL('../src/commonMain/resources/threejs_shot.js',import.meta.url),'utf8');
const {shotLook,explodePositions,xrayOpacity,createShot}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const close=(a,b,eps=1e-9)=>{if(typeof a==='object')Object.keys(b).forEach(k=>close(a[k],b[k],eps));else assert.ok(Math.abs(a-b)<eps,`${a} != ${b}`)};

// The default loop's keys (ThreeShotSpec.MONEY_SHOT).
const keys=[{at:0,distance:.6,explode:0,xray:0},{at:.15,distance:.7,explode:0,xray:0},{at:.4,distance:1,explode:0,xray:1},
  {at:.62,distance:1.4,explode:1,xray:1},{at:.82,distance:1.4,explode:1,xray:1},{at:1,distance:.6,explode:0,xray:0}];
// Exactly the keys at the keys, eased between them, and the loop closes: 1 looks like 0.
for(const k of keys)close(shotLook(keys,k.at===1?0.9999999999:k.at),{distance:k.distance,explode:k.explode,xray:k.xray},1e-6);
close(shotLook(keys,1),shotLook(keys,0));close(shotLook(keys,2.25),shotLook(keys,.25));close(shotLook(keys,-.75),shotLook(keys,.25));
// Smoothstep: halfway between two keys is halfway in value, a quarter of the way is less than a quarter.
const mid=shotLook(keys,(.4+.62)/2);close(mid.explode,.5);close(mid.distance,1.2);
assert.ok(shotLook(keys,.4+.22/4).explode<.25);
// Values stay inside the keys' range everywhere (no overshoot).
for(let p=0;p<1;p+=.001){const l=shotLook(keys,p);assert.ok(l.explode>=-1e-12&&l.explode<=1+1e-12&&l.xray>=-1e-12&&l.xray<=1+1e-12&&l.distance>=.6-1e-12&&l.distance<=1.4+1e-12,`p=${p}`)}
assert.equal(shotLook([],0),null);assert.equal(shotLook(null,0),null);

// Explode: zero is the assembled geometry exactly; full adds the offsets.
const base=new Float32Array([1,2,3,4,5,6]),off=new Float32Array([.5,0,-1,0,2,0]);
assert.deepEqual(Array.from(explodePositions(base,off,0)),Array.from(base));
assert.deepEqual(Array.from(explodePositions(base,off,1)),[1.5,2,2,4,7,6]);
const out=new Float32Array(6);assert.equal(explodePositions(base,off,.5,out),out);close(Array.from(out),[1.25,2,2.5,4,6,6],1e-6);
// X-ray: solid at 0, authored at 1, clamped.
close(xrayOpacity(1,.4,0),1);close(xrayOpacity(1,.4,1),.4);close(xrayOpacity(1,.4,.5),.7);close(xrayOpacity(1,.4,3),.4);

// The clock: the wall clock's phase, frozen while held, carrying on from where it stopped after the pause.
let t=1000*24*10;const woke=[];const shot=createShot({now:()=>t,wake:ms=>woke.push(ms)});
assert.equal(shot.step(),null);assert.equal(shot.active(),false);
shot.configure({periodSeconds:24,keys,resumeAfterSeconds:2});
close(shot.step().phase,0);t+=6000;close(shot.step().phase,.25);
shot.hold(true);t+=10000;close(shot.step().phase,.25);assert.equal(shot.held(),true);
shot.hold(false);assert.ok(woke.at(-1)>=2000);t+=1000;close(shot.step().phase,.25);
t+=1500;close(shot.step().phase,(6000+500)/24000);
// A nudge (press, wheel, key) holds for the pause too.
shot.nudge();t+=1000;const held=shot.step().phase;t+=500;close(shot.step().phase,held);t+=2000;assert.ok(shot.step().phase>held);
assert.equal(shot.status().active,true);assert.equal(shot.status().keys,6);
shot.configure(null);assert.equal(shot.step(),null);assert.equal(shot.status().active,false);
shot.configure({periodSeconds:24,keys:[keys[0]]});assert.equal(shot.active(),false);
// The viewer imports this module into one large module script; a name it already uses (it has a
// `const xrayOpacity`, the slider) breaks every three.js view. Check the script still parses.
const viewer=await readFile(new URL('../src/commonMain/resources/threejs_viewer.html',import.meta.url),'utf8');
const scripts=[...viewer.matchAll(/<script type="module">([\s\S]*?)<\/script>/g)].map(m=>m[1]);
assert.ok(scripts.length>0,'the viewer has a module script');
const dir=await mkdtemp(join(tmpdir(),'three-shot-'));
for(const [i,script] of scripts.entries()){const f=join(dir,`viewer-${i}.mjs`);await writeFile(f,script);execFileSync(process.execPath,['--check',f],{stdio:'pipe'})}
assert.match(viewer,/from '\/threejs_shot\.js'/,'the viewer imports the shot module');
console.log('threejs_shot.js: ok; threejs_viewer.html module script parses');
