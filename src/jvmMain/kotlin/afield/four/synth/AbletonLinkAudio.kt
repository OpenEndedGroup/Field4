@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.graphquery.LiveGraphEdge
import afield.graphquery.LiveGraphInspectable
import com.ableton.link.AbletonLink
import com.ableton.link.LinkAudioChannel
import com.ableton.link.LinkAudioSink
import com.ableton.link.LinkAudioSource
import com.ableton.link.SessionState
import java.util.concurrent.ConcurrentHashMap

data class LinkTimingSnapshot(
    val enabled: Boolean,
    val audioEnabled: Boolean,
    val peers: Long,
    val tempo: Double,
    val beat: Double,
    val phase: Double,
    val quantum: Double,
    val clockMicros: Long,
    val playing: Boolean
) {
    override fun toString(): String = synthPrettyObject(
        this,
        "enabled" to enabled,
        "audioEnabled" to audioEnabled,
        "peers" to peers,
        "tempo" to tempo,
        "beat" to beat,
        "phase" to phase,
        "quantum" to quantum,
        "clockMicros" to clockMicros,
        "playing" to playing
    )
}

class LinkAudioInterface private constructor(
    tempo: Double,
    peerName: String,
    val quantum: Double
) : AutoCloseable, LiveGraphInspectable {
    companion object {
        @Volatile
        private var sharedInstance: LinkAudioInterface? = null

        @Synchronized
        fun shared(
            tempo: Double = 120.0,
            peerName: String = "Field4",
            quantum: Double = 4.0
        ): LinkAudioInterface {
            val existing = sharedInstance
            if (existing != null) {
                existing.setPeerName(peerName)
                return existing
            }
            return LinkAudioInterface(tempo, peerName, quantum).also {
                sharedInstance = it
            }
        }

        fun currentOrNull(): LinkAudioInterface? = sharedInstance?.takeUnless { it.closed }

        @Synchronized
        fun destroyShared() {
            sharedInstance?.close()
            sharedInstance = null
        }
    }

    private val link = AbletonLink(tempo)
    private val appState = SessionState()
    private val audioState = SessionState()
    private val inputStates = ConcurrentHashMap<String, LinkCaptureState>()
    private val inputSources = ConcurrentHashMap<String, LinkAudioSource>()

    @Volatile
    private var sink: LinkAudioSink? = null

    @Volatile
    private var sinkChannels: Int = 2

    @Volatile
    var linkAudioCommits: Long = 0L
        private set

    @Volatile
    var linkAudioCommitFailures: Long = 0L
        private set

    @Volatile
    var linkAudioInvalidBuffers: Long = 0L
        private set

    @Volatile
    var closed: Boolean = false
        private set

    init {
        link.setPeerName(peerName)
        link.enable(true)
    }

    val enabled: Boolean
        get() = !closed && link.isEnabled

    val audioEnabled: Boolean
        get() = !closed && link.isLinkAudioEnabled

    val peers: Long
        get() = if (closed) 0L else link.numPeers()

    fun setPeerName(name: String): LinkAudioInterface = apply {
        if (!closed && name.isNotBlank()) link.setPeerName(name)
    }

    fun setTempo(tempo: Double): LinkAudioInterface = apply {
        if (closed) return@apply
        val now = link.clockMicros()
        synchronized(appState) {
            link.captureAppSessionState(appState)
            appState.setTempo(tempo, now)
            link.commitAppSessionState(appState)
        }
    }

    fun setPlaying(playing: Boolean): LinkAudioInterface = apply {
        if (closed) return@apply
        val now = link.clockMicros()
        synchronized(appState) {
            link.captureAppSessionState(appState)
            appState.setIsPlaying(playing, now)
            link.commitAppSessionState(appState)
        }
    }

    fun enableStartStopSync(enabled: Boolean = true): LinkAudioInterface = apply {
        if (!closed) link.enableStartStopSync(enabled)
    }

    fun requestBeat(beat: Double, quantum: Double = this.quantum): LinkAudioInterface = apply {
        if (closed) return@apply
        val now = link.clockMicros()
        synchronized(appState) {
            link.captureAppSessionState(appState)
            appState.requestBeatAtTime(beat, now, quantum)
            link.commitAppSessionState(appState)
        }
    }

    fun snapshot(quantum: Double = this.quantum): LinkTimingSnapshot {
        if (closed) {
            return LinkTimingSnapshot(false, false, 0L, 0.0, 0.0, 0.0, quantum, 0L, false)
        }
        synchronized(appState) {
            link.captureAppSessionState(appState)
            return snapshotFrom(appState, quantum)
        }
    }

    @Synchronized
    internal fun beginAudioBlock(quantum: Double = this.quantum): LinkAudioBlock? {
        if (closed) return null
        link.captureAudioSessionState(audioState)
        val now = link.clockMicros()
        val snapshot = snapshotFrom(audioState, quantum, now)
        return LinkAudioBlock(audioState, snapshot, snapshot.beat)
    }

    @Synchronized
    internal fun commitStereo(block: LinkAudioBlock?, samples: ShortArray, frames: Int, sampleRate: Int) {
        val activeSink = sink ?: return
        if (closed || block == null) return
        activeSink.retainBuffer().use { buffer ->
            if (!buffer.isValid) {
                linkAudioInvalidBuffers += 1
                return
            }
            val sampleCount = minOf(samples.size, frames * 2)
            if (sampleCount > buffer.maxNumSamples()) {
                linkAudioCommitFailures += 1
                return
            }
            buffer.copyFrom(samples, 0, sampleCount)
            val committed = buffer.commit(block.state, block.beginBeat, block.snapshot.quantum, frames.toLong(), 2L, sampleRate)
            if (committed) {
                linkAudioCommits += 1
            } else {
                linkAudioCommitFailures += 1
            }
        }
    }

    @Synchronized
    fun enableAudioOutput(name: String = "Field4", maxFrames: Int = 4096, channels: Int = 2): LinkAudioInterface = apply {
        if (closed) return@apply
        link.enableLinkAudio(true)
        val sampleCapacity = maxFrames.toLong() * channels.coerceAtLeast(1).toLong()
        sinkChannels = channels.coerceAtLeast(1)
        val active = sink
        if (active == null) {
            sink = link.createAudioSink(name, sampleCapacity)
        } else {
            active.close()
            sink = link.createAudioSink(name, sampleCapacity)
        }
    }

    @Synchronized
    fun disableAudioOutput(): LinkAudioInterface = apply {
        sink?.close()
        sink = null
        if (inputSources.isEmpty()) link.enableLinkAudio(false)
    }

    fun channels(): List<LinkAudioChannel> = if (closed) emptyList() else link.channels()

    fun stereoInput(channelName: String? = null): StereoSignal {
        val state = inputState(channelName)
        return StereoSignal(LinkCaptureSignal(state, 0), LinkCaptureSignal(state, 1))
    }

    fun monoInput(channelName: String? = null): Signal {
        val state = inputState(channelName)
        return LinkCaptureSignal(state, -1)
    }

    private fun inputState(channelName: String?): LinkCaptureState {
        if (closed) return LinkCaptureState()
        link.enableLinkAudio(true)
        val channel = selectChannel(channelName) ?: return LinkCaptureState()
        val key = channel.id().toString()
        val state = inputStates.computeIfAbsent(key) { LinkCaptureState() }
        inputSources.computeIfAbsent(key) {
            link.createAudioSource(channel.id(), LinkAudioSource.BufferListener { buffer ->
                val info = buffer.info()
                state.update(buffer.copySamples(), info.numFrames().toInt(), info.numChannels().toInt())
            })
        }
        return state
    }

    private fun selectChannel(channelName: String?): LinkAudioChannel? {
        val all = channels()
        if (channelName.isNullOrBlank()) return all.firstOrNull()
        return all.firstOrNull { it.name().equals(channelName, ignoreCase = true) }
    }

    private fun snapshotFrom(
        state: SessionState,
        quantum: Double,
        now: Long = link.clockMicros()
    ): LinkTimingSnapshot = LinkTimingSnapshot(
        enabled = link.isEnabled,
        audioEnabled = link.isLinkAudioEnabled,
        peers = link.numPeers(),
        tempo = state.tempo(),
        beat = state.beatAtTime(now, quantum),
        phase = state.phaseAtTime(now, quantum),
        quantum = quantum,
        clockMicros = now,
        playing = state.isPlaying
    )

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("enabled") { enabled },
        LiveGraphEdge("audioEnabled") { audioEnabled },
        LiveGraphEdge("peers") { peers },
        LiveGraphEdge("quantum") { quantum },
        LiveGraphEdge("sinkChannels") { sinkChannels },
        LiveGraphEdge("linkAudioCommits") { linkAudioCommits },
        LiveGraphEdge("linkAudioCommitFailures") { linkAudioCommitFailures },
        LiveGraphEdge("linkAudioInvalidBuffers") { linkAudioInvalidBuffers },
        LiveGraphEdge("channels") { channels() }
    )

    override fun toString(): String = synthPrettyObject(
        this,
        "snapshot" to snapshot(),
        "linkAudioCommits" to linkAudioCommits,
        "linkAudioCommitFailures" to linkAudioCommitFailures,
        "linkAudioInvalidBuffers" to linkAudioInvalidBuffers,
        "channels" to channels().map { it.name() }
    )

    override fun close() {
        if (closed) return
        closed = true
        inputSources.values.forEach { runCatching { it.close() } }
        inputSources.clear()
        inputStates.clear()
        runCatching { sink?.close() }
        sink = null
        runCatching { audioState.close() }
        runCatching { appState.close() }
        runCatching { link.close() }
    }
}

internal data class LinkAudioBlock(
    val state: SessionState,
    val snapshot: LinkTimingSnapshot,
    val beginBeat: Double
)

private class LinkCaptureState {
    private val lock = Any()
    private var left = FloatArray(0)
    private var right = FloatArray(0)
    private var frames = 0

    fun update(samples: ShortArray, frameCount: Int, channels: Int) {
        synchronized(lock) {
            if (left.size < frameCount) {
                left = FloatArray(frameCount)
                right = FloatArray(frameCount)
            }
            frames = frameCount
            for (i in 0 until frameCount) {
                val base = i * channels
                val l = if (base < samples.size) samples[base].toFloat() / Short.MAX_VALUE else 0f
                val r = if (channels > 1 && base + 1 < samples.size) {
                    samples[base + 1].toFloat() / Short.MAX_VALUE
                } else {
                    l
                }
                left[i] = l
                right[i] = r
            }
        }
    }

    fun copy(channel: Int, output: FloatArray, numFrames: Int) {
        synchronized(lock) {
            val count = minOf(numFrames, frames, left.size)
            when (channel) {
                0 -> System.arraycopy(left, 0, output, 0, count)
                1 -> System.arraycopy(right, 0, output, 0, count)
                else -> {
                    for (i in 0 until count) {
                        output[i] = (left[i] + right[i]) * 0.5f
                    }
                }
            }
            for (i in count until numFrames) {
                output[i] = 0f
            }
        }
    }
}

private class LinkCaptureSignal(
    private val state: LinkCaptureState,
    private val channel: Int
) : Signal() {
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        state.copy(channel, output, numFrames)
    }
}
