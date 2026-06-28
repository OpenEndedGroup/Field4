@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.ToHTML
import afield.h
import afield.four.buffers.Structure
import afield.four.modules.ComputeModulePointer
import afield.four.modules.MeshModulePointer
import afield.four.modules.ModulePointer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.joml.Quaternionf
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

@Serializable
enum class NumKind { BYTE, SHORT, INT, LONG, FLOAT, DOUBLE }

@Serializable
sealed interface KValue {
    @Serializable @SerialName("num")
    data class Num(val kind: NumKind, val value: Double) : KValue

    @Serializable @SerialName("vec2")
    data class Vec2(val x: Float, val y: Float) : KValue

    @Serializable @SerialName("vec3")
    data class Vec3(val x: Float, val y: Float, val z: Float) : KValue

    @Serializable @SerialName("vec4")
    data class Vec4(val x: Float, val y: Float, val z: Float, val w: Float) : KValue

    @Serializable @SerialName("quat")
    data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) : KValue

    @Serializable @SerialName("stacksl")
    data class StackSL(val value: String) : KValue

    @Serializable @SerialName("string")
    data class Str(val value: String) : KValue

    @Serializable @SerialName("sourceBlock")
    data class SourceBlock(val boxId: String, val source: String) : KValue

    fun toAny(): Any = when (this) {
        is Num -> when (kind) {
            NumKind.BYTE -> value.toInt().toByte()
            NumKind.SHORT -> value.toInt().toShort()
            NumKind.INT -> value.toInt()
            NumKind.LONG -> value.toLong()
            NumKind.FLOAT -> value.toFloat()
            NumKind.DOUBLE -> value
        }
        is Vec2 -> Vector2f(x, y)
        is Vec3 -> Vector3f(x, y, z)
        is Vec4 -> Vector4f(x, y, z, w)
        is Quat -> Quaternionf(x, y, z, w)
        is StackSL -> StackSLString(value)
        is Str -> value
        is SourceBlock -> SourceBackedBlock<Any?>(boxId, source)
    }

    companion object {
        fun fromAny(v: Any?): KValue? = when (v) {
            null -> null
            is Byte -> Num(NumKind.BYTE, v.toDouble())
            is Short -> Num(NumKind.SHORT, v.toDouble())
            is Int -> Num(NumKind.INT, v.toDouble())
            is Long -> Num(NumKind.LONG, v.toDouble())
            is Float -> Num(NumKind.FLOAT, v.toDouble())
            is Double -> Num(NumKind.DOUBLE, v)
            is Number -> Num(NumKind.DOUBLE, v.toDouble())

            is Vector2f -> Vec2(v.x, v.y)
            is Vector3f -> Vec3(v.x, v.y, v.z)
            is Vector4f -> Vec4(v.x, v.y, v.z, v.w)
            is Quaternionf -> Quat(v.x, v.y, v.z, v.w)
            is StackSLString -> StackSL(v.value)
            is String -> Str(v)
            is SourceBackedBlock<*> -> SourceBlock(v.boxId, v.source)

            else -> null
        }
    }
}

@Serializable
data class KeyDTO(
    val schemaVersion: Int = 0,
    val values: Map<String, KValue>
)

@Serializable
data class KeyVersionInfo(
    val index: Int,
    val timestamp: String,
    val note: String? = null,
    val file: String
)

@Serializable
private data class KeyVersionsDTO(
    val versions: List<KeyVersionInfo> = emptyList()
)

data class StackSLString(val value: String) {
    override fun toString(): String = value
}

fun stackSLString(value: String) = StackSLString(value)


class KeyframeScope(val schemaVersion: Int = 0) {
    private class Target(
        val resolve: () -> Structure?,
        val bind: ((Function0<*>) -> Unit)? = null,
        val set: ((Any?) -> Unit)? = null,
        val get: (() -> Any?)? = null
    )

    val targets = mutableMapOf<String, Structure>()
    private val dynamicTargets = mutableMapOf<String, Target>()
    private val globalMissingKeys = mutableMapOf<String, () -> Any?>()
    private val schemaMissingKeys = mutableMapOf<Int, MutableMap<String, () -> Any?>>()
    val remap = mutableMapOf<String, (Double) -> (Double)>()

    inner class Key(val values: MutableMap<String, Any?>) : ToHTML {
        operator fun invoke() {
            values.forEach { (k, v) ->
                val target = targetFor(k)
                if (target != null) {
                    val binding = bindingForValue(v)
                    target.set?.invoke(v) ?: target.bind?.invoke(binding) ?: target.resolve()?.let { it.bindings[k] = binding }
                }
            }
        }

        operator fun get(name: String): Any? = values[name]

        operator fun set(name: String, value: Any?) {
            values[name] = coerceEditedValue(values[name], value)
        }

        operator fun contains(name: String): Boolean = values.containsKey(name)

        fun put(name: String, value: Any?): Key {
            values[name] = coerceEditedValue(values[name], value)
            return this
        }

        fun putAll(vararg entries: Pair<String, Any?>): Key {
            entries.forEach { (name, value) -> values[name] = coerceEditedValue(values[name], value) }
            return this
        }

        fun remove(name: String): Any? = values.remove(name)

        fun copy(): Key = Key(values.toMutableMap())

        fun diff(other: Key, leftName: String = "left", rightName: String = "right"): KeyDiff =
            KeyDiff(
                leftName = leftName,
                rightName = rightName,
                entries = diffEntries(values, other.values)
            )

        fun diffHtml(other: Key, leftName: String = "left", rightName: String = "right"): String =
            diff(other, leftName, rightName).toHTML()

        fun diffText(other: Key, leftName: String = "left", rightName: String = "right"): String =
            diff(other, leftName, rightName).toString()

        fun update(block: Key.() -> Unit): Key {
            block()
            return this
        }

        fun applyKey(): Key {
            invoke()
            return this
        }

        fun updateAndApply(block: Key.() -> Unit): Key {
            block()
            invoke()
            return this
        }

        fun save(path: Path, note: String? = null): Key {
            saveKey(path, this, note)
            return this
        }

        fun updateCode(keyName: String = "key", apply: Boolean = true): String {
            val header = if (apply) "$keyName.updateAndApply {" else "$keyName.update {"
            return values.entries.sortedBy { it.key }.joinToString(
                separator = "\n",
                prefix = "$header\n",
                postfix = "\n}"
            ) { (name, value) ->
                "    this[${kotlinStringLiteral(name)}] = ${kotlinValueLiteral(value)}"
            }
        }

        fun copyUpdateCodeToClipboard(keyName: String = "key", apply: Boolean = true): String {
            val code = updateCode(keyName, apply)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
            return code
        }

        override fun toHTML(): String = keyframeKeyHtml(values)
    }

    private fun coerceEditedValue(previous: Any?, next: Any?): Any? =
        when {
            previous is StackSLString && next is String -> StackSLString(next)
            previous is SourceBackedBlock<*> && next is String -> SourceBackedBlock<Any?>(previous.boxId, next)
            else -> next
        }

    private fun bindingForValue(value: Any?): Function0<*> =
        if (value is SourceBackedBlock<*>) value else ({ value })

    private fun valueForBinding(binding: Function0<*>?): Any? =
        when (binding) {
            null -> null
            is SourceBackedBlock<*> -> binding
            else -> binding.invoke()
        }

    private fun targetPropertyName(keyName: String): String =
        keyName.substringAfterLast('@')

    private fun targetFor(name: String): Target? =
        dynamicTargets[name] ?: targets[name]?.let { structure ->
            val propertyName = targetPropertyName(name)
            Target(
                resolve = { structure },
                bind = { value -> structure.bind(propertyName, value) },
                get = { valueForBinding(structure.bindings[propertyName]) }
            )
        }

    fun target(name: String, structure: Structure) {
        targets[name] = structure
        dynamicTargets.remove(name)
    }

    fun target(name: String, structure: () -> Structure?) {
        val propertyName = targetPropertyName(name)
        dynamicTargets[name] = Target(
            resolve = structure,
            bind = { value -> structure()?.bind(propertyName, value) },
            get = { valueForBinding(structure()?.bindings?.get(propertyName)) }
        )
        targets.remove(name)
    }

    fun target(module: ModulePointer, name: String) =
        target(name) { module.current.liveUniformStructures[targetPropertyName(name)] }

    fun target(module: ComputeModulePointer, name: String) =
        target(name) { module.current.liveUniformStructures[targetPropertyName(name)] }

    fun target(module: MeshModulePointer, name: String) =
        target(name) { module.current.liveUniformStructures[targetPropertyName(name)] }

    fun stackAuto(module: ModulePointer, name: String, keyName: String = name) {
        val stackAutoName = targetPropertyName(name)
        dynamicTargets[keyName] = Target(
            resolve = { null },
            set = { value -> stackAutoShaderSource(module, stackAutoName, (value as? StackSLString)?.value ?: value?.toString().orEmpty()) },
            get = { StackSLString(stackAutoShaderSource(module, stackAutoName)) }
        )
        targets.remove(keyName)
    }

    fun stackAuto(module: ComputeModulePointer, name: String, keyName: String = name) {
        val stackAutoName = targetPropertyName(name)
        dynamicTargets[keyName] = Target(
            resolve = { null },
            set = { value -> stackAutoShaderSource(module, stackAutoName, (value as? StackSLString)?.value ?: value?.toString().orEmpty()) },
            get = { StackSLString(stackAutoShaderSource(module, stackAutoName)) }
        )
        targets.remove(keyName)
    }

    fun stackAuto(module: MeshModulePointer, name: String, keyName: String = name) {
        val stackAutoName = targetPropertyName(name)
        dynamicTargets[keyName] = Target(
            resolve = { null },
            set = { value -> stackAutoShaderSource(module, stackAutoName, (value as? StackSLString)?.value ?: value?.toString().orEmpty()) },
            get = { StackSLString(stackAutoShaderSource(module, stackAutoName)) }
        )
        targets.remove(keyName)
    }

    fun findByName(name : String) : ((Function0<*>) -> Unit)? {
        targetFor(name)?.bind?.let { return it }
        return null
    }

    fun missingKey(name: String, value: Any?) {
        globalMissingKeys[name] = { value }
    }

    fun missingKey(name: String, value: () -> Any?) {
        globalMissingKeys[name] = value
    }

    fun missingKey(schemaVersion: Int, name: String, value: Any?) {
        schemaMissingKeys.getOrPut(schemaVersion) { mutableMapOf() }[name] = { value }
    }

    fun missingKey(schemaVersion: Int, name: String, value: () -> Any?) {
        schemaMissingKeys.getOrPut(schemaVersion) { mutableMapOf() }[name] = value
    }

    fun capture() =
        Key((targets.keys + dynamicTargets.keys).map { k -> k to targetFor(k)?.get?.invoke() }
            .associate { it }.toMutableMap())

    fun interpolate(a: Key, b: Key, t: Double): Key {
        fun lerp(x: Double, y: Double, tt: Double) = x + (y - x) * tt

        val out = mutableMapOf<String, Any?>()
        val allKeys = a.values.keys + b.values.keys

        for (key in allKeys) {
            val va = a.values[key]
            val vb = b.values[key]
            if (va == null || vb == null) continue

            val tEffective = remap[key]?.invoke(t) ?: t
            val tf = tEffective.toFloat()

            val interpolated: Any = when {
                va is Number && vb is Number -> {
                    val v = lerp(va.toDouble(), vb.toDouble(), tEffective)
                    when {
                        va is Float && vb is Float -> v.toFloat()
                        va is Double && vb is Double -> v
                        else -> v
                    }
                }

                va is org.joml.Vector2f && vb is org.joml.Vector2f -> org.joml.Vector2f(va).lerp(vb, tf)
                va is org.joml.Vector3f && vb is org.joml.Vector3f -> org.joml.Vector3f(va).lerp(vb, tf)
                va is org.joml.Vector4f && vb is org.joml.Vector4f -> org.joml.Vector4f(va).lerp(vb, tf)
                va is org.joml.Quaternionf && vb is org.joml.Quaternionf -> org.joml.Quaternionf(va).slerp(vb, tf)
                va is StackSLString && vb is StackSLString -> if (tEffective >= 1.0) vb else va
                va is String && vb is String -> if (tEffective >= 1.0) vb else va
                va is SourceBackedBlock<*> && vb is SourceBackedBlock<*> -> if (tEffective >= 1.0) vb else va

                else -> continue
            }

            out[key] = interpolated
        }

        return Key(out)
    }

    // ---- persistence ----

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    private fun Key.toDTO(): KeyDTO =
        KeyDTO(
            schemaVersion = schemaVersion,
            values.mapNotNull { (k, v) ->
                val kv = KValue.fromAny(v) ?: return@mapNotNull null // drops null/unsupported
                k to kv
            }.toMap()
        )

    private fun keyFromDTO(dto: KeyDTO): Key {
        val values = mutableMapOf<String, Any?>()
        dto.values.forEach { (name, value) -> values[name] = value.toAny() }
        val defaults = globalMissingKeys.toMutableMap()
        schemaMissingKeys[dto.schemaVersion]?.let { defaults.putAll(it) }
        defaults.forEach { (name, defaultValue) ->
            if (!values.containsKey(name)) values[name] = defaultValue()
        }
        return Key(values)
    }

    fun saveKey(path: Path, key: Key, note: String? = null) {
        path.parent?.createDirectories()
        val text = json.encodeToString(key.toDTO())
        saveKeyVersion(path, text, note)
        Files.writeString(path, text)
    }

    fun loadKey(path: Path, version: Int? = null): Key {
        val text = Files.readString(version?.let { keyVersionFile(path, it) } ?: path)
        val dto = json.decodeFromString<KeyDTO>(text)
        return keyFromDTO(dto)
    }

    fun loadLatestKey(path: Path): Key =
        keyVersions(path).lastOrNull()?.let { loadKey(path, it.index) } ?: loadKey(path)

    fun loadKeyVersion(path: Path, version: Int): Key =
        loadKey(path, version)

    fun keyVersions(path: Path): List<KeyVersionInfo> =
        readKeyVersions(path).versions.sortedBy { it.index }

    private fun saveKeyVersion(path: Path, text: String, note: String?) {
        val dir = keyVersionsDir(path)
        dir.createDirectories()

        val manifest = readKeyVersions(path)
        val nextIndex = (manifest.versions.maxOfOrNull { it.index } ?: -1) + 1
        val fileName = keyVersionFileName(nextIndex)
        Files.writeString(dir.resolve(fileName), text)

        val updated = KeyVersionsDTO(
            manifest.versions + KeyVersionInfo(
                index = nextIndex,
                timestamp = Instant.now().toString(),
                note = note,
                file = fileName
            )
        )
        Files.writeString(keyVersionsManifest(path), json.encodeToString(updated))
    }

    private fun readKeyVersions(path: Path): KeyVersionsDTO {
        val manifest = keyVersionsManifest(path)
        if (!manifest.exists()) return KeyVersionsDTO()
        return runCatching { json.decodeFromString<KeyVersionsDTO>(Files.readString(manifest)) }
            .getOrElse { KeyVersionsDTO() }
    }

    private fun keyVersionsDir(path: Path): Path =
        (path.parent ?: Path.of(".")).resolve("${path.name}.versions")

    private fun keyVersionsManifest(path: Path): Path =
        keyVersionsDir(path).resolve("manifest.json")

    private fun keyVersionFile(path: Path, version: Int): Path {
        val info = keyVersions(path).firstOrNull { it.index == version }
            ?: throw IllegalArgumentException("No key version $version for $path")
        return keyVersionsDir(path).resolve(info.file)
    }

    private fun keyVersionFileName(version: Int): String =
        "%06d.json".format(version)
}

enum class KeyDiffKind { ADDED, REMOVED, CHANGED }

data class KeyDiffEntry(
    val name: String,
    val kind: KeyDiffKind,
    val left: Any?,
    val right: Any?
)

data class KeyDiff(
    val leftName: String,
    val rightName: String,
    val entries: List<KeyDiffEntry>
) : ToHTML {
    override fun toString(): String =
        if (entries.isEmpty()) "No key differences."
        else entries.joinToString("\n") { entry ->
            when (entry.kind) {
                KeyDiffKind.ADDED -> "+ ${entry.name}: ${keyframeValueText(entry.right)}"
                KeyDiffKind.REMOVED -> "- ${entry.name}: ${keyframeValueText(entry.left)}"
                KeyDiffKind.CHANGED -> "~ ${entry.name}: ${keyframeValueText(entry.left)} -> ${keyframeValueText(entry.right)}"
            }
        }

    override fun toHTML(): String {
        val rows = entries.joinToString("\n") { entry ->
            val mark = when (entry.kind) {
                KeyDiffKind.ADDED -> "+"
                KeyDiffKind.REMOVED -> "-"
                KeyDiffKind.CHANGED -> "~"
            }
            val color = when (entry.kind) {
                KeyDiffKind.ADDED -> "#8f8"
                KeyDiffKind.REMOVED -> "#f88"
                KeyDiffKind.CHANGED -> "#fc8"
            }
            """
            <tr>
              <td style="padding:2px 8px 2px 0;color:$color;">$mark</td>
              <td style="padding:2px 10px 2px 0;color:#9cdcfe">${keyframeEsc(entry.name)}</td>
              <td style="padding:2px 10px 2px 0;">${keyframeValueHtml(entry.left)}</td>
              <td style="padding:2px 0;">${keyframeValueHtml(entry.right)}</td>
            </tr>
            """.trimIndent()
        }
        val body = if (entries.isEmpty()) {
            "<div style='color:#888;'>No key differences.</div>"
        } else {
            """
            <table style="border-collapse:collapse;">
              <tr>
                <th></th>
                <th style="text-align:left;color:#777;padding-right:10px;">key</th>
                <th style="text-align:left;color:#777;padding-right:10px;">${keyframeEsc(leftName)}</th>
                <th style="text-align:left;color:#777;">${keyframeEsc(rightName)}</th>
              </tr>
              $rows
            </table>
            """.trimIndent()
        }
        return """
        <div style="font:12px/1.45 Iosevka, Menlo, Consolas, monospace; color:#d4d4d4;">
          <div style="margin-bottom:4px;color:#c586c0;">Key diff(${entries.size} changes)</div>
          $body
        </div>
        """.trimIndent()
    }
}

private fun diffEntries(left: Map<String, Any?>, right: Map<String, Any?>): List<KeyDiffEntry> =
    (left.keys + right.keys).sorted().mapNotNull { name ->
        val hasLeft = left.containsKey(name)
        val hasRight = right.containsKey(name)
        when {
            !hasLeft && hasRight -> KeyDiffEntry(name, KeyDiffKind.ADDED, null, right[name])
            hasLeft && !hasRight -> KeyDiffEntry(name, KeyDiffKind.REMOVED, left[name], null)
            !keyValuesEqual(left[name], right[name]) -> KeyDiffEntry(name, KeyDiffKind.CHANGED, left[name], right[name])
            else -> null
        }
    }

private fun keyValuesEqual(a: Any?, b: Any?): Boolean =
    when {
        a is SourceBackedBlock<*> && b is SourceBackedBlock<*> -> a.boxId == b.boxId && a.source == b.source
        a is Vector2f && b is Vector2f -> a.x == b.x && a.y == b.y
        a is Vector3f && b is Vector3f -> a.x == b.x && a.y == b.y && a.z == b.z
        a is Vector4f && b is Vector4f -> a.x == b.x && a.y == b.y && a.z == b.z && a.w == b.w
        a is Quaternionf && b is Quaternionf -> a.x == b.x && a.y == b.y && a.z == b.z && a.w == b.w
        else -> a == b
    }

private fun kotlinStringLiteral(text: String): String = buildString(text.length + 16) {
    append('"')
    text.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(c)
        }
    }
    append('"')
}

private fun kotlinRawStringLiteral(text: String): String {
    if (!text.contains("\"\"\"") && !text.endsWith("\"")) return "\"\"\"$text\"\"\""
    return kotlinStringLiteral(text)
}

private fun kotlinNumberLiteral(value: Number): String =
    when (value) {
        is Byte -> value.toString() + ".toByte()"
        is Short -> value.toString() + ".toShort()"
        is Int -> value.toString()
        is Long -> value.toString() + "L"
        is Float -> {
            val f = value
            when {
                f.isNaN() -> "Float.NaN"
                f == Float.POSITIVE_INFINITY -> "Float.POSITIVE_INFINITY"
                f == Float.NEGATIVE_INFINITY -> "Float.NEGATIVE_INFINITY"
                else -> value.toString()
            }
        }
        is Double -> {
            val d = value
            when {
                d.isNaN() -> "Double.NaN"
                d == Double.POSITIVE_INFINITY -> "Double.POSITIVE_INFINITY"
                d == Double.NEGATIVE_INFINITY -> "Double.NEGATIVE_INFINITY"
                else -> value.toString()
            }
        }
        else -> value.toDouble().toString()
    }

private fun kotlinValueLiteral(value: Any?): String =
    when (value) {
        null -> "null"
        is StackSLString -> "stackSLString(${kotlinStringLiteral(value.value)})"
        is SourceBackedBlock<*> -> kotlinRawStringLiteral(value.source)
        is String -> kotlinStringLiteral(value)
        is Byte, is Short, is Int, is Long, is Float, is Double -> kotlinNumberLiteral(value as Number)
        is Number -> value.toDouble().toString()
        is Vector2f -> "vec(${kotlinNumberLiteral(value.x)}, ${kotlinNumberLiteral(value.y)})"
        is Vector3f -> "vec(${kotlinNumberLiteral(value.x)}, ${kotlinNumberLiteral(value.y)}, ${kotlinNumberLiteral(value.z)})"
        is Vector4f -> "vec(${kotlinNumberLiteral(value.x)}, ${kotlinNumberLiteral(value.y)}, ${kotlinNumberLiteral(value.z)}, ${kotlinNumberLiteral(value.w)})"
        is Quaternionf -> "org.joml.Quaternionf(${kotlinNumberLiteral(value.x)}, ${kotlinNumberLiteral(value.y)}, ${kotlinNumberLiteral(value.z)}, ${kotlinNumberLiteral(value.w)})"
        else -> "/* unsupported ${value::class.java.name} */ null"
    }

val KeyframeScope.Key.h: String
    get() = toHTML()

private fun keyframeEsc(s: String): String = buildString(s.length) {
    s.forEach {
        when (it) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(it)
        }
    }
}

private fun keyframeSourceBlockHtml(v: SourceBackedBlock<*>): String {
    val boxHtml = sourceBackedBlockBox(v.boxId)?.h
        ?: "<span style='color:#f88;'>missing box ${keyframeEsc(v.boxId)}</span>"
    return """
    <div style="display:grid;gap:3px;">
      <div><span style="color:#777;">sourceBlock in </span>$boxHtml</div>
      <pre style="margin:0;padding:4px 6px;border-left:2px solid #555;background:rgba(255,255,255,0.04);white-space:pre-wrap;color:#d4d4d4;">${keyframeEsc(v.source)}</pre>
    </div>
    """.trimIndent()
}

private fun keyframeValueHtml(v: Any?): String = when (v) {
    is SourceBackedBlock<*> -> keyframeSourceBlockHtml(v)
    is StackSLString -> v.value.h
    else -> v.h
}

private fun keyframeValueText(v: Any?): String = when (v) {
    null -> "null"
    is StackSLString -> v.value
    is SourceBackedBlock<*> -> "sourceBlock@${v.boxId} { ${v.source.replace('\n', ' ')} }"
    is Vector2f -> "vec(${v.x}, ${v.y})"
    is Vector3f -> "vec(${v.x}, ${v.y}, ${v.z})"
    is Vector4f -> "vec(${v.x}, ${v.y}, ${v.z}, ${v.w})"
    is Quaternionf -> "quat(${v.x}, ${v.y}, ${v.z}, ${v.w})"
    else -> v.toString()
}

private fun keyframeKeyHtml(values: Map<String, Any?>): String {
    val rows = values.entries.sortedBy { it.key }.joinToString("\n") { (name, value) ->
        """
        <tr>
          <td style="padding:2px 10px 2px 0;color:#9cdcfe">${keyframeEsc(name)}</td>
          <td style="padding:2px 0;color:#d4d4d4">${keyframeValueHtml(value)}</td>
        </tr>
        """.trimIndent()
    }

    return """
    <div style="font:12px/1.45 Iosevka, Menlo, Consolas, monospace; color:#d4d4d4;">
      <div style="margin-bottom:4px;color:#c586c0;">Key(${values.size} values)</div>
      <table style="border-collapse:collapse;">$rows</table>
    </div>
    """.trimIndent()
}
