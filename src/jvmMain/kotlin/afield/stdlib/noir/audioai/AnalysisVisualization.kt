@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

/**
 * The analysis viewer: one function that turns an [AnalysisResult] into a zoomable beat, pitch or
 * note display for a box output.
 *
 * It draws whichever of the three analyses the result came from, deciding in the browser from
 * [AnalysisPreview.operation]. What crosses the wire is the downsampled preview and never the
 * `.npz`, which is what keeps this cheap on a long recording — and the reason it is a display and
 * not a measurement.
 */

/**
 * A compact, range-aware beat/pitch viewer. The browser fetches a carefully
 * downsampled JSON preview; full NPZ tensors and MIDI remain immutable assets.
 */
fun analysisVisualizationHtml(
    result: AnalysisResult,
    serviceBaseUrl: String = "/field-audio",
    width: Int = 1100,
    height: Int = 360,
    points: Int = 1600,
): String {
    require(width >= 320) { "analysis visualization width must be at least 320" }
    require(height >= 180) { "analysis visualization height must be at least 180" }
    require(points in 16..4096) { "analysis visualization points must be between 16 and 4096" }
    val base = serviceBaseUrl.trimEnd('/').htmlAttributeEscaped()
    val id = result.id.htmlAttributeEscaped()
    val label = result.data.role.removePrefix("analysis_").replace('_', ' ').htmlTextEscaped()
    return """
<section class="field-analysis-view" data-url="$base/v1/analysis-results/$id/preview?points=$points">
  <style>
    .field-analysis-view { width:${width}px; max-width:100%; color:#d8e2eb; background:#11161d;
      border:1px solid #29343e; border-radius:8px; padding:13px; font:12px/1.35 system-ui,sans-serif }
    .field-analysis-view header { display:flex; align-items:baseline; gap:10px; margin-bottom:8px }
    .field-analysis-view h3 { margin:0; font-size:15px; font-weight:590; color:#e2ebf2 }
    .field-analysis-view .meta,.field-analysis-view .readout { color:#91a4b5 }
    .field-analysis-view .plot { position:relative; height:${height}px; background:#080c11;
      border:1px solid #25313b; overflow:hidden; cursor:crosshair }
    .field-analysis-view canvas { display:block; width:100%; height:100% }
    .field-analysis-view .readout { min-height:18px; margin-top:6px; font-family:ui-monospace,monospace }
    .field-analysis-view .error { color:#e99a9a }
  </style>
  <header><h3>$label</h3><span class="meta">loading…</span></header>
  <div class="plot"><canvas></canvas></div>
  <div class="readout">wheel to zoom · drag to pan · double-click to reset</div>
  <script>
  (() => {
    const root=document.currentScript.closest('.field-analysis-view'), canvas=root.querySelector('canvas'),
      plot=root.querySelector('.plot'), meta=root.querySelector('.meta'), readout=root.querySelector('.readout'),
      context=canvas.getContext('2d');
    let data=null, start=0, duration=1, dragging=false, dragX=0, dragStart=0, hover=null;
    const colors=['#61c8d9','#e7b46a','#8ccf91','#c390d4','#d76d78','#78aee8'];
    const resize=()=>{ const r=plot.getBoundingClientRect(), d=devicePixelRatio||1;
      canvas.width=Math.max(1,Math.round(r.width*d)); canvas.height=Math.max(1,Math.round(r.height*d));
      context.setTransform(d,0,0,d,0,0); draw(); };
    const xOf=t=>(t-start)/duration*plot.clientWidth;
    const visible=t=>t>=start&&t<=start+duration;
    const series=name=>data.series.find(s=>s.name===name);
    const line=(item, mapY, color, alpha=1)=>{ if(!item)return; context.strokeStyle=color;
      context.globalAlpha=alpha; context.lineWidth=1.35; context.beginPath(); let begun=false;
      for(let i=0;i<item.values.length;i++){ const t=item.times_seconds[i], v=item.values[i];
        if(t==null||v==null||!visible(t))continue; const x=xOf(t), y=mapY(v);
        if(!begun){context.moveTo(x,y);begun=true}else context.lineTo(x,y); } context.stroke(); context.globalAlpha=1; };
    const draw=()=>{ if(!data||!canvas.width)return; const w=plot.clientWidth,h=plot.clientHeight;
      context.clearRect(0,0,w,h); context.fillStyle='#080c11'; context.fillRect(0,0,w,h);
      context.strokeStyle='#18232c'; context.lineWidth=1;
      for(let i=1;i<8;i++){const x=i*w/8;context.beginPath();context.moveTo(x,0);context.lineTo(x,h);context.stroke();}
      if(data.operation==='analysis.beats'){
        const onset=series('onset_strength'), max=Math.max(1e-9,...onset.values.filter(v=>v!=null));
        line(onset,v=>h-8-v/max*h*.42,colors[0],.9);
        const tempos=data.series.filter(s=>s.name.includes('_bpm'));
        const values=tempos.flatMap(s=>s.values).filter(v=>v!=null), lo=Math.min(...values), hi=Math.max(...values);
        tempos.forEach((s,i)=>line(s,v=>h*.53-(v-lo)/Math.max(1,hi-lo)*h*.45,colors[(i+1)%colors.length],.9));
        context.strokeStyle='rgba(255,235,175,.48)'; for(const t of data.beat_times_seconds)if(visible(t)){
          const x=xOf(t);context.beginPath();context.moveTo(x,0);context.lineTo(x,h);context.stroke();}
      } else if(data.operation==='analysis.pitch_mono'){
        const f=series('frequency_hz'), midi=f.values.filter(v=>v>0).map(v=>69+12*Math.log2(v/440));
        const lo=Math.floor(Math.min(...midi)-1),hi=Math.ceil(Math.max(...midi)+1);
        line(f,v=>h-8-(69+12*Math.log2(v/440)-lo)/Math.max(1,hi-lo)*(h-16),colors[0]);
      } else {
        const notes=data.notes.filter(n=>n.end_seconds>=start&&n.start_seconds<=start+duration);
        const lo=Math.min(127,...notes.map(n=>n.midi)), hi=Math.max(0,...notes.map(n=>n.midi));
        for(const n of notes){const x=xOf(n.start_seconds), x2=xOf(n.end_seconds),
          y=h-8-(n.midi-lo+1)/Math.max(2,hi-lo+2)*(h-16); context.globalAlpha=.25+.75*n.amplitude;
          context.fillStyle=colors[n.midi%colors.length]; context.fillRect(x,y-3,Math.max(1,x2-x),6);}
        context.globalAlpha=1;
      }
      if(hover!=null){context.strokeStyle='rgba(255,255,255,.7)';context.beginPath();context.moveTo(hover,0);context.lineTo(hover,h);context.stroke();}
    };
    plot.addEventListener('wheel',event=>{if(!data)return;event.preventDefault();const r=plot.getBoundingClientRect(),
      at=start+(event.clientX-r.left)/r.width*duration, factor=Math.exp(event.deltaY*.001),
      next=Math.max(.1,Math.min(data.duration_seconds,duration*factor)); start=Math.max(0,Math.min(data.duration_seconds-next,at-(at-start)*next/duration));duration=next;draw();},{passive:false});
    plot.addEventListener('pointerdown',event=>{dragging=true;dragX=event.clientX;dragStart=start;plot.setPointerCapture(event.pointerId)});
    plot.addEventListener('pointermove',event=>{const r=plot.getBoundingClientRect();hover=event.clientX-r.left;
      if(dragging){start=Math.max(0,Math.min(data.duration_seconds-duration,dragStart-(event.clientX-dragX)/r.width*duration));}
      const t=start+hover/r.width*duration;readout.textContent='time '+t.toFixed(3)+' s';draw();});
    plot.addEventListener('pointerup',()=>dragging=false); plot.addEventListener('pointerleave',()=>{hover=null;dragging=false;draw()});
    plot.addEventListener('dblclick',()=>{start=0;duration=data.duration_seconds;draw()});
    new ResizeObserver(resize).observe(plot);
    fetch(root.dataset.url,{cache:'no-store'}).then(r=>{if(!r.ok)throw new Error(r.status+' '+r.statusText);return r.json()})
      .then(value=>{data=value;duration=Math.max(.1,data.duration_seconds);meta.textContent=data.operation+' · '+data.duration_seconds.toFixed(2)+' s';resize();})
      .catch(error=>{meta.className='meta error';meta.textContent='unable to load analysis: '+error.message});
  })();
  </script>
</section>
""".trimIndent()
}

private fun String.htmlTextEscaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun String.htmlAttributeEscaped(): String = htmlTextEscaped().replace("\"", "&quot;")
