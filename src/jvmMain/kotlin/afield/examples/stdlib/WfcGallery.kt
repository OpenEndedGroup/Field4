package afield.examples.stdlib

import afield.stdlib.FloatOverlappingTileSet
import afield.stdlib.Img
import afield.stdlib.PngTileSet
import afield.stdlib.WFC_EAST
import afield.stdlib.WFC_SOUTH
import afield.stdlib.WfcNeighborhood
import afield.stdlib.WfcRules
import afield.stdlib.WfcSolver
import afield.stdlib.WfcStatus
import afield.stdlib.WfcSymmetry
import afield.stdlib.expandSymmetry
import afield.stdlib.colormap
import afield.stdlib.floatsToImg
import afield.stdlib.gaussianBlur
import afield.stdlib.isoContourFLines
import afield.stdlib.hstack
import afield.stdlib.montage
import afield.stdlib.possibilityCountImg
import afield.stdlib.resolvedFraction
import afield.stdlib.savePng
import afield.stdlib.sliceIntoOverlappingPatterns
import afield.stdlib.sliceIntoOverlappingPatterns2D
import afield.stdlib.sliceIntoUniqueTiles
import afield.stdlib.sliceIntoUniqueTiles2D
import afield.stdlib.solveWithRetries
import afield.stdlib.toBufferedImage
import afield.stdlib.toImg
import afield.stdlib.upscaleNearest
import afield.stdlib.waveToImg
import afield.stdlib.wfcRules
import java.io.File
import kotlin.math.exp
import kotlin.system.exitProcess

/*
FIELD_TEST
id: stdlib.wfc.gallery
runner: jvm-main
entrypoint: afield.examples.stdlib.WfcGallery
tags: [smoke, stdlib, wfc, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "wfc gallery rendered"
*/

/**
 * Renders — and *verifies* — the figures behind [public/docs/wfc_examples.md].
 *
 * Every figure on that page is produced by running the real solver. This object is where the
 * running happens headlessly, so the page's claims are checked by a build step rather than by
 * eyeballing: for each generated grid it asserts the invariant that actually matters, which is that
 * **every adjacent pair in a solved output is a pair the rules allow** ([checkOutputSatisfiesRules]).
 * That single check is what separates "WFC produced a picture" from "WFC produced a *correct*
 * picture", and it is the one a uniform-noise or unconstrained result fails.
 *
 * It also guards against the two failure modes that look like success in a thumbnail: an output
 * that collapsed to one tile everywhere, and an output that ignores the source's local structure.
 * [checkNotDegenerate] requires several distinct tiles with no single tile dominating, and
 * [checkStructureReused] requires that the set of terrain *edges* in the output is a subset of the
 * set in the source — the human-legible form of "it reused the source's structure".
 * [checkSymmetric] asserts the adjacency relation the solver needs in order to be sound at all.
 *
 * As a FIELD_TEST (`stdlib.wfc.gallery`) it renders to a temp dir and writes no committed assets.
 * Pass an output dir as `args[0]` (or set `WFC_GALLERY_DIR`) to regenerate committed captures.
 */
object WfcGallery {

    // ==========================================================================================
    // Shared source material: a terrain map, built rather than borrowed
    // ==========================================================================================

    /** Terrain IDs, in the order that makes them a *chain*: each may only touch its neighbours. */
    const val DEEP = 0
    const val SHALLOW = 1
    const val SAND = 2
    const val GRASS = 3
    const val FOREST = 4
    const val TERRAIN_COUNT = 5

    /** ARGB, opaque. Chosen so the five bands are unambiguous at one pixel per cell. */
    val TERRAIN_COLORS = intArrayOf(
        0xFF14324F.toInt(), // deep water
        0xFF2F7BA8.toInt(), // shallow water
        0xFFD9C88C.toInt(), // sand
        0xFF4E8B3C.toInt(), // grass
        0xFF1F4A24.toInt()  // forest
    )

    val TERRAIN_NAMES = arrayOf("deep", "shallow", "sand", "grass", "forest")

    private fun bump(u: Double, v: Double, cx: Double, cy: Double, r: Double): Double {
        val dx = u - cx
        val dy = v - cy
        return exp(-(dx * dx + dy * dy) / (2.0 * r * r))
    }

    /**
     * A quantised smooth elevation field: three overlapping bumps sliced into five bands.
     *
     * Quantising a field is what makes this good WFC source material: the set of band-to-band edges
     * it contains is small and knowable, so "did WFC reuse the source's structure" becomes a question
     * with a checkable answer (see [checkStructureReused]).
     *
     * Note that a coarse *sampling* of a smooth field does not only put neighbouring bands next to
     * each other. Where the gradient is steep, one cell step can jump two bands, so this map really
     * does contain a few band-skipping edges. That is a property of the sampling, not a bug, and the
     * verification derives the source's edge set rather than assuming what it ought to be.
     */
    fun terrainMap(w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x / (w - 1.0)
            val v = y / (h - 1.0)
            val e = 1.00 * bump(u, v, 0.30, 0.40, 0.25) +
                0.90 * bump(u, v, 0.70, 0.62, 0.21) +
                0.55 * bump(u, v, 0.82, 0.22, 0.13) -
                // An inland lake. Without it every water cell in the source sits on the map border,
                // so water has very few observed contexts and a Moore neighborhood drops it entirely.
                0.62 * bump(u, v, 0.34, 0.44, 0.085)
            out[x + y * w] = when {
                e < 0.16 -> DEEP
                e < 0.32 -> SHALLOW
                e < 0.44 -> SAND
                e < 0.74 -> GRASS
                else -> FOREST
            }
        }
        return out
    }

    /**
     * One 8x8 tile per terrain: a flat base plus a *fixed* dither.
     *
     * The dither must be a function of `(terrain, px, py)` only. Anything that varies with position
     * in the source image would make every cell a different unique tile, and
     * `sliceIntoUniqueTiles` would report hundreds of tiles instead of five.
     */
    fun terrainTile(terrain: Int, tileSide: Int): IntArray {
        val base = TERRAIN_COLORS[terrain]
        val r = (base shr 16) and 0xff
        val g = (base shr 8) and 0xff
        val b = base and 0xff
        val out = IntArray(tileSide * tileSide)
        for (py in 0 until tileSide) for (px in 0 until tileSide) {
            val speckle = ((px * 3 + py * 5 + terrain * 7) % 11) < 2
            val d = if (speckle) 22 else 0
            val rr = (r + d).coerceAtMost(255)
            val gg = (g + d).coerceAtMost(255)
            val bb = (b + d).coerceAtMost(255)
            out[px + py * tileSide] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return out
    }

    /** The terrain map at one pixel per cell — the source the overlapping model reads. */
    fun terrainPixelImg(w: Int, h: Int): Img {
        val map = terrainMap(w, h)
        val data = FloatArray(w * h * 3)
        for (i in map.indices) {
            val c = TERRAIN_COLORS[map[i]]
            data[i * 3] = ((c shr 16) and 0xff) / 255f
            data[i * 3 + 1] = ((c shr 8) and 0xff) / 255f
            data[i * 3 + 2] = (c and 0xff) / 255f
        }
        return Img(w, h, 3, data)
    }

    /** The terrain map with each cell drawn as an 8x8 textured tile — the simple-tiled source. */
    fun terrainTiledImg(w: Int, h: Int, tileSide: Int = 8): Img {
        val map = terrainMap(w, h)
        val outW = w * tileSide
        val outH = h * tileSide
        val data = FloatArray(outW * outH * 3)
        val tiles = Array(TERRAIN_COUNT) { terrainTile(it, tileSide) }
        for (cy in 0 until h) for (cx in 0 until w) {
            val tile = tiles[map[cx + cy * w]]
            for (py in 0 until tileSide) for (px in 0 until tileSide) {
                val argb = tile[px + py * tileSide]
                val di = ((cy * tileSide + py) * outW + (cx * tileSide + px)) * 3
                data[di] = ((argb shr 16) and 0xff) / 255f
                data[di + 1] = ((argb shr 8) and 0xff) / 255f
                data[di + 2] = (argb and 0xff) / 255f
            }
        }
        return Img(outW, outH, 3, data)
    }

    // ==========================================================================================
    // Overlapping-model source: flowers, where the structure is at the pixel scale
    // ==========================================================================================

    val SKY = 0xFFBEDCF0.toInt()
    val SOIL = 0xFF6B4B2A.toInt()
    val STEM = 0xFF3E8F3A.toInt()
    val BLOSSOM = 0xFFE0566A.toInt()

    /** Row at which soil starts in [flowersSource]; everything above it is sky, stem or blossom. */
    const val FLOWERS_SOIL_TOP = 13

    /**
     * The overlapping model's source: sky over soil, with a few flowers rooted in the soil.
     *
     * Chosen because its structure lives at the *pixel* scale, which is what the overlapping model
     * reads. The terrain map used for the simple-tiled figures is the wrong shape for it — its
     * interiors are large flat regions, so most 3x3 windows say only "all grass" and the output
     * collapses to a flat field with streak artefacts. A source whose every 3x3 window says
     * something ("stem continues upward", "blossom sits on a stem", "stem meets soil") gives the
     * model something worth preserving.
     */
    fun flowersSource(w: Int = 26, h: Int = 18): IntArray {
        val out = IntArray(w * h) { if (it / w >= FLOWERS_SOIL_TOP) SOIL else SKY }
        // (column, stem height) — kept apart so no two flowers touch, and clear of the wrap seam.
        for ((x, height) in listOf(3 to 4, 8 to 6, 13 to 3, 18 to 5, 22 to 4)) {
            for (y in (FLOWERS_SOIL_TOP - height) until FLOWERS_SOIL_TOP) out[x + y * w] = STEM
            out[x + (FLOWERS_SOIL_TOP - height - 1) * w] = BLOSSOM
        }
        return out
    }

    /** [flowersSource] as an [Img]. */
    fun flowersImg(w: Int = 26, h: Int = 18): Img = argbToImg(flowersSource(w, h), w, h)

    private fun argbToImg(argb: IntArray, w: Int, h: Int): Img {
        val data = FloatArray(w * h * 3)
        for (i in argb.indices) {
            data[i * 3] = ((argb[i] shr 16) and 0xff) / 255f
            data[i * 3 + 1] = ((argb[i] shr 8) and 0xff) / 255f
            data[i * 3 + 2] = (argb[i] and 0xff) / 255f
        }
        return Img(w, h, 3, data)
    }

    // ==========================================================================================
    // Hand-authored tileset: circuit traces, adjacency by edge sockets
    // ==========================================================================================

    /** Socket per edge, in `WFC_NORTH, WFC_EAST, WFC_SOUTH, WFC_WEST` order; 1 = a trace leaves here. */
    val CIRCUIT_SOCKETS = arrayOf(
        intArrayOf(0, 0, 0, 0), // 0 blank
        intArrayOf(0, 1, 0, 1), // 1 horizontal
        intArrayOf(1, 0, 1, 0), // 2 vertical
        intArrayOf(1, 1, 0, 0), // 3 elbow N-E
        intArrayOf(0, 1, 1, 0), // 4 elbow E-S
        intArrayOf(0, 0, 1, 1), // 5 elbow S-W
        intArrayOf(1, 0, 0, 1), // 6 elbow W-N
        intArrayOf(1, 1, 1, 1)  // 7 cross
    )

    val CIRCUIT_NAMES = arrayOf("blank", "horizontal", "vertical", "elbow NE", "elbow ES", "elbow SW", "elbow WN", "cross")

    /**
     * Rules from edge sockets: `b` may sit east of `a` exactly when `a`'s east socket matches `b`'s
     * west socket. This is the original "simple tiled model", stated directly instead of inferred.
     */
    fun circuitRules(): WfcRules = wfcRules(tileCount = CIRCUIT_SOCKETS.size) {
        // Blank space is common; a four-way cross is rare. Weights bias the sampling, not legality.
        weights(6.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 0.35)
        for (a in CIRCUIT_SOCKETS.indices) for (b in CIRCUIT_SOCKETS.indices) {
            if (CIRCUIT_SOCKETS[a][WFC_EAST] == CIRCUIT_SOCKETS[b][3]) allow(WFC_EAST, a, b)
            if (CIRCUIT_SOCKETS[a][WFC_SOUTH] == CIRCUIT_SOCKETS[b][0]) allow(WFC_SOUTH, a, b)
        }
    }

    /** 11x11 tile art for the circuit set: a centre pad plus a trace out to each live socket. */
    fun circuitTile(tile: Int, side: Int = 11): IntArray {
        val bg = 0xFF10241C.toInt()
        val trace = 0xFF64E0A0.toInt()
        val pad = 0xFFB8FFDA.toInt()
        val out = IntArray(side * side) { bg }
        val c = side / 2
        val sockets = CIRCUIT_SOCKETS[tile]
        val live = sockets.any { it == 1 }

        fun put(x: Int, y: Int, color: Int) {
            if (x in 0 until side && y in 0 until side) out[x + y * side] = color
        }

        if (sockets[0] == 1) for (y in 0..c) { put(c, y, trace); put(c - 1, y, trace) }
        if (sockets[2] == 1) for (y in c until side) { put(c, y, trace); put(c - 1, y, trace) }
        if (sockets[3] == 1) for (x in 0..c) { put(x, c, trace); put(x, c - 1, trace) }
        if (sockets[1] == 1) for (x in c until side) { put(x, c, trace); put(x, c - 1, trace) }
        if (live) for (y in (c - 1)..c) for (x in (c - 1)..c) put(x, y, pad)
        return out
    }

    /**
     * A [PngTileSet] assembled from hand-drawn art rather than sliced from a source image.
     *
     * `sampleIds` is set to the tile row `0, 1, 2, …` so [PngTileSet.sampleToImg] renders the atlas
     * as a labelled strip. Nothing learns from it — the rules come from [circuitRules].
     */
    fun circuitTileSet(side: Int = 11): PngTileSet = PngTileSet(
        tileWidth = side,
        tileHeight = side,
        tiles = Array(CIRCUIT_SOCKETS.size) { circuitTile(it, side) },
        sampleIds = IntArray(CIRCUIT_SOCKETS.size) { it },
        sampleWidthInTiles = CIRCUIT_SOCKETS.size,
        sampleHeightInTiles = 1
    )

    // ==========================================================================================
    // Verification
    // ==========================================================================================

    private val failures = ArrayList<String>()

    private fun fail(message: String) {
        failures.add(message)
        println("[wfc-gallery] FAIL $message")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }

    /**
     * The invariant that matters: every adjacent pair in [grid] must be allowed by [rules].
     *
     * Checked over the rules' own neighborhood, so it covers the diagonal constraints of a Moore
     * neighborhood too, not just the four cardinal ones.
     */
    fun checkOutputSatisfiesRules(label: String, rules: WfcRules, grid: IntArray, w: Int, h: Int) {
        var violations = 0
        var firstDetail = ""
        for (y in 0 until h) for (x in 0 until w) {
            val a = grid[x + y * w]
            for (d in 0 until rules.neighborhood.directionCount) {
                val off = rules.neighborhood.offset(d)
                val nx = x + off.dx
                val ny = y + off.dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val b = grid[nx + ny * w]
                if (!rules.allows(d, a, b)) {
                    if (violations == 0) firstDetail = "tile $a at ($x, $y) has disallowed neighbour $b at ($nx, $ny) in direction $d"
                    violations++
                }
            }
        }
        check(violations == 0, "$label: $violations adjacency violation(s) in the solved output — $firstDetail")
        if (violations == 0) println("[wfc-gallery] ok  $label: every adjacent pair is an allowed pair (${w}x$h)")
    }

    /** Guards the two failure modes that still look like a picture: one tile everywhere, or noise. */
    fun checkNotDegenerate(label: String, grid: IntArray, tileCount: Int, minDistinct: Int, maxShare: Double) {
        val counts = IntArray(tileCount)
        for (t in grid) counts[t]++
        val distinct = counts.count { it > 0 }
        val topShare = (counts.max().toDouble()) / grid.size
        check(distinct >= minDistinct, "$label: only $distinct distinct tiles used, expected at least $minDistinct")
        check(topShare <= maxShare, "$label: one tile covers ${(topShare * 100).toInt()}% of the output, expected at most ${(maxShare * 100).toInt()}%")
        println("[wfc-gallery] ok  $label: $distinct distinct tiles, most common covers ${(topShare * 100).toInt()}%")
    }

    /** Adjacency must be a symmetric relation or the solver cannot enforce it. See [WfcRules.findAsymmetry]. */
    fun checkSymmetric(label: String, rules: WfcRules) {
        val asymmetry = rules.findAsymmetry()
        check(asymmetry == null, "$label: rules are not symmetric — $asymmetry")
        if (asymmetry == null) println("[wfc-gallery] ok  $label: adjacency relation is symmetric")
    }

    /**
     * The stronger form of [checkOutputSatisfiesRules]: every adjacent pair must be a pair the
     * *sample actually contained*, not merely one the widened table permits.
     *
     * These come apart exactly when [WfcRules.fromSampleGrid]'s fallback fires, and then they come
     * apart badly — see [WfcRules.observedAllows]. Asserting the weak form against a widened model
     * is close to asserting nothing, because widening a single mask makes that whole row legal.
     * A learned model should satisfy this; [maxInvented] exists only so a figure that deliberately
     * relies on the fallback can say so out loud rather than quietly passing the weak check.
     */
    fun checkOutputUsesObservedPairsOnly(
        label: String,
        rules: WfcRules,
        grid: IntArray,
        w: Int,
        h: Int,
        maxInvented: Int = 0
    ) {
        var invented = 0
        var firstDetail = ""
        for (y in 0 until h) for (x in 0 until w) {
            val a = grid[x + y * w]
            for (d in 0 until rules.neighborhood.directionCount) {
                val off = rules.neighborhood.offset(d)
                val nx = x + off.dx
                val ny = y + off.dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val b = grid[nx + ny * w]
                if (!rules.observedAllows(d, a, b)) {
                    if (invented == 0) {
                        firstDetail = "tile $a at ($x, $y) sits beside $b at ($nx, $ny) in direction $d, " +
                            "a pair the sample never contained"
                    }
                    invented++
                }
            }
        }
        check(
            invented <= maxInvented,
            "$label: $invented adjacent pair(s) exist only because of the widening fallback " +
                "(allowed at most $maxInvented) — $firstDetail"
        )
        val (observedPairs, inventedPairs) = rules.observedAndInventedPairCounts()
        println(
            "[wfc-gallery] ok  $label: $invented output pair(s) unobserved (limit $maxInvented); " +
                "rule table is $observedPairs observed + $inventedPairs invented, " +
                "${rules.widenedMaskCount} widened mask(s)"
        )
    }

    /**
     * The widening fallback, measured across disjoint tile sizes — the axis nothing else covers.
     *
     * Every learned figure on the page uses one tile per terrain band, where no mask is ever empty
     * and the fallback never fires. Step up to 2x2 tiles and most tiles are seen once, at a sample
     * edge, and the fallback invents the majority of the adjacency table. This pins both ends of
     * that so the page's claim about it cannot rot: 1x1 must be pure evidence, and 2x2 must still
     * be mostly invention (if that ever stops being true the fallback has been changed and the
     * page's numbers need rewriting).
     */
    private fun checkWideningAudit() {
        val sw = 26
        val sh = 18
        val source = terrainTiledImg(sw, sh)

        val oneByOne = source.toBufferedImage().sliceIntoUniqueTiles(8, 8).toRules()
        val (obs1, inv1) = oneByOne.observedAndInventedPairCounts()
        check(
            oneByOne.widenedMaskCount == 0 && inv1 == 0,
            "widening audit: one tile per band should need no widening, got " +
                "${oneByOne.widenedMaskCount} widened mask(s) and $inv1 invented pair(s)"
        )
        println("[wfc-gallery] ok  widening audit: 1x1 tiles -> $obs1 observed pairs, 0 invented")

        // 16x16 pixels per cell = a 2x2 block of the 8x8 terrain tiles.
        val twoByTwo = source.toBufferedImage().sliceIntoUniqueTiles(16, 16).toRules()
        val (obs2, inv2) = twoByTwo.observedAndInventedPairCounts()
        check(
            twoByTwo.widenedMaskCount > 0 && inv2 > obs2,
            "widening audit: 2x2 tiles should be mostly invented adjacency, got " +
                "${twoByTwo.widenedMaskCount} widened mask(s), $obs2 observed vs $inv2 invented"
        )
        // ... and the fallback must not have broken the invariant the solver depends on.
        checkSymmetric("widening audit (2x2 tiles)", twoByTwo)
        println(
            "[wfc-gallery] ok  widening audit: 2x2 tiles -> ${twoByTwo.tileCount} tiles, " +
                "${twoByTwo.widenedMaskCount} widened mask(s), $obs2 observed + $inv2 invented pairs " +
                "(${100 * inv2 / (obs2 + inv2)}% of the table)"
        )

        // Hand-authored rules record it too, so `allowMissingAnything()` is auditable after the fact.
        val authored = wfcRules(tileCount = 3) {
            allow(WFC_EAST, 0, 1)
            allow(WFC_SOUTH, 0, 1)
        }
        check(authored.widenedMaskCount == 0, "widening audit: hand-authored rules must not widen by default")
        val relaxed = wfcRules(tileCount = 3) {
            allow(WFC_EAST, 0, 1)
            allow(WFC_SOUTH, 0, 1)
            allowMissingAnything()
        }
        check(relaxed.widenedMaskCount > 0, "widening audit: allowMissingAnything() did not record what it widened")
        check(
            relaxed.observedAllows(WFC_EAST, 0, 1) && !relaxed.observedAllows(WFC_EAST, 1, 0),
            "widening audit: observedAllows should keep the authored pair and reject the invented one"
        )
        println("[wfc-gallery] ok  widening audit: allowMissingAnything() recorded ${relaxed.widenedMaskCount} widened mask(s)")
    }

    /** The unordered set of terrain pairs that touch (east or south) in a terrain grid. */
    fun terrainPairs(terrain: IntArray, w: Int, h: Int): Set<Pair<Int, Int>> {
        val pairs = HashSet<Pair<Int, Int>>()
        for (y in 0 until h) for (x in 0 until w) {
            val a = terrain[x + y * w]
            for ((dx, dy) in listOf(1 to 0, 0 to 1)) {
                val nx = x + dx
                val ny = y + dy
                if (nx >= w || ny >= h) continue
                val b = terrain[nx + ny * w]
                pairs.add(if (a <= b) a to b else b to a)
            }
        }
        return pairs
    }

    private fun describePairs(pairs: Set<Pair<Int, Int>>): String =
        pairs.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString(", ") { "${TERRAIN_NAMES[it.first]}-${TERRAIN_NAMES[it.second]}" }

    /**
     * The human-legible form of "it reused the source's structure": the output may only contain
     * terrain *edges* the source contained.
     *
     * Derived from the source rather than assumed. An earlier version of this check asserted that
     * adjacent cells never skip a band, on the theory that quantising a smooth elevation field can
     * only ever put neighbouring bands next to each other. That is true of the continuous field and
     * false of a coarse sampling of it — where the gradient is steep, one cell step really does jump
     * two bands, and the source legitimately contains a few such edges. Asserting the subset
     * relation says the true thing instead.
     */
    fun checkStructureReused(
        label: String,
        source: IntArray,
        sw: Int,
        sh: Int,
        output: IntArray,
        ow: Int,
        oh: Int
    ) {
        val sourcePairs = terrainPairs(source, sw, sh)
        val outputPairs = terrainPairs(output, ow, oh)
        val invented = outputPairs - sourcePairs
        check(
            invented.isEmpty(),
            "$label: output invented terrain edge(s) absent from the source: ${describePairs(invented)}"
        )
        println("[wfc-gallery] ok  $label: source edges {${describePairs(sourcePairs)}}")
        println("[wfc-gallery] ok  $label: output edges {${describePairs(outputPairs)}} — no invented edge")
    }

    // ==========================================================================================
    // Figures
    // ==========================================================================================

    private fun figureCircuit(outDir: File) {
        val tileSet = circuitTileSet()
        val rules = circuitRules()
        check(rules.tileCount == 8, "circuit: expected 8 tiles, got ${rules.tileCount}")
        checkSymmetric("circuit", rules)
        // A blank tile may only touch a blank edge; a cross may only touch live edges.
        check(!rules.allows(WFC_EAST, 0, 1), "circuit: blank must not sit west of a horizontal trace")
        check(rules.allows(WFC_EAST, 1, 1), "circuit: horizontal must accept horizontal to its east")
        check(rules.allows(WFC_EAST, 7, 1), "circuit: cross must accept horizontal to its east")

        val w = 28
        val h = 18
        val solver = WfcSolver(w, h, rules, seed = 20260728L)
        val outcome = solver.solveWithRetries()
        check(outcome.solved, "circuit: not solved after ${outcome.attempts} attempts")
        val grid = solver.resolvedTileGridOrNull() ?: return
        checkOutputSatisfiesRules("circuit", rules, grid, w, h)
        checkNotDegenerate("circuit", grid, rules.tileCount, minDistinct = 6, maxShare = 0.72)

        val atlas = tileSet.toImg(IntArray(8) { it }, 8, 1).upscaleNearest(4)
        val out = tileSet.toImg(grid, w, h).upscaleNearest(3)
        montage(listOf("tile atlas (0..7)" to atlas, "28x18 output, seed 20260728" to out), columns = 1, cellSide = 960)
            .savePng(File(outDir, "wfc-circuit.png").absolutePath)
        println("[wfc-gallery] circuit solved in ${outcome.attempts} attempt(s)")
    }

    private fun figureSimpleTiled(outDir: File) {
        val sw = 26
        val sh = 18
        val source = terrainTiledImg(sw, sh)
        val tiles = source.toBufferedImage().sliceIntoUniqueTiles(8, 8)
        check(tiles.tileCount == TERRAIN_COUNT, "simple-tiled: expected $TERRAIN_COUNT unique tiles, got ${tiles.tileCount}")
        check(tiles.sampleWidthInTiles == sw, "simple-tiled: sample width ${tiles.sampleWidthInTiles} != $sw")

        val rules = tiles.toRules()
        checkSymmetric("simple-tiled", rules)

        // Tile IDs are discovery order, so map both ways through the sample grid.
        val map = terrainMap(sw, sh)
        val idOfTerrain = IntArray(TERRAIN_COUNT) { -1 }
        val terrainOfId = IntArray(tiles.tileCount) { -1 }
        for (i in map.indices) {
            idOfTerrain[map[i]] = tiles.sampleIds[i]
            terrainOfId[tiles.sampleIds[i]] = map[i]
        }
        check(idOfTerrain.none { it < 0 }, "simple-tiled: some terrain band never appeared in the source")
        check(
            !rules.allows(WFC_EAST, idOfTerrain[DEEP], idOfTerrain[GRASS]),
            "simple-tiled: learned rules wrongly allow grass east of deep water"
        )

        val w = 40
        val h = 26

        fun solveWith(model: WfcRules, label: String): IntArray? {
            val solver = WfcSolver(w, h, model, seed = 12L)
            val outcome = solver.solveWithRetries(maxAttempts = 30)
            check(outcome.solved, "$label: not solved after ${outcome.attempts} attempts")
            val grid = solver.resolvedTileGridOrNull() ?: return null
            checkOutputSatisfiesRules(label, model, grid, w, h)
            // One tile per band, so nothing is widened and the strong form must hold exactly.
            checkOutputUsesObservedPairsOnly(label, model, grid, w, h)
            checkStructureReused(label, source = map, sw = sw, sh = sh,
                output = IntArray(grid.size) { terrainOfId[grid[it]] }, ow = w, oh = h)
            return grid
        }

        fun bandShare(grid: IntArray, band: Int): Int =
            grid.count { terrainOfId[it] == band } * 100 / grid.size

        val learned = solveWith(rules, "simple-tiled (learned weights)") ?: return

        // The page's headline finding: with the learned weights this model is bistable and the
        // water bands vanish completely, even though the source is ~28% water. If this ever stops
        // being true the page's histogram and its whole argument are wrong, so assert it.
        val sourceWater = map.count { it == DEEP || it == SHALLOW } * 100 / map.size
        val learnedWater = bandShare(learned, DEEP) + bandShare(learned, SHALLOW)
        check(sourceWater >= 20, "simple-tiled: source is only $sourceWater% water, expected ~28%")
        check(learnedWater == 0, "simple-tiled: learned weights kept $learnedWater% water; the page claims 0%")
        println("[wfc-gallery] ok  simple-tiled: source ${sourceWater}% water -> learned weights ${learnedWater}% water (bistable)")

        // ... and that boosting the interface band rescues all five.
        val reweighted = tiles.toRules()
        reweighted.weights[idOfTerrain[DEEP]] *= 2.0
        reweighted.weights[idOfTerrain[SHALLOW]] *= 1.5
        reweighted.weights[idOfTerrain[SAND]] *= 10.0
        val boosted = solveWith(reweighted, "simple-tiled (sand boosted)") ?: return
        val boostedBands = (0 until TERRAIN_COUNT).count { bandShare(boosted, it) > 0 }
        check(boostedBands == TERRAIN_COUNT, "simple-tiled: sand boost produced only $boostedBands of $TERRAIN_COUNT bands")
        checkNotDegenerate("simple-tiled (sand boosted)", boosted, tiles.tileCount, minDistinct = 5, maxShare = 0.60)
        println("[wfc-gallery] ok  simple-tiled: sand x10 restores all $TERRAIN_COUNT bands " +
            "(deep ${bandShare(boosted, DEEP)}%, shallow ${bandShare(boosted, SHALLOW)}%, sand ${bandShare(boosted, SAND)}%, " +
            "grass ${bandShare(boosted, GRASS)}%, forest ${bandShare(boosted, FOREST)}%)")

        montage(
            listOf(
                "source ${sw}x$sh" to source.upscaleNearest(2),
                "learned weights" to tiles.toImg(learned, w, h),
                "sand boosted x10" to tiles.toImg(boosted, w, h)
            ),
            columns = 1,
            cellSide = 700
        ).savePng(File(outDir, "wfc-simple-tiled.png").absolutePath)
    }

    /** Fraction of 4-adjacent cell pairs that hold the same value — "how blobby is this". */
    fun coherence(grid: IntArray, w: Int, h: Int): Double {
        var same = 0
        var total = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x + 1 < w) { total++; if (grid[x + y * w] == grid[x + 1 + y * w]) same++ }
            if (y + 1 < h) { total++; if (grid[x + y * w] == grid[x + (y + 1) * w]) same++ }
        }
        return same.toDouble() / total
    }

    /**
     * The section-2 payoff: the *same* terrain source the 1x1 model turned to speckle, read by the
     * overlapping model, which reproduces the source's coastline grammar across every seed.
     *
     * What this asserts is the page's claim, which is deliberately narrower than "it looks like the
     * source". The output is not an island and cannot be — an island is global composition and the
     * model has no representation of it. What the model *does* hold is the source's local language:
     * bands in chain order, a sand fringe between land and water, forest interior to grass. So the
     * checks are (a) every seed solves, (b) every adjacent pair is one the source contained, and
     * (c) the result is materially more coherent than the 1x1 model on the same source — which is
     * the difference between "correct speckle" and "a coastline".
     *
     * It also pins the reason the figure is unpinned. A uniform 3x3 window of a band thinner than
     * three cells can only ever be followed by itself, so pinning one propagates it across the whole
     * grid and any second pin is an instant contradiction. That is why section 2 shows this model
     * free rather than with a ground constraint, and it is checkable via [WfcRules.allowedCount].
     */
    private fun figureTerrainOverlapping(outDir: File) {
        val sw = 26
        val sh = 18
        val source = terrainPixelImg(sw, sh)
        val map = terrainMap(sw, sh)
        val patterns = source.toBufferedImage().sliceIntoOverlappingPatterns(3, 3, wrapX = false, wrapY = false)
        val rules = patterns.toRules()
        checkSymmetric("terrain overlapping", rules)
        check(patterns.tileCount > 100, "terrain overlapping: expected a rich 3x3 pattern set, got ${patterns.tileCount}")

        // The uniform-window trap, stated as a number rather than as a story. The deep band is
        // thinner than three cells almost everywhere, so no window has two full deep columns and a
        // non-deep third — leaving the all-deep window with itself as its only southward successor.
        val uniformDeep = (0 until patterns.tileCount)
            .first { id -> patterns.patterns[id].all { it == TERRAIN_COLORS[DEEP] } }
        val southward = rules.allowedCount(WFC_SOUTH, uniformDeep)
        check(
            southward == 1,
            "terrain overlapping: the all-deep 3x3 window has $southward southward successor(s); " +
                "the page says it has exactly one (itself), which is why pinning it floods the grid"
        )
        println(
            "[wfc-gallery] ok  terrain overlapping: all-deep window #$uniformDeep may be followed by " +
                "$southward tile southward — pinning it would flood the output"
        )

        // The band a pattern stands for is the band of its origin pixel.
        val bandOfPattern = IntArray(patterns.tileCount) { id ->
            val argb = patterns.patterns[id][0]
            TERRAIN_COLORS.indexOfFirst { it == argb }
        }
        check(bandOfPattern.none { it < 0 }, "terrain overlapping: a pattern's origin pixel is not a terrain colour")

        val w = 40
        val h = 26
        val seeds = longArrayOf(1, 3, 5, 7, 9, 11, 12, 17, 23, 42, 101, 2026)
        val panels = ArrayList<Pair<String, Img>>()
        var coherenceSum = 0.0
        var firstAttemptCount = 0

        for (seed in seeds) {
            val solver = WfcSolver(w, h, rules, seed = seed)
            val outcome = solver.solveWithRetries(maxAttempts = 30)
            check(outcome.solved, "terrain overlapping seed $seed: not solved after ${outcome.attempts} attempts")
            if (outcome.attempts == 1) firstAttemptCount++
            val grid = solver.resolvedTileGridOrNull() ?: continue
            checkOutputSatisfiesRules("terrain overlapping seed $seed", rules, grid, w, h)
            // The overlapping model never widens, so the strong form is exact here.
            checkOutputUsesObservedPairsOnly("terrain overlapping seed $seed", rules, grid, w, h)
            val terrain = IntArray(grid.size) { bandOfPattern[grid[it]] }
            checkStructureReused("terrain overlapping seed $seed", map, sw, sh, terrain, w, h)
            coherenceSum += coherence(terrain, w, h)
            panels.add("seed $seed" to patterns.toImg(grid, w, h).upscaleNearest(6))
        }
        check(panels.size == seeds.size, "terrain overlapping: only ${panels.size} of ${seeds.size} seeds produced a figure")

        // The comparison the section is built on: the same source, one tile per band, is speckle.
        val flatTiles = terrainTiledImg(sw, sh).toBufferedImage().sliceIntoUniqueTiles(8, 8)
        val flatSolver = WfcSolver(w, h, flatTiles.toRules(), seed = 12L)
        check(flatSolver.solveWithRetries(maxAttempts = 30).solved, "terrain overlapping: the 1x1 baseline did not solve")
        val flatGrid = flatSolver.resolvedTileGridOrNull() ?: return
        val flatCoherence = coherence(flatGrid, w, h)
        val meanCoherence = coherenceSum / panels.size
        val sourceCoherence = coherence(map, sw, sh)
        check(
            meanCoherence > flatCoherence * 1.5,
            "terrain overlapping: mean coherence ${"%.3f".format(meanCoherence)} is not materially above " +
                "the 1x1 model's ${"%.3f".format(flatCoherence)} — the section's whole claim"
        )
        check(
            meanCoherence > sourceCoherence,
            "terrain overlapping: mean coherence ${"%.3f".format(meanCoherence)} fell below the source's " +
                "${"%.3f".format(sourceCoherence)}; the page claims it reaches or exceeds it"
        )
        println(
            "[wfc-gallery] ok  terrain overlapping: ${panels.size}/${seeds.size} seeds solved " +
                "($firstAttemptCount on the first attempt), mean coherence ${"%.3f".format(meanCoherence)} " +
                "vs source ${"%.3f".format(sourceCoherence)} vs 1x1 ${"%.3f".format(flatCoherence)}"
        )

        montage(
            listOf("source ${sw}x$sh, ${patterns.tileCount} 3x3 patterns" to source.upscaleNearest(6)) + panels,
            columns = 4,
            cellSide = 330
        ).savePng(File(outDir, "wfc-terrain-overlapping.png").absolutePath)
    }

    /**
     * Which output pixels are blossoms whose pixel directly below is not a stem.
     *
     * A 3x3 pattern that contains a blossom also contains whatever was under it in the source, and
     * in the source that is always a stem. "Blossom sits on stem" is therefore a *local* relation
     * the overlapping model can and must enforce, which makes it a real check on whether the model
     * preserved the source's structure rather than just its colours.
     */
    fun floatingBlossoms(pixels: IntArray, w: Int, h: Int): Int {
        var floating = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (pixels[x + y * w] != BLOSSOM) continue
            if (y + 1 >= h) continue
            if (pixels[x + (y + 1) * w] != STEM) floating++
        }
        return floating
    }

    private fun imgToArgb(img: Img): IntArray {
        val out = IntArray(img.width * img.height)
        for (i in out.indices) {
            val r = (img.data[i * 3] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (img.data[i * 3 + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (img.data[i * 3 + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun figureOverlapping(outDir: File) {
        val sw = 26
        val sh = 18
        val source = flowersImg(sw, sh)
        val patterns = source.toBufferedImage().sliceIntoOverlappingPatterns(3, 3, wrapX = true, wrapY = false)
        check(patterns.tileCount > 8, "overlapping: expected a rich pattern set, got ${patterns.tileCount}")

        val rules = patterns.toRules()
        checkSymmetric("overlapping", rules)

        // WFC has no notion of absolute position: the rules only say "soil may sit below sky", never
        // "sky belongs at the top". Pinning the boundary is the classic WFC "ground" constraint.
        val groundTile = (0 until patterns.tileCount).first { id -> patterns.patterns[id].all { it == SOIL } }
        val skyTile = (0 until patterns.tileCount).first { id -> patterns.patterns[id].all { it == SKY } }

        val w = 52
        val h = 16

        // Unpinned first, to show what translation-invariance actually costs.
        val free = WfcSolver(w, h, rules, seed = 9L)
        val freeOutcome = free.solveWithRetries(maxAttempts = 30)
        check(freeOutcome.solved, "overlapping (free): not solved after ${freeOutcome.attempts} attempts")
        val freeGrid = free.resolvedTileGridOrNull() ?: return
        checkOutputSatisfiesRules("overlapping (free)", rules, freeGrid, w, h)

        val solver = WfcSolver(w, h, rules, seed = 4L)
        val outcome = solver.solveWithRetries(maxAttempts = 30) {
            for (x in 0 until w) {
                forceTile(x, h - 1, groundTile)
                forceTile(x, 0, skyTile)
            }
        }
        check(outcome.solved, "overlapping (grounded): not solved after ${outcome.attempts} attempts")
        val grid = solver.resolvedTileGridOrNull() ?: return
        checkOutputSatisfiesRules("overlapping (grounded)", rules, grid, w, h)
        checkNotDegenerate("overlapping (grounded)", grid, rules.tileCount, minDistinct = 8, maxShare = 0.72)

        val result = patterns.toImg(grid, w, h)
        val pixels = imgToArgb(result)
        val floating = floatingBlossoms(pixels, w, h)
        check(floating == 0, "overlapping: $floating blossom(s) float with no stem beneath them")
        val blossoms = pixels.count { it == BLOSSOM }
        check(blossoms >= 3, "overlapping: only $blossoms blossom(s) in the output, expected several")
        val soilRow = (0 until w).count { pixels[it + (h - 1) * w] == SOIL }
        check(soilRow == w, "overlapping: ground row is not all soil ($soilRow of $w)")
        println("[wfc-gallery] ok  overlapping: $blossoms blossom(s), none floating, ground row intact")

        montage(
            listOf(
                "source ${sw}x$sh px, ${patterns.tileCount} 3x3 patterns" to source.upscaleNearest(8),
                "no ground constraint" to patterns.toImg(freeGrid, w, h).upscaleNearest(8),
                "ground + sky pinned with forceTile" to result.upscaleNearest(8)
            ),
            columns = 1,
            cellSide = 860
        ).savePng(File(outDir, "wfc-overlapping.png").absolutePath)
        println("[wfc-gallery] overlapping solved in ${outcome.attempts} attempt(s), ${patterns.tileCount} patterns")
    }

    /** Mean number of tiles allowed per (direction, tile) — the model's branching factor. */
    fun meanBranching(rules: WfcRules): Double {
        var total = 0L
        var n = 0
        for (d in 0 until rules.neighborhood.directionCount) for (t in 0 until rules.tileCount) {
            total += rules.allowedCount(d, t)
            n++
        }
        return total.toDouble() / n
    }

    val STRIPE_COLORS = intArrayOf(0xFF2B3F72.toInt(), 0xFFE8B44A.toInt())

    /**
     * Diagonal stripes two cells wide — a source built so the neighborhood choice provably matters.
     *
     * Colour depends on `(x + y) mod 4`, so along the anti-diagonal offset `(1, -1)` the value never
     * changes, while along east and south it changes only half the time. A von Neumann model
     * therefore observes every cardinal pair (`A-A`, `A-B`, `B-B`, `B-A`) and learns *nothing*: the
     * stripes are invisible to it. A Moore model additionally observes `(1, -1)` — where the colour
     * is constant — and that single extra constraint is enough to pin the stripes exactly.
     */
    fun diagonalStripeSource(w: Int, h: Int): IntArray =
        IntArray(w * h) { i -> if (((i % w) + (i / w)) % 4 < 2) 0 else 1 }

    /** [diagonalStripeSource] drawn as flat 8x8 tiles. */
    fun diagonalStripeImg(w: Int, h: Int, tileSide: Int = 8): Img {
        val map = diagonalStripeSource(w, h)
        val outW = w * tileSide
        val outH = h * tileSide
        val argb = IntArray(outW * outH)
        for (cy in 0 until h) for (cx in 0 until w) {
            val color = STRIPE_COLORS[map[cx + cy * w]]
            for (py in 0 until tileSide) for (px in 0 until tileSide) {
                argb[(cy * tileSide + py) * outW + (cx * tileSide + px)] = color
            }
        }
        return argbToImg(argb, outW, outH)
    }

    /** How many cells break "the anti-diagonal neighbour has the same tile" — 0 means clean stripes. */
    fun antiDiagonalBreaks(grid: IntArray, w: Int, h: Int): Int {
        var breaks = 0
        for (y in 1 until h) for (x in 0 until w - 1) {
            if (grid[x + y * w] != grid[(x + 1) + (y - 1) * w]) breaks++
        }
        return breaks
    }

    private fun figureNeighborhood(outDir: File) {
        val panels = ArrayList<Pair<String, Img>>()

        // Case 1: a terrain chain, where the diagonals are nearly implied by the cardinals.
        val terrainTiles = terrainTiledImg(26, 18).toBufferedImage().sliceIntoUniqueTiles(8, 8)
        for ((label, nb) in listOf(
            "terrain, von Neumann (4)" to WfcNeighborhood.VonNeumann,
            "terrain, Moore (8)" to WfcNeighborhood.Moore
        )) {
            val rules = terrainTiles.toRules(neighborhood = nb)
            checkSymmetric(label, rules)
            val solver = WfcSolver(30, 20, rules, seed = 5L)
            val outcome = solver.solveWithRetries(maxAttempts = 30)
            check(outcome.solved, "$label: not solved after ${outcome.attempts} attempts")
            val grid = solver.resolvedTileGridOrNull() ?: continue
            checkOutputSatisfiesRules(label, rules, grid, 30, 20)
            val branching = meanBranching(rules)
            panels.add("$label, branching ${"%.2f".format(branching)}" to terrainTiles.toImg(grid, 30, 20))
            println(
                "[wfc-gallery] $label: mean branching ${"%.2f".format(branching)} of ${rules.tileCount}, " +
                    "${grid.toSortedSet().size} distinct tiles used"
            )
        }

        // Case 2: diagonal stripes, where the (1, -1) offset is the whole story.
        val stripeTiles = diagonalStripeImg(24, 16).toBufferedImage().sliceIntoUniqueTiles(8, 8)
        check(stripeTiles.tileCount == 2, "stripes: expected 2 unique tiles, got ${stripeTiles.tileCount}")
        val stripeBreaks = HashMap<String, Int>()
        for ((label, nb) in listOf(
            "stripes, von Neumann (4)" to WfcNeighborhood.VonNeumann,
            "stripes, Moore (8)" to WfcNeighborhood.Moore
        )) {
            val rules = stripeTiles.toRules(neighborhood = nb)
            checkSymmetric(label, rules)
            val solver = WfcSolver(30, 20, rules, seed = 5L)
            val outcome = solver.solveWithRetries(maxAttempts = 30)
            check(outcome.solved, "$label: not solved after ${outcome.attempts} attempts")
            val grid = solver.resolvedTileGridOrNull() ?: continue
            checkOutputSatisfiesRules(label, rules, grid, 30, 20)
            val breaks = antiDiagonalBreaks(grid, 30, 20)
            stripeBreaks[label] = breaks
            panels.add("$label, $breaks diagonal break(s)" to stripeTiles.toImg(grid, 30, 20))
            println("[wfc-gallery] $label: $breaks anti-diagonal break(s) of ${29 * 19} pairs")
        }

        // The claim the figure makes: von Neumann cannot see the stripes, Moore reproduces them exactly.
        val vn = stripeBreaks["stripes, von Neumann (4)"] ?: -1
        val moore = stripeBreaks["stripes, Moore (8)"] ?: -1
        check(moore == 0, "stripes/Moore: expected perfectly clean diagonals, got $moore break(s)")
        check(vn > 100, "stripes/von Neumann: expected the stripes to be lost, got only $vn break(s)")

        check(panels.size == 4, "neighborhood: expected 4 panels, got ${panels.size}")
        montage(panels, columns = 2, cellSide = 460).savePng(File(outDir, "wfc-neighborhoods.png").absolutePath)
    }

    private fun figureCollapse(outDir: File) {
        val sw = 22
        val sh = 15
        val tiles = terrainTiledImg(sw, sh).toBufferedImage().sliceIntoUniqueTiles(8, 8)
        val rules = tiles.toRules()
        checkSymmetric("collapse", rules)
        val w = 32
        val h = 22
        val solver = WfcSolver(w, h, rules, seed = 3L)

        val waves = ArrayList<Pair<String, Img>>()
        val entropies = ArrayList<Pair<String, Img>>()
        var lastFraction = -1.0
        var steps = 0
        fun sample() {
            val f = solver.resolvedFraction()
            waves.add("${(f * 100).toInt()}% decided" to tiles.waveToImg(solver))
            entropies.add("${(f * 100).toInt()}% decided" to
                solver.possibilityCountImg().colormap("magma", lo = 0f, hi = rules.tileCount.toFloat()).upscaleNearest(8))
            lastFraction = f
        }
        sample()
        check(lastFraction == 0.0, "collapse: a fresh solver should have nothing decided, got $lastFraction")
        while (solver.step() == WfcStatus.Running) {
            steps++
            if (steps % 90 == 0) sample()
        }
        check(solver.status == WfcStatus.Solved, "collapse: ended in ${solver.status} after $steps steps")
        sample()
        check(lastFraction == 1.0, "collapse: solved but resolvedFraction is $lastFraction")
        check(waves.size >= 4, "collapse: only ${waves.size} frames sampled, expected at least 4")

        val grid = solver.resolvedTileGridOrNull() ?: return
        checkOutputSatisfiesRules("collapse", rules, grid, w, h)
        // The final wave frame must be pixel-identical to the rendered result: a solved cell has
        // exactly one possibility, so blending is the identity there.
        val finalWave = tiles.waveToImg(solver)
        val rendered = tiles.toImg(grid, w, h)
        var maxDiff = 0f
        for (i in finalWave.data.indices) {
            val d = kotlin.math.abs(finalWave.data[i] - rendered.data[i])
            if (d > maxDiff) maxDiff = d
        }
        check(maxDiff < 1e-6f, "collapse: final wave frame differs from the rendered output by $maxDiff")
        println("[wfc-gallery] collapse: $steps steps, ${waves.size} frames, final wave == rendered output")

        montage(waves, columns = 3, cellSide = 300).savePng(File(outDir, "wfc-collapse-wave.png").absolutePath)
        montage(entropies, columns = 3, cellSide = 300).savePng(File(outDir, "wfc-collapse-entropy.png").absolutePath)
    }

    /** Quantised smooth field, the source both float paths learn from. */
    fun quantisedField(w: Int, h: Int, levels: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x / (w - 1.0)
            val v = y / (h - 1.0)
            val e = (bump(u, v, 0.32, 0.42, 0.26) +
                0.85 * bump(u, v, 0.70, 0.60, 0.22) +
                0.50 * bump(u, v, 0.80, 0.24, 0.14)).coerceIn(0.0, 1.0)
            out[x + y * w] = (Math.round(e * (levels - 1)).toDouble() / (levels - 1)).toFloat()
        }
        return out
    }

    /** Mean absolute difference between horizontally / vertically adjacent values. */
    fun roughness(values: FloatArray, w: Int, h: Int): Pair<Double, Double> {
        var hSum = 0.0
        var hN = 0
        var vSum = 0.0
        var vN = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x + 1 < w) { hSum += kotlin.math.abs(values[x + y * w] - values[x + 1 + y * w]); hN++ }
            if (y + 1 < h) { vSum += kotlin.math.abs(values[x + y * w] - values[x + (y + 1) * w]); vN++ }
        }
        return (hSum / hN) to (vSum / vN)
    }

    private fun figureScalarField(outDir: File) {
        val sw = 26
        val sh = 18
        val levels = 6
        val source = quantisedField(sw, sh, levels)

        val tiles = source.sliceIntoUniqueTiles2D(sw, sh, tileWidth = 1, tileHeight = 1)
        check(tiles.tileCount == levels, "scalar: expected $levels levels, got ${tiles.tileCount}")
        val patterns = source.sliceIntoOverlappingPatterns2D(sw, sh, 3, 3, wrapX = false, wrapY = false)
        check(patterns.tileCount > levels, "scalar: expected many 3x3 patterns, got ${patterns.tileCount}")

        val tileRules = tiles.toRules()
        val patternRules = patterns.toRules()
        checkSymmetric("scalar 1x1", tileRules)
        checkSymmetric("scalar 3x3", patternRules)

        val w = 40
        val h = 26

        var lowestTile = 0
        for (id in 0 until tiles.tileCount) {
            if (tiles.tiles[id][0] < tiles.tiles[lowestTile][0]) lowestTile = id
        }
        // The page notes there is no 3x3 window uniformly at the lowest level in this source.
        val floor = tiles.tiles[lowestTile][0]
        check(
            (0 until patterns.tileCount).none { id -> patterns.patterns[id].all { it == floor } },
            "scalar: a uniformly-lowest 3x3 window exists, so the page's note about it is wrong"
        )

        // The section's payoff: the same 3x3 pattern set, closed under the symmetries of the square.
        // The banding is an artefact of the source's orientation statistics, so removing orientation
        // from the model should remove the banding -- and this asserts that it does, in the same
        // roughness numbers the banding was diagnosed with.
        val symmetric = patterns.expandSymmetry(WfcSymmetry.All)
        val symmetricRules = symmetric.toRules()
        checkSymmetric("scalar 3x3 + D4", symmetricRules)
        check(
            symmetric.tileCount > patterns.tileCount,
            "scalar 3x3 + D4: expansion produced no new patterns (${symmetric.tileCount})"
        )
        // Weight-preserving: sharing a pattern's weight across its orbit must not change the total.
        val beforeTotal = (0 until patterns.tileCount).sumOf { patterns.toRules().weights[it] }
        val afterTotal = symmetric.patternWeights!!.sum()
        check(
            kotlin.math.abs(beforeTotal - afterTotal) < 1e-6,
            "scalar 3x3 + D4: expansion changed total weight from $beforeTotal to $afterTotal"
        )

        // Isotropy has to be measured over an ENSEMBLE, not one output, and the distinction is not
        // pedantic -- it is the difference between two opposite conclusions. Averaging horizontal
        // and vertical roughness across seeds and then dividing tells you whether the model has a
        // systematic preferred direction. Taking the ratio per output and then averaging tells you
        // whether any single output is directionless. The first number is the one D4 fixes; the
        // second stays near the source's own anisotropy, because a heightfield with no local grain
        // at all is not the goal and is not what the source looks like either.
        fun ensembleRoughness(rules: WfcRules, set: FloatOverlappingTileSet, seeds: LongArray):
            Triple<Double, Double, List<Double>> {
            var hSum = 0.0
            var vSum = 0.0
            val perSeed = ArrayList<Double>()
            for (seed in seeds) {
                val s = WfcSolver(w, h, rules, seed = seed)
                val outcome = s.solveWithRetries(maxAttempts = 30)
                check(outcome.solved, "scalar ensemble seed $seed: not solved after ${outcome.attempts} attempts")
                val g = s.resolvedTileGridOrNull() ?: continue
                checkOutputSatisfiesRules("scalar ensemble seed $seed", rules, g, w, h)
                val (rh, rv) = roughness(set.render(g, w, h), w, h)
                hSum += rh
                vSum += rv
                perSeed.add(maxOf(rh, rv) / maxOf(1e-6, minOf(rh, rv)))
            }
            return Triple(hSum / seeds.size, vSum / seeds.size, perSeed.sorted())
        }

        val ensembleSeeds = longArrayOf(1, 3, 5, 7, 9, 11, 17, 23)
        val (plainEH, plainEV, plainPerSeed) = ensembleRoughness(patternRules, patterns, ensembleSeeds)
        val (symEH, symEV, symPerSeed) = ensembleRoughness(symmetricRules, symmetric, ensembleSeeds)
        val plainEnsemble = maxOf(plainEH, plainEV) / maxOf(1e-6, minOf(plainEH, plainEV))
        val symEnsemble = maxOf(symEH, symEV) / maxOf(1e-6, minOf(symEH, symEV))
        val plainMedian = plainPerSeed[plainPerSeed.size / 2]
        val symMedian = symPerSeed[symPerSeed.size / 2]

        val symSolver = WfcSolver(w, h, symmetricRules, seed = 5L)
        val symOutcome = symSolver.solveWithRetries(maxAttempts = 30)
        check(symOutcome.solved, "scalar 3x3 + D4: not solved after ${symOutcome.attempts} attempts")
        val symGrid = symSolver.resolvedTileGridOrNull() ?: return
        checkOutputSatisfiesRules("scalar 3x3 + D4", symmetricRules, symGrid, w, h)
        val symHeights = symmetric.render(symGrid, w, h)

        val tiledSolver = WfcSolver(w, h, tileRules, seed = 5L)
        val tiledOutcome = tiledSolver.solveWithRetries(maxAttempts = 30) {
            for (x in 0 until w) { forceTile(x, 0, lowestTile); forceTile(x, h - 1, lowestTile) }
            for (y in 0 until h) { forceTile(0, y, lowestTile); forceTile(w - 1, y, lowestTile) }
        }
        check(tiledOutcome.solved, "scalar 1x1: not solved after ${tiledOutcome.attempts} attempts")
        val tiledGrid = tiledSolver.resolvedTileGridOrNull() ?: return
        checkOutputSatisfiesRules("scalar 1x1", tileRules, tiledGrid, w, h)
        val tiledHeights = tiles.render(tiledGrid, w, h)

        val patternSolver = WfcSolver(w, h, patternRules, seed = 5L)
        val patternOutcome = patternSolver.solveWithRetries(maxAttempts = 30)
        check(patternOutcome.solved, "scalar 3x3: not solved after ${patternOutcome.attempts} attempts")
        val patternGrid = patternSolver.resolvedTileGridOrNull() ?: return
        checkOutputSatisfiesRules("scalar 3x3", patternRules, patternGrid, w, h)
        val patternHeights = patterns.render(patternGrid, w, h)

        // The page's claim, made checkable: the 1x1 result is rough in both axes, while the 3x3
        // result is banded HORIZONTALLY -- near-zero variation along rows, real variation down them.
        val (tiledH, tiledV) = roughness(tiledHeights, w, h)
        val (patternH, patternV) = roughness(patternHeights, w, h)
        // The distinguishing property is ANISOTROPY, not absolute roughness: the 1x1 result is rough
        // about equally in both axes, the 3x3 result is smooth along rows and rough down them.
        check(tiledH > 0.06, "scalar 1x1: horizontal roughness ${"%.3f".format(tiledH)} is too smooth to be speckle")
        check(
            tiledV / tiledH in 0.7..1.4,
            "scalar 1x1: roughness should be isotropic, got h=${"%.3f".format(tiledH)} v=${"%.3f".format(tiledV)}"
        )
        check(patternH < 0.03, "scalar 3x3: horizontal roughness ${"%.3f".format(patternH)} is too high for horizontal banding")
        check(
            patternV > patternH * 5.0,
            "scalar 3x3: expected strong horizontal banding, got h=${"%.3f".format(patternH)} v=${"%.3f".format(patternV)}"
        )
        println(
            "[wfc-gallery] ok  scalar: 1x1 roughness h=${"%.3f".format(tiledH)} v=${"%.3f".format(tiledV)}; " +
                "3x3 h=${"%.3f".format(patternH)} v=${"%.3f".format(patternV)} (horizontally banded)"
        )

        // The page's two claims about D4, asserted as the two different measurements they are.
        //
        // 1. The systematic bias goes away. This is the real effect and it is large.
        check(
            plainEnsemble > 4.0,
            "scalar: the unexpanded 3x3 model shows only ${"%.1f".format(plainEnsemble)}x ensemble " +
                "anisotropy; the page's whole diagnosis assumes it is strongly and consistently banded"
        )
        check(
            symEnsemble < 2.0,
            "scalar 3x3 + D4: expected the systematic direction preference to be gone, got " +
                "${"%.1f".format(symEnsemble)}x across ${ensembleSeeds.size} seeds"
        )
        // 2. Individual outputs keep a local grain, at roughly the source's own level. The page says
        //    so explicitly, so the day that stops being true the page is wrong and should fail.
        val (sourceH, sourceV) = roughness(source, sw, sh)
        val sourceRatio = maxOf(sourceH, sourceV) / maxOf(1e-6, minOf(sourceH, sourceV))
        check(
            symMedian < plainMedian / 3.0,
            "scalar 3x3 + D4: per-output anisotropy barely improved (${"%.1f".format(plainMedian)}x -> " +
                "${"%.1f".format(symMedian)}x median)"
        )
        check(
            symMedian in 1.2..(sourceRatio * 2.0),
            "scalar 3x3 + D4: per-output anisotropy ${"%.1f".format(symMedian)}x is outside the range the " +
                "page claims — around the source's own ${"%.1f".format(sourceRatio)}x, neither flat nor banded"
        )
        val (symH, symV) = roughness(symHeights, w, h)
        check(
            symH > 0.02 && symV > 0.02,
            "scalar 3x3 + D4: output is too flat to be terrain (h=${"%.3f".format(symH)} v=${"%.3f".format(symV)})"
        )
        println(
            "[wfc-gallery] ok  scalar: D4 expansion ${patterns.tileCount} -> ${symmetric.tileCount} patterns; " +
                "ensemble anisotropy ${"%.1f".format(plainEnsemble)}x -> ${"%.1f".format(symEnsemble)}x, " +
                "per-output median ${"%.1f".format(plainMedian)}x -> ${"%.1f".format(symMedian)}x " +
                "(source ${"%.1f".format(sourceRatio)}x)"
        )
        println("[wfc-gallery] ok  scalar: ${patterns.tileCount} 3x3 value patterns; " +
            "means ${"%.2f".format(tiledHeights.average())} vs ${"%.2f".format(patternHeights.average())}")

        // The FLine bridge the section exists for -- taken off the D4 field, which is the one the
        // page now ends on and the only one whose contours are worth extracting.
        val contours = floatsToImg(symHeights, w, h).gaussianBlur(1.2).isoContourFLines(0.35)
        check(contours.isNotEmpty(), "scalar: no iso-contours extracted from the synthesised field")
        println("[wfc-gallery] ok  scalar: ${contours.size} iso-contour FLine(s) at 0.35")

        montage(
            listOf(
                "source ${sw}x$sh, $levels levels" to floatsToImg(source, sw, sh).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(4),
                "1x1 tiles, border pinned" to floatsToImg(tiledHeights, w, h).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(3),
                "3x3 value patterns" to floatsToImg(patternHeights, w, h).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(3),
                "3x3 + D4 symmetry" to floatsToImg(symHeights, w, h).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(3)
            ),
            columns = 1,
            cellSide = 640
        ).savePng(File(outDir, "wfc-scalar-field.png").absolutePath)
    }

    private fun figureContradiction(outDir: File) {
        // Deliberately over-constrained: three tiles in a chain, but nothing may repeat, so no
        // 2-wide row can be filled without a contradiction on a von Neumann neighborhood.
        val strict = wfcRules(tileCount = 3) {
            allow(WFC_EAST, 0, 1)
            allow(WFC_EAST, 1, 2)
            allow(WFC_SOUTH, 0, 1)
            allow(WFC_SOUTH, 1, 2)
        }
        checkSymmetric("over-constrained", strict)
        val stuck = WfcSolver(6, 6, strict, seed = 1L)
        val outcome = stuck.solveWithRetries(maxAttempts = 8)
        check(!outcome.solved, "contradiction: the over-constrained rules unexpectedly solved")
        check(outcome.attempts == 8, "contradiction: expected all 8 attempts to be used, got ${outcome.attempts}")
        check(stuck.status == WfcStatus.Contradiction, "contradiction: expected Contradiction, got ${stuck.status}")
        println("[wfc-gallery] contradiction: over-constrained rules stayed ${stuck.status} across ${outcome.attempts} attempts")

        // Same rules plus the widening fallback: now solvable.
        val relaxed = wfcRules(tileCount = 3) {
            allow(WFC_EAST, 0, 1)
            allow(WFC_EAST, 1, 2)
            allow(WFC_SOUTH, 0, 1)
            allow(WFC_SOUTH, 1, 2)
            allowMissingAnything()
        }
        checkSymmetric("relaxed", relaxed)
        val ok = WfcSolver(6, 6, relaxed, seed = 1L)
        val okOutcome = ok.solveWithRetries()
        check(okOutcome.solved, "contradiction: allowMissingAnything() did not rescue the rules")
        val grid = ok.resolvedTileGridOrNull() ?: return
        checkOutputSatisfiesRules("relaxed", relaxed, grid, 6, 6)
        println("[wfc-gallery] contradiction: allowMissingAnything() solves in ${okOutcome.attempts} attempt(s)")

        // Seeds are reproducible: same seed, same output; different seed, different output.
        val tiles = terrainTiledImg(22, 15).toBufferedImage().sliceIntoUniqueTiles(8, 8)
        val rules = tiles.toRules()
        fun solve(seed: Long): IntArray {
            val s = WfcSolver(26, 18, rules, seed = seed)
            check(s.solveWithRetries(maxAttempts = 30).solved, "seed $seed did not solve")
            return s.resolvedTileGridOrNull()!!
        }
        val a1 = solve(42L)
        val a2 = solve(42L)
        val b1 = solve(43L)
        check(a1.contentEquals(a2), "seeds: the same seed produced different output")
        check(!a1.contentEquals(b1), "seeds: two different seeds produced identical output")
        println("[wfc-gallery] seeds: 42 reproduces exactly, 43 differs")

        hstack(
            tiles.toImg(a1, 26, 18),
            tiles.toImg(b1, 26, 18),
            gap = 12
        ).savePng(File(outDir, "wfc-seeds.png").absolutePath)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("WFC_GALLERY_DIR")
                ?: java.nio.file.Files.createTempDirectory("wfc-gallery").toFile().absolutePath
        )
        outDir.mkdirs()

        checkWideningAudit()
        figureCircuit(outDir)
        figureSimpleTiled(outDir)
        figureTerrainOverlapping(outDir)
        figureOverlapping(outDir)
        figureNeighborhood(outDir)
        figureCollapse(outDir)
        figureScalarField(outDir)
        figureContradiction(outDir)

        if (failures.isNotEmpty()) {
            System.err.println("WfcGallery FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("wfc gallery rendered to ${outDir.absolutePath}")
    }
}
