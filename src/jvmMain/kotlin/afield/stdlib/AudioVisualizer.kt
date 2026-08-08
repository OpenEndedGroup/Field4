package afield.stdlib

import afield.Box
import afield.four.audio.WavFloatArrayWriter
import java.util.Base64
import java.util.Locale

data class AudioVisualizerSource(
    val audioUrl: String,
    /** A GET endpoint accepting start_seconds, duration_seconds, and points. */
    val waveformUrl: String? = null,
    /** Browser-compatible PCM16 WAV used only for local waveform analysis, without replacing playback audio. */
    val inlineWaveformWavBase64: String? = null,
    val durationSeconds: Double? = null,
    val label: String? = null,
)

data class AudioVisualizerOptions(
    val width: Int = 960,
    val waveformHeight: Int = 150,
    val showPlayhead: Boolean = true,
    val followPlayhead: Boolean = true,
    val interactive: Boolean = true,
    val showNativeControls: Boolean = true,
    val initialStartSeconds: Double = 0.0,
    val initialDurationSeconds: Double? = null,
    val initialPositionSeconds: Double? = null,
    val accent: String = "#63c7dc",
)

/**
 * General-purpose waveform player for Field output areas.
 *
 * Large/remote assets should supply [AudioVisualizerSource.waveformUrl]. The browser then asks
 * only for a min/max envelope of the visible interval. Small self-contained examples may omit
 * it; the component decodes the clip locally and uses the same interaction model.
 */
object AudioVisualizer {
    fun renderHtml(
        source: AudioVisualizerSource,
        options: AudioVisualizerOptions = AudioVisualizerOptions(),
    ): String {
        require(options.width > 0) { "width must be positive" }
        require(options.waveformHeight >= 48) { "waveformHeight must be at least 48" }
        require(options.initialStartSeconds >= 0.0) { "initialStartSeconds must be non-negative" }
        require(options.initialPositionSeconds == null || options.initialPositionSeconds >= 0.0) {
            "initialPositionSeconds must be non-negative"
        }
        val duration = source.durationSeconds?.takeIf { it > 0.0 }
        val initialDuration = options.initialDurationSeconds?.takeIf { it > 0.0 }
        val title = source.label?.let { "<div class=\"fav-title\">${it.audioHtmlEscaped()}</div>" }.orEmpty()
        val waveformUrl = source.waveformUrl.orEmpty().audioHtmlEscaped()
        val inlineWaveform = source.inlineWaveformWavBase64.orEmpty().audioHtmlEscaped()
        return """
            <div class="field-audio-visualizer"
              data-waveform-url="$waveformUrl"
              data-inline-waveform-wav="$inlineWaveform"
              data-duration="${duration?.let { "%.9f".format(Locale.US, it) }.orEmpty()}"
              data-start="${"%.9f".format(Locale.US, options.initialStartSeconds)}"
              data-window="${initialDuration?.let { "%.9f".format(Locale.US, it) }.orEmpty()}"
              data-position="${options.initialPositionSeconds?.let { "%.9f".format(Locale.US, it) }.orEmpty()}"
              data-playhead="${options.showPlayhead}"
              data-follow-playhead="${options.followPlayhead}"
              data-interactive="${options.interactive}"
              data-afield-intrinsic-width="${options.width}"
              style="--fav-accent:${options.accent.audioHtmlEscaped()};width:${options.width}px;max-width:100%;box-sizing:border-box">
              <style>
                .field-audio-visualizer { color:#d9e2e9; font:12px/1.3 system-ui,sans-serif }
                .field-audio-visualizer .fav-title { color:#a9b9c5; margin:0 0 5px; font-weight:560 }
                .field-audio-visualizer .fav-stage { position:relative; overflow:hidden; border:1px solid #303b45;
                  border-radius:5px; background:#080d12; touch-action:none; user-select:none }
                .field-audio-visualizer canvas { display:block; width:100%; height:${options.waveformHeight}px;
                  cursor:${if (options.interactive) "crosshair" else "default"} }
                .field-audio-visualizer .fav-busy { position:absolute; top:7px; right:7px; display:flex;
                  align-items:center; gap:6px; padding:4px 7px; color:#aebdc8; background:#101820e8;
                  border:1px solid #35434e; border-radius:12px; font-size:10px; opacity:0;
                  transform:translateY(-2px); transition:opacity 90ms ease,transform 90ms ease;
                  pointer-events:none }
                .field-audio-visualizer.fav-loading .fav-busy { opacity:1; transform:translateY(0) }
                .field-audio-visualizer .fav-spinner { width:13px; height:13px; box-sizing:border-box;
                  border:1px solid #46545f; border-top-color:var(--fav-accent); border-radius:50%;
                  animation:fav-spin 760ms linear infinite }
                @keyframes fav-spin { to { transform:rotate(360deg) } }
                .field-audio-visualizer .fav-toolbar { display:flex; align-items:center; gap:5px; min-height:22px;
                  color:#8193a1; margin-top:4px }
                .field-audio-visualizer button { color:#aebdc8; background:#1a232b; border:1px solid #35434e;
                  border-radius:3px; min-width:24px; height:22px; padding:0 6px; cursor:pointer }
                .field-audio-visualizer button:hover { border-color:var(--fav-accent) }
                .field-audio-visualizer .fav-range { flex:1; font-family:ui-monospace,monospace; font-size:11px }
                .field-audio-visualizer .fav-status { color:#71818d; font-size:10px; white-space:nowrap }
                .field-audio-visualizer audio { display:${if (options.showNativeControls) "block" else "none"};
                  width:100%; height:30px; margin-top:4px }
              </style>
              $title
              <div class="fav-stage"><canvas tabindex="${if (options.interactive) 0 else -1}"></canvas>
                <div class="fav-busy" role="status" aria-live="polite" aria-hidden="true"><span class="fav-spinner" aria-hidden="true"></span><span>loading waveform</span></div>
              </div>
              <div class="fav-toolbar">
                <button class="fav-zoom-in" title="zoom in">+</button>
                <button class="fav-zoom-out" title="zoom out">−</button>
                <button class="fav-reset" title="show whole clip">all</button>
                <span class="fav-range"></span><span class="fav-status"></span>
              </div>
              <audio controls preload="metadata" src="${source.audioUrl.audioHtmlEscaped()}"></audio>
              <script>${runtimeScript()}</script>
            </div>
        """.trimIndent()
    }

    fun renderSamplesHtml(
        samples: FloatArray,
        sampleRate: Int,
        options: AudioVisualizerOptions = AudioVisualizerOptions(),
        wavBytes: ByteArray? = null,
        label: String? = null,
    ): String {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val waveformWav = WavFloatArrayWriter.encodeMono16(samples, sampleRate)
        val playbackWav = wavBytes ?: waveformWav
        val uri = "data:audio/wav;base64,${Base64.getEncoder().encodeToString(playbackWav)}"
        return renderHtml(
            AudioVisualizerSource(
                audioUrl = uri,
                inlineWaveformWavBase64 = if (wavBytes == null) null else
                    Base64.getEncoder().encodeToString(waveformWav),
                durationSeconds = samples.size.toDouble() / sampleRate,
                label = label,
            ),
            options,
        )
    }

    /**
     * Embed a short browser-decodable recording (WAV, MP3, Ogg, and so on) in a self-contained
     * player. Prefer [renderHtml] with ordinary audio and waveform URLs for long recordings.
     */
    fun renderBytesHtml(
        bytes: ByteArray,
        mediaType: String = "audio/wav",
        durationSeconds: Double? = null,
        label: String? = null,
        options: AudioVisualizerOptions = AudioVisualizerOptions(),
    ): String {
        require(bytes.isNotEmpty()) { "audio bytes must not be empty" }
        require(mediaType.startsWith("audio/") && mediaType.none { it == '\r' || it == '\n' || it == ';' }) {
            "mediaType must be a simple audio/* media type"
        }
        val uri = "data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}"
        return renderHtml(
            AudioVisualizerSource(
                audioUrl = uri,
                durationSeconds = durationSeconds,
                label = label,
            ),
            options,
        )
    }

    fun fieldAudioWaveformUrl(representationId: String, serviceBaseUrl: String = "/field-audio"): String =
        "${serviceBaseUrl.trimEnd('/')}/v1/representations/${representationId.audioUrlSegment()}/waveform"

    private fun runtimeScript(): String = """
        (() => {
          const script=document.currentScript, root=script.closest('.field-audio-visualizer');
          if (!root || root.dataset.ready==='true') return;
          root.dataset.ready='true';
          const stage=root.querySelector('.fav-stage'), canvas=root.querySelector('canvas'), audio=root.querySelector('audio');
          const busy=root.querySelector('.fav-busy');
          const range=root.querySelector('.fav-range'), status=root.querySelector('.fav-status');
          const interactive=root.dataset.interactive==='true', showPlayhead=root.dataset.playhead==='true';
          const followPlayback=root.dataset.followPlayhead==='true';
          const remote=root.dataset.waveformUrl || null;
          const inlineWaveform=root.dataset.inlineWaveformWav || null;
          let total=Number(root.dataset.duration)||0, viewStart=Number(root.dataset.start)||0;
          let viewDuration=Number(root.dataset.window)||total||0;
          const initialPosition=Number(root.dataset.position);
          let envelope=null, decoded=null, decoderPromise=null, request=null, timer=null, dragging=null, loadToken=0;
          let staticLayer=document.createElement('canvas'), raf=0;

          const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
          const format=t=>{
            if (!Number.isFinite(t)) return '–';
            const m=Math.floor(t/60), s=t-m*60;
            return m+':'+s.toFixed(s<10?2:1).padStart(s<10?5:4,'0');
          };
          const dimensions=()=>{
            const rect=canvas.getBoundingClientRect(), dpr=Math.min(2,window.devicePixelRatio||1);
            const w=Math.max(64,Math.round(rect.width*dpr)), h=Math.max(48,Math.round(rect.height*dpr));
            if (canvas.width!==w || canvas.height!==h) {
              canvas.width=w; canvas.height=h; staticLayer.width=w; staticLayer.height=h;
            }
            return {rect,dpr,w,h};
          };
          const normalizeView=()=>{
            if (!(total>0)) return;
            const minimum=Math.max(1/1000,total/100000);
            viewDuration=clamp(viewDuration||total,minimum,total);
            viewStart=clamp(viewStart,0,Math.max(0,total-viewDuration));
          };
          const updateRange=()=>{
            range.textContent=(total>0)
              ? format(viewStart)+' – '+format(viewStart+viewDuration)+' / '+format(total)
              : 'loading duration…';
          };
          const setLoading=loading=>{
            root.classList.toggle('fav-loading',loading);
            busy.setAttribute('aria-hidden',String(!loading));
          };
          const drawStatic=()=>{
            const {dpr,w,h}=dimensions(), c=staticLayer.getContext('2d');
            c.setTransform(1,0,0,1,0,0); c.clearRect(0,0,w,h); c.fillStyle='#080d12'; c.fillRect(0,0,w,h);
            c.strokeStyle='#1b2730'; c.lineWidth=dpr;
            for(let q=1;q<4;q++){ const x=Math.round(w*q/4)+.5; c.beginPath();c.moveTo(x,0);c.lineTo(x,h);c.stroke(); }
            if (!envelope || !envelope.minimum || !envelope.maximum) { drawFrame(); return; }
            const channels=Math.max(1,envelope.channels||envelope.minimum.length), n=envelope.point_count||0;
            const accent=getComputedStyle(root).getPropertyValue('--fav-accent').trim()||'#63c7dc';
            for(let ch=0;ch<channels;ch++){
              const y0=h*ch/channels, lane=h/channels, mid=y0+lane/2;
              c.strokeStyle=ch%2===0?accent:'#d6a85f';
              c.globalAlpha=.92; c.lineWidth=Math.max(1,dpr*.75); c.beginPath();
              const lows=envelope.minimum[ch]||envelope.minimum[0], highs=envelope.maximum[ch]||envelope.maximum[0];
              for(let i=0;i<n;i++){
                const x=(i+.5)/n*w, hi=clamp(Number(highs[i])||0,-1,1), lo=clamp(Number(lows[i])||0,-1,1);
                c.moveTo(x,mid-hi*lane*.46); c.lineTo(x,mid-lo*lane*.46);
              }
              c.stroke(); c.globalAlpha=1; c.strokeStyle='#26333d'; c.beginPath(); c.moveTo(0,mid+.5); c.lineTo(w,mid+.5); c.stroke();
            }
            drawFrame();
          };
          const drawFrame=()=>{
            const {dpr,w,h}=dimensions(), c=canvas.getContext('2d');
            c.setTransform(1,0,0,1,0,0); c.clearRect(0,0,w,h); c.drawImage(staticLayer,0,0);
            if (showPlayhead && viewDuration>0 && audio.currentTime>=viewStart && audio.currentTime<=viewStart+viewDuration){
              const x=(audio.currentTime-viewStart)/viewDuration*w;
              c.strokeStyle='#ffdf73'; c.lineWidth=Math.max(1.5,dpr); c.beginPath(); c.moveTo(x,0); c.lineTo(x,h); c.stroke();
            }
          };
          const localEnvelope=async points=>{
            if (!decoderPromise) decoderPromise=(async()=>{
              const inline=audio.getAttribute('src')||'', encoded=inlineWaveform||
                (inline.startsWith('data:audio/wav;base64,')?inline.substring(inline.indexOf(',')+1):null);
              if(encoded){
                const bytes=Uint8Array.from(atob(encoded),c=>c.charCodeAt(0));
                const view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength), text=(at,n)=>
                  String.fromCharCode(...bytes.subarray(at,at+n));
                if(text(0,4)==='RIFF'&&text(8,4)==='WAVE'){
                  let offset=12,format=0,channels=0,rate=0,bits=0,dataOffset=-1,dataSize=0;
                  while(offset+8<=bytes.length){
                    const kind=text(offset,4),size=view.getUint32(offset+4,true),body=offset+8;
                    if(kind==='fmt '&&size>=16){format=view.getUint16(body,true);channels=view.getUint16(body+2,true);
                      rate=view.getUint32(body+4,true);bits=view.getUint16(body+14,true)}
                    else if(kind==='data'){dataOffset=body;dataSize=Math.min(size,bytes.length-body);break}
                    offset=body+size+(size&1);
                  }
                  if(format===1&&channels>0&&rate>0&&bits===16&&dataOffset>=0){
                    const length=Math.floor(dataSize/(channels*2)),planes=Array.from({length:channels},()=>new Float32Array(length));
                    for(let i=0;i<length;i++)for(let ch=0;ch<channels;ch++)
                      planes[ch][i]=view.getInt16(dataOffset+(i*channels+ch)*2,true)/32768;
                    return {sampleRate:rate,length,numberOfChannels:channels,duration:length/rate,getChannelData:ch=>planes[ch]};
                  }
                }
              }
              const Ctx=window.AudioContext||window.webkitAudioContext, context=new Ctx();
              try { return await context.decodeAudioData((await (await fetch(audio.currentSrc)).arrayBuffer()).slice(0)); }
              finally { context.close(); }
            })();
            decoded=decoded||await decoderPromise; total=decoded.duration; normalizeView();
            const rate=decoded.sampleRate, begin=Math.floor(viewStart*rate), end=Math.min(decoded.length,Math.ceil((viewStart+viewDuration)*rate));
            const frames=Math.max(1,end-begin), count=Math.min(points,frames), minimum=[], maximum=[];
            for(let ch=0;ch<decoded.numberOfChannels;ch++){
              const data=decoded.getChannelData(ch), lo=new Array(count), hi=new Array(count);
              for(let x=0;x<count;x++){
                const a=begin+Math.floor(x*frames/count), b=begin+Math.max(1,Math.floor((x+1)*frames/count));
                let mn=1,mx=-1; for(let i=a;i<Math.min(end,b);i++){ const v=data[i]; if(v<mn)mn=v;if(v>mx)mx=v; }
                lo[x]=mn; hi[x]=mx;
              }
              minimum.push(lo); maximum.push(hi);
            }
            return {start_seconds:viewStart,duration_seconds:viewDuration,sample_rate:rate,
              channels:decoded.numberOfChannels,point_count:count,minimum,maximum};
          };
          const loadEnvelope=async(token=++loadToken)=>{
            if (!(total>0)) {if(token===loadToken)setLoading(false);return}
            normalizeView(); updateRange();
            const points=clamp(Math.round(canvas.getBoundingClientRect().width),64,4096);
            if (request) request.abort();
            const controller=new AbortController(); request=controller; setLoading(true); status.textContent='loading waveform…';
            let timedOut=false, timeoutId=null;
            const deadline=new Promise((_,reject)=>{
              timeoutId=setTimeout(()=>{
                timedOut=true; controller.abort(); reject(new Error('request timed out after 15 seconds'));
              },15000);
            });
            const beforeDeadline=promise=>Promise.race([promise,deadline]);
            try {
              if (remote) {
                const url=new URL(remote,document.baseURI);
                url.searchParams.set('start_seconds',viewStart.toFixed(9));
                url.searchParams.set('duration_seconds',viewDuration.toFixed(9));
                url.searchParams.set('points',String(points));
                const response=await beforeDeadline(fetch(url,{signal:controller.signal}));
                if(!response.ok) throw new Error((await response.text())||('HTTP '+response.status));
                const nextEnvelope=await beforeDeadline(response.json());
                if(request!==controller)return; envelope=nextEnvelope;
              } else {
                const nextEnvelope=await beforeDeadline(localEnvelope(points));
                if(request!==controller)return; envelope=nextEnvelope;
              }
              status.textContent=interactive?'wheel zoom at playhead · drag pan · click seek':''; drawStatic();
            } catch(error) {
              if(error.name!=='AbortError'||timedOut){
                status.textContent='waveform: '+(timedOut?'request timed out after 15 seconds':error.message); drawStatic();
              }
            } finally {
              if(timeoutId!==null)clearTimeout(timeoutId);
              if(request===controller)request=null;
              if(token===loadToken)setLoading(false);
            }
          };
          const scheduleLoad=(delay=40)=>{
            clearTimeout(timer); const token=++loadToken; setLoading(true);
            timer=setTimeout(()=>loadEnvelope(token),delay);
          };
          const zoomAnchor=(fallback=.5)=>{
            if(!(viewDuration>0))return fallback;
            const playhead=audio.currentTime;
            return Number.isFinite(playhead)&&playhead>=viewStart&&playhead<=viewStart+viewDuration
              ? clamp((playhead-viewStart)/viewDuration,0,1) : fallback;
          };
          const zoom=(factor,fallbackAnchor=.5)=>{
            if(!interactive||!(total>0))return;
            const anchor=zoomAnchor(fallbackAnchor);
            const at=viewStart+viewDuration*anchor, next=clamp(viewDuration*factor,Math.max(.001,total/100000),total);
            viewStart=at-next*anchor; viewDuration=next; normalizeView(); updateRange(); drawFrame(); scheduleLoad();
          };
          root.querySelector('.fav-zoom-in').onclick=()=>zoom(.5,.5);
          root.querySelector('.fav-zoom-out').onclick=()=>zoom(2,.5);
          root.querySelector('.fav-reset').onclick=()=>{viewStart=0;viewDuration=total;normalizeView();scheduleLoad(0)};
          if(!interactive) root.querySelector('.fav-toolbar').querySelectorAll('button').forEach(b=>b.style.display='none');
          canvas.addEventListener('wheel',event=>{
            if(!interactive)return; event.preventDefault();
            const r=canvas.getBoundingClientRect(), anchor=clamp((event.clientX-r.left)/r.width,0,1);
            if(event.shiftKey||Math.abs(event.deltaX)>Math.abs(event.deltaY)){
              viewStart+=((event.deltaX||event.deltaY)/r.width)*viewDuration; normalizeView(); updateRange(); scheduleLoad();
            } else zoom(Math.exp(event.deltaY*.0015),anchor);
          },{passive:false});
          canvas.addEventListener('pointerdown',event=>{
            if(!interactive)return; try{canvas.setPointerCapture(event.pointerId)}catch(_ignored){}
            dragging={x:event.clientX,start:viewStart,moved:false};
          });
          canvas.addEventListener('pointermove',event=>{
            if(!dragging)return; const r=canvas.getBoundingClientRect(), dx=event.clientX-dragging.x;
            if(Math.abs(dx)>3)dragging.moved=true;
            if(dragging.moved){viewStart=dragging.start-dx/r.width*viewDuration;normalizeView();updateRange();scheduleLoad(80)}
          });
          canvas.addEventListener('pointerup',event=>{
            if(!dragging)return; const r=canvas.getBoundingClientRect();
            if(!dragging.moved && total>0) audio.currentTime=clamp(viewStart+(event.clientX-r.left)/r.width*viewDuration,0,total);
            dragging=null; scheduleLoad(0); drawFrame();
          });
          canvas.addEventListener('dblclick',()=>{viewStart=0;viewDuration=total;scheduleLoad(0)});
          canvas.addEventListener('keydown',event=>{
            if(!interactive)return;
            if(event.key==='+'||event.key==='='){zoom(.5);event.preventDefault()}
            else if(event.key==='-'){zoom(2);event.preventDefault()}
            else if(event.key==='ArrowLeft'||event.key==='ArrowRight'){
              viewStart+=(event.key==='ArrowLeft'?-1:1)*viewDuration*.1;normalizeView();scheduleLoad();event.preventDefault();
            }
          });
          const followPlayhead=()=>{
            if(!followPlayback||!(total>0)||!(viewDuration>0))return;
            const playhead=audio.currentTime;
            if(!Number.isFinite(playhead))return;
            let nextStart=null;
            if(playhead>viewStart+viewDuration) nextStart=playhead-viewDuration*.08;
            else if(playhead<viewStart) nextStart=playhead-viewDuration*.92;
            if(nextStart===null)return;
            viewStart=nextStart; normalizeView(); updateRange(); drawFrame(); scheduleLoad(0);
          };
          const animate=()=>{followPlayhead();drawFrame();if(!audio.paused&&!audio.ended)raf=requestAnimationFrame(animate)};
          audio.addEventListener('play',()=>{cancelAnimationFrame(raf);animate()});
          audio.addEventListener('pause',drawFrame); audio.addEventListener('seeked',drawFrame);
          audio.addEventListener('loadedmetadata',()=>{
            total=Number.isFinite(audio.duration)?audio.duration:total;
            if(Number.isFinite(initialPosition)) audio.currentTime=clamp(initialPosition,0,total);
            if(!(viewDuration>0))viewDuration=total;normalizeView();updateRange();loadEnvelope();
          });
          // Observe the fixed-layout stage, not the canvas whose backing-store width we mutate
          // for device-pixel resolution. Observing the canvas can form an intrinsic-size feedback
          // loop in Field's shrink-to-fit output areas: 300 -> 600 -> ... -> max width.
          new ResizeObserver(()=>scheduleLoad(80)).observe(stage);
          if(total>0){if(!(viewDuration>0))viewDuration=total;normalizeView();updateRange();loadEnvelope()}
          else updateRange();
        })();
    """.trimIndent()
}

/** Display a URL-backed recording in this Field box. */
fun Box.displayAudio(
    source: AudioVisualizerSource,
    channel: String = "audio",
    options: AudioVisualizerOptions = AudioVisualizerOptions(),
): String = AudioVisualizer.renderHtml(source, options).also { display(channel, it) }

/** Display generated or otherwise in-memory mono samples in this Field box. */
fun Box.displayAudioSamples(
    samples: FloatArray,
    sampleRate: Int,
    channel: String = "audio",
    options: AudioVisualizerOptions = AudioVisualizerOptions(),
    wavBytes: ByteArray? = null,
    label: String? = null,
): String = AudioVisualizer.renderSamplesHtml(
    samples = samples,
    sampleRate = sampleRate,
    options = options,
    wavBytes = wavBytes,
    label = label,
).also { display(channel, it) }

/** Display a short browser-decodable WAV, MP3, Ogg, or similar byte buffer in this Field box. */
fun Box.displayAudioBytes(
    bytes: ByteArray,
    mediaType: String = "audio/wav",
    channel: String = "audio",
    durationSeconds: Double? = null,
    label: String? = null,
    options: AudioVisualizerOptions = AudioVisualizerOptions(),
): String = AudioVisualizer.renderBytesHtml(
    bytes = bytes,
    mediaType = mediaType,
    durationSeconds = durationSeconds,
    label = label,
    options = options,
).also { display(channel, it) }

private fun String.audioHtmlEscaped(): String = buildString(length) {
    for (character in this@audioHtmlEscaped) append(
        when (character) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> character
        }
    )
}

private fun String.audioUrlSegment(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
