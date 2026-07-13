package afield.four.texture

import afield.four.CPUSpan
import afield.four.Renderer
import afield.four.buffers.BufferParameters
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.device.Queue
import afield.four.device.Semaphore
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.Parameters
import afield.four.modules.pointer
import afield.four.util.vkCheck
import afield.logger.lprintln
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.stb.STBImage.stbi_image_free
import org.lwjgl.stb.STBImage.stbi_load
import org.lwjgl.stb.STBImageResize.stbir_resize_uint8_linear
import org.lwjgl.stb.STBImageWrite.stbi_write_jpg
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.vkGetSemaphoreCounterValue
import org.lwjgl.vulkan.VK13.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/**
 * Virtual texture system for textured quads using preallocated atlases per LOD band.
 * CPU-driven visibility + LOD selection; uploads are staged and published conservatively.
 */
class VirtualTextureSystem(
    val renderer: Renderer,
    val config: VirtualTextureConfig,
    val source: VirtualTextureSource
) : HasImageViewArray {

    // --- Public data for binding ---
    override val imageViewArray: List<HasImageView> get() = atlasImages
    val sampler: TextureSampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)

    val indirectionParams: BufferParameters
    val indirectionBuffer: FrameBufferGroup

    val bandInfoParams: BufferParameters
    val bandInfoBuffer: FrameBufferGroup

    val instanceParams: BufferParameters
    val instanceBuffer: FrameBufferGroup

    val totalAtlasCount: Int
    val bandCount: Int get() = bands.size

    // --- runtime data ---
    private val bands: List<LODBandRuntime>
    private val atlasImages: List<AtlasImage>
    val atlasSets: List<AtlasSet>

    private val entryCount: Int
    private val entryStates: IntArray
    private val entrySlots: IntArray
    private val entryRequestId: IntArray

    private val slotStates: Array<IntArray>
    private val slotVirtualTex: Array<IntArray>
    private val slotGeneration: Array<IntArray>
    private val slotLastUsedFrame: Array<LongArray>

    private val indirectionCPU: IntArray
    private var indirectionDirty = true

    private val bandInfoCPU: IntArray
    private var bandInfoDirty = true

    private val desiredLodByTex: IntArray
    private val desiredPriorityByTex: FloatArray
    private val desiredMaskByTex: IntArray
    private val visibleByTex: BooleanArray
    private val visibleTexIds: IntArray
    private var visibleTexCount: Int = 0
    private val missingVisibleTexIds: IntArray
    private var missingVisibleCount: Int = 0

    private val residentCountByTex: IntArray

    private val basePrefetchBandIndex: Int
    private val thumbnailBandIndex: Int
    private val thumbnailCacheFromBandIndex: Int
    private val thumbnailLocks = ConcurrentHashMap<Path, Any>()

    private val lodThresholdsCached: IntArray
    private val bandNoCapacityFrame: LongArray
    private val bandNextFreeSearch: IntArray

    private val mvpScratch = Matrix4f()
    private val tmpV4 = Vector4f()
    private val tmpMat16 = FloatArray(16)

    private val frameCounter: () -> Long = { renderer.frameCounter }

    private val decodeQueue = PriorityBlockingQueue<DecodeRequest>()
    private val readyUploads = ConcurrentLinkedQueue<DecodedUpload>()
    private val decodeStop = AtomicBoolean(false)
    private val nextRequestId = AtomicInteger(1)

    private val stagingPool: StagingPool

    private var debugFlags: Int = 0
    var lastInstanceCount: Int = 0

    // Timeline semaphore for upload completion if supported.
    private val uploadTimeline: Semaphore?

    private val pendingBatches = ArrayDeque<PendingBatch>()
    private val uploadCmdPool = ArrayDeque<CommandBuffer>()

    private val statsRequests = AtomicLong(0)
    private val statsUploads = AtomicLong(0)
    private val statsPublishes = AtomicLong(0)
    private val statsDecodeStarted = AtomicLong(0)
    private val statsDecodeFailed = AtomicLong(0)
    private val statsDecodeZero = AtomicLong(0)
    private val statsAllocFailed = AtomicLong(0)
    private val statsResolveMiss = AtomicLong(0)
    private val statsThumbHit = AtomicLong(0)
    private val statsThumbWrite = AtomicLong(0)
    private val statsThumbWriteFail = AtomicLong(0)
    private val statsCanceled = AtomicLong(0)
    private var lastLogFrame = -1L
    private var lastRequests = 0L
    private var lastUploads = 0L
    private var lastPublishes = 0L
    private var lastDecodeStarted = 0L
    private var lastDecodeFailed = 0L
    private var lastDecodeZero = 0L
    private var lastAllocFailed = 0L
    private var lastResolveMiss = 0L
    private var lastThumbHit = 0L
    private var lastThumbWrite = 0L
    private var lastThumbWriteFail = 0L
    private var lastCanceled = 0L

    init {
        // Build LOD bands and atlases
        val maxDim = renderer.physicalDevice.vkPhysicalDeviceProperties.limits().maxImageDimension2D()
        val (bandsBuilt, atlasList) = buildBandsAndAtlases(maxDim)
        bands = bandsBuilt
        atlasImages = atlasList
        totalAtlasCount = atlasImages.size
        atlasSets = bands.map { band ->
            val start = band.atlasBaseIndex
            val end = start + band.atlasCount
            AtlasSet(band, atlasImages.subList(start, end))
        }

        entryCount = config.virtualTexCount * bands.size
        entryStates = IntArray(entryCount) { VTState.INVALID.code }
        entrySlots = IntArray(entryCount) { -1 }
        entryRequestId = IntArray(entryCount) { 0 }

        desiredLodByTex = IntArray(config.virtualTexCount) { bands.size - 1 }
        desiredPriorityByTex = FloatArray(config.virtualTexCount) { 0f }
        desiredMaskByTex = IntArray(config.virtualTexCount) { 0 }
        visibleByTex = BooleanArray(config.virtualTexCount) { false }
        visibleTexIds = IntArray(config.virtualTexCount) { 0 }
        missingVisibleTexIds = IntArray(config.virtualTexCount) { 0 }
        residentCountByTex = IntArray(config.virtualTexCount) { 0 }

        lodThresholdsCached = computeLodThresholds()
        bandNoCapacityFrame = LongArray(bands.size) { Long.MIN_VALUE }
        bandNextFreeSearch = IntArray(bands.size) { 0 }

        // Slot arrays per band
        slotStates = Array(bands.size) { IntArray(bands[it].totalSlots) { SlotState.FREE.code } }
        slotVirtualTex = Array(bands.size) { IntArray(bands[it].totalSlots) { -1 } }
        slotGeneration = Array(bands.size) { IntArray(bands[it].totalSlots) { 1 } }
        slotLastUsedFrame = Array(bands.size) { LongArray(bands[it].totalSlots) { 0L } }

        // Indirection CPU buffer
        indirectionCPU = IntArray(entryCount * 4)
        // Init to INVALID
        for (i in 0 until entryCount) {
            writeEntryCPU(i, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0, VTState.INVALID.code)
        }

        // Band info CPU buffer
        bandInfoCPU = IntArray((1 + bands.size * 3) * 4)
        updateBandInfoCPU()

        // Buffers
        indirectionParams = BufferParameters(name = "Indirection").apply {
            size = indirectionCPU.size.toLong() * 4L
            storage()
            hosted()
        }
        indirectionBuffer = indirectionParams.toBuffer(renderer)

        bandInfoParams = BufferParameters(name = "BandInfo").apply {
            size = bandInfoCPU.size.toLong() * 4L
            storage()
            hosted()
        }
        bandInfoBuffer = bandInfoParams.toBuffer(renderer)

        instanceParams = BufferParameters(name = "Instances").apply {
            size = (config.maxInstances * INSTANCE_STRIDE_BYTES).toLong()
            storage()
            hosted()
        }
        instanceBuffer = instanceParams.toBuffer(renderer)

        // Transition atlas images to SHADER_READ_ONLY once
        transitionAtlasesToReadable()

        // Upload completion signaling
        uploadTimeline = if (config.useTimelineSemaphore) {
            try {
                Semaphore(renderer.device, timeline = true)
            } catch (t: Throwable) {
                lprintln("warning,graphics", "Timeline semaphore not available: ${t.message}; falling back to fences")
                null
            }
        } else null

        stagingPool = StagingPool(renderer, config.stagingBytesPerBatch, config.stagingPoolSize)

        basePrefetchBandIndex = computeBasePrefetchBandIndex()
        thumbnailBandIndex = computeThumbnailBandIndex()
        thumbnailCacheFromBandIndex = computeThumbnailCacheFromBandIndex()

        // Start decode workers
        startDecodeWorkers()

        // Pre-allocate a small set of reusable upload command buffers.
        // We never need more in flight than the staging pool size.
        repeat(config.stagingPoolSize.coerceAtLeast(1)) {
            uploadCmdPool.add(CommandBuffer(renderer.commandPool, primary = true, oneTimeSubmit = true))
        }

        // Push initial band info
        flushBandInfo()
        flushIndirection()
    }

    // --- Configuration & data structures ---

    data class VirtualTextureConfig(
        val virtualTexCount: Int,
        val maxInstances: Int,
        val bands: List<LODBandConfig> = defaultBands(),
        val stagingBytesPerBatch: Long = 32L * 1024 * 1024, // 32 MB
        val stagingPoolSize: Int = 6,
        val decodeThreads: Int = 6,
        val maxUploadsPerFrame: Int = 64*4,
        val maxRequestsPerFrame: Int = 256*4,
        val useTimelineSemaphore: Boolean = true,
        val lodHysteresis: Float = 0.10f,
        val logEveryNFrames: Int = 120,
        val enableLogging: Boolean = true,
        // Debug: scan decoded pixels for obvious "all zero/flat" issues.
        val debugCheckDecoded: Boolean = false,
        val debugLogDecodedAnomalies: Boolean = true,
        val debugMaxDecodedWarnings: Int = 10,
        // Debug: bypass IO/decoder and generate a procedural pattern instead.
        val debugForcePattern: Boolean = false,
        // Prefetch a cheap base LOD so we can fall back instead of showing placeholders.
        val prefetchBaseEnabled: Boolean = true,
        // -1 means "coarsest band" (bands.lastIndex). Otherwise, a concrete band index.
        val prefetchBaseBandIndex: Int = -1,
        // Requests reserved per frame for base prefetch so it doesn't get starved by higher LODs.
        val prefetchBaseBudgetPerFrame: Int = 64,
        // Lower priority multiplier for "background" base prefetch when a texture already has some resident LOD.
        val prefetchBasePriorityScale: Float = 0.05f,
        // If false, prefetch base only for textures that have no resident LOD at all (i.e. would show placeholder).
        val prefetchBaseEvenIfResident: Boolean = true,
        // Thumbnail cache: for a low-res band, load/write cached JPEGs in a sibling directory.
        val thumbnailCacheEnabled: Boolean = false,
        // -1 means "coarsest band" (bands.lastIndex). Otherwise, a concrete band index.
        val thumbnailCacheBandIndex: Int = -1,
        // If set (>=0), cache this band and all coarser bands (inclusive). Otherwise, only `thumbnailCacheBandIndex`.
        val thumbnailCacheFromBandIndex: Int = -1,
        val thumbnailCacheDirName: String = ".thumbnails",
        val thumbnailCacheJpegQuality: Int = 80,
        // Request cancellation & backpressure.
        val cancelStaleRequests: Boolean = true,
        val pruneCanceledDecodeRequests: Boolean = true,
        val pruneDecodeQueueMinSize: Int = 2048,
        // 0 disables the cap.
        val maxDecodeQueueSize: Int = 0,
        // Center-screen boost: prioritize and slightly bias LOD for quads whose projected center lies
        // within a central rectangle of the screen (e.g. 0.5 => middle half).
        val centerBoostEnabled: Boolean = false,
        val centerBoostRegionFraction: Float = 0.5f,
        val centerBoostPixelScale: Float = 1.15f,
        val centerBoostPriorityScale: Float = 1.25f,
        // Faster approximate culling/LOD for large instance counts.
        val fastCullingEnabled: Boolean = false,
        val fastCullingThreshold: Int = 5000,
        // Optional single-pixel final LOD (if it fits within the sampler array limit).
        val singlePixelBandEnabled: Boolean = true,
        val singlePixelBandSlots: Int = -1,
        val maxAtlasSamplers: Int = 16
    )

    data class LODBandConfig(
        val slotW: Int,
        val slotH: Int,
        val padding: Int,
        val maxSlotsTotal: Int,
        val preferredAtlasSlots: Int = maxSlotsTotal
    )

    data class LODBandRuntime(
        val index: Int,
        val slotW: Int,
        val slotH: Int,
        val padding: Int,
        val slotPitchX: Int,
        val slotPitchY: Int,
        val cols: Int,
        val rows: Int,
        val atlasW: Int,
        val atlasH: Int,
        val slotsPerAtlas: Int,
        val atlasCount: Int,
        val totalSlots: Int,
        val atlasBaseIndex: Int
    )

    interface VirtualTextureSource {
        fun resolvePath(virtualTexId: Int): Path?
    }

    data class AtlasSet(
        val band: LODBandRuntime,
        val images: List<AtlasImage>
    )

    data class VirtualQuadInstance(
        val virtualTexId: Int,
        var model: Matrix4f = Matrix4f(),
        var desiredLod: Int = 0,
        var lastLod: Int = -1,
        var pixelSize: Float = 0f,
        var visible: Boolean = true
    )

    data class LODChoice(val lod: Int, val pixelSize: Float)

    private enum class VTState(val code: Int) { INVALID(0), LOADING(1), RESIDENT(2) }
    private enum class SlotState(val code: Int) { FREE(0), LOADING(1), UPLOADING(2), RESIDENT(3) }

    private data class AtlasGrid(val cols: Int, val rows: Int)

    private data class DecodeRequest(
        val virtualTexId: Int,
        val bandIndex: Int,
        val priority: Float,
        val requestId: Int,
        val slotId: Int,
        val path: Path
    ) : Comparable<DecodeRequest> {
        override fun compareTo(other: DecodeRequest): Int {
            val byPriority = other.priority.compareTo(priority)
            return if (byPriority != 0) byPriority else requestId.compareTo(other.requestId)
        }
    }

    private data class DecodedUpload(
        val virtualTexId: Int,
        val bandIndex: Int,
        val requestId: Int,
        val slotId: Int,
        val data: ByteBuffer,
        val bytes: Int
    )

    private data class PendingPublish(
        val virtualTexId: Int,
        val bandIndex: Int,
        val slotId: Int,
        val requestId: Int
    )

    private data class PendingBatch(
        val cmd: CommandBuffer,
        val fence: Fence?,
        val timelineValue: Long,
        val publishes: List<PendingPublish>,
        val staging: StagingPool.StagingBuffer
    )

    private class StagingPool(
        val renderer: Renderer,
        val capacityBytes: Long,
        poolSize: Int
    ) {
        data class StagingBuffer(
            val buffer: VulkanBuffer,
            val capacity: Long,
            var inUse: Boolean = false,
            var fence: Fence? = null,
            var timelineValue: Long = 0,
            var offset: Long = 0
        )

        private val buffers = Array(poolSize) {
            StagingBuffer(
                VulkanBuffer(renderer, capacityBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT),
                capacityBytes
            )
        }

        fun acquire(completedTimeline: Long, useTimeline: Boolean): StagingBuffer? {
            for (buf in buffers) {
                if (!buf.inUse) {
                    buf.offset = 0
                    buf.fence = null
                    buf.timelineValue = 0
                    buf.inUse = true
                    return buf
                }
                if (buf.inUse) {
                    if (useTimeline) {
                        if (buf.timelineValue <= completedTimeline) {
                            buf.offset = 0
                            buf.fence?.cleanup()
                            buf.fence = null
                            buf.timelineValue = 0
                            buf.inUse = true
                            return buf
                        }
                    } else {
                        val f = buf.fence
                        if (f != null && f.isSignaled()) {
                            f.cleanup()
                            buf.fence = null
                            buf.offset = 0
                            buf.inUse = true
                            return buf
                        }
                    }
                }
            }
            return null
        }

        fun release(buf: StagingBuffer, fence: Fence?, timelineValue: Long) {
            buf.fence = fence
            buf.timelineValue = timelineValue
            buf.inUse = true
        }

        fun markFree(buf: StagingBuffer) {
            buf.inUse = false
            buf.offset = 0
            buf.fence?.cleanup()
            buf.fence = null
            buf.timelineValue = 0
        }
    }

    // --- Public API ---

    fun setDebugFlags(showLodTint: Boolean, showPlaceholder: Boolean, showUv: Boolean = false) {
        var flags = 0
        if (showLodTint) flags = flags or 1
        if (showPlaceholder) flags = flags or 2
        if (showUv) flags = flags or 4
        if (flags != debugFlags) {
            debugFlags = flags
            bandInfoDirty = true
        }
    }

    private fun computeBasePrefetchBandIndex(): Int {
        val requested = config.prefetchBaseBandIndex
        return if (requested in 0 until bands.size) requested else bands.lastIndex
    }

    private fun computeThumbnailBandIndex(): Int {
        val requested = config.thumbnailCacheBandIndex
        return if (requested in 0 until bands.size) requested else bands.lastIndex
    }

    private fun computeThumbnailCacheFromBandIndex(): Int {
        val requested = config.thumbnailCacheFromBandIndex
        if (requested < 0) return -1
        return requested.coerceIn(0, bands.lastIndex)
    }

    private fun cancelStaleRequests() {
        // Cancel LOADING entries that are no longer desired / visible. This prevents runaway decode queues
        // when the camera moves rapidly or LOD requirements churn.
        var canceled = 0L
        val baseBit = if (config.prefetchBaseEnabled) (1 shl basePrefetchBandIndex) else 0
        val allBits = ((1L shl bands.size) - 1L).toInt()

        for (vt in 0 until config.virtualTexCount) {
            val keepMask = if (!visibleByTex[vt]) {
                0
            } else {
                // Keep any currently-loading coarser bands as potential fallbacks while finer LODs stream in.
                val minDesired = desiredLodByTex[vt].coerceIn(0, bands.size - 1)
                (allBits and (allBits shl minDesired)) or baseBit
            }
            val base = vt * bands.size
            for (lod in 0 until bands.size) {
                val entry = base + lod
                if (entryStates[entry] != VTState.LOADING.code) continue
                if ((keepMask and (1 shl lod)) != 0) continue

                val slotId = entrySlots[entry]

                entryStates[entry] = VTState.INVALID.code
                entrySlots[entry] = -1
                entryRequestId[entry] = 0
                writeEntryCPU(entry, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0, VTState.INVALID.code)
                indirectionDirty = true

                if (slotId >= 0) {
                    freeLoadingSlotIfOwned(lod, slotId, vt)
                }
                canceled++
            }
        }

        if (canceled > 0) statsCanceled.addAndGet(canceled)

        // If the decode queue is huge, prune any stale requests in bulk.
        val dqSize = decodeQueue.size
        if (config.pruneCanceledDecodeRequests && (canceled > 0 || dqSize >= config.pruneDecodeQueueMinSize)) {
            decodeQueue.removeIf {
                val entry = entryIndex(it.virtualTexId, it.bandIndex)
                entryRequestId[entry] != it.requestId
            }
        }
    }

    private fun freeLoadingSlotIfOwned(bandIndex: Int, slotId: Int, virtualTexId: Int) {
        if (slotId < 0) return
        if (slotStates[bandIndex][slotId] == SlotState.LOADING.code && slotVirtualTex[bandIndex][slotId] == virtualTexId) {
            slotStates[bandIndex][slotId] = SlotState.FREE.code
            slotVirtualTex[bandIndex][slotId] = -1
            slotGeneration[bandIndex][slotId] += 1
        }
    }

    /**
     * Update LOD selection, request streaming, schedule uploads, and publish completed residency.
     * Call once per frame.
     */
    fun update(
        quads: List<VirtualQuadInstance>,
        viewProj: Matrix4f,
        viewportW: Int,
        viewportH: Int
    ) {
        val frame = frameCounter()

        // Compute LODs + write instance buffer
        CPUSpan.time("vt_lod") {
            updateDesiredLods(quads, viewProj, viewportW, viewportH)
            writeInstanceBuffer(quads)
        }

        // Track visible textures with no resident LOD (placeholders). We prioritize these.
        var missingCount = 0
        if (visibleTexCount > 0) {
            for (i in 0 until visibleTexCount) {
                val vt = visibleTexIds[i]
                if (vt !in 0 until config.virtualTexCount) continue
                if (residentCountByTex[vt] == 0) {
                    missingVisibleTexIds[missingCount++] = vt
                }
            }
        }
        missingVisibleCount = missingCount

        if (config.cancelStaleRequests) cancelStaleRequests()

        // Touch LRU for resident slots (best-effort). Do this per *visible texture* and per desired LOD,
        // rather than per quad, to avoid O(quads) duplication when many instances share textures.
        for (i in 0 until visibleTexCount) {
            val vt = visibleTexIds[i]
            if (vt !in 0 until config.virtualTexCount) continue
            var mask = desiredMaskByTex[vt]
            while (mask != 0) {
                val lod0 = Integer.numberOfTrailingZeros(mask).coerceIn(0, bands.size - 1)
                mask = mask and (mask - 1)

                // Mirror shader fallback: desired->coarser, then finer.
                var touched = false
                for (lod in lod0 until bands.size) {
                    val entry = entryIndex(vt, lod)
                    if (entryStates[entry] == VTState.RESIDENT.code) {
                        val slotId = entrySlots[entry]
                        if (slotId >= 0) slotLastUsedFrame[lod][slotId] = frame
                        touched = true
                        break
                    }
                }
                if (!touched) {
                    for (lod in (lod0 - 1) downTo 0) {
                        val entry = entryIndex(vt, lod)
                        if (entryStates[entry] == VTState.RESIDENT.code) {
                            val slotId = entrySlots[entry]
                            if (slotId >= 0) slotLastUsedFrame[lod][slotId] = frame
                            break
                        }
                    }
                }
            }
        }

        // Process pending publish completions
        drainPendingBatches()

        // Enqueue streaming requests
        CPUSpan.time("vt_requests") {
            val maxReq = config.maxRequestsPerFrame.coerceAtLeast(0)
            val doBasePrefetch = config.prefetchBaseEnabled && config.prefetchBaseBudgetPerFrame > 0
            val baseBand = basePrefetchBandIndex
            val baseBudget = if (doBasePrefetch) {
                if (missingVisibleCount > 0) maxReq else config.prefetchBaseBudgetPerFrame.coerceIn(0, maxReq)
            } else 0
            val desiredBudget = (maxReq - baseBudget).coerceAtLeast(0)

            var desiredCount = 0

            // Pass 1: request all LODs currently desired by any instance, coarse-first.
            // (Skip base band when prefetch is enabled so it doesn't starve higher LODs.)
            run {
                for (lod in bands.size - 1 downTo 0) {
                    if (doBasePrefetch && lod == baseBand) continue
                    val bit = 1 shl lod
                    val sourceCount = if (missingVisibleCount > 0) missingVisibleCount else visibleTexCount
                    val source = if (missingVisibleCount > 0) missingVisibleTexIds else visibleTexIds
                    for (i in 0 until sourceCount) {
                        val vt = source[i]
                        if ((desiredMaskByTex[vt] and bit) == 0) continue
                        val entry = entryIndex(vt, lod)
                        if (entryStates[entry] != VTState.INVALID.code) continue
                        if (desiredCount >= desiredBudget) return@run

                        val priority = desiredPriorityByTex[vt]
                        if (priority <= 0f) continue
                        if (enqueueRequestBestEffort(vt, lod, priority)) desiredCount++
                    }
                }
            }

            // Pass 2: prefetch base LOD for visible textures so transitions can fall back to it.
            if (doBasePrefetch && baseBudget > 0 && visibleTexCount > 0) {
                var baseCount = 0
                // 2a) First, ensure we always have *something* (no placeholders): base for textures with no resident LOD.
                for (i in 0 until visibleTexCount) {
                    if (baseCount >= baseBudget) break
                    val vt = visibleTexIds[i]
                    if (hasAnyResident(vt)) continue
                    val baseEntry = entryIndex(vt, baseBand)
                    if (entryStates[baseEntry] != VTState.INVALID.code) continue
                    val p = desiredPriorityByTex[vt]
                    if (p <= 0f) continue
                    if (enqueueRequestBestEffort(vt, baseBand, p)) baseCount++
                }

                // 2b) Then prefetch base in the background for textures that already have some resident LOD.
                if (baseCount < baseBudget && config.prefetchBaseEvenIfResident && missingVisibleCount == 0) {
                    for (i in 0 until visibleTexCount) {
                        if (baseCount >= baseBudget) break
                        val vt = visibleTexIds[i]
                        val baseEntry = entryIndex(vt, baseBand)
                        if (entryStates[baseEntry] != VTState.INVALID.code) continue
                        if (!hasAnyResident(vt)) continue // handled above

                        val p = desiredPriorityByTex[vt] * config.prefetchBasePriorityScale
                        if (p <= 0f) continue
                        if (enqueueRequestBestEffort(vt, baseBand, p)) baseCount++
                    }
                }
            }
        }

        // Schedule uploads (decoded -> staging -> copy)
        CPUSpan.time("vt_upload") {
            scheduleUploads()
        }

        // Flush indirection + band info
        if (bandInfoDirty) flushBandInfo()
        if (indirectionDirty) flushIndirection()

        logStats(frame)
    }

    /** Create a module with shaders wired to this virtual texture system. */
    fun buildModule(name: String = "virtual-textured-quads"): ModulePointer {
        val (vs, fs) = buildShaders()
        val params = Parameters(renderer, vertex = vs, fragment = fs, name = name)
        params.textureResolver = {
            if (it.name == "atlasTex") {
                this to sampler
            } else null
        }
        params.ssboResolver = { bp ->
            when (bp.name) {
                "Instances" -> instanceParams
                "Indirection" -> indirectionParams
                "BandInfo" -> bandInfoParams
                else -> bp
            }
        }
        params.ssboSizer = { ubo ->
            when (ubo.name) {
                "Instances" -> instanceParams.size.toInt()
                "Indirection" -> indirectionParams.size.toInt()
                "BandInfo" -> bandInfoParams.size.toInt()
                else -> 0
            }
        }
        return Module(params).pointer()
    }

    // --- Shader strings ---

    fun buildShaders(): Pair<String, String> {
        val atlasCount = totalAtlasCount
        val vs = """
            #version 450
            #extension GL_ARB_separate_shader_objects : enable
            #extension GL_EXT_nonuniform_qualifier : enable

            layout(location = 0) in vec3 inPos;
            layout(location = 1) in vec2 inUV;

            layout(push_constant) uniform PushConsts {
                mat4 viewProj;
            } pc;

            struct InstanceData {
                mat4 model;
                uvec4 extra;
            };

            layout(set = 0, binding = 1, std430) readonly buffer Instances {
                InstanceData inst[];
            } instances;

            layout(location = 0) out vec2 vUV;
            layout(location = 1) flat out uint vTexId;
            layout(location = 2) flat out uint vDesiredLod;

            void main() {
                uint idx = gl_InstanceIndex;
                InstanceData d = instances.inst[idx];
                vec4 world = d.model * vec4(inPos, 1.0);
                gl_Position = pc.viewProj * world;
                vUV = inUV;
                vTexId = d.extra.x;
                vDesiredLod = d.extra.y;
            }
        """.trimIndent()

        // Note: instance extra data is fetched in fragment via explicit buffer below.
        val fs = """
            #version 450
            #extension GL_ARB_separate_shader_objects : enable
            #extension GL_EXT_nonuniform_qualifier : enable

            layout(location = 0) in vec2 vUV;
            layout(location = 1) flat in uint vTexId;
            layout(location = 2) flat in uint vDesiredLod;

            layout(location = 0) out vec4 outColor;

            layout(set = 0, binding = 0) uniform sampler2D atlasTex[$atlasCount];

            layout(set = 0, binding = 2, std430) readonly buffer Indirection {
                uvec4 entries[];
            } indir;

            layout(set = 0, binding = 3, std430) readonly buffer BandInfo {
                uvec4 bandInfo[];
            } binfo;

            const uint STATE_INVALID = 0u;
            const uint STATE_LOADING = 1u;
            const uint STATE_RESIDENT = 2u;

            uvec4 band_header() { return binfo.bandInfo[0]; }

            uvec4 band_a(uint band) { return binfo.bandInfo[1u + band*3u]; }
            uvec4 band_b(uint band) { return binfo.bandInfo[2u + band*3u]; }
            uvec4 band_c(uint band) { return binfo.bandInfo[3u + band*3u]; }

            void main() {
                uvec4 header = band_header();
                uint bandCount = header.x;
                uint debugFlags = header.y;

                uint texId = vTexId;
                uint desired = vDesiredLod;
                uint lod = min(desired, bandCount - 1u);
                vec4 color = vec4(1.0, 0.0, 1.0, 1.0); // placeholder magenta
                bool found = false;

                if ((debugFlags & 4u) != 0u) {
                    outColor = vec4(vUV, 0.0, 1.0);
                    return;
                }

                // Prefer desired->coarser, but if we're missing at the coarsest LOD,
                // fall back to finer rather than showing a placeholder.
                for (uint band = lod; band < bandCount; ++band) {
                    uint entryIndex = texId * bandCount + band;
                    uvec4 entry = indir.entries[entryIndex];
                    if (entry.w == STATE_RESIDENT) {
                        uint atlasIndex = entry.x;
                        uint slotIndex = entry.y;
                        uvec4 a = band_a(band);
                        uvec4 b = band_b(band);
                        uint slotW = a.x;
                        uint slotH = a.y;
                        uint padding = a.z;
                        uint cols = a.w;
                        uint rows = b.x;
                        uint atlasW = b.y;
                        uint atlasH = b.z;
                        uint slotPitchX = b.w;
                        uint slotPitchY = band_c(band).x;

                        uint col = slotIndex % cols;
                        uint row = slotIndex / cols;
                        vec2 base = vec2(float(col * slotPitchX + padding), float(row * slotPitchY + padding));
                        vec2 uvAtlas = (base + vUV * vec2(float(slotW), float(slotH))) / vec2(float(atlasW), float(atlasH));

                        color = texture(atlasTex[nonuniformEXT(atlasIndex)], uvAtlas);
                        if ((debugFlags & 1u) != 0u) {
                            // LOD tint
                            float t = float(band) / float(max(bandCount - 1u, 1u));
                            color.rgb = mix(color.rgb, vec3(t, 1.0 - t, 0.2), 0.35);
                        }
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    for (int bi = int(lod) - 1; bi >= 0; --bi) {
                        uint band = uint(bi);
                        uint entryIndex = texId * bandCount + band;
                        uvec4 entry = indir.entries[entryIndex];
                        if (entry.w == STATE_RESIDENT) {
                            uint atlasIndex = entry.x;
                            uint slotIndex = entry.y;
                            uvec4 a = band_a(band);
                            uvec4 b = band_b(band);
                            uint slotW = a.x;
                            uint slotH = a.y;
                            uint padding = a.z;
                            uint cols = a.w;
                            uint rows = b.x;
                            uint atlasW = b.y;
                            uint atlasH = b.z;
                            uint slotPitchX = b.w;
                            uint slotPitchY = band_c(band).x;

                            uint col = slotIndex % cols;
                            uint row = slotIndex / cols;
                            vec2 base = vec2(float(col * slotPitchX + padding), float(row * slotPitchY + padding));
                            vec2 uvAtlas = (base + vUV * vec2(float(slotW), float(slotH))) / vec2(float(atlasW), float(atlasH));

                            color = texture(atlasTex[nonuniformEXT(atlasIndex)], uvAtlas);
                            if ((debugFlags & 1u) != 0u) {
                                float t = float(band) / float(max(bandCount - 1u, 1u));
                                color.rgb = mix(color.rgb, vec3(t, 1.0 - t, 0.2), 0.35);
                            }
                            found = true;
                            break;
                        }
                    }
                }

                if (!found) {
                    if ((debugFlags & 2u) != 0u) {
                        color = vec4(1.0, 0.0, 0.5, 1.0);
                    } else {
                        color = vec4(0.2, 0.2, 0.3, 1.0);
                    }
                }

                outColor = color;
            }
        """.trimIndent()

        return vs to fs
    }

    // --- LOD selection ---

    private data class QuadScreenInfo(val visible: Boolean, val pixelSize: Float, val centerBoost: Boolean)

    private fun computeQuadScreenInfo(
        model: Matrix4f,
        viewProj: Matrix4f,
        viewportW: Int,
        viewportH: Int,
        mvpScratch: Matrix4f,
        tmp: Vector4f
    ): QuadScreenInfo {
        mvpScratch.set(viewProj).mul(model)

        var allBehind = true
        var anyBehind = false

        // Conservative homogeneous frustum reject for the easy case (all corners in front).
        var allLeft = true
        var allRight = true
        var allBottom = true
        var allTop = true
        var allNear = true
        var allFar = true

        var minSx = Float.POSITIVE_INFINITY
        var maxSx = Float.NEGATIVE_INFINITY
        var minSy = Float.POSITIVE_INFINITY
        var maxSy = Float.NEGATIVE_INFINITY
        var frontCount = 0

        fun corner(x: Float, y: Float) {
            tmp.set(x, y, 0f, 1f)
            mvpScratch.transform(tmp)
            val cx = tmp.x
            val cy = tmp.y
            val cz = tmp.z
            val cw = tmp.w

            if (cw <= 0f) {
                anyBehind = true
                return
            }
            allBehind = false

            // Plane tests in homogeneous clip space (Vulkan depth [0,1]): inside is
            // -w <= x <= w, -w <= y <= w, 0 <= z <= w.
            if (cx >= -cw) allLeft = false
            if (cx <= cw) allRight = false
            if (cy >= -cw) allBottom = false
            if (cy <= cw) allTop = false
            if (cz >= 0f) allNear = false
            if (cz <= cw) allFar = false

            val ndcX = cx / cw
            val ndcY = cy / cw
            val sx = (ndcX * 0.5f + 0.5f) * viewportW.toFloat()
            val sy = (ndcY * 0.5f + 0.5f) * viewportH.toFloat()
            if (sx < minSx) minSx = sx
            if (sx > maxSx) maxSx = sx
            if (sy < minSy) minSy = sy
            if (sy > maxSy) maxSy = sy
            frontCount++
        }

        corner(-0.5f, -0.5f)
        corner(0.5f, -0.5f)
        corner(0.5f, 0.5f)
        corner(-0.5f, 0.5f)

        if (allBehind) return QuadScreenInfo(visible = false, pixelSize = 0f, centerBoost = false)

        // If any corner is behind, avoid culling; the quad may cross the near plane.
        if (!anyBehind) {
            val offscreen = allLeft || allRight || allBottom || allTop || allNear || allFar
            if (offscreen) return QuadScreenInfo(visible = false, pixelSize = 0f, centerBoost = false)
        }

        // Pixel footprint estimate: bounding box of projected corners in front of the camera.
        // If we have too few corners in front (near-plane crossing), treat as big.
        val px = if (frontCount >= 2) {
            max(maxSx - minSx, maxSy - minSy)
        } else {
            max(viewportW, viewportH).toFloat()
        }

        val centerBoost = if (!config.centerBoostEnabled) {
            false
        } else {
            val frac = config.centerBoostRegionFraction
            if (!frac.isFinite() || frac <= 0f) {
                false
            } else {
                val clamped = frac.coerceIn(0f, 1f)
                val minX = viewportW.toFloat() * (0.5f - clamped * 0.5f)
                val maxX = viewportW.toFloat() * (0.5f + clamped * 0.5f)
                val minY = viewportH.toFloat() * (0.5f - clamped * 0.5f)
                val maxY = viewportH.toFloat() * (0.5f + clamped * 0.5f)
                val cx = (minSx + maxSx) * 0.5f
                val cy = (minSy + maxSy) * 0.5f
                (cx >= minX && cx <= maxX && cy >= minY && cy <= maxY)
            }
        }

        return QuadScreenInfo(visible = true, pixelSize = px, centerBoost = centerBoost)
    }

    private fun computeQuadScreenInfoFast(
        model: Matrix4f,
        viewProj: Matrix4f,
        viewportW: Int,
        viewportH: Int,
        mvpScratch: Matrix4f,
        tmp: Vector4f
    ): QuadScreenInfo {
        mvpScratch.set(viewProj).mul(model)

        val vw = viewportW.toFloat()
        val vh = viewportH.toFloat()

        // Center
        tmp.set(0f, 0f, 0f, 1f)
        mvpScratch.transform(tmp)
        val cw = tmp.w
        if (cw == 0f || cw.isNaN()) {
            return QuadScreenInfo(visible = true, pixelSize = max(vw, vh), centerBoost = false)
        }
        if (cw < 0f) {
            // Conservative: treat as visible with large footprint to avoid popping when crossing near plane.
            return QuadScreenInfo(visible = true, pixelSize = max(vw, vh), centerBoost = false)
        }

        val ndcX = tmp.x / cw
        val ndcY = tmp.y / cw
        val sx = (ndcX * 0.5f + 0.5f) * vw
        val sy = (ndcY * 0.5f + 0.5f) * vh

        // Approximate radius using projected half-axes.
        var dx = 0f
        var dy = 0f

        tmp.set(0.5f, 0f, 0f, 1f)
        mvpScratch.transform(tmp)
        val cwX = tmp.w
        if (cwX != 0f && cwX.isFinite()) {
            val sx2 = (tmp.x / cwX * 0.5f + 0.5f) * vw
            dx = abs(sx2 - sx)
        }

        tmp.set(0f, 0.5f, 0f, 1f)
        mvpScratch.transform(tmp)
        val cwY = tmp.w
        if (cwY != 0f && cwY.isFinite()) {
            val sy2 = (tmp.y / cwY * 0.5f + 0.5f) * vh
            dy = abs(sy2 - sy)
        }

        var radius = max(dx, dy)
        if (!radius.isFinite() || radius <= 0f) radius = max(vw, vh) * 0.5f

        val minX = sx - radius
        val maxX = sx + radius
        val minY = sy - radius
        val maxY = sy + radius
        if (maxX < 0f || minX > vw || maxY < 0f || minY > vh) {
            return QuadScreenInfo(visible = false, pixelSize = 0f, centerBoost = false)
        }

        val px = radius * 2f
        val centerBoost = if (!config.centerBoostEnabled) {
            false
        } else {
            val frac = config.centerBoostRegionFraction
            if (!frac.isFinite() || frac <= 0f) {
                false
            } else {
                val clamped = frac.coerceIn(0f, 1f)
                val minXb = vw * (0.5f - clamped * 0.5f)
                val maxXb = vw * (0.5f + clamped * 0.5f)
                val minYb = vh * (0.5f - clamped * 0.5f)
                val maxYb = vh * (0.5f + clamped * 0.5f)
                (sx >= minXb && sx <= maxXb && sy >= minYb && sy <= maxYb)
            }
        }

        return QuadScreenInfo(visible = true, pixelSize = px, centerBoost = centerBoost)
    }

    fun computeQuadScreenPixelSize(
        model: Matrix4f,
        viewProj: Matrix4f,
        viewportW: Int,
        viewportH: Int
    ): Float {
        val mvp = Matrix4f(viewProj).mul(model)
        val corners = arrayOf(
            Vector4f(-0.5f, -0.5f, 0f, 1f),
            Vector4f(0.5f, -0.5f, 0f, 1f),
            Vector4f(0.5f, 0.5f, 0f, 1f),
            Vector4f(-0.5f, 0.5f, 0f, 1f)
        )
        val screen = Array(4) { FloatArray(2) }
        for (i in 0 until 4) {
            val clip = mvp.transform(corners[i])
            val w = if (clip.w == 0f) 1e-6f else clip.w
            val invW = 1.0f / w
            val ndcX = clip.x * invW
            val ndcY = clip.y * invW
            val sx = (ndcX * 0.5f + 0.5f) * viewportW
            val sy = (ndcY * 0.5f + 0.5f) * viewportH
            screen[i][0] = sx
            screen[i][1] = sy
        }
        fun dist(a: FloatArray, b: FloatArray): Float {
            val dx = a[0] - b[0]
            val dy = a[1] - b[1]
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val e0 = dist(screen[0], screen[1])
        val e1 = dist(screen[1], screen[2])
        val e2 = dist(screen[2], screen[3])
        val e3 = dist(screen[3], screen[0])
        return max(max(e0, e2), max(e1, e3))
    }

    private fun updateDesiredLods(
        quads: List<VirtualQuadInstance>,
        viewProj: Matrix4f,
        viewportW: Int,
        viewportH: Int
    ) {
        // reset desired per texture
        java.util.Arrays.fill(desiredLodByTex, bands.size - 1)
        java.util.Arrays.fill(desiredPriorityByTex, 0f)
        java.util.Arrays.fill(desiredMaskByTex, 0)
        java.util.Arrays.fill(visibleByTex, false)
        visibleTexCount = 0

        val thresholds = lodThresholdsCached
        val maxQuads = min(quads.size, config.maxInstances)
        val useFast = config.fastCullingEnabled && maxQuads >= config.fastCullingThreshold
        var qi = 0
        for (q in quads) {
            if (qi++ >= maxQuads) break
            val info = if (useFast) {
                computeQuadScreenInfoFast(q.model, viewProj, viewportW, viewportH, mvpScratch, tmpV4)
            } else {
                computeQuadScreenInfo(q.model, viewProj, viewportW, viewportH, mvpScratch, tmpV4)
            }
            q.visible = info.visible
            if (!info.visible) {
                q.pixelSize = 0f
                val target = bands.size - 1
                q.desiredLod = target
                q.lastLod = target
                continue
            }

            val px = info.pixelSize
            q.pixelSize = px
            val lodPx = if (info.centerBoost) px * config.centerBoostPixelScale else px
            val priorityPx = if (info.centerBoost) px * config.centerBoostPriorityScale else px
            val target = chooseLod(lodPx, q.lastLod, thresholds, config.lodHysteresis)
            q.desiredLod = target
            q.lastLod = target

            val vt = q.virtualTexId
            if (vt in 0 until config.virtualTexCount) {
                if (!visibleByTex[vt]) {
                    visibleByTex[vt] = true
                    visibleTexIds[visibleTexCount++] = vt
                }
                if (target < desiredLodByTex[vt]) desiredLodByTex[vt] = target
                if (priorityPx > desiredPriorityByTex[vt]) desiredPriorityByTex[vt] = priorityPx
                desiredMaskByTex[vt] = desiredMaskByTex[vt] or (1 shl target)
            }
        }
    }

    private fun computeLodThresholds(): IntArray {
        // thresholds aligned with band sizes: for band i (>0), threshold is max(slotW, slotH)
        if (bands.size <= 1) return intArrayOf(1)
        val out = IntArray(bands.size - 1)
        for (i in 1 until bands.size) {
            out[i - 1] = max(bands[i].slotW, bands[i].slotH)
        }
        return out
    }

    private fun lodThresholds(): IntArray {
        return computeLodThresholds()
    }

    private fun chooseLod(pixelSize: Float, prev: Int, thresholds: IntArray, hysteresis: Float): Int {
        val bandCount = bands.size
        if (bandCount <= 1) return 0
        var target = 0
        for (i in thresholds.indices) {
            if (pixelSize <= thresholds[i]) target = i + 1
        }
        target = target.coerceIn(0, bandCount - 1)

        if (prev < 0) return target
        if (target == prev) return prev

        // Hysteresis: require a margin to move between bands
        val lastThresholdIndex = thresholds.size - 1
        val prevMin = when {
            prev <= 0 -> thresholds[0].toFloat()
            prev >= bandCount - 1 -> 0f
            else -> thresholds[prev].toFloat()
        }
        val prevMax = when {
            prev <= 0 -> Float.MAX_VALUE
            prev >= bandCount - 1 -> thresholds[lastThresholdIndex].toFloat()
            else -> thresholds[prev - 1].toFloat()
        }

        return if (target < prev) {
            if (pixelSize > prevMax * (1f + hysteresis)) target else prev
        } else {
            if (pixelSize < prevMin * (1f - hysteresis)) target else prev
        }
    }

    // --- Requests, decode, uploads ---

    private fun enqueueRequest(vt: Int, bandIndex: Int, priority: Float): Boolean {
        val entry = entryIndex(vt, bandIndex)
        if (entryStates[entry] != VTState.INVALID.code) return false
        if (config.maxDecodeQueueSize > 0 && decodeQueue.size >= config.maxDecodeQueueSize) return false

        val path = source.resolvePath(vt)
        if (path == null) {
            statsResolveMiss.incrementAndGet()
            return false
        }
        val slotId = allocateSlot(bandIndex)
        if (slotId < 0) {
            statsAllocFailed.incrementAndGet()
            return false
        }

        val requestId = nextRequestId.getAndIncrement()
        entryStates[entry] = VTState.LOADING.code
        entryRequestId[entry] = requestId
        entrySlots[entry] = slotId
        indirectionDirty = true
        // Keep atlas invalid until upload complete
        writeEntryCPU(entry, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0, VTState.LOADING.code)

        slotStates[bandIndex][slotId] = SlotState.LOADING.code
        slotVirtualTex[bandIndex][slotId] = vt

        decodeQueue.offer(
            DecodeRequest(vt, bandIndex, priority, requestId, slotId, path)
        )
        statsRequests.incrementAndGet()
        return true
    }

    /**
     * Best-effort request: if the desired band is saturated, fall back to requesting
     * progressively coarser bands so the shader can show *something* instead of a placeholder.
     *
     * Returns true only if a new request was enqueued.
     */
    private fun enqueueRequestBestEffort(vt: Int, desiredBandIndex: Int, priority: Float): Boolean {
        if (enqueueRequest(vt, desiredBandIndex, priority)) return true

        // If desired can't be requested (usually saturated), try coarser bands.
        for (band in (desiredBandIndex + 1) until bands.size) {
            val entry = entryIndex(vt, band)
            val st = entryStates[entry]
            if (st == VTState.RESIDENT.code) {
                // Already resident at a coarser band — no new request needed.
                return false
            }
            if (st == VTState.LOADING.code) {
                // Coarser is in flight, but we might still want an even-coarser fallback to avoid placeholders.
                continue
            }
            if (enqueueRequest(vt, band, priority)) return true
        }
        return false
    }

    private fun hasAnyResident(vt: Int): Boolean {
        return vt in 0 until config.virtualTexCount && residentCountByTex[vt] > 0
    }

    private fun hasResidentOtherThan(vt: Int, excludingBandIndex: Int): Boolean {
        if (vt !in 0 until config.virtualTexCount) return false
        val count = residentCountByTex[vt]
        if (count == 0) return false
        val entry = entryIndex(vt, excludingBandIndex)
        return if (entryStates[entry] == VTState.RESIDENT.code) count > 1 else true
    }

    private fun allocateSlot(bandIndex: Int): Int {
        val total = bands[bandIndex].totalSlots
        val framesInFlight = renderer.numImages
        val frame = frameCounter()
        val desiredBit = 1 shl bandIndex

        // If we've already determined this band has no capacity this frame, bail early to avoid O(total) scans.
        if (bandNoCapacityFrame[bandIndex] == frame) return -1

        val states = slotStates[bandIndex]

        // Free slot first (ring scan so we don't always start at 0).
        run {
            var i = bandNextFreeSearch[bandIndex].coerceIn(0, max(total - 1, 0))
            var n = 0
            while (n < total) {
                if (states[i] == SlotState.FREE.code) {
                    bandNextFreeSearch[bandIndex] = if (i + 1 >= total) 0 else i + 1
                    return i
                }
                i++
                if (i >= total) i = 0
                n++
            }
        }

        // Evict LRU (oldest lastUsed) that is safe. We use a frames-in-flight heuristic
        // because renderer fences are not exposed here; this is conservative enough for now.
        var best = -1
        var bestAge = Long.MIN_VALUE
        var bestKey = Int.MAX_VALUE
        for (i in 0 until total) {
            if (states[i] != SlotState.RESIDENT.code) continue
            val last = slotLastUsedFrame[bandIndex][i]
            if (frame - last < framesInFlight) continue
            val age = frame - last

            val vt = slotVirtualTex[bandIndex][i]
            // Prefer evicting entries that won't leave a texture with no resident LOD (avoid placeholders).
            val hasOtherResident = vt >= 0 && hasResidentOtherThan(vt, bandIndex)
            val isLastResident = if (vt >= 0 && !hasOtherResident) 1 else 0
            // Prefer evicting entries that aren't currently desired at this band.
            val isDesiredHere = if (vt >= 0 && (desiredMaskByTex[vt] and desiredBit) != 0) 1 else 0
            val key = isLastResident * 2 + isDesiredHere

            if (key < bestKey || (key == bestKey && age > bestAge)) {
                bestKey = key
                bestAge = age
                best = i
            }
        }
        if (best >= 0) {
            evictSlot(bandIndex, best)
            return best
        }

        bandNoCapacityFrame[bandIndex] = frame
        return -1
    }

    private fun evictSlot(bandIndex: Int, slotId: Int) {
        val vt = slotVirtualTex[bandIndex][slotId]
        if (vt >= 0) {
            val entry = entryIndex(vt, bandIndex)
            if (entryStates[entry] == VTState.RESIDENT.code && vt in 0 until config.virtualTexCount) {
                residentCountByTex[vt] = (residentCountByTex[vt] - 1).coerceAtLeast(0)
            }
            entryStates[entry] = VTState.INVALID.code
            entrySlots[entry] = -1
            writeEntryCPU(entry, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0, VTState.INVALID.code)
            indirectionDirty = true
        }
        slotGeneration[bandIndex][slotId] += 1
        slotVirtualTex[bandIndex][slotId] = -1
        slotStates[bandIndex][slotId] = SlotState.FREE.code
    }

    private fun scheduleUploads() {
        val timeline = uploadTimeline
        val useTimeline = timeline != null
        val completedTimeline = if (timeline != null) getTimelineValue(timeline) else 0L

        val staging = stagingPool.acquire(completedTimeline, useTimeline) ?: return

        var uploads = 0
        val uploadJobs = ArrayList<UploadJob>(config.maxUploadsPerFrame.coerceAtLeast(0))
        val mapped = staging.buffer.map()
        val dst = MemoryUtil.memByteBuffer(mapped, staging.capacity.toInt())

        while (uploads < config.maxUploadsPerFrame) {
            val decoded = readyUploads.poll() ?: break
            // drop if outdated
            val entry = entryIndex(decoded.virtualTexId, decoded.bandIndex)
            if (entryRequestId[entry] != decoded.requestId) {
                MemoryUtil.memFree(decoded.data)
                // Slot might still be marked LOADING for an outdated request; free it so we don't leak capacity.
                freeLoadingSlotIfOwned(decoded.bandIndex, decoded.slotId, decoded.virtualTexId)
                continue
            }

            val alignedOffset = align4(staging.offset)
            if (alignedOffset + decoded.bytes > staging.capacity) {
                // no space; push back and stop
                readyUploads.add(decoded)
                break
            }

            // copy to staging buffer
            dst.position(alignedOffset.toInt())
            dst.put(decoded.data)

            val atlasIndex = slotAtlasIndex(decoded.bandIndex, decoded.slotId)
            val slotIndex = slotIndexInAtlas(decoded.bandIndex, decoded.slotId)
            slotStates[decoded.bandIndex][decoded.slotId] = SlotState.UPLOADING.code
            uploadJobs.add(
                UploadJob(
                    bandIndex = decoded.bandIndex,
                    atlasIndex = atlasIndex,
                    slotIndex = slotIndex,
                    bufferOffset = alignedOffset,
                    bytes = decoded.bytes,
                    virtualTexId = decoded.virtualTexId,
                    slotId = decoded.slotId,
                    requestId = decoded.requestId
                )
            )

            staging.offset = alignedOffset + decoded.bytes.toLong()
            MemoryUtil.memFree(decoded.data)
            uploads++
        }

        if (uploadJobs.isEmpty()) {
            stagingPool.markFree(staging)
            return
        }

        // Flush all staging writes in one go.
        staging.buffer.unmap(0, staging.offset)
        statsUploads.addAndGet(uploadJobs.size.toLong())

        // record command buffer
        val cmd = acquireUploadCommandBuffer()
        cmd.beginRecording()
        recordUploads(cmd, uploadJobs, staging.buffer.buffer)
        cmd.endRecording()

        // submit
        val pendingPublishes = uploadJobs.map {
            PendingPublish(it.virtualTexId, it.bandIndex, it.slotId, it.requestId)
        }

        if (timeline != null) {
            val signalValue = timeline.nextSignalValue()
            submitWithTimeline(cmd, timeline, signalValue)
            pendingBatches.add(
                PendingBatch(cmd, null, signalValue, pendingPublishes, staging)
            )
            stagingPool.release(staging, null, signalValue)
        } else {
            val fence = Fence(renderer.device, signaled = false)
            submitWithFence(cmd, fence)
            pendingBatches.add(
                PendingBatch(cmd, fence, 0L, pendingPublishes, staging)
            )
            stagingPool.release(staging, fence, 0L)
        }
    }

    private fun drainPendingBatches() {
        if (pendingBatches.isEmpty()) return
        val timeline = uploadTimeline
        val useTimeline = timeline != null
        val completed = if (timeline != null) getTimelineValue(timeline) else 0L

        val iter = pendingBatches.iterator()
        while (iter.hasNext()) {
            val batch = iter.next()
            val done = if (useTimeline) {
                batch.timelineValue <= completed
            } else {
                batch.fence?.isSignaled() == true
            }
            if (!done) continue

            recycleUploadCommandBuffer(batch.cmd)
            batch.publishes.forEach { publishResidency(it) }
            stagingPool.markFree(batch.staging)
            iter.remove()
        }
    }

    private fun acquireUploadCommandBuffer(): CommandBuffer {
        val cmd = if (uploadCmdPool.isNotEmpty()) {
            uploadCmdPool.removeFirst()
        } else {
            // Should be rare; fall back to allocating another one-time buffer.
            CommandBuffer(renderer.commandPool, primary = true, oneTimeSubmit = true)
        }
        cmd.reset()
        return cmd
    }

    private fun recycleUploadCommandBuffer(cmd: CommandBuffer) {
        uploadCmdPool.addLast(cmd)
    }

    private fun publishResidency(p: PendingPublish) {
        val entry = entryIndex(p.virtualTexId, p.bandIndex)
        val slotId = p.slotId
        // If the request was canceled/replaced while the upload was in flight, drop the publish and free the slot.
        if (entryRequestId[entry] != p.requestId || entryStates[entry] != VTState.LOADING.code) {
            if (slotId >= 0 &&
                (slotStates[p.bandIndex][slotId] == SlotState.UPLOADING.code || slotStates[p.bandIndex][slotId] == SlotState.LOADING.code) &&
                slotVirtualTex[p.bandIndex][slotId] == p.virtualTexId
            ) {
                slotStates[p.bandIndex][slotId] = SlotState.FREE.code
                slotVirtualTex[p.bandIndex][slotId] = -1
                slotGeneration[p.bandIndex][slotId] += 1
            }
            return
        }

        val atlasIndex = slotAtlasIndex(p.bandIndex, slotId)
        val slotIndex = slotIndexInAtlas(p.bandIndex, slotId)
        val gen = slotGeneration[p.bandIndex][slotId]

        entryStates[entry] = VTState.RESIDENT.code
        entrySlots[entry] = slotId
        writeEntryCPU(entry, atlasIndex, slotIndex, gen, VTState.RESIDENT.code)
        indirectionDirty = true

        slotStates[p.bandIndex][slotId] = SlotState.RESIDENT.code
        slotLastUsedFrame[p.bandIndex][slotId] = frameCounter()
        val vt = p.virtualTexId
        if (vt in 0 until config.virtualTexCount) {
            residentCountByTex[vt] += 1
        }
        statsPublishes.incrementAndGet()
    }

    private fun recordUploads(cmd: CommandBuffer, jobs: List<UploadJob>, stagingBuffer: Long) {
        MemoryStack.stackPush().use { stack ->
            // group by atlas
            val byAtlas = jobs.groupBy { it.atlasIndex }
            for ((atlasIndex, list) in byAtlas) {
                val atlas = atlasImages[atlasIndex]
                // transition to transfer
                val range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)

                image_layout_transition(
                    cmd.vkCommandBuffer,
                    atlas.image.vkImage,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    atlas.currentLayout,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    range
                )
                atlas.currentLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL

                for (job in list) {
                    val band = bands[job.bandIndex]
                    val col = job.slotIndex % band.cols
                    val row = job.slotIndex / band.cols
                    val offsetX = col * band.slotPitchX
                    val offsetY = row * band.slotPitchY

                    val region = VkBufferImageCopy.calloc(1, stack)
                        .bufferOffset(job.bufferOffset)
                        .bufferRowLength(0)
                        .bufferImageHeight(0)
                        .imageSubresource { it: VkImageSubresourceLayers ->
                            it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(0)
                                .baseArrayLayer(0)
                                .layerCount(1)
                        }
                        .imageOffset { it: VkOffset3D -> it.x(offsetX).y(offsetY).z(0) }
                        .imageExtent { it: VkExtent3D ->
                            it.width(band.slotPitchX).height(band.slotPitchY).depth(1)
                        }

                    vkCmdCopyBufferToImage(
                        cmd.vkCommandBuffer,
                        stagingBuffer,
                        atlas.image.vkImage,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region
                    )
                }

                image_layout_transition(
                    cmd.vkCommandBuffer,
                    atlas.image.vkImage,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    range
                )
                atlas.currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            }
        }
    }

    private fun submitWithTimeline(cmd: CommandBuffer, semaphore: Semaphore, signalValue: Long) {
        MemoryStack.stackPush().use { stack ->
            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmd.vkCommandBuffer))

            val timeline = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO)
                .pSignalSemaphoreValues(stack.longs(signalValue))
            submitInfo.pNext(timeline.address())

            submitInfo.pSignalSemaphores(stack.longs(semaphore.vkSemaphore))

            synchronized(Queue.globalQueueLock) {
                vkCheck(VK10.vkQueueSubmit(renderer.graphQueue.vkQueue, submitInfo, VK_NULL_HANDLE), "Failed to submit upload")
            }
        }
    }

    private fun submitWithFence(cmd: CommandBuffer, fence: Fence) {
        MemoryStack.stackPush().use { stack ->
            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmd.vkCommandBuffer))
            synchronized(Queue.globalQueueLock) {
                vkCheck(VK10.vkQueueSubmit(renderer.graphQueue.vkQueue, submitInfo, fence.vkFence), "Failed to submit upload")
            }
        }
    }

    private fun getTimelineValue(semaphore: Semaphore): Long {
        MemoryStack.stackPush().use { stack ->
            val p = stack.mallocLong(1)
            vkGetSemaphoreCounterValue(renderer.device.vkDevice, semaphore.vkSemaphore, p)
            return p[0]
        }
    }

    private fun transitionAtlasesToReadable() {
        if (atlasImages.isEmpty()) return
        val cmd = CommandBuffer(renderer.commandPool, primary = true, oneTimeSubmit = true)
        cmd.beginRecording()
        MemoryStack.stackPush().use { stack ->
            for (atlas in atlasImages) {
                val range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)

                image_layout_transition(
                    cmd.vkCommandBuffer,
                    atlas.image.vkImage,
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    0,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    range
                )
                atlas.currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            }
        }
        cmd.endRecording()
        val fence = Fence(renderer.device, signaled = false)
        submitWithFence(cmd, fence)
        fence.fenceWait()
        cmd.cleanup()
        fence.cleanup()
    }

    // --- Buffer updates ---

    private fun flushIndirection() {
        indirectionBuffer.get(write = true).mapAndWrite_ints {
            clear()
            put(indirectionCPU)
            flip()
        }
        indirectionDirty = false
    }

    private fun flushBandInfo() {
        if (bandInfoDirty) updateBandInfoCPU()
        bandInfoBuffer.get(write = true).mapAndWrite_ints {
            clear()
            put(bandInfoCPU)
            flip()
        }
        bandInfoDirty = false
    }

    private fun updateBandInfoCPU() {
        // header
        bandInfoCPU[0] = bands.size
        bandInfoCPU[1] = debugFlags
        bandInfoCPU[2] = 0
        bandInfoCPU[3] = 0
        for (i in bands.indices) {
            val base = (1 + i * 3) * 4
            val b = bands[i]
            bandInfoCPU[base + 0] = b.slotW
            bandInfoCPU[base + 1] = b.slotH
            bandInfoCPU[base + 2] = b.padding
            bandInfoCPU[base + 3] = b.cols

            bandInfoCPU[base + 4] = b.rows
            bandInfoCPU[base + 5] = b.atlasW
            bandInfoCPU[base + 6] = b.atlasH
            bandInfoCPU[base + 7] = b.slotPitchX

            bandInfoCPU[base + 8] = b.slotPitchY
            bandInfoCPU[base + 9] = b.totalSlots
            bandInfoCPU[base + 10] = 0
            bandInfoCPU[base + 11] = 0
        }
    }

    private fun writeEntryCPU(entryIndex: Int, atlasIndex: Int, slotIndex: Int, generation: Int, state: Int) {
        val base = entryIndex * 4
        indirectionCPU[base + 0] = atlasIndex
        indirectionCPU[base + 1] = slotIndex
        indirectionCPU[base + 2] = generation
        indirectionCPU[base + 3] = state
    }

    // --- Instance buffer ---

    private fun writeInstanceBuffer(quads: List<VirtualQuadInstance>) {
        val max = min(quads.size, config.maxInstances)
        lastInstanceCount = max
        instanceBuffer.get(write = true).mapAndWrite_bytes {
            clear()
            order(ByteOrder.nativeOrder())
            var written = 0
            for (q in quads) {
                if (written >= max) break
                q.model.get(tmpMat16)
                for (k in 0 until 16) putFloat(tmpMat16[k])
                putInt(q.virtualTexId)
                putInt(q.desiredLod)
                putInt(0)
                putInt(0)
                written++
            }
            flip()
        }
    }

    // --- Decode workers ---

    private fun startDecodeWorkers() {
        val executor = Executors.newFixedThreadPool(config.decodeThreads)
        repeat(config.decodeThreads) {
            executor.submit {
                while (!decodeStop.get()) {
                    val req = decodeQueue.take()
                    val entry = entryIndex(req.virtualTexId, req.bandIndex)
                    statsDecodeStarted.incrementAndGet()
                    if (entryRequestId[entry] != req.requestId) {
                        freeLoadingSlotIfOwned(req.bandIndex, req.slotId, req.virtualTexId)
                        continue
                    }
                    val band = bands[req.bandIndex]

                    val decoded = decodeAndDownsample(req.path, band.slotW, band.slotH, req.bandIndex)
                    if (decoded == null) {
                        println("Failed to decode $req")

                        // If this request was canceled/replaced while decoding, don't touch shared slot state.
                        if (entryRequestId[entry] == req.requestId) {
                            entryStates[entry] = VTState.INVALID.code
                            entrySlots[entry] = -1
                            writeEntryCPU(entry, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0, VTState.INVALID.code)
                            indirectionDirty = true
                            statsDecodeFailed.incrementAndGet()
                        }
                        freeLoadingSlotIfOwned(req.bandIndex, req.slotId, req.virtualTexId)
                        continue
                    }

                    // Canceled while decoding; drop work early.
                    if (entryRequestId[entry] != req.requestId) {
                        MemoryUtil.memFree(decoded)
                        freeLoadingSlotIfOwned(req.bandIndex, req.slotId, req.virtualTexId)
                        continue
                    }

                    val padded = padWithClamp(decoded, band.slotW, band.slotH, band.padding)
                    if (entryRequestId[entry] != req.requestId) {
                        MemoryUtil.memFree(padded)
                        freeLoadingSlotIfOwned(req.bandIndex, req.slotId, req.virtualTexId)
                        continue
                    }
                    if (config.debugCheckDecoded) {
                        val stats = sampleDecodedStats(padded, padded.remaining())
                        if (stats.isSuspicious) {
                            val count = statsDecodeZero.incrementAndGet()
                            if (config.debugLogDecodedAnomalies && count <= config.debugMaxDecodedWarnings) {
                                lprintln(
                                    "warning,vt",
                                    "decoded suspicious pixels vt=${req.virtualTexId} band=${req.bandIndex} " +
                                        "minRGB=${stats.minRGB} maxRGB=${stats.maxRGB} " +
                                        "minA=${stats.minA} maxA=${stats.maxA} avgRGB=${stats.avgRGB} " +
                                        "path=${req.path.fileName}"
                                )
                            }
                        }
                    }
                    readyUploads.add(
                        DecodedUpload(req.virtualTexId, req.bandIndex, req.requestId, req.slotId, padded, padded.remaining())
                    )
                }
            }
        }
    }

    private fun decodeAndDownsample(path: Path, targetW: Int, targetH: Int, bandIndex: Int): ByteBuffer? {
        val useDiskCache = config.thumbnailCacheEnabled && (
            (thumbnailCacheFromBandIndex >= 0 && bandIndex >= thumbnailCacheFromBandIndex) ||
                (thumbnailCacheFromBandIndex < 0 && bandIndex == thumbnailBandIndex)
            )
        if (!useDiskCache) return decodeAndDownsampleNoCache(path, targetW, targetH)

        val thumbPath = thumbnailPathFor(path, targetW, targetH) ?: return decodeAndDownsampleNoCache(path, targetW, targetH)

        // Fast path: try cached thumbnail first.
        decodeAndDownsampleNoCache(thumbPath, targetW, targetH)?.let {
            statsThumbHit.incrementAndGet()
            return it
        }

        // Slow path: serialize generation per thumbnail to avoid duplicate decode+resize work.
        val lockKey = try {
            thumbPath.toAbsolutePath().normalize()
        } catch (_: Throwable) {
            thumbPath
        }
        val lock = thumbnailLocks.computeIfAbsent(lockKey) { Any() }
        synchronized(lock) {
            // Re-check after acquiring the lock in case another worker generated it.
            decodeAndDownsampleNoCache(thumbPath, targetW, targetH)?.let {
                statsThumbHit.incrementAndGet()
                return it
            }

            val decoded = decodeAndDownsampleNoCache(path, targetW, targetH) ?: return null

            val wrote = writeThumbnailJpeg(thumbPath, targetW, targetH, decoded)
            if (wrote) statsThumbWrite.incrementAndGet() else statsThumbWriteFail.incrementAndGet()

            return decoded
        }
    }

    private fun decodeAndDownsampleNoCache(path: Path, targetW: Int, targetH: Int): ByteBuffer? {
        return try {
            MemoryStack.stackPush().use { stack ->
                val w = stack.mallocInt(1)
                val h = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val buf = stbi_load(path.toString(), w, h, channels, 4)
                    ?: return null
                val srcW = w[0]
                val srcH = h[0]

                if (srcW == targetW && srcH == targetH) {
                    // copy to heap buffer we own
                    val out = MemoryUtil.memAlloc(targetW * targetH * 4)
                    val src = buf.duplicate()
                    out.put(src)
                    out.flip()
                    stbi_image_free(buf)
                    out
                } else {
                    val out = MemoryUtil.memAlloc(targetW * targetH * 4)
                    // Use stb_image_resize for native bilinear resizing; fall back to Kotlin if it fails.
                    val ok = resizeRgbaStbir(buf, srcW, srcH, out, targetW, targetH)
                    if (!ok) resizeRgbaBilinear(buf, srcW, srcH, out, targetW, targetH)
                    stbi_image_free(buf)
                    out
                }
            }
        } catch (t: Throwable) {
            lprintln("warning,graphics", "decode failed: ${t.message}")
            null
        }
    }

    private fun thumbnailPathFor(original: Path, targetW: Int, targetH: Int): Path? {
        val parent = original.parent ?: return null
        val dirName = config.thumbnailCacheDirName.ifBlank { ".thumbnails" }
        val thumbDir = parent.resolve(dirName)

        val file = original.fileName?.toString() ?: return null
        // Include the source extension in the cache key to avoid collisions (e.g. foo.jpg vs foo.png).
        val base = file.replace('.', '_')
        val name = "${base}_${targetW}x${targetH}.jpg"
        return thumbDir.resolve(name)
    }

    private fun writeThumbnailJpeg(path: Path, w: Int, h: Int, rgba: ByteBuffer): Boolean {
        return try {
            val parent = path.parent ?: return false
            Files.createDirectories(parent)

            val quality = config.thumbnailCacheJpegQuality.coerceIn(1, 100)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            runCatching { Files.deleteIfExists(tmp) }

            rgba.position(0)
            val ok = stbi_write_jpg(tmp.toString(), w, h, 4, rgba, quality)
            rgba.position(0)
            if (!ok) {
                runCatching { Files.deleteIfExists(tmp) }
                return false
            }

            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Throwable) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun resizeRgbaStbir(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int): Boolean {
        // stb_image_resize uses the buffer's current position as the base address.
        src.position(0)
        dst.position(0)
        val out = stbir_resize_uint8_linear(src, srcW, srcH, 0, dst, dstW, dstH, 0, 4)
        dst.position(0)
        dst.limit(dst.capacity())
        return out != null
    }

    private fun resizeRgbaBilinear(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int) {
        val scaleX = srcW.toFloat() / dstW.toFloat()
        val scaleY = srcH.toFloat() / dstH.toFloat()
        for (y in 0 until dstH) {
            val sy = (y + 0.5f) * scaleY - 0.5f
            var y0 = floor(sy).toInt()
            var y1 = y0 + 1
            val fy = sy - y0
            if (y0 < 0) { y0 = 0; y1 = 0 }
            if (y1 >= srcH) { y1 = srcH - 1; if (y0 >= srcH) y0 = srcH - 1 }

            for (x in 0 until dstW) {
                val sx = (x + 0.5f) * scaleX - 0.5f
                var x0 = floor(sx).toInt()
                var x1 = x0 + 1
                val fx = sx - x0
                if (x0 < 0) { x0 = 0; x1 = 0 }
                if (x1 >= srcW) { x1 = srcW - 1; if (x0 >= srcW) x0 = srcW - 1 }

                val idx00 = (y0 * srcW + x0) * 4
                val idx10 = (y0 * srcW + x1) * 4
                val idx01 = (y1 * srcW + x0) * 4
                val idx11 = (y1 * srcW + x1) * 4

                val dstIndex = (y * dstW + x) * 4
                for (c in 0 until 4) {
                    val c00 = src.get(idx00 + c).toInt() and 0xFF
                    val c10 = src.get(idx10 + c).toInt() and 0xFF
                    val c01 = src.get(idx01 + c).toInt() and 0xFF
                    val c11 = src.get(idx11 + c).toInt() and 0xFF

                    val c0 = c00 + (c10 - c00) * fx
                    val c1 = c01 + (c11 - c01) * fx
                    val cFinal = c0 + (c1 - c0) * fy
                    dst.put(dstIndex + c, cFinal.toInt().coerceIn(0, 255).toByte())
                }
            }
        }
        dst.position(0)
        dst.limit(dst.capacity())
    }

    private fun padWithClamp(src: ByteBuffer, w: Int, h: Int, pad: Int): ByteBuffer {
        val outW = w + pad * 2
        val outH = h + pad * 2
        val out = MemoryUtil.memAlloc(outW * outH * 4)
        for (y in 0 until outH) {
            val sy = (y - pad).coerceIn(0, h - 1)
            for (x in 0 until outW) {
                val sx = (x - pad).coerceIn(0, w - 1)
                val srcIndex = (sy * w + sx) * 4
                val dstIndex = (y * outW + x) * 4
                out.put(dstIndex + 0, src.get(srcIndex + 0))
                out.put(dstIndex + 1, src.get(srcIndex + 1))
                out.put(dstIndex + 2, src.get(srcIndex + 2))
                out.put(dstIndex + 3, src.get(srcIndex + 3))
            }
        }
        out.position(0)
        out.limit(out.capacity())
        MemoryUtil.memFree(src)
        return out
    }

    private data class SampleStats(
        val minRGB: Int,
        val maxRGB: Int,
        val minA: Int,
        val maxA: Int,
        val avgRGB: Int
    ) {
        val isSuspicious: Boolean
            get() = (maxRGB == 0 && maxA == 0) || (minRGB == maxRGB && (minRGB == 0 || minRGB == 255)) ||
                (minA == maxA && minA == 0)
    }

    private fun sampleDecodedStats(buf: ByteBuffer, bytes: Int): SampleStats {
        val limit = min(bytes, buf.capacity())
        if (limit < 4) return SampleStats(0, 0, 0, 0, 0)
        val maxSamples = 2048
        val pixels = limit / 4
        val samples = min(maxSamples, pixels)
        val step = max(4, (limit / samples) and -4) // keep 4-byte alignment

        var minRGB = 255
        var maxRGB = 0
        var minA = 255
        var maxA = 0
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 3 < limit) {
            val r = buf.get(i).toInt() and 0xFF
            val g = buf.get(i + 1).toInt() and 0xFF
            val b = buf.get(i + 2).toInt() and 0xFF
            val a = buf.get(i + 3).toInt() and 0xFF
            if (r < minRGB) minRGB = r
            if (g < minRGB) minRGB = g
            if (b < minRGB) minRGB = b
            if (r > maxRGB) maxRGB = r
            if (g > maxRGB) maxRGB = g
            if (b > maxRGB) maxRGB = b
            if (a < minA) minA = a
            if (a > maxA) maxA = a
            sum += r + g + b
            count++
            i += step
        }
        val avg = if (count > 0) (sum / (count * 3)).toInt() else 0
        return SampleStats(minRGB, maxRGB, minA, maxA, avg)
    }

    // --- Atlas layout ---

    private fun buildBandsAndAtlases(maxDim: Int): Pair<List<LODBandRuntime>, List<AtlasImage>> {
        var atlasBase = 0
        val runtimeBands = mutableListOf<LODBandRuntime>()
        val atlasList = mutableListOf<AtlasImage>()

        val baseTargetSlots = min(config.virtualTexCount, config.maxInstances).coerceAtLeast(1)
        val samplerLimit = config.maxAtlasSamplers

        fun atlasCountFor(band: LODBandConfig, bandSlotsTotal: Int): Int {
            val slotPitchX = band.slotW + band.padding * 2
            val slotPitchY = band.slotH + band.padding * 2
            val maxCols = max(1, maxDim / slotPitchX)
            val maxRows = max(1, maxDim / slotPitchY)
            val maxSlotsPerAtlas = maxCols * maxRows
            val preferred = min(band.preferredAtlasSlots, maxSlotsPerAtlas)
            return ceil(bandSlotsTotal / preferred.toDouble()).toInt().coerceAtLeast(1)
        }

        val requestedBase = config.prefetchBaseBandIndex
        val singlePixelSlots = if (config.singlePixelBandSlots > 0) config.singlePixelBandSlots else baseTargetSlots
        val singlePixelBand = LODBandConfig(
            slotW = 1,
            slotH = 1,
            padding = 0,
            maxSlotsTotal = singlePixelSlots,
            preferredAtlasSlots = singlePixelSlots
        )

        val canAddSinglePixel = if (config.singlePixelBandEnabled) {
            val baseIndexIfSingle = if (requestedBase in 0 until (config.bands.size + 1)) {
                requestedBase
            } else {
                config.bands.size
            }

            var tempAtlasBase = 0
            config.bands.forEachIndexed { index, band ->
                val bandSlotsTotal = if (config.prefetchBaseEnabled && index == baseIndexIfSingle) {
                    max(band.maxSlotsTotal, baseTargetSlots)
                } else band.maxSlotsTotal
                tempAtlasBase += atlasCountFor(band, bandSlotsTotal)
            }
            val singleAtlasCount = atlasCountFor(singlePixelBand, singlePixelSlots)
            (samplerLimit <= 0 || tempAtlasBase + singleAtlasCount <= samplerLimit)
        } else false

        val baseIndexWanted = if (canAddSinglePixel) {
            if (requestedBase in 0 until (config.bands.size + 1)) requestedBase else config.bands.size
        } else {
            if (requestedBase in 0 until config.bands.size) requestedBase else config.bands.lastIndex
        }

        config.bands.forEachIndexed { index, band ->
            val bandSlotsTotal = if (config.prefetchBaseEnabled && index == baseIndexWanted) {
                max(band.maxSlotsTotal, baseTargetSlots)
            } else band.maxSlotsTotal

            val slotPitchX = band.slotW + band.padding * 2
            val slotPitchY = band.slotH + band.padding * 2
            val maxCols = max(1, maxDim / slotPitchX)
            val maxRows = max(1, maxDim / slotPitchY)
            val maxSlotsPerAtlas = maxCols * maxRows

            val preferred = min(band.preferredAtlasSlots, maxSlotsPerAtlas)
            val atlasCount = ceil(bandSlotsTotal / preferred.toDouble()).toInt().coerceAtLeast(1)
            val slotsPerAtlas = ceil(bandSlotsTotal / atlasCount.toDouble()).toInt().coerceAtMost(maxSlotsPerAtlas)

            val grid = computeAtlasGrid(slotsPerAtlas, maxCols, maxRows)
            val cols = grid.cols
            val rows = grid.rows
            val atlasW = cols * slotPitchX
            val atlasH = rows * slotPitchY

            val runtime = LODBandRuntime(
                index = index,
                slotW = band.slotW,
                slotH = band.slotH,
                padding = band.padding,
                slotPitchX = slotPitchX,
                slotPitchY = slotPitchY,
                cols = cols,
                rows = rows,
                atlasW = atlasW,
                atlasH = atlasH,
                slotsPerAtlas = cols * rows,
                atlasCount = atlasCount,
                totalSlots = atlasCount * cols * rows,
                atlasBaseIndex = atlasBase
            )
            runtimeBands.add(runtime)

            repeat(atlasCount) {
                val img = createAtlasImage(atlasW, atlasH)
                val view = ImageView(renderer.device, img, ImageView.ImageViewData()
                    .format(img.format)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevels(1)
                )
                atlasList.add(AtlasImage(img, view, atlasW, atlasH, cols, rows, slotPitchX, slotPitchY, atlasBase))
                atlasBase++
            }
        }

        if (config.singlePixelBandEnabled) {
            if (canAddSinglePixel) {
                val bandSlotsTotal = if (config.prefetchBaseEnabled && baseIndexWanted == config.bands.size) {
                    max(singlePixelBand.maxSlotsTotal, baseTargetSlots)
                } else singlePixelBand.maxSlotsTotal

                val slotPitchX = singlePixelBand.slotW + singlePixelBand.padding * 2
                val slotPitchY = singlePixelBand.slotH + singlePixelBand.padding * 2
                val maxCols = max(1, maxDim / slotPitchX)
                val maxRows = max(1, maxDim / slotPitchY)
                val maxSlotsPerAtlas = maxCols * maxRows

                val preferred = min(singlePixelBand.preferredAtlasSlots, maxSlotsPerAtlas)
                val atlasCount = ceil(bandSlotsTotal / preferred.toDouble()).toInt().coerceAtLeast(1)
                val slotsPerAtlas = ceil(bandSlotsTotal / atlasCount.toDouble()).toInt().coerceAtMost(maxSlotsPerAtlas)

                val grid = computeAtlasGrid(slotsPerAtlas, maxCols, maxRows)
                val cols = grid.cols
                val rows = grid.rows
                val atlasW = cols * slotPitchX
                val atlasH = rows * slotPitchY

                val runtime = LODBandRuntime(
                    index = runtimeBands.size,
                    slotW = singlePixelBand.slotW,
                    slotH = singlePixelBand.slotH,
                    padding = singlePixelBand.padding,
                    slotPitchX = slotPitchX,
                    slotPitchY = slotPitchY,
                    cols = cols,
                    rows = rows,
                    atlasW = atlasW,
                    atlasH = atlasH,
                    slotsPerAtlas = cols * rows,
                    atlasCount = atlasCount,
                    totalSlots = atlasCount * cols * rows,
                    atlasBaseIndex = atlasBase
                )
                runtimeBands.add(runtime)

                repeat(atlasCount) {
                    val img = createAtlasImage(atlasW, atlasH)
                    val view = ImageView(renderer.device, img, ImageView.ImageViewData()
                        .format(img.format)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevels(1)
                    )
                    atlasList.add(AtlasImage(img, view, atlasW, atlasH, cols, rows, slotPitchX, slotPitchY, atlasBase))
                    atlasBase++
                }
            } else if (samplerLimit > 0) {
                lprintln("info,vt", "Skipping single-pixel LOD: atlas sampler limit=$samplerLimit exceeded")
            }
        }

        return runtimeBands to atlasList
    }

    private fun computeAtlasGrid(slotsPerAtlas: Int, maxCols: Int, maxRows: Int): AtlasGrid {
        val target = max(1, slotsPerAtlas)
        var cols = min(maxCols, ceil(sqrt(target.toDouble())).toInt())
        var rows = ceil(target.toDouble() / cols).toInt()
        if (rows > maxRows) {
            cols = maxCols
            rows = maxRows
        }
        return AtlasGrid(cols, rows)
    }

    private fun createAtlasImage(width: Int, height: Int): Image {
        val imageData = Image.ImageData()
            .width(width)
            .height(height)
            .format(VK_FORMAT_R8G8B8A8_SRGB)
            .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
            .mipLevels(1)
        return Image(renderer.device, imageData)
    }

    data class AtlasImage(
        val image: Image,
        val view: ImageView,
        val width: Int,
        val height: Int,
        val cols: Int,
        val rows: Int,
        val slotPitchX: Int,
        val slotPitchY: Int,
        val atlasIndex: Int
    ) : HasImageView {
        override val imageView: ImageView get() = view
        var currentLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED
    }

    private fun slotAtlasIndex(bandIndex: Int, slotId: Int): Int {
        val band = bands[bandIndex]
        val atlasOffset = slotId / band.slotsPerAtlas
        return band.atlasBaseIndex + atlasOffset
    }

    private fun slotIndexInAtlas(bandIndex: Int, slotId: Int): Int {
        val band = bands[bandIndex]
        return slotId % band.slotsPerAtlas
    }

    private fun entryIndex(virtualTexId: Int, bandIndex: Int): Int {
        return virtualTexId * bands.size + bandIndex
    }

    private fun logStats(frame: Long) {
        if (!config.enableLogging) return
        val every = config.logEveryNFrames
        if (every <= 0) return
        if (frame % every.toLong() != 0L || frame == lastLogFrame) return

        val req = statsRequests.get()
        val up = statsUploads.get()
        val pub = statsPublishes.get()
        val decS = statsDecodeStarted.get()
        val decF = statsDecodeFailed.get()
        val allocF = statsAllocFailed.get()
        val miss = statsResolveMiss.get()
        val decZ = statsDecodeZero.get()
        val th = statsThumbHit.get()
        val tw = statsThumbWrite.get()
        val twf = statsThumbWriteFail.get()
        val canc = statsCanceled.get()

        val dReq = req - lastRequests
        val dUp = up - lastUploads
        val dPub = pub - lastPublishes
        val dDecS = decS - lastDecodeStarted
        val dDecF = decF - lastDecodeFailed
        val dAllocF = allocF - lastAllocFailed
        val dMiss = miss - lastResolveMiss
        val dDecZ = decZ - lastDecodeZero
        val dTh = th - lastThumbHit
        val dTw = tw - lastThumbWrite
        val dTwf = twf - lastThumbWriteFail
        val dCanc = canc - lastCanceled

        lastLogFrame = frame
        lastRequests = req
        lastUploads = up
        lastPublishes = pub
        lastDecodeStarted = decS
        lastDecodeFailed = decF
        lastAllocFailed = allocF
        lastResolveMiss = miss
        lastDecodeZero = decZ
        lastThumbHit = th
        lastThumbWrite = tw
        lastThumbWriteFail = twf
        lastCanceled = canc

        val bandSummary = bands.indices.joinToString(" | ") { i ->
            val total = bands[i].totalSlots
            var res = 0
            var load = 0
            val slots = slotStates[i]
            for (s in slots) {
                if (s == SlotState.RESIDENT.code) res++
                else if (s == SlotState.LOADING.code || s == SlotState.UPLOADING.code) load++
            }
            "L$i res=$res load=$load/$total"
        }

        lprintln(
            "info,vt,upload",
            "vt frame=$frame vis=$visibleTexCount/${config.virtualTexCount} miss=$missingVisibleCount req+$dReq up+$dUp pub+$dPub dec+$dDecS fail+$dDecF cancel+$dCanc allocFail+$dAllocF thumb(h+$dTh w+$dTw f+$dTwf) zero+$dDecZ miss+$dMiss " +
                "q(decode=${decodeQueue.size}, ready=${readyUploads.size}, pending=${pendingBatches.size}) | $bandSummary"
        )
    }

    private fun align4(x: Long): Long = (x + 3L) and -4L

    private data class UploadJob(
        val bandIndex: Int,
        val atlasIndex: Int,
        val slotIndex: Int,
        val bufferOffset: Long,
        val bytes: Int,
        val virtualTexId: Int,
        val slotId: Int,
        val requestId: Int
    )

    companion object {
        const val INSTANCE_STRIDE_BYTES = 80

        fun defaultBands(): List<LODBandConfig> = listOf(
            LODBandConfig(1920, 1080, padding = 4, maxSlotsTotal = 128, preferredAtlasSlots = 64),
            LODBandConfig(480, 270, padding = 4, maxSlotsTotal = 512, preferredAtlasSlots = 256),
            LODBandConfig(120, 72, padding = 2, maxSlotsTotal = 2048, preferredAtlasSlots = 1024),
            LODBandConfig(32, 20, padding = 2, maxSlotsTotal = 8192, preferredAtlasSlots = 2048),
            LODBandConfig(8, 8, padding = 2, maxSlotsTotal = 16384, preferredAtlasSlots = 4096)
        )
    }
}
