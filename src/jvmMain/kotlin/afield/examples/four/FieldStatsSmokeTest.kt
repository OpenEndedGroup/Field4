package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import afield.four.util.FIELD_STATS_PARTIAL_BUFFER_NAME
import afield.four.util.FIELD_STATS_RESULT_BUFFER_NAME
import afield.four.util.Options
import afield.four.util.ShaderFieldStatsBinding
import afield.four.util.GpuProbe
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
            val bench = GpuProbe(renderer, "FieldStatsSmokeTest", pump = {
                window.pollEvents(); renderer.render()
            })
            try {
                val box = Box("field-stats-smoke").apply { name = "field-stats-smoke" }
                val stats = box.fieldStatsHere(
                    channels = 2,
                    sketchSize = 64,
                    maxPartials = 2048,
                    stateSlots = 2
                )

                val shader = preprocess(
                    box,
                    """
                        #version 450
                        layout(local_size_x = 64) in;
                        {% include "fieldStats" %}

                        void main() {
                            float smoothedMedian =
                                fieldStatsSmoothedQuantile(0u, 0u, 0.5, 2.0);
                            float smoothedMean =
                                fieldStatsSmoothedMean(1u, 1u, 2.0);
                            fieldStatsRecord(0u, float(gl_GlobalInvocationID.x));
                            fieldStatsRecord(1u, float(fieldStatsResult.generation) * 100.0);
                            if (smoothedMedian < -1e30 || smoothedMean < -1e30) {
                                fieldStatsResult.generation = 0u;
                            }
                        }
                    """.trimIndent(),
                    ShaderKind.COMPUTE_SHADER
                )

                val stage = bench.stage("producer", shader) {
                    fieldStats = ShaderFieldStatsBinding(stats, producer = true)
                    ssboResolver = { requested ->
                        when (requested.name) {
                            FIELD_STATS_PARTIAL_BUFFER_NAME -> stats.partialBufferParameters(renderer)
                            FIELD_STATS_RESULT_BUFFER_NAME -> stats.resultBufferParameters(renderer)
                            else -> null
                        }
                    }
                }.dispatch(groups = 16) {}

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
                            float smoothedMedian = fieldStatsState(0u);
                            if (median < -1e30 || smoothedMedian < -1e30) {
                                fieldStatsResult.generation = 0u;
                            }
                        }
                    """.trimIndent(),
                    ShaderKind.COMPUTE_SHADER
                )
                require(Introspection(consumerShader, ShaderKind.COMPUTE_SHADER).valid) {
                    "FieldStats consumer shader did not compile"
                }

                run {
                    // Six producer generations are the fixture: channel 1 records generation * 100.
                    // This is a sampled sequence, not an arbitrary wait for a shader to become ready.
                    require(stage.valid) { "FieldStats producer shader did not compile" }
                    bench.pumpFrames(6)
                    bench.idle()
                    check(bench.verdict() == 0) { "FieldStats producer shader failed" }

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
                    val state = requireNotNull(stats.state(0).latest()) {
                        "FieldStats asynchronous state snapshot was not published"
                    }
                    require(state.initialized) { "Expected smoothing state to be initialized" }
                    require(state.mode == 2) { "Expected quantile smoothing mode, got ${state.mode}" }
                    require(state.channel == 0) { "Expected smoothing channel 0, got ${state.channel}" }
                    require(state.value in 400.0..624.0) {
                        "Expected smoothed median near the center, got ${state.value}"
                    }
                    val changingSnapshot = requireNotNull(stats.scalar(1).latest()) {
                        "FieldStats changing-channel snapshot was not published"
                    }
                    val changingState = requireNotNull(stats.state(1).latest()) {
                        "FieldStats mean smoothing state was not published"
                    }
                    require(abs(changingSnapshot.mean - 500.0) < 0.1) {
                        "Expected current changing mean 500, got ${changingSnapshot.mean}"
                    }
                    require(changingState.value in 250.0..400.0) {
                        "Expected half-life smoothing to lag current mean, got ${changingState.value}"
                    }

                    println(
                        "FieldStatsSmokeTest ok: generation=${snapshot.generation} count=${snapshot.finiteCount} " +
                            "mean=${snapshot.mean} q50=${snapshot.quantile(0.5)} smoothedQ50=${state.value} " +
                            "changingMean=${changingSnapshot.mean} smoothedMean=${changingState.value}"
                    )
                }
            } finally {
                bench.close()
                renderer.cleanup()
                window.cleanup()
            }
        }
    }
}
