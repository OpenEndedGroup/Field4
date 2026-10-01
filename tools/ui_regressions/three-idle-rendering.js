const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let frame;
for(let i=0;i<100;i++) {
  frame=[...document.querySelectorAll('iframe')].find(f=>{try{return new URL(f.src).searchParams.get('uid')?.endsWith('_idle-rendering') && f.contentWindow.FieldInspector?.snapshot().partIds.length}catch(_){return false}});
  if(frame)break; await delay(100);
}
if(!frame) throw new Error('Missing real inspector');
const w=frame.contentWindow, api=w.FieldInspector, stats=()=>w.FieldViewer.renderStats(), checks=[];
const expect=(ok,label)=>{checks.push({label,passed:!!ok});if(!ok)throw new Error(label)};
const original=api.snapshot();
const left=api.sources().find(s=>s.source.id==='left').id;
const right=api.sources().find(s=>s.source.id==='right').id;
async function settles(){
  const deadline=Date.now()+4000;let last=-1, stable=0;
  while(Date.now()<deadline){await delay(100);const s=stats();if(s.frames===last&&!s.pending&&!s.dirty)stable++;else stable=0;if(stable>=3)return;last=s.frames;}
  throw new Error('Viewer never settled: '+JSON.stringify(stats()));
}
await settles();
let before=stats().frames;await delay(800);
expect(stats().frames===before,'Idle viewer draws zero extra frames');
api.hide(left);api.hide(right);api.hide(right,false);
await settles();
expect(stats().frames===before+1,'A burst of visibility changes draws exactly one frame');
expect(!api.rendered().find(p=>p.partId==='left').visible,'Hidden geometry matches rendered state');
api.restore(original);await settles();
before=stats().frames;
const light=w.document.querySelector('[aria-label="Grazing light direction"]');
light.value='115';light.dispatchEvent(new w.Event('input',{bubbles:true}));
await settles();expect(stats().frames>before,'Lighting redraws');
before=stats().frames;api.section([0,1,0],.42);await settles();expect(stats().frames>before,'Section redraws');
before=stats().frames;api.assembly(.5);await settles();expect(stats().frames>before,'Insertion redraws');
const canvas=w.document.querySelector('canvas'),rect=canvas.getBoundingClientRect();
const camera=api.snapshot().camera;
before=stats().frames;
canvas.dispatchEvent(new w.WheelEvent('wheel',{deltaY:120,clientX:rect.x+rect.width/2,clientY:rect.y+rect.height/2,bubbles:true,cancelable:true}));
await settles();
expect(JSON.stringify(api.snapshot().camera)!==JSON.stringify(camera),'Zoom changes the actual camera');
expect(stats().frames>before,'Camera movement redraws and settles');
before=stats().frames;
const oldStyle=frame.getAttribute('style');frame.style.setProperty('width','620px','important');await settles();
expect(stats().frames>before,'Real iframe resize redraws');
if(oldStyle===null)frame.removeAttribute('style');else frame.setAttribute('style',oldStyle);api.restore(original);await settles();
before=stats().frames;await delay(800);expect(stats().frames===before,'View returns to zero rendering after interaction');
// Measure actual callback pacing in the capture browser; not a synthetic timer.
let ticks=0, active=true;const t=performance.now();
function tick(){if(active){ticks++;requestAnimationFrame(tick)}}requestAnimationFrame(tick);
await delay(600);active=false;
return {ok:true,checks,renderStats:stats(),captureRafPerSecond:ticks*1000/(performance.now()-t)};
