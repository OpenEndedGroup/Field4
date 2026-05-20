@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class PlyFloatTable(
    val offsets: Map<String, Int>,
    val stride: Int,
    val buffer: FloatBuffer
) {
    val columns: List<String>
        get() = offsets.entries.sortedBy { it.value }.map { it.key }

    val rowCount: Int
        get() {
            require(stride > 0) { "PLY float table has no scalar properties" }
            val totalFloats = buffer.limit()
            require(totalFloats % stride == 0) {
                "Buffer length ($totalFloats) is not a multiple of stride ($stride)"
            }
            return totalFloats / stride
        }

    fun hasColumn(name: String): Boolean = name in offsets

    fun columnOffset(name: String): Int =
        offsets[name] ?: throw IllegalArgumentException("Field '$name' not found. Available fields: ${columns.joinToString()}")

    fun floatAt(row: Int, field: String): Float {
        require(row in 0 until rowCount) { "row $row out of bounds for $rowCount-row PLY table" }
        return buffer.get(row * stride + columnOffset(field))
    }

    fun floatAtOrNull(row: Int, field: String): Float? =
        offsets[field]?.let { buffer.get(row * stride + it) }

    fun firstFloatAtOrNull(row: Int, fields: Iterable<String>): Float? {
        for (field in fields) {
            val value = floatAtOrNull(row, field)
            if (value != null) return value
        }
        return null
    }

    fun requireColumns(vararg fields: String): PlyFloatTable {
        val missing = fields.filterNot(offsets::containsKey)
        require(missing.isEmpty()) {
            "PLY table is missing required field(s): ${missing.joinToString()}. Available fields: ${columns.joinToString()}"
        }
        return this
    }

    fun subsetFloatBufferColumns(selectedFields: List<String>): FloatBuffer {
        val rows = rowCount
        val newColCount = selectedFields.size
        val newBytes = rows * newColCount * Float.SIZE_BYTES
        val newBb = ByteBuffer.allocateDirect(newBytes).order(buffer.order())
        val newFb = newBb.asFloatBuffer()

        for (row in 0 until rows) {
            val baseIdx = row * stride
            for (field in selectedFields) {
                val colIdx = columnOffset(field)
                newFb.put(buffer.get(baseIdx + colIdx))
            }
        }

        newFb.flip()
        return newFb
    }
}

private enum class PlyTableFormat {
    ASCII, BINARY_LITTLE_ENDIAN, BINARY_BIG_ENDIAN
}

private data class PlyTableProperty(
    val name: String,
    val type: String,
    val isList: Boolean = false,
    val listCountType: String? = null,
    val listElementType: String? = null
)

private data class PlyTableElement(
    val name: String,
    val count: Int,
    val properties: List<PlyTableProperty>
)

private data class PlyTableHeader(
    val format: PlyTableFormat,
    val elements: List<PlyTableElement>,
    val dataStart: Long
)

fun readPlyAsFloatTable(path: String, elementName: String = "vertex"): PlyFloatTable {
    val file = File(path)
    require(file.exists()) { "PLY file not found: $path" }

    RandomAccessFile(file, "r").use { raf ->
        val header = parseFloatTableHeader(raf)
        val target = header.elements.find { it.name == elementName }
            ?: error("PLY file '$path' does not contain element '$elementName'")
        val scalarProperties = target.properties.filterNot { it.isList }
        require(scalarProperties.isNotEmpty()) { "PLY element '$elementName' has no scalar properties" }

        val offsets = scalarProperties.mapIndexed { index, property -> property.name to index }.toMap()
        val outBytes = target.count.toLong() * scalarProperties.size.toLong() * Float.SIZE_BYTES.toLong()
        require(outBytes <= Int.MAX_VALUE) {
            "PLY element '$elementName' is too large for a single FloatBuffer: $outBytes bytes"
        }
        val out = ByteBuffer.allocateDirect(outBytes.toInt())
            .order(if (header.format == PlyTableFormat.BINARY_BIG_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()

        when (header.format) {
            PlyTableFormat.ASCII -> parseAsciiFloatTable(raf, header, elementName, out)
            PlyTableFormat.BINARY_LITTLE_ENDIAN -> parseBinaryFloatTable(raf, header, elementName, out, ByteOrder.LITTLE_ENDIAN)
            PlyTableFormat.BINARY_BIG_ENDIAN -> parseBinaryFloatTable(raf, header, elementName, out, ByteOrder.BIG_ENDIAN)
        }

        out.flip()
        return PlyFloatTable(offsets = offsets, stride = scalarProperties.size, buffer = out)
    }
}

fun testPly(fn: String): PlyFloatTable {
    val table = readPlyAsFloatTable(fn)
    println("Columns (${table.stride}): ${table.columns}")
    println("Rows loaded: ${table.rowCount}")
    println("Total floats loaded: ${table.buffer.limit()}")
    return table
}

private fun parseFloatTableHeader(raf: RandomAccessFile): PlyTableHeader {
    val magic = raf.readLine()?.trim() ?: error("Empty PLY file")
    require(magic == "ply") { "File does not start with 'ply' magic number" }

    var format: PlyTableFormat? = null
    val elements = mutableListOf<PlyTableElement>()
    var currentElement: PlyTableElement? = null
    val currentProperties = mutableListOf<PlyTableProperty>()

    fun flushElement() {
        currentElement?.let {
            elements += it.copy(properties = currentProperties.toList())
            currentProperties.clear()
        }
    }

    while (true) {
        val line = raf.readLine()?.trim() ?: throw IOException("Unexpected EOF in PLY header")
        if (line.isEmpty() || line.startsWith("comment") || line.startsWith("obj_info")) continue

        val parts = line.split(Regex("\\s+"))
        when (parts[0]) {
            "format" -> {
                format = when (parts.getOrNull(1)) {
                    "ascii" -> PlyTableFormat.ASCII
                    "binary_little_endian" -> PlyTableFormat.BINARY_LITTLE_ENDIAN
                    "binary_big_endian" -> PlyTableFormat.BINARY_BIG_ENDIAN
                    else -> error("Unknown PLY format: ${parts.getOrNull(1)}")
                }
            }

            "element" -> {
                require(parts.size >= 3) { "Malformed PLY element line: '$line'" }
                flushElement()
                currentElement = PlyTableElement(parts[1], parts[2].toInt(), emptyList())
            }

            "property" -> {
                require(currentElement != null) { "PLY property appears before any element: '$line'" }
                if (parts.getOrNull(1) == "list") {
                    require(parts.size >= 5) { "Malformed PLY list property line: '$line'" }
                    currentProperties += PlyTableProperty(
                        name = parts[4],
                        type = "",
                        isList = true,
                        listCountType = parts[2],
                        listElementType = parts[3]
                    )
                } else {
                    require(parts.size >= 3) { "Malformed PLY property line: '$line'" }
                    currentProperties += PlyTableProperty(name = parts[2], type = parts[1])
                }
            }

            "end_header" -> {
                flushElement()
                return PlyTableHeader(
                    format = format ?: error("No PLY format specified"),
                    elements = elements,
                    dataStart = raf.filePointer
                )
            }
        }
    }
}

private fun parseAsciiFloatTable(
    raf: RandomAccessFile,
    header: PlyTableHeader,
    elementName: String,
    out: FloatBuffer
) {
    raf.seek(header.dataStart)
    for (element in header.elements) {
        repeat(element.count) {
            val line = raf.readLine()?.trim() ?: error("Unexpected EOF while reading '${element.name}'")
            if (line.isEmpty()) return@repeat
            val values = line.split(Regex("\\s+"))
            var valueIndex = 0
            for (property in element.properties) {
                if (property.isList) {
                    val count = parseAsciiScalar(values[valueIndex++], property.listCountType!!).toInt()
                    repeat(count) { valueIndex++ }
                } else {
                    val value = parseAsciiScalar(values[valueIndex++], property.type)
                    if (element.name == elementName) out.put(value)
                }
            }
        }
    }
}

private fun parseBinaryFloatTable(
    raf: RandomAccessFile,
    header: PlyTableHeader,
    elementName: String,
    out: FloatBuffer,
    byteOrder: ByteOrder
) {
    raf.seek(header.dataStart)
    val remainingBytes = raf.length() - header.dataStart
    val bytes = ByteArray(remainingBytes.toInt())
    raf.readFully(bytes)
    val data = ByteBuffer.wrap(bytes).order(byteOrder)

    for (element in header.elements) {
        repeat(element.count) {
            for (property in element.properties) {
                if (property.isList) {
                    val count = readBinaryScalar(data, property.listCountType!!).toInt()
                    repeat(count) { readBinaryScalar(data, property.listElementType!!) }
                } else {
                    val value = readBinaryScalar(data, property.type)
                    if (element.name == elementName) out.put(value)
                }
            }
        }
    }
}

private fun parseAsciiScalar(token: String, type: String): Float =
    when (type) {
        "char", "int8",
        "uchar", "uint8",
        "short", "int16",
        "ushort", "uint16",
        "int", "int32",
        "uint", "uint32" -> token.toLong().toFloat()
        "float", "float32",
        "double", "float64" -> token.toDouble().toFloat()
        else -> error("Unsupported PLY property type: $type")
    }

private fun readBinaryScalar(buffer: ByteBuffer, type: String): Float =
    when (type) {
        "char", "int8" -> buffer.get().toFloat()
        "uchar", "uint8" -> (buffer.get().toInt() and 0xff).toFloat()
        "short", "int16" -> buffer.short.toFloat()
        "ushort", "uint16" -> (buffer.short.toInt() and 0xffff).toFloat()
        "int", "int32" -> buffer.int.toFloat()
        "uint", "uint32" -> (buffer.int.toLong() and 0xffffffffL).toFloat()
        "float", "float32" -> buffer.float
        "double", "float64" -> buffer.double.toFloat()
        else -> error("Unsupported PLY property type: $type")
    }
