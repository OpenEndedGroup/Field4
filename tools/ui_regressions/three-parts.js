const delay=ms=>new Promise(r=>setTimeout(r,ms)),checks=[];
const expect=(value,label)=>{checks.push({label,passed:!!value});if(!value)throw new Error(label)};
let frame,ordinary;
for(let i=0;i<100;i++) {
 const frames=[...document.querySelectorAll('iframe')];
 frame=frames.find(f=>{try{return new URL(f.src).searchParams.get('uid')?.endsWith('_parts')&&f.contentWindow.FieldInspector?.snapshot().partIds.length;}catch(e){return false}});
 ordinary=frames.find(f=>{try{return new URL(f.src).searchParams.get('uid')?.endsWith('_ordinary')&&f.contentWindow.FieldInspector?.snapshot().partIds.length;}catch(e){return false}});
 if(frame&&ordinary)break;await delay(100);
}
if(!frame||!ordinary)throw new Error('Missing parts fixture');
const w=frame.contentWindow,d=w.document,api=w.FieldInspector;
const cb=name=>[...d.querySelectorAll('#part-inspector input[type=checkbox]')].find(x=>x.getAttribute('aria-label')===name);
const click=(name,shift=false)=>{const input=cb(name);if(!input)throw new Error('Missing checkbox '+name);input.dispatchEvent(new w.MouseEvent('click',{bubbles:true,shiftKey:shift}));};
const open=name=>d.querySelector(`[aria-label="Expand ${name}"]`)?.click();
const hidden=()=>api.snapshot().hidden.slice().sort();
try {
 expect(!ordinary.contentWindow.document.getElementById('part-inspector').classList.contains('hidden'),'Ordinary meshes have Parts without explicit inspect setup');
 const initial=api.snapshot(),ids=initial.partIds;
 const tree=api.parts();
 expect(tree.leaves.length===4&&ids.length===5,'Two companion buffers share one logical part; saved feature IDs remain distinct');
 expect(tree.children.some(n=>n.label==='Vehicle')&&tree.children.some(n=>n.label==='Guides'),'Explicit hierarchy and default batched-guide folder coexist');
 d.querySelector('#part-inspector > details').open=true;open('Vehicle');open('Bodywork');open('Running gear');
 click('body');
 expect(hidden().length===2&&!cb('body').checked,'One part checkbox hides both companion buffers');
 expect(cb('Vehicle').indeterminate&&cb('Select all').indeterminate,'Parent and Select all expose mixed visibility');
 await delay(100);
 expect(api.rendered().filter(x=>x.partId==='body').every(x=>!x.visible),'Companion buffers remain hidden after rendering');
 const prior=hidden();click('wheel',true);
 expect(api.rendered().filter(x=>x.visible).map(x=>x.partId).join(',')==='wheel','Shift-click solos the chosen part');
 expect(hidden().includes('batch:lines'),'Solo excludes unowned guide buffers');
 click('axle',true);click('axle',true);
 expect(JSON.stringify(hidden())===JSON.stringify(prior),'Switching solos and repeating restores the original visibility mask');
 click('Running gear',true);
 expect(api.rendered().filter(x=>x.visible).length===2&&!cb('Running gear').indeterminate,'Folder solo retains all its children');
 click('Running gear',true);expect(JSON.stringify(hidden())===JSON.stringify(prior),'Folder solo is reversible');
 click('Select all');expect(hidden().length===0,'Select all from mixed state shows everything');
 click('Select all');expect(hidden().length===ids.length&&!cb('Select all').indeterminate,'Select all toggles all off');
 click('Select all');click('wheel',true);click('body');click('wheel',true);click('wheel',true);
 expect(api.rendered().filter(x=>x.visible).length===3,'Ordinary click ends solo; subsequent solo restores that edited mask');
 api.restore(initial);api.hide('drawable:0');
 expect(cb('body').indeterminate&&cb('Vehicle').indeterminate,'Agent visibility edits synchronise tri-state controls');
 expect(d.querySelector('[aria-label="Collapse Vehicle"]')&&d.querySelector('#part-inspector > details').open,'API updates preserve expanded folders and menu');
 const saved=api.snapshot();api.restore(initial);api.restore(saved);
 expect(cb('body').indeterminate&&hidden().join(',')==='drawable:0','Saved review visibility still uses original feature IDs');
 api.restore(initial);click('body',true);api.restore(saved);click('body',true);click('body',true);
 expect(hidden().join(',')==='drawable:0','Restoring a review clears obsolete solo history');
 api.restore(initial);
 const controls=['#xray-toggle','#glb-export','#feature-tools select','#feature-inbox','.hud-slider','#part-inspector summary'].map(sel=>d.querySelector(sel)).filter(Boolean);
 const sizes=controls.map(el=>w.getComputedStyle(el).fontSize);
 expect(new Set(sizes).size===1&&sizes[0]==='12px','Toolbar labels, actions, Parts and select share one text size');
 const picker=w.getComputedStyle(d.querySelector('#feature-tools select'));
 expect(picker.paddingRight==='30px'&&picker.backgroundPosition.includes('10px'),'Pick arrow retains space on its right');
 expect(w.getComputedStyle(d.getElementById('hud')).userSelect==='none'&&w.getComputedStyle(d.getElementById('feature-message')).userSelect==='text','Chrome is unselectable; errors remain copyable');
 await delay(1400);const frames=w.FieldViewer.renderStats().frames;await delay(500);
 expect(frames===w.FieldViewer.renderStats().frames,'Parts tree causes no idle draws');
 click('body');await delay(100);expect(w.FieldViewer.renderStats().frames>frames,'Visibility changes redraw the scene');
 api.restore(initial);open('Vehicle');open('Bodywork');open('Running gear');
 const originalStyle=frame.style.cssText;
 frame.style.setProperty("min-width","0","important");
 for(const width of [760,540,360]) {
  frame.style.setProperty('width',width+'px','important');await delay(120);
  expect(w.innerWidth===width,'Fixture actually resized to '+width+'px');
  const groups=[...d.querySelectorAll('#hud-left > .hud-group,#part-inspector')].filter(el=>el.getClientRects().length).map(el=>el.getBoundingClientRect());
  expect(groups.every((a,i)=>a.left>=0&&a.right<=width+.5&&groups.slice(i+1).every(b=>Math.min(a.right,b.right)-Math.max(a.left,b.left)<1 || Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top)<1)),'Toolbar groups fit without overlap at '+width+'px');
 }
 frame.style.cssText=originalStyle;
 return {ok:true,checks,featureIds:ids,logicalParts:tree.leaves.length};
} catch(e) { return {ok:false,reason:String(e),checks}; }
