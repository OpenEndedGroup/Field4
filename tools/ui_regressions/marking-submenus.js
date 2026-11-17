window.__submenuCounts={leaf:0,cancel:0,followupCancel:0};
window.__openSubmenuProbe=()=>goMarkingMenu(600,450,[{
    text:'More',angle:90,children:[{text:'Deeper',angle:90,children:[{
        text:'Leaf',angle:90,callback:()=>{
            window.__submenuCounts.leaf++;
            goMarkingMenu(600,450,[{text:'Follow-up',angle:90,callback:()=>{}}],null,()=>window.__submenuCounts.followupCancel++);
        }
    }]}]
}],null,()=>window.__submenuCounts.cancel++);
return {ok:true};
