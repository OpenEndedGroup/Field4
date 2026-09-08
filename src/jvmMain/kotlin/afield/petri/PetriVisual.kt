@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Visualization")

package afield.petri

import afield.Box
import afield.four.synth.PatArc
import afield.four.synth.PatPattern
import afield.four.synth.PatQ
import afield.graphics.FLine
import afield.graphics.drawToSVG
import afield.graphics.lines
import afield.layout.D2Layout
import afield.stdlib.FLineIntersection
import afield.stdlib.Vec2
import afield.stdlib.arrow
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.hoverColor
import afield.stdlib.hsl
import afield.stdlib.layoutText
import afield.stdlib.onClick
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.toFLines
import afield.stdlib.typeface
import afield.stdlib.vec
import java.awt.Font
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 Looking at a net, and poking it.

 A Petri net is one of the few computational models with a *standard picture*, and the picture is
 good: circles hold tokens, bars move them, arrows say where. Drawing it properly is not decoration
 — a net you can see is a net you can debug, and the two things you most need to see are which
 transitions are ready to go and where the tokens actually are.

 So the drawing says four things the plain graph does not:

 - a **place** is a circle with its tokens as dots inside it, and a token carrying a `PatPattern`
   gets a ring rather than a dot, because a place holding a rhythm is a different kind of thing
   from a place holding a count;
 - a **transition** is a bar, drawn dim when it cannot fire, bright when it is enabled, and filled
   when it is firing at the instant being drawn;
 - an **inhibitor arc** ends in a small circle and a **read arc** in no head at all, which is the
   standard notation and is exactly the distinction that is invisible in a token count;
 - **arc weights** above one are written on the arc.

 Interaction goes through `FLine.onClick`, so it only does anything in the interactive outputs —
 which is where you want it. Click a place to drop a token in, click a transition to make it fire.
 Both need somewhere for the change to go, so both are wired to a [PetriLive]; a [PetriRun] is an
 immutable trajectory and clicking one only tells you about it.

     val live = net.live()
     box.showPetriNet(live)          // click the picture; the net changes under your hands

 Layout
 ------

 [PetriLayout.RING] draws a cycle as a cycle, which is what most drum nets are, and is chosen
 automatically when the net is one. Otherwise [PetriLayout.FORCE] relaxes a spring model.
 [PetriLayout.TALA] hands the net to d2's diagram layout engine instead, if you have built the
 optional helper for it; it falls back to [PetriLayout.FORCE] if you have not.
 [PetriDrawing.at] pins anything you would rather place yourself.
*/

// ── layout ─────────────────────────────────────────────────────────────────────────────────

enum class PetriLayout {
    /** Detects a single cycle and draws it as a circle; falls back to [FORCE]. The default. */
    AUTO,

    /** Places and transitions alternating round a circle, in graph order. */
    RING,

    /** A spring model: arcs pull, everything pushes. */
    FORCE,

    /** Left to right by distance from the initially marked places. */
    LAYERED,

    /**
     * TALA, d2's layout engine for diagrams of labelled boxes, through the optional
     * `native/field-d2-layout` helper.
     *
     * Never chosen by [AUTO] and never a default: it needs a binary Field does not build, so it
     * has to be asked for. When the helper is missing this quietly becomes [FORCE], which is why
     * asking for it is safe in a document you might open on another machine.
     *
     * Worth asking for when [FORCE] gives you a hairball. TALA places boxes with their real sizes
     * and routes the arcs, where the spring model treats every node as a point.
     */
    TALA,
}

// ── a frame ────────────────────────────────────────────────────────────────────────────────

/**
 * One frame: a net, a marking, what is ready, what is sounding, and where everything sits.
 *
 * Separated from the rendering so a frame can be built once and drawn at several sizes, and so the
 * animation harness can walk a run frame by frame without re-laying-out every time.
 */
class PetriDrawing(
    val net: PetriNet,
    val marking: PetriMarking,
    /** Transitions that could fire against [marking]. Drawn bright. */
    val enabled: Set<String> = emptySet(),
    /** Transitions whose event arc covers the instant being drawn. Drawn filled. */
    val firing: Set<String> = emptySet(),
    /** The instant this frame shows, for the caption. */
    val at: PatQ = PatQ.ZERO,
    layout: PetriLayout = PetriLayout.AUTO,
    // Positions you would rather set yourself, by place or transition name. Not a `val`, so a
    // KDoc block here would slide onto the next declaration — see KDocAttachmentGuard.
    pinned: Map<String, Vec2> = emptyMap(),
    val radius: Double = 240.0,
) {
    /** Where each place and transition sits, in the drawing's own coordinates. */
    val positions: Map<String, Vec2> = petriLayout(net, layout, radius) + pinned

    /** A copy with [name] pinned at ([x], [y]). */
    fun at(name: String, x: Number, y: Number): PetriDrawing = PetriDrawing(
        net, marking, enabled, firing, this.at, PetriLayout.AUTO,
        positions + (name to vec(x.toDouble(), y.toDouble())), radius,
    )

    fun position(name: String): Vec2 = positions[name] ?: vec(0.0, 0.0)

    override fun toString(): String =
        "PetriDrawing(@$at, ${marking.size} tokens, ${enabled.size} enabled, ${firing.size} firing)"
}

/** The frame a run is showing at [instant]: the marking then, what was ready, and what is sounding. */
fun PetriRun.frameAt(
    instant: PatQ = PatQ.ZERO,
    layout: PetriLayout = PetriLayout.AUTO,
    radius: Double = 240.0,
): PetriDrawing {
    val marking = markingAt(instant)
    // A firing is "sounding" for as long as its event arc lasts, which is the thing a viewer is
    // trying to match up with what they can hear.
    val half = (net.grid / PatQ.of(2)).takeIf { it > PatQ.ZERO } ?: PatQ.ONE
    val window = PatArc(instant, instant + half)
    val sounding = firingsOver(window).filter { it.arc.contains(instant) || it.arc.start == instant }
    return PetriDrawing(
        net = net,
        marking = marking,
        enabled = net.enabled(marking, instant).toSet(),
        firing = sounding.map { it.transition }.toSet(),
        at = instant,
        layout = layout,
        radius = radius,
    )
}

/** The frame for a net sitting at its initial marking — the picture before anything has happened. */
fun PetriNet.frame(layout: PetriLayout = PetriLayout.AUTO, radius: Double = 240.0): PetriDrawing =
    PetriDrawing(this, initial, enabled(initial).toSet(), emptySet(), PatQ.ZERO, layout, radius = radius)

private fun PatArc.contains(instant: PatQ): Boolean = instant >= start && instant < end

// ── laying out ─────────────────────────────────────────────────────────────────────────────

/**
 * Solved layouts, keyed on the structure they were solved for.
 *
 * A [PetriDrawing] computes its positions on construction, and an animation builds one per frame
 * via `frameAt` — so without this the whole restart-and-score search would run again for every
 * frame of every playing net. Positions depend only on the shape of the graph, never on the
 * marking, which is exactly what makes the frames of one animation a cache hit.
 *
 * Bounded and keyed by value rather than identity, so rebuilding an equal net from the notation
 * hits the same entry.
 */
private val petriLayoutCache = object : LinkedHashMap<String, Map<String, Vec2>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Map<String, Vec2>>): Boolean = size > 64
}

/**
 * Drops every cached layout, so the next [frame] re-solves.
 *
 * Layouts are memoised on the net's structure, so redrawing the same net is free. Two reasons to
 * throw that away: you have just built `native/field-d2-layout` under a running Field and want
 * [PetriLayout.TALA] to start using it, or you are timing a layout and need each run to be real
 * work rather than a map lookup.
 */
fun clearPetriLayoutCache() {
    synchronized(petriLayoutCache) { petriLayoutCache.clear() }
}

/** Everything about a net that can move a node, and nothing that cannot. */
private fun petriLayoutKey(net: PetriNet, layout: PetriLayout, radius: Double): String = buildString {
    append(layout).append('|').append(radius).append('|')
    // [PetriLayout.TALA] is the one layout whose answer depends on something outside the net:
    // whether the optional helper is installed, and which d2 it links. Leaving that out of the key
    // means a drawing produced by the FORCE fallback goes on being served under the TALA key after
    // you build the helper, with no way back short of restarting Field — and, less visibly, a
    // helper rebuilt against a newer d2 keeps handing you the old engine's drawing. Consulted only
    // for TALA, so a net that never asks for it never goes looking for the binary.
    if (layout == PetriLayout.TALA) append(D2Layout.version ?: "no-helper").append('|')
    append(net.placeNames.joinToString(","))
    for (transition in net.transitions) {
        append('|').append(transition.name).append('<')
        append(transition.inputs.joinToString(",") { it.place })
        append('>')
        append(transition.outputs.joinToString(",") { it.place })
    }
}

private fun petriLayout(net: PetriNet, layout: PetriLayout, radius: Double): Map<String, Vec2> {
    val key = petriLayoutKey(net, layout, radius)
    synchronized(petriLayoutCache) { petriLayoutCache[key] }?.let { return it }
    val solved = petriLayoutUncached(net, layout, radius)
    synchronized(petriLayoutCache) { petriLayoutCache[key] = solved }
    return solved
}

/**
 * Lays the net out, **one connected component at a time**, then packs the components side by side.
 *
 * Doing the whole net at once is the obvious implementation and it is badly wrong for exactly the
 * nets you most want to look at. A drum kit is two independent token rings; a spring model with no
 * edges between them has nothing to hold them apart, so the origin pull drags both into the same
 * space and a picture that should be two clean circles becomes one unreadable fan of overlapping
 * arcs. Laying out each piece on its own and *then* placing the pieces is what makes a multi-track
 * net legible — and it means a component that happens to be a cycle is still drawn as a cycle even
 * when the net as a whole is not one.
 */
private fun petriLayoutUncached(net: PetriNet, layout: PetriLayout, radius: Double): Map<String, Vec2> {
    val components = petriComponents(net)
    if (components.isEmpty()) return emptyMap()

    // Each component is laid out at whatever size *it* needs — a sixteen-place ring cannot be drawn
    // on the same circle as a three-place one without the places overlapping — and the pieces are
    // then packed by their measured extents rather than by a nominal cell size. The output goes
    // through `fitToSize`, so the absolute scale does not matter; only the ratios do.
    val laid = components.map { layoutComponent(net, it, layout, radius) }
    if (laid.size == 1) return laid.single()

    val gap = PLACE_RADIUS * 2.0
    val out = HashMap<String, Vec2>()
    var cursor = 0.0
    for (local in laid) {
        if (local.isEmpty()) continue
        val minX = local.values.minOf { it.x.toDouble() }
        val maxX = local.values.maxOf { it.x.toDouble() }
        for ((name, position) in local) out[name] = vec(position.x - minX + cursor, position.y)
        cursor += (maxX - minX) + gap
    }
    // Re-centre on the origin, so a pinned position is still in a predictable frame.
    if (out.isNotEmpty()) {
        val centreX = (out.values.minOf { it.x.toDouble() } + out.values.maxOf { it.x.toDouble() }) / 2.0
        for ((name, position) in out.toList()) out[name] = vec(position.x - centreX, position.y)
    }
    return out
}

/** A circle big enough that [count] nodes of radius [PLACE_RADIUS] sit side by side without touching. */
private fun ringRadiusFor(count: Int, requested: Double): Double =
    max(requested, count * (PLACE_RADIUS * 2.0 + 8.0) / (2.0 * PI))

/** The names in each connected component of the bipartite graph, in declaration order. */
private fun petriComponents(net: PetriNet): List<List<String>> {
    val names = net.placeNames + net.transitionNames
    if (names.isEmpty()) return emptyList()
    val parent = HashMap<String, String>().apply { names.forEach { put(it, it) } }

    fun find(name: String): String {
        var root = name
        while (parent.getValue(root) != root) root = parent.getValue(root)
        var walk = name
        while (parent.getValue(walk) != walk) {
            val next = parent.getValue(walk)
            parent[walk] = root
            walk = next
        }
        return root
    }

    fun union(a: String, b: String) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    for (transition in net.transitions) {
        for (arc in transition.inputs) union(transition.name, arc.place)
        for (arc in transition.outputs) union(transition.name, arc.place)
    }
    return names.groupBy { find(it) }.values.toList()
}

/** Lays out one component, choosing per component under [PetriLayout.AUTO]. */
private fun layoutComponent(
    net: PetriNet,
    names: List<String>,
    layout: PetriLayout,
    radius: Double,
): Map<String, Vec2> {
    val cycle = petriCycleOrder(net, names)
    val resolved = if (layout != PetriLayout.AUTO) layout
    else if (cycle != null) PetriLayout.RING else PetriLayout.FORCE
    return when (resolved) {
        PetriLayout.RING -> petriRingLayout(cycle ?: names, radius)
        PetriLayout.LAYERED -> petriLayeredLayout(net, names, radius)
        // A missing helper is the normal case, not an error: fall through to the spring model.
        PetriLayout.TALA -> petriTalaLayout(net, names) ?: petriForceLayout(net, names, radius)
        else -> petriForceLayout(net, names, radius)
    }
}

/**
 * The order round a net that is a single cycle, or null if it is not one.
 *
 * "A single cycle" means every place has exactly one transition in and one out, and every
 * transition likewise, and following those arcs visits everything. Which is exactly a drum ring —
 * hence drawing one as a ring without being asked.
 */
private fun petriCycleOrder(net: PetriNet, names: List<String>): List<String>? {
    val placeNames = names.filter { net.place(it) != null }
    val transitionNames = names.filter { net.transition(it) != null }
    if (placeNames.isEmpty() || transitionNames.size != placeNames.size) return null

    val outOfPlace = HashMap<String, MutableList<String>>()
    val outOfTransition = HashMap<String, MutableList<String>>()
    for (name in transitionNames) {
        val transition = net.transition(name) ?: return null
        val ins = transition.inputs.filter { it.kind == PetriArcKind.NORMAL }
        if (ins.size != 1 || transition.outputs.size != 1) return null
        if (ins.single().weight != 1 || transition.outputs.single().weight != 1) return null
        outOfPlace.getOrPut(ins.single().place) { mutableListOf() } += transition.name
        outOfTransition.getOrPut(transition.name) { mutableListOf() } += transition.outputs.single().place
    }
    if (outOfPlace.size != placeNames.size || outOfPlace.values.any { it.size != 1 }) return null

    val order = mutableListOf<String>()
    var place = placeNames.first()
    repeat(placeNames.size) {
        val transition = outOfPlace[place]?.singleOrNull() ?: return null
        order += place
        order += transition
        place = outOfTransition[transition]?.singleOrNull() ?: return null
    }
    return if (place == placeNames.first() && order.size == placeNames.size * 2) order else null
}

private fun petriRingLayout(order: List<String>, radius: Double): Map<String, Vec2> {
    if (order.isEmpty()) return emptyMap()
    val fitted = ringRadiusFor(order.size, radius)
    return order.withIndex().associate { (index, name) ->
        // Start at the top and go clockwise, which is how anyone reads a cycle.
        val angle = -PI / 2.0 + 2.0 * PI * index / order.size
        name to vec(cos(angle) * fitted, sin(angle) * fitted)
    }
}

private fun petriLayeredLayout(net: PetriNet, names: List<String>, radius: Double): Map<String, Vec2> {
    val here = names.toSet()
    val placeNames = names.filter { net.place(it) != null }
    val depth = HashMap<String, Int>()
    val queue = ArrayDeque<String>()
    for (place in placeNames) if (net.initial.count(place) > 0) {
        depth[place] = 0
        queue += place
    }
    if (queue.isEmpty()) placeNames.firstOrNull()?.let { depth[it] = 0; queue += it }

    while (queue.isNotEmpty()) {
        val at = queue.removeFirst()
        val level = depth.getValue(at)
        for (transition in net.transitions) {
            if (transition.name !in here) continue
            val touchesHere = transition.inputs.any { it.place == at }
            if (!touchesHere || transition.name in depth) continue
            depth[transition.name] = level + 1
            queue += transition.name
            for (arc in transition.outputs) if (arc.place in here && arc.place !in depth) {
                depth[arc.place] = level + 2
                queue += arc.place
            }
        }
    }
    for (name in names) depth.putIfAbsent(name, 0)

    val byLevel = depth.entries.groupBy({ it.value }, { it.key })
    val levels = byLevel.keys.sorted()
    val spanX = radius * 2.0
    return buildMap {
        for ((column, level) in levels.withIndex()) {
            val here = byLevel.getValue(level).sorted()
            val x = if (levels.size <= 1) 0.0 else -radius + spanX * column / (levels.size - 1)
            for ((row, name) in here.withIndex()) {
                val y = if (here.size <= 1) 0.0 else -radius + 2.0 * radius * row / (here.size - 1)
                put(name, vec(x, y))
            }
        }
    }
}

/**
 * A spring model relaxed from many random starts, scored, and the best drawing kept.
 *
 * The physics lives in [PetriLayoutSolver]; this only translates the net into the graph it wants
 * and the answer back into positions. The separation of concerns is worth the indirection: the
 * solver knows nothing about places, transitions or arcs, which is what lets it be tested against
 * a measurable objective rather than against a screenshot.
 */
private fun petriForceLayout(
    net: PetriNet,
    names: List<String>,
    radius: Double,
): Map<String, Vec2> {
    if (names.isEmpty()) return emptyMap()
    if (names.size == 1) return mapOf(names.single() to vec(0.0, 0.0))

    val index = names.withIndex().associate { (i, name) -> name to i }
    // Undirected and de-duplicated: a place that is both an input and an output of the same
    // transition is drawn as two arcs but is one line as far as crossings are concerned.
    val edges = LinkedHashSet<PetriLayoutSolver.Edge>()
    for (transition in net.transitions) {
        val t = index[transition.name] ?: continue
        for (arc in transition.inputs) index[arc.place]?.let { edges += PetriLayoutSolver.Edge(min(it, t), max(it, t)) }
        for (arc in transition.outputs) index[arc.place]?.let { edges += PetriLayoutSolver.Edge(min(it, t), max(it, t)) }
    }
    val edgeList = edges.toList()

    val solution = PetriLayoutSolver.solve(
        count = names.size,
        edges = edgeList,
        radius = radius,
        seed = PetriLayoutSolver.seedFor(names, edgeList),
    )
    return names.withIndex().associate { (i, name) ->
        name to vec(solution.x[i].toDouble(), solution.y[i].toDouble())
    }
}

/**
 * The layout behind [PetriLayout.TALA]: the net handed to d2's diagram engine through the
 * optional `native/field-d2-layout` helper.
 *
 * Returns null — not an exception, not a bad drawing — whenever the helper cannot answer: not
 * built, wrong protocol, timed out, or a net it refused. [layoutComponent] turns that into
 * [petriForceLayout], so a document asking for TALA still opens on a machine without the binary.
 *
 * No `radius` parameter, unlike its siblings. Those need one because they place dimensionless
 * points and have to be told how far apart; TALA is given the real size of every place and
 * transition and derives its own extent. The result goes through `fitToSize` regardless.
 *
 * Arcs are passed directed, place to transition and transition to place. [petriForceLayout]
 * deliberately collapses them to undirected unique pairs, because a spring does not care which
 * way it points and a doubled arc would pull twice; TALA reads direction as flow and lays out
 * along it, so here the distinction is worth keeping.
 */
private fun petriTalaLayout(net: PetriNet, names: List<String>): Map<String, Vec2>? {
    if (names.isEmpty()) return emptyMap()
    if (names.size == 1) return mapOf(names.single() to vec(0.0, 0.0))
    if (!D2Layout.available) return null

    val here = names.toSet()
    // A place is a circle of PLACE_RADIUS. A transition is a bar that rotates to face its arcs,
    // so the only size that holds at every angle is a square at the bar's full length.
    val nodes = names.map { name ->
        val size = if (net.place(name) != null) PLACE_RADIUS * 2.0 else BAR_HALF_LENGTH * 2.0
        D2Layout.Node(id = name, w = size, h = size)
    }
    val edges = ArrayList<D2Layout.Edge>()
    for (transition in net.transitions) {
        if (transition.name !in here) continue
        for (arc in transition.inputs) if (arc.place in here) {
            edges += D2Layout.Edge(src = arc.place, dst = transition.name)
        }
        for (arc in transition.outputs) if (arc.place in here) {
            edges += D2Layout.Edge(src = transition.name, dst = arc.place)
        }
    }

    // TALA takes a direction bias, and which one reads best is a property of the net rather than
    // something to guess: on the dining table below, `up` draws it with no crossings at all while
    // the unbiased layout has five. Each attempt costs about what one layout costs, and the whole
    // sweep is cheaper than raising the seed budget for a worse result, so run them and score.
    val attempts = listOf(
        D2Layout.Direction.NONE, D2Layout.Direction.UP,
        D2Layout.Direction.DOWN, D2Layout.Direction.RIGHT,
    ).mapNotNull { direction ->
        D2Layout.layout(nodes, edges, engine = D2Layout.Engine.TALA, direction = direction)
    }
    // First-wins on a tie, over a fixed list, so the chosen drawing is reproducible.
    val result = attempts.minByOrNull { drawnCrossings(it, edges) } ?: return null
    val placed = result.nodes.associateBy { it.id }
    // A partial answer would draw some of the net in the right place and pile the rest on the
    // origin, which reads as a bug in the net rather than in the layout. Refuse the whole thing.
    if (names.any { it !in placed }) return null

    // The helper reports top-left corners; every other layout here reports centres about the
    // origin, and the component packer in `petriLayout` assumes that.
    val centres = names.associateWith { name ->
        val n = placed.getValue(name)
        n.centreX to n.centreY
    }
    val midX = (centres.values.minOf { it.first } + centres.values.maxOf { it.first }) / 2.0
    val midY = (centres.values.minOf { it.second } + centres.values.maxOf { it.second }) / 2.0
    return centres.mapValues { (_, c) -> vec(c.first - midX, c.second - midY) }
}

/**
 * How many pairs of arcs cross, given where the nodes are.
 *
 * [at] resolves a node name to the point the arc is drawn from or to. Arcs sharing an endpoint are
 * skipped: every net has those, and counting them would score the net rather than the drawing.
 */
private fun crossingCount(
    arcs: List<Pair<String, String>>,
    at: (String) -> Pair<Double, Double>?,
): Int {
    fun turns(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)

    val drawn = arcs.mapNotNull { (from, to) ->
        val a = at(from) ?: return@mapNotNull null
        val b = at(to) ?: return@mapNotNull null
        Triple(from, to, a to b)
    }
    var count = 0
    for (i in drawn.indices) {
        for (j in i + 1 until drawn.size) {
            val (aFrom, aTo, a) = drawn[i]
            val (bFrom, bTo, b) = drawn[j]
            if (aFrom == bFrom || aFrom == bTo || aTo == bFrom || aTo == bTo) continue
            val (p, q) = a
            val (r, t) = b
            if ((turns(r, t, p) > 0) != (turns(r, t, q) > 0) &&
                (turns(p, q, r) > 0) != (turns(p, q, t) > 0)
            ) count++
        }
    }
    return count
}

/** Every arc in the net as a `from to to` pair, in declaration order. */
private fun PetriNet.arcPairs(): List<Pair<String, String>> = buildList {
    for (transition in transitions) {
        for (arc in transition.inputs) add(arc.place to transition.name)
        for (arc in transition.outputs) add(transition.name to arc.place)
    }
}

/**
 * How many of this drawing's arcs cross each other.
 *
 * Counted on straight centre-to-centre segments, which is what [toFLines] lays down. Comparing two
 * layouts of one net is what this is for -- [PetriLayout.TALA] uses it to choose between direction
 * biases, and a figure can print it so the reader is not taking anyone's word for the improvement.
 */
fun PetriDrawing.arcCrossings(): Int =
    crossingCount(net.arcPairs()) { name ->
        positions[name]?.let { it.x.toDouble() to it.y.toDouble() }
    }

/**
 * The same count for a layout that has not been turned into a [PetriDrawing] yet.
 *
 * TALA also returns routed polylines and they are the obvious thing to measure, but this drawing
 * never uses them: only the node positions are taken. Scoring the routes instead chose a layout
 * with no route crossings and three visible ones.
 */
private fun drawnCrossings(result: D2Layout.Response, edges: List<D2Layout.Edge>): Int {
    val centres = result.nodes.associate { it.id to (it.centreX to it.centreY) }
    return crossingCount(edges.map { it.src to it.dst }) { centres[it] }
}

// ── drawing ────────────────────────────────────────────────────────────────────────────────

private const val PLACE_RADIUS = 26.0
private const val BAR_HALF_LENGTH = 22.0
private const val BAR_HALF_WIDTH = 5.0


private val DIM = hsl(0.58, 0.10, 0.42, 0.55)
private val LIVE = hsl(0.09, 0.85, 0.60, 0.98)
private val READY = hsl(0.45, 0.70, 0.62, 0.92)
private val QUIET = hsl(0.58, 0.30, 0.62, 0.80)
private val INK = vec(0.87, 0.89, 0.92, 0.95)

/**
 * Default label budget for [toFLines]: how many places-plus-transitions can carry names.
 *
 * Named rather than inline because a caller who needs to know whether a given drawing *should* have
 * words in it — the petri gallery's figure guard, for one — must ask the same question this file
 * answers, and two copies of `28` is how that stops being true.
 */
const val PETRI_LABEL_LIMIT = 28

/**
 * The picture — hand it to `lines(...)` or use [Box.showPetriNet].
 *
 * [labels] draws place and transition names as real glyph outlines. It needs a font, so it is
 * skipped rather than fatal if one cannot be resolved (a headless box with no fonts installed
 * still gets a usable diagram, just an anonymous one).
 *
 * Labels are also dropped once the net passes [labelLimit] nodes, whatever [labels] says. Thirty-two
 * names crammed into one diagram do not read as names, they read as a grey haze over the part you
 * were trying to look at — and the summary card next to the drawing lists every one of them anyway.
 */
fun PetriDrawing.toFLines(
    labels: Boolean = true,
    labelSize: Double = 15.0,
    labelLimit: Int = PETRI_LABEL_LIMIT,
): List<FLine> {
    @Suppress("NAME_SHADOWING")
    val labels = labels && net.places.size + net.transitions.size <= labelLimit
    val out = mutableListOf<FLine>()
    if (net.places.isEmpty() && net.transitions.isEmpty()) return out

    for (transition in net.transitions) {
        val centre = position(transition.name)
        val angle = barAngle(transition, centre)
        for (arc in transition.inputs) {
            out += arcLine(position(arc.place), centre, arc.kind, arc.weight, transition.name in firing, angle, labels, labelSize)
        }
        for (arc in transition.outputs) {
            out += arcLine(centre, position(arc.place), null, arc.weight, transition.name in firing, angle, labels, labelSize)
        }
    }

    for (place in net.places) out += placeLines(place)
    for (transition in net.transitions) out += barLines(transition)

    if (labels) {
        for (place in net.places) {
            out += label(place.name, position(place.name).let { vec(it.x, it.y + PLACE_RADIUS + labelSize + 3.0) }, labelSize)
        }
        for (transition in net.transitions) {
            val emit = transition.emit?.let { " ${it}" } ?: ""
            out += label(
                transition.name + emit,
                position(transition.name).let { vec(it.x, it.y + BAR_HALF_LENGTH + labelSize + 3.0) },
                labelSize,
                if (transition.name in firing) LIVE else if (transition.name in enabled) READY else DIM,
            )
        }
    }

    return out.filter { it.nodes.isNotEmpty() }
}

/** Perpendicular to the average direction the transition's arcs run, so a bar sits across its flow. */
private fun PetriDrawing.barAngle(transition: PetriTransition, centre: Vec2): Double {
    var sx = 0.0
    var sy = 0.0
    for (arc in transition.inputs) {
        val from = position(arc.place)
        sx += (centre.x - from.x).toDouble()
        sy += (centre.y - from.y).toDouble()
    }
    for (arc in transition.outputs) {
        val to = position(arc.place)
        sx += (to.x - centre.x).toDouble()
        sy += (to.y - centre.y).toDouble()
    }
    return if (hypot(sx, sy) < 1e-6) 0.0 else atan2(sy, sx)
}

private fun PetriDrawing.placeLines(place: PetriPlace): List<FLine> {
    val centre = position(place.name)
    val tokens = marking[place.name]
    val out = mutableListOf<FLine>()

    out += fline {
        circle(centre.x.toDouble(), centre.y.toDouble(), PLACE_RADIUS, 40)
        filled = true
        stroked = false
        color = hsl(0.60, 0.22, 0.13, 0.92)
    }
    out += fline {
        circle(centre.x.toDouble(), centre.y.toDouble(), PLACE_RADIUS, 40)
        filled = false
        stroked = true
        thickness = if (tokens.isEmpty()) 1.4 else 2.2
        color = if (tokens.isEmpty()) DIM else QUIET
    }
    if (place.capacity != null) {
        out += fline {
            circle(centre.x.toDouble(), centre.y.toDouble(), PLACE_RADIUS - 4.0, 40)
            filled = false
            stroked = true
            thickness = 0.8
            color = hsl(0.58, 0.30, 0.55, 0.40)
        }
    }

    // Up to seven dots arranged the way dice are, then a count instead — a place with forty tokens
    // in it wants a number, not forty overlapping dots.
    if (tokens.size in 1..7) {
        val plain = tokens.filter { it.colour !is PatPattern<*> }
        val rhythmic = tokens.filter { it.colour is PatPattern<*> }
        if (plain.isNotEmpty()) {
            out += fline {
                pointed = true
                pointSize = 3.4
                color = INK
                plain.forEachIndexed { index, token ->
                    val spot = tokenSpot(centre, index, tokens.size)
                    moveTo(spot)
                    if (token.colour != null) hoverColor = colourOf(token.colour)
                }
            }
        }
        // A token carrying a pattern is a section, not a count, so it gets a ring of its own.
        rhythmic.forEachIndexed { index, _ ->
            val spot = tokenSpot(centre, plain.size + index, tokens.size)
            out += fline {
                circle(spot.x.toDouble(), spot.y.toDouble(), 6.0, 18)
                filled = false
                stroked = true
                thickness = 1.6
                color = hsl(0.13, 0.80, 0.66, 0.95)
            }
        }
    } else if (tokens.size > 7) {
        out += label(tokens.size.toString(), centre, 16.0, INK)
    }
    return out
}

private fun tokenSpot(centre: Vec2, index: Int, total: Int): Vec2 {
    if (total == 1) return centre
    val angle = -PI / 2.0 + 2.0 * PI * index / total
    val ring = if (total <= 4) 9.0 else 12.0
    return vec(centre.x + cos(angle) * ring, centre.y + sin(angle) * ring)
}

private fun PetriDrawing.barLines(transition: PetriTransition): List<FLine> {
    val centre = position(transition.name)
    val angle = barAngle(transition, centre) + PI / 2.0
    val ux = cos(angle)
    val uy = sin(angle)
    val px = -uy
    val py = ux
    val alive = transition.name in firing
    val ready = transition.name in enabled

    fun corner(alongSign: Double, acrossSign: Double) = vec(
        centre.x + ux * BAR_HALF_LENGTH * alongSign + px * BAR_HALF_WIDTH * acrossSign,
        centre.y + uy * BAR_HALF_LENGTH * alongSign + py * BAR_HALF_WIDTH * acrossSign,
    )

    return listOf(
        fline {
            moveTo(corner(1.0, 1.0))
            lineTo(corner(-1.0, 1.0))
            lineTo(corner(-1.0, -1.0))
            lineTo(corner(1.0, -1.0))
            close()
            filled = alive || ready
            stroked = true
            thickness = if (alive) 2.6 else 1.5
            color = when {
                alive -> LIVE
                ready -> READY
                else -> DIM
            }
        }
    )
}

/** One arc, clipped to the shapes at each end so an arrowhead lands on the rim rather than the centre. */
private fun PetriDrawing.arcLine(
    from: Vec2,
    to: Vec2,
    kind: PetriArcKind?,
    weight: Int,
    hot: Boolean,
    barAngle: Double,
    labels: Boolean,
    labelSize: Double,
): List<FLine> {
    val dx = (to.x - from.x).toDouble()
    val dy = (to.y - from.y).toDouble()
    val length = hypot(dx, dy)
    if (length < 1e-6) return emptyList()
    val ux = dx / length
    val uy = dy / length

    // An input arc runs place → transition; an output arc runs transition → place. `kind == null`
    // marks the output direction.
    val startInset = if (kind == null) BAR_HALF_WIDTH + 3.0 else PLACE_RADIUS + 2.0
    val endInset = if (kind == null) PLACE_RADIUS + 3.0 else BAR_HALF_WIDTH + 4.0
    if (length <= startInset + endInset + 6.0) return emptyList()

    val a = vec(from.x + ux * startInset, from.y + uy * startInset)
    val b = vec(to.x - ux * endInset, to.y - uy * endInset)
    val tint = if (hot) LIVE else hsl(0.58, 0.22, 0.52, 0.72)
    val out = mutableListOf<FLine>()

    when (kind) {
        PetriArcKind.INHIBIT -> {
            // The standard notation: a small hollow circle at the transition end, no arrowhead.
            val stop = vec(b.x - ux * 6.0, b.y - uy * 6.0)
            out += fline {
                moveTo(a); lineTo(stop)
                stroked = true; filled = false
                thickness = if (hot) 2.2 else 1.3
                color = tint
            }
            out += fline {
                circle(b.x.toDouble(), b.y.toDouble(), 5.0, 16)
                stroked = true; filled = false
                thickness = 1.6
                color = tint
            }
        }
        PetriArcKind.READ -> {
            // No head at all: nothing flows along a test arc.
            out += fline {
                moveTo(a); lineTo(b)
                stroked = true; filled = false
                thickness = if (hot) 2.0 else 1.1
                color = hsl(0.45, 0.35, 0.55, 0.55)
            }
        }
        else -> {
            out += arrow(
                Vec2(a), Vec2(b),
                color = tint,
                thickness = if (hot) 2.4 else 1.4,
                headLength = 11.0,
                headWidth = 7.0,
            )
        }
    }

    if (weight > 1 && labels) {
        val mid = vec((a.x + b.x) / 2.0 + uy * 9.0, (a.y + b.y) / 2.0 - ux * 9.0)
        out += label(weight.toString(), mid, labelSize * 0.9, tint)
    }
    return out
}

/**
 * A short string as glyph outlines, centred on [centre].
 *
 * Returns nothing rather than throwing when no font can be resolved: a diagram without labels beats
 * no diagram at all, and a headless environment with no fonts is a real place this code runs.
 *
 * It says so on stderr, though, once per JVM. Falling back silently is how the whole petri gallery
 * came to write plausible-looking wordless figures and exit 0 for as long as the `jvm-main` child
 * was missing `-Djava.awt.headless=true` (see `requiredAgentJvmArgs` in `agent_backdoor.kt`, and
 * [petriLabelsRenderable]); a fallback nobody can see is indistinguishable from a net whose places
 * and transitions have no names.
 */
private fun label(text: String, centre: Vec2, size: Double, tint: afield.stdlib.Vec4 = INK): List<FLine> {
    if (text.isEmpty()) return emptyList()
    val face = petriLabelTypeface(size) ?: return emptyList()
    return try {
        val layout = layoutText(text, face)
        val bounds = layout.bounds
        val offsetX = centre.x - (bounds.x + bounds.w / 2.0).toFloat()
        val offsetY = centre.y - (bounds.y + bounds.h / 2.0).toFloat()
        // One FLine per glyph, stroked rather than filled, and moved with `FLine.transform`. All
        // three matter:
        //
        //  - flattening a whole string into one *filled* FLine hands the triangulator a single path
        //    made of dozens of disjoint contours and it fans them from a common origin, drawing a
        //    starburst across the diagram instead of a word;
        //  - `transform` moves every stored point, control points included. Nudging `node.to` alone
        //    leaves the cubic handles behind, which smears each letter into a horizontal streak —
        //    letters being mostly curves.
        layout.toFLines(color = tint, thickness = 0.7, filled = false, stroked = true).map { line ->
            line.transform { point -> afield.stdlib.vec(point.x + offsetX, point.y + offsetY, point.z) }
                .also { moved ->
                    moved.color = tint
                    moved.thickness = 0.7
                    moved.filled = false
                    moved.stroked = true
                }
        }
    } catch (ex: Throwable) {
        reportLabelFailure("laying out '$text'", ex)
        emptyList()
    }
}

@Volatile
private var labelFaceFailed = false

private val labelFailureReported = java.util.concurrent.atomic.AtomicBoolean(false)

private fun petriLabelTypeface(size: Double): afield.stdlib.Typeface? {
    if (labelFaceFailed) return null
    return try {
        typeface(Font.MONOSPACED, size)
    } catch (ex: Throwable) {
        labelFaceFailed = true
        reportLabelFailure("resolving ${Font.MONOSPACED}", ex)
        null
    }
}

/** Say once, on stderr, that this JVM is drawing petri figures without any words in them. */
private fun reportLabelFailure(what: String, ex: Throwable) {
    if (!labelFailureReported.compareAndSet(false, true)) return
    System.err.println(
        "afield.petri: no glyph outlines in this JVM ($what failed: ${ex.message}). " +
            "Every figure drawn from here will be correct but wordless. " +
            "If this is a forked JVM, it needs -Djava.awt.headless=true."
    )
}

/**
 * Whether this JVM can turn a label into glyph outlines at all.
 *
 * The one thing worth asserting before believing a figure: [label] is deliberately forgiving, so
 * "the fonts are gone" and "this net has short names" produce the same picture. Cheap after the
 * first call — the answer is a cached outline either way.
 */
fun petriLabelsRenderable(): Boolean = label("Mg", vec(0.0, 0.0), 12.0).isNotEmpty()

private fun colourOf(colour: Any?): afield.stdlib.Vec4 {
    val hash = colour?.hashCode() ?: 0
    return hsl(((hash % 997 + 997) % 997) / 997.0, 0.72, 0.66, 0.95)
}

private fun FLine.circle(cx: Double, cy: Double, r: Double, segments: Int = 24) {
    for (step in 0..segments) {
        val angle = 2.0 * PI * step / segments
        val nx = cx + r * cos(angle)
        val ny = cy + r * sin(angle)
        if (step == 0) moveTo(nx, ny) else lineTo(nx, ny)
    }
    close()
}

// ── showing it in a box ────────────────────────────────────────────────────────────────────

/**
 * Draws a static frame of a net into a box output.
 *
 * Nothing here is clickable — a [PetriDrawing] is a photograph. Use the [PetriLive] overload when
 * you want to poke it.
 */
fun Box.showPetriNet(
    drawing: PetriDrawing,
    channel: String = "petri",
    summaryChannel: String? = "$channel-summary",
    w: Double = 900.0,
    h: Double = 560.0,
    labels: Boolean = true,
    fitToSize: Boolean = true,
): List<FLine> {
    val rendered = drawing.toFLines(labels)
    lines(rendered, channel = channel, w = w, h = h, fitToSize = fitToSize)
    summaryChannel?.let { display(it, drawing.summaryHtml()) }
    return rendered
}

/** [showPetriNet] for a net sitting at its initial marking. */
fun Box.showPetriNet(
    net: PetriNet,
    channel: String = "petri",
    summaryChannel: String? = "$channel-summary",
    w: Double = 900.0,
    h: Double = 560.0,
    layout: PetriLayout = PetriLayout.AUTO,
    labels: Boolean = true,
): List<FLine> = showPetriNet(net.frame(layout), channel, summaryChannel, w, h, labels)

/** [showPetriNet] for a run at a particular instant — the frame the animation harness draws. */
fun Box.showPetriNet(
    run: PetriRun,
    at: PatQ,
    channel: String = "petri",
    summaryChannel: String? = "$channel-summary",
    w: Double = 900.0,
    h: Double = 560.0,
    layout: PetriLayout = PetriLayout.AUTO,
    labels: Boolean = true,
): List<FLine> = showPetriNet(run.frameAt(at, layout), channel, summaryChannel, w, h, labels)

/**
 * Draws a **live** net, and wires the picture up so clicking it changes the net.
 *
 * Click a place to drop a token in; shift is not available in the SVG output, so clicking a place
 * that is already full to its capacity takes one out instead — the one gesture cycles. Click a
 * transition to force it to fire now, whether or not the clock was going to.
 *
 * Both are `FLine.onClick`, which only does anything in the interactive outputs. Call [redraw] from
 * the handlers (or hand [onChange] a redraw) to see the result.
 */
fun Box.showPetriNet(
    live: PetriLive,
    channel: String = "petri",
    summaryChannel: String? = "$channel-summary",
    w: Double = 900.0,
    h: Double = 560.0,
    layout: PetriLayout = PetriLayout.AUTO,
    labels: Boolean = true,
    onChange: (() -> Unit)? = null,
): List<FLine> {
    val drawing = live.frame(layout)
    val rendered = drawing.toFLines(labels).toMutableList()

    fun refresh() {
        showPetriNet(live, channel, summaryChannel, w, h, layout, labels, onChange)
        onChange?.invoke()
    }

    // One transparent disc per place and one per transition, drawn last so they sit on top of the
    // artwork and catch the click. Making the *visible* shapes clickable would work too, but a
    // place is several FLines and a hit target should be one.
    for (place in net@ drawing.net.places) {
        val centre = drawing.position(place.name)
        rendered += fline {
            circle(centre.x.toDouble(), centre.y.toDouble(), PLACE_RADIUS, 28)
            filled = true
            stroked = false
            color = vec(1.0, 1.0, 1.0, 0.001)
            hoverColor = hsl(0.45, 0.70, 0.62, 0.18)
            onClick = { _: FLineIntersection ->
                val full = place.capacity != null && live.marking.count(place.name) >= place.capacity
                if (full || (place.capacity == null && live.marking.count(place.name) > 0 && live.clickRemoves)) {
                    live.remove(place.name)
                } else {
                    live.inject(place.name)
                }
                refresh()
            }
        }
    }
    for (transition in drawing.net.transitions) {
        val centre = drawing.position(transition.name)
        rendered += fline {
            circle(centre.x.toDouble(), centre.y.toDouble(), BAR_HALF_LENGTH, 24)
            filled = true
            stroked = false
            color = vec(1.0, 1.0, 1.0, 0.001)
            hoverColor = hsl(0.09, 0.85, 0.60, 0.22)
            onClick = { _: FLineIntersection ->
                live.force(transition.name)
                refresh()
            }
        }
    }

    lines(rendered, channel = channel, w = w, h = h, fitToSize = true)
    summaryChannel?.let { display(it, drawing.summaryHtml(live = true)) }
    return rendered
}

/** The drawing as a standalone SVG string — for docs, for a file, for anywhere without a box. */
fun PetriDrawing.toSvg(w: Double = 900.0, h: Double = 560.0, labels: Boolean = true): String =
    drawToSVG(toFLines(labels), w, h)

// ── the words next to the picture ──────────────────────────────────────────────────────────

/** A dark card summarising the frame: the marking, what is ready, what is sounding, and the period. */
fun PetriDrawing.summaryHtml(title: String = "Petri net", live: Boolean = false): String {
    fun chip(text: String, tint: String) =
        """<span style="display:inline-block;margin:2px 4px 2px 0;padding:2px 7px;border-radius:9px;background:$tint;color:#eee;font-size:11px">${escape(text)}</span>"""

    val places = net.places.joinToString("") { place ->
        val count = marking.count(place.name)
        val patterns = marking[place.name].count { it.colour is PatPattern<*> }
        val detail = when {
            patterns > 0 -> "$count (${patterns} pattern${if (patterns == 1) "" else "s"})"
            else -> count.toString()
        }
        chip("${place.name} $detail", if (count > 0) "#1d3a2e" else "#242424")
    }
    // Every transition, tinted by state — not just the enabled ones. A transition that never fires
    // is the single most common thing wrong with a net, and leaving it off the list is exactly how
    // you fail to notice.
    val ready = net.transitionNames.joinToString("") { name ->
        chip(
            name, when {
                name in firing -> "#5a2a10"
                name in enabled -> "#1b3444"
                else -> "#242424"
            }
        )
    } + if (enabled.isEmpty()) {
        """<div style="color:#a06060;font-size:11px;margin-top:3px">nothing is enabled — the net is stuck</div>"""
    } else ""

    return """
        <div style="font-family:Iosevka Web, IBM Plex Mono, monospace;background:#0a0a0a;color:#d8d8d8;border:1px solid #2c2c2c;padding:10px 12px;max-width:900px">
          <div style="display:flex;justify-content:space-between;align-items:baseline">
            <strong style="font-size:13px">${escape(title)}</strong>
            <span style="color:#8a8a8a;font-size:11px">${if (live) "live · click to inject or fire" else "at ${escape(at.toString())} cycles"}</span>
          </div>
          <div style="margin-top:7px;color:#8a8a8a;font-size:11px">places</div>
          <div>$places</div>
          <div style="margin-top:6px;color:#8a8a8a;font-size:11px">transitions — orange is sounding, blue is ready, grey cannot go</div>
          <div>$ready</div>
          <div style="margin-top:7px;color:#6e6e6e;font-size:11px">
            ${net.places.size} places · ${net.transitions.size} transitions · ${escape(net.timing.name.lowercase(Locale.US))} at grid ${escape(net.grid.toString())}
          </div>
        </div>
    """.trimIndent()
}

private fun escape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** An ASCII picture of the marking, for a terminal, a log, or a smoke test. */
fun PetriMarking.asciiRow(net: PetriNet): String = net.placeNames.joinToString(" ") { place ->
    val count = count(place)
    "$place:" + when {
        count == 0 -> "·"
        count <= 4 -> "•".repeat(count)
        else -> count.toString()
    }
}

/** [PetriRun.asciiTimeline] from a fresh run — what you want when you are looking at a net, not a performance. */
fun PetriNet.asciiTimeline(steps: Int = 16): String = run().asciiTimeline(steps)

/** A marking-over-time strip: one row per step, one column per place. The cheapest useful picture. */
fun PetriRun.asciiTimeline(steps: Int = 16): String {
    val rows = steps(steps)
    val width = max(4, net.placeNames.maxOfOrNull { it.length } ?: 4)
    return buildString {
        append("time".padEnd(9))
        net.placeNames.forEach { append(it.padStart(width + 1)) }
        append("   fired\n")
        for (step in rows) {
            append(step.at.toString().padEnd(9))
            net.placeNames.forEach { place -> append(step.after.count(place).toString().padStart(width + 1)) }
            append("   ${step.firings.joinToString(",") { it.transition }}\n")
        }
    }
}

// ── the state space, drawn ─────────────────────────────────────────────────────────────────

/*
 A net has a standard picture and a state space does not, which is most of the reason the classical
 properties feel abstract when the net they are about does not. The drawing below is an attempt at
 the missing picture, and it is built round one decision: the *counterexample* is the subject, and
 everything else is context for it.

 So the graph is laid out in breadth-first layers — depth left to right, which reads as elapsed
 firings — and the shortest path to the deadlock runs across it as a bright line with its transition
 names written on it. The rest of the space is drawn thin and quiet. What you should be able to see
 at a glance is: how wide the net's future is, how few steps it takes to ruin it, and which way.

 Above a node count that stops working, and the honest thing is to say so rather than draw it anyway.
 [PETRI_STATE_DRAW_LIMIT] is where. Eighty markings is dense and still readable, because the path is
 bright and the rest is context. A few thousand is a grey rectangle with an orange line somewhere in
 it, and the grey rectangle is not information, it is ink — `nodeRadius` has already bottomed out at
 two pixels and the layers are overlapping each other. Past the limit the drawing declines the nodes
 and keeps the two things that survive being summarised: the counterexample, drawn *larger* because
 it is now the whole subject, and one bar per breadth-first layer whose height is that layer's
 population, which is the only thing the hairball was ever really saying. And it writes on the figure
 that it declined, because a picture that has quietly stopped showing you the data is worse than no
 picture.
*/

private val STATE_DEAD = hsl(0.01, 0.80, 0.58, 0.98)
private val STATE_EDGE = hsl(0.58, 0.22, 0.52, 0.34)
private val STATE_BACK = hsl(0.52, 0.30, 0.50, 0.30)
private val STATE_NODE = hsl(0.58, 0.28, 0.60, 0.75)

/**
 * How many markings [PetriStateGraph.toFLines] will draw one dot each for before it declines.
 *
 * Four hundred is not a taste judgement, it is where the layout runs out of pixels. `nodeRadius`
 * floors itself at two pixels, and at a default 1200×700 with a margin the widest layer can hold
 * about a hundred and fifty two-pixel dots before they touch; four hundred markings in a graph five
 * or six layers deep is already past that on some layer. Below the limit the picture is dense and
 * readable — the eighty-two-marking dining table is the case it is tuned around. Above it, the dots
 * are noise and [PetriStateGraph.tooDenseToDraw] is true.
 */
const val PETRI_STATE_DRAW_LIMIT: Int = 400

/**
 * True when this graph has more markings than [maxNodes], and so [toFLines] will summarise rather
 * than draw it.
 *
 * Worth asking before building a figure round the drawing: the answer changes what the picture is
 * *of*, from a state space with a path through it to a path with a state space behind it.
 */
fun PetriStateGraph.tooDenseToDraw(maxNodes: Int = PETRI_STATE_DRAW_LIMIT): Boolean = stateCount > maxNodes

/**
 * The state graph as a picture: BFS layers left to right, one dot per reachable marking, and the
 * shortest route to a deadlock drawn over the top of them.
 *
 * [highlight] defaults to [PetriStateGraph.deadlockTrace], which is the trace you almost always
 * want; pass any other trace — a [PetriStateGraph.witness] of some property — to draw that instead,
 * or null for the bare graph.
 *
 * Labels are the transition names *along the highlighted path only*. Writing the marking on every
 * node was tried and is unreadable at eighty states: a dining table's marking is fifteen places
 * long and the labels overlap into a grey band. The path names are the part a reader can act on,
 * and the summary card next to the drawing has the numbers.
 *
 * Above [maxNodes] markings this hands off to a **declared summary** instead of drawing the nodes —
 * see [PETRI_STATE_DRAW_LIMIT] and the section comment above. Pass a larger [maxNodes] to insist,
 * or `Int.MAX_VALUE` to get the old behaviour back for a figure you have measured yourself.
 */
fun PetriStateGraph.toFLines(
    highlight: PetriTrace? = deadlockTrace(),
    w: Double = 1200.0,
    h: Double = 700.0,
    labels: Boolean = true,
    labelSize: Double = 13.0,
    maxNodes: Int = PETRI_STATE_DRAW_LIMIT,
): List<FLine> {
    if (stateCount == 0) return emptyList()
    if (stateCount > maxNodes) return declinedDrawing(highlight, w, h, labels, labelSize, maxNodes)
    val position = layeredPositions(w, h)
    val radius = nodeRadius(w, h)
    val out = mutableListOf<FLine>()

    // Which nodes and which edges the highlighted trace runs through. Matching by marking rather
    // than by remembering indices keeps a hand-built trace drawable.
    val idOf = HashMap<PetriMarking, Int>()
    states.forEachIndexed { id, marking -> idOf.putIfAbsent(marking, id) }
    val pathNodes = highlight?.markings?.mapNotNull { idOf[it] } ?: emptyList()
    val pathEdges = HashSet<Triple<Int, String, Int>>()
    if (highlight != null && pathNodes.size == highlight.markings.size) {
        for ((step, name) in highlight.transitions.withIndex()) {
            pathEdges += Triple(pathNodes[step], name, pathNodes[step + 1])
        }
    }

    // Arrowheads are dropped on a dense graph. Two hundred heads at three pixels each are not
    // direction, they are texture, and they cost more than they say.
    val heads = edgeCount <= 140 && radius >= 4.0

    for (edge in edges) {
        val hot = Triple(edge.from, edge.transition, edge.to) in pathEdges
        if (edge.from == edge.to) {
            out += selfLoop(position[edge.from], radius, hot)
            continue
        }
        val from = position[edge.from]
        val to = position[edge.to]
        val dx = (to.x - from.x).toDouble()
        val dy = (to.y - from.y).toDouble()
        val length = hypot(dx, dy)
        if (length < 1e-6) continue
        val a = vec(from.x + dx / length * radius, from.y + dy / length * radius)
        val b = vec(to.x - dx / length * (radius + if (heads) 2.0 else 0.0), to.y - dy / length * radius)
        val backward = depth(edge.to) <= depth(edge.from)
        val tint = if (hot) LIVE else if (backward) STATE_BACK else STATE_EDGE
        // An edge that goes *back* a layer is drawn bowed, because it otherwise lands exactly on
        // top of the forward edge between the same two states and the picture says "one way" about
        // a net that can return. On the four-slot buffer that is the difference between a chain and
        // a cycle, which is the whole property being illustrated.
        out += if (backward) {
            bowedEdge(a, b, min(44.0, length * 0.28), tint, if (hot) 3.0 else 0.9)
        } else if (heads) {
            arrow(Vec2(a), Vec2(b), color = tint, thickness = if (hot) 3.0 else 1.0, headLength = 8.0, headWidth = 5.0)
        } else {
            fline {
                moveTo(a); lineTo(b)
                stroked = true; filled = false
                thickness = if (hot) 3.0 else 0.8
                color = tint
            }
        }
    }

    for (id in states.indices) {
        val centre = position[id]
        val dead = id in deadMarkings
        val onPath = id in pathNodes
        out += fline {
            circle(centre.x.toDouble(), centre.y.toDouble(), radius, 20)
            filled = true; stroked = false
            color = when {
                dead -> STATE_DEAD
                onPath -> LIVE
                id == 0 -> READY
                else -> STATE_NODE
            }
        }
        // The initial marking and every deadlock get a ring, so they are findable in a thicket of
        // otherwise identical dots even when the picture is shrunk into a docs page.
        if (dead || id == 0) {
            out += fline {
                circle(centre.x.toDouble(), centre.y.toDouble(), radius + 3.5, 24)
                filled = false; stroked = true
                thickness = 1.6
                color = if (dead) STATE_DEAD else READY
            }
        }
    }

    if (labels && highlight != null && pathEdges.isNotEmpty()) {
        for ((step, name) in highlight.transitions.withIndex()) {
            val a = position[pathNodes[step]]
            val b = position[pathNodes[step + 1]]
            val mid = vec((a.x + b.x) / 2.0, (a.y + b.y) / 2.0 - labelSize * 0.9)
            out += label(name, mid, labelSize, LIVE)
        }
        out += label("start", vec(position[0].x, position[0].y - radius - labelSize), labelSize, READY)
        pathNodes.lastOrNull()?.let { last ->
            if (last in deadMarkings) {
                out += label("stuck", vec(position[last].x, position[last].y - radius - labelSize), labelSize, STATE_DEAD)
            }
        }
    }

    return out.filter { it.nodes.isNotEmpty() }
}

/** The drawing as a standalone SVG string — for docs, for a file, for anywhere without a box. */
fun PetriStateGraph.toSvg(
    highlight: PetriTrace? = deadlockTrace(),
    w: Double = 1200.0,
    h: Double = 700.0,
    labels: Boolean = true,
    maxNodes: Int = PETRI_STATE_DRAW_LIMIT,
): String = drawToSVG(toFLines(highlight, w, h, labels, maxNodes = maxNodes), w, h)

/**
 * What gets drawn instead of the hairball: the counterexample in the top band, one bar per
 * breadth-first layer in the bottom band, and a caption saying that is what happened.
 *
 * Four decisions, and the first two are the ones that matter:
 *
 * - it is **two panels, not an overlay**. The first version drew the path straight over the layer
 *   bars, and that was wrong for exactly the reason the node bound exists: a line over grey is what
 *   the hairball already looked like, so the summary was indistinguishable from the thing it was
 *   replacing. Splitting them makes the picture visibly a *different kind* of picture, before any
 *   caption has been read.
 * - the **refusal is marked without words**. `label` returns nothing rather than throwing when no
 *   font resolves — a real case, and the case the docs pipeline and headless runs are in — so a
 *   caption alone would silently vanish in precisely the environments that generate figures. The
 *   dashed rule under the caption is the font-free signature: a broken line across the top of a
 *   figure reads as "cut", and it is always drawn.
 * - the **path is laid out from the trace**, not from the graph's own layered positions, which is
 *   what makes it legible: one step per column across the full width, so a twelve-firing
 *   counterexample gets a twelfth of the picture per firing instead of whatever the barycentre
 *   heuristic left over. It is a different drawing, not a zoom.
 * - the **layer bars are the only surviving statement about size**, drawn from a baseline so they
 *   read as a histogram. Height is population against the widest layer, so their shape is the shape
 *   of the state space's growth — the one thing the fan of dots was saying before it ran out of
 *   pixels.
 */
private fun PetriStateGraph.declinedDrawing(
    highlight: PetriTrace?,
    w: Double,
    h: Double,
    labels: Boolean,
    labelSize: Double,
    maxNodes: Int,
): List<FLine> {
    val out = mutableListOf<FLine>()
    val left = STATE_MARGIN
    val right = w - STATE_MARGIN
    val top = STATE_MARGIN * 0.55 + labelSize * 3.2
    val floor = h - STATE_MARGIN
    val pathY = top + (floor - top) * 0.20
    val barTop = top + (floor - top) * 0.38

    // The font-free mark: a dashed rule across the top, always. See the KDoc.
    val dashes = max(8, ((right - left) / 26.0).toInt())
    for (dash in 0 until dashes) {
        val a = left + (right - left) * dash / dashes
        val b = a + (right - left) / dashes * 0.55
        out += fline {
            moveTo(vec(a, top - labelSize * 0.8)); lineTo(vec(b, top - labelSize * 0.8))
            stroked = true; filled = false
            // 2.2 is not arbitrary: it is a weight nothing else in either drawing uses, so the
            // refusal rule is countable on its own. `PetriStateDrawingTest` counts on that.
            thickness = 2.2
            color = STATE_DEAD
        }
    }

    // The histogram: one bar per BFS layer, from a baseline, height proportional to population.
    val sizes = layerSizes()
    val widest = max(1, sizes.maxOrNull() ?: 1)
    val step = if (sizes.size > 1) (right - left) / (sizes.size - 1) else 0.0
    val barHalf = min(16.0, max(1.5, step * 0.34))
    out += fline {
        moveTo(vec(left, floor)); lineTo(vec(right, floor))
        stroked = true; filled = false
        thickness = 1.0
        color = STATE_EDGE
    }
    for ((layer, count) in sizes.withIndex()) {
        if (count == 0) continue
        val x = left + layer * step
        // A floor of two pixels, so a layer of one marking against a layer of seven hundred is a
        // visible nub rather than nothing at all — "no bar" and "one marking" must not look alike.
        val head = floor - max(2.0, (floor - barTop) * count.toDouble() / widest)
        out += fline {
            moveTo(vec(x - barHalf, head))
            lineTo(vec(x + barHalf, head))
            lineTo(vec(x + barHalf, floor))
            lineTo(vec(x - barHalf, floor))
            lineTo(vec(x - barHalf, head))
            filled = true; stroked = false
            color = STATE_BACK
        }
    }
    // The widest layer gets its number, because "how wide does this get" is the question the bars
    // are answering and a shape without a scale only half answers it.
    if (labels) sizes.withIndex().maxByOrNull { it.value }?.let { (layer, count) ->
        out += label(
            "$count markings at depth $layer",
            vec(left + layer * step, barTop - labelSize),
            labelSize * 0.85,
            STATE_NODE,
        )
    }

    // The counterexample, laid out on its own terms, in its own band.
    val radius = 9.0
    if (highlight != null && highlight.length > 0) {
        val columns = highlight.markings.size
        val across = if (columns > 1) (right - left) / (columns - 1) else 0.0
        fun at(index: Int) = vec(left + index * across, pathY)
        val wedged = canDeadlock == true
        for (index in 0 until columns - 1) {
            val a = at(index)
            val b = at(index + 1)
            val length = (b.x - a.x).toDouble()
            if (length <= radius * 2.2) continue
            out += arrow(
                Vec2(vec(a.x + radius, a.y)),
                Vec2(vec(b.x - radius - 2.0, b.y)),
                color = LIVE, thickness = 3.0, headLength = 9.0, headWidth = 6.0,
            )
        }
        for (index in 0 until columns) {
            val centre = at(index)
            val last = index == columns - 1
            out += fline {
                circle(centre.x.toDouble(), centre.y.toDouble(), radius, 24)
                filled = true; stroked = false
                color = if (last && wedged) STATE_DEAD else if (index == 0) READY else LIVE
            }
            if (index == 0 || last) out += fline {
                circle(centre.x.toDouble(), centre.y.toDouble(), radius + 4.0, 28)
                filled = false; stroked = true
                thickness = 1.8
                color = if (last && wedged) STATE_DEAD else READY
            }
        }
        if (labels) {
            for ((index, name) in highlight.transitions.withIndex()) {
                val a = at(index)
                val b = at(index + 1)
                out += label(name, vec((a.x + b.x) / 2.0, pathY - labelSize * 1.5), labelSize, LIVE)
            }
            out += label("start", vec(at(0).x, pathY + radius + labelSize * 1.4), labelSize, READY)
            out += label(
                if (wedged) "stuck" else "end",
                vec(at(columns - 1).x, pathY + radius + labelSize * 1.4),
                labelSize,
                if (wedged) STATE_DEAD else READY,
            )
        }
    }

    // The caption, drawn whatever `labels` says: it is about the *figure*, not about the transition
    // names, and a summary that does not admit to being one is the bug this path exists to fix.
    val what = if (highlight == null || highlight.length == 0) {
        "no counterexample to draw — the bars below are the population of each layer"
    } else {
        "the ${highlight.length}-firing counterexample above, per-layer population below"
    }
    out += label(
        "$stateCount markings, $edgeCount firings — too dense to draw (limit $maxNodes)",
        vec(w / 2.0, STATE_MARGIN * 0.5),
        labelSize * 1.1,
        STATE_DEAD,
    )
    out += label(what, vec(w / 2.0, STATE_MARGIN * 0.5 + labelSize * 1.7), labelSize * 0.95, STATE_NODE)

    return out.filter { it.nodes.isNotEmpty() }
}

/**
 * How big a dot can be before the widest layer collides with itself.
 *
 * Derived from the layout rather than fixed, because the same function draws a five-state buffer
 * and an eighty-state dining table and neither should be told what the other needed.
 */
private fun PetriStateGraph.nodeRadius(w: Double, h: Double): Double {
    val widest = layerSizes().maxOrNull() ?: 1
    val deepest = max(1, diameterFound)
    val vertical = (h - 2.0 * STATE_MARGIN) / max(1, widest)
    val horizontal = (w - 2.0 * STATE_MARGIN) / deepest
    return min(11.0, max(2.0, min(vertical, horizontal) * 0.28))
}

private fun PetriStateGraph.layerSizes(): List<Int> {
    val counts = IntArray(diameterFound + 1)
    for (id in states.indices) counts[depth(id)]++
    return counts.toList()
}

private const val STATE_MARGIN = 46.0

/** How many barycentre sweeps to run. Eight is where the crossing count stops moving on the table. */
private const val STATE_ORDER_SWEEPS = 8

/**
 * BFS depth across, and within each layer an ordering chosen to keep the edges from crossing.
 *
 * The ordering is the classical barycentre heuristic: sweep down the layers putting each node at
 * the average position of its predecessors, then sweep up doing the same with its successors, and
 * repeat. It is not optimal and does not need to be — [PetriLayoutSolver] exists for the small
 * exact case, and a state graph is the large approximate one. Without any ordering at all the
 * picture is genuinely a hairball; with this it is a fan, which is what a state space looks like.
 */
private fun PetriStateGraph.layeredPositions(w: Double, h: Double): Array<Vec2> {
    val count = stateCount
    val depths = IntArray(count) { depth(it) }
    val layers = Array(depths.maxOrNull()!! + 1) { mutableListOf<Int>() }
    for (id in 0 until count) layers[depths[id]] += id
    val slot = IntArray(count)
    for (layer in layers) layer.forEachIndexed { position, id -> slot[id] = position }

    val before = Array(count) { mutableListOf<Int>() }
    val after = Array(count) { mutableListOf<Int>() }
    for (edge in edges) {
        if (edge.from == edge.to || depths[edge.from] == depths[edge.to]) continue
        before[edge.to] += edge.from
        after[edge.from] += edge.to
    }

    repeat(STATE_ORDER_SWEEPS) { pass ->
        val downward = pass % 2 == 0
        val neighbours = if (downward) before else after
        val order = if (downward) layers.indices.toList() else layers.indices.reversed().toList()
        for (level in order) {
            val sorted = layers[level].sortedBy { id ->
                val seen = neighbours[id]
                if (seen.isEmpty()) slot[id].toDouble() else seen.sumOf { slot[it].toDouble() } / seen.size
            }
            layers[level].clear()
            layers[level] += sorted
            sorted.forEachIndexed { position, id -> slot[id] = position }
        }
    }

    val widest = layers.maxOf { it.size }
    val across = if (layers.size > 1) (w - 2.0 * STATE_MARGIN) / (layers.size - 1) else 0.0
    val down = if (widest > 1) (h - 2.0 * STATE_MARGIN) / (widest - 1) else 0.0
    val out = arrayOfNulls<Vec2>(count)
    for ((level, layer) in layers.withIndex()) {
        layer.forEachIndexed { position, id ->
            out[id] = vec(
                STATE_MARGIN + level * across,
                h / 2.0 + (position - (layer.size - 1) / 2.0) * down,
            )
        }
    }
    @Suppress("UNCHECKED_CAST")
    return out as Array<Vec2>
}

/** How many segments a bowed edge is flattened into. Twelve is smooth at any size these are drawn. */
private const val STATE_BOW_STEPS = 12

/** One quadratic bow from [a] to [b], bulging [bow] pixels to the left of the direction of travel. */
private fun bowedEdge(a: Vec2, b: Vec2, bow: Double, tint: afield.stdlib.Vec4, weight: Double): FLine {
    val ax = a.x.toDouble()
    val ay = a.y.toDouble()
    val bx = b.x.toDouble()
    val by = b.y.toDouble()
    val length = hypot(bx - ax, by - ay).coerceAtLeast(1e-6)
    val controlX = (ax + bx) / 2.0 - (by - ay) / length * bow
    val controlY = (ay + by) / 2.0 + (bx - ax) / length * bow
    return fline {
        moveTo(a)
        for (step in 1..STATE_BOW_STEPS) {
            val t = step.toDouble() / STATE_BOW_STEPS
            val u = 1.0 - t
            lineTo(
                vec(
                    u * u * ax + 2.0 * u * t * controlX + t * t * bx,
                    u * u * ay + 2.0 * u * t * controlY + t * t * by,
                )
            )
        }
        stroked = true; filled = false
        thickness = weight
        color = tint
    }
}

/** A self-loop: a small ring perched above the node, because a zero-length arrow draws nothing. */
private fun selfLoop(centre: Vec2, radius: Double, hot: Boolean): List<FLine> = listOf(
    fline {
        circle(centre.x.toDouble(), centre.y.toDouble() - radius * 1.6, radius * 0.9, 16)
        filled = false; stroked = true
        thickness = if (hot) 2.4 else 0.9
        color = if (hot) LIVE else STATE_EDGE
    }
)

/**
 * Draws the state space into a box, with the classical properties as a card beside it.
 *
 *     val graph = net.stateSpace()
 *     showPetriStateSpace(graph)
 */
fun Box.showPetriStateSpace(
    graph: PetriStateGraph,
    highlight: PetriTrace? = graph.deadlockTrace(),
    channel: String = "statespace",
    summaryChannel: String? = "$channel-summary",
    w: Double = 1200.0,
    h: Double = 700.0,
    labels: Boolean = true,
    maxNodes: Int = PETRI_STATE_DRAW_LIMIT,
): List<FLine> {
    val rendered = graph.toFLines(highlight, w, h, labels, maxNodes = maxNodes)
    lines(rendered, channel = channel, w = w, h = h, fitToSize = true)
    summaryChannel?.let { display(it, graph.summaryHtml(highlight, maxNodes)) }
    return rendered
}

/** [showPetriStateSpace] for a net, searched at the default budget. */
fun Box.showPetriStateSpace(
    net: PetriNet,
    channel: String = "statespace",
    summaryChannel: String? = "$channel-summary",
    w: Double = 1200.0,
    h: Double = 700.0,
    budget: Int = PETRI_STATE_BUDGET,
    maxNodes: Int = PETRI_STATE_DRAW_LIMIT,
): List<FLine> = showPetriStateSpace(
    net.stateSpace(budget),
    channel = channel,
    summaryChannel = summaryChannel,
    w = w,
    h = h,
    maxNodes = maxNodes,
)

/**
 * The card beside the picture: how big the space is, what the search proved, and — when there is
 * one — the counterexample written out as a firing sequence.
 */
fun PetriStateGraph.summaryHtml(
    highlight: PetriTrace? = deadlockTrace(),
    maxNodes: Int = PETRI_STATE_DRAW_LIMIT,
): String {
    fun chip(text: String, tint: String) =
        """<span style="display:inline-block;margin:2px 4px 2px 0;padding:2px 7px;border-radius:9px;background:$tint;color:#eee;font-size:11px">${escape(text)}</span>"""

    val verdicts = buildList {
        add(
            when (canDeadlock) {
                true -> "can deadlock" to "#5a1010"
                false -> "deadlock-free" to "#123a1c"
                null -> "deadlock unknown" to "#4a3a10"
            }
        )
        isSafe?.let { add((if (it) "safe (1-bounded)" else "bounded") to "#1b3444") }
        isLive?.let { add((if (it) "live" else "not live") to if (it) "#123a1c" else "#4a2010") }
        isReversible?.let { add((if (it) "reversible" else "not reversible") to "#242424") }
        if (!complete) add("BUDGET EXHAUSTED — every 'no' above is 'unknown'" to "#5a1010")
        // The card and the picture have to agree about what the picture is. A reader who takes the
        // summarised figure for a drawing of the space has been misled by the pair of them, not by
        // one of them.
        if (tooDenseToDraw(maxNodes)) add("drawing declined above $maxNodes markings — path only" to "#4a3a10")
    }.joinToString("") { (text, tint) -> chip(text, tint) }

    val counterexample = highlight?.let { trace ->
        """
        <div style="margin-top:8px;color:#8a8a8a;font-size:11px">shortest counterexample — ${trace.length} firings</div>
        <div style="color:#e2a06a;font-size:12px;word-break:break-word">${escape(trace.toString())}</div>
        <div style="color:#8a8a8a;font-size:11px;margin-top:3px">ends at ${escape(trace.end.toString())}</div>
        """.trimIndent()
    } ?: ""

    return """
        <div style="font-family:Iosevka Web, IBM Plex Mono, monospace;background:#0a0a0a;color:#d8d8d8;border:1px solid #2c2c2c;padding:10px 12px;max-width:1200px">
          <div style="display:flex;justify-content:space-between;align-items:baseline">
            <strong style="font-size:13px">state space</strong>
            <span style="color:#8a8a8a;font-size:11px">$stateCount markings · $edgeCount firings · depth $diameterFound</span>
          </div>
          <div style="margin-top:7px">$verdicts</div>
          $counterexample
        </div>
    """.trimIndent()
}
