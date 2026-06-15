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
import afield.stdlib.AdaptiveDistributionController
import afield.stdlib.ControllerConfig
import afield.stdlib.ShaderProbeAutoControlConfig
import afield.stdlib.UniformQuantileFeatureExtractor
import afield.stdlib.fieldProbeAutoControl
import afield.stdlib.fieldProbeHere
import afield.stdlib.preprocess
import kotlin.math.abs

class ShaderFieldAutoControlSmokeTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val window = Window("Shader Field Auto Control Smoke Test")
            val renderer = Renderer(window)
            val box = Box("shader-field-auto-control-smoke").apply {
                name = "shader-field-auto-control-smoke"
            }

            val probe = box.fieldProbeHere(samples = 512)
            val featureExtractor = UniformQuantileFeatureExtractor()
            val controller = AdaptiveDistributionController(
                config = ControllerConfig(
                    observationDelayFrames = renderer.numImages.coerceAtLeast(1),
                    gainBase = 0.70,
                    emaAlpha = 0.16,
                    maxStep = doubleArrayOf(0.0060, 0.0180, 0.0140, 0.0180, 0.0180),
                    ditherAmplitude = doubleArrayOf(0.0025, 0.0080, 0.0060, 0.0080, 0.0080)
                ),
                featureExtractor = featureExtractor
            )

            val rawShader = """
                #version 450
                layout(local_size_x = 64) in;

                layout(push_constant) uniform Controls {
                    float time;
                    float offset;
                    float logContrast;
                    float logGamma;
                    float cubic;
                    float skew;
                } controls;

                {% include "fieldProbe" %}

                const float PI = 3.14159265358979323846;

                uint autoHash(uint value) {
                    value ^= value >> 16;
                    value *= 0x7feb352du;
                    value ^= value >> 15;
                    value *= 0x846ca68bu;
                    value ^= value >> 16;
                    return value;
                }

                float u01(uint seed) {
                    return float(autoHash(seed)) / 4294967295.0;
                }

                float gaussian(uint idx) {
                    uint frameSeed = uint(controls.time * 17.0);
                    float u0 = max(u01(idx * 747796405u + frameSeed + 1u), 1e-6);
                    float u1 = u01(idx * 2891336453u + frameSeed * 3u + 11u);
                    return sqrt(-2.0 * log(u0)) * cos(2.0 * PI * u1);
                }

                float warp01(float x) {
                    float shifted = x + controls.offset;
                    float centered = shifted - 0.5;
                    float contrasted = 0.5 + exp(controls.logContrast) * centered;
                    float limited = clamp(contrasted, 0.0, 1.0);
                    float gamma = exp(controls.logGamma);
                    float curved = pow(limited, gamma);
                    float z = curved - 0.5;
                    float shaped = 0.5 + z + controls.cubic * z * z * z + controls.skew * z * abs(z);
                    return clamp(shaped, 0.0, 1.0);
                }

                void main() {
                    uint idx = gl_GlobalInvocationID.x;
                    float t = controls.time * 0.25;
                    float mean = 0.50 + 0.18 * sin(0.0033 * t) + 0.05 * sin(0.00071 * t + 1.7);
                    float sigma = 0.12 + 0.04 * (0.5 + 0.5 * sin(0.0021 * t + 0.9));
                    float x = clamp(mean + sigma * gaussian(idx), 0.0, 1.0);
                    float y = warp01(x);
                    fieldProbe(y);
                }
            """.trimIndent()

            val computeShader = preprocess(box, rawShader, ShaderKind.COMPUTE_SHADER)
            val module = ComputeModule(
                Parameters(
                    renderer = renderer,
                    compute = computeShader,
                    name = "ShaderFieldAutoControlSmoke",
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
                push.set("time", 0f)
                push.set("offset", 0f)
                push.set("logContrast", 0f)
                push.set("logGamma", 0f)
                push.set("cubic", 0f)
                push.set("skew", 0f)
            }

            module += "dispatch" to dispatch
            renderer += "shaderFieldAutoControlSmoke" to module

            val autoControl = box.fieldProbeAutoControl(
                probe = probe,
                controller = controller,
                config = ShaderProbeAutoControlConfig(
                    minSamples = 256,
                    clipLower = 0.0,
                    clipUpper = 1.0,
                    displayChannel = null
                ),
                install = false
            ) { params, _, _ ->
                dispatch.push.set("offset", params[0].toFloat())
                dispatch.push.set("logContrast", params[1].toFloat())
                dispatch.push.set("logGamma", params[2].toFloat())
                dispatch.push.set("cubic", params[3].toFloat())
                dispatch.push.set("skew", params[4].toFloat())
            }

            val warmupFrames = renderer.numImages * 2 + 6
            val baselineFrames = 160
            val adaptiveFrames = 520
            val measurementTail = 160

            var baselineLoss = 0.0
            var baselineCount = 0
            var finalLoss = 0.0
            var finalCount = 0

            fun squaredFeatureLoss(samples: DoubleArray): Double {
                val error = featureExtractor.errorFromSamples(samples)
                var sum = 0.0
                for (value in error) sum += value * value
                return 0.5 * sum
            }

            try {
                repeat(warmupFrames + baselineFrames + adaptiveFrames) { frame ->
                    if (frame >= warmupFrames + baselineFrames) {
                        autoControl.tick()
                    }

                    dispatch.push.set("time", frame.toFloat())

                    window.pollEvents()
                    renderer.render()
                    if (window.shouldClose()) return@repeat

                    val batch = probe.readLatestScalarBatch(
                        minSamples = 256,
                        clipLower = 0.0,
                        clipUpper = 1.0
                    ) ?: return@repeat

                    val loss = squaredFeatureLoss(batch.batch.samples)
                    when {
                        frame in warmupFrames until (warmupFrames + baselineFrames) -> {
                            baselineLoss += loss
                            baselineCount += 1
                        }

                        frame >= warmupFrames + baselineFrames + adaptiveFrames - measurementTail -> {
                            finalLoss += loss
                            finalCount += 1
                        }
                    }
                }

                require(baselineCount > 40) { "Insufficient baseline observations: $baselineCount" }
                require(finalCount > 40) { "Insufficient adaptive observations: $finalCount" }

                val baselineAverage = baselineLoss / baselineCount
                val finalAverage = finalLoss / finalCount
                val lastOutput = requireNotNull(autoControl.lastOutput) {
                    "Adaptive controller never produced an update"
                }
                val maxAbsParam = lastOutput.appliedParams.maxOf { abs(it) }

                require(finalAverage < baselineAverage * 0.75) {
                    "Expected adaptive control to reduce feature loss. baseline=$baselineAverage final=$finalAverage"
                }
                require(maxAbsParam > 0.01) {
                    "Expected controller to move parameters away from zero, got $maxAbsParam"
                }

                println(
                    "ShaderFieldAutoControlSmokeTest ok: baselineLoss=%.6f finalLoss=%.6f updates=%d params=%s"
                        .format(
                            baselineAverage,
                            finalAverage,
                            autoControl.tickCount,
                            lastOutput.appliedParams.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) }
                        )
                )
            } finally {
                autoControl.stop()
                module.current.cleanup()
                renderer.cleanup()
                window.cleanup()
            }
        }
    }
}
