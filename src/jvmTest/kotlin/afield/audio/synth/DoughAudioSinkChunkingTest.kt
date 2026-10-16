package afield.audio.synth

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Chunking a request differently does not change what [DoughAudioSink] plays: no frame is dropped. */
class DoughAudioSinkChunkingTest {
    private fun render(chunk: Int, total: Int): FloatArray {
        val sink = DoughAudioSink(Paths.get("./src/jvmMain/resources/dough.wasm"))
        try {
            sink.evaluatePath("/dough/play/s/saw/n/60/gain/.3")
            val ctx = SynthContext(44_100f, chunk)
            val out = FloatArray(total)
            var at = 0
            while (at < total) {
                val n = minOf(chunk, total - at)
                val l = FloatArray(n)
                val r = FloatArray(n)
                sink.render(l, r, n, ctx)
                ctx.advanceCycle()
                System.arraycopy(l, 0, out, at, n)
                at += n
            }
            return out
        } finally {
            sink.close()
        }
    }

    @Test
    fun aBufferOf441FramesPlaysWhatWholeBlocksPlay() {
        val total = 441 * 8
        val blocks = render(128, total)
        val odd = render(441, total)
        assertTrue(blocks.any { it != 0f }, "premise: the note sounds")
        assertContentEquals(blocks, odd)
    }
}
