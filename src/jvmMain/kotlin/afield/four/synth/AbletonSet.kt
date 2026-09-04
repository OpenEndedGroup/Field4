@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.four.audio.WavFloatArrayLoader
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.ShortMessage
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.cos
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class AbletonSet(
    val source: File,
    val tempoMap: AbletonTempoMap,
    val tracks: List<AbletonTrack>,
    val mainTrack: AbletonTrack? = null
) {
    val clips: List<AbletonClip> = tracks.flatMap { it.clips }
    val allTracks: List<AbletonTrack> = tracks + listOfNotNull(mainTrack)
    val automationEnvelopes: List<AbletonAutomationEnvelope> = allTracks.flatMap { it.automation }

    fun clipsPlayingAtBeat(arrangementBeat: Double): List<AbletonClipPlayback> =
        clipsPlayingAtBeatInternal(arrangementBeat, sampleMapper = null)

    fun clipsPlayingAtBeat(
        arrangementBeat: Double,
        sampleMapper: AbletonSampleFileMapper
    ): List<AbletonClipPlayback> =
        clipsPlayingAtBeatInternal(arrangementBeat, sampleMapper)

    private fun clipsPlayingAtBeatInternal(
        arrangementBeat: Double,
        sampleMapper: AbletonSampleFileMapper?
    ): List<AbletonClipPlayback> =
        tracks.flatMap { track ->
            track.clips
                .filter { it.containsBeat(arrangementBeat) }
                .map { clip ->
                    AbletonClipPlayback(
                        track = track,
                        clip = clip,
                        arrangementBeat = arrangementBeat,
                        arrangementSeconds = tempoMap.beatToSeconds(arrangementBeat),
                        sourceSeconds = clip.sourceSecondsAtBeat(arrangementBeat, tempoMap),
                        sourceFile = sampleMapper?.resolve(clip)
                    )
                }
        }

    fun clipsPlayingAtSeconds(arrangementSeconds: Double): List<AbletonClipPlayback> =
        clipsPlayingAtBeat(tempoMap.secondsToBeat(arrangementSeconds))

    fun clipsPlayingAtSeconds(
        arrangementSeconds: Double,
        sampleMapper: AbletonSampleFileMapper
    ): List<AbletonClipPlayback> =
        clipsPlayingAtBeat(tempoMap.secondsToBeat(arrangementSeconds), sampleMapper)

    fun trackVolumesAtBeat(
        arrangementBeat: Double,
        sampleMapper: AbletonSampleFileMapper,
        analysisCache: AbletonSampleVolumeAnalysisCache = AbletonSampleVolumeAnalysisCache.shared
    ): List<AbletonTrackVolume> {
        val byTrack = clipsPlayingAtBeat(arrangementBeat, sampleMapper)
            .mapNotNull { playback -> playback.volumeContribution(analysisCache) }
            .groupBy { it.track }

        return byTrack.map { (track, contributions) ->
            AbletonTrackVolume(
                track = track,
                arrangementBeat = arrangementBeat,
                arrangementSeconds = tempoMap.beatToSeconds(arrangementBeat),
                bands = contributions.rootSumSquareBands(),
                clips = contributions
            )
        }.sortedBy { it.track.index }
    }

    fun trackVolumesAtSeconds(
        arrangementSeconds: Double,
        sampleMapper: AbletonSampleFileMapper,
        analysisCache: AbletonSampleVolumeAnalysisCache = AbletonSampleVolumeAnalysisCache.shared
    ): List<AbletonTrackVolume> =
        trackVolumesAtBeat(tempoMap.secondsToBeat(arrangementSeconds), sampleMapper, analysisCache)

    fun dominantPitchesAtBeat(
        arrangementBeat: Double,
        sampleMapper: AbletonSampleFileMapper,
        pitchCache: AbletonSamplePitchAnalysisCache = AbletonSamplePitchAnalysisCache.shared
    ): List<AbletonClipPitch> =
        clipsPlayingAtBeat(arrangementBeat, sampleMapper)
            .mapNotNull { playback -> playback.pitchContribution(pitchCache) }
            .sortedWith(compareBy<AbletonClipPitch> { it.track.index }.thenBy { it.clip.currentStartBeat })

    fun dominantPitchesAtSeconds(
        arrangementSeconds: Double,
        sampleMapper: AbletonSampleFileMapper,
        pitchCache: AbletonSamplePitchAnalysisCache = AbletonSamplePitchAnalysisCache.shared
    ): List<AbletonClipPitch> =
        dominantPitchesAtBeat(tempoMap.secondsToBeat(arrangementSeconds), sampleMapper, pitchCache)

    fun dominantPitchesByTrackAtBeat(
        arrangementBeat: Double,
        sampleMapper: AbletonSampleFileMapper,
        pitchCache: AbletonSamplePitchAnalysisCache = AbletonSamplePitchAnalysisCache.shared
    ): List<AbletonTrackPitch> {
        val clipPitches = dominantPitchesAtBeat(arrangementBeat, sampleMapper, pitchCache)
        return clipPitches
            .groupBy { it.track }
            .mapNotNull { (track, contributions) ->
                val dominant = contributions.dominantClipPitchOrNull() ?: return@mapNotNull null
                AbletonTrackPitch(
                    track = track,
                    arrangementBeat = arrangementBeat,
                    arrangementSeconds = tempoMap.beatToSeconds(arrangementBeat),
                    pitch = dominant.pitch,
                    clips = contributions
                )
            }
            .sortedBy { it.track.index }
    }

    fun dominantPitchesByTrackAtSeconds(
        arrangementSeconds: Double,
        sampleMapper: AbletonSampleFileMapper,
        pitchCache: AbletonSamplePitchAnalysisCache = AbletonSamplePitchAnalysisCache.shared
    ): List<AbletonTrackPitch> =
        dominantPitchesByTrackAtBeat(tempoMap.secondsToBeat(arrangementSeconds), sampleMapper, pitchCache)

    fun track(name: String): AbletonTrack? = tracks.firstOrNull { it.name == name }

    fun resolveSamples(searchDirectory: File): Map<AbletonClip, File> {
        val mapper = AbletonSampleFileMapper.forDirectory(searchDirectory)
        return clips.mapNotNull { clip -> mapper.resolve(clip)?.let { clip to it } }.toMap()
    }

    companion object {
        fun read(path: String): AbletonSet = read(File(path))

        fun read(file: File): AbletonSet {
            val document = parseXml(file)
            val liveSet = document.documentElement.childElements("LiveSet").firstOrNull()
                ?: error("No <LiveSet> element found in ${file.absolutePath}")
            val masterTrack = liveSet.firstDescendant("MasterTrack")
            val tempoMap = AbletonTempoMap.fromMasterTrack(masterTrack)
            val tracks = liveSet
                .childElements("Tracks")
                .firstOrNull()
                ?.childElements()
                ?.filter { it.tagName in setOf("AudioTrack", "MidiTrack", "GroupTrack", "ReturnTrack") }
                ?.mapIndexed { index, element -> AbletonTrack.fromElement(index, element) }
                ?: emptyList()
            val mainTrack = liveSet.childElements("MainTrack")
                .firstOrNull()
                ?.let { AbletonTrack.fromElement(tracks.size, it) }
            return AbletonSet(file, tempoMap, tracks, mainTrack)
        }

        private fun parseXml(file: File): Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isIgnoringComments = true
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            return file.inputStream().buffered().use { raw ->
                val input = if (file.looksGzipped()) GZIPInputStream(raw) else raw
                input.use {
                    factory.newDocumentBuilder().parse(InputSource(it)).also { document ->
                        document.documentElement.normalize()
                    }
                }
            }
        }

        private fun File.looksGzipped(): Boolean {
            inputStream().use { stream ->
                val first = stream.read()
                val second = stream.read()
                return first == (GZIPInputStream.GZIP_MAGIC and 0xff) &&
                    second == ((GZIPInputStream.GZIP_MAGIC shr 8) and 0xff)
            }
        }
    }
}

data class AbletonTrack(
    val index: Int,
    val id: String,
    val kind: String,
    val name: String,
    val color: Int?,
    val clips: List<AbletonClip>,
    val automation: List<AbletonAutomationEnvelope>
) {
    fun clipsPlayingAtBeat(arrangementBeat: Double): List<AbletonClip> =
        clips.filter { it.containsBeat(arrangementBeat) }

    fun automationValuesAt(arrangementTime: Double): Map<AbletonAutomationTarget, AbletonAutomationValue> =
        automation.associate { it.target to it.valueAt(arrangementTime) }

    companion object {
        internal fun fromElement(index: Int, track: Element): AbletonTrack {
            val nameElement = track.childElements("Name").firstOrNull()
            val name = nameElement?.valueChild("EffectiveName")
                ?: nameElement?.valueChild("UserName")
                ?: "${track.tagName} ${track.attr("Id") ?: index}"
            val targets = track.automationTargetsById()
            val clips = track.descendants()
                .filter { it.tagName == "AudioClip" || it.tagName == "MidiClip" }
                .mapNotNull { AbletonClip.fromElement(it) }
                .sortedBy { it.currentStartBeat }
                .toList()
            val automation = track.childElements("AutomationEnvelopes")
                .firstOrNull()
                ?.firstDescendant("Envelopes")
                ?.childElements("AutomationEnvelope")
                ?.mapNotNull { AbletonAutomationEnvelope.fromElement(it, targets) }
                ?: emptyList()
            return AbletonTrack(
                index = index,
                id = track.attr("Id") ?: index.toString(),
                kind = track.tagName.removeSuffix("Track").ifEmpty { track.tagName }.lowercase(),
                name = name,
                color = track.valueChild("Color")?.toIntOrNull(),
                clips = clips,
                automation = automation
            )
        }
    }
}

data class AbletonClip(
    val id: String,
    val kind: AbletonClipKind,
    val name: String,
    val arrangementTimeBeat: Double,
    val currentStartBeat: Double,
    val currentEndBeat: Double,
    val loop: AbletonClipLoop,
    val disabled: Boolean,
    val sample: AbletonSampleRef?,
    val warpMarkers: List<AbletonWarpMarker>,
    val isWarped: Boolean?
) {
    val durationBeats: Double get() = max(0.0, currentEndBeat - currentStartBeat)

    fun containsBeat(arrangementBeat: Double): Boolean =
        !disabled && arrangementBeat >= currentStartBeat && arrangementBeat < currentEndBeat

    fun sourceSecondsAtBeat(arrangementBeat: Double, tempoMap: AbletonTempoMap): Double {
        val localBeat = localBeatAt(arrangementBeat)
        if (warpMarkers.size >= 2) {
            return warpMarkers.sourceSecondsAtClipBeat(localBeat)
        }

        val localSeconds = tempoMap.beatsToDurationSeconds(localBeat)
        return loop.loopStartSeconds + localSeconds
    }

    fun localBeatAt(arrangementBeat: Double): Double {
        val rawBeat = arrangementBeat - currentStartBeat + loop.startRelativeBeat
        if (!loop.loopOn || loop.loopEndSeconds <= loop.loopStartSeconds) {
            return rawBeat
        }

        val markerStartBeat = warpMarkers.clipBeatAtSourceSecondsOrNull(loop.loopStartSeconds)
        val markerEndBeat = warpMarkers.clipBeatAtSourceSecondsOrNull(loop.loopEndSeconds)
        val loopStartBeat = markerStartBeat ?: 0.0
        val loopLengthBeat = (markerEndBeat ?: durationBeats) - loopStartBeat
        if (loopLengthBeat <= 0.0) return rawBeat
        return loopStartBeat + ((rawBeat - loopStartBeat) positiveMod loopLengthBeat)
    }

    companion object {
        internal fun fromElement(clip: Element): AbletonClip? {
            val arrangementTime = clip.attr("Time")?.toDoubleOrNull() ?: return null
            val loop = AbletonClipLoop.fromElement(clip.childElements("Loop").firstOrNull())
            return AbletonClip(
                id = clip.attr("Id") ?: "",
                kind = if (clip.tagName == "MidiClip") AbletonClipKind.Midi else AbletonClipKind.Audio,
                name = clip.valueChild("Name") ?: "${clip.tagName} ${clip.attr("Id") ?: ""}".trim(),
                arrangementTimeBeat = arrangementTime,
                currentStartBeat = clip.valueChild("CurrentStart")?.toDoubleOrNull() ?: arrangementTime,
                currentEndBeat = clip.valueChild("CurrentEnd")?.toDoubleOrNull() ?: arrangementTime,
                loop = loop,
                disabled = clip.valueChild("Disabled")?.toBooleanStrictOrNull() ?: false,
                sample = clip.childElements("SampleRef").firstOrNull()?.let(AbletonSampleRef::fromElement),
                warpMarkers = clip.firstDescendant("WarpMarkers")
                    ?.childElements("WarpMarker")
                    ?.mapNotNull(AbletonWarpMarker::fromElement)
                    ?.sortedBy { it.clipBeat }
                    ?: emptyList(),
                isWarped = clip.valueChild("IsWarped")?.toBooleanStrictOrNull()
            )
        }
    }
}

enum class AbletonClipKind {
    Audio,
    Midi
}

data class AbletonClipLoop(
    val loopStartSeconds: Double,
    val loopEndSeconds: Double,
    val startRelativeBeat: Double,
    val loopOn: Boolean,
    val outMarkerSeconds: Double?,
    val hiddenLoopStartSeconds: Double?,
    val hiddenLoopEndSeconds: Double?
) {
    companion object {
        internal fun fromElement(loop: Element?): AbletonClipLoop =
            AbletonClipLoop(
                loopStartSeconds = loop?.valueChild("LoopStart")?.toDoubleOrNull() ?: 0.0,
                loopEndSeconds = loop?.valueChild("LoopEnd")?.toDoubleOrNull() ?: 0.0,
                startRelativeBeat = loop?.valueChild("StartRelative")?.toDoubleOrNull() ?: 0.0,
                loopOn = loop?.valueChild("LoopOn")?.toBooleanStrictOrNull() ?: false,
                outMarkerSeconds = loop?.valueChild("OutMarker")?.toDoubleOrNull(),
                hiddenLoopStartSeconds = loop?.valueChild("HiddenLoopStart")?.toDoubleOrNull(),
                hiddenLoopEndSeconds = loop?.valueChild("HiddenLoopEnd")?.toDoubleOrNull()
            )
    }
}

data class AbletonSampleRef(
    val relativePath: String?,
    val absolutePath: String?,
    val defaultDurationSamples: Long?,
    val defaultSampleRate: Double?,
    val originalFileSize: Long?,
    val originalCrc: Long?
) {
    val defaultDurationSeconds: Double?
        get() = if (defaultDurationSamples != null && defaultSampleRate != null && defaultSampleRate > 0.0) {
            defaultDurationSamples / defaultSampleRate
        } else {
            null
        }

    companion object {
        internal fun fromElement(sampleRef: Element): AbletonSampleRef {
            val fileRef = sampleRef.childElements("FileRef").firstOrNull()
            return AbletonSampleRef(
                relativePath = fileRef?.valueChild("RelativePath"),
                absolutePath = fileRef?.valueChild("Path"),
                defaultDurationSamples = sampleRef.valueChild("DefaultDuration")?.toLongOrNull(),
                defaultSampleRate = sampleRef.valueChild("DefaultSampleRate")?.toDoubleOrNull(),
                originalFileSize = fileRef?.valueChild("OriginalFileSize")?.toLongOrNull(),
                originalCrc = fileRef?.valueChild("OriginalCrc")?.toLongOrNull()
            )
        }
    }
}

class AbletonSampleFileMapper private constructor(
    val searchDirectory: File,
    private val filesByName: Map<String, List<File>>,
    private val filesBySuffix: Map<String, List<File>>
) {
    fun resolve(clip: AbletonClip): File? = resolve(clip.sample)

    fun resolve(playback: AbletonClipPlayback): File? = resolve(playback.clip)

    fun resolve(sample: AbletonSampleRef?): File? {
        sample ?: return null
        sample.absolutePath?.let { path ->
            File(path).takeIf { it.isFile }?.let { return it }
        }
        sample.relativePath?.let { path ->
            val relativeToSearch = File(searchDirectory, path).takeIf { it.isFile }
            if (relativeToSearch != null) return relativeToSearch
        }

        val candidates = sample.lookupKeys()
        for (key in candidates) {
            filesBySuffix[key]?.singleOrNull()?.let { return it }
        }
        for (key in candidates) {
            filesBySuffix[key]?.firstOrNull()?.let { return it }
        }

        sample.fileNameKey()?.let { key ->
            filesByName[key]?.singleOrNull()?.let { return it }
            filesByName[key]?.firstOrNull()?.let { return it }
        }
        return null
    }

    fun candidates(sample: AbletonSampleRef?): List<File> {
        sample ?: return emptyList()
        val suffixMatches = sample.lookupKeys().flatMap { filesBySuffix[it].orEmpty() }
        val nameMatches = sample.fileNameKey()?.let { filesByName[it].orEmpty() }.orEmpty()
        return (suffixMatches + nameMatches).distinctBy { it.absolutePath }
    }

    companion object {
        private val cache = ConcurrentHashMap<String, AbletonSampleFileMapper>()

        fun forDirectory(searchDirectory: File): AbletonSampleFileMapper {
            val root = searchDirectory.absoluteFile.normalize()
            val key = root.path
            return cache.getOrPut(key) { build(root) }
        }

        fun clearCache() {
            cache.clear()
        }

        private fun build(root: File): AbletonSampleFileMapper {
            val files = if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile }.toList()
            } else {
                emptyList()
            }
            val byName = files.groupBy { it.name.lowercase() }
            val bySuffix = mutableMapOf<String, MutableList<File>>()
            for (file in files) {
                val relative = file.relativeToOrSelf(root).invariantPath()
                val segments = relative.split('/').filter { it.isNotBlank() }
                for (index in segments.indices) {
                    val suffix = segments.drop(index).joinToString("/")
                    bySuffix.getOrPut(suffix.lowercase()) { mutableListOf() }.add(file)
                }
            }
            return AbletonSampleFileMapper(
                searchDirectory = root,
                filesByName = byName,
                filesBySuffix = bySuffix
            )
        }
    }
}

data class AbletonWarpMarker(
    val id: String,
    val sourceSeconds: Double,
    val clipBeat: Double
) {
    companion object {
        internal fun fromElement(marker: Element): AbletonWarpMarker? {
            val sourceSeconds = marker.attr("SecTime")?.toDoubleOrNull() ?: return null
            val clipBeat = marker.attr("BeatTime")?.toDoubleOrNull() ?: return null
            return AbletonWarpMarker(
                id = marker.attr("Id") ?: "",
                sourceSeconds = sourceSeconds,
                clipBeat = clipBeat
            )
        }
    }
}

data class AbletonClipPlayback(
    val track: AbletonTrack,
    val clip: AbletonClip,
    val arrangementBeat: Double,
    val arrangementSeconds: Double,
    val sourceSeconds: Double,
    val sourceFile: File? = null
) {
    val sourcePath: String? get() = sourceFile?.absolutePath ?: originalSourcePath
    val originalSourcePath: String? get() = clip.sample?.relativePath ?: clip.sample?.absolutePath
}

data class AbletonTrackVolume(
    val track: AbletonTrack,
    val arrangementBeat: Double,
    val arrangementSeconds: Double,
    val bands: AbletonVolumeBands,
    val clips: List<AbletonClipVolume>
) {
    val rms: Double get() = bands.rms
}

data class AbletonClipVolume(
    val track: AbletonTrack,
    val clip: AbletonClip,
    val sourceFile: File,
    val sourceSeconds: Double,
    val bands: AbletonVolumeBands
) {
    val rms: Double get() = bands.rms
}

data class AbletonVolumeBands(
    val low: Double,
    val mid: Double,
    val high: Double
) {
    val rms: Double get() = sqrt((low * low + mid * mid + high * high) / 3.0)

    companion object {
        val Zero = AbletonVolumeBands(0.0, 0.0, 0.0)
    }
}

data class AbletonSampleVolumeAnalysisConfig(
    val windowSeconds: Double = 0.150,
    val hopSeconds: Double = 0.005,
    val lowCutoffHz: Double = 250.0,
    val highCutoffHz: Double = 2_000.0,
    val fftSize: Int = 2048
) {
    init {
        require(windowSeconds > 0.0) { "windowSeconds must be > 0" }
        require(hopSeconds > 0.0) { "hopSeconds must be > 0" }
        require(lowCutoffHz > 0.0) { "lowCutoffHz must be > 0" }
        require(highCutoffHz > lowCutoffHz) { "highCutoffHz must be > lowCutoffHz" }
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }
}

data class AbletonSampleVolumeAnalysis(
    val sourceFile: File,
    val sampleRate: Int,
    val sampleCount: Int,
    val config: AbletonSampleVolumeAnalysisConfig,
    val hopSeconds: Double,
    val frames: List<AbletonVolumeBands>
) {
    val durationSeconds: Double get() = sampleCount.toDouble() / sampleRate.toDouble()

    fun volumeAtSeconds(sourceSeconds: Double): AbletonVolumeBands {
        if (frames.isEmpty() || sourceSeconds < 0.0 || sourceSeconds > durationSeconds) return AbletonVolumeBands.Zero
        val frame = sourceSeconds / hopSeconds
        val leftIndex = frame.toInt().coerceIn(0, frames.lastIndex)
        val rightIndex = min(leftIndex + 1, frames.lastIndex)
        val t = (frame - leftIndex).coerceIn(0.0, 1.0)
        return frames[leftIndex].lerp(frames[rightIndex], t)
    }

    companion object {
        fun analyze(
            sourceFile: File,
            config: AbletonSampleVolumeAnalysisConfig = AbletonSampleVolumeAnalysisConfig()
        ): AbletonSampleVolumeAnalysis {
            val wav = WavFloatArrayLoader.loadWavToFloatArray(sourceFile.absolutePath)
            val hopSamples = max(1, (config.hopSeconds * wav.sampleRate).roundToInt())
            val frames = analyzeFrames(wav.samples, wav.sampleRate, config, hopSamples)
            return AbletonSampleVolumeAnalysis(
                sourceFile = sourceFile.absoluteFile.normalize(),
                sampleRate = wav.sampleRate,
                sampleCount = wav.samples.size,
                config = config,
                hopSeconds = hopSamples.toDouble() / wav.sampleRate.toDouble(),
                frames = frames
            )
        }

        private fun analyzeFrames(
            samples: FloatArray,
            sampleRate: Int,
            config: AbletonSampleVolumeAnalysisConfig,
            hopSamples: Int
        ): List<AbletonVolumeBands> {
            if (samples.isEmpty()) return emptyList()
            val fftSize = max(config.fftSize, nextPowerOfTwo((config.windowSeconds * sampleRate).toInt().coerceAtLeast(2)))
            val complex = DoubleArray(fftSize * 2)
            val windowSamples = min(samples.size, (config.windowSeconds * sampleRate).toInt().coerceAtLeast(1))
            val frameCount = max(1, ((samples.size - 1) / hopSamples) + 1)

            return List(frameCount) { frameIndex ->
                val center = frameIndex * hopSamples
                val start = center - windowSamples / 2
                java.util.Arrays.fill(complex, 0.0)
                for (i in 0 until windowSamples) {
                    val sampleIndex = start + i
                    if (sampleIndex !in samples.indices) continue
                    val window = hann(i, windowSamples)
                    complex[i * 2] = samples[sampleIndex].toDouble() * window
                }
                forwardFftDouble(complex, fftSize)
                spectralBands(complex, fftSize, sampleRate, config)
            }
        }

        private fun spectralBands(
            complex: DoubleArray,
            fftSize: Int,
            sampleRate: Int,
            config: AbletonSampleVolumeAnalysisConfig
        ): AbletonVolumeBands {
            var low = 0.0
            var mid = 0.0
            var high = 0.0
            var lowCount = 0
            var midCount = 0
            var highCount = 0

            for (bin in 1..fftSize / 2) {
                val frequency = bin.toDouble() * sampleRate.toDouble() / fftSize.toDouble()
                val re = complex[bin * 2]
                val im = complex[bin * 2 + 1]
                val power = re * re + im * im
                when {
                    frequency < config.lowCutoffHz -> {
                        low += power
                        lowCount++
                    }
                    frequency < config.highCutoffHz -> {
                        mid += power
                        midCount++
                    }
                    else -> {
                        high += power
                        highCount++
                    }
                }
            }

            val scale = 2.0 / fftSize.toDouble()
            return AbletonVolumeBands(
                low = bandRms(low, lowCount, scale),
                mid = bandRms(mid, midCount, scale),
                high = bandRms(high, highCount, scale)
            )
        }

        private fun bandRms(power: Double, count: Int, scale: Double): Double =
            if (count <= 0) 0.0 else sqrt(power / count.toDouble()) * scale

        private fun hann(index: Int, size: Int): Double =
            if (size <= 1) 1.0 else 0.5 - 0.5 * cos((2.0 * PI * index) / (size - 1))

        private fun forwardFftDouble(data: DoubleArray, size: Int) {
            var j = 0
            for (i in 0 until size) {
                if (j > i) {
                    val ai = i * 2
                    val aj = j * 2
                    val re = data[ai]
                    val im = data[ai + 1]
                    data[ai] = data[aj]
                    data[ai + 1] = data[aj + 1]
                    data[aj] = re
                    data[aj + 1] = im
                }
                var bit = size shr 1
                while (bit >= 1 && j >= bit) {
                    j -= bit
                    bit = bit shr 1
                }
                j += bit
            }

            var step = 2
            while (step <= size) {
                val halfStep = step / 2
                val theta = -2.0 * PI / step.toDouble()
                val wStepRe = cos(theta)
                val wStepIm = kotlin.math.sin(theta)
                for (group in 0 until size step step) {
                    var wRe = 1.0
                    var wIm = 0.0
                    for (pair in 0 until halfStep) {
                        val even = (group + pair) * 2
                        val odd = (group + pair + halfStep) * 2
                        val oddRe = data[odd]
                        val oddIm = data[odd + 1]
                        val twiddledRe = wRe * oddRe - wIm * oddIm
                        val twiddledIm = wRe * oddIm + wIm * oddRe
                        val evenRe = data[even]
                        val evenIm = data[even + 1]
                        data[even] = evenRe + twiddledRe
                        data[even + 1] = evenIm + twiddledIm
                        data[odd] = evenRe - twiddledRe
                        data[odd + 1] = evenIm - twiddledIm

                        val nextWRe = wRe * wStepRe - wIm * wStepIm
                        val nextWIm = wRe * wStepIm + wIm * wStepRe
                        wRe = nextWRe
                        wIm = nextWIm
                    }
                }
                step = step shl 1
            }
        }

        private fun nextPowerOfTwo(value: Int): Int {
            var out = 1
            while (out < value) out = out shl 1
            return out
        }
    }
}

class AbletonSampleVolumeAnalysisCache(
    private val config: AbletonSampleVolumeAnalysisConfig = AbletonSampleVolumeAnalysisConfig()
) {
    private val cache = ConcurrentHashMap<AbletonSampleAnalysisKey, AbletonSampleVolumeAnalysis>()

    fun analysisFor(sourceFile: File): AbletonSampleVolumeAnalysis {
        val file = sourceFile.absoluteFile.normalize()
        val key = AbletonSampleAnalysisKey(
            path = file.absolutePath,
            lastModified = file.lastModified(),
            length = file.length(),
            config = config
        )
        cache[key]?.let { return it }

        return cache.computeIfAbsent(key) { loadFromDiskOrAnalyze(file, key) }
    }

    fun clear() {
        cache.clear()
    }

    companion object {
        val shared = AbletonSampleVolumeAnalysisCache()
    }

    private fun loadFromDiskOrAnalyze(file: File, key: AbletonSampleAnalysisKey): AbletonSampleVolumeAnalysis {
        val cacheFile = diskCacheFileFor(file, config)
        readDiskCache(cacheFile, file, key)?.let { analysis ->
            println(
                "Ableton volume analysis: loaded cache ${cacheFile.name} " +
                    "for ${file.name} frames=${analysis.frames.size}"
            )
            return analysis
        }

        val startNs = System.nanoTime()
        println(
            "Ableton volume analysis: computing ${file.absolutePath} " +
                "(${formatByteCount(file.length())}, window=${config.windowSeconds}s, hop=${config.hopSeconds}s, fft=${config.fftSize})"
        )
        return AbletonSampleVolumeAnalysis.analyze(file, config).also { analysis ->
            writeDiskCache(cacheFile, file, key, analysis)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            println(
                "Ableton volume analysis: cached ${file.name} " +
                    "frames=${analysis.frames.size}, duration=${"%.3f".format(analysis.durationSeconds)}s, " +
                    "elapsed=${"%.1f".format(elapsedMs)}ms, disk=${cacheFile.name}"
            )
        }
    }
}

private const val ABLETON_VOLUME_CACHE_MAGIC = "F4 Ableton Volume Analysis"
private const val ABLETON_VOLUME_CACHE_VERSION = 3

private fun diskCacheFileFor(file: File, config: AbletonSampleVolumeAnalysisConfig): File {
    val suffix = listOf(
        "w${config.windowSeconds.cacheToken()}",
        "h${config.hopSeconds.cacheToken()}",
        "l${config.lowCutoffHz.cacheToken()}",
        "u${config.highCutoffHz.cacheToken()}",
        "f${config.fftSize}"
    ).joinToString("-")
    return File(file.parentFile ?: File("."), "${file.name}.field4-volume-$suffix.bin")
}

private fun readDiskCache(
    cacheFile: File,
    sourceFile: File,
    key: AbletonSampleAnalysisKey
): AbletonSampleVolumeAnalysis? {
    if (!cacheFile.isFile || cacheFile.lastModified() < sourceFile.lastModified()) return null

    return runCatching {
        DataInputStream(BufferedInputStream(cacheFile.inputStream())).use { input ->
            if (input.readUTF() != ABLETON_VOLUME_CACHE_MAGIC) return null
            if (input.readInt() != ABLETON_VOLUME_CACHE_VERSION) return null
            if (input.readUTF() != key.path) return null
            if (input.readLong() != key.lastModified) return null
            if (input.readLong() != key.length) return null

            val storedConfig = AbletonSampleVolumeAnalysisConfig(
                windowSeconds = input.readDouble(),
                hopSeconds = input.readDouble(),
                lowCutoffHz = input.readDouble(),
                highCutoffHz = input.readDouble(),
                fftSize = input.readInt()
            )
            if (storedConfig != key.config) return null

            val sampleRate = input.readInt()
            val sampleCount = input.readInt()
            val actualHopSeconds = input.readDouble()
            val frameCount = input.readInt()
            if (sampleRate <= 0 || sampleCount < 0 || actualHopSeconds <= 0.0 || frameCount < 0) return null

            val frames = List(frameCount) {
                AbletonVolumeBands(
                    low = input.readDouble(),
                    mid = input.readDouble(),
                    high = input.readDouble()
                )
            }
            AbletonSampleVolumeAnalysis(
                sourceFile = sourceFile.absoluteFile.normalize(),
                sampleRate = sampleRate,
                sampleCount = sampleCount,
                config = key.config,
                hopSeconds = actualHopSeconds,
                frames = frames
            )
        }
    }.getOrNull()
}

private fun writeDiskCache(
    cacheFile: File,
    sourceFile: File,
    key: AbletonSampleAnalysisKey,
    analysis: AbletonSampleVolumeAnalysis
) {
    runCatching {
        cacheFile.parentFile?.mkdirs()
        val tmp = File(cacheFile.parentFile ?: File("."), "${cacheFile.name}.tmp-${System.nanoTime()}")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { output ->
            output.writeUTF(ABLETON_VOLUME_CACHE_MAGIC)
            output.writeInt(ABLETON_VOLUME_CACHE_VERSION)
            output.writeUTF(key.path)
            output.writeLong(key.lastModified)
            output.writeLong(key.length)
            output.writeDouble(key.config.windowSeconds)
            output.writeDouble(key.config.hopSeconds)
            output.writeDouble(key.config.lowCutoffHz)
            output.writeDouble(key.config.highCutoffHz)
            output.writeInt(key.config.fftSize)
            output.writeInt(analysis.sampleRate)
            output.writeInt(analysis.sampleCount)
            output.writeDouble(analysis.hopSeconds)
            output.writeInt(analysis.frames.size)
            for (frame in analysis.frames) {
                output.writeDouble(frame.low)
                output.writeDouble(frame.mid)
                output.writeDouble(frame.high)
            }
        }
        Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        cacheFile.setLastModified(sourceFile.lastModified().coerceAtLeast(System.currentTimeMillis()))
    }.onFailure { error ->
        println("Ableton volume analysis: could not write disk cache ${cacheFile.absolutePath}: ${error.message}")
    }
}

private fun Double.cacheToken(): String =
    toString().replace('-', 'm').replace('.', 'p')

private fun formatByteCount(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) {
        "$bytes ${units[unit]}"
    } else {
        "%.1f %s".format(value, units[unit])
    }
}

private data class AbletonSampleAnalysisKey(
    val path: String,
    val lastModified: Long,
    val length: Long,
    val config: AbletonSampleVolumeAnalysisConfig
)

data class AbletonPitchEstimate(
    val midiPitch: Int,
    val velocity: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val channel: Int
) {
    val frequencyHz: Double get() = midiToFrequency(midiPitch.toDouble())
}

data class AbletonClipPitch(
    val track: AbletonTrack,
    val clip: AbletonClip,
    val sourceFile: File,
    val sourceSeconds: Double,
    val pitch: AbletonPitchEstimate
)

data class AbletonTrackPitch(
    val track: AbletonTrack,
    val arrangementBeat: Double,
    val arrangementSeconds: Double,
    val pitch: AbletonPitchEstimate,
    val clips: List<AbletonClipPitch>
)

data class AbletonSamplePitchAnalysis(
    val sourceFile: File,
    val midiFile: File?,
    val sourceLastModified: Long,
    val sourceLength: Long,
    val notes: List<AbletonPitchEstimate>
) {
    fun dominantPitchAtSeconds(sourceSeconds: Double): AbletonPitchEstimate? =
        notes.asSequence()
            .filter { sourceSeconds >= it.startSeconds && sourceSeconds < it.endSeconds }
            .maxWithOrNull(compareBy<AbletonPitchEstimate> { it.velocity }.thenBy { it.startSeconds })

    companion object {
        fun readMidi(
            sourceFile: File,
            midiFile: File
        ): AbletonSamplePitchAnalysis =
            AbletonSamplePitchAnalysis(
                sourceFile = sourceFile.absoluteFile.normalize(),
                midiFile = midiFile.absoluteFile.normalize(),
                sourceLastModified = sourceFile.lastModified(),
                sourceLength = sourceFile.length(),
                notes = readPitchNotes(midiFile)
            )

        /**
         * Read the note-on/note-off pairs out of a `basic-pitch` MIDI file.
         *
         * The note pairing, tempo map and tick-to-seconds conversion this used to inline are now
         * `Sequence.readTimedNotes()` in Midi.kt, which is the same code generalised — this
         * function is the caller, and `AbletonMidiReadCharacterizationTest` is the fence that says
         * the generalisation did not change what comes out. The sort is kept here rather than
         * pushed down because it is this reader's contract, not the file format's:
         * `dominantPitchAtSeconds` breaks velocity ties by picking the earlier note, which only
         * works if equal-start notes arrive loudest-first.
         */
        private fun readPitchNotes(midiFile: File): List<AbletonPitchEstimate> =
            MidiSystem.getSequence(midiFile).readTimedNotes()
                .map {
                    AbletonPitchEstimate(
                        midiPitch = it.pitch,
                        velocity = it.velocity,
                        startSeconds = it.startSeconds,
                        endSeconds = it.endSeconds,
                        channel = it.channel
                    )
                }
                .sortedWith(compareBy<AbletonPitchEstimate> { it.startSeconds }.thenByDescending { it.velocity })
    }
}

class AbletonSamplePitchAnalysisCache(
    private val basicPitchExecutable: File? = configuredBasicPitchExecutable(),
    private val onsetThreshold: Double = configuredBasicPitchOnsetThreshold()
) {
    private val cache = ConcurrentHashMap<AbletonSamplePitchAnalysisKey, AbletonSamplePitchAnalysis>()

    fun analysisFor(sourceFile: File): AbletonSamplePitchAnalysis {
        val file = sourceFile.absoluteFile.normalize()
        val key = AbletonSamplePitchAnalysisKey(
            path = file.absolutePath,
            lastModified = file.lastModified(),
            length = file.length()
        )
        cache[key]?.let { return it }
        return cache.computeIfAbsent(key) { loadFromDiskOrRunBasicPitch(file, key) }
    }

    fun dominantPitchAtSeconds(sourceFile: File, sourceSeconds: Double): AbletonPitchEstimate? =
        analysisFor(sourceFile).dominantPitchAtSeconds(sourceSeconds)

    fun clear() {
        cache.clear()
    }

    private fun loadFromDiskOrRunBasicPitch(file: File, key: AbletonSamplePitchAnalysisKey): AbletonSamplePitchAnalysis {
        findValidBasicPitchMidi(file, onsetThreshold)?.let { midi ->
            println("Ableton pitch analysis: loaded MIDI cache ${midi.name} for ${file.name}")
            return AbletonSamplePitchAnalysis.readMidi(file, midi)
        }

        val executable = basicPitchExecutable
        if (executable == null || !executable.isFile) {
            println(
                "Ableton pitch analysis: no valid MIDI cache for ${file.name}; " +
                    "configure AFIELD_BASIC_PITCH_EXE or -Dafield.basicPitch.exe to run basic-pitch"
            )
            return AbletonSamplePitchAnalysis(
                sourceFile = file,
                midiFile = null,
                sourceLastModified = key.lastModified,
                sourceLength = key.length,
                notes = emptyList()
            )
        }

        val startNs = System.nanoTime()
        println("Ableton pitch analysis: running ${executable.absolutePath} for ${file.absolutePath}")
        val midi = runBasicPitch(executable, file, onsetThreshold)
        val analysis = AbletonSamplePitchAnalysis.readMidi(file, midi)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        println(
            "Ableton pitch analysis: cached ${file.name} notes=${analysis.notes.size}, " +
                "elapsed=${"%.1f".format(elapsedMs)}ms, disk=${midi.name}"
        )
        return analysis
    }

    companion object {
        val shared = AbletonSamplePitchAnalysisCache()
    }
}

private data class AbletonSamplePitchAnalysisKey(
    val path: String,
    val lastModified: Long,
    val length: Long
)

// `ActiveMidiNote` and `MidiTempoEvent` used to live here, private to this file. They are now in
// Midi.kt, along with `Sequence.tempoEvents()`, `tickToSeconds`, `ticksToSeconds` and the
// note-on/note-off pairing loop — see `readPitchNotes` above, which is now a caller.

private fun configuredBasicPitchExecutable(): File? =
    (System.getProperty("afield.basicPitch.exe") ?: System.getenv("AFIELD_BASIC_PITCH_EXE"))
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it).absoluteFile.normalize() }

private fun configuredBasicPitchOnsetThreshold(): Double =
    (System.getProperty("afield.basicPitch.onsetThreshold") ?: System.getenv("AFIELD_BASIC_PITCH_ONSET_THRESHOLD"))
        ?.toDoubleOrNull()
        ?: 0.9

private fun findValidBasicPitchMidi(sourceFile: File, onsetThreshold: Double = configuredBasicPitchOnsetThreshold()): File? {
    val deterministic = basicPitchMidiFileFor(sourceFile, onsetThreshold)
    if (deterministic.isValidPitchMidiFor(sourceFile)) return deterministic

    val parent = sourceFile.parentFile ?: return null
    val stem = sourceFile.nameWithoutExtension
    return parent.listFiles { file ->
        file.isFile &&
            file.extension.equals("mid", ignoreCase = true) &&
            !file.name.contains(".field4-basic-pitch-onset", ignoreCase = true) &&
            file.nameWithoutExtension.contains(stem, ignoreCase = true) &&
            file.isValidPitchMidiFor(sourceFile)
    }.orEmpty()
        .maxByOrNull { it.lastModified() }
}

private fun basicPitchMidiFileFor(sourceFile: File, onsetThreshold: Double): File =
    File(sourceFile.parentFile ?: File("."), "${sourceFile.name}.field4-basic-pitch-onset${onsetThreshold.cacheToken()}.mid")

private fun File.isValidPitchMidiFor(sourceFile: File): Boolean =
    isFile && lastModified() >= sourceFile.lastModified()

private fun runBasicPitch(executable: File, sourceFile: File, onsetThreshold: Double): File {
    val outputDirectory = sourceFile.parentFile ?: File(".")
    val beforeMs = System.currentTimeMillis() - 2_000L
    val process = ProcessBuilder(
        executable.absolutePath,
        outputDirectory.absolutePath,
        sourceFile.absolutePath,
        "--onset-threshold",
        onsetThreshold.toString()
    )
        .directory(outputDirectory)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    if (exit != 0) {
        error("basic-pitch failed for ${sourceFile.absolutePath} with exit code $exit\n$output")
    }

    val generated = outputDirectory.listFiles { file ->
            file.isFile &&
            file.extension.equals("mid", ignoreCase = true) &&
            file.nameWithoutExtension.contains(sourceFile.nameWithoutExtension, ignoreCase = true) &&
            file.lastModified() >= beforeMs
    }.orEmpty()
        .maxByOrNull { it.lastModified() }
        ?: error("basic-pitch did not produce a MIDI file for ${sourceFile.absolutePath}\n$output")

    val deterministic = basicPitchMidiFileFor(sourceFile, onsetThreshold)
    if (generated.absoluteFile.normalize() != deterministic.absoluteFile.normalize()) {
        Files.move(generated.toPath(), deterministic.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    deterministic.setLastModified(sourceFile.lastModified().coerceAtLeast(System.currentTimeMillis()))
    return deterministic
}

// `tempoEvents()`, `closeActiveNote`, `tickToSeconds` and `ticksToSeconds` used to live here.
// They are in Midi.kt now — `tempoEvents`, `tickToSeconds` and `ticksToSeconds` under the same
// names and with unchanged bodies, and `closeActiveNote` generalised to emit `MidiTimedNote`
// instead of `AbletonPitchEstimate` so it can serve both this reader and `readMidiClip`.

data class AbletonAutomationEnvelope(
    val id: String,
    val target: AbletonAutomationTarget,
    val events: List<AbletonAutomationEvent>
) {
    fun bracketAt(arrangementTime: Double): Pair<AbletonAutomationEvent?, AbletonAutomationEvent?> {
        if (events.isEmpty()) return null to null

        val sorted = sortedEvents()
        val leftIndex = sorted.indexOfLast { it.time <= arrangementTime }
        val left = sorted.getOrNull(leftIndex)
        val right = sorted.drop(leftIndex + 1).firstOrNull { it.time > arrangementTime }
        return left to right
    }

    fun valueAt(arrangementTime: Double): AbletonAutomationValue {
        if (events.isEmpty()) return AbletonAutomationValue.Missing

        val sorted = sortedEvents()
        val first = sorted.first()
        if (arrangementTime <= first.time) return first.value

        val leftIndex = sorted.indexOfLast { it.time <= arrangementTime }
        val left = sorted[leftIndex]
        val right = sorted.drop(leftIndex + 1).firstOrNull { it.time > arrangementTime }
            ?: return left.value

        return when {
            left.value is AbletonAutomationValue.FloatValue && right.value is AbletonAutomationValue.FloatValue -> {
                val t = ((arrangementTime - left.time) / (right.time - left.time)).coerceIn(0.0, 1.0)
                val shaped = left.curve?.xToY(t) ?: t
                AbletonAutomationValue.FloatValue(left.value.value + shaped * (right.value.value - left.value.value))
            }
            else -> left.value
        }
    }

    private fun sortedEvents(): List<AbletonAutomationEvent> =
        events.sortedWith(compareBy<AbletonAutomationEvent> { it.time }.thenBy { it.id.toDoubleOrNull() ?: 0.0 })

    companion object {
        internal fun fromElement(
            envelope: Element,
            targets: Map<String, AbletonAutomationTarget>
        ): AbletonAutomationEnvelope? {
            val targetId = envelope.firstDescendant("EnvelopeTarget")
                ?.valueChild("PointeeId")
                ?: return null
            val events = envelope.firstDescendant("Automation")
                ?.firstDescendant("Events")
                ?.childElements()
                ?.mapNotNull(AbletonAutomationEvent::fromElement)
                ?.sortedWith(compareBy<AbletonAutomationEvent> { it.time }.thenBy { it.id.toDoubleOrNull() ?: 0.0 })
                ?: emptyList()
            return AbletonAutomationEnvelope(
                id = envelope.attr("Id") ?: "",
                target = targets[targetId] ?: AbletonAutomationTarget(
                    id = targetId,
                    name = "target:$targetId",
                    path = emptyList(),
                    deviceName = null,
                    parameterName = null,
                    manual = null,
                    range = null
                ),
                events = events
            )
        }
    }
}

data class AbletonAutomationTarget(
    val id: String,
    val name: String,
    val path: List<String>,
    val deviceName: String?,
    val parameterName: String?,
    val manual: AbletonAutomationValue?,
    val range: ClosedFloatingPointRange<Double>?
)

data class AbletonAutomationEvent(
    val id: String,
    val time: Double,
    val value: AbletonAutomationValue,
    val curve: AbletonAutomationCurve?
) {
    companion object {
        internal fun fromElement(event: Element): AbletonAutomationEvent? {
            val id = event.attr("Id") ?: ""
            val time = event.attr("Time")?.toDoubleOrNull() ?: return null
            val rawValue = event.attr("Value") ?: return null
            val value = when (event.tagName) {
                "FloatEvent" -> AbletonAutomationValue.FloatValue(rawValue.toDoubleOrNull() ?: return null)
                "BoolEvent" -> AbletonAutomationValue.BoolValue(rawValue.toBooleanStrictOrNull() ?: return null)
                "EnumEvent" -> AbletonAutomationValue.EnumValue(rawValue.toIntOrNull() ?: return null)
                else -> return null
            }
            return AbletonAutomationEvent(
                id = id,
                time = time,
                value = value,
                curve = AbletonAutomationCurve.fromElement(event)
            )
        }
    }
}

sealed class AbletonAutomationValue {
    data class FloatValue(val value: Double) : AbletonAutomationValue()
    data class BoolValue(val value: Boolean) : AbletonAutomationValue()
    data class EnumValue(val value: Int) : AbletonAutomationValue()
    data object Missing : AbletonAutomationValue()

    fun asDoubleOrNull(): Double? = when (this) {
        is FloatValue -> value
        is EnumValue -> value.toDouble()
        is BoolValue -> if (value) 1.0 else 0.0
        Missing -> null
    }
}

data class AbletonAutomationCurve(
    val control1X: Double,
    val control1Y: Double,
    val control2X: Double,
    val control2Y: Double
) {
    fun xToY(x: Double): Double {
        var low = 0.0
        var high = 1.0
        repeat(24) {
            val mid = (low + high) * 0.5
            if (cubic(mid, control1X, control2X) < x) low = mid else high = mid
        }
        return cubic((low + high) * 0.5, control1Y, control2Y).coerceIn(0.0, 1.0)
    }

    companion object {
        internal fun fromElement(event: Element): AbletonAutomationCurve? {
            val c1x = event.attr("CurveControl1X")?.toDoubleOrNull() ?: return null
            val c1y = event.attr("CurveControl1Y")?.toDoubleOrNull() ?: return null
            val c2x = event.attr("CurveControl2X")?.toDoubleOrNull() ?: return null
            val c2y = event.attr("CurveControl2Y")?.toDoubleOrNull() ?: return null
            return AbletonAutomationCurve(c1x, c1y, c2x, c2y)
        }

        private fun cubic(t: Double, c1: Double, c2: Double): Double {
            val u = 1.0 - t
            return 3.0 * u * u * t * c1 + 3.0 * u * t * t * c2 + t * t * t
        }
    }
}

data class AbletonTempoMap(
    val defaultBpm: Double,
    val events: List<AbletonTempoEvent> = emptyList()
) {
    fun bpmAtBeat(beat: Double): Double =
        events.lastOrNull { it.beat <= beat }?.bpm ?: defaultBpm

    fun beatToSeconds(beat: Double): Double {
        if (events.none { it.beat >= 0.0 }) {
            return beat * 60.0 / defaultBpm
        }

        var seconds = 0.0
        var cursorBeat = 0.0
        var bpm = defaultBpm
        for (event in events.filter { it.beat >= 0.0 }.sortedBy { it.beat }) {
            if (event.beat >= beat) break
            seconds += (event.beat - cursorBeat) * 60.0 / bpm
            cursorBeat = event.beat
            bpm = event.bpm
        }
        seconds += (beat - cursorBeat) * 60.0 / bpm
        return seconds
    }

    fun secondsToBeat(seconds: Double): Double {
        if (events.none { it.beat >= 0.0 }) {
            return seconds * defaultBpm / 60.0
        }

        var cursorSeconds = 0.0
        var cursorBeat = 0.0
        var bpm = defaultBpm
        for (event in events.filter { it.beat >= 0.0 }.sortedBy { it.beat }) {
            val segmentSeconds = (event.beat - cursorBeat) * 60.0 / bpm
            if (cursorSeconds + segmentSeconds >= seconds) {
                return cursorBeat + (seconds - cursorSeconds) * bpm / 60.0
            }
            cursorSeconds += segmentSeconds
            cursorBeat = event.beat
            bpm = event.bpm
        }
        return cursorBeat + (seconds - cursorSeconds) * bpm / 60.0
    }

    fun beatsToDurationSeconds(beats: Double, fromBeat: Double = 0.0): Double =
        beatToSeconds(fromBeat + beats) - beatToSeconds(fromBeat)

    companion object {
        internal fun fromMasterTrack(masterTrack: Element?): AbletonTempoMap {
            val tempoManual = masterTrack
                ?.firstDescendant("Tempo")
                ?.valueChild("Manual")
                ?.toDoubleOrNull()
            val tempoEvents = masterTrack
                ?.childElements("AutomationEnvelopes")
                ?.firstOrNull()
                ?.descendants()
                ?.filter { it.tagName == "FloatEvent" }
                ?.mapNotNull {
                    val beat = it.attr("Time")?.toDoubleOrNull() ?: return@mapNotNull null
                    val bpm = it.attr("Value")?.toDoubleOrNull() ?: return@mapNotNull null
                    AbletonTempoEvent(beat, bpm)
                }
                ?.filter { it.bpm in 1.0..999.0 }
                ?.sortedBy { it.beat }
                ?.toList()
                ?: emptyList()
            val defaultBpm = tempoManual
                ?: tempoEvents.lastOrNull { it.beat <= 0.0 }?.bpm
                ?: 120.0
            return AbletonTempoMap(defaultBpm, tempoEvents)
        }
    }
}

data class AbletonTempoEvent(
    val beat: Double,
    val bpm: Double
)

private fun List<AbletonWarpMarker>.sourceSecondsAtClipBeat(clipBeat: Double): Double {
    if (isEmpty()) return 0.0
    if (size == 1 || clipBeat <= first().clipBeat) {
        val first = first()
        val next = getOrNull(1)
        val slope = if (next != null && next.clipBeat != first.clipBeat) {
            (next.sourceSeconds - first.sourceSeconds) / (next.clipBeat - first.clipBeat)
        } else {
            0.5
        }
        return first.sourceSeconds + (clipBeat - first.clipBeat) * slope
    }

    for (index in 0 until lastIndex) {
        val left = this[index]
        val right = this[index + 1]
        if (clipBeat <= right.clipBeat) {
            val t = (clipBeat - left.clipBeat) / (right.clipBeat - left.clipBeat)
            return left.sourceSeconds + t * (right.sourceSeconds - left.sourceSeconds)
        }
    }

    val left = this[lastIndex - 1]
    val right = last()
    val slope = (right.sourceSeconds - left.sourceSeconds) / (right.clipBeat - left.clipBeat)
    return right.sourceSeconds + (clipBeat - right.clipBeat) * slope
}

private fun List<AbletonWarpMarker>.clipBeatAtSourceSecondsOrNull(sourceSeconds: Double): Double? {
    if (size < 2) return null
    if (sourceSeconds <= first().sourceSeconds) return first().clipBeat
    for (index in 0 until lastIndex) {
        val left = this[index]
        val right = this[index + 1]
        if (sourceSeconds <= right.sourceSeconds) {
            val t = (sourceSeconds - left.sourceSeconds) / (right.sourceSeconds - left.sourceSeconds)
            return left.clipBeat + t * (right.clipBeat - left.clipBeat)
        }
    }
    val left = this[lastIndex - 1]
    val right = last()
    val slope = (right.clipBeat - left.clipBeat) / (right.sourceSeconds - left.sourceSeconds)
    return right.clipBeat + (sourceSeconds - right.sourceSeconds) * slope
}

private infix fun Double.positiveMod(modulus: Double): Double =
    ((this % modulus) + modulus) % modulus

private fun AbletonClipPlayback.volumeContribution(
    analysisCache: AbletonSampleVolumeAnalysisCache
): AbletonClipVolume? {
    val file = sourceFile ?: return null
    val analysis = analysisCache.analysisFor(file)
    return AbletonClipVolume(
        track = track,
        clip = clip,
        sourceFile = file.absoluteFile.normalize(),
        sourceSeconds = sourceSeconds,
        bands = analysis.volumeAtSeconds(sourceSeconds)
    )
}

private fun AbletonClipPlayback.pitchContribution(
    pitchCache: AbletonSamplePitchAnalysisCache
): AbletonClipPitch? {
    val file = sourceFile ?: return null
    val pitch = pitchCache.dominantPitchAtSeconds(file, sourceSeconds) ?: return null
    return AbletonClipPitch(
        track = track,
        clip = clip,
        sourceFile = file.absoluteFile.normalize(),
        sourceSeconds = sourceSeconds,
        pitch = pitch
    )
}

private fun List<AbletonClipPitch>.dominantClipPitchOrNull(): AbletonClipPitch? =
    maxWithOrNull(
        compareBy<AbletonClipPitch> { it.pitch.velocity }
            .thenBy { it.pitch.startSeconds }
            .thenBy { it.pitch.midiPitch }
    )

private fun List<AbletonClipVolume>.rootSumSquareBands(): AbletonVolumeBands {
    if (isEmpty()) return AbletonVolumeBands.Zero
    var low = 0.0
    var mid = 0.0
    var high = 0.0
    for (clip in this) {
        low += clip.bands.low * clip.bands.low
        mid += clip.bands.mid * clip.bands.mid
        high += clip.bands.high * clip.bands.high
    }
    return AbletonVolumeBands(
        low = sqrt(low),
        mid = sqrt(mid),
        high = sqrt(high)
    )
}

private fun AbletonVolumeBands.lerp(other: AbletonVolumeBands, t: Double): AbletonVolumeBands {
    val u = 1.0 - t
    return AbletonVolumeBands(
        low = low * u + other.low * t,
        mid = mid * u + other.mid * t,
        high = high * u + other.high * t
    )
}

internal fun Element.attr(name: String): String? =
    if (hasAttribute(name)) getAttribute(name) else null

internal fun Element.valueChild(name: String): String? =
    childElements(name).firstOrNull()?.attr("Value")

internal fun Element.childElements(name: String? = null): List<Element> =
    (0 until childNodes.length)
        .asSequence()
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
        .filter { name == null || it.tagName == name }
        .toList()

internal fun Element.descendants(): Sequence<Element> =
    childElements().asSequence().flatMap { sequenceOf(it) + it.descendants() }

internal fun Element.firstDescendant(name: String): Element? =
    descendants().firstOrNull { it.tagName == name }

private fun AbletonSampleRef.lookupKeys(): List<String> =
    listOfNotNull(relativePath, absolutePath)
        .flatMap { path ->
            val normalized = path.normalizedLookupPath()
            val withoutDotDots = normalized.split('/').filter { it != ".." && it != "." && it.isNotBlank() }
            buildList {
                if (normalized.isNotBlank()) add(normalized)
                if (withoutDotDots.isNotEmpty()) {
                    for (index in withoutDotDots.indices) {
                        add(withoutDotDots.drop(index).joinToString("/"))
                    }
                }
            }
        }
        .map { it.lowercase() }
        .distinct()

private fun AbletonSampleRef.fileNameKey(): String? =
    listOfNotNull(relativePath, absolutePath)
        .mapNotNull { it.normalizedLookupPath().substringAfterLast('/', "").takeIf(String::isNotBlank) }
        .firstOrNull()
        ?.lowercase()

private fun String.normalizedLookupPath(): String =
    replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." }
        .joinToString("/")

private fun File.invariantPath(): String =
    path.replace(File.separatorChar, '/')

private fun Element.automationTargetsById(): Map<String, AbletonAutomationTarget> =
    descendants()
        .filter { it.tagName == "AutomationTarget" }
        .mapNotNull { target ->
            val id = target.attr("Id") ?: return@mapNotNull null
            val parameter = target.parentElement() ?: return@mapNotNull null
            val device = parameter.nearestDeviceLikeAncestor(this)
            val parameterName = parameter.tagName
            val deviceName = device?.displayName()
            val path = parameter.pathFrom(this)
            id to AbletonAutomationTarget(
                id = id,
                name = listOfNotNull(deviceName, parameterName).joinToString(".").ifEmpty { "target:$id" },
                path = path,
                deviceName = deviceName,
                parameterName = parameterName,
                manual = parameter.valueChild("Manual")?.let { manual ->
                    when {
                        manual.equals("true", ignoreCase = true) || manual.equals("false", ignoreCase = true) ->
                            AbletonAutomationValue.BoolValue(manual.toBooleanStrict())
                        manual.toIntOrNull() != null && parameter.firstDescendant("MidiControllerRange") == null ->
                            AbletonAutomationValue.EnumValue(manual.toInt())
                        else ->
                            manual.toDoubleOrNull()?.let(AbletonAutomationValue::FloatValue)
                    }
                },
                range = parameter.firstDescendant("MidiControllerRange")?.let { range ->
                    val min = range.valueChild("Min")?.toDoubleOrNull()
                    val max = range.valueChild("Max")?.toDoubleOrNull()
                    if (min != null && max != null) min..max else null
                }
            )
        }
        .toMap()

private fun Element.parentElement(): Element? =
    parentNode as? Element

private fun Element.ancestorsUntil(root: Element): Sequence<Element> =
    generateSequence(parentElement()) { it.parentElement() }
        .takeWhile { it != root.parentElement() }

private fun Element.nearestDeviceLikeAncestor(root: Element): Element? =
    ancestorsUntil(root).firstOrNull { candidate ->
        val parent = candidate.parentElement()
        parent?.tagName == "Devices" || candidate.tagName in setOf("Mixer", "MainSequencer", "FreezeSequencer")
    }

private fun Element.displayName(): String {
    val userName = valueChild("UserName")
    if (!userName.isNullOrBlank()) return userName
    val effectiveName = childElements("Name").firstOrNull()?.valueChild("EffectiveName")
    if (!effectiveName.isNullOrBlank()) return effectiveName
    return tagName
}

private fun Element.pathFrom(root: Element): List<String> =
    (ancestorsUntil(root).takeWhile { it != root }.map { it.tagName }.toList().asReversed() + tagName)
        .filterNot { it in setOf("LomId", "LomIdView") }
