package afield

import kotlinx.serialization.Serializable

@Serializable
data class CoroutineProfileRequest(
    val action: String = "snapshot",
    val seconds: Double = 10.0,
    val after: Long = -1,
    val captureId: String = "",
    val stacks: Boolean = false,
    val stackAfter: Int = -1
)

@Serializable
data class CoroutineProfileLane(
    val id: Int,
    val boxId: String,
    val boxName: String,
    val strand: String,
    val revision: String,
    val state: String,
    val firstMs: Double,
    val lastMs: Double
)

/** One scheduled continuation, ending when it returns/yields/suspends; gaps are not CPU work. */
@Serializable
data class CoroutineProfileSpan(
    val sequence: Long,
    val lane: Int,
    val frame: Long,
    val startMs: Double,
    val wallMs: Double,
    val cpuMs: Double,
    val allocatedBytes: Long,
    val threadId: Long = -1
)

/** Samples are statistical evidence, independent of the exact continuation counters. */
@Serializable
data class ProfileStackSample(val timeMs: Double, val threadId: Long, val threadName: String,
    val kind: String, val weight: Long, val stack: List<Int>)

@Serializable
data class ProfileStackFrame(val id: Int, val className: String, val method: String, val line: Int,
    val sourceId: Int = -1)

@Serializable
data class ProfileStackSource(val id: Int, val path: String, val code: String,
    val boxId: String = "", val property: String = "code", val boxCode: String = "")

@Serializable
data class CoroutineProfileSnapshot(
    val ok: Boolean = true,
    val error: String = "",
    val captureId: String = "",
    val recording: Boolean = false,
    val elapsedMs: Double = 0.0,
    val durationMs: Double = 0.0,
    val oldestSequence: Long = 0,
    val nextSequence: Long = 0,
    val droppedSpans: Long = 0,
    val lanes: List<CoroutineProfileLane> = emptyList(),
    val spans: List<CoroutineProfileSpan> = emptyList(),
    val stackState: String = "off",
    val stackError: String = "",
    val stackSampleCount: Int = 0,
    val stackDropped: Int = 0,
    val stackFrames: List<ProfileStackFrame> = emptyList(),
    val stackSources: List<ProfileStackSource> = emptyList(),
    val stackSamples: List<ProfileStackSample> = emptyList()
)
