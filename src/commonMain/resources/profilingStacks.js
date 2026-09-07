import { profileHotspots, mappedProfileLine, profileBoxHeatmap } from "./profileTimelineModel.js";

const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
const select=(id,options)=>{const n=node("select");n.id=id;for(const [value,label] of options){const o=node("option",label);o.value=value;n.append(o);}return n;};
const weight=(n,kind)=>kind==="cpu"?`${n} samples`:`${(n/1048576).toFixed(2)} MiB estimated`;

/** The brush is shared with the exact Runloop spans; the two measurements stay separate. */
export function createProfileStacks(parent,compact) {
    const controls=node("div");controls.className="profile-toolbar";
    const kind=select("profile-stack-kind",[["cpu","CPU samples"],["allocation","Allocation samples"]]);
    const group=select("profile-stack-group",[["method","Methods"],["class","Classes"],["line","Lines"]]);
    const mode=select("profile-stack-mode",[["inclusive","Including callees"],["self","Top frame only"]]);
    const scope=select("profile-stack-scope",[["jvm","Whole JVM"],["lane","Selected coroutine"]]);
    const threads=select("profile-stack-thread",[["all","All threads"]]);
    const search=node("input");search.type="search";search.placeholder="Find a package, class or method";search.id="profile-stack-search";
    const heatLabel=node("label","Box gutter heatmap");heatLabel.className="profile-stack-toggle";
    const heat=node("input");heat.type="checkbox";heat.id="profile-stack-heatmap";heat.checked=true;heatLabel.prepend(heat);
    heatLabel.title="Mapped box lines in the selected interval: brighter means more samples. Cyan is CPU; amber is estimated allocation. Updates as you browse unchanged boxes.";
    for(const [control,label] of [[kind,"Sample kind"],[group,"Group samples"],[mode,"Stack accounting"],[scope,"Sample scope"],[threads,"Thread"],[search,search.placeholder]])control.setAttribute("aria-label",label);
    controls.append(kind,group,mode,scope,threads,search,heatLabel);
    const status=node("p");status.id="profile-stack-status";
    const body=node("div");body.className="profile-hot-body";
    const list=node("div");list.className="profile-hot-list";list.id="profile-hot-list";
    const source=node("div");source.className="profile-hot-source";source.id="profile-hot-source";
    body.append(list,source);parent.append(controls,status,body);
    let data=null,chosen=null,lineRows=[],markGeneration=0;
    function clearMarks(){markGeneration++;window.LineHeatmap?.setProfile(window.globalEditor,null);}
    function options(){return {kind:kind.value,group:group.value,self:mode.value==="self",search:search.value,thread:threads.value==="all"?null:Number(threads.value),spans:data.spans,lane:scope.value==="lane"?data.lane:null};}
    const mappedLine=mappedProfileLine;
    function lineWeights(s){
        const counts=new Map();
        // Count each sample once per source line, including recursive or inlined frames.
        for(const r of lineRows)if(r.frame.sourceId===s.id&&r.frame.line>0)counts.set(r.frame.line,(counts.get(r.frame.line)||0)+r.weight);
        return counts;
    }
    function refreshMarks() {
        window.LineHeatmap?.setProfile(window.globalEditor, heat.checked && data.capture.stackState === "ready" ? {
            sources:profileBoxHeatmap(lineRows,data.capture.stackSources||[]),
            kind:kind.value, self:mode.value==="self", range:data.range
        } : null);
    }
    async function openBox(s,line) {
        const generation=markGeneration;
        window.navigateToBoxProperty?.(s.boxId,s.property||"code",null,line);
        compact();
        const expected=data.capture.captureId;
        for(let i=0;i<40;i++){
            await new Promise(r=>setTimeout(r,50));
            if(data.capture.captureId!==expected||generation!==markGeneration)return;
            const model=window.globalEditor?.getModel?.();
            const selected=window.currentSelectedCode||[];
            if(selected.length!==1||selected[0].uid!==s.boxId||String(window.currentSelectedProperty||"code")!==(s.property||"code"))continue;
            if(model?.getValue()!==s.boxCode)continue;
            refreshMarks();
            return;
        }
        source.append(node("p","The box has changed since this capture. Line annotations were not applied."));
    }
    function preview(frame) {
        chosen=frame;source.replaceChildren();
        const s=data.capture.stackSources?.find(s=>s.id===frame.sourceId);
        const boxName=s?.boxId&&(data.capture.lanes?.find(l=>l.boxId===s.boxId)?.boxName||s.boxId);
        source.append(node("h3",boxName?`${boxName} · line ${frame.line}`:`${frame.className}.${frame.method}`));
        if(!s){source.append(node("p",`Line ${frame.line>0?frame.line:"unavailable"} · source is not available in this checkout.`));return;}
        const counts=lineWeights(s);
        const origin=node("p",s.boxId?"captured compiled box source":`${s.path} · checkout source read after capture`);origin.title=s.path;source.append(origin);
        if(s.boxId){
            const open=node("button","Open box with gutter heatmap");open.id="profile-open-sampled-source";
            const line=mappedLine(s,frame.line);
            open.disabled=!line;
            open.title=line?"Only unchanged, directly mapped source lines are annotated":"Generated or fragment source has no verified mapping to this box line";
            open.onclick=()=>{heat.checked=true;openBox(s,line);};source.append(open);
        }
        const code=node("div");code.className="profile-source-lines";
        const lines=s.code.split("\n"),line=frame.line;
        for(let i=Math.max(0,line-9);i<Math.min(lines.length,Math.max(30,line+15));i++){
            const row=node("div");row.className="profile-source-line";if(i+1===line)row.classList.add("profile-source-focus");
            row.append(node("span",String(i+1)),node("span",counts.has(i+1)?weight(counts.get(i+1),kind.value):""),node("code",lines[i]));code.append(row);
        }
        source.append(code);
        requestAnimationFrame(()=>{const focus=code.querySelector('.profile-source-focus');if(focus)code.scrollTop+=focus.getBoundingClientRect().top-code.getBoundingClientRect().top-code.clientHeight*.4;});
    }
    function render(){
        if(!data)return;
        scope.options[1].disabled=data.lane===null;
        if(data.lane===null)scope.value="jvm";
        const old=threads.value,seen=new Map(data.samples.map(s=>[s.threadId,s.threadName]));
        threads.replaceChildren();const all=node("option","All threads");all.value="all";threads.append(all);
        for(const [id,name] of seen){const option=node("option",`${name} · ${id}`);option.value=id;threads.append(option);}threads.value=seen.has(Number(old))?old:"all";
        list.replaceChildren();
        const state=data.capture?.stackState||"off";
        lineRows=state==="ready"?profileHotspots(data.samples,data.capture.stackFrames||[],data.range,{...options(),group:"sourceLine",search:""}).rows:[];
        refreshMarks();
        if(state!=="ready"){
            status.textContent=state==="off"?"Enable JVM stacks before recording to inspect classes, methods and lines.":state==="failed"?`JFR capture failed: ${data.capture.stackError}`:state==="processing"?"Reading JVM stacks…":"Recording JVM stacks; hot code becomes available after latching.";
            source.replaceChildren();return;
        }
        const result=profileHotspots(data.samples,data.capture.stackFrames||[],data.range,options());
        status.textContent=`${result.count} events · ${weight(result.total,kind.value)} · ${scope.value==="jvm"?"whole JVM, including other documents and background threads":"matched by thread and resume interval"} · ${mode.value==="inclusive"?"inclusive rows overlap":"top sampled frame"}${data.capture.stackDropped?` · ${data.capture.stackDropped} events omitted`:""}`;
        parent.dataset.sampleCount=result.count;
        for(const r of result.rows.slice(0,150)){
            const b=node("button");b.className="profile-hot-row";b.title=r.label;b.dataset.className=r.frame.className;b.dataset.line=r.frame.line;
            const bar=node("span");bar.className="profile-hot-bar";bar.style.width=`${100*r.weight/Math.max(1,result.total)}%`;
            b.append(bar,node("strong",`${(100*r.weight/Math.max(1,result.total)).toFixed(1)}%`),node("span",r.label),node("small",weight(r.weight,kind.value)));
            b.onclick=()=>preview(r.frame);list.append(b);
        }
        if(!result.rows.length)list.append(node("p","No samples in this selection. Short or infrequent work may fall between samples."));
        if(chosen)preview(chosen);else source.replaceChildren(node("p","Select a method or line to inspect its source. CPU sample counts and weighted allocation estimates are statistical, not exact per-line measurements."));
    }
    for(const control of [kind,group,mode,scope,threads,search,heat])control.addEventListener(control===search?"input":"change",()=>render());
    return {update(capture,spans,samples,range,lane){if(data?.capture.captureId!==capture?.captureId){chosen=null;clearMarks();}data={capture:capture||{},spans,samples,range,lane};render();},clearMarks};
}
