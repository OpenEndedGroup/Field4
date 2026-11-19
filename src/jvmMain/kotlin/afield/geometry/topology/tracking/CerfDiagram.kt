package afield.geometry.topology.tracking

import afield.geometry.path.FLine
import afield.geometry.topology.morsesmale.CriticalType
import afield.geometry.topology.morsesmale.MorseSmaleResult
import afield.math.linalg.Vec4

/**
 * The Cerf diagram of a time-varying 2-D scalar field: every critical point's value plotted against
 * time, coloured by index, with the moments the topology changes marked on it.
 *
 * A generic one-parameter family of functions changes its critical points in only two ways (Cerf):
 * a pair of critical points of adjacent index is born or cancels (a minimum with a saddle, or a saddle
 * with a maximum), and two critical values swap order. [CerfDiagram] reads both off a sequence of
 * Morse–Smale results. Critical points are followed from frame to frame by a [FeatureTracker] over
 * all three types; a birth is a new saddle track whose persistence partner in that frame is a new
 * extremum track, a death is the same read backwards, and a swap is two tracks, alive in consecutive
 * frames, whose values change order between them. Births and deaths that are not paired this way (a
 * feature entering through the edge of the grid, or a tracking break) are recorded as
 * [CerfEventType.APPEAR] and [CerfEventType.VANISH].
 *
 * ```
 * val cerf = CerfDiagram()
 * for (frame in 0 until 60) cerf.add(frame.toDouble(), MorseSmale.compute(fieldAt(frame), 0.02), minPersistence = 0.02)
 * display("cerf", cerf.toFLines(cursor = 30.0))
 * display("field", cerf.fieldMarkers(30.0))
 * ```
 *
 * This samples time, so it sees the events between frames rather than solving for the instant they
 * happen, and with a persistence threshold a pair is "born" when its persistence rises above the
 * threshold rather than at zero. Dhar, Chakraborty and Natarajan, "Analyzing Time-Varying Scalar
 * Fields using Piecewise-Linear Morse-Cerf Theory" (2025, arXiv:2507.00725), compute the PL diagram
 * exactly on the space-time mesh. The worked account is `public/docs/topology_tracking.md`.
 */
class CerfDiagram(
    val weights: MatchingWeights = MatchingWeights(birthDistance = 4.0, maxDistance = 8.0),
) {
    /** The tracker following every critical point; its [FeatureTracker.tracks] are the diagram's curves. */
    val tracker = FeatureTracker(weights)

    /** One sampled moment: the time and the (track, feature) pairs alive at it. */
    class Frame internal constructor(val time: Double, val points: List<Pair<Track, Feature>>)

    private val frameList = ArrayList<Frame>()
    private val eventList = ArrayList<CerfEvent>()
    private var previousPartner: Map<Int, Int> = emptyMap()

    val frames: List<Frame> get() = frameList
    val events: List<CerfEvent> get() = eventList

    /** Adds the critical points of [result] at [time], keeping those whose persistence is at least [minPersistence]. */
    fun add(time: Double, result: MorseSmaleResult, minPersistence: Double = 0.0): List<CerfEvent> {
        val partner = HashMap<Int, Int>()
        result.complex.persistencePairs.forEach { p -> p.death?.let { partner[p.birth] = it; partner[it] = p.birth } }
        return add(time, result.features(minPersistence, CriticalType.entries.toSet()), partner)
    }

    /**
     * Adds [features] seen at [time]. [partner] maps a feature's `sourceId` to its persistence partner's,
     * which is how births and deaths are paired; without it they are all APPEAR / VANISH.
     */
    fun add(time: Double, features: List<Feature>, partner: Map<Int, Int> = emptyMap()): List<CerfEvent> {
        val before = frameList.lastOrNull()
        tracker.add(time, features)
        val frame = Frame(time, tracker.alive.zip(features))
        frameList.add(frame)
        val found = ArrayList<CerfEvent>()

        // births: a new saddle whose partner is a new extremum (the first frame is the starting state, not births)
        if (before != null) {
        val bornIds = tracker.born.map { it.id }.toSet()
        val bornBySource = frame.points.filter { it.first.id in bornIds }.associateBy { it.second.sourceId }
        pairUp(bornBySource, partner, time, CerfEventType.BIRTH, CerfEventType.APPEAR, found)
        }

        // deaths, read from the frame they were last seen in
        if (before != null) {
            val diedIds = tracker.died.map { it.id }.toSet()
            val diedBySource = before.points.filter { it.first.id in diedIds }.associateBy { it.second.sourceId }
            pairUp(diedBySource, previousPartner, before.time, CerfEventType.DEATH, CerfEventType.VANISH, found)

            // swaps: tracks alive in both frames whose values change order
            val now = frame.points.associate { it.first.id to it.second.value }
            val both = before.points.filter { it.first.id in now }
            for (a in both.indices) for (b in a + 1 until both.size) {
                val (ta, fa) = both[a]; val (tb, fb) = both[b]
                val d0 = fa.value - fb.value
                val d1 = now[ta.id]!! - now[tb.id]!!
                if (d0 == 0.0 || d1 == 0.0 || (d0 > 0) == (d1 > 0)) continue
                val s = d0 / (d0 - d1)
                val t = before.time + s * (time - before.time)
                val v = fa.value + s * (now[ta.id]!! - fa.value)
                found.add(CerfEvent(CerfEventType.SWAP, t, v, listOf(ta, tb), (fa.x + fb.x) / 2, (fa.y + fb.y) / 2))
            }
        }
        previousPartner = partner
        eventList.addAll(found)
        return found
    }

    private fun pairUp(
        bySource: Map<Int, Pair<Track, Feature>>, partner: Map<Int, Int>, time: Double,
        paired: CerfEventType, unpaired: CerfEventType, out: MutableList<CerfEvent>,
    ) {
        val used = HashSet<Int>()
        bySource.values.filter { it.second.type == CriticalType.SADDLE }.forEach { (st, sf) ->
            val other = partner[sf.sourceId]?.let { bySource[it] }
            if (other != null && other.first.id !in used && other.second.type != CriticalType.SADDLE) {
                used += st.id; used += other.first.id
                val (et, ef) = other
                out.add(CerfEvent(paired, time, (sf.value + ef.value) / 2, listOf(st, et), (sf.x + ef.x) / 2, (sf.y + ef.y) / 2))
            }
        }
        bySource.values.filter { it.first.id !in used }.forEach { (t, f) ->
            out.add(CerfEvent(unpaired, time, f.value, listOf(t), f.x, f.y))
        }
    }

    /** The frame at or before [time] (the first frame for earlier times), or null if nothing was added. */
    fun frameAt(time: Double): Frame? = frameList.lastOrNull { it.time <= time } ?: frameList.firstOrNull()

    /** Events of [type] (all types when null). */
    fun events(type: CerfEventType? = null): List<CerfEvent> = if (type == null) eventList else eventList.filter { it.type == type }

    /**
     * The diagram as FLines in a [width] × [height] rectangle with time running right and value up:
     * one line per track coloured by index (minima blue, saddles green, maxima red), births as
     * orange dots, deaths as violet dots, swaps as small grey dots, and a vertical cursor at [cursor].
     * Lines carry `trackId`; event dots carry `cerfEvent` on their node.
     */
    fun toFLines(
        width: Double = 600.0,
        height: Double = 300.0,
        cursor: Double? = null,
        showSwaps: Boolean = true,
        thickness: Double = 1.5,
    ): List<FLine> {
        if (frameList.isEmpty()) return emptyList()
        val t0 = frameList.first().time
        val t1 = frameList.last().time.let { if (it == t0) t0 + 1 else it }
        val values = frameList.flatMap { f -> f.points.map { it.second.value } }
        val v0 = values.min()
        val v1 = values.max().let { if (it == v0) v0 + 1 else it }
        val pad = 0.04
        fun x(t: Double) = width * (pad + (1 - 2 * pad) * (t - t0) / (t1 - t0))
        fun y(v: Double) = height * (1 - pad - (1 - 2 * pad) * (v - v0) / (v1 - v0))
        val out = ArrayList<FLine>()
        tracker.tracks.forEach { track ->
            val line = FLine()
            track.samples.forEachIndexed { i, (t, f) -> if (i == 0) line.moveTo(x(t), y(f.value)) else line.lineTo(x(t), y(f.value)) }
            if (track.samples.size == 1) line.lineTo(x(track.samples[0].first) + 1, y(track.samples[0].second.value))
            line.properties["color"] = colourOf(track.type)
            line.properties["thickness"] = thickness
            line.properties["trackId"] = track.id
            out.add(line)
        }
        fun dots(type: CerfEventType, colour: Vec4, size: Double) {
            val es = events(type)
            if (es.isEmpty()) return
            out.add(FLine().also { f ->
                es.forEach { e -> f.moveTo(x(e.time), y(e.value)); f.nodes.last().properties["cerfEvent"] = e }
                f.properties["pointed"] = true; f.properties["stroked"] = false
                f.properties["pointSize"] = size; f.properties["color"] = colour
            })
        }
        if (showSwaps) dots(CerfEventType.SWAP, Vec4(0.75f, 0.75f, 0.75f, 0.8f), 4.0)
        dots(CerfEventType.BIRTH, BIRTH_COLOUR, 9.0)
        dots(CerfEventType.DEATH, DEATH_COLOUR, 9.0)
        if (cursor != null) out.add(FLine().moveTo(x(cursor), 0.0).lineTo(x(cursor), height).also {
            it.properties["color"] = Vec4(1f, 1f, 1f, 0.85f); it.properties["thickness"] = 1.0
        })
        return out
    }

    /**
     * The critical points alive at [time] as dots in the field's pixel coordinates, coloured by index,
     * plus a larger orange or violet dot for each pair born or about to cancel within [window] of [time].
     */
    fun fieldMarkers(time: Double, pointSize: Double = 6.0, window: Double = 0.0): List<FLine> {
        val frame = frameAt(time) ?: return emptyList()
        val points = FLine().also { f ->
            frame.points.forEach { (_, p) -> f.moveTo(p.x, p.y); f.nodes.last().properties["color"] = colourOf(p.type) }
            f.properties["pointed"] = true; f.properties["stroked"] = false; f.properties["pointSize"] = pointSize
        }
        val near = eventList.filter {
            (it.type == CerfEventType.BIRTH || it.type == CerfEventType.DEATH) && kotlin.math.abs(it.time - frame.time) <= window
        }
        if (near.isEmpty()) return listOf(points)
        val rings = FLine().also { f ->
            near.forEach { e -> f.moveTo(e.x, e.y); f.nodes.last().properties["color"] = if (e.type == CerfEventType.BIRTH) BIRTH_COLOUR else DEATH_COLOUR }
            f.properties["pointed"] = true; f.properties["stroked"] = false; f.properties["pointSize"] = pointSize * 2.5
        }
        return listOf(rings, points)
    }

    override fun toString() = "CerfDiagram(${frameList.size} frames, ${tracker.tracks.size} tracks, " +
        "${events(CerfEventType.BIRTH).size} births, ${events(CerfEventType.DEATH).size} deaths, ${events(CerfEventType.SWAP).size} swaps)"

    companion object {
        val BIRTH_COLOUR = Vec4(1f, 0.6f, 0.1f, 1f)
        val DEATH_COLOUR = Vec4(0.75f, 0.35f, 1f, 1f)

        /** Minima blue, saddles green, maxima red: the colours of `MorseSmaleResult.criticalPointsFLine`. */
        fun colourOf(type: CriticalType): Vec4 = when (type) {
            CriticalType.MINIMUM -> Vec4(0.1f, 0.3f, 1f, 1f)
            CriticalType.SADDLE -> Vec4(0.1f, 0.75f, 0.2f, 1f)
            CriticalType.MAXIMUM -> Vec4(1f, 0.2f, 0.1f, 1f)
        }
    }
}

/** What happened: a pair born or cancelled, two values swapping order, or an unpaired arrival or departure. */
enum class CerfEventType { BIRTH, DEATH, SWAP, APPEAR, VANISH }

/**
 * One event of a [CerfDiagram]. [tracks] are the saddle and extremum of a birth or death, the two
 * tracks of a swap, or the one track of an APPEAR / VANISH. [value] is the critical value at the
 * event (the pair's mean for a birth or death, the crossing value for a swap); [x], [y] are in the
 * field's pixel units. A birth's [time] is the first frame the pair is seen in, a death's the last,
 * and a swap's is interpolated between the two frames it falls between.
 */
data class CerfEvent(
    val type: CerfEventType,
    val time: Double,
    val value: Double,
    val tracks: List<Track>,
    val x: Double,
    val y: Double,
) {
    /** For a birth or death: the index of the pair, 0 for minimum–saddle, 1 for saddle–maximum. */
    val pairIndex: Int?
        get() = if (type != CerfEventType.BIRTH && type != CerfEventType.DEATH) null
            else if (tracks.any { it.type == CriticalType.MINIMUM }) 0 else 1
}
