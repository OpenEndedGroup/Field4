package afield.examples.four

import afield.four.Renderer
import afield.four.buffers.GpuStruct
import afield.four.buffers.gpuSchema
import afield.four.util.GpuProbe
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.gpu-schema.readback
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.GpuSchemaSmokeTest
tags: [smoke, four, gpu, compute, schema]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "GPU schema readback PASSED"
*/
/** Verifies nested CPU packing and array stride through real buffer-address GPU loads. */
object GpuSchemaSmokeTest {
    @GpuStruct
    data class Material(val color: Vector4f, val roughness: Float, val metallic: Float)

    @GpuStruct
    data class Particle(val position: Vector3f, val radius: Float, val material: Material, val flags: UInt)

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("GPU schema readback")
        val renderer = Renderer(window)
        try {
            GpuProbe(renderer, "gpu-schema", pump = { window.pollEvents(); renderer.render() }).use { probe ->
                val schema = gpuSchema<Particle>()
                val values = (0 until 3).map { i ->
                    val n = (i * 16).toFloat()
                    Particle(Vector3f(n + 1, n + 2, n + 3), n + 4,
                        Material(Vector4f(n + 5, n + 6, n + 7, n + 8), n + 9, n + 10), (i + 11).toUInt())
                }
                val input = probe.fixture("particles", values.size * schema.strideBytes / 4)
                input.mapAndWrite_bytes { schema.writeArray(values, this) }
                val output = probe.fixture("readback", values.size * 11)
                probe.stage("schema-loads", """
                    #version 460
                    ${schema.glslDeclarations()}
                    layout(buffer_reference, std430) buffer Result { float values[]; };
                    layout(push_constant) uniform Push { ParticleArray particles; Result result; } pc;
                    layout(local_size_x = 1) in;
                    void main() {
                        uint i = gl_GlobalInvocationID.x;
                        Particle p = pc.particles.elements[i];
                        uint o = i * 11u;
                        pc.result.values[o] = p.position.x;
                        pc.result.values[o + 1u] = p.position.y;
                        pc.result.values[o + 2u] = p.position.z;
                        pc.result.values[o + 3u] = p.radius;
                        pc.result.values[o + 4u] = p.material.color.x;
                        pc.result.values[o + 5u] = p.material.color.y;
                        pc.result.values[o + 6u] = p.material.color.z;
                        pc.result.values[o + 7u] = p.material.color.w;
                        pc.result.values[o + 8u] = p.material.roughness;
                        pc.result.values[o + 9u] = p.material.metallic;
                        pc.result.values[o + 10u] = float(p.flags);
                    }
                """.trimIndent()).dispatch(groups = values.size) {
                    bind("particles") { input }
                    bind("result") { output }
                }
                probe.settle(output)
                val actual = probe.read(output)
                for (i in values.indices) {
                    val expected = (1..10).map { (i * 16 + it).toFloat() } + (i + 11).toFloat()
                    expected.forEachIndexed { lane, value ->
                        check(actual[i * 11 + lane] == value) {
                            "Particle $i lane $lane: expected $value, got ${actual[i * 11 + lane]}"
                        }
                    }
                }
                check(probe.verdict() == 0)
                println("GPU schema readback PASSED: 3 nested structs, 33 values, stride=${schema.strideBytes}")
            }
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }
}
