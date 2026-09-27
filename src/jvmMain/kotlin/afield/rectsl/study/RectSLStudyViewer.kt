package afield.rectsl.study

import afield.Box
import afield.registerStringCallback
import afield.rectsl.RectSLKnob
import afield.rectsl.formatRectSLKnob
import afield.rectsl.setRectSLKnobs
import afield.stdlib.BoxStudies
import afield.stdlib.SourceStudy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The person's side of a RectSL study: one output card that shows the scene over time, lets them turn
 * its numbers and colours with the window following as they drag, and lets them keep, adopt, restore or
 * check in — the same transactions the model's tools make, on the same study.
 *
 *  - **Filmstrip** — frames evenly across the clip, drawn as the window draws them.
 *  - **Window** — hold the window at a time on a scrubber, play again, or restart the loop.
 *  - **Knobs** — one of Field's sliders per number and a swatch per colour, grouped under the line they
 *    are on and labelled with what reads them. Clicking a label selects its number in the editor. Moving
 *    one previews the changed scene in the window without touching the program; **Keep these numbers**
 *    writes them in as a new revision through [RectSLStudy.setNumbers], checked like any other edit, and
 *    **Reset** puts the window back.
 *  - **Variants** held by [RectSLStudy.tryVariants], each with **Adopt**; **History** with **Restore**;
 *    and **Check in** for a person's own edit.
 *
 * Browser actions travel as JSON strings over Field's `string-update` control message and are carried
 * out on the card's own thread; a preview is latest-wins, so dragging never builds a queue.
 */
class RectSLStudyViewer(
    val study: RectSLStudy,
    val owner: Box,
    val channel: String = RectSLPrograms.CHANNEL
) {
    private val actionId = "rectsl-study-${UUID.randomUUID()}"
    private val mapper = jacksonObjectMapper()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rectsl-study-viewer").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    private val pendingPreview = AtomicReference<String?>(null)
    @Volatile private var status = ""

    init {
        registerStringCallback(actionId) { json ->
            val action = runCatching { mapper.readTree(json)["action"]?.asText() }.getOrNull()
            if (action == "preview") {
                if (pendingPreview.getAndSet(json) == null) worker.execute { pendingPreview.getAndSet(null)?.let { handleAction(it) } }
            } else worker.execute { handleAction(json) }
        }
        study.onChange = { refreshLater() }
    }

    /** Who drew the card's frames last time — the rasterizer's description when it was built. */
    @Volatile
    var drawnBy: String = ""
        private set

    /**
     * Redraw the card if it was drawn without the window — at a document's opening the scene can run
     * before its window's renderer exists — so its frames, and what it says drew them, become the GPU's.
     * Once per such card.
     */
    fun redrawIfDrawnWithoutWindow() {
        if (drawnBy.startsWith("CPU")) {
            drawnBy = "redrawing"
            refreshLater()
        }
    }

    /** Rebuild the card soon, on the card's own thread; repeated requests while one is pending collapse into it. */
    fun refreshLater() {
        if (pending.compareAndSet(false, true)) worker.execute {
            pending.set(false)
            runCatching { show() }.onFailure { e -> status = "card failed: ${e.message}"; runCatching { BoxStudies.show(owner, channel, errorCard(e)) } }
        }
    }

    /** Rebuild the card and open its channel; from the main thread, hands the work to the card's thread (its frames wait on the GPU). */
    fun show() {
        if (afield.Runloop.isMainThread) { refreshLater(); return }
        BoxStudies.show(owner, channel, html())
        drawnBy = study.rasterizer.description
    }

    /**
     * Carry out one browser action — `keep`, `preview`, `reset`, `adopt`, `restore`, `checkin`, `time`,
     * `play`, `restart`, `locate`, `cancel-export` or `refresh` — and say what happened. Previews, window
     * controls and `locate`, which puts the editor on a number or a line, leave the card as it is, so a
     * slider being dragged is not rebuilt under the pointer.
     */
    internal fun handleAction(json: String): String {
        val message = runCatching { mapper.readTree(json) }.getOrNull() ?: return "unreadable action"
        val live = RectSLPrograms.live(owner)
        val act = message["action"]?.asText()
        val result = try {
            when (act) {
                "preview" -> {
                    val values = message["values"].properties().associate { it.key.toInt() to it.value.asDouble() }
                    // Nothing turned: the window shows the program, not a preview of it.
                    if (values.isEmpty()) { live.preview = null; return "back to the program" }
                    val source = setRectSLKnobs(study.revisions.revision(message["revision"].asText()).source, values)
                    if (live.showing == null && live.preview == null) return "no window to preview in; keep the numbers to see them"
                    live.preview = RectSLStudy.drawable(source)
                    live.present(null) ?: "previewing"
                }
                "reset" -> { live.preview = null; "back to the program" }
                "time" -> { live.hold(message["seconds"].asDouble()); return "holding at ${RectSLStudy.secs(message["seconds"].asDouble())} s" }
                "play" -> { live.hold(null); return "playing" }
                "restart" -> { live.restart(); return "playing from the start" }
                // What the window shows: the scene up to a line, the editor's line, or all of it.
                "pin" -> {
                    val line = message["line"]?.asInt()
                    live.pinnedLine = line?.takeIf { it != live.pinnedLine }
                    return live.pinnedLine?.let { "the window shows the scene as it stands after line $it" } ?: "the window shows the whole scene"
                }
                "follow" -> {
                    live.followCursor = message["on"]?.asBoolean() == true
                    return if (live.followCursor) "the window shows the scene up to the editor's line" else "the window shows the whole scene"
                }
                // A key: every number as the card holds it now, kept by name for this scene's shape.
                // The card adds it in place, so the sliders keep what has not been kept.
                "capture-key" -> {
                    val revision = study.revisions.revision()
                    val values = message["values"].properties().associate { it.key.toInt() to it.value.asDouble() }
                    val name = message["name"]?.asText()?.let { afield.stdlib.StudyKeys.slug(it) } ?: afield.stdlib.StudyKeys.nextName(study.directory)
                    afield.stdlib.StudyKeys.save(study.directory, name, study.knobShape(revision.source, revision.value.knobs), values, "captured on the card from ${revision.id}")
                    return "captured $name"
                }
                // The editor, not the scene: put it on a knob's number, or on a line.
                "locate" -> {
                    val revision = study.revisions.revision()
                    val knob = message["knob"]?.asInt()?.let { revision.value.knobs.getOrNull(it) }
                    val line = knob?.line ?: message["line"]?.asInt() ?: return "nothing to show"
                    // The card's columns are the checked scene's; while the editor holds an edit, only the line is sure.
                    val exact = knob != null && (owner.getPropertyRaw(RectSLPrograms.PROPERTY, null) as? String) == revision.source
                    afield.stdlib.BoxStudies.reveal(owner, RectSLPrograms.PROPERTY, line,
                        if (exact) knob!!.column else -1, if (exact) knob!!.column + knob.text.length else -1)
                    return knob?.let { "k${it.knob} is line ${it.line} of the scene" } ?: "line $line of the scene"
                }
                "keep" -> {
                    val values = message["values"].properties().associate { it.key.toInt() to it.value.asDouble() }
                    val knobs = study.revisions.revision().value.knobs
                    val summary = values.entries.sortedBy { it.key }.joinToString(", ") { (k, v) ->
                        knobs.getOrNull(k)?.let { "k$k ${it.text}→${formatRectSLKnob(v, it.text)}" } ?: "k$k→$v"
                    }
                    val revision = study.setNumbers(message["revision"].asText(), message["sourceHash"].asText(), values, "numbers set by hand: $summary").revision
                    live.preview = null
                    "kept numbers as $revision"
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
        if (act == "preview" || act == "reset") return result
        status = result
        refreshLater()
        return status
    }

    private fun png(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun errorCard(e: Throwable) = "<div style='font:13px Iosevka,monospace;color:#e88'>RectSL card: ${esc(e.message ?: e.toString())}</div>"

    /** A slider's range and step for a knob: around its value, in steps of 1, 2 or 5 times a power of ten. */
    private fun range(k: RectSLKnob): Triple<Double, Double, Double> {
        val v = k.value
        val (lo, hi) = when {
            k.role.startsWith("->wall") || k.role.startsWith("->duration") || k.role.startsWith("->fps") || k.role.endsWith("time") -> 0.0 to maxOf(1.0, v * 3)
            k.role.startsWith("rgb") -> 0.0 to 1.0
            v > 0 -> 0.0 to maxOf(1.0, v * 3)
            v < 0 -> v * 3 to -v * 3
            else -> -2.0 to 2.0
        }
        val raw = (hi - lo) / 400
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw * 0.999 }
        return Triple(lo, hi, step)
    }

    /** The card's HTML for the study's current state. */
    fun html(): String {
        if (study.currentId() == SourceStudy.EMPTY) return "<div style='font:14px Iosevka,monospace;color:#bbb;padding:12px'>No checked scene yet.</div>"
        val r = study.revisions.revision()
        val dirty = study.revisions.liveState()["dirty"] == true
        val id = "rv" + UUID.randomUUID().toString().replace("-", "").take(10)
        val c = r.value
        val scene = RectSLStudy.drawable(r.source).scene
        val times = if (c.animated) RectSLStudy.defaultTimes(scene, 4) else listOf(0.0)
        val filmstrip = times.map { t -> t to runCatching { png(study.previewPng(r.id, 240, t)) }.getOrNull() }
        val live = RectSLPrograms.live(owner)
        val windowed = live.showing != null
        val history = study.revisions.history().takeLast(8).reversed()
        val variants = study.heldVariants()
        val aspect = c.wallWidth / c.wallHeight
        val (fw, fh) = if (aspect >= 1) 240 to (240 / aspect).toInt() else (160 * aspect).toInt() to 160
        val (tw, th) = if (aspect >= 1) 120 to (120 / aspect).toInt() else (90 * aspect).toInt() to 90
        val config = mapper.writeValueAsString(linkedMapOf("id" to id, "action" to actionId, "revision" to r.id, "checkedHash" to SourceStudy.hash(r.source),
            "duration" to c.duration, "keys" to afield.stdlib.StudyKeys.config(study.directory, study.knobShape(r.source, c.knobs))))
        return buildString {
            append("<div id='$id' class='rectsl-study' onpointerdown='event.stopPropagation()' onmousedown='event.stopPropagation()' onclick='event.stopPropagation()' onwheel='event.stopPropagation()'>")
            append("""<style>
                #$id{font:13px/1.45 Iosevka,ui-monospace,monospace;color:#ddd;background:#0b0b0d;padding:14px 16px;width:${maxOf(4 * (fw + 10), 1040)}px;border-radius:6px}
                #$id .muted{color:#8a8a90} #$id .warn{color:#e5b35f} #$id .bad{color:#ec8a74}
                #$id h4{font-weight:400;margin:14px 0 6px;color:#aaa;letter-spacing:.06em;text-transform:uppercase;font-size:11px}
                #$id .row{display:flex;gap:16px;align-items:flex-start}
                #$id .film{display:flex;gap:10px} #$id .film img{width:${fw}px;height:${fh}px;border:1px solid #333;display:block;background:#000}
                #$id .field-control{padding:2px 10px;margin:3px 0}
                #$id .field-slider,#$id .kcolour{max-width:640px}
                #$id .field-slider .field-label{width:300px;overflow:hidden;text-overflow:ellipsis}
                #$id .field-slider-visual{height:26px}
                #$id .field-value{min-width:64px}
                #$id .locate{cursor:pointer} #$id .locate:hover{color:var(--field-accent,#5ce1ff)}
                #$id .locate .purpose{text-transform:none;letter-spacing:0;opacity:.85}
                #$id .kcolour{display:flex;align-items:center;gap:10px}
                #$id .kcolour .field-label{width:300px;font-size:11px;color:var(--field-accent-text-soft)} #$id .kcolour input{flex:1}
                #$id .scrubber{display:inline-flex;width:420px;vertical-align:middle}
                #$id .scrubber .field-label{width:auto}
                #$id .kline{margin:10px 0 2px;color:#b8b8be;font-size:12px;border-top:1px solid #222;padding-top:6px}
                #$id .kline.locate:hover{color:var(--field-accent,#5ce1ff)}
                #$id button.pin{font-size:11px;padding:0 6px;margin-left:6px} #$id button.pin.pinned{border-color:#5ce1ff;color:#5ce1ff}
                #$id input[type=color]{width:100%;height:22px;border:1px solid #444;background:none;padding:0}
                #$id button{font:inherit;background:#222;color:#ddd;border:1px solid #444;padding:3px 10px;border-radius:4px;cursor:pointer}
                #$id button:hover{background:#333}
                #$id select{font:inherit;background:#222;color:#ddd;border:1px solid #444;padding:2px 6px;border-radius:4px}
                #$id .blendslider .field-label{width:auto}
                #$id .strip{display:flex;flex-wrap:wrap;gap:8px}
                #$id .cell{width:${tw}px;font-size:11px;color:#aaa}
                #$id .cell img{width:${tw}px;height:${th}px;border:1px solid #333;display:block;margin-bottom:3px;background:#000}
                #$id .cell span{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                #$id .status{margin-top:10px;color:#9c9}
            </style>""")
            append("<div><b>${esc(r.id)}</b> <span class='muted'>· ${esc(r.description.take(160))}</span></div>")
            append("<div class='muted'>wall ${RectSLStudy.fmt(c.wallWidth)} × ${RectSLStudy.fmt(c.wallHeight)} ${esc(c.unit)} · ${if (c.animated) "${RectSLStudy.secs(c.duration)} s at ${RectSLStudy.fmt(c.fps)} fps" else "still"} · ${c.rects} rectangles · ${c.knobs.size} knobs · ${esc(study.rasterizer.description)}</div>")
            c.warnings.forEach { append("<div class='warn'>${esc(it)}</div>") }
            if (dirty) append("<div class='warn'>The editor holds an unchecked edit. <button data-act='checkin'>Check in the editor's scene</button></div>")
            append("<h4>${if (c.animated) "Across the clip" else "The scene"}</h4><div class='film'>")
            filmstrip.forEach { (t, image) -> append("<div>${image?.let { "<img src='$it'>" } ?: "<div style='width:${fw}px;height:${fh}px;border:1px solid #333'></div>"}<div class='muted'>t = ${RectSLStudy.secs(t)} s</div></div>") }
            append("</div>")
            if (c.animated) {
                append("<div style='margin-top:8px'>")
                if (windowed) {
                    append("<span class='muted'>window:</span> <button data-act='play'>play</button> <button data-act='restart'>restart</button> ")
                    append("<span class='scrubber'>" + afield.buildSliderHtml("$id-time", "time", 0.0, c.duration, c.duration / 400, live.held ?: 0.0, send = false, extraAttributes = "data-scrub='time'") + "</span>")
                    append(" <span class='muted scrubvalue'>${live.held?.let { "held at ${RectSLStudy.secs(it)} s" } ?: "playing"}</span>")
                } else append("<span class='muted'>Run the box under a window to see it play; the sliders below then preview there as you drag.</span>")
                append("</div>")
            }
            append("<h4>Knobs</h4>")
            if (windowed) append("<div class='muted' style='margin-bottom:6px'>A line's <b>window</b> button shows the scene as it stands after that line; press it again for the whole scene. " +
                "<label><input type='checkbox' class='follow'${if (live.followCursor) " checked" else ""}> the window shows the scene up to the editor's line</label></div>")
            if (c.knobs.isEmpty()) append("<div class='muted'>This scene has no typed numbers.</div>")
            val lines = r.source.lines()
            // What each number does, named by the fast model once per scene shape; until then (and in the tooltip), what reads it.
            val names = study.knobNames(r.source, c.knobs, ask = afield.stdlib.KnobNames.shouldAsk(owner)) { refreshLater() }
            var shownCount = 0
            var folded = false
            c.knobs.groupBy { it.line }.forEach { (line, onLine) ->
                if (!folded && shownCount >= VISIBLE_KNOBS) {
                    append("<details><summary class='muted'>${c.knobs.size - shownCount} more numbers</summary>")
                    folded = true
                }
                val heading = RectSLStudy.comment(lines, line) ?: lines.getOrNull(line - 1)?.substringBefore('#')?.trim().orEmpty()
                val pinned = if (live.pinnedLine == line) " pinned" else ""
                append("<div class='kline locate' data-line='$line' title='show line $line in the editor'>line $line · ${esc(heading.take(80))}" +
                    (if (windowed) " <button class='pin$pinned' data-pin='$line' title='show the scene as it stands after this line in the window'>window</button>" else "") + "</div>")
                val colourDone = HashSet<String>()
                onLine.forEach { k ->
                    val locate = "class='locate' data-locate='${k.knob}' title='${esc(k.role)} · ${esc(k.context)} — click to select ${esc(k.text)} in the editor'"
                    if (k.text.startsWith("0x", ignoreCase = true)) {
                        append("<div class='field-control kcolour'><span class='field-label'><span $locate>k${k.knob} <span class='purpose'>${esc(names[k.knob] ?: k.role)}</span></span></span>")
                        append("<input type='color' data-hex='${k.knob}' value='${k.colour ?: "#000000"}'><span class='field-value'>${k.colour}</span></div>")
                    } else {
                        if (k.colour != null && k.role.endsWith("red") && colourDone.add(k.colour)) {
                            val parts = onLine.filter { it.colour == k.colour && it.knob in k.knob..k.knob + 2 }.map { it.knob }
                            append("<div class='field-control kcolour'><span class='field-label'>rgb colour</span><input type='color' data-rgb='${parts.joinToString(",")}' value='${k.colour}'><span class='field-value'>${k.colour}</span></div>")
                        }
                        val (lo, hi, step) = range(k)
                        val label = "<span $locate>k${k.knob} <span class='purpose'>${esc(names[k.knob] ?: k.role)}</span></span>"
                        append(afield.buildSliderHtml("$id-k${k.knob}", label, minOf(lo, k.value), maxOf(hi, k.value), step, k.value, send = false, extraAttributes = "data-knob='${k.knob}'"))
                    }
                    shownCount++
                }
            }
            if (folded) append("</details>")
            if (c.knobs.isNotEmpty()) {
                append("<div style='margin-top:6px'><button data-act='keep'>Keep these numbers</button> <button data-act='reset'>Reset</button></div>")
                append(afield.stdlib.StudyKeys.section(afield.buildSliderHtml("$id-blend", "blend", 0.0, 1.0, 0.005, 0.0, send = false, extraAttributes = "data-blend='1'")))
            }
            if (variants.isNotEmpty()) {
                append("<h4>Variants held for adoption</h4><div class='strip'>")
                variants.forEach { (label, note, source) ->
                    val image = runCatching {
                        val d = RectSLStudy.drawable(source)
                        val (w, h) = RectSLStudyShader.frameSize(d.scene, 160)
                        png(afield.stacksl.study.StackSLStudyShader.presented(study.rasterizer.renderAll(d, w, h, listOf(times.getOrElse(1) { 0.0 })).single()).toPngBytes())
                    }.getOrNull()
                    append("<div class='cell'>${image?.let { "<img src='$it'>" } ?: ""}<span>${esc(label)}</span><span class='muted' title='${esc(note)}'>${esc(note)}</span><button data-act='adopt' data-label='${esc(label)}'>Adopt</button></div>")
                }
                append("</div>")
            }
            append("<h4>History</h4><div class='strip'>")
            history.forEach { h ->
                val image = runCatching { png(study.previewPng(h.id, 160, times.getOrElse(1) { 0.0 })) }.getOrNull()
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

    companion object {
        private const val VISIBLE_KNOBS = 24

        /** The card's browser half: sliders that preview in the window, colour swatches, window controls, and actions. */
        private val SCRIPT = """
            const root=document.getElementById(C.id); if(!root) return;
            const send=(o)=>{ if(window.handleStringInput) window.handleStringInput({id:C.action,value:JSON.stringify(o)}); };
            // Field's own sliders (sliders.js), kept off the wire: each move is announced as field-slider-input.
            const sliders=[...root.querySelectorAll('.field-slider[data-knob]')];
            const hexes=[...root.querySelectorAll('input[type=color][data-hex]')];
            const original=new Map(); sliders.forEach(s=>original.set(s.dataset.knob,parseFloat(s.dataset.value)));
            hexes.forEach(h=>original.set(h.dataset.hex,parseInt(h.value.slice(1),16)));
            const current=new Map(original);
            const changed=()=>{ const o={}; current.forEach((v,k)=>{ if(Math.abs(v-original.get(k))>1e-12) o[k]=v; }); return o; };
            let timer=null;
            const preview=()=>{ if(timer) return; timer=setTimeout(()=>{ timer=null; send({action:'preview',revision:C.revision,values:changed()}); },90); };
            const scrubValue=root.querySelector('.scrubvalue');
            let resetting=false;
            root.addEventListener('field-slider-input',(ev)=>{ const s=ev.target.closest&&ev.target.closest('.field-slider'); if(!s||resetting) return;
              if(s.dataset.scrub){ const t=ev.detail.value; if(scrubValue) scrubValue.textContent='held at '+t.toFixed(2)+' s'; send({action:'time',seconds:t}); return; }
              if(s.dataset.knob===undefined) return; current.set(s.dataset.knob,ev.detail.value); preview(); });
            const setKnob=(s,v)=>{ if(window.updateFieldSlider) window.updateFieldSlider(s,v); else { current.set(s.dataset.knob,v); preview(); } };
            hexes.forEach(h=>h.addEventListener('input',()=>{ current.set(h.dataset.hex,parseInt(h.value.slice(1),16)); h.parentElement.querySelector('.field-value').textContent=h.value; preview(); }));
            // A slider's label, or a line's heading, puts the editor on its number or its line.
            root.querySelectorAll('[data-locate]').forEach(el=>el.addEventListener('click',(ev)=>{ ev.stopPropagation(); send({action:'locate',knob:parseInt(el.dataset.locate)}); }));
            root.querySelectorAll('.kline[data-line]').forEach(el=>el.addEventListener('click',(ev)=>{ ev.stopPropagation(); send({action:'locate',line:parseInt(el.dataset.line)}); }));
            // One line at a time in the window; the same button again for the whole scene.
            root.querySelectorAll('button.pin[data-pin]').forEach(b=>b.addEventListener('click',(ev)=>{ ev.stopPropagation();
              const on=!b.classList.contains('pinned'); root.querySelectorAll('button.pin').forEach(x=>x.classList.remove('pinned'));
              if(on) b.classList.add('pinned'); send({action:'pin',line:parseInt(b.dataset.pin)}); }));
            root.querySelectorAll('input.follow').forEach(f=>f.addEventListener('change',(ev)=>{ ev.stopPropagation(); send({action:'follow',on:f.checked}); }));
            // An rgb swatch sets the three numbers after rgb, which RectSL reads as sRGB 0..1, as typed.
            root.querySelectorAll('input[type=color][data-rgb]').forEach(p=>p.addEventListener('input',()=>{
              const rgb=[1,3,5].map(i=>parseInt(p.value.substr(i,2),16)/255);
              p.dataset.rgb.split(',').forEach((k,i)=>{ const s=sliders.find(x=>x.dataset.knob===k); if(s) setKnob(s,Math.round(rgb[i]*1000)/1000); });
              p.parentElement.querySelector('.field-value').textContent=p.value;
            }));
            // Keys (StudyKeys.SCRIPT): every knob's number now, and moving the sliders and swatches to a set of them.
            const knobValues=()=>{ const o={}; current.forEach((v,k)=>{ o[k]=v; }); return o; };
            const applyValues=(vals)=>{ Object.entries(vals).forEach(([k,v])=>{
              const s=sliders.find(x=>x.dataset.knob===k); if(s){ setKnob(s,v); return; }
              const h=hexes.find(x=>x.dataset.hex===k); if(!h) return; const hex='#'+Math.round(v).toString(16).padStart(6,'0');
              h.value=hex; h.parentElement.querySelector('.field-value').textContent=hex; current.set(k,Math.round(v)); preview(); }); };
            // A hex colour mixes channel by channel; any other number, straight.
            const blendValue=(a,b,t,k)=>{ if(!hexes.some(h=>h.dataset.hex===k)) return a+(b-a)*t;
              return [16,8,0].reduce((o,sh)=>o+(Math.round(((a>>sh)&255)+(((b>>sh)&255)-((a>>sh)&255))*t)<<sh),0); };
""".trimIndent() + "\n" + afield.stdlib.StudyKeys.SCRIPT.trimIndent() + "\n" + """
            root.querySelectorAll('button[data-act]').forEach(b=>b.addEventListener('click',(ev)=>{
              ev.stopPropagation(); const act=b.dataset.act;
              if(act==='play'||act==='restart'){ if(scrubValue) scrubValue.textContent='playing'; send({action:act}); return; }
              // Back to the typed numbers exactly: a slider snaps what it is given to its step, so its record is set after it.
              if(act==='reset'){ resetting=true; sliders.forEach(s=>{ const v=original.get(s.dataset.knob); setKnob(s,v); s.dataset.value=String(v); }); resetting=false;
                hexes.forEach(h=>{ h.value='#'+original.get(h.dataset.hex).toString(16).padStart(6,'0'); h.parentElement.querySelector('.field-value').textContent=h.value; });
                if(timer){ clearTimeout(timer); timer=null; } original.forEach((v,k)=>current.set(k,v)); send({action:'reset'}); return; }
              if(act==='keep'){ const o=changed(); if(Object.keys(o).length===0) return; b.disabled=true; send({action:'keep',revision:C.revision,sourceHash:C.checkedHash,values:o}); return; }
              b.disabled=true;
              send({action:act,revision:C.revision,sourceHash:C.checkedHash,label:b.dataset.label,id:b.dataset.id});
            }));
        """.trimIndent()
    }
}
