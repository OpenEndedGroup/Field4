package drowning

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** No renderer/assets needed: identity, loss, replacement, loop and bounded GPU record tests. */
object DrowningAnchorsMain {
    private fun frame(index: Int, tracks: List<Int>): MxgFrame {
        val nodes = ByteBuffer.allocate(tracks.size * MxgFrame.NODE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        tracks.forEachIndexed { n, id ->
            val at = n*MxgFrame.NODE_BYTES
            nodes.putFloat(at, 10f+n); nodes.putFloat(at+4, 20f)
            nodes.putFloat(at+16, 100f+n)
            nodes.putInt(at+64, 0); nodes.putInt(at+80, id)
        }
        return MxgFrame(100,100,256,tracks.size,tracks.size,0,0,index,index,index/24.0,nodes,ByteBuffer.allocate(0))
    }
    @JvmStatic fun main(args: Array<String>) {
        val a = DrowningAnchors(cells=1, slotsPerCell=2, graceFrames=2)
        a.update(listOf(frame(0,listOf(10,20,30))))
        check(a.activeCount == 2)
        val ids = (0..1).map(a::track)
        val generations = (0..1).map(a::generation)
        a.update(listOf(frame(1,listOf(30,20,10))))
        check((0..1).map(a::track) == ids && (0..1).map(a::generation) == generations)
        a.update(listOf(frame(2,listOf(99))))
        check(a.activeCount == 0) // reserve both lost identities; don't snap to 99
        a.update(listOf(frame(3,listOf(20,30))))
        check(a.activeCount == 2 && (0..1).map(a::generation) == generations)
        a.update(listOf(frame(7,listOf(99,100))))
        check(a.activeCount == 2 && (0..1).all { a.generation(it) > generations[it] })
        val beforeLoop = (0..1).map(a::generation)
        a.update(listOf(frame(0,listOf(99,100))))
        check((0..1).all { a.generation(it) > beforeLoop[it] })
        val bytes = ByteBuffer.allocate(a.vec4s*16)
        a.write(bytes)
        check(bytes.position() == a.vec4s*16)
        val sparse = DrowningAnchors(cells=2, slotsPerCell=1, graceFrames=0)
        sparse.update(intArrayOf(1), listOf(frame(4, listOf(40))))
        check(!sparse.valid(0) && sparse.valid(1) && sparse.activeCount == 1)
        sparse.update(intArrayOf(0), listOf(frame(5, listOf(50))))
        check(sparse.valid(0) && !sparse.valid(1) && sparse.activeCount == 1)

        val sculpted = DrowningAnchors(cells=1, slotsPerCell=3)
        val warp = MxgTreeWarp(1)
        warp[0].set(1f, 2f, 3f, .75f)
        sculpted.update(intArrayOf(0), listOf(frame(0, listOf(10, 20, 30))), warp)
        val sculptedBytes = ByteBuffer.allocate(sculpted.vec4s * 16).order(ByteOrder.LITTLE_ENDIAN)
        sculpted.write(sculptedBytes)
        val rootSlot = (0 until sculpted.count).single { sculpted.track(it) == 10 }
        val leafSlots = (0 until sculpted.count).filter { sculpted.track(it) != 10 }
        val treeAt = rootSlot * 48 + 32
        check(sculptedBytes.getFloat(treeAt) == 1f && sculptedBytes.getFloat(treeAt + 4) == 2f)
        check(sculptedBytes.getFloat(treeAt + 8) == 3f && sculptedBytes.getFloat(treeAt + 12) == .75f)
        check(leafSlots.all { sculptedBytes.getFloat(it * 48 + 44) == 0f })
        warp[0].set(4f, 5f, 6f, .5f)
        sculpted.applyTreeWarp(warp)
        val movedBytes = ByteBuffer.allocate(sculpted.vec4s * 16).order(ByteOrder.LITTLE_ENDIAN)
        sculpted.write(movedBytes)
        check(movedBytes.getFloat(treeAt) == 4f && movedBytes.getFloat(treeAt + 12) == .5f)
        println("ANCHOR_TEST_PASS stable slots, sparse cells, sculpted roots/leaves, generation on reuse/loop, bounded layout")
    }
}
