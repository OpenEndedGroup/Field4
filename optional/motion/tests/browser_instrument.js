(async()=>{
 const delay=ms=>new Promise(r=>setTimeout(r,ms));
 function docs(){const a=[document];for(let i=0;i<a.length;i++)for(const f of a[i].querySelectorAll('iframe')){try{if(f.contentDocument&&!a.includes(f.contentDocument))a.push(f.contentDocument)}catch(_){}}return a;}
 function control(label){for(const d of docs())for(const l of d.querySelectorAll('.field-label'))if(l.textContent.trim()===label)return l.closest('.field-control');throw Error('Missing control '+label);}
 function press(label){const b=control(label).querySelector('button'),w=b.ownerDocument.defaultView;for(const type of ['pointerdown','pointerup'])b.dispatchEvent(new w.PointerEvent(type,{bubbles:true,button:0,isPrimary:true}));}
 function check(label,value){const input=control(label).querySelector('input');if(input.checked!==value){input.checked=value;input.dispatchEvent(new input.ownerDocument.defaultView.Event('change',{bubbles:true}));}}
 function text(){return docs().map(d=>d.body?.innerText??'').join('\n');}
 check('Advance simulation',true);press('Resist — strong tether');
 let deadline=performance.now()+15000;
 while(!text().includes('cm stretch')&&performance.now()<deadline)await delay(50);
 if(!text().includes('cm stretch'))throw Error('No actual tether telemetry');
 await delay(700);
 const active=text();
 const knobs=['Stiffness N/m','Damping N s/m','Force cap N'].map(label=>({label,value:control(label).querySelector('input[type=range]')?.value}));
 // ControlValueHandle must show preset changes to the real controls as well as the backend.
 press('Release');deadline=performance.now()+15000;
 while(!text().includes('released — attach again')&&performance.now()<deadline)await delay(50);
 if(!text().includes('released — attach again'))throw Error('Release not reflected by physics telemetry');
 check('Advance simulation',false);
 return {ok:true,knobs,activeStatus:active.match(/L_Ankle:[^\n]+/)?.[0],releaseObserved:true,paused:true};
})()
