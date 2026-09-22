package afield.four.flow

import afield.stdlib.TileResidency

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
