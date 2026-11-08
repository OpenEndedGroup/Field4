package afield.graphics.text.sentences

import kotlin.math.max
import kotlin.math.min

/**
 * Lays out both halves of a sentence diagram around a row of words whose positions you
 * choose -- including their heights, which can be as arbitrary as you like.
 *
 * The words are the fixed input.  Everything else is derived from them:
 *
 *  - the **constituency tree** grows upward.  Internal nodes sit on depth layers, but
 *    each POS tag hugs its own word at a fixed gap above it.  That split is what makes
 *    arbitrary word heights work: the tag follows the word wherever it goes, and the
 *    layers above are clamped so a parent is never left below one of its children.
 *  - the **dependency arcs** hang below in lanes, deeper the more they span, so long
 *    arcs dip under short ones the way the 2011 diagrams do.
 *
 * Defaults are measured off those diagrams (74pt layers, 78pt tag gap).
 */

/** Text width in layout units. Plug in real font metrics when you have them. */
fun interface Metrics {
    fun width(text: String): Double
}

/**
 * Rough proportional-serif widths, good enough to space words believably before real
 * metrics are wired in.  Values are fractions of an em.
 */
class ApproxSerifMetrics(private val fontSize: Double = 26.0) : Metrics {
    override fun width(text: String): Double = text.sumOf { em(it) } * fontSize

    private fun em(c: Char): Double = when {
        c == ' ' -> 0.25
        c in "ijltfI.,;:'\"!|()[]{}`" -> 0.28
        c in "mwMW" -> 0.82
        c in "ABCDEFGHJKLNOPQRSTUVXYZ" -> 0.68
        c in "0123456789" -> 0.50
        c in "r" -> 0.36
        else -> 0.50
    }
}

/**
 * Real widths from an AWT font, so the layout measures words with the same font that gets
 * drawn.  Uses the glyph advance rather than the bounding box, which is what determines
 * where the next word starts.
 */
class Java2DMetrics(private val font: java.awt.Font) : Metrics {
    private val frc = java.awt.font.FontRenderContext(null, true, true)

    override fun width(text: String): Double {
        if (text.isEmpty()) return 0.0
        val gv = font.createGlyphVector(frc, text)
        return gv.getGlyphPosition(text.length).x
    }
}

/** Where a word sits: [x] is its left edge, [y] its text baseline. */
data class Placement(val x: Double, val y: Double)

enum class NodeX {
    /** Centre of gravity of the children. Even, symmetrical. */
    MEAN_OF_CHILDREN,

    /** Midway between leftmost and rightmost child, ignoring the ones between. */
    MIDPOINT_OF_EXTREMES,

    /** Biased toward the leftmost child by [LayoutConfig.leftLean]. */
    LEFT_LEANING,
}

enum class NodeY {
    /** Strict depth layers, as in the originals. Can collide with a very high word. */
    LAYERED,

    /** Each node sits just above its highest child; the tree follows the words. */
    HUG_CHILDREN,

    /** Depth layers, pushed up wherever a child would otherwise be above its parent. */
    LAYERED_CLAMPED,
}

enum class ArcDepth {
    /**
     * Depth proportional to the arc's own span, measured below the lower of its two words.
     *
     * What the originals do -- measured at 0.500 of span on 6 of 11 arcs in 3b.pdf, median
     * exactly 0.500 (a cubic semicircle would be 0.667, so these sit just inside one). Short
     * arcs stay short, and nesting falls out for free because a containing arc always spans
     * further than what it contains.
     */
    SPAN,

    /**
     * Lanes below a baseline clear of every word. Guarantees arcs never cross each other or
     * the words, but every arc plunges to the same depth however short its span, which reads
     * as pendulous.
     */
    LANES,
}

enum class ArcShape {
    /**
     * Both handles vertical, dropping to one depth. The originals: arcs leave each word
     * straight down. Where the two words differ in height the result is a tilted U.
     */
    LEVEL,

    /**
     * Both handles the same length, each dropping below its *own* word rather than to one
     * shared depth. The arc drapes parallel to the line between the two words instead of
     * bottoming out level, which is the graceful shape where the two differ in height -- and
     * it keeps [LEVEL]'s vertical departures, so arcs still leave each word straight down and
     * stay clear of their neighbours.
     *
     * Handle length is chosen so the arc reaches the same depth [LEVEL] would.
     */
    SHEARED,

    /**
     * A true parabola -- a quadratic through one apex, degree-elevated to a cubic. Any
     * quadratic Bezier is a parabolic arc whatever its endpoint heights.
     *
     * Be warned that arcs leave their words at an angle rather than straight down, so they
     * travel sideways sooner and pass nearer whatever lies between; on a dense sentence the
     * apexes also bunch together and the labels collide. [SHEARED] gets the drape without
     * either problem.
     */
    PARABOLA,
}

enum class LaneOrigin {
    /** All arc lanes are horizontal bands below every word. Most legible. */
    COMMON_BASELINE,

    /** Each arc's lanes start below its own two words, so arcs follow the words. */
    PER_ARC,
}

data class LayoutConfig(
    // -- constituency tree, above the words ---------------------------------------
    /** Vertical distance between depth layers. Measured at 50 in the originals. */
    val layerGap: Double = 50.0,
    /**
     * How far above its word a POS tag sits.  The originals use one whole layer, so the
     * whole diagram lands on a single 50pt grid.
     */
    val preterminalGap: Double = 50.0,
    /**
     * Where along its word a POS tag sits, as a fraction of the word's width: 0 is the
     * word's start, 0.5 its centre.
     *
     * The originals anchor to the start, which pulls each tag left and -- since parents
     * are placed from their children -- leans the whole tree left of where centring would
     * put it.
     */
    val preterminalAnchor: Double = 0.0,
    /**
     * Nudge applied after [preterminalAnchor], relative to the word's left edge.
     *
     * Set so the tag ends up about 5pt right of where its connector lands, which is the
     * lean measured on 16 of the originals' connector curves (dx -4.4 to -5.8).
     */
    val preterminalOffset: Double = -2.0,
    /** Smallest gap a clamped parent will accept above its highest child. */
    val minParentGap: Double = 34.0,
    /**
     * y of the ROOT layer, or null to derive it from the words: the layers are stacked
     * so the deepest one clears the highest word.  Leave it null unless you want the
     * tree pinned to an absolute height, since a fixed value that happens to sit below
     * the words leaves the layers no room and the clamp ends up doing all the work.
     */
    val treeTop: Double? = null,
    val nodeX: NodeX = NodeX.MEAN_OF_CHILDREN,
    val nodeY: NodeY = NodeY.LAYERED_CLAMPED,
    val leftLean: Double = 0.25,
    /**
     * Tree edge handle length, as a fraction of the edge's vertical drop.
     *
     * Both handles point straight down/up along y -- never sideways, however far apart the
     * two nodes are horizontally.  Measured off the original PDFs: 39 of 40 tree edges use
     * exactly 0.6, across horizontal spans from 4pt to 362pt.
     *
     * Because two 0.6 handles reach past each other (0.6 + 0.6 > 1), the curve blends and
     * flares slightly through the middle instead of meeting in a tight S.  At 0.5 the
     * handles just touch; below that the edge straightens toward a diagonal.
     */
    val treeHandle: Double = 0.6,
    /**
     * Nudges tree handles sideways toward the far end, as a fraction of the horizontal
     * span.  The originals use 0 -- exactly 0, measured -- so leave it there to match them;
     * raise it if you want the edges to lean into their direction of travel.
     */
    val treeHandleSpread: Double = 0.0,

    // -- words ---------------------------------------------------------------------
    /** How far below the baseline the underline sits; arcs leave from there. */
    val underlineDrop: Double = 10.0,
    /**
     * Height of the tick turned up at each end of the underline.  The word connector ends
     * at the top of the left tick, so the tree runs continuously into the bracket rather
     * than stopping just short of it.
     */
    val tickHeight: Double = 5.0,
    /**
     * How far the underline bracket overhangs the word at each end.
     *
     * The connector lands on the left tick, so this is also the clearance between the
     * connector and the word's first letter -- without it the curve comes down onto the
     * glyph.
     */
    val underlinePad: Double = 7.0,
    /** Height above the baseline used for bounds and the tick marks on the underline. */
    val wordAscent: Double = 22.0,

    // -- dependency arcs, below the words ------------------------------------------
    /** Choose span-proportional depth or lanes below the words; see [ArcDepth]. */
    val arcDepth: ArcDepth = ArcDepth.SPAN,
    /** [ArcShape.LEVEL] is the strict original; [ArcShape.SHEARED] drapes more gracefully. */
    val arcShape: ArcShape = ArcShape.SHEARED,
    /**
     * Control depth as a fraction of span, under [ArcDepth.SPAN]. Measured at 0.5 in the
     * originals; 0.667 would make each arc a semicircle.
     */
    val arcSpanRatio: Double = 0.5,
    /** Floor on arc depth, so an arc between two adjacent words still reads as an arc. */
    val arcMinDepth: Double = 22.0,
    /**
     * Measure every arc's depth from below the deepest word it spans, rather than below only
     * its own two ends.
     *
     * This is what makes [arcNesting] hold by construction. A container spans a superset of a
     * contained arc's words, so its reference depth is at least as low, and its span is
     * strictly greater -- so its natural depth is strictly greater too, and nesting comes out
     * right before the collision solver runs at all.
     *
     * Turning it off measures each arc from its own two words, and an inner arc between two
     * deep words then sits *below* a container between two shallow ones. No amount of pushing
     * fixes that, since the inner arc cannot rise above its own words.
     */
    val arcClearsWords: Boolean = true,
    /**
     * Push arcs down until they stop crossing any word or relation label.
     *
     * Each arc is sampled, and if a sample lands inside a piece of text (plus
     * [arcAvoidMargin] of clearance below it) that arc gets a little deeper and everything is
     * checked again. Labels move with their arcs, so resolving one can disturb another --
     * hence the iteration. Depths only ever increase, so it always settles.
     *
     * This is what keeps short arcs short: an arc is only pushed as far as it actually needs
     * to go, rather than down to a shared baseline.
     */
    val arcAvoidText: Boolean = true,
    /** How much deeper an arc goes per round while it is still crossing something. */
    val arcAvoidStep: Double = 7.0,
    /** Clearance kept below each piece of text. */
    val arcAvoidMargin: Double = 5.0,
    /**
     * The furthest an arc will be pushed, as a multiple of its own span.
     *
     * Bounding the distortion by span is what stops a short arc from being driven to many
     * times its own depth chasing a gap between deep labels -- it would stop reading as an arc
     * long before it got there. An arc that cannot find room within its budget stays at its
     * natural depth and is marked not [DepArc.clear], on the grounds that a slight overlap
     * beats a grotesque shape.
     */
    val arcMaxPushRatio: Double = 1.0,
    /** Absolute backstop on the search, however wide the arc. */
    val arcAvoidRounds: Int = 60,
    /**
     * Force one settle order: -1 for longest span first, or a seed to shuffle with. Setting a
     * seed disables [arcSettleRestarts]; it is here for experimenting with the ordering.
     */
    val arcSettleSeed: Long = -1L,
    /**
     * How many shuffled settle orders to try in addition to longest-span-first, keeping
     * whichever comes out best.
     *
     * The order arcs settle in is the solver's one real degree of freedom, and longest-first
     * turns out to be a decent heuristic but not a strong one: over 200 random orders, about
     * half beat it, usually by clearing an overlap it leaves behind. Restarts are cheap
     * (roughly 0.3ms each) and deterministic -- seeds are fixed, so the same input always gives
     * the same drawing. Set to 0 if you are animating and want the milliseconds back.
     */
    val arcSettleRestarts: Int = 12,
    /**
     * What one unresolved overlap is worth in units of arc depth, when scoring restarts.
     *
     * Raise it to trade deeper arcs for fewer overlaps, lower it for shallower arcs and more.
     */
    val arcOverlapPenalty: Double = 400.0,
    /**
     * Forbid an arc from crossing any arc whose token range contains its own.
     *
     * Nesting is what makes the bottom half readable as structure rather than a tangle: if
     * `advcl(1,6)` spans `nsubj(3,5)`, the reader should see one arc sitting inside the other,
     * never the two cutting through each other. Depth proportional to span gets this right most
     * of the time on its own -- a container always spans further -- but the collision solver can
     * push a contained arc past its container, so it is enforced as a ceiling: a contained arc
     * may not be pushed below the arc that contains it.
     *
     * Containers are settled first (order is by token range, widest first) so the ceiling is
     * always known by the time the contained arc is placed.
     */
    val arcNesting: Boolean = true,
    /** Tolerance before a contained arc counts as crossing its container. */
    val arcNestClearance: Double = 3.0,
    /** How finely each arc is sampled when testing for collisions. */
    val arcAvoidSamples: Int = 40,

    /**
     * Draw the relation labels at all.
     *
     * This is a layout setting, not just a drawing one: with labels on, the solver reserves
     * room for them and pushes arcs around to keep them clear, which is most of the reason arcs
     * end up deeper than their natural depth. Turn them off and the arcs pull back in to
     * something much closer to plain nested semicircles.
     */
    val arcLabels: Boolean = true,
    /**
     * Size the words are set at. [metrics] is what the layout actually measures with; this is
     * here so the renderers know what size to draw, and so all three text sizes have one home.
     */
    val wordSize: Double = 26.0,
    /** Size of the relation labels; the layout needs it to know where they sit. */
    val labelSize: Double = 11.0,
    /**
     * Size of the constituency node labels. The layout does not use these for anything, but
     * keeping them here means every text size has one home and the renderers cannot disagree.
     */
    val tagSize: Double = 9.5,
    /** Label-baseline offset from its tree anchor; branches and node decorations keep the anchor. */
    val tagOffset: P = P(7.0, 0.0),
    /** Widths for relation labels. Should agree with [labelSize]. */
    val labelMetrics: Metrics = ApproxSerifMetrics(11.0),
    /** How far below the curve a relation label's baseline sits. */
    val arcLabelDrop: Double = 12.0,
    /**
     * Extra depth per lane under [ArcDepth.SPAN], to prise apart arcs of similar span that
     * overlap. Zero matches the originals, which let such arcs sit at the same depth.
     */
    val arcLaneStep: Double = 0.0,

    /** Vertical spacing between successive arc lanes under [ArcDepth.LANES]. */
    val laneGap: Double = 50.0,
    /** Clear space between the lowest word and the first arc lane. */
    val baselineMargin: Double = 50.0,
    val laneOrigin: LaneOrigin = LaneOrigin.COMMON_BASELINE,
    /**
     * Make lanes come out visually evenly spaced.
     *
     * A cubic only reaches about three quarters of the way to its control points, so an
     * arc's actual depth is `0.75 * laneY + (y0 + y1) / 8` -- it depends on how deep its
     * two words are.  Where the words sit at very different heights (as under
     * `Placements.byTreeDepth`) that term dominates, and a shallow lane whose words are
     * deep sinks below a deeper lane whose words are shallow, which puts labels on top of
     * one another.  This raises each arc's control points to cancel the endpoint term, so
     * lane N always bottoms out at lane N's depth.
     */
    val evenLanes: Boolean = true,
    /**
     * Pulls arc control points toward each other horizontally, 0..0.5. Zero makes arcs
     * leave each word straight down, as in the originals.
     */
    val arcInset: Double = 0.0,
    /**
     * Where along the arc its label sits. At 0.5 an asymmetric arc puts the label on
     * the descending flank, tilted -- which is what the originals look like.
     */
    val arcLabelT: Double = 0.5,
    /** Put the label at the arc's lowest point instead, where it reads horizontally. */
    val arcLabelAtLowest: Boolean = false,
    /** Where along the arc the arrowhead sits. */
    val arrowT: Double = 0.9,
    /** The root arc has no governor to draw from, so it is normally left out. */
    val includeRootArc: Boolean = false,

    val metrics: Metrics = ApproxSerifMetrics(),
)

/** A word, placed, with the geometry the drawing needs. */
data class WordBox(
    val token: Int,
    val word: String,
    val left: Double,
    val baseline: Double,
    val width: Double,
    val pos: String?,
    /** Draw the underline when true -- see [Token.inGraph]. */
    val underlined: Boolean,
) {
    val centre: Double get() = left + width / 2
    val right: Double get() = left + width

    /** Where arcs attach, and where the underline is drawn. */
    fun anchor(config: LayoutConfig): P = P(centre, baseline + config.underlineDrop)

    fun underline(config: LayoutConfig): Pair<P, P> =
        Pair(
            P(left - config.underlinePad, baseline + config.underlineDrop),
            P(right + config.underlinePad, baseline + config.underlineDrop),
        )

    /** Top of the underline's left tick -- where the word's connector spline lands. */
    fun connectorEnd(config: LayoutConfig): P =
        P(left - config.underlinePad, baseline + config.underlineDrop - config.tickHeight)

    fun rect(config: LayoutConfig): Rect =
        Rect(
            left - config.underlinePad, baseline - config.wordAscent,
            right + config.underlinePad, baseline + config.underlineDrop,
        )
}

/** A constituency tree node, placed. */
data class PlacedNode(
    val id: Int,
    val label: String,
    val at: P,
    val depth: Int,
    val leaf: Boolean,
    /** The token this leaf sits above, or null on an internal node. */
    val token: Int?,
)

data class TreeEdge(val parent: Int, val child: Int, val curve: Cubic)

/**
 * The short spline joining a POS tag to the start of its word -- the last link in the
 * constituency tree, drawn like any other tree edge.
 */
data class WordEdge(val node: Int, val token: Int, val curve: Cubic)

/**
 * A tree edge's curve: handles straight down out of [a] and straight up out of [b], each
 * [handle] of the vertical drop. See [LayoutConfig.treeHandle].
 */
internal fun handleCurve(a: P, b: P, handle: Double, spread: Double): Cubic {
    val dy = b.y - a.y
    val sx = spread * (b.x - a.x)
    return Cubic(a, P(a.x + sx, a.y + handle * dy), P(b.x - sx, b.y - handle * dy), b)
}

/** A dependency arc, placed. */
data class DepArc(
    val edge: Edge,
    /** 1 for the shallowest lane; deeper arcs span more. */
    val lane: Int,
    val curve: Cubic,
    val labelAt: P,
    /** Degrees, for rotating the label along the curve. */
    val labelAngle: Double,
    /** Where the rotated label lands, used to keep other arcs out of it. */
    val labelBox: Rect,
    val arrowAt: P,
    val arrowAngle: Double,
    /** How far this arc had to be pushed down to stop crossing text. */
    val pushedDown: Double = 0.0,
    /**
     * False when no depth within this arc's budget kept it clear of the text, so it sits at its
     * natural depth and overlaps something. See [LayoutConfig.arcMaxPushRatio].
     */
    val clear: Boolean = true,
) {
    val label: String get() = edge.label
}

data class Diagram(
    val sentence: Sentence,
    val config: LayoutConfig,
    val words: List<WordBox>,
    val nodes: List<PlacedNode>,
    val treeEdges: List<TreeEdge>,
    /** POS tag to word-start splines; draw these with [treeEdges]. */
    val wordEdges: List<WordEdge>,
    val arcs: List<DepArc>,
    val bounds: Rect,
) {
    private val nodeById = nodes.associateBy { it.id }
    private val wordByToken = words.associateBy { it.token }

    fun node(id: Int): PlacedNode = nodeById.getValue(id)
    fun word(token: Int): WordBox = wordByToken.getValue(token)
    val deepestLane: Int get() = arcs.maxOfOrNull { it.lane } ?: 0
}

object Placements {
    /**
     * Words in reading order, each at whatever height you ask for.
     *
     * [height] is the whole point of this: it receives the 0-based index and the token,
     * and returns a baseline y. Return a constant for a flat row, or anything at all
     * for the drifting rows the original diagrams use.
     */
    fun reading(
        sentence: Sentence,
        x0: Double = 0.0,
        gap: (Int) -> Double = { 50.0 },
        metrics: Metrics = ApproxSerifMetrics(),
        height: (Int, Token) -> Double,
    ): Map<Int, Placement> {
        var x = x0
        val out = LinkedHashMap<Int, Placement>()
        sentence.tokens.forEachIndexed { i, tok ->
            out[tok.id] = Placement(x, height(i, tok))
            x += metrics.width(tok.word) + gap(i)
        }
        return out
    }

    /** A flat row, for checking a layout before making it wander. */
    fun flat(
        sentence: Sentence,
        y: Double = 0.0,
        x0: Double = 0.0,
        gap: Double = 50.0,
        metrics: Metrics = ApproxSerifMetrics(),
    ) = reading(sentence, x0 = x0, gap = { gap }, metrics = metrics) { _, _ -> y }

    /**
     * Hang each word at the depth of its own preterminal, so deeper words sit lower.
     *
     * This is what the 2011 diagrams appear to do, and it explains their look: measuring
     * 3b.pdf gives word baselines within a few points of `depth * 74 + 95` -- "Listen" at
     * depth 4 sits at 391, "birdsong" at depth 3 at 313, "you" at depth 7 at 617.  The
     * heights read as arbitrary but they are the constituency depth.
     *
     * A side effect worth knowing: because the drop per layer is the same [layerGap] the
     * tree uses, every POS tag lands exactly on its own depth layer, which is what makes
     * the originals look so ordered despite the uneven row of words.
     *
     * [jitter] loosens that lockstep without giving up the overall shape.
     */
    fun byTreeDepth(
        sentence: Sentence,
        config: LayoutConfig = LayoutConfig(),
        top: Double = 0.0,
        x0: Double = 0.0,
        gap: Double = 50.0,
        jitter: (Int, Token) -> Double = { _, _ -> 0.0 },
    ): Map<Int, Placement> {
        val depths = sentence.constituency.leafDepths()
        return reading(sentence, x0 = x0, gap = { gap }, metrics = config.metrics) { i, tok ->
            top + (depths[tok.id] ?: 0) * config.layerGap + config.preterminalGap +
                jitter(i, tok)
        }
    }

    /**
     * Reproducible pseudo-random heights, for exercising the layout against the kind of
     * arbitrary row the finished drawings use.
     */
    fun wandering(
        sentence: Sentence,
        seed: Long = 1L,
        amplitude: Double = 160.0,
        x0: Double = 0.0,
        gap: Double = 50.0,
        metrics: Metrics = ApproxSerifMetrics(),
    ): Map<Int, Placement> {
        val rng = java.util.Random(seed)
        var y = 0.0
        return reading(sentence, x0 = x0, gap = { gap }, metrics = metrics) { _, _ ->
            // A random walk rather than independent noise, so neighbours stay near each
            // other and the row drifts instead of jittering.
            y += (rng.nextDouble() - 0.5) * amplitude
            y
        }
    }
}

/**
 * Turn a tangent direction into one text can be set along without reading upside down,
 * by flipping any angle pointing leftward through 180 degrees.
 */
internal fun readable(angle: Double): Double {
    var a = angle
    while (a > 180) a -= 360
    while (a < -180) a += 360
    // Flip toward zero, not always downward: subtracting 180 from -152 gives -332, which
    // draws the same but reads as nonsense to anything inspecting the number.
    return when {
        a > 90 -> a - 180
        a < -90 -> a + 180
        else -> a
    }
}

/** Height of an x-sorted polyline at [x], or null if [x] falls outside it. */
internal fun yAlong(profile: List<P>, x: Double): Double? {
    if (profile.size < 2) return null
    if (x < profile.first().x || x > profile.last().x) return null
    for (i in 1 until profile.size) {
        val a = profile[i - 1]
        val b = profile[i]
        if (x in a.x..b.x) {
            val span = b.x - a.x
            val t = if (span <= 0.0) 0.0 else (x - a.x) / span
            return a.y + (b.y - a.y) * t
        }
    }
    return profile.last().y
}

/**
 * A rotated line of text, for testing whether a curve runs through it.
 *
 * Tests the oriented rectangle rather than its axis-aligned bounds, which matters a great
 * deal for a steeply rotated label: an 11pt line set at -65 degrees has axis-aligned bounds
 * 71 tall, so testing those would treat it as a far larger obstacle than it is and push
 * arcs needlessly deep.
 */
internal class TextBox(
    private val centre: P,
    private val width: Double,
    private val height: Double,
    angleDegrees: Double,
    /** Extra clearance kept below the text, in its own frame. */
    private val below: Double = 0.0,
) {
    private val cos = Math.cos(Math.toRadians(angleDegrees))
    private val sin = Math.sin(Math.toRadians(angleDegrees))

    operator fun contains(p: P): Boolean {
        val dx = p.x - centre.x
        val dy = p.y - centre.y
        val lx = dx * cos + dy * sin
        val ly = -dx * sin + dy * cos
        return lx >= -width / 2 && lx <= width / 2 &&
            ly >= -height * 0.78 && ly <= height * 0.22 + below
    }
}

/**
 * Assign each arc a lane. Narrow arcs first; an arc goes one lane deeper than anything
 * it overlaps, so nested arcs stack and long spans dip below short ones.
 */
internal fun assignLanes(edges: List<Edge>): Map<Edge, Int> {
    data class Span(val edge: Edge, val lo: Int, val hi: Int) {
        val width: Int get() = hi - lo
    }

    val spans = edges.map { Span(it, min(it.gov, it.dep), max(it.gov, it.dep)) }
        .sortedBy { it.width }

    val lanes = LinkedHashMap<Edge, Int>()
    val placed = mutableListOf<Pair<Span, Int>>()

    for (s in spans) {
        var lane = 1
        for ((other, otherLane) in placed) {
            // Strict overlap: arcs that merely meet at a shared word can share a lane.
            val overlaps = max(s.lo, other.lo) < min(s.hi, other.hi)
            if (overlaps) lane = max(lane, otherLane + 1)
        }
        lanes[s.edge] = lane
        placed += s to lane
    }
    return lanes
}

/**
 * Lay out [sentence] around the given word [placements].
 *
 * @param placements one per token id; see [Placements].
 */
fun layout(
    sentence: Sentence,
    placements: Map<Int, Placement>,
    config: LayoutConfig = LayoutConfig(),
    scheme: Scheme = Scheme.SD_COLLAPSED,
): Diagram {
    val missing = sentence.tokens.filter { it.id !in placements }
    if (missing.isNotEmpty()) {
        throw IllegalArgumentException(
            "no placement for token(s) " + missing.joinToString { "${it.id} \"${it.word}\"" }
        )
    }

    val boxes = sentence.tokens.map { tok ->
        val p = placements.getValue(tok.id)
        WordBox(
            token = tok.id,
            word = tok.word,
            left = p.x,
            baseline = p.y,
            width = config.metrics.width(tok.word),
            pos = tok.pos,
            underlined = tok.inGraph,
        )
    }
    val boxByToken = boxes.associateBy { it.token }

    // -- constituency tree, bottom-up ------------------------------------------------
    val placed = LinkedHashMap<Int, PlacedNode>()

    // Align the depth layers to the words rather than trusting a caller-supplied
    // absolute height to leave enough room.
    //
    // Each POS tag implies where the ROOT layer would have to be for that tag to land
    // exactly on its own depth layer.  Taking the smallest of those puts every layer at
    // or above the tag sitting on it, so an internal node is never left below one of its
    // children, and does so without wasting height on the deepest branch.
    //
    // When the words were placed by `Placements.byTreeDepth` every tag implies the same
    // value, so the layers land exactly on the tags -- which is what makes the originals
    // look ordered despite an uneven row of words.
    val treeTop = config.treeTop ?: run {
        val depths = sentence.constituency.leafDepths()
        boxes.minOf { box ->
            val depth = depths[box.token] ?: 0
            (box.baseline - config.preterminalGap) - depth * config.layerGap
        }
    }

    fun place(node: Node, depth: Int): P {
        if (node.leaf) {
            val box = boxByToken[node.token]
                ?: throw IllegalArgumentException("leaf references unknown token ${node.token}")
            // The tag follows its word rather than sitting on a layer, which is what
            // lets the words be at arbitrary heights.  It anchors near the word's start,
            // not its centre -- see LayoutConfig.preterminalAnchor.
            val at = P(
                box.left + config.preterminalAnchor * box.width + config.preterminalOffset,
                box.baseline - config.preterminalGap,
            )
            placed[node.id] = PlacedNode(node.id, node.label, at, depth, true, node.token)
            return at
        }

        val kids = node.children.map { place(it, depth + 1) }
        if (kids.isEmpty()) {
            // A childless internal node has nothing to hang off; put it on its layer.
            val at = P(0.0, treeTop + depth * config.layerGap)
            placed[node.id] = PlacedNode(node.id, node.label, at, depth, false, null)
            return at
        }

        val xs = kids.map { it.x }
        val x = when (config.nodeX) {
            NodeX.MEAN_OF_CHILDREN -> xs.average()
            NodeX.MIDPOINT_OF_EXTREMES -> (xs.min() + xs.max()) / 2
            NodeX.LEFT_LEANING -> xs.min() + config.leftLean * (xs.max() - xs.min())
        }

        val highestChild = kids.minOf { it.y }
        val layered = treeTop + depth * config.layerGap
        val y = when (config.nodeY) {
            NodeY.LAYERED -> layered
            NodeY.HUG_CHILDREN -> highestChild - config.layerGap
            // Keep the layer unless a child has risen above it.
            NodeY.LAYERED_CLAMPED -> min(layered, highestChild - config.minParentGap)
        }

        val at = P(x, y)
        placed[node.id] = PlacedNode(node.id, node.label, at, depth, false, null)
        return at
    }
    place(sentence.constituency, 0)

    // Handles point down out of the parent and up out of the child -- along y, not toward
    // each other.  See treeHandle: this is what the originals do.
    val treeEdges = sentence.constituency.edges().map { (parent, child) ->
        TreeEdge(
            parent.id, child.id,
            handleCurve(
                placed.getValue(parent.id).at,
                placed.getValue(child.id).at,
                config.treeHandle, config.treeHandleSpread,
            ),
        )
    }

    // The tree does not stop at the POS tag: a short spline carries on down to the start
    // of the word, which is what visually attaches the word to the tree.
    val wordEdges = sentence.constituency.leaves().mapNotNull { leaf ->
        val token = leaf.token ?: return@mapNotNull null
        val box = boxByToken[token] ?: return@mapNotNull null
        WordEdge(
            leaf.id, token,
            handleCurve(
                placed.getValue(leaf.id).at,
                box.connectorEnd(config),
                config.treeHandle, config.treeHandleSpread,
            ),
        )
    }

    // -- dependency arcs, below the words --------------------------------------------
    val wanted = sentence.edges(scheme).filter { config.includeRootArc || !it.isRoot }
    val lanes = assignLanes(wanted)

    val commonBase = (boxes.maxOfOrNull { it.baseline + config.underlineDrop } ?: 0.0) +
        config.baselineMargin

    // Each arc's geometry as a function of how far it has been pushed down, so the collision
    // solver below can re-derive a curve cheaply from one number.
    class Arc(val edge: Edge, val from: P, val to: P, val under: Double, val lane: Int) {
        var push = 0.0
        var clear = true

        /** Token range this arc reaches over. */
        val lo: Int = min(edge.gov, edge.dep)
        val hi: Int = max(edge.gov, edge.dep)
        val reach: Int get() = hi - lo

        /** True when [other]'s range strictly contains this arc's. */
        fun containedBy(other: Arc): Boolean =
            other.lo <= lo && hi <= other.hi && (other.lo < lo || hi < other.hi)

        /**
         * The curve as a polyline sorted by x, so another arc can ask how deep this one is at a
         * given x. Both [ArcShape.LEVEL] and [ArcShape.SHEARED] put their control points on the
         * endpoints' own x, which makes x monotonic along the curve and this well defined.
         */
        fun profile(samples: Int): List<P> {
            val c = curve()
            val pts = (0..samples).map { c.pointAt(it.toDouble() / samples) }
            return if (pts.first().x <= pts.last().x) pts else pts.reversed()
        }

        fun ctrlY(): Double = when (config.arcDepth) {
            ArcDepth.SPAN ->
                under +
                    max(config.arcMinDepth, config.arcSpanRatio * kotlin.math.abs(to.x - from.x)) +
                    (lane - 1) * config.arcLaneStep + push

            ArcDepth.LANES -> {
                val base = when (config.laneOrigin) {
                    LaneOrigin.COMMON_BASELINE -> commonBase
                    LaneOrigin.PER_ARC -> under + config.baselineMargin
                }
                val target = base + (lane - 1) * config.laneGap + push
                // Invert the cubic's midpoint depth so it bottoms out at `target`.
                if (config.evenLanes) (target - (from.y + to.y) / 8) / 0.75 else target
            }
        }

        fun curve(): Cubic {
            val dx = to.x - from.x
            val ctrlY = ctrlY()
            return when (config.arcShape) {
                ArcShape.LEVEL -> Cubic(
                    from,
                    P(from.x + config.arcInset * dx, ctrlY),
                    P(to.x - config.arcInset * dx, ctrlY),
                    to,
                )
                ArcShape.SHEARED -> {
                    // One handle length for both, set so the midpoint lands where LEVEL puts it.
                    val len = ctrlY - (from.y + to.y) / 2
                    Cubic(
                        from,
                        P(from.x + config.arcInset * dx, from.y + len),
                        P(to.x - config.arcInset * dx, to.y + len),
                        to,
                    )
                }
                ArcShape.PARABOLA -> {
                    // One apex, then degree-elevate the quadratic to a cubic. The 1.5 puts
                    // the parabola at the depth LEVEL reaches when the words are level.
                    val apex = P((from.x + to.x) / 2, under + 1.5 * (ctrlY - under))
                    Cubic(from, from.lerp(apex, 2.0 / 3), to.lerp(apex, 2.0 / 3), to)
                }
            }
        }

        /** Label position and rotation for the current curve. */
        fun label(): Pair<P, Double> {
            val c = curve()
            if (config.arcLabelAtLowest) return c.lowestPoint() + P(0.0, config.arcLabelDrop) to 0.0
            // Right-to-left arcs have a tangent pointing backwards, which would set the label
            // upside down; flip it so text always reads left to right.
            return c.pointAt(config.arcLabelT) + P(0.0, config.arcLabelDrop) to
                readable(c.angleAt(config.arcLabelT))
        }

        fun labelBox(): Rect {
            val (at, angle) = label()
            return Rect.rotatedText(
                at, config.labelMetrics.width(edge.label), config.labelSize, angle,
            )
        }

        /** The label as an oriented box, for collision rather than for bounds. */
        fun labelObb(below: Double): TextBox {
            val (at, angle) = label()
            return TextBox(
                at, config.labelMetrics.width(edge.label), config.labelSize, angle, below,
            )
        }
    }

    val pending = wanted.mapNotNull { edge ->
        val govBox = boxByToken[edge.gov]
        val depBox = boxByToken[edge.dep] ?: return@mapNotNull null
        // The root arc has no governor word; draw it straight down from the dependent.
        val from = govBox?.anchor(config) ?: depBox.anchor(config)
        val to = depBox.anchor(config)

        // An arc hangs below its own two ends, and optionally below anything it reaches over.
        val under = if (config.arcClearsWords) {
            val lo = min(edge.gov, edge.dep)
            val hi = max(edge.gov, edge.dep)
            boxes.filter { it.token in lo..hi }
                .maxOfOrNull { it.baseline + config.underlineDrop }
                ?.let { max(max(from.y, to.y), it) }
                ?: max(from.y, to.y)
        } else max(from.y, to.y)

        Arc(edge, from, to, under, lanes.getValue(edge))
    }

    // Push arcs down until none crosses a word or a relation label.
    //
    // Each arc only has to clear the words plus the labels of arcs settled before it, and they
    // settle **longest span first**. That order is what keeps short arcs looking like arcs: a
    // long arc is deep anyway, so its label is deep and out of a short arc's way, leaving the
    // short ones near their natural depth. Going shortest-first instead puts small labels at
    // shallow depths and every larger arc has to dodge them, a cascade that drove mid-size
    // arcs to nearly twice their span in depth -- measured 1.89 on one, against 0.4 natural.
    //
    // Settling in *some* fixed order is also what makes this terminate: pushing every
    // colliding arc at once moves two overlapping labels down together, so they stay
    // overlapped and descend for ever.
    if (config.arcAvoidText) {
        val wordRects = boxes.associate {
            it.token to it.rect(config).expandDown(config.arcAvoidMargin)
        }
        // One pass over the arcs in a given order, returning how many were left overlapping and
        // how far everything had to move.
        fun sweep(order: List<Arc>): Pair<Int, Double> {
            pending.forEach { it.push = 0.0; it.clear = true }
            val settled = mutableListOf<TextBox>()
            val settledBounds = mutableListOf<Rect>()
            val settledArcs = mutableListOf<Pair<Arc, List<P>>>()

            for (arc in order) {
                val containers = if (!config.arcNesting) emptyList() else
                    settledArcs.filter { (other, _) -> arc.containedBy(other) }

                /**
                 * True when this arc dips below one of the arcs containing it -- which is what a
                 * crossing looks like. Compared point by point along x rather than by any single
                 * depth: two arcs sharing an endpoint both rise to the same word there, so a
                 * ceiling taken at the endpoints would read as no room at all.
                 */
                fun crossesContainer(): Boolean {
                    if (containers.isEmpty()) return false
                    val c = arc.curve()
                    for ((_, profile) in containers) {
                        for (i in 1 until config.arcAvoidSamples) {
                            val p = c.pointAt(i.toDouble() / config.arcAvoidSamples)
                            val oy = yAlong(profile, p.x) ?: continue
                            if (p.y > oy + config.arcNestClearance) return true
                        }
                    }
                    return false
                }

                fun collides(): Boolean {
                    val curve = arc.curve()
                    // Skip the very ends, and ignore this arc's own two words: an arc starts on
                    // its own word's underline, which counts as inside that word's rectangle.
                    val curveHits = (1 until config.arcAvoidSamples).any { i ->
                        val p = curve.pointAt(i.toDouble() / config.arcAvoidSamples)
                        val inWord = wordRects.any { (token, r) ->
                            token != arc.edge.gov && token != arc.edge.dep && p in r
                        }
                        inWord || settled.any { p in it }
                    }
                    if (curveHits) return true
                    if (!config.arcLabels) return false
                    // The label travels with the arc and must land clear too -- including clear
                    // of its own two words, which it has no reason to sit on.
                    val box = arc.labelBox()
                    return wordRects.values.any { box.intersects(it) } ||
                        settledBounds.any { box.intersects(it) }
                }

                val span = kotlin.math.abs(arc.to.x - arc.from.x)
                val budget = max(config.arcMinDepth, config.arcMaxPushRatio * span)

                // Take the shallowest depth within budget that keeps clear; failing that, stay
                // at the natural depth rather than descend into a shape that no longer reads as
                // an arc.
                var found = false
                var step = 0
                while (arc.push <= budget && step < config.arcAvoidRounds) {
                    // Never push past a container, even if that leaves text overlapping:
                    // nesting reads as structure, a crossing reads as a mistake.
                    if (crossesContainer()) { arc.push -= config.arcAvoidStep; break }
                    if (!collides()) { found = true; break }
                    arc.push += config.arcAvoidStep
                    step++
                }
                if (!found) arc.push = 0.0
                // The natural depth can cross a container all by itself, when a short arc sits
                // at the mouth of a long one -- the long arc has to rise to meet its own word
                // there, so it is shallow exactly where the short arc bellies out. Pushing down
                // only makes that worse, so lift instead, as far as the minimum depth allows.
                if (config.arcNesting) {
                    while (crossesContainer() &&
                        arc.ctrlY() - arc.under > config.arcMinDepth + config.arcAvoidStep
                    ) {
                        arc.push -= config.arcAvoidStep
                    }
                }
                arc.clear = found && !crossesContainer()

                if (config.arcLabels) {
                    settled += arc.labelObb(config.arcAvoidMargin)
                    settledBounds += arc.labelBox().expandDown(config.arcAvoidMargin)
                }
                settledArcs += arc to arc.profile(config.arcAvoidSamples)
            }
            return pending.count { !it.clear } to pending.sumOf { it.push }
        }

        // Widest token range first, so a container is always settled before anything it
        // contains and the nesting ceiling is known when it is needed. Restarts shuffle first
        // and this sort is stable, so the variation lands among arcs of equal reach without
        // ever breaking the container-first invariant.
        fun order(seed: Long?): List<Arc> =
            (if (seed == null) pending else pending.shuffled(kotlin.random.Random(seed)))
                .sortedByDescending { it.reach }

        val byLength = order(null)

        if (config.arcSettleSeed >= 0) {
            // An explicit order was asked for; use it and do not search.
            sweep(order(config.arcSettleSeed))
        } else {
            // Longest-first, then a fixed set of shuffles; keep whichever scored best. Seeds are
            // fixed so the same input always produces the same drawing.
            var best = Double.MAX_VALUE
            var bestPush: List<Double> = emptyList()
            var bestClear: List<Boolean> = emptyList()

            for (attempt in -1 until config.arcSettleRestarts) {
                val (overlaps, push) =
                    sweep(if (attempt < 0) byLength else order(attempt.toLong()))
                val scored = overlaps * config.arcOverlapPenalty + push
                if (scored < best) {
                    best = scored
                    bestPush = pending.map { it.push }
                    bestClear = pending.map { it.clear }
                }
            }
            pending.forEachIndexed { i, a -> a.push = bestPush[i]; a.clear = bestClear[i] }
        }
    }

    val arcs = pending.map { arc ->
        val curve = arc.curve()
        val (labelAt, labelAngle) = arc.label()
        DepArc(
            edge = arc.edge,
            lane = arc.lane,
            curve = curve,
            labelAt = labelAt,
            labelAngle = labelAngle,
            labelBox = arc.labelBox(),
            arrowAt = curve.pointAt(config.arrowT),
            arrowAngle = curve.angleAt(config.arrowT),
            pushedDown = arc.push,
            clear = arc.clear,
        )
    }

    val bounds = Rect.union(
        boxes.map { it.rect(config) } +
            listOf(Rect.around(placed.values.map { it.at })) +
            treeEdges.map { it.curve.bounds() } +
            wordEdges.map { it.curve.bounds() } +
            arcs.map { it.curve.bounds() } +
            // Undrawn labels should not inflate the bounds.
            (if (config.arcLabels) arcs.map { it.labelBox } else emptyList())
    )

    return Diagram(
        sentence = sentence,
        config = config,
        words = boxes,
        nodes = placed.values.toList(),
        treeEdges = treeEdges,
        wordEdges = wordEdges,
        arcs = arcs,
        bounds = bounds,
    )
}
