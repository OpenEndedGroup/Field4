package afield.stacksl.cpu

import afield.util.PngEncoder

/**
 * A certified bound on the colour of every pixel in a rectangle of a fragment shader's output.
 *
 * When [certified], every channel `ch` of every pixel the `Float` reference path renders inside
 * the rectangle lies in `[lo[ch], hi[ch]]`, or is NaN and [maybeNaN] is set, or the pixel was
 * discarded and [maybeDiscarded] is set. When not, [refusal] says which rule was missing.
 */
class StackSLRegionBound(
    val lo: FloatArray,
    val hi: FloatArray,
    val maybeNaN: Boolean,
    val maybeDiscarded: Boolean,
    val refusal: String? = null,
) {
    val certified: Boolean get() = refusal == null

    /** The widest channel's `hi - lo`, or infinity when refused. */
    val width: Float get() = if (!certified) Float.POSITIVE_INFINITY else lo.indices.maxOf { hi[it] - lo[it] }

    /** Whether [rgba] (one pixel) lies inside, channel by channel. */
    fun contains(rgba: FloatArray): Boolean =
        certified && rgba.indices.all { ch -> rgba[ch].let { if (it.isNaN()) maybeNaN else it >= lo[ch] && it <= hi[ch] } }

    override fun toString(): String =
        if (!certified) "refused: $refusal"
        else lo.indices.joinToString(prefix = "[", postfix = "]") { "${lo[it]}..${hi[it]}" } +
            (if (maybeNaN) " ∪ NaN" else "") + (if (maybeDiscarded) " (may discard)" else "")
}

/** One square of a [StackSLIntervals.certifiedTiles] subdivision: pixels `[x, x+size) × [y, y+size)`. */
class StackSLTile(val x: Int, val y: Int, val size: Int, val bound: StackSLRegionBound, val settled: Boolean)

/**
 * Interval evaluation of StackSL programs: what a shader can output over a rectangle of pixels, as
 * a proof rather than a render. The rules are in `GlslInterval.kt`; this is the face a box or a
 * test uses.
 *
 * ```kotlin
 * val glsl = StackSLCpu.expandStackAuto(StackSLCpu.stillTemplate(), "uv x 3 * sin 0.5 * 0.5 + gray")
 * StackSLIntervals.enclosePixels(glsl, 0, 0, 63, 63, 256, 256)   // the top-left 64×64 corner
 * StackSLIntervals.certifiedTiles(glsl, 128, tolerance = 1f / 255) // a quadtree of proven-flat tiles
 * ```
 */
object StackSLIntervals {

    /**
     * Bound every pixel `x0..x1 × y0..y1` (inclusive) of a [width] × [height] render of [glsl], a
     * complete fragment shader writing `outColor` from `fragUV`, as `StackSLCpu.renderFragment`
     * runs it.
     */
    fun enclosePixels(
        glsl: String,
        x0: Int, y0: Int, x1: Int, y1: Int,
        width: Int, height: Int,
        uniforms: Map<String, GlslValue> = emptyMap(),
    ): StackSLRegionBound = enclosePixels(parseGlslShader(glsl), x0, y0, x1, y1, width, height, uniforms)

    private fun enclosePixels(
        shader: GlslShader,
        x0: Int, y0: Int, x1: Int, y1: Int,
        width: Int, height: Int,
        uniforms: Map<String, GlslValue>,
    ): StackSLRegionBound {
        // fragUV is monotone in the pixel index along each axis, so the corners' fragUVs bracket
        // every pixel centre inside, computed exactly as StackSLCpu.fragUvAt computes them.
        val a = StackSLCpu.fragUvAt(x0, y0, width, height)
        val b = StackSLCpu.fragUvAt(x1, y1, width, height)
        val uv = GlslIntervalScalars.region(
            GlslType.VEC2,
            floatArrayOf(minOf(a.c[0], b.c[0]), minOf(a.c[1], b.c[1])),
            floatArrayOf(maxOf(a.c[0], b.c[0]), maxOf(a.c[1], b.c[1]))
        )
        return try {
            val globals = uniforms.mapValues { GlslIntervalScalars.lift(it.value) } + ("fragUV" to uv)
            val machine = shader.newMachine(GlslIntervalScalars, globals)
            val completed = machine.runMain()
            if (!completed) return StackSLRegionBound(FloatArray(4), FloatArray(4), false, true)
            val out = machine.global("outColor") ?: throw GlslEvalError("shader never wrote 'outColor'")
            StackSLRegionBound(
                FloatArray(out.c.size) { out.c[it].lo },
                FloatArray(out.c.size) { out.c[it].hi },
                out.c.any { it.nan },
                machine.maybeDiscarded
            )
        } catch (e: GlslUnsupported) {
            StackSLRegionBound(FloatArray(4), FloatArray(4), false, false, e.message ?: "unsupported")
        }
    }

    /**
     * Split a [dim] × [dim] render of [glsl] into squares until each one's colour is pinned to
     * within [tolerance] in every channel, or the square is [minSize] pixels across.
     *
     * A settled tile is *proven* flat to the tolerance: no pixel in it can differ from any other by
     * more, whatever the samples would show. The unsettled ones at the bottom are where the program
     * has edges, noise, or a rule loose enough that a single pixel still straddles.
     */
    fun certifiedTiles(
        glsl: String,
        dim: Int,
        tolerance: Float = 1f / 255f,
        minSize: Int = 1,
        uniforms: Map<String, GlslValue> = emptyMap(),
        onEnclosure: (() -> Unit)? = null,
    ): List<StackSLTile> {
        require(dim > 0 && dim and (dim - 1) == 0) { "dim must be a power of two" }
        val shader = parseGlslShader(glsl)
        val out = ArrayList<StackSLTile>()
        fun visit(x: Int, y: Int, size: Int) {
            val bound = enclosePixels(shader, x, y, x + size - 1, y + size - 1, dim, dim, uniforms)
            onEnclosure?.invoke()
            val settled = bound.certified && !bound.maybeNaN && bound.width <= tolerance
            if (settled || size <= minSize || !bound.certified) {
                out += StackSLTile(x, y, size, bound, settled)
                return
            }
            val h = size / 2
            visit(x, y, h); visit(x + h, y, h); visit(x, y + h, h); visit(x + h, y + h, h)
        }
        visit(0, 0, dim)
        return out
    }

    /**
     * Render [program] through the gallery's still template at [dim] square, prove it into
     * [certifiedTiles], and return both as one picture.
     */
    fun tilePicture(program: String, dim: Int = 128, tolerance: Float = 2f / 255f, time: Float = 1.75f): StackSLTilePicture {
        val glsl = StackSLCpu.expandStackAuto(StackSLCpu.stillTemplate(time), program)
        val render = StackSLCpu.renderFragment(glsl, dim, dim)
        val start = System.nanoTime()
        var runs = 0
        val tiles = certifiedTiles(glsl, dim, tolerance, onEnclosure = { runs++ })
        return StackSLTilePicture(program, dim, tolerance, tiles, render, runs, (System.nanoTime() - start) / 1_000_000)
    }

    /**
     * The tiles drawn as a picture: each settled tile filled with its proven colour and outlined,
     * each unsettled pixel shown magenta. The quadtree *is* the proof, so its shape shows where the
     * program is simple and where it is not.
     */
    fun tilePng(tiles: List<StackSLTile>, dim: Int, scale: Int = 2): ByteArray {
        val w = dim * scale
        val argb = IntArray(w * w)
        // sRGB-encoded, as the gallery stills and CpuRaster.toSrgbArgb are, so a tile matches its render.
        fun channel(v: Float) = Math.round(CpuRaster.linearToSrgb(v) * 255.0).toInt()
        for (t in tiles) {
            val color = if (t.settled) {
                val mid = FloatArray(3) { (t.bound.lo[it] + t.bound.hi[it]) * 0.5f }
                (0xff shl 24) or (channel(mid[0]) shl 16) or (channel(mid[1]) shl 8) or channel(mid[2])
            } else 0xffff00ff.toInt()
            val edge = 0xff3a414a.toInt()
            for (py in t.y * scale until (t.y + t.size) * scale) for (px in t.x * scale until (t.x + t.size) * scale) {
                val border = t.settled && t.size * scale >= 4 &&
                    (px == t.x * scale || py == t.y * scale)
                argb[py * w + px] = if (border) edge else color
            }
        }
        return PngEncoder.encode(argb, w, w)
    }
}

/**
 * A StackSL program's render beside its proof: the `Float` render on the left, the
 * [StackSLIntervals.certifiedTiles] quadtree on the right, and a line of counts under both.
 *
 * Build one with [StackSLIntervals.tilePicture]; it is [afield.ToHTML], so it is the last
 * expression of a box or the argument to `display`.
 */
class StackSLTilePicture(
    val program: String,
    val dim: Int,
    val tolerance: Float,
    val tiles: List<StackSLTile>,
    val render: CpuRaster,
    /** Interval runs the subdivision took, one per tile it visited. */
    val enclosures: Int,
    val millis: Long,
) : afield.ToHTML {

    /** Pixels inside a settled tile: proven to within [tolerance] without being rendered. */
    val settledPixels: Int get() = tiles.filter { it.settled }.sumOf { it.size * it.size }

    /**
     * Pixels inside a settled tile **larger than one pixel** — the ones proven by a region rather
     * than by evaluating them one at a time, which is the saving the quadtree buys.
     */
    val provenByRegion: Int get() = tiles.filter { it.settled && it.size > 1 }.sumOf { it.size * it.size }

    /** Tiles the subdivision gave up on because a rule was missing, with the first reason. */
    val refusal: String? get() = tiles.firstOrNull { !it.bound.certified }?.bound?.refusal

    override fun toHTML(): String {
        val scale = maxOf(1, 384 / dim)
        fun img(png: ByteArray) =
            "<img src='data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(png)}' " +
                "style='width:${dim * scale}px;height:${dim * scale}px;image-rendering:pixelated'/>"
        val renderPng = PngEncoder.encode(upscale(render.toSrgbArgb(), dim, scale), dim * scale, dim * scale)
        val pct = 100.0 * provenByRegion / (dim * dim)
        val caption = buildString {
            append("<code>${program.replace("&", "&amp;").replace("<", "&lt;")}</code> · ")
            append("${tiles.size} tiles; ${"%.1f".format(pct)}% of pixels proven by a tile larger than one pixel; ")
            append("$enclosures interval runs for ${dim * dim} pixels (${millis} ms) · tolerance ${"%.4f".format(tolerance)}")
            refusal?.let { append("<br/>refused: ${it.replace("<", "&lt;")}") }
        }
        return "<div style='font-family:ui-monospace,Menlo,monospace;font-size:12px;color:#9aa4ae'>" +
            "<div style='display:flex;gap:12px'>${img(renderPng)}${img(StackSLIntervals.tilePng(tiles, dim, scale))}</div>" +
            "<div style='margin-top:6px;max-width:${2 * dim * scale + 12}px'>$caption</div></div>"
    }

    private fun upscale(argb: IntArray, dim: Int, scale: Int): IntArray {
        val w = dim * scale
        return IntArray(w * w) { i -> argb[(i / w / scale) * dim + (i % w) / scale] }
    }
}
