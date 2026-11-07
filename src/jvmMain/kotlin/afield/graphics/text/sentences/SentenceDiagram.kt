package afield.graphics.text.sentences

/**
 * A live, mutable handle on one sentence diagram -- the thing to hold onto in Field.
 *
 * Set a property, ask for output again. The parse is cached (it costs a round trip to the
 * parser server), the layout is not (it costs microseconds), so re-rendering after a tweak
 * is free and safe to do every frame:
 *
 *     val d = SentenceDiagram("Listen if you will birdsong reflects off the walls")
 *     d.layerGap = 60.0
 *     d.flines().curves          // List<FLine>, ready to draw
 *     d.svg()                    // or a string of SVG
 *
 * Animating is just assignment in a loop -- nothing re-parses:
 *
 *     d.heights = Heights.Custom { i, _ -> 300 + 80 * sin(i * 0.7 + t) }
 *     _self.lines.addAll(d.flines().curves)
 *
 * Start the parser first: `optional/sentence-parser/.venv/bin/python python/fieldsentences --serve 9500`.
 *
 * Not to be confused with [Diagram], which is the immutable snapshot this produces.
 */
class SentenceDiagram(text: String? = null) {

    // -- source ----------------------------------------------------------------------

    /** Sentence to parse. Assigning a new one invalidates the cached parse. */
    var text: String? = text

    var url: String = "http://127.0.0.1:9500"

    /** Which sentence, when the text held more than one. */
    var index: Int = 0

    private var loaded: List<Sentence>? = null
    private var loadedFrom: String? = null

    /** Parse JSON you already have, instead of asking the server. */
    fun load(json: String) = apply {
        loaded = Sentences.parseAll(json)
        loadedFrom = text
    }

    /** Parse a file written by `python python/fieldsentences -o`. */
    fun loadFile(path: String) = load(java.io.File(path).readText())

    /** Use an already-parsed sentence. */
    fun use(vararg sentences: Sentence) = apply {
        loaded = sentences.toList()
        loadedFrom = text
    }

    /** Drop the cached parse so the next call fetches again. */
    fun refresh() = apply { loaded = null; loadedFrom = null }

    /** Every sentence in the current source, parsing on first use. */
    val sentences: List<Sentence>
        get() {
            loaded?.let { if (loadedFrom == text) return it }
            val t = text
                ?: throw IllegalStateException(
                    "nothing to draw -- set text, or call load()/loadFile()/use()"
                )
            val got = Sentences.fromServer(t, url)
            loaded = got
            loadedFrom = t
            return got
        }

    /** The sentence being drawn. */
    val sentence: Sentence
        get() {
            val all = sentences
            if (all.isEmpty()) throw IllegalStateException("no sentences in \"$text\"")
            return all[index.coerceIn(0, all.size - 1)]
        }

    /** Any inconsistencies in the parse; empty when all is well. */
    fun problems(): List<String> = sentence.problems()

    // -- what to draw ----------------------------------------------------------------

    /** Which dependency analysis to draw. */
    var scheme: Scheme = Scheme.SD_COLLAPSED

    /** How high each word sits. See [Heights]. */
    var heights: Heights = Heights.ByTreeDepth()

    /**
     * Full manual control of both x and y per token id, overriding [heights] entirely.
     * Set this when the positions come from your own animation or physics.
     */
    var placements: Map<Int, Placement>? = null

    /** Left edge of the first word. */
    var x0: Double = 0.0

    /**
     * Space between words.
     *
     * 50 by default, the same grid as [layerGap] -- working back from the original pages
     * (3b is 1320pt for 13 words, 5a is 1994pt for 21) puts their gap at about 50, and the
     * extra room is most of what makes their arcs read as arcs rather than as narrow loops.
     */
    var wordGap: Double = 50.0

    /**
     * Everything else. The individual knobs below read and write this, and anything they
     * do not cover is reachable with `config = config.copy(...)`.
     */
    var config: LayoutConfig = LayoutConfig()

    // -- the knobs worth reaching for, delegating to config so defaults live in one place

    var layerGap: Double
        get() = config.layerGap
        set(v) { config = config.copy(layerGap = v) }

    var preterminalGap: Double
        get() = config.preterminalGap
        set(v) { config = config.copy(preterminalGap = v) }

    var preterminalAnchor: Double
        get() = config.preterminalAnchor
        set(v) { config = config.copy(preterminalAnchor = v) }

    var treeHandle: Double
        get() = config.treeHandle
        set(v) { config = config.copy(treeHandle = v) }

    var treeHandleSpread: Double
        get() = config.treeHandleSpread
        set(v) { config = config.copy(treeHandleSpread = v) }

    var arcDepth: ArcDepth
        get() = config.arcDepth
        set(v) { config = config.copy(arcDepth = v) }

    var arcShape: ArcShape
        get() = config.arcShape
        set(v) { config = config.copy(arcShape = v) }

    var arcSpanRatio: Double
        get() = config.arcSpanRatio
        set(v) { config = config.copy(arcSpanRatio = v) }

    var arcMinDepth: Double
        get() = config.arcMinDepth
        set(v) { config = config.copy(arcMinDepth = v) }

    var arcLaneStep: Double
        get() = config.arcLaneStep
        set(v) { config = config.copy(arcLaneStep = v) }

    var laneGap: Double
        get() = config.laneGap
        set(v) { config = config.copy(laneGap = v) }

    var baselineMargin: Double
        get() = config.baselineMargin
        set(v) { config = config.copy(baselineMargin = v) }

    var arcInset: Double
        get() = config.arcInset
        set(v) { config = config.copy(arcInset = v) }

    var arcLabelT: Double
        get() = config.arcLabelT
        set(v) { config = config.copy(arcLabelT = v) }

    var underlinePad: Double
        get() = config.underlinePad
        set(v) { config = config.copy(underlinePad = v) }

    var nodeX: NodeX
        get() = config.nodeX
        set(v) { config = config.copy(nodeX = v) }

    var nodeY: NodeY
        get() = config.nodeY
        set(v) { config = config.copy(nodeY = v) }

    var laneOrigin: LaneOrigin
        get() = config.laneOrigin
        set(v) { config = config.copy(laneOrigin = v) }

    var evenLanes: Boolean
        get() = config.evenLanes
        set(v) { config = config.copy(evenLanes = v) }

    /**
     * Word size, in the same units as everything else.
     *
     * Setting this replaces [LayoutConfig.metrics] with an [ApproxSerifMetrics] of the same
     * size, so word widths and the drawn text stay in step. If you have real font metrics,
     * set `config = config.copy(metrics = ...)` *after* setting this.
     */
    var fontSize: Double
        get() = config.wordSize
        set(v) {
            config = config.copy(wordSize = v, metrics = ApproxSerifMetrics(v))
        }

    /**
     * The font words are measured with, and the one to draw them in.  Setting it switches
     * the layout onto real glyph advances, so spacing matches what actually gets drawn --
     * worth doing before you fuss over [wordGap].
     *
     *     d.wordFont = afield.graphics.text.sentences.font("Georgia", 26.0)
     */
    var wordFont: java.awt.Font? = null
        set(v) {
            field = v
            if (v != null) {
                fontSize = v.size2D.toDouble()
                // Relation labels get real metrics too, from the same family at their own
                // size -- the collision solver reserves space using them, so an approximation
                // there would let labels overlap.
                config = config.copy(
                    metrics = Java2DMetrics(v),
                    labelMetrics = Java2DMetrics(v.deriveFont(config.labelSize.toFloat())),
                )
            }
        }

    // -- label appearance --------------------------------------------------------------

    /**
     * Draw the relation labels at all.
     *
     * Affects the layout, not only the drawing: with labels on, the solver keeps arcs clear of
     * them, which is most of what pushes arcs below their natural depth. Off, the arc field
     * pulls in to close to plain nested semicircles.
     */
    var arcLabels: Boolean
        get() = config.arcLabels
        set(v) { config = config.copy(arcLabels = v) }

    /**
     * Relation label size. Delegates to [LayoutConfig.labelSize] rather than standing apart
     * from it, because the collision solver reserves space using that -- if the two drifted,
     * labels would be drawn larger than the space kept for them and overlap again.
     */
    var labelFontSize: Double
        get() = config.labelSize
        set(v) {
            val family = wordFont
            config = config.copy(
                labelSize = v,
                labelMetrics = if (family != null) Java2DMetrics(family.deriveFont(v.toFloat()))
                else ApproxSerifMetrics(v),
            )
        }
    /** Constituency label size; delegates to config so the renderers agree. */
    var tagFontSize: Double
        get() = config.tagSize
        set(v) { config = config.copy(tagSize = v) }

    // -- output -----------------------------------------------------------------------

    /** The word positions this will lay out with. */
    fun placementsNow(): Map<Int, Placement> {
        placements?.let { return it }
        val s = sentence
        val m = config.metrics
        return when (val h = heights) {
            is Heights.ByTreeDepth -> {
                val rng = java.util.Random(h.seed)
                Placements.byTreeDepth(s, config, top = h.top, x0 = x0, gap = wordGap) { _, _ ->
                    if (h.jitter == 0.0) 0.0 else (rng.nextDouble() - 0.5) * 2 * h.jitter
                }
            }
            is Heights.Flat ->
                Placements.flat(s, y = h.y, x0 = x0, gap = wordGap, metrics = m)
            is Heights.Wandering ->
                Placements.wandering(
                    s, seed = h.seed, amplitude = h.amplitude,
                    x0 = x0, gap = wordGap, metrics = m,
                )
            is Heights.Explicit -> {
                require(h.ys.size == s.tokens.size) {
                    "Heights.Explicit has ${h.ys.size} values but there are " +
                        "${s.tokens.size} tokens"
                }
                Placements.reading(s, x0 = x0, gap = { wordGap }, metrics = m) { i, _ -> h.ys[i] }
            }
            is Heights.Custom ->
                Placements.reading(s, x0 = x0, gap = { wordGap }, metrics = m, height = h.f)
        }
    }

    /** Lay it out. Cheap -- call it as often as you like. */
    fun diagram(): Diagram = layout(sentence, placementsNow(), config, scheme)



    override fun toString(): String {
        val loadedNote = if (loaded == null) "not parsed yet" else "${sentences.size} sentence(s)"
        return "SentenceDiagram(text=${text?.let { "\"$it\"" }}, $loadedNote)"
    }
}

/**
 * How high each word sits. Only y -- x always comes from reading order, [SentenceDiagram.x0]
 * and [SentenceDiagram.wordGap]. For control over both, set [SentenceDiagram.placements].
 */
sealed interface Heights {

    /**
     * Each word hangs at the depth of its own preterminal, which is what the 2011 diagrams
     * appear to do. [jitter] loosens the lockstep without losing the shape.
     */
    data class ByTreeDepth(
        val jitter: Double = 0.0,
        val seed: Long = 7L,
        val top: Double = 0.0,
    ) : Heights

    /** One baseline for every word. */
    data class Flat(val y: Double = 0.0) : Heights

    /** A reproducible random walk, so neighbours stay near each other. */
    data class Wandering(val seed: Long = 1L, val amplitude: Double = 90.0) : Heights

    /** One baseline per token, in order. */
    data class Explicit(val ys: List<Double>) : Heights {
        constructor(vararg ys: Double) : this(ys.toList())
    }

    /** Whatever you like, from the 0-based index and the token. */
    data class Custom(val f: (Int, Token) -> Double) : Heights
}
