package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter
import afield.graphics.scene.body.BodyGeometry
import afield.graphics.scene.body.BodyHit
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyJoint
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyTrackingState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.JointPose
import afield.math.linalg.Vector3f
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.roundToInt

/**
 * The audio clip of `public/docs/chain_tracked_body.md`, rendered offline for the docs runner's
 * `audio=` fence.
 *
 * It repeats the page's `heard` box step for step: the recorded mediapipe pose stream of the swing
 * clip, WiVRn's eight body slots filled from it, the right wrist's signed distance to the body
 * from [BodyGeometry.closestPoint], one [TimedNote] every five frames, [patTimed], and
 * [patRenderStereo] at one cycle per clip. `TrackedBodyDocsTest` checks it gives the 61 notes
 * between MIDI 49 and 82 the page's capture shows.
 */
object TrackedBodyDocs {
    /** The recording, relative to a Field checkout. */
    const val RECORDING: String = "public/docs/assets/chain_tracked_body/swing_pose.json"

    /** The clip's frame rate; one cycle of the pattern lasts as long as the clip. */
    const val FPS: Double = 25.0

    /** The voice the `heard` box plays with. */
    val VOICE: PatVoiceSpec = patVoice(wave = PatWave.TRIANGLE, decay = .02, sustain = .6)

    /** Per frame, null or 33 landmarks as [x, y, z, visibility]; and the frame's width over height. */
    fun frames(path: String = RECORDING): Pair<List<List<List<Float>>?>, Float> {
        val rec = Json.parseToJsonElement(File(path).readText()).jsonObject
        val aspect = rec["width"]!!.jsonPrimitive.float / rec["height"]!!.jsonPrimitive.float
        val frames = rec["frames"]!!.jsonArray.map { f ->
            if (f is JsonNull) null else f.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.float } }
        }
        return frames to aspect
    }

    /** The right wrist's closest point on the body, per frame (null where no body was found). */
    fun hits(frames: List<List<List<Float>>?>, aspect: Float): List<BodyHit?> {
        fun world(p: List<Float>) = Vector3f((p[0] - .5f) * aspect, 1f - p[1], -p[2] * aspect)
        fun mid(pose: List<List<Float>>, a: Int, b: Int) = world(pose[a]).add(world(pose[b])).mul(.5f)
        return frames.map { pose ->
            pose ?: return@map null
            val body = BodyTrackingState().apply {
                isActive = true
                fun put(j: BodyJoint, p: Vector3f) = this[j].apply { position.set(p); positionValid = true }
                put(BodyJoint.HIPS, mid(pose, 23, 24))
                put(BodyJoint.CHEST, mid(pose, 11, 12))
                put(BodyJoint.LEFT_ARM_LOWER, mid(pose, 13, 15))
                put(BodyJoint.RIGHT_ARM_LOWER, mid(pose, 14, 16))
                put(BodyJoint.LEFT_LOWER_LEG, mid(pose, 25, 27))
                put(BodyJoint.RIGHT_LOWER_LEG, mid(pose, 26, 28))
                put(BodyJoint.LEFT_FOOT, world(pose[31]))
                put(BodyJoint.RIGHT_FOOT, world(pose[32]))
            }
            val head = JointPose(position = world(pose[0]), positionValid = true)
            BodyGeometry(body, head).closestPoint(world(pose[16]), limbRadius = .03f, headRadius = .07f)
        }
    }

    /** One note per five frames: nearer the body is higher, the head louder, a gap a rest. */
    fun notes(hits: List<BodyHit?>): List<TimedNote> {
        val near = hits.filterNotNull().minOf { it.signedDistance }
        val far = hits.filterNotNull().maxOf { it.signedDistance }
        return (hits.indices step 5).mapNotNull { i ->
            val h = hits[i] ?: return@mapNotNull null
            val t = (h.signedDistance - near) / (far - near)
            TimedNote(start = i.toDouble(), end = i + 5.0, note = (84 - t * 36).roundToInt().toDouble(), gain = if (h.head) 1.0 else .6)
        }
    }

    /** The clip as stereo, one cycle at the recording's speed. */
    fun render(path: String = RECORDING): PatSoundDocs.Stereo {
        val (frames, aspect) = frames(path)
        val tune = patTimed(notes(hits(frames, aspect)), beats = frames.size.toDouble())
        return patRenderStereo(tune, cycles = 1, cps = FPS / frames.size, voice = VOICE)
    }

    /** The published clip; [name] is the fence's `audio-spec` and must be `"right-wrist"`. */
    fun renderWav(name: String, outPath: String) {
        require(name == "right-wrist") { "unknown tracked-body clip '$name'" }
        val audio = render()
        require(audio.left.any { it != 0f }) { "the tracked body rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }
}
