@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.stdlib

import afield.Box
import afield.Rect
import afield.graphics.FLine
import afield.graphics.drawToSVG
import afield.graphics.lines
import org.joml.minus
import org.joml.plus
import java.util.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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

data class NodeState(
    var energy: Double = 1.0,
    var phase: Double = 0.0,
    var hue: Double = 0.0,
    var age: Double = 0.0,
    val scalars: MutableMap<String, Double> = linkedMapOf()
) {
    fun copyDeep(): NodeState = NodeState(
        energy = energy,
        phase = phase,
        hue = hue,
        age = age,
        scalars = LinkedHashMap(scalars)
    )

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
    val type: AgentType,
    val state: NodeState = NodeState()
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
    fun velocityOf(node: Node): Vec2 = motions[node.id]?.velocity ?: Vec2()

    fun setPosition(node: Node, p: Vec2) {
        positions[node.id] = p
    }

    fun setVelocity(node: Node, velocity: Vec2) {
        motions.getOrPut(node.id) { Motion() }.velocity = Vec2(velocity)
    }

    fun create(type: AgentType, id: String = ids.next(type.name.lowercase()), state: NodeState = NodeState()): Node {
        require(id !in _nodes) { "Duplicate node id: $id" }
        val node = Node(id, type, state.copyDeep())
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

    fun incrementAges(dt: Double = 1.0) {
        nodes.forEach { it.state.age += dt }
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

    fun totalEnergy(): Double = nodes.sumOf { it.state.energy }

    fun averageAge(): Double = if (nodes.isEmpty()) 0.0 else nodes.sumOf { it.state.age } / nodes.size.toDouble()

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
            out += FLine().apply {
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
                out += FLine().apply {
                    circle(center.x.toDouble(), center.y.toDouble(), radius + 10.0, segments = 32)
                    filled = false
                    stroked = true
                    thickness = 1.4
                    color = hsl(hue, 0.86, 0.60, (0.10 + node.state.energy * 0.08).coerceIn(0.10, 0.34))
                }
            }

            val velocity = velocityOf(node)
            if (velocity.lengthSquared().toDouble() > 0.01) {
                out += FLine().apply {
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

            out += FLine().apply {
                circle(center.x.toDouble(), center.y.toDouble(), radius, segments = 40)
                filled = true
                stroked = false
                color = fill
            }
            out += FLine().apply {
                circle(center.x.toDouble(), center.y.toDouble(), radius, segments = 40)
                filled = false
                stroked = true
                thickness = (1.5 + node.state.energy * 1.25).coerceIn(1.5, 4.2)
                color = stroke
            }

            out += FLine().apply {
                stroked = true
                filled = false
                thickness = 0.9
                color = hsl(hue, 0.38, 0.72, 0.28)
                node.ports.forEach { port ->
                    moveTo(center)
                    lineTo(portPosition(port))
                }
            }

            out += FLine().apply {
                pointed = true
                pointSize = (radius * 0.18).coerceIn(2.6, 5.0)
                color = vec(1.0, 0.96, 0.90, 0.96)
                moveTo(portPosition(node.principal))
            }

            if (node.type.auxPorts > 0) {
                out += FLine().apply {
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

    fun summaryHtml(title: String = "Interaction net", maxNodes: Int = 8): String {
        val active = activePairs()
        val nodeRows = nodes
            .sortedWith(compareByDescending<Node> { it.state.energy }.thenBy { it.id })
            .take(maxNodes)
            .joinToString("") { node ->
                """
                    <tr>
                        <td style="padding:4px 6px;border-bottom:1px solid #202b38;color:#f5f8fd;">${escapeInteractionHtml(node.id)}</td>
                        <td style="padding:4px 6px;border-bottom:1px solid #202b38;color:#9dc1ff;">${escapeInteractionHtml(node.type.name)}</td>
                        <td style="padding:4px 6px;border-bottom:1px solid #202b38;text-align:right;color:#f2d28b;">${fmt(node.state.energy)}</td>
                        <td style="padding:4px 6px;border-bottom:1px solid #202b38;text-align:right;color:#b9d7ff;">${fmt(node.state.age)}</td>
                    </tr>
                """.trimIndent()
            }
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
                <table style="width:100%;margin-top:12px;border-collapse:collapse;font-size:12px;">
                    <thead>
                        <tr style="text-align:left;color:#91a4ba;">
                            <th style="padding:4px 6px;border-bottom:1px solid #2b3a4d;">node</th>
                            <th style="padding:4px 6px;border-bottom:1px solid #2b3a4d;">type</th>
                            <th style="padding:4px 6px;border-bottom:1px solid #2b3a4d;text-align:right;">energy</th>
                            <th style="padding:4px 6px;border-bottom:1px solid #2b3a4d;text-align:right;">age</th>
                        </tr>
                    </thead>
                    <tbody>$nodeRows</tbody>
                </table>
                $more
                $activeHtml
            """.trimIndent()
        )
    }

    fun toSvg(width: Int = 900, height: Int = 700, padding: Double = 40.0): String {
        if (nodes.isEmpty()) {
            return """
                <svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">
                  <rect width="100%" height="100%" fill="white"/>
                </svg>
            """.trimIndent()
        }

        val fitted = fitLinesToBox(toFLines(), width.toDouble(), height.toDouble(), padding)
        val background = FLine().apply {
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

class NodeRef internal constructor(val name: String) {
    override fun toString(): String = name
}

data class PortRef(val node: NodeRef, val portIndex: Int)
internal data class NodeSpec(
    val type: AgentType,
    val suggestedPrefix: String,
    val offset: Vec2,
    val state: NodeState = NodeState(),
    val impulse: Vec2 = Vec2()
)

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

    fun node(
        name: String,
        type: AgentType,
        at: Vec2 = Vec2(),
        state: NodeState = NodeState(),
        impulse: Vec2 = Vec2()
    ): NodeRef {
        val ref = NodeRef(name)
        require(ref !in fragment.nodeSpecs) { "Duplicate fragment node name: $name" }
        fragment.nodeSpecs[ref] = NodeSpec(type, type.name.lowercase(), at, state.copyDeep(), Vec2(impulse))
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
    val nodes: Collection<Node> get() = nodeMap.values
}

// =========================
// Builder DSL for full nets
// =========================

class NetBuilder(private val net: Net) {
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
    fun matches(a: Node, b: Node): Boolean = bind(a, b) != null

    fun bind(a: Node, b: Node): SignatureBinding? = when {
        a.type == left && b.type == right -> SignatureBinding(a, b, swapped = false)
        a.type == right && b.type == left -> SignatureBinding(b, a, swapped = true)
        else -> null
    }
}

data class SignatureBinding(val first: Node, val second: Node, val swapped: Boolean)
data class RuleMatch(val rule: RewriteRule, val binding: SignatureBinding)

data class PairContext(
    val first: Node,
    val second: Node,
    val firstAuxPeers: List<Port?>,
    val secondAuxPeers: List<Port?>,
    val center: Vec2,
    val radius: Double,
    val firstVelocity: Vec2,
    val secondVelocity: Vec2,
    val axis: Vec2,
    val normal: Vec2
) {
    fun auxPeer(nodeOrdinal: Int, auxIndex: Int): Port? = when (nodeOrdinal) {
        0 -> firstAuxPeers.getOrNull(auxIndex)
        1 -> secondAuxPeers.getOrNull(auxIndex)
        else -> error("nodeOrdinal must be 0 or 1")
    }

    fun state(nodeOrdinal: Int): NodeState = when (nodeOrdinal) {
        0 -> first.state
        1 -> second.state
        else -> error("nodeOrdinal must be 0 or 1")
    }

    fun stateCopy(nodeOrdinal: Int): NodeState = state(nodeOrdinal).copyDeep()

    fun velocity(nodeOrdinal: Int): Vec2 = when (nodeOrdinal) {
        0 -> Vec2(firstVelocity)
        1 -> Vec2(secondVelocity)
        else -> error("nodeOrdinal must be 0 or 1")
    }

    fun blendedState(weightToFirst: Double = 0.5): NodeState = NodeState.blend(first.state, second.state, weightToFirst)

    val pairEnergy: Double get() = first.state.energy + second.state.energy

    fun branchImpulse(branch: Int, branches: Int = 2, amount: Double = radius * 0.35): Vec2 {
        require(branches > 0) { "branches must be > 0" }
        require(branch in 0 until branches) { "branch $branch out of range for $branches branches" }
        val centered = branch - (branches - 1) / 2.0
        return Vec2(normal).mul((centered * amount).toFloat())
    }
}

data class RewriteEvent(
    val kind: String,
    val at: Vec2,
    val magnitude: Double = 1.0,
    val data: Map<String, Double> = emptyMap()
)

data class ReductionStep(
    val ruleName: String,
    val context: PairContext,
    val swapped: Boolean,
    val removedNodeIds: List<String>,
    val createdNodeIds: List<String>,
    val events: List<RewriteEvent>
)

class RewriteBuilder internal constructor(private val ctx: PairContext) {
    private val result = RewriteResult()

    fun node(
        name: String,
        type: AgentType,
        at: Vec2 = Vec2(),
        state: NodeState = NodeState(),
        impulse: Vec2 = Vec2()
    ): NodeRef {
        val ref = NodeRef(name)
        require(ref !in result.fragment.nodeSpecs) { "Duplicate rewrite node name: $name" }
        result.fragment.nodeSpecs[ref] = NodeSpec(type, type.name.lowercase(), at, state.copyDeep(), Vec2(impulse))
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

    fun emit(
        kind: String,
        magnitude: Double = ctx.pairEnergy,
        at: Vec2 = ctx.center,
        data: Map<String, Double> = emptyMap()
    ) {
        result.events += RewriteEvent(kind, Vec2(at), magnitude, LinkedHashMap(data))
    }

    fun build(): RewriteResult = result
}

class RewriteResult internal constructor(
    internal val fragment: NetFragment = NetFragment(),
    internal val reconnections: MutableList<Pair<PortRef, Port>> = mutableListOf(),
    internal val passthroughs: MutableList<Pair<Port, Port>> = mutableListOf(),
    internal val events: MutableList<RewriteEvent> = mutableListOf()
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
    private val events = mutableListOf<RewriteEvent>()

    fun node(
        name: String,
        type: AgentType,
        at: Vec2 = Vec2(),
        state: NodeState = NodeState(),
        impulse: Vec2 = Vec2()
    ): NodeRef {
        val ref = NodeRef(name)
        require(ref !in fragment.nodeSpecs) { "Duplicate rewrite node name: $name" }
        fragment.nodeSpecs[ref] = NodeSpec(type, type.name.lowercase(), at, state.copyDeep(), Vec2(impulse))
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

    fun emit(
        kind: String,
        magnitude: Double = ctx.pairEnergy,
        at: Vec2 = ctx.center,
        data: Map<String, Double> = emptyMap()
    ) {
        events += RewriteEvent(kind, Vec2(at), magnitude, LinkedHashMap(data))
    }

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

    fun find(a: Node, b: Node): RewriteRule? = findMatch(a, b)?.rule

    fun findMatch(a: Node, b: Node): RuleMatch? =
        rules.firstNotNullOfOrNull { rule ->
            rule.signature.bind(a, b)?.let { RuleMatch(rule, it) }
        }
}

fun rules(block: RuleBook.() -> Unit): RuleBook = RuleBook().apply(block)

enum class ActivationOrder {
    FIRST_FOUND,
    LEFTMOST_BY_ID,
    RIGHTMOST_BY_ID,
    RANDOM,
    HIGHEST_PAIR_ENERGY,
    LOWEST_PAIR_ENERGY
}

data class PairCandidate(val pair: ActivePair, val match: RuleMatch, val context: PairContext) {
    val rule: RewriteRule get() = match.rule
}

fun interface PairSelector {
    fun select(net: Net, candidates: List<PairCandidate>): PairCandidate?
}

object PairSelectors {
    val firstFound = PairSelector { _, candidates -> candidates.firstOrNull() }
    val leftmostById = PairSelector { _, candidates -> candidates.minByOrNull { minOf(it.context.first.id, it.context.second.id) } }
    val rightmostById = PairSelector { _, candidates -> candidates.maxByOrNull { maxOf(it.context.first.id, it.context.second.id) } }
    val highestPairEnergy = PairSelector { _, candidates -> candidates.maxByOrNull { it.context.pairEnergy } }
    val lowestPairEnergy = PairSelector { _, candidates -> candidates.minByOrNull { it.context.pairEnergy } }

    fun random(random: Random = Random.Default): PairSelector =
        PairSelector { _, candidates ->
            if (candidates.isEmpty()) null else candidates[random.nextInt(candidates.size)]
        }

    fun nearest(point: Vec2): PairSelector =
        PairSelector { _, candidates ->
            candidates.minByOrNull { Vec2(it.context.center).distance(point) }
        }

    fun from(order: ActivationOrder): PairSelector = when (order) {
        ActivationOrder.FIRST_FOUND -> firstFound
        ActivationOrder.LEFTMOST_BY_ID -> leftmostById
        ActivationOrder.RIGHTMOST_BY_ID -> rightmostById
        ActivationOrder.RANDOM -> random()
        ActivationOrder.HIGHEST_PAIR_ENERGY -> highestPairEnergy
        ActivationOrder.LOWEST_PAIR_ENERGY -> lowestPairEnergy
    }
}

class Reducer(
    private val rules: RuleBook,
    private val selector: PairSelector = PairSelectors.firstFound,
    private val layout: LayoutConfig = LayoutConfig()
) {
    constructor(
        rules: RuleBook,
        order: ActivationOrder,
        layout: LayoutConfig = LayoutConfig()
    ) : this(rules, PairSelectors.from(order), layout)

    fun step(net: Net): Boolean = stepDetailed(net) != null

    fun stepDetailed(net: Net): ReductionStep? {
        val candidate = selectCandidate(net) ?: return null
        return apply(net, candidate)
    }

    fun reduce(net: Net, maxSteps: Int = Int.MAX_VALUE, onStep: ((Int, Net) -> Unit)? = null): Int {
        var steps = 0
        while (steps < maxSteps && stepDetailed(net) != null) {
            steps++
            onStep?.invoke(steps, net)
        }
        return steps
    }

    fun reduceDetailed(
        net: Net,
        maxSteps: Int = Int.MAX_VALUE,
        onStep: ((Int, Net, ReductionStep) -> Unit)? = null
    ): Int {
        var steps = 0
        while (steps < maxSteps) {
            val detail = stepDetailed(net) ?: break
            steps++
            onStep?.invoke(steps, net, detail)
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
        val deltaLength = max(delta.length(), 0.0001f)
        val axis = if (deltaLength < 1e-4f) vec(1.0, 0.0) else Vec2(delta).normalize()
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

fun Box.showInteractionSummary(
    channel: String = "interaction-net-summary",
    vararg cards: String
) {
    display(channel, interactionSummaryGrid(*cards))
}

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
     * A tiny nonlinear sketch: the lambda body is demanded twice through DUP.
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
