@file:afield.CompletionCategory("Data")

package afield.stdlib

import afield.Box
import afield.FieldJson
import afield.safeSave
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Per-box persistence for Kotlin values, on top of kotlinx.serialization. `var n by saved { 0 }`
 * declares a property that loads itself on first read and writes itself back on every assignment,
 * as JSON at `<document directory>/<box id>/<property name>.json`, encoded with Field's
 * `FieldJson` and written through the atomic `safeSave`. The no-argument [saved] overload skips
 * the default lambda but only for `List`, `Set` and `Map` (see [defaultSavedValue]); anything else
 * demands an explicit default.
 *
 * The delegate itself is [SavedJsonValue]; it takes the box from its receiver or from the ambient
 * `Box.context`, so it needs an initialised box and session and errors out otherwise. [toJSONFile]
 * and [fromJSONFile] do the same encode/decode against an explicit `File` with no delegate. Any
 * failure to read, write or serialize is rethrown as a `SerializationException` naming the file.
 */

/**
 * Writes any serializable value to a JSON file, atomically, creating parent directories as needed.
 *
 * The no-delegate half of this file: use it when you want to choose the path yourself rather than let [saved]
 * derive one. Atomic via Field's `safeSave`, so an interrupted write cannot leave a half-written file behind.
 *
 * @throws kotlinx.serialization.SerializationException wrapping **any** failure — a missing serializer, a
 * permission error, a full disk — with the absolute path in the message
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> toJSONFile(file: File, value: T) {
    try {
        file.parentFile?.mkdirs()
        @Suppress("UNCHECKED_CAST")
        val serializer = FieldJson.serializersModule.serializer(typeOf<T>()) as KSerializer<T>
        file.safeSave(FieldJson.encodeToString(serializer, value))
    } catch (t: Throwable) {
        throw SerializationException(
            "Couldn't save to ${file.absolutePath}: ${t.message}",
            t
        )
    }
}

/**
 * Reads a value back from a JSON file, or returns [defaultValue] if the file does not exist.
 *
 * A missing file is *not* an error — that is the whole point, so first-run code needs no special case. A file
 * that exists but cannot be decoded **is** an error, so a corrupted or schema-drifted file throws rather than
 * silently reverting to the default.
 *
 * @throws kotlinx.serialization.SerializationException on any decode or read failure, with the path in the
 * message (which reads "Couldn't save to …" — the message is wrong about the direction, though the path is right)
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> fromJSONFile(file: File, defaultValue: () -> T): T {
    try {
        if (file.exists()) {
            @Suppress("UNCHECKED_CAST")
            val serializer = FieldJson.serializersModule.serializer(typeOf<T>()) as KSerializer<T>
            return FieldJson.decodeFromString(serializer, file.readText())
        } else {
            return defaultValue()
        }
    } catch (t: Throwable) {
        throw SerializationException(
            "Couldn't save to ${file.absolutePath}: ${t.message}",
            t
        )
    }
}

/**
 * The delegate behind `var n by saved { 0 }`: a property that loads itself from disk on first read and writes
 * itself back on every assignment.
 *
 * The file is derived, not chosen — `<document directory>/<box id>/<property name>.json` — so renaming the
 * property or the box **orphans the old file** and the value appears to reset. The box comes from the delegate's
 * receiver if that is a `Box`, otherwise from the ambient `Box.context`.
 *
 * Every write hits the disk synchronously and atomically, so this is persistence for settings and small state,
 * not for something you assign in a per-frame loop. The value is cached in memory after the first read, which
 * also means an edit to the file underneath a running box is not noticed.
 *
 * Construct it through [saved] rather than directly; that is what supplies the serializer for `T`.
 */
class SavedJsonValue<T>(
    private val serializer: KSerializer<T>,
    private val defaultValue: () -> T
) : ReadWriteProperty<Any?, T> {

    private var loaded = false
    private var value: T? = null

    /**
     * The stored value, loading it from disk on the first read of this box's run.
     *
     * If there is no file yet, the default is computed **and immediately written**, so the file exists from then
     * on. A file that cannot be decoded throws rather than falling back to the default.
     *
     * @throws kotlinx.serialization.SerializationException if the existing file cannot be decoded
     * @throws IllegalArgumentException if there is no initialised box and session to derive a path from
     */
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (!loaded) {
            val file = fileFor(thisRef, property)
            value = if (file.exists()) {
                try {
                    FieldJson.decodeFromString(serializer, file.readText())
                } catch (t: Throwable) {
                    throw SerializationException(
                        "Couldn't decode saved value for '${property.name}' from ${file.absolutePath}: ${t.message}",
                        t
                    )
                }
            } else {
                defaultValue().also { save(file, it, property) }
            }
            loaded = true
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /**
     * Stores the value and writes it to disk immediately — there is no batching and no dirty flag, so an
     * assignment in a tight loop is a file write per iteration.
     *
     * @throws kotlinx.serialization.SerializationException if encoding or writing fails
     */
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val file = fileFor(thisRef, property)
        save(file, value, property)
        this.value = value
        loaded = true
    }

    private fun save(file: File, value: T, property: KProperty<*>) {
        try {
            file.parentFile?.mkdirs()
            file.safeSave(FieldJson.encodeToString(serializer, value))
        } catch (t: Throwable) {
            throw SerializationException(
                "Couldn't save '${property.name}' to ${file.absolutePath}: ${t.message}",
                t
            )
        }
    }

    private fun fileFor(thisRef: Any?, property: KProperty<*>): File {
        val box = resolveBox(thisRef)
        require(box.isInsideInitialized()) {
            "saved { ... } requires an initialized box/session for '${property.name}'"
        }
        val documentDir = File(box.inside.filename)
        val boxDir = File(documentDir, box.id)
        return File(boxDir, "${property.name}.json")
    }

    private fun resolveBox(thisRef: Any?): Box {
        return (thisRef as? Box)
            ?: Box.context.get()
            ?: error("saved { ... } requires an active Box context")
    }
}

/**
 * `var n by saved { 0 }` — a box property that persists across runs, restarts and reopening the document.
 *
 * The shortest way to give a sketch memory: assign to it and it is on disk; read it next session and it is still
 * there. [defaultValue] is used only when there is no file yet, and is then written out.
 *
 * `T` must be serializable by kotlinx.serialization — a `@Serializable` class, or one of the types Field's
 * `FieldJson` already knows. The failure for an unsupported type is a `SerializationException` at the first read
 * or write, not at compile time.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> saved(noinline defaultValue: () -> T): SavedJsonValue<T> {
    @Suppress("UNCHECKED_CAST")
    val serializer = FieldJson.serializersModule.serializer(typeOf<T>()) as KSerializer<T>
    return SavedJsonValue(serializer, defaultValue)
}

/**
 * `val log by saved<List<String>>()` — [saved] without a default, for the collection types that have an obvious
 * empty value.
 *
 * Supported only for `List`, `Set`, `Map` and their mutable forms; **anything else throws** on first use rather
 * than at compile time, with a message saying an explicit default is required. When in doubt use the overload
 * that takes one.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> saved(): SavedJsonValue<T> {
    val type = typeOf<T>()

    @Suppress("UNCHECKED_CAST")
    val serializer = FieldJson.serializersModule.serializer(type) as KSerializer<T>
    return SavedJsonValue(serializer) { defaultSavedValue(type) as T }
}

@PublishedApi
internal fun defaultSavedValue(type: KType): Any {
    return when (type.classifier) {
        List::class -> emptyList<Any?>()
        Set::class -> emptySet<Any?>()
        Map::class -> emptyMap<Any?, Any?>()
        MutableList::class -> mutableListOf<Any?>()
        MutableSet::class -> mutableSetOf<Any?>()
        MutableMap::class -> mutableMapOf<Any?, Any?>()
        else -> error("saved() requires an explicit default for ${type}. Only List, Set, and Map have built-in defaults.")
    }
}
