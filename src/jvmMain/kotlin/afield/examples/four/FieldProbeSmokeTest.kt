package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.Parameters
import afield.four.modules.ShaderKind
import afield.four.modules.pointer
import afield.four.util.FIELD_PROBE_BUFFER_NAME
import afield.four.util.ShaderFieldProbe
import afield.four.util.Window
import afield.stdlib._fieldProbe
import afield.stdlib.preprocess

class FieldProbeSmokeTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val window = Window("FieldProbe Smoke Test")
            val renderer = Renderer(window)
            val box = Box("field-probe-smoke").apply {
                name = "field-probe-smoke"
            }
            val probe = ShaderFieldProbe(box).configure(samples = 1024 * 256)
            check(probe.sampleCapacity == 1024 * 256) {
                "Field probe sample capacity was clamped to ${probe.sampleCapacity}"
            }
            box._fieldProbe = probe

            var latest = probe.readLatest()
            probe.onSnapshot { latest = it }

            val rawShader = """
                #version 450
                layout(local_size_x = 64) in;
                {% include "fieldProbe" %}
                void main() {
                    uint idx = gl_GlobalInvocationID.x;
                    float value = sin(float(idx) * 0.1) * 100.0;
                    if (idx == 7u) value = 0.0;
                    fieldProbe(value);
                }
            """.trimIndent()

            val computeShader = preprocess(box, rawShader, ShaderKind.COMPUTE_SHADER)
            val module = ComputeModule(
                Parameters(
                    renderer = renderer,
                    compute = computeShader,
                    name = "FieldProbeSmoke",
                    fieldProbe = probe,
                    ssboResolver = { requested ->
                        if (requested.name == FIELD_PROBE_BUFFER_NAME) probe.bufferParameters(renderer) else null
                    }
                )
            ).pointer()

            val dispatch = ComputeMesh(renderer, module).apply {
                x = 16
                y = 1
                z = 1
            }
            module += "dispatch" to dispatch
            renderer += "fieldProbeSmoke" to module
            probe.configure(samples = 32)

            try {
                repeat(6) {
                    window.pollEvents()
                    renderer.render()
                    if (window.shouldClose()) return@repeat
                }

                val snapshot = requireNotNull(latest) { "Field probe did not produce a snapshot" }
                require(snapshot.dimensions == 1) { "Expected scalar probe, got dims=${snapshot.dimensions}" }
                require(snapshot.finiteCount > 0) { "Expected finite probe samples, got ${snapshot.finiteCount}" }
                require(snapshot.sampleCount > 0) { "Expected reservoir samples, got ${snapshot.sampleCount}" }
                require(snapshot.sampleCount <= 32) { "Expected reconfigured reservoir size, got ${snapshot.sampleCount}" }
                require(!snapshot.anyNaN) { "Expected no NaN samples" }

                println(
                    "FieldProbeSmokeTest ok: finite=${snapshot.finiteCount} sampled=${snapshot.sampleCount} " +
                        "min=${snapshot.exactMin.firstOrNull()} p95=${snapshot.percentile(0.95)} max=${snapshot.exactMax.firstOrNull()}"
                )
            } finally {
                module.current.cleanup()
                renderer.cleanup()
                window.cleanup()
            }
        }
    }
}
