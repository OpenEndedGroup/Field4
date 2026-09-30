package afield.four.examples

import afield.four.buffers.GpuStruct
import afield.four.buffers.gpuSchema
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

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
