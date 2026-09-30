const delay=ms=>new Promise(r=>setTimeout(r,ms)), checks=[];
const expect=(v,label)=>{checks.push({label,passed:!!v});if(!v)throw new Error(label)};
let frame;
for(let i=0;i<100;i++){
 frame=[...document.querySelectorAll('iframe')].find(f=>{try{return f.contentWindow.FieldViewer?.tools?.ids().includes('axes-marker')&&f.contentWindow.FieldInspector?.sources().length;}catch(e){return false}});
 if(frame)break;await delay(100);
}
if(!frame)throw new Error('Missing tool fixture');
const w=frame.contentWindow,d=w.document,tools=w.FieldViewer.tools,api=w.FieldInspector;
const original=api.snapshot();
try {
 expect(tools.ids().includes('axes-marker'),'Field-authored module loads its tool');
 const button=d.querySelector('[data-tool="axes-marker"] button');
 expect(w.getComputedStyle(button).fontSize==='12px'&&button.getBoundingClientRect().height===28,'Extension tools inherit standard toolbar type and spacing');
 button.click();
 const saved=api.snapshot();expect(saved.tools['axes-marker'].shown===true,'Tool state is saved with the view');
 button.click();api.restore(saved);expect(button.getAttribute('aria-pressed')==='true','Restore applies tool state');
 const legacy={...original};delete legacy.tools;api.restore(legacy);
 expect(button.getAttribute('aria-pressed')==='false','An older view resets tools to their initial state');
 const before=JSON.stringify(api.snapshot());let rejected=false;
 try{api.restore({...original,tools:{missing:{value:1}}})}catch(e){rejected=true}
 expect(rejected&&JSON.stringify(api.snapshot())===before,'A missing tool fails before changing the view');
 rejected=false;try{await tools.load('https://example.invalid/tool.js')}catch(e){rejected=true}
 expect(rejected,'Tool loading rejects other origins');
 rejected=false;try{tools.register({id:'incomplete',snapshot:()=>0})}catch(e){rejected=true}
 expect(rejected&&!tools.ids().includes('incomplete'),'Incomplete state contracts are rejected');
 let mounts=0,refreshes=0,disposals=0;
 const dispose=tools.register({id:'lifecycle',mount(ctx){mounts++;ctx.element.textContent='Tool lifecycle';},refresh(){refreshes++;},dispose(){disposals++;}});
 tools.refresh();expect(mounts===1&&refreshes>=2,'Tools mount once and refresh explicitly');
 dispose();dispose();expect(disposals===1&&!d.querySelector('[data-tool="lifecycle"]'),'Disposal is idempotent and removes toolbar content');
 await delay(1200);const frames=w.FieldViewer.renderStats().frames;await delay(600);
 expect(w.FieldViewer.renderStats().frames===frames,'Registered tools do not add idle frames');
 await tools.sync([]);expect(!tools.ids().includes('axes-marker'),'Removing modules disposes their tools');
 await tools.sync(['/threejs_tools_demo.js']);expect(tools.ids().includes('axes-marker'),'A module can be reinstalled');
 return {ok:true,checks};
} catch(e){return {ok:false,reason:String(e),checks};}
finally{api.restore(original);}
