@file:afield.runtime.completion.CompletionCategory("Search")

package afield.study.search

import afield.document.graph.Box
import afield.study.source.BoxStudies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import afield.ui.output.registerUnownedStringCallback
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Interactive evolution, where the eye is the fitness function: a grid of tiles in a box's output
 * panel (the parents first, then their children), and clicking [EyeEvolution.pick] tiles makes
 * them the next parents and breeds again. This is the loop of Karl Sims' *Genetic Images* and of
 * Dawkins' biomorphs; [afield.lang.rpn.ColorRpn] programs (the madp GA demo) are one genome for it,
 * and any genome with a picture works — StackSL programs, FLines, synth patches drawn as spectra.
 *
 * ```kotlin
 * val evolution = evolveByEye(
 *     parents = listOf(a, b),
 *     breed = { parents, rng -> Offspring(myCrossover(parents[0], parents[1], rng), "cross") },
 *     picture = { g -> pngOf(g) },
 *     text = { g -> g.toString() },
 * )
 * ```
 *
 * A genome that already has [GenomeOps] can pass them instead of `breed` (the other [evolveByEye]):
 * children come from [GenomeOps.crossover], or a random one of [GenomeOps.mutations] when it
 * declines, and **Fresh parents** draws [GenomeOps.random].
 *
 * Box code drives the same loop the clicks do: [pick], [reroll], [fresh] and [restart]. The browser
 * half posts its clicks as JSON over Field's `string-update` control message to a callback this
 * object registers, the way the StackSL study viewer does; the grid is rebuilt on a background
 * thread, and [onPick] runs there too.
 */
class EyeEvolution<G> internal constructor(
    /** The box whose output panel holds the grid. */
    val owner: Box,
    /** The parents a run starts from, and [restart] returns to. */
    val start: List<G>,
    private val breed: (List<G>, Random) -> Offspring<G>,
    private val picture: (G) -> ByteArray,
    private val text: (G) -> String,
    private val meta: (G) -> String,
    private val freshParent: ((Random) -> G)?,
    /** How many children each generation has, after the parents. */
    val children: Int,
    /** How many tiles a click selection takes. */
    val pick: Int,
    /** How many tiles a grid row holds. */
    val columns: Int,
    /** The output channel that holds the grid. */
    val channel: String,
    /** The word in the grid's title bar. */
    val title: String,
    /** The settings the toolbar shows, editable there. */
    val settings: List<EyeSetting>,
    seed: Long?,
) {
    private val rng: Random = seed?.let { Random(it) } ?: Random.Default
    private val actionId = "eye-evolution-${UUID.randomUUID()}"
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "eye-evolution").apply { isDaemon = true } }
    private val lock = Any()

    /** Called with the new parents after every selection, on the grid's thread. */
    var onPick: (List<G>) -> Unit = {}

    /** The current parents. */
    var parents: List<G> = start
        private set

    /** The current tiles, parents first. */
    var tiles: List<EyeTile<G>> = emptyList()
        private set

    /** Every set of parents so far, oldest first; the current one last. */
    val history: MutableList<List<G>> = mutableListOf(start)

    /** How many generations have been bred, counting the first. */
    var generation: Int = 0
        private set

    private var status = ""
    private var log = "pick $pick tiles to breed the next generation"

    init {
        require(start.isNotEmpty()) { "evolveByEye needs at least one parent" }
        require(pick >= 1) { "pick must be at least 1, was $pick" }
        registerUnownedStringCallback(actionId) { json -> worker.execute { handle(json) } }
    }

    /** Make tiles [indices] (0 is the first parent) the parents, breed, and show the new grid. */
    fun pick(vararg indices: Int) {
        val chosen = synchronized(lock) {
            require(indices.isNotEmpty()) { "pick at least one tile" }
            indices.map { i ->
                require(i in tiles.indices) { "there is no tile $i; the grid has ${tiles.size}" }
                tiles[i].genome
            }
        }
        synchronized(lock) {
            parents = chosen
            history += chosen
        }
        log = "new parents: tiles ${indices.joinToString(" and ")}"
        breedAndShow("selection")
        onPick(chosen)
    }

    /** New children of the same parents. */
    fun reroll() = breedAndShow("reroll")

    /** Random parents, when the genome can draw them; otherwise a reroll. */
    fun fresh() {
        val f = freshParent ?: return reroll()
        synchronized(lock) {
            parents = parents.map { _ -> f(rng) }
            history += parents
        }
        breedAndShow("fresh")
    }

    /** Back to the parents the code started from. */
    fun restart() {
        synchronized(lock) {
            parents = start
            history += start
        }
        breedAndShow("restart")
    }

    /** Breed a generation from [parents] and show it. Runs on the calling thread. */
    fun breedAndShow(reason: String = "start") {
        val started = System.nanoTime()
        val html = synchronized(lock) {
            val labels = if (parents.size == 2) listOf("parent a", "parent b") else parents.indices.map { "parent ${it + 1}" }
            val next = parents.mapIndexed { i, p -> EyeTile(p, labels[i], "seed", true) } +
                List(children) { i -> breed(parents, rng).let { EyeTile(it.genome, "child ${i + 1}", it.note, false) } }
            tiles = next
            generation++
            val cache = HashMap<String, String>()
            val shown = next.map { tile ->
                val key = text(tile.genome)
                val cached = cache.containsKey(key)
                val src = cache.getOrPut(key) { "data:image/png;base64," + Base64.getEncoder().encodeToString(picture(tile.genome)) }
                Shown(tile, src, cached)
            }
            status = "ready in ${(System.nanoTime() - started) / 1_000_000}ms · $reason"
            html(shown)
        }
        BoxStudies.show(owner, channel, html)
    }

    private class Shown<G>(val tile: EyeTile<G>, val src: String, val cached: Boolean)

    private fun handle(json: String) {
        runCatching {
            val o = Json.parseToJsonElement(json) as JsonObject
            when (o["act"]?.jsonPrimitive?.content) {
                "pick" -> pick(*(o["tiles"] as JsonArray).mapNotNull { it.jsonPrimitive.intOrNull }.toIntArray())
                "reroll" -> reroll()
                "fresh" -> fresh()
                "restart" -> restart()
                "set" -> {
                    val name = o["name"]?.jsonPrimitive?.content
                    val value = o["value"]?.jsonPrimitive?.doubleOrNull
                    val s = settings.firstOrNull { it.name == name }
                    if (s != null && value != null) {
                        s.value = value.coerceIn(s.min, s.max)
                        s.onChange(s.value)
                        breedAndShow("${s.name} ${EyeSetting.format(s.value)}")
                    }
                }
            }
        }.onFailure { e ->
            log = "could not do that: ${e.message}"
            runCatching { BoxStudies.show(owner, channel, synchronized(lock) { html(tiles.map { Shown(it, "", false) }) }) }
        }
    }

    private fun html(shown: List<Shown<G>>): String {
        val id = "eye-${UUID.randomUUID()}"
        return buildString {
            append("<div class=\"eye-evolution\" id=\"$id\"><style>")
            append(CSS.replace("#ID", "#$id").replace("COLUMNS", "$columns"))
            append("</style><div class=\"bar\"><span class=\"title\">").append(escape(title)).append("</span><span class=\"sep\">|</span>")
            append("<button data-act=\"reroll\">Reroll</button>")
            if (freshParent != null) append("<button data-act=\"fresh\">Fresh Parents</button>")
            append("<button data-act=\"restart\" title=\"back to the parents in the code\">Start over</button>")
            if (settings.isNotEmpty()) append("<span class=\"sep\">|</span>")
            for (s in settings) {
                append("<label>").append(escape(s.label)).append(' ')
                if (s.choices.isNotEmpty()) {
                    append("<select data-setting=\"${escape(s.name)}\">")
                    for (c in s.choices) append("<option value=\"$c\"${if (c == s.value) " selected" else ""}>${EyeSetting.format(c)}</option>")
                    append("</select>")
                } else {
                    append("<input data-setting=\"${escape(s.name)}\" type=\"number\" min=\"${s.min}\" max=\"${s.max}\" step=\"${s.step}\" value=\"${EyeSetting.format(s.value)}\">")
                }
                append("</label>")
            }
            append("<span class=\"status\">").append(escape(status)).append("</span></div><div class=\"grid\">")
            shown.forEachIndexed { i, s ->
                val t = s.tile
                append("<article class=\"tile${if (t.isParent) " parent" else ""}\" data-index=\"$i\">")
                append("<div class=\"head\"><span class=\"badge\">${if (t.isParent) "seed" else "mut"}</span>")
                append("<span class=\"name\">").append(escape("${t.label}  ${t.note}")).append("</span>")
                append("<span class=\"meta\">").append(escape(meta(t.genome))).append("</span></div>")
                append("<div class=\"pic\">")
                if (s.src.isNotEmpty()) append("<img src=\"${s.src}\" alt=\"").append(escape(text(t.genome))).append("\">")
                append("<span class=\"overlay\">${if (t.isParent) "seed" else if (s.cached) "cached" else "done"}</span></div>")
                append("<div class=\"code\">").append(escape(clip(text(t.genome).replace('\n', ' '), 108))).append("</div></article>")
            }
            append("</div><div class=\"parents\">")
            synchronized(lock) { parents }.forEachIndexed { i, p ->
                append("<div><span class=\"label\">${if (parents.size == 2) "parent ${"ab"[i]}" else "parent ${i + 1}"}</span><pre>")
                append(escape(text(p))).append("</pre></div>")
            }
            append("</div><div class=\"log\">").append(escape(log)).append(" · generation $generation</div>")
            append("<script>(function(){const C={id:\"$id\",action:\"$actionId\",pick:$pick};").append(SCRIPT).append("})();</script></div>")
        }
    }

    private companion object {
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

        /** The madp GA page's look: near-black ground, Iosevka, blue parents, amber selection. */
        val CSS = """
            #ID{background:#0a0a0a;color:#b8b8b8;font-family:'Iosevka Web','Iosevka',monospace;font-size:12px;padding:0 0 2px;width:max-content}
            #ID .bar{display:flex;align-items:center;gap:8px;padding:6px 10px;background:#111;border-bottom:1px solid #1e1e1e;flex-wrap:wrap}
            #ID .title{color:#4a4a4a;letter-spacing:2px;text-transform:uppercase;font-size:11px}
            #ID .sep{color:#2a2a2a}
            #ID button,#ID input,#ID select{background:#171717;color:#909090;border:1px solid #272727;padding:3px 8px;font:inherit;font-size:12px}
            #ID button{cursor:pointer}#ID button:hover{color:#d8d8d8;border-color:#3a3a3a;background:#1b1b1b}
            #ID input,#ID select{width:64px;text-align:center}
            #ID label{display:inline-flex;align-items:center;gap:4px;color:#7a7a7a}
            #ID .status{margin-left:auto;font-size:11px;color:#555}
            #ID .grid{display:grid;grid-template-columns:repeat(COLUMNS,200px);gap:10px;padding:10px}
            #ID .tile{background:rgba(12,12,12,.9);border:1px solid #1e1e1e;display:flex;flex-direction:column;overflow:hidden;cursor:pointer;transition:border-color .12s,transform .12s}
            #ID .tile:hover{border-color:#353535;transform:translateY(-1px)}
            #ID .tile.parent{border-color:#244154}
            #ID .tile.pending{border-color:#66502a;box-shadow:0 0 0 1px rgba(255,211,124,.22) inset}
            #ID .tile.pending .badge{color:#ffdf96;border-color:#5c4c26}
            #ID .head{display:flex;align-items:center;gap:8px;padding:6px 8px;border-bottom:1px solid #1e1e1e;background:rgba(16,16,16,.94);font-size:11px}
            #ID .badge{color:#7396a8;border:1px solid #253c49;padding:1px 5px;text-transform:uppercase;letter-spacing:.08em;flex-shrink:0}
            #ID .name{color:#8a8a8a;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            #ID .meta{margin-left:auto;color:#434343;flex-shrink:0}
            #ID .pic{position:relative;background:#050505;width:200px;height:200px}
            #ID .pic img{width:200px;height:200px;display:block}
            #ID .overlay{position:absolute;right:8px;bottom:8px;background:rgba(8,8,8,.74);border:1px solid rgba(255,255,255,.06);color:#5d5d5d;padding:3px 6px;font-size:11px}
            #ID .code{padding:6px 8px 8px;color:#585858;font-size:11px;line-height:1.45;white-space:pre-wrap;word-break:break-word;min-height:56px;border-top:1px solid #1e1e1e;background:rgba(14,14,14,.84)}
            #ID .parents{display:flex;gap:10px;padding:0 10px 8px}
            #ID .parents div{flex:1;min-width:0;background:#0d0d0d;border:1px solid #1e1e1e;padding:6px 8px}
            #ID .parents .label{color:#7396a8;font-size:11px;text-transform:uppercase;letter-spacing:.08em}
            #ID .parents pre{margin:4px 0 0;color:#9a9a9a;font:inherit;white-space:pre-wrap;user-select:text}
            #ID .log{padding:4px 10px;color:#484848;font-size:11px;border-top:1px solid #1e1e1e;background:#0e0e0e}
        """.trimIndent().replace("\n", "")

        /** The grid's browser half: tiles toggle until [pick] are chosen, then the choice goes to the box. */
        val SCRIPT = """
            const root=document.getElementById(C.id); if(!root) return;
            const send=(o)=>{ if(window.handleStringInput) window.handleStringInput({id:C.action,value:JSON.stringify(o)}); };
            const log=root.querySelector('.log'); let chosen=[];
            root.querySelectorAll('.tile').forEach(t=>t.addEventListener('click',()=>{
              const i=+t.dataset.index, at=chosen.indexOf(i);
              if(at>=0){ chosen.splice(at,1); t.classList.remove('pending'); }
              else { chosen.push(i); t.classList.add('pending'); }
              if(chosen.length>=C.pick){ log.textContent='breeding from tiles '+chosen.join(' and ')+' …'; send({act:'pick',tiles:chosen}); chosen=[]; }
              else log.textContent=chosen.length?('pick '+(C.pick-chosen.length)+' more'):('pick '+C.pick+' tiles to breed the next generation');
            }));
            root.querySelectorAll('button[data-act]').forEach(b=>b.addEventListener('click',()=>{ log.textContent=b.textContent+' …'; send({act:b.dataset.act}); }));
            root.querySelectorAll('[data-setting]').forEach(e=>e.addEventListener('change',()=>send({act:'set',name:e.dataset.setting,value:parseFloat(e.value)})));
        """.trimIndent()
    }
}

/** One tile of an [EyeEvolution] grid: a genome, its label (`parent a`, `child 3`), and how it was made. */
class EyeTile<G>(val genome: G, val label: String, val note: String, val isParent: Boolean)

/** A child and a short note on how it was bred (shown in its tile's header, e.g. `seed+num+op`). */
class Offspring<G>(val genome: G, val note: String = "")

/**
 * A number the [EyeEvolution] toolbar shows and lets you change; changing it calls [onChange] and
 * breeds a new generation from the same parents. [choices], when given, makes it a menu.
 */
class EyeSetting(
    val name: String,
    var value: Double,
    val min: Double,
    val max: Double,
    val step: Double = 1.0,
    val choices: List<Double> = emptyList(),
    val label: String = name,
    val onChange: (Double) -> Unit,
) {
    internal companion object {
        fun format(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else v.toString()
    }
}

/**
 * Show an [EyeEvolution] grid for [parents] in this box and return it; see [EyeEvolution].
 *
 * @param breed one child of the current parents
 * @param picture a genome's tile, as PNG bytes
 * @param text a genome as text, under its tile and in the parents list; also what makes two tiles the same picture
 * @param meta a short note in a tile's header (a size, a length)
 * @param fresh a random genome, for **Fresh Parents**; `null` hides the button
 * @param seed seeds every random draw the grid makes, for a reproducible run
 */
fun <G> Box.evolveByEye(
    parents: List<G>,
    breed: (List<G>, Random) -> Offspring<G>,
    picture: (G) -> ByteArray,
    text: (G) -> String = { it.toString() },
    meta: (G) -> String = { "" },
    fresh: ((Random) -> G)? = null,
    children: Int = 7,
    pick: Int = 2,
    columns: Int = 3,
    channel: String = "evolve",
    title: String = "evolve",
    settings: List<EyeSetting> = emptyList(),
    seed: Long? = null,
): EyeEvolution<G> = EyeEvolution(
    this, parents, breed, picture, text, meta, fresh, children, pick, columns, channel, title, settings, seed,
).also { it.breedAndShow() }

/**
 * [evolveByEye] for a genome with [GenomeOps]: a child is [GenomeOps.crossover] of two of the
 * parents, or one of [GenomeOps.mutations] applied to one when crossover declines; **Fresh Parents**
 * draws [GenomeOps.random].
 */
fun <G> Box.evolveByEye(
    ops: GenomeOps<G>,
    parents: List<G>,
    picture: (G) -> ByteArray,
    text: (G) -> String = { it.toString() },
    children: Int = 7,
    pick: Int = 2,
    columns: Int = 3,
    channel: String = "evolve",
    seed: Long? = null,
): EyeEvolution<G> = evolveByEye(
    parents,
    breed = { ps, rng -> breedFrom(ops, ps, rng) },
    picture = picture, text = text, fresh = ops::random,
    children = children, pick = pick, columns = columns, channel = channel, seed = seed,
)

/** One child of [parents] by [ops]: crossover of two (distinct when there are two), else a named mutation of one. */
fun <G> breedFrom(ops: GenomeOps<G>, parents: List<G>, rng: Random): Offspring<G> {
    val i = rng.nextInt(parents.size)
    val a = parents[i]
    if (parents.size > 1) {
        val j = (i + 1 + rng.nextInt(parents.size - 1)) % parents.size
        ops.crossover(a, parents[j], rng)?.let { return Offspring(it, "cross") }
    }
    val m = ops.mutations[rng.nextInt(ops.mutations.size)]
    return Offspring(m.apply(a, rng), m.name)
}
