package afield.app.settings

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.audio.synth.*
import afield.document.graph.HasProperties
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.app.settings.PersistOnExit.needingPersistance
import afield.document.persistence.SavingContext.Companion.format
import afield.app.diagnostics.logging.lprintln
import afield.data.collections.invoke
import afield.math.linalg.invoke
import java.io.File
import java.lang.reflect.*
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Property delegates that keep a single `String`, `Int` or `Double` value across Field restarts,
 * one small file per property name under `.field/` in the working directory.
 *
 * Declare a property `by PersistentString { "default" }` (or [PersistentInt], [PersistentDouble]).
 * The first read loads `.field/<propertyName>` through [PersistOnExit.load] and falls back to the
 * default when the file is missing; a write records the value in
 * [PersistOnExit.needingPersistance]. A daemon thread writes pending values about once a second,
 * and a shutdown hook writes whatever is left on a graceful exit.
 *
 * The file is named by the Kotlin property name alone, so two persistent properties with the same
 * name share one file.
 */

class PersistentString(val defaultValue: (() -> String?) = { null }) {

    var first = true
    var value: String? = null

    operator fun getValue(f: Any?, property: KProperty<*>): String? {
        if (first) {
            first = false
            value = PersistOnExit.load<String>(property.name)
            if (value == null) {
                value = defaultValue.invoke()
            }
        }
        return value
    }

    operator fun setValue(f: Any?, property: KProperty<*>, v: String?) {
        value = v as String
        needingPersistance[property.name] = v
    }


}

class PersistentInt(val defaultValue: (() -> Int?) = { null }) {

    var first = true
    var value: Int? = null

    operator fun getValue(f: HasProperties, property: KProperty<*>): Int? {
        if (first) {
            first = false
            value = PersistOnExit.load<Int>(property.name)
            if (value == null) {
                value = defaultValue.invoke()
            }
        }
        return value!!
    }

    operator fun setValue(f: HasProperties, property: KProperty<*>, v: Int?) {
        value = v as Int
        needingPersistance[property.name] = v
    }

}

class PersistentDouble(val defaultValue: (() -> Double)? = { 0.0 }) {

    var first = true
    var value: Double? = null

    operator fun getValue(f: HasProperties, property: KProperty<*>): Double {
        if (first) {
            first = false
            value = PersistOnExit.load<Double>(property.name)
            if (value == null) {
                value = defaultValue?.invoke()
            }
        }
        return value!!
    }

    operator fun setValue(f: HasProperties, property: KProperty<*>, v: Double) {
        value = v
        needingPersistance[property.name] = v
    }

}

object PersistOnExit {

    var needingPersistance = ConcurrentHashMap<String, Any>()

    private fun drainPending() {
        needingPersistance.entries.removeIf {
            save(it.key, it.value)
            true
        }
    }

    // Daemon: this is a best-effort background flusher and must never be the reason a
    // JVM stays alive (otherwise every jvm-main smoke test that touches a persisted
    // property would hang until the launcher force-exits). A shutdown hook flushes any
    // last-second changes on graceful exit so we keep the "persist on exit" guarantee.
    var checkThread = thread(isDaemon = true) {
        while (true) {
            Thread.sleep(1000)
            if (needingPersistance.size > 0) lprintln("info,persist", "persistence number is ${needingPersistance.size}")
            drainPending()
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ drainPending() }, "afield-persist-flush"))
    }

    fun save(name: String, t: Any?) {
        if (t == null) return
        try {
            val f = System.getProperty("user.dir") + "/.field"
            File(f).mkdirs()
            val p = f + "/$name"

            var text: String? = null

            when (t::class) {
                String::class -> {
                    text = format.encodeToString<String>(t as String)
                }

                Int::class -> {
                    text = format.encodeToString<Int>(t as Int)
                }

                Double::class -> {
                    text = format.encodeToString<Double>(t as Double)
                }

                Float::class -> {
                    text = format.encodeToString<Float>(t as Float)
                }

                else -> {
                    throw IllegalArgumentException("unknown type ${t::class}")
                }
            }

            lprintln("info,persist", "saving $name to $text with path $p")
            File(p).writeText(text)

        } catch (e: Exception) {
            lprintln("error,persist", "problem saving $name — ${e.message}")
        }
    }

    inline fun <reified T> load(
        name: String,
        storageDirectory: File = File(System.getProperty("user.dir"), ".field"),
    ): T? {
        try {
            val source = File(storageDirectory, name)
            if (!source.exists()) return null
            val text = source.readText()

            when (T::class) {
                String::class -> {
                    return format.decodeFromString<String>(text) as T
                }

                Int::class -> {
                    return format.decodeFromString<Int>(text) as T
                }

                Double::class -> {
                    return format.decodeFromString<Double>(text) as T
                }

                Float::class -> {
                    return format.decodeFromString<Float>(text) as T
                }

                else -> {
                    throw IllegalArgumentException("unknown type ${T::class}")
                }
            }
        } catch (e: Exception) {
            lprintln("error,persist", "problem loading $name — ${e.message}")
            return null
        }
    }
}


