package afield

import org.joml.*
import kotlin.math.*

/**
 * Interface for objects that can provide their own HTML representation
 */
interface ToHTML {
    fun toHTML(): String
}

/**
 * Registry for custom value printers
 */
object ValuePrinterRegistry {
    private val printers = mutableMapOf<Class<*>, (Any) -> String>()
    
    /**
     * Register a custom printer for a specific type
     */
    fun <T : Any> register(clazz: Class<T>, printer: (T) -> String) {
        @Suppress("UNCHECKED_CAST")
        printers[clazz] = printer as (Any) -> String
    }
    
    /**
     * Get custom printer for a value, checking class hierarchy
     */
    internal fun getPrinter(value: Any): ((Any) -> String)? {
        // Check exact match first
        printers[value::class.java]?.let { return it }
        
        // Check superclasses and interfaces
        var clazz: Class<*>? = value::class.java
        while (clazz != null) {
            printers[clazz]?.let { return it }
            clazz = clazz.superclass
        }
        
        // Check interfaces
        value::class.java.interfaces.forEach { iface ->
            printers[iface]?.let { return it }
        }
        
        return null
    }
}

/**
 * Compact, monochrome visualizer for common Kotlin and JOML types
 */
object ValueVisualizer {
    private const val MAX_LIST_PREVIEW = 5
    private const val MAX_STRING_LENGTH = 100
    private const val MAX_MAP_ENTRIES = 5
    
    /**
     * Visualize any value and return compact HTML representation
     */
    fun visualize(value: Any?): String {
        return buildString {
            append("<div class='value-viz' style='font-family: \"Iosevka Web\", monospace; font-size: 12px; color: #ccc; line-height: 1.4;'>")
            append(visualizeValue(value))
            append("</div>")
        }
    }
    
    private fun visualizeValue(value: Any?, depth: Int = 0): String {
        if (depth > 3) return "<span style='color: #888;'>…</span>"
        
        return when (value) {
            null -> "<span style='color: #888;'>null</span>"
            
            // Check for ToHTML interface first
            is ToHTML -> value.toHTML()
            
            // Check custom printers
            else -> {
                ValuePrinterRegistry.getPrinter(value)?.invoke(value) ?: visualizeBuiltIn(value, depth)
            }
        }
    }
    
    private fun visualizeBuiltIn(value: Any, depth: Int): String = when (value) {
        // Primitives
        is Boolean -> "<span style='color: #f88;'>${value}</span>"
        is Number -> formatNumber(value)
        is Char -> "<span style='color: #8f8;'>'${escape(value.toString())}'</span>"
        is String -> formatString(value)
        
        // JOML vectors
        is Vector2fc -> formatVec2(value)
        is Vector3fc -> formatVec3(value)
        is Vector4fc -> formatVec4(value)
        is Vector2dc -> formatVec2(Vector2f(value.x().toFloat(), value.y().toFloat()))
        is Vector3dc -> formatVec3(Vector3f(value.x().toFloat(), value.y().toFloat(), value.z().toFloat()))
        is Vector4dc -> formatVec4(Vector4f(value.x().toFloat(), value.y().toFloat(), value.z().toFloat(), value.w().toFloat()))
        
        // JOML quaternions
        is Quaternionfc -> formatQuat(value)
        is Quaterniondc -> formatQuat(Quaternionf(value.x().toFloat(), value.y().toFloat(), value.z().toFloat(), value.w().toFloat()))
        
        // JOML matrices
        is Matrix2fc -> formatMatrix(value)
        is Matrix3fc -> formatMatrix(value)
        is Matrix4fc -> formatMatrix(value)
        is Matrix2dc -> formatMatrix(Matrix2f(value))
        is Matrix3dc -> formatMatrix(Matrix3f(value))
        is Matrix4dc -> formatMatrix(Matrix4f(value))
        
        // Collections
        is Map<*, *> -> formatMap(value, depth)
        is List<*> -> formatList(value, depth)
        is Set<*> -> formatSet(value, depth)
        is Array<*> -> formatArray(value, depth)
        is ByteArray -> formatByteArray(value)
        is IntArray -> formatIntArray(value)
        is FloatArray -> formatFloatArray(value)
        is DoubleArray -> formatDoubleArray(value)
        
        // Fallback
        else -> "<span style='color: #aaa;'>${escape(value.toString())}</span>"
    }
    
    private fun formatNumber(n: Number): String {
        val formatted = when (n) {
            is Float -> {
                when {
                    n.isNaN() -> "NaN"
                    n.isInfinite() -> if (n > 0) "∞" else "-∞"
                    abs(n) < 0.001f && n != 0f -> "%.3e".format(n)
                    abs(n) > 10000f -> "%.3e".format(n)
                    else -> "%.3f".format(n).trimEnd('0').trimEnd('.')
                }
            }
            is Double -> {
                when {
                    n.isNaN() -> "NaN"
                    n.isInfinite() -> if (n > 0) "∞" else "-∞"
                    abs(n) < 0.001 && n != 0.0 -> "%.3e".format(n)
                    abs(n) > 10000.0 -> "%.3e".format(n)
                    else -> "%.3f".format(n).trimEnd('0').trimEnd('.')
                }
            }
            else -> n.toString()
        }
        return "<span style='color: #8cf;'>$formatted</span>"
    }
    
    private fun formatString(s: String): String {
        val escaped = escape(s)
        return if (s.length > MAX_STRING_LENGTH) {
            val preview = escaped.substring(0, MAX_STRING_LENGTH)
            "<span style='color: #8f8;'>\"$preview…\"</span> <span style='color: #888;'>(${s.length} chars)</span>"
        } else {
            "<span style='color: #8f8;'>\"$escaped\"</span>"
        }
    }
    
    private fun formatVec2(v: Vector2fc): String {
        return "<span style='color: #fc8;'>vec2(${fmt(v.x())}, ${fmt(v.y())})</span>"
    }
    
    private fun formatVec3(v: Vector3fc): String {
        return "<span style='color: #fc8;'>vec3(${fmt(v.x())}, ${fmt(v.y())}, ${fmt(v.z())})</span>"
    }
    
    private fun formatVec4(v: Vector4fc): String {
        return "<span style='color: #fc8;'>vec4(${fmt(v.x())}, ${fmt(v.y())}, ${fmt(v.z())}, ${fmt(v.w())})</span>"
    }
    
    private fun formatQuat(q: Quaternionfc): String {
        return "<span style='color: #f8c;'>quat(${fmt(q.x())}, ${fmt(q.y())}, ${fmt(q.z())}, ${fmt(q.w())})</span>"
    }
    
    private fun formatMatrix(m: Matrix2fc): String {
        return """<span style='color: #cf8;'>mat2</span> <span style='color: #888;'>[${fmt(m.m00())}, ${fmt(m.m10())} / ${fmt(m.m01())}, ${fmt(m.m11())}]</span>"""
    }
    
    private fun formatMatrix(m: Matrix3fc): String {
        val det = m.determinant()
        return """<span style='color: #cf8;'>mat3</span> <span style='color: #888;'>[det=${fmt(det)}]</span>"""
    }
    
    private fun formatMatrix(m: Matrix4fc): String {
        val det = m.determinant()
        val translation = Vector3f()
        m.getTranslation(translation)
        return """<span style='color: #cf8;'>mat4</span> <span style='color: #888;'>[det=${fmt(det)}, t=(${fmt(translation.x)},${fmt(translation.y)},${fmt(translation.z)})]</span>"""
    }
    
    private fun formatList(list: List<*>, depth: Int): String {
        if (list.isEmpty()) return "<span style='color: #888;'>[empty list]</span>"
        
        val size = list.size
        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            
            val preview = list.take(MAX_LIST_PREVIEW)
            preview.forEachIndexed { i, item ->
                if (i > 0) append("<span style='color: #888;'>, </span>")
                append(visualizeValue(item, depth + 1))
            }
            
            if (size > MAX_LIST_PREVIEW) {
                append(" <span style='color: #888;'>… +${size - MAX_LIST_PREVIEW} more</span>")
                
                // Add summary stats if all numbers
                if (list.all { it is Number }) {
                    @Suppress("UNCHECKED_CAST")
                    val numbers = list as List<Number>
                    val stats = calculateStats(numbers)
                    append(" <span style='color: #888;'>[μ=${fmt(stats.mean)}, σ=${fmt(stats.stdDev)}, ")
                    append("range=${fmt(stats.min)}…${fmt(stats.max)}]</span>")
                }
            }
            
            append("<span style='color: #aaa;'>]</span>")
            append(" <span style='color: #666;'>(${size})</span>")
        }
    }
    
    private fun formatSet(set: Set<*>, depth: Int): String {
        if (set.isEmpty()) return "<span style='color: #888;'>{empty set}</span>"
        
        val size = set.size
        return buildString {
            append("<span style='color: #aaa;'>{</span>")
            
            val preview = set.take(MAX_LIST_PREVIEW)
            preview.forEachIndexed { i, item ->
                if (i > 0) append("<span style='color: #888;'>, </span>")
                append(visualizeValue(item, depth + 1))
            }
            
            if (size > MAX_LIST_PREVIEW) {
                append(" <span style='color: #888;'>… +${size - MAX_LIST_PREVIEW} more</span>")
            }
            
            append("<span style='color: #aaa;'>}</span>")
            append(" <span style='color: #666;'>(${size})</span>")
        }
    }
    
    private fun formatMap(map: Map<*, *>, depth: Int): String {
        if (map.isEmpty()) return "<span style='color: #888;'>{empty map}</span>"
        
        val size = map.size
        return buildString {
            append("<div style='margin-left: ${depth * 10}px;'>")
            append("<span style='color: #aaa;'>{</span>")
            
            val entries = map.entries.take(MAX_MAP_ENTRIES)
            entries.forEachIndexed { i, (key, value) ->
                if (i > 0) append("<span style='color: #888;'>,</span>")
                append("<div style='margin-left: 10px;'>")
                append(visualizeValue(key, depth + 1))
                append("<span style='color: #888;'> → </span>")
                append(visualizeValue(value, depth + 1))
                append("</div>")
            }
            
            if (size > MAX_MAP_ENTRIES) {
                append("<div style='margin-left: 10px;'><span style='color: #888;'>… +${size - MAX_MAP_ENTRIES} more entries</span></div>")
            }
            
            append("<span style='color: #aaa;'>}</span>")
            append(" <span style='color: #666;'>(${size})</span>")
            append("</div>")
        }
    }
    
    private fun formatArray(arr: Array<*>, depth: Int): String {
        return formatList(arr.toList(), depth)
    }
    
    private fun formatByteArray(arr: ByteArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty bytes]</span>"
        
        return buildString {
            append("<span style='color: #c8f;'>bytes[</span>")
            val preview = arr.take(8)
            append(preview.joinToString(" ") { "%02x".format(it) })
            if (arr.size > 8) {
                append(" <span style='color: #888;'>…</span>")
            }
            append("<span style='color: #c8f;'>]</span>")
            append(" <span style='color: #666;'>(${arr.size})</span>")
        }
    }
    
    private fun formatIntArray(arr: IntArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty ints]</span>"
        
        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            val preview = arr.take(MAX_LIST_PREVIEW)
            append(preview.joinToString(", ") { formatNumber(it) })
            
            if (arr.size > MAX_LIST_PREVIEW) {
                append(" <span style='color: #888;'>… +${arr.size - MAX_LIST_PREVIEW} more</span>")
                val stats = calculateStats(arr.map { it.toDouble() })
                append(" <span style='color: #888;'>[μ=${fmt(stats.mean)}, σ=${fmt(stats.stdDev)}, ")
                append("range=${fmt(stats.min)}…${fmt(stats.max)}]</span>")
            }
            
            append("<span style='color: #aaa;'>]</span>")
            append(" <span style='color: #666;'>(${arr.size})</span>")
        }
    }
    
    private fun formatFloatArray(arr: FloatArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty floats]</span>"
        
        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            val preview = arr.take(MAX_LIST_PREVIEW)
            append(preview.joinToString(", ") { fmt(it) })
            
            if (arr.size > MAX_LIST_PREVIEW) {
                append(" <span style='color: #888;'>… +${arr.size - MAX_LIST_PREVIEW} more</span>")
                val stats = calculateStats(arr.map { it.toDouble() })
                append(" <span style='color: #888;'>[μ=${fmt(stats.mean)}, σ=${fmt(stats.stdDev)}, ")
                append("range=${fmt(stats.min)}…${fmt(stats.max)}]</span>")
            }
            
            append("<span style='color: #aaa;'>]</span>")
            append(" <span style='color: #666;'>(${arr.size})</span>")
        }
    }
    
    private fun formatDoubleArray(arr: DoubleArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty doubles]</span>"
        
        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            val preview = arr.take(MAX_LIST_PREVIEW)
            append(preview.joinToString(", ") { fmt(it) })
            
            if (arr.size > MAX_LIST_PREVIEW) {
                append(" <span style='color: #888;'>… +${arr.size - MAX_LIST_PREVIEW} more</span>")
                val stats = calculateStats(arr.toList())
                append(" <span style='color: #888;'>[μ=${fmt(stats.mean)}, σ=${fmt(stats.stdDev)}, ")
                append("range=${fmt(stats.min)}…${fmt(stats.max)}]</span>")
            }
            
            append("<span style='color: #aaa;'>]</span>")
            append(" <span style='color: #666;'>(${arr.size})</span>")
        }
    }
    
    private fun fmt(n: Float): String {
        return when {
            n.isNaN() -> "NaN"
            n.isInfinite() -> if (n > 0) "∞" else "-∞"
            abs(n) < 0.001f && n != 0f -> "%.2e".format(n)
            abs(n) > 1000f -> "%.2e".format(n)
            else -> "%.3f".format(n).trimEnd('0').trimEnd('.')
        }
    }
    
    private fun fmt(n: Double): String {
        return when {
            n.isNaN() -> "NaN"
            n.isInfinite() -> if (n > 0) "∞" else "-∞"
            abs(n) < 0.001 && n != 0.0 -> "%.2e".format(n)
            abs(n) > 1000.0 -> "%.2e".format(n)
            else -> "%.3f".format(n).trimEnd('0').trimEnd('.')
        }
    }
    
    private fun escape(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
    
    private data class Stats(
        val mean: Double,
        val stdDev: Double,
        val min: Double,
        val max: Double
    )
    
    private fun calculateStats(numbers: List<Number>): Stats {
        if (numbers.isEmpty()) return Stats(0.0, 0.0, 0.0, 0.0)
        
        val doubles = numbers.map { it.toDouble() }
        val mean = doubles.average()
        val variance = doubles.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance)
        val min = doubles.minOrNull() ?: 0.0
        val max = doubles.maxOrNull() ?: 0.0
        
        return Stats(mean, stdDev, min, max)
    }
}

/**
 * Extension function to visualize a value in a Box's display output
 */
fun Box.viz(channel: String = "viz", value: Any?) {
    display(channel, ValueVisualizer.visualize(value))
}

/**
 * Register a custom printer for a type
 */
inline fun <reified T : Any> registerValuePrinter(noinline printer: (T) -> String) {
    ValuePrinterRegistry.register(T::class.java, printer)
}
