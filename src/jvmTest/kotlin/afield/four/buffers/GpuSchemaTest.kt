package afield.four.buffers

import afield.four.modules.Introspection
import afield.four.modules.Introspected
import afield.four.modules.ShaderKind
import afield.four.modules.ShaderSPIRVUtils
import afield.four.util.Resolver
import afield.four.util.runShaderTemplate
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@GpuStruct
data class TestMaterial(
    val color: Vector4f,
    val roughness: Float,
    val metallic: Float
)

@GpuStruct
data class TestParticle(
    val position: Vector3f,
    val radius: Float,
    val material: TestMaterial,
    val flags: UInt
)

class GpuSchemaTest {
    @Test
    fun canonicalLessonLoadsWithRendererParentAndShaderSidecars() {
        val corpus = java.io.File("public/docs/assets/canonical_graphics")
        val boxes = afield.SavingContext().readEdnTransfers(corpus.absolutePath).associateBy { it.id }
        val uid = "b42d7b13-680e-4f9b-b789-8e6e7d8c4a21"
        val renderer = "611072da-1af6-4d2d-85bf-07a9ba191aca"
        val lesson = boxes.getValue(uid)
        assertEquals("5d. GPU schema", lesson.properties["name"])
        assertTrue(renderer in lesson.properties["parents"].toString())
        assertTrue(uid in boxes.getValue(renderer).properties["children"].toString())
        for ((property, extension) in listOf("code" to "kt", "vertex" to "glsl", "fragment" to "glsl")) {
            val sidecar = "${uid}_${property}_.$extension"
            assertTrue(sidecar in lesson.properties[property].toString())
            assertTrue(java.io.File(corpus, sidecar).readText().isNotBlank())
        }
    }

    @Test
    fun std430LayoutAndNestedPackingComeFromOneSchema() {
        val material = gpuSchema<TestMaterial>()
        val particle = gpuSchema<TestParticle>()

        assertEquals(0, material.offsetOf("color"))
        assertEquals(16, material.offsetOf("roughness"))
        assertEquals(20, material.offsetOf("metallic"))
        assertEquals(16, material.alignmentBytes)
        assertEquals(32, material.sizeBytes)

        // vec3 is 16-byte aligned but 12 bytes large in std430, so radius may occupy its fourth lane.
        assertEquals(0, particle.offsetOf(TestParticle::position))
        assertEquals(12, particle.offsetOf(TestParticle::radius))
        assertEquals(16, particle.offsetOf(TestParticle::material))
        assertEquals(48, particle.offsetOf(TestParticle::flags))
        assertEquals(64, particle.sizeBytes)
        assertEquals(64, particle.strideBytes)

        val p = TestParticle(
            position = Vector3f(1f, 2f, 3f),
            radius = 4f,
            material = TestMaterial(Vector4f(5f, 6f, 7f, 8f), 9f, 10f),
            flags = 0xdeadbeefu
        )
        val bytes = ByteBuffer.allocate(particle.sizeBytes).order(ByteOrder.LITTLE_ENDIAN)
        particle.write(p, bytes)

        assertEquals(1f, bytes.getFloat(0))
        assertEquals(2f, bytes.getFloat(4))
        assertEquals(3f, bytes.getFloat(8))
        assertEquals(4f, bytes.getFloat(12))
        assertEquals(5f, bytes.getFloat(16))
        assertEquals(8f, bytes.getFloat(28))
        assertEquals(9f, bytes.getFloat(32))
        assertEquals(10f, bytes.getFloat(36))
        assertEquals(0xdeadbeefu.toInt(), bytes.getInt(48))
    }

    @Test
    fun arrayViewUsesSchemaStrideAndAddressArithmetic() {
        val schema = gpuSchema<TestParticle>()
        val bytes = ByteBuffer.allocate(schema.strideBytes * 3).order(ByteOrder.LITTLE_ENDIAN)
        val array = schema.arrayView(bytes, 3)

        array[2] = TestParticle(Vector3f(11f, 12f, 13f), 14f, TestMaterial(Vector4f(), 0f, 0f), 7u)

        assertEquals(schema.strideBytes * 2, array.byteOffsetOf(2))
        assertEquals(11f, bytes.getFloat(schema.strideBytes * 2))
        assertEquals(0x1000L + schema.strideBytes * 2L, array.addressOf(0x1000L, 2))
        assertEquals(0x1000L + schema.strideBytes + schema.offsetOf("flags"), schema.addressOf(0x1000L, 1, "flags"))
    }

    @Test
    fun templatePublishesStructAndBufferReferenceViews() {
        val schema = gpuSchema<TestParticle>()
        val expanded = runShaderTemplate(
            "{{ Particle.all }}\n// stride={{ Particle.stride }} abi={{ Particle.abi }}",
            Resolver { key -> if (key == "Particle") schema.template else null }
        )

        assertTrue("struct TestMaterial" in expanded)
        assertTrue("struct TestParticle" in expanded)
        assertTrue("buffer TestParticleRef" in expanded)
        assertTrue("buffer TestParticleArray" in expanded)
        assertTrue("TestParticle elements[];" in expanded)
        assertTrue("stride=${schema.strideBytes}" in expanded)
        assertTrue("abi=${schema.abiHash}" in expanded)
    }

    @Test
    fun reflectedShaderOffsetsAndArrayStrideMatchCpuSchema() {
        val schema = gpuSchema<TestParticle>()
        val shader = Introspection(
            """
            #version 460
            ${schema.glslDeclarations()}
            layout(local_size_x = 1) in;
            layout(std430, set = 0, binding = 0) buffer Input { TestParticle values[]; } inputData;
            layout(std430, set = 0, binding = 1) buffer Output { float result; } outputData;
            void main() {
                TestParticle p = inputData.values[1];
                outputData.result = p.position.x + p.radius + p.material.color.x
                    + p.material.roughness + p.material.metallic + float(p.flags);
            }
            """.trimIndent(), ShaderKind.COMPUTE_SHADER
        )
        try {
            assertTrue(shader.valid)
            val array = shader.ssbo.single { it.name == "Input" }.members.single()
            assertEquals(schema.strideBytes, array.arrayStride)
            fun compare(expected: GpuSchema<*>, actual: List<Introspected.Member>) {
                assertEquals(expected.fields.map { it.name }, actual.map { it.name })
                expected.fields.zip(actual).forEach { (field, member) ->
                    assertEquals(field.offsetBytes, member.offset, "${expected.glslName}.${field.name}")
                    val nested = field.type as? GpuFieldType.Struct
                    if (nested != null) compare(nested.schema, member.type.fold(
                        { error("Expected nested struct for ${field.name}") }, { it }
                    ))
                }
            }
            compare(schema, array.type.fold({ error("Expected struct array") }, { it }))
        } finally {
            shader.code.free()
        }
    }

    @Test
    fun generatedBufferReferenceGlslCompiles() {
        val schema = gpuSchema<TestParticle>()
        val source = """
            #version 460
            ${schema.glslDeclarations()}

            layout(local_size_x = 1) in;
            layout(push_constant) uniform Push {
                TestParticleArray particles;
                uint count;
            } pc;

            layout(set = 0, binding = 0, std430) buffer Result {
                vec4 value;
            } result;

            void main() {
                if (pc.count == 0u) return;
                TestParticle p = pc.particles.elements[0];
                result.value = vec4(p.position * p.radius, p.material.roughness + float(p.flags & 1u));
            }
        """.trimIndent()

        val spirv = ShaderSPIRVUtils.compileShader("gpu-schema-test.comp", source, ShaderKind.COMPUTE_SHADER)
        try {
            assertTrue(spirv.bytecode()?.remaining() ?: 0 > 0)
        } finally {
            spirv.free()
        }
    }
}
