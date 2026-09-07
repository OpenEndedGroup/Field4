@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Visualization")

package afield.stdlib

import afield.Box
import afield.Rect
import afield.graphics.FLine
import afield.graphics.drawToSVG
import afield.graphics.lines
import afield.stdlib.linalg.minus
import afield.stdlib.linalg.plus
import java.util.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import afield.Table
import afield.CellRole
import afield.Align

/**
 * Interaction nets as a live-codeable medium: a graph whose nodes rewrite each other wherever two of them
 * touch principal port to principal port, with a DSL for building nets, a rule language for the rewrites, a
 * scheduler that decides which collision fires next, and a force-directed layout so you can watch it happen.
 *
 * Start at [net] to build one, [rules] to say how it rewrites, [Reducer] to run it, and [Net.toFLines] or
 * [Box.showInteractionNet] to look at it. [Lambda] is a ready-made vocabulary (APP, LAM, DUP, ERASE, ROOT,
 * VAR, CONST) with its own rule book, if you want the classical behaviour rather than your own.
 *
 * A compact, single-file interaction-net playground meant to be easy to lift into a larger codebase,
 * but also meant to be a fertile computational substrate for live coding art.
 *
 * What is in here:
 * - a small in-memory interaction-net graph model
 * - a Kotlin DSL for building whole nets and reusable graph fragments
 * - two rewrite styles:
 *     1) a declarative "automatic rewiring" DSL built around ext(side, aux)
 *     2) an escape hatch for arbitrary custom rewrites in ordinary Kotlin
 * - configurable active-pair scheduling
 * - a simple force-directed layout with incremental seeding of rewrite RHS nodes at the LHS center
 * - FLine/output rendering plus SVG export for quick inspection
 * - a small lambda-oriented bundle including APP, LAM, DUP, ERASE, ROOT, VAR, CONST
 *
 * Design intent:
 * - Keep the core tiny and hackable.
 * - Make the common case pleasant without removing low-level control.
 * - Be explicit about where the model is still incomplete.
 * - Treat the system less like a theorem prover and more like a graph chemistry / reactive medium.
 *
 * The artistic framing:
 * This file is not most interesting as a route to "real lambda calculus". It is more interesting as a
 * local, spatial, rewiring-based substrate for live coding. In that framing:
 *
 * - principal ports are not just logical redex triggers; they are collision points, activation sites,
 *   rhythmic contact surfaces
 * - auxiliary ports are not just variables; they can be treated as tension channels, color buses,
 *   growth directions, continuation hooks, memory strands, or signal paths
 * - rewrite rules are not merely reductions; they are local laws of motion, branching, fusion,
 *   mutation, decay, reflection, gating, or pulse propagation
 * - active-pair scheduling is not merely evaluation order; it is rhythm, phrasing, emphasis, groove,
 *   cascade control
 * - layout is not merely display; it is motion memory and can become part of the meaning of the system
 *
 * DUP and ERASE in this context:
 * DUP and ERASE are useful here less as strict lambda-calculus machinery and more as artistic growth and
 * decay primitives.
 *
 * - DUP can mean branching, echo, motif propagation, splitting a line of force into two descendants,
 *   or generating two related but diverging futures
 * - ERASE can mean fade, pruning, absorption, dissipation, culling, or a sink that removes pressure from
 *   a local region
 *
 * In a more expressive live system, DUP does not need to be perfectly literal duplication. It can create
 * two descendants with slight positional offsets, inherited state, hue drift, phase drift, timing skew,
 * energy split, or generation markers. Likewise ERASE does not need to be instantaneous deletion; it can
 * become gradual attenuation, shrinkage, or multi-frame disappearance.
 *
 * Why this is promising for live coding art:
 * - Local causality: every visible change comes from a small neighborhood interaction.
 * - Spatial meaning: the graph is data and choreography at once.
 * - Compositionality: a small table of rewrite laws can yield rich large-scale behavior.
 * - Performability: rules, scheduler, layout constants, render mapping, and node injection can all be
 *   changed while the system is running.
 *
 * Activation order as an instrument:
 * Reduction order is a first-class artistic control. A deterministic scheduler can feel mechanical;
 * randomized scheduling can shimmer; energy-biased or cursor-biased scheduling can create bursts,
 * avalanches, attraction, or spotlighting. Treat scheduling as part of the score.
 *
 * Layout as part of computation:
 * The incremental layout already gives new RHS nodes a positional memory by seeding them at the centroid
 * of the consumed active pair before relaxation. That idea can be pushed further. Rewrite rules can read
 * or influence geometry; branch direction can depend on current edge angle; replication distance can
 * depend on energy; eligibility can depend on local spatial conditions; layout velocity can feed rendering
 * or sound. The graph need not be computed first and visualized second.
 *
 * A "wet" semantics is often better than a perfectly dry one:
 * For artistic purposes, clean instantaneous replacement is sometimes less interesting than sticky,
 * physicalized replacement. Newly created nodes can inherit centroid and velocity. Consumed edges can
 * leave ghosts. Erased nodes can decay over a few frames. Duplicated nodes can repel strongly at birth
 * and then settle. Rules can emit transient sparks or decorative residues. These choices improve visual
 * legibility and expressiveness during rapid change.
 *
 * The next high-leverage extension is per-node state:
 * Adding a small mutable state record to each node would make the substrate much richer. Examples:
 *
 *     data class State(
 *         var energy: Double = 1.0,
 *         var phase: Double = 0.0,
 *         var hue: Double = 0.0,
 *         var age: Double = 0.0
 *     )
 *
 * Then rewrite rules can split energy on DUP, accumulate age, drift hue, mutate phase, and directly map
 * these quantities to radius, stroke weight, opacity, curvature, synthesis parameters, or triggering.
 *
 * Rendering and sound hooks:
 * A rewrite can be more than a topology update. In a live system it is natural for a rewrite to also emit
 * events such as "flash this edge", "trigger a note", "spawn a particle", "increase camera pull", or
 * "mark this component active". That turns graph rewriting into the event engine for an audiovisual system.
 *
 * Useful node archetypes for an artistic palette:
 * - Pulse   : propagates motion or activation
 * - DUP     : bifurcates structure or motive
 * - ERASE   : consumes / attenuates / prunes
 * - Delay   : holds activity for N frames or beats
 * - Gate    : conditionally passes or blocks interaction
 * - Mirror  : swaps neighborhoods / reflects flow
 * - Fuse    : merges strands
 * - Noise   : injects stochastic variation
 * - Anchor  : spatial stabilizer
 * - Bloom   : expands into decorative local structure
 *
 * A note on rigor:
 * This file adds DUP and ERASE agents in the usual explicit-resource spirit, but it is not trying to be a
 * complete optimal reducer. There is no full fan/fan commuting story, no full lambda frontend, and no
 * claim of theoretical completeness. That is deliberate. The value here is as a hackable medium with:
 *
 * - topology that changes
 * - space that remembers
 * - order that matters
 * - small laws that create style
 *
 * In other words: treat this as a graph chemistry for live coding.
 */


/**
 * The knobs on the force-directed layout that [Net.relax] runs — every node pushing every other apart, every
 * edge pulling its two ends together.
 *
 * The defaults are tuned for the node radii this file draws, so start by changing one thing: [springLength] is
 * the distance an edge is happy at and is the quickest way to make the whole picture bigger or smaller;
 * [repulsion] decides how much empty space sits between unconnected nodes; [damping] below 1 is what makes the
 * simulation settle at all, and pushing it toward 1 makes the net wobble for much longer.
 *
 * [jitter] is stored but unused by the current layout step — nodes get their initial scatter from [Net.create]
 * instead. [clampStep] is a per-step speed limit in the same units as position, which is what stops a
 * near-coincident pair from firing off the canvas.
 */
data class LayoutConfig(
    /** How hard every node pushes every other away. Falls off as `1/d²`, so it sets the empty space between
     * unconnected nodes without much affecting connected ones. */
    val repulsion: Double = 2500.0,
    /** The distance an edge is happy at, in position units. The quickest single knob for the overall scale of
     * the picture. */
    val springLength: Double = 90.0,
    /** How strongly an edge pulls back toward [springLength]. Large values make the net rigid and jittery. */
    val springStrength: Double = 0.015,
    /** Velocity retained per step. Below 1 is what makes the simulation settle at all; toward 1 it wobbles for
     * far longer, and at 1 it never stops. */
    val damping: Double = 0.82,
    /** Integration step. Scales both the velocity update and the movement, so raising it is a faster but less
     * stable simulation. */
    val timeStep: Double = 1.0,
    /** Stored but **unused** by the current layout step — new nodes get their initial scatter from [Net.create]
     * instead. */
    val jitter: Double = 8.0,
    /** Per-step speed limit, in position units. What stops two nearly coincident nodes from firing each other
     * off the canvas. */
    val clampStep: Double = 20.0
)

internal data class Motion(var velocity: Vec2 = Vec2())

/**
 * The mutable per-node payload that turns a bare interaction net into something worth watching: four named
 * quantities the renderer already reads, plus [scalars] for anything else you want to carry.
 *
 * [energy] (clamped at or above 0 by every operation here) drives node radius and is what the energy-biased
 * schedulers in [PairSelectors] compare; [hue] drives colour and is a 0..1 turn, wrapped at render time rather
 * than here, so it may drift far outside that range; [age] is bumped by [Net.incrementAges] in whatever unit
 * you pass it; [phase] is carried and split and read by nothing here except the renderer's fallback port
 * angle for a node with no connected neighbours, so it is otherwise yours.
 *
 * Mutable and **shared by reference** through a rewrite unless you copy it — [copyDeep], [evolved] and [split]
 * are the three ways to get a fresh one, and [Net.create] copies whatever state you hand it.
 */
data class NodeState(
    /** Drives node radius when drawn, and is what the energy-biased [PairSelectors] rank pairs by. Every
     * operation here floors it at 0, but nothing caps it. */
    var energy: Double = 1.0,
    /** Carried and split, but **nothing in this file interprets it** — yours to use, though the renderer does
     * fall back to it for a node's port angle when the node has no connected neighbours. */
    var phase: Double = 0.0,
    /** Drives colour, as a fraction of a turn. Wrapped at render time rather than here, so it may drift far
     * outside `0..1` and that is fine. */
    var hue: Double = 0.0,
    /** Advanced only by [Net.incrementAges], in whatever unit you pass it — so age runs on the clock you choose.
     * Nudges drawn lightness. */
    var age: Double = 0.0,
    /** Room for your own named quantities. Deep-copied by [copyDeep] and blended key-wise by [blend], where a
     * key missing from one side counts as 0. */
    val scalars: MutableMap<String, Double> = linkedMapOf()
) {
    /** An independent copy, [scalars] map included, so later mutation of either cannot reach the other. */
    fun copyDeep(): NodeState = NodeState(
        energy = energy,
        phase = phase,
        hue = hue,
        age = age,
        scalars = LinkedHashMap(scalars)
    )

    /**
     * A copy with the four quantities moved on — the "one node becomes one node, a little different" step for a
     * rewrite rule to hand its replacement.
     *
     * Energy is scaled then biased and floored at 0; phase, hue and age are added to. All defaults are
     * no-change, so name only what you mean to alter. [scalars] comes across untouched.
     */
    fun evolved(
        energyScale: Double = 1.0,
        energyBias: Double = 0.0,
        phaseShift: Double = 0.0,
        hueShift: Double = 0.0,
        ageDelta: Double = 0.0
    ): NodeState = copyDeep().apply {
        energy = (energy * energyScale + energyBias).coerceAtLeast(0.0)
        phase += phaseShift
        hue += hueShift
        age += ageDelta
    }

    /**
     * One branch's share of this state when a node divides into [parts] — the state side of a DUP-style rewrite.
     *
     * Energy is divided by [parts] and then scaled by [attenuation], so the branches conserve it (or lose a
     * little) rather than each inheriting the whole. Hue and phase are spread **symmetrically about
     * the original**: branch index is re-centred, so with `parts = 2` the two children go `-0.5` and `+0.5` of
     * the spread and their average is where the parent was.
     *
     * @throws IllegalArgumentException if [parts] is not positive, or [branch] is outside `0 until parts`
     */
    fun split(
        parts: Int,
        branch: Int,
        attenuation: Double = 1.0,
        hueSpread: Double = 0.0,
        phaseSpread: Double = 0.0,
        ageDelta: Double = 0.0
    ): NodeState {
        require(parts > 0) { "parts must be > 0" }
        require(branch in 0 until parts) { "branch $branch out of range for $parts parts" }
        val centeredIndex = branch - (parts - 1) / 2.0
        return copyDeep().apply {
            energy = (energy / parts * attenuation).coerceAtLeast(0.0)
            hue += centeredIndex * hueSpread
            phase += centeredIndex * phaseSpread
            age += ageDelta
        }
    }

    companion object {
        /**
         * A weighted average of two states — the state side of a fuse-style rewrite, where two nodes become one.
         *
         * [weightToFirst] is clamped to `0..1` and is the weight on [a], so 0.5 is the midpoint and 1.0 keeps [a]
         * exactly. Every scalar key present in either map appears in the result, with a **missing key treated as
         * 0** rather than as "the other one's value" — so blending a state that lacks a key pulls that key
         * toward zero.
         */
        fun blend(a: NodeState, b: NodeState, weightToFirst: Double = 0.5): NodeState {
            val t = weightToFirst.coerceIn(0.0, 1.0)
            val merged = linkedMapOf<String, Double>()
            (a.scalars.keys + b.scalars.keys).distinct().forEach { key ->
                val av = a.scalars[key] ?: 0.0
                val bv = b.scalars[key] ?: 0.0
                merged[key] = av * t + bv * (1.0 - t)
            }
            return NodeState(
                energy = a.energy * t + b.energy * (1.0 - t),
                phase = a.phase * t + b.phase * (1.0 - t),
                hue = a.hue * t + b.hue * (1.0 - t),
                age = a.age * t + b.age * (1.0 - t),
                scalars = merged
            )
        }
    }
}

// =========================
// Net data model
// =========================

/**
 * Which of a node's two kinds of port this is — and therefore whether an edge through it can *react*.
 *
 * `PRINCIPAL` is the one reactive port every node has exactly one of: two principal ports wired to each other is
 * an [ActivePair], the only thing a rewrite rule ever fires on. `AUXILIARY` ports are inert wiring — they carry
 * structure between reactions and are what a rule reconnects.
 */
enum class PortKind { PRINCIPAL, AUXILIARY }

/**
 * A kind of node: a name to match rules against, and how many auxiliary ports its instances get.
 *
 * The vocabulary of your net. Build them with [Types.agent] and keep them in a `val` — rules are matched by
 * [PairSignature] on these type objects, and equality here is by name and port count, so two separately created
 * `agent("DUP", 2)` values do match each other.
 */
data class AgentType(val name: String, val auxPorts: Int) {
    /** Total port count: the auxiliaries plus the one principal port. */
    val arity: Int get() = auxPorts + 1
}

/**
 * One port on one node — the thing edges connect. Port 0 is always the principal port and `1..auxPorts` the
 * auxiliaries, which is why [Node.aux] takes a 0-based index and adds one for you.
 *
 * Created with its node and never separately, hence the internal constructor. Identity is by *instance*: a port
 * is only equal to itself, so it can be used as a map key straightforwardly, and [id] is the stable string form
 * (`"node_3:1"`) that [Net] indexes on.
 */
class Port internal constructor(
    /** The node this port belongs to. A port never moves between nodes. */
    val node: Node,
    /** Position in the node's `ports` list: 0 is the principal port, `1..auxPorts` the auxiliaries. Note this is
     * the raw index, one more than the number [Node.aux] takes. */
    val index: Int,
    /** Whether this is the one reactive port or an inert auxiliary. Fixed when the node is created. */
    val kind: PortKind
) {
    /** `"<nodeId>:<portIndex>"` — the stable string identity [Net] uses for its adjacency index. */
    val id: String get() = "${node.id}:$index"

    /** The port's [id]. */
    override fun toString(): String = id
}

/**
 * One node in a net: an [AgentType], a stable [id], a fixed list of [ports] and a mutable [NodeState].
 *
 * Make them through [Net.create] or the builder DSLs rather than directly — a node has to be registered with a
 * net to have a position or to be connectable, which is what the internal constructor is enforcing. The ports
 * are built once and never change, since the port count is a property of the type.
 */
class Node internal constructor(
    /** Unique within its net and never reused, so a [ReductionStep]'s removed ids stay unambiguous after the
     * fact. Minted as `type_n` by [IdSource] unless you named it. */
    val id: String,
    /** Which kind of agent this is — what rules match on, and what fixes the port count. */
    val type: AgentType,
    /** This node's mutable payload. [Net.create] copies whatever you pass, so two nodes never share one by
     * accident; a rewrite rule must copy it explicitly. */
    val state: NodeState = NodeState()
) {
    /** All of this node's ports, principal first at index 0, then the auxiliaries in order. */
    val ports: List<Port> = buildList {
        add(Port(this@Node, 0, PortKind.PRINCIPAL))
        repeat(type.auxPorts) { i ->
            add(Port(this@Node, i + 1, PortKind.AUXILIARY))
        }
    }

    /** This node's one reactive port. Wire two of these together and you have an [ActivePair]. */
    val principal: Port get() = ports[0]

    /**
     * Auxiliary port [i], numbered from **0** — so `aux(0)` is `ports[1]`.
     *
     * @throws IllegalArgumentException if [i] is outside `0 until type.auxPorts`, naming the type in the message
     */
    fun aux(i: Int): Port {
        require(i in 0 until type.auxPorts) { "Aux port $i out of range for ${type.name}" }
        return ports[i + 1]
    }

    /** `"<id>:<typeName>"`, e.g. `"dup_4:DUP"`. */
    override fun toString(): String = "$id:${type.name}"
}

/**
 * An undirected wire between two ports. A port holds at most one edge, so this is a matching, not a graph with
 * multiplicity.
 *
 * `Edge(a, b)` and `Edge(b, a)` are **different values** by `equals` even though they mean the same wire — [Net]
 * keeps one canonical instance per wire in its own index, so compare through [Net] rather than by constructing
 * edges to look them up.
 */
data class Edge(val a: Port, val b: Port) {
    /**
     * The port at the far end from [p] — how you walk from a node to its neighbour.
     *
     * @throws IllegalStateException if [p] is not one of this edge's two ports
     */
    fun other(p: Port): Port = when (p) {
        a -> b
        b -> a
        else -> error("Port $p not on edge $this")
    }
}

/**
 * Two nodes whose principal ports are wired to each other — a redex, the only place a rewrite can happen.
 *
 * `left` and `right` are in no meaningful order; which is which depends on how the edge happened to be stored,
 * which is exactly why rule matching goes through [PairSignature.bind] and reports whether it had to swap them.
 * Use [canonicalKey] whenever you need to compare or de-duplicate pairs.
 *
 * @throws IllegalArgumentException if both nodes are the same node
 */
data class ActivePair(val left: Node, val right: Node) {
    init {
        require(left != right)
    }

    /** The two node ids sorted, so the same pair always keys the same way whichever side it arrived on. */
    fun canonicalKey(): Pair<String, String> =
        if (left.id <= right.id) left.id to right.id else right.id to left.id
}

/**
 * The net itself: nodes, the wires between their ports, and — because this is meant to be watched — a position
 * and velocity per node.
 *
 * Build one with [net] rather than directly. Everything here mutates in place: [create] and [remove] change the
 * population, [connect] and [disconnect] the wiring, [relax] the geometry, and a [Reducer] does all of those on
 * your behalf. Insertion order is preserved throughout (`LinkedHashMap` everywhere), so a run is reproducible
 * apart from the random initial scatter [create] gives new nodes and any randomised scheduler you choose.
 *
 * The invariant the connect/disconnect methods enforce is that **a port holds at most one edge**; most of the
 * `require`s in this class are that rule being stated in different ways.
 */
class Net internal constructor(
    internal val ids: IdSource = IdSource()
) {
    private val _nodes = LinkedHashMap<String, Node>()
    private val _edges = LinkedHashSet<Edge>()
    private val adjacency = LinkedHashMap<String, Edge>()
    private val positions = LinkedHashMap<String, Vec2>()
    private val motions = LinkedHashMap<String, Motion>()

    /** Every node, in creation order. A **live view** of the net's own map — do not iterate it while rewriting. */
    val nodes: Collection<Node> get() = _nodes.values

    /** Every wire, in creation order. A live view, so a rewrite mid-iteration will upset it. */
    val edges: Collection<Edge> get() = _edges

    /**
     * The node with this id.
     *
     * @throws IllegalStateException if no such node is in the net — including one that a rewrite has since removed
     */
    fun node(id: String): Node = _nodes[id] ?: error("Unknown node: $id")

    /** Where the node currently sits. `(0, 0)` for a node this net has never seen, rather than an exception. */
    fun positionOf(node: Node): Vec2 = positions[node.id] ?: Vec2()

    /** The node's current layout velocity — `(0, 0)` if it has none yet. Carried across rewrites where a rule asks. */
    fun velocityOf(node: Node): Vec2 = motions[node.id]?.velocity ?: Vec2()

    /**
     * Places the node. Stores the vector **by reference**, so mutating the [Vec2] you passed moves the node
     * afterwards — pass `Vec2(p)` if that would surprise you.
     */
    fun setPosition(node: Node, p: Vec2) {
        positions[node.id] = p
    }

    /** Sets the node's layout velocity, copying the vector. A kick here survives into the next [relax]. */
    fun setVelocity(node: Node, velocity: Vec2) {
        motions.getOrPut(node.id) { Motion() }.velocity = Vec2(velocity)
    }

    /**
     * Adds a node of [type] to the net and returns it, with no wires attached.
     *
     * The id defaults to a fresh `type_n` from the net's own counter, which is what keeps ids unique and readable;
     * pass one only when you want to refer to the node by name later. The [state] is **copied**, so the same
     * `NodeState` can seed many nodes without them sharing it.
     *
     * New nodes land at a random point within ±30 of the origin, so an unrelaxed net looks like a scatter rather
     * than a pile — [setPosition] straight after if you want it somewhere specific.
     *
     * @throws IllegalArgumentException if [id] is already in the net
     */
    fun create(type: AgentType, id: String = ids.next(type.name.lowercase()), state: NodeState = NodeState()): Node {
        require(id !in _nodes) { "Duplicate node id: $id" }
        val node = Node(id, type, state.copyDeep())
        _nodes[id] = node
        positions[id] = positions[id] ?: vec(Random.nextDouble(-30.0, 30.0), Random.nextDouble(-30.0, 30.0))
        motions[id] = motions[id] ?: Motion()
        return node
    }

    /**
     * Deletes the node, disconnecting every wire it had first, and forgets its position and velocity.
     *
     * The neighbours are left with **free ports**, not reconnected — a rewrite rule that wants the structure to
     * survive has to say what the dangling ends attach to (see `RewriteBuilder.passthrough`). Removing a node that
     * is not in the net is a no-op.
     */
    fun remove(node: Node) {
        val incident = node.ports.mapNotNull { adjacency[it.id] }.distinct()
        incident.forEach { disconnect(it.a, it.b) }
        _nodes.remove(node.id)
        positions.remove(node.id)
        motions.remove(node.id)
    }

    /** The port on the other end of this port's wire, or null if it is free. The one-step graph walk. */
    fun connected(port: Port): Port? = adjacency[port.id]?.other(port)

    /**
     * Wires two ports together.
     *
     * Strict — it will not silently rewire — and each `require` catches a different mistake:
     *
     * @throws IllegalArgumentException if the two ports are the same port, if either node is not in this net, or
     * if **either port is already connected**. [disconnect] first when you mean to move a wire.
     */
    fun connect(a: Port, b: Port) {
        require(a != b) { "Cannot connect port to itself: $a" }
        require(a.node.id in _nodes) { "Node ${a.node.id} not in net" }
        require(b.node.id in _nodes) { "Node ${b.node.id} not in net" }
        require(adjacency[a.id] == null) { "Port already connected: $a" }
        require(adjacency[b.id] == null) { "Port already connected: $b" }
        val edge = Edge(a, b)
        _edges += edge
        adjacency[a.id] = edge
        adjacency[b.id] = edge
    }

    /**
     * Removes the wire between two ports, leaving both free.
     *
     * A no-op if [a] has no wire at all — but **not** forgiving if it has a wire to somewhere else, which is the
     * asymmetry worth knowing:
     *
     * @throws IllegalArgumentException if [a] is connected to something other than [b]
     */
    fun disconnect(a: Port, b: Port) {
        val edge = adjacency[a.id] ?: return
        require(edge.other(a) == b) { "Ports $a and $b are not directly connected" }
        _edges.remove(edge)
        adjacency.remove(a.id)
        adjacency.remove(b.id)
    }

    /**
     * Every place the net can currently react: each edge joining two principal ports, de-duplicated by
     * [ActivePair.canonicalKey].
     *
     * The list is a snapshot, so it is safe to iterate while you rewrite — but every rewrite invalidates it, since
     * a reaction usually creates and destroys pairs. An empty list means the net is in normal form and a [Reducer]
     * will stop. Order follows edge creation order, which is why the scheduler exists.
     */
    fun activePairs(): List<ActivePair> {
        val seen = LinkedHashSet<Pair<String, String>>()
        val pairs = ArrayList<ActivePair>()
        for (edge in _edges) {
            val a = edge.a
            val b = edge.b
            if (a.kind == PortKind.PRINCIPAL && b.kind == PortKind.PRINCIPAL) {
                val pair = ActivePair(a.node, b.node)
                val key = pair.canonicalKey()
                if (seen.add(key)) pairs += pair
            }
        }
        return pairs
    }

    internal fun absorb(fragment: NetFragment, placement: Placement): FragmentEmbed {
        val mapping = LinkedHashMap<NodeRef, Node>()
        for ((ref, spec) in fragment.nodeSpecs) {
            val created = create(spec.type, ids.next(spec.suggestedPrefix), spec.state)
            setPosition(created, placement.base + spec.offset)
            setVelocity(created, spec.impulse)
            mapping[ref] = created
        }
        for ((a, b) in fragment.pendingInternalConnections) {
            connect(mapping[a.node]!!.ports[a.portIndex], mapping[b.node]!!.ports[b.portIndex])
        }
        return FragmentEmbed(mapping)
    }

    /**
     * Adds [dt] to every node's `age`. Nothing calls this for you, so age advances only on the clock you choose —
     * per frame, per reduction step, per beat.
     */
    fun incrementAges(dt: Double = 1.0) {
        nodes.forEach { it.state.age += dt }
    }

    /**
     * Runs the force-directed layout for [iterations] steps, moving every node.
     *
     * **O(n²) per step** — every node repels every other — so this is the expensive call in the file, and it
     * reports progress to the running box for exactly that reason. A handful of iterations per frame gives a net
     * that visibly settles; a few hundred in one go gives you a laid-out picture immediately.
     *
     * Velocity carries over between calls, so `relax(1)` per frame animates and `relax(200)` once converges.
     */
    fun relax(iterations: Int = 1, config: LayoutConfig = LayoutConfig()) {
        // Force-directed layout is O(n^2) per step; offer a determinate bar to the running box.
        val progress = afield.progressReporter("relax")
        try {
            repeat(iterations) { i ->
                stepLayout(config)
                progress.update((i + 1).toLong(), iterations.toLong())
            }
        } finally {
            progress.finish()
        }
    }

    private fun stepLayout(config: LayoutConfig) {
        val ns = nodes.toList()
        if (ns.isEmpty()) return
        val forces = LinkedHashMap<String, Vec2>()
        ns.forEach { forces[it.id] = Vec2() }

        for (i in ns.indices) {
            for (j in i + 1 until ns.size) {
                val a = ns[i]
                val b = ns[j]
                val pa = positionOf(a)
                val pb = positionOf(b)
                val d = pa - pb
                val dist = max(d.length(), 0.1f)
                val dir = if (dist < 1e-9f) vec(Random.nextDouble(), Random.nextDouble()).normalize() else d / dist
                val mag = (config.repulsion / (dist * dist)).toFloat()
                forces[a.id] = Vec2(forces[a.id]!!).add(Vec2(dir).mul(mag))
                forces[b.id] = Vec2(forces[b.id]!!).sub(Vec2(dir).mul(mag))
            }
        }

        for (e in edges) {
            val a = e.a.node
            val b = e.b.node
            val pa = positionOf(a)
            val pb = positionOf(b)
            val d = pb - pa
            val dist = max(d.length(), 0.1f)
            val dir = d / dist
            val stretch = dist - config.springLength
            val f = Vec2(dir).mul((config.springStrength * stretch).toFloat())
            forces[a.id] = Vec2(forces[a.id]!!).add(f)
            forces[b.id] = Vec2(forces[b.id]!!).sub(f)
        }

        for (n in ns) {
            val motion = motions.getOrPut(n.id) { Motion() }
            val accel = forces[n.id] ?: Vec2()
            var vel = Vec2(motion.velocity).add(Vec2(accel).mul(config.timeStep.toFloat())).mul(config.damping.toFloat())
            val speed = vel.length().toDouble()
            if (speed > config.clampStep) vel = vel.normalize().mul(config.clampStep.toFloat())
            motion.velocity = vel
            positions[n.id] = Vec2(positionOf(n)).add(Vec2(vel).mul(config.timeStep.toFloat()))
        }
    }

    /**
     * The sum of every node's `energy` — a one-number read on the whole net.
     *
     * Whether it is conserved is entirely up to your rules: `NodeState.split` divides energy between branches, so a
     * net of splits holds it steady, while `evolved(energyScale = 0.9)` bleeds it away. Watching this fall is the
     * cheapest way to see a rule set is decaying rather than growing.
     */
    fun totalEnergy(): Double = nodes.sumOf { it.state.energy }

    /** Mean node `age`, or 0.0 for an empty net. Rises as the net persists, falls as rewrites replace old nodes with new. */
    fun averageAge(): Double = if (nodes.isEmpty()) 0.0 else nodes.sumOf { it.state.age } / nodes.size.toDouble()

    /**
     * Draws the net: one [FLine] per edge, and several per node — a filled disc and a stroked ring sized and
     * coloured from its [NodeState], a spoke per port, a point per port, a halo when the node is half of an
     * active pair, and a streak when it is moving.
     *
     * The picture the whole file is for — hand it to `lines(...)` or [Box.showInteractionNet]. Active pairs are
     * drawn emphatically (thicker, brighter) so a redex is visible before it fires; node radius follows `energy`,
     * fill hue follows `hue`, and lightness drifts with `age`.
     *
     * Positions come from the layout, so call [relax] first or everything overlaps near the origin. An empty net
     * gives an empty list. [velocityScale] is the length of the streak per unit of layout velocity; 0 removes them.
     */
    fun toFLines(
        velocityScale: Double = 18.0
    ): List<FLine> {
        if (nodes.isEmpty()) return emptyList()

        val nodeList = nodes.toList()
        val activeKeys = activePairs().map { it.canonicalKey() }.toSet()
        val activeNodeIds = activeKeys.flatMap { listOf(it.first, it.second) }.toSet()
        val nodeRadius = nodeList.associate { it.id to renderRadius(it) }
        val principalAngles = nodeList.associate { it.id to principalAngleFor(it) }

        fun portPosition(port: Port): Vec2 {
            val center = positionOf(port.node)
            val radius = nodeRadius.getValue(port.node.id) * if (port.index == 0) 1.0 else 0.92
            val angle = portAngleFor(port.node, port.index, principalAngles.getValue(port.node.id))
            return vec(
                center.x + cos(angle) * radius,
                center.y + sin(angle) * radius
            )
        }

        val out = mutableListOf<FLine>()

        edges.forEach { edge ->
            val active = edge.a.kind == PortKind.PRINCIPAL &&
                edge.b.kind == PortKind.PRINCIPAL &&
                ActivePair(edge.a.node, edge.b.node).canonicalKey() in activeKeys
            out += fline {
                moveTo(portPosition(edge.a))
                lineTo(portPosition(edge.b))
                stroked = true
                filled = false
                thickness = edgeThickness(edge, active)
                color = edgeColor(edge, active)
            }
        }

        nodeList.forEach { node ->
            val center = positionOf(node)
            val radius = nodeRadius.getValue(node.id)
            val hue = nodeHue01(node)
            val fill = hsl(
                hue,
                0.74,
                (0.68 - node.state.energy * 0.06 + node.state.age * 0.01).coerceIn(0.26, 0.82),
                0.92
            )
            val stroke = hsl(
                hue,
                0.78,
                (0.34 + node.state.energy * 0.04).coerceIn(0.18, 0.52),
                0.98
            )

            if (node.id in activeNodeIds) {
                out += fline {
                    circle(center.x.toDouble(), center.y.toDouble(), radius + 10.0, segments = 32)
                    filled = false
                    stroked = true
                    thickness = 1.4
                    color = hsl(hue, 0.86, 0.60, (0.10 + node.state.energy * 0.08).coerceIn(0.10, 0.34))
                }
            }

            val velocity = velocityOf(node)
            if (velocity.lengthSquared().toDouble() > 0.01) {
                out += fline {
                    moveTo(center)
                    lineTo(
                        vec(
                            center.x + velocity.x * velocityScale.toFloat(),
                            center.y + velocity.y * velocityScale.toFloat()
                        )
                    )
                    stroked = true
                    filled = false
                    thickness = 1.2
                    color = hsl(hue, 0.62, 0.72, 0.35)
                }
            }

            out += fline {
                circle(center.x.toDouble(), center.y.toDouble(), radius, segments = 40)
                filled = true
                stroked = false
                color = fill
            }
            out += fline {
                circle(center.x.toDouble(), center.y.toDouble(), radius, segments = 40)
                filled = false
                stroked = true
                thickness = (1.5 + node.state.energy * 1.25).coerceIn(1.5, 4.2)
                color = stroke
            }

            out += fline {
                stroked = true
                filled = false
                thickness = 0.9
                color = hsl(hue, 0.38, 0.72, 0.28)
                node.ports.forEach { port ->
                    moveTo(center)
                    lineTo(portPosition(port))
                }
            }

            out += fline {
                pointed = true
                pointSize = (radius * 0.18).coerceIn(2.6, 5.0)
                color = vec(1.0, 0.96, 0.90, 0.96)
                moveTo(portPosition(node.principal))
            }

            if (node.type.auxPorts > 0) {
                out += fline {
                    pointed = true
                    pointSize = (radius * 0.12).coerceIn(1.8, 3.6)
                    color = hsl(hue, 0.42, 0.86, 0.76)
                    repeat(node.type.auxPorts) { index ->
                        moveTo(portPosition(node.aux(index)))
                    }
                }
            }
        }

        return out.filter { it.nodes.isNotEmpty() }
    }

    /**
     * A dark-themed HTML card summarising the net — counts, total energy, the highest-energy nodes and the active
     * pairs waiting to fire — for putting in a box output next to the drawing.
     *
     * The numbers, where [toFLines] is the picture. Nodes are listed highest-energy first and capped at
     * [maxNodes], active pairs at six, so this stays a card rather than a dump on a large net. Ids and type names
     * are HTML-escaped. [Box.showInteractionSummary] is the one-liner that displays it.
     */
    fun summaryHtml(title: String = "Interaction net", maxNodes: Int = 8): String {
        val active = activePairs()
        // Table escapes the ids and types, and right-aligns energy and age because they are numbers —
        // the two things the hand-written rows had to say per cell.
        val nodeTable = Table(listOf("node", "type", "energy", "age"))
            .rows(
                nodes
                    .sortedWith(compareByDescending<Node> { it.state.energy }.thenBy { it.id })
                    .take(maxNodes)
                    .map { node -> listOf(node.id, node.type.name, node.state.energy, node.state.age) }
            )
            .role("type", CellRole.MUTED)
            .format("energy") { fmt(it as Double) }
            .format("age") { fmt(it as Double) }
            .align("energy", Align.RIGHT)
            .align("age", Align.RIGHT)
        val activeHtml = if (active.isEmpty()) {
            """<div style="margin-top:10px;color:#91a4ba;">No active principal-principal pairs.</div>"""
        } else {
            active.take(6).joinToString(
                prefix = """<div style="margin-top:10px;display:flex;flex-direction:column;gap:6px;">""",
                postfix = "</div>"
            ) { pair ->
                """
                    <div style="padding:6px 8px;background:#101822;border:1px solid #1e2a39;border-radius:7px;color:#dce6f3;">
                        ${escapeInteractionHtml(pair.left.id)}:${escapeInteractionHtml(pair.left.type.name)}
                        &#8596;
                        ${escapeInteractionHtml(pair.right.id)}:${escapeInteractionHtml(pair.right.type.name)}
                    </div>
                """.trimIndent()
            }
        }
        val more = if (nodes.size > maxNodes) {
            """<div style="margin-top:10px;color:#91a4ba;">+${nodes.size - maxNodes} more nodes not shown.</div>"""
        } else ""

        return interactionSummaryCard(
            title = title,
            body = """
                <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:8px;">
                    ${interactionMetric("nodes", nodes.size.toString())}
                    ${interactionMetric("edges", edges.size.toString())}
                    ${interactionMetric("active pairs", active.size.toString())}
                    ${interactionMetric("total energy", fmt(totalEnergy()))}
                    ${interactionMetric("mean age", fmt(averageAge()))}
                </div>
                ${nodeTable.toHTML()}
                $more
                $activeHtml
            """.trimIndent()
        )
    }

    /**
     * The net as a standalone SVG document, on **white** rather than the dark palette the box output uses — the
     * export for putting a net in a paper or a slide.
     *
     * The drawing is scaled to fit the given box with [padding] on every side, so the coordinate system of the
     * layout does not have to match anything. An empty net still yields a valid blank SVG. [Net.writeSvg] is the
     * same thing written to a file.
     */
    fun toSvg(width: Int = 900, height: Int = 700, padding: Double = 40.0): String {
        if (nodes.isEmpty()) {
            return """
                <svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">
                  <rect width="100%" height="100%" fill="white"/>
                </svg>
            """.trimIndent()
        }

        val fitted = fitLinesToBox(toFLines(), width.toDouble(), height.toDouble(), padding)
        val background = fline {
            moveTo(0.0, 0.0)
            lineTo(width.toDouble(), 0.0)
            lineTo(width.toDouble(), height.toDouble())
            lineTo(0.0, height.toDouble())
            close()
            filled = true
            stroked = false
            color = vec(1.0, 1.0, 1.0, 1.0)
        }
        return drawToSVG(listOf(background) + fitted, width.toDouble(), height.toDouble())
    }

    private fun renderRadius(node: Node): Double =
        (18.0 + node.state.energy * 5.5).coerceIn(16.0, 34.0)

    private fun nodeHue01(node: Node): Double =
        wrapUnit(baseHueDegrees(node.type.name) / 360.0 + node.state.hue * 0.22)

    private fun baseHueDegrees(typeName: String): Double = when (typeName) {
        "APP" -> 208.0
        "LAM" -> 280.0
        "DUP" -> 28.0
        "ERASE" -> 8.0
        "ROOT" -> 160.0
        "VAR" -> 190.0
        "CONST" -> 48.0
        else -> 200.0
    }

    private fun principalAngleFor(node: Node): Double {
        val center = positionOf(node)
        connected(node.principal)?.let { peer ->
            val delta = positionOf(peer.node) - center
            val lenSq = delta.x * delta.x + delta.y * delta.y
            if (lenSq > 1e-4f) return atan2(delta.y.toDouble(), delta.x.toDouble())
        }

        val auxAngles = (0 until node.type.auxPorts).mapNotNull { auxIndex ->
            connected(node.aux(auxIndex))?.let { peer ->
                val delta = positionOf(peer.node) - center
                val lenSq = delta.x * delta.x + delta.y * delta.y
                if (lenSq > 1e-4f) atan2(delta.y.toDouble(), delta.x.toDouble()) else null
            }
        }

        if (auxAngles.isNotEmpty()) {
            val dx = auxAngles.sumOf { cos(it) }
            val dy = auxAngles.sumOf { sin(it) }
            if (dx * dx + dy * dy > 1e-6) return atan2(-dy, -dx)
        }

        return node.state.phase * PI * 0.5 - PI / 2.0
    }

    private fun portAngleFor(node: Node, portIndex: Int, principalAngle: Double): Double {
        if (portIndex == 0) return principalAngle
        if (node.type.auxPorts <= 1) return principalAngle + PI
        val spread = min(1.65, 0.85 + 0.18 * node.type.auxPorts)
        val t = (portIndex - 1).toDouble() / (node.type.auxPorts - 1).toDouble()
        return principalAngle + PI + (-spread / 2.0 + spread * t)
    }

    private fun edgeThickness(edge: Edge, active: Boolean): Double {
        val energy = (edge.a.node.state.energy + edge.b.node.state.energy) * 0.5
        return when {
            active -> (2.6 + energy * 0.9).coerceIn(2.2, 5.2)
            edge.a.kind != edge.b.kind -> (1.6 + energy * 0.35).coerceIn(1.4, 3.2)
            else -> (1.1 + energy * 0.25).coerceIn(1.0, 2.4)
        }
    }

    private fun edgeColor(edge: Edge, active: Boolean): Vec4 {
        val hue = wrapUnit((nodeHue01(edge.a.node) + nodeHue01(edge.b.node)) * 0.5)
        val energy = (edge.a.node.state.energy + edge.b.node.state.energy) * 0.5
        val saturation = if (active) 0.84 else if (edge.a.kind != edge.b.kind) 0.54 else 0.32
        val lightness = if (active) 0.60 else (0.72 - energy * 0.05).coerceIn(0.44, 0.76)
        val alpha = if (active) 0.90 else if (edge.a.kind != edge.b.kind) 0.58 else 0.36
        return hsl(hue, saturation, lightness, alpha)
    }

    private fun fitLinesToBox(lines: List<FLine>, width: Double, height: Double, padding: Double): List<FLine> {
        return lines.fitInto(Rect(0.0, 0.0, width, height), padding = padding)
    }
}

// =========================
// DSL for fragments
// =========================

/**
 * A *name* for a node inside a fragment or a rewrite's right-hand side, standing in for a node that does not exist
 * yet.
 *
 * You get one from `node(...)` in a [FragmentBuilder], [RewriteBuilder] or [AutoRuleBuilder] and wire it up by
 * reference; the real [Node] is created when the fragment is embedded or the rule fires, and [FragmentEmbed] is how
 * you look it up afterwards. Identity is by instance, not by [name], so two refs with the same name in different
 * fragments are different refs.
 */
class NodeRef internal constructor(val name: String) {
    /** The ref's name, as given. */
    override fun toString(): String = name
}

/**
 * A port on a not-yet-created node: a [NodeRef] plus a **raw** port index, where 0 is the principal port and the
 * auxiliaries start at 1.
 *
 * Build these with the builders' `principal(...)` and `aux(...)` helpers rather than by hand — `aux` takes the
 * 0-based auxiliary number and does the `+1` and the range check for you.
 */
data class PortRef(val node: NodeRef, val portIndex: Int)
internal data class NodeSpec(
    val type: AgentType,
    val suggestedPrefix: String,
    val offset: Vec2,
    val state: NodeState = NodeState(),
    val impulse: Vec2 = Vec2()
)

/**
 * A reusable piece of net — some nodes, the wires between them, and a set of named ports it offers to the outside —
 * that can be stamped into a [Net] as many times as you like.
 *
 * The unit of composition: build one with [fragment], embed it with `NetBuilder.embed`, and wire to it by the names
 * it exported. Each embedding creates fresh nodes with fresh ids, so a fragment is a template rather than a shared
 * subgraph. Node positions inside it are **offsets** from wherever it is placed.
 */
class NetFragment internal constructor(
    internal val nodeSpecs: LinkedHashMap<NodeRef, NodeSpec> = LinkedHashMap(),
    internal val pendingInternalConnections: MutableList<Pair<PortRef, PortRef>> = mutableListOf(),
    internal val publishedPorts: LinkedHashMap<String, PortRef> = LinkedHashMap()
) {
    /**
     * Publishes a port under a name, so whoever embeds this fragment can wire to it without knowing what is inside.
     *
     * Re-exporting the same name **replaces** the previous entry silently. Usually called as `expose` from inside
     * the [fragment] builder rather than directly.
     */
    fun export(name: String, port: PortRef) {
        publishedPorts[name] = port
    }
}

/** The receiver inside a [fragment] block: declare nodes, wire them with `to`, and `expose` the ports that should
 * be visible from outside. */
class FragmentBuilder {
    private val fragment = NetFragment()

    /**
     * Declares a node in the fragment and returns the [NodeRef] you wire with. Nothing is created yet.
     *
     * [at] is an **offset** from wherever the fragment ends up being embedded, not an absolute position, and
     * [impulse] is a starting layout velocity — a fragment that should visibly burst outward when stamped in sets
     * one. The [state] is copied.
     *
     * @throws IllegalArgumentException if [name] is already used in this fragment
     */
    fun node(
        name: String,
        type: AgentType,
        at: Vec2 = Vec2(),
        state: NodeState = NodeState(),
        impulse: Vec2 = Vec2()
    ): NodeRef {
        val ref = NodeRef(name)
        // By name, not by ref: NodeRef is identity-keyed on purpose, so `ref !in map` compares a
        // freshly built instance against the map's keys and never matches — the duplicate check was
        // dead code, and a repeated name quietly produced two separate nodes.
        require(fragment.nodeSpecs.keys.none { it.name == name }) { "Duplicate fragment node name: $name" }
        fragment.nodeSpecs[ref] = NodeSpec(type, type.name.lowercase(), at, state.copyDeep(), Vec2(impulse))
        return ref
    }

    /** The reactive port of a declared node — the one that can form an active pair. */
    fun principal(ref: NodeRef): PortRef = PortRef(ref, 0)

    /**
     * Auxiliary port [i] of a declared node, numbered from **0**.
     *
     * @throws IllegalStateException if [ref] was not declared in this fragment
     * @throws IllegalArgumentException if [i] is outside the type's auxiliary range
     */
    fun aux(ref: NodeRef, i: Int): PortRef {
        val spec = fragment.nodeSpecs[ref] ?: error("Unknown fragment node: $ref")
        require(i in 0 until spec.type.auxPorts) { "Aux index $i out of range for ${spec.type.name}" }
        return PortRef(ref, i + 1)
    }

    /**
     * `principal(a) to aux(b, 0)` — records a wire between two ports of this fragment.
     *
     * Recorded, not made: the connection (and any error in it, such as a port used twice) surfaces when the fragment
     * is embedded, not here.
     */
    infix fun PortRef.to(other: PortRef) {
        fragment.pendingInternalConnections.add(Pair(this, other))
    }

    /** Publishes a port under a name for whoever embeds this fragment. A repeated name replaces the earlier one. */
    fun expose(name: String, port: PortRef) {
        fragment.export(name, port)
    }

    /** The finished [NetFragment]. [fragment] calls this for you. */
    fun build(): NetFragment = fragment
}

/**
 * Builds a reusable [NetFragment]: `fragment { val a = node("a", APP); expose("in", principal(a)) }`.
 *
 * Nothing is created until the fragment is embedded, so this is cheap to call and safe to keep in a `val` and stamp
 * repeatedly.
 */
fun fragment(block: FragmentBuilder.() -> Unit): NetFragment = FragmentBuilder().apply(block).build()

/**
 * The result of embedding a fragment: the mapping from the fragment's [NodeRef]s to the real [Node]s that were just
 * created.
 *
 * How you reach *inside* an embedded fragment, where [EmbeddedPorts] only gives you the ports it chose to export.
 */
class FragmentEmbed internal constructor(private val nodeMap: Map<NodeRef, Node>) {
    /**
     * The real node created for this ref.
     *
     * @throws IllegalStateException if the ref did not belong to the embedded fragment
     */
    operator fun get(ref: NodeRef): Node = nodeMap[ref] ?: error("Unknown embedded ref: $ref")

    /** Every node this embedding created, in the fragment's declaration order. */
    val nodes: Collection<Node> get() = nodeMap.values
}

// =========================
// Builder DSL for full nets
// =========================

/** The receiver inside a [net] block: create nodes, wire them with `to`, and stamp in [NetFragment]s. */
class NetBuilder(private val net: Net) {
    /**
     * Creates a node in the net at an **absolute** position and returns it, ready to wire.
     *
     * Unlike the fragment builder's `node`, this makes the real thing immediately. [at] defaults to the origin, so
     * pass positions if you do not intend to [Net.relax] afterwards, or everything starts stacked. [impulse] is a
     * starting layout velocity.
     */
    fun node(
        type: AgentType,
        id: String? = null,
        at: Vec2 = Vec2(),
        state: NodeState = NodeState(),
        impulse: Vec2 = Vec2()
    ): Node {
        val n = net.create(type, id ?: net.ids.next(type.name.lowercase()), state)
        net.setPosition(n, at)
        net.setVelocity(n, impulse)
        return n
    }

    /** `a.principal to b.principal` — wires two ports, immediately. Same rules (and exceptions) as [Net.connect]. */
    infix fun Port.to(other: Port) = net.connect(this, other)

    /** Wires two ports. The non-infix form of `to`, for when the expression reads better this way. */
    fun connect(a: Port, b: Port) = net.connect(a, b)

    /**
     * Stamps a [NetFragment] into the net at [at] and returns its exported ports, ready to wire to.
     *
     * Fresh nodes with fresh ids every time, so the same fragment can be embedded repeatedly. The fragment's own node
     * positions are treated as offsets from [at]. Only the ports the fragment chose to `expose` come back; use
     * [FragmentEmbed] if you need the nodes themselves.
     */
    fun embed(fragment: NetFragment, at: Vec2 = Vec2()): EmbeddedPorts {
        val embed = net.absorb(fragment, Placement(at))
        val exported = fragment.publishedPorts.mapValues { (_, p) -> embed[p.node].ports[p.portIndex] }
        return EmbeddedPorts(exported)
    }
}

/**
 * The ports an embedded fragment published, looked up by the names it exposed — `ports["in"]`.
 *
 * @throws IllegalStateException from [get] when the name was never exposed, which is the usual typo
 */
class EmbeddedPorts internal constructor(private val exported: Map<String, Port>) {
    /** The real port behind an exposed name. Throws if the fragment did not export it. */
    operator fun get(name: String): Port = exported[name] ?: error("No exported port named $name")
}

/**
 * Builds a [Net]: `net { val a = node(APP); val b = node(LAM); a.principal to b.principal }`.
 *
 * The entry point to the whole file. Everything inside happens immediately, so a `require` violation — a port wired
 * twice, a duplicate id — throws here rather than later. Hand the result to a [Reducer] to run it and to
 * [Net.toFLines] to look at it.
 */
fun net(block: NetBuilder.() -> Unit): Net {
    val n = Net()
    NetBuilder(n).apply(block)
    return n
}

// =========================
// Rewriting
// =========================

/**
 * The left-hand side of a rewrite rule: the pair of agent types it fires on.
 *
 * Matching is **unordered** — a `PairSignature(APP, LAM)` matches an APP-LAM collision whichever way round the edge
 * happens to be stored — and [bind] reports which way it went so the rule body can still talk about "the APP" and
 * "the LAM" as node 0 and node 1. That is the whole reason for the `swapped` flag downstream.
 *
 * A signature of one type with itself matches two nodes of that type, since [ActivePair] already rules out a node
 * meeting itself.
 */
data class PairSignature(val left: AgentType, val right: AgentType) {
    /** Whether these two nodes are this signature's pair, in either order. */
    fun matches(a: Node, b: Node): Boolean = bind(a, b) != null

    /**
     * Matches the two nodes against this signature, returning them **in the signature's order** — or null if they are
     * not this pair.
     *
     * `first` is always the `left` type and `second` the `right`, with `swapped` recording whether the arguments had
     * to be exchanged to get there. So a rule body can index by ordinal without caring how the edge was stored.
     */
    fun bind(a: Node, b: Node): SignatureBinding? = when {
        a.type == left && b.type == right -> SignatureBinding(a, b, swapped = false)
        a.type == right && b.type == left -> SignatureBinding(b, a, swapped = true)
        else -> null
    }
}

/**
 * A successful [PairSignature] match: the two nodes in the signature's own order, plus whether that meant swapping
 * them relative to how the edge was found.
 *
 * `first` is the signature's `left`, `second` its `right`. [swapped] is not needed to *use* the binding — it is
 * reported so a rule or a log can tell the two orientations apart.
 */
data class SignatureBinding(val first: Node, val second: Node, val swapped: Boolean)

/** A rule and the binding it matched with — what [RuleBook.findMatch] returns, and what the scheduler chooses between. */
data class RuleMatch(val rule: RewriteRule, val binding: SignatureBinding)

/**
 * Everything a rewrite rule gets told about the collision it is replacing: the two nodes in signature order, what
 * their auxiliary ports were attached to, and the local geometry.
 *
 * The auxiliary peers are the important part — the pair is about to be deleted, so these are the loose ends the rule
 * must account for, and a null entry means that port was already free. Index them by *ordinal* (0 for `first`, 1 for
 * `second`) through [auxPeer], which is the same numbering `ext(...)` uses in the automatic-rewiring DSL.
 *
 * The geometry is what lets a rewrite inherit motion instead of teleporting: [center] is the midpoint of the pair and
 * where new nodes are seeded, [radius] roughly how far apart they were, [axis] the unit direction from first to
 * second and [normal] its perpendicular — so [branchImpulse] can throw new nodes sideways out of the collision.
 */
data class PairContext(
    /** The node matching the rule signature's *left* type — ordinal 0 throughout the rule DSL, whichever way the
     * edge happened to be stored. About to be deleted. */
    val first: Node,
    /** The node matching the signature's *right* type — ordinal 1. Also about to be deleted. */
    val second: Node,
    /** What each of [first]'s auxiliary ports was wired to, in aux order; a null entry means that port was already
     * free. These are the loose ends the rule must account for. */
    val firstAuxPeers: List<Port?>,
    /** The same for [second]'s auxiliary ports. */
    val secondAuxPeers: List<Port?>,
    /** Midpoint of the two nodes, and the point replacement nodes are seeded at — which is why a rewrite appears to
     * grow out of the collision rather than at the origin. */
    val center: Vec2,
    /** Roughly half the pair's separation, **floored at 12.0** so a coincident pair still gives replacements
     * somewhere to go. The default scale for [branchImpulse]. */
    val radius: Double,
    /** [first]'s layout velocity at the moment of the collision, so a replacement can inherit its motion. */
    val firstVelocity: Vec2,
    /** [second]'s layout velocity at the moment of the collision. */
    val secondVelocity: Vec2,
    /** Unit vector from [first] toward [second]. Falls back to `(1, 0)` for a coincident pair rather than being NaN. */
    val axis: Vec2,
    /** Unit vector perpendicular to [axis]. The direction [branchImpulse] throws branches along. */
    val normal: Vec2
) {
    /**
     * What auxiliary port [auxIndex] of node [nodeOrdinal] (0 = `first`, 1 = `second`) was wired to — the loose end a
     * rule has to reattach.
     *
     * Null when that port was free, or when the index is out of range: **out-of-range is not an error here**, it
     * returns null, so a typo in an aux index silently drops a connection rather than complaining.
     *
     * @throws IllegalStateException if [nodeOrdinal] is not 0 or 1
     */
    fun auxPeer(nodeOrdinal: Int, auxIndex: Int): Port? = when (nodeOrdinal) {
        0 -> firstAuxPeers.getOrNull(auxIndex)
        1 -> secondAuxPeers.getOrNull(auxIndex)
        else -> error("nodeOrdinal must be 0 or 1")
    }

    /**
     * The **live** state of one of the two nodes — mutating it mutates the doomed node, which is almost never what you
     * want. Use [stateCopy] to seed a replacement.
     *
     * @throws IllegalStateException if [nodeOrdinal] is not 0 or 1
     */
    fun state(nodeOrdinal: Int): NodeState = when (nodeOrdinal) {
        0 -> first.state
        1 -> second.state
        else -> error("nodeOrdinal must be 0 or 1")
    }

    /** An independent copy of one node's state, safe to hand to a new node and then alter. */
    fun stateCopy(nodeOrdinal: Int): NodeState = state(nodeOrdinal).copyDeep()

    /**
     * A copy of one node's layout velocity, so a replacement can inherit its motion rather than appearing at rest.
     *
     * @throws IllegalStateException if [nodeOrdinal] is not 0 or 1
     */
    fun velocity(nodeOrdinal: Int): Vec2 = when (nodeOrdinal) {
        0 -> Vec2(firstVelocity)
        1 -> Vec2(secondVelocity)
        else -> error("nodeOrdinal must be 0 or 1")
    }

    /** The two states averaged — the state for a rewrite that fuses the pair into one node. See [NodeState.blend]. */
    fun blendedState(weightToFirst: Double = 0.5): NodeState = NodeState.blend(first.state, second.state, weightToFirst)

    /** The pair's combined energy. What the energy-biased [PairSelectors] rank by, and the default event magnitude. */
    val pairEnergy: Double get() = first.state.energy + second.state.energy

    /**
     * A sideways kick for branch [branch] of [branches], so replacement nodes fly apart out of the collision instead of
     * being born on top of each other.
     *
     * Along [normal] — perpendicular to the pair's axis — and **centred**, so with the default two branches the kicks
     * are equal and opposite and the pair's centre of motion is preserved. [amount] defaults to a fraction of the
     * pair's own separation, so the spread scales with the local geometry rather than being a fixed number of pixels.
     *
     * @throws IllegalArgumentException if [branches] is not positive or [branch] is outside `0 until branches`
     */
    fun branchImpulse(branch: Int, branches: Int = 2, amount: Double = radius * 0.35): Vec2 {
        require(branches > 0) { "branches must be > 0" }
        require(branch in 0 until branches) { "branch $branch out of range for $branches branches" }
        val centered = branch - (branches - 1) / 2.0
        return Vec2(normal).mul((centered * amount).toFloat())
    }
}

/**
 * Something a rewrite wants the outside world to notice — "flash here", "trigger a note", "spawn a particle" — with a
 * free-text [kind], a place, a strength and any extra numbers.
 *
 * The hook that turns graph rewriting into an event engine: nothing in this file interprets [kind], so the vocabulary
 * is entirely yours, and a rule emits them with `emit(...)`. Collected per step into [ReductionStep.events] and, in
 * bulk, by `Reducer.reduceDetailed`.
 */
data class RewriteEvent(
    /** Free text naming what happened — `"beta"`, `"spark"`, `"note"`. **Nothing in this file interprets it**, so
     * the vocabulary is entirely yours. */
    val kind: String,
    /** Where in layout space it happened. Defaults to the collision centre when emitted from a rule. */
    val at: Vec2,
    /** How much of it there was. Defaults to the pair's combined energy, which makes a decaying rule emit
     * progressively weaker events for free. */
    val magnitude: Double = 1.0,
    /** Any extra named numbers the rule wanted to pass along. Copied on emit, so the map you passed is not retained. */
    val data: Map<String, Double> = emptyMap()
)

/**
 * The record of one rewrite that happened: which rule fired, on what, what it removed, what it created and what it
 * emitted.
 *
 * What `Reducer.stepDetailed` returns — enough to animate the change, log it, or drive sound from it, without having to
 * diff the net before and after. The node ids are ids rather than nodes because the removed ones are gone by the time
 * you read this.
 */
data class ReductionStep(
    /** The [RewriteRule.name] that fired — which is why it is worth naming rules something you would read in a log. */
    val ruleName: String,
    /** The collision as the rule saw it, including the geometry, so a caller can flash the right place on screen. */
    val context: PairContext,
    /** Whether the pair had to be exchanged to match the rule's signature order. Informational; the rule's behaviour
     * does not depend on it. */
    val swapped: Boolean,
    /** The two consumed nodes' ids. Ids rather than nodes because those nodes are already gone by the time you read this. */
    val removedNodeIds: List<String>,
    /** The ids of the replacement nodes, in the order the rule declared them. */
    val createdNodeIds: List<String>,
    /** Everything the rule emitted, snapshot-copied — so this survives even though the nodes behind it do not. */
    val events: List<RewriteEvent>
)

/**
 * The receiver inside a `rule { }` body: declare the nodes that replace the active pair, wire them, and say what
 * happens to the loose ends.
 *
 * The full-control rewrite surface, where [AutoRuleBuilder] is the declarative one. The pair itself is always deleted;
 * your job is the replacement. Three ways to deal with the auxiliary ports the pair leaves dangling: [reconnect] a new
 * node's port to one of them, [passthrough] two of them straight to each other, or leave one out entirely and accept a
 * free port. Nothing checks that you accounted for all of them.
 *
 * New node positions are **offsets from the collision centre**, so a rewrite inherits the pair's location for free.
 */
class RewriteBuilder internal constructor(private val ctx: PairContext) {
    private val result = RewriteResult()

    /**
     * Declares a replacement node and returns its [NodeRef]. Nothing is created until the rule is applied.
     *
     * [at] is an offset from the collision centre, and [impulse] a starting velocity — see `PairContext.branchImpulse`
     * for throwing two branches apart. The [state] is copied; `ctx.stateCopy(0)` or `blendedState()` is the usual thing
     * to pass so the replacement inherits something from what it replaced.
     *
     * @throws IllegalArgumentException if [name] is already used in this rewrite
     */
    fun node(
        name: String,
        type: AgentType,
        at: Vec2 = Vec2(),
        state: NodeState = NodeState(),
        impulse: Vec2 = Vec2()
    ): NodeRef {
        val ref = NodeRef(name)
        // By name, not by ref: NodeRef is identity-keyed on purpose, so `ref !in map` compares a
        // freshly built instance against the map's keys and never matches — the duplicate check was
        // dead code, and a repeated name quietly produced two separate nodes.
        require(result.fragment.nodeSpecs.keys.none { it.name == name }) { "Duplicate rewrite node name: $name" }
        result.fragment.nodeSpecs[ref] = NodeSpec(type, type.name.lowercase(), at, state.copyDeep(), Vec2(impulse))
        return ref
    }

    /** The principal port of a replacement node — wire two of these together and the new net can react again. */
    fun principal(ref: NodeRef): PortRef = PortRef(ref, 0)

    /**
     * Auxiliary port [i] of a replacement node, numbered from **0**.
     *
     * @throws IllegalStateException if [ref] was not declared in this rewrite
     * @throws IllegalArgumentException if [i] is outside the type's auxiliary range (with no message, unlike its
     * fragment-builder twin)
     */
    fun aux(ref: NodeRef, i: Int): PortRef {
        val spec = result.fragment.nodeSpecs[ref] ?: error("Unknown rewrite node: $ref")
        require(i in 0 until spec.type.auxPorts)
        return PortRef(ref, i + 1)
    }

    /** `principal(a) to aux(b, 0)` — wires two ports **inside** the replacement. Applied when the rule fires. */
    infix fun PortRef.to(other: PortRef) {
        result.fragment.pendingInternalConnections.add(Pair(this, other))
    }

    /**
     * Attaches a replacement node's port to one of the loose ends the deleted pair left behind — this is how the
     * surrounding net stays connected.
     *
     * A null [oldPeer] is **silently ignored**, which is the point: `reconnect(aux(a, 0), ctx.auxPeer(1, 0))` is safe
     * even where that port of the old node happened to be free.
     */
    fun reconnect(newPort: PortRef, oldPeer: Port?) {
        if (oldPeer != null) result.reconnections += newPort to oldPeer
    }

    /**
     * The same as the other [reconnect], naming the loose end by which node of the pair it belonged to (0 = first,
     * 1 = second) and which of its auxiliary ports — the shorter form, and the one that matches `ext(...)` in the
     * automatic DSL. Silently does nothing if that port was free.
     */
    fun reconnect(newPort: PortRef, nodeOrdinal: Int, auxIndex: Int) {
        reconnect(newPort, ctx.auxPeer(nodeOrdinal, auxIndex))
    }

    /**
     * Wires two of the pair's loose ends **directly to each other**, bypassing the deleted nodes entirely — how you
     * write an ERASE or a plain "these two were connected through me, now connect them" rule.
     *
     * Does nothing unless both ports are non-null, so it is safe to hand it two possibly-free peers.
     */
    fun passthrough(a: Port?, b: Port?) {
        if (a != null && b != null) result.passthroughs += a to b
    }

    /**
     * Records a [RewriteEvent] for whoever is watching the reduction — a flash, a note, a particle.
     *
     * Defaults are chosen so `emit("spark")` is already meaningful: at the collision centre, with the pair's combined
     * energy as the magnitude. [kind] is free text that only your own code interprets.
     */
    fun emit(
        kind: String,
        magnitude: Double = ctx.pairEnergy,
        at: Vec2 = ctx.center,
        data: Map<String, Double> = emptyMap()
    ) {
        result.events += RewriteEvent(kind, Vec2(at), magnitude, LinkedHashMap(data))
    }

    /** The assembled [RewriteResult]. `RuleBook.rule` calls this for you. */
    fun build(): RewriteResult = result
}

/**
 * What a rule body produces: the replacement subgraph, how it attaches to the loose ends, which loose ends were joined
 * to each other, and any events emitted.
 *
 * Opaque by design — build one with [RewriteBuilder] or [AutoRuleBuilder] rather than by hand — and a *description* of a
 * rewrite rather than the rewrite itself. The [Reducer] is what applies it, which is what makes a rule replayable and
 * inspectable instead of a side effect.
 */
class RewriteResult internal constructor(
    internal val fragment: NetFragment = NetFragment(),
    internal val reconnections: MutableList<Pair<PortRef, Port>> = mutableListOf(),
    internal val passthroughs: MutableList<Pair<Port, Port>> = mutableListOf(),
    internal val events: MutableList<RewriteEvent> = mutableListOf()
)

/**
 * One law of motion: a name, the pair of types it fires on, and a function from the collision to its replacement.
 *
 * Declare these through [RuleBook.rule] or [RuleBook.autoRule] rather than constructing them, and collect them with
 * [rules]. The [name] is what appears in a [ReductionStep], so make it something you would want to read in a log.
 *
 * The [rewrite] lambda is called **each time** the rule fires, with that collision's [PairContext], so it can depend on
 * energy, geometry and velocity — a rule is a function of its situation, not a fixed template.
 */
class RewriteRule(
    /** What this law is called. Appears in every [ReductionStep] it produces, so make it readable. */
    val name: String,
    /** The pair of agent types this fires on, matched in either order. */
    val signature: PairSignature,
    /** Called afresh on **every** firing with that collision's context, so the replacement can depend on energy,
     * geometry and velocity rather than being a fixed template. */
    val rewrite: (PairContext) -> RewriteResult
)

/**
 * One end of a wire in the automatic-rewiring DSL: either a port of a node the rule is creating ([InternalEndpoint]) or
 * one of the loose ends the deleted pair left behind ([ExternalEndpoint]).
 *
 * The abstraction that lets [AutoRuleBuilder] take a single flat list of connections and work out the reconnections and
 * passthroughs itself, instead of making you classify each wire by hand as [RewriteBuilder] does.
 */
sealed interface AutoEndpoint

/** A port of a node this rule is creating. Produced by `principal(...)` / `aux(...)` in an [AutoRuleBuilder]. */
data class InternalEndpoint(val ref: PortRef) : AutoEndpoint

/**
 * A loose end of the *old* pair: auxiliary port [auxIndex] of pair node [nodeOrdinal] (0 = first, 1 = second).
 *
 * Written `ext(0, 1)` in a rule. It refers to whatever that port was attached to before the rewrite, so it may resolve
 * to nothing at all if the port was free.
 */
data class ExternalEndpoint(val nodeOrdinal: Int, val auxIndex: Int) : AutoEndpoint {
    init {
        require(nodeOrdinal == 0 || nodeOrdinal == 1) { "nodeOrdinal must be 0 or 1" }
        require(auxIndex >= 0) { "auxIndex must be >= 0" }
    }
}

/**
 * The receiver inside an `autoRule { }` body: declare the replacement nodes and then just say what connects to what,
 * mixing new ports and the old pair's loose ends freely.
 *
 * The declarative half of the rewriting story. Where [RewriteBuilder] makes you distinguish an internal wire from a
 * reconnection from a passthrough, here every connection is written the same way with `to` and [build] sorts them out
 * from the endpoint types. `ext(nodeOrdinal, auxIndex)` names a loose end of the pair; `principal(ref)` and
 * `aux(ref, i)` name ports of the nodes you are creating.
 *
 * Connections involving an `ext` whose port turned out to be **free are silently dropped**, so a rule written for a
 * fully wired pair degrades silently rather than throwing when it meets a partly wired one.
 */
class AutoRuleBuilder internal constructor(private val ctx: PairContext) {
    private val fragment = NetFragment()
    private val endpoints = mutableListOf<Pair<AutoEndpoint, AutoEndpoint>>()
    private val events = mutableListOf<RewriteEvent>()

    /**
     * Declares a replacement node and returns its [NodeRef].
     *
     * [at] is an offset from the collision centre and [impulse] a starting velocity; the [state] is copied. Same
     * semantics as `RewriteBuilder.node`.
     *
     * @throws IllegalArgumentException if [name] is already used in this rule
     */
    fun node(
        name: String,
        type: AgentType,
        at: Vec2 = Vec2(),
        state: NodeState = NodeState(),
        impulse: Vec2 = Vec2()
    ): NodeRef {
        val ref = NodeRef(name)
        // By name, not by ref: NodeRef is identity-keyed on purpose, so `ref !in map` compares a
        // freshly built instance against the map's keys and never matches — the duplicate check was
        // dead code, and a repeated name quietly produced two separate nodes.
        require(fragment.nodeSpecs.keys.none { it.name == name }) { "Duplicate rewrite node name: $name" }
        fragment.nodeSpecs[ref] = NodeSpec(type, type.name.lowercase(), at, state.copyDeep(), Vec2(impulse))
        return ref
    }

    /** The principal port of a node this rule creates, as an endpoint you can wire with `to`. */
    fun principal(ref: NodeRef): InternalEndpoint = InternalEndpoint(PortRef(ref, 0))

    /**
     * Auxiliary port [i] (from **0**) of a node this rule creates, as a wireable endpoint.
     *
     * @throws IllegalStateException if [ref] was not declared here
     * @throws IllegalArgumentException if [i] is out of range for the type
     */
    fun aux(ref: NodeRef, i: Int): InternalEndpoint {
        val spec = fragment.nodeSpecs[ref] ?: error("Unknown rewrite node: $ref")
        require(i in 0 until spec.type.auxPorts) { "Aux index $i out of range for ${spec.type.name}" }
        return InternalEndpoint(PortRef(ref, i + 1))
    }

    /**
     * One of the **old** pair's loose ends: auxiliary port [auxIndex] of pair node [nodeOrdinal] (0 = first, 1 = second).
     *
     * The heart of the DSL — `ext(...)` stands for whatever that port used to be attached to, so `principal(a) to
     * ext(0, 1)` means "hand the outside world's wire to my new node". Wiring one `ext` straight to another splices the
     * outside net together through the vanished pair.
     *
     * @throws IllegalArgumentException if [nodeOrdinal] is not 0 or 1, or [auxIndex] is negative
     */
    fun ext(nodeOrdinal: Int, auxIndex: Int): ExternalEndpoint = ExternalEndpoint(nodeOrdinal, auxIndex)

    /** Wires two ports of the replacement together. */
    infix fun InternalEndpoint.to(other: InternalEndpoint) {
        endpoints += (this as AutoEndpoint) to (other as AutoEndpoint)
    }

    /** Hands one of the old pair's loose ends to a port of the replacement. Ignored if that port was free. */
    infix fun InternalEndpoint.to(other: ExternalEndpoint) {
        endpoints += (this as AutoEndpoint) to (other as AutoEndpoint)
    }

    /** Hands one of the old pair's loose ends to a port of the replacement, written the other way round. */
    infix fun ExternalEndpoint.to(other: InternalEndpoint) {
        endpoints += (this as AutoEndpoint) to (other as AutoEndpoint)
    }

    /**
     * Splices two of the old pair's loose ends directly to each other — an erase or short-circuit rule.
     *
     * Silently does nothing unless **both** ports were actually attached to something.
     */
    infix fun ExternalEndpoint.to(other: ExternalEndpoint) {
        endpoints += (this as AutoEndpoint) to (other as AutoEndpoint)
    }

    /**
     * Records a [RewriteEvent] — at the collision centre and with the pair's energy, unless you say otherwise. Same as
     * `RewriteBuilder.emit`.
     */
    fun emit(
        kind: String,
        magnitude: Double = ctx.pairEnergy,
        at: Vec2 = ctx.center,
        data: Map<String, Double> = emptyMap()
    ) {
        events += RewriteEvent(kind, Vec2(at), magnitude, LinkedHashMap(data))
    }

    /**
     * Resolves every declared connection into the [RewriteResult] the reducer applies: internal wires stay internal,
     * anything touching an `ext` becomes a reconnection, and two `ext`s become a passthrough.
     *
     * Connections through a loose end that was **already free are dropped here**, which is where that quiet degradation
     * happens. `RuleBook.autoRule` calls this for you.
     */
    fun build(): RewriteResult {
        val out = RewriteResult(fragment = fragment, events = events.toMutableList())
        for ((a, b) in endpoints) {
            when {
                a is InternalEndpoint && b is InternalEndpoint -> out.fragment.pendingInternalConnections += a.ref to b.ref
                a is InternalEndpoint && b is ExternalEndpoint -> ctx.auxPeer(b.nodeOrdinal, b.auxIndex)?.let { out.reconnections += a.ref to it }
                a is ExternalEndpoint && b is InternalEndpoint -> ctx.auxPeer(a.nodeOrdinal, a.auxIndex)?.let { out.reconnections += b.ref to it }
                a is ExternalEndpoint && b is ExternalEndpoint -> {
                    val pa = ctx.auxPeer(a.nodeOrdinal, a.auxIndex)
                    val pb = ctx.auxPeer(b.nodeOrdinal, b.auxIndex)
                    if (pa != null && pb != null) out.passthroughs += pa to pb
                }
            }
        }
        return out
    }
}

/**
 * The table of rewrite laws a net runs under — declare rules into it with [rule] and [autoRule], and it answers "does
 * anything apply to these two nodes".
 *
 * Build one with [rules] and hand it to a [Reducer]. **Order matters**: [findMatch] returns the *first* rule whose
 * signature matches, so a general rule declared early shadows a specific one declared later.
 */
class RuleBook {
    private val rules = mutableListOf<RewriteRule>()

    /**
     * Declares a rule with the full-control [RewriteBuilder] body — you say what nodes appear and explicitly reconnect or
     * pass through each of the old pair's loose ends.
     *
     * Matching is unordered in the types, so `rule("beta", APP, LAM)` catches the collision either way round and the body
     * always sees the APP as node 0. Reach for [autoRule] unless you need the control.
     */
    fun rule(
        name: String,
        left: AgentType,
        right: AgentType,
        rewrite: RewriteBuilder.(PairContext) -> Unit
    ) {
        val sig = PairSignature(left, right)
        rules += RewriteRule(name, sig) { ctx ->
            RewriteBuilder(ctx).apply { rewrite(ctx) }.build()
        }
    }

    /**
     * Declares a rule with the declarative [AutoRuleBuilder] body — you list the connections and the builder works out
     * which are internal, which reattach the old pair's loose ends and which splice two of them together.
     *
     * The usual way to write a rule. Matching is unordered in the types, as with [rule].
     */
    fun autoRule(
        name: String,
        left: AgentType,
        right: AgentType,
        rewrite: AutoRuleBuilder.(PairContext) -> Unit
    ) {
        val sig = PairSignature(left, right)
        rules += RewriteRule(name, sig) { ctx ->
            AutoRuleBuilder(ctx).apply { rewrite(ctx) }.build()
        }
    }

    /** The first rule that fires on these two nodes, or null. [findMatch] when you also need the orientation. */
    fun find(a: Node, b: Node): RewriteRule? = findMatch(a, b)?.rule

    /**
     * The first rule matching these two nodes, together with the binding that puts them in the rule's own order.
     *
     * First, not best: declaration order is the priority order, and there is no check for two rules covering the same
     * pair of types — the later one simply never fires.
     */
    fun findMatch(a: Node, b: Node): RuleMatch? =
        rules.firstNotNullOfOrNull { rule ->
            rule.signature.bind(a, b)?.let { RuleMatch(rule, it) }
        }
}

/**
 * Builds a [RuleBook]: `rules { autoRule("beta", APP, LAM) { … } }`.
 *
 * Cheap and immutable-in-practice once built, so keep it in a `val` and reuse it across frames. Declaration order is
 * priority order.
 */
fun rules(block: RuleBook.() -> Unit): RuleBook = RuleBook().apply(block)

/**
 * The ready-made choices for *which* of several possible reactions fires next — reduction order as a dial.
 *
 * Not just an optimisation: in a net where several pairs could fire, this is the difference between a mechanical sweep
 * (`FIRST_FOUND`, `LEFTMOST_BY_ID`), a shimmer (`RANDOM`) and an avalanche that always takes the hottest spot first
 * (`HIGHEST_PAIR_ENERGY`) or lets it cool (`LOWEST_PAIR_ENERGY`). [PairSelectors.from] turns one of these into the
 * selector a [Reducer] uses; write your own [PairSelector] for anything else.
 */
enum class ActivationOrder {
    FIRST_FOUND,
    LEFTMOST_BY_ID,
    RIGHTMOST_BY_ID,
    RANDOM,
    HIGHEST_PAIR_ENERGY,
    LOWEST_PAIR_ENERGY
}

/**
 * One reaction that *could* happen this step: the active pair, the rule that matched it, and the fully built
 * [PairContext] the rule would be given.
 *
 * The context is computed before selection, not after, so a [PairSelector] can choose on energy, position or velocity —
 * that is what makes cursor-proximity and energy-biased scheduling possible.
 */
data class PairCandidate(val pair: ActivePair, val match: RuleMatch, val context: PairContext) {
    /** The rule that would fire. Shorthand for `match.rule`. */
    val rule: RewriteRule get() = match.rule
}

/**
 * The scheduler: given every reaction that could fire, pick one — or return null to stop the reduction.
 *
 * A `fun interface`, so a lambda will do: `PairSelector { _, c -> c.maxByOrNull { it.context.center.y } }`. Called once
 * per step with a freshly computed candidate list, and given the whole [Net] as well, so a selector may consult global
 * state. Returning null ends the run even though reactions remain.
 */
fun interface PairSelector {
    /**
     * Choose the reaction to fire, or null to stop.
     *
     * [candidates] **can be empty** — a net in normal form, or one where no rule matches, calls this with
     * nothing to choose from — so index into it only after checking. [PairSelectors.random] is the model.
     */
    fun select(net: Net, candidates: List<PairCandidate>): PairCandidate?
}

/** The stock [PairSelector]s — the schedulers [ActivationOrder] names, plus two that it does not. */
object PairSelectors {
    /** Takes whichever candidate came first out of the edge set. Cheapest, and the most mechanical-looking. */
    val firstFound = PairSelector { _, candidates -> candidates.firstOrNull() }

    /** Prefers the pair containing the alphabetically smallest node id — deterministic, and roughly oldest-first, since
     * ids are minted in creation order. */
    val leftmostById = PairSelector { _, candidates -> candidates.minByOrNull { minOf(it.context.first.id, it.context.second.id) } }

    /** Prefers the pair containing the alphabetically largest node id — deterministic, and roughly newest-first. */
    val rightmostById = PairSelector { _, candidates -> candidates.maxByOrNull { maxOf(it.context.first.id, it.context.second.id) } }

    /** Always fires the hottest pair. Tends to produce avalanches and to keep activity concentrated in one region. */
    val highestPairEnergy = PairSelector { _, candidates -> candidates.maxByOrNull { it.context.pairEnergy } }

    /** Always fires the coldest pair. Tends to spread activity out and to clear up dregs before hot spots. */
    val lowestPairEnergy = PairSelector { _, candidates -> candidates.minByOrNull { it.context.pairEnergy } }

    /**
     * Fires a uniformly random candidate — the shimmering, non-repeating schedule.
     *
     * Pass a seeded [Random] to make a run reproducible; the default shares `Random.Default` and so does not.
     */
    fun random(random: Random = Random.Default): PairSelector =
        PairSelector { _, candidates ->
            if (candidates.isEmpty()) null else candidates[random.nextInt(candidates.size)]
        }

    /**
     * Fires whichever reaction is closest to [point] — activity follows the cursor, or a moving attractor.
     *
     * The point is captured when the selector is built, so pass a fresh one each frame for something that tracks. Distance
     * is to the pair's centre in layout coordinates, which means it only behaves as expected on a relaxed net.
     */
    fun nearest(point: Vec2): PairSelector =
        PairSelector { _, candidates ->
            candidates.minByOrNull { Vec2(it.context.center).distance(point) }
        }

    /** The selector for an [ActivationOrder]. `RANDOM` yields an unseeded [random], so it is not reproducible. */
    fun from(order: ActivationOrder): PairSelector = when (order) {
        ActivationOrder.FIRST_FOUND -> firstFound
        ActivationOrder.LEFTMOST_BY_ID -> leftmostById
        ActivationOrder.RIGHTMOST_BY_ID -> rightmostById
        ActivationOrder.RANDOM -> random()
        ActivationOrder.HIGHEST_PAIR_ENERGY -> highestPairEnergy
        ActivationOrder.LOWEST_PAIR_ENERGY -> lowestPairEnergy
    }
}

/**
 * The engine: takes a [RuleBook] and a scheduler, and runs a net forward one reaction at a time.
 *
 * Each step finds every reaction the rules allow, lets the [PairSelector] pick one, deletes that pair, splices in the
 * replacement, and then **relaxes the layout by 8 iterations** — so a reduction is also an animation, and new nodes are
 * seeded at the collision's centre so they visibly emerge from it rather than appearing at the origin.
 *
 * Two side effects worth knowing, both in every step: every node's age is incremented, and the layout moves. And two
 * defensive skips: a passthrough is dropped when either end is already connected, and a reconnection when its
 * *outside* peer is — so a rule that double-books an outside port loses a wire with nothing to say so. The reconnection's own
 * new port is not checked, so a rule that wires a freshly built port internally and then reconnects it does
 * throw, out of [Net.connect].
 *
 * Use [step] or [stepDetailed] once per frame to watch it happen, [reduce] to run to normal form. There is a second
 * constructor taking one of the stock [ActivationOrder]s in place of a [PairSelector], for when you want a named
 * schedule rather than a lambda of your own.
 */
class Reducer(
    private val rules: RuleBook,
    private val selector: PairSelector = PairSelectors.firstFound,
    private val layout: LayoutConfig = LayoutConfig()
) {
    // No KDoc block of its own, deliberately: the completion parser's declaration matcher does not
    // recognise `constructor`, so a comment here is dropped — or, if the next declaration had no KDoc
    // of its own, would slide onto that instead. Described in the class KDoc above instead.
    constructor(
        rules: RuleBook,
        order: ActivationOrder,
        layout: LayoutConfig = LayoutConfig()
    ) : this(rules, PairSelectors.from(order), layout)

    /** Fires one reaction. False means nothing fired — the net is in normal form, or no rule covers what is left. */
    fun step(net: Net): Boolean = stepDetailed(net) != null

    /**
     * Fires one reaction and reports what happened, or null if nothing could fire.
     *
     * The per-frame call for a live sketch: the returned [ReductionStep] carries the rule name, the ids removed and
     * created and any emitted events, which is everything you need to flash the change or trigger sound from it.
     */
    fun stepDetailed(net: Net): ReductionStep? {
        val candidate = selectCandidate(net) ?: return null
        return apply(net, candidate)
    }

    /**
     * Reduces until nothing can fire or [maxSteps] have run, returning how many fired.
     *
     * **Not guaranteed to terminate** — a rule set that creates as much as it consumes will run forever, so leave
     * [maxSteps] finite unless you are sure. [onStep] is called after each reaction with the step number and the net, for
     * capturing frames. The net is mutated throughout; there is no snapshotting.
     */
    fun reduce(net: Net, maxSteps: Int = Int.MAX_VALUE, onStep: ((Int, Net) -> Unit)? = null): Int {
        // Runs to a fixpoint, so the total is unknown -> an indeterminate activity bar.
        val progress = afield.progressReporter("reduce")
        var steps = 0
        try {
            while (steps < maxSteps && stepDetailed(net) != null) {
                steps++
                onStep?.invoke(steps, net)
                progress.pulse()
            }
        } finally {
            progress.finish()
        }
        return steps
    }

    /**
     * [reduce], but [onStep] also receives the [ReductionStep] — so you can collect the events a whole run emitted
     * rather than just watching the net change.
     *
     * Same non-termination caveat: bound [maxSteps] unless the rule set provably shrinks the net.
     */
    fun reduceDetailed(
        net: Net,
        maxSteps: Int = Int.MAX_VALUE,
        onStep: ((Int, Net, ReductionStep) -> Unit)? = null
    ): Int {
        val progress = afield.progressReporter("reduce")
        var steps = 0
        try {
            while (steps < maxSteps) {
                val detail = stepDetailed(net) ?: break
                steps++
                onStep?.invoke(steps, net, detail)
                progress.pulse()
            }
        } finally {
            progress.finish()
        }
        return steps
    }

    private fun selectCandidate(net: Net): PairCandidate? =
        selector.select(net, activeCandidates(net))

    private fun activeCandidates(net: Net): List<PairCandidate> =
        net.activePairs().mapNotNull { pair ->
            rules.findMatch(pair.left, pair.right)?.let { match ->
                val context = contextFor(net, match.binding.first, match.binding.second)
                PairCandidate(pair, match, context)
            }
        }

    private fun contextFor(net: Net, first: Node, second: Node): PairContext {
        val pa = net.positionOf(first)
        val pb = net.positionOf(second)
        val delta = pb - pa
        // Test the *unfloored* length. Comparing the floored `max(length, 0.0001f)` against 1e-4f is the
        // same literal against itself, so the fallback was dead code and a coincident pair — every node in
        // a `net { }` built without positions starts at the origin — normalized a zero vector into
        // (NaN, NaN), which then reached the impulse, the velocities and the positions for good.
        val deltaLength = max(delta.length(), 0.0001f)
        val axis = if (delta.length() < 1e-4f) vec(1.0, 0.0) else Vec2(delta).normalize()
        val normal = vec(-axis.y, axis.x)
        return PairContext(
            first = first,
            second = second,
            firstAuxPeers = (0 until first.type.auxPorts).map { net.connected(first.aux(it)) },
            secondAuxPeers = (0 until second.type.auxPorts).map { net.connected(second.aux(it)) },
            center = (pa + pb) / 2.0f,
            radius = max((pb - pa).length().toDouble() / 2.0, 12.0),
            firstVelocity = net.velocityOf(first),
            secondVelocity = net.velocityOf(second),
            axis = axis,
            normal = normal
        )
    }

    private fun apply(net: Net, candidate: PairCandidate): ReductionStep {
        val pair = candidate.pair
        val out = candidate.rule.rewrite(candidate.context)

        net.incrementAges(1.0)
        net.remove(pair.left)
        net.remove(pair.right)

        for ((p, q) in out.passthroughs) {
            if (net.connected(p) == null && net.connected(q) == null) {
                net.connect(p, q)
            }
        }

        val embed = net.absorb(out.fragment, Placement(candidate.context.center, candidate.context.radius))

        for ((newPortRef, oldPeer) in out.reconnections) {
            val newPort = embed[newPortRef.node].ports[newPortRef.portIndex]
            if (net.connected(oldPeer) == null) {
                net.connect(newPort, oldPeer)
            }
        }

        net.relax(iterations = 8, config = layout)
        return ReductionStep(
            ruleName = candidate.rule.name,
            context = candidate.context,
            swapped = candidate.match.binding.swapped,
            removedNodeIds = listOf(pair.left.id, pair.right.id),
            createdNodeIds = embed.nodes.map { it.id },
            events = out.events.map { RewriteEvent(it.kind, Vec2(it.at), it.magnitude, LinkedHashMap(it.data)) }
        )
    }
}

// =========================
// Placement / helpers
// =========================

internal data class Placement(val base: Vec2, val radius: Double = 20.0)

/**
 * The counter behind every automatic node id: hands out `prefix_0`, `prefix_1`, … from **one shared counter**, so ids
 * are unique across prefixes and their numeric order is global creation order.
 *
 * Each [Net] owns one. Not thread-safe, and the counter only ever rises — ids of removed nodes are never reused, which
 * is what makes a [ReductionStep]'s removed ids unambiguous after the fact.
 */
class IdSource(private var counter: Int = 0) {
    /** The next id: `"${prefix}_$n"`, advancing the shared counter. */
    fun next(prefix: String): String = "${prefix}_${counter++}"

    /** The counter's current value without consuming it — how many ids have been handed out. Useful for logging. */
    fun peekCounter(): Int = counter
}

/** Where agent types come from. A one-function namespace, so `Types.agent("PULSE", 2)` reads as a declaration. */
object Types {
    /**
     * Declares an agent type with [auxPorts] auxiliary ports — plus the principal port every node has, so its arity is
     * one more than this.
     *
     * Keep the result in a `val`: rules are matched against these, and while equality is structural (name and port
     * count), reusing one value keeps a typo from silently creating a second type nothing has a rule for.
     */
    fun agent(name: String, auxPorts: Int): AgentType = AgentType(name, auxPorts)
}

/**
 * Writes the net as an SVG file at [path] — [Net.toSvg] straight to disk, on white.
 *
 * Overwrites without asking, and the path is resolved by the JVM's working directory rather than the workspace, so pass
 * an absolute one unless you know where that is. Any I/O failure propagates.
 */
fun Net.writeSvg(path: String, width: Int = 900, height: Int = 700) {
    java.io.File(path).writeText(toSvg(width, height))
}

/**
 * Draws the net into this box's output and puts its summary card beside it — the one call a live sketch needs per frame.
 *
 * Returns the [FLine]s it drew, so you can add to them or re-use them. Both outputs go to **named channels**, which means
 * calling this every frame replaces the previous picture rather than piling up; pass distinct [channel] names to show two
 * nets at once. `summaryChannel = null` suppresses the card.
 *
 * With [fitToSize] the drawing is scaled to the given box, so you do not have to care what coordinates the layout ended
 * up in — turn it off if you want the net's own scale, or a stable frame across steps.
 */
fun Box.showInteractionNet(
    net: Net,
    channel: String = "interaction-net",
    summaryChannel: String? = "$channel-summary",
    title: String = "Interaction net",
    w: Double = 920.0,
    h: Double = 560.0,
    fitToSize: Boolean = true
): List<FLine> {
    val rendered = net.toFLines()
    lines(rendered, channel = channel, w = w, h = h, fitToSize = fitToSize)
    summaryChannel?.let { display(it, net.summaryHtml(title = title)) }
    return rendered
}

/**
 * Displays several HTML cards side by side in one responsive grid in this box's output.
 *
 * The cards are strings you already have — `Net.summaryHtml`, `ReductionStep.summaryHtml` — so this is how you show
 * before-and-after, or several nets' statistics, in one row. Blank cards are dropped. One named channel, so repeated
 * calls replace rather than accumulate.
 */
fun Box.showInteractionSummary(
    channel: String = "interaction-net-summary",
    vararg cards: String
) {
    display(channel, interactionSummaryGrid(*cards))
}

/**
 * Displays a row of summary cards with one shared drawing underneath, as a single HTML block, and returns that HTML.
 *
 * The figure-making call — several nets' numbers above one comparison picture. The lines are rendered to inline SVG
 * rather than to the box's line output, so the whole thing is one self-contained value you could also write to a file.
 */
fun Box.showInteractionOverview(
    graphicLines: List<FLine>,
    cards: List<String>,
    channel: String = "interaction-net-overview",
    graphicTitle: String = "Comparison",
    graphicWidth: Double = 1040.0,
    graphicHeight: Double = 340.0
): String {
    val html = interactionOverviewHtml(
        cards = cards,
        graphicHtml = drawToSVG(graphicLines, graphicWidth, graphicHeight),
        graphicTitle = graphicTitle
    )
    display(channel, html)
    return html
}

/**
 * Wraps HTML cards in a responsive grid — columns of at least 260px, as many as fit — and returns the markup.
 *
 * The layout primitive behind [Box.showInteractionSummary], for when you want the string rather than the display. Blank
 * cards are dropped; the cards themselves are inserted **unescaped**, so only pass HTML you built.
 */
fun interactionSummaryGrid(vararg cards: String): String {
    val content = cards.filter { it.isNotBlank() }.joinToString("")
    return """
        <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px;align-items:start;">
            $content
        </div>
    """.trimIndent()
}

private fun interactionOverviewHtml(
    cards: List<String>,
    graphicHtml: String,
    graphicTitle: String
): String = """
    <div style="display:flex;flex-direction:column;gap:12px;">
        ${interactionSummaryGrid(*cards.toTypedArray())}
        <div style="padding:12px;background:linear-gradient(180deg,#0d1219,#090d12);border:1px solid #202b38;border-radius:10px;">
            <div style="font-family:monospace;font-size:12px;font-weight:700;letter-spacing:0.04em;text-transform:uppercase;color:#91a4ba;">${escapeInteractionHtml(graphicTitle)}</div>
            <div style="margin-top:10px;">$graphicHtml</div>
        </div>
    </div>
""".trimIndent()

/**
 * An HTML card describing one reaction: the rule that fired, whether the pair was matched swapped, which node ids went
 * and which arrived, and every event emitted.
 *
 * What to display next to the net while stepping by hand — it is the readable form of what `Reducer.stepDetailed`
 * returned. Rule names and event kinds are HTML-escaped, so free-text kinds are safe.
 */
fun ReductionStep.summaryHtml(title: String = "Reduction step"): String {
    val removed = if (removedNodeIds.isEmpty()) "none" else removedNodeIds.joinToString(", ")
    val created = if (createdNodeIds.isEmpty()) "none" else createdNodeIds.joinToString(", ")
    val eventsHtml = if (events.isEmpty()) {
        """<div style="margin-top:10px;color:#91a4ba;">No emitted rewrite events.</div>"""
    } else {
        events.joinToString(
            prefix = """<div style="margin-top:10px;display:flex;flex-direction:column;gap:6px;">""",
            postfix = "</div>"
        ) { event ->
            """
                <div style="padding:6px 8px;background:#101822;border:1px solid #1e2a39;border-radius:7px;color:#dce6f3;">
                    ${escapeInteractionHtml(event.kind)} @ ${fmt(event.magnitude)}
                </div>
            """.trimIndent()
        }
    }
    return interactionSummaryCard(
        title = title,
        body = """
            <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:8px;">
                ${interactionMetric("rule", escapeInteractionHtml(ruleName))}
                ${interactionMetric("swapped", if (swapped) "yes" else "no")}
                ${interactionMetric("pair energy", fmt(context.pairEnergy))}
            </div>
            <div style="margin-top:12px;display:flex;flex-direction:column;gap:8px;">
                <div style="padding:8px 10px;background:#101822;border:1px solid #1e2a39;border-radius:7px;color:#dce6f3;">
                    <strong style="color:#91a4ba;">removed</strong>: ${escapeInteractionHtml(removed)}
                </div>
                <div style="padding:8px 10px;background:#101822;border:1px solid #1e2a39;border-radius:7px;color:#dce6f3;">
                    <strong style="color:#91a4ba;">created</strong>: ${escapeInteractionHtml(created)}
                </div>
            </div>
            $eventsHtml
        """.trimIndent()
    )
}

private fun FLine.circle(cx: Double, cy: Double, r: Double, segments: Int = 24) {
    for (j in 0..segments) {
        val a = 2.0 * PI * j / segments
        val nx = cx + r * cos(a)
        val ny = cy + r * sin(a)
        if (j == 0) moveTo(nx, ny) else lineTo(nx, ny)
    }
    close()
}

private fun fmt(v: Number): String = String.format(Locale.US, "%.2f", v.toDouble())

private fun wrapUnit(v: Double): Double = ((v % 1.0) + 1.0) % 1.0

private fun escapeInteractionHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun interactionMetric(label: String, value: String): String = """
    <div style="padding:8px 10px;background:#101822;border:1px solid #1e2a39;border-radius:7px;">
        <div style="font-size:11px;letter-spacing:0.04em;text-transform:uppercase;color:#91a4ba;">${escapeInteractionHtml(label)}</div>
        <div style="margin-top:4px;font-size:15px;color:#f5f8fd;">$value</div>
    </div>
""".trimIndent()

private fun interactionSummaryCard(title: String, body: String): String = """
    <div style="font-family:monospace;background:linear-gradient(180deg,#111720,#0b0f15);color:#e7edf5;border:1px solid #223041;border-radius:10px;padding:14px 16px;">
        <div style="font-size:13px;font-weight:700;letter-spacing:0.04em;text-transform:uppercase;color:#dce6f3;">${escapeInteractionHtml(title)}</div>
        <div style="margin-top:10px;">$body</div>
    </div>
""".trimIndent()

// =========================
// Lambda-oriented helpers
// =========================

/**
 * Lambda calculus as an interaction net: the agent types, and rules for beta-reduction with
 * explicit copying and discarding.
 *
 * Conventions used here:
 * - APP(principal, fun, arg)
 * - LAM(principal, binder, body)
 * - DUP(principal, leftCopy, rightCopy)
 * - ERASE(principal)
 * - VAR/CONST/ROOT are nullary atoms
 *
 * The DUP/ERASE rules are written in a local interaction-net style:
 * - ERASE meeting a structured node recursively spawns more ERASEs for the substructure.
 * - DUP meeting a structured node recursively spawns more DUPs and reconstructs two copies.
 * - DUP meeting a nullary atom materializes two fresh copies of that atom.
 *
 * Enough for explicit resource handling and for experimenting with linear against nonlinear lambda
 * fragments; not the full Lafont/Lamping story.
 */
object Lambda {
    /**
     * Application. Its **principal** port is where the function arrives — that is what meets a [LAM] to
     * beta-reduce — `aux(1)` is the argument, and `aux(0)` is where the result comes back out (the beta rule
     * wires the lambda's body to it).
     */
    val APP = Types.agent("APP", 2)

    /** Abstraction. `aux(0)` is the binder (where the argument arrives), `aux(1)` the body. */
    val LAM = Types.agent("LAM", 2)

    /** Explicit duplication. `aux(0)` and `aux(1)` are the two copies; artistically, a branch or an echo. */
    val DUP = Types.agent("DUP", 2)

    /** Explicit deletion — a nullary sink. Artistically, a fade or a drain rather than an instant delete. */
    val ERASE = Types.agent("ERASE", 0)

    /** A free variable: a nullary atom with nothing but its principal port. */
    val VAR = Types.agent("VAR", 0)

    /** The root of a term — where the answer ends up. A nullary atom, distinguished only by its rules. */
    val ROOT = Types.agent("ROOT", 0)

    /** An opaque constant. A nullary atom that [DUP] copies and [ERASE] consumes. */
    val CONST = Types.agent("CONST", 0)

    private fun PairContext.structuralEcho(nodeOrdinal: Int, branch: Int, attenuation: Double = 0.9): NodeState =
        stateCopy(nodeOrdinal).split(
            parts = 2,
            branch = branch,
            attenuation = attenuation,
            hueSpread = 0.05,
            phaseSpread = 0.12,
            ageDelta = 1.0
        )

    private fun PairContext.operatorTrace(nodeOrdinal: Int, branch: Int, attenuation: Double = 0.82): NodeState =
        stateCopy(nodeOrdinal).split(
            parts = 2,
            branch = branch,
            attenuation = attenuation,
            hueSpread = 0.08,
            phaseSpread = 0.16,
            ageDelta = 1.0
        )

    private fun PairContext.decayOf(nodeOrdinal: Int, branch: Int): NodeState =
        stateCopy(nodeOrdinal).split(
            parts = 2,
            branch = branch,
            attenuation = 0.55,
            hueSpread = 0.02,
            phaseSpread = 0.04,
            ageDelta = 1.0
        ).evolved(energyBias = 0.05)

    /**
     * The rule book for this vocabulary: linear beta reduction, plus recursive [DUP] and [ERASE] against both atoms and
     * structure.
     *
     * A fresh book each call, so it is cheap to keep one in a `val` and cheap to build a variant from. Every rule also
     * emits an event — the whole vocabulary is `"beta"`, `"erase"`, `"prune"`, `"duplicate"`, `"branch"` and
     * `"annihilate"` — and splits or attenuates node energy as it goes, which is what
     * makes a reduction visibly decay rather than merely rewire — so the energies here are art direction, not semantics.
     *
     * Not a complete optimal reducer: there is no fan/fan commuting rule and no lambda frontend. Enough to watch beta
     * reduction happen, and to build your own vocabulary next to.
     */
    fun rules(): RuleBook = rules {
        // Linear beta: APP(fun,arg) ⟂ LAM(binder,body)
        autoRule("beta-linear", APP, LAM) { ctx ->
            ext(1, 1) to ext(0, 0) // lambda body becomes the result in the application's context
            ext(1, 0) to ext(0, 1) // binder is connected to the application argument
            emit(
                "beta",
                magnitude = ctx.pairEnergy,
                data = mapOf(
                    "energy" to ctx.pairEnergy,
                    "age" to ctx.blendedState().age
                )
            )
        }

        // ERASE against atoms: delete both sides and reconnect nothing.
        autoRule("erase-var", ERASE, VAR) { ctx ->
            emit("erase", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }
        autoRule("erase-const", ERASE, CONST) { ctx ->
            emit("erase", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }
        autoRule("erase-root", ERASE, ROOT) { ctx ->
            emit("erase", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }

        // ERASE against structure: recursively erase each auxiliary subtree.
        autoRule("erase-app", ERASE, APP) { ctx ->
            val eFun = node(
                "ef",
                ERASE,
                at = vec(-18.0, -16.0),
                state = ctx.decayOf(1, 0),
                impulse = ctx.branchImpulse(0)
            )
            val eArg = node(
                "ea",
                ERASE,
                at = vec(18.0, -16.0),
                state = ctx.decayOf(1, 1),
                impulse = ctx.branchImpulse(1)
            )
            principal(eFun) to ext(1, 0)
            principal(eArg) to ext(1, 1)
            emit("prune", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }
        autoRule("erase-lam", ERASE, LAM) { ctx ->
            val eBinder = node(
                "eb",
                ERASE,
                at = vec(-18.0, -16.0),
                state = ctx.decayOf(1, 0),
                impulse = ctx.branchImpulse(0)
            )
            val eBody = node(
                "ebody",
                ERASE,
                at = vec(18.0, -16.0),
                state = ctx.decayOf(1, 1),
                impulse = ctx.branchImpulse(1)
            )
            principal(eBinder) to ext(1, 0)
            principal(eBody) to ext(1, 1)
            emit("prune", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }
        autoRule("erase-dup", ERASE, DUP) { ctx ->
            val eLeft = node(
                "el",
                ERASE,
                at = vec(-18.0, -16.0),
                state = ctx.decayOf(1, 0),
                impulse = ctx.branchImpulse(0)
            )
            val eRight = node(
                "er",
                ERASE,
                at = vec(18.0, -16.0),
                state = ctx.decayOf(1, 1),
                impulse = ctx.branchImpulse(1)
            )
            principal(eLeft) to ext(1, 0)
            principal(eRight) to ext(1, 1)
            emit("prune", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }

        // DUP against atoms: materialize two copies, one for each duplicate demand.
        autoRule("dup-var", DUP, VAR) { ctx ->
            val v1 = node(
                "v1",
                VAR,
                at = vec(-22.0, 0.0),
                state = ctx.structuralEcho(1, 0),
                impulse = ctx.branchImpulse(0)
            )
            val v2 = node(
                "v2",
                VAR,
                at = vec(22.0, 0.0),
                state = ctx.structuralEcho(1, 1),
                impulse = ctx.branchImpulse(1)
            )
            principal(v1) to ext(0, 0)
            principal(v2) to ext(0, 1)
            emit("duplicate", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }
        autoRule("dup-const", DUP, CONST) { ctx ->
            val c1 = node(
                "c1",
                CONST,
                at = vec(-22.0, 0.0),
                state = ctx.structuralEcho(1, 0),
                impulse = ctx.branchImpulse(0)
            )
            val c2 = node(
                "c2",
                CONST,
                at = vec(22.0, 0.0),
                state = ctx.structuralEcho(1, 1),
                impulse = ctx.branchImpulse(1)
            )
            principal(c1) to ext(0, 0)
            principal(c2) to ext(0, 1)
            emit("duplicate", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }
        autoRule("dup-root", DUP, ROOT) { ctx ->
            val r1 = node(
                "r1",
                ROOT,
                at = vec(-22.0, 0.0),
                state = ctx.structuralEcho(1, 0),
                impulse = ctx.branchImpulse(0)
            )
            val r2 = node(
                "r2",
                ROOT,
                at = vec(22.0, 0.0),
                state = ctx.structuralEcho(1, 1),
                impulse = ctx.branchImpulse(1)
            )
            principal(r1) to ext(0, 0)
            principal(r2) to ext(0, 1)
            emit("duplicate", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }

        // DUP against structure: create two copies of the node and recursive DUPs for each field.
        autoRule("dup-app", DUP, APP) { ctx ->
            val appL = node(
                "appL",
                APP,
                at = vec(-34.0, 8.0),
                state = ctx.structuralEcho(1, 0),
                impulse = ctx.branchImpulse(0)
            )
            val appR = node(
                "appR",
                APP,
                at = vec(34.0, 8.0),
                state = ctx.structuralEcho(1, 1),
                impulse = ctx.branchImpulse(1)
            )
            val dFun = node(
                "dFun",
                DUP,
                at = vec(-22.0, -34.0),
                state = ctx.operatorTrace(0, 0),
                impulse = Vec2(ctx.axis).mul((-ctx.radius * 0.35).toFloat())
            )
            val dArg = node(
                "dArg",
                DUP,
                at = vec(22.0, -34.0),
                state = ctx.operatorTrace(0, 1),
                impulse = Vec2(ctx.axis).mul((ctx.radius * 0.35).toFloat())
            )

            principal(appL) to ext(0, 0)
            principal(appR) to ext(0, 1)

            principal(dFun) to ext(1, 0)
            principal(dArg) to ext(1, 1)

            aux(dFun, 0) to aux(appL, 0)
            aux(dFun, 1) to aux(appR, 0)
            aux(dArg, 0) to aux(appL, 1)
            aux(dArg, 1) to aux(appR, 1)
            emit("branch", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }

        autoRule("dup-lam", DUP, LAM) { ctx ->
            val lamL = node(
                "lamL",
                LAM,
                at = vec(-34.0, 8.0),
                state = ctx.structuralEcho(1, 0),
                impulse = ctx.branchImpulse(0)
            )
            val lamR = node(
                "lamR",
                LAM,
                at = vec(34.0, 8.0),
                state = ctx.structuralEcho(1, 1),
                impulse = ctx.branchImpulse(1)
            )
            val dBinder = node(
                "dBinder",
                DUP,
                at = vec(-22.0, -34.0),
                state = ctx.operatorTrace(0, 0),
                impulse = Vec2(ctx.axis).mul((-ctx.radius * 0.35).toFloat())
            )
            val dBody = node(
                "dBody",
                DUP,
                at = vec(22.0, -34.0),
                state = ctx.operatorTrace(0, 1),
                impulse = Vec2(ctx.axis).mul((ctx.radius * 0.35).toFloat())
            )

            principal(lamL) to ext(0, 0)
            principal(lamR) to ext(0, 1)

            principal(dBinder) to ext(1, 0)
            principal(dBody) to ext(1, 1)

            aux(dBinder, 0) to aux(lamL, 0)
            aux(dBinder, 1) to aux(lamR, 0)
            aux(dBody, 0) to aux(lamL, 1)
            aux(dBody, 1) to aux(lamR, 1)
            emit("branch", magnitude = ctx.state(1).energy, data = mapOf("energy" to ctx.state(1).energy))
        }

        // A modest custom rule example remains available as the escape hatch.
        rule("dup-erase-annihilate", DUP, ERASE) { ctx ->
            val e0 = node(
                "e0",
                ERASE,
                at = vec(-16.0, 0.0),
                state = ctx.decayOf(0, 0),
                impulse = ctx.branchImpulse(0)
            )
            val e1 = node(
                "e1",
                ERASE,
                at = vec(16.0, 0.0),
                state = ctx.decayOf(0, 1),
                impulse = ctx.branchImpulse(1)
            )
            reconnect(principal(e0), 0, 0)
            reconnect(principal(e1), 0, 1)
            emit("annihilate", magnitude = ctx.pairEnergy, data = mapOf("energy" to ctx.pairEnergy))
        }
    }

    /**
     * A minimal APP/LAM bridge demo.
     *
     * This is intentionally not a faithful lambda-term encoder. It is just the
     * smallest valid net that exercises the beta-style APP ⟂ LAM rewrite in this
     * simplified substrate.
     */
    fun betaBridgeDemo(): Net = net {
        val root = node(ROOT, id = "root", at = vec(-180.0, 0.0), state = NodeState(energy = 0.8, hue = 0.02))
        val app = node(APP, id = "app", at = vec(-60.0, 0.0), state = NodeState(energy = 1.15, phase = 0.1, hue = -0.03))
        val lam = node(LAM, id = "lam", at = vec(60.0, 0.0), state = NodeState(energy = 1.05, phase = 0.3, hue = 0.08))
        val binder = node(VAR, id = "binder", at = vec(180.0, -70.0), state = NodeState(energy = 0.7, phase = 0.45, hue = 0.12))
        val body = node(VAR, id = "body", at = vec(180.0, 0.0), state = NodeState(energy = 0.95, phase = 0.55, hue = 0.16))
        val constant = node(CONST, id = "c", at = vec(180.0, 80.0), state = NodeState(energy = 1.3, phase = 0.75, hue = 0.18))

        app.principal to lam.principal
        app.aux(0) to root.principal
        app.aux(1) to constant.principal
        lam.aux(0) to binder.principal
        lam.aux(1) to body.principal
    }

    /**
     * A tiny nonlinear sketch: a single [CONST] is demanded by two roots through a [DUP], so the rewrite
     * system has to copy it. No [LAM] and no body — [betaBridgeDemo] is the one with those.
     * This is not a full term encoder, but it gives the rewrite system something resource-sensitive to do.
     */
    fun duplicatedConstantDemo(): Net = net {
        val rootL = node(ROOT, id = "rootL", at = vec(-220.0, -40.0), state = NodeState(energy = 0.8, hue = -0.02))
        val rootR = node(ROOT, id = "rootR", at = vec(-220.0, 80.0), state = NodeState(energy = 0.8, hue = 0.02))
        val dup = node(DUP, id = "dup", at = vec(-90.0, 20.0), state = NodeState(energy = 1.4, phase = 0.2, hue = 0.0))
        val constant = node(CONST, id = "k", at = vec(60.0, 20.0), state = NodeState(energy = 1.8, phase = 0.6, hue = 0.14))

        dup.principal to constant.principal
        dup.aux(0) to rootL.principal
        dup.aux(1) to rootR.principal
    }

    /** Erase an application tree to demonstrate explicit garbage collection. */
    fun eraseApplicationDemo(): Net = net {
        val er = node(ERASE, id = "erase", at = vec(-140.0, 0.0), state = NodeState(energy = 0.9, hue = -0.05))
        val app = node(APP, id = "app", at = vec(0.0, 0.0), state = NodeState(energy = 1.25, phase = 0.1, hue = 0.04))
        val f = node(CONST, id = "f", at = vec(150.0, -60.0), state = NodeState(energy = 1.1, phase = 0.5, hue = 0.2))
        val x = node(VAR, id = "x", at = vec(150.0, 60.0), state = NodeState(energy = 0.75, phase = 0.35, hue = -0.08))

        er.principal to app.principal
        app.aux(0) to f.principal
        app.aux(1) to x.principal
    }
}

// =========================
// Example usage
// =========================

/**
 * A worked example of the whole pipeline, as a runnable `main`: build each [Lambda] demo net, relax it, reduce it and
 * write before/after SVGs.
 *
 * Read it rather than run it — the SVG paths are hard-coded to `/mnt/data/`, which does not exist on a developer
 * machine, so it throws partway through. It is here as the shortest end-to-end illustration of rules → reducer →
 * layout → export.
 */
fun main() {
    val lambdaRules = Lambda.rules()
    val reducer = Reducer(lambdaRules, order = ActivationOrder.HIGHEST_PAIR_ENERGY)

    val linear = Lambda.betaBridgeDemo()
    linear.relax(20)
    linear.writeSvg("/mnt/data/lambda_linear_before.svg")
    println(reducer.stepDetailed(linear))
    linear.relax(60)
    linear.writeSvg("/mnt/data/lambda_linear_after.svg")

    val dupDemo = Lambda.duplicatedConstantDemo()
    dupDemo.relax(20)
    dupDemo.writeSvg("/mnt/data/lambda_dup_before.svg")
    println(reducer.stepDetailed(dupDemo))
    dupDemo.relax(60)
    dupDemo.writeSvg("/mnt/data/lambda_dup_after.svg")

    val eraseDemo = Lambda.eraseApplicationDemo()
    eraseDemo.relax(20)
    eraseDemo.writeSvg("/mnt/data/lambda_erase_before.svg")
    reducer.reduceDetailed(eraseDemo, maxSteps = 4) { step, _, detail -> println("step=$step detail=$detail") }
    eraseDemo.relax(60)
    eraseDemo.writeSvg("/mnt/data/lambda_erase_after.svg")

    println("Wrote demo SVGs to /mnt/data")
}
