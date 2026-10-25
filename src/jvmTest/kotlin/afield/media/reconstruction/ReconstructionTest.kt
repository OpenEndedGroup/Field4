package afield.media.reconstruction

import kotlin.test.*
import afield.graphics.texture.HasImageView
import afield.graphics.texture.ImageView

class ReconstructionTest {
    @Test fun declaredMotionConventionConvertsKnownTranslationToPreviousPixels() {
        val image=object : HasImageView {
            override val imageView: ImageView get()=error("Motion scaling must not access GPU images")
        }
        val size=ReconstructionSize(1280,720)
        for(currentMinusPrevious in listOf(false,true))for(inPixels in listOf(false,true)) {
            val inputs=ReconstructionInputs(image,image,image,currentMinusPrevious,inPixels)
            val (sx,sy)=inputs.motionScale(size)
            val sign=if(currentMinusPrevious)1f else -1f
            val x=sign*8f/(if(inPixels)1f else size.width.toFloat())
            val y=sign*-4f/(if(inPixels)1f else size.height.toFloat())
            assertEquals(-8f,x*sx)
            assertEquals(4f,y*sy)
        }
        assertEquals(1f to 1f,ReconstructionInputs(image,image,image).motionScale(size))
    }
    @Test fun motionIsCurrentToPreviousInInputPixels() {
        // A point moves from x=320 to x=328 in a 1280-wide input. Field UV velocity is positive.
        val fieldVelocity=(328f-320f)/1280f
        assertEquals(-8f,ReconstructionJitter.fieldMotionToPixels(fieldVelocity,1280))
        assertEquals(4f,ReconstructionJitter.fieldMotionToPixels((176f-180f)/720f,720))
    }
    @Test fun jitterCoversAllQuadrantsAndScalesItsPeriod() {
        val output=ReconstructionSize(1920,1080)
        for(input in listOf(output,ReconstructionSize(960,540))) {
            val count=if(input==output)8 else 32
            val samples=(0 until count).map { ReconstructionJitter.halton(it.toLong(),input,output) }
            assertEquals(count,samples.distinct().size)
            assertEquals(samples[0],ReconstructionJitter.halton(count.toLong(),input,output))
            assertEquals(4,samples.filter { it.x!=0f && it.y!=0f }.map { (it.x>0) to (it.y>0) }.toSet().size)
            assertTrue(samples.all { it.x in -.5f.. .5f && it.y in -.5f.. .5f })
        }
    }
    @Test fun rejectInvalidFrameContractsBeforeNativeCalls() {
        assertFailsWith<IllegalArgumentException> { ReconstructionSize(0,16) }
        assertFailsWith<IllegalArgumentException> { ReconstructionJitter(Float.NaN,0f) }
        assertFailsWith<IllegalArgumentException> { ReconstructionFrame(0,ReconstructionJitter(0f,0f),preExposure=0f) }
        assertFailsWith<IllegalArgumentException> { ReconstructionFrame(0,ReconstructionJitter(0f,0f),milliseconds=Float.POSITIVE_INFINITY) }
    }
    @Test fun smallestInputRespectsBothBoundsAndOutputAspect() {
        val output=ReconstructionSize(1920,1080)
        val rec=ReconstructionRecommendation(ReconstructionSize(640,360),ReconstructionSize(300,180),output)
        assertEquals(ReconstructionSize(320,180),rec.smallestFor(output))
        rec.validate(ReconstructionSize(320,180),output,ReconstructionMode.ULTRA_PERFORMANCE)
        assertFailsWith<IllegalArgumentException> { rec.validate(ReconstructionSize(320,179),output,ReconstructionMode.ULTRA_PERFORMANCE) }
        assertFailsWith<IllegalArgumentException> { rec.validate(ReconstructionSize(1921,1080),output,ReconstructionMode.ULTRA_PERFORMANCE) }
        assertFailsWith<IllegalArgumentException> { rec.validate(ReconstructionSize(640,360),output,ReconstructionMode.DLAA) }
    }
}
