package afield.stacksl.study

import afield.Box
import afield.RunsAt
import afield.buildSliderHtml
import afield.plugins
import afield.registerSliderCallback
import afield.registerStringCallback
import afield.stdlib.BoxStudies
import afield.stdlib.KnobNames
import afield.stdlib.StackAutoNumbers
import afield.stdlib.StudyKeys
import afield.unregisterSliderCallback
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The numbers of a box's `stack.auto` slots, as sliders under the box: turn one and the box's own
 * shader — the whole of it, in its own window — redraws with it, because a slider writes the slot's
 * uniform block ([StackAutoNumbers]) and nothing is recompiled.
 *
 * It is the StackSL study card's knobs, for StackSL that lives inside someone else's shader. Each slot
 * with numbers gets a section: sliders grouped under the line they are on, labelled by the fast model
 * from what the compiler took each number as ([KnobNames]); a label click selects the number inside the
 * Kotlin string in the editor; keys ([StudyKeys]) capture and blend every number of the slot; **Keep
 * these numbers** writes them into the StackSL where it sits in the box's `code`, when it sits there
 * verbatim; **Reset** puts the literals back.
 *
 * **Show it in your shader** is the end result: the picture of wherever the slot's module is drawn,
 * found by walking what the renderer draws ([afield.four.ModuleDestinations]) — the window, with
 * everything else it draws, or the render-to-texture the module is in — at the slot's current numbers.
 * A compute module draws nowhere, and the card says so rather than show a stand-in.
 *
 * **Render this function alone** is the debugging view: the slot's program by itself, at its current
 * numbers, in the StackSL study's frame — `input` is `vec4(uv, hash, 0, time)` on a unit square — drawn
 * on the box's renderer (the CPU reference without one). That is not what the slot sees in the host
 * shader, and the card says so; a slot that samples a texture, or reads the host's push constants,
 * cannot be drawn alone.
 */
object StackAutoCard {
    /** The output channel the card is drawn in. */
    const val CHANNEL = "stack.auto numbers"

    private val mapper = jacksonObjectMapper()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "stack-auto-card").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap.newKeySet<Box>()
    private val sliders = ConcurrentHashMap<Box, List<String>>()
    private val actions = ConcurrentHashMap<Box, String>()
    private val status = ConcurrentHashMap<Box, String>()
    private val alone = ConcurrentHashMap<String, Pair<String?, String>>()

    /** Redraw [box]'s card soon, on the card's own thread; requests while one waits collapse into it. Boxes in no open document have none. */
    fun refreshLater(box: Box) {
        if (BoxStudies.documentOf(box) == null) return
        if (pending.add(box)) worker.execute {
            pending.remove(box)
            runCatching { BoxStudies.show(box, CHANNEL, html(box)) }
                .onFailure { e -> afield.logger.lprintln("warning,study", "stack.auto numbers card for '${box.name}': ${e.message}") }
        }
    }

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun directory(box: Box, slot: String): File? = runCatching { File(BoxStudies.directory(box), "stack.auto/$slot") }.getOrNull()

    private fun knobs(slot: StackAutoNumbers.Slot): List<StackSLKnob> =
        if (slot.numbers.isEmpty() || slot.source.isBlank()) emptyList() else runCatching { StackSLStudyShader.knobs(slot.source, slot.entrypoint) }.getOrDefault(emptyList())

    private fun namingKnobs(source: String, knobs: List<StackSLKnob>): List<KnobNames.Knob> {
        val lines = source.lines()
        return knobs.map { KnobNames.Knob(it.knob, it.line, it.token, StackSLStudyViewer.purpose(it, lines.getOrElse(it.line - 1) { "" })) }
    }

    /** [box]'s `code`, or null when it has none (a box whose slots are bound from elsewhere). */
    private fun codeOf(box: Box): String? = box.getPropertyRaw("code", null) as? String

    /** Where [source] sits in [box]'s `code`, when it sits there exactly once: its offset. */
    private fun offsetInCode(box: Box, source: String): Int? {
        val code = codeOf(box) ?: return null
        val at = code.indexOf(source)
        return at.takeIf { it >= 0 && code.indexOf(source, at + 1) < 0 }
    }

    /** Line [line], column [column] (1-based) of [source], as a line and column of [box]'s `code`. */
    private fun inCode(box: Box, source: String, line: Int, column: Int): Pair<Int, Int>? {
        val at = offsetInCode(box, source) ?: return null
        val before = codeOf(box)!!.substring(0, at)
        val startLine = before.count { it == '\n' } + 1
        val startColumn = at - before.lastIndexOf('\n')
        return (startLine + line - 1) to (if (line == 1) startColumn + column - 1 else column)
    }

    private fun current(box: Box, slot: StackAutoNumbers.Slot, k: StackSLKnob) = StackAutoNumbers.value(box, slot.name, k.wire) ?: k.value

    /** The card's HTML: a section per slot with numbers. */
    fun html(box: Box): String {
        val id = "sa" + UUID.randomUUID().toString().replace("-", "").take(10)
        val action = actions.getOrPut(box) { "stack-auto-card-${UUID.randomUUID()}".also { a -> registerStringCallback(a) { handle(box, it) } } }
        sliders.remove(box)?.forEach(::unregisterSliderCallback)
        val registered = mutableListOf<String>()
        // Every bound slot has a section — its picture in the shader is worth having without a number to turn.
        val slots = StackAutoNumbers.slots(box).values.filter { it.source.isNotBlank() }.sortedBy { it.name }.map { it to knobs(it) }
        if (slots.isEmpty()) return "<div style='font:13px Iosevka,monospace;color:#8a8a90;padding:8px'>No stack.auto slot of this box has a program yet.</div>"
        val keysConfig = mutableMapOf<String, Any?>()
        val body = buildString {
            slots.forEach { (slot, knobs) ->
                val lines = slot.source.lines()
                val dir = directory(box, slot.name)
                val names = KnobNames.names("StackSL", StackSLStudy.ABOUT_FOR_NAMES, slot.source, namingKnobs(slot.source, knobs), dir,
                    KnobNames.shouldAsk(box)) { refreshLater(box) }.orEmpty()
                val inCode = offsetInCode(box, slot.source) != null
                append("<div class='slot' data-slot='${slot.name}'>")
                append("<div><b>stack.auto.${esc(slot.name)}</b> <span class='muted'>· ${if (knobs.isEmpty()) "no numbers to turn" else "${knobs.size} numbers · ${if (slot.live) "live" else "as compiled"}"} · the window shows your whole shader</span></div>")
                if (!inCode) append("<div class='warn'>This program is not written out in the box's code, so Keep cannot put numbers back into it.</div>")
                knobs.groupBy { it.line }.forEach { (line, onLine) ->
                    val heading = lines.getOrNull(line - 2)?.trim()?.takeIf { it.startsWith("#") }?.removePrefix("#")?.trim()
                        ?: lines.getOrNull(line - 1)?.substringBefore('#')?.trim().orEmpty()
                    append("<div class='kline locate' data-slot='${slot.name}' data-line='$line'>line $line · ${esc(heading.take(80))}</div>")
                    onLine.forEach { k ->
                        val operand = StackSLStudyViewer.purpose(k, lines.getOrElse(k.line - 1) { "" })
                        val v = current(box, slot, k)
                        val lo = if (k.value > 0) 0.0 else if (k.value < 0) k.value * 4 else -1.0
                        val hi = if (k.value > 0) maxOf(1.0, k.value * 4) else if (k.value < 0) -k.value * 4 else 1.0
                        val raw = (hi - lo) / 400
                        val magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)))
                        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw * 0.999 }
                        val sliderId = "$id-${slot.name}-k${k.knob}"
                        registerSliderCallback(sliderId) { value -> StackAutoNumbers.set(box, slot.name, k.wire, value) }
                        registered += sliderId
                        val label = "<span class='locate' data-slot='${slot.name}' data-locate='${k.knob}' title='${esc(operand)} · ${esc(k.context)} — click to select ${esc(k.token)} in the editor'>" +
                            "k${k.knob} <span class='purpose'>${esc(names[k.knob] ?: operand)}</span></span>"
                        append(buildSliderHtml(sliderId, label, minOf(lo, v), maxOf(hi, v), step, v, extraAttributes = "data-knob='${k.knob}' data-slot='${slot.name}'"))
                    }
                }
                append("<div style='margin-top:6px'>")
                if (inCode && knobs.isNotEmpty()) append("<button data-act='keep' data-slot='${slot.name}'>Keep these numbers</button> ")
                if (knobs.isNotEmpty()) append("<button data-act='reset' data-slot='${slot.name}'>Reset</button> ")
                append("<button data-act='in-shader' data-slot='${slot.name}'>Show it in your shader</button> " +
                    "<button data-act='alone' data-slot='${slot.name}'>Render this function alone</button></div>")
                val views = listOfNotNull(inShader["${box.id}|${slot.name}"], alone["${box.id}|${slot.name}"])
                if (views.isNotEmpty()) {
                    append("<div class='views'>")
                    views.forEach { (image, note) -> append("<div class='alone'>${image?.let { "<img src='$it'>" } ?: ""}<div class='muted'>${esc(note)}</div></div>") }
                    append("</div>")
                }
                dir?.takeIf { knobs.isNotEmpty() }?.let { d ->
                    val shape = KnobNames.shape("StackSL", slot.source, namingKnobs(slot.source, knobs))
                    keysConfig[slot.name] = StudyKeys.config(d, shape)
                    append("<div class='slotkeys' data-slot='${slot.name}'>")
                    append(StudyKeys.section(buildSliderHtml("$id-${slot.name}-blend", "blend", 0.0, 1.0, 0.005, 0.0, send = false, extraAttributes = "data-blend='1'")))
                    append("</div>")
                }
                append("</div>")
            }
            status[box]?.takeIf { it.isNotBlank() }?.let { append("<div class='status'>${esc(it)}</div>") }
        }
        sliders[box] = registered
        val config = mapper.writeValueAsString(mapOf("id" to id, "action" to action, "keys" to keysConfig))
        return buildString {
            append("<div id='$id' class='stack-auto-card' onpointerdown='event.stopPropagation()' onmousedown='event.stopPropagation()' onclick='event.stopPropagation()' onwheel='event.stopPropagation()'>")
            append("""<style>
                #$id{font:13px/1.45 Iosevka,ui-monospace,monospace;color:#ddd;background:#0b0b0d;padding:12px 14px;width:640px;border-radius:6px}
                #$id .muted{color:#8a8a90} #$id .warn{color:#e5b35f} #$id .status{margin-top:8px;color:#9c9}
                #$id h4{font-weight:400;margin:12px 0 6px;color:#aaa;letter-spacing:.06em;text-transform:uppercase;font-size:11px}
                #$id .slot+.slot{margin-top:14px;border-top:1px solid #333;padding-top:10px}
                #$id .field-control{padding:2px 10px;margin:3px 0}
                #$id .field-slider{max-width:600px}
                #$id .field-slider .field-label{width:250px;overflow:hidden;text-overflow:ellipsis}
                #$id .field-slider-visual{height:26px}
                #$id .field-value{min-width:56px}
                #$id .locate{cursor:pointer} #$id .locate:hover{color:var(--field-accent,#5ce1ff)}
                #$id .locate .purpose{text-transform:none;letter-spacing:0;opacity:.85}
                #$id .kline{margin:10px 0 2px;color:#b8b8be;font-size:12px;border-top:1px solid #222;padding-top:6px}
                #$id button{font:inherit;background:#222;color:#ddd;border:1px solid #444;padding:3px 10px;border-radius:4px;cursor:pointer}
                #$id button:hover{background:#333}
                #$id select{font:inherit;background:#222;color:#ddd;border:1px solid #444;padding:2px 6px;border-radius:4px}
                #$id .blendslider .field-label{width:auto}
                #$id .views{display:flex;gap:12px;align-items:flex-start;margin-top:8px}
                #$id .alone{flex:1;min-width:0} #$id .alone img{max-width:100%;max-height:256px;border:1px solid #333;display:block;margin-bottom:4px}
            </style>""")
            append(body)
            append("<script>(function(){const CARD=$config;").append(SCRIPT).append("})();</script></div>")
        }
    }

    /** One browser action — `keep`, `reset`, `in-shader`, `alone`, `locate` or `capture-key` — on [box]'s slot; returns what the card says. */
    internal fun handle(box: Box, json: String): String {
        val message = runCatching { mapper.readTree(json) }.getOrNull() ?: return "unreadable action"
        val slotName = message["slot"]?.asText() ?: return "no slot"
        val slot = StackAutoNumbers.slots(box)[slotName] ?: return "no slot $slotName"
        val knobs = knobs(slot)
        val result = try {
            when (message["action"]?.asText()) {
                "locate" -> {
                    val k = message["knob"]?.asInt()?.let { knobs.getOrNull(it) }
                    val line = k?.line ?: message["line"]?.asInt() ?: return "nothing to show"
                    val at = inCode(box, slot.source, line, k?.column ?: 1) ?: return "this program is not written out in the box's code"
                    val span = k?.spans?.first()
                    BoxStudies.reveal(box, "code", at.first, if (span != null) at.second else -1, if (span != null) at.second + span.length else -1)
                    return k?.let { "k${it.knob} is line ${at.first} of the box's code" } ?: "line ${at.first} of the box's code"
                }
                "reset" -> { StackAutoNumbers.reset(box, slotName); "stack.auto.$slotName is back to its literals" }
                "keep" -> {
                    val values = knobs.mapNotNull { k -> StackAutoNumbers.value(box, slotName, k.wire)?.takeIf { kotlin.math.abs(it - k.value) > 1e-12 }?.let { k.knob to it } }.toMap()
                    if (values.isEmpty()) return "nothing to keep: every number is as written"
                    val rewritten = StackSLStudyShader.setKnobs(slot.source, values, slot.entrypoint)
                    val code = codeOf(box) ?: return "this box has no code to keep the numbers in"
                    val at = offsetInCode(box, slot.source) ?: return "this program is not written out in the box's code"
                    val next = code.substring(0, at) + rewritten + code.substring(at + slot.source.length)
                    if (!BoxStudies.replacer(box, "code")(code, next)) return "the box's code changed; Keep again"
                    "kept ${values.size} numbers in the box's code; run the box to make them its own"
                }
                "alone" -> {
                    worker.execute { renderAlone(box, slot, knobs); refreshLater(box) }
                    return "rendering stack.auto.$slotName alone"
                }
                "in-shader" -> {
                    worker.execute { captureInShader(box, slot); refreshLater(box) }
                    return "capturing stack.auto.$slotName in your shader"
                }
                "capture-key" -> {
                    val dir = directory(box, slotName) ?: return "the box is in no open document"
                    val values = message["values"].properties().associate { it.key.toInt() to it.value.asDouble() }
                    val name = message["name"]?.asText()?.let { StudyKeys.slug(it) } ?: StudyKeys.nextName(dir)
                    StudyKeys.save(dir, name, KnobNames.shape("StackSL", slot.source, namingKnobs(slot.source, knobs)), values, "captured on the card")
                    return "captured $name"
                }
                else -> "unknown action"
            }
        } catch (e: Exception) {
            "not done: ${e.message}"
        }
        status[box] = result
        refreshLater(box)
        return result
    }

    private val inShader = ConcurrentHashMap<String, Pair<String?, String>>()

    /**
     * Capture where [slot]'s module draws — the end result, with the host shader and whatever else draws
     * there — for the card. Several destinations are named and the first is shown; none, or a compute
     * module, is said plainly.
     */
    private fun captureInShader(box: Box, slot: StackAutoNumbers.Slot) {
        inShader["${box.id}|${slot.name}"] = StackAutoView.inShader(box, slot.name).let { it.dataUrl to it.note }
    }

    /** Draw [slot]'s program by itself at its current numbers, for the card; what it could not do, it says. */
    private fun renderAlone(box: Box, slot: StackAutoNumbers.Slot, knobs: List<StackSLKnob>) {
        val changed = knobs.mapNotNull { k -> StackAutoNumbers.value(box, slot.name, k.wire)?.takeIf { kotlin.math.abs(it - k.value) > 1e-12 }?.let { k.knob to it } }.toMap()
        alone["${box.id}|${slot.name}"] = StackAutoView.alone(box, slot.name, slot.source, slot.entrypoint, changed).let { it.dataUrl to it.note }
    }

    /** The card's browser half: keys per slot (StudyKeys.SCRIPT, once per slot), and the buttons. */
    private val SCRIPT = """
        const card=document.getElementById(CARD.id); if(!card) return;
        const post=(o)=>{ if(window.handleStringInput) window.handleStringInput({id:CARD.action,value:JSON.stringify(o)}); };
        card.querySelectorAll('[data-locate]').forEach(el=>el.addEventListener('click',(ev)=>{ ev.stopPropagation(); post({action:'locate',slot:el.dataset.slot,knob:parseInt(el.dataset.locate)}); }));
        card.querySelectorAll('.kline[data-line]').forEach(el=>el.addEventListener('click',(ev)=>{ ev.stopPropagation(); post({action:'locate',slot:el.dataset.slot,line:parseInt(el.dataset.line)}); }));
        card.querySelectorAll('button[data-act]').forEach(b=>b.addEventListener('click',(ev)=>{ ev.stopPropagation(); post({action:b.dataset.act,slot:b.dataset.slot}); }));
        // Each slot's keys, scoped to its own section and sliders.
        card.querySelectorAll('.slotkeys').forEach(section=>{ (function(root, slot){
          const C={keys:(CARD.keys||{})[slot]||[]};
          const send=(o)=>post(Object.assign({slot:slot},o));
          const own=[...card.querySelectorAll('.field-slider[data-knob][data-slot="'+slot+'"]')];
          // Keys move the real sliders, which send as a drag does, so the window follows.
          const knobValues=()=>{ const o={}; own.forEach(s=>{ o[s.dataset.knob]=parseFloat(s.dataset.value); }); return o; };
          const applyValues=(vals)=>{ Object.entries(vals).forEach(([k,v])=>{ const s=own.find(x=>x.dataset.knob===k); if(s&&window.updateFieldSlider) window.updateFieldSlider(s,v,{send:true}); }); };
          const blendValue=(a,b,t)=>a+(b-a)*t;
""" + StudyKeys.SCRIPT + """
        })(section, section.dataset.slot); });
    """.trimIndent()
}

/** The box's `stack.auto` numbers card redraws whenever one of its slots compiles new StackSL. */
@RunsAt(plugins)
fun installStackAutoCard() {
    StackAutoNumbers.onCompiled += { slot -> StackAutoCard.refreshLater(slot.box) }
}
