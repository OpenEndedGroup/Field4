package afield.graphics.render.neural

import afield.graphics.vulkan.Renderer
import java.lang.foreign.*
import java.lang.foreign.ValueLayout.ADDRESS
import java.nio.file.Path
import kotlin.io.path.*

/** Advertised features are checked before device creation; readiness also requires the pinned assets. */
object OpenDlssNrSupport {
    internal class DevicePlan(val native: OpenDlssNrNative,val handle: MemorySegment) : AutoCloseable {
        fun apply(features: Long) = native.check(native.apply.invokeWithArguments(handle,features))
        override fun close() {native.planClose.invokeWithArguments(handle)}
    }
    internal fun prepare(instance: Long,physical: Long): Result<DevicePlan> = OpenDlssNrNative.loaded.mapCatching { native ->
        Arena.ofConfined().use { arena ->
            val result=arena.allocate(ADDRESS)
            native.check(native.probe.invokeWithArguments(instance,physical,result))
            DevicePlan(native,result.get(ADDRESS,0))
        }
    }
    internal fun model(): Path=Path.of(System.getenv("FIELD_OPEN_DLSS_NR_MODEL") ?: OpenDlssNrNative.root().resolve("model").toString()).toAbsolutePath()
    internal fun shaders(): Path=Path.of(System.getenv("FIELD_OPEN_DLSS_NR_SHADERS") ?: OpenDlssNrNative.root().resolve("shaders").toString()).toAbsolutePath()
    data class Availability(val ready: Boolean,val reason: String)
    fun availability(renderer: Renderer): Availability {
        val failure=renderer.device.openDlssNrFeatureFailure
        if(failure!=null)return Availability(false,failure)
        if(!model().resolve("manifest.json").isRegularFile() || !model().resolve("model/stages/nr.bin").isRegularFile())return Availability(false,"NR weights absent; run fetchOpenDlssNrWeights")
        if(!shaders().resolve("field_nr_composite.spv").isRegularFile())return Availability(false,"NR kernels absent; run buildOpenDlssNrNative")
        return Availability(true,"NR Vulkan features enabled and pinned assets present")
    }
}
