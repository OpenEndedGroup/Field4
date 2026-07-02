package afield.four.synth

import afield.Box
import afield.stdlib.Inheritance
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

var Box.dirtSamples by Inheritance<DirtSampleLibrary>()

/**
 * Loads or reuses a shared Dirt sample library and stores it on the current root box.
 */
fun dirt(path: String, preload: Boolean = true): DirtSampleLibrary {
    val library = DirtSampleLibrary.shared(path, preload = preload)
    Box.context.get()?.root?.dirtSamples = library
    return library
}

/**
 * Returns the Dirt sample library installed in the current box context.
 *
 * Call `dirt("/path/to/Dirt-Samples")` first to initialize this context value.
 */
fun dirt(): DirtSampleLibrary =
    Box.context.get()?.dirtSamples ?: error("No DirtSampleLibrary in box context. Call dirt(\"/path/to/Dirt-Samples\") first.")

/**
 * Creates a polyphonic sample player for a Dirt sample library.
 */
fun dirtPlayer(
    voices: Int = 32,
    library: DirtSampleLibrary = dirt(),
    gain: Param = Param(1f)
): DirtSamplePlayer = DirtSamplePlayer(library = library, maxVoices = voices, masterGain = gain)

fun PatPattern<String>.playDirt(
    player: DirtSamplePlayer,
    cps: Number = 1.0,
    ahead: Number = 0.02,
    dispatchOnMain: Boolean = true,
    clock: PatClock? = Box.context.get()?.patClock ?: musicClock(),
    speed: (PatScheduledEvent<String>) -> Float = { 1f },
    gain: (PatScheduledEvent<String>) -> Float = { 1f },
    pan: (PatScheduledEvent<String>) -> Float = { 0f }
): PatResponseSpec<String> = respondAhead(
    ahead = ahead,
    cps = cps,
    dispatchOnMain = dispatchOnMain,
    clock = clock
) { event ->
    player.trigger(
        token = event.value,
        speed = speed(event),
        gain = gain(event),
        pan = pan(event)
    )
}

class StereoAudioSample(
    val left: FloatArray,
    val right: FloatArray = left,
    val sampleRate: Float = 44_100f
) {
    init {
        require(left.isNotEmpty()) { "StereoAudioSample must contain at least one sample" }
        require(right.size == left.size) { "StereoAudioSample channels must have equal length" }
    }

    val lengthSamples: Int
        get() = left.size

    val lengthSeconds: Float
        get() = left.size / sampleRate

    val isMono: Boolean
        get() = left === right

    fun readLeft(position: Double): Float = interpolate(left, position)

    fun readRight(position: Double): Float = interpolate(right, position)

    companion object {
        fun fromMono(sample: AudioSample): StereoAudioSample =
            StereoAudioSample(sample.data, sample.data, sample.sampleRate)
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "lengthSamples" to lengthSamples,
        "lengthSeconds" to lengthSeconds,
        "sampleRate" to sampleRate,
        "isMono" to isMono
    )
}

data class DirtSampleToken(
    val family: String,
    val variant: Int = 0
) {
    companion object {
        fun parse(token: String): DirtSampleToken? {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return null
            val colon = trimmed.lastIndexOf(':')
            if (colon <= 0 || colon >= trimmed.lastIndex) return DirtSampleToken(trimmed, 0)
            val suffix = trimmed.substring(colon + 1)
            if (!suffix.all(Char::isDigit)) return DirtSampleToken(trimmed, 0)
            return DirtSampleToken(trimmed.substring(0, colon), suffix.toInt())
        }
    }

    override fun toString(): String = if (variant == 0) family else "$family:$variant"
}

class DirtSampleDescriptor internal constructor(
    val family: String,
    val variant: Int,
    val path: Path,
    val relativePath: String,
    private val onFirstLoad: () -> Unit
) : LiveGraphInspectable {
    @Volatile
    private var cached: StereoAudioSample? = null

    @Volatile
    private var failure: Throwable? = null

    fun peekLoaded(): StereoAudioSample? = cached

    fun getOrLoad(): StereoAudioSample {
        cached?.let { return it }
        failure?.let { throw IllegalStateException("Failed to load Dirt sample $path", it) }
        synchronized(this) {
            cached?.let { return it }
            failure?.let { throw IllegalStateException("Failed to load Dirt sample $path", it) }
            return try {
                StereoWavSampleLoader.load(path).also {
                    cached = it
                    onFirstLoad()
                }
            } catch (t: Throwable) {
                failure = t
                throw t
            }
        }
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("family") { family },
        LiveGraphEdge("variant") { variant },
        LiveGraphEdge("path") { path.toString() },
        LiveGraphEdge("relativePath") { relativePath },
        LiveGraphEdge("loaded") { cached != null }
    )

    override fun toString(): String = synthPrettyObject(
        this,
        "token" to DirtSampleToken(family, variant),
        "relativePath" to relativePath,
        "loaded" to (cached != null)
    )
}

class DirtSampleFamily internal constructor(
    val name: String,
    val entries: List<DirtSampleDescriptor>
) {
    val size: Int
        get() = entries.size

    fun descriptor(index: Int = 0): DirtSampleDescriptor? {
        if (entries.isEmpty()) return null
        return entries[floorMod(index, entries.size)]
    }

    fun sample(index: Int = 0): StereoAudioSample? = descriptor(index)?.getOrLoad()

    override fun toString(): String = synthPrettyObject(
        this,
        "name" to name,
        "size" to size,
        "entries" to entries.take(3)
    )
}

class DirtSampleLibrary private constructor(
    val root: Path,
    preload: Boolean
) : LiveGraphInspectable, AutoCloseable {
    companion object {
        private val sharedLibraries = ConcurrentHashMap<Path, DirtSampleLibrary>()

        fun shared(path: String, preload: Boolean = true): DirtSampleLibrary =
            shared(Paths.get(path), preload)

        fun shared(path: Path, preload: Boolean = true): DirtSampleLibrary {
            val normalized = path.toAbsolutePath().normalize()
            return sharedLibraries.compute(normalized) { _, existing ->
                when {
                    existing == null -> DirtSampleLibrary(normalized, preload)
                    preload -> {
                        existing.preloadAllAsync()
                        existing
                    }
                    else -> existing
                }
            }!!
        }
    }

    private val loadedCount = AtomicInteger(0)
    private val preloadStarted = AtomicBoolean(false)
    private val preloadFuture = CompletableFuture<Int>()
    private val executor = Executors.newSingleThreadExecutor(DirtThreadFactory(root.fileName?.toString() ?: "dirt"))
    private val closed = AtomicBoolean(false)
    private val descriptorsByFamilyName: Map<String, DirtSampleFamily>
    private val descriptorsInOrder: List<DirtSampleDescriptor>

    init {
        require(root.isDirectory()) { "Dirt sample root is not a directory: $root" }
        val (families, descriptors) = scanFamilies(root)
        descriptorsByFamilyName = families
        descriptorsInOrder = descriptors
        if (preload) preloadAllAsync()
    }

    val families: Map<String, DirtSampleFamily>
        get() = descriptorsByFamilyName

    val totalSamples: Int
        get() = descriptorsInOrder.size

    val ready: Boolean
        get() = preloadFuture.isDone

    val loadedSamples: Int
        get() = loadedCount.get()

    fun family(name: String): DirtSampleFamily? {
        val exact = descriptorsByFamilyName[name]
        if (exact != null) return exact
        return descriptorsByFamilyName[name.lowercase()]
    }

    fun descriptor(token: String): DirtSampleDescriptor? {
        val parsed = DirtSampleToken.parse(token) ?: return null
        return family(parsed.family)?.descriptor(parsed.variant)
    }

    fun sample(token: String): StereoAudioSample? = descriptor(token)?.getOrLoad()

    fun preloadAllAsync(): CompletableFuture<Int> {
        if (preloadStarted.compareAndSet(false, true)) {
            executor.execute {
                try {
                    descriptorsInOrder.forEach { descriptor ->
                        try {
                            descriptor.getOrLoad()
                        } catch (_: Throwable) {
                            // Keep preloading other files; missing or malformed assets
                            // should not stop the rest of the library from becoming usable.
                        }
                    }
                    preloadFuture.complete(loadedCount.get())
                } catch (t: Throwable) {
                    preloadFuture.completeExceptionally(t)
                }
            }
        }
        return preloadFuture
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown()
        }
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("root") { root.toString() },
        LiveGraphEdge("familyCount") { families.size },
        LiveGraphEdge("totalSamples") { totalSamples },
        LiveGraphEdge("loadedSamples") { loadedSamples },
        LiveGraphEdge("ready") { ready }
    )

    private fun scanFamilies(root: Path): Pair<Map<String, DirtSampleFamily>, List<DirtSampleDescriptor>> {
        val allDescriptors = mutableListOf<DirtSampleDescriptor>()
        val families = mutableMapOf<String, DirtSampleFamily>()
        Files.list(root).use { children ->
            children.filter { it.isDirectory() }.sorted(compareBy { it.fileName.toString() }).forEach { familyDir ->
                val familyName = familyDir.fileName.toString()
                val files = Files.walk(familyDir).use { walk ->
                    walk.filter { Files.isRegularFile(it) && it.extension.equals("wav", ignoreCase = true) }
                        .sorted(compareBy { familyDir.relativize(it).toString() })
                        .toList()
                }
                val descriptors = files.mapIndexed { index, file ->
                    DirtSampleDescriptor(
                        family = familyName,
                        variant = index,
                        path = file,
                        relativePath = familyDir.relativize(file).toString(),
                        onFirstLoad = { loadedCount.incrementAndGet() }
                    )
                }
                families[familyName] = DirtSampleFamily(familyName, descriptors)
                allDescriptors += descriptors
            }
        }
        return families.toMap() to allDescriptors
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "root" to root,
        "families" to families.keys.sorted().take(6),
        "totalSamples" to totalSamples,
        "loadedSamples" to loadedSamples,
        "ready" to ready
    )
}

class DirtSamplePlayer(
    val library: DirtSampleLibrary,
    val maxVoices: Int = 32,
    val masterGain: Param = Param(1f)
) : StereoNode(), LiveGraphInspectable {
    private data class Voice(
        var active: Boolean = false,
        var sample: StereoAudioSample? = null,
        var position: Double = 0.0,
        var start: Double = 0.0,
        var end: Double = 0.0,
        var speed: Double = 1.0,
        var gainL: Float = 1f,
        var gainR: Float = 1f,
        var triggerId: Long = 0L
    )

    private val voices = Array(maxVoices.coerceAtLeast(1)) { Voice() }
    private val triggerIds = AtomicLong(0L)
    private val lock = Any()
    private var gainBuf = FloatArray(0)

    fun play(
        token: String,
        speed: Number = 1.0,
        gain: Number = 1.0,
        pan: Number = 0.0,
        start: Number = 0.0,
        end: Number = 1.0
    ): Boolean = trigger(token, speed, gain, pan, start, end)

    fun trigger(
        token: String,
        speed: Number = 1.0,
        gain: Number = 1.0,
        pan: Number = 0.0,
        start: Number = 0.0,
        end: Number = 1.0
    ): Boolean {
        val sample = library.sample(token) ?: return false
        return trigger(sample, speed, gain, pan, start, end)
    }

    fun trigger(
        sample: StereoAudioSample,
        speed: Number = 1.0,
        gain: Number = 1.0,
        pan: Number = 0.0,
        start: Number = 0.0,
        end: Number = 1.0
    ): Boolean {
        val playbackSpeed = speed.toDouble()
        if (!playbackSpeed.isFinite() || playbackSpeed == 0.0) return false

        val sliceA = start.toDouble().coerceIn(0.0, 1.0)
        val sliceB = end.toDouble().coerceIn(0.0, 1.0)
        val startNorm = min(sliceA, sliceB)
        val endNorm = max(sliceA, sliceB)
        val startSample = sample.lengthSamples * startNorm
        val endSample = sample.lengthSamples * endNorm
        if (abs(endSample - startSample) < 1.0) return false

        val (leftGain, rightGain) = equalPowerPan(
            gain = gain.toFloat(),
            pan = pan.toFloat()
        )
        val reverse = playbackSpeed < 0.0
        val triggerId = triggerIds.incrementAndGet()

        synchronized(lock) {
            val target = voices.firstOrNull { !it.active } ?: voices.minByOrNull { it.triggerId } ?: return false
            target.active = true
            target.sample = sample
            target.start = startSample
            target.end = endSample
            target.position = if (reverse) endSample else startSample
            target.speed = playbackSpeed
            target.gainL = leftGain
            target.gainR = rightGain
            target.triggerId = triggerId
        }
        return true
    }

    fun stopAll() {
        synchronized(lock) {
            voices.forEach { voice ->
                voice.active = false
                voice.sample = null
            }
        }
    }

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (gainBuf.size < numFrames) gainBuf = FloatArray(numFrames)
        masterGain.render(gainBuf, numFrames, ctx)

        synchronized(lock) {
            for (i in 0 until numFrames) {
                var left = 0f
                var right = 0f

                voices.forEach { voice ->
                    if (!voice.active) return@forEach
                    val sample = voice.sample ?: run {
                        voice.active = false
                        return@forEach
                    }
                    if (!voice.insideBounds()) {
                        voice.active = false
                        return@forEach
                    }

                    val sampleL = sample.readLeft(voice.position) * voice.gainL
                    val sampleR = sample.readRight(voice.position) * voice.gainR
                    left += sampleL
                    right += sampleR

                    voice.position += voice.speed * sample.sampleRate / ctx.sampleRate
                    if (!voice.insideBounds()) {
                        voice.active = false
                    }
                }

                outL[i] = left * gainBuf[i]
                outR[i] = right * gainBuf[i]
            }
        }
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("library") { library },
        LiveGraphEdge("masterGain") { masterGain },
        LiveGraphEdge("maxVoices") { maxVoices },
        LiveGraphEdge("activeVoices") { synchronized(lock) { voices.count { it.active } } }
    )

    private fun Voice.insideBounds(): Boolean {
        return if (speed >= 0.0) {
            position < end
        } else {
            position > start
        }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "library" to library,
        "maxVoices" to maxVoices,
        "activeVoices" to synchronized(lock) { voices.count { it.active } },
        "masterGain" to masterGain
    )
}

private object StereoWavSampleLoader {
    fun load(path: Path): StereoAudioSample {
        require(Files.exists(path)) { "WAV file not found: $path" }
        val bytes = Files.readAllBytes(path)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun readFourCC(): String {
            val id = ByteArray(4)
            buf.get(id)
            return String(id, Charsets.US_ASCII)
        }

        fun skipChunkPadding(size: Int) {
            if (size % 2 == 1 && buf.remaining() > 0) {
                buf.position(buf.position() + 1)
            }
        }

        val riff = readFourCC()
        require(riff == "RIFF") { "Invalid WAV (missing RIFF header): $path" }
        buf.int
        val wave = readFourCC()
        require(wave == "WAVE") { "Invalid WAV (missing WAVE header): $path" }

        var fmtFound = false
        var dataFound = false
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = 0
        var dataSize = 0

        while (buf.remaining() >= 8) {
            val chunkId = readFourCC()
            val chunkSize = buf.int
            val chunkStart = buf.position()

            when (chunkId) {
                "fmt " -> {
                    audioFormat = buf.short.toInt() and 0xFFFF
                    channels = buf.short.toInt() and 0xFFFF
                    sampleRate = buf.int
                    buf.int
                    buf.short
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                    fmtFound = true
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = chunkSize
                    dataFound = true
                }
            }

            buf.position(chunkStart + chunkSize)
            skipChunkPadding(chunkSize)
            if (fmtFound && dataFound) break
        }

        require(fmtFound) { "Invalid WAV (missing fmt chunk): $path" }
        require(dataFound) { "Invalid WAV (missing data chunk): $path" }
        require(audioFormat == 1 || audioFormat == 3) { "Unsupported WAV format (only PCM and IEEE Float supported): $path" }
        require(bitsPerSample == 8 || bitsPerSample == 16 || bitsPerSample == 32 || bitsPerSample == 64) {
            "Unsupported WAV bit depth: $bitsPerSample"
        }
        require(channels == 1 || channels == 2) { "Unsupported WAV channels: $channels" }
        require(dataOffset >= 0 && dataSize >= 0 && dataOffset + dataSize <= bytes.size) {
            "Invalid WAV data chunk bounds: offset=$dataOffset size=$dataSize total=${bytes.size}"
        }

        val raw = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        val bytesPerSample = bitsPerSample / 8
        val frameSize = channels * bytesPerSample
        val frameCount = raw.size / frameSize
        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)
        val rawBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        when {
            audioFormat == 3 && bitsPerSample == 32 -> {
                for (i in 0 until frameCount) {
                    left[i] = rawBuf.float
                    right[i] = if (channels == 2) rawBuf.float else left[i]
                }
            }
            audioFormat == 3 && bitsPerSample == 64 -> {
                for (i in 0 until frameCount) {
                    left[i] = rawBuf.double.toFloat()
                    right[i] = if (channels == 2) rawBuf.double.toFloat() else left[i]
                }
            }
            audioFormat == 1 && bitsPerSample == 16 -> {
                for (i in 0 until frameCount) {
                    left[i] = rawBuf.short.toInt() / 32768.0f
                    right[i] = if (channels == 2) rawBuf.short.toInt() / 32768.0f else left[i]
                }
            }
            audioFormat == 1 && bitsPerSample == 8 -> {
                for (i in 0 until frameCount) {
                    left[i] = ((rawBuf.get().toInt() and 0xFF) - 128) / 128.0f
                    right[i] = if (channels == 2) ((rawBuf.get().toInt() and 0xFF) - 128) / 128.0f else left[i]
                }
            }
            else -> error("Unsupported WAV combination format=$audioFormat bits=$bitsPerSample")
        }

        return if (channels == 1) {
            StereoAudioSample(left, left, sampleRate.toFloat())
        } else {
            StereoAudioSample(left, right, sampleRate.toFloat())
        }
    }
}

private class DirtThreadFactory(private val label: String) : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, "DirtSampleLibrary-$label").apply {
        isDaemon = true
    }
}

private fun equalPowerPan(gain: Float, pan: Float): Pair<Float, Float> {
    val piOver4 = (Math.PI / 4.0).toFloat()
    val clamped = pan.coerceIn(-1f, 1f)
    val angle = clamped * piOver4
    return (gain * kotlin.math.cos(angle + piOver4)) to (gain * kotlin.math.sin(angle + piOver4))
}

private fun interpolate(samples: FloatArray, position: Double): Float {
    if (samples.isEmpty()) return 0f
    val clamped = position.coerceIn(0.0, (samples.lastIndex).toDouble())
    val idx = clamped.toInt()
    val frac = (clamped - idx).toFloat()
    val s0 = samples[(idx - 1).coerceAtLeast(0)]
    val s1 = samples[idx]
    val s2 = samples[(idx + 1).coerceAtMost(samples.lastIndex)]
    val s3 = samples[(idx + 2).coerceAtMost(samples.lastIndex)]
    return hermite4(frac, s0, s1, s2, s3)
}

private fun hermite4(frac: Float, s0: Float, s1: Float, s2: Float, s3: Float): Float {
    val c0 = s1
    val c1 = 0.5f * (s2 - s0)
    val c2 = s0 - 2.5f * s1 + 2f * s2 - 0.5f * s3
    val c3 = 0.5f * (s3 - s0) + 1.5f * (s1 - s2)
    return ((c3 * frac + c2) * frac + c1) * frac + c0
}

private fun floorMod(value: Int, modulus: Int): Int {
    require(modulus > 0) { "Modulus must be positive" }
    val mod = value % modulus
    return if (mod < 0) mod + modulus else mod
}
