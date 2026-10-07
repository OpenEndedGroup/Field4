const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms)),checks=[];
const expect=(value,label)=>{checks.push({label,passed:!!value});if(!value)throw new Error(label);};
async function until(read,label) { for(let i=0;i<100;i++){const value=await read();if(value)return value;await delay(100);}throw new Error(label); }
try {
 const frame=await until(()=>[...document.querySelectorAll('iframe')].find(f=>{try{return new URL(f.src).searchParams.get('uid')?.endsWith('_mixed-review')&&f.contentWindow.FieldInspector?.sources().length===2;}catch(_){return false;}}),'Mixed viewer missing');
 const win=frame.contentWindow,doc=frame.contentDocument,api=win.FieldInspector;
 const inbox=async()=>{const response=await fetch('/three-review?'+new URLSearchParams({uid:new URL(frame.src).searchParams.get('uid')}));if(!response.ok)throw new Error('Inbox HTTP '+response.status);return response.json();};
 const panel=api.sources().find(s=>s.source.id==='panel'),target=api.sources().find(s=>s.source.id==='target');
 api.restore({...api.snapshot(),camera:[0,0,300],target:[0,0,0]});
 api.hide(panel.id);
 const at=api.project(target.id,[40,0,0]);
 expect(api.pick(at.x,at.y,'point')?.partId===target.id,'Pick the ordinary surface beside the canonical panel');
 const note=api.observe('This target is about to move');
 expect(note.artifactDigest!=='fixed-panel-boundary','Mixed view includes ordinary geometry in its identity');
 doc.getElementById('feature-note').value=note.text;
 doc.getElementById('feature-save').click();
 const saved=await until(async()=>{const state=await inbox();return state.notes?.length===1&&state;},'Note did not reach Field');
 expect(saved.revision===note.artifactDigest,'Browser and backend use the same mixed revision');
 const before=api.snapshot();
 const button=[...document.querySelectorAll('.field-button')].find(el=>el.textContent.includes('Move review target'))?.querySelector('button');
 expect(button,'Production Field control is available');
 button.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));
 button.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',bubbles:true}));
 await until(()=>api.snapshot().artifactDigest!==before.artifactDigest,'Geometry revision did not change');
 await delay(150);
 const after=api.snapshot(),current=await inbox();
 expect(current.revision===after.artifactDigest,'Updated backend and browser identities agree');
 expect(!after.selection&&!current.selection,'The old live selection clears in both places');
 expect(current.notes.length===1&&current.notes[0].artifactDigest===before.artifactDigest,'Saved note keeps its original revision');
 expect(current.notes[0].evidence?.context&&current.notes[0].evidence?.crop,'Saved note retains its real context and crop images');
 expect(JSON.stringify(after.hidden)===JSON.stringify(before.hidden),'Unchanged panel visibility survives a target move');
 expect(after.camera.every((v,i)=>Math.abs(v-before.camera[i])<1e-8)&&after.target.every((v,i)=>Math.abs(v-before.target[i])<1e-8),'Camera and orbit target survive the update');
 let rejected=false;try{api.review(note);}catch(e){rejected=String(e).includes('different view or geometry');}
 expect(rejected,'Restoring the old note is rejected as stale');
 const moved=api.project(target.id,[40,20,0]);
 expect(api.pick(moved.x,moved.y,'point')?.partId===target.id,'The moved surface is pickable at its new position');
 doc.getElementById('feature-note').value='Updated target';doc.getElementById('feature-save').click();
 await until(async()=>{const state=await inbox();return state.notes?.length===2;},'Updated note did not reach Field');
 api.hide(panel.id,false);
 return {ok:true,checks,oldRevision:before.artifactDigest,newRevision:after.artifactDigest};
} catch(error){return {ok:false,reason:String(error),checks};}
