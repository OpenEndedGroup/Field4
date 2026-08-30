package drowning

import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.texture.video.FrameSetBarrier
import afield.four.texture.video.VideoTexture
import java.util.concurrent.atomic.AtomicLong

/**
 * Presents one complete source-frame set before the RTT: video/depth/flow + 15 MXGs + 15 splats.
 * Decode stays asynchronous; only matching, ready data is copied on the render thread. Wall-clock
 * transport remains the requested position. [presentedFrame] is the image the audience actually sees.
 *
 * Resource boxes may register several named looks. [look] changes the requested set without closing
 * or reopening anything. The old set remains on the GPU while the new video's decoder and geometry
 * banks prepare a matching source frame; video, geometry and [onLookPresented] then commit together.
 *
 * Register the boxes' upload callbacks, and install this ONE Target before dust-main. Do not also
 * schedule VideoTexture or the banks' independent latest-frame feeds. Register every shade's live
 * enabled predicate. Dormant banks park; returning draws stay hidden until their first matched set.
 */
class DrowningPresentation(
    private val transport: DrowningTransport,
    initialLook: String = "default"
) : Target, AutoCloseable {
    private data class Key(val look: String, val frame: Int, val offsets: List<Int>)
    private data class PreparedLook(val frame: Int, val offsets: List<Int>)
    private data class Resources(
        var video: VideoTexture? = null,
        var presentVideo: ((VideoTexture) -> Unit)? = null,
        var splats: SplatCueBank? = null,
        var splatFrameCounts: IntArray? = null,
        var uploadSplats: ((SplatCueSnapshot, CommandBuffer) -> Unit)? = null,
        var mxg: MxgCueBank? = null,
        var uploadMxg: ((MxgCueSnapshot, CommandBuffer) -> Unit)? = null
    )

    private val barrier = FrameSetBarrier<Key>()
    private val resources = linkedMapOf<String, Resources>()
    /** Extra, explicitly pre-rolled looks. The requested look is active independently of this map. */
    private val preparedAhead = linkedMapOf<String, PreparedLook>()
    private val resourceRevision = AtomicLong()
    @Volatile private var wantedLook = initialLook
    private var heldSplats: SplatCueSnapshot? = null
    private var heldSplatBank: SplatCueBank? = null
    private var activeKey: Key? = null
    private var waitingSince = 0L
    private val splatShades = linkedMapOf<String, () -> Boolean>()
    private var mxgShade: () -> Boolean = { false }

    var splatsEnabled = true
    var mxgEnabled = true
    var splatsActive = false
        private set
    var mxgActive = false
        private set
    private var splatsReadyToDraw = false
    private var mxgReadyToDraw = false
    val splatsVisible: Boolean get() = splatsActive && splatsReadyToDraw
    val mxgVisible: Boolean get() = mxgActive && mxgReadyToDraw
    var activeSplatShades: Set<String> = emptySet()
        private set
    var splatUploads: Long = 0
        private set
    var mxgUploads: Long = 0
        private set

    /** Called on the render thread after the new resources upload and before their first RTT draw. */
    var onLookPresented: (String, CommandBuffer) -> Unit = { _, _ -> }

    val requestedLook: String get() = wantedLook
    var presentedLook: String = ""
        private set
    val availableLooks: Set<String> get() = synchronized(resources) { resources.keys.toSet() }
    val preparedLooks: Set<String> get() = synchronized(preparedAhead) { preparedAhead.keys.toSet() }

    /**
     * Prepare these looks at the transport's current source frame. For a scored transition, prefer
     * [prepareLook] with the exact source frame expected at the future musical boundary.
     */
    fun prepareLooks(vararg names: String) {
        names.forEach(::prepareLook)
    }

    fun prepareLook(name: String) = prepareLook(name, transport.frame)

    /**
     * Reserve one complete incoming set for a known future cut. Background workers stop chasing the
     * transport once this frame is ready, so pre-roll is bounded and the current look keeps its budget.
     * At the boundary, [look] is still the only required operation.
     */
    fun prepareLook(name: String, sourceFrame: Int) {
        require(name.isNotBlank()) { "look name must not be blank" }
        if (name == wantedLook) return
        val frame = Math.floorMod(sourceFrame, transport.frameCount)
        val offsets = List(15) { transport.unwrappedFrameFor(it) - transport.frame }
        val prepared = PreparedLook(frame, offsets)
        synchronized(preparedAhead) { preparedAhead[name] = prepared }
    }

    /** Park a no-longer-upcoming look without closing its files or caches. */
    fun parkLook(name: String) {
        synchronized(preparedAhead) { preparedAhead.remove(name) }
    }

    /** Request an already-open look. Missing registrations simply keep the previous look visible. */
    fun look(name: String) {
        require(name.isNotBlank()) { "look name must not be blank" }
        val wasPrepared = synchronized(preparedAhead) { preparedAhead.remove(name) != null }
        if (wantedLook == name) {
            if (wasPrepared) resourceRevision.incrementAndGet()
            return
        }
        wantedLook = name
        resourceRevision.incrementAndGet()
    }

    /**
     * True when [name] has a complete video/splat/MXG set for its reserved cue-boundary frame.
     */
    fun isLookPrepared(name: String): Boolean {
        val prepared = synchronized(preparedAhead) { preparedAhead[name] } ?: return false
        val set = synchronized(resources) { resources[name] } ?: return false
        val frame = prepared.frame
        val offsets = prepared.offsets
        val frames = IntArray(15) { Math.floorMod(frame + offsets[it], transport.frameCount) }
        if (set.video?.isFrameReady(frame.toLong()) != true) return false
        if (splatsActive && set.splats?.latestFrames()?.contentEquals(frames) != true) return false
        if (mxgActive) {
            val ready = set.mxg?.latest ?: return false
            if (ready.requestedFrame != frame || ready.offsets.toList() != offsets) return false
            if (ready.frames.map { it.frameIndex } != frames.toList()) return false
        }
        return true
    }

    fun preparedFrame(name: String): Int? = synchronized(preparedAhead) { preparedAhead[name]?.frame }

    /** Stable names replace previous registrations on box re-launch; lambdas follow shader reload. */
    fun registerSplatShade(name: String, enabled: () -> Boolean) { splatShades[name] = enabled }
    fun unregisterSplatShade(name: String) { splatShades.remove(name) }
    fun registerMxgShade(enabled: () -> Boolean) { mxgShade = enabled }

    var presentedFrame: Int = -1
        private set
    var presentedVideoFrame: Long = -1
        private set
    var presentedSplatFrames: List<Int> = emptyList()
        private set
    var presentedMxgFrames: List<Int> = emptyList()
        private set
    var presentations: Long = 0
        private set
    var renderFrames: Long = 0
        private set
    var lastWaitMillis = 0.0
        private set
    val pendingFrame: Int get() = barrier.pending?.frame ?: -1
    val pendingLook: String get() = barrier.pending?.look ?: ""
    var waitingFor = "registration"
        private set

    fun registerVideo(look: String, value: VideoTexture, onPresented: (VideoTexture) -> Unit = {}) {
        require(value.backgroundDecode)
        require(kotlin.math.abs(value.fps - transport.fps) < 1e-6) { "video/transport source fps mismatch" }
        synchronized(resources) {
            resources.getOrPut(look, ::Resources).apply {
                video = value
                presentVideo = onPresented
            }
        }
        resourceRevision.incrementAndGet()
    }

    fun registerVideo(value: VideoTexture) = registerVideo(wantedLook, value)

    fun registerSplats(
        look: String,
        value: SplatCueBank,
        frameCounts: IntArray,
        upload: (SplatCueSnapshot, CommandBuffer) -> Unit
    ) {
        require(frameCounts.size == 15 && frameCounts.all { it == transport.frameCount }) {
            "frame-locked splats need ${transport.frameCount} frames per cell, got ${frameCounts.contentToString()}"
        }
        value.setActive(false)
        synchronized(resources) {
            val set = resources.getOrPut(look, ::Resources)
            if (set.splats !== value) set.splats?.close()
            set.splats = value
            set.splatFrameCounts = frameCounts.copyOf()
            set.uploadSplats = upload
        }
        resourceRevision.incrementAndGet()
    }

    fun registerSplats(
        value: SplatCueBank,
        frameCounts: IntArray,
        upload: (SplatCueSnapshot, CommandBuffer) -> Unit
    ) = registerSplats(wantedLook, value, frameCounts, upload)

    fun registerMxg(look: String, value: MxgCueBank, upload: (MxgCueSnapshot, CommandBuffer) -> Unit) {
        require(value.profiles.all { it.frames == transport.frameCount }) {
            "frame-locked MXG needs ${transport.frameCount} frames per cell, got ${value.profiles.map { it.frames }}"
        }
        value.setActive(false)
        synchronized(resources) {
            val set = resources.getOrPut(look, ::Resources)
            if (set.mxg !== value) set.mxg?.close()
            set.mxg = value
            set.uploadMxg = upload
        }
        resourceRevision.incrementAndGet()
    }

    fun registerMxg(value: MxgCueBank, upload: (MxgCueSnapshot, CommandBuffer) -> Unit) =
        registerMxg(wantedLook, value, upload)

    private fun releaseHeld() {
        val snapshot = heldSplats
        val owner = heldSplatBank
        heldSplats = null
        heldSplatBank = null
        if (snapshot != null) checkNotNull(owner).release(snapshot)
    }

    fun reset() {
        releaseHeld()
        synchronized(resources) { resources.values.forEach { it.splats?.invalidate() } }
        barrier.reset()
        activeKey = null
        splatsReadyToDraw = false
        mxgReadyToDraw = false
        resourceRevision.incrementAndGet()
    }

    private fun updateDemand(targetLook: String) {
        activeSplatShades = splatShades.filterValues { it() }.keys.toSet()
        val needSplats = splatsEnabled && activeSplatShades.isNotEmpty()
        val needMxg = mxgEnabled && mxgShade()
        if (needSplats != splatsActive || needMxg != mxgActive) {
            releaseHeld()
            barrier.reset() // Drop a pending disabled layer; permit rejoin even while paused.
            activeKey = null
            if (needSplats != splatsActive) splatsReadyToDraw = false
            if (needMxg != mxgActive) mxgReadyToDraw = false
            splatsActive = needSplats
            mxgActive = needMxg
            resourceRevision.incrementAndGet()
        }
        val warm = synchronized(preparedAhead) { preparedAhead.keys.toSet() + targetLook }
        synchronized(resources) {
            resources.forEach { (name, set) ->
                set.splats?.setActive(needSplats && name in warm)
                set.mxg?.setActive(needMxg && name in warm)
            }
        }
    }

    private fun prepareStandbys(targetLook: String) {
        val warm = synchronized(preparedAhead) { preparedAhead.toMap() }
        val standbys = synchronized(resources) {
            resources.mapNotNull { (name, set) ->
                warm[name]?.takeIf { name != targetLook }?.let { it to set }
            }
        }
        standbys.forEach { (prepared, standby) ->
            val frames = IntArray(15) {
                Math.floorMod(prepared.frame + prepared.offsets[it], transport.frameCount)
            }
            standby.video?.prepareFrame(prepared.frame.toLong())
            if (splatsActive) standby.splats?.request(frames)
            if (mxgActive) standby.mxg?.request(prepared.frame, prepared.offsets.toIntArray())
        }
    }

    override fun render(command: CommandBuffer, index: Int) {
        renderFrames++
        val targetLook = wantedLook
        updateDemand(targetLook)
        val set = synchronized(resources) { resources[targetLook] }
        val video = set?.video
        if (set == null || video == null ||
            (splatsActive && (set.splats == null || set.uploadSplats == null)) ||
            (mxgActive && (set.mxg == null || set.uploadMxg == null))) {
            waitingFor = "registration:$targetLook"
            return
        }
        val splats = set.splats
        val mxg = set.mxg
        if (splatsActive) splats?.failure?.let { throw it }
        if (mxgActive) mxg?.failure?.let { throw it }
        val desired = Key(targetLook, transport.frame, List(15) { transport.unwrappedFrameFor(it) - transport.frame })
        prepareStandbys(targetLook)
        val revision = transport.splats.seekGeneration * 31L + resourceRevision.get()
        val key = barrier.select(desired, revision) ?: return
        val frames = IntArray(15) { Math.floorMod(key.frame + key.offsets[it], transport.frameCount) }
        if (activeKey != key) {
            releaseHeld()
            activeKey = key
            waitingSince = System.nanoTime()
        }
        video.prepareFrame(key.frame.toLong())
        if (splatsActive) checkNotNull(splats).request(frames)
        if (mxgActive) checkNotNull(mxg).request(key.frame, key.offsets.toIntArray())
        if (splatsActive && heldSplats == null) {
            checkNotNull(splats).takeLatest()?.let { snapshot ->
                if (snapshot.frames.contentEquals(frames)) {
                    heldSplats = snapshot
                    heldSplatBank = splats
                } else splats.release(snapshot)
            }
        }
        val mxgReady = mxg?.latest?.takeIf {
            it.requestedFrame == key.frame && it.offsets.toList() == key.offsets
        }
        if (mxgActive && mxgReady != null) check(mxgReady.frames.map { it.frameIndex } == frames.toList()) {
            "MXG record/source-frame mismatch: requested ${frames.toList()}, got ${mxgReady.frames.map { it.frameIndex }}"
        }
        val videoReady = video.isFrameReady(key.frame.toLong())
        waitingFor = listOfNotNull(
            if (!videoReady) "video" else null,
            if (splatsActive && heldSplats == null) "splats" else null,
            if (mxgActive && mxgReady == null) "mxg" else null
        ).joinToString(",")
        barrier.presentIfReady({ videoReady && (!splatsActive || heldSplats != null) && (!mxgActive || mxgReady != null) }) {
            // All copies and look-dependent state are recorded before the RTT, never across displays.
            check(video.uploadPreparedFrame(command, key.frame.toLong()))
            if (splatsActive) {
                checkNotNull(set.uploadSplats)(checkNotNull(heldSplats), command)
                splatUploads++
            }
            if (mxgActive) {
                checkNotNull(set.uploadMxg)(checkNotNull(mxgReady), command)
                mxgUploads++
            }
            if (presentedLook != key.look) {
                set.presentVideo?.invoke(video)
                onLookPresented(key.look, command)
            }
            presentedLook = key.look
            presentedFrame = key.frame
            presentedVideoFrame = video.uploadedFrameIndex
            presentedSplatFrames = if (splatsActive) checkNotNull(heldSplats).frames.toList() else emptyList()
            presentedMxgFrames = if (mxgActive) checkNotNull(mxgReady).frames.map { it.frameIndex } else emptyList()
            check(presentedVideoFrame == presentedFrame.toLong())
            if (splatsActive) check(presentedSplatFrames == frames.toList())
            if (mxgActive) check(presentedMxgFrames == frames.toList())
            splatsReadyToDraw = splatsActive
            mxgReadyToDraw = mxgActive
            presentations++
            lastWaitMillis = (System.nanoTime() - waitingSince) / 1e6
            releaseHeld()
            activeKey = null
        }
    }

    fun describe(): String = "look=$presentedLook requestedLook=$wantedLook presented=$presentedFrame video=$presentedVideoFrame " +
        "splats=${presentedSplatFrames.distinct()} mxg=${presentedMxgFrames.distinct()} " +
        "pending=$pendingLook/$pendingFrame waiting=$waitingFor sets=$presentations " +
        "warm=${synchronized(preparedAhead) { preparedAhead.entries.joinToString { "${it.key}@${it.value.frame}" } }} " +
        "active=splats:$splatsActive(${activeSplatShades.size}),mxg:$mxgActive " +
        "uploads=$splatUploads/$mxgUploads renderFrames=$renderFrames waitMs=%.1f".format(lastWaitMillis)

    override fun close() {
        reset()
        synchronized(resources) {
            resources.values.forEach {
                it.splats?.setActive(false)
                it.mxg?.setActive(false)
            }
        }
    }
}
