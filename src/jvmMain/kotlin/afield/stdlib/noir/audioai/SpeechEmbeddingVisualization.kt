@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private val speechVisualizationJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Draw a speech result as two synchronized phase portraits: MDS axes 0/1 and 2/3.
 *
 * The moving marker follows any [AudioVisualizer][afield.stdlib.AudioVisualizer] with the same
 * [syncGroup]. Hovering either portrait previews the corresponding audio time and clicking seeks.
 * The strip underneath keeps voice activity, energy, and embedding novelty visible while auditioning.
 */
fun speechEmbeddingVisualizationHtml(
    result: AnalysisResult,
    serviceBaseUrl: String = "/field-audio",
    width: Int = 1100,
    phaseHeight: Int = 300,
    points: Int = 1800,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String = speechEmbeddingVisualizationShell(
    label = result.analysis["backend"]?.toString()?.trim('"')
        ?: result.data.role.removePrefix("analysis_").replace('_', ' '),
    dataUrl = "${serviceBaseUrl.trimEnd('/')}/v1/analysis-results/${result.id}/preview?points=$points",
    embeddedPreview = null,
    width = width,
    phaseHeight = phaseHeight,
    points = points,
    syncGroup = syncGroup,
    timeOffsetSeconds = timeOffsetSeconds,
)

/** Model-free counterpart for cached previews, documentation, and hand-built trajectories. */
fun speechEmbeddingPreviewVisualizationHtml(
    preview: AnalysisPreview,
    width: Int = 1100,
    phaseHeight: Int = 300,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String = speechEmbeddingVisualizationShell(
    label = preview.analysis["backend"]?.toString()?.trim('"') ?: "speech embedding",
    dataUrl = null,
    embeddedPreview = speechVisualizationJson.encodeToString(preview),
    width = width,
    phaseHeight = phaseHeight,
    points = (preview.series.maxOfOrNull { it.values.size } ?: 16).coerceIn(16, 4096),
    syncGroup = syncGroup,
    timeOffsetSeconds = timeOffsetSeconds,
)

/** Stack learned speech spaces while preserving one shared audio cursor. */
fun speechEmbeddingDashboardHtml(
    results: List<AnalysisResult>,
    serviceBaseUrl: String = "/field-audio",
    width: Int = 1100,
    phaseHeight: Int = 280,
    points: Int = 1800,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String {
    require(results.isNotEmpty()) { "speech embedding dashboard needs at least one result" }
    val group = syncGroup ?: speechSyncGroup(results.map { it.id })
    return speechEmbeddingDashboardShell(
        results.map {
            speechEmbeddingVisualizationHtml(
                it, serviceBaseUrl, width, phaseHeight, points, group, timeOffsetSeconds,
            )
        },
        width,
    )
}

/** Stack already-fetched speech previews without contacting the service. */
fun speechEmbeddingPreviewDashboardHtml(
    previews: List<AnalysisPreview>,
    width: Int = 1100,
    phaseHeight: Int = 280,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String {
    require(previews.isNotEmpty()) { "speech embedding dashboard needs at least one preview" }
    val group = syncGroup ?: speechSyncGroup(
        previews.mapIndexed { index, preview -> "$index:${preview.operation}" },
    )
    return speechEmbeddingDashboardShell(
        previews.map {
            speechEmbeddingPreviewVisualizationHtml(it, width, phaseHeight, group, timeOffsetSeconds)
        },
        width,
    )
}

private fun speechEmbeddingDashboardShell(panels: List<String>, width: Int): String = """
<section class="field-speech-dashboard" data-afield-intrinsic-width="$width">
  <style>
    .field-speech-dashboard { width:${width}px;max-width:100%;display:grid;gap:12px }
    .field-speech-dashboard > header { color:#aabac8;font:12px/1.4 system-ui,sans-serif;
      display:flex;justify-content:space-between;padding:0 2px }
  </style>
  <header><b>learned speech spaces</b><span>shared audio playhead · hover audition · click seek</span></header>
  ${panels.joinToString("\n")}
</section>
""".trimIndent()

private fun speechSyncGroup(ids: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ids.joinToString("|").toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
    return "field-speech-$digest"
}

private fun speechEmbeddingVisualizationShell(
    label: String,
    dataUrl: String?,
    embeddedPreview: String?,
    width: Int,
    phaseHeight: Int,
    points: Int,
    syncGroup: String?,
    timeOffsetSeconds: Double,
): String {
    require(width >= 560) { "speech embedding visualization width must be at least 560" }
    require(phaseHeight >= 180) { "speech embedding phase height must be at least 180" }
    require(points in 16..4096) { "speech embedding visualization points must be between 16 and 4096" }
    require(timeOffsetSeconds.isFinite()) { "speech embedding time offset must be finite" }
    val urlAttribute = dataUrl?.let { " data-url=\"${it.speechHtmlAttributeEscaped()}\"" }.orEmpty()
    val syncAttribute = syncGroup?.let { " data-sync=\"${it.speechHtmlAttributeEscaped()}\"" }.orEmpty()
    val embedded = embeddedPreview?.replace("<", "\\u003c")?.let {
        "<script type=\"application/json\" class=\"speech-preview-data\">$it</script>"
    }.orEmpty()
    return """
<section class="field-speech-view"$urlAttribute$syncAttribute data-time-offset="$timeOffsetSeconds" data-afield-intrinsic-width="$width">
  <style>
    .field-speech-view { width:${width}px;max-width:100%;box-sizing:border-box;color:#d8e2eb;background:#11161d;
      border:1px solid #29343e;border-radius:8px;padding:13px;font:12px/1.35 system-ui,sans-serif }
    .field-speech-view header { display:flex;align-items:baseline;gap:10px;margin-bottom:9px }
    .field-speech-view h3 { margin:0;font-size:15px;font-weight:590;color:#e2ebf2 }
    .field-speech-view .meta,.field-speech-view .readout,.field-speech-view .legend { color:#91a4b5 }
    .field-speech-view .legend { margin-left:auto;font-size:11px;white-space:nowrap }
    .field-speech-view .phase-grid { display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px }
    .field-speech-view .phase { min-width:0 }
    .field-speech-view .phase label { display:block;color:#8397a8;font:10px ui-monospace,monospace;margin:0 0 4px }
    .field-speech-view .phase-canvas { display:block;width:100%;height:${phaseHeight}px;background:#080c11;
      border:1px solid #25313b;box-sizing:border-box;cursor:crosshair }
    .field-speech-view .activity { display:block;width:100%;height:82px;margin-top:9px;background:#080c11;
      border:1px solid #25313b;box-sizing:border-box;cursor:crosshair }
    .field-speech-view .readout { min-height:18px;margin-top:6px;font-family:ui-monospace,monospace }
    .field-speech-view .error { color:#e99a9a }
    .field-speech-view .spinner { width:11px;height:11px;border:2px solid #344652;border-top-color:#79c6d4;
      border-radius:50%;animation:field-speech-spin .75s linear infinite }
    @keyframes field-speech-spin { to { transform:rotate(360deg) } }
    @media(max-width:720px) { .field-speech-view .phase-grid { grid-template-columns:1fr } }
  </style>
  <header><h3>${label.speechHtmlTextEscaped()}</h3><span class="spinner"></span><span class="meta">loading embedding preview…</span><span class="legend"></span></header>
  <div class="phase-grid">
    <div class="phase"><label>axis 0 × axis 1</label><canvas class="phase-canvas" data-x="0" data-y="1"></canvas></div>
    <div class="phase"><label>axis 2 × axis 3</label><canvas class="phase-canvas" data-x="2" data-y="3"></canvas></div>
  </div>
  <canvas class="activity"></canvas>
  <div class="readout">play linked audio, hover a trajectory to audition time, or click to seek</div>
  $embedded
  <script>
  (() => {
    const root=document.currentScript.closest('.field-speech-view'), phases=[...root.querySelectorAll('.phase-canvas')],
      activity=root.querySelector('.activity'),meta=root.querySelector('.meta'),legend=root.querySelector('.legend'),
      spinner=root.querySelector('.spinner'),readout=root.querySelector('.readout'),instance=Math.random().toString(36).slice(2),
      sync=root.dataset.sync||'',timeOffset=Number(root.dataset.timeOffset)||0;
    const timeBus=(()=>{try{return window.parent&&window.parent!==window?window.parent:window}catch(_ignored){return window}})();
    let data=null,currentTime=null,cursorKind='hover',visibleStart=0,visibleEnd=Infinity,plotStart=0,plotEnd=Infinity,axisPrefix='mds_',axisName='MDS';
    const colorAt=t=>'hsl('+(188+112*t)+', 63%, 66%)';
    const series=name=>data&&data.series.find(item=>item.name===name);
    const valid=value=>value!=null&&Number.isFinite(value);
    const values=item=>item?item.values.filter(valid):[];
    const extent=item=>{const v=values(item);if(!v.length)return[-1,1];let lo=Math.min(...v),hi=Math.max(...v);if(!(hi>lo)){lo-=1;hi+=1}const p=(hi-lo)*.075;return[lo-p,hi+p]};
    const setup=canvas=>{const rect=canvas.getBoundingClientRect(),d=devicePixelRatio||1;canvas.width=Math.max(1,Math.round(rect.width*d));canvas.height=Math.max(1,Math.round(rect.height*d));const c=canvas.getContext('2d');c.setTransform(d,0,0,d,0,0);return c};
    const nearestTimeIndex=(times,time)=>{let best=-1,distance=Infinity;for(let i=0;i<times.length;i++){const t=times[i];if(valid(t)&&Math.abs(t-time)<distance){best=i;distance=Math.abs(t-time)}}return best};
    const phaseGeometry=canvas=>{const sx=series(axisPrefix+canvas.dataset.x),sy=series(axisPrefix+canvas.dataset.y),xb=extent(sx),yb=extent(sy),w=canvas.clientWidth,h=canvas.clientHeight,p=18;
      return{sx,sy,xb,yb,w,h,p,x:v=>p+(v-xb[0])/Math.max(1e-12,xb[1]-xb[0])*(w-2*p),y:v=>h-p-(v-yb[0])/Math.max(1e-12,yb[1]-yb[0])*(h-2*p)}};
    const drawPhase=canvas=>{const c=setup(canvas),g=phaseGeometry(canvas),times=g.sx?g.sx.times_seconds:[],voice=series('voice_activity');c.clearRect(0,0,g.w,g.h);c.fillStyle='#080c11';c.fillRect(0,0,g.w,g.h);
      c.strokeStyle='#17232d';c.lineWidth=1;for(let i=1;i<4;i++){const x=g.p+(g.w-2*g.p)*i/4,y=g.p+(g.h-2*g.p)*i/4;c.beginPath();c.moveTo(x,g.p);c.lineTo(x,g.h-g.p);c.stroke();c.beginPath();c.moveTo(g.p,y);c.lineTo(g.w-g.p,y);c.stroke()}
      if(!g.sx||!g.sy)return;const pass=(inside,alpha,width)=>{let previous=-1;for(let i=0;i<g.sx.values.length;i++){const x=g.sx.values[i],y=g.sy.values[i],t=times[i];if(!valid(x)||!valid(y)||!valid(t)){previous=-1;continue}const inWindow=t>=visibleStart&&t<=visibleEnd;if(inWindow!==inside){previous=i;continue}if(previous>=0){const px=g.sx.values[previous],py=g.sy.values[previous],pt=times[previous];if(valid(px)&&valid(py)&&valid(pt)){const voiceIndex=voice?nearestTimeIndex(voice.times_seconds,t):-1,activityValue=voiceIndex>=0&&valid(voice.values[voiceIndex])?Number(voice.values[voiceIndex]):.5;c.strokeStyle=colorAt(Math.max(0,Math.min(1,(t-plotStart)/Math.max(.001,plotEnd-plotStart))));c.globalAlpha=alpha*(.32+.68*activityValue);c.lineWidth=width;c.beginPath();c.moveTo(g.x(px),g.y(py));c.lineTo(g.x(x),g.y(y));c.stroke()}}previous=i}c.globalAlpha=1};
      pass(false,.15,1);pass(true,.86,1.55);
      if(currentTime!=null){const i=nearestTimeIndex(times,currentTime);if(i>=0&&valid(g.sx.values[i])&&valid(g.sy.values[i])){const x=g.x(g.sx.values[i]),y=g.y(g.sy.values[i]);c.fillStyle=cursorKind==='playhead'?'rgba(255,222,108,.18)':'rgba(255,255,255,.12)';c.beginPath();c.arc(x,y,cursorKind==='playhead'?10:8,0,Math.PI*2);c.fill();c.strokeStyle=cursorKind==='playhead'?'#ffde6c':'#ffffff';c.lineWidth=2;c.beginPath();c.arc(x,y,4,0,Math.PI*2);c.stroke()}}};
    const drawActivity=()=>{const c=setup(activity),w=activity.clientWidth,h=activity.clientHeight,duration=Math.max(.001,plotEnd-plotStart),voice=series('voice_activity'),energy=series('energy_dbfs'),novelty=series('embedding_novelty');c.clearRect(0,0,w,h);c.fillStyle='#080c11';c.fillRect(0,0,w,h);
      const drawLine=(item,color,map)=>{if(!item)return;c.strokeStyle=color;c.lineWidth=1.2;c.beginPath();let started=false;for(let i=0;i<item.values.length;i++){const t=item.times_seconds[i],v=item.values[i];if(!valid(t)||!valid(v)||t<plotStart||t>plotEnd){started=false;continue}const x=(t-plotStart)/duration*w,y=map(v);if(started)c.lineTo(x,y);else{c.moveTo(x,y);started=true}}c.stroke()};
      if(voice){c.fillStyle='rgba(103,202,174,.17)';for(let i=0;i<voice.values.length;i++){const t=voice.times_seconds[i],v=voice.values[i];if(valid(t)&&valid(v)&&t>=plotStart&&t<=plotEnd)c.fillRect((t-plotStart)/duration*w,h-4-v*(h-13),Math.max(1,w/voice.values.length+1),v*(h-13))}}
      const eb=extent(energy),nb=extent(novelty);drawLine(energy,'#77b9e8',v=>h-5-(v-eb[0])/(eb[1]-eb[0])*(h-12));drawLine(novelty,'#e4aa67',v=>h-5-(v-nb[0])/(nb[1]-nb[0])*(h-12));
      if(currentTime!=null){const x=(currentTime-plotStart)/duration*w;c.strokeStyle=cursorKind==='playhead'?'#ffde6c':'rgba(255,255,255,.8)';c.lineWidth=cursorKind==='playhead'?1.8:1;c.beginPath();c.moveTo(x,0);c.lineTo(x,h);c.stroke()}c.fillStyle='#708594';c.font='9px ui-monospace,monospace';c.fillText('voice activity · blue energy · amber novelty',7,11)};
    const draw=()=>{if(!data)return;phases.forEach(drawPhase);drawActivity()};
    const updateReadout=time=>{if(!data)return;const voice=series('voice_activity'),energy=series('energy_dbfs'),novelty=series('embedding_novelty'),pieces=['time '+time.toFixed(3)+' s'],append=(item,label,digits,suffix='')=>{if(!item)return;const i=nearestTimeIndex(item.times_seconds,time);if(i>=0&&valid(item.values[i]))pieces.push(label+' '+Number(item.values[i]).toFixed(digits)+suffix)};append(voice,'voice',2);append(energy,'energy',1,' dBFS');append(novelty,'novelty',3);readout.textContent=pieces.join(' · ')};
    const publish=(name,detail)=>{if(sync)timeBus.dispatchEvent(new timeBus.CustomEvent(name,{detail:{group:sync,source:instance,...detail}}))};
    const publishCursor=(time,kind='hover',active=true)=>publish('field-time-cursor',{time:time+timeOffset,kind,active});
    const bindPhase=canvas=>{canvas.addEventListener('pointermove',event=>{if(!data)return;const g=phaseGeometry(canvas),rect=canvas.getBoundingClientRect(),mx=event.clientX-rect.left,my=event.clientY-rect.top,times=g.sx.times_seconds;let best=-1,distance=Infinity;for(let i=0;i<g.sx.values.length;i++){const x=g.sx.values[i],y=g.sy.values[i];if(!valid(x)||!valid(y))continue;const d=(g.x(x)-mx)*(g.x(x)-mx)+(g.y(y)-my)*(g.y(y)-my);if(d<distance){best=i;distance=d}}if(best>=0){currentTime=Number(times[best]);cursorKind='hover';updateReadout(currentTime);publishCursor(currentTime);draw()}});
      canvas.addEventListener('pointerleave',()=>{if(cursorKind!=='playhead'){currentTime=null;draw();publish('field-time-cursor',{time:null,kind:'hover',active:false})}});
      canvas.addEventListener('click',()=>{if(currentTime!=null){cursorKind='playhead';publish('field-time-seek',{time:currentTime+timeOffset});publishCursor(currentTime,'playhead');draw()}})};
    phases.forEach(bindPhase);
    activity.addEventListener('pointermove',event=>{if(!data)return;const rect=activity.getBoundingClientRect();currentTime=plotStart+Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width))*(plotEnd-plotStart);cursorKind='hover';updateReadout(currentTime);publishCursor(currentTime);draw()});
    activity.addEventListener('pointerleave',()=>{if(cursorKind!=='playhead'){currentTime=null;draw();publish('field-time-cursor',{time:null,kind:'hover',active:false})}});
    activity.addEventListener('click',()=>{if(currentTime!=null){cursorKind='playhead';publish('field-time-seek',{time:currentTime+timeOffset});publishCursor(currentTime,'playhead');draw()}});
    timeBus.addEventListener('field-time-cursor',event=>{const detail=event.detail||{};if(data&&detail.group===sync&&detail.source!==instance){currentTime=detail.active===false||detail.time==null?null:Number(detail.time)-timeOffset;cursorKind=detail.kind||'hover';if(currentTime!=null)updateReadout(currentTime);draw()}});
    timeBus.addEventListener('field-time-window',event=>{const detail=event.detail||{};if(data&&detail.group===sync&&detail.source!==instance){visibleStart=Number(detail.start)-timeOffset;visibleEnd=visibleStart+Number(detail.duration);draw()}});
    const observer=new ResizeObserver(()=>draw());phases.forEach(canvas=>observer.observe(canvas));observer.observe(activity);
    const finish=value=>{data=value;const backend=(data.analysis&&data.analysis.backend)||data.operation;const layer=data.analysis&&data.analysis.wavlm_layer;const owner=data.analysis&&data.analysis.projection_owner,library=data.analysis&&data.analysis.projection_library,method=data.analysis&&data.analysis.projection_method,seriesPrefix=data.analysis&&data.analysis.projection_series_prefix,distance=data.analysis&&data.analysis.projection_distance,frameCount=data.analysis&&data.analysis.projection_sampled_frames,poolMillis=data.analysis&&data.analysis.projection_pool_millis,hopMillis=data.analysis&&data.analysis.projection_hop_millis,rangeStart=data.analysis&&data.analysis.projection_start_seconds,rangeEnd=data.analysis&&data.analysis.projection_end_seconds;axisPrefix=seriesPrefix||'mds_';axisName=method||'MDS';const required=[0,1,2,3].map(i=>series(axisPrefix+i));if(required.some(item=>!item))throw new Error('speech preview needs '+axisName+' axes 0 through 3');phases.forEach(canvas=>canvas.previousElementSibling.textContent=axisName+' '+canvas.dataset.x+' × '+axisName+' '+canvas.dataset.y);plotStart=Number.isFinite(rangeStart)?Number(rangeStart):0;plotEnd=Number.isFinite(rangeEnd)?Number(rangeEnd):data.duration_seconds;visibleStart=plotStart;visibleEnd=plotEnd;currentTime=plotStart;cursorKind='playhead';spinner.remove();const range=Number.isFinite(rangeStart)&&Number.isFinite(rangeEnd)?' · '+Number(rangeStart).toFixed(2)+'–'+Number(rangeEnd).toFixed(2)+' s':'';const pooling=Number.isFinite(poolMillis)&&Number.isFinite(hopMillis)?' · pool '+Number(poolMillis).toFixed(0)+' ms · hop '+Number(hopMillis).toFixed(0)+' ms':'';meta.textContent=backend+(layer==null?'':' · layer '+layer)+(owner?' · '+owner+(library?' '+library:'')+' '+axisName:'')+(distance?' · '+distance:'')+(frameCount?' · '+frameCount+' frames':'')+pooling+range+' · '+data.duration_seconds.toFixed(2)+' s source';legend.textContent='time-colour trajectory · opacity follows voice activity';updateReadout(plotStart);draw()};
    const fail=error=>{spinner.remove();meta.className='meta error';meta.textContent='unable to load speech embedding: '+error.message};
    const embedded=root.querySelector('.speech-preview-data');if(embedded){try{finish(JSON.parse(embedded.textContent))}catch(error){fail(error)}}else fetch(root.dataset.url,{cache:'no-store'}).then(response=>{if(!response.ok)throw new Error(response.status+' '+response.statusText);return response.json()}).then(finish).catch(fail);
  })();
  </script>
</section>
""".trimIndent()
}

private fun String.speechHtmlTextEscaped(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun String.speechHtmlAttributeEscaped(): String =
    speechHtmlTextEscaped().replace("\"", "&quot;")
