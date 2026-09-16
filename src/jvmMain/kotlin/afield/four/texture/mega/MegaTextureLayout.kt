package afield.four.texture.mega

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Where every page lives in the atlas, and how a page table entry is packed.
 *
 * Split out from [MegaTexture] because it is the part most worth testing and the part least able
 * to be tested through it: the arithmetic is invisible at runtime — a page written to the wrong
 * layer shows the wrong photograph, not an error — and reaching it needs a Vulkan device it does
 * not otherwise care about. Nothing here touches the GPU, so [MegaTextureLayoutTest] can check
 * the packing and the geometry directly.
 *
 * The other reason it is one object: the *only* two things that know the page-table format are
 * [encode] here and the GLSL in [megaFunctionsSource], and [decode] exists so a test can hold
 * those two accounts of it against each other.
 */
class MegaTextureLayout(
    val imageCount: Int,
    val minPageSize: Int,
    val levels: Int,
    val atlasEdge: Int,
    layersPerLevel: Int,
    maxBaseLevelLayers: Int
) {
    /** Pages across one atlas layer at this level. At least 1, even for a page wider than a layer. */
    val pagesPerRow = IntArray(levels) { max(1, atlasEdge / (minPageSize shl it)) }
    val pagesPerLayer = IntArray(levels) { pagesPerRow[it] * pagesPerRow[it] }
    val layersForLevel = IntArray(levels)

    /** First atlas array layer belonging to this level. */
    val layerBase = IntArray(levels)

    /** Total pages this level can hold at once. */
    val pageCapacity = IntArray(levels)

    /**
     * True when this level can hold every image, so `slot == image index`.
     *
     * Worth a name because it is the base level's defining property: identity means no allocator,
     * no map and no eviction, and it is what lets the page table always have a fallback to publish.
     */
    val identityLevel = BooleanArray(levels)

    val totalLayers: Int

    init {
        require(levels in 1..15) { "a MegaTexture has between 1 and 15 levels, not $levels" }
        require(minPageSize > 0) { "minPageSize must be positive" }
        require(atlasEdge > 0) { "atlasEdge must be positive" }
        var layer = 0
        for (level in 0 until levels) {
            val needed = ceil(imageCount.toDouble() / pagesPerLayer[level]).toInt().coerceAtLeast(1)
            val count = if (level == 0) min(needed, max(1, maxBaseLevelLayers))
            else min(needed, max(1, layersPerLevel))
            layersForLevel[level] = count
            layerBase[level] = layer
            pageCapacity[level] = count * pagesPerLayer[level]
            identityLevel[level] = pageCapacity[level] >= imageCount
            layer += count
        }
        totalLayers = layer
        require(totalLayers <= MAX_LAYER + 1) {
            "a MegaTexture needs $totalLayers atlas layers, more than the ${MAX_LAYER + 1} the " +
                    "page table can address; raise atlasEdge or lower layersPerLevel"
        }
    }

    fun pageSize(level: Int): Int = minPageSize shl level

    /** Bytes one atlas layer occupies as RGBA8. */
    val bytesPerLayer: Long get() = atlasEdge.toLong() * atlasEdge * 4

    /** Total atlas allocation in bytes. */
    val atlasBytes: Long get() = bytesPerLayer * totalLayers

    /** The atlas array layer holding this level's slot. */
    fun layerOf(level: Int, slot: Int): Int = layerBase[level] + slot / pagesPerLayer[level]

    /** Pixel column of this slot's page within its layer. */
    fun pageX(level: Int, slot: Int): Int =
        ((slot % pagesPerLayer[level]) % pagesPerRow[level]) * pageSize(level)

    /** Pixel row of this slot's page within its layer. */
    fun pageY(level: Int, slot: Int): Int =
        ((slot % pagesPerLayer[level]) / pagesPerRow[level]) * pageSize(level)

    /**
     * The highest level worth serving, given what the shader asked for and how many pages exist.
     *
     * [MegaTexture] clamps its requests to this. Without it, a close-up of forty images with thirty-two
     * 512-pixel pages available evicts a page that is still on screen in order to load another
     * that is also still on screen, every frame, forever: the classic megatexture thrash, and it
     * looks like flickering rather than like slowness.
     *
     * A ceiling `C` is feasible when, *after* clamping, no level is oversubscribed. Clamping moves
     * everything above `C` onto `C`, so that is two conditions: the images wanting `C` or finer
     * must fit `C`, and every level below `C` must already fit its own demand. The second is easy
     * to forget and is exactly what went wrong first time — scanning for "any level whose demand
     * fits" picked the top level whenever nothing was asking for it, which clamped nothing at all
     * and left 1189 images fighting over 512 pages while reporting a ceiling of 6.
     *
     * Nine tenths rather than all of a level's capacity: a page evicted this frame is not
     * allocatable for `numImages` more frames, so a level run to exactly full cannot
     * hand out the slot it just freed.
     */
    fun ceilingFor(wantCounts: IntArray): Int {
        fun budget(level: Int) = (pageCapacity[level] * 9) / 10

        // The lowest level that cannot hold even its own demand. No ceiling above it is feasible,
        // and no ceiling at or below it can help that level — but clamping to it keeps everything
        // coarser from making it worse.
        var firstOverflow = levels
        for (level in 0 until levels) {
            if (wantCounts[level] > budget(level)) {
                firstOverflow = level
                break
            }
        }

        var atOrAbove = 0
        for (level in levels - 1 downTo 0) {
            atOrAbove += wantCounts[level]
            if (level > firstOverflow) continue
            if (atOrAbove <= budget(level)) return level
        }
        return 0
    }

    /** One page table entry, as the four `uint`s the shader unpacks. */
    fun encode(
        level: Int,
        slot: Int,
        averageColor: Int,
        usedWidth: Int,
        usedHeight: Int
    ): IntArray {
        require(level in 0 until levels) { "level $level is outside 0..${levels - 1}" }
        require(slot in 0 until pageCapacity[level]) {
            "slot $slot is outside level $level's 0..${pageCapacity[level] - 1}"
        }
        return intArrayOf(
            (pageX(level, slot) and 0xffff) or ((pageY(level, slot) and 0xffff) shl 16),
            (layerOf(level, slot) and MAX_LAYER) or ((level and 0xf) shl LEVEL_SHIFT) or RESIDENT_BIT,
            averageColor,
            (usedWidth and 0xffff) or ((usedHeight and 0xffff) shl 16)
        )
    }

    /** An entry for an image with no page yet: mean colour only. */
    fun encodeAbsent(averageColor: Int): IntArray = intArrayOf(0, 0, averageColor, 0)

    /**
     * Read an entry back. The inverse of [encode], and the Kotlin twin of what the GLSL does —
     * which is the point: a test can assert that a page put in a slot comes back out as the same
     * page, in the same layer, with the same aspect, without a GPU.
     */
    fun decode(entry: IntArray, at: Int = 0): Entry {
        val x = entry[at]
        val y = entry[at + 1]
        return Entry(
            resident = (y and RESIDENT_BIT) != 0,
            pageX = x and 0xffff,
            pageY = (x ushr 16) and 0xffff,
            layer = y and MAX_LAYER,
            level = (y ushr LEVEL_SHIFT) and 0xf,
            averageColor = entry[at + 2],
            usedWidth = entry[at + 3] and 0xffff,
            usedHeight = (entry[at + 3] ushr 16) and 0xffff
        )
    }

    data class Entry(
        val resident: Boolean,
        val pageX: Int,
        val pageY: Int,
        val layer: Int,
        val level: Int,
        val averageColor: Int,
        val usedWidth: Int,
        val usedHeight: Int
    )

    override fun toString(): String =
        "MegaTextureLayout($imageCount images, $levels levels, ${atlasEdge}² × $totalLayers layers " +
                "= ${atlasBytes shr 20} MiB, capacities ${pageCapacity.toList()})"

    companion object {
        /** Bit 20 of the second word: "this entry names a real page". */
        const val RESIDENT_BIT = 1 shl 20

        /** The level occupies bits 12..15, so the layer gets the low 12. */
        const val LEVEL_SHIFT = 12
        const val MAX_LAYER = (1 shl LEVEL_SHIFT) - 1
    }
}
