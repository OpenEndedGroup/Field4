@file:afield.CompletionCategory("Graphics")
package afield.paint

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Tools kept by name between strokes: a brush stays dirty with what it last picked up, a knife
 * keeps its bead, a rag its load, a pencil its wear, a watercolour brush its water and pigment,
 * until they are reloaded, wiped or replaced.
 *
 * ```
 * val tools = PaintToolbox()
 * tools["big filbert"] = canvas.brush(BrushKind.Filbert, 40.0)
 * tools.brush("big filbert").load(rgb(0.2, 0.3, 0.5))
 * canvas.stroke(line, tools.brush("big filbert"))   // later: the same brush, as that stroke left it
 * ```
 *
 * [save] writes every tool exactly — each bristle's paint, a knife's bead, a rag's soaked faces —
 * and [PaintToolbox.load] puts them back, so a studio can keep its tools with the canvas across a
 * saved document or a rewound conversation.
 */
class PaintToolbox : AutoCloseable {
    private val tools = LinkedHashMap<String, PaintTool>()

    /** The tools' names, in the order they were added. */
    val names: List<String> get() = tools.keys.toList()

    /** The tool called [name], or `null`. */
    operator fun get(name: String): PaintTool? = tools[name]

    /** Keep [tool] as [name], putting away (closing) whatever had that name. */
    operator fun set(name: String, tool: PaintTool) {
        require(name.isNotBlank()) { "a tool needs a name" }
        val old = tools.put(name, tool)
        if (old !== tool) (old as? AutoCloseable)?.close()
    }

    operator fun contains(name: String): Boolean = name in tools

    /** Put away the tool called [name]. */
    fun remove(name: String) {
        (tools.remove(name) as? AutoCloseable)?.close()
    }

    /** The brush called [name]. */
    fun brush(name: String): PaintBrush = typed(name)

    /** The knife called [name]. */
    fun knife(name: String): PaintKnife = typed(name)

    /** The rag called [name]. */
    fun rag(name: String): PaintRag = typed(name)

    /** The pencil (or chalk) called [name]. */
    fun pencil(name: String): Pencil = typed(name)

    /** The watercolour brush called [name]. */
    fun waterBrush(name: String): WaterBrush = typed(name)

    private inline fun <reified T : PaintTool> typed(name: String): T = when (val t = tools[name]) {
        null -> throw NoSuchElementException("no tool named \"$name\"" + if (tools.isEmpty()) "" else "; there are: ${tools.keys.joinToString()}")
        is T -> t
        else -> throw IllegalArgumentException("\"$name\" is a ${kindOf(t)}, not a ${T::class.simpleName?.removePrefix("Paint")?.lowercase()}")
    }

    /** Every tool as `name to tool`. */
    val entries: List<Pair<String, PaintTool>> get() = tools.entries.map { it.key to it.value }

    /** Write every tool, exactly, to [file]. */
    fun save(file: File) = DataOutputStream(file.outputStream().buffered()).use { write(it) }

    /** Write every tool, exactly, to [out]. */
    fun write(out: DataOutputStream) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(tools.size)
        for ((name, t) in tools) {
            out.writeUTF(name)
            when (t) {
                is PaintBrush -> { out.writeByte('B'.code); out.writeInt(t.kind.code); bytes(out, t.state()) }
                is PaintKnife -> { out.writeByte('K'.code); bytes(out, t.state()) }
                is PaintRag -> { out.writeByte('R'.code); bytes(out, t.state()) }
                is Pencil -> { out.writeByte('P'.code); out.writeInt(t.medium.code); out.writeDouble(t.softness); out.writeDouble(t.worn) }
                is WaterBrush -> { out.writeByte('W'.code); bytes(out, t.state()) }
            }
        }
    }

    /** Put every tool away. */
    override fun close() {
        tools.values.forEach { (it as? AutoCloseable)?.close() }
        tools.clear()
    }

    /** One line per tool, for an agent or a person to read: what it is, and what it carries on [canvas]. */
    fun describe(canvas: PaintCanvas): String = if (tools.isEmpty()) "no tools kept" else tools.entries.joinToString("\n") { (name, t) ->
        "$name: " + when (t) {
            is PaintBrush -> {
                val c = canvas.carried(t)
                "${t.kind} brush ${"%.0f".format(t.width)} wide, " +
                    if (c.color == null) "clean" else "${"%.0f".format(t.fullness * 100)}% loaded with ${hex(c.color)} (${"%.2f".format(c.paintMm3)} mm³)"
            }
            is PaintKnife -> {
                val c = canvas.carried(t)
                "knife, " + if (c.color == null) "clean" else "bead ${"%.0f".format(t.fullness * 100)}% of full, ${hex(c.color)}"
            }
            is PaintRag -> canvas.ragState(t).let { "rag, face ${"%.0f".format(it.load * 100)}% loaded, cloth ${"%.0f".format(it.soaked * 100)}% soaked" + if (it.damp > 0.01) ", damp" else "" }
            is Pencil -> "${t.medium.name.lowercase()} (softness ${t.softness}), ${"%.0f".format(t.worn)} mm drawn since sharpened"
            is WaterBrush -> "watercolour brush ${"%.0f".format(t.width)} wide, ${"%.0f".format(t.fill * 100)}% full of water " +
                "(${"%.1f".format(t.waterMm3)} of ${"%.1f".format(t.capacityMm3)} mm³)" + if (t.pigmentUg > 0) ", ${"%.1f".format(t.pigmentUg)} µg of pigment" else ", clean"
        }
    }

    companion object {
        private const val MAGIC = 0x46505442 // "FPTB"
        private const val VERSION = 1

        /** A toolbox written by [save]. */
        fun load(file: File): PaintToolbox = DataInputStream(file.inputStream().buffered()).use { read(it) }

        /** A toolbox written by [write]. */
        fun read(input: DataInputStream): PaintToolbox {
            require(input.readInt() == MAGIC) { "not a paint toolbox" }
            val v = input.readInt()
            require(v == VERSION) { "a paint toolbox of version $v; this Field reads version $VERSION" }
            val box = PaintToolbox()
            repeat(input.readInt()) {
                val name = input.readUTF()
                box.tools[name] = when (val type = input.readByte().toInt().toChar()) {
                    'B' -> { val kind = input.readInt(); PaintBrush.restore(bytes(input), BrushKind.entries.first { it.code == kind }) }
                    'K' -> PaintKnife.restore(bytes(input))
                    'R' -> PaintRag.restore(bytes(input))
                    'W' -> WaterBrush.restore(bytes(input))
                    'P' -> {
                        val medium = input.readInt()
                        Pencil(DrawingMedium.entries.first { it.code == medium }, input.readDouble()).also { it.worn = input.readDouble() }
                    }
                    else -> throw IllegalArgumentException("unknown tool type '$type' in a paint toolbox")
                }
            }
            return box
        }

        private fun bytes(out: DataOutputStream, b: ByteArray) { out.writeInt(b.size); out.write(b) }
        private fun bytes(input: DataInputStream): ByteArray = ByteArray(input.readInt()).also { input.readFully(it) }

        private fun kindOf(t: PaintTool) = when (t) {
            is PaintBrush -> "brush"
            is PaintKnife -> "knife"
            is PaintRag -> "rag"
            is Pencil -> "pencil"
            is WaterBrush -> "watercolour brush"
        }

        private fun hex(c: afield.stdlib.Vec4) = "#%02x%02x%02x".format(
            (c.x * 255).toInt().coerceIn(0, 255), (c.y * 255).toInt().coerceIn(0, 255), (c.z * 255).toInt().coerceIn(0, 255))
    }
}
