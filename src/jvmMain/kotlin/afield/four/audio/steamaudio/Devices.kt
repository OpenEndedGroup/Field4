package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLOpenCLDeviceDesc
import afield.four.audio.steamaudio.raw.IPLOpenCLDeviceSettings
import afield.four.audio.steamaudio.raw.IPLTrueAudioNextDeviceSettings
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * An Intel Embree device, enabling the [SceneType.EMBREE] ray tracer.
 *
 * Embree is a CPU ray tracer, generally faster than Steam Audio's built-in one for large static
 * scenes. For geometry that changes every frame the advantage is smaller, because the win is
 * partly offset by rebuild cost on [Scene.commit].
 *
 * Only available on x86 platforms; creating one elsewhere fails with
 * [SteamAudioError.INITIALIZATION].
 */
public class EmbreeDevice private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplEmbreeDeviceRelease

    override val typeName: String get() = "EmbreeDevice"

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): EmbreeDevice = EmbreeDevice(Phonon.iplEmbreeDeviceRetain(liveHandle))

    public companion object {
        /**
         * Creates an Embree device.
         *
         * @throws SteamAudioException if Embree is unavailable on this platform or build.
         */
        public operator fun invoke(context: SteamAudioContext): EmbreeDevice {
            val contextHandle = context.liveHandle
            // IPLEmbreeDeviceSettings is `typedef void` unless the library was built with
            // Embree support compiled in, so the parameter is just a null pointer.
            return EmbreeDevice(
                createHandle("iplEmbreeDeviceCreate") { out ->
                    Phonon.iplEmbreeDeviceCreate(contextHandle, MemorySegment.NULL, out)
                },
            )
        }
    }
}

/** Describes one OpenCL device that Steam Audio could use. */
public data class OpenClDeviceDescription(
    public val platformName: String,
    public val platformVendor: String,
    public val platformVersion: String,
    public val deviceName: String,
    public val deviceVendor: String,
    public val deviceVersion: String,
    public val type: OpenClDeviceType,
    /** Compute units reserved for convolution. */
    public val convolutionComputeUnits: Int,
    /** Compute units reserved for impulse-response updates. */
    public val irUpdateComputeUnits: Int,
    /** The device's compute-unit granularity. */
    public val granularity: Int,
    /** A relative performance score; higher is faster. */
    public val performanceScore: Float,
) {
    override fun toString(): String =
        "$deviceName ($deviceVendor, $type, score $performanceScore)"
}

/**
 * The set of OpenCL devices available, so you can pick one before committing to it.
 *
 * ```
 * OpenClDeviceList(context).use { devices ->
 *     val best = (0 until devices.size)
 *         .map { devices[it] }
 *         .filter { it.type == OpenClDeviceType.GPU }
 *         .maxByOrNull { it.performanceScore }
 * }
 * ```
 */
public class OpenClDeviceList private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplOpenCLDeviceListRelease

    override val typeName: String get() = "OpenClDeviceList"

    /** Number of devices found. */
    public val size: Int
        get() = Phonon.iplOpenCLDeviceListGetNumDevices(liveHandle)

    /** Describes the device at [index]. */
    public operator fun get(index: Int): OpenClDeviceDescription {
        require(index in 0 until size) { "device index $index outside 0..<$size" }
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            val desc = IPLOpenCLDeviceDesc.allocate(arena)
            Phonon.iplOpenCLDeviceListGetDeviceDesc(handle, index, desc)
            return OpenClDeviceDescription(
                platformName = IPLOpenCLDeviceDesc.platformName(desc).readCString(),
                platformVendor = IPLOpenCLDeviceDesc.platformVendor(desc).readCString(),
                platformVersion = IPLOpenCLDeviceDesc.platformVersion(desc).readCString(),
                deviceName = IPLOpenCLDeviceDesc.deviceName(desc).readCString(),
                deviceVendor = IPLOpenCLDeviceDesc.deviceVendor(desc).readCString(),
                deviceVersion = IPLOpenCLDeviceDesc.deviceVersion(desc).readCString(),
                type = OpenClDeviceType.from(IPLOpenCLDeviceDesc.type(desc)),
                convolutionComputeUnits = IPLOpenCLDeviceDesc.numConvolutionCUs(desc),
                irUpdateComputeUnits = IPLOpenCLDeviceDesc.numIRUpdateCUs(desc),
                granularity = IPLOpenCLDeviceDesc.granularity(desc),
                performanceScore = IPLOpenCLDeviceDesc.perfScore(desc),
            )
        }
    }

    /** All devices found, as descriptions. */
    public fun toList(): List<OpenClDeviceDescription> = (0 until size).map { get(it) }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): OpenClDeviceList =
        OpenClDeviceList(Phonon.iplOpenCLDeviceListRetain(liveHandle))

    public companion object {
        /**
         * Enumerates OpenCL devices matching the given constraints.
         *
         * @param type restrict to CPU or GPU devices, or accept any.
         * @param computeUnitsToReserve how many compute units to reserve for Steam Audio. Zero
         *   means use the whole device.
         * @param fractionForIrUpdate of the reserved units, the fraction devoted to
         *   impulse-response updates rather than convolution.
         * @param requiresTrueAudioNext only list devices that can support TrueAudio Next.
         */
        public operator fun invoke(
            context: SteamAudioContext,
            type: OpenClDeviceType = OpenClDeviceType.ANY,
            computeUnitsToReserve: Int = 0,
            fractionForIrUpdate: Float = 0.5f,
            requiresTrueAudioNext: Boolean = false,
        ): OpenClDeviceList {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val settings = IPLOpenCLDeviceSettings.allocate(arena)
                IPLOpenCLDeviceSettings.type(settings, type.value)
                IPLOpenCLDeviceSettings.numCUsToReserve(settings, computeUnitsToReserve)
                IPLOpenCLDeviceSettings.fractionCUsForIRUpdate(settings, fractionForIrUpdate)
                IPLOpenCLDeviceSettings.requiresTAN(settings, if (requiresTrueAudioNext) 1 else 0)
                return OpenClDeviceList(
                    createHandle("iplOpenCLDeviceListCreate") { out ->
                        Phonon.iplOpenCLDeviceListCreate(contextHandle, settings, out)
                    },
                )
            }
        }
    }
}

/** An OpenCL device, needed for Radeon Rays and TrueAudio Next. */
public class OpenClDevice private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplOpenCLDeviceRelease

    override val typeName: String get() = "OpenClDevice"

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): OpenClDevice = OpenClDevice(Phonon.iplOpenCLDeviceRetain(liveHandle))

    public companion object {
        /** Opens the device at [index] in [list]. */
        public operator fun invoke(
            context: SteamAudioContext,
            list: OpenClDeviceList,
            index: Int,
        ): OpenClDevice {
            require(index in 0 until list.size) { "device index $index outside 0..<${list.size}" }
            val contextHandle = context.liveHandle
            return OpenClDevice(
                createHandle("iplOpenCLDeviceCreate") { out ->
                    Phonon.iplOpenCLDeviceCreate(contextHandle, list.liveHandle, index, out)
                },
            )
        }

        /**
         * Wraps OpenCL command queues your application already owns.
         *
         * [convolutionQueue] and [irUpdateQueue] are `cl_command_queue` handles, passed as raw
         * pointers. Use this when Steam Audio has to share a device with your own OpenCL work.
         */
        public fun fromExisting(
            context: SteamAudioContext,
            convolutionQueue: MemorySegment,
            irUpdateQueue: MemorySegment,
        ): OpenClDevice {
            val contextHandle = context.liveHandle
            return OpenClDevice(
                createHandle("iplOpenCLDeviceCreateFromExisting") { out ->
                    Phonon.iplOpenCLDeviceCreateFromExisting(
                        contextHandle,
                        convolutionQueue,
                        irUpdateQueue,
                        out,
                    )
                },
            )
        }
    }
}

/**
 * An AMD Radeon Rays device, enabling the [SceneType.RADEON_RAYS] GPU ray tracer.
 */
public class RadeonRaysDevice private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplRadeonRaysDeviceRelease

    override val typeName: String get() = "RadeonRaysDevice"

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): RadeonRaysDevice =
        RadeonRaysDevice(Phonon.iplRadeonRaysDeviceRetain(liveHandle))

    public companion object {
        /** Creates a Radeon Rays device on top of [openClDevice]. */
        public operator fun invoke(openClDevice: OpenClDevice): RadeonRaysDevice =
            // As with Embree, the settings struct is `typedef void` unless the backend was
            // compiled in, so this is a null pointer.
            RadeonRaysDevice(
                createHandle("iplRadeonRaysDeviceCreate") { out ->
                    Phonon.iplRadeonRaysDeviceCreate(openClDevice.liveHandle, MemorySegment.NULL, out)
                },
            )
    }
}

/**
 * An AMD TrueAudio Next device, enabling [ReflectionEffectType.TRUE_AUDIO_NEXT] -- GPU-accelerated
 * convolution reflections.
 *
 * This is the option that makes long convolution reverb affordable for many simultaneous
 * sources, since it moves the convolution onto reserved GPU compute units. Windows and AMD
 * hardware only.
 */
public class TrueAudioNextDevice private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplTrueAudioNextDeviceRelease

    override val typeName: String get() = "TrueAudioNextDevice"

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): TrueAudioNextDevice =
        TrueAudioNextDevice(Phonon.iplTrueAudioNextDeviceRetain(liveHandle))

    public companion object {
        /**
         * Creates a TrueAudio Next device.
         *
         * @param frameSize audio block size, matching your [AudioSettings].
         * @param irSize impulse response length in samples.
         * @param order Ambisonic order of the impulse responses.
         * @param maxSources how many source slots to reserve on the device.
         */
        public operator fun invoke(
            openClDevice: OpenClDevice,
            frameSize: Int,
            irSize: Int,
            order: Int,
            maxSources: Int,
        ): TrueAudioNextDevice {
            Arena.ofConfined().use { arena ->
                val settings = IPLTrueAudioNextDeviceSettings.allocate(arena)
                IPLTrueAudioNextDeviceSettings.frameSize(settings, frameSize)
                IPLTrueAudioNextDeviceSettings.irSize(settings, irSize)
                IPLTrueAudioNextDeviceSettings.order(settings, order)
                IPLTrueAudioNextDeviceSettings.maxSources(settings, maxSources)
                return TrueAudioNextDevice(
                    createHandle("iplTrueAudioNextDeviceCreate") { out ->
                        Phonon.iplTrueAudioNextDeviceCreate(openClDevice.liveHandle, settings, out)
                    },
                )
            }
        }
    }
}

/** Reads a NUL-terminated C string from a possibly-null pointer. */
internal fun MemorySegment.readCString(): String =
    if (address() == 0L) "" else reinterpret(Long.MAX_VALUE).getString(0L)
