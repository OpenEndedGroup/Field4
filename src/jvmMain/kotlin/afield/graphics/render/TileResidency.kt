@file:afield.runtime.completion.CompletionCategory("Simulation")

package afield.graphics.render

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.geometry.mesh.contains
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.audio.synth.*
import afield.data.collections.s
import afield.math.linalg.i
import afield.math.linalg.x
import kotlin.math.floor

/**
 * Bookkeeping for a scrolling window of world-aligned tiles: [TileResidency] maps tile coordinates to reusable toroidal slots and reports which slots changed after a move.
 *
 * It allocates no GPU resources or threads; callers use its slot arrays to decide what to regenerate.
 */


/**
 * A bounded, world-aligned rectangle of tiles, stored in reusable toroidal slots.
 * Moving one tile replaces only the entering row/column. Teleporting replaces at
 * most [capacity] tiles. No resources or threads are created here.
 *
 * A slot index alone is not an identity: check coordinates with [resident], and
 * use [generation] to reject old GPU/particle data after a slot is recycled.
 * Arrays are borrowed, read-only to callers, and owned by the updating thread.
 */
class TileResidency(val columns: Int, val rows: Int, val tileSize: Float) {
    init {
        require(columns > 0 && rows > 0 && columns.toLong() * rows <= Int.MAX_VALUE / 4)
        require(tileSize.isFinite() && tileSize > 0f)
    }
    val capacity = columns * rows
    val x = IntArray(capacity)
    val y = IntArray(capacity)
    val generation = IntArray(capacity)
    val changed = IntArray(capacity)
    private val occupied = BooleanArray(capacity)
    var changedCount = 0
        private set
    var minX = 0
        private set
    var minY = 0
        private set
    var relocations = 0L
        private set

    fun slot(tx: Int, ty: Int): Int = Math.floorMod(ty, rows) * columns + Math.floorMod(tx, columns)

    /** -1 outside the resident rectangle, even if the modulo slot contains another tile. */
    fun resident(tx: Int, ty: Int): Int {
        val s = slot(tx, ty)
        return if (occupied[s] && x[s] == tx && y[s] == ty) s else -1
    }

    /** Drop contents at the next move, preserving generation identities. */
    fun invalidate() { occupied.fill(false) }

    fun moveTo(px: Double, py: Double): Int {
        require(px.isFinite() && py.isFinite())
        val nx = floor(px / tileSize) - columns / 2
        val ny = floor(py / tileSize) - rows / 2
        require(nx >= Int.MIN_VALUE.toDouble() && nx + columns - 1 <= Int.MAX_VALUE.toDouble())
        require(ny >= Int.MIN_VALUE.toDouble() && ny + rows - 1 <= Int.MAX_VALUE.toDouble())
        val lowX = nx.toInt()
        val lowY = ny.toInt()
        // Preflight before mutation: overflow may not silently reuse an old generation.
        for (j in 0 until rows) for (i in 0 until columns) {
            val tx = lowX + i
            val ty = lowY + j
            val s = slot(tx, ty)
            if (resident(tx, ty) < 0) check(generation[s] < Int.MAX_VALUE) { "Tile generation exhausted" }
        }
        changedCount = 0
        minX = lowX
        minY = lowY
        for (j in 0 until rows) for (i in 0 until columns) {
            val tx = lowX + i
            val ty = lowY + j
            val s = slot(tx, ty)
            if (resident(tx, ty) < 0) {
                x[s] = tx
                y[s] = ty
                generation[s]++
                occupied[s] = true
                changed[changedCount++] = s
                relocations++
            }
        }
        return changedCount
    }

    /** Four ints per slot: world tile x/y, generation, occupied. No Float precision loss. */
    fun pack(out: IntArray, offset: Int = 0): IntArray {
        require(offset >= 0 && out.size.toLong() >= offset.toLong() + capacity * 4L)
        for (s in 0 until capacity) {
            val at = offset + s * 4
            out[at] = x[s]
            out[at + 1] = y[s]
            out[at + 2] = generation[s]
            out[at + 3] = if (occupied[s]) 1 else 0
        }
        return out
    }
}
