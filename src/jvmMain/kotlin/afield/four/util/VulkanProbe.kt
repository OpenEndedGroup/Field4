package afield.four.util

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
    val name: String,
    val type: String,
    val apiVersion: String,
    val driverName: String = "",
    val driverInfo: String = "",
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
    val os: String,
    val arch: String,
    val ok: Boolean = false,
    /** `sdk` (a LunarG SDK's loader), `system` (whatever the OS resolves), `lwjgl-moltenvk` (the MoltenVK
     *  inside LWJGL's jar, no loader), or `none`. */
    val loaderSource: String = "none",
    val loaderPath: String? = null,
    val sdkPath: String? = null,
    val sdkVersion: String? = null,
    val instanceVersion: String? = null,
    val surfaceExtension: String? = null,
    val validationLayer: Boolean = false,
    val devices: List<VulkanDeviceInfo> = emptyList(),
    /** macOS: the driver `Options` asked the loader for, and whether that SDK has a KosmicKrisp to ask for. */
    val requestedDriver: String? = null,
    val kosmicKrispAvailable: Boolean = false,
    val error: String? = null,
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
                "-Dorg.lwjgl.system.stackSize=256", "-cp", classpath, "afield.four.util.VulkanProbeKt"
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
