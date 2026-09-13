import { sendWithReply, clearReplyHandler } from "./afield.mjs";
import { selectedSpans, profileSummary, profileFrameSummary, profileFrameBuckets, mergeProfileSpans } from "./profileTimelineModel.js";
import { createProfileStacks } from "./profilingStacks.js";

const ROW = 32;
let panel, canvas, overview, rows, detail, status, summary, filter, metric, laneViewport;
let capture = null, spans = [], brush = null, view = null, selected = null, focusLane = null;
let visible = false, timer = null, pending = false, serial = 0, epoch = 0, dragging = null;
let documentName = "", laneOrder = [];
let replyName = "", replyTimeout = null;
let queuedAction = null;
let stackSamples=[],frameRecords=[],stackViewer,stackPanel,coroutineBody,framePanel;
const fmt = n => Number(n).toFixed(2);
const bytes = n => n === null || n < 0 ? "unavailable" : n >= 1048576 ? `${fmt(n/1048576)} MiB` : `${fmt(n/1024)} KiB`;
const el = (tag, cls, text) => { const n=document.createElement(tag); if(cls)n.className=cls; if(text!==undefined)n.textContent=text; return n; };

function styles() {
    if (document.getElementById("profile-timeline-style")) return;
    const link=el("link"); link.id="profile-timeline-style"; link.rel="stylesheet"; link.href="profilingTimeline.css"; document.head.append(link);
}

function button(text, action, id) {
    const b=el("button", "", text); if(id)b.id=id; b.addEventListener("click", action); return b;
}

function build() {
    if(panel)return;
    styles();
    panel=el("section", "profile-timeline"); panel.id="profile-timeline"; panel.setAttribute("aria-label", "Coroutine profiling timeline");
    const header=el("header"); header.append(el("strong", "", "Profiling"));
    const duration=el("select"); duration.id="profile-duration"; duration.setAttribute("aria-label", "Capture duration");
    [3,10,30].forEach(n=>{const o=el("option", "", `${n} seconds`);o.value=n;o.selected=n===10;duration.append(o);});
    header.append(duration, button("Record", ()=>request("start", Number(duration.value)), "profile-record"),
        button("Latch", ()=>request("stop"), "profile-stop"));
    const stackLabel=el("label","profile-stack-toggle","JVM stacks ");const stacks=el("input");stacks.type="checkbox";stacks.id="profile-enable-stacks";stackLabel.prepend(stacks);stackLabel.title="Sample CPU and allocations across the JVM, including background threads and other documents";header.append(stackLabel);
    status=el("span", "profile-status", "Ready"); status.id="profile-status"; status.setAttribute("role","status"); header.append(status);
    const closeButton=button("×",close,"profile-close");closeButton.className="macro-recorder-icon";closeButton.title="Close";closeButton.setAttribute("aria-label","Close profiling");
    header.append(button("Save capture", save, "profile-save"), closeButton);
    const toolbar=el("div", "profile-toolbar");
    filter=el("input"); filter.type="search"; filter.placeholder="Find a box or coroutine"; filter.id="profile-filter"; filter.setAttribute("aria-label",filter.placeholder);
    filter.addEventListener("input", ()=>{focusLane=null;render();});
    metric=el("select"); metric.id="profile-metric"; metric.setAttribute("aria-label","Overview metric");
    [["frameWall","Frame interval"],["frameBytes","Bytes per frame"],["wall","Self execution time"],["cpu","CPU time"],["bytes","Allocated bytes"],["sampleCpu","JVM CPU samples"],["sampleBytes","JVM allocation samples"]].forEach(([v,t])=>{const o=el("option","",t);o.value=v;metric.append(o);});
    metric.addEventListener("change",render);
    toolbar.append(filter,metric,button("Zoom to selection",()=>{if(brush){view=brush.slice();render();}},"profile-zoom"),
        button("Show all",()=>{brush=null;view=null;focusLane=null;render();},"profile-reset"));
    overview=el("canvas"); overview.id="profile-overview"; overview.setAttribute("aria-label","Capture overview. Drag to select a time range.");
    overview.tabIndex=0;
    const ticks=el("div","profile-axis"); ticks.id="profile-axis";
    summary=el("div","profile-summary"); summary.id="profile-summary";
    const body=el("div","profile-body"); const lanes=el("div","profile-lanes"); lanes.id="profile-lanes"; laneViewport=lanes;
    const track=el("div","profile-track"); rows=el("div","profile-labels"); canvas=el("canvas"); canvas.id="profile-spans";
    canvas.setAttribute("aria-label","Scheduled coroutine execution spans"); canvas.tabIndex=0; track.append(rows,canvas); lanes.append(track);
    detail=el("aside","profile-detail"); detail.id="profile-detail"; body.append(lanes,detail);
    coroutineBody=body;stackPanel=el("div","profile-stacks");stackPanel.id="profile-stacks";stackPanel.hidden=true;
    stackViewer=createProfileStacks(stackPanel,compact);
    const tabs=el("div","profile-tabs");
    framePanel=el("div","profile-frames");framePanel.id="profile-frames";framePanel.hidden=true;
    for(const [id,label,mode] of [["profile-tab-coroutines","Coroutines & scopes","coroutines"],["profile-tab-frames","Frame budgets","frames"],["profile-tab-stacks","Hot code","stacks"]]) {
        const b=button(label,()=>{body.hidden=mode!=="coroutines";stackPanel.hidden=mode!=="stacks";framePanel.hidden=mode!=="frames";
            focusLane=null;selected=null;metric.value=mode==="stacks"?"sampleCpu":mode==="frames"?"frameWall":"wall";
            tabs.querySelectorAll("button").forEach(n=>n.classList.toggle("profile-tab-active",n===b));render();},id);
        b.classList.toggle("profile-tab-active",mode==="coroutines");tabs.append(b);
    }
    metric.value="wall";
    const note=el("footer","", "Brush time to inspect work. All-lane totals use self costs; a selected lane includes its children. Frames are explicit application intervals. Amber marks GC pauses across the whole JVM.");
    panel.append(header,toolbar,overview,ticks,summary,tabs,body,framePanel,stackPanel,note);document.body.append(panel);
    installResize();
    lanes.addEventListener("scroll", drawLanes);
    overview.addEventListener("pointerdown",e=>{if(!capture)return;dragging=overviewTime(e);overview.setPointerCapture(e.pointerId);brush=[dragging,dragging];render();});
    overview.addEventListener("pointermove",e=>{if(dragging===null)return;const t=overviewTime(e);brush=[Math.min(t,dragging),Math.max(t,dragging)];render();});
    const finish=e=>{if(dragging===null)return;dragging=null;if(brush[1]-brush[0]<.001)brush=null;render();};
    overview.addEventListener("pointerup",finish);overview.addEventListener("pointercancel",finish);
    overview.addEventListener("keydown",e=>{
        if(e.key==="Escape"&&brush){brush=null;render();e.preventDefault();e.stopPropagation();}
        if(e.key==="ArrowLeft"||e.key==="ArrowRight"){
            const all=extent(), step=(all[1]-all[0])*.05, width=brush?brush[1]-brush[0]:(all[1]-all[0])*.2;
            const start=Math.max(all[0],Math.min(all[1]-width,(brush?.[0]??all[0])+(e.key==="ArrowRight"?step:-step)));
            brush=[start,start+width];render();e.preventDefault();
        }
    });
    canvas.addEventListener("click",e=>{
        const rect=canvas.getBoundingClientRect(),lane=laneOrder[Math.floor((e.clientY-rect.top+laneViewport.scrollTop)/ROW)];if(!lane)return;
        const [a,b]=windowRange(), t=a+(e.clientX-rect.left)/rect.width*(b-a), tolerance=(b-a)*4/rect.width;
        const candidates=spans.filter(s=>s.lane===lane.id&&s.startMs<=t+tolerance&&s.startMs+s.wallMs>=t-tolerance);
        selected=candidates.sort((x,y)=>Math.abs(x.startMs-t)-Math.abs(y.startMs-t))[0]||null;focusLane=lane.id;render();
    });
    new ResizeObserver(()=>{if(visible)render();}).observe(panel);
    panel.addEventListener("keydown",e=>{if(e.key==="Escape"&&!dragging){close();e.stopPropagation();}});
    // Keep canvas/editor shortcuts out of controls and brushing.
    panel.addEventListener("pointerdown",e=>{
        const r=panel.getBoundingClientRect();
        // Edge events must reach Interact's document listener to start a resize.
        if(e.clientX-r.left>14&&r.right-e.clientX>14&&e.clientY-r.top>14&&r.bottom-e.clientY>14)e.stopPropagation();
    });
    ["keydown","keyup","wheel"].forEach(type=>panel.addEventListener(type,e=>e.stopPropagation()));
}

function extent(){return [capture?.droppedSpans ? spans[0]?.startMs||0 : 0,Math.max(capture?.elapsedMs||1,spans.at(-1)?spans.at(-1).startMs+spans.at(-1).wallMs:1)];}
function windowRange(){return view||extent();}
function overviewTime(e){const r=overview.getBoundingClientRect(),[a,b]=extent();return a+Math.max(0,Math.min(1,(e.clientX-r.left)/r.width))*(b-a);}
function setupCanvas(c,height){const w=Math.max(1,c.clientWidth),ratio=window.devicePixelRatio||1;c.style.height=`${height}px`;c.width=Math.round(w*ratio);c.height=Math.round(height*ratio);const g=c.getContext("2d");g.scale(ratio,ratio);return[g,w];}

function render(){
    if(!panel||!visible)return;
    const available=capture?.captureId;
    const caughtUp=(spans.at(-1)?.sequence??-1)>=(capture?.nextSequence??0)-1 && (frameRecords.at(-1)?.sequence??-1)>=(capture?.nextFrameSequence??0)-1;
    document.getElementById("profile-record").disabled=!!capture?.recording;
    document.getElementById("profile-stop").disabled=!capture?.recording;
    const stacksDone=!capture||["off","failed","ready"].includes(capture.stackState||"off")&&stackSamples.length>=(capture.stackSampleCount||0);
    document.getElementById("profile-save").disabled=!available||capture.recording||!caughtUp||!stacksDone;
    document.getElementById("profile-enable-stacks").disabled=!!capture?.recording;
    document.getElementById("profile-zoom").disabled=!brush;
    status.textContent=available?`${capture.recording?"Recording":caughtUp?"Latched":"Loading"} · ${fmt(capture.elapsedMs/1000)} s · ${spans.length.toLocaleString()} spans · ${frameRecords.length} frames${capture.droppedSpans?` · ${capture.droppedSpans.toLocaleString()} omitted`:""}`:"Ready — record while your boxes run";
    const search=filter.value.toLowerCase();
    laneOrder=(capture?.lanes||[]).filter(l=>l.kind!=="frame").filter(l=>`${l.boxName} ${l.boxId} ${l.strand} ${l.label||""} ${l.id}`.toLowerCase().includes(search));
    const allowed=new Set(laneOrder.map(l=>l.id));
    const range=brush||windowRange();
    const selectedRecords=selectedSpans(spans,range,focusLane).filter(s=>allowed.has(s.lane));
    const totals=profileSummary(selectedRecords,focusLane===null);
    summary.textContent=`${focusLane===null?"Self costs":"Inclusive lane"} · ${fmt(range[0])}–${fmt(range[1])} ms · ${totals.count.toLocaleString()} spans · execution ${fmt(totals.wallMs)} ms · CPU ${totals.cpuMs===null?"unavailable":fmt(totals.cpuMs)+" ms"} · allocated ${bytes(totals.allocatedBytes)} · span p95 ${fmt(totals.p95Ms)} ms`;
    panel.dataset.selectedCount=totals.count;panel.dataset.rangeStart=range[0];panel.dataset.rangeEnd=range[1];
    const[g,w]=setupCanvas(overview,64),[start,end]=extent();g.fillStyle="#232323";g.fillRect(0,0,w,64);
    const buckets=new Float64Array(Math.min(400,Math.ceil(w/3)));
    const add=(t,n)=>{const i=Math.max(0,Math.min(buckets.length-1,Math.floor((t-start)/(end-start)*buckets.length)));buckets[i]+=n;};
    if(metric.value.startsWith("sample")){for(const s of stackSamples)if(s.kind===(metric.value==="sampleCpu"?"cpu":"allocation"))add(s.timeMs,s.weight);}
    else if(metric.value.startsWith("frame")){
        const frameLanes=new Set((capture?.lanes||[]).filter(l=>l.kind==="frame"&&`${l.boxName} ${l.label}`.toLowerCase().includes(search)).map(l=>l.id));
        buckets.set(profileFrameBuckets(frameRecords.filter(f=>frameLanes.has(f.lane)),[start,end],buckets.length,metric.value==="frameBytes"));
    }
    else for(const s of spans){if(allowed.has(s.lane))add(s.startMs,metric.value==="bytes"?Math.max(0,s.selfAllocatedBytes??s.allocatedBytes):metric.value==="cpu"?Math.max(0,s.selfCpuMs??s.cpuMs):(s.selfWallMs??s.wallMs));}
    const peak=Math.max(...buckets,1);g.fillStyle=["bytes","sampleBytes","frameBytes"].includes(metric.value)?"#d7ad79":["cpu","sampleCpu"].includes(metric.value)?"#48c9ff":"#aaa";buckets.forEach((v,i)=>g.fillRect(i*w/buckets.length,60-v/peak*52,Math.max(1,w/buckets.length-1),v/peak*52));
    g.fillStyle="#d7ad79";for(const pause of capture?.gcPauses||[]){const x=(pause.startMs-start)/(end-start)*w;g.fillRect(x,0,Math.max(2,pause.durationMs/(end-start)*w),6);}
    if(brush){const x=(brush[0]-start)/(end-start)*w,x2=(brush[1]-start)/(end-start)*w;g.fillStyle="rgba(72,201,255,.13)";g.fillRect(x,0,x2-x,64);g.strokeStyle="#48c9ff";g.strokeRect(x+.5,.5,x2-x,63);}
    document.getElementById("profile-axis").textContent=`${fmt(start/1000)} s${" ".repeat(8)} ${metric.value.startsWith("sample")?"whole JVM samples":metric.value.startsWith("frame")?"worst frame per time bin":"self continuation / scope costs"}${" ".repeat(8)}${fmt(end/1000)} s`;
    rows.replaceChildren();
    laneOrder.forEach(l=>{const b=button(`${l.boxName||l.boxId} · ${l.label||"#"+l.id}`,()=>{focusLane=focusLane===l.id?null:l.id;selected=null;render();});b.title=`${l.strand} · ${l.state} · source ${l.revision}`;b.dataset.lane=l.id;b.classList.toggle("profile-lane-selected",focusLane===l.id);rows.append(b);});
    drawLanes();
    renderDetail();
    renderFrames(range,search);
    stackViewer.update(capture,spans,stackSamples,range,focusLane);
}

// Only allocate pixels for the visible viewport, even for captures with hundreds of jobs.
function drawLanes(){
    if(!visible||!canvas||coroutineBody.hidden)return;
    const offset=laneViewport.scrollTop;
    const[ctx,width]=setupCanvas(canvas,Math.max(ROW,laneViewport.clientHeight));const[a,b]=windowRange();
    ctx.fillStyle="#232323";ctx.fillRect(0,0,width,canvas.clientHeight);
    const positions=new Map(laneOrder.map((l,i)=>[l.id,i]));
    for(let i=Math.floor(offset/ROW);i<Math.min(laneOrder.length,Math.ceil((offset+canvas.clientHeight)/ROW));i++){ctx.strokeStyle="#252525";ctx.beginPath();ctx.moveTo(0,(i+1)*ROW-offset-.5);ctx.lineTo(width,(i+1)*ROW-offset-.5);ctx.stroke();}
    for(const s of spans){const row=positions.get(s.lane);const y=row*ROW-offset;if(row===undefined||y+ROW<0||y>canvas.clientHeight||s.startMs+s.wallMs<a||s.startMs>b)continue;const x=Math.max(0,(s.startMs-a)/(b-a)*width),right=Math.min(width,(s.startMs+s.wallMs-a)/(b-a)*width);
        ctx.fillStyle=selected?.sequence===s.sequence?"#fff":brush&&(s.startMs<brush[0]||s.startMs>brush[1])?"#333":"#9f9f9f";ctx.fillRect(x,y+7,Math.max(1,right-x),18);
        if(s.cpuMs>=0){ctx.fillStyle="#48c9ff";ctx.fillRect(x,y+20,Math.max(1,(right-x)*Math.min(1,s.cpuMs/Math.max(.001,s.wallMs))),5);}
    }
}

function renderDetail(){
    detail.replaceChildren();const lane=capture?.lanes.find(l=>l.id===(selected?.lane??focusLane));
    if(!lane){detail.append(el("h3","","Inspect a coroutine"),el("p","","Select a lane, then zoom and click an execution span. The bright lower strip is CPU time; the full bar is elapsed execution time."));if(capture?.captureId&&!spans.length)detail.append(el("p","","No box-owned continuations observed. Launch a box with loop { … } or frames { … }, then record again."));return;}
    detail.append(el("h3","",lane.boxName||lane.boxId),el("p","",`${lane.kind==="scope"?"Scope":"Coroutine"} ${lane.label||"#"+lane.id} · ${lane.strand} · ${lane.state}`),el("p","",`Source revision ${lane.revision}`));
    detail.append(button("Open current box code",()=>{window.navigateToBoxProperty?.(lane.boxId,"code",null,null);compact();},"profile-open-source"));
    if(selected){const s=selected;detail.append(el("p","",`Runloop tick ${s.frame} · ${fmt(s.startMs)} ms`),el("p","",`Execution ${fmt(s.wallMs)} ms`),el("p","",`CPU ${s.cpuMs<0?"unavailable":fmt(s.cpuMs)+" ms"}`),el("p","",`Allocated ${bytes(s.allocatedBytes)} · self ${bytes(s.selfAllocatedBytes??s.allocatedBytes)}`));}
    const records=selectedSpans(spans,brush||windowRange(),lane.id).sort((a,b)=>b.wallMs-a.wallMs).slice(0,6);
    detail.append(el("h4","","Longest inclusive spans in selection"));records.forEach(s=>detail.append(button(`${fmt(s.startMs)} ms → ${fmt(s.wallMs)} ms`,()=>{selected=s;const pad=Math.max(1,s.wallMs);view=[Math.max(0,s.startMs-pad),s.startMs+s.wallMs+pad];render();})));
}

function renderFrames(range,search) {
    framePanel.replaceChildren();
    const lanes=(capture?.lanes||[]).filter(l=>l.kind==="frame"&&`${l.boxName} ${l.label}`.toLowerCase().includes(search));
    if(!lanes.length){framePanel.append(el("p","","No explicit frame boundaries observed. In your ordinary frames box, create frameProfile(), then call beginFrame() once per application frame. Use measure(\"stage\") { … } for named scopes."));return;}
    for(const lane of lanes) {
        const records=selectedSpans(frameRecords,range,lane.id), s=profileFrameSummary(records);
        const card=el("section","profile-frame-card");card.dataset.frameLane=lane.id;
        card.append(el("h3","",`${lane.boxName} · ${lane.label}`));
        card.append(el("p","",`${s.count} complete frames · mean ${fmt(s.meanMs)} ms · p50 ${fmt(s.p50Ms)} · p95 ${fmt(s.p95Ms)} · p99 ${fmt(s.p99Ms)} · max ${fmt(s.maxMs)} ms`));
        const budget=el("p",s.missed||s.allocationMissed?"profile-budget-miss":"",`${s.missed} interval budget misses · ${s.allocationMissed} allocation guide misses · mean CPU ${s.meanCpuMs===null?"unavailable":fmt(s.meanCpuMs)+" ms"} · ${bytes(s.meanBytes)} allocated/frame`);
        card.append(budget,button("Open frame owner",()=>{window.navigateToBoxProperty?.(lane.boxId,lane.strand,null,null);compact();}));
        const worst=el("div","profile-frame-worst");
        records.slice().sort((a,b)=>b.wallMs-a.wallMs).slice(0,5).forEach(f=>worst.append(button(`${fmt(f.startMs)} ms → ${fmt(f.wallMs)} ms · ${bytes(f.allocatedBytes)}`,()=>{brush=[f.startMs,f.startMs+f.wallMs];view=brush.slice();render();})));
        card.append(el("h4","","Slowest intervals — select to inspect their scopes"),worst);framePanel.append(card);
    }
    framePanel.append(el("p","",`Whole JVM during capture: ${capture.gcCollections||0} GC collections · ${capture.gcCollectionMs||0} ms collector time${capture.gcPauses?.length?" · "+fmt(capture.gcPauses.reduce((s,p)=>s+p.durationMs,0))+" ms recorded GC pause events":""}. Collector time and pause duration are different measurements.${capture.oldestFrameSequence?" Earlier frame records were overwritten: "+capture.oldestFrameSequence:""}`));
}

function request(action="snapshot", seconds=10){
    if(!visible)return;
    if(pending){if(action!=="snapshot")queuedAction=[action,seconds];return;}
    pending=true;const token=epoch;
    const after=spans.at(-1)?.sequence??-1;const payload={action,seconds,after,captureId:capture?.captureId||"",stacks:document.getElementById("profile-enable-stacks").checked,stackAfter:stackSamples.length-1,frameAfter:frameRecords.at(-1)?.sequence??-1};
    replyName=`profile-timeline-${++serial}`;
    replyTimeout=setTimeout(()=>{clearReplyHandler(replyName);pending=false;status.textContent="Capture request timed out — retrying";schedule(1000);},8000);
    sendWithReply("coroutine-profile",payload,replyName,(_message,response)=>{
        if(token!==epoch)return "remove";pending=false;clearTimeout(replyTimeout);
        const data={ok:true,captureId:"",recording:false,elapsedMs:0,nextSequence:0,oldestSequence:0,droppedSpans:0,lanes:[],spans:[],...response};
        if(!data.ok){status.textContent=data.error||"Capture request failed";schedule(1000);return "remove";}
        if(data.captureId!==capture?.captureId){spans=[];stackSamples=[];frameRecords=[];brush=null;view=null;selected=null;focusLane=null;if([3000,10000,30000].includes(data.durationMs))document.getElementById("profile-duration").value=data.durationMs/1000;}
        else {if(!data.stackFrames?.length)data.stackFrames=capture?.stackFrames||[];if(!data.stackSources?.length)data.stackSources=capture?.stackSources||[];}
        stackSamples.push(...(data.stackSamples||[]));
        if(data.captureId)document.getElementById("profile-enable-stacks").checked=(data.stackState||"off")!=="off";
        frameRecords=mergeProfileSpans(frameRecords,data.frameRecords||[],data.oldestFrameSequence||0);
        spans=mergeProfileSpans(spans,data.spans||[],data.oldestSequence||0);capture=data;render();
        const caughtUp=(spans.at(-1)?.sequence??-1)>=data.nextSequence-1&&(frameRecords.at(-1)?.sequence??-1)>=(data.nextFrameSequence||0)-1;
        if(queuedAction){const next=queuedAction;queuedAction=null;request(...next);}
        else if(data.recording||!caughtUp||data.stackState==="processing"||stackSamples.length<(data.stackSampleCount||0))schedule(caughtUp?500:60);
        return "remove";
    });
}

function schedule(ms){clearTimeout(timer);if(visible)timer=setTimeout(()=>request(),ms);}
function save(){if(!capture?.captureId||document.getElementById("profile-save").disabled)return;const blob=new Blob([JSON.stringify({formatVersion:3,document:documentName,...capture,spans,stackSamples,frameRecords},null,2)],{type:"application/json"});const u=URL.createObjectURL(blob),a=el("a");a.href=u;a.download=`field-profile-${capture.captureId}.json`;a.click();setTimeout(()=>URL.revokeObjectURL(u),1000);}
function close(){visible=false;epoch++;pending=false;queuedAction=null;stackViewer?.clearMarks();clearTimeout(timer);clearTimeout(replyTimeout);if(replyName)clearReplyHandler(replyName);if(panel)panel.hidden=true;}
function compact(){panel.style.width=`${Math.max(550,window.innerWidth*.5-24)}px`;panel.style.right="auto";render();}
function installResize(){
    // Use Field's existing Interact edge/corner gestures, with a visible corner affordance.
    for(const corner of ["nw","ne","sw","se"]){const handle=el("span",`profile-resize profile-resize-${corner}`);handle.dataset.corner=corner;panel.append(handle);}
    window.interact(panel).resizable({edges:{top:true,bottom:true,left:true,right:true},margin:12}).on("resizemove",event=>{
        const r=panel.getBoundingClientRect(),gutter=10,minW=Math.min(620,window.innerWidth-20),minH=Math.min(340,window.innerHeight-20);
        const width=Math.min(window.innerWidth-20,Math.max(minW,event.rect.width)),height=Math.min(window.innerHeight-20,Math.max(minH,event.rect.height));
        const left=Math.max(gutter,Math.min(window.innerWidth-width-gutter,r.left+event.deltaRect.left));
        const top=Math.max(gutter,Math.min(window.innerHeight-height-gutter,r.top+event.deltaRect.top));
        Object.assign(panel.style,{left:`${left}px`,top:`${top}px`,right:"auto",bottom:"auto",width:`${width}px`,height:`${height}px`});
    });
}
window.showProfilingTimeline=()=>{build();const name=window.currentDocumentFilename||"";if(documentName!==name){capture=null;spans=[];stackSamples=[];frameRecords=[];brush=null;view=null;documentName=name;}visible=true;panel.hidden=false;render();request();};
window.closeProfilingTimeline=close;
