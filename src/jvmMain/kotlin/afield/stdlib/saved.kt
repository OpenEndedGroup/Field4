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

class SavedJsonValue<T>(
    private val serializer: KSerializer<T>,
    private val defaultValue: () -> T
) : ReadWriteProperty<Any?, T> {

    private var loaded = false
    private var value: T? = null

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

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> saved(noinline defaultValue: () -> T): SavedJsonValue<T> {
    @Suppress("UNCHECKED_CAST")
    val serializer = FieldJson.serializersModule.serializer(typeOf<T>()) as KSerializer<T>
    return SavedJsonValue(serializer, defaultValue)
}

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
