package afield.four.lighting

import afield.four.device.RayDeviceSupport
import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import afield.four.modules.computeShaderAbiCheck
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import kotlin.test.*

class DirectLightingTest {
    @Test fun queryDoesNotRequirePipeline() {
        val queryOnly=RayDeviceSupport(true,true,true,false,true,true,true,false)
        assertTrue(queryOnly.queryUsable)
        assertFalse(queryOnly.pipelineUsable)
        assertFalse(queryOnly.copy(deferredHostOperations=false).queryUsable)
        assertFalse(queryOnly.copy(bufferDeviceAddress=false).queryUsable)
        assertFalse(queryOnly.copy(accelerationStructure=false).queryUsable)
        assertFalse(queryOnly.copy(rayQuery=false).queryUsable)
        assertFalse(queryOnly.copy(queryExtension=false).queryUsable)
    }
    @Test fun transformLayoutAndInvalidInput() {
        val m=Matrix4f().translation(2f,3f,4f).scale(2f,3f,4f)
        assertContentEquals(floatArrayOf(2f,0f,0f,2f,0f,3f,0f,3f,0f,0f,4f,4f),OpaqueRayScene.rowMajor3x4(m))
        assertFailsWith<IllegalArgumentException> { OpaqueRayScene.rowMajor3x4(Matrix4f().scale(0f)) }
        assertFailsWith<IllegalArgumentException> { OpaqueTriangles(floatArrayOf(0f,0f,0f),intArrayOf(0,1,2)) }
        assertFailsWith<IllegalArgumentException> { AreaEmitter(Vector3f(),Vector3f(1f),Vector3f(1f),Vector3f(1f)).validate() }
        assertFailsWith<IllegalArgumentException> { AreaEmitter(Vector3f(),Vector3f(1f,0f,0f),Vector3f(0f,1f,0f),Vector3f(Float.NaN)).validate() }
    }
    @Test fun geometrySnapshotsCannotBeMutatedBehindBlasCache() {
        val vertices=floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f)
        val indices=intArrayOf(0,1,2)
        val mesh=OpaqueTriangles(vertices,indices)
        vertices[0]=99f; indices[0]=2
        mesh.positions[0]=88f; mesh.indices[0]=1
        assertEquals(0f,mesh.positions[0])
        assertEquals(0,mesh.indices[0])
    }
    @Test fun shaderAndHostAbi() {
        for(hardware in listOf(false,true)) {
            val errors=mutableListOf<String>()
            val source=DirectLighting.shader(hardware)
            val i=Introspection(source,ShaderKind.COMPUTE_SHADER,errorHandler={ message,_ -> errors+=message })
            assertTrue(i.valid,errors.joinToString("\n"))
            assertEquals(if(hardware)1 else 0,i.accelerationStructures.size)
            assertEquals(listOf(8,8,1),i.fixedWorkgroupSize)
            val dispatch=Introspection(source.replace("local_size_x=8", "local_size_x=4"),ShaderKind.COMPUTE_SHADER)
            assertEquals(listOf(4,8,1),dispatch.fixedWorkgroupSize)
            val push=i.pushConstants.single()
            assertTrue(push.size<=128,"Push size=${push.size}")
            assertEquals(listOf(0,8,12,16,32,48,64,80,84,88,92,96,100),push.members.map { it.offset })
            val modified=Introspection(source.replace("float epsilon; float weight;","float epsilon; float weight; vec4 incompatible;"),ShaderKind.COMPUTE_SHADER)
            assertFalse(computeShaderAbiCheck(i,modified).compatible)
        }
    }
}
