package afield.four.util

import afield.logger.lprintln
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM
import org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT
import org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT
import org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT
import org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties
import org.lwjgl.vulkan.VkFormatProperties
import org.lwjgl.vulkan.VkPhysicalDevice
import java.io.File

object Options {

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    val isMac = System.getProperty("os.name").lowercase().contains("mac")

    val target = System.getProperty("os.name").lowercase().let {
        if ("mac" in it) "macosx"
        else if ("win" in it) "win-x64"
        else if ("linux" in it) "linux-x64"
        else throw IllegalStateException("Unsupported OS: $it")
    }

    /**
     * The macOS Vulkan driver (ICD) Field asks the loader for. The SDK ships two, and they differ in
     * ways Field's code can see — see `dev_docs/KOSMICKRISP.md`.
     *
     * - [MacVulkanDriver.MOLTENVK]: the portability driver Field grew up on; the only one on Intel Macs
     *   and before macOS 26. Has `VK_EXT_metal_objects`, which the IOSurface export/import paths use.
     * - [MacVulkanDriver.KOSMICKRISP]: LunarG's Mesa driver layered on Metal 4 — Apple silicon and
     *   macOS 26 or later only, conformant rather than a portability subset.
     * - [MacVulkanDriver.AUTO]: currently MoltenVK. Flipping AUTO to KosmicKrisp is a one-line change in
     *   [resolvedMacVulkanDriver], to be made once the sweep in that doc comes back clean.
     *
     * `-Dafield.four.vulkanDriver=kosmickrisp` or `AFIELD_VULKAN_DRIVER=kosmickrisp`.
     */
    enum class MacVulkanDriver(val icdManifest: String, val driverId: Int) {
        AUTO("", 0),
        MOLTENVK("MoltenVK_icd.json", org.lwjgl.vulkan.VK12.VK_DRIVER_ID_MOLTENVK),
        KOSMICKRISP("libkosmickrisp_icd.json", org.lwjgl.vulkan.VK12.VK_DRIVER_ID_MESA_KOSMICKRISP),
    }

    var macVulkanDriver: MacVulkanDriver = System.getProperty(
        "afield.four.vulkanDriver",
        System.getenv("AFIELD_VULKAN_DRIVER")
    )?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        MacVulkanDriver.entries.firstOrNull { it.name.equals(raw.replace("-", ""), ignoreCase = true) }
            ?: error("Unknown Vulkan driver '$raw'. Use auto, moltenvk or kosmickrisp.")
    } ?: MacVulkanDriver.AUTO

    /** [macVulkanDriver] with AUTO decided. */
    val resolvedMacVulkanDriver: MacVulkanDriver
        get() = if (macVulkanDriver == MacVulkanDriver.AUTO) MacVulkanDriver.MOLTENVK else macVulkanDriver

    /**
     * The macOS SDK root (`…/VulkanSDK/<version>/macOS`) whose loader Field uses: `VULKAN_SDK` when set,
     * else the newest version under `~/VulkanSDK`. Null when there is none, in which case LWJGL loads its
     * own bundled MoltenVK directly, with no loader, and KosmicKrisp cannot be reached at all.
     */
    val macVulkanSdk: File? by lazy {
        if (!isMac) return@lazy null
        System.getenv("VULKAN_SDK")?.let { File(it) }?.takeIf { File(it, "lib/libvulkan.1.dylib").exists() }
            ?: File(System.getProperty("user.home"), "VulkanSDK").listFiles()
                ?.filter { File(it, "macOS/lib/libvulkan.1.dylib").exists() }
                ?.maxWithOrNull(compareBy<File, List<Int>>(VersionComparator) { dir -> dir.name.split('.').map { it.toIntOrNull() ?: 0 } })
                ?.let { File(it, "macOS") }
    }

    private object VersionComparator : Comparator<List<Int>> {
        override fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }

    var vulkan_library_name: String? = System.getProperty("afield.four.vulkanLibrary", System.getenv("AFIELD_VULKAN_LIBRARY"))
        ?: macVulkanSdk?.let { File(it, "lib/libvulkan.1.dylib").path }
    val useVulkanSDK = true
    val robustness = false
    var profiling = System.getProperty(
        "afield.four.profiling",
        System.getenv("AFIELD_FOUR_PROFILING") ?: "false"
    ).toBoolean()
    var framePresentationLog = System.getProperty(
        "afield.four.framePresentationLog",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG") ?: "false"
    ).toBoolean()
    var framePresentationLogPath: String? = System.getProperty(
        "afield.four.framePresentationLogPath",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_PATH")
    )
    var framePresentationLogWaitAfterPresent = System.getProperty(
        "afield.four.framePresentationLogWaitAfterPresent",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_WAIT_AFTER_PRESENT") ?: "false"
    ).toBoolean()
    var framePresentationLogFlushEvery = System.getProperty(
        "afield.four.framePresentationLogFlushEvery",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_FLUSH_EVERY") ?: "120"
    ).toIntOrNull()?.coerceAtLeast(1) ?: 120
    var framePresentationLogPresentWaitTimeoutNs = System.getProperty(
        "afield.four.framePresentationLogPresentWaitTimeoutNs",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_PRESENT_WAIT_TIMEOUT_NS") ?: "50000000"
    ).toLongOrNull()?.coerceAtLeast(1L) ?: 50_000_000L
    // Number of past-presentation-timing slots pre-reserved on the swapchain for
    // VK_EXT_present_timing.  Increase if high-frequency drain calls miss entries.
    var presentTimingQueueSize = System.getProperty(
        "afield.four.presentTimingQueueSize",
        System.getenv("AFIELD_FOUR_PRESENT_TIMING_QUEUE_SIZE") ?: "8"
    ).toIntOrNull()?.coerceAtLeast(1) ?: 8
    var logShadercSource = System.getProperty(
        "afield.four.logShadercSource",
        System.getenv("AFIELD_FOUR_LOG_SHADERC_SOURCE") ?: "true"
    ).toBoolean()
    var computeOncePerFrame = System.getProperty(
        "afield.four.computeOncePerFrame",
        System.getenv("AFIELD_FOUR_COMPUTE_ONCE_PER_FRAME") ?: "true"
    ).toBoolean()
    // Requested by default; actual use is gated by Device.supportsMaintenance7.
    var secondaryGraphicsCommandBuffers = System.getProperty(
        "afield.four.secondaryGraphicsCommandBuffers",
        System.getenv("AFIELD_FOUR_SECONDARY_GRAPHICS_COMMAND_BUFFERS") ?: "true"
    ).toBoolean()
    var captureCompatibility = System.getProperty(
        "afield.four.captureCompatibility",
        System.getenv("AFIELD_FOUR_CAPTURE_COMPAT") ?: "false"
    ).toBoolean()
    // vkGetPhysicalDeviceToolPropertiesEXT crosses into injected tooling/layer code and
    // has crashed JVM startup under Nsight; keep it opt-in.
    var dumpToolingInfo = !captureCompatibility && System.getProperty(
        "afield.four.dumpToolingInfo",
        System.getenv("AFIELD_FOUR_DUMP_TOOLING_INFO") ?: "false"
    ).toBoolean()
    var debugObjectNames = !captureCompatibility && System.getProperty(
        "afield.four.debugObjectNames",
        System.getenv("AFIELD_FOUR_DEBUG_OBJECT_NAMES") ?: "true"
    ).toBoolean()
    var debugLabels = !captureCompatibility && System.getProperty(
        "afield.four.debugLabels",
        System.getenv("AFIELD_FOUR_DEBUG_LABELS") ?: "true"
    ).toBoolean()
    var enableVR = false

    fun doVulkanSDK() {
        val loader = vulkan_library_name?.takeIf { File(it).exists() }
        if (useVulkanSDK && isMac && loader != null) {
            Configuration.VULKAN_LIBRARY_NAME.set(loader)
            pinMacVulkanDriver()
        } else if (isMac) {
            lprintln("warning,vulkan", "No Vulkan SDK loader found (VULKAN_SDK, ~/VulkanSDK); LWJGL's bundled MoltenVK will be used" +
                if (macVulkanDriver == MacVulkanDriver.KOSMICKRISP) ", so the requested KosmicKrisp driver is unavailable" else "")
        }

        if (isWindows) // too many vulkan extensions on latest nvidia!!
            Configuration.STACK_SIZE.set(512)
    }

    /**
     * Point the loader at exactly one driver manifest, from the same SDK as the loader.
     *
     * Left to itself the SDK loader does not look in its own `share/vulkan/icd.d`: it finds whatever
     * sits in `/usr/local/share/vulkan/icd.d` and `~/.local/share/vulkan/icd.d`, which is whichever SDK
     * last did a system-global install plus any hand-built Mesa. Field would then take the first
     * device enumerated — MoltenVK today, by the loader's ordering, not by anyone's choice. Pinning
     * `VK_DRIVER_FILES` makes the driver a decision. A `VK_DRIVER_FILES` or `VK_ICD_FILENAMES` already
     * in the environment is the user's decision and is left alone.
     *
     * The loader reads the C environment when it scans for drivers, so this is a `setenv(3)` in this
     * process and has to run before the first Vulkan call, which `doVulkanSDK` does.
     *
     * Child processes inherit the C environment, so a `jvm-main` smoke started by a pinned Field sees
     * our `VK_DRIVER_FILES`. [PINNED_BY_FIELD] marks it as ours, so the child re-decides from its own
     * `AFIELD_VULKAN_DRIVER` rather than mistaking the parent's choice for the user's.
     */
    private fun pinMacVulkanDriver() {
        val ours = System.getenv(PINNED_BY_FIELD)?.let { it == System.getenv("VK_DRIVER_FILES") } == true
        if (!ours && (System.getenv("VK_DRIVER_FILES") != null || System.getenv("VK_ICD_FILENAMES") != null)) {
            lprintln("info,vulkan", "Vulkan driver chosen by the environment: " +
                (System.getenv("VK_DRIVER_FILES") ?: System.getenv("VK_ICD_FILENAMES")))
            return
        }
        val driver = resolvedMacVulkanDriver
        val manifest = macVulkanSdk?.let { File(it, "share/vulkan/icd.d/${driver.icdManifest}") }
        if (manifest == null || !manifest.exists()) {
            lprintln("warning,vulkan", "Vulkan SDK at $macVulkanSdk has no ${driver.icdManifest}; " +
                "leaving driver discovery to the loader" +
                if (driver == MacVulkanDriver.KOSMICKRISP) " (install the SDK's KosmicKrisp component)" else "")
            return
        }
        runCatching { nativeSetenv("VK_DRIVER_FILES", manifest.path); nativeSetenv(PINNED_BY_FIELD, manifest.path) }
            .onSuccess { lprintln("info,vulkan", "Vulkan driver: ${driver.name.lowercase()} ($manifest)") }
            .onFailure { lprintln("warning,vulkan", "Could not pin the Vulkan driver ($it); leaving discovery to the loader") }
    }

    private const val PINNED_BY_FIELD = "AFIELD_VK_DRIVER_FILES_PINNED"

    private fun nativeSetenv(name: String, value: String) {
        val linker = java.lang.foreign.Linker.nativeLinker()
        val setenv = linker.downcallHandle(
            linker.defaultLookup().find("setenv").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(
                java.lang.foreign.ValueLayout.JAVA_INT,
                java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_INT
            )
        )
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val rc = setenv.invokeWithArguments(arena.allocateFrom(name), arena.allocateFrom(value), 1) as Int
            check(rc == 0) { "setenv($name) returned $rc" }
        }
    }

    private val requestedDepthFormat = System.getProperty(
        "afield.four.depthFormat",
        System.getenv("AFIELD_FOUR_DEPTH_FORMAT")
    )?.trim()?.takeIf { it.isNotEmpty() }?.let { parseDepthFormat(it) }

    var defaultDepthFormat: Int = requestedDepthFormat ?: VK_FORMAT_D32_SFLOAT

    fun chooseDefaultDepthFormat(physicalDevice: VkPhysicalDevice): Int {
        requestedDepthFormat?.let {
            defaultDepthFormat = it
            lprintln("info,graphics", "Using requested depth format ${depthFormatName(it)}")
            return it
        }

        val candidates = listOf(
            VK_FORMAT_D32_SFLOAT,
            VK_FORMAT_D16_UNORM,
            VK_FORMAT_D24_UNORM_S8_UINT,
            VK_FORMAT_D32_SFLOAT_S8_UINT
        )

        MemoryStack.stackPush().use { stack ->
            val props = VkFormatProperties.calloc(stack)
            candidates.forEach { format ->
                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props)
                if ((props.optimalTilingFeatures() and VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    defaultDepthFormat = format
                    lprintln("info,graphics", "Selected depth format ${depthFormatName(format)}")
                    return format
                }
            }
        }

        error("No supported depth attachment format found")
    }

    fun depthHasStencil(format: Int): Boolean =
        format == VK_FORMAT_D24_UNORM_S8_UINT || format == VK_FORMAT_D32_SFLOAT_S8_UINT

    fun depthAspectMask(format: Int): Int =
        VK10.VK_IMAGE_ASPECT_DEPTH_BIT or
                (if (depthHasStencil(format)) VK10.VK_IMAGE_ASPECT_STENCIL_BIT else 0)

    fun depthFormatName(format: Int): String = when (format) {
        VK_FORMAT_D16_UNORM -> "D16_UNORM"
        VK_FORMAT_D32_SFLOAT -> "D32_SFLOAT"
        VK_FORMAT_D24_UNORM_S8_UINT -> "D24_UNORM_S8_UINT"
        VK_FORMAT_D32_SFLOAT_S8_UINT -> "D32_SFLOAT_S8_UINT"
        else -> "format-$format"
    }

    private fun parseDepthFormat(raw: String): Int {
        return when (raw.uppercase().replace("-", "_")) {
            "D16", "D16_UNORM", "VK_FORMAT_D16_UNORM" -> VK_FORMAT_D16_UNORM
            "D32", "D32F", "D32_SFLOAT", "VK_FORMAT_D32_SFLOAT" -> VK_FORMAT_D32_SFLOAT
            "D24S8", "D24_UNORM_S8_UINT", "VK_FORMAT_D24_UNORM_S8_UINT" -> VK_FORMAT_D24_UNORM_S8_UINT
            "D32S8", "D32FS8", "D32_SFLOAT_S8_UINT", "VK_FORMAT_D32_SFLOAT_S8_UINT" -> VK_FORMAT_D32_SFLOAT_S8_UINT
            else -> error("Unknown depth format '$raw'. Use D16, D32, D24S8, or D32S8.")
        }
    }

    val validateOverride: Boolean? = System.getProperty(
        "afield.validate",
        System.getenv("AFIELD_VALIDATE")
    )?.trim()?.takeIf { it.isNotEmpty() }?.toBooleanStrictOrNull()

    var validate = if (captureCompatibility) false else validateOverride ?: true
    var shaderDebugPrintf = !captureCompatibility && System.getProperty(
        "afield.four.shaderDebugPrintf",
        System.getenv("AFIELD_FOUR_SHADER_DEBUG_PRINTF") ?: "false"
    ).toBoolean()
    var preferredDevice: String? = null
    var enableRayTracing: Boolean = true
    var requestedImages = 3
    var vsync = true
    /**
     * Default vertical field of view, in **radians** — [Projection] and [SuperProjection] hand it
     * straight to `setPerspective(fovy, …)` and `tan(fov / 2)` respectively.
     *
     * It is written as `10π - 30` rather than as a plain number because that is what it is: the
     * unique positive fov reproducing the framing that this field's previous value, a bare `30.0`,
     * actually produced. `30.0` was a *degrees* literal in a radians slot, and it did not merely
     * widen the lens — `15 rad` wraps into the second quadrant, so `tan(15) = -0.856` is
     * **negative**, `h = 1 / tan(fov / 2)` came out at `-1.168`, and every perspective matrix in
     * the tree silently negated both `m00` and `m11`. That is a 180° rotation of the picture, and
     * being a rotation rather than a mirror it preserves triangle winding, so backface culling
     * never complained about it. It reached the docs as figures rendered upside down *and*
     * left-right reversed: in the pre-fix `fabricate-default-scene.png` the green torus at world
     * `x = -0.95` measures at image `x = 0.376`, the wrong side of centre.
     *
     * Since `tan(15) = -tan(5π - 15)`, `2(5π - 15) = 10π - 30 ≈ 1.4159` is the positive fov with
     * the identical `|tan(fov / 2)|`, so the sign bug is gone and the framing every committed
     * figure was composed against is unchanged to the last float. It works out at 81.13°, which is
     * wide but is the lens those compositions were made with; narrowing it to a conventional 30° is
     * a reframing, not a bug fix, and is deliberately not bundled in here.
     *
     * **Must stay positive.** A negative fovy re-introduces the mirror rather than flipping the
     * camera, and does it silently. `ProjectionOrientationTest` pins the sign.
     */
    val fov: Double = 10.0 * Math.PI - 30.0
    val zNear: Double = 0.001
    val zFar: Double = 1000.0

    var openXRClearColor: FloatArray = floatArrayOf(0f, 0f, 0f, 1f)

    // When true, the swap chain will prefer an HDR surface format (scRGB or HDR10) over the
    // default 8-bit sRGB.  On macOS a small JVM→ObjC FFI call also sets
    // CAMetalLayer.wantsExtendedDynamicRangeContent = YES so the OS actually delivers EDR
    // headroom.  Requires VK_EXT_swapchain_colorspace on the instance and a display that
    // advertises an HDR colorspace; falls back silently to sRGB when the hardware doesn't
    // support any HDR format.
    var enableHDR: Boolean = false

    // When true, prints OpenXR input diagnostics (interaction profiles, bound sources).
    var openXRDebugInput: Boolean = true

    // When true, we wait for the GPU to finish rendering into the XR swapchain images before
    // calling xrReleaseSwapchainImage(). This is the most conservative/correct approach but can
    // introduce stutter by forcing a hard GPU sync.
    //
    // When false, we submit and release immediately (no explicit GPU wait). This is much faster
    // and is commonly used in practice, but relies on runtime/driver behavior; test on your
    // target runtimes.
    var openXRWaitForGpuBeforeRelease: Boolean = false
}
