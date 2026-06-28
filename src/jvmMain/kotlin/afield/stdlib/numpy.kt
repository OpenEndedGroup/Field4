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

enum class NpyLinearOrder {
    C,
    STORAGE
}

enum class NpyByteOrder {
    LITTLE_ENDIAN,
    BIG_ENDIAN,
    NATIVE,
    NOT_APPLICABLE
}

enum class NpyKind {
    BOOL,
    SIGNED_INT,
    UNSIGNED_INT,
    FLOAT,
    COMPLEX,
    OTHER
}

data class NpyDType(
    val descriptor: String,
    val byteOrder: NpyByteOrder,
    val kind: NpyKind,
    val itemSize: Int
) {
    val isNumeric: Boolean
        get() = kind != NpyKind.OTHER
}

class NpyArray internal constructor(
    shape: IntArray,
    val dtype: NpyDType,
    val fortranOrder: Boolean,
    private val rawBytes: ByteArray
) {
    private val dims = shape.copyOf()
    private val storageStrides = computeStrides(dims, fortranOrder)
    private val cStrides = computeStrides(dims, fortran = false)
    private val needsCOrderRemap = fortranOrder && dims.size > 1

    val shape: List<Int> = dims.toList()

    val rank: Int
        get() = dims.size

    val elementCount: Int = elementCountOf(dims)

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

    fun copyBytes(): ByteArray = rawBytes.copyOf()

    fun linearIndex(vararg indices: Int): Int = linearIndex(indices, NpyLinearOrder.STORAGE)

    fun linearIndex(indices: IntArray, order: NpyLinearOrder = NpyLinearOrder.STORAGE): Int {
        val strides = when (order) {
            NpyLinearOrder.STORAGE -> storageStrides
            NpyLinearOrder.C -> cStrides
        }
        return linearFromIndices(indices, strides, dims)
    }

    fun getDouble(vararg indices: Int): Double = getDouble(indices, NpyLinearOrder.STORAGE)

    fun getDouble(indices: IntArray, order: NpyLinearOrder = NpyLinearOrder.STORAGE): Double {
        val linear = linearIndex(indices, order)
        val storageLinear = when {
            order == NpyLinearOrder.C && needsCOrderRemap -> cToStorageIndices[linear]
            else -> linear
        }
        return readElementAsDouble(storageLinear)
    }

    fun asBooleanArray(order: NpyLinearOrder = NpyLinearOrder.C): BooleanArray {
        require(dtype.kind == NpyKind.BOOL) {
            "Cannot convert dtype=${dtype.descriptor} to BooleanArray"
        }
        val storage = BooleanArray(elementCount) { i ->
            rawBytes[i] != 0.toByte()
        }
        return remapToCOrder(storage, order)
    }

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

    fun asFloatArray(order: NpyLinearOrder = NpyLinearOrder.C): FloatArray {
        val doubles = asDoubleArray(order)
        return FloatArray(doubles.size) { i -> doubles[i].toFloat() }
    }

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

object NumpyIO {
    private val magic = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())

    private val descrRegex = Regex("""['"]descr['"]\s*:\s*['"]([^'"]+)['"]""")
    private val fortranRegex = Regex("""['"]fortran_order['"]\s*:\s*(True|False)""")
    private val shapeRegex = Regex("""['"]shape['"]\s*:\s*\(([^)]*)\)""")

    fun readNpy(path: String): NpyArray = readNpy(File(path))

    fun readNpy(file: File): NpyArray {
        file.inputStream().buffered().use { input ->
            return readNpy(input, file.absolutePath)
        }
    }

    fun readNpz(path: String): LinkedHashMap<String, NpyArray> = readNpz(File(path))

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

fun npy(path: String): NpyArray = NumpyIO.readNpy(path)

fun npy(file: File): NpyArray = NumpyIO.readNpy(file)

fun npz(path: String): LinkedHashMap<String, NpyArray> = NumpyIO.readNpz(path)

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
