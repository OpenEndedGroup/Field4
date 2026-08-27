@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The PlayCanvas *compressed PLY* — the `.compressed.ply` that
 * [splat-transform](https://github.com/playcanvas/splat-transform) writes and that SuperSplat and
 * the PlayCanvas engine read back.
 *
 * It is an ordinary `binary_little_endian` PLY whose payload happens to be quantized. Splats are
 * sorted into Morton order and cut into chunks of [CHUNK_SIZE]; a `chunk` element records, as
 * floats, the min and max of each chunk's own positions, log-scales and colours; a `vertex` element
 * then spends exactly four 32-bit words per splat holding position, rotation, scale and colour
 * normalised into that chunk's range. An optional `sh` element carries the higher spherical-harmonic
 * bands at a byte per coefficient. The result is roughly an order of magnitude smaller than the
 * float-per-property PLY a 3DGS trainer emits.
 *
 * [readCompressedSplatPly] undoes all of that and returns the same [PlyFloatTable] the uncompressed
 * file would have produced — `x`/`y`/`z`, `f_dc_0..2`, `opacity`, `rot_0..3`, `scale_0..2`, in the
 * same conventions (log scales, logit opacity, SH-DC colour, `rot_0` real) — so
 * [GaussianSplatRenderer] and [gaussianSplats] need to know nothing about any of this.
 * [readPlyAsFloatTable] delegates here whenever it recognises the schema, and the recognition is
 * content-based: `.compressed.ply` in the name is a convention, not the signal.
 *
 * The lossy part happened when the file was written. Positions get 11/10/11 bits, but *per chunk*,
 * so 256 neighbouring splats share a tight bounding box and the absolute error stays small;
 * rotations get 10 bits per component with the largest dropped and rebuilt; colour and opacity get
 * 8 bits each. Reading is exact — every value below is the inverse of the packing, not an
 * approximation of it.
 */
object CompressedSplatPly {

    /** Splats per chunk. Fixed by the format, not a tunable. */
    const val CHUNK_SIZE = 256

    /** The DC term of the real spherical-harmonic basis, as 3DGS and splat-transform use it. */
    const val SH_C0 = 0.28209479177387814f

    /**
     * Opacity is stored as an 8-bit *sigmoid* value, so 0 and 255 invert to a logit of -infinity and
     * +infinity. The uncompressed PLY column this reader emits is the logit, and callers downstream
     * push it back through a sigmoid — an infinity there is either a NaN or, in
     * [GaussianSplatRenderer]'s case, a non-finite guard that lands on 0.5 and turns a solid splat
     * translucent. Clamping to ±30 costs nothing: `sigmoid(30f)` is 1 and `sigmoid(-30f)` is 9.4e-14,
     * both of which round-trip to the byte they came from.
     */
    const val OPACITY_LOGIT_LIMIT = 30f

    /** Chunk properties that every compressed PLY has, in the order the format declares them. */
    val requiredChunkProperties = listOf(
        "min_x", "min_y", "min_z",
        "max_x", "max_y", "max_z",
        "min_scale_x", "min_scale_y", "min_scale_z",
        "max_scale_x", "max_scale_y", "max_scale_z"
    )

    /**
     * Per-chunk colour range. Older files omit these six and store colour as a plain unorm instead
     * of a lerp between them, so they are optional — but all six or none.
     */
    val colorChunkProperties = listOf("min_r", "min_g", "min_b", "max_r", "max_g", "max_b")

    /** The four packed 32-bit words that make up a splat. */
    val vertexProperties = listOf("packed_position", "packed_rotation", "packed_scale", "packed_color")

    /** Coefficient counts a valid `sh` element may have: one, two or three bands beyond DC, times RGB. */
    val validShColumnCounts = setOf(9, 24, 45)

    /** Column names of the table [readCompressedSplatPly] produces, before any `f_rest_*`. */
    val decompressedColumns = listOf(
        "x", "y", "z",
        "f_dc_0", "f_dc_1", "f_dc_2",
        "opacity",
        "rot_0", "rot_1", "rot_2", "rot_3",
        "scale_0", "scale_1", "scale_2"
    )

    /** What a compressed PLY's header says about its payload, once recognised. */
    data class Layout(
        val splatCount: Int,
        val chunkCount: Int,
        val hasChunkColors: Boolean,
        /** `f_rest_*` names in the order the `sh` element declares them; empty when there is no `sh`. */
        val shColumns: List<String>
    ) {
        val shBands: Int
            get() = when (shColumns.size) {
                9 -> 1
                24 -> 2
                45 -> 3
                else -> 0
            }
    }

    /** The outcome of looking at a PLY header and asking "is this a compressed splat file?". */
    sealed interface Recognition {
        /** It is, and here is what it holds. */
        data class Compressed(val layout: Layout) : Recognition

        /** It is not, and nothing about it suggested it was trying to be. Read it as a normal PLY. */
        data object Plain : Recognition

        /**
         * It carries the packed vertex words, so it *is* meant to be a compressed PLY, but something
         * else does not add up. Reading it as a normal PLY would coerce 32-bit packed words into
         * floats and hand back plausible-looking nonsense, so callers should surface [reason].
         */
        data class Malformed(val reason: String) : Recognition
    }

    // ------------------------------------------------------------------------
    // Recognition
    // ------------------------------------------------------------------------

    /**
     * Decide whether [header] describes a compressed splat PLY, mirroring splat-transform's own
     * `isCompressedPly` check: the `chunk` element's floats, the `vertex` element's four packed
     * words, the chunk count implied by the splat count, and an optional `sh` element of 9, 24 or 45
     * `uchar` coefficients.
     *
     * Extra properties and unusual property *order* are tolerated everywhere except the chunk
     * colour block, which the format treats as all-or-nothing.
     */
    internal fun recognise(header: PlyTableHeader): Recognition {
        val vertex = header.elements.find { it.name == "vertex" } ?: return Recognition.Plain
        val chunk = header.elements.find { it.name == "chunk" }
        val sh = header.elements.find { it.name == "sh" }

        val packedProperties = vertex.properties.filter { it.name in vertexProperties }
        if (packedProperties.isEmpty()) return Recognition.Plain

        // From here on the file is claiming to be compressed, so every failure is a Malformed with a
        // reason rather than a silent fall-through to the float reader.
        fun bad(reason: String) = Recognition.Malformed(reason)

        val missingPacked = vertexProperties - packedProperties.map { it.name }.toSet()
        if (missingPacked.isNotEmpty()) {
            return bad("the 'vertex' element is missing ${missingPacked.joinToString()}")
        }
        val wrongType = packedProperties.filterNot { it.type in uint32Types }
        if (wrongType.isNotEmpty()) {
            return bad(
                "the packed vertex properties must be uint32, but " +
                    wrongType.joinToString { "${it.name} is '${it.type}'" }
            )
        }
        if (header.format == PlyTableFormat.ASCII) {
            return bad("it is an ascii PLY; the compressed format is only defined for binary payloads")
        }
        if (header.elements.any { element -> element.properties.any { it.isList } }) {
            return bad("it has a list property, which the compressed format never uses")
        }

        chunk ?: return bad("it has no 'chunk' element")

        val chunkNames = chunk.properties.map { it.name }.toSet()
        val missingChunk = requiredChunkProperties.filterNot { it in chunkNames }
        if (missingChunk.isNotEmpty()) {
            return bad("the 'chunk' element is missing ${missingChunk.joinToString()}")
        }
        val presentColors = colorChunkProperties.filter { it in chunkNames }
        if (presentColors.isNotEmpty() && presentColors.size != colorChunkProperties.size) {
            return bad(
                "the 'chunk' element has ${presentColors.joinToString()} but not the rest of " +
                    "${colorChunkProperties.joinToString()}; per-chunk colour is all six or none"
            )
        }
        val nonFloatChunk = chunk.properties.filter {
            it.name in requiredChunkProperties || it.name in colorChunkProperties
        }.filterNot { it.type in float32Types }
        if (nonFloatChunk.isNotEmpty()) {
            return bad(
                "the chunk ranges must be float32, but " +
                    nonFloatChunk.joinToString { "${it.name} is '${it.type}'" }
            )
        }

        val expectedChunks = (vertex.count + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (expectedChunks != chunk.count) {
            return bad(
                "${vertex.count} splats need $expectedChunks chunks of $CHUNK_SIZE, " +
                    "but the file declares ${chunk.count}"
            )
        }

        val shColumns: List<String>
        if (sh == null) {
            shColumns = emptyList()
        } else {
            if (sh.count != vertex.count) {
                return bad("the 'sh' element has ${sh.count} rows but there are ${vertex.count} splats")
            }
            if (sh.properties.size !in validShColumnCounts) {
                return bad(
                    "the 'sh' element has ${sh.properties.size} coefficients; " +
                        "the format allows ${validShColumnCounts.sorted().joinToString()}"
                )
            }
            val notUchar = sh.properties.filterNot { it.type in uint8Types }
            if (notUchar.isNotEmpty()) {
                return bad(
                    "the spherical-harmonic coefficients must be uchar, but " +
                        notUchar.joinToString { "${it.name} is '${it.type}'" }
                )
            }
            val expectedNames = sh.properties.indices.map { "f_rest_$it" }
            val actualNames = sh.properties.map { it.name }
            if (actualNames.toSet() != expectedNames.toSet()) {
                return bad(
                    "the 'sh' element's properties should be ${expectedNames.first()}.." +
                        "${expectedNames.last()}, but they are ${actualNames.joinToString()}"
                )
            }
            shColumns = actualNames
        }

        return Recognition.Compressed(
            Layout(
                splatCount = vertex.count,
                chunkCount = chunk.count,
                hasChunkColors = presentColors.size == colorChunkProperties.size,
                shColumns = shColumns
            )
        )
    }

    /**
     * True when [path] is a compressed splat PLY. Reads only the header. A file that is *trying* to
     * be one and failing counts as one for this purpose — it is certainly not a plain PLY — so use
     * [readCompressedSplatPly] when you want the reason.
     */
    fun isCompressedSplatPly(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return RandomAccessFile(file, "r").use { raf ->
            val header = runCatching { parseFloatTableHeader(raf) }.getOrNull() ?: return@use false
            recognise(header) !is Recognition.Plain
        }
    }

    // ------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------

    /**
     * Read a PlayCanvas compressed PLY and hand back the uncompressed splat table.
     *
     * @param path the `.compressed.ply` file.
     * @param includeSphericalHarmonics also decode the `sh` element into `f_rest_*` columns. Off by
     *   default because it is up to 45 extra floats per splat — three times the size of the table
     *   itself — and nothing in Field's splat renderer reads them yet. The bands are still there in
     *   the file either way.
     * @throws IllegalStateException if the file is not a compressed splat PLY, saying which part of
     *   the schema did not match.
     */
    fun readCompressedSplatPly(path: String, includeSphericalHarmonics: Boolean = false): PlyFloatTable {
        val file = File(path)
        require(file.exists()) { "PLY file not found: $path" }
        return RandomAccessFile(file, "r").use { raf ->
            val header = parseFloatTableHeader(raf)
            when (val recognition = recognise(header)) {
                is Recognition.Compressed ->
                    decompress(raf, header, recognition.layout, includeSphericalHarmonics, path)
                is Recognition.Malformed ->
                    error("'$path' looks like a compressed splat PLY but ${recognition.reason}")
                Recognition.Plain ->
                    error(
                        "'$path' is not a compressed splat PLY — it has no packed_position/" +
                            "packed_rotation/packed_scale/packed_color vertex properties. " +
                            "Read it with readPlyAsFloatTable instead."
                    )
            }
        }
    }

    /**
     * Unpack an already-open, already-recognised compressed PLY. [raf] may be positioned anywhere;
     * this seeks for itself.
     */
    internal fun decompress(
        raf: RandomAccessFile,
        header: PlyTableHeader,
        layout: Layout,
        includeSphericalHarmonics: Boolean,
        path: String
    ): PlyFloatTable {
        val order =
            if (header.format == PlyTableFormat.BINARY_BIG_ENDIAN) ByteOrder.BIG_ENDIAN
            else ByteOrder.LITTLE_ENDIAN

        val shColumns = if (includeSphericalHarmonics) layout.shColumns else emptyList()
        val columns = decompressedColumns + shColumns
        val stride = columns.size
        val offsets = columns.withIndex().associate { (index, name) -> name to index }

        val wanted = buildSet {
            add("chunk")
            add("vertex")
            if (shColumns.isNotEmpty()) add("sh")
        }
        val blocks = readElementBlocks(raf, header, wanted, order, path)
        val chunkBlock = blocks.getValue("chunk")
        val vertexBlock = blocks.getValue("vertex")
        val shBlock = blocks["sh"]

        val count = layout.splatCount
        val outBytes = count.toLong() * stride.toLong() * Float.SIZE_BYTES.toLong()
        require(outBytes <= Int.MAX_VALUE) {
            "'$path' decompresses to $outBytes bytes, more than a single FloatBuffer holds" +
                if (includeSphericalHarmonics) " — try includeSphericalHarmonics = false" else ""
        }
        val out = ByteBuffer.allocateDirect(outBytes.toInt()).order(order).asFloatBuffer()

        // Chunk ranges, hoisted out of the per-splat loop: 18 floats each, read once.
        val chunkRanges = FloatArray(layout.chunkCount * CHUNK_RANGE_COUNT)
        for (chunkIndex in 0 until layout.chunkCount) {
            val base = chunkIndex * CHUNK_RANGE_COUNT
            for ((rangeIndex, name) in chunkRangeOrder.withIndex()) {
                chunkRanges[base + rangeIndex] =
                    if (name in colorChunkProperties && !layout.hasChunkColors) 0f
                    else chunkBlock.float(chunkIndex, name)
            }
        }


        for (splat in 0 until count) {
            val base = (splat / CHUNK_SIZE) * CHUNK_RANGE_COUNT

            val position = vertexBlock.int(splat, "packed_position")
            out.put(lerp(chunkRanges[base + 0], chunkRanges[base + 3], unpackUnorm(position ushr 21, 11)))
            out.put(lerp(chunkRanges[base + 1], chunkRanges[base + 4], unpackUnorm(position ushr 11, 10)))
            out.put(lerp(chunkRanges[base + 2], chunkRanges[base + 5], unpackUnorm(position, 11)))

            val color = vertexBlock.int(splat, "packed_color")
            val red = unpackUnorm(color ushr 24, 8)
            val green = unpackUnorm(color ushr 16, 8)
            val blue = unpackUnorm(color ushr 8, 8)
            val alpha = unpackUnorm(color, 8)
            if (layout.hasChunkColors) {
                out.put(dcFromColor(lerp(chunkRanges[base + 12], chunkRanges[base + 15], red)))
                out.put(dcFromColor(lerp(chunkRanges[base + 13], chunkRanges[base + 16], green)))
                out.put(dcFromColor(lerp(chunkRanges[base + 14], chunkRanges[base + 17], blue)))
            } else {
                out.put(dcFromColor(red))
                out.put(dcFromColor(green))
                out.put(dcFromColor(blue))
            }
            out.put(logit(alpha))

            val rotation = vertexBlock.int(splat, "packed_rotation")
            unpackRotationInto(rotation, out)

            val scale = vertexBlock.int(splat, "packed_scale")
            out.put(lerp(chunkRanges[base + 6], chunkRanges[base + 9], unpackUnorm(scale ushr 21, 11)))
            out.put(lerp(chunkRanges[base + 7], chunkRanges[base + 10], unpackUnorm(scale ushr 11, 10)))
            out.put(lerp(chunkRanges[base + 8], chunkRanges[base + 11], unpackUnorm(scale, 11)))

            if (shBlock != null) {
                for (column in shColumns) {
                    out.put(unpackShCoefficient(shBlock.byte(splat, column)))
                }
            }
        }

        out.flip()
        require(out.limit() == count * stride) {
            "internal error decompressing '$path': wrote ${out.limit()} floats, expected ${count * stride}"
        }
        return PlyFloatTable(offsets = offsets, stride = stride, buffer = out)
    }

    // ------------------------------------------------------------------------
    // Bit-level inverse of splat-transform's CompressedChunk.pack
    // ------------------------------------------------------------------------

    /** Invert `packUnorm`: take the low [bits] of [value] back to 0..1. */
    fun unpackUnorm(value: Int, bits: Int): Float {
        val mask = (1 shl bits) - 1
        return (value and mask).toFloat() / mask.toFloat()
    }

    /** A colour channel, as unorm, back to the SH DC coefficient the trainer produced. */
    fun dcFromColor(color: Float): Float = (color - 0.5f) / SH_C0

    /**
     * A sigmoid'd opacity byte back to its logit, clamped to [OPACITY_LOGIT_LIMIT] so the 0 and 255
     * ends stay finite.
     */
    fun logit(alpha: Float): Float {
        if (alpha <= 0f) return -OPACITY_LOGIT_LIMIT
        if (alpha >= 1f) return OPACITY_LOGIT_LIMIT
        val value = -ln(1.0 / alpha - 1.0)
        return value.toFloat().coerceIn(-OPACITY_LOGIT_LIMIT, OPACITY_LOGIT_LIMIT)
    }

    /** An SH coefficient byte back to its float, matching splat-transform's endpoint handling. */
    fun unpackShCoefficient(byte: Int): Float {
        val normalized = when (byte) {
            0 -> 0f
            255 -> 1f
            else -> (byte + 0.5f) / 256f
        }
        return (normalized - 0.5f) * 8f
    }

    /**
     * Undo the 2,10,10,10 quaternion packing straight into [out] as `rot_0..rot_3`.
     *
     * The encoder drops whichever component had the largest magnitude — it is recoverable from the
     * unit-length constraint — records that component's index in the top two bits, negating the
     * whole quaternion first if it was negative so the dropped one is positive, and stores the other
     * three in ascending index order scaled by `sqrt(2)/2` so that ±1/sqrt(2) fills the range.
     */
    fun unpackRotationInto(value: Int, out: FloatBuffer) {
        val norm = sqrt(2f)
        val a = (unpackUnorm(value ushr 20, 10) - 0.5f) * norm
        val b = (unpackUnorm(value ushr 10, 10) - 0.5f) * norm
        val c = (unpackUnorm(value, 10) - 0.5f) * norm
        val largest = sqrt(maxOf(0f, 1f - (a * a + b * b + c * c)))
        when (value ushr 30) {
            0 -> { out.put(largest); out.put(a); out.put(b); out.put(c) }
            1 -> { out.put(a); out.put(largest); out.put(b); out.put(c) }
            2 -> { out.put(a); out.put(b); out.put(largest); out.put(c) }
            else -> { out.put(a); out.put(b); out.put(c); out.put(largest) }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a * (1f - t) + b * t

    // ------------------------------------------------------------------------
    // Generic binary element reading
    // ------------------------------------------------------------------------

    private const val CHUNK_RANGE_COUNT = 18

    private val chunkRangeOrder = requiredChunkProperties + colorChunkProperties

    private val float32Types = setOf("float", "float32")
    private val uint32Types = setOf("uint", "uint32")
    private val uint8Types = setOf("uchar", "uint8")

    /** One element's rows, verbatim, plus where each property sits inside a row. */
    private class ElementBlock(
        val stride: Int,
        val byteOffsets: Map<String, Int>,
        val bytes: ByteBuffer
    ) {
        fun float(row: Int, property: String): Float =
            bytes.getFloat(row * stride + byteOffsets.getValue(property))

        fun int(row: Int, property: String): Int =
            bytes.getInt(row * stride + byteOffsets.getValue(property))

        fun byte(row: Int, property: String): Int =
            bytes.get(row * stride + byteOffsets.getValue(property)).toInt() and 0xff
    }

    /**
     * Read the [wanted] elements' payloads, skipping the rest by computing their byte length. Every
     * property must be a fixed-size scalar, which [recognise] has already established.
     */
    private fun readElementBlocks(
        raf: RandomAccessFile,
        header: PlyTableHeader,
        wanted: Set<String>,
        order: ByteOrder,
        path: String
    ): Map<String, ElementBlock> {
        val blocks = mutableMapOf<String, ElementBlock>()
        var offset = header.dataStart
        for (element in header.elements) {
            var stride = 0
            val byteOffsets = mutableMapOf<String, Int>()
            for (property in element.properties) {
                byteOffsets[property.name] = stride
                stride += scalarSizeBytes(property.type)
            }
            val length = element.count.toLong() * stride.toLong()
            if (element.name in wanted) {
                require(length <= Int.MAX_VALUE) {
                    "'$path' element '${element.name}' is $length bytes, too large to read in one block"
                }
                require(offset + length <= raf.length()) {
                    "'$path' is truncated: element '${element.name}' needs $length bytes at offset " +
                        "$offset but the file is ${raf.length()} bytes"
                }
                raf.seek(offset)
                val bytes = ByteArray(length.toInt())
                raf.readFully(bytes)
                blocks[element.name] = ElementBlock(
                    stride = stride,
                    byteOffsets = byteOffsets,
                    bytes = ByteBuffer.wrap(bytes).order(order)
                )
            }
            offset += length
        }
        val missing = wanted - blocks.keys
        require(missing.isEmpty()) { "'$path' has no ${missing.joinToString()} element" }
        return blocks
    }

    private fun scalarSizeBytes(type: String): Int = when (type) {
        "char", "int8", "uchar", "uint8" -> 1
        "short", "int16", "ushort", "uint16" -> 2
        "int", "int32", "uint", "uint32", "float", "float32" -> 4
        "double", "float64" -> 8
        else -> error("Unsupported PLY property type: $type")
    }
}

/**
 * Read a PlayCanvas `.compressed.ply` as the uncompressed splat table it stands for. See
 * [CompressedSplatPly] for the format and [CompressedSplatPly.readCompressedSplatPly] for the
 * parameters.
 */
fun readCompressedSplatPly(path: String, includeSphericalHarmonics: Boolean = false): PlyFloatTable =
    CompressedSplatPly.readCompressedSplatPly(path, includeSphericalHarmonics)
