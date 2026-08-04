package afield.four.device

import afield.four.Renderer
import afield.four.device.Queue.PresentQueue
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.util.FramePresentationLog
import afield.four.util.Options
import afield.four.util.Window
import afield.four.util.vkCheck
import afield.logTime
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memPutAddress
import org.lwjgl.system.MemoryUtil.memPutInt
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.VK_INCOMPLETE
import org.lwjgl.vulkan.VK10.VK_TIMEOUT
import java.util.*
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min

class SwapChain(
    device: Device, surface: Surface, window: Window, requestedImages: Int, vsync: Boolean,
    presentationQueue: PresentQueue, concurrentQueues: Array<Queue>?
) {
    val device: Device
    val surface: Surface
    val imageViews: Array<ImageView>
    var surfaceFormat: SurfaceFormat
    var swapChainExtent: VkExtent2D
    val syncSemaphoresList: Array<SyncSemaphores>
    val supportsTransferSrc: Boolean
    var vkSwapChain: Long = 0

    /** True when VK_EXT_present_timing is active on this swapchain (never on macOS/MoltenVK). */
    val presentTimingEnabled: Boolean

    /** Nominal display refresh duration reported by vkGetSwapchainTimingPropertiesEXT (nanoseconds, 0 = unknown). */
    var refreshDurationNs: Long = 0L
        private set

    var currentFrame: Int = 0
        private set

    init {
        this.device = device
        this.surface = surface
        MemoryStack.stackPush().use { stack ->
            val physicalDevice = device.physicalDevice
            // Get surface capabilities
            val surfCapabilities = VkSurfaceCapabilitiesKHR.calloc(stack)
            vkCheck(
                KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                    device.physicalDevice.vkPhysicalDevice,
                    surface.vkSurface, surfCapabilities
                ), "Failed to get surface capabilities"
            )

            var numImages = calcNumImages(surfCapabilities, requestedImages)
            supportsTransferSrc =
                (surfCapabilities.supportedUsageFlags() and VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0

            surfaceFormat = calcSurfaceFormat(physicalDevice, surface)

            swapChainExtent = calcSwapChainExtent(window, surfCapabilities)

            // VK_EXT_present_timing: set the create flag so the swapchain records per-frame
            // scanout timestamps.  Guard is in Device.usesPresentTiming so macOS/MoltenVK is
            // never touched here.
            val ptFlags = if (device.usesPresentTiming)
                EXTPresentTiming.VK_SWAPCHAIN_CREATE_PRESENT_TIMING_BIT_EXT else 0

            val vkSwapchainCreateInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .flags(ptFlags)
                .surface(surface.vkSurface)
                .minImageCount(numImages)
                .imageFormat(surfaceFormat.imageFormat)
                .imageColorSpace(surfaceFormat.colorSpace)
                .imageExtent(swapChainExtent)
                .imageArrayLayers(1)
                .imageUsage(
                    VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
                        (if (supportsTransferSrc) VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT else 0)
                )
                .preTransform(surfCapabilities.currentTransform())
                .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .clipped(true)
            vkSwapchainCreateInfo.presentMode(calcPresentMode(physicalDevice, surface, vsync))

            val numQueues = concurrentQueues?.size ?: 0
            val indices: MutableList<Int> = ArrayList()
            for (i in 0 until numQueues) {
                val queue = concurrentQueues!![i]
                if (queue.queueFamilyIndex != presentationQueue.queueFamilyIndex) {
                    indices.add(queue.queueFamilyIndex)
                }
            }
            if (indices.size > 0) {
                val intBuffer = stack.mallocInt(indices.size + 1)
                indices.forEach(Consumer { i: Int? -> intBuffer.put(i!!) })
                intBuffer.put(presentationQueue.queueFamilyIndex).flip()
                vkSwapchainCreateInfo.imageSharingMode(VK10.VK_SHARING_MODE_CONCURRENT)
                    .queueFamilyIndexCount(intBuffer.capacity())
                    .pQueueFamilyIndices(intBuffer)
            } else {
                vkSwapchainCreateInfo.imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
            }

            val lp = stack.mallocLong(1)
            vkCheck(
                KHRSwapchain.vkCreateSwapchainKHR(device.vkDevice, vkSwapchainCreateInfo, null, lp),
                "Failed to create swap chain"
            )
            vkSwapChain = lp[0]

            imageViews = createImageViews(stack, device, vkSwapChain, surfaceFormat.imageFormat)
            numImages = imageViews.size
            syncSemaphoresList = (0 until numImages).map { SyncSemaphores(device) }.toTypedArray()
            currentFrame = 0
        }

        // VK_EXT_present_timing post-create setup.
        presentTimingEnabled = device.usesPresentTiming && initPresentTiming()

        // On macOS, tell the CAMetalLayer backing the window to enable Extended Dynamic Range so
        // the OS compositor delivers HDR headroom when an HDR Vulkan surface format is selected.
        if (Options.enableHDR && Options.isMac) {
            MacHdrActivator.activate(window.windowHandle)
        }
    }

    var nextPresentId: Long = 1 // must be > 0

    /** Rate-limits the "persistent SUBOPTIMAL at a matching extent" warning to once per swapchain. */
    private var loggedSuboptimal = false

    data class PresentResult(val resize: Boolean, val presentId: Long?, val result: Int)

    // ---------------------------------------------------------------------------
    // VK_EXT_present_timing helpers
    // ---------------------------------------------------------------------------

    /**
     * Post-create setup: reserve the timing queue and query the nominal refresh duration.
     * Called only when [device.usesPresentTiming] is true.
     * Returns true on success, false if any setup call fails (disables present timing for this swapchain).
     */
    private fun initPresentTiming(): Boolean {
        val queueSize = Options.presentTimingQueueSize
        val setRes = EXTPresentTiming.vkSetSwapchainPresentTimingQueueSizeEXT(
            device.vkDevice, vkSwapChain, queueSize
        )
        if (setRes != VK10.VK_SUCCESS) {
            lprintln("warning,perf", "[present_timing] vkSetSwapchainPresentTimingQueueSizeEXT failed: $setRes; disabling")
            return false
        }

        // Query nominal refresh duration.  Best-effort: failure just leaves refreshDurationNs=0.
        MemoryStack.stackPush().use { stack ->
            val timingProps = VkSwapchainTimingPropertiesEXT.calloc(stack)
                .sType(EXTPresentTiming.VK_STRUCTURE_TYPE_SWAPCHAIN_TIMING_PROPERTIES_EXT)
            val res = EXTPresentTiming.vkGetSwapchainTimingPropertiesEXT(
                device.vkDevice, vkSwapChain, timingProps, null as java.nio.LongBuffer?
            )
            if (res == VK10.VK_SUCCESS) {
                refreshDurationNs = timingProps.refreshDuration()
                lprintln(
                    "info,perf",
                    "[present_timing] enabled; queue=$queueSize refreshDuration=${refreshDurationNs}ns"
                )
            } else {
                lprintln("warning,perf", "[present_timing] vkGetSwapchainTimingPropertiesEXT failed: $res")
            }
        }
        return true
    }

    /**
     * Harvest any completed past-presentation-timing entries from the driver's ring buffer and
     * forward them to [FramePresentationLog].
     *
     * Call this once per acquire-image cycle (after the fence wait so the GPU work for the
     * previous frame is done and timings are likely committed).  Does nothing when
     * [presentTimingEnabled] is false or logging is not enabled.
     */
    fun drainPastTimings() {
        if (!presentTimingEnabled || !FramePresentationLog.enabled) return
        MemoryStack.stackPush().use { stack ->
            val MAX_TIMINGS = Options.presentTimingQueueSize
            val MAX_STAGES = 2 // IMAGE_FIRST_PIXEL_OUT + IMAGE_FIRST_PIXEL_VISIBLE

            val info = VkPastPresentationTimingInfoEXT.calloc(stack)
                .sType(EXTPresentTiming.VK_STRUCTURE_TYPE_PAST_PRESENTATION_TIMING_INFO_EXT)
                .flags(EXTPresentTiming.VK_PAST_PRESENTATION_TIMING_ALLOW_PARTIAL_RESULTS_BIT_EXT)
                .swapchain(vkSwapChain)

            // Pre-allocate stage arrays for each timing slot.
            val stageBufs = Array(MAX_TIMINGS) { VkPresentStageTimeEXT.calloc(MAX_STAGES, stack) }
            val timings = VkPastPresentationTimingEXT.calloc(MAX_TIMINGS, stack)
            for (i in 0 until MAX_TIMINGS) {
                timings[i].sType(EXTPresentTiming.VK_STRUCTURE_TYPE_PAST_PRESENTATION_TIMING_EXT)
                // presentStageCount and pPresentStages are set via unsafe writes because LWJGL
                // does not generate typed setters for these output-layout fields.
                memPutInt(timings[i].address() + VkPastPresentationTimingEXT.PRESENTSTAGECOUNT, MAX_STAGES)
                memPutAddress(timings[i].address() + VkPastPresentationTimingEXT.PPRESENTSTAGES, stageBufs[i].address())
            }

            val props = VkPastPresentationTimingPropertiesEXT.calloc(stack)
                .sType(EXTPresentTiming.VK_STRUCTURE_TYPE_PAST_PRESENTATION_TIMING_PROPERTIES_EXT)
            memPutInt(props.address() + VkPastPresentationTimingPropertiesEXT.PRESENTATIONTIMINGCOUNT, MAX_TIMINGS)
            memPutAddress(props.address() + VkPastPresentationTimingPropertiesEXT.PPRESENTATIONTIMINGS, timings.address())

            val res = EXTPresentTiming.vkGetPastPresentationTimingEXT(device.vkDevice, info, props)
            if (res != VK10.VK_SUCCESS && res != VK_INCOMPLETE) return
            // VK_INCOMPLETE = more entries exist than we requested; entries we got are valid.

            val count = props.presentationTimingCount()
            for (i in 0 until count) {
                val t = timings[i]
                val presentId = t.presentId()
                val reportComplete = t.reportComplete()
                val stageCount = t.presentStageCount()
                for (s in 0 until stageCount) {
                    val stage = stageBufs[i][s]
                    FramePresentationLog.scanoutObserved(
                        vkSwapChain, presentId, stage.stage(), stage.time(),
                        refreshDurationNs, reportComplete
                    )
                }
            }
        }
    }


    fun acquireNextImage() = logTime("ani") {
        var imageIndex: Int
        MemoryStack.stackPush().use { stack ->
            // Drain any completed scanout-timing entries before we block on the next acquire.
            drainPastTimings()

            if (device.usePresentIndexing && !FramePresentationLog.waitAfterPresent) {
                val idToWait = nextPresentId - 1
                if (idToWait > 0) waitForPresent(idToWait, "before-acquire")
            }
            val ip = stack.mallocInt(1)
            val err = KHRSwapchain.vkAcquireNextImageKHR(
                device.vkDevice, vkSwapChain, 0L.inv(),
                syncSemaphoresList[currentFrame].imgAcquisitionSemaphore.vkSemaphore, MemoryUtil.NULL, ip
            )
            // timeline semaphore is signaled by acquire; bump our tracking value
            syncSemaphoresList[currentFrame].imgAcquisitionSemaphore.markExternallySignaled()
            if (err == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                return@logTime -1
            } else if (err == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                // Not optimal but swapchain can still be used
            } else if (err != VK10.VK_SUCCESS) {
                throw Renderer.ImageNotAvailable("Failed to acquire image: $err")
            }
            imageIndex = ip[0]
        }
        return@logTime imageIndex
    }

    private fun waitForPresent(presentId: Long, reason: String): Int {
        val beginNs = System.nanoTime()
        val result = KHRPresentWait.vkWaitForPresentKHR(
            device.vkDevice,
            vkSwapChain,
            presentId,
            Options.framePresentationLogPresentWaitTimeoutNs
        )
        val endNs = System.nanoTime()
        FramePresentationLog.presentObserved(vkSwapChain, presentId, reason, result, beginNs, endNs)
        if (result == VK_TIMEOUT) {
            println(" -- warning, waitForPresent timed out")
        }
        return result
    }


    private fun calcNumImages(surfCapabilities: VkSurfaceCapabilitiesKHR, requestedImages: Int): Int {
        val maxImages = surfCapabilities.maxImageCount()
        val minImages = surfCapabilities.minImageCount()
        var result = minImages
        if (maxImages != 0) {
            result = min(requestedImages.toDouble(), maxImages.toDouble()).toInt()
        }
        result = max(result.toDouble(), minImages.toDouble()).toInt()

        return result
    }

    private fun calcSurfaceFormat(physicalDevice: PhysicalDevice, surface: Surface): SurfaceFormat {
        return MemoryStack.stackPush().use { stack ->
            val ip = stack.mallocInt(1)
            vkCheck(
                KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                    physicalDevice.vkPhysicalDevice,
                    surface.vkSurface, ip, null
                ), "Failed to get the number surface formats"
            )
            val numFormats = ip[0]
            if (numFormats <= 0) {
                throw RuntimeException("No surface formats retrieved")
            }

            val surfaceFormats = VkSurfaceFormatKHR.calloc(numFormats, stack)
            vkCheck(
                KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                    physicalDevice.vkPhysicalDevice,
                    surface.vkSurface, ip, surfaceFormats
                ), "Failed to get surface formats"
            )

            // When HDR is enabled, attempt to select an HDR surface format.
            // Priority order:
            //   1. scRGB  – R16G16B16A16_SFLOAT + EXTENDED_SRGB_LINEAR   (Mac EDR / Windows scRGB)
            //   2. HDR10  – A2B10G10R10_UNORM_PACK32 + HDR10_ST2084       (Windows HDR10 displays)
            //   3. sRGB   – B8G8R8A8_SRGB + SRGB_NONLINEAR               (universal fallback)
            if (Options.enableHDR) {
                val hdrCandidates = listOf(
                    Pair(VK10.VK_FORMAT_R16G16B16A16_SFLOAT, EXTSwapchainColorspace.VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT),
                    Pair(VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32, EXTSwapchainColorspace.VK_COLOR_SPACE_HDR10_ST2084_EXT)
                )
                for ((fmt, cs) in hdrCandidates) {
                    for (i in 0 until numFormats) {
                        val sf = surfaceFormats[i]
                        if (sf.format() == fmt && sf.colorSpace() == cs) {
                            lprintln("info,hdr", "[HDR] selected HDR surface format: format=$fmt colorSpace=$cs")
                            return@use SurfaceFormat(fmt, cs)
                        }
                    }
                }
                lprintln("warning,hdr", "[HDR] no HDR surface format available; falling back to sRGB")
            }

            // Default / fallback: prefer B8G8R8A8_SRGB + SRGB_NONLINEAR
            var imageFormat = VK10.VK_FORMAT_B8G8R8A8_SRGB
            var colorSpace = surfaceFormats[0].colorSpace()
            for (i in 0 until numFormats) {
                val surfaceFormatKHR = surfaceFormats[i]
                if (surfaceFormatKHR.format() == VK10.VK_FORMAT_B8G8R8A8_SRGB &&
                    surfaceFormatKHR.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
                ) {
                    imageFormat = surfaceFormatKHR.format()
                    colorSpace = surfaceFormatKHR.colorSpace()
                    break
                }
            }
            SurfaceFormat(imageFormat, colorSpace)
        }
    }

    private fun calcPresentMode(physicalDevice: PhysicalDevice, surface: Surface, vsync: Boolean): Int {
        return MemoryStack.stackPush().use { stack ->
            val count = stack.mallocInt(1)
            vkCheck(
                KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(
                    physicalDevice.vkPhysicalDevice,
                    surface.vkSurface,
                    count,
                    null
                ),
                "Failed to get the number of surface present modes"
            )
            val modes = stack.mallocInt(count[0])
            vkCheck(
                KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(
                    physicalDevice.vkPhysicalDevice,
                    surface.vkSurface,
                    count,
                    modes
                ),
                "Failed to get surface present modes"
            )
            val supported = (0 until count[0]).map { modes[it] }.toSet()
            val preferred = if (Options.enableVR) {
                // When VR is active the desktop window is only a mirror; it must NOT gate the
                // shared render loop, because openXRHmdTarget.renderFrame() (and its xrWaitFrame
                // pacing) runs at the tail of the same single-threaded loop as the desktop present.
                // A vsync-locked (FIFO) desktop present caps the whole loop at the monitor refresh
                // (e.g. 60Hz), starving the headset below its native rate (e.g. 90Hz). On Windows
                // MAILBOX is already used and hides this; on Linux the FIFO default exposes it.
                // Prefer a non-blocking present mode so xrWaitFrame becomes the pacing authority.
                listOf(
                    KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR,
                    KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR,
                    KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR,
                    KHRSurface.VK_PRESENT_MODE_FIFO_KHR
                )
            } else if (vsync) {
                when {
                    Options.isWindows -> listOf(
                        KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR,
                        KHRSurface.VK_PRESENT_MODE_FIFO_KHR
                    )
                    Options.isLinux -> listOf(
                        KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR,
                        KHRSurface.VK_PRESENT_MODE_FIFO_KHR
                    )
                    else -> listOf(KHRSurface.VK_PRESENT_MODE_FIFO_KHR)
                }
            } else {
                listOf(
                    KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR,
                    KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR,
                    KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR,
                    KHRSurface.VK_PRESENT_MODE_FIFO_KHR
                )
            }
            val selected = preferred.firstOrNull { it in supported } ?: KHRSurface.VK_PRESENT_MODE_FIFO_KHR
            if (selected !in supported) {
                lprintln("warning,graphics", "Surface did not report FIFO present mode; using Vulkan-required FIFO fallback")
            }
            if (Options.enableVR) {
                val name = when (selected) {
                    KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX"
                    KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE"
                    KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED"
                    KHRSurface.VK_PRESENT_MODE_FIFO_KHR -> "FIFO"
                    else -> selected.toString()
                }
                lprintln("info,graphics", "VR active: desktop mirror present mode = $name (non-blocking preferred so xrWaitFrame paces the headset)")
                if (selected == KHRSurface.VK_PRESENT_MODE_FIFO_KHR) {
                    lprintln("warning,graphics", "VR active but only FIFO present available; desktop mirror vsync may cap headset to monitor refresh")
                }
            }
            selected
        }
    }

    private fun calcSwapChainExtent(window: Window, surfCapabilities: VkSurfaceCapabilitiesKHR): VkExtent2D {
        val result = VkExtent2D.calloc()
        if (surfCapabilities.currentExtent().width() == -0x1) {
            // Surface size undefined. Set to the window size if within bounds
            var width = min(window.width.toDouble(), surfCapabilities.maxImageExtent().width().toDouble())
                .toInt()
            width = max(width.toDouble(), surfCapabilities.minImageExtent().width().toDouble()).toInt()

            var height = min(window.height.toDouble(), surfCapabilities.maxImageExtent().height().toDouble())
                .toInt()
            height = max(height.toDouble(), surfCapabilities.minImageExtent().height().toDouble()).toInt()

            result.width(width)
            result.height(height)
        } else {
            // Surface already defined, just use that for the swap chain
            result.set(surfCapabilities.currentExtent())
        }
        return result
    }

    /**
     * True if the surface's current extent differs from the extent this swapchain was created with.
     * Used to decide whether a VK_SUBOPTIMAL_KHR present result is worth a full swapchain recreate.
     * Returns false if capabilities can't be queried or the extent is driver-undefined (0xFFFFFFFF),
     * so we never recreate on the strength of a value we can't compare.
     */
    private fun surfaceExtentChanged(): Boolean {
        MemoryStack.stackPush().use { stack ->
            val caps = VkSurfaceCapabilitiesKHR.calloc(stack)
            val r = KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                device.physicalDevice.vkPhysicalDevice, surface.vkSurface, caps
            )
            if (r != VK10.VK_SUCCESS) return false
            val ce = caps.currentExtent()
            if (ce.width() == -0x1) return false
            return ce.width() != swapChainExtent.width() || ce.height() != swapChainExtent.height()
        }
    }

    fun cleanup() {
        swapChainExtent.free()
        Arrays.asList(*imageViews).forEach(Consumer { obj: ImageView -> obj.cleanup() })
        Arrays.asList(*syncSemaphoresList).forEach(Consumer { obj: SyncSemaphores -> obj.cleanup() })
        KHRSwapchain.vkDestroySwapchainKHR(device.vkDevice, vkSwapChain, null)
    }

    private fun createImageViews(stack: MemoryStack, device: Device, swapChain: Long, format: Int): Array<ImageView> {
        val result: Array<ImageView>

        val ip = stack.mallocInt(1)
        vkCheck(
            KHRSwapchain.vkGetSwapchainImagesKHR(device.vkDevice, swapChain, ip, null),
            "Failed to get number of surface images"
        )
        val numImages = ip[0]

        val swapChainImages = stack.mallocLong(numImages)
        vkCheck(
            KHRSwapchain.vkGetSwapchainImagesKHR(device.vkDevice, swapChain, ip, swapChainImages),
            "Failed to get surface images"
        )

        val imageViewData = ImageViewData().format(format).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
        result = buildList(numImages) {
            for (i in 0 until numImages) {
                add(ImageView(device, swapChainImages[i], imageViewData))
            }
        }.toTypedArray()

        result.forEachIndexed { i, iv ->
            device.nameObject(iv.image, VK10.VK_OBJECT_TYPE_IMAGE, "SwapchainImage[$i]")
            device.nameObject(iv.vkImageView, VK10.VK_OBJECT_TYPE_IMAGE_VIEW, "SwapchainView[$i]")
        }


        return result
    }

    val numImages: Int
        get() = imageViews.size

    fun presentImage(queue: Queue, imageIndex: Int, frameId: Long = -1L, windowIndex: Int = 0): PresentResult {
        var resize = false
        var presentIdValue: Long? = null
        var result = VK10.VK_SUCCESS
        MemoryStack.stackPush().use { stack ->

            val present = VkPresentInfoKHR.calloc(stack)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pWaitSemaphores(
                    stack.longs(
                        syncSemaphoresList[imageIndex].renderCompleteSemaphore.vkSemaphore
                    )
                )
                .swapchainCount(1)
                .pSwapchains(stack.longs(vkSwapChain))
                .pImageIndices(stack.ints(imageIndex))


            if (device.usePresentIndexing)
            {
                presentIdValue = nextPresentId
                val presentId = VkPresentIdKHR.calloc(stack)
                    .sType(KHRPresentId.VK_STRUCTURE_TYPE_PRESENT_ID_KHR)
                    .swapchainCount(1)
                    .pPresentIds(stack.longs(presentIdValue))

                present.pNext(presentId.address())
                nextPresentId++
            }

            // VK_EXT_present_timing: tag this present with a timing request so the driver
            // records scanout-stage timestamps we can harvest in drainPastTimings().
            // Only chained when present-timing logging is active to avoid any overhead when
            // profiling is off.
            if (presentTimingEnabled && FramePresentationLog.enabled) {
                // pTimingInfos requires a VkPresentTimingInfoEXT.Buffer (one element per swapchain).
                val timingInfoBuf = VkPresentTimingInfoEXT.calloc(1, stack)
                timingInfoBuf[0]
                    .`sType$Default`()
                    // flags=0: no scheduling constraint, just measurement.
                    // targetTime=0, timeDomainId=0: ASAP, driver-chosen domain.
                    .presentStageQueries(
                        EXTPresentTiming.VK_PRESENT_STAGE_IMAGE_FIRST_PIXEL_OUT_BIT_EXT or
                            EXTPresentTiming.VK_PRESENT_STAGE_IMAGE_FIRST_PIXEL_VISIBLE_BIT_EXT
                    )

                // swapchainCount is set automatically by pTimingInfos() from the buffer size.
                val timingsInfo = VkPresentTimingsInfoEXT.calloc(stack)
                    .`sType$Default`()
                    .pTimingInfos(timingInfoBuf)

                // Walk to the tail of the current pNext chain and append.
                var chainAddr = present.address()
                while (true) {
                    val next = org.lwjgl.system.MemoryUtil.memGetAddress(
                        chainAddr + VkBaseOutStructure.PNEXT
                    )
                    if (next == 0L) {
                        org.lwjgl.system.MemoryUtil.memPutAddress(
                            chainAddr + VkBaseOutStructure.PNEXT, timingsInfo.address()
                        )
                        break
                    }
                    chainAddr = next
                }
            }

            val callNs = System.nanoTime()
            val err = KHRSwapchain.vkQueuePresentKHR(queue.vkQueue, present)
            val returnNs = System.nanoTime()
            result = err
            FramePresentationLog.presentReturned(
                vkSwapChain,
                frameId,
                windowIndex,
                imageIndex,
                presentIdValue,
                callNs,
                returnNs,
                err
            )
            if (err == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                resize = true
            } else if (err == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                // Swapchain still usable but no longer optimally matches the surface. This is common
                // on Wayland when the compositor's fullscreen configure lands after the swapchain was
                // created. Recreate only when the surface extent has actually changed; recreating
                // unconditionally would spin (waitIdle + full rebuild) every frame on drivers that
                // keep reporting SUBOPTIMAL at a matching extent.
                if (surfaceExtentChanged()) {
                    resize = true
                } else if (!loggedSuboptimal) {
                    loggedSuboptimal = true
                    lprintln("warning,perf", "[swapchain] present returned SUBOPTIMAL_KHR; surface extent unchanged, keeping swapchain")
                }
            } else if (err != VK10.VK_SUCCESS) {
                throw RuntimeException("Failed to present KHR: $err")
            }

            if (FramePresentationLog.waitAfterPresent && device.usePresentIndexing && presentIdValue != null) {
                waitForPresent(presentIdValue, "after-present")
            }
        }
        currentFrame = (currentFrame + 1) % imageViews.size
        return PresentResult(resize, presentIdValue, result)
    }

    @JvmRecord
    data class SurfaceFormat(val imageFormat: Int, val colorSpace: Int)

    @JvmRecord
    data class SyncSemaphores(val imgAcquisitionSemaphore: Semaphore, val renderCompleteSemaphore: Semaphore) {
        constructor(device: Device) : this(Semaphore(device, timeline = false), Semaphore(device, timeline = false))

        fun cleanup() {
            imgAcquisitionSemaphore.cleanup()
            renderCompleteSemaphore.cleanup()
        }
    }
}
