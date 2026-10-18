/**
 * Maps tile slots of a [afield.graphics.render.TileResidency] onto pixel coordinates in one GPU
 * atlas texture, with a halo border around each tile.
 *
 * Each slot occupies a square of `cellsPerTile + 2 * halo` pixels, laid out in the residency's
 * columns and rows. [TileAtlasLayout.pixelX] and [TileAtlasLayout.pixelY] address a cell inside a
 * slot (negative cells and cells past the tile reach into the halo), and
 * [TileAtlasLayout.updateHeaders] packs four ints per slot (world tile x and y, generation,
 * occupied) for upload. The layout owns no textures and schedules no work. Halos are meant to be
 * filled from resident neighbouring tiles rather than by wrapping atlas coordinates, so the layout
 * implies no periodic world boundary.
 */

package afield.simulation.flow

import afield.graphics.render.TileResidency

/** GPU atlas addressing without ownership of textures or command scheduling. */
class TileAtlasLayout(val residency: TileResidency, val cellsPerTile: Int, val halo: Int = 1) {
    init {
        require(cellsPerTile > 0 && halo >= 0)
        require(cellsPerTile.toLong() + halo * 2L <= Int.MAX_VALUE)
    }
    val stride = cellsPerTile + halo * 2
    init {
        require(stride.toLong() * residency.columns <= Int.MAX_VALUE)
        require(stride.toLong() * residency.rows <= Int.MAX_VALUE)
    }
    val width = stride * residency.columns
    val height = stride * residency.rows
    val headers = IntArray(residency.capacity * 4)

    fun updateHeaders(): IntArray = residency.pack(headers)
    fun pixelX(slot: Int, cell: Int): Int {
        require(slot in 0 until residency.capacity && cell in -halo until cellsPerTile + halo)
        return (slot % residency.columns) * stride + halo + cell
    }
    fun pixelY(slot: Int, cell: Int): Int {
        require(slot in 0 until residency.capacity && cell in -halo until cellsPerTile + halo)
        return (slot / residency.columns) * stride + halo + cell
    }
    // Populate each halo from a *resident* neighbour, never by wrapping atlas UVs.
    // These functions deliberately do not imply periodic world boundary conditions.
}
