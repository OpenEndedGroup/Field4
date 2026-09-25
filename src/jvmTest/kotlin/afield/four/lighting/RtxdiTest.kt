package afield.four.lighting

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import org.junit.Assume
import java.io.File
import kotlin.test.*

class RtxdiTest {
    @Test fun reconstructionShaderLayout() {
        val source=javaClass.getResource("/afield/four/lighting/reconstruct.comp")!!.readText()
        val shader=afield.four.modules.Introspection(source,afield.four.modules.ShaderKind.COMPUTE_SHADER)
        assertTrue(shader.valid)
        assertEquals(listOf(8,8,1),shader.fixedWorkgroupSize)
        assertEquals(listOf(0,8,16,24,28,32,36,40,44,48),shader.pushConstants.single().members.map { it.offset })
    }
    @Test fun lightIdentityAndSnapshot() {
        val settings=RtxdiSettings({ Matrix4f() },{ Matrix4f() })
        val emitter=AreaEmitter(Vector3f(0f,4f,0f),Vector3f(1f,0f,0f),Vector3f(0f,0f,1f),Vector3f(5f))
        settings.commitLights(listOf(IdentifiedAreaLight(7,emitter)))
        emitter.radiance.set(0f)
        assertEquals(5f,settings.lights.single().emitter.radiance.x)
        assertFailsWith<IllegalArgumentException> { settings.commitLights(List(2) { IdentifiedAreaLight(7,emitter) }) }
        assertEquals(5f,settings.lights.single().emitter.radiance.x,"Failed edit must preserve old lights")
        settings.commitLights(emptyList());assertTrue(settings.lights.isEmpty())
        assertFailsWith<IllegalArgumentException> { settings.spatialSamples=33 }
        assertFailsWith<IllegalArgumentException> { settings.maxHistory=0 }
    }
    @Test fun optionalSdkLayout() {
        val file=File("build/lighting-experiment/rtxdi-shaders/current.json")
        Assume.assumeTrue("Optional pinned SDK has not been built",file.isFile)
        val program=RtxdiProgram.load(file)
        val reflection=program.reflection
        assertEquals(listOf("reservoirs","offsets"),reflection.ssbo.sortedBy { it.binding }.map { it.name })
        assertEquals(24,reflection.ssbo.single { it.name=="reservoirs" }.members.single().arrayStride)
        assertEquals(8,reflection.ssbo.single { it.name=="offsets" }.members.single().arrayStride)
        assertEquals(1,reflection.accelerationStructures.size)
        assertEquals(16*16*24*3L,RtxdiEngine.reservoirBytes(1,1))
        assertEquals(4*16*16*24*3L,RtxdiEngine.reservoirBytes(17,17))
    }
}
