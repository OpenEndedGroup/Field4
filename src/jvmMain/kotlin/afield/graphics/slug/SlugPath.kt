package afield.graphics.slug

import afield.graphics.FLine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** One filled XY path compiled into immutable Slughorn data. Placement is external. */
class SlugPath private constructor(val words: IntArray) {
    val curveCount: Int get() = words[20]
    val byteSize: Long get() = words.size * 4L

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
            val commands = buildString {
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
            return SlugPath(words)
        }
    }
}
