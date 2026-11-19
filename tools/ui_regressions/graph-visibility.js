const {newBox,sendWithReply}=await import('./afield.mjs');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const wait=async(fn,message)=>{for(let i=0;i<200;i++){if(fn())return;await delay(50);}throw Error(message);};
await wait(()=>window.globalEditor?.getModel(),'Editor not ready');
window.setAutoDisplayEditor(false);
const graph={};
for(const [name,x,y,w=200,h=100] of [['P',20,40],['Q',900,40],['A',500,300],['B',200,550],['C',800,550],['D',500,750],['Outside',1050,350,120,80]]) {
    newBox({kind:'text-frame',name:'Visibility '+name,contents:name,textFrame:'sizing: fixed\nreflow: false\n',frame:{x,y,w,h}});
    const box=[...document.querySelectorAll('.text-frame-box')].find(b=>b.name==='Visibility '+name);
    box.dataset.visibilityFixture=name;graph[name]=box;
    const editor=box.querySelector('.text-frame-editor');
    await wait(()=>document.activeElement===editor,'Fixture did not focus');
    editor.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
}
const rpc=(name,payload)=>new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(Error('Graph RPC timed out')),15000);
    sendWithReply(name,payload,'visibility-'+Math.random(),(_message,result)=>{
        clearTimeout(timer);if(result.success===false)reject(Error(result.error));else resolve(result);return 'remove';
    });
});
const edges=[['P','A'],['Q','A'],['A','B'],['A','C'],['B','D'],['C','D'],['D','A']];
for(const [parent,child] of edges) await rpc('perform-action',{uid_currentSelection:[graph[parent].uid,graph[child].uid],uid_action:'-connect'});
await wait(()=>edges.every(([p,c])=>graph[p].box_children.includes(graph[c].uid)),'Topology did not synchronize');
const topology=()=>JSON.stringify(Object.fromEntries(Object.entries(graph).map(([name,b])=>[name,{children:[...b.box_children].sort()}])));
const cases=[
    {anchor:'A',label:'Hide all children',hidden:['B','C','D']},
    {anchor:'A',label:'Reveal all children',hidden:[]},
    {anchor:'A',label:'Hide this and all children',hidden:['A','B','C','D']},
    {anchor:'P',label:'Reveal all children',hidden:[]},
    {anchor:'A',label:'Hide all parents',hidden:['P','Q','B','C','D']},
    {anchor:'A',label:'Reveal all parents',hidden:[]},
    {anchor:'A',label:'Hide this and all parents',hidden:['P','Q','A','B','C','D']},
    {anchor:'Outside',label:'Reveal All Boxes',hidden:[]}
];
window.__graphVisibility={graph,cases,index:0,topology,originalTopology:topology()};
window.__graphVisibilityCase=()=>window.__graphVisibility.cases[window.__graphVisibility.index];
return {ok:true,cycle:true,diamond:true};
