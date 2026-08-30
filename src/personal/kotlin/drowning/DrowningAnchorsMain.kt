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
        println("ANCHOR_TEST_PASS stable slots, missing/reappearing tracks, generation on reuse/loop, bounded layout")
    }
}
