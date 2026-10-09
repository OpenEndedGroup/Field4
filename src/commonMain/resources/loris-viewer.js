/* Native irregular Loris breakpoints. Only the drawing is bounded; Kotlin retains all observations. */
(() => {
  const root = document.currentScript.closest('.field-loris-view');
  if (!root || root.dataset.ready) return;
  root.dataset.ready = 'true';
  const cfg = JSON.parse(root.querySelector('.lv-config').textContent), svg = root.querySelector('.lv-plot');
  const text = root.querySelector('.lv-readout'), H = cfg.height, left = 70, right = 980, top = 22, bottom = H - 32;
  root.querySelector('.lv-title').textContent = cfg.label;
  const shownBW=cfg.tracks.flatMap(t=>t.points.map(p=>p[3]));
  const bwMax=shownBW.reduce((a,b)=>Math.max(a,b),0);
  root.querySelector('.lv-count').textContent = `${cfg.tracks.length}/${cfg.totalTracks} tracks · ${cfg.selection.toLowerCase().replace('_',' ')} · max bandwidth ${bwMax.toFixed(3)}`;
  let bus; try { bus = window.parent || window; } catch (_) { bus = window; }
  const source = 'loris-' + Math.random().toString(36).slice(2), listeners = new AbortController();
  const on = (target, name, fn, opts = {}) => target.addEventListener(name, fn, {...opts, signal:listeners.signal});
  const emit = (name, detail) => { if (cfg.group) bus.dispatchEvent(new bus.CustomEvent(name, {detail:{group:cfg.group,source,...detail}})); };
  const clamp = (v,a,b) => Math.max(a, Math.min(b,v));
  const metric = f => cfg.axis === 'HZ' ? f : cfg.axis === 'ERB' ? 21.4 * Math.log10(1 + .00437 * f) : 1200 * Math.log2(f / 440);
  let lo = metric(cfg.minimumHz), hi = metric(cfg.maximumHz);
  let start = 0, span = cfg.duration, cursor = null, playhead = null, drag = null;
  const x = t => left + (t - start) / span * (right - left), y = f => bottom - (metric(f) - lo) / (hi - lo) * (bottom - top);
  const publishWindow = () => emit('field-time-window', {start,duration:span});
  const setWindow = (s,d,broadcast=true) => { span=clamp(d, Math.min(.005,cfg.duration),cfg.duration); start=clamp(s,0,cfg.duration-span); draw(); if(broadcast)publishWindow(); };
  const marker = (name,t,color) => `<line class="${name}" x1="${x(t ?? -1)}" x2="${x(t ?? -1)}" y1="${top}" y2="${bottom}" stroke="${color}" stroke-width="1.5" style="display:${t!==null && t>=start && t<=start+span?'':'none'}"/>`;
  const place = () => { for(const [name,t] of [['lv-cursor',cursor],['lv-playhead',playhead]]) { const el=svg.querySelector('.'+name);if(!el)continue;el.setAttribute('x1',x(t??-1));el.setAttribute('x2',x(t??-1));el.style.display=t!==null&&t>=start&&t<=start+span?'':'none'; } };
  const draw = () => {
    let html = `<defs><clipPath id="${source}"><rect x="${left}" y="${top}" width="${right-left}" height="${bottom-top}"/></clipPath></defs>`;
    for(let i=0;i<=6;i++){const t=start+span*i/6,px=x(t);html+=`<line x1="${px}" x2="${px}" y1="${top}" y2="${bottom}" stroke="#263440"/><text x="${px}" y="${H-10}" text-anchor="middle" fill="#90a6b7" font-size="11">${t.toFixed(2)}s</text>`;}
    const ticks=[...new Set([0,20,40,80,160,320,640,1280,2560,4000,8000,16000,cfg.maximumHz])].sort((a,b)=>a-b);
    let previousTick=Infinity;
    for(const f of ticks.filter(f=>f>=cfg.minimumHz&&f<=cfg.maximumHz)){const py=y(f);if(Math.abs(py-previousTick)<22)continue;previousTick=py;html+=`<line x1="${left}" x2="${right}" y1="${py}" y2="${py}" stroke="#263440"/><text x="${left-9}" y="${py+4}" text-anchor="end" fill="#90a6b7" font-size="11">${f}</text>`;}
    html+=`<g clip-path="url(#${source})">`;
    const color=bw=>`rgb(${Math.round(83+172*bw)},${Math.round(194-30*bw)},${Math.round(229-154*bw)})`;
    for(const track of cfg.tracks){const points=track.points;
      const amp=Math.max(...points.map(p=>p[2])),alpha=clamp((20*Math.log10(Math.max(amp,1e-8))+65)/65,.28,1);
      // Quantize only the drawing palette. Keep local bandwidth rather than a whole-track mean.
      // Batch segments by colour so large models do not create one DOM element per breakpoint.
      const paths=new Map();
      for(let i=1;i<points.length;i++){const a=points[i-1],b=points[i],bin=Math.round((a[3]+b[3])*.5*15);
        const segment=`M${x(a[0]).toFixed(2)},${y(a[1]).toFixed(2)} L${x(b[0]).toFixed(2)},${y(b[1]).toFixed(2)} `;
        paths.set(bin,(paths.get(bin)||'')+segment);
      }
      for(const [bin,path] of paths)html+=`<path d="${path}" stroke="${color(bin/15)}" stroke-opacity="${alpha}" stroke-width="1.5" fill="none"/>`;
      if(points.length===1)html+=`<circle cx="${x(points[0][0])}" cy="${y(points[0][1])}" r="2" fill="${color(points[0][3])}"/>`;
    }
    html+='</g>'+marker('lv-cursor',cursor,'#efcf86')+marker('lv-playhead',playhead,'#f6f9fa');
    svg.innerHTML=html;
    root.dataset.start=String(start);root.dataset.end=String(start+span);root.dataset.rendered='true';
    text.textContent=`${start.toFixed(3)} — ${(start+span).toFixed(3)} s · ${cfg.minimumHz.toFixed(0)}–${cfg.maximumHz.toFixed(0)} Hz · ${cfg.axis.toLowerCase()} spacing · native timestamps (drawing may subsample)`;
  };
  const position = e => {const r=svg.getBoundingClientRect();return {t:clamp(start+((e.clientX-r.left)/r.width*1000-left)/(right-left)*span,start,start+span),py:(e.clientY-r.top)/r.height*H};};
  on(svg,'pointermove',e=>{const p=position(e);if(drag){if(Math.abs(e.clientX-drag.x)>3)drag.moved=true;setWindow(drag.start-(e.clientX-drag.x)/svg.getBoundingClientRect().width*1000/(right-left)*span,span);return;}
    cursor=p.t;place();emit('field-time-cursor',{time:p.t,kind:'hover',active:true});
    let nearest=null,best=Infinity;for(const track of cfg.tracks)for(const b of track.points){const d=Math.hypot((b[0]-p.t)/span*(right-left),y(b[1])-p.py);if(d<best){best=d;nearest={id:track.id,b};}}
    if(nearest&&best<30){const b=nearest.b;text.textContent=`track ${nearest.id} · ${b[0].toFixed(4)}s · ${b[1].toFixed(1)}Hz · ${(20*Math.log10(Math.max(b[2],1e-12))).toFixed(1)}dB · bandwidth ${b[3].toFixed(3)} · phase ${b[4].toFixed(2)}rad`;}
  });
  on(svg,'pointerleave',()=>{cursor=null;place();emit('field-time-cursor',{time:null,kind:'hover',active:false});});
  on(svg,'pointerdown',e=>{drag={x:e.clientX,start,moved:false};if(e.isTrusted)svg.setPointerCapture(e.pointerId);});
  on(svg,'pointerup',e=>{if(drag&&!drag.moved){playhead=position(e).t;emit('field-time-seek',{time:playhead});emit('field-time-cursor',{time:playhead,kind:'playhead',active:true});place();}drag=null;});
  on(svg,'pointercancel',()=>{drag=null;});
  on(svg,'wheel',e=>{e.preventDefault();const anchor=playhead!==null&&playhead>=start&&playhead<=start+span?playhead:position(e).t;const ratio=(anchor-start)/span,next=span*Math.exp(clamp(e.deltaY,-300,300)*.003);setWindow(anchor-ratio*next,next);},{passive:false});
  on(root.querySelector('.lv-all'),'click',()=>setWindow(0,cfg.duration));
  const frequencies=(minimum,maximum)=>{cfg.minimumHz=cfg.axis==='CENTS'?Math.max(20,minimum):minimum;cfg.maximumHz=maximum;lo=metric(cfg.minimumHz);hi=metric(cfg.maximumHz);draw();};
  root.querySelector('.lv-axis').value=cfg.axis;
  on(root.querySelector('.lv-axis'),'change',e=>{cfg.axis=e.target.value;frequencies(cfg.minimumHz,cfg.maximumHz);});
  on(root.querySelector('.lv-full'),'click',()=>frequencies(0,cfg.nyquistHz));
  on(root.querySelector('.lv-voice'),'click',()=>frequencies(80,Math.min(4000,cfg.nyquistHz)));
  on(bus,'field-time-window',e=>{const d=e.detail||{};if(d.group===cfg.group&&cfg.group&&d.source!==source&&Number.isFinite(d.start)&&Number.isFinite(d.duration)&&d.duration>0)setWindow(d.start,d.duration,false);});
  on(bus,'field-time-cursor',e=>{const d=e.detail||{};if(!cfg.group||d.group!==cfg.group||d.source===source)return;const t=d.active===false||d.time==null?null:Number(d.time);if(t!==null&&!Number.isFinite(t))return;if(d.kind==='playhead')playhead=t;else cursor=t;place();});
  const observer=new MutationObserver(()=>{if(!root.isConnected){listeners.abort();observer.disconnect();}});observer.observe(document.documentElement,{childList:true,subtree:true});
  draw();
})();
