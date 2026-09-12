package afield.graphics

import afield.stdlib.Vec3
import afield.stdlib.Vec4
import kotlin.math.max

/**
 * Incrementally tracks drawing-instruction anchor points across frames and emits one trajectory [FLine] per tracked point.
 *
 * Call [update] every frame with the current line(s). Matching is done with a small Hungarian assignment
 * solver, which is robust to topology changes where points appear, disappear, or intermediate instructions
 * get dropped. For stable topology, a fast path can skip Hungarian and match by instruction index when all
 * point deltas are under [stableTopologyDistanceThreshold].
 */
class FLineSideways(
    val maxMatchDistance: Float = Float.POSITIVE_INFINITY,
    val stableTopologyDistanceThreshold: Float = if (maxMatchDistance.isFinite()) maxMatchDistance else 0f,
    val maxMissedFrames: Int = 8,
    val breakOnGap: Boolean = true,
    val includeCloseInstructions: Boolean = false,
    val instructionIndexWeight: Double = 0.05,
    val lineKeySelector: (lineIndex: Int, line: FLine) -> Any = { lineIndex, _ -> lineIndex }
) {

    init {
        require(maxMissedFrames >= 0) { "maxMissedFrames must be >= 0" }
        require(instructionIndexWeight >= 0.0) { "instructionIndexWeight must be >= 0" }
        require(stableTopologyDistanceThreshold >= 0f) { "stableTopologyDistanceThreshold must be >= 0" }
    }

    private data class Observation(
        val lineKey: Any,
        val instructionIndex: Int,
        val position: Vec3,
        val color: Vec4
    )

    private data class Track(
        val id: Long,
        val lineKey: Any,
        val trajectory: FLine,
        var lastPoint: Vec3,
        var lastInstructionIndex: Int,
        var missedFrames: Int = 0,
        var gapPending: Boolean = false
    )

    private val activeTracks = LinkedHashMap<Long, Track>()
    private val archivedTracks = LinkedHashMap<Long, Track>()
    private var nextTrackId = 1L
    private val maxMatchDistanceSq = if (maxMatchDistance.isFinite()) {
        val d = maxMatchDistance.toDouble()
        d * d
    } else {
        null
    }
    private val stableTopologyDistanceThresholdSq = stableTopologyDistanceThreshold.squaredPositiveOrNull()

    /**
     * Number of currently active tracks.
     */
    val activeTrackCount: Int
        get() = activeTracks.size

    /**
     * Number of total tracks (active + archived).
     */
    val totalTrackCount: Int
        get() = activeTracks.size + archivedTracks.size

    /**
     * Clears all state and trajectories.
     */
    fun reset() {
        activeTracks.clear()
        archivedTracks.clear()
        nextTrackId = 1L
    }

    /**
     * Update with a single line frame.
     */
    fun update(line: FLine): List<FLine> = update(listOf(line))

    /**
     * Update with a frame of lines.
     *
     * The default line identity is the input index, but you can change it with [lineKeySelector].
     */
    fun update(lines: Iterable<FLine>): List<FLine> {
        val keyed = lines.mapIndexed { i, line -> lineKeySelector(i, line) to line }
        return updateKeyed(keyed)
    }

    /**
     * Update with explicitly keyed lines (handy when source line order can change).
     */
    fun update(lines: Map<*, FLine>): List<FLine> {
        val keyed = lines.entries.map { (key, line) -> (key ?: "_null_key_") to line }
        return updateKeyed(keyed)
    }

    /**
     * Return trajectories ordered by creation id.
     */
    fun trajectories(includeArchived: Boolean = true): List<FLine> {
        val out = ArrayList<Pair<Long, FLine>>(totalTrackCount)
        activeTracks.values.forEach { out.add(it.id to it.trajectory) }
        if (includeArchived) archivedTracks.values.forEach { out.add(it.id to it.trajectory) }
        out.sortBy { it.first }
        return out.map { it.second }
    }

    /**
     * Return trajectories as an [FLineMap] (`track_<id>` keys).
     */
    fun trajectoryMap(includeArchived: Boolean = true): FLineMap {
        val out = FLineMap()
        activeTracks.values.sortedBy { it.id }.forEach { out["track_${it.id}"] = it.trajectory }
        if (includeArchived) {
            archivedTracks.values.sortedBy { it.id }.forEach { out["track_${it.id}"] = it.trajectory }
        }
        return out
    }

    private fun updateKeyed(keyedLines: List<Pair<Any, FLine>>): List<FLine> {
        val observations = ArrayList<Observation>()
        keyedLines.forEach { (lineKey, line) ->
            observations.addAll(extractObservations(lineKey, line))
        }

        val groupedObservations = observations.groupBy { it.lineKey }
        val groupedTracks = activeTracks.values.groupBy { it.lineKey }

        val allKeys = LinkedHashSet<Any>()
        allKeys.addAll(groupedTracks.keys)
        allKeys.addAll(groupedObservations.keys)

        val matchedTrackIds = HashSet<Long>()

        for (key in allKeys) {
            val tracks = groupedTracks[key].orEmpty()
            val obs = groupedObservations[key].orEmpty()
            matchGroup(tracks, obs, matchedTrackIds)
        }

        val toArchive = ArrayList<Long>()
        activeTracks.values.forEach { track ->
            if (!matchedTrackIds.contains(track.id)) {
                track.missedFrames += 1
                if (breakOnGap) track.gapPending = true
                if (track.missedFrames > maxMissedFrames) {
                    toArchive.add(track.id)
                }
            }
        }
        toArchive.forEach { id ->
            val removed = activeTracks.remove(id)
            if (removed != null) archivedTracks[id] = removed
        }

        return trajectories()
    }

    private fun matchGroup(
        tracks: List<Track>,
        observations: List<Observation>,
        matchedTrackIds: MutableSet<Long>
    ) {
        if (tracks.isEmpty()) {
            observations.forEach { observation ->
                matchedTrackIds.add(spawnTrack(observation).id)
            }
            return
        }
        if (observations.isEmpty()) return
        if (tryStableTopologyFastPath(tracks, observations, matchedTrackIds)) return

        val n = max(tracks.size, observations.size)
        val unmatchedCost = unmatchedCost()
        val disallowCost = unmatchedCost * 1024.0
        val indexScale = estimateInstructionStepScale(observations)

        val cost = Array(n) { DoubleArray(n) { 0.0 } }
        for (row in 0 until n) {
            for (col in 0 until n) {
                cost[row][col] = when {
                    row < tracks.size && col < observations.size -> pairCost(
                        track = tracks[row],
                        observation = observations[col],
                        indexScale = indexScale,
                        disallowCost = disallowCost
                    )

                    row < tracks.size && col >= observations.size -> unmatchedCost
                    row >= tracks.size && col < observations.size -> unmatchedCost
                    else -> 0.0
                }
            }
        }

        val assignment = HungarianAssignment.solve(cost)
        val usedObservation = BooleanArray(observations.size)

        tracks.indices.forEach { row ->
            val col = assignment[row]
            if (col in observations.indices) {
                val c = cost[row][col]
                if (c <= unmatchedCost) {
                    applyMatch(tracks[row], observations[col])
                    matchedTrackIds.add(tracks[row].id)
                    usedObservation[col] = true
                }
            }
        }

        observations.indices.forEach { col ->
            if (!usedObservation[col]) {
                matchedTrackIds.add(spawnTrack(observations[col]).id)
            }
        }
    }

    private fun tryStableTopologyFastPath(
        tracks: List<Track>,
        observations: List<Observation>,
        matchedTrackIds: MutableSet<Long>
    ): Boolean {
        val thresholdSq = stableTopologyDistanceThresholdSq ?: return false
        if (tracks.size != observations.size || tracks.isEmpty()) return false

        val observationByInstructionIndex = HashMap<Int, Observation>(observations.size * 2)
        for (observation in observations) {
            if (observationByInstructionIndex.put(observation.instructionIndex, observation) != null) {
                return false
            }
        }

        val matchedObservations = ArrayList<Observation>(tracks.size)
        for (track in tracks) {
            if (track.missedFrames != 0) return false

            val observation = observationByInstructionIndex.remove(track.lastInstructionIndex) ?: return false
            val spatial = squaredDistance(track.lastPoint, observation.position)
            if (spatial > thresholdSq) return false
            if (maxMatchDistanceSq != null && spatial > maxMatchDistanceSq) return false
            matchedObservations.add(observation)
        }

        if (observationByInstructionIndex.isNotEmpty()) return false

        for (i in tracks.indices) {
            val track = tracks[i]
            val observation = matchedObservations[i]
            applyMatch(track, observation)
            matchedTrackIds.add(track.id)
        }
        return true
    }

    private fun applyMatch(track: Track, observation: Observation) {
        if (track.trajectory.nodes.isEmpty()) {
            track.trajectory.moveTo(observation.position)
        } else if (track.gapPending && breakOnGap) {
            track.trajectory.moveTo(observation.position)
        } else {
            track.trajectory.lineTo(observation.position)
        }
        applyObservationColor(track.trajectory, observation.color)
        track.lastPoint.set(observation.position)
        track.lastInstructionIndex = observation.instructionIndex
        track.missedFrames = 0
        track.gapPending = false

        track.trajectory.properties["lastInstructionIndex"] = observation.instructionIndex
    }

    private fun spawnTrack(observation: Observation): Track {
        val id = nextTrackId++
        val trajectory = FLine()
        trajectory.moveTo(observation.position)
        applyObservationColor(trajectory, observation.color)
        trajectory.properties["trackId"] = id
        trajectory.properties["lineKey"] = observation.lineKey
        trajectory.properties["firstInstructionIndex"] = observation.instructionIndex
        trajectory.properties["lastInstructionIndex"] = observation.instructionIndex
        return Track(
            id = id,
            lineKey = observation.lineKey,
            trajectory = trajectory,
            lastPoint = Vec3(observation.position),
            lastInstructionIndex = observation.instructionIndex
        ).also { activeTracks[id] = it }
    }

    private fun extractObservations(lineKey: Any, line: FLine): List<Observation> {
        val out = ArrayList<Observation>(line.nodes.size)
        var subpathStart: Vec3? = null
        var currentColor = (line.properties["color"] as? Vec4)?.let { Vec4(it) } ?: Vec4(0.8f, 0.8f, 0.8f, 0.5f)

        line.nodes.forEachIndexed { instructionIndex, node ->
            (node.properties["color"] as? Vec4)?.let { currentColor = Vec4(it) }
            when (node) {
                is FLine.MoveTo -> {
                    val p = Vec3(node.to)
                    out.add(Observation(lineKey, instructionIndex, p, Vec4(currentColor)))
                    subpathStart = Vec3(p)
                }

                is FLine.LineTo -> out.add(Observation(lineKey, instructionIndex, Vec3(node.to), Vec4(currentColor)))
                is FLine.CubicTo -> out.add(Observation(lineKey, instructionIndex, Vec3(node.to), Vec4(currentColor)))
                is FLine.Close -> {
                    if (includeCloseInstructions && subpathStart != null) {
                        out.add(Observation(lineKey, instructionIndex, Vec3(subpathStart), Vec4(currentColor)))
                    }
                }
            }
        }
        return out
    }

    private fun applyObservationColor(trajectory: FLine, color: Vec4) {
        trajectory.properties["color"] = Vec4(color)
        if (trajectory.nodes.isNotEmpty()) {
            trajectory.here.properties["color"] = Vec4(color)
        }
    }

    private fun pairCost(
        track: Track,
        observation: Observation,
        indexScale: Double,
        disallowCost: Double
    ): Double {
        val spatial = squaredDistance(track.lastPoint, observation.position)

        if (maxMatchDistanceSq != null) {
            if (spatial > maxMatchDistanceSq) return disallowCost
        }

        val idxDelta = (track.lastInstructionIndex - observation.instructionIndex).toDouble()
        val indexCost = idxDelta * idxDelta * instructionIndexWeight * indexScale
        return spatial + indexCost
    }

    private fun unmatchedCost(): Double {
        if (maxMatchDistance.isFinite()) {
            val d = maxMatchDistance.toDouble().coerceAtLeast(1e-6)
            return d * d
        }
        return 1e18
    }

    private fun estimateInstructionStepScale(observations: List<Observation>): Double {
        if (observations.size < 2) return 1.0
        val sorted = observations.sortedBy { it.instructionIndex }
        var accum = 0.0
        var count = 0
        for (i in 1 until sorted.size) {
            val step = squaredDistance(sorted[i - 1].position, sorted[i].position)
            if (step > 1e-12) {
                accum += step
                count++
            }
        }
        return if (count == 0) 1.0 else accum / count
    }

    private fun squaredDistance(a: Vec3, b: Vec3): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return (dx * dx) + (dy * dy) + (dz * dz)
    }

    private fun Float.squaredPositiveOrNull(): Double? {
        if (!isFinite()) return null
        if (this <= 0f) return null
        val d = toDouble()
        return d * d
    }
}

/**
 * Small O(n^3) Hungarian solver for square cost matrices.
 *
 * Internal rather than private because `afield.stdlib.flowMatch` needs the same solve for its
 * minibatch optimal-transport coupling, and a third copy in this repository would be one too many.
 */
internal object HungarianAssignment {
    fun solve(cost: Array<DoubleArray>): IntArray {
        val n = cost.size
        if (n == 0) return IntArray(0)
        require(cost.all { it.size == n }) { "Hungarian solver expects a square matrix" }

        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(n + 1)

            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0

                for (j in 1..n) {
                    if (!used[j]) {
                        val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minv[j]) {
                            minv[j] = cur
                            way[j] = j0
                        }
                        if (minv[j] < delta) {
                            delta = minv[j]
                            j1 = j
                        }
                    }
                }

                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val assignment = IntArray(n) { -1 }
        for (j in 1..n) {
            if (p[j] != 0) assignment[p[j] - 1] = j - 1
        }
        return assignment
    }
}
