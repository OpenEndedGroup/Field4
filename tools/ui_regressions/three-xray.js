const delay=ms=>new Promise(r=>setTimeout(r,ms)), checks=[];
const expect=(value,label)=>{checks.push({label,passed:!!value});if(!value)throw new Error(label)};
let frame;
for(let i=0;i<100;i++){
 frame=[...document.querySelectorAll('iframe')].find(f=>{try{return new URL(f.src).searchParams.get('uid')?.endsWith('_xray')&&f.contentWindow.FieldInspector?.snapshot().partIds.length;}catch(e){return false}});
 if(frame)break; await delay(100);
}
if(!frame)throw new Error('Missing x-ray fixture');
const w=frame.contentWindow,d=w.document,api=w.FieldInspector;
try {
 const sources=api.sources(),front=sources.find(s=>s.source.id==='front'),rear=sources.find(s=>s.source.id==='rear');
 expect(front&&rear&&typeof api.xray==='function','X-ray is available for ordinary meshes without construction setup');
 const blank=api.snapshot();expect(blank.xray===false,'Normal surfaces are the default');
 const at=api.project(front.id,[-45,28,10]);api.pick(at.x,at.y,'point');
 const original=api.snapshot();
 async function bitmap(record){const e=api.capture(record);const im=new w.Image();im.src=e.context.dataUrl;await im.decode();const c=d.createElement('canvas');c.width=im.width;c.height=im.height;const cx=c.getContext('2d');cx.drawImage(im,0,0);return {data:cx.getImageData(0,0,c.width,c.height).data,width:c.width,height:c.height,e};}
 const normal=await bitmap(api.observe('Normal surfaces'));
 d.getElementById('xray-toggle').click();
 expect(api.snapshot().xray&&d.getElementById('xray-toggle').getAttribute('aria-pressed')==='true','Toolbar enables x-ray');
 const slider=d.getElementById('xray-opacity');slider.value='18';slider.dispatchEvent(new w.Event('input',{bubbles:true}));
 expect(api.snapshot().xrayOpacity===.18&&d.getElementById('xray-readout').textContent==='18%','Opacity slider and saved state agree');
 const ghost=await bitmap(api.observe('X-ray surfaces'));
 const center=api.project(rear.id,[0,10,0]);
 function sample(b,p){const x=Math.round(p.x*b.width),y=Math.round(p.y*b.height),i=(y*b.width+x)*4;return [...b.data.slice(i,i+3)];}
 const solidColor=sample(normal,center),ghostColor=sample(ghost,center);
 expect(ghostColor[0]-ghostColor[1]>solidColor[0]-solidColor[1]+15,'Rendered x-ray reveals the red mesh behind an opaque grey surface');
 const edgePoint=api.project(front.id,[0,50,12]);
 expect(edgePoint.x>0&&edgePoint.x<1&&edgePoint.y>0&&edgePoint.y<1,'Translucent edge is inside the captured viewport');
 function brightness(b,p){const x=Math.round(p.x*b.width),y=Math.round(p.y*b.height);let best=0;for(let dy=-2;dy<=2;dy++)for(let dx=-2;dx<=2;dx++){const i=((y+dy)*b.width+x+dx)*4;best=Math.max(best,(b.data[i]+b.data[i+1]+b.data[i+2])*b.data[i+3]/255);}return best;}
 const edgeBefore=brightness(normal,edgePoint),edgeAfter=brightness(ghost,edgePoint);
 expect(edgeAfter>edgeBefore+40,'X-ray strengthens translucent edges without changing the source stroke: '+JSON.stringify({edgeBefore,edgeAfter}));
 function cyan(b,p){const x=Math.round(p.x*b.width),y=Math.round(p.y*b.height);let best=-255;for(let dy=-2;dy<=2;dy++)for(let dx=-2;dx<=2;dx++){const i=((y+dy)*b.width+x+dx)*4;best=Math.max(best,Math.min(b.data[i+1],b.data[i+2])-b.data[i]);}return best;}
 for(const pos of [[0,-20,5],[45,20,5]]){
  const p=api.project(front.id,pos),before=cyan(normal,p),after=cyan(ghost,p);
  expect(after>before+30,'Hidden cyan stroke/point becomes visible through faded surfaces: '+JSON.stringify({pos,before,after}));
 }
 const p=api.project(front.id,[0,10,10]);
 expect(api.pick(p.x,p.y,'point')?.partId===front.id,'X-ray point selection still picks the front surface');
 expect(api.pick(p.x,p.y,'face')?.partId===front.id,'X-ray face selection still picks the front surface');
 const region=api.region({cx:p.x,cy:p.y,width:.12,height:.12,angle:25});
 expect(region.region.surfaces.every(s=>s.partId===front.id),'X-ray rotated rectangle excludes the occluded rear surface');
 const rearCorner=api.project(rear.id,[30,40,0]);
 const vertex=api.pick(rearCorner.x,rearCorner.y,'vertex');
 expect(vertex===null||vertex.partId!==rear.id,'X-ray does not make an occluded rear vertex selectable');
 api.restore({...original,xray:true,xrayOpacity:.18});
 const note=api.observe('See behind the front surface'),saved=api.snapshot();api.xray(false);
 const captured=await bitmap(note);
 expect(captured.e.view.xray===true&&captured.e.view.xrayOpacity===.18&&!api.snapshot().xray,'Saved evidence uses its x-ray state and restores the current view');
 expect(captured.data.every((v,i)=>v===ghost.data[i]),'Saved x-ray image reproduces the original rendered pixels');
 api.review(note);expect(api.snapshot().xray&&api.snapshot().xrayOpacity===.18,'Loading the note restores x-ray');
 const legacy={...original};delete legacy.xray;delete legacy.xrayOpacity;api.restore(legacy);
 expect(!api.snapshot().xray,'Notes made before x-ray restore normal surfaces');
 const restored=await bitmap(api.observe('Restored surfaces'));
 expect(restored.data.every((v,i)=>v===normal.data[i]),'Turning x-ray off restores the authored rendering exactly');
 const prior=api.snapshot();let rejected=false;try{api.restore({...prior,xrayOpacity:NaN})}catch(e){rejected=true}
 expect(rejected&&JSON.stringify(api.snapshot())===JSON.stringify(prior),'Invalid opacity fails before changing the view');
 api.restore({...blank,xray:true});api.section([0,0,1],.5);
 api.restore({...api.snapshot(),sectionFlip:true});
 const cut=api.region({cx:p.x,cy:p.y,width:.12,height:.12,angle:0});
 expect(cut.region.surfaces.every(s=>s.partId===rear.id),'A section explicitly exposes the rear surface for selection');
 api.restore({...blank,xray:true});api.hide(front.id);
 expect(api.pick(p.x,p.y,'point')?.partId===rear.id,'Explicitly hiding the front mesh allows selecting the rear');
 api.restore({...blank,xray:true});
 await delay(1200);const before=w.FieldViewer.renderStats().frames;await delay(600);
 expect(w.FieldViewer.renderStats().frames===before,'X-ray submits no idle frames');
 d.getElementById('xray-toggle').click();await delay(100);
 expect(w.FieldViewer.renderStats().frames>before,'X-ray toggle redraws');
 api.xray(true,.18);
 return {ok:true,checks,solidColor,ghostColor,state:api.snapshot()};
}catch(e){return {ok:false,reason:String(e),checks};}
