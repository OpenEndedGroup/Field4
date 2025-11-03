package afield

import java.lang.Double
import java.lang.Float
import afield.Server
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly


inline fun <reified T> option(name: String, def: () -> T): T {
    val i = Server.args.indexOfFirst { it.equals("--" + name, true) }
    if (i == -1) {
        return def()
    }
    if (i == Server.args.size - 1) throw IllegalArgumentException("expected argument after $name")
    return convertTo(T::class.java, Server.args[i + 1])
}

fun <T> convertTo(target: Class<T>, value: String): T {
    if (target == String::class.java) return value as T
    if (target == Integer::class.java) return Integer.parseInt(value) as T
    if (target == Double::class.java) return Double.parseDouble(value) as T
    if (target == Float::class.java) return Float.parseFloat(value) as T
    throw IllegalArgumentException("can't convert String to $target")
}

