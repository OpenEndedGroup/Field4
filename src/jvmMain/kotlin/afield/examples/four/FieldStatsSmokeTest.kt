package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.Introspection
import afield.four.modules.Parameters
import afield.four.modules.ShaderKind
import afield.four.modules.pointer
import afield.four.util.FIELD_STATS_PARTIAL_BUFFER_NAME
import afield.four.util.FIELD_STATS_RESULT_BUFFER_NAME
import afield.four.util.Options
import afield.four.util.ShaderFieldStatsBinding
import afield.four.util.Window
import afield.stdlib.fieldStatsHere
import afield.stdlib.preprocess
import afield.stdlib.useFieldStats
import kotlin.math.abs

class FieldStatsSmokeTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Options.enableVR = false
            val window = Window("FieldStats Smoke Test")
            val renderer = Renderer(window)
            val box = Box("field-stats-smoke").apply { name = "field-stats-smoke" }
            val stats = box.fieldStatsHere(channels = 1, sketchSize = 64, maxPartials = 2048)

            val shader = preprocess(
                box,
                """
                    #version 450
                    layout(local_size_x = 64) in;
                    {% include "fieldStats" %}

                    void main() {
                        fieldStatsRecord(0u, float(gl_GlobalInvocationID.x));
                    }
                """.trimIndent(),
                ShaderKind.COMPUTE_SHADER
            )

            val module = ComputeModule(
                Parameters(
                    renderer = renderer,
                    compute = shader,
                    name = "FieldStats Smoke",
                    fieldStats = ShaderFieldStatsBinding(stats, producer = true),
                    ssboResolver = { requested ->
                        when (requested.name) {
                            FIELD_STATS_PARTIAL_BUFFER_NAME -> stats.partialBufferParameters(renderer)
                            FIELD_STATS_RESULT_BUFFER_NAME -> stats.resultBufferParameters(renderer)
                            else -> null
                        }
                    }
                )
            ).pointer()
            module += "dispatch" to ComputeMesh(renderer, module).apply { x = 16 }
            renderer += "fieldStatsSmoke" to module

            val consumerBox = Box("field-stats-consumer").apply { name = "field-stats-consumer" }
            consumerBox.useFieldStats(stats)
            val consumerShader = preprocess(
                consumerBox,
                """
                    #version 450
                    layout(local_size_x = 1) in;
                    {% include "fieldStats" %}

                    void main() {
                        float median = fieldStatsQuantile(0u, 0.5);
                        if (median < -1e30) {
                            fieldStatsResult.generation = 0u;
                        }
                    }
                """.trimIndent(),
                ShaderKind.COMPUTE_SHADER
            )
            require(Introspection(consumerShader, ShaderKind.COMPUTE_SHADER).valid) {
                "FieldStats consumer shader did not compile"
            }

            try {
                repeat(6) {
                    window.pollEvents()
                    renderer.render()
                }
                renderer.windowContext().computeFences.forEach { it.fenceWait() }

                val scalar = stats.scalar(0)
                val deadline = System.nanoTime() + 2_000_000_000L
                while (scalar.latest() == null && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                }
                val snapshot = requireNotNull(scalar.latest()) {
                    "FieldStats asynchronous snapshot was not published"
                }
                require(snapshot.calls == 1024L) { "Expected 1024 calls, got ${snapshot.calls}" }
                require(snapshot.finiteCount == 1024L) { "Expected 1024 finite values, got ${snapshot.finiteCount}" }
                require(abs(snapshot.min - 0.0) < 0.01) { "Expected min 0, got ${snapshot.min}" }
                require(abs(snapshot.max - 1023.0) < 0.01) { "Expected max 1023, got ${snapshot.max}" }
                require(abs(snapshot.mean - 511.5) < 0.1) { "Expected mean 511.5, got ${snapshot.mean}" }
                require(snapshot.quantile(0.5) in 400.0..624.0) {
                    "Expected approximate median near the center, got ${snapshot.quantile(0.5)}"
                }

                println(
                    "FieldStatsSmokeTest ok: generation=${snapshot.generation} count=${snapshot.finiteCount} " +
                        "mean=${snapshot.mean} q50=${snapshot.quantile(0.5)}"
                )
            } finally {
                module.current.cleanup()
                renderer.cleanup()
                window.cleanup()
            }
        }
    }
}
