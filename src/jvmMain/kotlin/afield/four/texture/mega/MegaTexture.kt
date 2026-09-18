package afield.four.texture.mega

import afield.Table
import afield.ToHTML
import afield.four.Renderer
import afield.four.buffers.BufferParameters
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.VulkanBuffer
import afield.four.buffers.buffer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.BeforeTarget
import afield.four.modules.Introspected
import afield.four.modules.ModulePointer
import afield.four.modules.Parameters
import afield.four.modules.before
import afield.four.modules.graphicsHostReadBarrier
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.Image
import afield.four.texture.ImageView
import afield.four.texture.TextureSampler
import afield.four.texture.image_layout_transition
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkExtent3D
import org.lwjgl.vulkan.VkImageSubresourceLayers
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkOffset3D
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * How a [MegaTexture] is shaped: how big its pages are, how much GPU memory it may hold, how hard
 * it decodes, and what its shader-visible names and bindings are.
 *
 * Everything here has a default that works for a directory of a few thousand photographs. The
 * knobs that matter for a *million* are [levels] (how blurry the coarsest version may be),
 * [atlasEdge] and [layersPerLevel] (the residency budget) and [feedbackEveryNFrames].
 */
class MegaTextureConfig {
    /**
     * Edge of a level-0 page, for a *square* page ladder. Level `L` is then `minPageSize shl L`.
     *
     * The square shorthand for [sourcePage], which is what to reach for when the images have a
     * shape. Ignored once [sourcePage] has been called.
     */
    var minPageSize: Int = 8

    /** How many levels exist. The default 7 gives 8, 16, 32, 64, 128, 256 and 512-pixel pages. */
    var levels: Int = 7

    private var explicitTopWidth: Int = 0
    private var explicitTopHeight: Int = 0

    /**
     * Shape the pages like the source, and make the top level exactly this size.
     *
     * The one call that matters for film, where nothing is square. A 1920×798 frame in a square
     * 2048 page is 63% padding — paid on every byte of atlas, at every level — so a set of one
     * shape should say so: `sourcePage(1920, 798)` gives a top page of exactly that, and each
     * level down is it halved (960×399, 480×200, 240×100, …). Full resolution with no waste, and
     * about two and a half times as many pages resident for the same memory.
     *
     * A set of *mixed* shapes still works: each picture is fitted into the page's aspect and the
     * page table publishes the rectangle it actually used, so the difference is letterboxing
     * rather than distortion. Pick the aspect most of them share.
     */
    fun sourcePage(width: Int, height: Int): MegaTextureConfig {
        require(width > 0 && height > 0) { "sourcePage needs a positive size, not ${width}x$height" }
        explicitTopWidth = width
        explicitTopHeight = height
        return this
    }

    /** Top-level page width: [sourcePage]'s, or the square ladder's `minPageSize shl (levels-1)`. */
    val topPageWidth: Int
        get() = if (explicitTopWidth > 0) explicitTopWidth else minPageSize shl (levels - 1)

    /** Top-level page height. Equal to [topPageWidth] unless [sourcePage] said otherwise. */
    val topPageHeight: Int
        get() = if (explicitTopHeight > 0) explicitTopHeight else minPageSize shl (levels - 1)

    /** Edge of one atlas array layer, in pixels. Every level packs whole pages into these. */
    var atlasEdge: Int = 2048

    /** Array layers given to each level above the base. More layers, more pages resident. */
    var layersPerLevel: Int = 2

    /**
     * Ceiling on layers for level 0, which otherwise gets however many it takes to hold *every*
     * image at once.
     *
     * Level 0 being complete is what makes the wall never show a hole: it is the fallback the page
     * table publishes for anything whose wanted level has not arrived. At the default 8-pixel
     * pages, one 2048 layer holds 65536 images, so a million needs 16 layers and 256 MiB.
     */
    var maxBaseLevelLayers: Int = 64

    var decodeThreads: Int = max(2, Runtime.getRuntime().availableProcessors() - 1)

    /** Queued requests beyond this are dropped worst-first at the end of a feedback pass. */
    var maxQueuedRequests: Int = 1 shl 16

    /** Ceiling on page bytes copied into the atlas per frame. Keeps a burst off the frame time. */
    var uploadBytesPerFrame: Int = 8 shl 20

    /**
     * How often the GPU's request buffer is read and cleared, in frames. `0` means "choose from
     * the image count".
     *
     * Not clearing is not the same as not reading: the fragment shader keeps `atomicMax`-ing into
     * the same per-frame buffer, so a larger number gives a *union* of several frames' requests
     * rather than a sample of one. That is usually what you want anyway — a page at the edge of a
     * sweeping camera stays requested — and the cost avoided is a linear scan of one `uint` per
     * image.
     */
    var feedbackEveryNFrames: Int = 0

    /** A request not re-asked for in this many frames is abandoned rather than decoded. */
    var staleFrames: Int = 45

    /** Base-level requests submitted per frame while warming up. */
    var warmUpRequestsPerFrame: Int = 4096

    /** Warm-up pauses while the decode queue is deeper than this, so it cannot outrun the workers. */
    var warmUpQueueTarget: Int = 8192

    /** Warm-up also pauses while this many decoded pages are waiting for an upload slot. */
    var warmUpReadyTarget: Int = 8192

    /**
     * Backstop: after this many frames an outstanding request is assumed lost regardless.
     *
     * Every path a request can take out of the system without arriving now reports itself (see
     * [MegaTextureDecoder.drainAbandoned]), so this should never fire. It is set long enough not to
     * matter if it does — the failure it guards against is an image marked as asked-for forever
     * and so never asked for again, which is worth one duplicate decode every thirty seconds to
     * rule out.
     */
    var pendingExpiryFrames: Int = 1800

    /** Slots not sampled for this many frames may be evicted. */
    var evictionGraceFrames: Int = 20

    /**
     * Frames a newly arrived page takes to fade in over the one it replaces. 0 to switch instantly.
     *
     * Only *upgrades* fade. Losing a level to eviction cannot: by the time the page table stops
     * naming a page, its pixels are already somebody else's, and a fade needs both halves to still
     * be there. That asymmetry is not as visible as it sounds — an image refines while you are
     * looking at it and coarsens as it leaves the screen.
     */
    var crossfadeFrames: Int = 10

    /** Evictions started per level per frame when a level is under pressure. */
    var evictionsPerFramePerLevel: Int = 16

    /** Highest level written to the on-disk page cache. Above this, pages are re-decoded. */
    var cacheMaxLevel: Int = 5

    /** A level whose fully-populated cache file would exceed this is not cached at all. */
    var cacheMaxVirtualBytes: Long = 32L shl 30

    /** Where the catalog and page cache live. Null means [MegaTextureCatalog.defaultCacheRoot]. */
    var cacheRoot: Path? = null

    var descriptorSet: Int = 0
    var atlasBinding: Int = 56
    var pageTableBinding: Int = 57
    var feedbackBinding: Int = 58

    /** GLSL names. Change them if they collide with something already in your shader. */
    var atlasName: String = "megaAtlas"
    var pageTableName: String = "MegaTexturePageTable"
    var feedbackName: String = "MegaTextureFeedback"

    var name: String = "megaTexture"
}

/**
 * What a [MegaTexture] is doing right now — for `println`, an output card, or a test.
 *
 * Implements [ToHTML], so `display(mega.stats().h)` in a box gives the level table rather than the
 * reflected field list `.h` would otherwise produce — which elided everything past the eighth
 * field, and the per-level residency is the whole point of looking.
 */
data class MegaTextureStats(
    val imageCount: Int,
    val frame: Long,
    val wantedImages: Int,
    val residentPages: IntArray,
    val pageCapacity: IntArray,
    val publishedAtLevel: IntArray,
    val unpublished: Int,
    val queueDepth: Int,
    val inFlightDecodes: Int,
    val decodedFromCache: Long,
    val decodedFromSource: Long,
    val decodeFailures: Long,
    val cancelled: Long,
    val uploadedPagesThisFrame: Int,
    val uploadBytesThisFrame: Int,
    val effectiveMaxLevel: Int,
    /** Images with no base-level page yet. */
    val warmUpRemaining: Int,
    /** Page width per level, so the table can name each level by the page it actually holds. */
    val pageWidths: IntArray,
    /** Page height per level. Equal to [pageWidths] for a square ladder. */
    val pageHeights: IntArray
) : ToHTML {

    /**
     * One row per level, because that is the shape of the question being asked: how sharp is the
     * wall, and is any level out of pages. The ceiling is marked rather than stated separately —
     * a level above it is not being served, and seeing which one is more useful than the number.
     */
    override fun toHTML(): String {
        val levels = residentPages.size
        val table = Table(listOf("level", "page", "resident", "of", "showing"))
        table.title = "megaTexture — $imageCount images, frame $frame"
        table.note = buildString {
            append("$wantedImages asked for a level this frame; ")
            append(if (unpublished == 0) "every image has a page" else "$unpublished have no page yet")
            if (warmUpRemaining > 0) append(" ($warmUpRemaining base pages still to come)")
            append(". Ceiling is level $effectiveMaxLevel. ")
            append("Decoded $decodedFromSource from source, $decodedFromCache from cache")
            if (decodeFailures > 0) append(", $decodeFailures FAILED")
            if (cancelled > 0) append(", $cancelled cancelled")
            append(". Queue $queueDepth, $inFlightDecodes in flight. ")
            append("Uploaded $uploadedPagesThisFrame pages / $uploadBytesThisFrame bytes this frame.")
        }
        for (level in 0 until levels) {
            table.row(
                if (level > effectiveMaxLevel) "$level ·" else "$level",
                "${pageWidths[level]}x${pageHeights[level]}",
                residentPages[level],
                pageCapacity[level],
                publishedAtLevel[level]
            )
        }
        return table.toHTML()
    }

    override fun toString(): String = buildString {
        append("megaTexture[$imageCount images, frame $frame] ")
        append("wanted=$wantedImages unpublished=$unpublished maxLevel=$effectiveMaxLevel")
        if (warmUpRemaining > 0) append(" warmUpRemaining=$warmUpRemaining")
        append("\n")
        for (level in residentPages.indices) {
            append("  level $level: ${residentPages[level]}/${pageCapacity[level]} pages, ")
            append("${publishedAtLevel[level]} images shown here\n")
        }
        append("  decode: queue=$queueDepth inFlight=$inFlightDecodes ")
        append("cache=$decodedFromCache source=$decodedFromSource ")
        append("failed=$decodeFailures cancelled=$cancelled\n")
        append("  upload: $uploadedPagesThisFrame pages / $uploadBytesThisFrame bytes this frame")
    }
}

/**
 * A GPU-driven virtual texture over an arbitrarily large set of images.
 *
 * ## What is virtual about it
 *
 * The CPU does not know where anything is on screen, how big it looks, or whether it is visible.
 * The fragment shader does: it has the UV derivatives, so it knows the screen footprint of the
 * image it is shading, and it writes the resolution it wants into a feedback buffer with
 * `atomicMax`. One frame later the CPU reads that buffer, and *that* is the entire input to its
 * decode, residency and eviction decisions.
 *
 * This is the property the previous implementation in this repository did not have. It computed
 * screen footprints on the CPU from a list of quads it was handed, which meant scene knowledge had
 * to be kept in agreement on both sides — and the two drifted, so the wrong resolutions loaded.
 * Here there is one source of truth, the shader, and the CPU is a cache that serves it.
 *
 * ## Residency
 *
 * One `sampler2DArray` holds every level. A layer belongs to exactly one level, so a page's size
 * is implied by its layer and the shader never has to index a sampler array dynamically (which
 * would need `nonuniformEXT` and descriptor indexing, neither of which this needs).
 *
 * The page table publishes one page per image to *sample* — the highest-resolution one currently
 * resident — and, alongside it, the page that one is fading in over. The shader samples, blends,
 * and separately requests what it wanted, so there is no search in the shader and no way for the
 * two to disagree. Level 0 is sized to hold every image, so after the first pass over the
 * directory there is always *something* to draw; before that, and for anything that fails to
 * decode, the table carries the image's mean colour — which is also what a first page fades out
 * of. See [MegaTextureConfig.crossfadeFrames] for what the second entry costs and what it buys.
 *
 * ## Why uploads are recorded, not submitted
 *
 * Page copies go into the frame's own graphics command buffer from a `before` target, ahead of the
 * render pass. That puts the transfer and the draw that reads it in the same submission in the
 * right order, with one barrier pair per frame and no second queue, no timeline semaphore and no
 * staging fence to get wrong. The staging ring is indexed by frame-in-flight, which the renderer
 * has already fenced before recording.
 *
 * A slot is never reused in the frame it is evicted in. Frames still in flight have page tables
 * that point at it, so the pixels it holds must stay untouched for [Renderer.numImages] more
 * frames; eviction only *starts* the clock, and allocation takes the slot when it matures.
 */
class MegaTexture(
    val renderer: Renderer,
    val catalog: MegaTextureCatalog,
    val config: MegaTextureConfig = MegaTextureConfig()
) : AutoCloseable, HasImageViewArray, HasImageView {

    val imageCount: Int = catalog.count
    val levels: Int = config.levels.coerceIn(1, 15)
    private val numImages = renderer.numImages

    // --- atlas geometry -------------------------------------------------------------------

    /**
     * Where every page lives, and how a page table entry is packed. Pure arithmetic, no device —
     * see [MegaTextureLayout] for why it is a separate object.
     */
    val layout = MegaTextureLayout(
        imageCount = imageCount,
        levels = levels,
        atlasEdge = config.atlasEdge,
        layersPerLevel = config.layersPerLevel,
        maxBaseLevelLayers = config.maxBaseLevelLayers,
        topPageWidth = config.topPageWidth,
        topPageHeight = config.topPageHeight
    )

    private val pageCapacity get() = layout.pageCapacity
    private val identityLevel get() = layout.identityLevel
    val totalLayers: Int get() = layout.totalLayers
    val atlasImage: Image
    val atlasView: ImageView
    val sampler: TextureSampler

    // --- per-slot state -------------------------------------------------------------------

    private val slotImage: Array<IntArray>
    private val slotLastUsed: Array<LongArray>
    private val slotFreeAt: Array<LongArray>
    private val slotCursor = IntArray(levels)

    /**
     * The last frame each level turned an arrived page away for want of a slot.
     *
     * A level that is full will keep being asked for, keep decoding, and keep throwing the result
     * away — in the stress run that was 108,840 cache reads for 1639 images. Eviction needs a few
     * frames to mature a slot, so a level that just said no is not asked again until it has had
     * them.
     */
    private val noCapacityFrame = LongArray(levels) { Long.MIN_VALUE / 4 }
    private val slotOfImage: Array<HashMap<Int, Int>?>
    private val residentPages = IntArray(levels)

    // --- per-image state ------------------------------------------------------------------

    private val residentMask = IntArray(imageCount)
    private val publishedLevel = ByteArray(imageCount) { -1 }
    private val publishedSlot = IntArray(imageCount) { -1 }
    private val averageColor = IntArray(imageCount)
    private val wantLevel = ByteArray(imageCount) { -1 }
    private val wantFrame = IntArray(imageCount) { Int.MIN_VALUE }
    private val pendingLevel = ByteArray(imageCount) { -1 }
    private val pendingFrame = IntArray(imageCount) { Int.MIN_VALUE }

    // --- crossfade ------------------------------------------------------------------------
    //
    // The page a fading image is fading *out* of, held separately from the resident bookkeeping:
    // the outgoing page is still resident at its own level (upgrading never releases the coarser
    // page), so this is a pointer into that, not a second claim on the slot. `fadeStart` doubles
    // as the membership test for `fading`, which is why it is Int.MIN_VALUE rather than 0 when
    // absent.

    private val outgoingLevel = ByteArray(imageCount) { -1 }
    private val outgoingSlot = IntArray(imageCount) { -1 }
    private val fadeStart = IntArray(imageCount) { Int.MIN_VALUE }
    private val fading = ArrayList<Int>()

    // --- buffers --------------------------------------------------------------------------

    private val pageTableParameters: BufferParameters
    private val pageTable: FrameBufferGroup
    private val feedbackParameters: BufferParameters
    private val feedback: FrameBufferGroup
    private val staging: List<VulkanBuffer>

    /** One bitset per frame-in-flight: entries this buffer has not caught up with yet. */
    private val dirtyPerFrame: Array<LongArray>

    // --- decode ---------------------------------------------------------------------------

    private val pyramid: MegaTexturePyramidCache
    private val decoder: MegaTextureDecoder

    @Volatile
    private var generation: Long = 0

    @Volatile
    private var frame: Long = 0

    private var atlasInitialized = false
    private var wantedImages = 0
    private var uploadedPagesThisFrame = 0
    private var uploadBytesThisFrame = 0
    private var effectiveMaxLevel = levels - 1
    private var warmLevel = -1
    private var warmCursor = 0

    /** A decoded page that did not fit last frame's upload budget. See [uploadReadyPages]. */
    private var carriedPage: MegaDecodedPage? = null
    private val feedbackInterval: Int
    private val requestScratch = ArrayList<Int>(4096)

    override val imageViewArray: List<HasImageView> get() = listOf(atlasView)
    override val imageView: ImageView get() = atlasView

    /** The `{{ mega.… }}` slots this system exposes to a box's shaders. */
    val template: Map<String, Any> get() = megaTextureTemplate(this)

    init {
        require(imageCount > 0) { "a MegaTexture needs at least one image; the catalog is empty" }

        slotImage = Array(levels) { level -> IntArray(pageCapacity[level]) { -1 } }
        slotLastUsed = Array(levels) { level -> LongArray(pageCapacity[level]) }
        slotFreeAt = Array(levels) { level -> LongArray(pageCapacity[level]) }
        slotOfImage = Array(levels) { level -> if (identityLevel[level]) null else HashMap() }

        atlasImage = Image(
            renderer.device,
            Image.ImageData()
                .width(config.atlasEdge)
                .height(config.atlasEdge)
                .arrayLayers(totalLayers)
                .format(VK_FORMAT_R8G8B8A8_SRGB)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                .mipLevels(1)
                .debugName("${config.name} atlas")
        )
        atlasView = ImageView(
            renderer.device,
            atlasImage,
            ImageView.ImageViewData()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .format(VK_FORMAT_R8G8B8A8_SRGB)
                .viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                .layerCount(totalLayers)
                .mipLevels(1)
                .debugName("${config.name} atlas view")
        )
        // Explicit-LOD sampling only (each level *is* a mip), so there is nothing for a mip mode
        // or anisotropy to do here.
        sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)

        pageTableParameters = buffer(name = config.pageTableName) {
            size = imageCount.toLong() * MegaTextureLayout.ENTRY_STRIDE_BYTES
            perFrame = true
            storage()
            hosted()
        }
        pageTable = pageTableParameters.toBuffer(renderer)

        feedbackParameters = buffer(name = config.feedbackName) {
            size = imageCount.toLong() * 4
            perFrame = true
            storage()
            streamReads()
        }
        feedback = feedbackParameters.toBuffer(renderer)

        // At least one of the largest page, whatever the per-frame budget is: uploadReadyPages has
        // to be able to stage a single oversized page rather than stall on it.
        val stagingBytes = max(config.uploadBytesPerFrame, layout.pageBytes(levels - 1))
        staging = (0 until numImages).map {
            VulkanBuffer(
                renderer,
                stagingBytes.toLong(),
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or
                        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            )
        }

        dirtyPerFrame = Array(numImages) { LongArray((imageCount + 63) / 64) }

        pyramid = MegaTexturePyramidCache(
            cacheDir = catalog.cacheDir,
            imageCount = imageCount,
            layout = layout,
            maxLevelCached = config.cacheMaxLevel,
            maxVirtualBytes = config.cacheMaxVirtualBytes
        )
        if (catalog.changedIndices.isNotEmpty()) pyramid.invalidate(catalog.changedIndices)

        decoder = MegaTextureDecoder(
            catalog = catalog,
            cache = pyramid,
            layout = layout,
            threads = config.decodeThreads,
            stillWanted = ::stillWanted
        )

        feedbackInterval = when {
            config.feedbackEveryNFrames > 0 -> config.feedbackEveryNFrames
            imageCount > 200_000 -> 4
            else -> 1
        }

        // Nothing is resident and nothing has been decoded, so every image starts as mid grey.
        // The first level-0 pass replaces these with real mean colours.
        averageColor.fill(0xff808080.toInt())
        for (index in 0 until imageCount) markDirty(index)

        lprintln(
            "info,megatexture",
            "megaTexture '${config.name}': $imageCount images, $levels levels " +
                    "(${layout.pageWidth(0)}x${layout.pageHeight(0)}.." +
                    "${layout.topPageWidth}x${layout.topPageHeight}), " +
                    "$totalLayers atlas layers of ${config.atlasEdge}² " +
                    "(${layout.atlasBytes shr 20} MiB), " +
                    "capacities ${pageCapacity.toList()}, feedback every $feedbackInterval frames"
        )
    }

    // ------------------------------------------------------------------------------------------
    // Shader-visible facts. The template and the tests both read these rather than recomputing.
    // ------------------------------------------------------------------------------------------

    val atlasEdge: Int get() = config.atlasEdge
    fun pageWidth(level: Int): Int = layout.pageWidth(level)
    fun pageHeight(level: Int): Int = layout.pageHeight(level)
    fun capacityOf(level: Int): Int = pageCapacity[level]
    fun isIdentityLevel(level: Int): Boolean = identityLevel[level]

    // ------------------------------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------------------------------

    /**
     * Point a module's atlas sampler, page table and feedback buffer at this system.
     *
     * Call it inside the `moduleHere { }` lambda: the resolvers have to be in place before the
     * `Module` is built, because that is when introspection asks what each binding is.
     */
    fun bind(parameters: Parameters): Parameters {
        val previousTexture = parameters.textureResolver
        parameters.textureResolver = { texture ->
            if (texture.name == config.atlasName) this to sampler else previousTexture(texture)
        }
        val previousSsbo = parameters.ssboResolver
        parameters.ssboResolver = { requested ->
            when (requested.name) {
                config.pageTableName -> pageTableParameters
                config.feedbackName -> feedbackParameters
                else -> previousSsbo(requested)
            }
        }
        val previousSizer = parameters.ssboSizer
        parameters.ssboSizer = { ubo: Introspected.UBO ->
            when (ubo.name) {
                config.pageTableName -> imageCount * MegaTextureLayout.ENTRY_STRIDE_BYTES
                config.feedbackName -> imageCount * 4
                else -> previousSizer(ubo)
            }
        }
        return parameters
    }

    /** The per-frame work, as a target to hang on a module ahead of its render pass. */
    fun beforeTarget(): BeforeTarget = before(::frame)

    /**
     * Make the shader's feedback writes readable by the host once the render pass has ended.
     *
     * A fence signal carries an implicit host-domain dependency, and the renderer waits on frame
     * `n - numImages`'s fence before recording frame `n`, so in principle the read in [frame] is
     * already ordered. This is the explicit barrier for it anyway — the same one `ShaderFieldProbe`
     * takes for the same reason — because a `null` buffer read produces no error, just a wall that
     * never sharpens, which is precisely the failure mode that is hardest to attribute.
     *
     * It is recorded from inside the render pass instance, where image and buffer barriers are not
     * allowed, so [graphicsHostReadBarrier] defers it to `flushDeferredGraphicsHostReadBarriers`
     * after `vkCmdEndRendering`.
     */
    fun afterTarget(): Target = object : Target {
        override fun render(command: CommandBuffer, index: Int) {
            graphicsHostReadBarrier(command, feedback.getAt(index, write = false))
        }
    }

    /** Register [beforeTarget] and [afterTarget] on [module] under [name]. */
    fun attach(module: ModulePointer, name: String = config.name): MegaTexture {
        module += name to beforeTarget()
        module += name to afterTarget()
        return this
    }

    // ------------------------------------------------------------------------------------------
    // Warm-up
    // ------------------------------------------------------------------------------------------

    /**
     * Ask for a base-level page for every image, so the wall fills in without being looked at.
     *
     * This is the "loading a million JPEGs takes a while" pass, and it is deliberately the only
     * bulk pass there is: every higher level arrives on demand. On a warm cache it is a sequential
     * read of `level0.bin`; cold, it is one full decode per file spread over the decode threads,
     * which also populates every other cacheable level for free.
     *
     * It does not enqueue anything here. A million requests submitted at once would be ten times
     * the queue's cap, so [MegaTextureDecoder.trimTo] would throw most of them away on the first
     * feedback frame and the wall would stay grey in patches forever. Instead this sets a cursor
     * that [pumpWarmUp] advances a few thousand at a time, which also keeps warm-up behind
     * anything the shader has actually asked for: feedback requests score far above these.
     */
    fun warmBaseLevel(level: Int = 0): MegaTexture {
        warmLevel = level.coerceIn(0, levels - 1)
        warmCursor = 0
        return this
    }

    /** True while the base level is not yet complete. */
    val warmingUp: Boolean get() = warmLevel >= 0 && residentPages[warmLevel] < imageCount

    /**
     * Submit the next slice of the warm-up, if the decoder has room.
     *
     * Gated on queue depth rather than on a per-frame count alone, so a cold cache (where each
     * request is a full JPEG decode) does not build a backlog that outruns the workers, while a
     * warm one (a `pread` per page) drains as fast as the disk allows.
     */
    private fun pumpWarmUp() {
        if (!warmingUp) return
        // Gate on both ends of the decoder: a deep request queue means the workers are behind, and
        // a deep *ready* queue means the frame's upload budget is behind. Either way, asking for
        // more base-level pages would only grow native memory that nothing can consume yet.
        if (decoder.queueDepth > config.warmUpQueueTarget) return
        if (decoder.readyCount > config.warmUpReadyTarget) return
        var submitted = 0
        while (warmCursor < imageCount && submitted < config.warmUpRequestsPerFrame) {
            val index = warmCursor++
            // Residency at the warm level, not "is published at all". An image that got a level-4
            // page from feedback first still needs its base page: that page is the fallback
            // `republish` reaches for when the level-4 slot is evicted, and without it the image
            // goes back to a flat colour. Skipping on publication left holes in the one level the
            // design promises is complete, and they only appeared once eviction started.
            if ((residentMask[index] and (1 shl warmLevel)) != 0) continue
            if (alreadyRequested(index, warmLevel)) continue
            // Generation 0, not the current one: a warm-up request has no feedback behind it, so
            // `stillWanted` must not cancel it for the absence of any.
            //
            // A slightly descending score keeps the queue draining in catalog order rather than in
            // whatever order the priority queue's ties happen to resolve.
            decoder.request(index, warmLevel, -index.toFloat() / max(1, imageCount), generation = 0L)
            noteRequested(index, warmLevel)
            submitted++
        }
        // Sweep again rather than stopping at the end of the catalog. The cursor is the only thing
        // that guarantees the base level ends up complete, and a single pass cannot: on the first
        // frame every visible image is already marked pending by feedback, so the pass skips it,
        // and if that request is then cancelled or trimmed the image is never asked for again.
        if (warmCursor >= imageCount && residentPages[warmLevel] < imageCount) warmCursor = 0
    }

    // ------------------------------------------------------------------------------------------
    // Per-frame
    // ------------------------------------------------------------------------------------------

    /**
     * One frame of cache work, recorded into [command] ahead of the render pass.
     *
     * Order matters and is not arbitrary: the feedback buffer for [imageIndex] is read *before*
     * anything is cleared or uploaded, because it is the completed result of the frame
     * [Renderer.numImages] ago; then eviction is started for levels under pressure; then finished
     * pages are uploaded; then this frame's page table is brought up to date with everything that
     * has changed since it was last visited.
     */
    fun frame(command: CommandBuffer, imageIndex: Int) {
        frame = renderer.frameCounter
        uploadedPagesThisFrame = 0
        uploadBytesThisFrame = 0

        ensureAtlasLayout(command)
        decoder.drainAbandoned(::forgetRequest)
        if (frame % feedbackInterval == 0L) collectFeedback(imageIndex)
        pumpWarmUp()
        startEvictions()
        uploadReadyPages(command, imageIndex)
        advanceFades()
        publishPageTable(imageIndex)
    }

    private fun ensureAtlasLayout(command: CommandBuffer) {
        if (atlasInitialized) return
        atlasInitialized = true
        MemoryStack.stackPush().use { stack ->
            image_layout_transition(
                command.vkCommandBuffer,
                atlasImage,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                0,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                fullRange(stack)
            )
        }
    }

    private fun fullRange(stack: MemoryStack): VkImageSubresourceRange =
        VkImageSubresourceRange.calloc(stack)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0)
            .levelCount(1)
            .baseArrayLayer(0)
            .layerCount(totalLayers)

    /**
     * Read what the shader asked for, then zero the buffer so the next frames that use it start
     * clean.
     *
     * The scan is one pass over one `uint` per image. For a million images that is 4 MiB of cached
     * host memory, which is why [MegaTextureConfig.feedbackEveryNFrames] exists.
     */
    private fun collectFeedback(imageIndex: Int) {
        generation++
        val target = feedback.getAt(imageIndex, write = false)
        val currentFrame = frame.toInt()
        var wanted = 0
        requestScratch.clear()
        val wantCounts = IntArray(levels)

        target.mapAndRead_ints {
            for (index in 0 until imageCount) {
                val raw = get(index)
                if (raw == 0) continue
                put(index, 0)
                wanted++
                val asked = (raw - 1).coerceIn(0, levels - 1)
                wantCounts[asked]++
                wantLevel[index] = asked.toByte()
                wantFrame[index] = currentFrame
                touch(index)
                requestScratch.add(index)
            }
        }
        target.unmap()
        wantedImages = wanted

        effectiveMaxLevel = layout.ceilingFor(wantCounts)

        // A level that has just run out stays out of reach until eviction has had time to free a
        // slot, which is numImages frames at the earliest plus the grace period.
        var reachable = effectiveMaxLevel
        val settle = numImages + config.evictionGraceFrames
        while (reachable > 0 && frame - noCapacityFrame[reachable] < settle) reachable--

        for (index in requestScratch) {
            // An image with no page at all asks for its *base* page first, whatever the shader
            // wanted. It is the cheapest thing in the system — 256 bytes against a megabyte at
            // level 6 — and it is what turns a flat colour into a picture. Going straight to the
            // wanted level instead meant a cold start spent all its decode threads on level-3
            // pages for images that were still blank, and the wall filled in over a minute rather
            // than over a second.
            val want = if (publishedLevel[index] < 0) 0 else min(wantLevel[index].toInt(), reachable)
            if (want <= publishedLevel[index]) continue
            if (alreadyRequested(index, want)) continue
            noteRequested(index, want)
            decoder.request(index, want, scoreFor(index, want), generation)
        }
        decoder.trimTo(config.maxQueuedRequests)
    }

    /**
     * How badly this image needs this page.
     *
     * An image with no page at all outranks every refinement, by a margin nothing else can close.
     * That ordering is not a preference, it is what keeps the system's one invariant true: the
     * page table always has something to publish. Ranked the other way — by how many levels short
     * of the shader's request each image was — a cold start served level-3 pages to images that
     * were already showing something while 1189 blank ones sat behind them in the queue, and the
     * base level reached nine images out of 1639 before the decoders were saturated.
     *
     * Among refinements, two terms: how far short of what the shader asked for it currently is,
     * and how cheap the page is. The second matters because the cheap ones come back fast enough
     * to keep the wall moving while the expensive ones are still decoding.
     */
    private fun scoreFor(index: Int, level: Int): Float {
        val published = publishedLevel[index].toInt()
        if (published < 0) return 1000f - level
        return (level - published) * 8f - level
    }

    private fun touch(index: Int) {
        val level = publishedLevel[index].toInt()
        if (level < 0) return
        val slot = publishedSlot[index]
        if (slot >= 0 && slot < slotLastUsed[level].size) slotLastUsed[level][slot] = frame
        // Everything coarser than what is shown is what we will fall back to, so keep it warm too.
        var mask = residentMask[index]
        while (mask != 0) {
            val other = Integer.numberOfTrailingZeros(mask)
            mask = mask and (mask - 1)
            if (other == level) continue
            val otherSlot = slotFor(other, index) ?: continue
            slotLastUsed[other][otherSlot] = frame
        }
    }

    /**
     * Is there an outstanding request for exactly this (image, level)?
     *
     * Time-limited on purpose — see [MegaTextureConfig.pendingExpiryFrames]. The mark is an
     * optimisation, not a record: being wrong about it costs one duplicate decode, which the
     * upload path drops on arrival, while being wrong the other way costs an image its page
     * forever.
     */
    private fun alreadyRequested(index: Int, level: Int): Boolean =
        pendingLevel[index].toInt() == level &&
                frame - pendingFrame[index] < config.pendingExpiryFrames

    /** A request for this (image, level) is gone; let it be asked for again. */
    private fun forgetRequest(index: Int, level: Int) {
        if (index < 0 || index >= imageCount) return
        if (pendingLevel[index].toInt() != level) return
        pendingLevel[index] = -1
        pendingFrame[index] = Int.MIN_VALUE
    }

    private fun noteRequested(index: Int, level: Int) {
        pendingLevel[index] = level.toByte()
        pendingFrame[index] = frame.toInt()
    }

    private fun slotFor(level: Int, index: Int): Int? =
        if (identityLevel[level]) index.takeIf { it < pageCapacity[level] }
        else slotOfImage[level]?.get(index)

    /**
     * Start freeing least-recently-sampled slots at any level that has run out.
     *
     * Freeing is not immediate — see the class comment on the reuse barrier — so this has to run
     * *ahead* of demand rather than in response to it, which is what the low-water mark is for.
     */
    private fun startEvictions() {
        for (level in 0 until levels) {
            if (identityLevel[level]) continue
            val capacity = pageCapacity[level]
            if (capacity == 0) continue
            val lowWater = max(1, capacity / 16)
            if (capacity - residentPages[level] >= lowWater) continue

            var evicted = 0
            var scanned = 0
            val scanLimit = min(capacity, max(256, config.evictionsPerFramePerLevel * 16))
            while (evicted < config.evictionsPerFramePerLevel && scanned < scanLimit) {
                val slot = slotCursor[level]
                slotCursor[level] = (slot + 1) % capacity
                scanned++
                val occupant = slotImage[level][slot]
                if (occupant < 0) continue
                if (slotLastUsed[level][slot] > frame - config.evictionGraceFrames) continue
                releaseSlot(level, slot)
                evicted++
            }
        }
    }

    private fun releaseSlot(level: Int, slot: Int) {
        val occupant = slotImage[level][slot]
        if (occupant < 0) return
        slotImage[level][slot] = -1
        slotOfImage[level]?.remove(occupant)
        residentPages[level]--
        residentMask[occupant] = residentMask[occupant] and (1 shl level).inv()
        // Something is fading out of these pixels and they are about to stop being its. Snapping
        // is the only option: the fade needs both halves resident, and this half no longer is.
        if (outgoingLevel[occupant].toInt() == level && outgoingSlot[occupant] == slot) {
            endFade(occupant)
        }
        // Frames already in flight still have page tables naming this slot, so its pixels must
        // survive until every one of them has been retired.
        slotFreeAt[level][slot] = frame + numImages + 1
        if (publishedLevel[occupant].toInt() == level) republish(occupant)
    }

    /** Point an image at the best page it still has, or at its mean colour if it has none. */
    private fun republish(index: Int) {
        var mask = residentMask[index]
        var best = -1
        while (mask != 0) {
            val level = 31 - Integer.numberOfLeadingZeros(mask)
            mask = mask and (1 shl level).inv()
            if (slotFor(level, index) != null) {
                best = level
                break
            }
        }
        if (best < 0) {
            publishedLevel[index] = -1
            publishedSlot[index] = -1
        } else {
            publishedLevel[index] = best.toByte()
            publishedSlot[index] = slotFor(best, index)!!
        }
        markDirty(index)
    }

    private fun allocateSlot(level: Int, index: Int): Int {
        if (identityLevel[level]) return if (index < pageCapacity[level]) index else -1
        val capacity = pageCapacity[level]
        if (capacity == 0) return -1
        var scanned = 0
        while (scanned < capacity) {
            val slot = slotCursor[level]
            slotCursor[level] = (slot + 1) % capacity
            scanned++
            if (slotImage[level][slot] < 0 && slotFreeAt[level][slot] <= frame) return slot
        }
        return -1
    }

    /**
     * Copy finished pages into the atlas and publish them.
     *
     * Bounded by [MegaTextureConfig.uploadBytesPerFrame] rather than by page count: 16 pages at
     * level 6 is a megabyte of copy and 16 at level 0 is four kilobytes, and it is the bytes that
     * show up in the frame time.
     *
     * A page that does not fit is *carried* to the next frame rather than dropped and re-requested.
     * Re-requesting looks equivalent and is not: a warm-up request carries no feedback behind it,
     * so `stillWanted` would cancel the retry, and the base-level warm-up — which for a million
     * images is 256 MiB of pages against an 8 MiB budget, so it hits this every frame — would lose
     * whatever it had decoded and never come back for it. The wall would have permanent grey
     * patches, in a pattern depending on decode timing.
     */
    private fun uploadReadyPages(command: CommandBuffer, imageIndex: Int) {
        val stage = staging[imageIndex % staging.size]
        var offset = 0L
        val jobs = ArrayList<UploadJob>()
        val stageAddress = stage.map()

        while (true) {
            val page = carriedPage?.also { carriedPage = null } ?: decoder.poll() ?: break
            val bytes = page.pageWidth * page.pageHeight * 4
            // Carry only if something has already been staged. A page larger than the whole
            // budget must go through anyway, or it is carried forever and blocks every page behind
            // it: with a 64 KiB budget and a 256 KiB page that stalled the whole system at one
            // 256-byte page per frame while the queue grew to ten thousand.
            if (offset > 0 && offset + bytes > config.uploadBytesPerFrame) {
                carriedPage = page
                break
            }

            if (pendingLevel[page.index].toInt() == page.level) {
                pendingLevel[page.index] = -1
                pendingFrame[page.index] = Int.MIN_VALUE
            }
            averageColor[page.index] = page.averageColor

            if (page.level >= levels || (residentMask[page.index] and (1 shl page.level)) != 0) {
                // Already have this level (a duplicate request, or a warm-up racing feedback).
                markDirty(page.index)
                decoder.recycle(page)
                continue
            }

            val slot = allocateSlot(page.level, page.index)
            if (slot < 0) {
                noCapacityFrame[page.level] = frame
                forgetRequest(page.index, page.level)
                decoder.recycle(page)
                continue
            }

            MemoryUtil.memCopy(MemoryUtil.memAddress(page.pixels), stageAddress + offset, bytes.toLong())
            jobs.add(UploadJob(page.level, slot, offset, page.pageWidth, page.pageHeight))

            slotImage[page.level][slot] = page.index
            slotLastUsed[page.level][slot] = frame
            slotOfImage[page.level]?.put(page.index, slot)
            residentPages[page.level]++
            residentMask[page.index] = residentMask[page.index] or (1 shl page.level)
            if (page.level > publishedLevel[page.index]) {
                beginFade(page.index)
                publishedLevel[page.index] = page.level.toByte()
                publishedSlot[page.index] = slot
            }
            markDirty(page.index)

            offset += bytes
            uploadedPagesThisFrame++
            decoder.recycle(page)
        }

        uploadBytesThisFrame = offset.toInt()
        if (jobs.isEmpty()) return
        stage.unmap(0, offset)
        recordUploads(command, stage, jobs)
    }

    private class UploadJob(
        val level: Int,
        val slot: Int,
        val bufferOffset: Long,
        val pageWidth: Int,
        val pageHeight: Int
    )

    private fun recordUploads(command: CommandBuffer, stage: VulkanBuffer, jobs: List<UploadJob>) {
        MemoryStack.stackPush().use { stack ->
            val range = fullRange(stack)
            image_layout_transition(
                command.vkCommandBuffer,
                atlasImage,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                range
            )

            val regions = VkBufferImageCopy.calloc(jobs.size, stack)
            jobs.forEachIndexed { at, job ->
                val layer = layout.layerOf(job.level, job.slot)
                val x = layout.pageX(job.level, job.slot)
                val y = layout.pageY(job.level, job.slot)
                regions[at]
                    .bufferOffset(job.bufferOffset)
                    .bufferRowLength(0)
                    .bufferImageHeight(0)
                    .imageSubresource { it: VkImageSubresourceLayers ->
                        it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0)
                            .baseArrayLayer(layer)
                            .layerCount(1)
                    }
                    .imageOffset { it: VkOffset3D -> it.x(x).y(y).z(0) }
                    .imageExtent { it: VkExtent3D -> it.width(job.pageWidth).height(job.pageHeight).depth(1) }
            }
            vkCmdCopyBufferToImage(
                command.vkCommandBuffer,
                stage.buffer,
                atlasImage.vkImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                regions
            )

            image_layout_transition(
                command.vkCommandBuffer,
                atlasImage,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                fullRange(stack)
            )
        }
    }

    private fun markDirty(index: Int) {
        val word = index ushr 6
        val bit = 1L shl (index and 63)
        for (set in dirtyPerFrame) set[word] = set[word] or bit
    }

    /**
     * Bring the page table for this frame-in-flight up to date.
     *
     * There is one buffer per frame-in-flight and each one is only written while its frame is not
     * in flight, so an entry that changes has to be replayed into all of them — hence one dirty
     * set per buffer rather than one shared set. The alternative, a single buffer written under
     * the GPU's nose, would tear a `uvec4` and show one image's pixels in another's slot.
     */
    private fun publishPageTable(imageIndex: Int) {
        val dirty = dirtyPerFrame[imageIndex % dirtyPerFrame.size]
        var any = false
        for (word in dirty) if (word != 0L) { any = true; break }
        if (!any) return

        pageTable.getAt(imageIndex, write = true).mapAndWrite_ints {
            for (wordIndex in dirty.indices) {
                var word = dirty[wordIndex]
                if (word == 0L) continue
                dirty[wordIndex] = 0L
                while (word != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(word)
                    word = word and (word - 1)
                    val index = (wordIndex shl 6) or bit
                    if (index >= imageCount) continue
                    writeEntry(this, index)
                }
            }
        }
    }

    /**
     * Both halves of this image's page table entry: what it is showing, and what it is fading out
     * of.
     *
     * The outgoing half is written at full weight — the fade is entirely described by the *current*
     * half's weight, so there is one number for the shader to read and one for this to compute.
     */
    private fun writeEntry(into: java.nio.IntBuffer, index: Int) {
        val at = index * MegaTextureLayout.ENTRY_STRIDE_WORDS
        writeHalf(into, at, index, publishedLevel[index].toInt(), publishedSlot[index], fadeOf(index))
        writeHalf(
            into,
            at + MegaTextureLayout.ENTRY_WORDS,
            index,
            outgoingLevel[index].toInt(),
            outgoingSlot[index],
            MegaTextureLayout.FADE_FULL
        )
    }

    private fun writeHalf(
        into: java.nio.IntBuffer,
        at: Int,
        index: Int,
        level: Int,
        slot: Int,
        fade: Int
    ) {
        val entry = if (level < 0 || slot < 0) layout.encodeAbsent(averageColor[index]) else {
            val pageW = layout.pageWidth(level)
            val pageH = layout.pageHeight(level)
            val source = pyramid.sourceSize(index)
            val used = if (source == null) pageW to pageH
            else MegaTextureDecoder.fitInto(source.first, source.second, pageW, pageH)
            layout.encode(
                level = level,
                slot = slot,
                averageColor = averageColor[index],
                usedWidth = used.first,
                usedHeight = used.second,
                fade = fade
            )
        }
        for (word in 0 until MegaTextureLayout.ENTRY_WORDS) into.put(at + word, entry[word])
    }

    /** This image's fade weight right now: full unless it is mid-fade. */
    private fun fadeOf(index: Int): Int =
        if (fadeStart[index] == Int.MIN_VALUE) MegaTextureLayout.FADE_FULL
        else MegaTextureLayout.fadeWeight(frame - fadeStart[index], config.crossfadeFrames)

    /**
     * Start fading whatever is published now out, in favour of what is about to be.
     *
     * Called *before* the new page is published, because the old one is still in
     * [publishedLevel]/[publishedSlot] at that moment and this is the only chance to record it.
     * An image with nothing published yet fades out of its mean colour, which is the case that
     * matters most: the flat-colour-to-photograph snap is the one visible at every scale.
     */
    private fun beginFade(index: Int) {
        if (config.crossfadeFrames <= 0) return
        outgoingLevel[index] = publishedLevel[index]
        outgoingSlot[index] = publishedSlot[index]
        if (fadeStart[index] == Int.MIN_VALUE) fading.add(index)
        fadeStart[index] = frame.toInt()
    }

    /** Stop fading: either it finished, or the page underneath it went away. */
    private fun endFade(index: Int) {
        if (fadeStart[index] == Int.MIN_VALUE) return
        fadeStart[index] = Int.MIN_VALUE
        outgoingLevel[index] = -1
        outgoingSlot[index] = -1
        markDirty(index)
    }

    /**
     * Republish every fading image with this frame's weight, and retire the ones that are done.
     *
     * The weight has to be re-sent every frame of a fade, so this is the one thing in the system
     * that dirties entries on a timer rather than on an event. It is bounded by the upload budget
     * — nothing starts a fade except a page arriving — so the list is at most a few frames' worth
     * of uploads, not a share of the image count.
     *
     * It also touches the outgoing slot, which keeps the eviction scanner off a page that is still
     * being sampled. [touch] does the same for every resident level, but only for images the
     * shader asked about this pass, and a fade can outlive that.
     */
    private fun advanceFades() {
        if (fading.isEmpty()) return
        var keep = 0
        for (read in fading.indices) {
            val index = fading[read]
            val start = fadeStart[index]
            if (start == Int.MIN_VALUE) continue
            markDirty(index)
            if (MegaTextureLayout.fadeWeight(frame - start, config.crossfadeFrames) >=
                MegaTextureLayout.FADE_FULL
            ) {
                endFade(index)
                continue
            }
            val level = outgoingLevel[index].toInt()
            val slot = outgoingSlot[index]
            if (level >= 0 && slot >= 0 && slot < slotLastUsed[level].size) {
                slotLastUsed[level][slot] = frame
            }
            fading[keep++] = index
        }
        while (fading.size > keep) fading.removeAt(fading.size - 1)
    }

    private fun stillWanted(index: Int, level: Int, requestedGeneration: Long): Boolean {
        if (index < 0 || index >= imageCount) return false
        if (level > levels - 1) return false
        if ((residentMask[index] and (1 shl level)) != 0) return false
        // The base level is the fallback the page table always publishes, so a request for it is
        // never stale. An image that scrolls out of view before its first page arrives still needs
        // that page: without it, `republish` has nothing to fall back to when a finer page is
        // evicted, and the image drops to a flat colour for the rest of the session.
        if (level == 0) return true
        // A warm-up request has no feedback behind it and must not be cancelled by its absence.
        if (requestedGeneration == 0L) return true
        if (wantLevel[index] < level) return false
        return wantFrame[index] > frame.toInt() - config.staleFrames
    }

    // ------------------------------------------------------------------------------------------

    fun stats(): MegaTextureStats {
        val published = IntArray(levels)
        var unpublished = 0
        for (index in 0 until imageCount) {
            val level = publishedLevel[index].toInt()
            if (level < 0) unpublished++ else published[level]++
        }
        return MegaTextureStats(
            imageCount = imageCount,
            frame = frame,
            wantedImages = wantedImages,
            residentPages = residentPages.copyOf(),
            pageCapacity = pageCapacity.copyOf(),
            publishedAtLevel = published,
            unpublished = unpublished,
            queueDepth = decoder.queueDepth,
            inFlightDecodes = decoder.inFlight.get(),
            decodedFromCache = decoder.decodedFromCache.get(),
            decodedFromSource = decoder.decodedFromSource.get(),
            decodeFailures = decoder.decodeFailures.get(),
            cancelled = decoder.cancelled.get(),
            uploadedPagesThisFrame = uploadedPagesThisFrame,
            uploadBytesThisFrame = uploadBytesThisFrame,
            effectiveMaxLevel = effectiveMaxLevel,
            warmUpRemaining = if (warmLevel < 0) 0 else (imageCount - residentPages[warmLevel]).coerceAtLeast(0),
            pageWidths = layout.pageWidths.copyOf(),
            pageHeights = layout.pageHeights.copyOf()
        )
    }

    override fun close() {
        carriedPage?.let { decoder.recycle(it) }
        carriedPage = null
        decoder.close()
        pyramid.close()
    }

    companion object {
        /** Bit 20 of the page table's second word: "this entry names a real page". */
        const val RESIDENT_BIT = MegaTextureLayout.RESIDENT_BIT
    }
}
