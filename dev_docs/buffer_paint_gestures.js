// Real pointer gestures on the drawing layer over the "gpu buffer" frame: a freehand stroke, a
// rectangle, an ellipse, and an arrow from inside the rectangle to empty space.
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
const ptr=(t,type,x,y,b)=>t.dispatchEvent(new PointerEvent(type,{clientX:x,clientY:y,button:0,buttons:b,pointerId:1,pointerType:'mouse',isPrimary:true,bubbles:true,cancelable:true,composed:true,pressure:b?0.5:0}));
window.toggleDrawingLayer();
for(let i=0;i<200&&!window.drawingLayerState().ready;i++)await pause(50);
await pause(300);
const fr=window.drawingLayerScene().find(e=>e.type==='frame'&&(e.name||'')==='gpu buffer'&&!e.isDeleted);
if(!fr) throw Error('no gpu buffer frame: '+JSON.stringify(window.drawingLayerScene().map(e=>[e.type,e.name])));
const s=window.canvasPanesState(), p=s.panes[s.panes.length-1];
// cell (cx, cy) -> screen; two world units per cell
const sc=(cx,cy)=>[(fr.x+cx*fr.width/256)*s.zoom+p.tx,(fr.y+cy*fr.height/256)*s.zoom+p.ty];
const host=[...document.querySelectorAll('.field-drawing-pane')].pop();
const canvas=host.querySelector('canvas.interactive')||host.querySelectorAll('canvas')[1];
async function drag(tool, pts){
  window.drawingLayerSetTool(tool);
  for(let i=0;i<40&&window.drawingLayerState().tool!==tool;i++)await pause(25);
  const q=pts.map(([a,b])=>sc(a,b));
  ptr(canvas,'pointermove',...q[0],0); await frame();
  ptr(canvas,'pointerdown',...q[0],1); await frame();
  for(let k=1;k<q.length;k++){ptr(canvas,'pointermove',...q[k],1);await frame();}
  ptr(canvas,'pointerup',...q[q.length-1],0);
  await pause(1500);
}
const line=(a,b,n)=>Array.from({length:n+1},(_,k)=>[a[0]+(b[0]-a[0])*k/n,a[1]+(b[1]-a[1])*k/n]);
const wave=Array.from({length:30},(_,k)=>[20+k*7, 220+12*Math.sin(k*0.5)]);
await drag('freedraw', wave);
await drag('rectangle', line([40,40],[85,85],8));
await drag('ellipse', line([180,170],[225,215],8));
await drag('arrow', line([62,62],[200,90],8));
window.toggleDrawingLayer();
await pause(500);
return JSON.stringify(window.drawingLayerScene().filter(e=>!e.isDeleted).map(e=>e.type));
