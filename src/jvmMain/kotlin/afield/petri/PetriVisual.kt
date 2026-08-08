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
    /** Positions you would rather set yourself, by place or transition name. */
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

/** Everything about a net that can move a node, and nothing that cannot. */
private fun petriLayoutKey(net: PetriNet, layout: PetriLayout, radius: Double): String = buildString {
    append(layout).append('|').append(radius).append('|')
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
    labelLimit: Int = 28,
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
        emptyList()
    }
}

@Volatile
private var labelFaceFailed = false

private fun petriLabelTypeface(size: Double): afield.stdlib.Typeface? {
    if (labelFaceFailed) return null
    return try {
        typeface(Font.MONOSPACED, size)
    } catch (ex: Throwable) {
        labelFaceFailed = true
        null
    }
}

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
