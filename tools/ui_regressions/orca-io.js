const delay=ms=>new Promise(r=>setTimeout(r,ms)), checks=[];
const expect=(v,label)=>{checks.push({label,passed:!!v});if(!v)throw new Error(label)};
const read=()=>{const el=document.querySelector('#orca-heard');try{return el?JSON.parse(el.textContent):null}catch(e){return null}};
let frame=null, heard=null;
for(let i=0;i<200;i++){
 frame=frame||[...document.querySelectorAll('iframe')].find(f=>{try{return f.contentWindow.fieldOrca}catch(e){return false}});
 heard=read();
 if(frame&&heard&&heard.notes>=3&&heard.udp&&heard.osc&&heard.cc>0&&heard.gridMarked&&heard.saved)break;
 await delay(100);
}
expect(frame,'ORCA loads in the box output and installs window.fieldOrca');
const orca=frame.contentWindow.fieldOrca;
expect(orca.connected,'The page holds a live binding to its box');
expect(heard,'The box displays what it heard from ORCA');
expect(heard.playing,'ORCA reports that it is playing');
expect(heard.notes>=3,`':' notes reach onNote (got ${heard.notes})`);
expect(heard.midi===60,`':03C' is MIDI note 60 (got ${heard.midi})`);
expect(heard.udp==='hello',`';hello' reaches onUdp (got '${heard.udp}')`);
expect(heard.osc==='/a [1, 2]',`'=a12' reaches onOsc as /a [1, 2] (got '${heard.osc}')`);
expect(heard.cc>0,"'!012' reaches onCC");
expect(orca.grid().split('\n')[9].startsWith('#heard#'),'A Kotlin write lands on the grid at (0, 9)');
expect(heard.gridMarked,"Kotlin's grid reflects its own write once ORCA reports back");
expect(heard.saved,'The edited grid is saved in the box property orcaGrids');
const rect=frame.getBoundingClientRect();
expect(rect.width>=200&&rect.height>=150,`The ORCA iframe is visible (${Math.round(rect.width)}x${Math.round(rect.height)})`);
return {ok:true, checks, heard, size:[rect.width,rect.height]};
