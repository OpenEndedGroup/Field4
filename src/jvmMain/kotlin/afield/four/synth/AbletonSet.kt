package afield.four.synth

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import java.util.zip.GZIPInputStream
import kotlin.math.max

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

data class AbletonAutomationEnvelope(
    val id: String,
    val target: AbletonAutomationTarget,
    val events: List<AbletonAutomationEvent>
) {
    fun valueAt(arrangementTime: Double): AbletonAutomationValue {
        if (events.isEmpty()) return AbletonAutomationValue.Missing

        val sorted = events.sortedWith(compareBy<AbletonAutomationEvent> { it.time }.thenBy { it.id.toDoubleOrNull() ?: 0.0 })
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
