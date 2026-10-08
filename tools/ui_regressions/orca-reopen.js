const delay=ms=>new Promise(r=>setTimeout(r,ms)), checks=[];
const expect=(v,label)=>{checks.push({label,passed:!!v});if(!v)throw new Error(label)};
const read=()=>{const el=document.querySelector('#orca-tools');try{return el?JSON.parse(el.textContent):null}catch(e){return null}};
let frame=null, tools=null;
for(let i=0;i<200;i++){
 frame=[...document.querySelectorAll('iframe')].find(f=>{try{return f.contentWindow.fieldOrca?.connected}catch(e){return false}});
 tools=read();
 if(frame&&tools)break;
 await delay(100);
}
expect(frame,'The re-run box shows a connected ORCA');
expect(tools,'The box ran its tool calls');
const grid=frame.contentWindow.fieldOrca.grid();
expect(grid.split('\n')[9]?.startsWith('#heard#'),'Run without a grid, the box reopens the grid saved by the last edit');
expect(grid.split('\n')[0].slice(20).startsWith('#ai#'),'orca_write put its glyphs on the live grid');
expect(tools.written,'orca_write returns the grid with its write in it');
expect(tools.read,'orca_read returns the current grid');
expect(tools.names==='orca_read,orca_write,orca_load,orca_command',`exposeTools registers four tools (got ${tools.names})`);
expect(tools.played>0,'Notes play through PatSynthBank.play without error');
expect(grid.split('\n')[1][22]==='x',"o[22, 1] = 'x' sets that cell on the live grid");
expect(tools.immediate==='x'&&tools.reported==='x','o[22, 1] reads the set value at once, and again once ORCA reports it');
expect(frame.contentWindow.fieldOrca.client.orca.variables.z==='7',"o.variable('z', '7') holds ORCA variable z");
expect(tools.changed>0,'onChange sees the cells the operators change');
return {ok:true, checks, tools};
