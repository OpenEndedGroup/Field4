const delay=ms=>new Promise(r=>setTimeout(r,ms));
const check=(v,message)=>{if(!v)throw new Error(message)};
const wait=async(fn,message)=>{for(let i=0;i<150;i++){if(fn())return;await delay(50)}throw new Error(message)};
const boxes=[...document.querySelectorAll('.box')];
const owner=boxes.find(b=>b.name==='ControlSearchTarget');
const other=boxes.find(b=>b.name==='ControlSearchOther');
check(owner&&other,'fixture boxes');
const controls=box=>[...box.querySelectorAll('.field-control')];
const mist=()=>controls(owner).find(c=>c.dataset.sliderId&&c.closest('.disclosure').__disclosureId==='Far controls');
const panel=mist().closest('.disclosure'), content=mist().closest('.disclosure-content');
const spacer=document.createElement('div');spacer.style.height='1100px';content.prepend(spacer);
content.style.height='200px';content.style.maxHeight='200px';content.style.overflow='auto';
window.disclosureInterface(panel.closest('.disclosure-group')).close(panel.__disclosureId);
const input=()=>document.querySelector('.afield-palette-input');
const rows=()=>[...document.querySelectorAll('.afield-palette-row')];
const controller=()=>fieldPalette();
const query=async text=>{
 if(document.querySelector('.afield-palette')?.style.display==='none'||!document.querySelector('.afield-palette'))window.openAnywherePalette();
 input().value=text;input().dispatchEvent(new Event('input',{bubbles:true}));
 await wait(()=>controller().items.length>0,'results for '+text);
};
const accept=()=>input().dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));
const result={};
try{
 await query('> Mist density');
 result.scoped=controller().items.every(i=>i.provider==='control');
 const matches=controller().items.filter(i=>i.label==='Mist density');
 result.duplicates=matches.length===4;
 const index=controller().items.findIndex(i=>i.controlTarget?.boxId===owner.uid&&i.controlTarget.channel==='Far controls');
 check(index>=0,'closed panel control indexed');controller().setActive(index);
 const preview=document.querySelector('.afield-palette-control-preview');
 check(preview?.querySelector('.field-slider'),'slider preview');
 result.previewSafe=preview.querySelector('.field-control').inert && !preview.querySelector('[id], [data-slider-id], [data-control-id]');
 window.updateFieldSlider(mist(),0.6,{send:false});
 await wait(()=>document.querySelector('.afield-palette-control-value')?.textContent.includes('0.60'),'live preview value');
 result.livePreview=true;
 // The selected result must survive an output rerender without losing the target.
 const old=mist();old.replaceWith(old.cloneNode(true));
 await wait(()=>document.querySelector('.afield-palette-control-value')?.textContent.includes('0.60'),'preview after rerender');
 result.rerender=true;
 accept();
 await wait(()=>window.selectedList?.length===1&&window.selectedList[0]===owner,'owner selected');
 await wait(()=>{
  const r=mist().getBoundingClientRect(),e=document.querySelector('.monaco-editor-container').getBoundingClientRect();
  const x=e.left-30>innerWidth-e.right-30?(16+e.left-14)/2:(e.right+14+innerWidth-16)/2;
  return Math.abs(r.left+r.width/2-x)<3&&Math.abs(r.top+r.height/2-innerHeight/2)<3;
 },'editor-aware frame animation completed');
 const r=mist().getBoundingClientRect(), e=document.querySelector('.monaco-editor-container').getBoundingClientRect();
 result.open=panel.classList.contains('open');
 result.scrolled=content.scrollTop>700;
 result.inViewport=r.left>=0&&r.top>=0&&r.right<=innerWidth&&r.bottom<=innerHeight;
 result.clearOfEditor=r.right<=e.left||r.left>=e.right||r.bottom<=e.top||r.top>=e.bottom;
 const leftWidth=e.left-30,rightWidth=innerWidth-e.right-30;
 const expectedX=leftWidth>rightWidth?(16+e.left-14)/2:(e.right+14+innerWidth-16)/2;
 result.centered=Math.abs(r.left+r.width/2-expectedX)<3&&Math.abs(r.top+r.height/2-innerHeight/2)<3;
 const pane=window.canvasPanesState().panes.find(p=>p.id===owner.closest('[data-pane-id]')?.dataset.paneId)||window.canvasPanesState().panes.at(-1);
 result.geometry={control:{left:r.left,top:r.top,width:r.width,height:r.height},editor:{left:e.left,top:e.top,width:e.width,height:e.height},pane};
 // A real callback must still reach the original widget after preview/navigation.
 window.updateFieldSlider(mist(),0.4,{send:true});
 await wait(()=>owner.querySelector('#navigation-mist-value')?.textContent==='0.4','original callback');
 result.callback=true;
 await query('controls: Enable drifting');
 check(controller().items.every(i=>i.provider==='control'),'plural category alias');
 await wait(()=>document.querySelector('.afield-palette-control-value')?.textContent==='On','checkbox value');
 result.checkboxPreview=true;
 await query(',ControlSearchTarget');
 result.boxScope=controller().items.every(i=>i.provider==='box')&&controller().items.some(i=>i.boxId===owner.uid);
 await query('command: Frame Selected');
 result.commandScope=controller().items.every(i=>i.provider==='command');
 check(paletteAnywhereQuery('constructor: text').query==='constructor: text','unknown prototype prefix preserved');
 check(paletteAnywhereQuery('https://example.com').query==='https://example.com','unknown prefixes preserved');
 check(paletteAnywhereQuery('`wind').providers.includes('box-prop'),'single-character code prefix');
 check(paletteAnywhereQuery('`wind').query==='wind','code prefix removed');
 check(paletteAnywhereQuery('property: wind').providers[0]==='box-prop','property prefix');
 controller().hide();
 window.splitCanvasAt(650,null);await delay(300);
 window.unlinkCanvasPane(0,0,true,false);
 const ownPane=owner.closest('.field-pane'),ownRect=ownPane.getBoundingClientRect();
 ownPane.dispatchEvent(new MouseEvent('mousedown',{clientX:ownRect.left+20,clientY:ownRect.top+30,button:1,buttons:4,bubbles:true,cancelable:true}));
 window.dispatchEvent(new MouseEvent('mousemove',{clientX:ownRect.left+20,clientY:ownRect.top+30,movementX:1200,movementY:0,buttons:4,bubbles:true,cancelable:true}));
 window.dispatchEvent(new MouseEvent('mouseup',{button:1,buttons:0,bubbles:true}));
 const off=mist().getBoundingClientRect();
 result.splitInitiallyOffscreen=off.right<ownRect.left||off.left>ownRect.right;
 const splitBefore=window.canvasPanesState(),siblingBefore=splitBefore.panes.at(-1).tx;
 await query('> Mist density');
 controller().setActive(controller().items.findIndex(i=>i.controlTarget?.boxId===owner.uid&&i.controlTarget.channel==='Far controls'));
 accept();
 await wait(()=>{
  const r=mist().getBoundingClientRect();
  return Math.abs(r.left+r.width/2-expectedX)<3&&Math.abs(r.top+r.height/2-innerHeight/2)<3;
 },'split-pane frame animation completed');
 const sr=mist().getBoundingClientRect(),pr=owner.closest('.field-pane').getBoundingClientRect();
 result.splitRect={control:sr.toJSON(),pane:pr.toJSON()};
 result.splitPane=sr.left>=pr.left-1&&sr.right<=pr.right+1&&sr.top>=pr.top-1&&sr.bottom<=pr.bottom+1;
 // Zoom belongs to every pane; only a pan at the same zoom must preserve the sibling X.
 const splitAfter=window.canvasPanesState();
 result.splitOtherX=Math.abs(splitAfter.zoom-splitBefore.zoom)>0.001||Math.abs(splitAfter.panes.at(-1).tx-siblingBefore)<1;
 result.splitGeometry={before:splitBefore.zoom,after:splitAfter.zoom,siblingBefore,siblingAfter:splitAfter.panes.at(-1).tx};
 await query('`navigationMist');
 result.codeScope=controller().items.some(i=>i.provider==='box-prop'&&i.boxId===owner.uid)&&controller().items.every(i=>['box-prop','semantic'].includes(i.provider)&&i.semanticHit?.corpus!=='documentation');
 // Scoped searches must dispatch only their provider, with the prefix removed.
 const originalFetch=window.fetch,calls=[];
 window.fetch=async(url,...args)=>{
  if(String(url).includes('/api/docs-search')){calls.push(String(url));return {ok:true,json:async()=>({hits:[]})};}
  if(/\/api\/(workspace-search|semantic-search)/.test(String(url)))throw new Error('wrong provider '+url);
  return originalFetch(url,...args);
 };
 try{
  input().value='? palette';input().dispatchEvent(new Event('input',{bubbles:true}));
  await wait(()=>calls.length>0,'docs provider called');
  check(new URL(calls[0],location.href).searchParams.get('q')==='palette','prefix stripped before backend search');
  result.docsScope=true;
 }finally{window.fetch=originalFetch;}
 result.ok=result.scoped&&result.duplicates&&result.previewSafe&&result.livePreview&&result.open&&result.scrolled&&result.inViewport&&result.clearOfEditor&&result.centered&&result.callback&&result.checkboxPreview&&result.boxScope&&result.commandScope&&result.docsScope&&result.rerender&&result.splitPane&&result.splitInitiallyOffscreen&&result.codeScope;
 await query('> Mist density');
 controller().setActive(controller().items.findIndex(i=>i.controlTarget?.boxId===owner.uid&&i.controlTarget.channel==='Far controls'));
 document.querySelectorAll('.field-webgl-background').forEach(e=>e.style.display='none');
 return result;
}catch(error){controller().hide();return {...result,ok:false,error:String(error)};}
