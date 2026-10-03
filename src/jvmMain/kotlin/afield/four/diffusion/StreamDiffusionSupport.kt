package afield.four.diffusion

import afield.four.Renderer
import afield.four.texture.UnixFdPass
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import java.io.File

/** Availability without creating GPU allocations, starting Python, or loading model weights. */
data class StreamDiffusionSupport(val available: Boolean, val reason: String) {
    companion object {
        /** No default model download or machine-specific installation path. */
        fun configuredRoot(): File? = System.getenv("FIELD_STREAMDIFFUSION_ROOT")
            ?.takeIf { it.isNotBlank() }?.let(::File)

        fun probe(renderer: Renderer, config: StreamDiffusionConfig? = null): StreamDiffusionSupport {
            fun missing(message: String) = StreamDiffusionSupport(false, message)
            // This must precede all Unix/native helper access, including on macOS.
            if (!System.getProperty("os.name").lowercase().contains("linux"))
                return missing("Local streaming diffusion requires Linux with an NVIDIA CUDA GPU. This document can still be opened and edited here.")
            if (renderer.isClosed) return missing("Start the renderer before running diffusion.")
            val device = renderer.device
            if (!device.supportsExternalMemoryFd || !device.supportsExternalSemaphoreFd)
                return missing("The selected Vulkan device does not enable external memory and semaphore FD sharing.")
            val hardware = MemoryStack.stackPush().use { s ->
                val properties = VkPhysicalDeviceProperties.calloc(s)
                vkGetPhysicalDeviceProperties(device.physicalDevice.vkPhysicalDevice, properties)
                if (properties.vendorID() != 0x10de) return@use "Select an NVIDIA Vulkan device that is also available to CUDA."
                val buffer = VkExternalBufferProperties.calloc(s).`sType$Default`()
                vkGetPhysicalDeviceExternalBufferProperties(device.physicalDevice.vkPhysicalDevice,
                    VkPhysicalDeviceExternalBufferInfo.calloc(s).`sType$Default`()
                        .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT), buffer)
                if (buffer.externalMemoryProperties().externalMemoryFeatures() and VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT == 0)
                    return@use "The selected device cannot export the GPU buffers required by CUDA."
                val timeline = VkSemaphoreTypeCreateInfo.calloc(s).`sType$Default`().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)
                val semaphore = VkExternalSemaphoreProperties.calloc(s).`sType$Default`()
                vkGetPhysicalDeviceExternalSemaphoreProperties(device.physicalDevice.vkPhysicalDevice,
                    VkPhysicalDeviceExternalSemaphoreInfo.calloc(s).`sType$Default`().pNext(timeline.address())
                        .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), semaphore)
                if (semaphore.externalSemaphoreFeatures() and VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT == 0)
                    return@use "The selected device cannot export timeline semaphores required by CUDA."
                null
            }
            if (hardware != null) return missing(hardware)
            val installation = config ?: configuredRoot()?.let { StreamDiffusionConfig(it) }
                ?: return missing("Set FIELD_STREAMDIFFUSION_ROOT to your StreamDiffusionV2 installation before starting Field, or supply StreamDiffusionConfig(root = File(...)).")
            if (!installation.root.isDirectory || !installation.python.canExecute())
                return missing("The backend root or Python environment is missing: ${installation.root}. Follow the streaming diffusion setup guide.")
            if (!installation.worker.isFile) return missing("The Field worker is missing: ${installation.worker}. Run Field from its checkout with optional/streamdiffusion installed.")
            if (installation.backend == "diffusion") {
                val required = listOf("configs/local_rtx4090.yaml", "ckpts/wan_causal_dmd_v2v/model.pt",
                    "ckpts/taew2_1.pth", "wan_models/Wan2.1-T2V-1.3B/Wan2.1_VAE.pth",
                    "wan_models/Wan2.1-T2V-1.3B/models_t5_umt5-xxl-enc-bf16.pth")
                val absent = required.filter { !File(installation.root, it).isFile }
                if (absent.isNotEmpty()) return missing("Backend files missing: ${absent.joinToString()}. Complete the optional model setup.")
            }
            if (!File("native/field-fdpass/build/fieldfdpass-native.jar").isFile || !UnixFdPass.available)
                return missing("Build native/field-fdpass before running local streaming diffusion.")
            return StreamDiffusionSupport(true, "GPU sharing and backend paths are available. CUDA, Python packages, weights and TensorRT are checked when the worker starts.")
        }
    }
}
