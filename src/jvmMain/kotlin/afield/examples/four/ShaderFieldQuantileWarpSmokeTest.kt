package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.Parameters
import afield.four.modules.ShaderKind
import afield.four.modules.pointer
import afield.four.util.FIELD_PROBE_BUFFER_NAME
import afield.four.util.Window
import afield.stdlib.AdaptiveQuantileWarp
import afield.stdlib.QuantileWarpConfig
import afield.stdlib.QuantileWarpState
import afield.stdlib.ShaderProbeQuantileWarpConfig
import afield.stdlib.TargetDistributions
import afield.stdlib.UniformQuantileFeatureExtractor
import afield.stdlib.fieldProbeHere
import afield.stdlib.fieldProbeQuantileWarp
import afield.stdlib.preprocess
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class ShaderFieldQuantileWarpSmokeTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val window = Window("Shader Field Quantile Warp Smoke Test")
            val renderer = Renderer(window)

            val inputBox = Box("shader-field-quantile-warp-input").apply {
                name = "shader-field-quantile-warp-input"
            }
            val outputBox = Box("shader-field-quantile-warp-output").apply {
                name = "shader-field-quantile-warp-output"
            }

            val inputProbe = inputBox.fieldProbeHere(samples = 512)
            val outputProbe = outputBox.fieldProbeHere(samples = 512)
            val featureExtractor = UniformQuantileFeatureExtractor()
            val identityWarp = QuantileWarpState.identity01()
            val warp = AdaptiveQuantileWarp(
                config = QuantileWarpConfig(
                    historyFrames = 8,
                    smoothingAlpha = 0.18
                ),
                target = TargetDistributions.Uniform01
            ).reset(seed = identityWarp)

            val inputShader = """
                #version 450
                layout(local_size_x = 64) in;

                layout(push_constant) uniform Controls {
                    float time;
                } controls;

                {% include "fieldProbe" %}

                const float PI = 3.14159265358979323846;

                uint quantileWarpHash(uint value) {
                    value ^= value >> 16;
                    value *= 0x7feb352du;
                    value ^= value >> 15;
                    value *= 0x846ca68bu;
                    value ^= value >> 16;
                    return value;
                }

                float u01(uint seed) {
                    return float(quantileWarpHash(seed)) / 4294967295.0;
                }

                float gaussian(uint idx, float timeValue) {
                    uint frameSeed = uint(timeValue * 17.0);
                    float u0 = max(u01(idx * 747796405u + frameSeed + 1u), 1e-6);
                    float u1 = u01(idx * 2891336453u + frameSeed * 3u + 11u);
                    return sqrt(-2.0 * log(u0)) * cos(2.0 * PI * u1);
                }

                float rawSample(uint idx, float timeValue) {
                    float t = timeValue * 0.25;
                    float mean = 0.50 + 0.18 * sin(0.0033 * t) + 0.05 * sin(0.00071 * t + 1.7);
                    float sigma = 0.12 + 0.04 * (0.5 + 0.5 * sin(0.0021 * t + 0.9));
                    return clamp(mean + sigma * gaussian(idx, timeValue), 0.0, 1.0);
                }

                void main() {
                    uint idx = gl_GlobalInvocationID.x;
                    float x = rawSample(idx, controls.time);
                    fieldProbe(x);
                }
            """.trimIndent()

            val outputShader = """
                #version 450
                layout(local_size_x = 64) in;

                layout(push_constant) uniform Controls {
                    float time;
                    float warpStrength;
                    vec4 warpXLo;
                    vec4 warpXHi;
                    vec4 warpYLo;
                    vec4 warpYHi;
                } controls;

                {% include "fieldProbe" %}
                {% include "quantileWarp" %}

                const float PI = 3.14159265358979323846;

                uint quantileWarpHash(uint value) {
                    value ^= value >> 16;
                    value *= 0x7feb352du;
                    value ^= value >> 15;
                    value *= 0x846ca68bu;
                    value ^= value >> 16;
                    return value;
                }

                float u01(uint seed) {
                    return float(quantileWarpHash(seed)) / 4294967295.0;
                }

                float gaussian(uint idx, float timeValue) {
                    uint frameSeed = uint(timeValue * 17.0);
                    float u0 = max(u01(idx * 747796405u + frameSeed + 1u), 1e-6);
                    float u1 = u01(idx * 2891336453u + frameSeed * 3u + 11u);
                    return sqrt(-2.0 * log(u0)) * cos(2.0 * PI * u1);
                }

                float rawSample(uint idx, float timeValue) {
                    float t = timeValue * 0.25;
                    float mean = 0.50 + 0.18 * sin(0.0033 * t) + 0.05 * sin(0.00071 * t + 1.7);
                    float sigma = 0.12 + 0.04 * (0.5 + 0.5 * sin(0.0021 * t + 0.9));
                    return clamp(mean + sigma * gaussian(idx, timeValue), 0.0, 1.0);
                }

                void main() {
                    uint idx = gl_GlobalInvocationID.x;
                    float x = rawSample(idx, controls.time);
                    float y = fieldQuantileWarpMix(
                        x,
                        controls.warpStrength,
                        controls.warpXLo,
                        controls.warpXHi,
                        controls.warpYLo,
                        controls.warpYHi
                    );
                    fieldProbe(y);
                }
            """.trimIndent()

            fun moduleFor(box: Box, shader: String, probe: afield.four.util.ShaderFieldProbe, name: String): ComputeModulePointerBundle {
                val computeShader = preprocess(box, shader, ShaderKind.COMPUTE_SHADER)
                val module = ComputeModule(
                    Parameters(
                        renderer = renderer,
                        compute = computeShader,
                        name = name,
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
                    initializePushConstantsFromModule(module)
                }

                module += "dispatch" to dispatch
                renderer += name to module
                return ComputeModulePointerBundle(module, dispatch)
            }

            val input = moduleFor(
                box = inputBox,
                shader = inputShader,
                probe = inputProbe,
                name = "shaderFieldQuantileWarpInputSmoke"
            )
            val output = moduleFor(
                box = outputBox,
                shader = outputShader,
                probe = outputProbe,
                name = "shaderFieldQuantileWarpOutputSmoke"
            )

            input.dispatch.push.set("time", 0f)
            output.dispatch.push.set("time", 0f)
            output.dispatch.push.set("warpStrength", 1f)
            identityWarp.applyToPushConstants(setVec4 = { name, value ->
                output.dispatch.push.set(name, value)
            })

            val runtime = outputBox.fieldProbeQuantileWarp(
                probe = inputProbe,
                warp = warp,
                config = ShaderProbeQuantileWarpConfig(
                    minSamples = 256,
                    clipLower = 0.0,
                    clipUpper = 1.0,
                    displayChannel = null
                ),
                install = false
            ) { nextWarp, _ ->
                nextWarp.applyToPushConstants(setVec4 = { name, value ->
                    output.dispatch.push.set(name, value)
                })
            }

            val warmupFrames = 8
            val baselineFrames = 140
            val adaptiveFrames = 260
            val measurementTail = 100

            var activeWarp = identityWarp
            var baselineLoss = 0.0
            var baselineCount = 0
            var finalLoss = 0.0
            var finalCount = 0
            var finalDistributionAgreement = 0.0

            fun squaredFeatureLoss(samples: DoubleArray): Double {
                val error = featureExtractor.errorFromSamples(samples)
                var sum = 0.0
                for (value in error) sum += value * value
                return 0.5 * sum
            }

            fun quantile(sorted: DoubleArray, p: Double): Double {
                if (sorted.size == 1) return sorted[0]
                val index = p * (sorted.size - 1)
                val lo = floor(index).toInt()
                val hi = ceil(index).toInt()
                if (lo == hi) return sorted[lo]
                val t = index - lo
                return sorted[lo] * (1.0 - t) + sorted[hi] * t
            }

            fun meanQuantileAgreement(inputSamples: DoubleArray, outputSamples: DoubleArray, state: QuantileWarpState): Double {
                require(inputSamples.isNotEmpty()) { "Expected non-empty input samples" }
                require(outputSamples.isNotEmpty()) { "Expected non-empty output samples" }

                val cpuWarped = DoubleArray(inputSamples.size) { index ->
                    state.evaluate(inputSamples[index])
                }
                cpuWarped.sort()
                val gpuWarped = outputSamples.copyOf().also { it.sort() }
                val probeQuantiles = doubleArrayOf(0.05, 0.20, 0.50, 0.80, 0.95)

                var total = 0.0
                for (p in probeQuantiles) {
                    total += abs(quantile(cpuWarped, p) - quantile(gpuWarped, p))
                }
                return total / probeQuantiles.size.toDouble()
            }

            try {
                repeat(warmupFrames + baselineFrames + adaptiveFrames) { frame ->
                    input.dispatch.push.set("time", frame.toFloat())
                    output.dispatch.push.set("time", frame.toFloat())

                    window.pollEvents()
                    renderer.render()
                    if (window.shouldClose()) return@repeat

                    val inputBatch = inputProbe.readLatestScalarBatch(
                        minSamples = 256,
                        clipLower = 0.0,
                        clipUpper = 1.0
                    ) ?: return@repeat
                    val outputBatch = outputProbe.readLatestScalarBatch(
                        minSamples = 256,
                        clipLower = 0.0,
                        clipUpper = 1.0
                    ) ?: return@repeat

                    val loss = squaredFeatureLoss(outputBatch.batch.samples)
                    val distributionAgreement = meanQuantileAgreement(
                        inputSamples = inputBatch.batch.samples,
                        outputSamples = outputBatch.batch.samples,
                        state = activeWarp
                    )

                    when {
                        frame in warmupFrames until (warmupFrames + baselineFrames) -> {
                            baselineLoss += loss
                            baselineCount += 1
                        }

                        frame >= warmupFrames + baselineFrames + adaptiveFrames - measurementTail -> {
                            finalLoss += loss
                            finalDistributionAgreement += distributionAgreement
                            finalCount += 1
                        }
                    }

                    if (frame >= warmupFrames + baselineFrames) {
                        runtime.tick()?.let { nextWarp ->
                            activeWarp = nextWarp
                        }
                    }
                }

                require(baselineCount > 40) { "Insufficient baseline observations: $baselineCount" }
                require(finalCount > 40) { "Insufficient adaptive observations: $finalCount" }

                val baselineAverage = baselineLoss / baselineCount
                val finalAverage = finalLoss / finalCount
                val finalDistributionAgreementAverage = finalDistributionAgreement / finalCount
                val lastWarp = requireNotNull(runtime.lastWarp) {
                    "Quantile warp never produced an update"
                }

                println(
                    "ShaderFieldQuantileWarpSmokeTest metrics: baselineLoss=%.6f finalLoss=%.6f quantileAgreement=%.8f updates=%d"
                        .format(
                            baselineAverage,
                            finalAverage,
                            finalDistributionAgreementAverage,
                            runtime.tickCount
                        )
                )

                require(finalAverage < baselineAverage * 0.45) {
                    "Expected quantile warp to reduce feature loss. baseline=$baselineAverage final=$finalAverage"
                }
                require(finalDistributionAgreementAverage < 0.02) {
                    "Expected CPU/GPU quantile warp distributions to agree within 0.02, got $finalDistributionAgreementAverage"
                }
                require(runtime.tickCount > 40) {
                    "Expected repeated warp updates, got ${runtime.tickCount}"
                }

                println(
                    "ShaderFieldQuantileWarpSmokeTest ok: baselineLoss=%.6f finalLoss=%.6f quantileAgreement=%.8f updates=%d knots=%s"
                        .format(
                            baselineAverage,
                            finalAverage,
                            finalDistributionAgreementAverage,
                            runtime.tickCount,
                            lastWarp.inputKnots.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) }
                        )
                )
            } finally {
                runtime.stop()
                input.module.current.cleanup()
                output.module.current.cleanup()
                renderer.cleanup()
                window.cleanup()
            }
        }
    }
}

private data class ComputeModulePointerBundle(
    val module: afield.four.modules.ComputeModulePointer,
    val dispatch: ComputeMesh
)
