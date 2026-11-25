@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.graphics.render.ComputeMesh
import afield.graphics.render.ComputeModule
import afield.graphics.render.ComputeTask
import afield.graphics.render.Parameters
import afield.graphics.render.computeMemoryBarrier
import afield.graphics.render.computeTask
import afield.graphics.render.pointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.math.linalg.Vec3
import java.util.UUID

/**
 * Evaluates the same spectral sum for phase maps, spatial waves and cached mesh eigenfunctions.
 * Owns one dispatch and its buffers on [renderer]. Keep it alive while a draw module reads
 * [output], and close it when its box stops. Changed coefficients upload without rebuilding the
 * basis. The output is group-major vec4s, with one row per sample within each four-channel group.
 */
class SpectralGpuEvaluator private constructor(
    private val renderer: Renderer,
    private val template: Spectrum,
    val sampleCount: Int,
    private val dimensions: Int,
    basisCount: Int,
    writeBasis: java.nio.FloatBuffer.() -> Unit,
    queries: FloatArray,
    offset: DoubleArray,
) : AutoCloseable {
    val channels: Int get() = template.channels
    val channelGroups = (channels - 1) / 4 + 1
    private val outputRows = Math.multiplyExact(sampleCount, channelGroups)
    private val workgroups = (outputRows - 1) / 64 + 1
    private val coefficientRows = Math.addExact(1, Math.multiplyExact(2, template.modeCount))
    private val key = "spectral." + UUID.randomUUID()
    private val basisBuffer = allocate("basis", basisCount.coerceAtLeast(1))
    private val queryBuffer = allocate("queries", queries.size.coerceAtLeast(1))
    private val coefficients = allocate("coefficients", Math.multiplyExact(4 * channelGroups, coefficientRows), perFrame = true)
    val output = allocate("output", Math.multiplyExact(4, outputRows), readback = true)
    private val uploaded = arrayOfNulls<FloatArray>(renderer.numImages)
    private val initialOffset = offset.copyOf()
    @Volatile private var nextCoefficients = pack(template, initialOffset)
    private val module = ComputeModule(Parameters(
        renderer = renderer,
        name = key,
        compute = source,
        computeSourceName = "spectral/evaluate.comp",
    )).pointer()

    init {
        try {
            check(module.current.valid) { "The spectral compute shader did not compile" }
            if (basisCount > 0) basisBuffer.mapAndWrite(writeBasis)
            if (queries.isNotEmpty()) queryBuffer.mapAndWrite { put(queries) }
            val invocation = ComputeMesh(renderer, module).apply {
                initializePushConstantsFromModule(module)
                x = minOf(workgroups, 65535)
                y = (workgroups - 1) / x + 1
                z = 1
                push.bind("basis") { basisBuffer }
                push.bind("queries") { queryBuffer }
                push.bind("coefficients") { uploadForFrame() }
                push.bind("results") { output.get(write = true) }
                push.bind("sampleCount") { sampleCount }
                push.bind("modeCount") { template.modeCount }
                push.bind("dimensions") { dimensions }
                push.bind("channelGroups") { channelGroups }
            }
            module += "sum" to (invocation as ComputeTask)
            module += "publish" to computeTask { _, cmd, _ -> computeMemoryBarrier(cmd, null as VulkanBuffer?) }
            renderer += key to module
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    /** Changes the coefficient rows and DC value. All modes must still refer to the original basis. */
    fun update(spectrum: Spectrum, offset: DoubleArray = initialOffset) {
        require(spectrum.modes == template.modes && spectrum.channels == channels && spectrum.domain == template.domain)
        nextCoefficients = pack(spectrum, offset)
    }

    /** Wait for submitted compute work before calling. Returns sample-major, unpadded channels. */
    fun read(): FloatArray {
        val result = FloatArray(Math.multiplyExact(sampleCount, channels))
        output.mapAndRead {
            for (sample in 0 until sampleCount) for (channel in 0 until channels) {
                val row = (channel / 4) * sampleCount + sample
                result[sample * channels + channel] = get(4 * row + channel % 4)
            }
        }
        return result
    }

    private fun uploadForFrame(): VulkanBuffer {
        val image = renderer.currentImage
        val next = nextCoefficients
        val buffer = coefficients.getAt(image)
        if (uploaded[image % uploaded.size] !== next) {
            buffer.mapAndWrite { put(next) }
            uploaded[image % uploaded.size] = next
        }
        return buffer
    }

    private fun pack(spectrum: Spectrum, offset: DoubleArray): FloatArray {
        require(offset.size == channels)
        val packed = FloatArray(Math.multiplyExact(4 * channelGroups, coefficientRows))
        for (channel in 0 until channels) {
            val base = 4 * coefficientRows * (channel / 4) + channel % 4
            packed[base] = offset[channel].toFloat()
            for (mode in 0 until template.modeCount) {
                packed[base + 4 + 8 * mode] = spectrum.primary(mode, channel).toFloat()
                packed[base + 8 + 8 * mode] = spectrum.quadrature(mode, channel).toFloat()
            }
        }
        require(packed.all { it.isFinite() }) { "Spectral GPU coefficients must fit FP32" }
        return packed
    }

    private fun allocate(label: String, floats: Int, perFrame: Boolean = false, readback: Boolean = false): FrameBufferGroup =
        defaultBuffer(name = "$key.$label") {
            size = floats.floats
            if (readback) streamReads()
            this.perFrame = perFrame || readback
        }.toBuffer(renderer)

    override fun close() {
        renderer.computeList.modules.remove(key)
        module.current.cleanup()
        for (group in listOf(basisBuffer, queryBuffer, coefficients, output)) {
            group.buffers.forEach { it.cleanup() }
        }
    }

    companion object {
        private val source by lazy {
            checkNotNull(SpectralGpuEvaluator::class.java.getResource("/spectral/evaluate.comp")).readText()
        }

        fun phase(renderer: Renderer, geometry: EpicycleGeometry, phases: List<DoubleArray>): SpectralGpuEvaluator {
            val dimension = geometry.dimension
            require(phases.isNotEmpty() && phases.all { it.size == dimension && it.all(Double::isFinite) })
            val queries = FloatArray(Math.multiplyExact(phases.size, dimension)) {
                (phases[it / dimension][it % dimension] % TAU).toFloat()
            }
            val center = geometry.center
            return SpectralGpuEvaluator(renderer, geometry.spectrum, phases.size, dimension,
                Math.multiplyExact(geometry.harmonics.size, dimension),
                { for (harmonic in geometry.harmonics) for (mode in harmonic.lattice) put(mode.toFloat()) },
                queries, doubleArrayOf(center.x.toDouble(), center.y.toDouble(), center.z.toDouble()))
        }

        fun spatial(renderer: Renderer, field: PlaneWaveField, points: List<Vec3>): SpectralGpuEvaluator =
            spatial(renderer, field, points, field.spectrum, doubleArrayOf(field.offset))

        /** The four output lanes are gradient xyz and field value w. */
        fun spatialValueGradient(renderer: Renderer, field: PlaneWaveField, points: List<Vec3>): SpectralGpuEvaluator =
            spatial(renderer, field, points, field.valueGradientSpectrum(), doubleArrayOf(0.0, 0.0, 0.0, field.offset))

        private fun spatial(renderer: Renderer, field: PlaneWaveField, points: List<Vec3>,
                            spectrum: Spectrum, offset: DoubleArray): SpectralGpuEvaluator {
            require(points.isNotEmpty() && points.all { it.isFinite() })
            val queries = FloatArray(Math.multiplyExact(points.size, 3)) { points[it / 3][it % 3] }
            return SpectralGpuEvaluator(renderer, spectrum, points.size, 3,
                Math.multiplyExact(field.waves.size, 3),
                { for (wave in field.waves) put(wave.kx.toFloat()).put(wave.ky.toFloat()).put(wave.kz.toFloat()) },
                queries, offset)
        }

        fun manifold(renderer: Renderer, basis: ManifoldBasis, spectrum: Spectrum): SpectralGpuEvaluator {
            basis.checkSpectrum(spectrum)
            return SpectralGpuEvaluator(renderer, spectrum, basis.vertexCount, 0,
                Math.multiplyExact(basis.vertexCount, basis.modeCount),
                { for (mode in 0 until basis.modeCount) for (vertex in 0 until basis.vertexCount) {
                    put(basis.basisValue(mode, vertex).toFloat())
                } },
                FloatArray(0), DoubleArray(spectrum.channels))
        }
    }
}
