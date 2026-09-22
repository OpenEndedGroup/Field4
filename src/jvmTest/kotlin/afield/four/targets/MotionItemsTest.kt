package afield.four.targets

import kotlin.test.*
import kotlin.math.PI

class MotionItemsTest {
    @Test fun followsIdentityAndAffinePoseWithoutDependingOnDrawOrder() {
        val items = MotionItems()
        val out = FloatArray(8)
        items.begin(0)
        items.write("a", MotionPose(2f,3f,PI.toFloat()/2,2f), out)
        items.write("b", MotionPose(9f,9f), out)
        items.begin(1)
        items.write("b", MotionPose(9f,10f), out)
        items.write("a", MotionPose(4f,3f,0f,1f), out)
        assertEquals(2f,out[0]); assertEquals(3f,out[1])
        assertEquals(0f,out[2],1e-6f); assertEquals(2f,out[3],1e-6f)
        assertEquals(1f,out[4])
        items.write("a", MotionPose(4f,3f), out)
        assertEquals(2f,out[0], "Repeated vertex writes must use the preceding frame")
        items.begin(2)
        assertEquals(2,items.retained)
        items.begin(3)
        assertEquals(0,items.retained)
    }
    @Test fun topologyChangesGapsAndRewindsInvalidateCorrespondence() {
        val items=MotionItems(); val out=FloatArray(8); val pose=MotionPose()
        items.begin(0); items.write("a",pose,out,revision=1)
        items.begin(1); items.write("a",pose,out,revision=2)
        assertEquals(0f,out[4])
        items.begin(2); items.write("a",pose,out,revision=2)
        assertEquals(1f,out[4])
        items.begin(8); items.write("a",pose,out,revision=2)
        assertEquals(0f,out[4])
        items.begin(0); items.write("a",pose,out,revision=2)
        assertEquals(0f,out[4])
        assertFailsWith<IllegalArgumentException> { items.write("a",MotionPose(scale=0f),out) }
    }
}
