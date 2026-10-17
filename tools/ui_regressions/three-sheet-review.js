// Pick / Save note on a contact sheet: each pick resolves the panel under it, records the panel with
// the feature, restores it, and the saved images are of that panel.
const delay=ms=>new Promise(r=>setTimeout(r,ms));
let frame;
for(let i=0;i<100;i++){
 frame=[...document.querySelectorAll('iframe')].find(f=>{try{return new URL(f.src).searchParams.get('uid')?.endsWith('_sheet_review')&&f.contentWindow.FieldInspector?.snapshot().artifactDigest;}catch(_){return false;}});
 if(frame)break;await delay(100);
}
if(!frame)return {ok:false,reason:'Contact sheet viewer missing'};
await delay(500);
const api=frame.contentWindow.FieldInspector, checks=[];
const expect=(v,label)=>{checks.push({label,passed:!!v});if(!v)throw new Error(label);};
try {
 const sources=api.sources();
 const left=sources.find(s=>s.source.id==='left-quad'),right=sources.find(s=>s.source.id==='right-quad');
 expect(left&&right,'Both panels\' quads are features');
 const pl=api.project(left.id,[0,0,0]),pr=api.project(right.id,[0,0,0]);
 expect(pl.x<.5&&pr.x>.5,'Each quad projects into its own panel');
 const a=api.pick(pl.x,pl.y,'face');
 expect(a?.partId===left.id&&a.panel===0,'A pick in the left panel finds the left quad and names panel 0');
 const b=api.pick(pr.x,pr.y,'point');
 expect(b?.partId===right.id&&b.panel===1&&Math.abs(b.point[0])<1,'A pick in the right panel finds the right quad, at the point under it, and names panel 1');
 const note=api.observe('Right panel, centre');
 expect(note.selection.panel===1&&note.view.selection.panel===1,'The note records its panel');
 api.select(null);
 api.review(note);
 expect(api.snapshot().selection?.panel===1&&api.snapshot().selection.partId===right.id,'Reviewing the note restores the pick in its panel');
 const evidence=api.capture(note);
 const canvas=frame.contentDocument.querySelector('canvas').getBoundingClientRect();
 expect(evidence.panel===1&&evidence.viewport[0]<canvas.width*.6,'The note\'s images are of its panel, not the whole sheet');
 const doc=frame.contentDocument,win=frame.contentWindow;
 for(const kind of ['context','crop']) {
  const im=new win.Image();im.src=evidence[kind].dataUrl;await im.decode();
  const c=doc.createElement('canvas');c.width=im.width;c.height=im.height;const cx=c.getContext('2d');cx.drawImage(im,0,0);
  // The quad fills most of its panel, so a real image is mostly opaque; a blank read-back is not.
  const bytes=cx.getImageData(0,0,c.width,c.height).data;let opaque=0;for(let i=3;i<bytes.length;i+=4)if(bytes[i]>200)opaque++;
  expect(opaque>bytes.length/8,`${kind} image shows the panel's geometry, not a blank canvas`);
 }
 let refused=false;try{api.select({...b,panel:0});}catch(_){refused=true;}
 expect(refused,'A feature cannot claim a panel its part is not drawn in');
 const mode=doc.getElementById('feature-mode');
 expect(!mode.disabled&&mode.querySelector('option[value="region"]').disabled,'Pick is offered on a sheet; Rectangle still needs a single viewport');
 return {ok:true,checks};
}catch(e){return {ok:false,reason:String(e),checks};}
