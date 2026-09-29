package afield.stacksl.study

import afield.stdlib.StudyEvidence
import afield.Box
import afield.displayOpen
import afield.registerUnownedStringCallback
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
 *    the study checks, translated by [StackSLStudyShader.webgl2]. The CPU reference still — what the
 *    model is shown, and what every measurement is taken from — is in the model's own tool results, so
 *    the card draws it only when there is no live preview: when the program does not translate.
 *  - **Knobs** — one of Field's sliders per number the person typed ([StackSLKnob]), labelled with
 *    what the number does ([purpose]). Sliders move uniforms, so the preview follows without a round
 *    trip; clicking a slider's label selects its number in the editor; **Keep these numbers** writes
 *    them into the program as a new revision through [StackSLStudy.setNumbers], which checks them
 *    like any other edit.
 *  - **Stages** — what each line leaves on top of the stack, small, so the program can be read as a
 *    sequence of pictures. Clicking one holds the window at that stage; the window can also follow the
 *    editor's cursor, showing what the line under it leaves ([StackSLLive.wanted]).
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
        registerUnownedStringCallback(actionId) { handleAction(it) }
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
     * the card; or `stage` and `follow`, which choose what the window shows, and `locate`, which puts
     * the editor on a knob's number or a line, and leave the card as it is.
     * Returns the status line the card will show; a refused action says why.
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
                // The window, not the program: the card shows the choice itself, so it is not rebuilt.
                "stage" -> {
                    val live = StackSLPrograms.live(owner)
                    val words = message["words"]?.asInt()
                    live.pinnedStage = words?.takeIf { it != live.pinnedStage }
                    return live.pinnedStage?.let { "the window shows the stage after word $it" } ?: "the window shows the whole program"
                }
                // A key: every number as the card holds it now, kept by name for this program's shape.
                // The card adds it in place, so the sliders keep what has not been kept.
                "capture-key" -> {
                    val revision = study.revisions.revision()
                    val values = message["values"].properties().associate { it.key.toInt() to it.value.asDouble() }
                    val name = message["name"]?.asText()?.let { afield.stdlib.StudyKeys.slug(it) } ?: afield.stdlib.StudyKeys.nextName(study.directory)
                    afield.stdlib.StudyKeys.save(study.directory, name, study.knobShape(revision.source, revision.value.knobs), values, "captured on the card from ${revision.id}")
                    return "captured $name"
                }
                "locate" -> {
                    val revision = study.revisions.revision()
                    val knob = message["knob"]?.asInt()?.let { revision.value.knobs.getOrNull(it) }
                    val line = knob?.line ?: message["line"]?.asInt() ?: return "nothing to show"
                    // The card's columns are the checked program's; while the editor holds an edit, only the line is sure.
                    val exact = knob != null && (owner.getPropertyRaw(StackSLPrograms.PROPERTY, null) as? String) == revision.source
                    val span = knob?.spans?.first()
                    afield.stdlib.BoxStudies.reveal(owner, StackSLPrograms.PROPERTY, line,
                        if (exact) span!!.column else -1, if (exact) span!!.column + span.length else -1)
                    return knob?.let { "k${it.knob} is line ${it.line} of the program" } ?: "line $line of the program"
                }
                "follow" -> {
                    StackSLPrograms.live(owner).followCursor = message["on"]?.asBoolean() == true
                    return if (StackSLPrograms.live(owner).followCursor) "the window follows the editor's line" else "the window shows the whole program"
                }
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
        // The model's evidence is in its chat; the card draws a still only when there is no live preview to show.
        val still = if (webgl == null) png(study.previewPng(r.id, 192, parallel = false)) else null
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
            "animated" to r.value.animated,
            "keys" to afield.stdlib.StudyKeys.config(study.directory, study.knobShape(r.source, knobs))
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
        val sourceLines = r.source.lines()
        // What each number does, named by the fast model once per program shape; until then (and in the tooltip), the compiler's operand.
        val names = study.knobNames(r.source, knobs, ask = afield.stdlib.KnobNames.shouldAsk(owner)) { refreshLater() }
        return buildString {
            append("<div id='$id' class='stacksl-study' onpointerdown='event.stopPropagation()' onmousedown='event.stopPropagation()' onclick='event.stopPropagation()' onwheel='event.stopPropagation()'>")
            append("""<style>
                #$id{font:13px/1.45 Iosevka,ui-monospace,monospace;color:#ddd;background:#0b0b0d;padding:14px 16px;width:${previewSize + 720}px;border-radius:6px}
                #$id .row{display:flex;gap:16px;align-items:flex-start}
                #$id .muted{color:#8a8a90} #$id .warn{color:#e5b35f} #$id .bad{color:#ec8a74}
                #$id h4{font-weight:400;margin:14px 0 6px;color:#aaa;letter-spacing:.06em;text-transform:uppercase;font-size:11px}
                #$id canvas,#$id img.still{width:${previewSize}px;height:${previewSize}px;image-rendering:auto;border:1px solid #333;background:#000}
                #$id .field-control{padding:2px 10px;margin:3px 0}
                #$id .field-slider,#$id .kcolour{max-width:560px}
                #$id .field-slider .field-label{width:250px;overflow:hidden;text-overflow:ellipsis}
                #$id .field-slider-visual{height:26px}
                #$id .field-value{min-width:56px}
                #$id .locate{cursor:pointer} #$id .locate:hover{color:var(--field-accent,#5ce1ff)}
                #$id .locate .purpose{text-transform:none;letter-spacing:0;opacity:.85}
                #$id .kcolour{display:flex;align-items:center;gap:10px}
                #$id .kcolour .field-label{width:250px;font-size:11px;color:var(--field-accent-text-soft)} #$id .kcolour input{flex:1}
                #$id .kline{margin:10px 0 2px;color:#b8b8be;font-size:12px;border-top:1px solid #222;padding-top:6px}
                #$id .kline.locate:hover{color:var(--field-accent,#5ce1ff)}
                #$id input[type=color]{width:100%;height:22px;border:1px solid #444;background:none;padding:0}
                #$id button{font:inherit;background:#222;color:#ddd;border:1px solid #444;padding:3px 10px;border-radius:4px;cursor:pointer}
                #$id button:hover{background:#333}
                #$id select{font:inherit;background:#222;color:#ddd;border:1px solid #444;padding:2px 6px;border-radius:4px}
                #$id .blendslider .field-label{width:auto}
                #$id .strip{display:flex;flex-wrap:wrap;gap:8px}
                #$id .cell{width:96px;font-size:11px;color:#aaa}
                #$id .cell img{width:96px;height:96px;border:1px solid #333;display:block;margin-bottom:3px}
                #$id .cell span{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                #$id .status{margin-top:10px;color:#9c9}
                #$id .cell.stage{cursor:pointer} #$id .cell.stage.pinned img{border-color:#5ce1ff}
            </style>""")
            append("<div><b>${esc(r.id)}</b> <span class='muted'>· ${esc(r.description.take(160))}</span></div>")
            append("<div class='muted'>${if (r.value.animated) "animated" else "still"} · ${r.value.words} words · ${r.value.stages.size} stages · ${r.value.knobs.size} knobs")
            if (r.value.nonFiniteFraction > 0) append(" · <span class='bad'>${"%.1f".format(r.value.nonFiniteFraction * 100)}% NaN pixels</span>")
            append("</div>")
            r.value.warnings.forEach { append("<div class='warn'>warning: ${esc(it)}</div>") }
            if (dirty) append("<div class='warn'>The editor holds an unchecked edit. <button data-act='checkin'>Check in the editor's program</button></div>")
            append("<div class='row' style='margin-top:10px'>")
            append("<div><canvas width='$previewSize' height='$previewSize'></canvas><div class='muted'><button data-act='pause'>pause</button> <span class='time'>t = 0.00 s</span> · live WebGL2 preview</div><div class='bad glerror'></div></div>")
            still?.let { append("<div><img class='still' src='$it'><div class='muted'>at t = ${StackSLStudyShader.REFERENCE_TIME} s · ${esc(study.rasterizer.description)}</div></div>") }
            append("<div style='flex:1;min-width:300px'>")
            append("<h4>Knobs</h4>")
            if (knobs.isEmpty()) append("<div class='muted'>This program has no typed numbers.</div>")
            // Grouped under the line they are on, headed by what that line says it does; a colour
            // literal also gets a swatch. Past about 24 numbers the remaining lines wait behind a
            // disclosure, so a long program's card stays readable and still reaches every number.
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
                append("<div class='kline locate' data-line='$line' title='show line $line in the editor'>line $line · ${esc(heading.take(80))}</div>")
                onLine.forEach { k ->
                    colours[k.knob]?.let { c ->
                        append("<div class='field-control kcolour'><span class='field-label locate' data-locate='${k.knob}' title='${esc(k.context)} — click to select it in the editor'>colour <span class='purpose'>${esc(k.token)}</span></span>")
                        append("<input type='color' data-knobs='${c.knobs.joinToString(",")}' value='${c.hex}'><span class='field-value'>${c.hex}</span></div>")
                    }
                    val (lo, hi, step) = range(k)
                    val operand = purpose(k, sourceLines.getOrElse(k.line - 1) { "" })
                    val label = "<span class='locate' data-locate='${k.knob}' title='${esc(operand)} · ${esc(k.context)} — click to select ${esc(k.token)} in the editor'>" +
                        "k${k.knob} <span class='purpose'>${esc(names[k.knob] ?: operand)}</span></span>"
                    append(afield.buildSliderHtml("$id-k${k.knob}", label, lo, hi, step, k.value, send = false, extraAttributes = "data-knob='${k.knob}'"))
                    shownCount++
                }
            }
            if (folded) append("</details>")
            if (knobs.isNotEmpty()) {
                append("<div style='margin-top:6px'><button data-act='keep'>Keep these numbers</button> <button data-act='reset'>Reset</button></div>")
                append(afield.stdlib.StudyKeys.section(afield.buildSliderHtml("$id-blend", "blend", 0.0, 1.0, 0.005, 0.0, send = false, extraAttributes = "data-blend='1'")))
            }
            append("</div></div>")
            if (stages.isNotEmpty()) {
                val unit = r.value.stages.firstOrNull()?.granularity ?: "line"
                val which = if (stages.size == r.value.stages.size) "each $unit" else "${stages.size} of ${r.value.stages.size} ${unit}s, evenly spaced"
                val live = StackSLPrograms.live(owner)
                append("<h4>Stages — the top of the stack after $which</h4>")
                append("<div class='muted' style='margin-bottom:6px'>Click a stage to show it in the window; click it again for the whole program. ")
                append("<label><input type='checkbox' class='follow'${if (live.followCursor) " checked" else ""}> the window shows the editor's line</label></div><div class='strip'>")
                stages.forEach { c ->
                    val pinned = if (live.pinnedStage == c.words) " pinned" else ""
                    append("<div class='cell stage$pinned' data-words='${c.words}' title='show this stage in the window'>${c.image?.let { "<img src='$it'>" } ?: "<div style='width:96px;height:96px;border:1px solid #333'></div>"}<span>${esc(c.label)}</span><span class='muted' title='${esc(c.text)}'>${esc(c.text)}</span></div>")
                }
                append("</div>")
            }
            if (variants.isNotEmpty()) {
                append("<h4>Variants held for adoption</h4><div class='strip'>")
                variants.forEach { (label, note, source) ->
                    val image = runCatching { png(StudyEvidence.presented(study.frame(source, 96, StackSLStudyShader.REFERENCE_TIME, parallel = false)).toPngBytes()) }.getOrNull()
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

    /** A stage tile: its label, its picture (a data URL), its line's text, and how many root words it has run. */
    private data class StageCell(val label: String, val image: String?, val text: String, val words: Int)

    private fun stageThumbnails(source: String, stages: List<StackSLStage>): List<StageCell> {
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
                    val (tile, _) = StudyEvidence.normalizedTile(raw, "", "")
                    val bytes = afield.util.PngEncoder.encode(tile.argb!!, tile.width, tile.height)
                    png(bytes)
                }
            }.getOrNull()
            StageCell("${if (stage.granularity == "line") "line" else "word"} ${if (stage.granularity == "line") stage.line else stage.stage}" +
                (stored?.let { " → $it" } ?: ""), image, stage.text, stage.wordsThrough)
        }
    }

    companion object {
        private const val VISIBLE_KNOBS = 24

        /** Arithmetic operands said as what the number does to the value under it. */
        private val VERBS = mapOf(
            "multiply a" to "multiplies", "multiply b" to "multiplies", "add a" to "adds", "add b" to "adds",
            "subtract b" to "subtracts", "safe divide b" to "divides by"
        )

        private val NUMBER = Regex("""-?(?:\d+\.?\d*|\.\d+)""")

        /** [knobs] of [source] as the language-neutral [afield.stdlib.StudyKnob]s names and keys are built on. */
        internal fun studyKnobs(source: String, knobs: List<StackSLKnob>): List<afield.stdlib.StudyKnob> {
            val lines = source.lines()
            return knobs.map { afield.stdlib.StudyKnob(it.knob, it.line, it.token, purpose(it, lines.getOrElse(it.line - 1) { "" }), it.value) }
        }

        /**
         * What [k]'s number does, in a few words, for its slider's label: the operand the compiler took it
         * as ([StackSLKnob.takenAs] — `mix amount`, `smoothstep high`), with arithmetic said as a verb
         * (`multiplies`); for a component of a vector literal, which one, of what (`x of palette a`); or
         * that nothing took it. [line] is the knob's source line.
         */
        internal fun purpose(k: StackSLKnob, line: String): String {
            if (k.role != "number" && k.role != "vec literal") return k.role
            val taken = k.takenAs?.let { VERBS[it] ?: it.takeIf { ' ' in it } ?: "into $it" }
            if (!k.token.startsWith("[")) return taken ?: "left on the stack"
            // Which components: where the knob's digits sit in the literal, on its line.
            val start = generateSequence(line.indexOf(k.token).takeIf { it >= 0 }) { line.indexOf(k.token, it + 1).takeIf { i -> i >= 0 } }
                .firstOrNull { k.column - 1 in it until it + k.token.length }
            val numbers = NUMBER.findAll(k.token).map { it.range.first }.toList()
            val components = start?.let { s -> k.spans.map { span -> numbers.indexOf(span.column - 1 - s) } }
                ?.takeIf { it.all { i -> i in 0..3 } }?.joinToString("") { "xyzw"[it].toString() }
            return "${components ?: "part"} of ${k.takenAs ?: k.token}"
        }

        /** The card's browser half: WebGL2 preview, sliders, and actions posted back through `handleStringInput`. */
        private val SCRIPT = """
            const root=document.getElementById(C.id); if(!root) return;
            const send=(o)=>{ if(window.handleStringInput) window.handleStringInput({id:C.action,value:JSON.stringify(o)}); };
            const canvas=root.querySelector('canvas'); const err=root.querySelector('.glerror'); const timeLabel=root.querySelector('.time');
            const values=(C.wires||[]).map(w=>w[1]);
            let paused=false, offset=0, start=performance.now();
            function fail(m){ err.textContent='Live preview unavailable: '+m+'.'; canvas.style.display='none'; }
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
            // Field's own sliders (sliders.js), kept off the wire: each move is announced as field-slider-input.
            const sliders=[...root.querySelectorAll('.field-slider[data-knob]')];
            const original=new Map(sliders.map(s=>[s.dataset.knob,parseFloat(s.dataset.value)]));
            const changed=new Map();
            const setWire=(k,v)=>{ const w=C.knobWires[k]; if(w!==undefined) values[w]=v; };
            root.addEventListener('field-slider-input',(ev)=>{ const s=ev.target.closest&&ev.target.closest('.field-slider[data-knob]'); if(!s) return;
              changed.set(s.dataset.knob,ev.detail.value); setWire(s.dataset.knob,ev.detail.value); });
            const setKnob=(s,v)=>{ if(window.updateFieldSlider) window.updateFieldSlider(s,v); else { changed.set(s.dataset.knob,v); setWire(s.dataset.knob,v); } };
            // A slider's label, or a line's heading, puts the editor on its number or its line.
            root.querySelectorAll('[data-locate]').forEach(el=>el.addEventListener('click',(ev)=>{ ev.stopPropagation(); send({action:'locate',knob:parseInt(el.dataset.locate)}); }));
            root.querySelectorAll('.kline[data-line]').forEach(el=>el.addEventListener('click',(ev)=>{ ev.stopPropagation(); send({action:'locate',line:parseInt(el.dataset.line)}); }));
            // A colour swatch is sRGB, as the window shows it; the literal it sets is linear light.
            const toLinear=(c)=>{ c=c/255; return c<=0.04045 ? c/12.92 : Math.pow((c+0.055)/1.055, 2.4); };
            root.querySelectorAll('input[type=color]').forEach(picker=>picker.addEventListener('input',()=>{
              const hex=picker.value; const rgb=[1,3,5].map(i=>toLinear(parseInt(hex.substr(i,2),16)));
              picker.dataset.knobs.split(',').forEach((k,i)=>{ const s=sliders.find(x=>x.dataset.knob===k); if(s) setKnob(s,Math.round(rgb[i]*1000)/1000); });
              picker.parentElement.querySelector('.field-value').textContent=hex;
            }));
            // A stage shown in the window: one at a time, the same tile again for the whole program.
            root.querySelectorAll('.cell.stage').forEach(c=>c.addEventListener('click',(ev)=>{ ev.stopPropagation();
              const on=!c.classList.contains('pinned'); root.querySelectorAll('.cell.stage').forEach(x=>x.classList.remove('pinned'));
              if(on) c.classList.add('pinned'); send({action:'stage',words:parseInt(c.dataset.words)}); }));
            root.querySelectorAll('input.follow').forEach(f=>f.addEventListener('change',(ev)=>{ ev.stopPropagation(); send({action:'follow',on:f.checked}); }));
            // Keys (StudyKeys.SCRIPT): every knob's number now, and moving the sliders to a set of them.
            const knobValues=()=>{ const o={}; sliders.forEach(s=>{ o[s.dataset.knob]=parseFloat(s.dataset.value); }); return o; };
            const applyValues=(vals)=>{ Object.entries(vals).forEach(([k,v])=>{ const s=sliders.find(x=>x.dataset.knob===k); if(s) setKnob(s,v); }); };
            const blendValue=(a,b,t)=>a+(b-a)*t;
""".trimIndent() + "\n" + afield.stdlib.StudyKeys.SCRIPT.trimIndent() + "\n" + """
            root.querySelectorAll('button[data-act]').forEach(b=>b.addEventListener('click',(ev)=>{
              ev.stopPropagation(); const act=b.dataset.act;
              if(act==='pause'){ const now=performance.now(); if(paused){ start=now; paused=false; b.textContent='pause'; } else { offset=offset+(now-start)/1000; paused=true; b.textContent='play'; } return; }
              // Back to the typed numbers exactly: a slider snaps what it is given to its step, so the wire and the record are set after it.
              if(act==='reset'){ sliders.forEach(s=>{ const k=s.dataset.knob, v=original.get(k); setKnob(s,v); s.dataset.value=String(v); setWire(k,v); }); changed.clear(); return; }
              if(act==='keep'){ const out={}; changed.forEach((v,k)=>{ if(Math.abs(v-original.get(k))>1e-12) out[k]=v; }); if(Object.keys(out).length===0) return; b.disabled=true; send({action:'keep',revision:C.revision,sourceHash:C.checkedHash,values:out}); return; }
              b.disabled=true;
              send({action:act,revision:C.revision,sourceHash:C.checkedHash,label:b.dataset.label,id:b.dataset.id});
            }));
        """.trimIndent()
    }
}
