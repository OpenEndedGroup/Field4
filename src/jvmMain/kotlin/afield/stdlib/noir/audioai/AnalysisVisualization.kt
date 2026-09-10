@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Analysis viewers and dashboards for beat, pitch, and note results, with zoom, pan, and a shared time cursor.
 *
 * [analysisVisualizationHtml] turns an [AnalysisResult] into a zoomable interactive display; the
 * browser decides which of the three layouts to draw from [AnalysisPreview.operation]: onset
 * strength and multi-scale tempo for a beat analysis, pYIN frequency and voicing probability for
 * a monophonic pitch analysis, or a Basic Pitch piano roll with activation curves for a
 * polyphonic one. [analysisPreviewVisualizationHtml] takes an already-fetched [AnalysisPreview]
 * and works without any service round trip.
 *
 * Dashboard variants ([analysisDashboardHtml], [analysisPreviewDashboardHtml]) stack panels with
 * a synchronized cursor, making beat/pYIN/Basic Pitch disagreements inspectable side by side.
 * All panels support wheel zoom, drag pan, hover inspection, and click-to-seek, synchronized
 * across panels that share a `syncGroup`.
 */

private val analysisVisualizationJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * The analysis viewer: one function that turns an [AnalysisResult] into a zoomable beat, pitch or
 * note display for a box output.
 *
 * It draws whichever of the three analyses the result came from, deciding in the browser from
 * [AnalysisPreview.operation]. What crosses the wire is the downsampled preview and never the
 * `.npz`, which is what keeps this cheap on a long recording — and the reason it is a display and
 * not a measurement.
 */
fun analysisVisualizationHtml(
    result: AnalysisResult,
    serviceBaseUrl: String = "/field-audio",
    width: Int = 1100,
    height: Int = 360,
    points: Int = 1600,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String = analysisVisualizationShell(
    label = result.data.role.removePrefix("analysis_").replace('_', ' '),
    dataUrl = "${serviceBaseUrl.trimEnd('/')}/v1/analysis-results/${result.id}/preview?points=$points",
    embeddedPreview = null,
    width = width,
    height = height,
    points = points,
    syncGroup = syncGroup,
    timeOffsetSeconds = timeOffsetSeconds,
)

/** Render a deterministic or already-fetched preview without contacting the service. */
fun analysisPreviewVisualizationHtml(
    preview: AnalysisPreview,
    width: Int = 1100,
    height: Int = 360,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String = analysisVisualizationShell(
    label = preview.operation.removePrefix("analysis.").replace('_', ' '),
    dataUrl = null,
    embeddedPreview = analysisVisualizationJson.encodeToString(preview),
    width = width,
    height = height,
    points = (preview.series.maxOfOrNull { it.values.size } ?: 16).coerceIn(16, 4096),
    syncGroup = syncGroup,
    timeOffsetSeconds = timeOffsetSeconds,
)

/**
 * Stack several live results into one board. Zoom, pan and hover cursors are
 * synchronized, which makes beat/pYIN/Basic Pitch disagreements inspectable.
 */
fun analysisDashboardHtml(
    results: List<AnalysisResult>,
    serviceBaseUrl: String = "/field-audio",
    width: Int = 1100,
    panelHeight: Int = 270,
    points: Int = 1600,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String {
    require(results.isNotEmpty()) { "analysis dashboard needs at least one result" }
    val group = syncGroup ?: analysisSyncGroup(results.map { it.id })
    return analysisDashboardShell(
        results.map {
            analysisVisualizationHtml(it, serviceBaseUrl, width, panelHeight, points, group, timeOffsetSeconds)
        },
        width,
    )
}

/** Model-free counterpart used by documentation, fixtures and cached previews. */
fun analysisPreviewDashboardHtml(
    previews: List<AnalysisPreview>,
    width: Int = 1100,
    panelHeight: Int = 270,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String {
    require(previews.isNotEmpty()) { "analysis dashboard needs at least one preview" }
    val group = syncGroup ?: analysisSyncGroup(previews.mapIndexed { index, preview -> "$index:${preview.operation}" })
    return analysisDashboardShell(
        previews.map { analysisPreviewVisualizationHtml(it, width, panelHeight, group, timeOffsetSeconds) },
        width,
    )
}

private fun analysisDashboardShell(panels: List<String>, width: Int): String = """
<section class="field-analysis-dashboard" data-afield-intrinsic-width="$width">
  <style>
    .field-analysis-dashboard { width:${width}px;max-width:100%;display:grid;gap:12px }
    .field-analysis-dashboard > header { color:#aabac8;font:12px/1.4 system-ui,sans-serif;
      display:flex;justify-content:space-between;padding:0 2px }
  </style>
  <header><b>analysis board</b><span>shared playhead/cursor · click seek · wheel zoom · drag pan</span></header>
  ${panels.joinToString("\n")}
</section>
""".trimIndent()

private fun analysisSyncGroup(ids: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ids.joinToString("|").toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
    return "field-analysis-$digest"
}

private fun analysisVisualizationShell(
    label: String,
    dataUrl: String?,
    embeddedPreview: String?,
    width: Int,
    height: Int,
    points: Int,
    syncGroup: String?,
    timeOffsetSeconds: Double,
): String {
    require(width >= 320) { "analysis visualization width must be at least 320" }
    require(height >= 180) { "analysis visualization height must be at least 180" }
    require(points in 16..4096) { "analysis visualization points must be between 16 and 4096" }
    require(timeOffsetSeconds.isFinite()) { "analysis time offset must be finite" }
    val urlAttribute = dataUrl?.let { " data-url=\"${it.htmlAttributeEscaped()}\"" }.orEmpty()
    val syncAttribute = syncGroup?.let { " data-sync=\"${it.htmlAttributeEscaped()}\"" }.orEmpty()
    val embedded = embeddedPreview?.replace("<", "\\u003c")?.let {
        "<script type=\"application/json\" class=\"analysis-preview-data\">$it</script>"
    }.orEmpty()
    return """
<section class="field-analysis-view"$urlAttribute$syncAttribute data-time-offset="$timeOffsetSeconds" data-afield-intrinsic-width="$width">
  <style>
    .field-analysis-view { width:${width}px; max-width:100%; color:#d8e2eb; background:#11161d;
      border:1px solid #29343e; border-radius:8px; padding:13px; font:12px/1.35 system-ui,sans-serif;box-sizing:border-box }
    .field-analysis-view header { display:flex; align-items:baseline; gap:10px; margin-bottom:8px }
    .field-analysis-view h3 { margin:0; font-size:15px; font-weight:590; color:#e2ebf2 }
    .field-analysis-view .meta,.field-analysis-view .readout,.field-analysis-view .legend { color:#91a4b5 }
    .field-analysis-view .plot { position:relative; height:${height}px; background:#080c11;
      border:1px solid #25313b; overflow:hidden; cursor:crosshair }
    .field-analysis-view canvas { display:block; width:100%; height:100% }
    .field-analysis-view .readout { min-height:18px; margin-top:6px; font-family:ui-monospace,monospace }
    .field-analysis-view .legend { margin-left:auto;font-size:11px;white-space:nowrap }
    .field-analysis-view .error { color:#e99a9a }
    .field-analysis-view .spinner { width:11px;height:11px;border:2px solid #344652;border-top-color:#79c6d4;
      border-radius:50%;animation:field-analysis-spin .75s linear infinite }
    @keyframes field-analysis-spin { to { transform:rotate(360deg) } }
  </style>
  <header><h3>${label.htmlTextEscaped()}</h3><span class="spinner"></span><span class="meta">loading preview…</span><span class="legend"></span></header>
  <div class="plot"><canvas></canvas></div>
  <div class="readout">hover to inspect · click to seek linked audio · wheel to zoom · drag to pan</div>
  $embedded
  <script>
  (() => {
    const root=document.currentScript.closest('.field-analysis-view'), canvas=root.querySelector('canvas'),
      plot=root.querySelector('.plot'), meta=root.querySelector('.meta'), legend=root.querySelector('.legend'),
      spinner=root.querySelector('.spinner'),readout=root.querySelector('.readout'),context=canvas.getContext('2d'),
      instance=Math.random().toString(36).slice(2),sync=root.dataset.sync||'',timeOffset=Number(root.dataset.timeOffset)||0;
    const timeBus=(()=>{try{return window.parent&&window.parent!==window?window.parent:window}catch(_ignored){return window}})();
    let data=null,start=0,duration=1,dragging=null,hover=null,cursorKind='hover';
    const colors=['#61c8d9','#e7b46a','#8ccf91','#c390d4','#d76d78','#78aee8'];
    const finite=a=>a.filter(v=>v!=null&&Number.isFinite(v));
    const bounds=(a,fallback=[0,1])=>{const v=finite(a);return v.length?[Math.min(...v),Math.max(...v)]:fallback};
    const resize=()=>{ const r=plot.getBoundingClientRect(), d=devicePixelRatio||1;
      canvas.width=Math.max(1,Math.round(r.width*d));canvas.height=Math.max(1,Math.round(r.height*d));
      context.setTransform(d,0,0,d,0,0);draw();};
    const xOf=t=>(t-start)/duration*plot.clientWidth,visible=t=>t>=start&&t<=start+duration;
    const series=name=>data.series.find(s=>s.name===name);
    const line=(item,mapY,color,alpha=1,width=1.35)=>{if(!item)return;context.strokeStyle=color;context.globalAlpha=alpha;
      context.lineWidth=width;context.beginPath();let begun=false;for(let i=0;i<item.values.length;i++){
        const t=item.times_seconds[i],v=item.values[i];if(t==null||v==null||!visible(t)){begun=false;continue}
        const x=xOf(t),y=mapY(v);if(!begun){context.moveTo(x,y);begun=true}else context.lineTo(x,y)}context.stroke();context.globalAlpha=1};
    const lane=(top,bottom,label)=>{context.fillStyle='rgba(21,30,39,.72)';context.fillRect(0,top,plot.clientWidth,bottom-top);
      context.fillStyle='#718494';context.font='10px ui-monospace,monospace';context.fillText(label,7,top+13)};
    const grid=()=>{const w=plot.clientWidth,h=plot.clientHeight;context.strokeStyle='#18232c';context.lineWidth=1;
      for(let i=0;i<=8;i++){const x=i*w/8;context.beginPath();context.moveTo(x,0);context.lineTo(x,h);context.stroke();
        const t=start+i*duration/8;context.fillStyle='#647888';context.font='9px ui-monospace,monospace';context.fillText(t.toFixed(duration<5?2:1)+'s',x+3,h-5)}};
    const nearest=(item,t)=>{if(!item||!item.times_seconds.length)return null;let best=0,dist=Infinity;
      item.times_seconds.forEach((x,i)=>{if(x!=null&&Math.abs(x-t)<dist){dist=Math.abs(x-t);best=i}});return item.values[best]};
    const updateReadout=t=>{if(!data)return;const values=data.series.map(s=>{const v=nearest(s,t);return v==null?'':s.label+' '+Number(v).toFixed(s.unit==='bpm'?1:2)+(s.unit?' '+s.unit:'')}).filter(Boolean);
      readout.textContent='time '+t.toFixed(3)+' s'+(values.length?' · '+values.join(' · '):'')};
    const draw=()=>{if(!data||!canvas.width)return;const w=plot.clientWidth,h=plot.clientHeight;context.clearRect(0,0,w,h);context.fillStyle='#080c11';context.fillRect(0,0,w,h);
      if(data.operation==='analysis.beats'){
        const onset=series('onset_strength'),tempos=data.series.filter(s=>s.name.includes('_bpm')),conf=data.series.filter(s=>s.name.includes('_confidence'));
        lane(0,h*.32,'onset');lane(h*.33,h*.72,'local tempo');lane(h*.73,h,'tempo confidence');grid();
        const oMax=Math.max(1e-9,...finite(onset?onset.values:[]));line(onset,v=>h*.30-v/oMax*h*.23,colors[0]);
        const tb=bounds(tempos.flatMap(s=>s.values),[30,300]),lo=tb[0],hi=tb[1];tempos.forEach((s,i)=>line(s,v=>h*.69-(v-lo)/Math.max(1,hi-lo)*h*.30,colors[(i+1)%colors.length]));
        conf.forEach((s,i)=>line(s,v=>h*.96-v*h*.20,colors[(i+1)%colors.length],.75));
        context.strokeStyle='rgba(255,235,175,.42)';for(const t of data.beat_times_seconds)if(visible(t)){const x=xOf(t);context.beginPath();context.moveTo(x,0);context.lineTo(x,h);context.stroke()}
        legend.textContent='cyan onset · coloured scales · gold beats';
      }else if(data.operation==='analysis.pitch_mono'){
        const f=series('frequency_hz'),voice=series('voiced_probability'),midi=finite(f?f.values:[]).filter(v=>v>0).map(v=>69+12*Math.log2(v/440)),pb=bounds(midi,[48,72]);
        const lo=Math.floor(pb[0]-1),hi=Math.ceil(pb[1]+1);lane(0,h*.78,'pYIN pitch');lane(h*.79,h,'voiced probability');grid();
        for(let note=Math.ceil(lo/12)*12;note<hi;note+=12){const y=h*.75-(note-lo)/Math.max(1,hi-lo)*h*.69;context.strokeStyle='rgba(97,200,217,.16)';context.beginPath();context.moveTo(0,y);context.lineTo(w,y);context.stroke();context.fillStyle='#61798a';context.fillText('C'+(note/12-1),7,y-3)}
        line(f,v=>h*.75-(69+12*Math.log2(v/440)-lo)/Math.max(1,hi-lo)*h*.69,colors[0],1,1.7);line(voice,v=>h*.97-v*h*.15,colors[2]);legend.textContent='cyan F0 · green voicing';
      }else{
        const notes=data.notes.filter(n=>n.end_seconds>=start&&n.start_seconds<=start+duration),maxA=series('maximum_note_activation'),count=series('active_pitch_count'),nb=notes.length?[Math.min(...notes.map(n=>n.midi)),Math.max(...notes.map(n=>n.midi))]:[48,72],lo=nb[0]-1,hi=nb[1]+1;
        lane(0,h*.72,'Basic Pitch piano roll');lane(h*.73,h,'activation / active pitches');grid();
        for(const n of notes){const x=xOf(n.start_seconds),x2=xOf(n.end_seconds),y=h*.69-(n.midi-lo)/Math.max(2,hi-lo)*h*.64;context.globalAlpha=.25+.75*n.amplitude;context.fillStyle=colors[n.midi%colors.length];context.fillRect(x,y-3,Math.max(1,x2-x),6)}context.globalAlpha=1;
        line(maxA,v=>h*.96-v*h*.19,colors[0]);const cb=bounds(count?count.values:[],[0,1]);line(count,v=>h*.96-(v-cb[0])/Math.max(1,cb[1]-cb[0])*h*.19,colors[1],.9);legend.textContent='notes · cyan activation · gold active count';
      }
      if(hover!=null){context.strokeStyle=cursorKind==='playhead'?'rgba(255,223,115,.9)':'rgba(255,255,255,.72)';context.lineWidth=cursorKind==='playhead'?1.7:1;context.beginPath();context.moveTo(hover,0);context.lineTo(hover,h);context.stroke()}}
    const publish=(name,detail)=>{if(sync)timeBus.dispatchEvent(new timeBus.CustomEvent(name,{detail:{group:sync,source:instance,...detail}}))};
    const announceWindow=()=>{if(sync)publish('field-time-window',{start:start+timeOffset,duration})};
    const announceCursor=(t,kind='hover',active=t!=null)=>{if(sync)publish('field-time-cursor',{time:t==null?null:t+timeOffset,kind,active})};
    const announceSeek=t=>publish('field-time-seek',{time:t+timeOffset});
    const setWindow=(s,d,announce=true)=>{duration=Math.max(.1,Math.min(data.duration_seconds,d));start=Math.max(0,Math.min(Math.max(0,data.duration_seconds-duration),s));draw();if(announce)announceWindow()};
    plot.addEventListener('wheel',event=>{if(!data)return;event.preventDefault();const r=plot.getBoundingClientRect(),at=start+(event.clientX-r.left)/r.width*duration,factor=Math.exp(event.deltaY*.001),next=Math.max(.1,Math.min(data.duration_seconds,duration*factor));setWindow(at-(at-start)*next/duration,next)},{passive:false});
    plot.addEventListener('pointerdown',event=>{dragging={x:event.clientX,start,moved:false};plot.setPointerCapture(event.pointerId)});
    plot.addEventListener('pointermove',event=>{const r=plot.getBoundingClientRect();hover=Math.max(0,Math.min(r.width,event.clientX-r.left));cursorKind='hover';if(dragging){const dx=event.clientX-dragging.x;if(Math.abs(dx)>3)dragging.moved=true;if(dragging.moved)setWindow(dragging.start-dx/r.width*duration,duration)}const t=start+hover/r.width*duration;updateReadout(t);announceCursor(t);draw()});
    plot.addEventListener('pointerup',event=>{if(!dragging)return;const r=plot.getBoundingClientRect(),t=start+Math.max(0,Math.min(r.width,event.clientX-r.left))/r.width*duration;if(!dragging.moved){cursorKind='playhead';announceSeek(t);announceCursor(t,'playhead');updateReadout(t)}dragging=null;draw()});
    plot.addEventListener('pointerleave',()=>{hover=null;dragging=null;draw();announceCursor(null)});plot.addEventListener('dblclick',()=>setWindow(0,data.duration_seconds));
    window.addEventListener('field-analysis-window',event=>{const d=event.detail;if(data&&d.sync===sync&&d.instance!==instance)setWindow(d.start,d.duration,false)});
    window.addEventListener('field-analysis-cursor',event=>{const d=event.detail;if(data&&d.sync===sync&&d.instance!==instance){hover=d.time==null?null:xOf(d.time);cursorKind='hover';if(d.time!=null)updateReadout(d.time);draw()}});
    timeBus.addEventListener('field-time-window',event=>{const d=event.detail||{};if(data&&d.group===sync&&d.source!==instance)setWindow(Number(d.start)-timeOffset,Number(d.duration),false)});
    timeBus.addEventListener('field-time-cursor',event=>{const d=event.detail||{};if(data&&d.group===sync&&d.source!==instance){const t=d.active===false||d.time==null?null:Number(d.time)-timeOffset;hover=t==null?null:xOf(t);cursorKind=d.kind||'hover';if(t!=null)updateReadout(t);draw()}});
    new ResizeObserver(resize).observe(plot);
    const finish=value=>{data=value;duration=Math.max(.1,data.duration_seconds);spinner.remove();meta.textContent=data.operation+' · '+data.duration_seconds.toFixed(2)+' s';resize()};
    const embedded=root.querySelector('.analysis-preview-data');if(embedded){try{finish(JSON.parse(embedded.textContent))}catch(error){meta.className='meta error';meta.textContent='invalid embedded preview: '+error.message}}
    else fetch(root.dataset.url,{cache:'no-store'}).then(r=>{if(!r.ok)throw new Error(r.status+' '+r.statusText);return r.json()}).then(finish).catch(error=>{spinner.remove();meta.className='meta error';meta.textContent='unable to load analysis: '+error.message});
  })();
  </script>
</section>
""".trimIndent()
}

private fun String.htmlTextEscaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun String.htmlAttributeEscaped(): String = htmlTextEscaped().replace("\"", "&quot;")
