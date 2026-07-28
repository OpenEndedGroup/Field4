@file:OptIn(ExperimentalUnsignedTypes::class)
@file:afield.CompletionCategory("Data")

package afield.stdlib

import afield.four.util.halfBitsToFloat
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Reader for NumPy's `.npy` and `.npz` on-disk formats. [npy] and [npz] are the short entry
 * points over [NumpyIO]; `.npz` is unzipped into a `LinkedHashMap` of entry name to [NpyArray] and
 * must contain at least one `.npy` member. An [NpyArray] holds the raw payload bytes plus the
 * shape, the Fortran-order flag and an [NpyDType], and converts on demand via
 * [NpyArray.asDoubleArray], [NpyArray.asFloatArray], [NpyArray.asLongArray],
 * [NpyArray.asULongArray], [NpyArray.asBooleanArray] and [NpyArray.asComplexDoublePairs] —
 * Fortran-ordered data is remapped to C order unless you ask for [NpyLinearOrder.STORAGE].
 *
 * Read-only and pure JVM: there is no writer and no ndarray arithmetic. Format versions 1–3 are
 * accepted; bool, signed/unsigned int, half/float/double and complex64/128 decode, and any other
 * dtype kind ([NpyKind.OTHER]) throws. Element and byte counts must fit in an `Int`.
 */

/**
 * Which order to walk a multidimensional array in: `C` is row-major, last index varying fastest; `STORAGE`
 * is however the file happens to hold it.
 *
 * They differ **only** for a Fortran-ordered array of rank 2 or more — for anything else the two are the
 * same and the choice does not matter. `C` is the one that makes an array iterate the way the same array
 * does in Python, and it is what the bulk conversions default to; `STORAGE` skips the remap and is cheaper
 * when you do not care.
 *
 * Watch the defaults, which are **not** consistent across [NpyArray]: the per-element accessors
 * ([NpyArray.getDouble], [NpyArray.linearIndex]) default to `STORAGE`, while the bulk conversions default to
 * `C`. On Fortran-ordered data that means indexing and converting disagree unless you say which you want.
 */
enum class NpyLinearOrder {
    C,
    STORAGE
}

/**
 * The byte order a dtype declares, from the `<`, `>`, `=` or `|` character in its descriptor.
 *
 * `NOT_APPLICABLE` is `|`, used for single-byte types where order is meaningless — bool, int8 — and is not
 * an error. `NATIVE` is `=`, meaning whatever the writing machine used.
 */
enum class NpyByteOrder {
    LITTLE_ENDIAN,
    BIG_ENDIAN,
    NATIVE,
    NOT_APPLICABLE
}

/**
 * The family a dtype belongs to — what decides which conversion will accept it.
 *
 * `OTHER` is everything this reader does not decode: strings, datetimes, structured/record dtypes, objects.
 * Having one is not an error — the array still reports its shape and its raw bytes — but converting it throws.
 */
enum class NpyKind {
    BOOL,
    SIGNED_INT,
    UNSIGNED_INT,
    FLOAT,
    COMPLEX,
    OTHER
}

/**
 * A parsed NumPy dtype: the descriptor exactly as the file wrote it, plus the three things worth acting on —
 * byte order, [kind], and bytes per element.
 *
 * [descriptor] is kept verbatim (`"<f8"`, `"|b1"`, `">c16"`) so an unsupported dtype can still be named in an
 * error message rather than reduced to "unknown".
 */
data class NpyDType(
    val descriptor: String,
    val byteOrder: NpyByteOrder,
    val kind: NpyKind,
    val itemSize: Int
) {
    /** Whether this dtype is one the reader can convert at all — anything but [NpyKind.OTHER]. */
    val isNumeric: Boolean
        get() = kind != NpyKind.OTHER
}

/**
 * One array read out of a `.npy` file: the raw payload bytes, the shape, the Fortran-order flag and the
 * dtype, with conversion to Kotlin arrays on demand.
 *
 * Deliberately not an ndarray — there is no arithmetic, no slicing and no writer. It is a decoder: get the
 * numbers out with [asDoubleArray] and friends, or read single elements with [getDouble].
 *
 * The bulk conversions each require a matching [NpyKind] and throw otherwise, so a `float64` file will not
 * quietly come back through [asBooleanArray]. They default to C order and remap Fortran-ordered data for
 * you; the per-element accessors default to storage order instead, which is the inconsistency
 * [NpyLinearOrder] warns about.
 *
 * @throws IllegalArgumentException on construction if the payload length does not match the shape and item
 * size — i.e. a truncated or mis-described file is caught at read time rather than at first access
 */
class NpyArray internal constructor(
    shape: IntArray,
    /** The dtype as declared in the file's header. */
    val dtype: NpyDType,
    /**
     * True when the payload is column-major, as NumPy writes for an array created with `order='F'` or produced
     * by transposing. Only meaningful above rank 1.
     */
    val fortranOrder: Boolean,
    private val rawBytes: ByteArray
) {
    private val dims = shape.copyOf()
    private val storageStrides = computeStrides(dims, fortranOrder)
    private val cStrides = computeStrides(dims, fortran = false)
    private val needsCOrderRemap = fortranOrder && dims.size > 1

    /** The array's dimensions, outermost first — NumPy's `.shape`. Empty for a 0-d scalar array. */
    val shape: List<Int> = dims.toList()

    /** How many dimensions there are — NumPy's `.ndim`. */
    val rank: Int
        get() = dims.size

    /** Total number of elements, the product of [shape] — NumPy's `.size`. 1 for a 0-d array, not 0. */
    val elementCount: Int = elementCountOf(dims)

    /** Size of the payload in bytes, `elementCount * dtype.itemSize` — NumPy's `.nbytes`. */
    val nbytes: Int
        get() = rawBytes.size

    private val cToStorageIndices: IntArray by lazy {
        if (!needsCOrderRemap) {
            IntArray(elementCount) { it }
        } else {
            IntArray(elementCount) { cLinearIndex ->
                val indices = indicesFromLinear(cLinearIndex, cStrides, dims)
                linearFromIndices(indices, storageStrides, dims)
            }
        }
    }

    init {
        require(dtype.itemSize > 0) { "dtype.itemSize must be positive, found ${dtype.itemSize}" }
        val expected = safeByteCount(elementCount, dtype.itemSize, "array")
        require(rawBytes.size == expected) {
            "Expected $expected data bytes for shape=${dims.contentToString()} and dtype=${dtype.descriptor}, got ${rawBytes.size}"
        }
    }

    /** A copy of the undecoded payload, exactly as it sat in the file. For handing to your own decoder. */
    fun copyBytes(): ByteArray = rawBytes.copyOf()

    /**
     * The flat offset of an element from its per-dimension indices, in **storage** order.
     *
     * The vararg form for readability: `a.linearIndex(2, 3)`. Note the storage default — on a Fortran-ordered
     * array this is not the C-order offset; pass the order explicitly if that matters.
     */
    fun linearIndex(vararg indices: Int): Int = linearIndex(indices, NpyLinearOrder.STORAGE)

    /**
     * The flat offset of an element from its indices, in the order you name. Defaults to **storage** order, not C.
     *
     * @throws IllegalArgumentException if the index count does not match [rank], or an index is out of bounds
     */
    fun linearIndex(indices: IntArray, order: NpyLinearOrder = NpyLinearOrder.STORAGE): Int {
        val strides = when (order) {
            NpyLinearOrder.STORAGE -> storageStrides
            NpyLinearOrder.C -> cStrides
        }
        return linearFromIndices(indices, strides, dims)
    }

    /**
     * One element as a `Double`, whatever its dtype — `a.getDouble(2, 3)`.
     *
     * The random-access read, for when you want a few values out of a large array rather than the whole thing.
     * Integers and half floats are widened; a complex dtype yields its real part only. Indices are in
     * **storage** order by default.
     */
    fun getDouble(vararg indices: Int): Double = getDouble(indices, NpyLinearOrder.STORAGE)

    /**
     * One element as a `Double`, with the index order named. Defaults to **storage** order.
     *
     * Pass `NpyLinearOrder.C` to index a Fortran-ordered array the way you would in Python; the remap table is
     * built once, lazily, and then reused.
     */
    fun getDouble(indices: IntArray, order: NpyLinearOrder = NpyLinearOrder.STORAGE): Double {
        val linear = linearIndex(indices, order)
        val storageLinear = when {
            order == NpyLinearOrder.C && needsCOrderRemap -> cToStorageIndices[linear]
            else -> linear
        }
        return readElementAsDouble(storageLinear)
    }

    /**
     * The array as a `BooleanArray`, C order by default. **Only** for a `bool` dtype — anything else throws
     * rather than being coerced, so a 0/1 integer array has to go through [asLongArray].
     *
     * Any non-zero byte is true, matching NumPy.
     */
    fun asBooleanArray(order: NpyLinearOrder = NpyLinearOrder.C): BooleanArray {
        require(dtype.kind == NpyKind.BOOL) {
            "Cannot convert dtype=${dtype.descriptor} to BooleanArray"
        }
        val storage = BooleanArray(elementCount) { i ->
            rawBytes[i] != 0.toByte()
        }
        return remapToCOrder(storage, order)
    }

    /**
     * The array as a `LongArray`, C order by default. For the signed integer dtypes and bool.
     *
     * A `uint64` whose values exceed `Long.MAX_VALUE` wraps to negative here — use [asULongArray] if the file
     * might contain such values.
     */
    fun asLongArray(order: NpyLinearOrder = NpyLinearOrder.C): LongArray {
        require(dtype.kind == NpyKind.BOOL || dtype.kind == NpyKind.SIGNED_INT || dtype.kind == NpyKind.UNSIGNED_INT) {
            "Cannot convert dtype=${dtype.descriptor} to LongArray"
        }
        val bb = dataBuffer()
        val storage = LongArray(elementCount) { i ->
            readElementAsLong(bb, i)
        }
        return remapToCOrder(storage, order)
    }

    /**
     * The array as a `ULongArray`, C order by default — the unsigned counterpart of [asLongArray], and the one
     * to use for a `uint64` that may run past the signed range.
     */
    fun asULongArray(order: NpyLinearOrder = NpyLinearOrder.C): ULongArray {
        require(dtype.kind == NpyKind.BOOL || dtype.kind == NpyKind.SIGNED_INT || dtype.kind == NpyKind.UNSIGNED_INT) {
            "Cannot convert dtype=${dtype.descriptor} to ULongArray"
        }
        val bb = dataBuffer()
        val storage = ULongArray(elementCount) { i ->
            readElementAsULong(bb, i)
        }
        return remapToCOrder(storage, order)
    }

    /**
     * The array as a `DoubleArray`, C order by default — the workhorse conversion, and what most callers want.
     *
     * Accepts bool, every integer width and half/float/double, widening as needed. A **complex** dtype is
     * refused rather than silently truncated to its real part; use [asComplexDoublePairs]. Note that a very
     * large `int64` or `uint64` loses precision on the way into a `Double`, as always.
     */
    fun asDoubleArray(order: NpyLinearOrder = NpyLinearOrder.C): DoubleArray {
        require(dtype.kind != NpyKind.COMPLEX) {
            "dtype=${dtype.descriptor} is complex; use asComplexDoublePairs()"
        }
        require(dtype.isNumeric) {
            "Cannot convert dtype=${dtype.descriptor} to DoubleArray"
        }
        val bb = dataBuffer()
        val storage = DoubleArray(elementCount) { i ->
            readElementAsDouble(bb, i)
        }
        return remapToCOrder(storage, order)
    }

    /**
     * The array as a `FloatArray`, C order by default — [asDoubleArray] narrowed, for feeding a GPU buffer or
     * Field's `Vec` types.
     *
     * Allocates the `DoubleArray` first and then narrows, so it costs twice the memory of the result at its peak,
     * and values outside float range become infinities.
     */
    fun asFloatArray(order: NpyLinearOrder = NpyLinearOrder.C): FloatArray {
        val doubles = asDoubleArray(order)
        return FloatArray(doubles.size) { i -> doubles[i].toFloat() }
    }

    /**
     * A complex array flattened to **interleaved real/imaginary pairs**: `2 * elementCount` doubles, real at even
     * indices and imaginary at odd. C order by default.
     *
     * Only for `complex64` and `complex128`; anything else throws. Note the returned length is twice
     * [elementCount], so index it as `out[i * 2]` and `out[i * 2 + 1]` rather than by element.
     */
    fun asComplexDoublePairs(order: NpyLinearOrder = NpyLinearOrder.C): DoubleArray {
        require(dtype.kind == NpyKind.COMPLEX) {
            "Cannot convert dtype=${dtype.descriptor} to complex pairs"
        }
        val bb = dataBuffer()
        val storage = DoubleArray(elementCount * 2)
        when (dtype.itemSize) {
            8 -> {
                for (i in 0 until elementCount) {
                    val offset = i * dtype.itemSize
                    storage[i * 2] = bb.getFloat(offset).toDouble()
                    storage[i * 2 + 1] = bb.getFloat(offset + 4).toDouble()
                }
            }

            16 -> {
                for (i in 0 until elementCount) {
                    val offset = i * dtype.itemSize
                    storage[i * 2] = bb.getDouble(offset)
                    storage[i * 2 + 1] = bb.getDouble(offset + 8)
                }
            }

            else -> {
                throw IllegalArgumentException("Unsupported complex item size ${dtype.itemSize} for dtype=${dtype.descriptor}")
            }
        }

        if (order == NpyLinearOrder.STORAGE || !needsCOrderRemap) return storage

        val out = DoubleArray(storage.size)
        for (cIndex in 0 until elementCount) {
            val sIndex = cToStorageIndices[cIndex]
            out[cIndex * 2] = storage[sIndex * 2]
            out[cIndex * 2 + 1] = storage[sIndex * 2 + 1]
        }
        return out
    }

    private fun dataBuffer(): ByteBuffer =
        ByteBuffer.wrap(rawBytes).order(dtype.byteOrder.toJvmByteOrder())

    private fun readElementAsDouble(storageLinear: Int): Double {
        return readElementAsDouble(dataBuffer(), storageLinear)
    }

    private fun readElementAsDouble(bb: ByteBuffer, storageLinear: Int): Double {
        val offset = storageLinear * dtype.itemSize
        return when (dtype.kind) {
            NpyKind.BOOL -> if (rawBytes[storageLinear] != 0.toByte()) 1.0 else 0.0
            NpyKind.SIGNED_INT -> readSignedInt(bb, offset, dtype.itemSize).toDouble()
            NpyKind.UNSIGNED_INT -> readUnsignedIntAsULong(bb, offset, dtype.itemSize).toDouble()
            NpyKind.FLOAT -> readFloating(bb, offset, dtype.itemSize)
            NpyKind.COMPLEX -> throw IllegalArgumentException("dtype=${dtype.descriptor} is complex; use asComplexDoublePairs()")
            NpyKind.OTHER -> throw IllegalArgumentException("Unsupported dtype kind=${dtype.kind} (${dtype.descriptor})")
        }
    }

    private fun readElementAsLong(bb: ByteBuffer, storageLinear: Int): Long {
        val offset = storageLinear * dtype.itemSize
        return when (dtype.kind) {
            NpyKind.BOOL -> if (rawBytes[storageLinear] != 0.toByte()) 1L else 0L
            NpyKind.SIGNED_INT -> readSignedInt(bb, offset, dtype.itemSize)
            NpyKind.UNSIGNED_INT -> {
                val value = readUnsignedIntAsULong(bb, offset, dtype.itemSize)
                if (value > Long.MAX_VALUE.toULong()) {
                    throw IllegalArgumentException("Value $value from dtype=${dtype.descriptor} cannot fit into Long")
                }
                value.toLong()
            }

            else -> throw IllegalArgumentException("Cannot convert dtype=${dtype.descriptor} to LongArray")
        }
    }

    private fun readElementAsULong(bb: ByteBuffer, storageLinear: Int): ULong {
        val offset = storageLinear * dtype.itemSize
        return when (dtype.kind) {
            NpyKind.BOOL -> if (rawBytes[storageLinear] != 0.toByte()) 1uL else 0uL
            NpyKind.SIGNED_INT -> readSignedInt(bb, offset, dtype.itemSize).toULong()
            NpyKind.UNSIGNED_INT -> readUnsignedIntAsULong(bb, offset, dtype.itemSize)
            else -> throw IllegalArgumentException("Cannot convert dtype=${dtype.descriptor} to ULongArray")
        }
    }

    private fun remapToCOrder(values: DoubleArray, order: NpyLinearOrder): DoubleArray {
        if (order == NpyLinearOrder.STORAGE || !needsCOrderRemap) return values
        val out = DoubleArray(values.size)
        for (i in values.indices) out[i] = values[cToStorageIndices[i]]
        return out
    }

    private fun remapToCOrder(values: LongArray, order: NpyLinearOrder): LongArray {
        if (order == NpyLinearOrder.STORAGE || !needsCOrderRemap) return values
        val out = LongArray(values.size)
        for (i in values.indices) out[i] = values[cToStorageIndices[i]]
        return out
    }

    private fun remapToCOrder(values: ULongArray, order: NpyLinearOrder): ULongArray {
        if (order == NpyLinearOrder.STORAGE || !needsCOrderRemap) return values
        val out = ULongArray(values.size)
        for (i in values.indices) out[i] = values[cToStorageIndices[i]]
        return out
    }

    private fun remapToCOrder(values: BooleanArray, order: NpyLinearOrder): BooleanArray {
        if (order == NpyLinearOrder.STORAGE || !needsCOrderRemap) return values
        val out = BooleanArray(values.size)
        for (i in values.indices) out[i] = values[cToStorageIndices[i]]
        return out
    }

    override fun toString(): String {
        return "NpyArray(shape=$shape, dtype=${dtype.descriptor}, fortranOrder=$fortranOrder, nbytes=$nbytes)"
    }
}

/**
 * The reader itself: `.npy` and `.npz` off disk, with the header parsing and dtype decoding behind it.
 *
 * Most callers want the top-level [npy] and [npz] functions instead — these are the same thing spelled out,
 * for code that prefers an explicit namespace. Format versions 1 through 3 are accepted, and everything is
 * read into memory at once, so a multi-gigabyte file is a multi-gigabyte allocation.
 */
object NumpyIO {
    private val magic = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())

    private val descrRegex = Regex("""['"]descr['"]\s*:\s*['"]([^'"]+)['"]""")
    private val fortranRegex = Regex("""['"]fortran_order['"]\s*:\s*(True|False)""")
    private val shapeRegex = Regex("""['"]shape['"]\s*:\s*\(([^)]*)\)""")

    /** Reads one `.npy` from a path. The path is used verbatim — it does **not** go through [FileManager]. */
    fun readNpy(path: String): NpyArray = readNpy(File(path))

    /**
     * Reads one `.npy` file into an [NpyArray].
     *
     * @throws IllegalArgumentException on a bad magic header, an unsupported format version, an undecodable dtype,
     * or a payload whose length disagrees with the declared shape
     */
    fun readNpy(file: File): NpyArray {
        file.inputStream().buffered().use { input ->
            return readNpy(input, file.absolutePath)
        }
    }

    /** Reads a `.npz` archive from a path, keyed by member name with the `.npy` suffix removed. */
    fun readNpz(path: String): LinkedHashMap<String, NpyArray> = readNpz(File(path))

    /**
     * Reads every `.npy` member of a `.npz` archive, keyed by member name **without** the `.npy` suffix, in the
     * order the zip stores them.
     *
     * Non-`.npy` members and directories are skipped silently, so an archive carrying extra files still reads. An
     * archive with **no** `.npy` members throws rather than returning empty, and so does a duplicate key.
     *
     * Every member is decompressed and held in memory at once.
     */
    fun readNpz(file: File): LinkedHashMap<String, NpyArray> {
        val out = linkedMapOf<String, NpyArray>()

        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".npy", ignoreCase = true)) continue

                val key = entry.name.substring(0, entry.name.length - 4)
                val entryBytes = zip.readBytes()
                val entryArray = ByteArrayInputStream(entryBytes).use { entryInput ->
                    readNpy(entryInput, "${file.absolutePath}!/${entry.name}")
                }

                require(out.put(key, entryArray) == null) {
                    "Duplicate entry '$key' in ${file.absolutePath}"
                }
            }
        }

        require(out.isNotEmpty()) { "No .npy entries found in ${file.absolutePath}" }
        return LinkedHashMap(out)
    }

    private fun readNpy(input: InputStream, sourceName: String): NpyArray {
        val gotMagic = readExactly(input, magic.size, sourceName)
        require(gotMagic.contentEquals(magic)) {
            "Invalid NumPy magic header in $sourceName"
        }

        val major = readUByte(input, sourceName)
        val minor = readUByte(input, sourceName)

        val headerLengthLong = when (major) {
            1 -> readUShortLe(input, sourceName).toLong()
            2, 3 -> readUIntLe(input, sourceName)
            else -> throw IllegalArgumentException("Unsupported .npy format version $major.$minor in $sourceName")
        }

        require(headerLengthLong <= Int.MAX_VALUE.toLong()) {
            "Header too large in $sourceName: $headerLengthLong bytes"
        }
        val headerLength = headerLengthLong.toInt()

        require(headerLength >= 0) { "Negative header length in $sourceName" }
        require(headerLength <= 16 * 1024 * 1024) { "Header too large in $sourceName: $headerLength bytes" }

        val headerBytes = readExactly(input, headerLength, sourceName)
        val headerText = headerBytes.toString(if (major >= 3) Charsets.UTF_8 else Charsets.US_ASCII).trim()

        val parsed = parseHeader(headerText, sourceName)
        val elementCount = elementCountOf(parsed.shape)
        val dataSize = safeByteCount(elementCount, parsed.dtype.itemSize, sourceName)
        val payload = readExactly(input, dataSize, sourceName)

        return NpyArray(parsed.shape, parsed.dtype, parsed.fortranOrder, payload)
    }

    private fun parseHeader(headerText: String, sourceName: String): ParsedHeader {
        val descr = descrRegex.find(headerText)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Missing 'descr' in header for $sourceName")
        val fortran = fortranRegex.find(headerText)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Missing 'fortran_order' in header for $sourceName")
        val shapeText = shapeRegex.find(headerText)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Missing 'shape' in header for $sourceName")

        val dtype = parseDType(descr, sourceName)
        val shape = parseShape(shapeText, sourceName)

        return ParsedHeader(
            dtype = dtype,
            fortranOrder = fortran == "True",
            shape = shape
        )
    }

    private fun parseDType(descr: String, sourceName: String): NpyDType {
        val text = descr.trim()
        require(text.isNotEmpty()) { "Empty dtype descriptor in $sourceName" }

        val hasEndianPrefix = text[0] == '<' || text[0] == '>' || text[0] == '|' || text[0] == '='
        val endianChar = if (hasEndianPrefix) text[0] else '='
        val body = if (hasEndianPrefix) text.substring(1) else text

        require(body.length >= 2) { "Invalid dtype descriptor '$text' in $sourceName" }

        val kindCode = body[0]
        val itemSize = body.substring(1).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid dtype item size in descriptor '$text' for $sourceName")
        require(itemSize > 0) { "dtype item size must be > 0 in descriptor '$text' for $sourceName" }

        val byteOrder = when (endianChar) {
            '<' -> NpyByteOrder.LITTLE_ENDIAN
            '>' -> NpyByteOrder.BIG_ENDIAN
            '=' -> NpyByteOrder.NATIVE
            '|' -> NpyByteOrder.NOT_APPLICABLE
            else -> throw IllegalArgumentException("Unsupported dtype byte-order prefix '$endianChar' in '$text' for $sourceName")
        }

        val kind = when (kindCode) {
            '?', 'b' -> NpyKind.BOOL
            'i' -> NpyKind.SIGNED_INT
            'u' -> NpyKind.UNSIGNED_INT
            'f' -> NpyKind.FLOAT
            'c' -> NpyKind.COMPLEX
            else -> NpyKind.OTHER
        }

        return NpyDType(
            descriptor = text,
            byteOrder = byteOrder,
            kind = kind,
            itemSize = itemSize
        )
    }

    private fun parseShape(shapeText: String, sourceName: String): IntArray {
        val raw = shapeText.trim()
        if (raw.isEmpty()) return intArrayOf()

        val dims = mutableListOf<Int>()
        raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { token ->
                val normalized = token.removeSuffix("L").removeSuffix("l")
                val dim = normalized.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid shape dimension '$token' in $sourceName")
                require(dim >= 0) { "Negative shape dimension '$dim' in $sourceName" }
                require(dim <= Int.MAX_VALUE.toLong()) { "Shape dimension too large ($dim) in $sourceName" }
                dims += dim.toInt()
            }
        return dims.toIntArray()
    }
}

/**
 * Reads a `.npy` file — `npy("data.npy").asDoubleArray()` is the whole idiom.
 *
 * The path is passed straight to `File` and is **not** resolved through [FileManager], so a bare relative name is
 * relative to the JVM's working directory rather than the document. Use `"data.npy".file` if you want Field's
 * resolution.
 */
fun npy(path: String): NpyArray = NumpyIO.readNpy(path)

/** Reads a `.npy` from a `File` — the overload to pair with `"data.npy".file`. */
fun npy(file: File): NpyArray = NumpyIO.readNpy(file)

/**
 * Reads a `.npz` archive into a map of member name to array, preserving the archive's own order.
 *
 * Keys have the `.npy` suffix stripped, so `np.savez(f, xs=..., ys=...)` gives you `"xs"` and `"ys"`. Same
 * working-directory caveat as [npy].
 */
fun npz(path: String): LinkedHashMap<String, NpyArray> = NumpyIO.readNpz(path)

/** Reads a `.npz` archive from a `File`, keyed by member name with `.npy` stripped. */
fun npz(file: File): LinkedHashMap<String, NpyArray> = NumpyIO.readNpz(file)

private data class ParsedHeader(
    val dtype: NpyDType,
    val fortranOrder: Boolean,
    val shape: IntArray
)

private fun computeStrides(shape: IntArray, fortran: Boolean): IntArray {
    if (shape.isEmpty()) return intArrayOf()
    val strides = IntArray(shape.size)
    var stride = 1L
    if (fortran) {
        for (i in shape.indices) {
            strides[i] = stride.toInt()
            stride *= shape[i].toLong()
        }
    } else {
        for (i in shape.lastIndex downTo 0) {
            strides[i] = stride.toInt()
            stride *= shape[i].toLong()
        }
    }
    return strides
}

private fun linearFromIndices(indices: IntArray, strides: IntArray, shape: IntArray): Int {
    require(indices.size == shape.size) {
        "Expected ${shape.size} indices but got ${indices.size}"
    }
    var linear = 0
    for (i in indices.indices) {
        val index = indices[i]
        val dim = shape[i]
        require(index in 0 until dim) {
            "Index $index out of bounds for dimension $i (size=$dim)"
        }
        linear += index * strides[i]
    }
    return linear
}

private fun indicesFromLinear(linear: Int, cStrides: IntArray, shape: IntArray): IntArray {
    if (shape.isEmpty()) return intArrayOf()
    var remaining = linear
    val out = IntArray(shape.size)
    for (i in shape.indices) {
        val stride = cStrides[i]
        if (shape[i] == 0) {
            out[i] = 0
            continue
        }
        out[i] = remaining / stride
        remaining %= stride
    }
    return out
}

private fun elementCountOf(shape: IntArray): Int {
    var count = 1L
    for (d in shape) {
        require(d >= 0) { "Shape dimensions must be >= 0, found $d" }
        count *= d.toLong()
        require(count <= Int.MAX_VALUE.toLong()) {
            "Array is too large ($count elements), Int-sized storage limit exceeded"
        }
    }
    return count.toInt()
}

private fun safeByteCount(elementCount: Int, itemSize: Int, sourceName: String): Int {
    val size = elementCount.toLong() * itemSize.toLong()
    require(size <= Int.MAX_VALUE.toLong()) {
        "Array payload too large in $sourceName: $size bytes"
    }
    return size.toInt()
}

private fun NpyByteOrder.toJvmByteOrder(): ByteOrder =
    when (this) {
        NpyByteOrder.LITTLE_ENDIAN -> ByteOrder.LITTLE_ENDIAN
        NpyByteOrder.BIG_ENDIAN -> ByteOrder.BIG_ENDIAN
        NpyByteOrder.NATIVE -> ByteOrder.nativeOrder()
        NpyByteOrder.NOT_APPLICABLE -> ByteOrder.nativeOrder()
    }

private fun readSignedInt(bb: ByteBuffer, offset: Int, itemSize: Int): Long =
    when (itemSize) {
        1 -> bb.get(offset).toLong()
        2 -> bb.getShort(offset).toLong()
        4 -> bb.getInt(offset).toLong()
        8 -> bb.getLong(offset)
        else -> throw IllegalArgumentException("Unsupported signed integer item size $itemSize")
    }

private fun readUnsignedIntAsULong(bb: ByteBuffer, offset: Int, itemSize: Int): ULong =
    when (itemSize) {
        1 -> (bb.get(offset).toInt() and 0xFF).toULong()
        2 -> (bb.getShort(offset).toInt() and 0xFFFF).toULong()
        4 -> (bb.getInt(offset).toLong() and 0xFFFF_FFFFL).toULong()
        8 -> bb.getLong(offset).toULong()
        else -> throw IllegalArgumentException("Unsupported unsigned integer item size $itemSize")
    }

private fun readFloating(bb: ByteBuffer, offset: Int, itemSize: Int): Double =
    when (itemSize) {
        2 -> halfBitsToFloat(bb.getShort(offset)).toDouble()
        4 -> bb.getFloat(offset).toDouble()
        8 -> bb.getDouble(offset)
        else -> throw IllegalArgumentException("Unsupported floating-point item size $itemSize")
    }

private fun readExactly(input: InputStream, len: Int, sourceName: String): ByteArray {
    val out = ByteArray(len)
    var offset = 0
    while (offset < len) {
        val n = input.read(out, offset, len - offset)
        if (n < 0) throw EOFException("Unexpected EOF while reading $len bytes from $sourceName")
        offset += n
    }
    return out
}

private fun readUByte(input: InputStream, sourceName: String): Int {
    val b = input.read()
    if (b < 0) throw EOFException("Unexpected EOF while reading byte from $sourceName")
    return b and 0xFF
}

private fun readUShortLe(input: InputStream, sourceName: String): Int {
    val b0 = readUByte(input, sourceName)
    val b1 = readUByte(input, sourceName)
    return b0 or (b1 shl 8)
}

private fun readUIntLe(input: InputStream, sourceName: String): Long {
    val b0 = readUByte(input, sourceName).toLong()
    val b1 = readUByte(input, sourceName).toLong()
    val b2 = readUByte(input, sourceName).toLong()
    val b3 = readUByte(input, sourceName).toLong()
    return (b0) or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}
