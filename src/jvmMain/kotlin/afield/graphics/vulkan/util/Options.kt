/**
 * Global switches for the Vulkan renderer, mostly read from system properties at startup, and the code that locates the Vulkan loader and driver.
 *
 * [Options] holds the platform flags, profiling and frame-presentation logging toggles, debug
 * naming and labels, and VR. On macOS, [Options.doVulkanSDK] uses the loader named by
 * `AFIELD_VULKAN_LIBRARY`, else `VULKAN_SDK`, else the newest `~/VulkanSDK` version, falls back to
 * LWJGL's bundled MoltenVK when none exists, and pins the driver chosen by [Options.macVulkanDriver].
 */

package afield.graphics.vulkan.util

import afield.app.diagnostics.logging.lprintln
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

    /** Running on Windows, by `os.name`. The three platform flags are how the rest of the renderer branches, rather than each asking the JVM again. */
    val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** Running on Linux, by `os.name`. */
    val isLinux = System.getProperty("os.name").lowercase().contains("linux")

    /** Running on macOS, by `os.name`. This is the flag that gates the loader and driver work in [doVulkanSDK], and the default for [unifiedImageLayouts]. */
    val isMac = System.getProperty("os.name").lowercase().contains("mac")

    /**
     * The native classifier for this OS: `macosx`, `win-x64` or `linux-x64`. Nothing reads it, but
     * it is not inert: it is an eager `val`, so first touching [Options] on an OS that is none of
     * the three throws rather than running on without a platform.
     */
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
     * - [MacVulkanDriver.AUTO], the default: KosmicKrisp wherever it can run — Apple silicon, macOS 26
     *   or later, and an SDK that ships it — else MoltenVK. The default since 2026-09-29, when the
     *   GPU smoke catalog passed on it with no validation errors (`dev_docs/KOSMICKRISP.md`).
     *
     * `-Dafield.four.vulkanDriver=moltenvk` or `AFIELD_VULKAN_DRIVER=moltenvk` for MoltenVK, which the
     * IOSurface export/import paths still need.
     */
    enum class MacVulkanDriver(val icdManifest: String, val driverId: Int) {
        AUTO("", 0),
        MOLTENVK("MoltenVK_icd.json", org.lwjgl.vulkan.VK12.VK_DRIVER_ID_MOLTENVK),
        KOSMICKRISP("libkosmickrisp_icd.json", org.lwjgl.vulkan.VK12.VK_DRIVER_ID_MESA_KOSMICKRISP),
    }

    /**
     * Which macOS driver to ask the loader for, from `afield.four.vulkanDriver` or
     * `AFIELD_VULKAN_DRIVER` — `auto`, `moltenvk` or `kosmickrisp`, with `auto` the default and an
     * unrecognised name an error when [Options] loads. [resolvedMacVulkanDriver] is the same
     * question with `auto` answered, and is what [doVulkanSDK] pins.
     */
    var macVulkanDriver: MacVulkanDriver = System.getProperty(
        "afield.four.vulkanDriver",
        System.getenv("AFIELD_VULKAN_DRIVER")
    )?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        MacVulkanDriver.entries.firstOrNull { it.name.equals(raw.replace("-", ""), ignoreCase = true) }
            ?: error("Unknown Vulkan driver '$raw'. Use auto, moltenvk or kosmickrisp.")
    } ?: MacVulkanDriver.AUTO

    /** [macVulkanDriver] with AUTO decided. */
    val resolvedMacVulkanDriver: MacVulkanDriver
        get() = if (macVulkanDriver != MacVulkanDriver.AUTO) macVulkanDriver
        else if (kosmicKrispCanRun) MacVulkanDriver.KOSMICKRISP else MacVulkanDriver.MOLTENVK

    /**
     * Apple silicon, macOS 26+ (KosmicKrisp is built on Metal 4), and an SDK of at least
     * [KOSMICKRISP_MIN_SDK] that ships the driver. Earlier SDKs ship a KosmicKrisp that is not ready
     * for Field: 1.4.335's lacked vertex-pipeline stores, so `vkCreateDevice` refused Field's
     * features. An SDK whose directory names no version (a hand-set `VULKAN_SDK`) is taken at its word.
     */
    val kosmicKrispCanRun: Boolean by lazy {
        val macMajor = System.getProperty("os.version")?.substringBefore('.')?.toIntOrNull() ?: 0
        val sdk = macVulkanSdk ?: return@lazy false
        val version = listOfNotNull(sdk.name, sdk.parentFile?.name).firstOrNull { it.matches(Regex("""\d+(\.\d+)+""")) }
            ?.split('.')?.map { it.toInt() }
        isMac && System.getProperty("os.arch") == "aarch64" && macMajor >= 26 &&
            File(sdk, "share/vulkan/icd.d/${MacVulkanDriver.KOSMICKRISP.icdManifest}").exists() &&
            (version == null || VersionComparator.compare(version, KOSMICKRISP_MIN_SDK) >= 0)
    }

    /** The first SDK whose KosmicKrisp Field's GPU smoke catalog passed on (1.4-conformant). */
    val KOSMICKRISP_MIN_SDK = listOf(1, 4, 363)

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

    /**
     * The loader [doVulkanSDK] hands LWJGL: `afield.four.vulkanLibrary` or `AFIELD_VULKAN_LIBRARY`
     * when either is set, else the `libvulkan.1.dylib` inside [macVulkanSdk]. A path that does not
     * exist is ignored, and setting it after Vulkan has loaded changes nothing.
     */
    var vulkan_library_name: String? = System.getProperty("afield.four.vulkanLibrary", System.getenv("AFIELD_VULKAN_LIBRARY"))
        ?: macVulkanSdk?.let { File(it, "lib/libvulkan.1.dylib").path }

    /** Whether to hand LWJGL the SDK loader at all. Constant, and read only by [doVulkanSDK]: turning it off would leave macOS on LWJGL's bundled MoltenVK. */
    val useVulkanSDK = true

    /** Whether the device asks for `robustBufferAccess`, which bounds-checks shader buffer reads at some cost. Off: Field's shaders index within bounds and the smoke catalog is the check. */
    val robustness = false

    /**
     * Collect per-frame GPU timestamps, which the renderer's profiler reports. `afield.four.profiling`
     * or `AFIELD_FOUR_PROFILING`, off by default, and also set from the user's preferences at startup.
     *
     * It can be switched on a renderer that is already running: the queries start and stop at the
     * next frame boundary, which `LiveProfilingSwitchSmoke` checks. [captureCompatibility] forces it
     * off.
     */
    var profiling = System.getProperty(
        "afield.four.profiling",
        System.getenv("AFIELD_FOUR_PROFILING") ?: "false"
    ).toBoolean()
    /**
     * Start the frame-presentation log with the process rather than when something asks for it —
     * `afield.four.framePresentationLog` or `AFIELD_FOUR_FRAME_PRESENTATION_LOG`. From a box,
     * `frameLogStart` and `frameLogStop` start and stop the same log without a restart, which is
     * what to reach for when frames are janky.
     *
     * This is the starting value of `FramePresentationLog.enabled`, and the server then follows the
     * user's preference.
     */
    var framePresentationLog = System.getProperty(
        "afield.four.framePresentationLog",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG") ?: "false"
    ).toBoolean()
    /** Where to write that log. `afield.four.framePresentationLogPath` or `AFIELD_FOUR_FRAME_PRESENTATION_LOG_PATH`; null or blank lets `FramePresentationLog` choose a timestamped path and report it. */
    var framePresentationLogPath: String? = System.getProperty(
        "afield.four.framePresentationLogPath",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_PATH")
    )
    /**
     * Where the swapchain's wait for a present goes, which changes what the log measures rather than
     * how it reports it. Off, each acquire first waits for the *previous* present, so a frame's
     * presentation is observed a frame late; on, every present is waited for as soon as it is made,
     * which dates it to the frame it belongs to and costs throughput.
     * `afield.four.framePresentationLogWaitAfterPresent` or
     * `AFIELD_FOUR_FRAME_PRESENTATION_LOG_WAIT_AFTER_PRESENT`.
     *
     * Either way the wait needs present indexing on the device; without it there is no wait and no
     * observed presentation.
     */
    var framePresentationLogWaitAfterPresent = System.getProperty(
        "afield.four.framePresentationLogWaitAfterPresent",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_WAIT_AFTER_PRESENT") ?: "false"
    ).toBoolean()
    /** How many lines the log buffers before flushing to disk. Default 120, floored at 1; 1 costs a write per line and is what to use when the run may end in a crash. */
    var framePresentationLogFlushEvery = System.getProperty(
        "afield.four.framePresentationLogFlushEvery",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_FLUSH_EVERY") ?: "120"
    ).toIntOrNull()?.coerceAtLeast(1) ?: 120
    /** How long `vkWaitForPresentKHR` is given, in nanoseconds. Default 50 ms, floored at 1 ns; a wait that times out warns and the frame goes on, so this bounds a stalled compositor rather than tuning anything. */
    var framePresentationLogPresentWaitTimeoutNs = System.getProperty(
        "afield.four.framePresentationLogPresentWaitTimeoutNs",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_PRESENT_WAIT_TIMEOUT_NS") ?: "50000000"
    ).toLongOrNull()?.coerceAtLeast(1L) ?: 50_000_000L
    /**
     * How many past-presentation-timing slots the swapchain reserves for `VK_EXT_present_timing`.
     * Default 8, floored at 1. Raise it if a high-frequency drain misses entries.
     * `afield.four.presentTimingQueueSize` or `AFIELD_FOUR_PRESENT_TIMING_QUEUE_SIZE`.
     */
    var presentTimingQueueSize = System.getProperty(
        "afield.four.presentTimingQueueSize",
        System.getenv("AFIELD_FOUR_PRESENT_TIMING_QUEUE_SIZE") ?: "8"
    ).toIntOrNull()?.coerceAtLeast(1) ?: 8
    /**
     * Log the line-numbered GLSL handed to shaderc, on the `debug,graphics` channel, for every stage
     * compiled. On by default, since a compiler error names a line in the assembled source rather
     * than in anything you wrote. `afield.four.logShadercSource` or `AFIELD_FOUR_LOG_SHADERC_SOURCE`.
     */
    var logShadercSource = System.getProperty(
        "afield.four.logShadercSource",
        System.getenv("AFIELD_FOUR_LOG_SHADERC_SOURCE") ?: "true"
    ).toBoolean()
    /**
     * Record the compute pass once per frame rather than once per window. On by default:
     * a simulation stepped per window advances two or three times a frame when the same document has
     * several windows open on it. `afield.four.computeOncePerFrame` or
     * `AFIELD_FOUR_COMPUTE_ONCE_PER_FRAME`.
     */
    var computeOncePerFrame = System.getProperty(
        "afield.four.computeOncePerFrame",
        System.getenv("AFIELD_FOUR_COMPUTE_ONCE_PER_FRAME") ?: "true"
    ).toBoolean()
    /**
     * Ask for `VK_KHR_unified_image_layouts` where the device offers it, which lets every layout but
     * `UNDEFINED`, `PREINITIALIZED` and `PRESENT_SRC` go to Vulkan as `GENERAL` (`ImageLayouts`).
     * On by default on macOS only, where the GPU sweep has run with it; elsewhere it is
     * `afield.four.unifiedLayouts` or `AFIELD_FOUR_UNIFIED_LAYOUTS=true`.
     *
     * `Device.usesUnifiedImageLayouts` is the answer for a given device, and it is false under
     * OpenXR whatever this says. Recent NVIDIA drivers offer the extension too, but the Linux and
     * Windows import paths — NVENC and CUDA, dma-buf, D3D11 handles — have not been exercised with
     * it, which is why it is opt-in there.
     */
    var unifiedImageLayouts = System.getProperty(
        "afield.four.unifiedLayouts",
        System.getenv("AFIELD_FOUR_UNIFIED_LAYOUTS") ?: isMac.toString()
    ).toBoolean()
    /**
     * Record the renderer's per-frame command buffers with `ONE_TIME_SUBMIT`, telling the driver each
     * is submitted once and can be discarded after. On by default, and a switch rather than a
     * constant so the gain can be measured on a driver without a rebuild.
     * `afield.four.oneTimeSubmit` or `AFIELD_FOUR_ONE_TIME_SUBMIT`.
     */
    var oneTimeSubmitFrames = System.getProperty(
        "afield.four.oneTimeSubmit",
        System.getenv("AFIELD_FOUR_ONE_TIME_SUBMIT") ?: "true"
    ).toBoolean()
    /**
     * Record a window's graphics work into secondary command buffers, so several can be recorded in
     * parallel. Asked for by default, and used only where `Device.supportsMaintenance7` also holds,
     * so on a device without that extension this reads true and nothing changes.
     * `afield.four.secondaryGraphicsCommandBuffers` or
     * `AFIELD_FOUR_SECONDARY_GRAPHICS_COMMAND_BUFFERS`.
     */
    var secondaryGraphicsCommandBuffers = System.getProperty(
        "afield.four.secondaryGraphicsCommandBuffers",
        System.getenv("AFIELD_FOUR_SECONDARY_GRAPHICS_COMMAND_BUFFERS") ?: "true"
    ).toBoolean()
    /**
     * Run as a GPU capture tool expects: it clears [validate], [dumpToolingInfo], [debugObjectNames],
     * [debugLabels] and [shaderDebugPrintf], every one of which either injects a layer of its own or
     * annotates the stream a capture is trying to read. `afield.four.captureCompatibility` or
     * `AFIELD_FOUR_CAPTURE_COMPAT`.
     *
     * Each of those five has a property of its own, and this overrides all of them, so asking for
     * validation and capture compatibility together gets capture compatibility. The server applies it
     * to [profiling] the same way at startup.
     */
    var captureCompatibility = System.getProperty(
        "afield.four.captureCompatibility",
        System.getenv("AFIELD_FOUR_CAPTURE_COMPAT") ?: "false"
    ).toBoolean()
    /**
     * Report the tooling layers attached to the device, which answers "is a capture tool in this
     * stream". Off by default, because the call that asks — `vkGetPhysicalDeviceToolPropertiesEXT` —
     * runs inside whatever layer injected itself, and under Nsight that has taken the JVM down at
     * startup. `afield.four.dumpToolingInfo` or `AFIELD_FOUR_DUMP_TOOLING_INFO`, and
     * [captureCompatibility] holds it off.
     */
    var dumpToolingInfo = !captureCompatibility && System.getProperty(
        "afield.four.dumpToolingInfo",
        System.getenv("AFIELD_FOUR_DUMP_TOOLING_INFO") ?: "false"
    ).toBoolean()
    /**
     * Attach names to Vulkan handles, so a validation message or a RenderDoc capture says which
     * buffer rather than which address. On by default, and inert unless [validate] is on and the
     * driver exposes `VK_EXT_debug_utils`. `afield.four.debugObjectNames` or
     * `AFIELD_FOUR_DEBUG_OBJECT_NAMES`.
     */
    var debugObjectNames = !captureCompatibility && System.getProperty(
        "afield.four.debugObjectNames",
        System.getenv("AFIELD_FOUR_DEBUG_OBJECT_NAMES") ?: "true"
    ).toBoolean()
    /**
     * Bracket regions of a command buffer with labels, which is what gives a frame debugger its
     * nesting. Same conditions as [debugObjectNames], and additionally skipped on macOS.
     * `afield.four.debugLabels` or `AFIELD_FOUR_DEBUG_LABELS`.
     */
    var debugLabels = !captureCompatibility && System.getProperty(
        "afield.four.debugLabels",
        System.getenv("AFIELD_FOUR_DEBUG_LABELS") ?: "true"
    ).toBoolean()
    /**
     * Bring up OpenXR alongside Vulkan: the instance and device ask for the runtime's required
     * extensions, the renderer creates an XR session, and the swapchain picks a format the runtime
     * will take. Off by default and set from the user's preferences at startup, so it is read before
     * the instance exists and changing it afterwards does not reach one.
     *
     * It also holds [unifiedImageLayouts] off, since OpenXR runtimes expect the layouts they were
     * handed.
     */
    var enableVR = false

    private var vulkanConfigured = false

    /**
     * Choose the Vulkan loader and driver. Must run before LWJGL first loads Vulkan, after which
     * `VULKAN_LIBRARY_NAME` is ignored. `Window` and `Instance` call it themselves, because an
     * entrypoint that does not silently gets LWJGL's bundled MoltenVK: no loader, no validation
     * layers, no KosmicKrisp. Repeat calls do nothing.
     */
    @Synchronized
    fun doVulkanSDK() {
        if (vulkanConfigured) return
        vulkanConfigured = true
        val loader = vulkan_library_name?.takeIf { File(it).exists() }
        if (useVulkanSDK && isMac && loader != null) {
            Configuration.VULKAN_LIBRARY_NAME.set(loader)
            pinMacVulkanDriver()
            addMacSdkLayers()
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

    /**
     * Put the SDK's own layers ahead of the system ones, so validation is the loader's version.
     * As with drivers, the SDK loader otherwise takes `VK_LAYER_KHRONOS_validation` from
     * `/usr/local/share/vulkan/explicit_layer.d` — on 2026-09-29 a 1.4.335 layer validating a
     * 1.4.363 KosmicKrisp. A `VK_LAYER_PATH` or `VK_ADD_LAYER_PATH` the user set is left alone.
     */
    private fun addMacSdkLayers() {
        if (System.getenv("VK_LAYER_PATH") != null) return
        val layers = macVulkanSdk?.let { File(it, "share/vulkan/explicit_layer.d") }?.takeIf { it.isDirectory } ?: return
        val existing = System.getenv("VK_ADD_LAYER_PATH")
        if (existing != null && System.getenv(LAYERS_ADDED_BY_FIELD) != existing) return
        runCatching { nativeSetenv("VK_ADD_LAYER_PATH", layers.path); nativeSetenv(LAYERS_ADDED_BY_FIELD, layers.path) }
            .onFailure { lprintln("warning,vulkan", "Could not point the loader at the SDK's layers ($it)") }
    }

    private const val PINNED_BY_FIELD = "AFIELD_VK_DRIVER_FILES_PINNED"
    private const val LAYERS_ADDED_BY_FIELD = "AFIELD_VK_ADD_LAYER_PATH_PINNED"

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

    /**
     * The depth format new depth attachments are created with. `D32_SFLOAT` until
     * [chooseDefaultDepthFormat] has asked a physical device, after which it is the first of
     * `D32_SFLOAT`, `D16_UNORM`, `D24_UNORM_S8_UINT`, `D32_SFLOAT_S8_UINT` that device can use as a
     * depth attachment — unless `afield.four.depthFormat` or `AFIELD_FOUR_DEPTH_FORMAT` named one
     * (`D16`, `D32`, `D24S8`, `D32S8`), which pins it with no support check at all.
     *
     * A pinned format the device cannot use therefore fails at the attachment rather than here, and
     * a name none of the four match throws when [Options] loads.
     */
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

    /**
     * What `afield.validate` or `AFIELD_VALIDATE` asked for, or null when neither is set to a strict
     * `true` or `false`. Kept apart from [validate] so that "nobody said" is distinguishable from
     * "someone said no": the server takes the user's preference only in the null case, so a
     * command-line answer outranks the preference without having to be re-stated.
     */
    val validateOverride: Boolean? = System.getProperty(
        "afield.validate",
        System.getenv("AFIELD_VALIDATE")
    )?.trim()?.takeIf { it.isNotEmpty() }?.toBooleanStrictOrNull()

    /**
     * Ask the instance for `VK_LAYER_KHRONOS_validation`. On in a bare entrypoint, and under the
     * server whatever [validateOverride] says, or the user's preference when it says nothing;
     * [captureCompatibility] clears it either way.
     *
     * That preference ships on, because the layer is what turns a mistake in box shader code into a
     * message. [debugObjectNames], [debugLabels] and a shader's `printf` all do nothing without it.
     */
    var validate = if (captureCompatibility) false else validateOverride ?: true

    /**
     * Enable `debugPrintfEXT` in shaders without turning all of [validate] on, since the printf
     * plumbing rides on the same validation layer. Off by default; with neither this nor [validate]
     * on, a shader's `printf` compiles and prints nothing, and the compiler warns once that it will.
     * `afield.four.shaderDebugPrintf` or `AFIELD_FOUR_SHADER_DEBUG_PRINTF`.
     */
    var shaderDebugPrintf = !captureCompatibility && System.getProperty(
        "afield.four.shaderDebugPrintf",
        System.getenv("AFIELD_FOUR_SHADER_DEBUG_PRINTF") ?: "false"
    ).toBoolean()
    /**
     * Pick a physical device by its exact reported name rather than by the default rule. Null, and
     * nothing sets it from the environment, so this is for code that has read the device names and
     * wants one of them. A name that matches nothing, or matches a device missing a required
     * extension, is not an error: selection falls through to the default, which on macOS is the
     * device belonging to the driver [resolvedMacVulkanDriver] names and elsewhere the first that
     * qualifies.
     */
    var preferredDevice: String? = null

    /** Ask the device for the ray-tracing pipeline and acceleration-structure extensions. On, and skipped on a device that does not offer them, so the cost of leaving it on is a feature query. */
    var enableRayTracing: Boolean = true

    /**
     * How many swapchain images to ask for, 3 by default. It is an upper bound rather than a count:
     * the swapchain clamps the request to the surface's maximum and then raises it to the surface's
     * minimum, and a surface that reports *no* maximum drops the request altogether and gives its
     * minimum, which is usually 2. A window that will not triple-buffer is generally that, rather
     * than a driver refusing.
     */
    var requestedImages = 3

    /**
     * Prefer a present mode that waits for vertical blank. On, which on macOS is FIFO, on Windows
     * mailbox and on Linux relaxed FIFO; off puts immediate first, which tears and is what a
     * frame-rate measurement wants. [enableVR] overrides it either way, because the desktop window
     * is then a mirror on the same loop as `xrWaitFrame` and a blocking desktop present would cap
     * the headset at the monitor's rate.
     */
    var vsync = true

    /**
     * Upper bound on renderer frames per second; 0 (the default) leaves the loop uncapped. A
     * window the compositor is not showing is not throttled by [vsync] and can run at thousands of
     * frames a second, so anything measured per frame (motion vectors, frame-to-frame
     * displacement) shrinks towards zero. A capture that shows per-frame motion sets this, in its
     * renderer box or through `AFIELD_MAX_FRAME_RATE`.
     */
    var maxFrameRate: Double = System.getenv("AFIELD_MAX_FRAME_RATE")?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.0
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
    /**
     * Default near clip distance, in world units, which every [Projection] and [SuperProjection]
     * starts from. 0.001 is close enough that a camera a metre from its subject does not clip it,
     * and near enough zero to spend most of the depth buffer's precision on the first few units;
     * push it out if depth fighting matters more than close geometry.
     */
    val zNear: Double = 0.001

    /** Default far clip distance, in world units. 1000, with [zNear] giving the ratio the depth buffer's precision follows from. */
    val zFar: Double = 1000.0

    /** The colour the OpenXR target clears each eye to, as RGBA. Opaque black. The desktop window has its own clear colour and does not read this. */
    var openXRClearColor: FloatArray = floatArrayOf(0f, 0f, 0f, 1f)

    /**
     * Prefer an HDR surface format — scRGB or HDR10 — over the default 8-bit sRGB, which needs
     * `VK_EXT_swapchain_colorspace` on the instance and a display advertising an HDR colorspace.
     * Where either is missing the swapchain is sRGB and nothing says so, so check the chosen surface
     * format rather than this flag.
     *
     * On macOS it also sets `CAMetalLayer.wantsExtendedDynamicRangeContent`, without which the OS
     * gives no EDR headroom however the swapchain is formatted.
     */
    var enableHDR: Boolean = false

    /** Print what the OpenXR runtime bound: interaction profiles and the sources behind each action. On, since a controller doing nothing is usually a profile that did not bind. */
    var openXRDebugInput: Boolean = true

    /**
     * Wait for the GPU to finish rendering into an XR swapchain image before releasing it. Off: the
     * frame is submitted and released at once, which is what most runtimes expect and is much the
     * faster of the two, but it leaves the runtime's own synchronisation to be relied on.
     *
     * On is the conservative reading of the specification, and it costs a hard GPU sync per eye,
     * which shows up as stutter. Worth turning on to test whether a tearing or flickering headset is
     * a synchronisation problem on a particular runtime.
     */
    var openXRWaitForGpuBeforeRelease: Boolean = false
}
