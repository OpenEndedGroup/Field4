for(let i=0;i<160&&window.__frameContent.menu.name!=='Archived';i++)await new Promise(r=>setTimeout(r,50));
if(window.__frameContent.menu.name!=='Archived')throw Error('Nested backend callback did not update its owning box: '+window.__frameContent.menu.name);
return {ok:true,nestedBackendCallback:true};
