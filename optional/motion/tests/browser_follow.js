(async()=>{
 const delay=ms=>new Promise(r=>setTimeout(r,ms));
 function docs(){const a=[document];for(let i=0;i<a.length;i++)for(const f of a[i].querySelectorAll('iframe')){try{if(f.contentDocument&&!a.includes(f.contentDocument))a.push(f.contentDocument)}catch(_){}}return a;}
 function viewer(){return docs().find(d=>d.defaultView?.FieldViewer?.followStatus);}
 let d=viewer();if(!d)throw Error('No follow-capable viewer');
 d.defaultView.frameElement?.scrollIntoView({block:'center'});await delay(300);
 const api=d.defaultView.FieldViewer;
 if(!api.followStatus().enabled)throw Error('Follow is disabled');
 const inspector=d.defaultView.FieldInspector;
 const state=inspector.snapshot(), p=api.cameraPose();
 const offset=p.eye.map((v,i)=>v-p.target[i]);
 // A real inspector camera edit rotates and dollies the orbit while follow remains enabled.
 const rotated=[offset[2]*1.2,offset[1]*1.2,-offset[0]*1.2];
 inspector.restore({...state,camera:p.target.map((v,i)=>v+rotated[i]),target:[...p.target]});
 await delay(150);
 const first=api.cameraPose();await delay(800);const last=api.cameraPose();
 const a=first.eye.map((v,i)=>v-first.target[i]), b=last.eye.map((v,i)=>v-last.target[i]);
 if(a.some((v,i)=>Math.abs(v-b[i])>1e-4))throw Error('Follow changed the authored orbit/dolly offset');
 const displacement=Math.hypot(...last.target.map((v,i)=>v-first.target[i]));
 if(displacement<.005)throw Error('Camera target did not follow the moving root');
 return {ok:true,renderedFrames:api.renderStats().frames,follow:api.followStatus(),cameraDisplacement:displacement,
   orbitOffsetBefore:a,orbitOffsetAfter:b,orbitAndDollyPreserved:true};
})()
