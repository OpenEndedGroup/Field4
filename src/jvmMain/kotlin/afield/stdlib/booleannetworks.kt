@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Data")

package afield.stdlib

import afield.graphics.FLine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synchronous Boolean networks and the pictures you draw of them. A [BooleanNetwork] is a list of
 * [BoolNode]s whose update lambdas all fire at once on the current state vector;
 * [BooleanNetwork.trajectory] and [BooleanNetwork.trajectoryUntilCycle] run one initial state
 * forward, and [BooleanNetwork.findAttractors] walks the whole `2^size` state-transition graph to
 * return every [Attractor] with its basin — it is `O(2^size)` and returns an empty list above 20
 * nodes.
 *
 * The drawing helpers return `FLine`s for a box: [BooleanNetwork.drawUnrolled] (spacetime grid),
 * [BooleanNetwork.drawNetworkGraph] (wiring diagram on a circle),
 * [BooleanNetwork.drawStateTransitionGraph] (only for `size <= 6`),
 * [BooleanNetwork.drawAttractorBasins], and the standalone [drawRhythmGrid] for any list of
 * Boolean lane states. [BooleanNetwork.notateHtml] and
 * [BooleanNetwork.notateAttractorsHtml] emit HTML tables instead, [BooleanNetwork.printAttractors]
 * plain text. Ready-made networks: [toggleNetwork], [threeNodeNetwork],
 * [segmentPolarityNetwork] and the random N-K [kauffmanNetwork].
 */

/**
 * A single node in a Boolean network.
 *
 * @param name    Human-readable label, e.g. "A", "x1", or "CDK2".
 * @param rule    Textual description of the update rule, e.g. "A AND NOT B".
 * @param inputs  Indices of nodes whose current states are read by [update].
 * @param update  Function that receives the full current state array and returns
 *                the new state for this node (`true = 1`, `false = 0`).
 */
data class BoolNode(
    val name: String,
    val rule: String,
    val inputs: List<Int>,
    val update: (BooleanArray) -> Boolean,
)

/**
 * A synchronous Boolean network with [size] binary-state nodes.
 *
 * All nodes are updated simultaneously at each discrete time step from the
 * current global state vector. The state space contains exactly `2^size`
 * distinct states.
 *
 * Quick start:
 * ```kotlin
 * val net = BooleanNetwork(listOf(
 *     BoolNode("A", "NOT B", listOf(1)) { s -> !s[1] },
 *     BoolNode("B", "A",     listOf(0)) { s ->  s[0] },
 * ))
 *
 * val traj = net.trajectory(booleanArrayOf(true, false), steps = 6)
 * traj.forEach { state -> println(net.stateString(state)) }
 * lines(net.drawNetworkGraph(), w = 480.0, h = 480.0, fitToSize = true)
 * ```
 */
class BooleanNetwork(val nodes: List<BoolNode>) {

    /** Number of nodes. */
    val size: Int get() = nodes.size

    /** Pack a state array to an integer (node `i` maps to bit `i`, LSB-first). */
    fun stateToInt(state: BooleanArray): Int =
        state.foldIndexed(0) { i, acc, b -> if (b) acc or (1 shl i) else acc }

    /** Unpack an integer to a state array (bit `i` maps to node `i`). */
    fun intToState(n: Int): BooleanArray =
        BooleanArray(size) { i -> (n shr i) and 1 != 0 }

    /** Compute the synchronous successor state: apply all update functions once. */
    fun step(state: BooleanArray): BooleanArray =
        BooleanArray(size) { i -> nodes[i].update(state) }

    /**
     * Return a compact binary string with the highest-indexed node first.
     *
     * Example: for 3 nodes and state `[true, false, true]` this returns `"101"`.
     */
    fun stateString(state: BooleanArray): String =
        (size - 1 downTo 0).joinToString("") { i -> if (state[i]) "1" else "0" }

    /** Format the current state as a readable node-by-node table. */
    fun stateTable(state: BooleanArray): String = buildString {
        appendLine("Node   | State | Rule")
        appendLine("-------|-------|---------------------------")
        nodes.forEachIndexed { i, n ->
            val bit = if (state[i]) "  1  " else "  0  "
            appendLine("${n.name.padEnd(7)}| $bit | ${n.rule}")
        }
    }
}

/**
 * Advance [initial] forward by exactly [steps] synchronous updates.
 *
 * Returns a list of length `steps + 1` starting with [initial].
 */
fun BooleanNetwork.trajectory(initial: BooleanArray, steps: Int): List<BooleanArray> {
    val result = mutableListOf(initial.copyOf())
    var cur = initial.copyOf()
    repeat(steps) {
        cur = step(cur)
        result += cur.copyOf()
    }
    return result
}

/**
 * Follow the trajectory from [initial] until a state repeats or [maxSteps]
 * is exceeded.
 *
 * The returned list starts at [initial] and ends with the first repeated
 * state (inclusive), so the caller can identify the cycle by checking which
 * earlier entry equals the last element.
 */
fun BooleanNetwork.trajectoryUntilCycle(
    initial: BooleanArray,
    maxSteps: Int = 1024,
): List<BooleanArray> {
    val seen = LinkedHashMap<String, Int>()
    val traj = mutableListOf(initial.copyOf())
    seen[stateString(initial)] = 0
    var cur = initial.copyOf()
    while (traj.size <= maxSteps) {
        cur = step(cur)
        val key = stateString(cur)
        traj += cur.copyOf()
        if (key in seen) break
        seen[key] = traj.size - 1
    }
    return traj
}

/**
 * An attractor of the Boolean network: the minimal invariant set(s) reached
 * after transients die out.
 *
 * @param period  `1 = fixed point`, `> 1 = limit cycle`.
 * @param cycle   Ordered sequence of states forming the attractor.
 * @param basin   Set of all state integers that eventually converge to it.
 */
data class Attractor(
    val period: Int,
    val cycle: List<BooleanArray>,
    val basin: Set<Int>,
)

/** Repeat the attractor cycle to exactly [steps] states, preserving the original phase order. */
fun Attractor.repeatedCycle(steps: Int): List<BooleanArray> {
    require(steps >= 0) { "steps must be >= 0" }
    if (steps == 0 || cycle.isEmpty()) return emptyList()
    return List(steps) { cycle[it % cycle.size].copyOf() }
}

/**
 * Enumerate every attractor and its basin of attraction by exhaustive
 * traversal of the state-transition graph.
 *
 * Complexity is `O(2^size)`, so this is only practical for small networks.
 * Returns an empty list for `size > 20`.
 */
fun BooleanNetwork.findAttractors(): List<Attractor> {
    if (size > 20) return emptyList()
    val total = 1 shl size
    val attrOf = IntArray(total) { -1 }
    val attractorCycles = mutableListOf<List<BooleanArray>>()

    for (start in 0 until total) {
        if (attrOf[start] != -1) continue

        val path = mutableListOf<Int>()
        val indexInPath = LinkedHashMap<Int, Int>()
        var cur = start

        while (cur !in indexInPath && attrOf[cur] == -1) {
            indexInPath[cur] = path.size
            path += cur
            cur = stateToInt(step(intToState(cur)))
        }

        val attractorIndex = if (attrOf[cur] != -1) {
            attrOf[cur]
        } else {
            val newIndex = attractorCycles.size
            val cycleStart = indexInPath[cur]!!
            attractorCycles += path.subList(cycleStart, path.size).map { intToState(it) }
            newIndex
        }

        path.forEach { attrOf[it] = attractorIndex }
    }

    val basins = Array(attractorCycles.size) { mutableSetOf<Int>() }
    for (s in 0 until total) basins[attrOf[s]] += s

    return attractorCycles.mapIndexed { i, cycle ->
        Attractor(period = cycle.size, cycle = cycle, basin = basins[i])
    }
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

private fun List<FLine>.withoutEmpty(): List<FLine> = filter { it.nodes.isNotEmpty() }

/**
 * Render the time evolution of a Boolean network as a spacetime grid.
 *
 * Rows are time steps (top row `t = 0`); columns are nodes.
 */
fun BooleanNetwork.drawUnrolled(
    trajectory: List<BooleanArray>,
    cellSize: Double = 24.0,
): List<FLine> {
    val steps = trajectory.size
    val on = fline { filled = true; stroked = false; color = vec(0.94, 0.93, 0.86, 1.0) }
    val off = fline { filled = true; stroked = false; color = vec(0.14, 0.14, 0.18, 1.0) }
    val grid = fline {
        stroked = true
        filled = false
        thickness = 0.5
        color = vec(0.5, 0.5, 0.5, 0.25)
    }

    for (t in 0 until steps) {
        for (i in 0 until size) {
            val x = i * cellSize
            val y = t * cellSize
            (if (trajectory[t][i]) on else off).rect(x, y, cellSize, cellSize)
        }
    }
    for (t in 0..steps) {
        grid.moveTo(0.0, t * cellSize)
        grid.lineTo(size * cellSize, t * cellSize)
    }
    for (i in 0..size) {
        grid.moveTo(i * cellSize, 0.0)
        grid.lineTo(i * cellSize, steps * cellSize)
    }
    return listOf(off, on, grid).withoutEmpty()
}

/**
 * Render the wiring diagram of the network as a directed graph.
 *
 * Nodes are placed on a circle; edges show which nodes feed each update
 * function. Self-loops are drawn as small arcs offset outward.
 */
fun BooleanNetwork.drawNetworkGraph(
    radius: Double = 120.0,
    nodeR: Double = 14.0,
): List<FLine> {
    val pad = nodeR * 2 + radius
    val pos = Array(size) { i ->
        val angle = 2.0 * PI * i / size - PI / 2.0
        pad + radius * cos(angle) to pad + radius * sin(angle)
    }

    val edgeLines = fline {
        stroked = true
        filled = false
        thickness = 1.2
        color = vec(0.75, 0.75, 0.75, 0.60)
    }
    val selfLoops = fline {
        stroked = true
        filled = false
        thickness = 1.2
        color = vec(1.0, 0.70, 0.30, 0.75)
    }
    val nodeFill = fline {
        filled = true
        stroked = true
        thickness = 1.5
        color = vec(0.28, 0.55, 1.0, 0.88)
    }

    nodes.forEachIndexed { dst, node ->
        node.inputs.forEach { src ->
            val (fx, fy) = pos[src]
            val (tx, ty) = pos[dst]
            if (src == dst) {
                val a = 2.0 * PI * src / size - PI / 2.0
                val ox = pad + (radius + nodeR * 2.5) * cos(a)
                val oy = pad + (radius + nodeR * 2.5) * sin(a)
                selfLoops.moveTo(fx + nodeR * cos(a - 0.4), fy + nodeR * sin(a - 0.4))
                selfLoops.cubicTo(
                    ox + nodeR * cos(a - 0.6), oy + nodeR * sin(a - 0.6),
                    ox + nodeR * cos(a + 0.6), oy + nodeR * sin(a + 0.6),
                    fx + nodeR * cos(a + 0.4), fy + nodeR * sin(a + 0.4),
                )
            } else {
                val dx = tx - fx
                val dy = ty - fy
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-9)
                edgeLines.moveTo(
                    fx + (nodeR + 1.5) / d * dx,
                    fy + (nodeR + 1.5) / d * dy,
                )
                edgeLines.lineTo(
                    tx - (nodeR + 3.0) / d * dx,
                    ty - (nodeR + 3.0) / d * dy,
                )
            }
        }
    }

    pos.forEach { (cx, cy) -> nodeFill.circle(cx, cy, nodeR) }

    return listOf(edgeLines, selfLoops, nodeFill).withoutEmpty()
}

/**
 * Render the full state-transition graph for small networks (`size <= 6`).
 *
 * Each state is laid out on a circle. Arrows show the synchronous successor
 * mapping; attractor states are highlighted in orange.
 */
fun BooleanNetwork.drawStateTransitionGraph(radius: Double = 200.0): List<FLine> {
    if (size > 6) return emptyList()
    val total = 1 shl size
    val pad = radius + 40.0
    val nodeR = if (total <= 8) 12.0 else 7.0

    val angles = DoubleArray(total) { i -> 2.0 * PI * i / total - PI / 2.0 }
    val pos = Array(total) { i ->
        pad + radius * cos(angles[i]) to pad + radius * sin(angles[i])
    }

    val attractorStateSet = mutableSetOf<Int>()
    findAttractors().forEach { attr -> attr.cycle.forEach { attractorStateSet += stateToInt(it) } }

    val arrows = fline {
        stroked = true
        filled = false
        thickness = 1.0
        color = vec(0.68, 0.68, 0.68, 0.55)
    }
    val attrNodes = fline {
        filled = true
        stroked = true
        thickness = 1.5
        color = vec(1.0, 0.52, 0.18, 0.95)
    }
    val transNodes = fline {
        filled = true
        stroked = false
        color = vec(0.28, 0.48, 0.82, 0.70)
    }

    for (s in 0 until total) {
        val ns = stateToInt(step(intToState(s)))
        val (fx, fy) = pos[s]
        val (tx, ty) = pos[ns]
        if (s == ns) {
            val a = angles[s]
            arrows.moveTo(fx + nodeR * cos(a - 0.35), fy + nodeR * sin(a - 0.35))
            arrows.cubicTo(
                fx + nodeR * 2.6 * cos(a - 0.55), fy + nodeR * 2.6 * sin(a - 0.55),
                fx + nodeR * 2.6 * cos(a + 0.55), fy + nodeR * 2.6 * sin(a + 0.55),
                fx + nodeR * cos(a + 0.35), fy + nodeR * sin(a + 0.35),
            )
        } else {
            val dx = tx - fx
            val dy = ty - fy
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-9)
            arrows.moveTo(fx + (nodeR + 1.0) / d * dx, fy + (nodeR + 1.0) / d * dy)
            arrows.lineTo(tx - (nodeR + 3.0) / d * dx, ty - (nodeR + 3.0) / d * dy)
        }
    }

    for (s in 0 until total) {
        val (cx, cy) = pos[s]
        (if (s in attractorStateSet) attrNodes else transNodes).circle(cx, cy, nodeR)
    }

    return listOf(arrows, transNodes, attrNodes).withoutEmpty()
}

/**
 * Render each attractor's basin as a stack of transient spacetime sketches.
 */
fun BooleanNetwork.drawAttractorBasins(
    attractors: List<Attractor>,
    samplesPerAttractor: Int = 6,
    cellSize: Double = 16.0,
): List<FLine> {
    val on = fline { filled = true; stroked = false; color = vec(0.94, 0.93, 0.86, 1.0) }
    val off = fline { filled = true; stroked = false; color = vec(0.14, 0.14, 0.18, 1.0) }
    val sep = fline {
        stroked = true
        filled = false
        thickness = 1.0
        color = vec(1.0, 0.6, 0.2, 0.6)
    }
    val grid = fline {
        stroked = true
        filled = false
        thickness = 0.4
        color = vec(0.5, 0.5, 0.5, 0.20)
    }

    var xOffset = 0.0

    attractors.forEach { attr ->
        val basinList = attr.basin.toList().shuffled().take(samplesPerAttractor)
        var maxRows = 0

        basinList.forEach { startInt ->
            val traj = trajectoryUntilCycle(intToState(startInt))
            maxRows = maxOf(maxRows, traj.size)
        }

        basinList.forEachIndexed { col, startInt ->
            val traj = trajectoryUntilCycle(intToState(startInt))
            val x0 = xOffset + col * (size + 1) * cellSize
            for (t in traj.indices) {
                for (i in 0 until size) {
                    val x = x0 + i * cellSize
                    val y = t * cellSize
                    (if (traj[t][i]) on else off).rect(x, y, cellSize, cellSize)
                }
            }
            for (t in 0..traj.size) {
                grid.moveTo(x0, t * cellSize)
                grid.lineTo(x0 + size * cellSize, t * cellSize)
            }
        }

        val sepX = xOffset + basinList.size * (size + 1) * cellSize - cellSize * 0.5
        sep.moveTo(sepX, 0.0)
        sep.lineTo(sepX, maxRows * cellSize)

        xOffset += (basinList.size * (size + 1) + 2) * cellSize
    }

    return listOf(off, on, grid, sep).withoutEmpty()
}

/**
 * Render a step-by-step rhythm grid from a list of Boolean lane states.
 *
 * Each row is a lane and each column is a step. Active cells are colored by
 * lane, dark cells are inactive, and every [accentEvery]th step gets a heavier
 * separator to suggest bars / beat groups.
 */
fun drawRhythmGrid(
    steps: List<BooleanArray>,
    cellW: Double = 24.0,
    cellH: Double = 18.0,
    stepGap: Double = 5.0,
    laneGap: Double = 5.0,
    accentEvery: Int = 4,
): List<FLine> {
    if (steps.isEmpty()) return emptyList()
    val lanes = steps.maxOfOrNull { it.size } ?: 0
    if (lanes <= 0) return emptyList()

    val off = fline {
        filled = true
        stroked = false
        color = vec(0.14, 0.14, 0.18, 0.95)
    }
    val grid = fline {
        filled = false
        stroked = true
        thickness = 0.6
        color = vec(1.0, 1.0, 1.0, 0.08)
    }
    val accents = fline {
        filled = false
        stroked = true
        thickness = 1.2
        color = vec(1.0, 0.62, 0.24, 0.34)
    }
    val fills = listOf(
        vec(0.34, 0.66, 1.0, 0.96),
        vec(1.0, 0.57, 0.22, 0.96),
        vec(0.48, 1.0, 0.62, 0.96),
        vec(0.88, 0.44, 1.0, 0.96),
        vec(1.0, 0.88, 0.28, 0.96),
        vec(0.44, 0.92, 0.92, 0.96),
    ).map { color ->
        fline {
            filled = true
            stroked = false
            this.color = color
        }
    }

    val inset = minOf(cellW, cellH) * 0.14
    val totalHeight = lanes * cellH + (lanes - 1) * laneGap

    steps.forEachIndexed { stepIndex, state ->
        val x0 = stepIndex * (cellW + stepGap)
        if (accentEvery > 0 && stepIndex % accentEvery == 0) {
            val accentX = x0 - stepGap * 0.5
            accents.moveTo(accentX, -laneGap * 0.5)
            accents.lineTo(accentX, totalHeight + laneGap * 0.5)
        }

        for (lane in 0 until lanes) {
            val y0 = lane * (cellH + laneGap)
            off.rect(x0, y0, cellW, cellH)
            grid.rect(x0, y0, cellW, cellH)
            if (lane < state.size && state[lane]) {
                fills[lane % fills.size].rect(x0 + inset, y0 + inset, cellW - 2 * inset, cellH - 2 * inset)
            }
        }
    }

    if (accentEvery > 0) {
        val endX = steps.size * (cellW + stepGap) - stepGap * 0.5
        accents.moveTo(endX, -laneGap * 0.5)
        accents.lineTo(endX, totalHeight + laneGap * 0.5)
    }

    return (listOf(off, grid, accents) + fills).withoutEmpty()
}

/** Generate an HTML table of nodes, input wiring, and update rules. */
fun BooleanNetwork.notateHtml(): String = buildString {
    appendLine(
        "<table style='font-family:monospace;font-size:13px;border-collapse:collapse;" +
            "background:#16181f;color:#ccc;'>"
    )
    appendLine(
        "<tr>" +
            "<th style='padding:4px 10px;text-align:left;border-bottom:1px solid #444;color:#aaa'>Node</th>" +
            "<th style='padding:4px 10px;text-align:left;border-bottom:1px solid #444;color:#aaa'>Inputs</th>" +
            "<th style='padding:4px 10px;text-align:left;border-bottom:1px solid #444;color:#aaa'>Update rule</th>" +
            "</tr>"
    )
    nodes.forEach { n ->
        val inputNames = n.inputs.joinToString(", ") { nodes[it].name }
        appendLine(
            "<tr>" +
                "<td style='padding:3px 10px;color:#7ec8e3'><b>${n.name}</b></td>" +
                "<td style='padding:3px 10px;color:#f0a070'>${inputNames.ifEmpty { "—" }}</td>" +
                "<td style='padding:3px 10px'>${n.rule}</td>" +
                "</tr>"
        )
    }
    appendLine("</table>")
}

/** Generate an HTML summary of attractor type, cycle states, and basin sizes. */
fun BooleanNetwork.notateAttractorsHtml(attractors: List<Attractor>): String = buildString {
    appendLine("<div style='font-family:monospace;font-size:13px;color:#ccc;background:#16181f;padding:8px'>")
    if (attractors.isEmpty()) {
        appendLine("<p style='color:#f88'>No attractors found (network size > 20?).</p>")
    } else {
        attractors.forEachIndexed { idx, attr ->
            val typeLabel = if (attr.period == 1) "Fixed point" else "Limit cycle — period ${attr.period}"
            appendLine(
                "<div style='margin:6px 0;padding:6px 12px;background:#1e2030;" +
                    "border-left:3px solid #f80;border-radius:3px'>"
            )
            appendLine(
                "<b style='color:#f80'>Attractor ${idx + 1}</b> — $typeLabel " +
                    "— basin: <b style='color:#7ec'>${attr.basin.size}</b> / ${1 shl size} states<br>"
            )
            attr.cycle.forEachIndexed { t, state ->
                val bits = stateString(state)
                val labels = nodes.mapIndexed { i, n ->
                    "${n.name}=<span style='color:#cfc'>${if (state[i]) "1" else "0"}</span>"
                }.joinToString("  ")
                appendLine(
                    "<span style='color:#8cf'>t+$t:</span>&nbsp;" +
                        "<span style='letter-spacing:3px;color:#cfc'>$bits</span>&nbsp;&nbsp;" +
                        "<span style='color:#888;font-size:11px'>$labels</span><br>"
                )
            }
            if (attr.period > 1) {
                appendLine("<span style='color:#555;font-size:11px'>↩ returns to t+0</span>")
            }
            appendLine("</div>")
        }
    }
    appendLine("</div>")
}

/** Print a plain-text attractor summary to stdout. */
fun BooleanNetwork.printAttractors() {
    val attractors = findAttractors()
    println("${attractors.size} attractor(s) for ${size}-node network:")
    attractors.forEachIndexed { idx, attr ->
        val type = if (attr.period == 1) "fixed-point" else "cycle(${attr.period})"
        println("  [${idx + 1}] $type  basin=${attr.basin.size}")
        attr.cycle.forEachIndexed { t, state ->
            println("      t+$t  ${stateString(state)}  ${stateTable(state).lines().drop(2).joinToString("  ").trimEnd()}")
        }
    }
}

/**
 * A 2-node network with one period-4 cycle through all four states.
 *
 * `A(t+1) = NOT B(t)`
 * `B(t+1) = A(t)`
 */
fun toggleNetwork(): BooleanNetwork = BooleanNetwork(
    listOf(
        BoolNode("A", "NOT B", listOf(1)) { s -> !s[1] },
        BoolNode("B", "A", listOf(0)) { s -> s[0] },
    )
)

/**
 * A 3-node network with a single period-5 limit cycle.
 *
 * This is a compact example for spacetime unrolling and basin sketches.
 */
fun threeNodeNetwork(): BooleanNetwork = BooleanNetwork(
    listOf(
        BoolNode("A", "B AND C", listOf(1, 2)) { s -> s[1] && s[2] },
        BoolNode("B", "A OR C", listOf(0, 2)) { s -> s[0] || s[2] },
        BoolNode("C", "NOT A", listOf(0)) { s -> !s[0] },
    )
)

/**
 * Simplified 4-node segment-polarity network inspired by Drosophila.
 */
fun segmentPolarityNetwork(): BooleanNetwork = BooleanNetwork(
    listOf(
        BoolNode("en", "wg", listOf(1)) { s -> s[1] },
        BoolNode("wg", "en", listOf(0)) { s -> s[0] },
        BoolNode("ptc", "NOT ci", listOf(3)) { s -> !s[3] },
        BoolNode("ci", "ptc AND NOT en", listOf(2, 0)) { s -> s[2] && !s[0] },
    )
)

/**
 * A random N-K Kauffman network.
 *
 * Each node has exactly [k] randomly chosen inputs and a random Boolean
 * lookup table over those inputs.
 */
fun kauffmanNetwork(n: Int = 5, k: Int = 2, seed: Long = 42L): BooleanNetwork {
    val rng = java.util.Random(seed)
    val nodeList = (0 until n).map { i ->
        val inputs = (0 until k).map { rng.nextInt(n) }
        val table = BooleanArray(1 shl k) { rng.nextBoolean() }
        BoolNode(
            name = "x$i",
            rule = "random(${inputs.joinToString(",")})",
            inputs = inputs,
        ) { s ->
            val idx = inputs.foldIndexed(0) { bit, acc, src ->
                if (s[src]) acc or (1 shl bit) else acc
            }
            table[idx]
        }
    }
    return BooleanNetwork(nodeList)
}
