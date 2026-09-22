package afield.stdlib

import afield.four.flow.TileAtlasLayout
import kotlin.test.*

class TileResidencyTest {
    @Test fun scrollRetainsOverlapAndRejectsWrappedNeighbours() {
        val tiles = TileResidency(5, 3, 16f)
        assertEquals(15, tiles.moveTo(-.01, -.01))
        val old = tiles.resident(-1,-1)
        val generation = tiles.generation[old]
        assertEquals(0, tiles.moveTo(-.02,-.02))
        assertEquals(3, tiles.moveTo(0.0, -.01))
        assertEquals(old, tiles.resident(-1,-1))
        assertEquals(generation, tiles.generation[old])
        assertEquals(-1, tiles.resident(1000,1000))
        assertEquals(15, tiles.moveTo(1000000.0,1000000.0))
        assertEquals(-1, tiles.resident(-1,-1))
        tiles.invalidate()
        assertEquals(15, tiles.moveTo(1000000.0,1000000.0))
    }
    @Test fun rejectsInvalidDimensionsAndCoordinatesBeforeMutation() {
        assertFailsWith<IllegalArgumentException> { TileResidency(0, 2, 1f) }
        assertFailsWith<IllegalArgumentException> { TileResidency(Int.MAX_VALUE, 2, 1f) }
        assertFailsWith<IllegalArgumentException> { TileResidency(1, 1, Float.NaN) }
        val tiles = TileResidency(1,1,1f)
        tiles.moveTo(0.0,0.0)
        assertFailsWith<IllegalArgumentException> { tiles.moveTo(Double.MAX_VALUE,0.0) }
        assertEquals(0, tiles.resident(0,0))
        // No uninitialized sentinel can appear as a resident world tile.
        assertEquals(-1, TileResidency(1,1,1f).resident(0,0))
    }
    @Test fun atlasKeepsEachTileInteriorAndHaloSeparate() {
        val tiles = TileResidency(3,2,16f)
        tiles.moveTo(0.0,0.0)
        val atlas = TileAtlasLayout(tiles, 32)
        assertEquals(102, atlas.width)
        assertEquals(68, atlas.height)
        assertEquals(35, atlas.pixelX(1,0))
        assertEquals(33, atlas.pixelY(0,32))
        val data = atlas.updateHeaders()
        assertEquals(tiles.x[2], data[8])
        assertEquals(1, data[11])
    }
}
