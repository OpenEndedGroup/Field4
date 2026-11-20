import afield.media.image.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.*

val rate = 44100
val ring = FloatArray(rate / 2) // a half-second delay line
// A ring is one long row; viewed as rows of 210 samples it becomes a picture.
val row = 210

// Fill it with what a delay would hold: three decaying plucks.
for (k in 0 until 3) {
    val start = k * 5000
    for (i in 0 until 4000) ring[start + i] =
        (0.8 * exp(-i / 1500.0) * sin(i * 2 * PI * (220.0 * (k + 1)) / rate)).toFloat()
}
val before = ring.copyOf()

val view = bufferCanvas(ring, width = row, wrapX = false)
view.map = ValueMap.Signed(1.0)
view.zoom = 2

// The same storage as a single wrapped row, for edits along time.
val timeline = bufferCanvas(ring, width = ring.size, wrapX = true)
val pluck = timeline.copy(CellRect(0, 0, 4000, 1))
timeline.paste(pluck, 16000, 0, opacity = 0.7) // a fourth, quieter pluck
timeline.motionBlur(CellRect(5000, 0, 4000, 1), length = 61) // smear the second: a low-pass

fun rms(a: FloatArray, from: Int, len: Int) = sqrt((from until from + len).sumOf { a[it].toDouble() * a[it] } / len)
println("pluck 2 rms before %.4f after %.4f".format(rms(before, 5000, 4000), rms(ring, 5000, 4000)))
println("pasted region rms before %.4f after %.4f".format(rms(before, 16000, 4000), rms(ring, 16000, 4000)))

fun wav(samples: FloatArray): String {
    val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
    val out = ByteArrayOutputStream()
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray())
    h.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    h.put("data".toByteArray()).putInt(pcm.capacity())
    out.write(h.array())
    out.write(pcm.array())
    return "<audio controls src='data:audio/wav;base64," +
        Base64.getEncoder().encodeToString(out.toByteArray()) +
        "'></audio>"
}

display("ring", view.toHTML())
display("listen", "<p>before</p>" + wav(before) + "<p>after</p>" + wav(ring))
