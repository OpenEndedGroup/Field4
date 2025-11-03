package afield.four.device

import afield.four.Renderer
import afield.four.device.Queue.PresentQueue
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.util.Options
import afield.four.util.Window
import afield.four.util.vkCheck
import afield.logTime
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
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
    val imageViews: Array<ImageView>
    var surfaceFormat: SurfaceFormat
    var swapChainExtent: VkExtent2D
    val syncSemaphoresList: Array<SyncSemaphores>
    var vkSwapChain: Long = 0

    var currentFrame: Int = 0
        private set

    init {
        this.device = device
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

            surfaceFormat = calcSurfaceFormat(physicalDevice, surface)

            swapChainExtent = calcSwapChainExtent(window, surfCapabilities)

            val vkSwapchainCreateInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(surface.vkSurface)
                .minImageCount(numImages)
                .imageFormat(surfaceFormat.imageFormat)
                .imageColorSpace(surfaceFormat.colorSpace)
                .imageExtent(swapChainExtent)
                .imageArrayLayers(1)
                .imageUsage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                .preTransform(surfCapabilities.currentTransform())
                .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .clipped(true)
            if (vsync) {
                if (Options.isWindows)
                    vkSwapchainCreateInfo.presentMode(KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR)
                else
                    vkSwapchainCreateInfo.presentMode(KHRSurface.VK_PRESENT_MODE_FIFO_KHR)
            } else {
                vkSwapchainCreateInfo.presentMode(KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR)
            }

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
    }

    var nextPresentId: Long = 1 // must be > 0


    fun acquireNextImage() = logTime("ani") {
        var imageIndex: Int
        MemoryStack.stackPush().use { stack ->


            if (device.usePresentIndexing) {
                val idToWait = nextPresentId - 1
                if (idToWait <= 0) {}
                else {

                    // timeout: e.g., 50 ms in nanoseconds (tune as you like)
                    val timeoutNs = 50_000_000L

                    val result = KHRPresentWait.vkWaitForPresentKHR(
                        device.vkDevice, vkSwapChain, idToWait, timeoutNs
                    )
                    if (result == VK_TIMEOUT) {
                        println(" -- warning, waitForPresent timed out")
                    }
                }
            }
            val ip = stack.mallocInt(1)
            val err = KHRSwapchain.vkAcquireNextImageKHR(
                device.vkDevice, vkSwapChain, 0L.inv(),
                syncSemaphoresList[currentFrame].imgAcquisitionSemaphore.vkSemaphore, MemoryUtil.NULL, ip
            )
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
        var imageFormat: Int
        var colorSpace: Int
        MemoryStack.stackPush().use { stack ->
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

            imageFormat = VK10.VK_FORMAT_B8G8R8A8_SRGB
            colorSpace = surfaceFormats[0].colorSpace()
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
        }
        return SurfaceFormat(imageFormat, colorSpace)
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

        return result
    }

    val numImages: Int
        get() = imageViews.size

    fun presentImage(queue: Queue, imageIndex: Int): Boolean {
        var resize = false
        MemoryStack.stackPush().use { stack ->


            val present = VkPresentInfoKHR.calloc(stack)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pWaitSemaphores(
                    stack.longs(
                        syncSemaphoresList[currentFrame].renderCompleteSemaphore.vkSemaphore
                    )
                )
                .swapchainCount(1)
                .pSwapchains(stack.longs(vkSwapChain))
                .pImageIndices(stack.ints(imageIndex))


            if (device.usePresentIndexing)
            {
                val presentId = VkPresentIdKHR.calloc(stack)
                    .sType(KHRPresentId.VK_STRUCTURE_TYPE_PRESENT_ID_KHR)
                    .swapchainCount(1)
                    .pPresentIds(stack.longs(nextPresentId))

                present.pNext(presentId.address())
                nextPresentId++
            }

            val err = KHRSwapchain.vkQueuePresentKHR(queue.vkQueue, present)
            if (err == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                resize = true
            } else if (err == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                // Not optimal but swap chain can still be used
                println(" warning: vk_suboptimal_hkr")
            } else if (err != VK10.VK_SUCCESS) {
                throw RuntimeException("Failed to present KHR: $err")
            }
        }
        currentFrame = (currentFrame + 1) % imageViews.size
        return resize
    }

    @JvmRecord
    data class SurfaceFormat(val imageFormat: Int, val colorSpace: Int)

    @JvmRecord
    data class SyncSemaphores(val imgAcquisitionSemaphore: Semaphore, val renderCompleteSemaphore: Semaphore) {
        constructor(device: Device) : this(Semaphore(device), Semaphore(device))

        fun cleanup() {
            imgAcquisitionSemaphore.cleanup()
            renderCompleteSemaphore.cleanup()
        }
    }
}
