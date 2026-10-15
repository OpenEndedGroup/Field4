package afield.examples.four

import afield.graphics.vulkan.buffers.GpuStruct
import afield.graphics.vulkan.buffers.gpuSchema
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f

/**
 * Smallest non-renderer example of the structured-memory ABI.
 *
 * In a live Field box, publish `PARTICLE_SCHEMA.template` as
 * `shaderTemplate["Particle"]`, then use `{{ Particle.all }}` in GLSL.
 */
@GpuStruct
data class SchemaExampleParticle(
    val position: Vector3f,
    val radius: Float,
    val color: Vector4f,
    val flags: UInt
)

val PARTICLE_SCHEMA = gpuSchema<SchemaExampleParticle>()

/** GLSL declaration text suitable for a quick compiler/debug inspection. */
fun gpuSchemaExampleGlsl(): String = PARTICLE_SCHEMA.glslDeclarations()
