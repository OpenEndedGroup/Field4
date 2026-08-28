@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import afield.four.fabricated.GaussianSplatRenderer
import afield.four.fabricated.SplatSequence
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A whole splat sequence in one image: N frames rasterized small and tiled into a grid.
 *
 * The capture this pipeline was built for is 126 frames. Before this there was no way to see more
 * than one of them at a time — [SplatSequence] plus [afield.four.fabricated.SplatTransport] can
 * scrub a play head over them beautifully, but scrubbing is a *serial* instrument, and the questions
 * you actually have about a fresh capture are parallel ones: which frames are empty, where does the
 * subject leave frame, did the depth estimator lose the plot at 90, are frames 40-60 the same shot.
 * A contact sheet answers all four at a glance and takes no GPU.
 *
 * ```
 * val sheet = SplatContactSheet.of(SplatSequence.fromDirectory(dir), every = 6)
 * File("sheet.png").writeBytes(sheet.png())
 * ```
 *
 * @see SplatRaster for what draws each tile, and for the Y convention.
 */
object SplatContactSheet {

    /**
     * Where the camera goes. [autoFrame] is the useful default: an arbitrary capture's units and
     * origin are not knowable in advance, so guessing an eye position produces an empty sheet and no
     * hint as to why.
     */
    data class SheetCamera(
        val eye: Vector3f,
        val target: Vector3f,
        val up: Vector3f = Vector3f(0f, 1f, 0f),
        val fovY: Float = Math.toRadians(50.0).toFloat()
    ) {
        fun viewAt(width: Int, height: Int): SplatRaster.View = SplatRaster.View(
            modelView = Matrix4f().lookAtField(eye, target, up),
            proj = Matrix4f().setPerspective(
                fovY, width.toFloat() / height.toFloat(), 0.01f, 1000f, zZeroToOne = true
            ),
            width = width,
            height = height
        )

        companion object {
            /**
             * A camera that fits the box [min]..[max] in frame, looking from direction [from].
             *
             * Fits the **box in the view's own axes**, not its bounding sphere. The sphere is one
             * line shorter and wastes a great deal on a flat cloud, which is the normal case here:
             * a depth-map capture of one video frame measures something like 1.77 x 0.92 x 0.58, so
             * its bounding sphere has radius 1.04 where the view needs 0.46 vertically. That is more
             * than a factor of two of linear magnification thrown away, and on a 192 x 108 contact
             * sheet tile it is the difference between seeing the subject and seeing that there is one.
             *
             * The default [from] and [up] are the depth-map capture convention this pipeline exists
             * for: those captures are `+z` forward and `+y` **down**, which is why `up` is
             * `(0, -1, 0)` and the camera sits at negative `z`. The `splat_stream` document records
             * the same convention. A cloud from anywhere else will want its own.
             *
             * @param aspect width over height of the target viewport. Fitting needs it: a cloud wider
             *   than the frame is limited horizontally, and which limit binds cannot be known from the
             *   box alone.
             */
            fun autoFrame(
                min: Vector3f,
                max: Vector3f,
                from: Vector3f = Vector3f(0f, -0.35f, -1f),
                up: Vector3f = Vector3f(0f, -1f, 0f),
                fovY: Float = Math.toRadians(50.0).toFloat(),
                aspect: Float = 16f / 9f,
                margin: Float = 1.08f
            ): SheetCamera {
                val centre = Vector3f(
                    (min.x + max.x) * 0.5f, (min.y + max.y) * 0.5f, (min.z + max.z) * 0.5f
                )
                val direction = Vector3f(from)
                if (direction.lengthSquared() < 1e-12f) direction.set(0f, 0f, -1f)
                direction.normalize()

                // The same basis lookAtField will build: forward, then right = forward x up, then a
                // re-orthogonalised up. Derived here rather than assumed, so a `from` that is nearly
                // parallel to `up` degrades the same way the view matrix does.
                val forward = Vector3f(-direction.x, -direction.y, -direction.z)
                var right = Vector3f(forward).cross(up, Vector3f())
                if (right.lengthSquared() < 1e-12f) right = Vector3f(1f, 0f, 0f)
                right.normalize()
                val trueUp = Vector3f(right).cross(forward, Vector3f()).normalize()

                val tanY = tan(fovY * 0.5f)
                val tanX = tanY * aspect
                var needed = 1e-3f
                // Every corner has to fit both ways, and a corner nearer the camera needs more
                // distance for the same offset, so the depth of each corner enters the bound.
                for (cornerX in 0..1) for (cornerY in 0..1) for (cornerZ in 0..1) {
                    val point = Vector3f(
                        (if (cornerX == 0) min.x else max.x) - centre.x,
                        (if (cornerY == 0) min.y else max.y) - centre.y,
                        (if (cornerZ == 0) min.z else max.z) - centre.z
                    )
                    val alongRight = point.dot(right)
                    val alongUp = point.dot(trueUp)
                    val alongForward = point.dot(forward)
                    needed = max(needed, abs(alongUp) / tanY - alongForward)
                    needed = max(needed, abs(alongRight) / tanX - alongForward)
                }
                val distance = needed * margin
                return SheetCamera(
                    eye = Vector3f(
                        centre.x + direction.x * distance,
                        centre.y + direction.y * distance,
                        centre.z + direction.z * distance
                    ),
                    target = centre,
                    up = up,
                    fovY = fovY
                )
            }
        }
    }

    /** How the grid is laid out. */
    data class Layout(
        val tileWidth: Int = 192,
        val tileHeight: Int = 108,
        /** Tiles per row, or null to pick a roughly square grid. */
        val columns: Int? = null,
        val gutter: Int = 3,
        /** Linear-space grey behind everything, including the gutters. */
        val paper: Float = 0.06f,
        /** Draw the frame number in the corner of each tile. Off makes the sheet denser. */
        val labels: Boolean = true
    )

    /** A finished sheet: the image, the grid it used, and what went into each tile. */
    class Sheet(
        val image: SplatRaster.SplatImage,
        val frames: List<Int>,
        val columns: Int,
        val rows: Int,
        val camera: SheetCamera,
        val tiles: List<SplatRaster.RasterStats>
    ) {
        /** Total splats drawn across every tile, and total considered. */
        val drawn: Int get() = tiles.sumOf { it.drawn }
        val considered: Int get() = tiles.sumOf { it.considered }

        /**
         * Tiles where nothing landed, by frame number. The first thing to look at on a fresh
         * capture, and the reason [SplatRaster.RasterStats] is kept per tile rather than summed: a
         * sheet where three frames are empty and the rest are fine looks, in aggregate, entirely
         * healthy.
         */
        val emptyFrames: List<Int>
            get() = frames.filterIndexed { index, _ -> tiles[index].drawn == 0 }

        fun png(background: Int? = 0xff000000.toInt()): ByteArray = image.toPng(background)

        override fun toString(): String =
            "${columns}x$rows sheet of ${frames.size} frames, $drawn/$considered splats drawn" +
                if (emptyFrames.isEmpty()) "" else ", EMPTY at ${emptyFrames.joinToString()}"
    }

    /**
     * Rasterize [frames] of [sequence] into one tiled image.
     *
     * @param frames which frames, in the order they should appear. Defaults to a spread of at most
     *   [maxTiles] frames across the whole sequence, which is the "show me the shot" case.
     * @param camera null to [SheetCamera.autoFrame] over the union of the chosen frames' bounds — the
     *   union rather than each frame's own, so a subject that moves reads as moving instead of being
     *   re-centred in every tile.
     * @param transform applied to every frame, as [GaussianSplatRenderer.SplatSource.transform].
     */
    fun of(
        sequence: SplatSequence,
        frames: List<Int> = spread(sequence.frameCount, 24),
        layout: Layout = Layout(),
        camera: SheetCamera? = null,
        config: SplatRaster.RasterConfig = SplatRaster.RasterConfig(),
        packConfig: GaussianSplatRenderer.Config = GaussianSplatRenderer.Config(),
        transform: Matrix4f? = null
    ): Sheet {
        require(frames.isNotEmpty()) { "a contact sheet needs at least one frame" }

        val columns = layout.columns ?: ceil(sqrt(frames.size.toDouble())).toInt()
        require(columns >= 1) { "columns must be at least 1, got $columns" }
        val rows = ceil(frames.size / columns.toDouble()).toInt()

        // One scratch buffer, sized for the largest frame in the selection, reused per tile.
        val peak = frames.maxOf { sequence.raw(it).count }
        val packed = SplatRaster.allocatePacked(peak)

        val resolved = camera ?: run {
            // Union of every chosen frame's bounds. packInto reports them, so this costs one extra
            // pack per frame -- worth it, because an auto-framed camera derived from frame 0 alone
            // clips the shot the moment the subject moves.
            val min = Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val max = Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (frame in frames) {
                val raw = sequence.raw(frame)
                val bounds = GaussianSplatRenderer.packInto(
                    listOf(GaussianSplatRenderer.SplatSource(raw, transform)), packed, packConfig
                )
                min.set(
                    min(min.x, bounds.boundsMin.x), min(min.y, bounds.boundsMin.y),
                    min(min.z, bounds.boundsMin.z)
                )
                max.set(
                    max(max.x, bounds.boundsMax.x), max(max.y, bounds.boundsMax.y),
                    max(max.z, bounds.boundsMax.z)
                )
            }
            SheetCamera.autoFrame(
                min, max,
                aspect = layout.tileWidth.toFloat() / layout.tileHeight.toFloat()
            )
        }

        val width = columns * layout.tileWidth + (columns + 1) * layout.gutter
        val height = rows * layout.tileHeight + (rows + 1) * layout.gutter
        val sheet = SplatRaster.SplatImage(width, height)
        sheet.fill(0, 0, width, height, layout.paper, layout.paper, layout.paper)

        val view = resolved.viewAt(layout.tileWidth, layout.tileHeight)
        val tile = SplatRaster.SplatImage(layout.tileWidth, layout.tileHeight)
        val tiles = ArrayList<SplatRaster.RasterStats>(frames.size)

        frames.forEachIndexed { index, frame ->
            java.util.Arrays.fill(tile.linear, 0f)
            val raw = sequence.raw(frame)
            GaussianSplatRenderer.packInto(
                listOf(GaussianSplatRenderer.SplatSource(raw, transform)), packed, packConfig
            )
            val result = SplatRaster.rasterize(packed, raw.count, view, config, tile)
            tiles += result.stats

            val atX = layout.gutter + (index % columns) * (layout.tileWidth + layout.gutter)
            val atY = layout.gutter + (index / columns) * (layout.tileHeight + layout.gutter)
            sheet.over(tile, atX, atY)
            if (layout.labels) label(sheet, frame.toString(), atX + 3, atY + 3)
        }

        return Sheet(sheet, frames, columns, rows, resolved, tiles)
    }

    /**
     * [count] frames spread evenly over [frameCount], endpoints included.
     *
     * Evenly rather than the first [count]: the first twenty-four frames of a 126-frame shot are the
     * least informative twenty-four you could pick, because they are all the same moment.
     */
    fun spread(frameCount: Int, count: Int): List<Int> {
        require(frameCount >= 1) { "frameCount must be at least 1" }
        val wanted = count.coerceIn(1, frameCount)
        if (wanted == 1) return listOf(0)
        return (0 until wanted).map { ((it.toLong() * (frameCount - 1)) / (wanted - 1)).toInt() }
    }

    // ------------------------------------------------------------------------
    // A 3x5 digit font
    // ------------------------------------------------------------------------

    /**
     * Frame numbers, drawn from a 3x5 bitmap.
     *
     * Deliberately not AWT. A contact sheet whose labels needed `java.awt.Font` would drag a
     * headless-toolkit dependency into a path whose whole point is that it runs anywhere, and the
     * house route for CPU raster in this tree is [afield.util.PngEncoder] and no AWT at all. Ten
     * glyphs of fifteen bits each is a cheaper answer than a font.
     */
    private val DIGITS = arrayOf(
        intArrayOf(0b111, 0b101, 0b101, 0b101, 0b111), // 0
        intArrayOf(0b010, 0b110, 0b010, 0b010, 0b111), // 1
        intArrayOf(0b111, 0b001, 0b111, 0b100, 0b111), // 2
        intArrayOf(0b111, 0b001, 0b111, 0b001, 0b111), // 3
        intArrayOf(0b101, 0b101, 0b111, 0b001, 0b001), // 4
        intArrayOf(0b111, 0b100, 0b111, 0b001, 0b111), // 5
        intArrayOf(0b111, 0b100, 0b111, 0b101, 0b111), // 6
        intArrayOf(0b111, 0b001, 0b001, 0b001, 0b001), // 7
        intArrayOf(0b111, 0b101, 0b111, 0b101, 0b111), // 8
        intArrayOf(0b111, 0b101, 0b111, 0b001, 0b001)  // 9
    )

    private fun label(into: SplatRaster.SplatImage, text: String, atX: Int, atY: Int, scale: Int = 2) {
        // A dark plate first, so a digit stays legible over a bright splat.
        into.fill(
            atX - 1, atY - 1,
            text.length * (3 * scale + scale) + scale, 5 * scale + 2 * scale,
            0f, 0f, 0f, 0.55f
        )
        var penX = atX
        for (character in text) {
            val glyph = DIGITS.getOrNull(character - '0') ?: continue
            for (row in 0 until 5) {
                for (column in 0 until 3) {
                    if ((glyph[row] shr (2 - column)) and 1 == 0) continue
                    into.fill(
                        penX + column * scale, atY + row * scale, scale, scale,
                        1f, 1f, 1f, 1f
                    )
                }
            }
            penX += 4 * scale
        }
    }
}
