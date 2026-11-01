package afield.graphics.vulkan.util

/**
 * Reports what Vulkan this machine offers: loader, instance, devices, validation layers, the SDK and, on macOS, the KosmicKrisp driver.
 *
 * [VulkanProbe] runs the check in a child JVM and returns a [VulkanStatus], which the Vulkan panel on `/welcome` shows along with install steps for the current OS. [VulkanDeviceInfo] describes one physical device.
 */

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import java.io.File
import java.util.concurrent.TimeUnit

/** One physical device as the loader reports it. */
@Serializable
data class VulkanDeviceInfo(
    /** The device name the driver reports, which is what the Vulkan panel lists. */
    val name: String,
    /** `discrete`, `integrated`, `virtual`, `cpu` or `other`. A machine whose only device is `cpu` has a software rasteriser and no GPU driver. */
    val type: String,
    /** The highest Vulkan version this device supports, as `major.minor.patch`. */
    val apiVersion: String,
    /** The driver's own name, from `VkPhysicalDeviceDriverProperties`. Empty on a driver that does not fill it in. */
    val driverName: String = "",
    /** The driver's version string, from the same properties and equally optional. */
    val driverInfo: String = "",
    /**
     * The `VK_DRIVER_ID_*` the driver reports, which is what tells a MoltenVK device from a
     * KosmicKrisp one on macOS. 0 when the driver reports none. `PhysicalDevice` matches this number
     * against [Options.MacVulkanDriver] when it picks a device.
     */
    val driverId: Int = 0,
)

/**
 * What a graphics box on this machine would get, and what is missing. Shown by the Vulkan panel on
 * `/welcome`, where a fresh clone first lands, so the user finds out before opening a window.
 *
 * [ok] means a loader, an instance, and at least one device with a platform surface. Everything else
 * is optional: validation layers (Field asks for them by default and carries on without), the SDK,
 * and on macOS the KosmicKrisp driver.
 */
@Serializable
data class VulkanStatus(
    /** `macos`, `windows` or `linux`. */
    val os: String,
    /** The JVM's `os.arch`. On macOS it is one of the conditions on KosmicKrisp, which runs on Apple silicon only. */
    val arch: String,
    /** A loader, an instance, and at least one device with a platform surface. False leaves [error] saying which of those was missing. */
    val ok: Boolean = false,
    /** `sdk` (a LunarG SDK's loader), `system` (whatever the OS resolves), `lwjgl-moltenvk` (the MoltenVK
     *  inside LWJGL's jar, no loader), or `none`. */
    val loaderSource: String = "none",
    /** The loader `Options` pinned, on macOS. Null everywhere else, where the loader is whatever the OS resolves and has no path worth printing. */
    val loaderPath: String? = null,
    /** The SDK root the probe found: `Options.macVulkanSdk` on macOS, `VULKAN_SDK` elsewhere. Null when there is none, which is not by itself a failure. */
    val sdkPath: String? = null,
    /** The version read out of [sdkPath]'s own directory name or its parent's, whichever looks like a version. Null for an SDK installed under a path that names none. */
    val sdkVersion: String? = null,
    /** The highest instance API version the loader supports, as `major.minor.patch`. Null when the loader did not load. */
    val instanceVersion: String? = null,
    /** The platform surface extension the instance offers beside `VK_KHR_surface`, such as `VK_EXT_metal_surface`. Null means no window can be opened, and is one of the two things that clears [ok]. */
    val surfaceExtension: String? = null,
    /** Whether `VK_LAYER_KHRONOS_validation` is installed. Field asks for it by default and runs without it, so false is a weaker diagnostic story rather than a broken one. */
    val validationLayer: Boolean = false,
    /** Every physical device the instance enumerated. Empty is the other thing that clears [ok]: a loader loaded, and no GPU driver offered it an ICD. */
    val devices: List<VulkanDeviceInfo> = emptyList(),
    /** macOS: the driver `Options` asked the loader for, and whether that SDK has a KosmicKrisp to ask for. */
    val requestedDriver: String? = null,
    /** Whether the SDK at [sdkPath] ships a KosmicKrisp manifest to ask for. That it is there does not mean it is in use — [requestedDriver] says which one was asked for. */
    val kosmicKrispAvailable: Boolean = false,
    /**
     * What went wrong, phrased for the Vulkan panel to show, or null when nothing did. A probe that
     * crashed loading a driver, or ran past its timeout, reports it here rather than throwing, so a
     * failed probe still returns a status.
     */
    val error: String? = null,
    /** How long the probe took, in milliseconds. A cached status carries the duration of the probe that produced it, not of the lookup. */
    val probeMs: Long = 0,
)

object VulkanProbe {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val osName: String = when {
        Options.isMac -> "macos"
        Options.isWindows -> "windows"
        else -> "linux"
    }

    @Volatile
    private var cached: VulkanStatus? = null

    /** The last probe, or a new one when there is none or [refresh] asks. One probe at a time. */
    @Synchronized
    fun status(refresh: Boolean = false): VulkanStatus {
        if (!refresh) cached?.let { return it }
        return probeInChildJvm().also { cached = it }
    }

    /**
     * Run [probeInProcess] in a child JVM, because it loads every ICD the loader finds. A broken
     * driver can crash the process that does that — Nsight already crashed it once (see
     * `Options.dumpToolingInfo`) — and the process asking here is the Field server.
     */
    fun probeInChildJvm(timeoutSec: Long = 30): VulkanStatus {
        val started = System.nanoTime()
        fun failed(message: String) = VulkanStatus(os = osName, arch = System.getProperty("os.arch"), error = message,
            probeMs = (System.nanoTime() - started) / 1_000_000)
        val javaHome = System.getProperty("java.home") ?: return failed("java.home is not set")
        val java = File(File(javaHome, "bin"), if (Options.isWindows) "java.exe" else "java").path
        val classpath = System.getProperty("java.class.path")?.takeIf { it.isNotBlank() } ?: return failed("java.class.path is blank")
        val process = runCatching {
            ProcessBuilder(
                java, "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
                "-Dorg.lwjgl.system.stackSize=256", "-cp", classpath, "afield.graphics.vulkan.util.VulkanProbeKt"
            ).redirectErrorStream(false).start()
        }.getOrElse { return failed("could not start the probe JVM: ${it.message}") }
        process.errorStream.bufferedReader().let { err -> Thread { err.readText() }.apply { isDaemon = true }.start() }
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return failed("the probe did not finish in ${timeoutSec}s")
        }
        val line = out.lineSequence().lastOrNull { it.startsWith(PREFIX) }
            ?: return failed("the probe exited with code ${process.exitValue()} without a result — " +
                "a crash while loading a Vulkan driver looks like this")
        return runCatching { json.decodeFromString<VulkanStatus>(line.removePrefix(PREFIX)) }
            .getOrElse { failed("unreadable probe result: ${it.message}") }
    }

    /** Load the loader the way Field does, create a bare instance, list devices, tear it all down. */
    fun probeInProcess(): VulkanStatus {
        val started = System.nanoTime()
        Options.doVulkanSDK()
        val sdk = if (Options.isMac) Options.macVulkanSdk else System.getenv("VULKAN_SDK")?.let(::File)
        val macLoader = Options.vulkan_library_name?.takeIf { Options.isMac && File(it).exists() }
        var status = VulkanStatus(
            os = osName,
            arch = System.getProperty("os.arch"),
            loaderSource = when {
                macLoader != null -> "sdk"
                Options.isMac -> "lwjgl-moltenvk"
                else -> "system"
            },
            loaderPath = macLoader,
            sdkPath = sdk?.path,
            // …/VulkanSDK/<v>/macOS, C:\VulkanSDK\<v>, ~/vulkan/<v>/x86_64: the version is the first
            // of the directory or its parent that looks like one.
            sdkVersion = sdk?.let { dir -> listOfNotNull(dir.name, dir.parentFile?.name).firstOrNull { it.matches(Regex("""\d+(\.\d+)+""")) } },
            requestedDriver = if (Options.isMac) Options.resolvedMacVulkanDriver.name.lowercase() else null,
            kosmicKrispAvailable = Options.isMac &&
                File(sdk, "share/vulkan/icd.d/${Options.MacVulkanDriver.KOSMICKRISP.icdManifest}").exists(),
        )
        try {
            // With VULKAN_EXPLICIT_INIT off, LWJGL loads the loader when VK is first touched and keeps
            // any failure until a function is asked for — which is this.
            VK.getFunctionProvider()
        } catch (t: Throwable) {
            return status.copy(loaderSource = "none", error = "no Vulkan loader: ${t.message ?: t.javaClass.simpleName}",
                probeMs = (System.nanoTime() - started) / 1_000_000)
        }
        try {
            MemoryStack.stackPush().use { stack ->
                val instanceVersion = VK.getInstanceVersionSupported()
                val count = stack.mallocInt(1)
                VK10.vkEnumerateInstanceExtensionProperties(null as String?, count, null)
                val extProps = VkExtensionProperties.calloc(count[0], stack)
                VK10.vkEnumerateInstanceExtensionProperties(null as String?, count, extProps)
                val exts = (0 until extProps.capacity()).map { extProps[it].extensionNameString() }.toSet()
                VK10.vkEnumerateInstanceLayerProperties(count, null)
                val layerProps = VkLayerProperties.calloc(count[0], stack)
                VK10.vkEnumerateInstanceLayerProperties(count, layerProps)
                val layers = (0 until layerProps.capacity()).map { layerProps[it].layerNameString() }.toSet()
                val surface = listOf(
                    "VK_EXT_metal_surface", "VK_KHR_win32_surface", "VK_KHR_wayland_surface",
                    "VK_KHR_xcb_surface", "VK_KHR_xlib_surface"
                ).firstOrNull { it in exts && "VK_KHR_surface" in exts }
                val portability = "VK_KHR_portability_enumeration" in exts
                status = status.copy(
                    instanceVersion = versionString(instanceVersion),
                    surfaceExtension = surface,
                    validationLayer = "VK_LAYER_KHRONOS_validation" in layers,
                )

                val names = stack.mallocPointer(if (portability) 1 else 0)
                if (portability) names.put(0, stack.ASCII("VK_KHR_portability_enumeration"))
                val ci = VkInstanceCreateInfo.calloc(stack).`sType$Default`()
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).`sType$Default`().apiVersion(VK13.VK_API_VERSION_1_3))
                    .ppEnabledExtensionNames(names)
                    .flags(if (portability) KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR else 0)
                val pInstance = stack.mallocPointer(1)
                val rc = VK10.vkCreateInstance(ci, null, pInstance)
                if (rc != VK10.VK_SUCCESS) {
                    return status.copy(error = "vkCreateInstance returned $rc", probeMs = (System.nanoTime() - started) / 1_000_000)
                }
                val instance = VkInstance(pInstance[0], ci)
                try {
                    VK10.vkEnumeratePhysicalDevices(instance, count, null)
                    val handles = stack.mallocPointer(count[0])
                    VK10.vkEnumeratePhysicalDevices(instance, count, handles)
                    val devices = (0 until handles.capacity()).map { i ->
                        val pd = VkPhysicalDevice(handles[i], instance)
                        val driver = VkPhysicalDeviceDriverProperties.calloc(stack).`sType$Default`()
                        val props2 = VkPhysicalDeviceProperties2.calloc(stack).`sType$Default`().pNext(driver.address())
                        VK11.vkGetPhysicalDeviceProperties2(pd, props2)
                        val p = props2.properties()
                        VulkanDeviceInfo(
                            name = p.deviceNameString(),
                            type = when (p.deviceType()) {
                                VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "discrete"
                                VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "integrated"
                                VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "virtual"
                                VK10.VK_PHYSICAL_DEVICE_TYPE_CPU -> "cpu"
                                else -> "other"
                            },
                            apiVersion = versionString(p.apiVersion()),
                            driverName = driver.driverNameString(),
                            driverInfo = driver.driverInfoString(),
                            driverId = driver.driverID(),
                        )
                    }
                    status = status.copy(
                        devices = devices,
                        ok = devices.isNotEmpty() && surface != null,
                        error = when {
                            devices.isEmpty() -> "the loader found no Vulkan device — no GPU driver provides a Vulkan ICD"
                            surface == null -> "no platform surface extension, so Field cannot open a window"
                            else -> null
                        },
                    )
                } finally {
                    VK10.vkDestroyInstance(instance, null)
                }
            }
        } catch (t: Throwable) {
            status = status.copy(error = "${t.javaClass.simpleName}: ${t.message}")
        }
        return status.copy(probeMs = (System.nanoTime() - started) / 1_000_000)
    }

    private fun versionString(v: Int) =
        "${VK10.VK_API_VERSION_MAJOR(v)}.${VK10.VK_API_VERSION_MINOR(v)}.${VK10.VK_API_VERSION_PATCH(v)}"

    internal const val PREFIX = "VULKAN_STATUS "

    internal fun encode(status: VulkanStatus) = json.encodeToString(status)
}

/** Entry point for [VulkanProbe.probeInChildJvm]; prints one prefixed JSON line. */
fun main() {
    println(VulkanProbe.PREFIX + VulkanProbe.encode(VulkanProbe.probeInProcess()))
    System.out.flush()
    Runtime.getRuntime().halt(0)
}
