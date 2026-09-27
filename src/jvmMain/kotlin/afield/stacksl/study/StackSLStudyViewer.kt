package afield.stacksl.study

import afield.Box
import afield.displayOpen
import afield.registerStringCallback
import afield.stacksl.StackSLCompiler
import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.SourceStudy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The person's side of a StackSL study: one output card that shows the program running, lets them
 * turn its numbers, and lets them keep, adopt, restore or check in — the same transactions the model's
 * tools make, from the same study.
 *
 * The card has five parts:
 *
 *  - **Live preview** — the program in WebGL2, animated at display rate. It is the same emitted GLSL
 *    the study checks, translated by [StackSLStudyShader.webgl2]; beside it sits the CPU reference
 *    still, which is what the model is shown and what every measurement is taken from. If the browser
 *    has no WebGL2, or the translation fails to compile, the card says so and shows the still alone.
 *  - **Knobs** — a slider per number the person typed ([StackSLKnob]). Sliders move uniforms, so the
 *    preview follows without a round trip; **Keep these numbers** writes them into the program as a
 *    new revision through [StackSLStudy.setNumbers], which checks them like any other edit.
 *  - **Stages** — what each line leaves on top of the stack, small, so the program can be read as a
 *    sequence of pictures.
 *  - **Variants** — whatever [StackSLStudy.tryVariants] is holding, each with **Adopt**.
 *  - **History** — recent revisions with **Restore**, and **Check in the editor's program** for a
 *    person's own edit.
 *
 * Browser actions travel as JSON strings over Field's `string-update` control message, to a callback
 * this viewer registers. The card is rebuilt on a background thread after every change the study
 * reports, so a tool call never waits for it.
 */
class StackSLStudyViewer(
    val study: StackSLStudy,
    val owner: Box,
    val channel: String = "shader",
    val previewSize: Int = 320
) {
    private val actionId = "stacksl-study-${UUID.randomUUID()}"
    private val mapper = jacksonObjectMapper()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "stacksl-study-viewer").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    @Volatile private var status = ""

    init {
        registerStringCallback(actionId) { handleAction(it) }
        study.onChange = { refreshLater() }
    }

    /** Rebuild the card soon, on the viewer's own thread; repeated requests while one is pending collapse into it. */
    fun refreshLater() {
        if (pending.compareAndSet(false, true)) worker.execute {
            pending.set(false)
            runCatching { show() }.onFailure { e -> status = "viewer failed: ${e.message}"; runCatching { afield.stdlib.BoxStudies.show(owner, channel, errorCard(e)) } }
        }
    }

    /**
     * Rebuild the card and open its channel. Called on Field's main thread — as the tools box does —
     * it hands the work to the viewer's thread instead: the card's images render on the window's
     * GPU, which waits for a frame the main thread draws, so rendering here would fall back to the
     * CPU reference and hold up the main thread while it ran.
     */
    fun show() {
        if (afield.Runloop.isMainThread) { refreshLater(); return }
        afield.stdlib.BoxStudies.show(owner, channel, html())
    }

    /**
     * Carry out one browser action — `keep`, `adopt`, `restore`, `checkin` or `refresh` — and rebuild
     * the card. Returns the status line the card will show; a refused action says why.
     */
    internal fun handleAction(json: String): String {
        val message = runCatching { mapper.readTree(json) }.getOrNull() ?: return "unreadable action"
        status = try {
            when (message["action"]?.asText()) {
                "keep" -> {
                    val values = message["values"].properties().associate { it.key.toInt() to it.value.asDouble() }
                    val knobs = study.revisions.revision().value.knobs
                    val summary = values.entries.sortedBy { it.key }.joinToString(", ") { (k, v) ->
                        "k$k ${knobs.getOrNull(k)?.value?.let { StackSLStudyShader.formatKnob(it, "$it") } ?: "?"}→${StackSLStudyShader.formatKnob(v, "$v")}"
                    }
                    study.setNumbers(message["revision"].asText(), message["sourceHash"].asText(), values, "numbers set by hand: $summary").revision
                        .let { "kept numbers as $it" }
                }
                "adopt" -> study.adoptVariant(message["revision"].asText(), message["sourceHash"].asText(), message["label"].asText()).revision
                    .let { "adopted ${message["label"].asText()} as $it" }
                "restore" -> study.restore(message["revision"].asText(), message["sourceHash"].asText(), message["id"].asText()).revision
                    .let { "restored ${message["id"].asText()} as $it" }
                "checkin" -> study.checkIn().let { it.facts["message"]?.toString() + " (" + it.revision + ")" }
                "cancel-export" -> study.cancelExport(message["id"].asText()).facts["message"].toString()
                "refresh" -> "refreshed"
                else -> "unknown action"
            }
        } catch (e: Exception) {
            "not done: ${e.message}"
        }
        refreshLater()
        return status
    }

    private fun png(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun errorCard(e: Throwable) = "<div style='font:13px Iosevka,monospace;color:#e88'>StackSL viewer: ${esc(e.message ?: e.toString())}</div>"

    /** The card's HTML for the study's current state. */
    fun html(): String {
        val current = study.currentId()
        if (current == SourceStudy.EMPTY) return "<div style='font:14px Iosevka,monospace;color:#bbb;padding:12px'>No checked program yet.</div>"
        val r = study.revisions.revision()
        val live = study.revisions.liveState()
        val dirty = live["dirty"] == true
        val id = "sv" + UUID.randomUUID().toString().replace("-", "").take(10)
        val webgl = runCatching { StackSLStudyShader.webgl2(r.source) }.getOrNull()
        // The card renders on its own single thread, so a tool call waiting on the CPU reference keeps the other cores.
        val still = png(study.previewPng(r.id, 192, parallel = false))
        // Every number gets a slider; past the first 24 they wait behind a disclosure, so a long
        // program's card stays readable and still reaches all of them.
        val knobs = r.value.knobs
        val wireOf = knobs.associate { it.knob to it.wire }
        val stages = stageThumbnails(r.source, r.value.stages)
        val history = study.revisions.history().takeLast(10).reversed()
        val variants = study.heldVariants()
        val config = mapper.writeValueAsString(linkedMapOf(
            "id" to id, "action" to actionId, "revision" to r.id, "checkedHash" to SourceStudy.hash(r.source),
            "vertex" to webgl?.vertex, "fragment" to webgl?.fragment,
            "wires" to webgl?.wires?.map { listOf(it.first, it.second) }, "knobWires" to wireOf,
            "animated" to r.value.animated
        ))
        fun range(k: StackSLKnob): Triple<Double, Double, Double> {
            val v = k.value
            val (lo, hi) = when {
                v > 0 -> 0.0 to maxOf(1.0, v * 4)
                v < 0 -> v * 4 to -v * 4
                else -> -1.0 to 1.0
            }
            // A step of 1, 2 or 5 times a power of ten, so a kept value is a number a person would type.
            val raw = (hi - lo) / 400
            val magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)))
            val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw * 0.999 }
            return Triple(lo, hi, step)
        }
        return buildString {
            append("<div id='$id' class='stacksl-study' onpointerdown='event.stopPropagation()' onmousedown='event.stopPropagation()' onclick='event.stopPropagation()' onwheel='event.stopPropagation()'>")
            append("""<style>
                #$id{font:13px/1.45 Iosevka,ui-monospace,monospace;color:#ddd;background:#0b0b0d;padding:14px 16px;width:${previewSize * 2 + 360}px;border-radius:6px}
                #$id .row{display:flex;gap:16px;align-items:flex-start}
                #$id .muted{color:#8a8a90} #$id .warn{color:#e5b35f} #$id .bad{color:#ec8a74}
                #$id h4{font-weight:400;margin:14px 0 6px;color:#aaa;letter-spacing:.06em;text-transform:uppercase;font-size:11px}
                #$id canvas,#$id img.still{width:${previewSize}px;height:${previewSize}px;image-rendering:auto;border:1px solid #333;background:#000}
                #$id .knob{display:grid;grid-template-columns:160px 1fr 70px;gap:8px;align-items:center;margin:3px 0}
                #$id .knob label{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                #$id .kline{margin:10px 0 2px;color:#b8b8be;font-size:12px;border-top:1px solid #222;padding-top:6px}
                #$id input[type=color]{width:100%;height:22px;border:1px solid #444;background:none;padding:0}
                #$id input[type=range]{width:100%}
                #$id button{font:inherit;background:#222;color:#ddd;border:1px solid #444;padding:3px 10px;border-radius:4px;cursor:pointer}
                #$id button:hover{background:#333}
                #$id .strip{display:flex;flex-wrap:wrap;gap:8px}
                #$id .cell{width:96px;font-size:11px;color:#aaa}
                #$id .cell img{width:96px;height:96px;border:1px solid #333;display:block;margin-bottom:3px}
                #$id .cell span{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                #$id .status{margin-top:10px;color:#9c9}
            </style>""")
            append("<div><b>${esc(r.id)}</b> <span class='muted'>· ${esc(r.description.take(160))}</span></div>")
            append("<div class='muted'>${if (r.value.animated) "animated" else "still"} · ${r.value.words} words · ${r.value.stages.size} stages · ${r.value.knobs.size} knobs")
            if (r.value.nonFiniteFraction > 0) append(" · <span class='bad'>${"%.1f".format(r.value.nonFiniteFraction * 100)}% NaN pixels</span>")
            append("</div>")
            r.value.warnings.forEach { append("<div class='warn'>warning: ${esc(it)}</div>") }
            if (dirty) append("<div class='warn'>The editor holds an unchecked edit. <button data-act='checkin'>Check in the editor's program</button></div>")
            append("<div class='row' style='margin-top:10px'>")
            append("<div><canvas width='$previewSize' height='$previewSize'></canvas><div class='muted'><button data-act='pause'>pause</button> <span class='time'>t = 0.00 s</span> · live WebGL2 preview</div><div class='bad glerror'></div></div>")
            append("<div><img class='still' src='$still'><div class='muted'>the model's evidence at t = ${StackSLStudyShader.REFERENCE_TIME} s · ${esc(study.rasterizer.description)}</div></div>")
            append("<div style='flex:1;min-width:300px'>")
            append("<h4>Knobs</h4>")
            if (knobs.isEmpty()) append("<div class='muted'>This program has no typed numbers.</div>")
            // Grouped under the line they are on, headed by what that line says it does; a colour
            // literal also gets a swatch. Past about 24 numbers the remaining lines wait behind a
            // disclosure, so a long program's card stays readable and still reaches every number.
            val sourceLines = r.source.lines()
            val colours = StackSLStudyShader.colourKnobs(r.source, knobs).associateBy { it.knobs.first() }
            var shownCount = 0
            var folded = false
            knobs.groupBy { it.line }.forEach { (line, onLine) ->
                if (!folded && shownCount >= VISIBLE_KNOBS) {
                    append("<details><summary class='muted'>${knobs.size - shownCount} more numbers</summary>")
                    folded = true
                }
                // What the line says it does: its own comment, else a comment line just above it, else its code.
                val heading = r.value.stages.firstOrNull { it.line == line }?.comment?.takeIf { it.isNotBlank() }
                    ?: sourceLines.getOrNull(line - 2)?.trim()?.takeIf { it.startsWith("#") }?.removePrefix("#")?.trim()
                    ?: sourceLines.getOrNull(line - 1)?.substringBefore('#')?.trim().orEmpty()
                append("<div class='kline'>line $line · ${esc(heading.take(80))}</div>")
                onLine.forEach { k ->
                    colours[k.knob]?.let { c ->
                        append("<div class='knob'><label>colour <span class='muted'>${esc(k.token)}</span></label>")
                        append("<input type='color' data-knobs='${c.knobs.joinToString(",")}' value='${c.hex}'><span class='value'>${c.hex}</span></div>")
                    }
                    val (lo, hi, step) = range(k)
                    append("<div class='knob'><label title='${esc(k.context)}'>k${k.knob} <span class='muted'>${esc(k.token)} · ${k.line}:${k.column}</span></label>")
                    append("<input type='range' data-knob='${k.knob}' min='$lo' max='$hi' step='$step' value='${k.value}'>")
                    append("<span class='value'>${StackSLStudyShader.formatKnob(k.value, k.token)}</span></div>")
                    shownCount++
                }
            }
            if (folded) append("</details>")
            if (knobs.isNotEmpty()) append("<div style='margin-top:6px'><button data-act='keep'>Keep these numbers</button> <button data-act='reset'>Reset</button></div>")
            append("</div></div>")
            if (stages.isNotEmpty()) {
                val unit = r.value.stages.firstOrNull()?.granularity ?: "line"
                val which = if (stages.size == r.value.stages.size) "each $unit" else "${stages.size} of ${r.value.stages.size} ${unit}s, evenly spaced"
                append("<h4>Stages — the top of the stack after $which</h4><div class='strip'>")
                stages.forEach { (label, image, text) -> append("<div class='cell'>${image?.let { "<img src='$it'>" } ?: "<div style='width:96px;height:96px;border:1px solid #333'></div>"}<span>${esc(label)}</span><span class='muted' title='${esc(text)}'>${esc(text)}</span></div>") }
                append("</div>")
            }
            if (variants.isNotEmpty()) {
                append("<h4>Variants held for adoption</h4><div class='strip'>")
                variants.forEach { (label, note, source) ->
                    val image = runCatching { png(StackSLStudyShader.presented(study.frame(source, 96, StackSLStudyShader.REFERENCE_TIME, parallel = false)).toPngBytes()) }.getOrNull()
                    append("<div class='cell'>${image?.let { "<img src='$it'>" } ?: ""}<span>${esc(label)}</span><span class='muted' title='${esc(note)}'>${esc(note)}</span><button data-act='adopt' data-label='${esc(label)}'>Adopt</button></div>")
                }
                append("</div>")
            }
            append("<h4>History</h4><div class='strip'>")
            history.forEach { h ->
                val image = runCatching { png(study.previewPng(h.id, 96, parallel = false)) }.getOrNull()
                append("<div class='cell'>${image?.let { "<img src='$it'>" } ?: ""}<span>${esc(h.id)}</span><span class='muted' title='${esc(h.description)}'>${esc(h.description)}</span>")
                if (h.id != r.id) append("<button data-act='restore' data-id='${esc(h.id)}'>Restore</button>") else append("<span class='muted'>current</span>")
                append("</div>")
            }
            append("</div>")
            study.exports().takeIf { it.isNotEmpty() }?.let { jobs ->
                append("<h4>Exports</h4>")
                jobs.forEach { j ->
                    append("<div class='muted'>${esc(j["kind"].toString())} · ${esc(java.io.File(j["path"].toString()).name)} · ${esc(j["status"].toString())} · frames ${esc(j["frames"].toString())}${j["message"]?.let { " · " + esc(it.toString()) } ?: ""}")
                    if (j["status"] in setOf("queued", "running")) append(" <button data-act='cancel-export' data-id='${esc(j["id"].toString())}'>cancel</button>")
                    append("</div>")
                }
            }
            if (status.isNotBlank()) append("<div class='status'>${esc(status)}</div>")
            append("<script>(function(){const C=$config;").append(SCRIPT).append("})();</script>")
            append("</div>")
        }
    }

    private fun stageThumbnails(source: String, stages: List<StackSLStage>): List<Triple<String, String?, String>> {
        if (stages.isEmpty()) return emptyList()
        val outline = StackSLCompiler.outline(source)
        // About fourteen, evenly spaced: every card refresh renders each one, and on the GPU each is a
        // pipeline compiled on the main thread. TraceStackSLStudy draws them all, once, when asked.
        val shown = if (stages.size <= 14) stages else (stages.indices.filter { it % ((stages.size + 12) / 13) == 0 } + stages.lastIndex).distinct().map { stages[it] }
        return shown.map { stage ->
            val prefix = outline.rootPrefix(source, stage.wordsThrough)
            val stored = outline.storedMark(stage.wordsThrough)
            val image = runCatching {
                if (!StackSLCpu.compile(prefix).ok) null
                else {
                    // A line that ends by storing (>name) is shown by the value it stored.
                    val raw = study.frame(outline.stageProgram(source, stage.wordsThrough), 64, StackSLStudyShader.REFERENCE_TIME, parallel = false)
                    val (tile, _) = StackSLStudyEvidence.normalizedTile(raw, "", "")
                    val bytes = afield.util.PngEncoder.encode(tile.argb!!, tile.width, tile.height)
                    png(bytes)
                }
            }.getOrNull()
            Triple("${if (stage.granularity == "line") "line" else "word"} ${if (stage.granularity == "line") stage.line else stage.stage}" +
                (stored?.let { " → $it" } ?: ""), image, stage.text)
        }
    }

    companion object {
        private const val VISIBLE_KNOBS = 24

        /** The card's browser half: WebGL2 preview, sliders, and actions posted back through `handleStringInput`. */
        private val SCRIPT = """
            const root=document.getElementById(C.id); if(!root) return;
            const send=(o)=>{ if(window.handleStringInput) window.handleStringInput({id:C.action,value:JSON.stringify(o)}); };
            const canvas=root.querySelector('canvas'); const err=root.querySelector('.glerror'); const timeLabel=root.querySelector('.time');
            const values=(C.wires||[]).map(w=>w[1]);
            let paused=false, offset=0, start=performance.now();
            function fail(m){ err.textContent='Live preview unavailable: '+m+' — the CPU still beside it is exact.'; canvas.style.display='none'; }
            let gl=null, prog=null, locs=[], tLoc=null;
            if(!C.fragment){ fail('this program could not be translated'); }
            else {
              gl=canvas.getContext('webgl2',{preserveDrawingBuffer:true,antialias:false});
              if(!gl){ fail('this browser has no WebGL2'); }
              else {
                const sh=(type,src)=>{const s=gl.createShader(type);gl.shaderSource(s,src);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));return s;};
                try{
                  prog=gl.createProgram(); gl.attachShader(prog,sh(gl.VERTEX_SHADER,C.vertex)); gl.attachShader(prog,sh(gl.FRAGMENT_SHADER,C.fragment)); gl.linkProgram(prog);
                  if(!gl.getProgramParameter(prog,gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
                  locs=(C.wires||[]).map(w=>gl.getUniformLocation(prog,w[0])); tLoc=gl.getUniformLocation(prog,'u_time');
                  gl.bindVertexArray(gl.createVertexArray());
                }catch(e){ fail(String(e.message||e).slice(0,300)); gl=null; }
              }
            }
            function draw(now){
              if(!root.isConnected) return;
              const t=paused?offset:offset+(now-start)/1000;
              if(gl){ gl.viewport(0,0,canvas.width,canvas.height); gl.useProgram(prog); gl.uniform1f(tLoc,t); values.forEach((v,i)=>{ if(locs[i]) gl.uniform1f(locs[i],v); }); gl.drawArrays(gl.TRIANGLES,0,3); }
              timeLabel.textContent='t = '+t.toFixed(2)+' s';
              requestAnimationFrame(draw);
            }
            requestAnimationFrame(draw);
            const sliders=[...root.querySelectorAll('input[type=range]')];
            const original=sliders.map(s=>parseFloat(s.value));
            const fmt=(v)=>{ const r=Math.round(v*100000)/100000; return String(r); };
            sliders.forEach(s=>s.addEventListener('input',()=>{ const k=s.dataset.knob; const w=C.knobWires[k]; const v=parseFloat(s.value); if(w!==undefined) values[w]=v; s.parentElement.querySelector('.value').textContent=fmt(v); }));
            // A colour swatch is sRGB, as the window shows it; the literal it sets is linear light.
            const toLinear=(c)=>{ c=c/255; return c<=0.04045 ? c/12.92 : Math.pow((c+0.055)/1.055, 2.4); };
            root.querySelectorAll('input[type=color]').forEach(picker=>picker.addEventListener('input',()=>{
              const hex=picker.value; const rgb=[1,3,5].map(i=>toLinear(parseInt(hex.substr(i,2),16)));
              picker.dataset.knobs.split(',').forEach((k,i)=>{ const s=sliders.find(x=>x.dataset.knob===k); if(!s) return;
                s.value=String(Math.round(rgb[i]*1000)/1000); s.dispatchEvent(new Event('input')); });
              picker.parentElement.querySelector('.value').textContent=hex;
            }));
            root.querySelectorAll('button[data-act]').forEach(b=>b.addEventListener('click',(ev)=>{
              ev.stopPropagation(); const act=b.dataset.act;
              if(act==='pause'){ const now=performance.now(); if(paused){ start=now; paused=false; b.textContent='pause'; } else { offset=offset+(now-start)/1000; paused=true; b.textContent='play'; } return; }
              if(act==='reset'){ sliders.forEach((s,i)=>{ s.value=original[i]; s.dispatchEvent(new Event('input')); }); return; }
              if(act==='keep'){ const changed={}; sliders.forEach((s,i)=>{ const v=parseFloat(s.value); if(Math.abs(v-original[i])>1e-12) changed[s.dataset.knob]=v; }); if(Object.keys(changed).length===0) return; b.disabled=true; send({action:'keep',revision:C.revision,sourceHash:C.checkedHash,values:changed}); return; }
              b.disabled=true;
              send({action:act,revision:C.revision,sourceHash:C.checkedHash,label:b.dataset.label,id:b.dataset.id});
            }));
        """.trimIndent()
    }
}
