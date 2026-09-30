package afield.graphics.slug

import afield.graphics.FLine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** One filled XY path compiled into immutable Slughorn data. Placement is external. */
class SlugPath private constructor(private val packed: IntArray) {
    /** A defensive copy; mutating it cannot change a validated path. */
    val words: IntArray get() = packed.copyOf()
    val curveCount: Int get() = packed[20]
    val byteSize: Long get() = packed.size * 4L

    companion object {
        /**
         * Preparation prototype: runs the native helper once per geometry edit, not per frame.
         * Projects no coordinates: rejects paths outside z=0 to avoid flattening 3D silently.
         * Includes all subpaths, with nonzero winding and implicit fill closure.
         * tolerance is in the FLine's coordinate units.
         */
        fun compile(line: FLine, executable: File, tolerance: Float = 1e-4f): SlugPath {
            require(tolerance.isFinite() && tolerance > 0)
            require(executable.isFile && executable.canExecute()) { "Missing field-slug executable: $executable" }
            val commands = commands(line, tolerance)
            // File redirects prevent pipe deadlock for large binary output. A production JNI
            // bridge can replace this boundary without changing the GPU layout.
            val dir = java.nio.file.Files.createTempDirectory("field-slug-").toFile()
            try {
                val input = File(dir, "path.txt").apply { writeText(commands, Charsets.UTF_8) }
                val output = File(dir, "data.bin")
                val errors = File(dir, "errors.txt")
                val process = ProcessBuilder(executable.absolutePath)
                    .redirectInput(input).redirectOutput(output).redirectError(errors).start()
                try {
                    check(process.waitFor(30, TimeUnit.SECONDS)) { "Slug preparation exceeded 30 seconds" }
                    check(process.exitValue() == 0) { errors.readText() }
                } finally {
                    if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                }
                return decode(output.readBytes())
            } finally { dir.deleteRecursively() }
        }

        internal fun commands(line: FLine, tolerance: Float): String {
            require(tolerance.isFinite() && tolerance > 0) { "Tolerance must be finite and positive" }
            require(line.nodes.any { it is FLine.LineTo || it is FLine.CubicTo }) { "Empty fill geometry" }
            require(line.nodes.firstOrNull() is FLine.MoveTo) { "A filled path must begin with moveTo" }
            return buildString {
                append("T ").append(tolerance).append('\n')
                for (node in line.nodes) {
                    node.storage.forEach { p ->
                        require(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
                        require(p.z == 0f) { "SlugPath prototype requires an XY FLine; transform the rendered plane afterward" }
                    }
                    when (node) {
                        is FLine.MoveTo -> append("M ${node.to.x} ${node.to.y}\n")
                        is FLine.LineTo -> append("L ${node.to.x} ${node.to.y}\n")
                        is FLine.CubicTo -> append("C ${node.c1.x} ${node.c1.y} ${node.c2.x} ${node.c2.y} ${node.to.x} ${node.to.y}\n")
                        is FLine.Close -> append("Z\n")
                    }
                }
            }
        }

        internal fun decode(bytes: ByteArray): SlugPath {
            require(bytes.size >= 96 && bytes.size % 4 == 0) { "Truncated Slug data" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val words = IntArray(b.remaining()).also { b.get(it) }
            require(words[0] == 0x534c5547 && words[1] == 1) { "Unsupported Slug format" }
            require(words[2] == 512 && words[4] == 512) { "Prototype shaders require 512-wide atlases" }
            require(words[3] > 0 && words[5] > 0 && words[6] == 24 && words[20] > 0)
            require(words[21].toLong() == words[2].toLong() * words[3] * 16)
            require(words[22].toLong() == words[4].toLong() * words[5] * 4)
            require(words[7].toLong() == 24L + words[21] / 4)
            require(bytes.size.toLong() == 96L + words[21] + words[22])
            // Validate everything the shaders can address before publishing a GPU buffer.
            for (i in 8..15) require(Float.fromBits(words[i]).isFinite()) { "Non-finite Slug metrics" }
            require(Float.fromBits(words[8]) < Float.fromBits(words[10]) &&
                Float.fromBits(words[9]) < Float.fromBits(words[11])) { "Degenerate Slug bounds" }
            require(Float.fromBits(words[12]) > 0 && Float.fromBits(words[13]) > 0) { "Invalid band scale" }
            val curveTexels = words[21] / 16
            val bandTexels = words[22] / 4
            require(words[18] in 0..31 && words[19] in 0..31) { "Invalid band count" }
            require(words[16] in 0 until 512 && words[17] in 0 until words[5]) { "Invalid band origin" }
            val origin = words[17] * 512L + words[16]
            val headers = 64 + words[18] + words[19] + 2
            require(words[16] + headers <= 512 && origin + headers <= bandTexels) { "Band headers exceed atlas" }
            fun band(at: Long): Int {
                require(at in 0 until bandTexels.toLong()) { "Band lookup exceeds atlas" }
                return words[words[7] + at.toInt()]
            }
            for (q in 0 until 32) {
                require((band(origin + q) and 65535) <= words[19]) { "Invalid horizontal indirection" }
                require((band(origin + 32 + q) and 65535) <= words[18]) { "Invalid vertical indirection" }
            }
            for (h in 64 until headers) {
                val header = band(origin + h)
                val count = header and 65535
                val offset = header ushr 16
                require(count <= words[20] && origin + offset + count <= bandTexels) { "Invalid band curve list" }
                if (count > 0) require(offset >= headers) { "Curve list overlaps band headers" }
                for (c in 0 until count) {
                    val xy = band(origin + offset + c)
                    val x = xy and 65535
                    val y = xy ushr 16
                    val texel = y * 512L + x
                    require(x < 512 && texel + 1 < curveTexels) { "Curve lookup exceeds atlas" }
                    val at = words[6] + texel.toInt() * 4
                    for (component in 0 until 6) {
                        require(Float.fromBits(words[at + component]).isFinite()) { "Non-finite curve data" }
                    }
                }
            }
            return SlugPath(words)
        }
    }
}
