package afield.graphics.scene.video

import afield.graphics.render.*
import afield.graphics.shader.fromViews
import afield.graphics.texture.HasImageView
import afield.graphics.texture.LoadedTexture
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.*
import afield.graphics.vulkan.device.CommandBuffer
import afield.math.linalg.Rectanglef
import afield.math.linalg.Vector4f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkMemoryBarrier2
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable

/** GPU accumulation statistics, asymmetric alpha-beta tracking, and bounded per-screen or pooled exposure. */
class VideoAutoExposure(
    private val renderer: Renderer,
    val screens: Int,
    private val now: () -> Long = System::nanoTime,
) : AutoCloseable {
    init { require(screens in 1..16) }

    @Serializable
    enum class Mode { OFF, PER_SCREEN, LINKED }

    /** Gains apply once per metering sample; velocity is in linear-light units per second. */
    @Serializable
    data class Settings(
        val enabled: Boolean = false,
        val mode: Mode = Mode.OFF,
        val mix: Float = 1f,
        val meterHz: Double = 12.0,
        val risingAlpha: Float = .25f,
        val risingBeta: Float = .05f,
        val fallingAlpha: Float = .1f,
        val fallingBeta: Float = .01f,
        val maximumVelocity: Float = 0f,
        val blackPadding: Float = .01f,
        val whitePadding: Float = .02f,
        val blackMinimum: Float = 0f,
        val blackMaximum: Float = .25f,
        val whiteMinimum: Float = .25f,
        val whiteMaximum: Float = 4f,
        val minimumSpan: Float = .05f,
    ) {
        init {
            require(mix.isFinite() && mix in 0f..1f)
            require(meterHz.isFinite() && meterHz > 0.0 && meterHz <= 240.0)
            require(listOf(risingAlpha, risingBeta, fallingAlpha, fallingBeta).all { it.isFinite() && it in 0f..1f })
            require(listOf(maximumVelocity, blackPadding, whitePadding, blackMinimum, blackMaximum,
                whiteMinimum, whiteMaximum, minimumSpan).all { it.isFinite() })
            require(maximumVelocity >= 0f && minimumSpan > 0f)
            require(blackMinimum <= blackMaximum) { "Black minimum cannot exceed black maximum" }
            require(whiteMinimum <= whiteMaximum) { "White minimum cannot exceed white maximum" }
            require(blackMinimum <= whiteMaximum - minimumSpan) { "Black minimum must leave the minimum span below white maximum" }
        }
    }

    /** RGB plus linear luminance in component 3. Filter levels and velocities are independent per statistic. */
    data class Statistics(
        val rawMinimum: List<Float>, val rawMean: List<Float>, val rawMaximum: List<Float>,
        val minimum: List<Float>, val mean: List<Float>, val maximum: List<Float>,
        val minimumVelocity: List<Float>, val meanVelocity: List<Float>, val maximumVelocity: List<Float>,
        val finiteCount: List<Float>, val valid: Boolean, val predictionSeconds: Float,
        val black: Float, val white: Float,
    )

    /** The last entry pools all valid buffers with a pixel-count-weighted mean; UI-only delayed readback. */
    data class Snapshot(val screens: List<Statistics>, val linked: Statistics, val serial: Long)

    @Volatile
    var settings = Settings()
    @Volatile
    var latest: Snapshot? = null
        private set
    @Volatile
    var error: String? = null
        private set
    @Volatile
    var measurements = 0L
        private set
    @Volatile
    var tileDispatches = 0L
        private set
    @Volatile
    private var closed = false
    internal var frameSettings = settings
        private set

    private val recordBytes = 12L * 16
    private val resultBytes = (screens + 1) * recordBytes
    internal val statisticsParameters = buffer(name = "video-exposure-statistics") {
        size = resultBytes; storage(); source(); zeroInit(); perFrame = false
    }
    private val statistics by lazy { statisticsParameters.toBuffer(renderer) }
    private val resetRequest = AtomicBoolean(true)
    private val readbackPending = AtomicBoolean(false)
    private var readbackParameters: BufferParameters? = null
    private var readback: VulkanBuffer? = null
    private var partialParameters: BufferParameters? = null
    private var partials: FrameBufferGroup? = null
    private var partialBytes = 0L
    private var dimensions = Pair(0, 0)
    private var tileCount = 0
    private var tileX = 0
    private var tileY = 0
    private var selected: HasImageView? = null
    private var sampler: TextureSampler? = null
    private var tileModule: ComputeModulePointer? = null
    private var finishModule: ComputeModulePointer? = null
    private var linkedModule: ComputeModulePointer? = null
    private var tileMesh: ComputeMesh? = null
    private var finishMesh: ComputeMesh? = null
    private var linkedMesh: ComputeMesh? = null
    private val revisions = LongArray(screens) { Long.MIN_VALUE }
    private var lastMeasurement = 0L
    private var lastReadback = 0L
    private var serial = 0L

    /** Reinitialize tracker levels and velocities from the next valid measurement. */
    fun reset() { resetRequest.set(true) }

    internal fun beginFrame(command: CommandBuffer) {
        frameSettings = settings
        statistics.clean(false, command) // initialize even the manual-only shader's inactive SSBO binding
    }

    internal fun appearance(screen: Int, mix: Float): Vector4f = Vector4f(
        if (frameSettings.mode == Mode.OFF) 0f else frameSettings.mix * mix,
        (if (frameSettings.mode == Mode.LINKED) screens else screen).toFloat(), 0f, 0f,
    )

    internal data class Source(val image: HasImageView, val width: Int, val height: Int, val revision: Long)

    private fun ensureStorage(width: Int, height: Int) {
        if (dimensions == Pair(width, height)) return
        tileX = (width + 31) / 32; tileY = (height + 31) / 32
        tileCount = Math.multiplyExact(tileX, tileY)
        val bytes = tileCount.toLong() * screens * 64
        if (partials == null) {
            partialParameters = buffer(name = "video-meter-tiles") { size = bytes; storage(); zeroInit(); perFrame = false }
            partials = partialParameters!!.toBuffer(renderer)
            partialBytes = bytes
        } else if (bytes > partialBytes) {
            partialParameters!!.size = bytes
            partials!!.buffers.forEach { it.realloc(bytes) }
            partialBytes = bytes
        }
        dimensions = Pair(width, height)
        revisions.fill(Long.MIN_VALUE)
        resetRequest.set(true)
    }

    private fun ensureModules(): Boolean {
        if (tileModule == null) {
            val texture = object : HasImageView {
                override val imageView get() = selected?.imageView ?: LoadedTexture.stubTexture(renderer).imageView
            }
            val sampling = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false).also { sampler = it }
            fun module(stage: String): ComputeModulePointer = ComputeModule(Parameters(renderer,
                name = "video-meter-$stage", computeSourceName = "field/stereovideo/meter_$stage.glsl", compute = resource("meter_$stage").replace("// METER_COMMON", resource("meter_common")),
                ssboResolver = { request -> when (request.name) {
                    "VideoMeterTiles" -> partialParameters
                    "VideoExposureStatistics" -> statisticsParameters
                    else -> null
                } },
                textureResolver = { request -> if (request.name == "accumulationImage") fromViews(listOf(texture), request.name, sampling) else null },
                errorHandler = { message, _ -> if (error == null) error = "$stage: $message" },
            )).pointer()
            tileModule = module("tiles"); finishModule = module("finish"); linkedModule = module("linked")
        }
        if (listOf(tileModule, finishModule, linkedModule).any { it?.current?.valid != true }) return false
        if (tileMesh == null) {
            tileMesh = ComputeMesh(renderer, tileModule!!).also { tileModule!! += "tiles" to it }
            finishMesh = ComputeMesh(renderer, finishModule!!).also { finishModule!! += "finish" to it }
            linkedMesh = ComputeMesh(renderer, linkedModule!!).also { linkedModule!! += "linked" to it }
        }
        return true
    }

    /** Record after all accumulators, before the scene. Cached images avoid repeated scans while paused. */
    internal fun record(command: CommandBuffer, sources: List<Source?>) {
        if (closed || !frameSettings.enabled) return
        require(sources.size == screens)
        val first = sources.firstOrNull { it != null }
        if (first != null) ensureStorage(first.width, first.height)
        if (partials == null) return
        require(sources.filterNotNull().all { Pair(it.width, it.height) == dimensions }) { "Accumulation meter requires matching source dimensions" }
        selected = first?.image
        if (!ensureModules()) return
        val time = now()
        val s = frameSettings
        val interval = (1e9 / s.meterHz).toLong().coerceAtLeast(1)
        val reset = resetRequest.getAndSet(false)
        val measurement = reset || lastMeasurement == 0L || time - lastMeasurement >= interval ||
            sources.indices.any { sources[it] != null && revisions[it] == Long.MIN_VALUE }
        val dt = if (lastMeasurement == 0L) 0f else ((time - lastMeasurement).coerceAtLeast(0L) / 1e9).toFloat()
        val prediction = if (measurement) 0f else dt.coerceAtMost((1 / s.meterHz).toFloat())
        var activeMask = 0
        var changedMask = 0
        barrier(command)
        partials!!.clean(false, command)
        sources.forEachIndexed { i, source ->
            if (source != null) {
                activeMask = activeMask or (1 shl i)
                if (measurement && revisions[i] != source.revision) {
                    selected = source.image
                    tileMesh!!.apply {
                        x = tileX; y = tileY; z = 1
                        push["geometry"] = Vector4f(source.width.toFloat(), source.height.toFloat(), tileX.toFloat(), (i * tileCount * 4).toFloat())
                    }
                    tileModule!!.render(command, Rectanglef())
                    revisions[i] = source.revision
                    changedMask = changedMask or (1 shl i)
                    tileDispatches++
                }
            } else revisions[i] = Long.MIN_VALUE
        }
        barrier(command)
        fun configure(mesh: ComputeMesh) {
            mesh.push["dimensions"] = Vector4f(tileCount.toFloat(), screens.toFloat(), activeMask.toFloat(), changedMask.toFloat())
            mesh.push["times"] = Vector4f(dt, prediction, if (measurement) 1f else 0f, if (reset) 1f else 0f)
            mesh.push["gains"] = Vector4f(s.risingAlpha, s.risingBeta, s.fallingAlpha, s.fallingBeta)
            mesh.push["margins"] = Vector4f(s.blackPadding, s.whitePadding, s.minimumSpan, 0f)
            mesh.push["bounds"] = Vector4f(s.blackMinimum, s.blackMaximum, s.whiteMinimum, s.whiteMaximum)
            mesh.push["limits"] = Vector4f(s.maximumVelocity, 0f, 0f, 0f)
        }
        finishMesh!!.apply { x = screens; y = 1; z = 1; configure(this) }
        finishModule!!.render(command, Rectanglef())
        barrier(command)
        linkedMesh!!.apply { x = 1; y = 1; z = 1; configure(this) }
        linkedModule!!.render(command, Rectanglef())
        barrier(command)
        if (measurement) { lastMeasurement = time; measurements++ }
        serial++
        requestReadback(command, time)
    }

    private fun requestReadback(command: CommandBuffer, time: Long) {
        if (time - lastReadback < 250_000_000L || !readbackPending.compareAndSet(false, true)) return
        lastReadback = time
        val destination = readback ?: buffer(name = "video-meter-ui-snapshot") {
            size = resultBytes; dest(); streamReads(); perFrame = false
        }.also { readbackParameters = it }.toBuffer(renderer).buffers[0].also { readback = it }
        MemoryStack.stackPush().use { stack ->
            val copy = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(resultBytes)
            vkCmdCopyBuffer(command.vkCommandBuffer, statistics.buffers[0].buffer, destination.buffer, copy)
            val host = VkMemoryBarrier2.calloc(1, stack).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
            vkCmdPipelineBarrier2(command.vkCommandBuffer,
                VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO).pMemoryBarriers(host))
        }
        barrier(command)
        val capturedSerial = serial
        renderer.whenCurrentGraphicsFrameComplete {
            try {
                if (!closed) {
                    val data = FloatArray(resultBytes.toInt() / 4)
                    destination.mapAndRead { get(data) }
                    fun row(i: Int): Statistics {
                        val base = i * 48
                        fun v(record: Int) = (0..3).map { data[base + record * 4 + it] }
                        return Statistics(v(0), v(1), v(2), v(3), v(4), v(5), v(6), v(7), v(8), v(9),
                            data[base + 40] > .5f, data[base + 41], data[base + 44], data[base + 45])
                    }
                    latest = Snapshot((0 until screens).map { row(it) }, row(screens), capturedSerial)
                }
            } finally { readbackPending.set(false) }
        }
    }

    override fun close() = renderer.runOnQueueThread {
        if (!closed) {
            closed = true
            vkDeviceWaitIdle(renderer.device.vkDevice)
            listOf(tileModule, finishModule, linkedModule).forEach { it?.current?.cleanup() }
            sampler?.cleanup()
            partials?.buffers?.forEach { it.cleanup() }
            statistics.buffers.forEach { it.cleanup() }
            readback?.cleanup()
        }
    }

    private fun resource(name: String) = VideoAutoExposure::class.java.getResource("/afield/stereovideo/$name.glsl")!!.readText()

    private fun barrier(command: CommandBuffer) = MemoryStack.stackPush().use { stack ->
        val memory = VkMemoryBarrier2.calloc(1, stack).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
            .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT)
        vkCmdPipelineBarrier2(command.vkCommandBuffer,
            VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO).pMemoryBarriers(memory))
    }
}
