@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * A dependency-free reader for the integer NPY tensors the audio service emits — the codec token
 * grids, in practice.
 *
 * `numpy.kt` already reads `.npy` and `.npz` generally; this is the narrow, self-checking version
 * for the one shape that matters here (codebooks × frames of small integers), so the codec views
 * can index tokens by codebook and frame without a general tensor type in between. It refuses what
 * it cannot represent, rather than guessing: Fortran order, an unsupported dtype and a
 * multi-item batch are all errors with a message.
 */

/** Small, dependency-free reader for the C-order integer NPY tensors emitted by the audio service. */
data class NpyIntTensor(
    /** Array dimensions as the NPY header gave them; the last two are codebooks and time. */
    val shape: IntArray,
    /** All codes, C-order (row-major), widened to `Long` whatever the on-disk integer width was. */
    val values: LongArray,
) {
    /** Size of the second-to-last axis: how many codebooks the encoder used. */
    val codebooks: Int get() = shape[shape.size - 2]
    /** Size of the last axis: how many token frames, and so the time extent. */
    val frames: Int get() = shape.last()

    init {
        require(shape.size >= 2) { "token tensor must have at least codebook and time axes" }
        require(shape.dropLast(2).fold(1) { a, b -> a * b } == 1) {
            "only a single token batch is currently visualized"
        }
        require(values.size == shape.fold(1) { a, b -> a * b }) { "NPY shape/data size mismatch" }
    }

    /** One code, by codebook and frame. Unchecked: an out-of-range index is an array error, not null. */
    operator fun get(codebook: Int, frame: Int): Long = values[codebook * frames + frame]

    companion object {
        /** Read and decode a `.npy` file whole. See [decode] for what is accepted. */
        fun read(path: Path): NpyIntTensor = decode(Files.readAllBytes(path))

        /**
         * Decode NPY bytes, or fail with a message saying what was wrong.
         *
         * Narrow: C-order only (Fortran order is rejected rather than silently transposed),
         * `i8`, `i4` and `u2` integer dtypes only, and a single batch only — the constructor rejects a
         * shape whose leading axes multiply to anything but 1, because a batch of token grids has no one
         * picture. Both NPY header versions are handled, and the dtype's byte order is honoured.
         */
        fun decode(bytes: ByteArray): NpyIntTensor {
            require(bytes.size >= 10 && bytes.copyOfRange(0, 6).contentEquals(
                byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(),
                    'P'.code.toByte(), 'Y'.code.toByte())
            )) { "not a NumPy NPY file" }
            val major = bytes[6].toInt() and 0xff
            val headerSizeBytes = if (major == 1) 2 else 4
            val headerLength = ByteBuffer.wrap(bytes, 8, headerSizeBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .let { if (headerSizeBytes == 2) it.short.toInt() and 0xffff else it.int }
            val headerStart = 8 + headerSizeBytes
            val header = bytes.copyOfRange(headerStart, headerStart + headerLength).toString(Charsets.US_ASCII)
            require("'fortran_order': False" in header) { "Fortran-order NPY tensors are unsupported" }
            val descriptor = Regex("['\"]descr['\"]\\s*:\\s*['\"]([^'\"]+)").find(header)
                ?.groupValues?.get(1) ?: error("NPY header has no dtype descriptor")
            val shapeText = Regex("['\"]shape['\"]\\s*:\\s*\\(([^)]*)\\)").find(header)
                ?.groupValues?.get(1) ?: error("NPY header has no shape")
            val shape = shapeText.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toInt() }.toIntArray()
            val count = shape.fold(1) { a, b -> Math.multiplyExact(a, b) }
            val data = ByteBuffer.wrap(bytes, headerStart + headerLength, bytes.size - headerStart - headerLength)
                .order(if (descriptor.startsWith(">")) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            val values = when {
                descriptor.endsWith("i8") -> LongArray(count) { data.long }
                descriptor.endsWith("i4") -> LongArray(count) { data.int.toLong() }
                descriptor.endsWith("u2") -> LongArray(count) { (data.short.toInt() and 0xffff).toLong() }
                else -> error("unsupported NPY token dtype $descriptor")
            }
            return NpyIntTensor(shape, values)
        }
    }
}
