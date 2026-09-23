/* Field's bundled Plotly renderer, backed by cancellable visible-window queries. */
(() => {
  const root = document.currentScript.closest('.field-signal-viewer');
  if (!root || root.dataset.ready) return;
  root.dataset.ready = 'true'; root.dataset.renderer = 'plotly';
  const config = JSON.parse(root.querySelector('.fsv-config').textContent);
  const stage = root.querySelector('.fsv-stage'), status = root.querySelector('.fsv-status');
  const range = root.querySelector('.fsv-range'), readout = root.querySelector('.fsv-readout');
  const title = root.querySelector('.fsv-title'); title.textContent = config.label;
  const plot = document.createElement('div'); plot.className = 'fsv-plot'; plot.tabIndex = 0;
  plot.setAttribute('aria-label', 'Signal plot. Drag to pan, wheel to zoom, Home to show all.');
  stage.replaceChildren(plot);
  const nav = document.createElement('input'); nav.type = 'range'; nav.className = 'fsv-navigation';
  nav.min = '0'; nav.max = '1'; nav.step = 'any'; nav.setAttribute('aria-label', 'Pan across the whole signal');
  nav.style.cssText = 'width:100%;accent-color:#aaa;margin:4px 0'; stage.appendChild(nav);
  const Plot = window.Plotly || window.parent?.Plotly;
  if (!Plot) { status.textContent = 'Signal: Field’s Plotly library is not loaded'; root.setAttribute('aria-busy','false'); return; }
  const bus = window.parent || window, group = config.syncGroup, instance = Math.random().toString(36).slice(2);
  const views = bus.__fieldSignalViews || (bus.__fieldSignalViews = new Map());
  const saved = config.viewKey && views.get(config.viewKey), total = config.end - config.start;
  const minSpan = Math.min(total, Math.max(total / 1e9, Number.EPSILON * Math.max(1,Math.abs(config.start),Math.abs(config.end))*16));
  let start = saved?.start ?? config.initialStart ?? config.start, span = saved?.span ?? config.initialDuration ?? total;
  let data = null, timer = 0, request = null, generation = 0, disposed = false, applying = 0, initialized = false;
  let rendering = Promise.resolve();
  const listeners = new AbortController(), clamp = (v,a,b) => Math.max(a,Math.min(b,v));
  const format = v => Number(v.toPrecision(7)) + ' s';
  const publish = (name,detail) => { if(group)bus.dispatchEvent(new bus.CustomEvent(name,{detail:{group,source:instance,...detail}})); };
  const on = (target,name,fn,options={}) => target.addEventListener(name,fn,{...options,signal:listeners.signal});
  // Plotly handles the target first; keep Field's enclosing box click/editor handlers out.
  for(const target of [plot,nav])for(const name of ['pointerdown','mousedown','click','touchstart'])
    on(target,name,event=>event.stopPropagation());
  const normalize = () => {
    span=clamp(span,minSpan,total); start=clamp(start,config.start,config.end-span);
    root.dataset.start=String(start); root.dataset.end=String(start+span);
    range.textContent=format(start)+' — '+format(start+span)+' / '+format(total);
    nav.disabled=span>=total; nav.value=String(total>span?(start-config.start)/(total-span):0);
    if(config.viewKey){views.delete(config.viewKey);views.set(config.viewKey,{start,span});while(views.size>64)views.delete(views.keys().next().value);}
  };
  const axesUpdate = () => Object.fromEntries(config.lanes.map((_,i)=>['xaxis'+(i ? i+1 : '')+'.range',[start,start+span]]));
  const layout = () => {
    const l={width:Math.max(200,stage.clientWidth),height:config.lanes.length*config.laneHeight+45,
      paper_bgcolor:'#080808',plot_bgcolor:'#080808',font:{family:'Iosevka, monospace',color:'#bbb',size:12},
      margin:{l:52,r:12,t:12,b:32},dragmode:'pan',hovermode:'x',showlegend:false,
      uirevision:config.viewKey||'signal',annotations:[]};
    const count=config.lanes.length;
    config.lanes.forEach((name,i)=>{
      const suffix=i ? i+1 : '', lane=data?.lanes[i];
      l['xaxis'+suffix]={range:[start,start+span],autorange:false,anchor:'y'+suffix,matches:i?'x':undefined,
        showticklabels:i===count-1,gridcolor:'#262626',zeroline:false,title:i===count-1?{text:'seconds',standoff:0}:undefined};
      l['yaxis'+suffix]={domain:[1-(i+1)/count+.055/count,1-i/count-.025/count],range:lane?[lane.minimum,lane.maximum]:[0,1],
        fixedrange:true,gridcolor:'#262626',zeroline:false,tickvals:lane?[lane.minimum,(lane.minimum+lane.maximum)/2,lane.maximum]:[0,.5,1]};
      l.annotations.push({xref:'paper',yref:'paper',x:0,y:1-(i+.5)/count,xanchor:'right',xshift:-34,text:name,showarrow:false});
    });return l;
  };
  const traces = () => {
    if(!data)return [];
    const result=[];
    data.lanes.forEach((lane,index)=>{
      const base={type:'scatter',mode:'lines',xaxis:'x'+(index ? index+1 : ''),yaxis:'y'+(index ? index+1 : ''),connectgaps:false};
      const add=(v,previous)=>{
        const n=v.low.length, color=previous?'#888':'#eee', name=lane.name+(previous?' · '+config.comparisonLabel:'');
        const hovertemplate='%{x:.6g} s<br>'+name.replaceAll('<','&lt;')+': %{y:.6g}<extra></extra>';
        if(data.mode==='waveform'){
          result.push({...base,name,x:v.low.map((_,i)=>data.start+(data.end-data.start)*i/(n-1)),y:v.low,
            line:{color,width:previous?1:1.5,dash:previous?'dash':'solid',simplify:false},hovertemplate});
        }else{
          const x=Array.from({length:n+1},(_,i)=>data.start+(data.end-data.start)*i/n);
          // Continuous filled extrema bands avoid a comb of separately rasterized vertical strokes.
          result.push({...base,name,x,y:[...v.low,v.low[n-1]],line:{color,width:.6,shape:'hv',dash:previous?'dash':'solid'},hovertemplate});
          result.push({...base,name,x,y:[...v.high,v.high[n-1]],line:{color,width:.6,shape:'hv',dash:previous?'dash':'solid'},
            fill:'tonexty',fillcolor:previous?'rgba(150,150,150,.12)':'rgba(235,235,235,.42)',hovertemplate});
        }
      };
      if(lane.comparison)add(lane.comparison,true);add(lane.current,false);
    });return result;
  };
  const draw = token => {
    rendering=rendering.then(async()=>{
      if(disposed||token!==generation)return;
      applying++;
      try {
        await Plot.react(plot,traces(),layout(),{displaylogo:false,displayModeBar:false,scrollZoom:false,responsive:false,doubleClick:false});
        if(!initialized){initialized=true;bindPlotEvents();}
      }finally{applying--;}
    });return rendering;
  };
  const schedule = (delay=65) => {
    clearTimeout(timer);request?.abort();const token=++generation;
    root.setAttribute('aria-busy','true');status.textContent='sampling…';
    timer=setTimeout(async()=>{
      const controller=new AbortController();request=controller;
      const deadline=setTimeout(()=>controller.abort('timeout'),15000);
      try{
        const url=new URL(config.url,document.baseURI),points=clamp(Math.round(stage.clientWidth-64),16,4096);
        url.search=new URLSearchParams({start:String(start),end:String(Math.min(config.end,start+span)),points:String(points)});
        const response=await fetch(url,{signal:controller.signal});
        if(!response.ok)throw Error((await response.text()).slice(0,180));
        const next=await response.json();if(disposed||token!==generation)return;
        data=next;await draw(token);if(disposed||token!==generation)return;
        root.dataset.mode=data.mode;root.dataset.spacing=String(data.sampleSpacing);
        status.textContent=data.mode+' · Δt '+format(data.sampleSpacing);
        title.textContent=config.label+(data.lanes.some(l=>l.comparison)?' / white: current · dashed grey: '+config.comparisonLabel:'');
      }catch(error){if(!disposed&&token===generation)status.textContent='Signal: '+(controller.signal.reason==='timeout'?'request timed out':error.message);}
      finally{clearTimeout(deadline);if(token===generation&&!disposed)root.setAttribute('aria-busy','false');if(request===controller)request=null;}
    },delay);
  };
  const changed = (broadcast=true, native=false) => {
    normalize();schedule();
    if(initialized&&!native){
      rendering=rendering.then(async()=>{if(disposed)return;applying++;try{await Plot.relayout(plot,axesUpdate());}finally{applying--;}});
    }
    if(broadcast)publish('field-time-window',{start,duration:span});
  };
  const zoom=(factor,anchor=.5)=>{const at=start+span*anchor;span=clamp(span*factor,minSpan,total);start=at-span*anchor;changed();};
  const reset=()=>{start=config.start;span=total;changed();};
  function bindPlotEvents(){
    plot.on('plotly_relayout',event=>{
      if(applying||disposed)return;
      const key=Object.keys(event).find(k=>/^xaxis\d*\.range\[0\]$/.test(k));
      const whole=Object.keys(event).find(k=>/^xaxis\d*\.range$/.test(k));
      const next=key?[event[key],event[key.replace('[0]','[1]')]]:whole?event[whole]:null;
      if(!next||!next.every(Number.isFinite)||next[1]<=next[0])return;
      start=next[0];span=next[1]-next[0];changed(true);
    });
    plot.on('plotly_hover',event=>{const p=event.points?.[0];if(!p)return;readout.textContent=format(p.x)+'  '+p.data.name+' ≈'+Number(p.y.toPrecision(6));publish('field-time-cursor',{time:p.x,active:true,kind:'hover'});});
    plot.on('plotly_unhover',()=>{readout.textContent='';publish('field-time-cursor',{active:false});});
  }
  on(plot,'wheel',event=>{
    event.preventDefault();event.stopPropagation();const rect=plot.getBoundingClientRect();
    const size=plot._fullLayout?._size,left=size?.l??52,width=size?.w??Math.max(1,rect.width-64);
    const unit=event.deltaMode===1?16:event.deltaMode===2?width:1;
    if(event.shiftKey||Math.abs(event.deltaX)>Math.abs(event.deltaY)){start+=(event.deltaX||event.deltaY)*unit/width*span;changed();}
    else zoom(Math.exp(clamp(event.deltaY*unit*.002,-2,2)),clamp((event.clientX-rect.left-left)/width,0,1));
  },{passive:false,capture:true});
  on(plot,'dblclick',event=>{event.preventDefault();reset();});
  on(plot,'keydown',event=>{
    if(event.key==='+'||event.key==='=')zoom(.5);else if(event.key==='-')zoom(2);else if(event.key==='Home'||event.key==='0')reset();
    else if(event.key==='ArrowLeft'||event.key==='ArrowRight'){start+=(event.key==='ArrowLeft'?-1:1)*span*.1;changed();}else return;
    event.preventDefault();event.stopPropagation();
  });
  on(nav,'input',()=>{start=config.start+Number(nav.value)*(total-span);changed();});
  on(root.querySelector('[data-action="in"]'),'click',()=>zoom(.5));
  on(root.querySelector('[data-action="out"]'),'click',()=>zoom(2));
  on(root.querySelector('[data-action="all"]'),'click',reset);
  on(bus,'field-time-window',event=>{const d=event.detail||{};if(!group||d.group!==group||d.source===instance||!Number.isFinite(d.start)||!Number.isFinite(d.duration)||d.duration<=0)return;start=d.start;span=d.duration;changed(false);});
  on(bus,'field-time-cursor',event=>{const d=event.detail||{};if(!group||d.group!==group||d.source===instance||!initialized)return;if(d.active===false||!Number.isFinite(d.time))Plot.Fx.unhover(plot);else Plot.Fx.hover(plot,{xval:d.time},config.lanes.map((_,i)=>i?'x'+(i+1)+'y'+(i+1):'xy'));});
  const resize=new ResizeObserver(()=>schedule(80));
  const dispose=()=>{if(disposed)return;disposed=true;listeners.abort();request?.abort();clearTimeout(timer);resize.disconnect();removal.disconnect();rendering.finally(()=>Plot.purge(plot));};
  const removal=new MutationObserver(()=>{if(!root.isConnected)dispose();});removal.observe(document,{childList:true,subtree:true});
  on(window,'pagehide',dispose);normalize();resize.observe(stage);schedule(0);
})();
