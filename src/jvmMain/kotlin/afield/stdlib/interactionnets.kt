@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.stdlib

import org.joml.minus
import org.joml.plus
import kotlin.math.max
import kotlin.random.Random

/**
 * InteractionNet.kt
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
 * - SVG export for quick inspection
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


data class LayoutConfig(
    val repulsion: Double = 2500.0,
    val springLength: Double = 90.0,
    val springStrength: Double = 0.015,
    val damping: Double = 0.82,
    val timeStep: Double = 1.0,
    val jitter: Double = 8.0,
    val clampStep: Double = 20.0
)

internal data class Motion(var velocity: Vec2 = Vec2())

// =========================
// Net data model
// =========================

enum class PortKind { PRINCIPAL, AUXILIARY }

data class AgentType(val name: String, val auxPorts: Int) {
    val arity: Int get() = auxPorts + 1
}

class Port internal constructor(
    val node: Node,
    val index: Int,
    val kind: PortKind
) {
    val id: String get() = "${node.id}:$index"
    override fun toString(): String = id
}

class Node internal constructor(
    val id: String,
    val type: AgentType
) {
    val ports: List<Port> = buildList {
        add(Port(this@Node, 0, PortKind.PRINCIPAL))
        repeat(type.auxPorts) { i ->
            add(Port(this@Node, i + 1, PortKind.AUXILIARY))
        }
    }

    val principal: Port get() = ports[0]

    fun aux(i: Int): Port {
        require(i in 0 until type.auxPorts) { "Aux port $i out of range for ${type.name}" }
        return ports[i + 1]
    }

    override fun toString(): String = "$id:${type.name}"
}

data class Edge(val a: Port, val b: Port) {
    fun other(p: Port): Port = when (p) {
        a -> b
        b -> a
        else -> error("Port $p not on edge $this")
    }
}

data class ActivePair(val left: Node, val right: Node) {
    init {
        require(left != right)
    }

    fun canonicalKey(): Pair<String, String> =
        if (left.id <= right.id) left.id to right.id else right.id to left.id
}

class Net internal constructor(
    internal val ids: IdSource = IdSource()
) {
    private val _nodes = LinkedHashMap<String, Node>()
    private val _edges = LinkedHashSet<Edge>()
    private val adjacency = LinkedHashMap<String, Edge>()
    private val positions = LinkedHashMap<String, Vec2>()
    private val motions = LinkedHashMap<String, Motion>()

    val nodes: Collection<Node> get() = _nodes.values
    val edges: Collection<Edge> get() = _edges

    fun node(id: String): Node = _nodes[id] ?: error("Unknown node: $id")
    fun positionOf(node: Node): Vec2 = positions[node.id] ?: Vec2()

    fun setPosition(node: Node, p: Vec2) {
        positions[node.id] = p
    }

    fun create(type: AgentType, id: String = ids.next(type.name.lowercase())): Node {
        require(id !in _nodes) { "Duplicate node id: $id" }
        val node = Node(id, type)
        _nodes[id] = node
        positions[id] = positions[id] ?: vec(Random.nextDouble(-30.0, 30.0), Random.nextDouble(-30.0, 30.0))
        motions[id] = motions[id] ?: Motion()
        return node
    }

    fun remove(node: Node) {
        val incident = node.ports.mapNotNull { adjacency[it.id] }.distinct()
        incident.forEach { disconnect(it.a, it.b) }
        _nodes.remove(node.id)
        positions.remove(node.id)
        motions.remove(node.id)
    }

    fun connected(port: Port): Port? = adjacency[port.id]?.other(port)

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

    fun disconnect(a: Port, b: Port) {
        val edge = adjacency[a.id] ?: return
        require(edge.other(a) == b) { "Ports $a and $b are not directly connected" }
        _edges.remove(edge)
        adjacency.remove(a.id)
        adjacency.remove(b.id)
    }

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
            val created = create(spec.type, ids.next(spec.suggestedPrefix))
            setPosition(created, placement.base + spec.offset)
            motions[created.id]?.velocity = Vec2()
            mapping[ref] = created
        }
        for ((a, b) in fragment.pendingInternalConnections) {
            connect(mapping[a.node]!!.ports[a.portIndex], mapping[b.node]!!.ports[b.portIndex])
        }
        return FragmentEmbed(mapping)
    }

    fun relax(iterations: Int = 1, config: LayoutConfig = LayoutConfig()) {
        repeat(iterations) { stepLayout(config) }
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

    fun toSvg(width: Int = 900, height: Int = 700, padding: Double = 40.0): String {
        if (nodes.isEmpty()) {
            return """
                <svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">
                  <rect width="100%" height="100%" fill="white"/>
                </svg>
            """.trimIndent()
        }

        val xs = nodes.map { positionOf(it).x }
        val ys = nodes.map { positionOf(it).y }
        val minX = xs.minOrNull()!!
        val maxX = xs.maxOrNull()!!
        val minY = ys.minOrNull()!!
        val maxY = ys.maxOrNull()!!
        val spanX = max(maxX - minX, 1.0f)
        val spanY = max(maxY - minY, 1.0f)
        val scale = minOf((width - 2 * padding) / spanX, (height - 2 * padding) / spanY)

        fun map(p: Vec2): Vec2 = vec(
            padding + (p.x - minX) * scale,
            padding + (p.y - minY) * scale
        )

        val sb = StringBuilder()
        sb.appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">")
        sb.appendLine("  <rect width=\"100%\" height=\"100%\" fill=\"white\"/>")
        sb.appendLine("  <g stroke=\"#444\" stroke-width=\"2\" fill=\"none\">")
        for (e in edges) {
            val a = map(positionOf(e.a.node))
            val b = map(positionOf(e.b.node))
            sb.appendLine("    <line x1=\"${fmt(a.x)}\" y1=\"${fmt(a.y)}\" x2=\"${fmt(b.x)}\" y2=\"${fmt(b.y)}\"/>")
        }
        sb.appendLine("  </g>")
        sb.appendLine("  <g font-family=\"monospace\" font-size=\"12\" text-anchor=\"middle\">")
        for (n in nodes) {
            val p = map(positionOf(n))
            val r = 21
            val fill = when (n.type.name) {
                "APP" -> "#eef7ff"
                "LAM" -> "#f6f0ff"
                "DUP" -> "#fff3e8"
                "ERASE" -> "#fff0f0"
                else -> "#f8fbff"
            }
            sb.appendLine("    <circle cx=\"${fmt(p.x)}\" cy=\"${fmt(p.y)}\" r=\"$r\" fill=\"$fill\" stroke=\"#1a1a1a\" stroke-width=\"2\"/>")
            sb.appendLine("    <circle cx=\"${fmt(p.x)}\" cy=\"${fmt(p.y - 14)}\" r=\"4\" fill=\"#1a1a1a\"/>")
            sb.appendLine("    <text x=\"${fmt(p.x)}\" y=\"${fmt(p.y + 4)}\">${escapeXml(n.type.name)}</text>")
            sb.appendLine("    <text x=\"${fmt(p.x)}\" y=\"${fmt(p.y + 36)}\" fill=\"#666\">${escapeXml(n.id)}</text>")
        }
        sb.appendLine("  </g>")
        sb.appendLine("</svg>")
        return sb.toString()
    }

    private fun fmt(v: Number): String = "%.2f".format(v)

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

// =========================
// DSL for fragments
// =========================

class NodeRef internal constructor(val name: String) {
    override fun toString(): String = name
}

data class PortRef internal constructor(val node: NodeRef, val portIndex: Int)
internal data class NodeSpec(val type: AgentType, val suggestedPrefix: String, val offset: Vec2)

class NetFragment internal constructor(
    internal val nodeSpecs: LinkedHashMap<NodeRef, NodeSpec> = LinkedHashMap(),
    internal val pendingInternalConnections: MutableList<Pair<PortRef, PortRef>> = mutableListOf(),
    internal val publishedPorts: LinkedHashMap<String, PortRef> = LinkedHashMap()
) {
    fun export(name: String, port: PortRef) {
        publishedPorts[name] = port
    }
}

class FragmentBuilder {
    private val fragment = NetFragment()

    fun node(name: String, type: AgentType, at: Vec2 = Vec2()): NodeRef {
        val ref = NodeRef(name)
        require(ref !in fragment.nodeSpecs) { "Duplicate fragment node name: $name" }
        fragment.nodeSpecs[ref] = NodeSpec(type, type.name.lowercase(), at)
        return ref
    }

    fun principal(ref: NodeRef): PortRef = PortRef(ref, 0)

    fun aux(ref: NodeRef, i: Int): PortRef {
        val spec = fragment.nodeSpecs[ref] ?: error("Unknown fragment node: $ref")
        require(i in 0 until spec.type.auxPorts) { "Aux index $i out of range for ${spec.type.name}" }
        return PortRef(ref, i + 1)
    }

    infix fun PortRef.to(other: PortRef) {
        fragment.pendingInternalConnections.add(Pair(this, other))
    }

    fun expose(name: String, port: PortRef) {
        fragment.export(name, port)
    }

    fun build(): NetFragment = fragment
}

fun fragment(block: FragmentBuilder.() -> Unit): NetFragment = FragmentBuilder().apply(block).build()

class FragmentEmbed internal constructor(private val nodeMap: Map<NodeRef, Node>) {
    operator fun get(ref: NodeRef): Node = nodeMap[ref] ?: error("Unknown embedded ref: $ref")
}

// =========================
// Builder DSL for full nets
// =========================

class NetBuilder(private val net: Net) {
    fun node(type: AgentType, id: String? = null, at: Vec2 = Vec2()): Node {
        val n = net.create(type, id ?: net.ids.next(type.name.lowercase()))
        net.setPosition(n, at)
        return n
    }

    infix fun Port.to(other: Port) = net.connect(this, other)
    fun connect(a: Port, b: Port) = net.connect(a, b)

    fun embed(fragment: NetFragment, at: Vec2 = Vec2()): EmbeddedPorts {
        val embed = net.absorb(fragment, Placement(at))
        val exported = fragment.publishedPorts.mapValues { (_, p) -> embed[p.node].ports[p.portIndex] }
        return EmbeddedPorts(exported)
    }
}

class EmbeddedPorts internal constructor(private val exported: Map<String, Port>) {
    operator fun get(name: String): Port = exported[name] ?: error("No exported port named $name")
}

fun net(block: NetBuilder.() -> Unit): Net {
    val n = Net()
    NetBuilder(n).apply(block)
    return n
}

// =========================
// Rewriting
// =========================

data class PairSignature(val left: AgentType, val right: AgentType) {
    fun matches(a: Node, b: Node): Boolean =
        (a.type == left && b.type == right) || (a.type == right && b.type == left)
}

data class PairContext(
    val first: Node,
    val second: Node,
    val firstAuxPeers: List<Port?>,
    val secondAuxPeers: List<Port?>,
    val center: Vec2,
    val radius: Double
) {
    fun auxPeer(nodeOrdinal: Int, auxIndex: Int): Port? = when (nodeOrdinal) {
        0 -> firstAuxPeers.getOrNull(auxIndex)
        1 -> secondAuxPeers.getOrNull(auxIndex)
        else -> error("nodeOrdinal must be 0 or 1")
    }
}

class RewriteBuilder internal constructor(private val ctx: PairContext) {
    private val result = RewriteResult()

    fun node(name: String, type: AgentType, at: Vec2 = Vec2()): NodeRef {
        val ref = NodeRef(name)
        require(ref !in result.fragment.nodeSpecs) { "Duplicate rewrite node name: $name" }
        result.fragment.nodeSpecs[ref] = NodeSpec(type, type.name.lowercase(), at)
        return ref
    }

    fun principal(ref: NodeRef): PortRef = PortRef(ref, 0)

    fun aux(ref: NodeRef, i: Int): PortRef {
        val spec = result.fragment.nodeSpecs[ref] ?: error("Unknown rewrite node: $ref")
        require(i in 0 until spec.type.auxPorts)
        return PortRef(ref, i + 1)
    }

    infix fun PortRef.to(other: PortRef) {
        result.fragment.pendingInternalConnections.add(Pair(this, other))
    }

    fun reconnect(newPort: PortRef, oldPeer: Port?) {
        if (oldPeer != null) result.reconnections += newPort to oldPeer
    }

    fun reconnect(newPort: PortRef, nodeOrdinal: Int, auxIndex: Int) {
        reconnect(newPort, ctx.auxPeer(nodeOrdinal, auxIndex))
    }

    fun passthrough(a: Port?, b: Port?) {
        if (a != null && b != null) result.passthroughs += a to b
    }

    fun build(): RewriteResult = result
}

class RewriteResult internal constructor(
    internal val fragment: NetFragment = NetFragment(),
    internal val reconnections: MutableList<Pair<PortRef, Port>> = mutableListOf(),
    internal val passthroughs: MutableList<Pair<Port, Port>> = mutableListOf()
)

class RewriteRule(
    val name: String,
    val signature: PairSignature,
    val rewrite: (PairContext) -> RewriteResult
)

sealed interface AutoEndpoint

data class InternalEndpoint(val ref: PortRef) : AutoEndpoint

data class ExternalEndpoint(val nodeOrdinal: Int, val auxIndex: Int) : AutoEndpoint {
    init {
        require(nodeOrdinal == 0 || nodeOrdinal == 1) { "nodeOrdinal must be 0 or 1" }
        require(auxIndex >= 0) { "auxIndex must be >= 0" }
    }
}

class AutoRuleBuilder internal constructor(private val ctx: PairContext) {
    private val fragment = NetFragment()
    private val endpoints = mutableListOf<Pair<AutoEndpoint, AutoEndpoint>>()

    fun node(name: String, type: AgentType, at: Vec2 = Vec2()): NodeRef {
        val ref = NodeRef(name)
        require(ref !in fragment.nodeSpecs) { "Duplicate rewrite node name: $name" }
        fragment.nodeSpecs[ref] = NodeSpec(type, type.name.lowercase(), at)
        return ref
    }

    fun principal(ref: NodeRef): InternalEndpoint = InternalEndpoint(PortRef(ref, 0))

    fun aux(ref: NodeRef, i: Int): InternalEndpoint {
        val spec = fragment.nodeSpecs[ref] ?: error("Unknown rewrite node: $ref")
        require(i in 0 until spec.type.auxPorts) { "Aux index $i out of range for ${spec.type.name}" }
        return InternalEndpoint(PortRef(ref, i + 1))
    }

    fun ext(nodeOrdinal: Int, auxIndex: Int): ExternalEndpoint = ExternalEndpoint(nodeOrdinal, auxIndex)

    infix fun InternalEndpoint.to(other: InternalEndpoint) {
        endpoints += (this as AutoEndpoint) to (other as AutoEndpoint)
    }

    infix fun InternalEndpoint.to(other: ExternalEndpoint) {
        endpoints += (this as AutoEndpoint) to (other as AutoEndpoint)
    }

    infix fun ExternalEndpoint.to(other: InternalEndpoint) {
        endpoints += (this as AutoEndpoint) to (other as AutoEndpoint)
    }

    infix fun ExternalEndpoint.to(other: ExternalEndpoint) {
        endpoints += (this as AutoEndpoint) to (other as AutoEndpoint)
    }

    fun build(): RewriteResult {
        val out = RewriteResult(fragment = fragment)
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

class RuleBook {
    private val rules = mutableListOf<RewriteRule>()

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

    fun find(a: Node, b: Node): RewriteRule? = rules.firstOrNull { it.signature.matches(a, b) }
}

fun rules(block: RuleBook.() -> Unit): RuleBook = RuleBook().apply(block)

enum class ActivationOrder {
    FIRST_FOUND,
    LEFTMOST_BY_ID,
    RIGHTMOST_BY_ID,
    RANDOM
}

class Reducer(
    private val rules: RuleBook,
    private val order: ActivationOrder = ActivationOrder.FIRST_FOUND,
    private val layout: LayoutConfig = LayoutConfig()
) {
    fun step(net: Net): Boolean {
        val pair = selectPair(net) ?: return false
        val rule = rules.find(pair.left, pair.right) ?: return false
        apply(net, pair, rule)
        return true
    }

    fun reduce(net: Net, maxSteps: Int = Int.MAX_VALUE, onStep: ((Int, Net) -> Unit)? = null): Int {
        var steps = 0
        while (steps < maxSteps && step(net)) {
            steps++
            onStep?.invoke(steps, net)
        }
        return steps
    }

    private fun selectPair(net: Net): ActivePair? {
        val pairs = net.activePairs().filter { rules.find(it.left, it.right) != null }
        if (pairs.isEmpty()) return null
        return when (order) {
            ActivationOrder.FIRST_FOUND -> pairs.first()
            ActivationOrder.LEFTMOST_BY_ID -> pairs.minBy { minOf(it.left.id, it.right.id) }
            ActivationOrder.RIGHTMOST_BY_ID -> pairs.maxBy { maxOf(it.left.id, it.right.id) }
            ActivationOrder.RANDOM -> pairs.random()
        }
    }

    private fun apply(net: Net, pair: ActivePair, rule: RewriteRule) {
        val (a, b) = pair
        val pa = net.positionOf(a)
        val pb = net.positionOf(b)
        val center = (pa + pb) / 2.0f
        val radius = max((pb - pa).length().toDouble() / 2.0, 12.0)

        val context = PairContext(
            first = a,
            second = b,
            firstAuxPeers = (0 until a.type.auxPorts).map { net.connected(a.aux(it)) },
            secondAuxPeers = (0 until b.type.auxPorts).map { net.connected(b.aux(it)) },
            center = center,
            radius = radius
        )
        val out = rule.rewrite(context)

        net.remove(a)
        net.remove(b)

        for ((p, q) in out.passthroughs) {
            if (net.connected(p) == null && net.connected(q) == null) {
                net.connect(p, q)
            }
        }

        val embed = net.absorb(out.fragment, Placement(center, radius))

        for ((newPortRef, oldPeer) in out.reconnections) {
            val newPort = embed[newPortRef.node].ports[newPortRef.portIndex]
            if (net.connected(oldPeer) == null) {
                net.connect(newPort, oldPeer)
            }
        }

        net.relax(iterations = 8, config = layout)
    }
}

// =========================
// Placement / helpers
// =========================

internal data class Placement(val base: Vec2, val radius: Double = 20.0)

class IdSource(private var counter: Int = 0) {
    fun next(prefix: String): String = "${prefix}_${counter++}"
    fun peekCounter(): Int = counter
}

object Types {
    fun agent(name: String, auxPorts: Int): AgentType = AgentType(name, auxPorts)
}

fun Net.writeSvg(path: String, width: Int = 900, height: Int = 700) {
    java.io.File(path).writeText(toSvg(width, height))
}

// =========================
// Lambda-oriented helpers
// =========================

/**
 * This bundle is intentionally pragmatic rather than dogmatic.
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
 * This is already enough to demonstrate explicit resource handling and to support experimentation
 * with linear vs nonlinear lambda fragments, although it is not yet the full Lafont/Lamping story.
 */
object Lambda {
    val APP = Types.agent("APP", 2)
    val LAM = Types.agent("LAM", 2)
    val DUP = Types.agent("DUP", 2)
    val ERASE = Types.agent("ERASE", 0)
    val VAR = Types.agent("VAR", 0)
    val ROOT = Types.agent("ROOT", 0)
    val CONST = Types.agent("CONST", 0)

    fun rules(): RuleBook = rules {
        // Linear beta: APP(fun,arg) ⟂ LAM(binder,body)
        autoRule("beta-linear", APP, LAM) {
            ext(1, 1) to ext(0, 0) // lambda body becomes the result in the application's context
            ext(1, 0) to ext(0, 1) // binder is connected to the application argument
        }

        // ERASE against atoms: delete both sides and reconnect nothing.
        autoRule("erase-var", ERASE, VAR) {}
        autoRule("erase-const", ERASE, CONST) {}
        autoRule("erase-root", ERASE, ROOT) {}

        // ERASE against structure: recursively erase each auxiliary subtree.
        autoRule("erase-app", ERASE, APP) {
            val eFun = node("ef", ERASE, at = vec(-18.0, -16.0))
            val eArg = node("ea", ERASE, at = vec(18.0, -16.0))
            principal(eFun) to ext(1, 0)
            principal(eArg) to ext(1, 1)
        }
        autoRule("erase-lam", ERASE, LAM) {
            val eBinder = node("eb", ERASE, at = vec(-18.0, -16.0))
            val eBody = node("ebody", ERASE, at = vec(18.0, -16.0))
            principal(eBinder) to ext(1, 0)
            principal(eBody) to ext(1, 1)
        }
        autoRule("erase-dup", ERASE, DUP) {
            val eLeft = node("el", ERASE, at = vec(-18.0, -16.0))
            val eRight = node("er", ERASE, at = vec(18.0, -16.0))
            principal(eLeft) to ext(1, 0)
            principal(eRight) to ext(1, 1)
        }

        // DUP against atoms: materialize two copies, one for each duplicate demand.
        autoRule("dup-var", DUP, VAR) {
            val v1 = node("v1", VAR, at = vec(-22.0, 0.0))
            val v2 = node("v2", VAR, at = vec(22.0, 0.0))
            principal(v1) to ext(0, 0)
            principal(v2) to ext(0, 1)
        }
        autoRule("dup-const", DUP, CONST) {
            val c1 = node("c1", CONST, at = vec(-22.0, 0.0))
            val c2 = node("c2", CONST, at = vec(22.0, 0.0))
            principal(c1) to ext(0, 0)
            principal(c2) to ext(0, 1)
        }
        autoRule("dup-root", DUP, ROOT) {
            val r1 = node("r1", ROOT, at = vec(-22.0, 0.0))
            val r2 = node("r2", ROOT, at = vec(22.0, 0.0))
            principal(r1) to ext(0, 0)
            principal(r2) to ext(0, 1)
        }

        // DUP against structure: create two copies of the node and recursive DUPs for each field.
        autoRule("dup-app", DUP, APP) {
            val appL = node("appL", APP, at = vec(-34.0, 8.0))
            val appR = node("appR", APP, at = vec(34.0, 8.0))
            val dFun = node("dFun", DUP, at = vec(-22.0, -34.0))
            val dArg = node("dArg", DUP, at = vec(22.0, -34.0))

            principal(appL) to ext(0, 0)
            principal(appR) to ext(0, 1)

            principal(dFun) to ext(1, 0)
            principal(dArg) to ext(1, 1)

            aux(dFun, 0) to aux(appL, 0)
            aux(dFun, 1) to aux(appR, 0)
            aux(dArg, 0) to aux(appL, 1)
            aux(dArg, 1) to aux(appR, 1)
        }

        autoRule("dup-lam", DUP, LAM) {
            val lamL = node("lamL", LAM, at = vec(-34.0, 8.0))
            val lamR = node("lamR", LAM, at = vec(34.0, 8.0))
            val dBinder = node("dBinder", DUP, at = vec(-22.0, -34.0))
            val dBody = node("dBody", DUP, at = vec(22.0, -34.0))

            principal(lamL) to ext(0, 0)
            principal(lamR) to ext(0, 1)

            principal(dBinder) to ext(1, 0)
            principal(dBody) to ext(1, 1)

            aux(dBinder, 0) to aux(lamL, 0)
            aux(dBinder, 1) to aux(lamR, 0)
            aux(dBody, 0) to aux(lamL, 1)
            aux(dBody, 1) to aux(lamR, 1)
        }

        // A modest custom rule example remains available as the escape hatch.
        rule("dup-erase-annihilate", DUP, ERASE) { _ ->
            val e0 = node("e0", ERASE, at = vec(-16.0, 0.0))
            val e1 = node("e1", ERASE, at = vec(16.0, 0.0))
            reconnect(principal(e0), 0, 0)
            reconnect(principal(e1), 0, 1)
        }
    }

    /** ((λx. x) c) in the linear fragment. */
    fun identityAppliedToConstant(): Net = net {
        val root = node(ROOT, id = "root", at = vec(-180.0, 0.0))
        val app = node(APP, id = "app", at = vec(-60.0, 0.0))
        val lam = node(LAM, id = "lam", at = vec(60.0, 0.0))
        val variable = node(VAR, id = "x", at = vec(180.0, -40.0))
        val constant = node(CONST, id = "c", at = vec(180.0, 60.0))

        app.principal to lam.principal
        app.aux(0) to root.principal
        app.aux(1) to constant.principal
        lam.aux(0) to variable.principal
        lam.aux(1) to variable.principal
    }

    /**
     * A tiny nonlinear sketch: the lambda body is demanded twice through DUP.
     * This is not a full term encoder, but it gives the rewrite system something resource-sensitive to do.
     */
    fun duplicatedConstantDemo(): Net = net {
        val rootL = node(ROOT, id = "rootL", at = vec(-220.0, -40.0))
        val rootR = node(ROOT, id = "rootR", at = vec(-220.0, 80.0))
        val dup = node(DUP, id = "dup", at = vec(-90.0, 20.0))
        val constant = node(CONST, id = "k", at = vec(60.0, 20.0))

        dup.principal to constant.principal
        dup.aux(0) to rootL.principal
        dup.aux(1) to rootR.principal
    }

    /** Erase an application tree to demonstrate explicit garbage collection. */
    fun eraseApplicationDemo(): Net = net {
        val er = node(ERASE, id = "erase", at = vec(-140.0, 0.0))
        val app = node(APP, id = "app", at = vec(0.0, 0.0))
        val f = node(CONST, id = "f", at = vec(150.0, -60.0))
        val x = node(VAR, id = "x", at = vec(150.0, 60.0))

        er.principal to app.principal
        app.aux(0) to f.principal
        app.aux(1) to x.principal
    }
}

// =========================
// Example usage
// =========================

fun main() {
    val lambdaRules = Lambda.rules()

    val linear = Lambda.identityAppliedToConstant()
    linear.relax(20)
    linear.writeSvg("/mnt/data/lambda_linear_before.svg")
    Reducer(lambdaRules).step(linear)
    linear.relax(60)
    linear.writeSvg("/mnt/data/lambda_linear_after.svg")

    val dupDemo = Lambda.duplicatedConstantDemo()
    dupDemo.relax(20)
    dupDemo.writeSvg("/mnt/data/lambda_dup_before.svg")
    Reducer(lambdaRules).step(dupDemo)
    dupDemo.relax(60)
    dupDemo.writeSvg("/mnt/data/lambda_dup_after.svg")

    val eraseDemo = Lambda.eraseApplicationDemo()
    eraseDemo.relax(20)
    eraseDemo.writeSvg("/mnt/data/lambda_erase_before.svg")
    Reducer(lambdaRules).reduce(eraseDemo, maxSteps = 4)
    eraseDemo.relax(60)
    eraseDemo.writeSvg("/mnt/data/lambda_erase_after.svg")

    println("Wrote demo SVGs to /mnt/data")
}
