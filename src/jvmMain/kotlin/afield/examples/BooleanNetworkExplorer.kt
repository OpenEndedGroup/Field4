@file:Suppress("MemberVisibilityCanBePrivate")

package afield.examples

import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.*

// ============================================================
// BOOLEAN NETWORK — Core Data Model
// ============================================================

/**
 * A single node in a Boolean network.
 *
 * @param name    Human-readable label, e.g. "A", "x1", or "CDK2".
 * @param rule    Textual description of the update rule, e.g. "A AND NOT B".
 * @param inputs  Indices of nodes whose current states are read by [update].
 * @param update  Function that receives the **full** current state array
 *                and returns the new state for this node (true = 1, false = 0).
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
 * current global state vector.  The state space contains exactly 2^[size]
 * distinct states.
 *
 * Quick-start:
 * ```kotlin
 * val net = BooleanNetwork(listOf(
 *     BoolNode("A", "NOT B", listOf(1)) { s -> !s[1] },
 *     BoolNode("B", "A",     listOf(0)) { s ->  s[0] },
 * ))
 * val traj = net.trajectory(booleanArrayOf(true, false), steps = 6)
 * traj.forEach { state -> println(net.stateString(state)) }
 * ```
 */
class BooleanNetwork(val nodes: List<BoolNode>) {

    /** Number of nodes. */
    val size: Int get() = nodes.size

    // ---- State encoding ------------------------------------------------

    /** Pack a state array to an integer (node i → bit i, LSB-first). */
    fun stateToInt(state: BooleanArray): Int =
        state.foldIndexed(0) { i: Int, acc: Int, b: Boolean -> if (b) acc or (1 shl i) else acc }

    /** Unpack an integer to a state array (bit i → node i). */
    fun intToState(n: Int): BooleanArray =
        BooleanArray(size) { i -> (n shr i) and 1 != 0 }

    // ---- Single update step --------------------------------------------

    /** Compute the synchronous successor state: apply all update functions once. */
    fun step(state: BooleanArray): BooleanArray =
        BooleanArray(size) { i -> nodes[i].update(state) }

    // ---- Display helpers -----------------------------------------------

    /**
     * Return a compact binary string with the highest-indexed node first.
     *
     * Example: for 3 nodes and state [true, false, true] → "101".
     */
    fun stateString(state: BooleanArray): String =
        (size - 1 downTo 0).joinToString("") { i -> if (state[i]) "1" else "0" }

    /**
     * Format the current state as a readable node-by-node table.
     */
    fun stateTable(state: BooleanArray): String = buildString {
        appendLine("Node   | State | Rule")
        appendLine("-------|-------|---------------------------")
        nodes.forEachIndexed { i, n ->
            val bit = if (state[i]) "  1  " else "  0  "
            appendLine("${n.name.padEnd(7)}| $bit | ${n.rule}")
        }
    }
}

// ============================================================
// EVOLUTION — trajectory helpers
// ============================================================

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
 * The returned list starts at [initial] and ends with the **first repeated
 * state** (inclusive), so the caller can identify the cycle by checking
 * which earlier entry equals the last element.
 */
fun BooleanNetwork.trajectoryUntilCycle(
    initial: BooleanArray,
    maxSteps: Int = 1024,
): List<BooleanArray> {
    val seen = LinkedHashMap<String, Int>()       // stateString → index in traj
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

// ============================================================
// EXPLORATION — attractors and basins
// ============================================================

/**
 * An attractor of the Boolean network: the minimal invariant set(s) reached
 * after transients die out.
 *
 * @param period  1 = fixed point, > 1 = limit cycle.
 * @param cycle   The ordered sequence of states forming the attractor
 *                (length == [period]).
 * @param basin   The set of **all** state integers that eventually converge
 *                to this attractor (includes the attractor states themselves).
 */
data class Attractor(
    val period: Int,
    val cycle: List<BooleanArray>,
    val basin: Set<Int>,
)

/**
 * Enumerate every attractor and its basin of attraction by exhaustive
 * traversal of the state-transition graph.
 *
 * Complexity: O(2^[BooleanNetwork.size]).  Only feasible for small networks
 * (size ≤ ~20).  Returns an empty list when [BooleanNetwork.size] > 20.
 */
fun BooleanNetwork.findAttractors(): List<Attractor> {
    if (size > 20) return emptyList()
    val total = 1 shl size
    val attrOf = IntArray(total) { -1 }          // maps every state to its attractor index
    val attractorCycles = mutableListOf<List<BooleanArray>>()

    for (start in 0 until total) {
        if (attrOf[start] != -1) continue

        // Follow the chain until we hit a known attractor or revisit a state.
        val path = mutableListOf<Int>()
        val indexInPath = LinkedHashMap<Int, Int>()  // state integer → position in path
        var cur = start

        while (cur !in indexInPath && attrOf[cur] == -1) {
            indexInPath[cur] = path.size
            path += cur
            cur = stateToInt(step(intToState(cur)))
        }

        val ai: Int
        if (attrOf[cur] != -1) {
            // Tail leading to a previously found attractor.
            ai = attrOf[cur]
        } else {
            // New cycle found starting at position indexInPath[cur].
            ai = attractorCycles.size
            val cycleStart = indexInPath[cur]!!
            attractorCycles += path.subList(cycleStart, path.size).map { intToState(it) }
        }
        path.forEach { attrOf[it] = ai }
    }

    // Collect basins.
    val basins = Array(attractorCycles.size) { mutableSetOf<Int>() }
    for (s in 0 until total) basins[attrOf[s]] += s

    return attractorCycles.mapIndexed { i, cycle ->
        Attractor(period = cycle.size, cycle = cycle, basin = basins[i])
    }
}

// ============================================================
// VISUALIZATION — FLine rendering
// ============================================================

// Internal helper: add a filled rectangle as a closed sub-path to an FLine.
private fun FLine.rect(x: Double, y: Double, w: Double, h: Double) {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
}

// Internal helper: append a circle polygon to an FLine.
private fun FLine.circle(cx: Double, cy: Double, r: Double, segments: Int = 24) {
    for (j in 0..segments) {
        val a = 2.0 * PI * j / segments
        val nx = cx + r * cos(a)
        val ny = cy + r * sin(a)
        if (j == 0) moveTo(nx, ny) else lineTo(nx, ny)
    }
    close()
}

/**
 * Render the time evolution of a Boolean network as a **spacetime grid**.
 *
 * - Rows  = time steps (top row = t = 0).
 * - Columns = nodes (left column = node 0).
 * - Light cells = true (1); dark cells = false (0).
 *
 * ```kotlin
 * val net  = threeNodeNetwork()
 * val traj = net.trajectory(booleanArrayOf(true, false, true), steps = 12)
 * lines(net.drawUnrolled(traj), w = 600.0, h = 400.0, fitToSize = true)
 * ```
 *
 * @param trajectory  Result of [trajectory] or [trajectoryUntilCycle].
 * @param cellSize    Size (px) of each grid cell.
 * @return A list of [FLine] objects suitable for [Box.lines].
 */
fun BooleanNetwork.drawUnrolled(
    trajectory: List<BooleanArray>,
    cellSize: Double = 24.0,
): List<FLine> {
    val steps = trajectory.size
    val on  = FLine().apply { filled = true; stroked = false; color = vec(0.94, 0.93, 0.86, 1.0) }
    val off = FLine().apply { filled = true; stroked = false; color = vec(0.14, 0.14, 0.18, 1.0) }
    val grid = FLine().apply {
        stroked = true; filled = false
        thickness = 0.5; color = vec(0.5, 0.5, 0.5, 0.25)
    }

    for (t in 0 until steps) {
        for (i in 0 until size) {
            val x = i * cellSize
            val y = t * cellSize
            (if (trajectory[t][i]) on else off).rect(x, y, cellSize, cellSize)
        }
    }
    // Horizontal and vertical grid lines.
    for (t in 0..steps) {
        grid.moveTo(0.0, t * cellSize); grid.lineTo(size * cellSize, t * cellSize)
    }
    for (i in 0..size) {
        grid.moveTo(i * cellSize, 0.0); grid.lineTo(i * cellSize, steps * cellSize)
    }
    return listOf(off, on, grid)
}

/**
 * Render the **wiring diagram** of the network as a directed graph.
 *
 * Nodes are placed on a circle; edges show which nodes feed each update
 * function.  Self-loops are drawn as small arcs offset outward.
 *
 * ```kotlin
 * val net = threeNodeNetwork()
 * lines(net.drawNetworkGraph(), w = 500.0, h = 500.0, fitToSize = true)
 * ```
 *
 * @param radius  Radius of the node-placement circle (px).
 * @param nodeR   Radius of each node disc (px).
 * @return A list of [FLine] objects suitable for [Box.lines].
 */
fun BooleanNetwork.drawNetworkGraph(
    radius: Double = 120.0,
    nodeR: Double = 14.0,
): List<FLine> {
    val pad = nodeR * 2 + radius
    // Node centres on a circle.
    val pos = Array(size) { i ->
        val angle = 2.0 * PI * i / size - PI / 2.0
        Pair(pad + radius * cos(angle), pad + radius * sin(angle))
    }

    val edgeLines = FLine().apply {
        stroked = true; filled = false
        thickness = 1.2; color = vec(0.75, 0.75, 0.75, 0.60)
    }
    val selfLoops = FLine().apply {
        stroked = true; filled = false
        thickness = 1.2; color = vec(1.0, 0.70, 0.30, 0.75)
    }
    val nodeFill = FLine().apply {
        filled = true; stroked = true
        thickness = 1.5; color = vec(0.28, 0.55, 1.0, 0.88)
    }

    nodes.forEachIndexed { dst, node ->
        node.inputs.forEach { src ->
            val (fx, fy) = pos[src]
            val (tx, ty) = pos[dst]
            if (src == dst) {
                // Self-loop: small cubic bump outward from the node.
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
                val dx = tx - fx; val dy = ty - fy
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-9)
                // Shorten both ends so arrows start/stop at node borders.
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

    return listOf(edgeLines, selfLoops, nodeFill)
}

/**
 * Render the **full state-transition graph** for small networks (size ≤ 6).
 *
 * Each of the 2^[BooleanNetwork.size] states is laid out on a circle.
 * Arrows show the synchronous successor mapping.  Attractor states are
 * highlighted in orange; transient states are shown in blue.
 *
 * Returns an empty list when [BooleanNetwork.size] > 6 to avoid extreme clutter.
 *
 * ```kotlin
 * val net = toggleNetwork()
 * lines(net.drawStateTransitionGraph(), w = 600.0, h = 600.0, fitToSize = true)
 * ```
 *
 * @param radius  Radius (px) of the state-node circle layout.
 * @return A list of [FLine] objects suitable for [Box.lines].
 */
fun BooleanNetwork.drawStateTransitionGraph(radius: Double = 200.0): List<FLine> {
    if (size > 6) return emptyList()
    val total = 1 shl size
    val pad = radius + 40.0
    val nodeR = if (total <= 8) 12.0 else 7.0

    val angles = DoubleArray(total) { i -> 2.0 * PI * i / total - PI / 2.0 }
    val pos = Array(total) { i ->
        Pair(pad + radius * cos(angles[i]), pad + radius * sin(angles[i]))
    }

    val attractorStateSet = mutableSetOf<Int>()
    findAttractors().forEach { attr -> attr.cycle.forEach { attractorStateSet += stateToInt(it) } }

    val arrows = FLine().apply {
        stroked = true; filled = false
        thickness = 1.0; color = vec(0.68, 0.68, 0.68, 0.55)
    }
    val attrNodes = FLine().apply {
        filled = true; stroked = true
        thickness = 1.5; color = vec(1.0, 0.52, 0.18, 0.95)
    }
    val transNodes = FLine().apply {
        filled = true; stroked = false
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
            val dx = tx - fx; val dy = ty - fy
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-9)
            arrows.moveTo(fx + (nodeR + 1.0) / d * dx, fy + (nodeR + 1.0) / d * dy)
            arrows.lineTo(tx - (nodeR + 3.0) / d * dx, ty - (nodeR + 3.0) / d * dy)
        }
    }

    for (s in 0 until total) {
        val (cx, cy) = pos[s]
        (if (s in attractorStateSet) attrNodes else transNodes).circle(cx, cy, nodeR)
    }

    return listOf(arrows, transNodes, attrNodes)
}

/**
 * Render each attractor's **basin** as a small spacetime diagram showing
 * sample transient trajectories converging to the cycle.
 *
 * For each attractor a random sample of basin states is evolved until the
 * cycle is reached, and all trajectories are stacked in a grid.
 *
 * ```kotlin
 * val net = threeNodeNetwork()
 * val attrs = net.findAttractors()
 * lines(net.drawAttractorBasins(attrs), w = 800.0, h = 400.0, fitToSize = true)
 * ```
 *
 * @param attractors  Result of [findAttractors].
 * @param samplesPerAttractor  How many basin states to trace per attractor.
 * @param cellSize    Size (px) of each grid cell.
 * @return A list of [FLine] objects suitable for [Box.lines].
 */
fun BooleanNetwork.drawAttractorBasins(
    attractors: List<Attractor>,
    samplesPerAttractor: Int = 6,
    cellSize: Double = 16.0,
): List<FLine> {
    val on  = FLine().apply { filled = true; stroked = false; color = vec(0.94, 0.93, 0.86, 1.0) }
    val off = FLine().apply { filled = true; stroked = false; color = vec(0.14, 0.14, 0.18, 1.0) }
    val sep = FLine().apply {
        stroked = true; filled = false
        thickness = 1.0; color = vec(1.0, 0.6, 0.2, 0.6)
    }
    val grid = FLine().apply {
        stroked = true; filled = false
        thickness = 0.4; color = vec(0.5, 0.5, 0.5, 0.20)
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
            // Column grid lines.
            for (t in 0..traj.size) {
                grid.moveTo(x0, t * cellSize); grid.lineTo(x0 + size * cellSize, t * cellSize)
            }
        }

        // Attractor separator line.
        val sepX = xOffset + basinList.size * (size + 1) * cellSize - cellSize * 0.5
        sep.moveTo(sepX, 0.0); sep.lineTo(sepX, maxRows * cellSize)

        xOffset += (basinList.size * (size + 1) + 2) * cellSize
    }

    return listOf(off, on, grid, sep)
}

// ============================================================
// NOTATION — HTML text output
// ============================================================

/**
 * Generate an HTML table listing all nodes with their names, input wiring,
 * and update rules — the network "truth table" at a glance.
 *
 * ```kotlin
 * display("notation", net.notateHtml())
 * ```
 */
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
    nodes.forEachIndexed { _, n ->
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

/**
 * Generate an HTML panel summarising all attractors: type, cycle states,
 * basin size, and per-node state labels.
 *
 * ```kotlin
 * display("attractors", net.notateAttractorsHtml(net.findAttractors()))
 * ```
 *
 * @param attractors  Result of [findAttractors].
 */
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

/**
 * Print a plain-text summary of all attractors to stdout — useful for
 * standalone (non-Field4) use.
 */
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

// ============================================================
// EXAMPLE NETWORKS
// ============================================================

/**
 * A 2-node network that produces a single period-4 limit cycle through all
 * four states:
 *
 *   A(t+1) = NOT B(t)
 *   B(t+1) = A(t)
 *
 * State sequence: [1,0] → [1,1] → [0,1] → [0,0] → [1,0] → …
 *
 * All four states form one attractor whose basin is the entire state space.
 */
fun toggleNetwork(): BooleanNetwork = BooleanNetwork(
    listOf(
        BoolNode("A", "NOT B", listOf(1)) { s -> !s[1] },
        BoolNode("B", "A",     listOf(0)) { s ->  s[0] },
    )
)

/**
 * A 3-node network with a single period-5 limit cycle:
 *
 *   A = B AND C
 *   B = A OR C
 *   C = NOT A
 *
 * All 8 states funnel into one attractor, making it a useful example for
 * trajectory unrolling and spacetime diagrams.
 */
fun threeNodeNetwork(): BooleanNetwork = BooleanNetwork(
    listOf(
        BoolNode("A", "B AND C",  listOf(1, 2)) { s ->  s[1] && s[2] },
        BoolNode("B", "A OR C",   listOf(0, 2)) { s ->  s[0] || s[2] },
        BoolNode("C", "NOT A",    listOf(0))    { s -> !s[0] },
    )
)

/**
 * Simplified 4-node segment-polarity network inspired by Drosophila:
 *
 *   en  (engrailed)  = wg
 *   wg  (wingless)   = en
 *   ptc (patched)    = NOT ci
 *   ci  (cubitus)    = ptc AND NOT en
 *
 * A classic example used in developmental biology Boolean modelling.
 */
fun segmentPolarityNetwork(): BooleanNetwork = BooleanNetwork(
    listOf(
        BoolNode("en",  "wg",             listOf(1))    { s ->  s[1] },
        BoolNode("wg",  "en",             listOf(0))    { s ->  s[0] },
        BoolNode("ptc", "NOT ci",         listOf(3))    { s -> !s[3] },
        BoolNode("ci",  "ptc AND NOT en", listOf(2, 0)) { s ->  s[2] && !s[0] },
    )
)

/**
 * A random N-K Kauffman network.
 *
 * Each node has exactly [k] randomly chosen inputs and a random Boolean
 * lookup table over those inputs.
 *
 * @param n    Number of nodes.
 * @param k    In-degree (number of inputs per node).
 * @param seed Random seed for reproducibility.
 */
fun kauffmanNetwork(n: Int = 5, k: Int = 2, seed: Long = 42L): BooleanNetwork {
    val rng = java.util.Random(seed)
    val nodeList = (0 until n).map { i ->
        val inputs = (0 until k).map { rng.nextInt(n) }
        val table  = BooleanArray(1 shl k) { rng.nextBoolean() }
        BoolNode(
            name   = "x$i",
            rule   = "random(${inputs.joinToString(",")})",
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

// ============================================================
// FIELD4 USAGE EXAMPLE  (paste into a box to see live output)
// ============================================================
/*
 * ─── Visualise a Boolean network interactively in Field4 ────────────────────
 *
 * Paste this snippet into a Field4 box and execute.  Each display(channel,…)
 * call populates a separate disclosure panel in the box's output area.
 *
 *   val net  = threeNodeNetwork()
 *   val init = booleanArrayOf(true, false, true)
 *
 *   // 1 ─ wiring diagram
 *   lines(net.drawNetworkGraph(), channel = "wiring",
 *         w = 500.0, h = 500.0, fitToSize = true)
 *
 *   // 2 ─ spacetime unrolling
 *   val traj = net.trajectory(init, steps = 16)
 *   lines(net.drawUnrolled(traj), channel = "spacetime",
 *         w = 480.0, h = 520.0, fitToSize = true)
 *
 *   // 3 ─ state-transition graph (works for size ≤ 6)
 *   lines(net.drawStateTransitionGraph(), channel = "states",
 *         w = 600.0, h = 600.0, fitToSize = true)
 *
 *   // 4 ─ attractor basin sketches
 *   val attrs = net.findAttractors()
 *   lines(net.drawAttractorBasins(attrs), channel = "basins",
 *         w = 800.0, h = 400.0, fitToSize = true)
 *
 *   // 5 ─ HTML notation panels
 *   display("notation", net.notateHtml())
 *   display("attractors", net.notateAttractorsHtml(attrs))
 *
 * ─────────────────────────────────────────────────────────────────────────────
 */

// ============================================================
// SMOKE TEST — verifies core behaviour without a Field4 runtime
// ============================================================

/*
FIELD_TEST
id: examples.boolean-network.smoke
runner: jvm-main
entrypoint: afield.examples.BooleanNetworkExplorer
tags: [smoke, examples, boolean-network, graph]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "Boolean network smoke tests passed"
*/
/**
 * Self-contained smoke test for the Boolean network library.
 *
 * Run via the jvm-main FIELD_TEST runner, or from the command line:
 * ```
 * ./gradlew runJvm --args="--class afield.examples.BooleanNetworkExplorer"
 * ```
 */
object BooleanNetworkExplorer {

    @JvmStatic
    fun main(args: Array<String>) {
        runTests()
    }

    /**
     * Execute all smoke tests.  Returns true on success; prints a summary and
     * throws on the first failure.
     */
    fun runTests(): Boolean {
        return try {
            println("Running Boolean network smoke tests...")
            testToggleNetwork()
            testThreeNodeNetwork()
            testSegmentPolarityNetwork()
            testKauffmanNetwork()
            testTrajectory()
            testTrajectoryUntilCycle()
            testAttractors()
            testDrawUnrolled()
            testDrawNetworkGraph()
            testDrawStateTransitionGraph()
            testDrawAttractorBasins()
            testNotation()
            println("Boolean network smoke tests passed")
            true
        } catch (e: Exception) {
            System.err.println("FAILED: ${e.message}")
            e.printStackTrace(System.err)
            false
        }
    }

    private fun testToggleNetwork() {
        val net = toggleNetwork()
        require(net.size == 2)
        // A(t+1) = NOT B(t),  B(t+1) = A(t)
        // [T,F] → [T,T] → [F,T] → [F,F] → [T,F] → …  (period-4 cycle)
        val s0 = booleanArrayOf(true, false)
        val s1 = net.step(s0)
        require(s1[0] && s1[1]) { "Step 1: expected [true,true], got ${net.stateString(s1)}" }
        val s2 = net.step(s1)
        require(!s2[0] && s2[1]) { "Step 2: expected [false,true], got ${net.stateString(s2)}" }
        val attractors = net.findAttractors()
        require(attractors.size == 1) { "Toggle network should have 1 attractor" }
        require(attractors[0].period == 4) { "Toggle attractor period should be 4, got ${attractors[0].period}" }
        println("  ✓ toggleNetwork: period-4 cycle, basin=${attractors[0].basin.size}")
    }

    private fun testThreeNodeNetwork() {
        val net = threeNodeNetwork()
        require(net.size == 3)
        val s = net.step(booleanArrayOf(false, false, false))
        // C = NOT A = true; B = A OR C = true; A = B AND C = true (using previous state)
        // Previous: A=0,B=0,C=0 → A'=B∧C=0∧0=0, B'=A∨C=0∨0=0, C'=¬A=¬0=1
        require(!s[0] && !s[1] && s[2]) {
            "threeNodeNetwork step from [0,0,0]: expected [0,0,1], got ${net.stateString(s)}"
        }
        println("  ✓ threeNodeNetwork step")
    }

    private fun testSegmentPolarityNetwork() {
        val net = segmentPolarityNetwork()
        require(net.size == 4)
        val attrs = net.findAttractors()
        require(attrs.isNotEmpty()) { "segmentPolarityNetwork should have attractors" }
        require(attrs.sumOf { it.basin.size } == 1 shl net.size) {
            "Basin sizes should cover all ${1 shl net.size} states"
        }
        println("  ✓ segmentPolarityNetwork: ${attrs.size} attractor(s)")
    }

    private fun testKauffmanNetwork() {
        val net = kauffmanNetwork(n = 5, k = 2, seed = 42L)
        require(net.size == 5)
        val attrs = net.findAttractors()
        require(attrs.isNotEmpty())
        require(attrs.sumOf { it.basin.size } == 1 shl net.size)
        println("  ✓ kauffmanNetwork(5,2,42): ${attrs.size} attractor(s), basin total ${attrs.sumOf { it.basin.size }}")
    }

    private fun testTrajectory() {
        val net = toggleNetwork()
        val traj = net.trajectory(booleanArrayOf(true, false), steps = 6)
        require(traj.size == 7) { "Expected 7 states, got ${traj.size}" }
        // [T,F] → [T,T] → [F,T] → [F,F] → [T,F] → [T,T] → [F,T] → …
        require(traj[0][0] && !traj[0][1]) { "traj[0] should be [T,F]" }
        require(traj[1][0] && traj[1][1])  { "traj[1] should be [T,T]" }
        require(!traj[2][0] && traj[2][1]) { "traj[2] should be [F,T]" }
        require(traj[4][0] && !traj[4][1]) { "traj[4] should equal traj[0]=[T,F]" }
        println("  ✓ trajectory: length ${traj.size}, period-4 cycling confirmed")
    }

    private fun testTrajectoryUntilCycle() {
        val net = toggleNetwork()
        val traj = net.trajectoryUntilCycle(booleanArrayOf(true, false))
        // Period-4 cycle: [T,F] → [T,T] → [F,T] → [F,F] → [T,F] (repeated)
        // Expect traj = [[T,F],[T,T],[F,T],[F,F],[T,F]] — the last state repeats traj[0]
        require(traj.size >= 5) { "Expected at least 5 states for the period-4 cycle, got ${traj.size}" }
        val last = traj.last()
        val repeated = traj.dropLast(1).any { st -> st.contentEquals(last) }
        require(repeated) { "Last state should repeat an earlier state" }
        println("  ✓ trajectoryUntilCycle detects the period-4 cycle (${traj.size} states)")
    }

    private fun testAttractors() {
        // The toggle network has exactly one attractor: a period-4 cycle
        // whose basin is the entire 4-state space.
        val net = toggleNetwork()
        val attrs = net.findAttractors()
        require(attrs.size == 1) { "Toggle network should have exactly 1 attractor, got ${attrs.size}" }
        val totalBasin = attrs.sumOf { it.basin.size }
        require(totalBasin == 1 shl net.size) {
            "Sum of basin sizes ($totalBasin) should equal ${1 shl net.size}"
        }
        println("  ✓ findAttractors: ${attrs.size} attractor(s), total basin $totalBasin")

        // State encoding round-trip.
        val state = booleanArrayOf(true, false, true)
        val net3 = threeNodeNetwork()
        val i = net3.stateToInt(state)
        val back = net3.intToState(i)
        require(state.contentEquals(back)) { "stateToInt/intToState round-trip failed" }
        println("  ✓ stateToInt/intToState round-trip")
    }

    private fun testDrawUnrolled() {
        val net = threeNodeNetwork()
        val traj = net.trajectory(booleanArrayOf(true, false, true), steps = 8)
        val flines = net.drawUnrolled(traj)
        require(flines.isNotEmpty())
        require(flines.all { it.nodes.isNotEmpty() }) { "All FLines should be non-empty" }
        println("  ✓ drawUnrolled: ${flines.size} FLine(s) for ${traj.size}×${net.size} grid")
    }

    private fun testDrawNetworkGraph() {
        val net = threeNodeNetwork()
        val flines = net.drawNetworkGraph()
        require(flines.isNotEmpty())
        println("  ✓ drawNetworkGraph: ${flines.size} FLine(s)")
    }

    private fun testDrawStateTransitionGraph() {
        val net = toggleNetwork()   // size=2 → 4 states, comfortably ≤ 6
        val flines = net.drawStateTransitionGraph()
        require(flines.isNotEmpty())
        // Large networks should return empty.
        val bigNet = kauffmanNetwork(n = 8)
        require(bigNet.drawStateTransitionGraph().isEmpty()) { "size>6 should return empty list" }
        println("  ✓ drawStateTransitionGraph: ${flines.size} FLine(s)")
    }

    private fun testDrawAttractorBasins() {
        val net = threeNodeNetwork()
        val attrs = net.findAttractors()
        val flines = net.drawAttractorBasins(attrs, samplesPerAttractor = 4)
        require(flines.isNotEmpty())
        println("  ✓ drawAttractorBasins: ${flines.size} FLine(s)")
    }

    private fun testNotation() {
        val net = threeNodeNetwork()
        val tableHtml = net.notateHtml()
        require("<table" in tableHtml) { "notateHtml should produce a <table>" }
        require("A" in tableHtml && "B" in tableHtml && "C" in tableHtml)

        val attrs = net.findAttractors()
        val attrHtml = net.notateAttractorsHtml(attrs)
        require("Attractor" in attrHtml)
        require("basin" in attrHtml)
        println("  ✓ notateHtml (${tableHtml.length} chars)  notateAttractorsHtml (${attrHtml.length} chars)")

        // stateTable plain text
        val table = net.stateTable(booleanArrayOf(true, false, true))
        require("Node" in table && "Rule" in table)
        println("  ✓ stateTable plain-text output")
    }
}
