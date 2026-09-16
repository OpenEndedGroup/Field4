package afield.four.texture.mega

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The atlas arithmetic and the page-table format.
 *
 * Both are invisible when wrong: a page written to the wrong layer shows a different photograph,
 * and a mis-packed entry shows a different photograph *stretched*. Neither raises anything, so the
 * only way either gets caught is here.
 */
class MegaTextureLayoutTest {

    private fun layout(
        imageCount: Int = 1500,
        minPageSize: Int = 8,
        levels: Int = 7,
        atlasEdge: Int = 2048,
        layersPerLevel: Int = 2,
        maxBaseLevelLayers: Int = 64
    ) = MegaTextureLayout(imageCount, minPageSize, levels, atlasEdge, layersPerLevel, maxBaseLevelLayers)

    @Test
    fun pagesTileTheLayerExactly() {
        val l = layout()
        for (level in 0 until l.levels) {
            assertEquals(
                l.atlasEdge / l.pageSize(level), l.pagesPerRow[level],
                "level $level should fit ${l.atlasEdge / l.pageSize(level)} pages across"
            )
            assertEquals(l.pagesPerRow[level] * l.pagesPerRow[level], l.pagesPerLayer[level])
        }
        assertEquals(256, l.pagesPerRow[0], "2048 / 8")
        assertEquals(4, l.pagesPerRow[6], "2048 / 512")
    }

    @Test
    fun theBaseLevelHoldsEveryImage() {
        // The fallback the page table publishes is only always available because of this.
        for (count in listOf(1, 1000, 65_536, 65_537, 1_000_000)) {
            val l = layout(imageCount = count)
            assertTrue(
                l.identityLevel[0],
                "level 0 must hold all $count images; it holds ${l.pageCapacity[0]}"
            )
        }
    }

    @Test
    fun aMillionImagesNeedSixteenBaseLayers() {
        val l = layout(imageCount = 1_000_000)
        assertEquals(16, l.layersForLevel[0], "1e6 / 65536, rounded up")
        assertEquals(16 + 6 * 2, l.totalLayers)
        assertEquals((2048L * 2048 * 4 * 28) shr 20, l.atlasBytes shr 20)
    }

    @Test
    fun higherLevelsAreBoundedAndNotIdentity() {
        val l = layout(imageCount = 1_000_000)
        for (level in 1 until l.levels) {
            assertFalse(l.identityLevel[level], "level $level cannot hold a million images")
            assertEquals(2, l.layersForLevel[level])
        }
        // Two 2048 layers of 512-pixel pages is 32 pages: the residency budget that
        // MegaTexture's anti-thrash cap is measured against.
        assertEquals(32, l.pageCapacity[6])
    }

    @Test
    fun everyLevelsLayersAreItsOwn() {
        val l = layout(imageCount = 200_000)
        val owner = IntArray(l.totalLayers) { -1 }
        for (level in 0 until l.levels) {
            for (layer in l.layerBase[level] until l.layerBase[level] + l.layersForLevel[level]) {
                assertEquals(-1, owner[layer], "layer $layer claimed by two levels")
                owner[layer] = level
            }
        }
        assertTrue(owner.none { it < 0 }, "every layer must belong to a level: ${owner.toList()}")
    }

    @Test
    fun slotsCoverTheirLayerWithoutOverlap() {
        val l = layout(imageCount = 4000, atlasEdge = 64, minPageSize = 8, levels = 3, layersPerLevel = 3)
        for (level in 0 until l.levels) {
            val seen = mutableSetOf<Triple<Int, Int, Int>>()
            for (slot in 0 until l.pageCapacity[level]) {
                val where = Triple(l.layerOf(level, slot), l.pageX(level, slot), l.pageY(level, slot))
                assertTrue(seen.add(where), "level $level slot $slot collides at $where")
                assertTrue(
                    where.second + l.pageSize(level) <= l.atlasEdge &&
                            where.third + l.pageSize(level) <= l.atlasEdge,
                    "level $level slot $slot runs off the layer at $where"
                )
            }
            assertEquals(l.pageCapacity[level], seen.size)
        }
    }

    @Test
    fun encodeAndDecodeAgree() {
        val l = layout(imageCount = 1_000_000)
        for (level in 0 until l.levels) {
            for (slot in listOf(0, 1, l.pagesPerLayer[level] - 1, l.pageCapacity[level] - 1)) {
                val entry = l.encode(level, slot, averageColor = 0x7f112233, usedWidth = 17, usedHeight = 9)
                val back = l.decode(entry)
                assertTrue(back.resident)
                assertEquals(level, back.level, "level round trip at $level/$slot")
                assertEquals(l.layerOf(level, slot), back.layer)
                assertEquals(l.pageX(level, slot), back.pageX)
                assertEquals(l.pageY(level, slot), back.pageY)
                assertEquals(0x7f112233, back.averageColor)
                assertEquals(17, back.usedWidth)
                assertEquals(9, back.usedHeight)
            }
        }
    }

    @Test
    fun anAbsentEntryCarriesOnlyItsColour() {
        val back = layout().decode(layout().encodeAbsent(0xff204060.toInt()))
        assertFalse(back.resident)
        assertEquals(0xff204060.toInt(), back.averageColor)
        assertEquals(0, back.usedWidth)
    }

    @Test
    fun theResidentBitCannotCollideWithLayerOrLevel() {
        // 12 bits of layer + 4 bits of level sit below bit 20; if that stopped being true, an
        // entry in a high layer would read as "not resident" and the image would go grey.
        assertEquals(1 shl 20, MegaTextureLayout.RESIDENT_BIT)
        assertTrue(MegaTextureLayout.MAX_LAYER < MegaTextureLayout.RESIDENT_BIT)
        assertTrue((0xf shl MegaTextureLayout.LEVEL_SHIFT) < MegaTextureLayout.RESIDENT_BIT)
    }

    @Test
    fun anUnaddressableAtlasIsRefusedRatherThanWrapped() {
        // 8-pixel pages in a 128-pixel layer is 256 per layer, so ten million images would want
        // far more layers than the page table's 12 bits can name.
        assertFailsWith<IllegalArgumentException> {
            layout(imageCount = 10_000_000, atlasEdge = 128, maxBaseLevelLayers = Int.MAX_VALUE)
        }
    }

    @Test
    fun encodeRefusesASlotOutsideItsLevel() {
        val l = layout()
        assertFailsWith<IllegalArgumentException> { l.encode(0, l.pageCapacity[0], 0, 8, 8) }
        assertFailsWith<IllegalArgumentException> { l.encode(l.levels, 0, 0, 8, 8) }
    }

    // ------------------------------------------------------------------ the anti-thrash ceiling

    private fun wants(vararg pairs: Pair<Int, Int>): IntArray {
        val counts = IntArray(7)
        pairs.forEach { (level, count) -> counts[level] = count }
        return counts
    }

    @Test
    fun aDemandThatFitsIsNotClampedAtAll() {
        val l = layout()
        // 28 images wanting 512-pixel pages, 32 of them available: serve it.
        assertEquals(6, l.ceilingFor(wants(6 to 28)))
        // Nothing on screen: the ceiling is the top, because there is no pressure to relieve and
        // the first request to arrive next frame should be served at whatever it asks for.
        assertEquals(l.levels - 1, l.ceilingFor(wants()))
    }

    /**
     * The regression this policy exists for, from the stress run that found it.
     *
     * 1189 images wanted 128-pixel pages and only 512 of those exist. The first version of this
     * scanned for *any* level whose demand fitted and took the highest, so the empty top level
     * always qualified: it reported a ceiling of 6, clamped nothing, and left those 1189 images
     * evicting each other every frame — 108,840 page loads for 1639 images.
     */
    @Test
    fun anEmptyTopLevelCannotRaiseTheCeilingOverAnOversubscribedOneBelow() {
        val l = layout()
        val ceiling = l.ceilingFor(wants(3 to 677, 4 to 1189))
        assertTrue(
            ceiling < 4,
            "level 4 holds ${l.pageCapacity[4]} pages and 1189 images want it, so the ceiling " +
                    "must be below it; got $ceiling"
        )
        assertTrue(ceiling >= 0)
    }

    @Test
    fun theCeilingLandsOnALevelThatCanHoldEverythingAtOrAboveIt() {
        val l = layout()
        val cases = listOf(
            wants(6 to 1000),
            wants(5 to 400, 6 to 30),
            wants(3 to 677, 4 to 1189),
            wants(2 to 1639),
            wants(0 to 1639),
            wants(1 to 20, 3 to 900, 6 to 5)
        )
        for (counts in cases) {
            val ceiling = l.ceilingFor(counts)
            val atOrAbove = (ceiling until l.levels).sumOf { counts[it] }
            assertTrue(
                atOrAbove <= (l.pageCapacity[ceiling] * 9) / 10,
                "ceiling $ceiling for ${counts.toList()} puts $atOrAbove images in " +
                        "${l.pageCapacity[ceiling]} pages"
            )
        }
    }

    @Test
    fun demandNoLevelCanHoldFallsBackToTheBase() {
        // Every image at once at the finest level: the base is the only level that fits them all,
        // and it is the one the design guarantees can.
        val l = layout(imageCount = 1_000_000)
        assertEquals(0, l.ceilingFor(IntArray(l.levels).also { it[6] = 1_000_000 }))
    }

    /**
     * The shader's masks and shifts are the layout's, not a remembered copy of them.
     *
     * [MegaTextureLayout.encode] and the GLSL in [megaFunctionsSource] are two separate accounts
     * of one bit layout. `MegaTextureShaderTest` compiles that GLSL; this checks it is talking
     * about the same bits.
     */
    @Test
    fun theShaderTakesItsBitLayoutFromTheLayout() {
        val declarations = megaDeclarationsSource(layout(), MegaTextureConfig())
        assertTrue(
            "0x%xu".format(MegaTextureLayout.RESIDENT_BIT) in declarations,
            "the shader's megaResidentBit must be the layout's:\n$declarations"
        )
        assertTrue(
            "megaLevelShift   = ${MegaTextureLayout.LEVEL_SHIFT}" in declarations,
            "the shader's level shift must be the layout's:\n$declarations"
        )
        assertTrue(
            "0x%xu".format(MegaTextureLayout.MAX_LAYER) in declarations,
            "the shader's layer mask must be the layout's:\n$declarations"
        )
    }
}
