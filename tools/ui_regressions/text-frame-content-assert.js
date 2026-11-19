for(let i=0;i<160&&window.__frameContent.menu.name!=='Archived';i++)await new Promise(r=>setTimeout(r,50));
if(window.__frameContent.menu.name!=='Archived')throw Error('Nested backend callback did not update its owning box: '+window.__frameContent.menu.name);
const group=window.__frameContent.menu.querySelector('.outputArea .disclosure-group');
const api=window.disclosureInterface(group);
const cw=[...group.querySelectorAll('.disclosure-content')].find(e=>e.querySelector('.field-text-input'));
if(!cw)throw Error('Missing real controls output');
window.__menuWidget={group,api,cw,parent:cw.parentNode,size:api.getSize('controls'),input:cw.querySelector('.field-text-input')};
return {ok:true,nestedBackendCallback:true};
