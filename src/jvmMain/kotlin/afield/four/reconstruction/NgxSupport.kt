package afield.four.reconstruction

import afield.four.device.Device
import afield.four.device.Instance
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*

/** Feature-specific SR/DLAA discovery; ordinary rendering never requires NGX. */
object NgxSupport {
    const val SDK_REVISION="374959484e79a640feaba44c93ac8cfb0a03f5b5"
    const val SDK_VERSION="310.9.1"
    /** Query before instance creation. A missing SDK/extension leaves Field's baseline intact. */
    internal fun instanceExtensions(supported: Set<String>): Set<String> = runCatching {
        val n=NgxNative.loaded.getOrThrow()
        listOf(false,true).flatMap { rr -> runCatching { n.required(rr=rr).takeIf { supported.containsAll(it) }.orEmpty() }.getOrDefault(emptySet()) }.toSet()
    }.getOrDefault(emptySet())
    internal fun deviceExtensions(instance: Instance,physical: Long,supported: Set<String>): Set<String> = runCatching {
        val native=NgxNative.loaded.getOrThrow()
        listOf(false,true).flatMap { rr -> runCatching {
            check(instance.enabledExtensions.containsAll(native.required(rr=rr)))
            native.required(instance.vkInstance.address(),physical,rr).takeIf { supported.containsAll(it) }.orEmpty()
        }.getOrDefault(emptySet()) }.toSet()
    }.getOrDefault(emptySet())
    fun availability(device: Device): Result<String> = device.ngxRuntime.map {
        "DLAA / DLSS SR available; SDK $SDK_VERSION ($SDK_REVISION), NGX API 0x15"
    }
    fun rayReconstructionAvailability(device: Device): Result<String> = device.ngxRuntime.mapCatching {
        it.requireRayReconstruction();"DLSS Ray Reconstruction available; SDK $SDK_VERSION ($SDK_REVISION)"
    }
}

enum class ReconstructionMode(internal val sdkValue: Int) { DLAA(5), QUALITY(2), BALANCED(1), PERFORMANCE(0), ULTRA_PERFORMANCE(3) }
data class ReconstructionSize(val width: Int,val height: Int) {
    init { require(width>0 && height>0) { "Image dimensions must be positive" } }
}
data class ReconstructionRecommendation(val optimal: ReconstructionSize,val minimum: ReconstructionSize,val maximum: ReconstructionSize) {
    /** Smallest legal input at the output's aspect ratio, rounded up to whole pixels. */
    fun smallestFor(output: ReconstructionSize): ReconstructionSize {
        val scale=maxOf(minimum.width.toDouble()/output.width,minimum.height.toDouble()/output.height)
        return ReconstructionSize(kotlin.math.ceil(output.width*scale).toInt(),kotlin.math.ceil(output.height*scale).toInt())
            .also { validate(it,output,ReconstructionMode.ULTRA_PERFORMANCE) }
    }
    internal fun validate(input: ReconstructionSize,output: ReconstructionSize,mode: ReconstructionMode) {
        require(output.width>=32 && output.height>=32) { "NGX output must be at least 32 × 32" }
        require(input.width in minimum.width..maximum.width && input.height in minimum.height..maximum.height) {
            "NGX input ${input.width} × ${input.height} is outside ${minimum.width} × ${minimum.height} – ${maximum.width} × ${maximum.height} for $mode"
        }
        require(mode!=ReconstructionMode.DLAA || input==output) { "DLAA requires native resolution" }
    }
}

/** Device owns initialization and shutdown; individual views own their feature and parameter map. */
internal class NgxRuntime(val native: NgxNative,val handle: MemorySegment,private val rrExtensionsEnabled: Boolean) : AutoCloseable {
    fun requireRayReconstruction() {
        check(rrExtensionsEnabled) { "RR required extensions were not enabled; recreate renderer with the optional SDK" }
        native.check(native.rrAvailable.invokeWithArguments(handle))
    }
    fun recommend(output: ReconstructionSize,mode: ReconstructionMode,rr: Boolean=false): ReconstructionRecommendation = Arena.ofShared().use { a ->
        if(rr)requireRayReconstruction()
        val sizes=a.allocate(24,4)
        native.check((if(rr)native.rrOptimal else native.optimal).invokeWithArguments(handle,output.width,output.height,mode.sdkValue,sizes))
        fun size(i: Long)=ReconstructionSize(sizes.get(JAVA_INT,i),sizes.get(JAVA_INT,i+4))
        ReconstructionRecommendation(size(0),size(16),size(8))
    }
    val allocatedBytes: Long get()=native.memory.invokeWithArguments(handle) as Long
    override fun close() { native.close.invokeWithArguments(handle) }
    companion object {
        fun open(device: Device,instance: Instance): Result<NgxRuntime> = runCatching {
            val n=NgxNative.loaded.getOrThrow()
            check(instance.enabledExtensions.containsAll(n.required())) { "NGX required instance extensions were not enabled; recreate renderer" }
            check(device.enabledExtensions.containsAll(n.required(instance.vkInstance.address(),device.physicalDevice.vkPhysicalDevice.address()))) {
                "NGX required device extensions were not enabled; recreate renderer"
            }
            Arena.ofShared().use { a ->
                val out=a.allocate(ADDRESS)
                n.check(n.open.invokeWithArguments(instance.vkInstance.address(),device.physicalDevice.vkPhysicalDevice.address(),device.vkDevice.address(),out))
                val rr=runCatching { instance.enabledExtensions.containsAll(n.required(rr=true)) &&
                    device.enabledExtensions.containsAll(n.required(instance.vkInstance.address(),device.physicalDevice.vkPhysicalDevice.address(),true)) }.getOrDefault(false)
                NgxRuntime(n,out.get(ADDRESS,0),rr)
            }
        }
    }
}
