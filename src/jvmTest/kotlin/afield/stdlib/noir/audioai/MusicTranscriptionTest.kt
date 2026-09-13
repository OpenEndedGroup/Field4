package afield.stdlib.noir.audioai

import org.msgpack.core.MessagePack
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicTranscriptionTest {
    @Test
    fun decodesTypedNotesBeatGridAndProvenance() {
        val bytes = MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packMapHeader(4)
            packer.packString("format").packString("field.audio.music-transcription.v1")
            packer.packString("notes").packArrayHeader(2)
            fun note(start: Double, end: Double, midi: Int, instrument: String) {
                packer.packMapHeader(4)
                packer.packString("start_seconds").packDouble(start)
                packer.packString("end_seconds").packDouble(end)
                packer.packString("midi").packInt(midi)
                packer.packString("instrument").packString(instrument)
            }
            note(0.075, 0.775, 57, "cello")
            note(0.5, 1.4, 64, "violin")
            packer.packString("beat_grid").packMapHeader(5)
            packer.packString("bpm").packDouble(92.0)
            packer.packString("beats_per_bar").packInt(4)
            packer.packString("first_downbeat").packDouble(0.2)
            packer.packString("onset_delay").packDouble(0.025)
            packer.packString("beat_subdivision").packInt(4)
            packer.packString("metadata").packMapHeader(4)
            packer.packString("backend").packString("analysis.muscriptor.small")
            packer.packString("model_id").packString("MuScriptor/muscriptor-small")
            packer.packString("model_revision").packString("revision")
            packer.packString("checkpoint_sha256").packString("a".repeat(64))
            packer.toByteArray()
        }

        val transcription = decodeMusicTranscriptionMessagePack(bytes)
        assertEquals(2, transcription.notes.size)
        assertEquals("cello", transcription.notes.first().instrument)
        assertEquals(57, transcription.notes.first().midi)
        assertEquals(92.0, transcription.beatGrid?.bpm)
        assertEquals(0.025, transcription.beatGrid?.onsetDelaySeconds)
        assertEquals("analysis.muscriptor.small", transcription.backend)
        assertEquals("a".repeat(64), transcription.checkpointSha256)
    }
}
