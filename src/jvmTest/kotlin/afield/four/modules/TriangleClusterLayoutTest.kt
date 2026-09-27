package afield.four.modules

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class TriangleClusterLayoutTest {
    @Test fun cpuDescriptionMatchesTheComputeProducerWireFormat() {
        val bytes=ByteBuffer.allocate(TriangleCluster.STRIDE*2).order(ByteOrder.nativeOrder())
        TriangleCluster(81,384,81,128,0x12345678,17).write(bytes)
        TriangleCluster(162,768,9,8,7,3).write(bytes)
        assertEquals(64,bytes.position())
        // Offsets are part of the public GPU ABI, not native Kotlin object layout.
        assertEquals(81,bytes.getInt(0));assertEquals(384,bytes.getInt(4))
        assertEquals(81,bytes.getInt(8));assertEquals(128,bytes.getInt(12))
        assertEquals(0x12345678,bytes.getInt(16));assertEquals(17,bytes.getInt(20))
        assertEquals(0L,bytes.getLong(24));assertEquals(162,bytes.getInt(32))
        assertEquals(8,bytes.getInt(44));assertEquals(3,bytes.getInt(52));assertEquals(0L,bytes.getLong(56))
    }
    @Test fun rejectByteSwappedDescriptions() {
        val wrong=if(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN)ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        assertFailsWith<IllegalArgumentException> { TriangleCluster(0,0,3,1,0).write(ByteBuffer.allocate(32).order(wrong)) }
    }
    @Test fun invalidHostRangesFailBeforeWritingAnAddressableRecord() {
        assertFailsWith<IllegalArgumentException> { TriangleCluster(-1,0,3,1,0) }
        assertFailsWith<IllegalArgumentException> { TriangleCluster(0,-1,3,1,0) }
        assertFailsWith<IllegalArgumentException> { TriangleCluster(0,0,2,1,0) }
        assertFailsWith<IllegalArgumentException> { TriangleCluster(0,0,3,0,0) }
        assertFailsWith<IllegalArgumentException> { TriangleCluster(0,0,3,1,0,-1) }
    }
}
