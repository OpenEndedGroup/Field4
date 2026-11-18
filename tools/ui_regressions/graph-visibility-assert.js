const state=window.__graphVisibility,scenario=state.cases[state.index];
for(let i=0;i<200;i++) {
    const correct=Object.entries(state.graph).every(([name,b])=>{
        const hidden=getComputedStyle(b).display==='none';
        return hidden===scenario.hidden.includes(name) && (b.server_visible!==false)===!hidden;
    });
    if(correct) {
        if(state.topology()!==state.originalTopology)throw Error('Visibility changed graph connections');
        if(window.selectedList.some(b=>getComputedStyle(b).display==='none'))throw Error('A hidden box remained selected');
        state.index++;
        return {ok:true,action:scenario.label,hidden:scenario.hidden};
    }
    await new Promise(resolve=>setTimeout(resolve,50));
}
throw Error('Incorrect visibility after '+scenario.label+': '+JSON.stringify(Object.fromEntries(Object.entries(state.graph).map(([name,b])=>[name,{display:getComputedStyle(b).display,visible:b.server_visible}]))));
