const delay=ms=>new Promise(r=>setTimeout(r,ms));
let frame;
for(let i=0;i<100;i++){
 frame=[...document.querySelectorAll('iframe')].find(f=>{try{return new URL(f.src).searchParams.get('uid')?.endsWith('_features')&&f.contentWindow.FieldInspector?.snapshot().artifactDigest;}catch(_){return false;}});
 if(frame)break;await delay(100);
}
if(!frame)return {ok:false,reason:'Feature viewer missing'};
const api=frame.contentWindow.FieldInspector, checks=[];
const expect=(v,label)=>{checks.push({label,passed:!!v});if(!v)throw new Error(label);};
try {
 const sources=api.sources();
 const mesh=sources.find(s=>s.source.kind==='drawable'),line=sources.find(s=>s.source.role==='lines'),point=sources.find(s=>s.source.role==='points');
 expect(mesh&&line&&point,'Mesh, FLine stroke and points available without inspector opt-in');
 let p=api.project(mesh.id,[-40,0,0]);
 expect(api.pick(p.x,p.y,'edge')===null,'Ordinary mesh triangulation diagonal is not a feature');
 let selected=api.pick(p.x,p.y,'face');
 expect(selected?.kind==='face'&&selected.partId===mesh.id,'Ordinary mesh face selectable');
 const pointPick=api.pick(p.x,p.y,'point');
 expect(pointPick?.kind==='point'&&Math.abs(pointPick.point[2])<1e-6,'Surface point belongs to the front face, never the hidden rear quad');
 const region=api.region({cx:p.x,cy:p.y,width:.2,height:.25,angle:0});
 expect(region?.kind==='region'&&region.region.pixelCount>100&&region.region.surfaces.every(s=>s.partId===mesh.id),'Depth-buffer rectangle selects only the closest visible surface');
 const rotated=api.region({...region.region,angle:35});
 expect(rotated.region.angle===35&&rotated.region.surfaces.every(s=>s.partId===mesh.id),'Oriented rectangle also excludes fully occluded surfaces');
 const savedBeforeClip=api.snapshot();
 api.restore({...savedBeforeClip,selection:null,selectedPart:null,selectedTriangle:null,sectionEnabled:true,sectionNormal:[0,0,1],sectionFraction:.5,sectionFlip:true});
 const cutRegion=api.region({...region.region,angle:0});
 const rear=sources.find(s=>s.source.id==='hidden-quad');
 expect(cutRegion.region.surfaces.every(s=>s.partId===rear.id),'Section clipping reveals the next visible surface, not the discarded front face');
 api.restore(savedBeforeClip);
 const regionNote=api.observe('Only this visible patch');api.review(regionNote);
 expect(api.snapshot().selection.region.angle===35,'Oriented rectangle and visible triangles round-trip');
 api.restore({...api.snapshot(),camera:[...api.snapshot().camera].map((v,i)=>i===0?v+10:v)});
 expect(JSON.stringify(api.observe('After orbit').view.camera)===JSON.stringify(regionNote.view.camera),'Region notes retain the captured camera after orbiting');
 api.review(regionNote);
 const doc=frame.contentDocument,win=frame.contentWindow,style=id=>win.getComputedStyle(doc.getElementById(id));
 expect(style('hud').userSelect==='none'&&style('feature-tools').userSelect==='none','Toolbar labels cannot be selected');
 expect(style('feature-note').userSelect==='text'&&style('feature-reference').userSelect==='text'&&style('feature-message').userSelect==='text','Notes, references and diagnostic text remain selectable');
 expect(style('feature-review').borderTopWidth==='0px'&&style('feature-review').backgroundColor==='rgb(0, 0, 0)','Annotation panel uses borderless black Field chrome');
 expect(style('feature-save').backgroundColor==='rgb(98, 215, 255)'&&style('feature-note').backgroundColor==='rgb(36, 36, 36)','Primary action is cyan; note editor is grey');
 const diagnostic=doc.getElementById('feature-message');
 const badFile=new win.File([JSON.stringify({...regionNote,artifactDigest:'stale'})],'stale.json',{type:'application/json'});
 const transfer=new win.DataTransfer();transfer.items.add(badFile);doc.getElementById('feature-file').files=transfer.files;
 doc.getElementById('feature-file').dispatchEvent(new win.Event('change'));await delay(100);
 expect(diagnostic.dataset.error==='true'&&style('feature-message').color==='rgb(255, 112, 112)','Failed note restoration is shown in red');
 const range=doc.createRange();range.selectNodeContents(diagnostic);win.getSelection().removeAllRanges();win.getSelection().addRange(range);
 expect(win.getSelection().toString().includes('different view or geometry'),'Error message can be selected for copying');win.getSelection().removeAllRanges();
 api.review(regionNote);
 const beforeCapture=api.snapshot();
 api.restore({...beforeCapture,camera:beforeCapture.camera.map((v,i)=>i===0?v+10:v)});
 const moved=api.snapshot(),evidence=api.capture(regionNote);
 expect(api.snapshot().camera.every((v,i)=>Math.abs(v-moved.camera[i])<1e-9),'Capturing saved evidence restores the current camera');
 expect(JSON.stringify(evidence.view)===JSON.stringify(regionNote.view),'Images carry the exact saved observation state');
 for(const kind of ['context','crop']) {
  const im=new win.Image();im.src=evidence[kind].dataUrl;await im.decode();
  expect(im.width===evidence[kind].width&&im.height===evidence[kind].height&&im.width>10&&im.height>10,`${kind} PNG has meaningful dimensions`);
  const c=doc.createElement('canvas');c.width=im.width;c.height=im.height;const cx=c.getContext('2d');cx.drawImage(im,0,0);
  const bytes=cx.getImageData(0,0,c.width,c.height).data,colors=new Set();for(let i=0;i<bytes.length;i+=4)colors.add(`${bytes[i]},${bytes[i+1]},${bytes[i+2]}`);
  expect(colors.size>3,`${kind} PNG contains rendered geometry and selection, not a blank canvas`);
 }
 expect(evidence.crop.width<evidence.context.width,'Detail crop is tighter than full context');
 api.review(regionNote);

 const originalWidth=frame.style.width;
 frame.style.width='620px';await delay(150);api.review(regionNote);
 const canvas=frame.contentDocument.querySelector('canvas').getBoundingClientRect();
 const overlay=frame.contentDocument.getElementById('feature-region-box');
 const projectedCenter=api.project(mesh.id,[-40,0,0]);
 expect(Math.abs(parseFloat(overlay.style.left)-canvas.left-projectedCenter.x*canvas.width)<2,'Restored rectangle stays over the same surface in a narrower viewer');
 expect(api.snapshot().cameraFov===regionNote.view.cameraFov,'Saved camera projection restores');
 frame.style.width=originalWidth;await delay(150);api.review(regionNote);
 p=api.project(line.id,[45,0,0]);selected=api.pick(p.x,p.y,'edge');
 expect(selected?.kind==='edge'&&selected.partId===line.id,'Ordinary FLine stroke selectable');
 p=api.project(point.id,[60,-40,0]);selected=api.pick(p.x,p.y,'vertex');
 expect(selected?.kind==='vertex'&&selected.partId===point.id,'Ordinary point selectable');
 const note=api.observe('A point in the source drawing');
 const pointEvidence=api.capture(note);
 expect(pointEvidence.crop.width>10&&pointEvidence.crop.width<pointEvidence.context.width,'Point annotations also receive a bounded detail crop');
 expect(note.coordinatePrecision==='display-float'&&note.sourceBox,'Rendered precision and source box are explicit');
 api.review(note);
 expect(JSON.stringify(api.snapshot().selection)===JSON.stringify(selected),'Ordinary geometry note restores');
 // Exercise explicit display densities and off-centre surfaces in standalone
 // viewers, rather than relying on the embedded capture's current settings.
 const standalone=document.createElement('iframe');
 standalone.style='position:fixed;left:0;top:0;width:800px;height:600px;border:0;z-index:999999';
 document.body.appendChild(standalone);
 try {
  for(const dpr of [1,2,3]) {
   const url=new URL(frame.src);url.searchParams.set('dpr',String(dpr));
   standalone.src=url.href;
   await new Promise((resolve,reject)=>{standalone.onload=resolve;standalone.onerror=reject;});
   for(let i=0;i<100&&!standalone.contentWindow.FieldInspector?.snapshot().partIds.length;i++)await delay(100);
   const high=standalone.contentWindow.FieldInspector;
   const highMesh=high.sources().find(s=>s.source.id==='quad');
   const highCanvas=standalone.contentDocument.querySelector('canvas');
   expect(Math.abs(highCanvas.width/highCanvas.getBoundingClientRect().width-dpr)<.01,`Standalone viewer renders at DPR ${dpr}`);
   for(const point of [[-40,0,0],[-50,20,0]])for(const angle of [0,35]) {
    const at=high.project(highMesh.id,point);
    const selection=high.region({cx:at.x,cy:at.y,width:.08,height:.08,angle});
    expect(selection?.kind==='region'&&selection.region.pixelCount>0&&selection.region.surfaces.every(s=>s.partId===highMesh.id),`DPR ${dpr}, angle ${angle}: visible rectangle excludes the rear surface`);
    const anchor=high.project(selection.partId,selection.point);
    const dx=(anchor.x-at.x)*800,dy=(anchor.y-at.y)*600,a=angle*Math.PI/180;
    expect(Math.abs(dx*Math.cos(a)+dy*Math.sin(a))<=32&&Math.abs(-dx*Math.sin(a)+dy*Math.cos(a))<=24,`DPR ${dpr}, angle ${angle}: surface anchor lies inside the drawn rectangle`);
    const hit=high.pick(anchor.x,anchor.y,'point');
    expect(hit?.partId===selection.partId&&hit.point.every((v,i)=>Math.abs(v-selection.point[i])<1e-5),`DPR ${dpr}, angle ${angle}: rectangle and point pick agree`);
   }
  }
 } finally { standalone.remove(); }
 // Count actual GPU submissions after the viewer has settled.
 await delay(1200);
 const gl=frame.contentDocument.querySelector('canvas').getContext('webgl2'),originals=new Map();let draws=0;
 try {
  for(const key of ['drawElements','drawArrays','drawElementsInstanced','drawArraysInstanced']){originals.set(key,gl[key]);gl[key]=function(...args){draws++;return originals.get(key).apply(this,args);};}
  await delay(500);expect(draws===0,'Static annotated viewer submits no idle GPU draws');
 } finally {for(const [key,value] of originals)gl[key]=value;}
 return {ok:true,checks};
}catch(e){return {ok:false,reason:String(e),checks};}
