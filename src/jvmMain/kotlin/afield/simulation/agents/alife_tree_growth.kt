@file:Suppress("unused")

package afield.simulation.agents

import afield.geometry.path.FLine
import afield.graphics.color.Colormaps
import afield.graphics.render.lineTo
import afield.graphics.render.moveTo
import afield.media.image.Img
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Self-organizing tree growth: buds competing for light, season by season.
 *
 * The method is Palubicki, Horel, Longay, Runions, Lane, Měch and Prusinkiewicz, "Self-organizing tree
 * models for image synthesis" (SIGGRAPH 2009), with the Borchert–Honda allocation of growth resource
 * (Borchert and Honda, "Control of development in the bifurcating branch system of Tabebuia rosea",
 * 1984), as walked through by Chema Guerra's "Growing trees" (brashandplucky.com, 2026-10-15). This file
 * is a 2-D version of it, drawn in the same y-down coordinates as [spaceColonization], so its output goes
 * through [rasterizeFLines] and the rest of the family unchanged.
 *
 * A season is five passes over the tree:
 *
 *  1. **Shadow.** Every node that still carries a bud casts shadow into a widening wedge of grid cells
 *     below it, weaker with depth ([ShadowGrid]). Shadows add.
 *  2. **Light.** Each bud reads `Q = max(0, 1 - (s - own))` at its cell: full light, minus everyone
 *     else's shadow. It also reads the direction of the least-shaded cell ahead of it.
 *  3. **Basipetal sum.** Light is summed from the tips toward the root, so every node knows the light its
 *     whole subtree gathers.
 *  4. **Acropetal allocation.** The root gets `v = α · Q_root` units of growth resource and passes it back
 *     up. At every node it is split between the continuing axis and the lateral side by Borchert–Honda:
 *     `v_main = v · λQ_m / (λQ_m + (1−λ)Q_l)`. At `λ = 0.5` resource follows light; above it the leader
 *     wins (apical dominance).
 *  5. **Growth and shedding.** A bud given `v ≥ 1` grows a shoot of `⌊v⌋` metamers (capped). A lateral
 *     branch whose light per metamer falls under a threshold is shed, after a grace period.
 */

/**
 * The 2-D stand-in for Palubicki's voxel shadow propagation: a grid of non-negative shadow values.
 *
 * A caster at cell `(cx, cy)` adds `a · b^-q` to every cell `(cx + i, cy + q)` with `|i| ≤ q`, for
 * `q = 0 .. depth`: a wedge widening one cell per row below it (y grows downward), weaker with depth.
 * Shadow only ever *adds*, which is what makes light monotone under shading — a property the tests pin.
 *
 * @param width cells across
 * @param height cells down
 * @param a the shadow a caster puts on its own cell
 * @param b the decay base per row; must exceed 1
 * @param depth how many rows below a caster its shadow reaches
 */
class ShadowGrid(
    val width: Int,
    val height: Int,
    val a: Double = 1.0,
    val b: Double = 2.0,
    val depth: Int = 6,
) {
    init {
        require(width >= 1 && height >= 1) { "grid must be at least 1x1, got ${width}x$height" }
        require(b > 1.0) { "shadow decay base b must exceed 1, got $b" }
        require(depth >= 0) { "depth must be >= 0, got $depth" }
    }

    /** Shadow per cell, row-major. */
    val s = DoubleArray(width * height)

    /** The cell column of world x, unclamped. */
    fun col(x: Double): Int = floor(x).toInt()

    /** The cell row of world y, unclamped. */
    fun row(y: Double): Int = floor(y).toInt()

    /** Whether a world point lies inside the grid. */
    fun inside(x: Double, y: Double): Boolean = col(x) in 0 until width && row(y) in 0 until height

    /** Shadow at a cell; outside the grid there is no shadow (open sky), except below the ground row. */
    fun at(cx: Int, cy: Int): Double = if (cx in 0 until width && cy in 0 until height) s[cy * width + cx] else 0.0

    /** Add one caster's wedge at world `(x, y)`. */
    fun cast(x: Double, y: Double) {
        val cx = col(x)
        val cy = row(y)
        for (q in 0..depth) {
            val yy = cy + q
            if (yy !in 0 until height) continue
            val v = a * b.pow(-q.toDouble())
            for (i in -q..q) {
                val xx = cx + i
                if (xx in 0 until width) s[yy * width + xx] += v
            }
        }
    }

    /**
     * The light a bud at `(x, y)` receives, `max(0, 1 - (s - own))` clamped to `0 .. 1`. Pass
     * `ownCaster = true` when the bud's own node is one of the casters, so its own shadow is removed.
     */
    fun lightAt(x: Double, y: Double, ownCaster: Boolean = true): Double {
        val shade = at(col(x), row(y)) - if (ownCaster && inside(x, y)) a else 0.0
        return (1.0 - shade).coerceIn(0.0, 1.0)
    }

    /** Clear every cell. */
    fun clear() = s.fill(0.0)
}

/**
 * The parameters of [TreeGrowth]. Defaults grow a broad deciduous-looking crown in 30 seasons on the
 * default 120 × 100 grid.
 *
 * @param lambda Borchert–Honda apical control `0 .. 1`: 0.5 sends resource where the light is, more
 *   favours the leader, less favours laterals
 * @param alpha resource per unit of light gathered at the root
 * @param maxShoot the most metamers one bud may grow in a season
 * @param metamerLength internode length, in grid cells
 * @param branchAngle radians between an axillary bud and its parent's heading
 * @param lightWeight how strongly a new shoot turns toward the least-shaded cell ahead of it
 * @param tropism how strongly shoots turn toward the sky (negative y); negative values droop
 * @param noise random turning per metamer, in the same units as the two weights above
 * @param perceptionAngle half-angle of the cone a bud searches for light in
 * @param shedThreshold a lateral branch is shed when its light per metamer falls under this
 * @param shedGrace seasons a branch is exempt from shedding after it starts
 * @param pipeExponent the `n` in `r_parent^n = Σ r_child^n`; 2 keeps cross-section area at forks
 * @param tipRadius the radius of a bare tip, in grid cells
 * @param shadowA the shadow a bud casts on its own cell ([ShadowGrid.a])
 * @param shadowB the shadow decay base per row below it ([ShadowGrid.b])
 * @param shadowDepth how many rows a bud's shadow reaches ([ShadowGrid.depth])
 */
data class TreeGrowthParams(
    val lambda: Double = 0.5,
    val alpha: Double = 3.0,
    val maxShoot: Int = 3,
    val metamerLength: Double = 1.0,
    val branchAngle: Double = Math.toRadians(40.0),
    val lightWeight: Double = 0.35,
    val tropism: Double = 0.3,
    val noise: Double = 0.12,
    val perceptionAngle: Double = Math.toRadians(60.0),
    val shedThreshold: Double = 0.05,
    val shedGrace: Int = 3,
    val pipeExponent: Double = 2.0,
    val tipRadius: Double = 0.12,
    val shadowA: Double = 0.2,
    val shadowB: Double = 1.6,
    val shadowDepth: Int = 8,
) {
    init {
        require(lambda in 0.0..1.0) { "lambda must be in 0..1, got $lambda" }
        require(alpha > 0.0) { "alpha must be > 0, got $alpha" }
        require(maxShoot >= 1) { "maxShoot must be >= 1, got $maxShoot" }
        require(metamerLength > 0.0) { "metamerLength must be > 0, got $metamerLength" }
        require(pipeExponent > 0.0) { "pipeExponent must be > 0, got $pipeExponent" }
    }
}

/**
 * What one call of [TreeGrowth.step] did.
 *
 * @param season the season just completed, from 1
 * @param buds buds that measured light this season
 * @param baseLight `Q` summed over the whole tree, at the root
 * @param baseResource `α · baseLight`, the resource handed to the root
 * @param deliveredResource the resource that reached buds; equal to [baseResource] up to float rounding,
 *   because Borchert–Honda only ever splits
 * @param terminalResource the part of [deliveredResource] that reached terminal buds, extending axes
 *   rather than starting branches
 * @param grown metamers added
 * @param shedBranches lateral branches removed
 * @param shedMetamers metamers removed with them
 */
data class SeasonReport(
    val season: Int,
    val buds: Int,
    val baseLight: Double,
    val baseResource: Double,
    val deliveredResource: Double,
    val terminalResource: Double,
    val grown: Int,
    val shedBranches: Int,
    val shedMetamers: Int,
) {
    /** `|delivered − base| / base`, 0 when nothing was handed out. */
    val conservationError: Double get() =
        if (baseResource <= 0.0) abs(deliveredResource) else abs(deliveredResource - baseResource) / baseResource
}

/**
 * A tree grown by light competition and resource flow, one season per [step].
 *
 * Nodes are metamers: an internode from its parent's position to its own, with a **terminal bud** at its
 * tip while it has no continuing (`main`) child and an **axillary bud** while it has no lateral child. The
 * parent index of every node is smaller than its own, which is kept through shedding, so the same check
 * [ColonizationTree.isTree] makes holds here ([isTree]).
 *
 * Mutable, so [step] returns `this` and fits [Box.animateEvolution]'s `step` directly.
 *
 * ```
 * val tree = TreeGrowth(seed = 2L)
 * repeat(30) { tree.step() }
 * println(tree)                 // nodes, buds, seasons, shed so far
 * println(tree.isTree())        // true
 * showImg(tree.render(TreeView.LIGHT))
 * ```
 *
 * @param width grid cells across; the tree is planted at the bottom centre
 * @param height grid cells down
 */
class TreeGrowth(
    val width: Int = 120,
    val height: Int = 100,
    val params: TreeGrowthParams = TreeGrowthParams(),
    seed: Long = 1L,
    rootX: Double = width / 2.0,
    rootY: Double = height - 1.0,
) {
    private val rng = kotlin.random.Random(seed)

    /** The shadow grid of the last season, for drawing. */
    val shadow = ShadowGrid(width, height, params.shadowA, params.shadowB, params.shadowDepth)

    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val par = ArrayList<Int>()
    private val heading = ArrayList<Double>()
    private val born = ArrayList<Int>()
    /** Whether this node is its parent's lateral child (false: it continues the parent's axis). */
    private val lateral = ArrayList<Boolean>()
    /** Which side the axillary bud of this node points, +1 or -1. */
    private val side = ArrayList<Int>()
    private val mainChild = ArrayList<Int>()
    private val latChild = ArrayList<Int>()
    private val subtreeLight = ArrayList<Double>()
    private val resourceIn = ArrayList<Double>()
    private val termLight = ArrayList<Double>()
    private val axLight = ArrayList<Double>()
    private val termRes = ArrayList<Double>()
    private val axRes = ArrayList<Double>()

    /** Seasons completed. */
    var season: Int = 0
        private set

    /** Every season's report, in order. */
    val history = ArrayList<SeasonReport>()

    /** Branches and metamers shed so far. */
    var shedBranchesTotal = 0
        private set
    var shedMetamersTotal = 0
        private set

    init {
        require(width >= 8 && height >= 8) { "grid must be at least 8x8, got ${width}x$height" }
        addNode(rootX, rootY, -1, -PI / 2.0, lateral = false)
        measure()
    }

    private fun addNode(x: Double, y: Double, p: Int, h: Double, lateral: Boolean): Int {
        val i = xs.size
        xs.add(x); ys.add(y); par.add(p); heading.add(h); born.add(season)
        this.lateral.add(lateral)
        side.add(if (rng.nextBoolean()) 1 else -1)
        mainChild.add(-1); latChild.add(-1)
        subtreeLight.add(0.0); resourceIn.add(0.0)
        termLight.add(0.0); axLight.add(0.0); termRes.add(0.0); axRes.add(0.0)
        if (p >= 0) {
            if (lateral) latChild[p] = i else mainChild[p] = i
            // Alternate phyllotaxis along an axis: the next axillary bud points to the other side.
            if (!lateral) side[i] = -side[p]
        }
        return i
    }

    /** How many metamers (the root counts as one). */
    val nodeCount: Int get() = xs.size

    /** Parent→child edges; `nodeCount - 1` for a tree. */
    val edgeCount: Int get() = par.count { it >= 0 }

    /** Live buds: terminal and axillary together. */
    val budCount: Int get() = (0 until nodeCount).sumOf { (if (mainChild[it] < 0) 1 else 0) + (if (latChild[it] < 0) 1 else 0) }

    /** Node coordinates and parent, as read-only views. */
    fun x(i: Int): Double = xs[i]
    fun y(i: Int): Double = ys[i]
    fun parent(i: Int): Int = par[i]

    /** The light the subtree rooted at node [i] gathered last season. */
    fun light(i: Int): Double = subtreeLight[i]

    /** The resource that flowed *into* node [i] last season. */
    fun resource(i: Int): Double = resourceIn[i]

    /** Light read by the terminal / axillary bud of node [i] last season (0 when it has no such bud). */
    fun terminalBudLight(i: Int): Double = termLight[i]
    fun axillaryBudLight(i: Int): Double = axLight[i]

    /**
     * `true` when the structure is a tree: one root, `nodeCount - 1` edges, every parent index valid and
     * smaller than its child (so no cycle), and the child links agree with the parent links.
     */
    fun isTree(): Boolean {
        if (par.count { it < 0 } != 1 || par[0] != -1) return false
        if (edgeCount != nodeCount - 1) return false
        for (i in 1 until nodeCount) {
            val p = par[i]
            if (p < 0 || p >= i) return false
            val link = if (lateral[i]) latChild[p] else mainChild[p]
            if (link != i) return false
        }
        return true
    }

    /** Branch radius per node by the pipe model, accumulated from [TreeGrowthParams.tipRadius] at the tips. */
    fun radii(): DoubleArray {
        val n = params.pipeExponent
        val acc = DoubleArray(nodeCount)
        for (i in nodeCount - 1 downTo 0) {
            var sum = 0.0
            val m = mainChild[i]; val l = latChild[i]
            if (m >= 0) sum += acc[m]
            if (l >= 0) sum += acc[l]
            acc[i] = if (sum == 0.0) params.tipRadius.pow(n) else sum
        }
        return DoubleArray(nodeCount) { acc[it].pow(1.0 / n) }
    }

    /** Metamers in the subtree rooted at each node. */
    fun subtreeSizes(): IntArray {
        val c = IntArray(nodeCount) { 1 }
        for (i in nodeCount - 1 downTo 1) c[par[i]] += c[i]
        return c
    }

    /** One [FLine] per internode, parent position to node position. */
    fun toFLines(z: Number = 0): List<FLine> = (1 until nodeCount).map { i ->
        val p = par[i]
        FLine().also { it.moveTo(xs[p], ys[p], z); it.lineTo(xs[i], ys[i], z) }
    }

    /** Recompute [shadow] from the current buds, without growing. */
    fun castShadows() {
        shadow.clear()
        for (i in 0 until nodeCount) if (mainChild[i] < 0 || latChild[i] < 0) shadow.cast(xs[i], ys[i])
    }

    /**
     * The light-seeking direction at `(x, y)` for heading [h]: the mean of seven directions across the
     * perception cone, each weighted by the light (`1 − shadow`) three cells along it. In open sky every
     * weight is equal and this is [h] itself, so the tree has no handedness of its own; a picked
     * "least-shaded cell" with ties broken by order would lean every tree the same way.
     */
    private fun lightDirection(x: Double, y: Double, h: Double): Double {
        var sx = 0.0
        var sy = 0.0
        val k = 7
        for (j in 0 until k) {
            val a = h - params.perceptionAngle + 2.0 * params.perceptionAngle * j / (k - 1)
            val px = x + cos(a) * 3.0
            val py = y + sin(a) * 3.0
            val w = if (shadow.inside(px, py)) (1.0 - shadow.at(shadow.col(px), shadow.row(py))).coerceAtLeast(0.0) else 0.0
            sx += w * cos(a)
            sy += w * sin(a)
        }
        return if (hypot(sx, sy) < 1e-9) h else atan2(sy, sx)
    }

    private fun blend(h: Double, v: Double): Double {
        var dx = cos(h) + params.lightWeight * cos(v) + params.noise * (rng.nextDouble() * 2 - 1)
        var dy = sin(h) + params.lightWeight * sin(v) - params.tropism + params.noise * (rng.nextDouble() * 2 - 1)
        val d = hypot(dx, dy)
        if (d < 1e-9) return h
        dx /= d; dy /= d
        return atan2(dy, dx)
    }

    /**
     * Passes 1–3 of a season without growing: recast [shadow] from the current buds, read every bud's
     * light, and sum it basipetally. [step] calls it before allocating, and again after growing and
     * shedding so that [light], [terminalBudLight] and [render] describe the tree as it now stands.
     *
     * @return how many buds read light
     */
    fun measure(): Int {
        val n0 = nodeCount
        castShadows()
        var buds = 0
        for (i in 0 until n0) {
            val hasBud = mainChild[i] < 0 || latChild[i] < 0
            val q = if (hasBud && shadow.inside(xs[i], ys[i])) shadow.lightAt(xs[i], ys[i]) else 0.0
            termLight[i] = if (mainChild[i] < 0) q.also { buds++ } else 0.0
            axLight[i] = if (latChild[i] < 0) q.also { buds++ } else 0.0
        }
        // Basipetal: children have larger indices than parents, so one reverse sweep sums every subtree.
        for (i in n0 - 1 downTo 0) {
            val m = mainChild[i]; val l = latChild[i]
            subtreeLight[i] = (if (m >= 0) subtreeLight[m] else termLight[i]) + (if (l >= 0) subtreeLight[l] else axLight[i])
        }
        return buds
    }

    /** Advance one season: shadow, light, basipetal sum, Borchert–Honda allocation, growth, shedding. */
    fun step(): TreeGrowth {
        season++
        val n0 = nodeCount
        // 1–3. Shadow, bud light, basipetal sum.
        val buds = measure()
        // 4. Acropetal Borchert–Honda allocation. A forward sweep reaches every parent before its children.
        val lam = params.lambda
        val baseLight = subtreeLight[0]
        val baseResource = params.alpha * baseLight
        var delivered = 0.0
        var terminal = 0.0
        resourceIn[0] = baseResource
        for (i in 1 until n0) resourceIn[i] = 0.0
        for (i in 0 until n0) {
            val v = resourceIn[i]
            val m = mainChild[i]; val l = latChild[i]
            val qm = if (m >= 0) subtreeLight[m] else termLight[i]
            val ql = if (l >= 0) subtreeLight[l] else axLight[i]
            // Borchert–Honda. At the extremes (λ = 0 or 1 against a dark side) the weighted denominator can
            // vanish while light is still present; fall back to following light, then to the main axis,
            // so the split always sums to v and nothing is lost.
            val denom = lam * qm + (1 - lam) * ql
            val vm = when {
                denom > 0.0 -> v * lam * qm / denom
                qm + ql > 0.0 -> v * qm / (qm + ql)
                else -> v
            }
            val vl = v - vm
            if (m >= 0) resourceIn[m] = vm else { termRes[i] = vm; delivered += vm; terminal += vm }
            if (l >= 0) resourceIn[l] = vl else { axRes[i] = vl; delivered += vl }
        }
        // 5a. Growth: buds existing at the start of the season, each given v >= 1, grow floor(v) metamers.
        var grown = 0
        for (i in 0 until n0) {
            val tv = if (mainChild[i] < 0) termRes[i] else 0.0
            val av = if (latChild[i] < 0) axRes[i] else 0.0
            if (mainChild[i] < 0 && tv >= 1.0) grown += growShoot(i, heading[i], lateral = false, count = min(params.maxShoot, tv.toInt()))
            if (latChild[i] < 0 && av >= 1.0) {
                val h = heading[i] + side[i] * params.branchAngle
                grown += growShoot(i, h, lateral = true, count = min(params.maxShoot, av.toInt()))
            }
        }
        // New nodes have not measured anything yet.
        for (i in n0 until nodeCount) { termLight[i] = 0.0; axLight[i] = 0.0; termRes[i] = 0.0; axRes[i] = 0.0 }
        for (i in 0 until n0) { if (mainChild[i] >= 0) termRes[i] = 0.0; if (latChild[i] >= 0) axRes[i] = 0.0 }
        // 5b. Shedding.
        val (branches, metamers) = shed()
        shedBranchesTotal += branches
        shedMetamersTotal += metamers
        val r = SeasonReport(season, buds, baseLight, baseResource, delivered, terminal, grown, branches, metamers)
        history.add(r)
        measure()
        return this
    }

    /** Advance [seasons] seasons. */
    fun stepTimes(seasons: Int): TreeGrowth { repeat(seasons) { step() }; return this }

    private fun growShoot(from: Int, h0: Double, lateral: Boolean, count: Int): Int {
        var at = from
        var h = h0
        var isLat = lateral
        var made = 0
        repeat(count) {
            val v = lightDirection(xs[at], ys[at], h)
            h = blend(h, v)
            val nx = xs[at] + cos(h) * params.metamerLength
            val ny = ys[at] + sin(h) * params.metamerLength
            if (!shadow.inside(nx, ny)) return made
            at = addNode(nx, ny, at, h, isLat)
            isLat = false
            made++
        }
        return made
    }

    /**
     * Remove every lateral branch, past its grace period, whose last-season light per metamer is under
     * the threshold, then compact the arrays keeping parent < child. Returns (branches, metamers) removed.
     */
    private fun shed(): Pair<Int, Int> {
        val sizes = subtreeSizes()
        val dead = BooleanArray(nodeCount)
        var branches = 0
        for (i in 1 until nodeCount) {
            if (dead[par[i]]) { dead[i] = true; continue }
            if (!lateral[i] || season - born[i] < params.shedGrace) continue
            if (subtreeLight[i] / sizes[i] < params.shedThreshold) { dead[i] = true; branches++ }
        }
        val removed = dead.count { it }
        if (removed == 0) return 0 to 0
        val map = IntArray(nodeCount) { -1 }
        var k = 0
        for (i in 0 until nodeCount) if (!dead[i]) map[i] = k++
        fun <T> ArrayList<T>.compact() {
            var w = 0
            for (i in indices) if (!dead[i]) this[w++] = this[i]
            while (size > w) removeAt(size - 1)
        }
        val newPar = ArrayList<Int>(k); val newMain = ArrayList<Int>(k); val newLat = ArrayList<Int>(k)
        for (i in 0 until nodeCount) if (!dead[i]) {
            newPar.add(if (par[i] < 0) -1 else map[par[i]])
            newMain.add(if (mainChild[i] < 0) -1 else map[mainChild[i]])
            newLat.add(if (latChild[i] < 0) -1 else map[latChild[i]])
        }
        for (l in listOf(xs, ys, subtreeLight, resourceIn, termLight, axLight, termRes, axRes, heading)) l.compact()
        born.compact(); lateral.compact(); side.compact()
        par.clear(); par.addAll(newPar)
        mainChild.clear(); mainChild.addAll(newMain)
        latChild.clear(); latChild.addAll(newLat)
        return branches to removed
    }

    override fun toString(): String =
        "TreeGrowth(season $season, $nodeCount metamers, $budCount buds, " +
            "$shedBranchesTotal branches / $shedMetamersTotal metamers shed, λ = ${params.lambda})"

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    /**
     * Draw the tree into an RGB [Img] framed by the whole grid, so successive seasons share one frame.
     * Branch thickness follows the pipe model; colour follows [view].
     *
     * @param scale pixels per grid cell
     */
    fun render(view: TreeView = TreeView.STRUCTURE, scale: Int = 4): Img {
        require(scale >= 1) { "scale must be >= 1, got $scale" }
        val w = width * scale
        val h = height * scale
        val img = Img.zeros(w, h, 3)
        val bg = floatArrayOf(0.96f, 0.95f, 0.91f)
        if (view == TreeView.SHADOW) {
            for (py in 0 until h) for (px in 0 until w) {
                val light = (1.0 - shadow.at(px / scale, py / scale)).coerceIn(0.0, 1.0)
                val c = Colormaps.sample("magma", 0.15 + 0.85 * light)
                val o = (py * w + px) * 3
                img.data[o] = c.r.toFloat(); img.data[o + 1] = c.g.toFloat(); img.data[o + 2] = c.b.toFloat()
            }
        } else {
            for (p in 0 until w * h) { img.data[p * 3] = bg[0]; img.data[p * 3 + 1] = bg[1]; img.data[p * 3 + 2] = bg[2] }
        }
        val r = radii()
        val maxRes = (1 until nodeCount).maxOfOrNull { resourceIn[it] }?.coerceAtLeast(1e-9) ?: 1.0
        for (i in 1 until nodeCount) {
            val p = par[i]
            val col: FloatArray = when (view) {
                TreeView.RESOURCE -> {
                    // log scale: the trunk carries hundreds of units, a twig a fraction of one
                    val t = if (resourceIn[i] <= 0.0) 0.0 else (kotlin.math.ln(1.0 + resourceIn[i]) / kotlin.math.ln(1.0 + maxRes))
                    Colormaps.sample("viridis", t).let { floatArrayOf(it.r.toFloat(), it.g.toFloat(), it.b.toFloat()) }
                }
                TreeView.SHADOW -> floatArrayOf(0.85f, 0.95f, 0.75f)
                else -> floatArrayOf(0.30f, 0.22f, 0.15f)
            }
            stroke(img, xs[p] * scale, ys[p] * scale, xs[i] * scale, ys[i] * scale, max(0.5, r[i] * scale), col)
        }
        if (view == TreeView.LIGHT) {
            for (i in 0 until nodeCount) {
                val hasBud = mainChild[i] < 0 || latChild[i] < 0
                if (!hasBud) continue
                val q = max(termLight[i], axLight[i])
                val c = Colormaps.sample("plasma", q)
                disc(img, xs[i] * scale, ys[i] * scale, 0.45 * scale + 0.8, floatArrayOf(c.r.toFloat(), c.g.toFloat(), c.b.toFloat()))
            }
        }
        return img
    }

    private fun disc(img: Img, cx: Double, cy: Double, rad: Double, c: FloatArray) {
        val x0 = floor(cx - rad).toInt(); val x1 = floor(cx + rad).toInt() + 1
        val y0 = floor(cy - rad).toInt(); val y1 = floor(cy + rad).toInt() + 1
        val r2 = rad * rad
        for (py in max(0, y0)..min(img.height - 1, y1)) for (px in max(0, x0)..min(img.width - 1, x1)) {
            val dx = px + 0.5 - cx; val dy = py + 0.5 - cy
            if (dx * dx + dy * dy <= r2) {
                val o = (py * img.width + px) * 3
                img.data[o] = c[0]; img.data[o + 1] = c[1]; img.data[o + 2] = c[2]
            }
        }
    }

    private fun stroke(img: Img, x0: Double, y0: Double, x1: Double, y1: Double, rad: Double, c: FloatArray) {
        val len = hypot(x1 - x0, y1 - y0)
        val steps = max(1, (len / max(0.5, rad * 0.5)).toInt())
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            disc(img, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, rad, c)
        }
    }
}

/** What [TreeGrowth.render] colours by. */
enum class TreeView {
    /** Bark-brown branches, thickness by the pipe model. */
    STRUCTURE,
    /** Branches plus every bud as a dot coloured by the light it read (plasma, dark = shaded). */
    LIGHT,
    /** Branches coloured by the resource flowing through them, log scale (viridis). */
    RESOURCE,
    /** The shadow grid itself behind pale branches (magma, bright = lit). */
    SHADOW,
}
